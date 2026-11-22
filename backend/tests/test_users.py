from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

from app import config
from app.db import dispose_engine, get_engine
from app.main import create_app

ADMIN = {"username": "admin", "display_name": "Admin", "password": "correct horse battery"}
VIEWER = {"username": "viewer", "display_name": "Viewer", "password": "viewer password 123"}


@pytest.fixture
def settings(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path / 'test.db'}")
    monkeypatch.setenv("APP_PASSWORD", "")
    monkeypatch.setenv("APP_PASSWORD_HASH", "")
    monkeypatch.setenv("APP_USERNAME", "admin")
    monkeypatch.setenv("AUTH_DISABLED", "false")
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    monkeypatch.setenv("SECRET_KEY", "test-secret-key-that-is-long-enough-123")
    config.get_settings.cache_clear()
    dispose_engine()
    yield config.get_settings()
    dispose_engine()
    config.get_settings.cache_clear()


@pytest.fixture
def app(settings):
    return create_app(settings)


@pytest.fixture
def client(app):
    with TestClient(app) as test_client:
        yield test_client


def csrf(client: TestClient) -> str:
    status = client.get("/api/setup/status")
    assert status.status_code == 200
    return status.json()["csrf_token"]


def complete_setup(client: TestClient, payload: dict | None = None) -> None:
    payload = payload or ADMIN
    response = client.post(
        "/api/setup", json=payload, headers={"X-CSRF-Token": csrf(client)}
    )
    assert response.status_code == 200, response.text


def login(client: TestClient, username: str, password: str) -> str:
    response = client.post(
        "/api/auth/login", json={"username": username, "password": password}
    )
    assert response.status_code == 200, response.text
    return response.json()["csrf_token"]


def test_first_run_setup_creates_admin(client: TestClient):
    status = client.get("/api/setup/status").json()
    assert status["setup_required"] is True
    assert status["csrf_token"]

    complete_setup(client)

    me = client.get("/api/auth/me")
    assert me.status_code == 200
    body = me.json()
    assert body["username"] == "admin"
    assert body["role"] == "admin"

    # The password is stored as a scrypt hash, never in plaintext.
    with get_engine().connect() as connection:
        row = connection.execute(
            text("SELECT password_hash FROM users WHERE username = 'admin'")
        ).one()
    assert row[0].startswith("scrypt$")
    assert ADMIN["password"] not in row[0]

    assert client.get("/api/setup/status").json()["setup_required"] is False

    second = client.post(
        "/api/setup",
        json={"username": "second", "password": "another long password"},
        headers={"X-CSRF-Token": client.get("/api/auth/me").json()["csrf_token"]},
    )
    assert second.status_code == 409


def test_setup_rejects_weak_password(client: TestClient):
    token = csrf(client)
    response = client.post(
        "/api/setup",
        json={"username": "admin", "password": "short"},
        headers={"X-CSRF-Token": token},
    )
    assert response.status_code == 400
    assert client.get("/api/setup/status").json()["setup_required"] is True


def test_login_and_role_enforcement(client: TestClient):
    complete_setup(client)
    created = client.post(
        "/api/users",
        json={**VIEWER, "role": "viewer"},
        headers={"X-CSRF-Token": client.get("/api/auth/me").json()["csrf_token"]},
    )
    assert created.status_code == 201, created.text

    client.post("/api/auth/logout")
    login(client, VIEWER["username"], VIEWER["password"])

    assert client.get("/api/devices").status_code == 200
    denied = client.put(
        "/api/system/audit-retention",
        json={"days": 0},
        headers={"X-CSRF-Token": client.get("/api/auth/me").json()["csrf_token"]},
    )
    assert denied.status_code == 403
    assert client.get("/api/users").status_code == 403


