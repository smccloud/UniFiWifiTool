from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app import audit
from app.auth import csrf_guard, require_user
from app.config import get_settings
from app.db import get_db
from app.deps import get_request_id, get_service
from app.discipline import (
    active_discipline,
    compute_expiry,
    end_discipline,
    start_discipline,
)
from app.models import WIRELESS_ONLY, Device, DisciplineAction
from app.reconcile import apply_device_now, refresh_device_state
from app.schedule import ScheduleError, clear_schedule, save_schedule, validate_schedule
from app.schemas import (
    DeviceCreate,
    DeviceList,
    DeviceOut,
    DeviceUpdate,
    DisciplineOut,
    DisciplineStart,
    ScheduleUpdate,
    device_out,
    discipline_out,
)
from app.unifi.errors import UniFiError
from app.unifi.service import UniFiService
from app.unifi.types import validate_device_mac

router = APIRouter(
    prefix="/api/devices",
    tags=["devices"],
    dependencies=[Depends(require_user), Depends(csrf_guard)],
)


def _get_device(db: Session, device_id: int) -> Device:
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
    return device


def _device_response(db: Session, device: Device, connected: bool | None = None) -> DeviceOut:
    action = active_discipline(db, device.id)
    return device_out(
        device,
        connected=connected,
        discipline=discipline_out(action) if action else None,
    )


@router.get("", response_model=DeviceList)
def list_devices(
    q: str = "",
    access_mode: str = "",
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
):
    query = db.query(Device).filter(WIRELESS_ONLY)
    if q.strip():
        needle = f"%{q.strip().lower()}%"
        query = query.filter(
            or_(
                func.lower(Device.mac).like(needle),
                func.lower(Device.label).like(needle),
                func.lower(func.coalesce(Device.hostname, "")).like(needle),
                func.lower(func.coalesce(Device.notes, "")).like(needle),
            )
        )
    if access_mode:
        modes = {item.strip() for item in access_mode.split(",") if item.strip()}
        valid = {"allowed", "blocked", "scheduled", "ignored"}
        if modes - valid:
            raise HTTPException(status_code=400, detail="Invalid access_mode filter")
        query = query.filter(Device.access_mode.in_(modes))

    total = query.count()
    devices = (
        query.order_by(Device.updated_at.desc()).offset(offset).limit(limit).all()
    )
    active = {
        action.device_id: action
        for action in db.query(DisciplineAction)
        .filter(
            DisciplineAction.device_id.in_([device.id for device in devices]),
            DisciplineAction.status == "active",
        )
        .all()
    }
    return DeviceList(
        items=[
            device_out(
                device,
                discipline=discipline_out(active[device.id]) if device.id in active else None,
            )
            for device in devices
        ],
        total=total,
    )


@router.get("/{device_id}", response_model=DeviceOut)
def get_device(device_id: int, db: Session = Depends(get_db)):
    return _device_response(db, _get_device(db, device_id))


