from __future__ import annotations

import asyncio
import logging
import platform
import uuid
from datetime import timedelta

import httpx
from sqlalchemy.orm import Session

from app import __version__
from app.config import Settings
from app.db import get_session_factory
from app.runtime import get_setting, set_setting
from app.timeutil import parse_iso_datetime, utcnow

logger = logging.getLogger("unifi_wifi_tool.telemetry")

INSTALL_ID_SETTING = "telemetry_install_id"
LAST_SENT_SETTING = "telemetry_last_sent"
PAYLOAD_SCHEMA_VERSION = 1


def get_or_create_install_id(db: Session) -> str:
    existing = get_setting(db, INSTALL_ID_SETTING)
    if isinstance(existing, str):
        try:
            return str(uuid.UUID(existing))
        except ValueError:
            pass

    install_id = str(uuid.uuid4())
    set_setting(db, INSTALL_ID_SETTING, install_id)
    db.commit()
    return install_id


def platform_label() -> str:
    system = (platform.system() or "unknown").lower()[:16]
    machine = (platform.machine() or "unknown").lower()
    arch = {
        "x86_64": "amd64",
        "amd64": "amd64",
        "aarch64": "arm64",
        "arm64": "arm64",
    }.get(machine, machine[:32] or "unknown")
    return f"{system}/{arch}"


def build_payload(db: Session, settings: Settings) -> dict:
    return {
        "install_id": get_or_create_install_id(db),
        "app_version": __version__,
        "platform": platform_label(),
        "deployment": settings.deployment_type,
        "schema_version": PAYLOAD_SCHEMA_VERSION,
    }


def heartbeat_is_due(db: Session, settings: Settings) -> bool:
    last = parse_iso_datetime(get_setting(db, LAST_SENT_SETTING))
    if last is None:
        return True
    return utcnow() - last >= timedelta(seconds=settings.telemetry_interval_seconds)


def send_heartbeat(db: Session, settings: Settings, *, force: bool = False) -> bool:
    if not settings.telemetry_enabled:
        return False
    if not force and not heartbeat_is_due(db, settings):
        return False

    payload = build_payload(db, settings)
    try:
        response = httpx.post(
            settings.telemetry_url,
            json=payload,
            timeout=10.0,
            headers={"User-Agent": f"UniFiWifiTool/{__version__}"},
        )
        response.raise_for_status()
    except httpx.HTTPError as exc:
        # Do not include the install ID or any request metadata in this log.
        logger.warning("anonymous install heartbeat failed: %s", exc)
        return False

    set_setting(db, LAST_SENT_SETTING, utcnow().isoformat())
    db.commit()
    logger.info("anonymous install heartbeat sent")
    return True


def send_heartbeat_once(settings: Settings) -> bool:
    session = get_session_factory()()
    try:
        return send_heartbeat(session, settings)
    finally:
        session.close()


async def telemetry_loop(settings: Settings) -> None:
    # Let startup finish before making the first outbound request.
    await asyncio.sleep(10)
    while True:
        try:
            await asyncio.to_thread(send_heartbeat_once, settings)
        except Exception:  # noqa: BLE001 - telemetry must never stop the app
            logger.exception("anonymous install heartbeat failed")
        await asyncio.sleep(settings.telemetry_interval_seconds)
