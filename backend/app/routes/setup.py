from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import audit
from app.auth import (
    ROLE_ADMIN,
    csrf_guard,
    hash_password,
    issue_session,
    password_policy_error,
    user_count,
    username_error,
)
from app.config import get_settings
from app.db import get_db
from app.models import Setting, User
from app.schemas import LoginResponse, SetupRequest, SetupStatus

router = APIRouter(prefix="/api/setup", tags=["setup"])

SETUP_MARKER = "setup.completed"


def _setup_required(db: Session) -> bool:
    if get_settings().auth_disabled:
        return False
    return user_count(db) == 0 and db.get(Setting, SETUP_MARKER) is None


@router.get("/status", response_model=SetupStatus)
def setup_status(request: Request, db: Session = Depends(get_db)):
    required = _setup_required(db)
    csrf = request.session.get("csrf")
    if required and not csrf:
        csrf = secrets.token_urlsafe(32)
        request.session["csrf"] = csrf
    return SetupStatus(setup_required=required, csrf_token=str(csrf or ""))


@router.post("", response_model=LoginResponse, dependencies=[Depends(csrf_guard)])
def create_initial_admin(
    payload: SetupRequest, request: Request, db: Session = Depends(get_db)
):
    if not _setup_required(db):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Setup has already been completed.",
        )

    username = payload.username.strip()
    error = username_error(username)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)
    error = password_policy_error(payload.password)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)

    display_name = payload.display_name.strip()
    user = User(
        username=username,
        display_name=display_name[:128],
        password_hash=hash_password(payload.password),
        role=ROLE_ADMIN,
        disabled=False,
        session_version=1,
    )
    db.add(user)
    db.flush()
    # The marker's primary key is the race guard: two concurrent setup posts
    # cannot both commit, even with different usernames.
    db.add(Setting(key=SETUP_MARKER, value=str(user.id)))
    audit.record(
        db,
        actor=user.audit_name,
        user_id=user.id,
        action="setup_completed",
        detail={"username": user.username},
    )
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Setup has already been completed.",
        ) from None

    csrf = issue_session(request, user)
    return LoginResponse(
        username=user.username,
        csrf_token=csrf,
        role=user.role,  # type: ignore[arg-type]
        display_name=user.display_name,
    )
