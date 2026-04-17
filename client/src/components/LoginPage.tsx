import { useState } from "react";

type LoginPageProps = {
  status: "idle" | "connecting" | "error";
  onStart: (email: string, displayName?: string) => void;
  error: string | null;
};

const isValidEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);

export function LoginPage({ status, onStart, error }: LoginPageProps) {
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");

  const canStart = status === "idle" || status === "error";
  const emailOk = isValidEmail(email);

  const submit = () => {
    if (canStart && emailOk) onStart(email, displayName || undefined);
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") submit();
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <p className="eyebrow">Voice-enabled Evolving Reasoning Assistant</p>
        <h1>VERA</h1>
        <p className="login-subhead">
          Always-on voice, proactive suggestions, and privacy-first memory controls.
        </p>
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
          className="button login-btn"
          onClick={submit}
          disabled={!canStart || !emailOk}
        >
          {status === "connecting" ? "Connecting…" : "Start session"}
        </button>
      </div>
    </div>
  );
}
