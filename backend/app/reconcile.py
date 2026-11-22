from __future__ import annotations

import threading
from dataclasses import asdict, dataclass, field
from datetime import datetime

from sqlalchemy.orm import Session

from app import audit
from app.config import Settings
from app.models import Device
from app.runtime import set_setting
from app.schedule import (
    effective_access,
    load_schedule,
    next_transition_for_mode,
)
from app.timeutil import utcnow
from app.unifi.errors import UniFiError
from app.unifi.service import UniFiService
from app.unifi.types import LiveClient


class SyncBusy(Exception):
    pass


@dataclass
class SyncSummary:
    started_at: datetime
    finished_at: datetime
    discovered: int = 0
    updated: int = 0
    applied: int = 0
    skipped: int = 0
    errors: list[str] = field(default_factory=list)
    transitions: list[dict] = field(default_factory=list)
    dry_run: bool = False

    def to_dict(self) -> dict:
        data = asdict(self)
        data["started_at"] = self.started_at.isoformat()
        data["finished_at"] = self.finished_at.isoformat()
        return data


_SYNC_LOCK = threading.Lock()


def run_sync(
    db: Session,
    service: UniFiService,
    settings: Settings,
    *,
    actor: str = "scheduler",
    user_id: int | None = None,
    dry_run: bool | None = None,
) -> SyncSummary:
    """Run a reconciliation, refusing concurrent runs."""
    if not _SYNC_LOCK.acquire(blocking=False):
        raise SyncBusy("A sync is already running")
    try:
        return sync(db, service, settings, actor=actor, user_id=user_id, dry_run=dry_run)
    finally:
        _SYNC_LOCK.release()


def sync(
    db: Session,
    service: UniFiService,
    settings: Settings,
    *,
    actor: str = "scheduler",
    user_id: int | None = None,
    dry_run: bool | None = None,
) -> SyncSummary:
    started = utcnow()
    dry_run = settings.dry_run if dry_run is None else dry_run
    summary = SyncSummary(started_at=started, finished_at=started, dry_run=dry_run)

    clients = service.list_clients()
    summary.discovered, summary.updated = _discover(db, clients, settings)
    db.commit()

    now = utcnow()
    live_by_mac = {client.mac: client for client in clients}
    devices = db.query(Device).filter(Device.access_mode != "ignored").all()

    for device in devices:
        if device.connection_type == "wired":
            # This tool only manages WiFi clients; leave wired devices alone.
            summary.skipped += 1
            continue

        schedule = load_schedule(device)
        effective = effective_access(device.access_mode, schedule, now)
        next_change = next_transition_for_mode(device.access_mode, schedule, now)
        device.effective_access = effective
        device.next_change_at = next_change
        device.last_synced_at = now

        if effective == "ignored":
            summary.skipped += 1
            continue

        desired_blocked = effective == "blocked"
        live = live_by_mac.get(device.mac)
        observed = live.blocked if live and live.blocked is not None else None
        if observed is not None:
            device.observed_blocked = observed

        if not _needs_apply(effective, device.last_applied_access, observed):
            summary.skipped += 1
            continue

        if not settings.auto_apply:
            summary.skipped += 1
            continue

        transition = {
            "mac": device.mac,
            "action": "block" if desired_blocked else "unblock",
            "effective": effective,
            "observedBlocked": observed,
            "at": now.isoformat(),
        }

        if dry_run:
            summary.transitions.append({**transition, "dryRun": True})
            audit.record(
                db,
                actor=actor,
                user_id=user_id,
                action="dry_run",
                target_mac=device.mac,
                result="skipped",
                detail={**transition, "reason": "sync"},
            )
            continue

        try:
            service.set_blocked(device.mac, desired_blocked)
            device.last_applied_access = effective
            device.last_applied_at = now
            device.last_error = None
            summary.applied += 1
            summary.transitions.append(transition)
            audit.record(
                db,
                actor=actor,
                user_id=user_id,
                action="block" if desired_blocked else "unblock",
                target_mac=device.mac,
                result="success",
                detail={**transition, "reason": "sync"},
            )
        except UniFiError as exc:
            device.last_error = str(exc)
            summary.errors.append(f"{device.mac}: {exc}")
            audit.record(
                db,
                actor=actor,
                user_id=user_id,
                action="block" if desired_blocked else "unblock",
                target_mac=device.mac,
                result="failure",
                detail={**transition, "reason": "sync", "error": str(exc)},
            )

    summary.finished_at = utcnow()
    set_setting(db, "last_sync", summary.to_dict())
    db.commit()
    return summary


