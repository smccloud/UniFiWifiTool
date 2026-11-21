from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import logging
import math
import secrets
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Form, HTTPException, Query, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.config import Settings, load_settings
from app.db import SessionLocal, get_db
from app.models import Install, utcnow
from app.queries import list_installs, prune_installs, summary

logger = logging.getLogger("unifi_wifi_tool.telemetry_service")
BASE_DIR = Path(__file__).resolve().parent
TEMPLATES = Jinja2Templates(directory=str(BASE_DIR / "templates"))
TEMPLATES.env.filters["dt"] = lambda value: (
    value.strftime("%Y-%m-%d %H:%M UTC") if value else "—"
)
security = HTTPBasic(auto_error=False)


class HeartbeatIn(BaseModel):
    install_id: uuid.UUID
    app_version: str = Field(
        min_length=1,
        max_length=32,
        pattern=r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$",
    )
    platform: str = Field(
        min_length=1,
        max_length=64,
        pattern=r"^[a-z0-9_.-]+/[a-z0-9_.-]+$",
    )
    deployment: str = Field(
        min_length=1,
        max_length=32,
        pattern=r"^[a-z0-9_.-]+$",
    )
    schema_version: int = Field(default=1, ge=1, le=100)


def _settings() -> Settings:
    return load_settings()


def _csrf_token(settings: Settings) -> str:
    return hmac.new(
        settings.admin_password.encode("utf-8"),
        b"telemetry-prune",
        hashlib.sha256,
    ).hexdigest()[:32]


def require_admin(
    credentials: HTTPBasicCredentials | None = Depends(security),
    settings: Settings = Depends(_settings),
) -> str:
    if credentials is None or not secrets.compare_digest(
        credentials.username, settings.admin_username
    ) or not secrets.compare_digest(credentials.password, settings.admin_password):
        raise HTTPException(
            status_code=401,
            detail="Admin authentication required",
            headers={"WWW-Authenticate": 'Basic realm="UniFi WiFi Tool telemetry"'},
        )
    return credentials.username


async def _retention_loop(settings: Settings) -> None:
    await asyncio.sleep(60)
    while True:
        db = SessionLocal()
        try:
            deleted = prune_installs(db, settings.retention_days)
            if deleted:
                logger.info("pruned %s stale installs", deleted)
        except Exception:  # noqa: BLE001 - retention must not stop the service
            logger.exception("install retention failed")
        finally:
            db.close()
        await asyncio.sleep(86_400)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    settings = load_settings()
    logging.basicConfig(
        level=getattr(logging, settings.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    logger.info("telemetry service started; client IP logging is disabled")
    retention_task = asyncio.create_task(_retention_loop(settings))
    try:
        yield
    finally:
        retention_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await retention_task
        logger.info("telemetry service stopped")


app = FastAPI(
    title="UniFi WiFi Access Manager install tracking",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; style-src 'self'; img-src 'self' data:; "
        "script-src 'self'; frame-ancestors 'none'"
    )
    return response


@app.get("/healthz", include_in_schema=False)
def healthz():
    return {"status": "ok"}


@app.post("/api/v1/heartbeat", status_code=204, include_in_schema=False)
def heartbeat(payload: HeartbeatIn, db: Session = Depends(get_db)):
    """Record an anonymous heartbeat.

    Deliberately does not accept a Request object, so this endpoint cannot
    accidentally read or log the client's IP address.
    """
    install_id = str(payload.install_id)
    now = utcnow()
    row = db.get(Install, install_id)
    if row is None:
        row = Install(
            install_id=install_id,
            first_seen=now,
            last_seen=now,
            app_version=payload.app_version,
            platform=payload.platform,
            deployment=payload.deployment,
            schema_version=payload.schema_version,
        )
        db.add(row)
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
            row = db.get(Install, install_id)
            if row is not None:
                _touch(row, payload, now)
                db.commit()
    else:
        _touch(row, payload, now)
        db.commit()

    return Response(
        status_code=204,
        headers={"Cache-Control": "no-store"},
    )


def _touch(row: Install, payload: HeartbeatIn, now) -> None:
    row.last_seen = now
    row.app_version = payload.app_version
    row.platform = payload.platform
    row.deployment = payload.deployment
    row.schema_version = payload.schema_version


@app.get("/api/v1/stats", include_in_schema=False)
def public_stats(db: Session = Depends(get_db)):
    data = summary(db)
    data["updated_at"] = data["updated_at"].isoformat() + "Z"
    return JSONResponse(data, headers={"Cache-Control": "public, max-age=60"})


@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def public_page(request: Request, db: Session = Depends(get_db)):
    stats = summary(db)
    return TEMPLATES.TemplateResponse(
        request=request,
        name="public.html",
        context={"stats": stats, "public_url": load_settings().public_url},
    )


@app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_page(
    request: Request,
    version: str = Query(default="", max_length=32),
    platform_filter: str = Query(default="", alias="platform", max_length=64),
    active: str = Query(default="", max_length=8),
    page: int = Query(default=1, ge=1),
    db: Session = Depends(get_db),
    _admin: str = Depends(require_admin),
    settings: Settings = Depends(_settings),
):
    limit = 50
    rows, total = list_installs(
        db,
        version=version,
        platform=platform_filter,
        active=active,
        limit=limit,
        offset=(page - 1) * limit,
    )
    pages = max(1, math.ceil(total / limit))
    stats = summary(db)
    return TEMPLATES.TemplateResponse(
        request=request,
        name="admin.html",
        context={
            "installs": rows,
            "total": total,
            "page": page,
            "pages": pages,
            "version_filter": version,
            "platform_filter": platform_filter,
            "active": active,
            "stats": stats,
            "retention_days": settings.retention_days,
            "csrf_token": _csrf_token(settings),
            "pruned": request.query_params.get("pruned"),
        },
    )


@app.post("/admin/prune", include_in_schema=False)
def admin_prune(
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    _admin: str = Depends(require_admin),
    settings: Settings = Depends(_settings),
):
    if not secrets.compare_digest(csrf_token, _csrf_token(settings)):
        raise HTTPException(status_code=403, detail="Invalid CSRF token")
    deleted = prune_installs(db, settings.retention_days)
    logger.info("pruned %s stale installs", deleted)
    return RedirectResponse(f"/admin?pruned={deleted}", status_code=303)
