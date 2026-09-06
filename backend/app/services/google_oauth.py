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

# Scopes — must match what's declared in Google Cloud Console "Data Access".
# Added openid + userinfo.profile so /auth/google can fetch real display name.
SCOPES = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
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


MOBILE_REDIRECT_URI = "https://vera-app.hopto.org/api/google/callback"


def build_mobile_auth_url(state: str) -> str:
    """Generate the OAuth authorization URL for the Android Chrome Custom Tab flow."""
    from google_auth_oauthlib.flow import Flow  # noqa: PLC0415

    flow = Flow.from_client_secrets_file(str(find_client_secret()), scopes=SCOPES)
    flow.redirect_uri = MOBILE_REDIRECT_URI
    url, _ = flow.authorization_url(
        access_type="offline",
        prompt="consent",
        state=state,
        include_granted_scopes="true",
    )
    return url


def exchange_mobile_code(code: str) -> "Credentials":
    """Exchange an authorization code (from mobile callback) for credentials."""
    from google_auth_oauthlib.flow import Flow  # noqa: PLC0415

    flow = Flow.from_client_secrets_file(str(find_client_secret()), scopes=SCOPES)
    flow.redirect_uri = MOBILE_REDIRECT_URI
    flow.fetch_token(code=code)
    return flow.credentials


def reset_service_cache() -> None:
    """Force the next call to rebuild the service clients (e.g. after re-auth)."""
    global _calendar_cache, _gmail_cache
    _calendar_cache = None
    _gmail_cache = None


def fetch_user_profile(creds) -> dict:
    """Return the signed-in user's Google profile (id, email, name, picture).

    Requires openid + userinfo.profile scopes in the granted credentials.
    Returns {} on any failure — callers should fall back to email local-part.
    """
    try:
        import httpx
        r = httpx.get(
            "https://www.googleapis.com/oauth2/v2/userinfo",
            headers={"Authorization": f"Bearer {creds.token}"},
            timeout=8.0,
        )
        if r.status_code == 200:
            return r.json() or {}
        logger.warning("userinfo fetch returned %d: %s", r.status_code, r.text[:200])
    except Exception as exc:
        logger.warning("fetch_user_profile failed: %r", exc)
    return {}


# ---------------------------------------------------------------------------
# Custom loopback OAuth flow with auto-close success page.
#
# google_auth_oauthlib's run_local_server returns a plain "you can close this
# tab" message. We host our own callback that responds with HTML + JS that
# closes the popup automatically after 0.8 sec.
# ---------------------------------------------------------------------------

_SUCCESS_HTML = b"""<!doctype html>
<html><head><meta charset="utf-8"><title>VERA signed in</title></head>
<body style="font-family:system-ui,sans-serif;text-align:center;padding:60px 24px;background:#f7f2ec;color:#1f1d1a">
  <div style="font-size:46px;color:#2a7f62;margin-bottom:10px">&#10003;</div>
  <h2 style="margin:0 0 8px;font-weight:600">Signed in to VERA</h2>
  <p style="color:#6d635c;margin:0">You can close this tab. (It will close itself.)</p>
  <script>
    // Best effort -- only works if this tab was opened via window.open from same origin.
    // Otherwise user closes manually.
    setTimeout(() => { try { window.close(); } catch (e) {} }, 800);
  </script>
</body></html>"""

_ERROR_HTML = b"""<!doctype html>
<html><body style="font-family:system-ui;text-align:center;padding:60px;background:#f7f2ec;color:#b1442f">
<h2>Sign-in failed</h2><p>Return to VERA and try again.</p></body></html>"""


def run_oauth_with_autoclose(timeout_s: int = 120):
    """Open OAuth in browser, host a one-shot callback that auto-closes the tab.

    Returns the granted google.oauth2.credentials.Credentials.
    Raises GoogleAuthError on timeout / user-cancel / network failure.
    """
    import threading
    import time
    import webbrowser
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from urllib.parse import parse_qs, urlparse

    from google_auth_oauthlib.flow import Flow

    secret_file = find_client_secret()
    flow = Flow.from_client_secrets_file(str(secret_file), scopes=SCOPES)
    flow.redirect_uri = f"http://localhost:{OAUTH_LOCAL_PORT}/"

    # access_type=offline + prompt=consent → ensure refresh_token issued
    auth_url, _state = flow.authorization_url(
        access_type="offline",
        prompt="consent",
        include_granted_scopes="true",
    )

    result: dict = {"code": None, "error": None}

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            qs = parse_qs(urlparse(self.path).query)
            if "code" in qs:
                result["code"] = qs["code"][0]
                body = _SUCCESS_HTML
                status = 200
            elif "error" in qs:
                result["error"] = qs["error"][0]
                body = _ERROR_HTML
                status = 400
            else:
                body = b"OK"
                status = 200
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass  # silence default access log spam

    httpd = HTTPServer(("localhost", OAUTH_LOCAL_PORT), _Handler)
    server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    server_thread.start()

    try:
        webbrowser.open(auth_url)
        deadline = time.time() + timeout_s
        while not result["code"] and not result["error"] and time.time() < deadline:
            time.sleep(0.2)
    finally:
        httpd.shutdown()

    if result["error"]:
        raise GoogleAuthError(f"OAuth error: {result['error']}")
    if not result["code"]:
        raise GoogleAuthError(f"OAuth timed out after {timeout_s}s")

    flow.fetch_token(code=result["code"])
    return flow.credentials
