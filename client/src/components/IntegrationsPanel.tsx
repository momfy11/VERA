import { useCallback, useEffect, useRef, useState } from "react";
import { googleConnect, googleDisconnect, googleStatus, type GoogleStatus } from "../lib/api";

type IntegrationsPanelProps = {
  sessionToken: string | null;
};

export function IntegrationsPanel({ sessionToken }: IntegrationsPanelProps) {
  const [status, setStatus] = useState<GoogleStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const pollTimerRef = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    if (!sessionToken) return;
    try {
      const s = await googleStatus(sessionToken);
      setStatus(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load status");
    }
  }, [sessionToken]);

  // Initial fetch + cleanup on unmount
  useEffect(() => {
    refresh();
    return () => {
      if (pollTimerRef.current !== null) {
        window.clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };
  }, [refresh]);

  // While the OAuth flow is mid-flight, poll status every 2 s so we update
  // the UI as soon as the user finishes (or cancels) the Google consent screen.
  useEffect(() => {
    if (status?.in_progress) {
      if (pollTimerRef.current === null) {
        pollTimerRef.current = window.setInterval(refresh, 2000);
      }
    } else if (pollTimerRef.current !== null) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, [status?.in_progress, refresh]);

  const handleConnect = async () => {
    if (!sessionToken) return;
    setError(null);
    setBusy(true);
    try {
      await googleConnect(sessionToken);
      // Server has launched the browser flow — bump status to in_progress
      setStatus((prev) => (prev ? { ...prev, in_progress: true, error: null } : prev));
      // Refresh after a short delay so the in_progress flag picks up
      setTimeout(refresh, 500);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start authorization");
    } finally {
      setBusy(false);
    }
  };

  const handleDisconnect = async () => {
    if (!sessionToken) return;
    setError(null);
    setBusy(true);
    try {
      await googleDisconnect(sessionToken);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to disconnect");
    } finally {
      setBusy(false);
    }
  };

  if (!sessionToken) {
    return null;
  }

  const connected = status?.connected ?? false;
  const inProgress = status?.in_progress ?? false;

  return (
    <section className="panel">
      <h2>Integrations</h2>
      <p>External accounts VERA can use on your behalf.</p>

      <div className="integration-row">
        <div className="integration-info">
          <div className="integration-name">
            <span className="integration-label">Google</span>
            <span className={`integration-status ${connected ? "ok" : "off"}`}>
              {inProgress ? "Authorizing…" : connected ? "Connected" : "Not connected"}
            </span>
          </div>
          {connected && status?.email && (
            <div className="integration-email">{status.email}</div>
          )}
          {!connected && !inProgress && (
            <div className="integration-hint">
              Required for Calendar and Gmail. Test-mode tokens expire weekly — reconnect when that happens.
            </div>
          )}
          {inProgress && (
            <div className="integration-hint">
              A browser tab opened — sign in and approve the requested permissions.
            </div>
          )}
        </div>

        <div className="integration-actions">
          {!connected ? (
            <button
              type="button"
              className="button"
              onClick={handleConnect}
              disabled={busy || inProgress}
            >
              {inProgress ? "Waiting…" : "Connect"}
            </button>
          ) : (
            <>
              <button
                type="button"
                className="button ghost"
                onClick={handleConnect}
                disabled={busy || inProgress}
                title="Re-authorize (use when token expires)"
              >
                Reconnect
              </button>
              <button
                type="button"
                className="button ghost"
                onClick={handleDisconnect}
                disabled={busy || inProgress}
              >
                Disconnect
              </button>
            </>
          )}
        </div>
      </div>

      {error && <p className="error-text">{error}</p>}
      {status?.error && !inProgress && !connected && (
        <p className="hint-text">{status.error}</p>
      )}
    </section>
  );
}
