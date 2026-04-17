"""WebSocket gateway — real-time event bus between PWA client and VERA backend.

Security model
--------------
* Token is sent as the first WebSocket message (``client.hello`` event) NOT in
  the URL so it never appears in access logs, browser history, or Referer
  headers.
* Connections that fail to send a valid hello within 10 seconds are closed.
* Per-connection rate limiting: max 60 messages per 60-second window.
* Incoming messages are capped at 4 096 characters.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from datetime import datetime, timezone

from fastapi import WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session

from backend.app.api.connection_manager import manager
from backend.app.db import models
from backend.app.db.session import SessionLocal
from backend.app.services.orchestrator import Orchestrator

# ── Security constants ──────────────────────────────────────────────────────
_HELLO_TIMEOUT_SECONDS = 10
_MAX_MESSAGE_LENGTH = 4_096
_RATE_LIMIT_MESSAGES = 60
_RATE_LIMIT_WINDOW_SECONDS = 60


# ── DB helpers ───────────────────────────────────────────────────────────────

def _get_session_from_token(db: Session, token: str | None) -> models.Session | None:
    if not token:
        return None
    return (
        db.query(models.Session)
        .filter(models.Session.session_token == token)
        .first()
    )


def _log_session_event(
    db: Session, session_id: str, event_type: str, payload: dict
) -> None:
    db.add(
        models.SessionEvent(
            session_id=session_id,
            type=event_type,
            payload_json=payload,
        )
    )
    db.commit()


# ── Rate limiter ─────────────────────────────────────────────────────────────

class _RateLimiter:
    """Simple sliding-window rate limiter per WebSocket connection."""

    def __init__(self, max_messages: int, window_seconds: int) -> None:
        self._max = max_messages
        self._window = window_seconds
        self._count = 0
        self._window_start = time.monotonic()

    def is_allowed(self) -> bool:
        now = time.monotonic()
        if now - self._window_start >= self._window:
            self._count = 0
            self._window_start = now
        self._count += 1
        return self._count <= self._max


# ── Main handler ─────────────────────────────────────────────────────────────

_ws_log = logging.getLogger(__name__)


async def websocket_endpoint(websocket: WebSocket) -> None:
    """Handle a single WebSocket connection for the lifetime of a session.

    Auth flow:
    1. Accept the raw TCP upgrade (no token in URL).
    2. Wait up to 10 s for a ``client.hello`` message containing the token.
    3. Validate token → look up user.
    4. Enter the normal message loop.
    """
    _ws_log.debug("WS endpoint reached — calling accept()")
    await websocket.accept()
    _ws_log.info("WS accepted from %s", websocket.client)

    db: Session = SessionLocal()
    user: models.User | None = None

    try:
        # ── Step 1: wait for client.hello ────────────────────────────────
        try:
            raw_hello = await asyncio.wait_for(
                websocket.receive_text(),
                timeout=_HELLO_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            await websocket.send_text(
                json.dumps({"type": "error", "payload": {"message": "hello_timeout"}})
            )
            await websocket.close(code=1008)
            return
        except WebSocketDisconnect:
            return

        try:
            hello = json.loads(raw_hello)
        except json.JSONDecodeError:
            await websocket.send_text(
                json.dumps({"type": "error", "payload": {"message": "invalid_json"}})
            )
            await websocket.close(code=1008)
            return

        if not isinstance(hello, dict) or hello.get("type") != "client.hello":
            await websocket.send_text(
                json.dumps({"type": "error", "payload": {"message": "expected_client_hello"}})
            )
            await websocket.close(code=1008)
            return

        token: str | None = (
            hello.get("payload", {}).get("token")
            if isinstance(hello.get("payload"), dict)
            else None
        )

        # ── Step 2: validate token ───────────────────────────────────────
        _ws_log.debug("WS hello received — validating token (len=%d)", len(token or ""))
        session = _get_session_from_token(db, token)
        if not session:
            await websocket.send_text(
                json.dumps({"type": "error", "payload": {"message": "unauthorized"}})
            )
            await websocket.close(code=1008)
            return

        user = db.query(models.User).filter(models.User.id == session.user_id).first()
        if not user:
            await websocket.send_text(
                json.dumps({"type": "error", "payload": {"message": "user_not_found"}})
            )
            await websocket.close(code=1008)
            return

        # ── Step 3: register connection + greet ──────────────────────────
        _ws_log.info("WS authenticated — user=%s", user.email)
        try:
            orchestrator = Orchestrator(
                db=db,
                user_id=user.id,
                display_name=user.display_name,
                session_id=session.id,
            )
        except Exception as exc:
            _ws_log.exception("Failed to initialise Orchestrator for user %s: %r", user.email, exc)
            await websocket.send_text(
                json.dumps({"type": "server.error", "payload": {"message": "backend_init_failed", "detail": str(exc)}})
            )
            await websocket.close(code=1011)
            return

        manager.connect(user.id, websocket)
        rate_limiter = _RateLimiter(_RATE_LIMIT_MESSAGES, _RATE_LIMIT_WINDOW_SECONDS)

        await websocket.send_text(
            json.dumps({
                "type": "server.hello",
                "payload": {
                    "ts": datetime.now(timezone.utc).isoformat(),
                    "display_name": user.display_name or user.email,
                },
            })
        )

        # ── Step 4: message loop ─────────────────────────────────────────
        while True:
            try:
                raw_message = await websocket.receive_text()
            except WebSocketDisconnect:
                return

            # Length guard
            if len(raw_message) > _MAX_MESSAGE_LENGTH:
                await websocket.send_text(
                    json.dumps({"type": "server.error", "payload": {"message": "message_too_long"}})
                )
                continue

            # Rate limit guard
            if not rate_limiter.is_allowed():
                await websocket.send_text(
                    json.dumps({"type": "server.error", "payload": {"message": "rate_limited"}})
                )
                continue

            try:
                data = json.loads(raw_message)
            except json.JSONDecodeError:
                await websocket.send_text(
                    json.dumps({"type": "server.error", "payload": {"message": "invalid_json"}})
                )
                continue

            if not isinstance(data, dict):
                await websocket.send_text(
                    json.dumps({"type": "server.error", "payload": {"message": "invalid_payload"}})
                )
                continue

            event_type = str(data.get("type") or "")
            payload = data.get("payload") if isinstance(data.get("payload"), dict) else {}

            if event_type:
                _log_session_event(db, session.id, event_type, payload)

            if event_type in {"client.message", "stt.final"}:
                text = payload.get("text") if isinstance(payload.get("text"), str) else ""
                if text:
                    response = await orchestrator.handle_text(text)
                    await websocket.send_text(
                        json.dumps({"type": "assistant.text", "payload": {"text": response}})
                    )

            elif event_type == "voice.vad_start":
                await websocket.send_text(
                    json.dumps({
                        "type": "assistant.tts_cancel",
                        "payload": {"ts": datetime.now(timezone.utc).isoformat()},
                    })
                )

    finally:
        if user:
            manager.disconnect(user.id, websocket)
        db.close()
