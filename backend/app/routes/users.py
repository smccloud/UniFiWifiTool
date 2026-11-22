from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import audit
from app.auth import (
    ROLE_ADMIN,
    csrf_guard,
    get_current_user,
    hash_password,
    is_last_enabled_admin,
    issue_session,
    password_policy_error,
    require_admin,
    revoke_sessions,
    username_error,
    verify_password,
)
from app.db import get_db
from app.deps import get_request_id
from app.models import User
from app.schemas import (
    PasswordChange,
    PasswordChangeResponse,
    PasswordReset,
    UserCreate,
    UserList,
    UserOut,
    UserUpdate,
)

router = APIRouter(
    prefix="/api",
    tags=["users"],
    dependencies=[Depends(get_current_user), Depends(csrf_guard)],
)


def _get_user(db: Session, user_id: int) -> User:
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return user


def _actor(current: User) -> tuple[str, int]:
    return current.audit_name, current.id


@router.get("/users", response_model=UserList)
def list_users(db: Session = Depends(get_db), _admin: User = Depends(require_admin)):
    users = db.query(User).order_by(func.lower(User.username)).all()
    return UserList(items=[UserOut.model_validate(user) for user in users], total=len(users))


@router.post("/users", response_model=UserOut, status_code=status.HTTP_201_CREATED)
def create_user(
    payload: UserCreate,
    request: Request,
    db: Session = Depends(get_db),
    admin: User = Depends(require_admin),
):
    username = payload.username.strip()
    error = username_error(username)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)
    error = password_policy_error(payload.password)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)
    if (
        db.query(User)
        .filter(func.lower(User.username) == username.lower())
        .one_or_none()
        is not None
    ):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Username is already in use"
        )

    user = User(
        username=username,
        display_name=payload.display_name.strip()[:128],
        password_hash=hash_password(payload.password),
        role=payload.role,
        disabled=False,
        session_version=1,
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Username is already in use"
        ) from None

    actor, actor_id = _actor(admin)
    audit.record(
        db,
        actor=actor,
        user_id=actor_id,
        action="user_created",
        detail={"username": user.username, "role": user.role},
        request_id=get_request_id(request),
    )
    db.commit()
    db.refresh(user)
    return UserOut.model_validate(user)


@router.patch("/users/{user_id}", response_model=UserOut)
def update_user(
    user_id: int,
    payload: UserUpdate,
    db: Session = Depends(get_db),
    admin: User = Depends(require_admin),
):
    user = _get_user(db, user_id)
    actions: list[str] = []

    if payload.display_name is not None:
        user.display_name = payload.display_name.strip()[:128]
        actions.append("display_name")
    if payload.role is not None and payload.role != user.role:
        if is_last_enabled_admin(db, user) and payload.role != ROLE_ADMIN:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot demote the last enabled administrator",
            )
        user.role = payload.role
        revoke_sessions(user)
        actions.append("role")
    if payload.disabled is not None and payload.disabled != user.disabled:
        if payload.disabled and user.id == admin.id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="You cannot disable your own account",
            )
        if payload.disabled and is_last_enabled_admin(db, user):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot disable the last enabled administrator",
            )
        user.disabled = payload.disabled
        if payload.disabled:
            revoke_sessions(user)
        actions.append("disabled" if payload.disabled else "enabled")
    if payload.revoke_sessions:
        revoke_sessions(user)
        actions.append("sessions_revoked")

    actor, actor_id = _actor(admin)
    audit.record(
        db,
        actor=actor,
        user_id=actor_id,
        action=(
            "user_disabled"
            if "disabled" in actions
            else "user_enabled"
            if "enabled" in actions
            else "user_updated"
        ),
        detail={"username": user.username, "changes": actions},
    )
    db.commit()
    db.refresh(user)
    return UserOut.model_validate(user)


@router.delete("/users/{user_id}")
def delete_user(
    user_id: int,
    db: Session = Depends(get_db),
    admin: User = Depends(require_admin),
):
    user = _get_user(db, user_id)
    if user.id == admin.id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You cannot delete your own account",
        )
    if is_last_enabled_admin(db, user):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot delete the last enabled administrator",
        )
    username = user.username
    db.delete(user)
    actor, actor_id = _actor(admin)
    audit.record(
        db,
        actor=actor,
        user_id=actor_id,
        action="user_deleted",
        detail={"username": username},
    )
    db.commit()
    return {"ok": True, "username": username}


@router.post("/users/{user_id}/password")
def reset_password(
    user_id: int,
    payload: PasswordReset,
    request: Request,
    db: Session = Depends(get_db),
    admin: User = Depends(require_admin),
):
    user = _get_user(db, user_id)
    error = password_policy_error(payload.new_password)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)

    user.password_hash = hash_password(payload.new_password)
    revoke_sessions(user)
    actor, actor_id = _actor(admin)
    audit.record(
        db,
        actor=actor,
        user_id=actor_id,
        action="password_reset",
        detail={"username": user.username},
    )
    db.commit()
    if user.id == admin.id:
        # The acting admin reset their own password; keep this session usable.
        csrf = issue_session(request, user)
        return {"ok": True, "csrf_token": csrf}
    return {"ok": True}


@router.post("/account/password", response_model=PasswordChangeResponse)
def change_own_password(
    payload: PasswordChange,
    request: Request,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    if not verify_password(payload.current_password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Current password is incorrect",
        )
    if verify_password(payload.new_password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Choose a password different from the current one",
        )
    error = password_policy_error(payload.new_password)
    if error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=error)

    user.password_hash = hash_password(payload.new_password)
    revoke_sessions(user)
    audit.record(
        db,
        actor=user.audit_name,
        user_id=user.id,
        action="password_changed",
        detail={"username": user.username},
    )
    db.commit()
    # Re-issue this session so the caller is not logged out by the version bump.
    csrf = issue_session(request, user)
    return PasswordChangeResponse(ok=True, csrf_token=csrf)
