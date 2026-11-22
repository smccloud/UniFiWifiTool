from __future__ import annotations

import secrets
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, loaded from environment variables / .env."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_name: str = "UniFi WiFi Access Manager"
    environment: str = "production"
    log_level: str = "INFO"

    database_url: str = "sqlite:///./data/unifi-wifi.db"
    static_dir: str = "static"
    license_file: str = ""

    # --- App auth ---------------------------------------------------------
    secret_key: str = ""
    session_cookie: str = "unifi_wifi_session"
    session_max_age_seconds: int = 12 * 60 * 60
    cookie_secure: bool = False
    trust_proxy: bool = False
    auth_disabled: bool = False
    app_username: str = "admin"
    app_password: str = ""
    app_password_hash: str = ""
    login_max_attempts: int = 8
    login_window_seconds: int = 5 * 60
    login_lockout_seconds: int = 5 * 60

    # --- UniFi controller -------------------------------------------------
    unifi_host: str = ""
    unifi_api_key: str = ""
    unifi_username: str = ""
    unifi_password: str = ""
    unifi_site: str = "default"
    unifi_site_id: str = ""
    unifi_wifi_name: str = ""
    unifi_os: bool = True
    unifi_tls_verify: bool = True
    unifi_ca_bundle: str = ""
    unifi_tls_insecure: bool = False
    unifi_timeout_seconds: float = 20.0

    # --- Behaviour --------------------------------------------------------
    app_timezone: str = "UTC"
    discovery_enabled: bool = True
    auto_apply: bool = True
    dry_run: bool = False
    audit_retention_days: int = Field(default=0, ge=0, le=36500)
    scheduler_enabled: bool = True
    scheduler_interval_seconds: int = Field(default=30, ge=5, le=3600)
    enable_ssid_allowlist: bool = False

    # --- Anonymous install tracking (opt-in) -----------------------------
    telemetry_enabled: bool = False
    telemetry_url: str = "https://unifiwifitool.smccloud.com/api/v1/heartbeat"
    telemetry_interval_seconds: int = Field(default=86400, ge=3600, le=604800)
    deployment_type: str = "docker"

    @field_validator("log_level")
    @classmethod
    def _upper_log_level(cls, value: str) -> str:
        return value.upper()

    @field_validator("unifi_host")
    @classmethod
    def _strip_host(cls, value: str) -> str:
        return value.strip().rstrip("/")

    @field_validator("telemetry_url")
    @classmethod
    def _strip_telemetry_url(cls, value: str) -> str:
        return value.strip().rstrip("/")

    @field_validator("deployment_type")
    @classmethod
    def _clean_deployment_type(cls, value: str) -> str:
        cleaned = "".join(ch for ch in value.lower() if ch.isalnum() or ch in {"_", "-"})
        return cleaned[:32] or "docker"

    @field_validator("app_timezone")
    @classmethod
    def _valid_timezone(cls, value: str) -> str:
        from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError(f"Unknown timezone: {value}") from exc
        return value

    @model_validator(mode="after")
    def _validate_auth(self) -> "Settings":
        if self.auth_disabled and self.environment.lower() in {"production", "prod"}:
            # Warned about at startup; do not block development over it here.
            pass
        if not self.secret_key:
            # Generated per process; sessions are invalidated on restart.
            self.secret_key = secrets.token_urlsafe(48)
        return self

    @property
    def tls_verify(self) -> bool | str:
        if self.unifi_tls_insecure:
            return False
        if self.unifi_ca_bundle:
            return self.unifi_ca_bundle
        return self.unifi_tls_verify

    @property
    def classic_configured(self) -> bool:
        return bool(self.unifi_host and self.unifi_username and self.unifi_password)

    @property
    def integration_configured(self) -> bool:
        return bool(self.unifi_host and self.unifi_api_key)

    @property
    def security_note(self) -> str | None:
        if self.environment.lower() in {"production", "prod"} and self.auth_disabled:
            return "AUTH_DISABLED is enabled in production. This is unsafe."
        if self.cookie_secure is False:
            return "Session cookie is not marked Secure. Use HTTPS in production."
        return None

    @property
    def static_path(self) -> Path:
        return Path(self.static_dir).resolve()


@lru_cache
def get_settings() -> Settings:
    return Settings()
