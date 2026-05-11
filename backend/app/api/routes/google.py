"""Google OAuth status + connect/disconnect endpoints.

Lets the frontend trigger and monitor the OAuth flow without the user
ever leaving VERA's UI. Replaces having to run google_authorize.py from CLI.
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

from backend.app.api.routes.suggestions import get_user_by_token
from backend.app.db.session import get_db
from backend.app.services.google_oauth import (
    OAUTH_LOCAL_PORT,
    SCOPES,
    GoogleAuthError,
    find_client_secret,
    get_token_path,
    load_credentials,
    reset_service_cache,
    run_oauth_with_autoclose,
)

logger = logging.getLogger(__name__)
router = APIRouter()

# Module-level state for the in-flight OAuth flow. We only allow one at a time.
_auth_state: dict[str, Any] = {
    "in_progress": False,
    "error": None,
    "completed_at": None,
}


def _get_connected_email() -> str | None:
    """Return the email of the currently connected Google account, if any."""
    try:
        creds = load_credentials()
        from googleapiclient.discovery import build  # noqa: PLC0415
        gmail = build("gmail", "v1", credentials=creds, cache_discovery=False)
        profile = gmail.users().getProfile(userId="me").execute()
        return profile.get("emailAddress")
    except Exception:
        return None


@router.get("/google/status")
def google_status(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> dict:
    """Return whether Google is connected for this VERA instance."""
    get_user_by_token(db, x_session_token)  # require VERA session

    try:
        load_credentials()
        connected = True
        error: str | None = None
    except GoogleAuthError as exc:
        connected = False
        error = str(exc)

    email = _get_connected_email() if connected else None

    return {
        "connected": connected,
        "email": email,
        "in_progress": _auth_state["in_progress"],
        "error": _auth_state["error"] or error,
    }


@router.post("/google/connect")
def google_connect(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> dict:
    """Trigger the OAuth flow in a background thread.

    Opens the user's default browser to Google's consent screen.  When the
    user finishes (or cancels), `_auth_state` is updated; the frontend polls
    `/google/status` to discover the result.
    """
    get_user_by_token(db, x_session_token)

    if _auth_state["in_progress"]:
        raise HTTPException(status_code=409, detail="Authorization already in progress")

    try:
        secret_file = find_client_secret()
    except GoogleAuthError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    _auth_state["in_progress"] = True
    _auth_state["error"] = None

    def _run_flow() -> None:
        try:
            creds = run_oauth_with_autoclose(timeout_s=120)
            token_path = get_token_path()
            token_path.parent.mkdir(parents=True, exist_ok=True)
            token_path.write_text(creds.to_json())
            reset_service_cache()
            _auth_state["completed_at"] = time.time()
            _auth_state["error"] = None
            logger.info("Google OAuth completed via /google/connect")
        except Exception as exc:
            logger.warning("Google OAuth flow failed: %r", exc)
            _auth_state["error"] = str(exc)
        finally:
            _auth_state["in_progress"] = False

    threading.Thread(target=_run_flow, daemon=True).start()
    return {"started": True}


@router.post("/google/disconnect")
def google_disconnect(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
) -> dict:
    """Delete token.json so VERA forgets the Google account."""
    get_user_by_token(db, x_session_token)

    token_path = get_token_path()
    if token_path.exists():
        token_path.unlink()
    reset_service_cache()
    return {"disconnected": True}
