"""One-time Google OAuth authorization for VERA.

Run once after creating the OAuth client and adding scopes in Google Cloud Console:

    vera/Scripts/python -m backend.scripts.google_authorize

It will:
1. Open your default browser to Google's permission page
2. Save the granted token to backend/credentials/token.json
3. Subsequent VERA runs auto-refresh this token until you revoke access

If your OAuth client is type "Web application", the redirect URI
http://localhost:8089/  MUST be in the client's "Authorized redirect URIs".
Desktop app type works without that registration.
"""
from __future__ import annotations

import sys

from google_auth_oauthlib.flow import InstalledAppFlow

from backend.app.services.google_oauth import (
    OAUTH_LOCAL_PORT,
    SCOPES,
    find_client_secret,
    get_token_path,
    reset_service_cache,
)


def main() -> int:
    try:
        secret_file = find_client_secret()
    except Exception as exc:
        print(f"❌ {exc}")
        return 1

    print(f"→ Using client secret: {secret_file.name}")
    print(f"→ Requesting scopes:")
    for s in SCOPES:
        print(f"    {s}")
    print(f"→ Opening browser for authorization (callback on port {OAUTH_LOCAL_PORT})…")

    flow = InstalledAppFlow.from_client_secrets_file(str(secret_file), SCOPES)
    try:
        creds = flow.run_local_server(port=OAUTH_LOCAL_PORT, open_browser=True)
    except Exception as exc:
        print(f"\n❌ OAuth flow failed: {exc}")
        print(
            "\nIf your OAuth client is type 'Web application', add this redirect URI\n"
            f"in Google Cloud Console → Credentials → your OAuth client:\n"
            f"   http://localhost:{OAUTH_LOCAL_PORT}/\n"
            "Then re-run this script."
        )
        return 1

    token_path = get_token_path()
    token_path.parent.mkdir(parents=True, exist_ok=True)
    token_path.write_text(creds.to_json())
    reset_service_cache()

    print(f"\n✓ Authorization saved to {token_path}")
    print("✓ VERA can now use Calendar and Gmail.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
