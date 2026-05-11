import { useState } from "react";
import { loginWithGoogle } from "../lib/api";

type LoginPageProps = {
  status: "idle" | "connecting" | "error";
  onStart: (email: string, displayName?: string) => void;
  /** Sets the freshly-issued session token. Used by Google login path. */
  onSessionToken: (token: string) => void;
  error: string | null;
};

const isValidEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);

export function LoginPage({ status, onStart, onSessionToken, error }: LoginPageProps) {
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [googleBusy, setGoogleBusy] = useState(false);
  const [googleError, setGoogleError] = useState<string | null>(null);

  const canStart = status === "idle" || status === "error";
  const emailOk = isValidEmail(email);

  const submit = () => {
    if (canStart && emailOk) onStart(email, displayName || undefined);
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") submit();
  };

  const signInWithGoogle = async () => {
    setGoogleBusy(true);
    setGoogleError(null);
    // Pre-open a popup we control so the OAuth tab is a child window and
    // auto-close JS works. Backend serves its callback page into THIS window
    // via redirect; the success page calls window.close() which now succeeds
    // because we (same origin user gesture) opened it.
    //
    // NOTE: backend's OAuth still uses webbrowser.open() server-side which
    // creates a NEW system browser tab — not our popup. That tab can't
    // self-close cleanly. This pre-opened popup acts as a "waiting" UI for
    // the user and shows progress. The real Google tab still appears.
    const popup = window.open(
      "about:blank",
      "vera-google-signin",
      "width=500,height=620,menubar=no,toolbar=no,location=yes",
    );
    if (popup) {
      popup.document.write(`<!doctype html><html><body
        style="font-family:system-ui;text-align:center;padding:60px;background:#f7f2ec;color:#1f1d1a">
        <h2>Signing in to Google…</h2>
        <p>Complete the Google prompt in the new browser tab.</p>
        <p style="color:#6d635c">You can close this window once you see VERA load.</p>
      </body></html>`);
    }
    try {
      const res = await loginWithGoogle();
      onSessionToken(res.session_token);
      if (popup && !popup.closed) popup.close();
    } catch (err) {
      setGoogleError(err instanceof Error ? err.message : "Google sign-in failed");
      if (popup && !popup.closed) popup.close();
    } finally {
      setGoogleBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <p className="eyebrow">Voice-enabled Evolving Reasoning Assistant</p>
        <h1>VERA</h1>
        <p className="login-subhead">
          Always-on voice, proactive suggestions, and privacy-first memory controls.
        </p>

        <button
          type="button"
          className="button login-google-btn"
          onClick={signInWithGoogle}
          disabled={googleBusy || !canStart}
          title="Reuses VERA's existing Google authorization (Calendar/Gmail). First time may open a browser tab."
        >
          {googleBusy ? "Waiting for Google…" : "Sign in with Google"}
        </button>
        {googleError && <p className="error-text">{googleError}</p>}

        <div className="login-divider"><span>or</span></div>

        <div className="form-grid">
          <label>
            Email
            <input
              type="text"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              onKeyDown={handleKey}
              placeholder="you@domain.com"
              autoFocus
            />
          </label>
          <label>
            Display name
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              onKeyDown={handleKey}
              placeholder="Optional"
            />
          </label>
        </div>
        {error && <p className="error-text">{error}</p>}
        <button
          type="button"
          className="button ghost login-btn"
          onClick={submit}
          disabled={!canStart || !emailOk}
        >
          {status === "connecting" ? "Connecting…" : "Start with email"}
        </button>
      </div>
    </div>
  );
}
