"""Shared Google OAuth — load credentials, refresh token, build service clients.

One-time authorization is done by running:
    vera/Scripts/python -m backend.scripts.google_authorize

After that, this module loads the saved token and refreshes it automatically.
If the token is missing or revoked, every Google tool call returns a clear error
asking the user to re-run the auth script — no crash, no hang.
"""
from __future__ import annotations

import logging
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

logger = logging.getLogger(__name__)

# Scopes — must match what's declared in Google Cloud Console "Data Access"
SCOPES = [
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/calendar.events",
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.send",
    "https://www.googleapis.com/auth/gmail.modify",
]

# Local OAuth callback port — must be in the client's "Authorized redirect URIs"
# if your OAuth client is type "Web application" (Desktop apps don't need this).
OAUTH_LOCAL_PORT = 8089

_CREDS_DIR = Path(__file__).resolve().parent.parent.parent / "credentials"
_TOKEN_FILE = _CREDS_DIR / "token.json"


class GoogleAuthError(RuntimeError):
    """Raised when Google credentials are missing, revoked, or expired beyond refresh."""


def find_client_secret() -> Path:
    """Locate the OAuth client-secret JSON downloaded from Google Cloud Console.

    Accepts either:
      - backend/credentials/credentials.json (renamed)
      - backend/credentials/client_secret_*.json (original Google download)
    """
    configured = _CREDS_DIR / "credentials.json"
    if configured.exists():
        return configured
    candidates = list(_CREDS_DIR.glob("client_secret_*.json"))
    if candidates:
        return candidates[0]
    raise GoogleAuthError(
        f"No OAuth client secret found in {_CREDS_DIR}. "
        "Download it from Google Cloud Console → Credentials → your OAuth client → Download JSON."
    )


def get_token_path() -> Path:
    """Return the path where the user's OAuth token is cached."""
    return _TOKEN_FILE


def load_credentials() -> Credentials:
    """Load saved user credentials, refresh them if expired.

    Raises
    ------
    GoogleAuthError
        If no token exists yet (run google_authorize.py first), or if the
        refresh token has been revoked / expired beyond recovery.
    """
    if not _TOKEN_FILE.exists():
        raise GoogleAuthError(
            f"No saved Google token at {_TOKEN_FILE}. "
            "Run: vera/Scripts/python -m backend.scripts.google_authorize"
        )

    creds = Credentials.from_authorized_user_file(str(_TOKEN_FILE), SCOPES)

    if creds.expired and creds.refresh_token:
        try:
            creds.refresh(Request())
            # Persist the refreshed access token
            _TOKEN_FILE.write_text(creds.to_json())
            logger.info("Refreshed Google access token")
        except Exception as exc:
            raise GoogleAuthError(
                f"Token refresh failed ({exc}). Re-run: "
                "vera/Scripts/python -m backend.scripts.google_authorize"
            ) from exc

    if not creds.valid:
        raise GoogleAuthError(
            "Google credentials invalid. Re-run: "
            "vera/Scripts/python -m backend.scripts.google_authorize"
        )

    return creds


_calendar_cache = None
_gmail_cache = None


def calendar_service():
    """Return a cached Google Calendar v3 service client."""
    global _calendar_cache
    if _calendar_cache is None:
        creds = load_credentials()
        _calendar_cache = build("calendar", "v3", credentials=creds, cache_discovery=False)
    return _calendar_cache


def gmail_service():
    """Return a cached Gmail v1 service client."""
    global _gmail_cache
    if _gmail_cache is None:
        creds = load_credentials()
        _gmail_cache = build("gmail", "v1", credentials=creds, cache_discovery=False)
    return _gmail_cache


def reset_service_cache() -> None:
    """Force the next call to rebuild the service clients (e.g. after re-auth)."""
    global _calendar_cache, _gmail_cache
    _calendar_cache = None
    _gmail_cache = None
