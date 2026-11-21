from __future__ import annotations

import secrets
from datetime import datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app import audit
from app.models import Device, DisciplineAction
from app.timeutil import utcnow
from app.unifi.errors import UniFiError
from app.unifi.service import UniFiService

MAX_DURATION = timedelta(days=30)


def compute_expiry(
    duration_minutes: int | None,
    expires_at: datetime | None,
    now: datetime | None = None,
) -> datetime:
    now = now or utcnow()
    if expires_at is not None:
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        expiry = expires_at.astimezone(timezone.utc)
        if expiry <= now:
            raise ValueError("The end time must be in the future")
        if expiry > now + MAX_DURATION:
            raise ValueError("The end time must be within 30 days")
        return expiry

    minutes = 60 if duration_minutes is None else duration_minutes
    if minutes < 1 or minutes > 43_200:
        raise ValueError("Duration must be between 1 minute and 30 days")
    return now + timedelta(minutes=minutes)


def active_discipline(db: Session, device_id: int) -> DisciplineAction | None:
    return (
        db.query(DisciplineAction)
        .filter(
            DisciplineAction.device_id == device_id,
            DisciplineAction.status == "active",
        )
        .order_by(DisciplineAction.started_at.desc())
        .first()
    )


def start_discipline(
    db: Session,
    service: UniFiService,
    device: Device,
    *,
    download_kbps: int,
    upload_kbps: int,
    expires_at: datetime,
    actor: str,
    request_id: str | None = None,
) -> DisciplineAction:
    if download_kbps < 1 or upload_kbps < 1:
        raise ValueError("Speed limits must be positive")
    if download_kbps > 1_000_000 or upload_kbps > 1_000_000:
        raise ValueError("Speed limits must be 1,000,000 Kbps or less")
    if active_discipline(db, device.id) is not None:
        raise ValueError("This device already has an active temporary speed limit")

    client_id, previous_group = service.get_client_group(device.mac, device.unifi_client_id)
    if not client_id:
        raise UniFiError("Could not determine the controller client ID")

    restore_group = previous_group or service.default_usergroup_id()
    group_name = f"Discipline {device.mac} {secrets.token_hex(3)}"
    group = service.create_discipline_group(group_name, download_kbps, upload_kbps)
    temp_group = str(group["_id"])

    try:
        service.assign_usergroup(client_id, temp_group)
    except UniFiError:
        try:
            service.delete_usergroup(temp_group)
        except UniFiError:
            pass
        raise

    now = utcnow()
    action = DisciplineAction(
        device_id=device.id,
        mac=device.mac,
        status="active",
        download_kbps=download_kbps,
        upload_kbps=upload_kbps,
        started_at=now,
        expires_at=expires_at,
        previous_usergroup_id=restore_group,
        temp_usergroup_id=temp_group,
        unifi_client_id=client_id,
        created_by=actor,
    )
    device.unifi_client_id = client_id
    db.add(action)
    db.flush()
    audit.record(
        db,
        actor=actor,
        action="discipline_started",
        target_mac=device.mac,
        detail={
            "downloadKbps": download_kbps,
            "uploadKbps": upload_kbps,
            "expiresAt": expires_at.isoformat(),
            "groupName": group_name,
        },
        request_id=request_id,
    )
    try:
        db.commit()
    except Exception:
        db.rollback()
        # Best effort: put the client back and remove the temporary group.
        try:
            service.assign_usergroup(client_id, restore_group)
            service.delete_usergroup(temp_group)
        except UniFiError:
            pass
        raise
    db.refresh(action)
    return action


def end_discipline(
    db: Session,
    service: UniFiService,
    action: DisciplineAction,
    *,
    actor: str,
    reason: str = "manual",
    request_id: str | None = None,
) -> tuple[bool, str | None]:
    if action.status != "active":
        return True, None

    if not action.unifi_client_id:
        message = "The controller client ID is missing; restore the client's bandwidth group manually"
        action.last_error = message
        audit.record(
            db,
            actor=actor,
            action="discipline_ended",
            target_mac=action.mac,
            result="failure",
            detail={"reason": reason, "error": message},
            request_id=request_id,
        )
        db.commit()
        return False, message

    try:
        restore_group = action.previous_usergroup_id or service.default_usergroup_id()
    except UniFiError as exc:
        action.last_error = str(exc)
        audit.record(
            db,
            actor=actor,
            action="discipline_ended",
            target_mac=action.mac,
            result="failure",
            detail={"reason": reason, "error": str(exc)},
            request_id=request_id,
        )
        db.commit()
        return False, str(exc)

    try:
        service.assign_usergroup(action.unifi_client_id, restore_group)
    except UniFiError as exc:
        action.last_error = str(exc)
        audit.record(
            db,
            actor=actor,
            action="discipline_ended",
            target_mac=action.mac,
            result="failure",
            detail={"reason": reason, "error": str(exc)},
            request_id=request_id,
        )
        db.commit()
        return False, str(exc)

    warning: str | None = None
    if action.temp_usergroup_id:
        try:
            service.delete_usergroup(action.temp_usergroup_id)
        except UniFiError as exc:
            warning = f"Speed limit ended but temporary group cleanup failed: {exc}"

    action.status = "ended"
    action.ended_at = utcnow()
    action.end_reason = reason
    action.last_error = warning
    audit.record(
        db,
        actor=actor,
        action="discipline_ended",
        target_mac=action.mac,
        detail={
            "reason": reason,
            "restoredUsergroupId": restore_group,
            **({"warning": warning} if warning else {}),
        },
        request_id=request_id,
    )
    db.commit()
    return True, warning


def expire_due_actions(
    db: Session,
    service: UniFiService,
    *,
    now: datetime | None = None,
) -> int:
    now = now or utcnow()
    actions = (
        db.query(DisciplineAction)
        .filter(
            DisciplineAction.status == "active",
            DisciplineAction.expires_at <= now,
        )
        .all()
    )
    ended = 0
    for action in actions:
        try:
            ok, _warning = end_discipline(
                db, service, action, actor="scheduler", reason="expired"
            )
            if ok:
                ended += 1
        except Exception as exc:  # noqa: BLE001 - keep processing other actions
            action.last_error = str(exc)
            db.commit()
    return ended