def test_password_change_revokes_other_sessions(app):
    with TestClient(app) as first, TestClient(app) as second:
        complete_setup(first)
        login(second, ADMIN["username"], ADMIN["password"])

        token = first.get("/api/auth/me").json()["csrf_token"]
        changed = first.post(
            "/api/account/password",
            json={
                "current_password": ADMIN["password"],
                "new_password": "a completely different passphrase",
            },
            headers={"X-CSRF-Token": token},
        )
        assert changed.status_code == 200, changed.text
        assert second.get("/api/auth/me").status_code == 401
        assert first.get("/api/auth/me").status_code == 200


def test_last_admin_cannot_be_disabled_or_deleted(client: TestClient):
    complete_setup(client)
    me = client.get("/api/auth/me").json()
    token = me["csrf_token"]

    disabled = client.patch(
        f"/api/users/{me['user_id']}",
        json={"disabled": True},
        headers={"X-CSRF-Token": token},
    )
    assert disabled.status_code == 400

    deleted = client.delete(
        f"/api/users/{me['user_id']}", headers={"X-CSRF-Token": token}
    )
    assert deleted.status_code == 400


def test_env_admin_is_imported_on_start(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path / 'env.db'}")
    monkeypatch.setenv("APP_PASSWORD", "environment password 123")
    monkeypatch.setenv("APP_USERNAME", "legacy")
    monkeypatch.setenv("AUTH_DISABLED", "false")
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    monkeypatch.setenv("SECRET_KEY", "test-secret-key-that-is-long-enough-123")
    config.get_settings.cache_clear()
    dispose_engine()

    app = create_app(config.get_settings())
    with TestClient(app) as client:
        assert client.get("/api/setup/status").json()["setup_required"] is False
        login(client, "legacy", "environment password 123")
        assert client.get("/api/auth/me").json()["role"] == "admin"

    dispose_engine()
    config.get_settings.cache_clear()


def test_audit_entries_record_user_id(client: TestClient):
    complete_setup(client)
    me = client.get("/api/auth/me").json()

    entries = client.get("/api/audit", params={"action": "setup_completed"}).json()
    assert entries["total"] == 1
    entry = entries["items"][0]
    assert entry["actor"] == "Admin"
    assert entry["user_id"] == me["user_id"]


def test_deleted_user_keeps_audit_history(client: TestClient):
    complete_setup(client)
    token = client.get("/api/auth/me").json()["csrf_token"]
    created = client.post(
        "/api/users",
        json={**VIEWER, "role": "viewer"},
        headers={"X-CSRF-Token": token},
    ).json()

    # Give the viewer a login entry attributed to their user id.
    client.post("/api/auth/logout")
    login(client, VIEWER["username"], VIEWER["password"])
    client.post("/api/auth/logout")
    login(client, ADMIN["username"], ADMIN["password"])
    token = client.get("/api/auth/me").json()["csrf_token"]

    deleted = client.delete(
        f"/api/users/{created['id']}", headers={"X-CSRF-Token": token}
    )
    assert deleted.status_code == 200

    entries = client.get("/api/audit", params={"action": "login"}).json()["items"]
    viewer_entries = [entry for entry in entries if entry["actor"] == "Viewer"]
    assert viewer_entries
    assert viewer_entries[0]["user_id"] is None

    client.post("/api/auth/logout")
    denied = client.post(
        "/api/auth/login",
        json={"username": VIEWER["username"], "password": VIEWER["password"]},
    )
    assert denied.status_code == 401


def test_viewer_cannot_mutate_devices(client: TestClient):
    complete_setup(client)
    token = client.get("/api/auth/me").json()["csrf_token"]
    client.post(
        "/api/users",
        json={**VIEWER, "role": "viewer"},
        headers={"X-CSRF-Token": token},
    )
    client.post("/api/auth/logout")
    login(client, VIEWER["username"], VIEWER["password"])
    viewer_token = client.get("/api/auth/me").json()["csrf_token"]

    response = client.post(
        "/api/devices",
        json={"mac": "aa:bb:cc:dd:ee:ff", "label": "Test"},
        headers={"X-CSRF-Token": viewer_token},
    )
    assert response.status_code == 403
