"""Auth routes for MVP."""
from __future__ import annotations

import secrets
import time
from collections import defaultdict, deque
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from backend.app.db.session import get_db
from backend.app.db import models
from backend.app.schemas.auth import LoginRequest, LoginResponse

router = APIRouter()

# Per-IP sliding-window rate limit on /auth/login. Brute force / enumeration guard.
_LOGIN_LIMIT = 10
_LOGIN_WINDOW_S = 60
_login_calls: dict[str, deque[float]] = defaultdict(deque)


def _login_rate_limited(ip: str) -> bool:
    now = time.monotonic()
    bucket = _login_calls[ip]
    cutoff = now - _LOGIN_WINDOW_S
    while bucket and bucket[0] < cutoff:
        bucket.popleft()
    if len(bucket) >= _LOGIN_LIMIT:
        return True
    bucket.append(now)
    return False


@router.post("/auth/login", response_model=LoginResponse)
def login(payload: LoginRequest, request: Request, db: Session = Depends(get_db)) -> LoginResponse:
    """Create or fetch a user, then issue a session token."""
    ip = request.client.host if request.client else "unknown"
    if _login_rate_limited(ip):
        raise HTTPException(status_code=429, detail="Too many login attempts — slow down")

    user = db.query(models.User).filter(models.User.email == payload.email).first()
    if not user:
        user = models.User(email=payload.email, display_name=payload.display_name)
        db.add(user)
        db.commit()
        db.refresh(user)
    elif payload.display_name and user.display_name != payload.display_name:
        user.display_name = payload.display_name
        db.commit()

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
