from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from sqlalchemy import DateTime, Engine, create_engine, event
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.types import TypeDecorator

from app.config import get_settings


class UTCDateTime(TypeDecorator):
    """Store naive UTC in SQLite and always return timezone-aware UTC values."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):  # noqa: ANN001
        if value is None:
            return None
        if value.tzinfo is None:
            return value
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect):  # noqa: ANN001
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


class Base(DeclarativeBase):
    pass


def _ensure_sqlite_dir(url: str) -> None:
    parsed = make_url(url)
    if not parsed.drivername.startswith("sqlite"):
        return
    database = parsed.database
    if not database or database == ":memory:" or database.startswith("file:"):
        return
    Path(database).expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)


def create_db_engine(url: str) -> Engine:
    _ensure_sqlite_dir(url)
    connect_args: dict = {}
    if make_url(url).drivername.startswith("sqlite"):
        connect_args["check_same_thread"] = False
    engine = create_engine(url, connect_args=connect_args, future=True)

    if make_url(url).drivername.startswith("sqlite"):

        @event.listens_for(engine, "connect")
        def _sqlite_pragmas(dbapi_connection, connection_record):  # noqa: ANN001
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA busy_timeout=5000")
            cursor.close()

    return engine


_engine: Engine | None = None
_session_factory: sessionmaker[Session] | None = None


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        _engine = create_db_engine(get_settings().database_url)
    return _engine


def get_session_factory() -> sessionmaker[Session]:
    global _session_factory
    if _session_factory is None:
        _session_factory = sessionmaker(
            bind=get_engine(), expire_on_commit=False, autoflush=False, future=True
        )
    return _session_factory


def init_db() -> None:
    # Imported for side effects so all tables are registered on Base.metadata.
    from app import models  # noqa: F401

    engine = get_engine()
    Base.metadata.create_all(bind=engine)
    _migrate_sqlite(engine)


def _migrate_sqlite(engine: Engine) -> None:
    """Apply tiny additive migrations for existing SQLite deployments.

    ``create_all`` does not alter existing tables. Keep these migrations
    idempotent and additive so container upgrades preserve data.
    """
    if not engine.url.drivername.startswith("sqlite"):
        return
    with engine.begin() as connection:
        columns = {
            row[1]
            for row in connection.exec_driver_sql("PRAGMA table_info(devices)")
        }
        if columns and "schedule_default" not in columns:
            connection.exec_driver_sql(
                "ALTER TABLE devices ADD COLUMN schedule_default VARCHAR(16)"
            )
        if columns and "connection_type" not in columns:
            connection.exec_driver_sql(
                "ALTER TABLE devices ADD COLUMN connection_type VARCHAR(16)"
            )
        audit_columns = {
            row[1]
            for row in connection.exec_driver_sql("PRAGMA table_info(audit_log)")
        }
        if audit_columns and "user_id" not in audit_columns:
            connection.exec_driver_sql(
                "ALTER TABLE audit_log ADD COLUMN user_id INTEGER REFERENCES users(id) ON DELETE SET NULL"
            )


def dispose_engine() -> None:
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _session_factory = None


def get_db() -> Iterable[Session]:
    session = get_session_factory()()
    try:
        yield session
    finally:
        session.close()
