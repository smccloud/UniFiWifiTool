from __future__ import annotations

import asyncio
import contextlib
import logging
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

from app import __version__
from app.config import Settings, get_settings
from app.db import dispose_engine, get_session_factory, init_db
from app.discipline import expire_due_actions
from app.reconcile import SyncBusy, run_sync
from app.retention import purge_if_due
from app.routes import audit, auth, clients, devices, system
from app.timeutil import utcnow
from app.unifi.errors import UniFiError
from app.unifi.service import UniFiService

logger = logging.getLogger("unifi_wifi_tool")


def _sync_once(service: UniFiService, settings: Settings) -> None:
    session = get_session_factory()()
    try:
        try:
            summary = run_sync(session, service, settings, actor="scheduler")
            if summary.applied or summary.errors:
                logger.info(
                    "sync: discovered=%s updated=%s applied=%s errors=%s",
                    summary.discovered,
                    summary.updated,
                    summary.applied,
                    len(summary.errors),
                )
        except SyncBusy:
            session.rollback()
            logger.info("background sync skipped: another sync is running")
        except Exception:  # noqa: BLE001 - keep the scheduler alive
            session.rollback()
            logger.exception("background sync failed")

        # Retention is independent of UniFi availability.
        try:
            purge = purge_if_due(session)
            if purge and purge.deleted:
                logger.info("audit retention: deleted=%s old entries", purge.deleted)
        except Exception:  # noqa: BLE001 - retention must not stop the scheduler
            logger.exception("audit retention purge failed")

        # Temporary speed limits are independent of the normal sync result.
        try:
            ended = expire_due_actions(session, service)
            if ended:
                logger.info("discipline: restored %s expired speed limit(s)", ended)
        except Exception:  # noqa: BLE001 - discipline expiry must not stop the scheduler
            logger.exception("discipline expiry failed")
    finally:
        session.close()


async def _scheduler_loop(service: UniFiService, settings: Settings) -> None:
    await asyncio.sleep(3)
    while True:
        try:
            await asyncio.to_thread(_sync_once, service, settings)
        except SyncBusy:
            pass
        except Exception:  # noqa: BLE001 - keep the scheduler alive
            logger.exception("background sync failed")
        await asyncio.sleep(settings.scheduler_interval_seconds)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        init_db()
        if settings.auth_disabled:
            logger.warning("AUTH_DISABLED is enabled; anyone who can reach this port can manage WiFi access")
        if not settings.unifi_host:
            logger.warning("UNIFI_HOST is not configured")
        if settings.unifi_tls_insecure:
            logger.warning("UNIFI_TLS_INSECURE is enabled; controller TLS is not verified")
        if settings.secret_key and len(settings.secret_key) < 24:
            logger.warning("SECRET_KEY is short; generate a long random value")

        service = UniFiService(settings)
        app.state.unifi = service
        app.state.settings = settings
        task: asyncio.Task | None = None
        if settings.scheduler_enabled:
            task = asyncio.create_task(_scheduler_loop(service, settings))
        try:
            yield
        finally:
            if task is not None:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
            service.close()
            dispose_engine()

    app = FastAPI(
        title=settings.app_name,
        version=__version__,
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        redoc_url=None,
        lifespan=lifespan,
    )

    app.add_middleware(
        SessionMiddleware,
        secret_key=settings.secret_key,
        session_cookie=settings.session_cookie,
        max_age=settings.session_max_age_seconds,
        same_site="lax",
        https_only=settings.cookie_secure,
    )

    @app.middleware("http")
    async def security_middleware(request: Request, call_next):
        request.state.request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.request_id
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
        )
        if settings.cookie_secure:
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
        return response

    @app.exception_handler(UniFiError)
    async def unifi_exception_handler(request: Request, exc: UniFiError):
        logger.warning("controller error on %s: %s", request.url.path, exc)
        return JSONResponse(status_code=502, content={"detail": str(exc)})

    @app.get("/api/healthz", include_in_schema=False)
    def healthz():
        return {"status": "ok", "version": __version__, "time": utcnow().isoformat()}

    def _license_text() -> str:
        candidates = []
        if settings.license_file:
            candidates.append(Path(settings.license_file))
        candidates.extend(
            [
                Path(__file__).resolve().parents[2] / "LICENSE",
                Path("/app/LICENSE"),
            ]
        )
        for candidate in candidates:
            if candidate.is_file():
                return candidate.read_text(encoding="utf-8")
        raise HTTPException(status_code=404, detail="LICENSE file not found")

    @app.get("/api/license", include_in_schema=False)
    def license_info():
        return {"name": "MIT License", "spdx": "MIT", "text": _license_text()}

    @app.get("/LICENSE", include_in_schema=False)
    def license_text():
        return PlainTextResponse(_license_text())

    app.include_router(auth.router)
    app.include_router(devices.router)
    app.include_router(clients.router)
    app.include_router(audit.router)
    app.include_router(system.router)

    _mount_frontend(app, settings.static_path)
    return app


def _mount_frontend(app: FastAPI, static_dir: Path) -> None:
    index = static_dir / "index.html"
    if index.is_file():
        assets = static_dir / "assets"
        if assets.is_dir():
            app.mount("/assets", StaticFiles(directory=assets), name="assets")

        @app.get("/{path:path}", include_in_schema=False)
        async def spa(path: str):
            if path.startswith("api/") or path.startswith("api"):
                raise HTTPException(status_code=404, detail="Not found")
            candidate = (static_dir / path).resolve()
            if path and candidate.is_file() and static_dir in candidate.parents:
                return FileResponse(candidate)
            return FileResponse(index)

        return

    @app.get("/", include_in_schema=False)
    def placeholder():
        return {
            "message": "Frontend has not been built.",
            "hint": "Run `npm install && npm run build` in frontend/, or use the Vite dev server.",
            "apiDocs": "/api/docs",
        }


app = create_app()
