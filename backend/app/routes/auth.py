from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app import __version__, audit
from app.auth import (
    LoginRateLimiter,
    account_key,
    authenticate,
    clear_session,
    get_current_user,
    issue_session,
    login_key,
)
from app.config import get_settings
from app.db import get_db
from app.models import User
from app.schemas import LoginRequest, LoginResponse, MeResponse
from app.timeutil import utcnow

router = APIRouter(prefix="/api/auth", tags=["auth"])

_limiter: LoginRateLimiter | None = None


def _get_limiter() -> LoginRateLimiter:
    global _limiter
    if _limiter is None:
        settings = get_settings()
        _limiter = LoginRateLimiter(
            max_attempts=settings.login_max_attempts,
            window_seconds=settings.login_window_seconds,
            lockout_seconds=settings.login_lockout_seconds,
        )
    return _limiter


@router.post("/login", response_model=LoginResponse)
def login(payload: LoginRequest, request: Request, db: Session = Depends(get_db)):
    limiter = _get_limiter()
    key = login_key(request)
    account = account_key(payload.username)
    limiter.check(key)
    limiter.check(account)
    request_id = getattr(request.state, "request_id", None)

    user = authenticate(db, payload.username, payload.password)
    if user is None:
        limiter.fail(key)
        limiter.fail(account)
        audit.record(
            db,
            actor=(payload.username.strip()[:64] or "unknown"),
            action="login",
            result="failure",
            detail={"reason": "invalid credentials"},
            request_id=request_id,
        )
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password",
        )

    limiter.reset(key)
    limiter.reset(account)
    user.last_login_at = utcnow()
    csrf = issue_session(request, user)
    audit.record(
        db,
        actor=user.audit_name,
        user_id=user.id,
        action="login",
        result="success",
        request_id=request_id,
    )
    db.commit()
    return LoginResponse(
        username=user.username,
        csrf_token=csrf,
        role=user.role,  # type: ignore[arg-type]
        display_name=user.display_name,
    )


@router.post("/logout")
def logout(request: Request, user: User = Depends(get_current_user)):
    actor = user.audit_name
    clear_session(request)
    return {"ok": True, "actor": actor}


@router.get("/me", response_model=MeResponse)
def me(request: Request, user: User = Depends(get_current_user)):
    settings = get_settings()
    csrf = str(request.session.get("csrf", ""))
    return MeResponse(
        username=user.username,
        user_id=user.id,
        display_name=user.display_name,
        role=user.role,  # type: ignore[arg-type]
        csrf_token=csrf,
        auth_disabled=settings.auth_disabled,
        app_name=settings.app_name,
        version=__version__,
    )
