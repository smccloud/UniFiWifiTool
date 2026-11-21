from __future__ import annotations

import uuid
from datetime import datetime, timedelta

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import Base, Install, utcnow
from app.queries import list_installs, prune_installs, summary


def make_db():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)(), engine


def add_install(db, now: datetime, days_ago: int, version: str = "0.8.0", platform: str = "linux/amd64"):
    row = Install(
        install_id=str(uuid.uuid4()),
        first_seen=now - timedelta(days=days_ago + 1),
        last_seen=now - timedelta(days=days_ago),
        app_version=version,
        platform=platform,
        deployment="docker",
        schema_version=1,
    )
    db.add(row)
    db.commit()
    return row


def test_summary_counts_active_windows():
    db, engine = make_db()
    now = utcnow()
    try:
        add_install(db, now, 0)
        add_install(db, now, 2)
        add_install(db, now, 10)
        add_install(db, now, 40)
        result = summary(db, now=now)
        assert result["counts"] == {"24h": 1, "7d": 2, "30d": 3, "total": 4}
        assert ("0.8.0", 3) in result["versions"]
        assert ("linux/amd64", 3) in result["platforms"]
    finally:
        db.close()
        engine.dispose()


def test_admin_filters_and_pagination():
    db, engine = make_db()
    now = utcnow()
    try:
        add_install(db, now, 0, version="0.8.0", platform="linux/amd64")
        add_install(db, now, 10, version="0.7.0", platform="linux/arm64")
        add_install(db, now, 40, version="0.7.0", platform="linux/amd64")

        rows, total = list_installs(db, version="0.7.0", now=now)
        assert total == 2
        assert {row.app_version for row in rows} == {"0.7.0"}

        rows, total = list_installs(db, platform="linux/arm64", now=now)
        assert total == 1
        assert rows[0].platform == "linux/arm64"

        rows, total = list_installs(db, active="24h", limit=1, offset=0, now=now)
        assert total == 1
        assert len(rows) == 1
    finally:
        db.close()
        engine.dispose()


def test_prune_removes_only_stale_installs():
    db, engine = make_db()
    now = utcnow()
    try:
        add_install(db, now, 0)
        add_install(db, now, 40)
        assert prune_installs(db, 30, now=now) == 1
        assert db.query(Install).count() == 1
    finally:
        db.close()
        engine.dispose()
