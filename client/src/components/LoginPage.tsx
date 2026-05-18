import { useState } from "react";
import { getGoogleAuthUrl } from "../lib/api";

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
    try {
      const url = await getGoogleAuthUrl();
      window.location.href = url;
      // Page navigates away — no need to reset busy state
    } catch (err) {
      setGoogleError(err instanceof Error ? err.message : "Google sign-in failed");
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
