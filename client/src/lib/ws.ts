export type WsMessage = {
  type: string;
  payload: Record<string, unknown>;
};

/**
 * Open a WebSocket connection WITHOUT embedding the token in the URL.
 * The caller must send a client.hello message with the token immediately
 * after the socket opens — see App.tsx.
 */
export function createSessionSocket(): WebSocket {
  const wsBase = (import.meta.env.VITE_WS_BASE as string | undefined) ?? "ws://localhost:8000";
  return new WebSocket(`${wsBase}/ws`);
}
