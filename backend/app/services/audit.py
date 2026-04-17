"""Audit logging service."""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy.orm import Session

from backend.app.db import models


def log_event(
    db: Session,
    *,
    event_type: str,
    severity: str = "info",
    user_id: str | None = None,
    session_id: str | None = None,
    payload: dict | None = None,
) -> None:
    """Persist a new audit log entry."""
    payload = payload or {}
    entry = models.AuditLog(
        ts=datetime.now(timezone.utc),
        user_id=user_id,
        session_id=session_id,
        event_type=event_type,
        severity=severity,
        payload_json=payload,
    )
    db.add(entry)
    db.commit()
