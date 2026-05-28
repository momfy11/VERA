"""Auth routes for MVP."""
from __future__ import annotations

import logging
import secrets
import time
import urllib.parse
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from backend.app.db.session import get_db
from backend.app.db import models
from backend.app.schemas.auth import LoginRequest, LoginResponse
from backend.app.services.google_oauth import (
    SCOPES,
    fetch_user_profile,
    find_client_secret,
    get_token_path,
    load_credentials,
    reset_service_cache,
)

logger = logging.getLogger(__name__)

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
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
        client_meta_json={},
        session_token=session_token,
    )
    db.add(session)
    db.commit()

    return LoginResponse(user_id=user.id, session_token=session_token)


# ---------------------------------------------------------------------------
# Sign in with Google — Web redirect flow
# ---------------------------------------------------------------------------

# CSRF state store: maps state token → {ts, code_verifier}
_oauth_states: dict[str, dict] = {}
_OAUTH_STATE_TTL_S = 600


def _callback_uri(request: Request) -> str:
    """Build the OAuth callback URI. Uses X-Forwarded-* headers when behind Caddy."""
    base = str(request.base_url).rstrip("/")
    return f"{base}/api/auth/google/callback"


def _upsert_google_user(email: str, display: str, db: Session) -> models.User:
    user = db.query(models.User).filter(models.User.email == email).first()
    if not user:
        user = models.User(email=email, display_name=display)
        db.add(user)
        db.commit()
        db.refresh(user)
    elif user.display_name != display:
        user.display_name = display
        db.commit()
    return user


@router.get("/auth/google/url")
def google_oauth_url(request: Request) -> dict:
    """Return the Google authorization URL. Frontend redirects the user there."""
    from google_auth_oauthlib.flow import Flow

    state = secrets.token_urlsafe(16)
    now = time.time()
    # Prune expired states
    for k in [k for k, v in _oauth_states.items() if now - v["ts"] > _OAUTH_STATE_TTL_S]:
        del _oauth_states[k]

    flow = Flow.from_client_secrets_file(str(find_client_secret()), scopes=SCOPES)
    flow.redirect_uri = _callback_uri(request)
    auth_url, _ = flow.authorization_url(
        access_type="offline",
        prompt="consent",
        state=state,
    )
    # Store code_verifier so callback can complete PKCE exchange
    _oauth_states[state] = {"ts": now, "code_verifier": flow.code_verifier}
    return {"url": auth_url}


@router.get("/auth/google/callback")
def google_oauth_callback(
    request: Request,
    db: Session = Depends(get_db),
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
):
    """Receive Google OAuth code, exchange for token, create session, redirect to frontend."""
    from google_auth_oauthlib.flow import Flow

    if error:
        return RedirectResponse(f"/?auth_error={urllib.parse.quote(error)}")
    if not state or state not in _oauth_states:
        return RedirectResponse("/?auth_error=invalid_state")
    if not code:
        return RedirectResponse("/?auth_error=no_code")

    state_data = _oauth_states.pop(state)

    try:
        flow = Flow.from_client_secrets_file(
            str(find_client_secret()), scopes=SCOPES, state=state
        )
        flow.redirect_uri = _callback_uri(request)
        flow.code_verifier = state_data["code_verifier"]
        flow.fetch_token(code=code)
        creds = flow.credentials

        token_path = get_token_path()
        token_path.parent.mkdir(parents=True, exist_ok=True)
        token_path.write_text(creds.to_json())
        reset_service_cache()

        profile = fetch_user_profile(creds)
        email = profile.get("email")
        if not email:
            return RedirectResponse("/?auth_error=no_email")

        display = profile.get("name") or profile.get("given_name") or email.split("@")[0]
        user = _upsert_google_user(email, display, db)

        session_token = secrets.token_urlsafe(32)
        session = models.Session(
            user_id=user.id,
            started_at=datetime.now(timezone.utc),
            expires_at=datetime.now(timezone.utc) + timedelta(days=30),
            client_meta_json={"auth": "google"},
            session_token=session_token,
        )
        db.add(session)
        db.commit()

        return RedirectResponse(f"/?token={session_token}")

    except Exception as exc:
        logger.error("Google OAuth callback failed: %r", exc)
        return RedirectResponse(f"/?auth_error={urllib.parse.quote(str(exc)[:200])}")


@router.post("/auth/logout")
def logout(
    request: Request,
    db: Session = Depends(get_db),
) -> dict:
    """Invalidate the session token so it can no longer be used."""
    token: str | None = request.headers.get("X-Session-Token")
    if not token:
        return {"ok": True}  # already logged out / no-op
    session = (
        db.query(models.Session)
        .filter(models.Session.session_token == token, models.Session.ended_at.is_(None))
        .first()
    )
    if session:
        session.ended_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True}
