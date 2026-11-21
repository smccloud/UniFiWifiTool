from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from urllib.parse import urlsplit, urlunsplit

_MAC_HEX_RE = re.compile(r"^[0-9a-fA-F]{12}$")
_HOST_SUFFIXES = (
    "/proxy/network",
    "/integration",
    "/api",
)


def normalize_mac(value: str) -> str:
    """Normalize aa:bb:cc:dd:ee:ff / AA-BB-... / aabb... to lowercase colons."""
    if not isinstance(value, str):
        raise ValueError("MAC address must be a string")
    raw = re.sub(r"[^0-9a-fA-F]", "", value.strip())
    if not _MAC_HEX_RE.match(raw):
        raise ValueError("Invalid MAC address")
    return ":".join(raw[i : i + 2].lower() for i in range(0, 12, 2))


def is_unicast_mac(mac: str) -> bool:
    first_octet = int(mac.split(":", 1)[0], 16)
    return first_octet & 0x01 == 0 and mac != "ff:ff:ff:ff:ff:ff"


def validate_device_mac(value: str) -> str:
    mac = normalize_mac(value)
    if not is_unicast_mac(mac):
        raise ValueError("Multicast and broadcast MAC addresses cannot be managed")
    return mac


def normalize_host(value: str) -> str:
    host = (value or "").strip()
    if not host:
        return ""
    if "://" not in host:
        host = f"https://{host}"
    parts = urlsplit(host)
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        raise ValueError("UNIFI_HOST must be a valid http(s) URL")
    path = parts.path.rstrip("/")
    for marker in _HOST_SUFFIXES:
        index = path.find(marker)
        if index >= 0:
            path = path[:index]
            break
    return urlunsplit((parts.scheme, parts.netloc, path, "", ""))


@dataclass
class LiveClient:
    mac: str
    name: str | None = None
    hostname: str | None = None
    ip_address: str | None = None
    ssid: str | None = None
    signal: int | None = None
    blocked: bool | None = None
    connected: bool = True
    last_seen_at: datetime | None = None
    source: str = "classic"
    unifi_client_id: str | None = None
    usergroup_id: str | None = None
    connection_type: str | None = None


@dataclass
class ControllerStatus:
    host: str = ""
    integration_ok: bool = False
    classic_ok: bool = False
    ssid_allowlist_ok: bool = False
    discipline_ok: bool = False
    version: str | None = None
    site_id: str | None = None
    site_name: str | None = None
    error: str | None = None
    warnings: list[str] = field(default_factory=list)
    checked_at: datetime | None = None

    def to_dict(self) -> dict:
        return {
            "host": self.host,
            "integrationOk": self.integration_ok,
            "classicOk": self.classic_ok,
            "ssidAllowlistOk": self.ssid_allowlist_ok,
            "disciplineOk": self.discipline_ok,
            "version": self.version,
            "site": (
                {"id": self.site_id, "name": self.site_name}
                if self.site_id or self.site_name
                else None
            ),
            "error": self.error,
            "warnings": self.warnings,
            "checkedAt": self.checked_at.isoformat() if self.checked_at else None,
        }
