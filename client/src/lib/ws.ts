export type WsMessage = {
  type: string;
  payload: Record<string, unknown>;
};

export function createSessionSocket(token: string): WebSocket {
  return new WebSocket(`ws://localhost:8000/ws?token=${token}`);
}
