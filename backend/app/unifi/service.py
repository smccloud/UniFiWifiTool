from __future__ import annotations

import threading
import time
from app.config import Settings
from app.timeutil import utcnow
from app.unifi.classic import ClassicClient
from app.unifi.errors import UniFiAPIError, UniFiCapabilityError, UniFiError
from app.unifi.integration import IntegrationClient
from app.unifi.types import (
    ControllerStatus,
    LiveClient,
    normalize_host,
    validate_device_mac,
)

STATUS_TTL_SECONDS = 30


class UniFiService:
    """Facade over the two controller APIs, with capability detection."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.host = normalize_host(settings.unifi_host)
        self.integration = IntegrationClient(settings) if settings.integration_configured else None
        self.classic = ClassicClient(settings) if settings.classic_configured else None
        self._lock = threading.Lock()
        self._status: ControllerStatus | None = None
        self._status_at = 0.0

    def close(self) -> None:
        if self.integration:
            self.integration.close()
        if self.classic:
            self.classic.close()

    def invalidate(self) -> None:
        with self._lock:
            self._status = None
            self._status_at = 0.0

    # -- status -----------------------------------------------------------
    def detect(self, force: bool = False) -> ControllerStatus:
        now = time.monotonic()
        with self._lock:
            if (
                not force
                and self._status is not None
                and now - self._status_at < STATUS_TTL_SECONDS
            ):
                return self._status

            status = ControllerStatus(
                host=self.host,
                checked_at=utcnow(),
            )
            errors: list[str] = []
            if not self.host:
                status.error = "UNIFI_HOST is not configured"
                self._status = status
                self._status_at = now
                return status

            if self.integration:
                try:
                    info = self.integration.info()
                    status.version = _version_from_info(info)
                    site_id, site_name = self.integration.resolve_site()
                    status.site_id = site_id
                    status.site_name = site_name
                    status.integration_ok = True
                    try:
                        self.integration.list_wifi_broadcasts(site_id)
                        status.ssid_allowlist_ok = True
                    except UniFiError as exc:
                        status.warnings.append(f"SSID allowlist unavailable: {exc}")
                except UniFiError as exc:
                    errors.append(f"Integration API: {exc}")
            else:
                status.warnings.append("UNIFI_API_KEY is not configured")

            if self.classic:
                try:
                    self.classic.login()
                    status.classic_ok = True
                    try:
                        self.classic.list_usergroups()
                        status.discipline_ok = True
                    except UniFiError as exc:
                        status.warnings.append(
                            f"Temporary speed limits unavailable: {exc}"
                        )
                except UniFiError as exc:
                    errors.append(f"Local admin API: {exc}")
            else:
                status.warnings.append(
                    "UNIFI_USERNAME/UNIFI_PASSWORD are not configured; "
                    "block/unblock and temporary speed limits are unavailable"
                )

            if not status.integration_ok and not status.classic_ok:
                status.error = "; ".join(errors) or "No UniFi credentials configured"
            elif errors:
                status.warnings.extend(errors)

            if status.classic_ok and not status.ssid_allowlist_ok:
                status.warnings.append(
                    "Per-SSID MAC allowlists need Network 10.0+ and an API key"
                )

            self._status = status
            self._status_at = now
            return status

    # -- clients ----------------------------------------------------------
    def list_clients(self) -> list[LiveClient]:
        status = self.detect()
        if not status.classic_ok and not status.integration_ok:
            raise UniFiCapabilityError(status.error or "No controller access configured")

        if status.classic_ok:
            try:
                return self.classic.list_clients()  # type: ignore[union-attr]
            except UniFiError:
                self.invalidate()
                if not status.integration_ok:
                    raise

        if status.integration_ok:
            return self.integration.list_clients(status.site_id)  # type: ignore[union-attr]
        return []

    # -- actions ----------------------------------------------------------
    def set_blocked(self, mac: str, blocked: bool) -> None:
        status = self.detect()
        if not status.classic_ok or self.classic is None:
            raise UniFiCapabilityError(
                "Block/unblock requires a local admin account "
                "(UNIFI_USERNAME and UNIFI_PASSWORD). The official API key cannot perform this action."
            )
        self.classic.set_blocked(mac, blocked)

    # -- temporary speed limits (classic user groups) ---------------------
    def _require_discipline(self) -> ControllerStatus:
        status = self.detect()
        if not status.discipline_ok or self.classic is None:
            raise UniFiCapabilityError(
                "Temporary speed limits require a local admin session "
                "(UNIFI_USERNAME and UNIFI_PASSWORD) and a controller with user-group support."
            )
        return status

    def list_usergroups(self) -> list[dict]:
        self._require_discipline()
        return self.classic.list_usergroups()  # type: ignore[union-attr]

    def get_client_group(
        self, mac: str, client_id: str | None = None
    ) -> tuple[str | None, str | None]:
        self._require_discipline()
        row = self.classic.get_client(mac)  # type: ignore[union-attr]
        if row:
            return str(row.get("_id") or client_id or ""), row.get("usergroup_id") or None
        if client_id:
            return client_id, None
        raise UniFiCapabilityError(
            "This device is not known to the controller yet. Connect it once before applying a speed limit."
        )

    def default_usergroup_id(self) -> str:
        self._require_discipline()
        groups = self.classic.list_usergroups()  # type: ignore[union-attr]
        for group in groups:
            if str(group.get("name", "")).casefold() == "default":
                group_id = group.get("_id")
                if group_id:
                    return str(group_id)
        for group in groups:
            if (
                group.get("attr_no_delete")
                or (
                    group.get("qos_rate_max_down") == -1
                    and group.get("qos_rate_max_up") == -1
                )
            ):
                group_id = group.get("_id")
                if group_id:
                    return str(group_id)
        created = self.classic.create_usergroup(  # type: ignore[union-attr]
            "UniFiWifiTool Unlimited", -1, -1
        )
        group_id = created.get("_id")
        if not group_id:
            raise UniFiAPIError("Could not create an unlimited bandwidth group")
        return str(group_id)

    def create_discipline_group(
        self, name: str, download_kbps: int, upload_kbps: int
    ) -> dict:
        self._require_discipline()
        group = self.classic.create_usergroup(  # type: ignore[union-attr]
            name, download_kbps, upload_kbps
        )
        if not group.get("_id"):
            raise UniFiAPIError("Controller did not return a bandwidth group ID")
        return group

    def assign_usergroup(self, client_id: str, group_id: str) -> None:
        self._require_discipline()
        self.classic.set_client_usergroup(client_id, group_id)  # type: ignore[union-attr]

    def delete_usergroup(self, group_id: str) -> None:
        self._require_discipline()
        self.classic.delete_usergroup(group_id)  # type: ignore[union-attr]

    # -- SSID allowlist (official API, Network 10+) -----------------------
    def list_wifi_broadcasts(self) -> list[dict]:
        status = self.detect()
        if not status.integration_ok or self.integration is None:
            raise UniFiCapabilityError("The official integration API is not available")
        result = []
        for broadcast in self.integration.list_wifi_broadcasts(status.site_id):
            policy = broadcast.get("clientFilteringPolicy") or {}
            result.append(
                {
                    "id": broadcast.get("id"),
                    "name": broadcast.get("name"),
                    "enabled": broadcast.get("enabled"),
                    "type": broadcast.get("type"),
                    "policy": {
                        "action": policy.get("action"),
                        "macCount": len(policy.get("macAddressFilter") or []),
                    },
                }
            )
        return result

    def apply_ssid_allowlist(self, broadcast_id: str, macs: list[str]) -> dict:
        if not self.settings.enable_ssid_allowlist:
            raise UniFiCapabilityError(
                "SSID allowlist writes are disabled. Set ENABLE_SSID_ALLOWLIST=true to enable this experimental feature."
            )
        status = self.detect()
        if not status.integration_ok or self.integration is None:
            raise UniFiCapabilityError("The official integration API is not available")

        normalized = sorted({validate_device_mac(mac) for mac in macs})
        if len(normalized) > 512:
            raise UniFiCapabilityError("UniFi allows at most 512 MAC addresses per SSID filter")

        detail = self.integration.get_wifi_broadcast(broadcast_id, status.site_id)
        payload = {key: value for key, value in detail.items() if key not in {"id", "metadata"}}
        if not payload.get("type"):
            payload["type"] = "STANDARD"
        payload["clientFilteringPolicy"] = {
            "action": "ALLOW",
            "macAddressFilter": normalized,
        }
        # This endpoint reprovisions the SSID. Callers must confirm the blast radius.
        return self.integration.update_wifi_broadcast(broadcast_id, payload, status.site_id)


def _version_from_info(info: dict) -> str | None:
    if not isinstance(info, dict):
        return None
    for key in ("applicationVersion", "version", "networkVersion", "appVersion"):
        value = info.get(key)
        if value:
            return str(value)
    return None