def _discover(db: Session, clients: list[LiveClient], settings: Settings) -> tuple[int, int]:
    existing = {device.mac: device for device in db.query(Device).all()}
    discovered = 0
    updated = 0
    now = utcnow()

    for client in clients:
        device = existing.get(client.mac)
        if device is None:
            if not settings.discovery_enabled or client.connection_type == "wired":
                continue
            device = Device(
                mac=client.mac,
                label="",
                notes="",
                access_mode="ignored",
                source="discovered",
                created_by="discovery",
                created_at=now,
                updated_at=now,
            )
            db.add(device)
            existing[client.mac] = device
            discovered += 1

        changed = False
        for field_name, value in (
            ("hostname", client.hostname or client.name),
            ("ip_address", client.ip_address),
            ("last_ssid", client.ssid),
            ("last_signal", client.signal),
            ("last_seen_at", client.last_seen_at or now),
            ("unifi_client_id", client.unifi_client_id),
            ("connection_type", client.connection_type),
            ("observed_blocked", client.blocked),
        ):
            if value is not None and getattr(device, field_name) != value:
                setattr(device, field_name, value)
                changed = True
        if device.last_synced_at != now:
            device.last_synced_at = now
        if changed:
            updated += 1

    return discovered, updated


def refresh_device_state(device: Device, now=None) -> None:  # noqa: ANN001
    """Recompute effective access and the next transition without calling UniFi."""
    now = now or utcnow()
    schedule = load_schedule(device)
    device.effective_access = effective_access(device.access_mode, schedule, now)
    device.next_change_at = next_transition_for_mode(device.access_mode, schedule, now)
    device.last_synced_at = now


def _needs_apply(effective: str, last_applied: str | None, observed: bool | None) -> bool:
    if effective not in {"allowed", "blocked"}:
        return False
    desired_blocked = effective == "blocked"
    if observed is not None and observed != desired_blocked:
        return True
    if last_applied == effective:
        return False
    if last_applied is None and not desired_blocked:
        # Never seen a block and nothing to unblock.
        return False
    return True


def apply_device_now(
    db: Session,
    service: UniFiService,
    settings: Settings,
    device: Device,
    *,
    actor: str,
    user_id: int | None = None,
    reason: str,
    dry_run: bool | None = None,
) -> tuple[bool, str | None]:
    """Compute and apply a device's current effective state immediately."""
    dry_run = settings.dry_run if dry_run is None else dry_run
    now = utcnow()
    refresh_device_state(device, now)
    effective = device.effective_access or "ignored"

    if not _needs_apply(effective, device.last_applied_access, device.observed_blocked):
        return True, None

    desired_blocked = effective == "blocked"
    detail = {
        "reason": reason,
        "effective": effective,
        "previous": device.last_applied_access,
        "at": now.isoformat(),
    }
    if dry_run:
        audit.record(
            db,
            actor=actor,
            user_id=user_id,
            action="dry_run",
            target_mac=device.mac,
            result="skipped",
            detail={**detail, "wouldBlock": desired_blocked},
        )
        return True, None

    try:
        service.set_blocked(device.mac, desired_blocked)
    except UniFiError as exc:
        device.last_error = str(exc)
        audit.record(
            db,
            actor=actor,
            user_id=user_id,
            action="block" if desired_blocked else "unblock",
            target_mac=device.mac,
            result="failure",
            detail={**detail, "error": str(exc)},
        )
        return False, str(exc)

    device.last_applied_access = effective
    device.last_applied_at = now
    device.last_error = None
    audit.record(
        db,
        actor=actor,
        user_id=user_id,
        action="block" if desired_blocked else "unblock",
        target_mac=device.mac,
        result="success",
        detail=detail,
    )
    return True, None
