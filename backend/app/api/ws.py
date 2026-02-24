"""WebSocket gateway."""
from __future__ import annotations

import json
from datetime import datetime

from fastapi import WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session

from backend.app.db import models
from backend.app.db.session import SessionLocal
from backend.app.services.orchestrator import Orchestrator


def get_session_from_token(db: Session, token: str | None) -> models.Session | None:
    if not token:
        return None
    return db.query(models.Session).filter(models.Session.session_token == token).first()


def log_session_event(db: Session, session_id: str, event_type: str, payload: dict) -> None:
    entry = models.SessionEvent(
        session_id=session_id,
        type=event_type,
        payload_json=payload,
    )
    db.add(entry)
    db.commit()


async def websocket_endpoint(websocket: WebSocket) -> None:
    """Accept WebSocket connections and echo minimal events."""
    token = websocket.query_params.get("token")
    await websocket.accept()

    db = SessionLocal()
    session = get_session_from_token(db, token)
    if not session:
        await websocket.send_text(json.dumps({"type": "error", "payload": {"message": "unauthorized"}}))
        await websocket.close(code=1008)
        db.close()
        return

    user = db.query(models.User).filter(models.User.id == session.user_id).first()
    if not user:
        await websocket.send_text(json.dumps({"type": "error", "payload": {"message": "user_not_found"}}))
        await websocket.close(code=1008)
        db.close()
        return

    orchestrator = Orchestrator()

    try:
        await websocket.send_text(json.dumps({"type": "server.hello", "payload": {"ts": datetime.utcnow().isoformat()}}))
        while True:
            message = await websocket.receive_text()
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                await websocket.send_text(json.dumps({"type": "server.error", "payload": {"message": "invalid_json"}}))
                continue

            if not isinstance(data, dict):
                await websocket.send_text(json.dumps({"type": "server.error", "payload": {"message": "invalid_payload"}}))
                continue

            event_type = str(data.get("type") or "")
            payload = data.get("payload") if isinstance(data.get("payload"), dict) else {}

            if event_type:
                log_session_event(db, session.id, event_type, payload)

            if event_type in {"client.message", "stt.final"}:
                text = payload.get("text") if isinstance(payload.get("text"), str) else ""
                if text:
                    response = orchestrator.handle_text(text)
                    await websocket.send_text(
                        json.dumps({"type": "assistant.text", "payload": {"text": response}})
                    )
            elif event_type == "voice.vad_start":
                await websocket.send_text(json.dumps({"type": "assistant.tts_cancel", "payload": {"ts": datetime.utcnow().isoformat()}}))
    except WebSocketDisconnect:
        return
    finally:
        db.close()
