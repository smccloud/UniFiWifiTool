from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, ForeignKey, Index, Integer, String, Text, or_
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UTCDateTime
from app.timeutil import utcnow


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    mac: Mapped[str] = mapped_column(String(17), unique=True, index=True, nullable=False)
    label: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    notes: Mapped[str] = mapped_column(Text, default="", nullable=False)

    # allowed | blocked | scheduled | ignored
    access_mode: Mapped[str] = mapped_column(String(16), default="ignored", index=True)

    # Schedule (only meaningful when access_mode == "scheduled").
    # Windows can individually allow or block; block windows win.
    # schedule_default is the state outside every window.
    schedule_kind: Mapped[str] = mapped_column(String(16), default="allow_during")
    schedule_default: Mapped[str | None] = mapped_column(String(16), nullable=True)
    schedule_timezone: Mapped[str | None] = mapped_column(String(64), nullable=True)
    schedule_windows: Mapped[str | None] = mapped_column(Text, nullable=True)

    # Computed state and enforcement bookkeeping
    effective_access: Mapped[str | None] = mapped_column(String(16), nullable=True)
    next_change_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    last_applied_access: Mapped[str | None] = mapped_column(String(16), nullable=True)
    last_applied_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    last_synced_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)

    # Last observed controller state
    observed_blocked: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    hostname: Mapped[str | None] = mapped_column(String(255), nullable=True)
    ip_address: Mapped[str | None] = mapped_column(String(64), nullable=True)
    last_ssid: Mapped[str | None] = mapped_column(String(128), nullable=True)
    last_signal: Mapped[int | None] = mapped_column(Integer, nullable=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    unifi_client_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # wireless | wired | NULL when not yet observed
    connection_type: Mapped[str | None] = mapped_column(String(16), nullable=True)

    # manual | discovered
    source: Mapped[str] = mapped_column(String(16), default="manual")
    created_by: Mapped[str] = mapped_column(String(64), default="system")
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )

    __table_args__ = (Index("ix_devices_mode_source", "access_mode", "source"),)


# The inventory is WiFi-only. Rows that have not been observed yet (NULL) stay
# visible; once the controller reports a device as wired, it is hidden.
WIRELESS_ONLY = or_(Device.connection_type.is_(None), Device.connection_type != "wired")


class DisciplineAction(Base):
    """A temporary per-client bandwidth limit implemented with a user group."""

    __tablename__ = "discipline_actions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[int] = mapped_column(
        ForeignKey("devices.id", ondelete="CASCADE"), index=True, nullable=False
    )
    mac: Mapped[str] = mapped_column(String(17), index=True, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default="active", index=True)
    download_kbps: Mapped[int] = mapped_column(Integer, nullable=False)
    upload_kbps: Mapped[int] = mapped_column(Integer, nullable=False)
    started_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True, nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    previous_usergroup_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    temp_usergroup_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    unifi_client_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_by: Mapped[str] = mapped_column(String(64), default="system")
    end_reason: Mapped[str | None] = mapped_column(String(16), nullable=True)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)

    __table_args__ = (Index("ix_discipline_status_expiry", "status", "expires_at"),)


class AuditLog(Base):
    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, index=True, nullable=False
    )
    actor: Mapped[str] = mapped_column(String(64), default="system", nullable=False)
    action: Mapped[str] = mapped_column(String(48), index=True, nullable=False)
    target_mac: Mapped[str | None] = mapped_column(String(17), index=True, nullable=True)
    result: Mapped[str] = mapped_column(String(16), default="success", nullable=False)
    detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    request_id: Mapped[str | None] = mapped_column(String(64), nullable=True)


class Setting(Base):
    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
