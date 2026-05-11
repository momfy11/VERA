"""Auth routes for MVP."""
from __future__ import annotations

import logging
import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from backend.app.db.session import get_db
from backend.app.db import models
from backend.app.schemas.auth import LoginRequest, LoginResponse
from backend.app.services.google_oauth import (
    GoogleAuthError,
    OAUTH_LOCAL_PORT,
    SCOPES,
    fetch_user_profile,
    find_client_secret,
    get_token_path,
    gmail_service,
    load_credentials,
    reset_service_cache,
    run_oauth_with_autoclose,
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
        client_meta_json={},
        session_token=session_token,
    )
    db.add(session)
    db.commit()

    return LoginResponse(user_id=user.id, session_token=session_token)


# ---------------------------------------------------------------------------
# Sign in with Google
# ---------------------------------------------------------------------------

# Tracks the in-flight OAuth flow for /auth/google. Only one at a time.
# Threading.Event signals completion; result_holder carries email/error.
_google_auth_state = {
    "in_progress": False,
    "completed": threading.Event(),
    "email": None,  # type: str | None
    "error": None,  # type: str | None
}


def _ensure_google_profile_blocking(timeout_s: int = 90) -> dict:
    """Return Google user profile {email, name, given_name, picture, ...}.

    Fast path: existing token.json valid + has required scopes → fetch userinfo.
    Slow path: run auto-close OAuth flow (blocks up to timeout_s).

    Raises GoogleAuthError on failure / timeout / user cancel.
    """
    # Fast path: existing token
    try:
        creds = load_credentials()
        profile = fetch_user_profile(creds)
        if profile.get("email"):
            return profile
        # Token valid but profile fetch failed — likely missing scopes (old token).
        # Fall through and force re-auth so we get the right scopes.
        logger.info("Token valid but no userinfo — forcing re-auth for new scopes")
    except GoogleAuthError:
        pass

    if _google_auth_state["in_progress"]:
        raise GoogleAuthError("Another Google sign-in is already in progress")

    _google_auth_state["in_progress"] = True
    _google_auth_state["completed"].clear()
    _google_auth_state["email"] = None
    _google_auth_state["error"] = None
    profile_holder: dict = {}

    def _run_flow() -> None:
        try:
            creds = run_oauth_with_autoclose(timeout_s=timeout_s)
            token_path = get_token_path()
            token_path.parent.mkdir(parents=True, exist_ok=True)
            token_path.write_text(creds.to_json())
            reset_service_cache()
            profile = fetch_user_profile(creds)
            profile_holder.update(profile)
            _google_auth_state["email"] = profile.get("email")
        except Exception as exc:
            logger.warning("Google sign-in flow failed: %r", exc)
            _google_auth_state["error"] = str(exc)
        finally:
            _google_auth_state["in_progress"] = False
            _google_auth_state["completed"].set()

    threading.Thread(target=_run_flow, daemon=True).start()

    finished = _google_auth_state["completed"].wait(timeout=timeout_s + 5)
    if not finished:
        raise GoogleAuthError(f"Sign-in timed out after {timeout_s}s")
    if _google_auth_state["error"]:
        raise GoogleAuthError(_google_auth_state["error"])
    if not profile_holder.get("email"):
        raise GoogleAuthError("Sign-in completed but no email returned")
    return profile_holder


@router.post("/auth/google", response_model=LoginResponse)
def google_login(request: Request, db: Session = Depends(get_db)) -> LoginResponse:
    """Sign in via Google OAuth.

    Reuses the Desktop OAuth client already configured for Calendar/Gmail.
    If user has previously authorized (token.json exists), returns immediately.
    Otherwise launches the OAuth browser flow and blocks up to 60s.
    """
    ip = request.client.host if request.client else "unknown"
    if _login_rate_limited(ip):
        raise HTTPException(status_code=429, detail="Too many login attempts — slow down")

    try:
        profile = _ensure_google_profile_blocking(timeout_s=90)
    except GoogleAuthError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    email = profile["email"]
    # Prefer Google's display name; fall back through given_name → email local part
    display = (
        profile.get("name")
        or profile.get("given_name")
        or email.split("@")[0]
    )

    # Upsert user by email; refresh display_name from Google on every login so
    # it stays current even if user changes it in Google account settings.
    user = db.query(models.User).filter(models.User.email == email).first()
    if not user:
        user = models.User(email=email, display_name=display)
        db.add(user)
        db.commit()
        db.refresh(user)
    elif user.display_name != display:
        user.display_name = display
        db.commit()

    session_token = secrets.token_urlsafe(32)
    session = models.Session(
        user_id=user.id,
        started_at=datetime.now(timezone.utc),
        client_meta_json={"auth": "google"},
        session_token=session_token,
    )
    db.add(session)
    db.commit()

    return LoginResponse(user_id=user.id, session_token=session_token)
