import { useCallback, useEffect, useState } from "react";
import { deleteMemory, listMemories, type MemoryItem } from "../lib/api";

type MemoriesPanelProps = {
  sessionToken: string | null;
};

const KIND_ORDER = ["preference", "routine", "fact", "commitment", "summary"];

export function MemoriesPanel({ sessionToken }: MemoriesPanelProps) {
  const [memories, setMemories] = useState<MemoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());

  const refresh = useCallback(async () => {
    if (!sessionToken) return;
    setLoading(true);
    setError(null);
    try {
      const items = await listMemories(sessionToken);
      setMemories(items);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load memories");
    } finally {
      setLoading(false);
    }
  }, [sessionToken]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleDelete = async (id: string) => {
    if (!sessionToken) return;
    setBusyIds((prev) => new Set(prev).add(id));
    try {
      await deleteMemory(sessionToken, id);
      setMemories((prev) => prev.filter((m) => m.id !== id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Delete failed");
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  if (!sessionToken) return null;

  // Group by kind, ordered
  const byKind: Record<string, MemoryItem[]> = {};
  for (const m of memories) {
    (byKind[m.kind] = byKind[m.kind] || []).push(m);
  }
  const sortedKinds = Object.keys(byKind).sort(
    (a, b) => (KIND_ORDER.indexOf(a) + 99) - (KIND_ORDER.indexOf(b) + 99),
  );

  return (
    <section className="panel">
      <div className="panel-header-row">
        <h2>Memories</h2>
        <button type="button" className="button ghost panel-refresh" onClick={refresh} disabled={loading}>
          {loading ? "…" : "↻"}
        </button>
      </div>
      <p>Things VERA has stored about you. Delete anything wrong or stale.</p>
      {error && <p className="error-text">{error}</p>}
      {memories.length === 0 && !loading && (
        <p className="hint-text">No stored memories yet.</p>
      )}
      {sortedKinds.map((kind) => (
        <div key={kind} className="memory-group">
          <div className="memory-group-label">{kind} ({byKind[kind].length})</div>
          {byKind[kind].map((m) => (
            <div className="memory-row" key={m.id}>
              <span className="memory-text">{m.text}</span>
              <button
                type="button"
                className="memory-delete"
                onClick={() => handleDelete(m.id)}
                disabled={busyIds.has(m.id)}
                title="Delete memory"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      ))}
    </section>
  );
}
