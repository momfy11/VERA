import { useCallback, useEffect, useState } from "react";
import { actOnSuggestion, fetchSuggestions } from "../lib/api";
import type { Suggestion, SuggestionAction } from "../lib/types";
import type { WsMessage } from "../lib/ws";

type SuggestionsPanelProps = {
  sessionToken: string | null;
  ws: WebSocket | null;
};

export function SuggestionsPanel({ sessionToken, ws }: SuggestionsPanelProps) {
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Load existing suggestions from REST on session start
  useEffect(() => {
    if (!sessionToken) {
      setSuggestions([]);
      return;
    }

    fetchSuggestions(sessionToken)
      .then((items) => setSuggestions(items.filter((s) => s.status === "new")))
      .catch(() => setError("Could not load suggestions"));
  }, [sessionToken]);

  // Listen for real-time agent.suggestion pushes over the open WebSocket
  useEffect(() => {
    if (!ws) return;

    const handleMessage = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(event.data as string) as WsMessage;
        if (msg.type !== "agent.suggestion") return;

        const p = msg.payload as Record<string, unknown>;
        const incoming: Suggestion = {
          id: p.id as string,
          type: p.type as string,
          priority: p.priority as number,
          title: (p.title as string | undefined) ?? (p.type as string),
          reason: (p.reason as string | undefined) ?? "",
          ts: p.ts as string,
          status: "new",
        };

        // Deduplicate by id — scheduler may fire again before UI refreshes
        setSuggestions((prev) =>
          prev.some((s) => s.id === incoming.id) ? prev : [incoming, ...prev],
        );
      } catch {
        // Non-JSON or irrelevant message — ignore
      }
    };

    ws.addEventListener("message", handleMessage);
    return () => ws.removeEventListener("message", handleMessage);
  }, [ws]);

  const handleAction = useCallback(
    async (id: string, action: SuggestionAction) => {
      if (!sessionToken) return;

      // Optimistic update
      setSuggestions((prev) => prev.filter((s) => s.id !== id));

      try {
        await actOnSuggestion(sessionToken, id, action);
      } catch {
        setError(`Could not ${action} suggestion — please try again`);
      }
    },
    [sessionToken],
  );

  return (
    <section className="panel">
      <h2>Suggestions</h2>
      <p>Proactive ideas from VERA with clear reasons and next actions.</p>

      {error && <p className="error-text">{error}</p>}

      {!sessionToken && (
        <p className="muted">Start a session to see proactive suggestions.</p>
      )}

      {sessionToken && suggestions.length === 0 && !error && (
        <p className="muted">No suggestions right now — VERA is watching.</p>
      )}

      {suggestions.map((item) => (
        <div className="suggestion" key={item.id}>
          <strong>{item.title}</strong>
          <span>{item.reason}</span>
          <div className="suggestion-actions">
            <button type="button" onClick={() => handleAction(item.id, "accepted")}>
              Accept
            </button>
            <button type="button" onClick={() => handleAction(item.id, "snoozed")}>
              Snooze 30m
            </button>
            <button type="button" onClick={() => handleAction(item.id, "rejected")}>
              Reject
            </button>
          </div>
        </div>
      ))}
    </section>
  );
}
