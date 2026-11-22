from __future__ import annotations

from sqlalchemy import create_engine

from app.db import _migrate_sqlite


def test_additive_columns_are_added_to_existing_database():
    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "CREATE TABLE devices (id INTEGER PRIMARY KEY, schedule_kind VARCHAR(16))"
        )
        connection.exec_driver_sql(
            "CREATE TABLE audit_log (id INTEGER PRIMARY KEY, actor VARCHAR(64))"
        )

    _migrate_sqlite(engine)

    with engine.connect() as connection:
        columns = {
            row[1] for row in connection.exec_driver_sql("PRAGMA table_info(devices)")
        }
        audit_columns = {
            row[1] for row in connection.exec_driver_sql("PRAGMA table_info(audit_log)")
        }
    assert "schedule_default" in columns
    assert "connection_type" in columns
    assert "user_id" in audit_columns

    # Running it again is a no-op.
    _migrate_sqlite(engine)
    engine.dispose()
