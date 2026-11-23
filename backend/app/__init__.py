"""UniFi WiFi Access Manager backend."""

from __future__ import annotations

import os
from pathlib import Path


def _read_version() -> str:
    override = os.environ.get("APP_VERSION", "").strip()
    if override:
        return override

    # Repository root in development, /app/VERSION in the container.
    for candidate in (
        Path(__file__).resolve().parents[2] / "VERSION",
        Path("/app/VERSION"),
    ):
        if candidate.is_file():
            value = candidate.read_text(encoding="utf-8").strip()
            if value:
                return value
    return "1.1.3"


__version__ = _read_version()
