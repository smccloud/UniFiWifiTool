from __future__ import annotations

import base64
import json
import threading
import time
from typing import Any

import httpx

from app.config import Settings
from app.timeutil import epoch_to_datetime
from app.unifi.errors import (
    TwoFactorRequired,
    UniFiAPIError,
    UniFiAuthError,
    UniFiRateLimited,
    UniFiUnavailable,
)
from app.unifi.types import LiveClient, normalize_host, normalize_mac

USER_AGENT = "UniFiWifiTool/0.1"


class ClassicClient:
    """Legacy controller API (cookie + CSRF).

    The official Integration API does not expose client block/unblock, so this
    adapter is required for that action. It also provides richer client details
    (SSID, signal, blocked flag) than the official API.
    """

    def __init__(self, settings: Settings):
        self.settings = settings
        self.base_url = normalize_host(settings.unifi_host)
        self.site = settings.unifi_site or "default"
        self._http = httpx.Client(
            timeout=settings.unifi_timeout_seconds,
            verify=settings.tls_verify,
            headers={"Accept": "application/json", "User-Agent": USER_AGENT},
        )
        self._csrf: str | None = None
        self._expires_at: float = 0.0
        self._logged_in = False
        self._login_lock = threading.Lock()

    def close(self) -> None:
        self._http.close()

    @property
    def available(self) -> bool:
        return self._logged_in

    # -- auth -------------------------------------------------------------
    @property
    def _login_path(self) -> str:
        return "/api/auth/login" if self.settings.unifi_os else "/api/login"

    @property
    def _api_prefix(self) -> str:
        if self.settings.unifi_os:
            return f"/proxy/network/api/s/{self.site}"
        return f"/api/s/{self.site}"

    def login(self) -> None:
        with self._login_lock:
            if self._logged_in and time.time() < self._expires_at - 30:
                return
            self._http.cookies.clear()
            try:
                response = self._http.post(
                    f"{self.base_url}{self._login_path}",
                    json={
                        "username": self.settings.unifi_username,
                        "password": self.settings.unifi_password,
                        "remember": True,
                    },
                )
            except httpx.RequestError as exc:
                raise UniFiUnavailable(f"Could not reach the controller: {exc}") from exc

            payload = _safe_json(response)
            if response.status_code == 499 or _meta_msg(payload) == "api.err.Ubic2faTokenRequired":
                raise TwoFactorRequired(
                    "This admin account requires 2FA. Use a dedicated local service account without 2FA."
                )
            if response.status_code == 429:
                raise UniFiRateLimited(
                    "Controller login rate limit reached. Wait before retrying."
                )
            if response.status_code >= 400:
                raise UniFiAuthError(
                    f"Local admin login failed: {_error_message(payload, response)}"
                )
            if isinstance(payload, dict) and payload.get("meta", {}).get("rc") == "error":
                raise UniFiAuthError(
                    f"Local admin login failed: {_error_message(payload, response)}"
                )

            self._csrf = response.headers.get("x-csrf-token") or _csrf_from_cookie(
                self._http.cookies.get("TOKEN")
            )
            if not self._csrf:
                raise UniFiAuthError("Login succeeded but no CSRF token was returned")
            self._expires_at = _token_expiry(response.headers.get("x-token-expire-time"))
            self._logged_in = True

    def _ensure_login(self) -> None:
        if not self._logged_in or time.time() >= self._expires_at - 30:
            self.login()

    def _invalidate(self) -> None:
        self._logged_in = False
        self._csrf = None
        self._expires_at = 0.0

    # -- plumbing ---------------------------------------------------------
    def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict | None = None,
        json_body: Any | None = None,
        retry_auth: bool = True,
    ) -> dict:
        self._ensure_login()
        headers = {}
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            if not self._csrf:
                raise UniFiAuthError("No CSRF token available; re-login required")
            headers["X-CSRF-Token"] = self._csrf

        try:
            response = self._http.request(
                method,
                f"{self.base_url}{self._api_prefix}{path}",
                params=params,
                json=json_body,
                headers=headers,
            )
        except httpx.RequestError as exc:
            raise UniFiUnavailable(f"Could not reach the controller: {exc}") from exc

        new_csrf = response.headers.get("x-updated-csrf-token")
        if new_csrf:
            self._csrf = new_csrf

        payload = _safe_json(response)
        if response.status_code == 429:
            raise UniFiRateLimited("Controller rate limit reached")
        if response.status_code == 499 or _meta_msg(payload) == "api.err.Ubic2faTokenRequired":
            raise TwoFactorRequired("The controller requested a 2FA token")
        if response.status_code in {401, 403} or _meta_msg(payload) == "api.err.LoginRequired":
            if retry_auth:
                self._invalidate()
                self.login()
                return self._request(
                    method,
                    path,
                    params=params,
                    json_body=json_body,
                    retry_auth=False,
                )
            raise UniFiAuthError("Controller session expired and re-login failed")
        if response.status_code >= 400:
            raise UniFiAPIError(
                _error_message(payload, response),
                status_code=response.status_code,
                payload=payload,
            )
        if not isinstance(payload, dict):
            raise UniFiAPIError("Unexpected response from the controller", payload=payload)
        if payload.get("meta", {}).get("rc") == "error":
            message = _error_message(payload, response)
            if message == "api.err.LoginRequired" and retry_auth:
                self._invalidate()
                self.login()
                return self._request(
                    method,
                    path,
                    params=params,
                    json_body=json_body,
                    retry_auth=False,
                )
            raise UniFiAPIError(message, status_code=response.status_code, payload=payload)
        return payload

    # -- operations -------------------------------------------------------
    def list_clients(self) -> list[LiveClient]:
        payload = self._request("GET", "/stat/sta", params={"_limit": 1000})
        rows = payload.get("data") or []
        if not isinstance(rows, list):
            return []
        clients: list[LiveClient] = []
        for row in rows:
            if not isinstance(row, dict) or not row.get("mac"):
                continue
            try:
                mac = normalize_mac(str(row["mac"]))
            except ValueError:
                continue
            connection_type = "wired" if row.get("is_wired") else "wireless"
            clients.append(
                LiveClient(
                    mac=mac,
                    name=row.get("name") or row.get("hostname") or None,
                    hostname=row.get("hostname") or None,
                    ip_address=row.get("ip") or None,
                    ssid=row.get("essid") or row.get("ssid") or None,
                    signal=_as_int(row.get("signal")),
                    blocked=bool(row.get("blocked")) if "blocked" in row else None,
                    connected=True,
                    last_seen_at=epoch_to_datetime(row.get("last_seen")),
                    source="classic",
                    unifi_client_id=row.get("_id") or row.get("user_id"),
                    usergroup_id=row.get("usergroup_id") or None,
                    connection_type=connection_type,
                )
            )
        return clients

    def set_blocked(self, mac: str, blocked: bool) -> None:
        cmd = "block-sta" if blocked else "unblock-sta"
        self._request("POST", "/cmd/stamgr", json_body={"cmd": cmd, "mac": mac})

    # -- bandwidth user groups -------------------------------------------
    def list_usergroups(self) -> list[dict]:
        payload = self._request("GET", "/list/usergroup")
        data = payload.get("data") or []
        if isinstance(data, dict):
            data = data.get("data") or []
        return [row for row in data if isinstance(row, dict)]

    def create_usergroup(self, name: str, download_kbps: int, upload_kbps: int) -> dict:
        payload = self._request(
            "POST",
            "/rest/usergroup",
            json_body={
                "name": name,
                "qos_rate_max_down": download_kbps,
                "qos_rate_max_up": upload_kbps,
            },
        )
        data = payload.get("data") or []
        if isinstance(data, list) and data and isinstance(data[0], dict):
            return data[0]
        if isinstance(data, dict):
            return data
        return {}

    def delete_usergroup(self, group_id: str) -> None:
        self._request("DELETE", f"/rest/usergroup/{group_id}")

    def set_client_usergroup(self, client_id: str, group_id: str) -> None:
        self._request(
            "POST",
            f"/upd/user/{client_id}",
            json_body={"usergroup_id": group_id},
        )

    def get_client(self, mac: str) -> dict | None:
        for path, params in (
            ("/stat/sta", {"mac": mac}),
            (f"/stat/user/{mac}", None),
        ):
            try:
                payload = self._request("GET", path, params=params)
            except UniFiAPIError:
                continue
            for row in _data_rows(payload):
                try:
                    if normalize_mac(str(row.get("mac", ""))) == mac:
                        return row
                except ValueError:
                    continue
        return None


