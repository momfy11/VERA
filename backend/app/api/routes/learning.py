"""Proactive learning answer endpoint.

POST /api/learning/answer
  Body: {"question_id": "<AgentSuggestion.id>", "answer": "yes" | "no"}
  Auth: X-Session-Token header

Records the user's Yes/No response to a proactive learning question, updates
memory confidence, and marks the suggestion as answered.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from backend.app.db.session import SessionLocal
from backend.app.db import models
from backend.app.services.memory import MemoryService

logger = logging.getLogger(__name__)

router = APIRouter()


class AnswerRequest(BaseModel):
    question_id: str
    answer: str  # "yes" | "no"


@router.post("/learning/answer", status_code=200)
def submit_answer(body: AnswerRequest, x_session_token: str = Header(...)):
    if body.answer not in ("yes", "no"):
        raise HTTPException(400, "answer must be 'yes' or 'no'")

    db = SessionLocal()
    try:
        # Resolve session → user
        session = (
            db.query(models.UserSession)
            .filter(
                models.UserSession.token == x_session_token,
                models.UserSession.is_active.is_(True),
            )
            .first()
        )
        if not session:
            raise HTTPException(401, "invalid session")

        user_id = str(session.user_id)

        # Find the proactive question suggestion
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
        target_confidence = float(action.get("target_confidence", 1.0 if body.answer == "yes" else 0.1))
        pattern_id = payload.get("pattern_id", "")
        category = payload.get("category", "preference")
        notification = payload.get("notification", {})
        question_text = notification.get("body", pattern_id)

        # Store / update memory item
        memory_svc = MemoryService(user_id=user_id)
        kind_map = {
            "PREFERENCE": "preference",
            "ROUTINE_AND_CONTEXT": "routine",
            "PROACTIVE_PERMISSION": "preference",
        }
        kind = kind_map.get(category, "preference")

        if target_confidence >= 0.5:
            # Confirmed — persist as real memory
            memory_svc.store(
                db,
                kind=kind,
                text=question_text,
                source="proactive_learning",
                confidence=target_confidence,
            )
            logger.info("Stored learning memory for user %s: %r (conf=%.2f)", user_id, question_text, target_confidence)
        else:
            # Denied — store low-confidence as negation marker so we don't re-ask
            memory_svc.store(
                db,
                kind=kind,
                text=f"[denied] {question_text}",
                source="proactive_learning",
                confidence=target_confidence,
            )

        # Mark suggestion answered
        suggestion.status = "answered"
        db.commit()

        return {"ok": True}
    finally:
        db.close()
