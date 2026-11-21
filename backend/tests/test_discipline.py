from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import models  # noqa: F401  (register tables)
from app.db import Base
from app.discipline import (
    active_discipline,
    compute_expiry,
    end_discipline,
    expire_due_actions,
    start_discipline,
)
from app.models import AuditLog, Device, DisciplineAction
from app.unifi.errors import UniFiCapabilityError

MAC = "aa:bb:cc:dd:ee:01"
NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)
CLIENT_ID = "client-123"


class FakeService:
    def __init__(
        self,
        previous_group: str | None = "group-old",
        fail_restore: bool = False,
    ):
        self.previous_group = previous_group
        self.fail_restore = fail_restore
        self.assignments: list[tuple[str, str]] = []
        self.created: list[tuple[str, int, int]] = []
        self.deleted: list[str] = []
        self.default_calls = 0

    def get_client_group(self, mac: str, client_id: str | None = None):
        return CLIENT_ID, self.previous_group

    def default_usergroup_id(self) -> str:
        self.default_calls += 1
        return "group-default"

    def create_discipline_group(self, name: str, download_kbps: int, upload_kbps: int):
        self.created.append((name, download_kbps, upload_kbps))
        return {"_id": "group-temp", "name": name}

    def assign_usergroup(self, client_id: str, group_id: str) -> None:
        if self.fail_restore and group_id != "group-temp":
            raise UniFiCapabilityError("controller rejected restore")
        self.assignments.append((client_id, group_id))

    def delete_usergroup(self, group_id: str) -> None:
        self.deleted.append(group_id)


@pytest.fixture
def db():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


def add_device(db) -> Device:
    device = Device(mac=MAC, label="Child tablet", access_mode="allowed", source="manual")
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


def start(db, device, service, **overrides) -> DisciplineAction:
    values = {
        "download_kbps": 1000,
        "upload_kbps": 500,
        "expires_at": NOW + timedelta(hours=1),
        "actor": "parent",
    }
    values.update(overrides)
    return start_discipline(db, service, device, **values)


def test_compute_expiry_duration():
    assert compute_expiry(60, None, NOW) == NOW + timedelta(hours=1)
    assert compute_expiry(None, None, NOW) == NOW + timedelta(hours=1)
    assert compute_expiry(None, NOW + timedelta(minutes=10), NOW) == NOW + timedelta(minutes=10)


@pytest.mark.parametrize(
    "expires_at",
    [NOW, NOW - timedelta(minutes=1), NOW + timedelta(days=31)],
)
def test_compute_expiry_rejects_invalid_end_times(expires_at):
    with pytest.raises(ValueError):
        compute_expiry(None, expires_at, NOW)


def test_start_discipline_creates_group_and_assigns(db):
    device = add_device(db)
    service = FakeService()

    action = start(db, device, service)

    assert action.status == "active"
    assert action.previous_usergroup_id == "group-old"
    assert action.temp_usergroup_id == "group-temp"
    assert action.unifi_client_id == CLIENT_ID
    assert service.created[0][1:] == (1000, 500)
    assert service.assignments == [(CLIENT_ID, "group-temp")]
    assert device.unifi_client_id == CLIENT_ID
    assert any(entry.action == "discipline_started" for entry in db.query(AuditLog).all())


def test_start_discipline_uses_default_group_when_unassigned(db):
    device = add_device(db)
    service = FakeService(previous_group=None)

    action = start(db, device, service)

    assert service.default_calls == 1
    assert action.previous_usergroup_id == "group-default"


def test_start_discipline_rejects_second_active_limit(db):
    device = add_device(db)
    service = FakeService()
    start(db, device, service)

    with pytest.raises(ValueError):
        start(db, device, service)


def test_end_discipline_restores_group_and_cleans_up(db):
    device = add_device(db)
    service = FakeService()
    action = start(db, device, service)

    ok, warning = end_discipline(db, service, action, actor="parent", reason="manual")

    assert ok is True
    assert warning is None
    assert action.status == "ended"
    assert action.end_reason == "manual"
    assert service.assignments[-1] == (CLIENT_ID, "group-old")
    assert service.deleted == ["group-temp"]
    assert active_discipline(db, device.id) is None


def test_end_discipline_failure_keeps_limit_active(db):
    device = add_device(db)
    service = FakeService(fail_restore=True)
    action = start(db, device, service)

    ok, error = end_discipline(db, service, action, actor="parent")

    assert ok is False
    assert error
    assert action.status == "active"
    assert action.last_error
    assert active_discipline(db, device.id).id == action.id
    assert any(
        entry.action == "discipline_ended" and entry.result == "failure"
        for entry in db.query(AuditLog).all()
    )


def test_expire_due_actions_restores_expired_limits(db):
    device = add_device(db)
    service = FakeService()
    action = start(db, device, service, expires_at=NOW + timedelta(minutes=5))

    with pytest.raises(ValueError):
        compute_expiry(None, NOW, NOW)

    ended = expire_due_actions(db, service, now=NOW + timedelta(minutes=10))

    assert ended == 1
    assert action.status == "ended"
    assert action.end_reason == "expired"
    assert service.assignments[-1] == (CLIENT_ID, "group-old")


def test_expire_due_actions_ignores_future_limits(db):
    device = add_device(db)
    service = FakeService()
    action = start(db, device, service, expires_at=NOW + timedelta(hours=2))

    assert expire_due_actions(db, service, now=NOW) == 0
    assert action.status == "active"
