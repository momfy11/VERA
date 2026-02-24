"""Suggestion routes."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

from backend.app.db import models
from backend.app.db.session import get_db
from backend.app.schemas.suggestions import SuggestionItem, SuggestionListResponse

router = APIRouter()


def get_user_by_token(db: Session, token: str | None) -> models.User:
    if not token:
        raise HTTPException(status_code=401, detail="Missing session token")

    session = db.query(models.Session).filter(models.Session.session_token == token).first()
    if not session:
        raise HTTPException(status_code=401, detail="Invalid session token")

    user = db.query(models.User).filter(models.User.id == session.user_id).first()
    if not user:
        raise HTTPException(status_code=401, detail="User not found")

    return user


@router.get("/suggestions", response_model=SuggestionListResponse)
def list_suggestions(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> SuggestionListResponse:
    """Return latest suggestions for the current user."""
    user = get_user_by_token(db, x_session_token)
    rows = (
        db.query(models.AgentSuggestion)
        .filter(models.AgentSuggestion.user_id == user.id)
        .order_by(models.AgentSuggestion.ts.desc())
        .limit(50)
        .all()
    )
    items = [
        SuggestionItem(
            id=row.id,
            ts=row.ts,
            type=row.type,
            priority=row.priority,
            payload=row.payload_json,
            status=row.status,
        )
        for row in rows
    ]
    return SuggestionListResponse(items=items)
