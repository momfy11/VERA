"""Memory inspection + deletion endpoints.

Lets the user view what VERA has stored about them and remove anything
that's wrong, stale, or invasive. Soft-delete only — sets is_active=False.
"""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from backend.app.api.routes.suggestions import get_user_by_token
from backend.app.db import models
from backend.app.db.session import get_db
from backend.app.services.memory import MemoryService

router = APIRouter()


class MemoryItemResponse(BaseModel):
    id: str
    kind: str
    text: str
    ts: datetime
    source: str | None
    confidence: float


class MemoryListResponse(BaseModel):
    items: list[MemoryItemResponse]


@router.get("/memories", response_model=MemoryListResponse)
def list_memories(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> MemoryListResponse:
    """Return all active memories for the current user, newest first."""
    user = get_user_by_token(db, x_session_token)
    rows = (
        db.query(models.MemoryItem)
        .filter(
            models.MemoryItem.user_id == user.id,
            models.MemoryItem.is_active.is_(True),
        )
        .order_by(models.MemoryItem.ts.desc())
        .limit(200)
        .all()
    )
    return MemoryListResponse(items=[
        MemoryItemResponse(
            id=r.id, kind=r.kind, text=r.text, ts=r.ts,
            source=r.source, confidence=r.confidence,
        )
        for r in rows
    ])


@router.delete("/memories/{memory_id}")
def delete_memory(
    memory_id: str,
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> dict:
    """Soft-delete a memory item (is_active = False). User-owned only."""
    user = get_user_by_token(db, x_session_token)
    svc = MemoryService(user_id=user.id)
    ok = svc.deactivate(db, memory_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Memory not found")
    return {"deleted": True}
