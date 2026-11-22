from __future__ import annotations

import uuid
from datetime import timedelta
from types import SimpleNamespace

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import models  # noqa: F401  (register tables)
from app.config import Settings
from app.db import Base
from app.heartbeat import (
    LAST_SENT_SETTING,
    build_payload,
    get_or_create_install_id,
    heartbeat_is_due,
    platform_label,
    send_heartbeat,
)
from app.runtime import get_setting, set_setting
from app.timeutil import utcnow


def make_db():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)(), engine


def test_telemetry_is_opt_in_by_default():
    assert Settings().telemetry_enabled is False


def test_install_id_is_random_and_stable():
    db, engine = make_db()
    try:
        first = get_or_create_install_id(db)
        second = get_or_create_install_id(db)
        assert first == second
        assert uuid.UUID(first)
        assert get_setting(db, "telemetry_install_id") == first
    finally:
        db.close()
        engine.dispose()


def test_payload_contains_no_network_or_device_identifiers():
    db, engine = make_db()
    settings = SimpleNamespace(deployment_type="docker")
    try:
        payload = build_payload(db, settings)
    finally:
        db.close()
        engine.dispose()

    assert set(payload) == {
        "install_id",
        "app_version",
        "platform",
        "deployment",
        "schema_version",
    }
    assert "/" in payload["platform"]
    serialized = str(payload).lower()
    for forbidden in ("ip", "mac", "ssid", "hostname", "password", "controller"):
        assert forbidden not in serialized


def test_due_logic_respects_interval():
    db, engine = make_db()
    settings = SimpleNamespace(telemetry_interval_seconds=86400)
    try:
        assert heartbeat_is_due(db, settings) is True
        set_setting(db, LAST_SENT_SETTING, utcnow().isoformat())
        db.commit()
        assert heartbeat_is_due(db, settings) is False
        set_setting(db, LAST_SENT_SETTING, (utcnow() - timedelta(hours=25)).isoformat())
        db.commit()
        assert heartbeat_is_due(db, settings) is True
    finally:
        db.close()
        engine.dispose()


def test_disabled_telemetry_does_not_call_network(monkeypatch):
    db, engine = make_db()
    settings = SimpleNamespace(
        telemetry_enabled=False,
        telemetry_url="https://example.invalid/heartbeat",
        telemetry_interval_seconds=86400,
        deployment_type="docker",
    )
    called = {"value": False}

    def fake_post(*args, **kwargs):
        called["value"] = True

    monkeypatch.setattr("app.heartbeat.httpx.post", fake_post)
    try:
        assert send_heartbeat(db, settings, force=True) is False
        assert called["value"] is False
    finally:
        db.close()
        engine.dispose()


def test_send_heartbeat_stores_last_sent(monkeypatch):
    db, engine = make_db()
    settings = SimpleNamespace(
        telemetry_enabled=True,
        telemetry_url="https://example.invalid/heartbeat",
        telemetry_interval_seconds=86400,
        deployment_type="unraid",
    )
    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

    def fake_post(url, json, timeout, headers):
        captured["url"] = url
        captured["json"] = json
        captured["headers"] = headers
        return FakeResponse()

    monkeypatch.setattr("app.heartbeat.httpx.post", fake_post)
    try:
        assert send_heartbeat(db, settings, force=True) is True
        assert captured["json"]["deployment"] == "unraid"
        assert "install_id" in captured["json"]
        assert get_setting(db, LAST_SENT_SETTING)
    finally:
        db.close()
        engine.dispose()


def test_platform_label_is_coarse():
    label = platform_label()
    assert "/" in label
    assert len(label) <= 64
