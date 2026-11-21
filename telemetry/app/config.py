from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache


@dataclass(frozen=True)
class Settings:
    database_url: str
    admin_username: str
    admin_password: str
    retention_days: int
    public_url: str
    log_level: str


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must be set")
    return value


@lru_cache
def load_settings() -> Settings:
    try:
        retention = int(os.environ.get("TELEMETRY_RETENTION_DAYS", "90"))
    except ValueError as exc:
        raise RuntimeError("TELEMETRY_RETENTION_DAYS must be an integer") from exc
    if retention < 1 or retention > 3650:
        raise RuntimeError("TELEMETRY_RETENTION_DAYS must be between 1 and 3650")

    return Settings(
        database_url=_required("TELEMETRY_DATABASE_URL"),
        admin_username=os.environ.get("TELEMETRY_ADMIN_USERNAME", "admin").strip() or "admin",
        admin_password=_required("TELEMETRY_ADMIN_PASSWORD"),
        retention_days=retention,
        public_url=os.environ.get(
            "TELEMETRY_PUBLIC_URL", "https://unifiwifitool.smccloud.com"
        ).rstrip("/"),
        log_level=os.environ.get("TELEMETRY_LOG_LEVEL", "INFO").upper(),
    )
