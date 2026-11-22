from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from app import audit
from app.config import get_settings
from app.models import AuditLog
from app.runtime import get_setting, set_setting
from app.timeutil import parse_iso_datetime, utcnow

RETENTION_SETTING = "audit_retention_days"
LAST_PURGE_SETTING = "last_audit_purge"
MAX_RETENTION_DAYS = 36_500
AUTO_PURGE_INTERVAL = timedelta(hours=24)


@dataclass
class PurgeResult:
    retention_days: int
    deleted: int = 0
    cutoff: datetime | None = None
    ran: bool = False

    def to_dict(self) -> dict:
        return {
            "retention_days": self.retention_days,
            "deleted": self.deleted,
            "cutoff": self.cutoff.isoformat() if self.cutoff else None,
            "ran": self.ran,
        }


def get_retention_days(db: Session) -> int:
    """Return the runtime override, falling back to AUDIT_RETENTION_DAYS."""
    value = get_setting(db, RETENTION_SETTING)
    if (
        isinstance(value, int)
        and not isinstance(value, bool)
        and 0 <= value <= MAX_RETENTION_DAYS
    ):
        return value
    return get_settings().audit_retention_days


def set_retention_days(db: Session, days: int) -> int:
    if not isinstance(days, int) or isinstance(days, bool):
        raise ValueError("retention days must be an integer")
    if days < 0 or days > MAX_RETENTION_DAYS:
        raise ValueError(f"retention days must be between 0 and {MAX_RETENTION_DAYS}")
    set_setting(db, RETENTION_SETTING, days)
    return days


def purge_old_audit(
    db: Session,
    retention_days: int | None = None,
    *,
    actor: str = "scheduler",
    user_id: int | None = None,
) -> PurgeResult:
    """Delete audit rows older than the retention period.

    A retention of 0 (or less) disables deletion. The purge itself is recorded
    in the audit log when rows are removed.
    """
    days = get_retention_days(db) if retention_days is None else retention_days
    if days <= 0:
        return PurgeResult(retention_days=days, ran=False)

    now = utcnow()
    cutoff = now - timedelta(days=days)
    deleted = (
        db.query(AuditLog)
        .filter(AuditLog.created_at < cutoff)
        .delete(synchronize_session=False)
    )

    detail = {
        "retentionDays": days,
        "deleted": deleted,
        "cutoff": cutoff.isoformat(),
    }
    set_setting(
        db,
        LAST_PURGE_SETTING,
        {
            "at": now.isoformat(),
            "deleted": deleted,
            "retentionDays": days,
            "cutoff": cutoff.isoformat(),
        },
    )
    if deleted:
        audit.record(
            db,
            actor=actor,
            user_id=user_id,
            action="audit_purged",
            result="success",
            detail=detail,
        )
    db.commit()
    return PurgeResult(retention_days=days, deleted=deleted, cutoff=cutoff, ran=True)


def purge_if_due(db: Session) -> PurgeResult | None:
    """Run automatic retention at most once per day."""
    days = get_retention_days(db)
    if days <= 0:
        return None

    last = get_setting(db, LAST_PURGE_SETTING)
    if isinstance(last, dict):
        last_at = parse_iso_datetime(last.get("at"))
        if last_at is not None and utcnow() - last_at < AUTO_PURGE_INTERVAL:
            return None
    return purge_old_audit(db, days, actor="scheduler")
