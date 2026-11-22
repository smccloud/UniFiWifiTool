from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import re
import secrets
import threading
import time
from collections import deque
from functools import lru_cache

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app import audit
from app.config import get_settings
from app.db import get_db
from app.models import Setting, User
from app.timeutil import utcnow

logger = logging.getLogger("unifi_wifi_tool")

SAFE_METHODS = {"GET", "HEAD", "OPTIONS"}
ROLE_ADMIN = "admin"
ROLE_VIEWER = "viewer"
ROLES = {ROLE_ADMIN, ROLE_VIEWER}
PASSWORD_MIN_LENGTH = 12
PASSWORD_MAX_LENGTH = 1024

_SCRYPT_N = 2**14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_DKLEN = 32


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_SCRYPT_DKLEN,
    )
    return "scrypt${}${}${}${}${}".format(
        _SCRYPT_N,
        _SCRYPT_R,
        _SCRYPT_P,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(digest).decode("ascii"),
    )


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, n, r, p, salt_b64, digest_b64 = stored.split("$")
        if scheme != "scrypt":
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(digest_b64)
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


@lru_cache
def _timing_hash() -> str:
    """A throwaway hash so unknown usernames cost the same as known ones."""
    return hash_password(secrets.token_urlsafe(32))


def password_policy_error(password: str) -> str | None:
    if len(password) < PASSWORD_MIN_LENGTH:
        return f"Password must be at least {PASSWORD_MIN_LENGTH} characters."
    if len(password) > PASSWORD_MAX_LENGTH:
        return f"Password must be at most {PASSWORD_MAX_LENGTH} characters."
    if not password.strip():
        return "Password must not be blank."
    return None


_USERNAME_RE = re.compile(r"^[A-Za-z0-9._-]{3,64}$")


def username_error(username: str) -> str | None:
    candidate = username.strip()
    if not _USERNAME_RE.fullmatch(candidate):
        return "Username must be 3-64 characters using letters, digits, dot, dash, or underscore."
    if candidate.lower() in {"system", "scheduler", "dev"}:
        return "That username is reserved."
    return None


def _normalise_username(username: str) -> str:
    return username.strip().lower()


def find_user(db: Session, username: str) -> User | None:
    return (
        db.query(User)
        .filter(func.lower(User.username) == _normalise_username(username))
        .one_or_none()
    )


def authenticate(db: Session, username: str, password: str) -> User | None:
    """Return the enabled user for these credentials, or None.

    Unknown usernames and disabled accounts still pay the scrypt cost so the
    request timing does not reveal whether an account exists.
    """
    user = find_user(db, username)
    if user is None:
        verify_password(password, _timing_hash())
        return None
    if not verify_password(password, user.password_hash):
        return None
    if user.disabled:
        return None
    return user


def user_count(db: Session) -> int:
    return int(db.query(func.count(User.id)).scalar() or 0)


def enabled_admin_count(db: Session) -> int:
    return int(
        db.query(func.count(User.id))
        .filter(User.role == ROLE_ADMIN, User.disabled.is_(False))
        .scalar()
        or 0
    )


def is_last_enabled_admin(db: Session, user: User) -> bool:
    return (
        user.role == ROLE_ADMIN
        and not user.disabled
        and enabled_admin_count(db) <= 1
    )


