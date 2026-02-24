import { useState } from "react";

type SessionPanelProps = {
  status: "idle" | "connecting" | "active" | "error";
  onStart: (email: string, displayName?: string) => void;
  onStop: () => void;
  error: string | null;
};

export function SessionPanel({ status, onStart, onStop, error }: SessionPanelProps) {
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");

  const canStart = status === "idle" || status === "error";

  return (
    <section className="panel">
      <h2>Session</h2>
      <p>Identify yourself to start a secure voice session.</p>
      <div className="form-grid">
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@domain.com"
          />
        </label>
        <label>
          Display name
          <input
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            placeholder="Optional"
          />
        </label>
      </div>
      {error && <p className="error-text">{error}</p>}
      <div className="panel-actions">
        <button
          type="button"
          className="button"
          onClick={() => onStart(email, displayName || undefined)}
          disabled={!canStart || !email}
        >
          {status === "connecting" ? "Connecting..." : "Start session"}
        </button>
        <button type="button" className="button ghost" onClick={onStop} disabled={status !== "active"}>
          End session
        </button>
      </div>
    </section>
  );
}
