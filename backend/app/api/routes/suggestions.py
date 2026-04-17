"""Suggestion routes — list, and action (accept / snooze / reject)."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from backend.app.db import models
from backend.app.db.session import get_db
from backend.app.schemas.suggestions import SuggestionItem, SuggestionListResponse

router = APIRouter()

# Valid status transitions a client may request
_ALLOWED_ACTIONS = {"accepted", "rejected", "snoozed"}

# How long a snoozed suggestion is suppressed before re-appearing (minutes)
_SNOOZE_MINUTES = 30


class SuggestionActionRequest(BaseModel):
    """Body for PATCH /api/suggestions/{suggestion_id}."""

    action: str  # "accepted" | "rejected" | "snoozed"


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


@router.patch("/suggestions/{suggestion_id}", response_model=SuggestionItem)
def action_suggestion(
    suggestion_id: str,
    body: SuggestionActionRequest,
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> SuggestionItem:
    """Accept, reject, or snooze a suggestion.

    Snoozing creates a fresh copy of the suggestion scheduled to reappear
    after ``_SNOOZE_MINUTES`` minutes, and marks the original as snoozed.

    Parameters
    ----------
    suggestion_id:
        UUID of the suggestion to act on.
    body:
        JSON body with ``action`` field: ``"accepted"`` | ``"rejected"`` | ``"snoozed"``.

    Raises
    ------
    HTTPException 401:
        If the session token is invalid or missing.
    HTTPException 404:
        If the suggestion does not exist or belongs to another user.
    HTTPException 400:
        If the action value is not one of the allowed values.
    """
    user = get_user_by_token(db, x_session_token)

    if body.action not in _ALLOWED_ACTIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid action '{body.action}'. Must be one of: {sorted(_ALLOWED_ACTIONS)}",
        )

    suggestion = (
        db.query(models.AgentSuggestion)
        .filter(
            models.AgentSuggestion.id == suggestion_id,
            models.AgentSuggestion.user_id == user.id,
        )
        .first()
    )
    if not suggestion:
        raise HTTPException(status_code=404, detail="Suggestion not found")

    suggestion.status = body.action
    db.commit()

    if body.action == "snoozed":
        # Create a new suggestion to reappear after the snooze window
        snoozed_payload = dict(suggestion.payload_json)
        snoozed_payload["snoozed_from"] = suggestion.id
        reappear_at = datetime.now(timezone.utc) + timedelta(minutes=_SNOOZE_MINUTES)
        rescheduled = models.AgentSuggestion(
            user_id=user.id,
            type=suggestion.type,
            priority=suggestion.priority,
            status="new",
            payload_json={**snoozed_payload, "reappear_at": reappear_at.isoformat()},
        )
        db.add(rescheduled)
        db.commit()

    db.refresh(suggestion)
    return SuggestionItem(
        id=suggestion.id,
        ts=suggestion.ts,
        type=suggestion.type,
        priority=suggestion.priority,
        payload=suggestion.payload_json,
        status=suggestion.status,
    )
