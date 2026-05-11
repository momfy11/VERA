import { useEffect, useState } from "react";
import { decideAction } from "../lib/api";

export type PendingAction = {
  action_id: string;
  tool: string;
  summary: string;
  args: Record<string, unknown>;
  timeout_s: number;
};

type ApprovalModalProps = {
  pending: PendingAction | null;
  sessionToken: string | null;
  onResolved: () => void;
};

export function ApprovalModal({ pending, sessionToken, onResolved }: ApprovalModalProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [secondsLeft, setSecondsLeft] = useState<number>(pending?.timeout_s ?? 60);

  // Reset countdown every time a new action arrives
  useEffect(() => {
    if (!pending) return;
    setSecondsLeft(pending.timeout_s ?? 60);
    setError(null);
    setBusy(false);
    const t = window.setInterval(() => {
      setSecondsLeft((s) => Math.max(0, s - 1));
    }, 1000);
    return () => window.clearInterval(t);
  }, [pending]);

  if (!pending) return null;

  const decide = async (decision: "approve" | "reject") => {
    if (!sessionToken) return;
    setBusy(true);
    setError(null);
    try {
      await decideAction(sessionToken, pending.action_id, decision);
      onResolved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Decision failed");
      setBusy(false);
    }
  };

  // Pretty-print args for review
  const argRows = Object.entries(pending.args).slice(0, 6);

  return (
    <>
      <div className="approval-backdrop" onClick={() => !busy && decide("reject")} />
      <div className="approval-modal" role="dialog" aria-labelledby="approval-title">
        <div className="approval-header">
          <h2 id="approval-title">VERA wants to do this</h2>
          <span className="approval-timer" title="Auto-rejects when timer runs out">{secondsLeft}s</span>
        </div>
        <p className="approval-summary">{pending.summary}</p>
        <details className="approval-details">
          <summary>Show details</summary>
          <table className="approval-args">
            <tbody>
              {argRows.map(([k, v]) => (
                <tr key={k}>
                  <th>{k}</th>
                  <td>{typeof v === "string" ? v : JSON.stringify(v)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
        {error && <p className="error-text">{error}</p>}
        <div className="approval-actions">
          <button
            type="button"
            className="button ghost"
            onClick={() => decide("reject")}
            disabled={busy}
          >
            Deny
          </button>
          <button
            type="button"
            className="button"
            onClick={() => decide("approve")}
            disabled={busy}
            autoFocus
          >
            {busy ? "…" : "Allow"}
          </button>
        </div>
      </div>
    </>
  );
}