class LoginRateLimiter:
    """Small in-process limiter. Replace with Redis for multi-replica setups."""

    def __init__(self, max_attempts: int, window_seconds: int, lockout_seconds: int):
        self.max_attempts = max_attempts
        self.window_seconds = window_seconds
        self.lockout_seconds = lockout_seconds
        self._failures: dict[str, deque[float]] = {}
        self._locked: dict[str, float] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            locked_until = self._locked.get(key, 0)
            if locked_until > now:
                retry = max(1, int(locked_until - now))
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=f"Too many login attempts. Try again in {retry}s.",
                    headers={"Retry-After": str(retry)},
                )
            if locked_until:
                self._locked.pop(key, None)
            bucket = self._failures.get(key)
            if not bucket:
                return
            while bucket and bucket[0] < now - self.window_seconds:
                bucket.popleft()
            if not bucket:
                self._failures.pop(key, None)

    def fail(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            bucket = self._failures.setdefault(key, deque())
            bucket.append(now)
            if len(bucket) >= self.max_attempts:
                self._locked[key] = now + self.lockout_seconds
                self._failures.pop(key, None)

    def reset(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
            self._locked.pop(key, None)


def login_key(request: Request) -> str:
    settings = get_settings()
    host = request.client.host if request.client else "unknown"
    if settings.trust_proxy:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            host = forwarded.split(",", 1)[0].strip()
    return f"{host}:{request.headers.get('user-agent', '')[:80]}"


def account_key(username: str) -> str:
    return f"account:{_normalise_username(username)}"


_DEV_USER = User(
    id=0,
    username="dev",
    display_name="dev",
    password_hash="",
    role=ROLE_ADMIN,
    disabled=False,
    session_version=1,
    created_at=utcnow(),
)


def _dev_user() -> User:
    return _DEV_USER


def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    settings = get_settings()
    if settings.auth_disabled:
        return _dev_user()

    uid = request.session.get("uid")
    username = request.session.get("user")
    user: User | None = None
    if uid is not None:
        try:
            user = db.get(User, int(uid))
        except (TypeError, ValueError):
            user = None
    if user is None and username:
        user = find_user(db, str(username))

    if user is None or user.disabled:
        clear_session(request)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
        )

    expected_version = request.session.get("sv")
    if expected_version is None:
        # Sessions created before user accounts existed: adopt them once.
        request.session["uid"] = user.id
        request.session["sv"] = user.session_version
    elif int(expected_version) != user.session_version:
        clear_session(request)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session expired. Sign in again.",
        )

    request.state.current_user = user
    return user


def require_admin(current: User = Depends(get_current_user)) -> User:
    if current.role != ROLE_ADMIN:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Administrator role required",
        )
    return current


# Kept as an alias while callers migrate from usernames to user records.
require_user = get_current_user


def is_admin(user: User) -> bool:
    return user.role == ROLE_ADMIN


def csrf_guard(request: Request) -> None:
    if request.method in SAFE_METHODS or get_settings().auth_disabled:
        return
    expected = request.session.get("csrf")
    supplied = request.headers.get("x-csrf-token", "")
    if not expected or not supplied or not secrets.compare_digest(str(expected), supplied):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid or missing CSRF token",
        )


def issue_session(request: Request, user: User) -> str:
    csrf = secrets.token_urlsafe(32)
    request.session.clear()
    request.session["user"] = user.username
    request.session["uid"] = user.id
    request.session["sv"] = user.session_version
    request.session["csrf"] = csrf
    return csrf


def clear_session(request: Request) -> None:
    request.session.clear()


def revoke_sessions(user: User) -> None:
    user.session_version += 1


def bootstrap_from_environment(db: Session) -> User | None:
    """Import the env-configured admin on first start, if configured.

    Fresh installs without APP_PASSWORD/APP_PASSWORD_HASH stay in setup mode.
    """
    settings = get_settings()
    if settings.auth_disabled or user_count(db) > 0:
        return None
    if not (settings.app_password or settings.app_password_hash):
        return None

    password_hash = settings.app_password_hash or hash_password(settings.app_password)
    username = (settings.app_username or "admin").strip() or "admin"
    user = User(
        username=username[:64],
        display_name=username[:128],
        password_hash=password_hash,
        role=ROLE_ADMIN,
        disabled=False,
        session_version=1,
    )
    db.add(user)
    db.flush()
    if db.get(Setting, "setup.completed") is None:
        db.add(Setting(key="setup.completed", value=str(user.id)))
    audit.record(
        db,
        actor="system",
        user_id=user.id,
        action="user_bootstrapped",
        detail={"username": user.username, "source": "environment"},
    )
    db.commit()
    logger.info(
        "imported environment admin %r; manage users in the Web UI from now on", user.username
    )
    return user
