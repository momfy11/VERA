"""Proactive learning answer endpoint.

POST /api/learning/answer
  Body: {"question_id": "<AgentSuggestion.id>", "answer": "yes" | "no"}
  Auth: X-Session-Token header

Records the user's Yes/No response to a proactive learning question, updates
memory confidence, and marks the suggestion as answered.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from backend.app.api.routes.suggestions import get_user_by_token
from backend.app.db import models
from backend.app.db.session import get_db
from backend.app.services.memory import MemoryService

logger = logging.getLogger(__name__)

router = APIRouter()


class AnswerRequest(BaseModel):
    question_id: str
    answer: str  # "yes" | "no"


@router.post("/learning/answer", status_code=200)
def submit_answer(
    body: AnswerRequest,
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
    db: Session = Depends(get_db),
):
    if body.answer not in ("yes", "no"):
        raise HTTPException(400, "answer must be 'yes' or 'no'")

    user = get_user_by_token(db, x_session_token)
    user_id = str(user.id)

    suggestion = (
        db.query(models.AgentSuggestion)
        .filter(
            models.AgentSuggestion.id == body.question_id,
            models.AgentSuggestion.user_id == user_id,
            models.AgentSuggestion.type == "proactive_question",
        )
        .first()
    )
    if not suggestion:
        raise HTTPException(404, "question not found")

    payload = suggestion.payload_json or {}
    action_key = "action_yes" if body.answer == "yes" else "action_no"
    action = payload.get(action_key, {})
    try:
        target_confidence = float(action.get("target_confidence", 1.0 if body.answer == "yes" else 0.1))
    except (TypeError, ValueError):
        target_confidence = 1.0 if body.answer == "yes" else 0.1
    pattern_id = payload.get("pattern_id", "")
    category = payload.get("category", "preference")
    notification = payload.get("notification", {})
    question_text = notification.get("body", pattern_id)

    kind_map = {
        "PREFERENCE": "preference",
        "ROUTINE_AND_CONTEXT": "routine",
        "PROACTIVE_PERMISSION": "preference",
    }
    kind = kind_map.get(category, "preference")
    memory_svc = MemoryService(user_id=user_id)

    if target_confidence >= 0.5:
        memory_svc.store(db, kind=kind, text=question_text, source="proactive_learning", confidence=target_confidence)
        logger.info("Stored learning memory for user %s: %r (conf=%.2f)", user_id, question_text, target_confidence)
    else:
        memory_svc.store(db, kind=kind, text=f"[denied] {question_text}", source="proactive_learning", confidence=target_confidence)

    suggestion.status = "answered"
    db.commit()

    return {"ok": True}
