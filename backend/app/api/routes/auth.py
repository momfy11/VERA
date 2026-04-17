"""Auth routes for MVP."""
from __future__ import annotations

import secrets
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from backend.app.db.session import get_db
from backend.app.db import models
from backend.app.schemas.auth import LoginRequest, LoginResponse

router = APIRouter()


@router.post("/auth/login", response_model=LoginResponse)
def login(payload: LoginRequest, db: Session = Depends(get_db)) -> LoginResponse:
    """Create or fetch a user, then issue a session token."""
    user = db.query(models.User).filter(models.User.email == payload.email).first()
    if not user:
        user = models.User(email=payload.email, display_name=payload.display_name)
        db.add(user)
        db.commit()
        db.refresh(user)

    session_token = secrets.token_urlsafe(32)
    session = models.Session(
        user_id=user.id,
        started_at=datetime.now(timezone.utc),
        client_meta_json={},
        session_token=session_token,
    )
    db.add(session)
    db.commit()

    return LoginResponse(user_id=user.id, session_token=session_token)