@router.post("", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)
def create_device(
    payload: DeviceCreate,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    try:
        mac = validate_device_mac(payload.mac)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if db.query(Device).filter(Device.mac == mac).first():
        raise HTTPException(status_code=409, detail="A device with this MAC already exists")

    device = Device(
        mac=mac,
        label=payload.label.strip()[:128],
        notes=payload.notes.strip(),
        access_mode=payload.access_mode,
        source="manual",
        created_by=user,
        effective_access=payload.access_mode,
    )
    db.add(device)
    db.flush()
    audit.record(
        db,
        actor=user,
        action="device_added",
        target_mac=mac,
        detail={"accessMode": payload.access_mode},
        request_id=get_request_id(request),
    )
    db.commit()
    db.refresh(device)

    if payload.access_mode != "ignored":
        apply_device_now(
            db,
            service,
            get_settings(),
            device,
            actor=user,
            reason="device_created",
        )
        db.commit()
        db.refresh(device)

    return _device_response(db, device)


@router.patch("/{device_id}", response_model=DeviceOut)
def update_device(
    device_id: int,
    payload: DeviceUpdate,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    if payload.label is not None:
        device.label = payload.label.strip()[:128]
    if payload.notes is not None:
        device.notes = payload.notes.strip()

    mode_changed = False
    if payload.access_mode is not None and payload.access_mode != device.access_mode:
        previous = device.access_mode
        device.access_mode = payload.access_mode
        mode_changed = True
        if payload.access_mode != "scheduled":
            device.next_change_at = None
        audit.record(
            db,
            actor=user,
            action="access_changed",
            target_mac=device.mac,
            detail={"from": previous, "to": payload.access_mode},
            request_id=get_request_id(request),
        )
    db.commit()
    db.refresh(device)

    if mode_changed:
        if payload.apply:
            apply_device_now(
                db,
                service,
                get_settings(),
                device,
                actor=user,
                reason="manual",
            )
        else:
            refresh_device_state(device)
        db.commit()
        db.refresh(device)

    return _device_response(db, device)


@router.delete("/{device_id}")
def delete_device(
    device_id: int,
    request: Request,
    db: Session = Depends(get_db),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    if active_discipline(db, device.id) is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="End the active temporary speed limit before removing this device",
        )
    mac = device.mac
    label = device.label
    db.delete(device)
    audit.record(
        db,
        actor=user,
        action="device_removed",
        target_mac=mac,
        detail={"label": label, "note": "UniFi block state was not changed"},
        request_id=get_request_id(request),
    )
    db.commit()
    return {"ok": True, "mac": mac}


@router.post("/{device_id}/schedule", response_model=DeviceOut)
def set_schedule(
    device_id: int,
    payload: ScheduleUpdate,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    try:
        normalized = validate_schedule(payload.schedule.model_dump())
    except ScheduleError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    save_schedule(device, normalized)
    device.access_mode = "scheduled"
    audit.record(
        db,
        actor=user,
        action="schedule_set",
        target_mac=device.mac,
        detail=normalized,
        request_id=get_request_id(request),
    )
    db.commit()
    db.refresh(device)

    if payload.apply:
        apply_device_now(
            db,
            service,
            get_settings(),
            device,
            actor=user,
            reason="schedule_updated",
        )
    else:
        refresh_device_state(device)
    db.commit()
    db.refresh(device)

    return _device_response(db, device)


@router.delete("/{device_id}/schedule", response_model=DeviceOut)
def remove_schedule(
    device_id: int,
    request: Request,
    db: Session = Depends(get_db),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    clear_schedule(device)
    device.access_mode = "ignored"
    device.effective_access = "ignored"
    audit.record(
        db,
        actor=user,
        action="schedule_cleared",
        target_mac=device.mac,
        detail={"note": "Device is now unmanaged; UniFi block state was not changed"},
        request_id=get_request_id(request),
    )
    db.commit()
    db.refresh(device)
    return _device_response(db, device)


@router.post("/{device_id}/apply", response_model=DeviceOut)
def apply_now(
    device_id: int,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    try:
        apply_device_now(
            db,
            service,
            get_settings(),
            device,
            actor=user,
            reason="manual_apply",
        )
    except UniFiError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    db.commit()
    db.refresh(device)
    return _device_response(db, device)


@router.get("/{device_id}/discipline", response_model=DisciplineOut | None)
def get_discipline(device_id: int, db: Session = Depends(get_db)):
    device = _get_device(db, device_id)
    action = active_discipline(db, device.id)
    return discipline_out(action) if action else None


@router.post("/{device_id}/discipline", response_model=DeviceOut)
def start_device_discipline(
    device_id: int,
    payload: DisciplineStart,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    try:
        expires_at = compute_expiry(payload.duration_minutes, payload.expires_at)
        start_discipline(
            db,
            service,
            device,
            download_kbps=payload.download_kbps,
            upload_kbps=payload.upload_kbps,
            expires_at=expires_at,
            actor=user,
            request_id=get_request_id(request),
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except UniFiError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    db.refresh(device)
    return _device_response(db, device)


@router.delete("/{device_id}/discipline", response_model=DeviceOut)
def end_device_discipline(
    device_id: int,
    request: Request,
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
    user: str = Depends(require_user),
):
    device = _get_device(db, device_id)
    action = active_discipline(db, device.id)
    if action is None:
        raise HTTPException(status_code=404, detail="No active temporary speed limit")

    ok, error = end_discipline(
        db,
        service,
        action,
        actor=user,
        reason="manual",
        request_id=get_request_id(request),
    )
    if not ok:
        raise HTTPException(status_code=502, detail=error or "Could not restore the speed limit")

    db.refresh(device)
    return _device_response(db, device)
