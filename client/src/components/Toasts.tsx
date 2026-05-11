/**
 * Toasts — transient notifications that auto-dismiss after a few seconds.
 *
 * Use via `useToast()` hook. Stack appears bottom-right. Click to dismiss early.
 */
import { useCallback, useEffect, useRef, useState } from "react";

export type ToastKind = "info" | "warn" | "error" | "ok";

export type Toast = {
  id: string;
  kind: ToastKind;
  message: string;
  ttlMs: number;
};

let _push: ((t: Omit<Toast, "id">) => void) | null = null;

/** Imperative API — call from anywhere (services, hooks). */
export function pushToast(kind: ToastKind, message: string, ttlMs = 5000): void {
  if (_push) _push({ kind, message, ttlMs });
  else console.warn("[toast] no provider mounted:", kind, message);
}

export function ToastContainer() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timersRef = useRef<Map<string, number>>(new Map());

  const remove = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
    const handle = timersRef.current.get(id);
    if (handle !== undefined) {
      window.clearTimeout(handle);
      timersRef.current.delete(id);
    }
  }, []);

  // Wire global pusher on mount
  useEffect(() => {
    _push = ({ kind, message, ttlMs }) => {
      const id = crypto.randomUUID();
      setToasts((prev) => [...prev, { id, kind, message, ttlMs }]);
      const handle = window.setTimeout(() => remove(id), ttlMs);
      timersRef.current.set(id, handle);
    };
    return () => {
      _push = null;
      timersRef.current.forEach((h) => window.clearTimeout(h));
      timersRef.current.clear();
    };
  }, [remove]);

  if (toasts.length === 0) return null;

  return (
    <div className="toast-stack">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.kind}`} onClick={() => remove(t.id)}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
