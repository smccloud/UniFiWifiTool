from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def utcnow() -> datetime:
    # MySQL DATETIME is timezone-naive; store and compare in UTC.
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class Install(Base):
    __tablename__ = "installs"

    install_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    first_seen: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    last_seen: Mapped[datetime] = mapped_column(DateTime, index=True, nullable=False)
    app_version: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    platform: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    deployment: Mapped[str] = mapped_column(String(32), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    @property
    def is_active(self) -> bool:
        return self.last_seen >= utcnow() - timedelta(hours=24)
