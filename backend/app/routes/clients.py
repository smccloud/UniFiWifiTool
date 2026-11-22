from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.auth import csrf_guard, get_current_user
from app.db import get_db
from app.deps import get_service
from app.models import Device
from app.schemas import ClientList, ClientOut, WifiNetworkOut
from app.unifi.errors import UniFiError
from app.unifi.service import UniFiService

router = APIRouter(
    prefix="/api",
    tags=["clients"],
    dependencies=[Depends(get_current_user), Depends(csrf_guard)],
)


@router.get("/clients", response_model=ClientList)
def list_clients(
    include_wired: bool = Query(default=False),
    db: Session = Depends(get_db),
    service: UniFiService = Depends(get_service),
):
    try:
        clients = service.list_clients()
    except UniFiError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    devices = {device.mac: device for device in db.query(Device).all()}
    items: list[ClientOut] = []
    for client in clients:
        if not include_wired and client.connection_type == "wired":
            continue
        device = devices.get(client.mac)
        items.append(
            ClientOut(
                mac=client.mac,
                name=client.name,
                hostname=client.hostname,
                ip_address=client.ip_address,
                ssid=client.ssid,
                signal=client.signal,
                blocked=client.blocked,
                connected=client.connected,
                connection_type=client.connection_type,
                last_seen_at=client.last_seen_at,
                source=client.source,
                device_id=device.id if device else None,
                label=device.label if device else None,
                access_mode=device.access_mode if device else None,  # type: ignore[arg-type]
                effective_access=device.effective_access if device else None,
                managed=bool(device and device.access_mode != "ignored"),
            )
        )

    items.sort(key=lambda item: ((item.label or item.name or "").lower(), item.mac))
    return ClientList(
        items=items,
        connected_total=len(items),
        managed_total=sum(1 for item in items if item.managed),
    )


@router.get("/wifi", response_model=list[WifiNetworkOut])
def list_wifi_networks(service: UniFiService = Depends(get_service)):
    try:
        networks = service.list_wifi_broadcasts()
    except UniFiError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return [WifiNetworkOut(**network) for network in networks]
