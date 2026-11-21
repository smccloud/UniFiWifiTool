from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models import Install, utcnow


def _since(days: int | None = None, hours: int | None = None, now: datetime | None = None) -> datetime:
    now = now or utcnow()
    if hours is not None:
        return now - timedelta(hours=hours)
    return now - timedelta(days=days or 0)


def summary(db: Session, now: datetime | None = None) -> dict:
    now = now or utcnow()
    counts = {
        "24h": db.query(Install).filter(Install.last_seen >= _since(hours=24, now=now)).count(),
        "7d": db.query(Install).filter(Install.last_seen >= _since(days=7, now=now)).count(),
        "30d": db.query(Install).filter(Install.last_seen >= _since(days=30, now=now)).count(),
        "total": db.query(Install).count(),
    }
    active_since = _since(days=30, now=now)
    versions = _distribution(db, Install.app_version, active_since)
    platforms = _distribution(db, Install.platform, active_since)
    deployments = _distribution(db, Install.deployment, active_since)
    return {
        "counts": counts,
        "versions": versions,
        "platforms": platforms,
        "deployments": deployments,
        "updated_at": now,
    }


def _distribution(db: Session, column, since: datetime, limit: int = 20) -> list[tuple[str, int]]:
    rows = (
        db.query(column, func.count(Install.install_id))
        .filter(Install.last_seen >= since)
        .group_by(column)
        .order_by(func.count(Install.install_id).desc(), column.asc())
        .limit(limit)
        .all()
    )
    return [(str(value), int(count)) for value, count in rows]


def list_installs(
    db: Session,
    *,
    version: str = "",
    platform: str = "",
    active: str = "",
    limit: int = 50,
    offset: int = 0,
    now: datetime | None = None,
) -> tuple[list[Install], int]:
    now = now or utcnow()
    query = db.query(Install)
    if version:
        query = query.filter(Install.app_version == version)
    if platform:
        query = query.filter(Install.platform == platform)
    if active == "24h":
        query = query.filter(Install.last_seen >= _since(hours=24, now=now))
    elif active == "7d":
        query = query.filter(Install.last_seen >= _since(days=7, now=now))
    elif active == "30d":
        query = query.filter(Install.last_seen >= _since(days=30, now=now))
    total = query.count()
    rows = (
        query.order_by(Install.last_seen.desc(), Install.install_id.asc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return rows, total


def prune_installs(db: Session, retention_days: int, now: datetime | None = None) -> int:
    now = now or utcnow()
    cutoff = now - timedelta(days=retention_days)
    deleted = db.query(Install).filter(Install.last_seen < cutoff).delete(
        synchronize_session=False
    )
    db.commit()
    return int(deleted)
