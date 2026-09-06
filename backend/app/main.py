"""FastAPI entrypoint for VERA backend."""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.types import ASGIApp, Receive, Scope, Send

from backend.app.core.config import settings
from backend.app.observability.logger import configure_logging
from backend.app.api.routes import actions, auth, google, health, learning, memories, suggestions
from backend.app.api.wake_word_enroll import router as wake_enroll_router
from backend.app.api.ws import websocket_endpoint
from backend.app.api.wake_ws import wake_word_endpoint
from backend.app.api.stt_ws import stt_endpoint
from backend.app.services.scheduler import proactive_scheduler

configure_logging()

logger = logging.getLogger(__name__)


def _allowed_origins() -> list[str]:
    """Parse ALLOWED_ORIGINS env var (comma-separated). Default safe.

    Use ['*'] only if explicitly set. In dev, defaults to localhost frontend.
    """
    raw = (settings.allowed_origins or "").strip()
    if not raw:
        return ["http://localhost:5173"]
    return [o.strip() for o in raw.split(",") if o.strip()]


class _NoCORSForWebSocket:
    """ASGI middleware that short-circuits WebSocket requests directly to the
    websocket_endpoint handler, completely bypassing FastAPI's routing and every
    middleware layer below.  HTTP requests still go through CORSMiddleware.

    Why direct dispatch instead of routing through FastAPI's inner app:
    Starlette's add_middleware rebuilds the middleware stack around
    ExceptionMiddleware(router), but in FastAPI ≥0.115 the WebSocketRoute
    matching inside that router silently returns NONE without calling the
    handler under certain build configurations.  Dispatching directly is the
    safest workaround.
    """

    def __init__(self, app: ASGIApp) -> None:
        origins = _allowed_origins()
        # If user explicitly opted into wildcard ('*' anywhere) preserve it,
        # otherwise tighten to whatever they listed.
        wildcard = "*" in origins
        # In development, also accept any LAN IP (so phone-on-WiFi can hit
        # http://192.168.x.y:5173) and well-known dev-tunnel hostnames so
        # we can install the PWA from a phone before deploying.
        allow_origin_regex = None
        if settings.environment.lower() == "development" and not wildcard:
            allow_origin_regex = (
                r"^https?://(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|"
                r"192\.168\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+\.\d+)"
                r"(:\d+)?$"
                r"|^https://[\w-]+\.trycloudflare\.com$"
                r"|^https://[\w-]+\.ngrok(?:-free)?\.app$"
                r"|^https://[\w-]+\.loca\.lt$"
            )
        self._http_app = CORSMiddleware(
            app,
            allow_origins=origins,
            allow_origin_regex=allow_origin_regex,
            allow_credentials=not wildcard,
            allow_methods=["*"],
            allow_headers=["*"],
        )
        logger.info("CORS allow_origins=%s regex=%s", origins, bool(allow_origin_regex))

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "websocket":
            path = scope.get("path", "")
            logger.info("WS upgrade path=%s — dispatching directly to endpoint", path)
            from fastapi import WebSocket  # noqa: PLC0415
            ws = WebSocket(scope, receive=receive, send=send)
            if path == "/ws/wake":
                await wake_word_endpoint(ws)
            elif path == "/ws/stt":
                await stt_endpoint(ws)
            else:
                await websocket_endpoint(ws)
        else:
            await self._http_app(scope, receive, send)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Start background services on startup and stop them on shutdown."""
    proactive_scheduler.start()
    yield
    proactive_scheduler.stop()


app = FastAPI(title="VERA API", version="0.1.0", lifespan=lifespan)

# Outermost middleware: bypass CORS entirely for WebSocket upgrades.
# CORSMiddleware still protects all regular HTTP endpoints.
app.add_middleware(_NoCORSForWebSocket)

app.include_router(health.router, prefix="/api")
app.include_router(auth.router, prefix="/api")
app.include_router(suggestions.router, prefix="/api")
app.include_router(google.router, prefix="/api")
app.include_router(memories.router, prefix="/api")
app.include_router(actions.router, prefix="/api")
app.include_router(wake_enroll_router, prefix="/api")
app.include_router(learning.router, prefix="/api")


@app.exception_handler(ValidationError)
async def validation_error_handler(request: Request, exc: ValidationError) -> JSONResponse:
    logging.getLogger(__name__).warning("Validation error on %s: %s", request.url, exc)
    return JSONResponse(status_code=422, content={"detail": exc.errors()})


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logging.getLogger(__name__).error("Unhandled error on %s: %r", request.url, exc, exc_info=True)
    return JSONResponse(status_code=500, content={"detail": "internal_server_error"})


# WebSocket is dispatched directly in _NoCORSForWebSocket — no route needed here.
