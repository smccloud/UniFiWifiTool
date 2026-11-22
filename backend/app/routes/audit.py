from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.auth import csrf_guard, get_current_user
from app.db import get_db
from app.models import AuditLog
from app.schemas import AuditList, audit_out

router = APIRouter(
    prefix="/api/audit",
    tags=["audit"],
    dependencies=[Depends(get_current_user), Depends(csrf_guard)],
)


@router.get("", response_model=AuditList)
def list_audit(
    mac: str = "",
    action: str = "",
    result: str = "",
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
):
    query = db.query(AuditLog)
    if mac.strip():
        query = query.filter(AuditLog.target_mac == mac.strip().lower())
    if action.strip():
        query = query.filter(AuditLog.action == action.strip())
    if result.strip():
        query = query.filter(AuditLog.result == result.strip())
    total = query.count()
    entries = (
        query.order_by(AuditLog.created_at.desc(), AuditLog.id.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return AuditList(items=[audit_out(entry) for entry in entries], total=total)
