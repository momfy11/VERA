"""Approve/reject pending agent actions (destructive tool calls)."""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

from backend.app.api.routes.suggestions import get_user_by_token
from backend.app.db import models
from backend.app.db.session import get_db
from backend.app.services import approval_gate

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/actions/{action_id}/{decision}")
def decide_action(
    action_id: str,
    decision: str,
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> dict:
    """Approve or reject a pending destructive action."""
    user = get_user_by_token(db, x_session_token)
    if decision not in {"approve", "reject"}:
        raise HTTPException(status_code=400, detail="decision must be 'approve' or 'reject'")

    # Action must exist and belong to this user
    action = (
        db.query(models.AgentAction)
        .filter(
            models.AgentAction.id == action_id,
            models.AgentAction.user_id == user.id,
        )
        .first()
    )
    if not action:
        raise HTTPException(status_code=404, detail="Action not found")
    if action.approval_status != "pending":
        raise HTTPException(status_code=409, detail=f"Action already {action.approval_status}")

    new_status = "approved" if decision == "approve" else "rejected"
    action.approval_status = new_status
    if decision == "approve":
        action.executed_at = datetime.now(timezone.utc)
    db.commit()

    # Wake the waiting orchestrator coroutine
    woke = approval_gate.resolve(action_id, new_status, user.id)
    if not woke:
        # Coroutine may have already timed out — DB state still useful as record
        logger.warning("approval_gate.resolve missed action_id=%s (timed out?)", action_id)

    return {"ok": True, "status": new_status}
