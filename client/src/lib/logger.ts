/**
 * Client-side logger.
 *
 * Every warn/error is forwarded to POST /api/log so it lands in frontend.log.
 * Debug/info stay browser-only to keep network noise low.
 * Call `initLogger()` once at app startup to also intercept console.error/warn.
 */

const API_BASE =
  (import.meta.env.VITE_API_BASE as string | undefined) ?? "http://localhost:8000/api";

function send(level: string, message: string, source?: string): void {
  // Fire-and-forget — never throw, never block the UI
  fetch(`${API_BASE}/log`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ level, message, source }),
  }).catch(() => {/* backend unreachable — silently ignore */});
}

export const logger = {
  debug: (msg: string, source?: string) => console.debug(`[VERA] ${msg}`),
  info:  (msg: string, source?: string) => { console.info(`[VERA] ${msg}`); send("info", msg, source); },
  warn:  (msg: string, source?: string) => { console.warn(`[VERA] ${msg}`);  send("warn", msg, source); },
  error: (msg: string, source?: string) => { console.error(`[VERA] ${msg}`); send("error", msg, source); },
};

/**
 * Monkey-patch console.error and console.warn so all browser errors —
 * including WebSocket failures — are automatically forwarded to frontend.log.
 */
export function initLogger(): void {
  const origError = console.error.bind(console);
  const origWarn  = console.warn.bind(console);

  console.error = (...args: unknown[]) => {
    origError(...args);
    const msg = args.map(String).join(" ");
    send("error", msg);
  };

  console.warn = (...args: unknown[]) => {
    origWarn(...args);
    const msg = args.map(String).join(" ");
    send("warn", msg);
  };
}