def _data_rows(payload: Any) -> list[dict]:
    if not isinstance(payload, dict):
        return []
    data = payload.get("data")
    if isinstance(data, list):
        return [row for row in data if isinstance(row, dict)]
    if isinstance(data, dict):
        return [data]
    return []


def _first_data(payload: Any) -> dict | None:
    if not isinstance(payload, dict):
        return None
    data = payload.get("data")
    if isinstance(data, list):
        return data[0] if data and isinstance(data[0], dict) else None
    if isinstance(data, dict):
        return data
    return None


def _safe_json(response: httpx.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return None


def _meta_msg(payload: Any) -> str | None:
    if isinstance(payload, dict):
        meta = payload.get("meta")
        if isinstance(meta, dict):
            msg = meta.get("msg")
            return str(msg) if msg else None
    return None


def _error_message(payload: Any, response: httpx.Response) -> str:
    if isinstance(payload, dict):
        meta = payload.get("meta")
        if isinstance(meta, dict) and meta.get("msg"):
            return str(meta["msg"])
        for key in ("message", "error", "detail"):
            if payload.get(key):
                return str(payload[key])
    text = response.text.strip()
    return text[:300] if text else f"Controller returned HTTP {response.status_code}"


def _csrf_from_cookie(token: str | None) -> str | None:
    if not token:
        return None
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        value = data.get("csrfToken")
        return str(value) if value else None
    except (IndexError, ValueError, json.JSONDecodeError):
        return None


def _token_expiry(raw: str | None) -> float:
    if not raw:
        return time.time() + 30 * 60
    try:
        value = float(raw)
    except ValueError:
        return time.time() + 30 * 60
    if value > 10_000_000_000:  # milliseconds
        value /= 1000.0
    return value


def _as_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
