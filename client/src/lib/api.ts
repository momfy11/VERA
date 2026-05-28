import type { Suggestion, SuggestionAction } from "./types";

export type LoginResponse = {
  user_id: string;
  session_token: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? "http://localhost:8000/api";

export async function getGoogleAuthUrl(): Promise<string> {
  const response = await fetch(`${API_BASE}/auth/google/url`);
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.detail || "Could not get Google sign-in URL");
  }
  const data = await response.json();
  return data.url as string;
}

export async function login(email: string, displayName?: string): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, display_name: displayName }),
  });

  if (!response.ok) {
    throw new Error("Login failed");
  }

  return response.json();
}

export async function fetchSuggestions(sessionToken: string): Promise<Suggestion[]> {
  const response = await fetch(`${API_BASE}/suggestions`, {
    headers: { "X-Session-Token": sessionToken },
  });

  if (!response.ok) {
    throw new Error("Failed to fetch suggestions");
  }

  const data = await response.json();
  return (data.items as Array<Record<string, unknown>>).map(normaliseSuggestion);
}

export type GoogleStatus = {
  connected: boolean;
  email: string | null;
  in_progress: boolean;
  error: string | null;
};

export async function googleStatus(sessionToken: string): Promise<GoogleStatus> {
  const response = await fetch(`${API_BASE}/google/status`, {
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) throw new Error("Failed to fetch Google status");
  return response.json();
}

export async function googleConnect(sessionToken: string): Promise<void> {
  const response = await fetch(`${API_BASE}/google/connect`, {
    method: "POST",
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.detail || "Could not start Google authorization");
  }
}

export async function decideAction(
  sessionToken: string,
  actionId: string,
  decision: "approve" | "reject",
): Promise<void> {
  const response = await fetch(`${API_BASE}/actions/${actionId}/${decision}`, {
    method: "POST",
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.detail || "Decision failed");
  }
}

export type MemoryItem = {
  id: string;
  kind: string;
  text: string;
  ts: string;
  source: string | null;
  confidence: number;
};

export async function listMemories(sessionToken: string): Promise<MemoryItem[]> {
  const response = await fetch(`${API_BASE}/memories`, {
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) throw new Error("Failed to fetch memories");
  const data = await response.json();
  return data.items as MemoryItem[];
}

export async function deleteMemory(sessionToken: string, memoryId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/memories/${memoryId}`, {
    method: "DELETE",
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) throw new Error("Failed to delete memory");
}

export async function logout(sessionToken: string): Promise<void> {
  await fetch(`${API_BASE}/auth/logout`, {
    method: "POST",
    headers: { "X-Session-Token": sessionToken },
  }).catch(() => {/* best-effort — token removed from client regardless */});
}

export async function googleDisconnect(sessionToken: string): Promise<void> {
  const response = await fetch(`${API_BASE}/google/disconnect`, {
    method: "POST",
    headers: { "X-Session-Token": sessionToken },
  });
  if (!response.ok) throw new Error("Could not disconnect Google");
}

export async function actOnSuggestion(
  sessionToken: string,
  suggestionId: string,
  action: SuggestionAction,
): Promise<void> {
  const response = await fetch(`${API_BASE}/suggestions/${suggestionId}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      "X-Session-Token": sessionToken,
    },
    body: JSON.stringify({ action }),
  });

  if (!response.ok) {
    throw new Error(`Action '${action}' failed`);
  }
}

// The REST API returns `payload.title` / `payload.reason` nested inside payload_json.
// This normaliser flattens it to match the Suggestion type.
function normaliseSuggestion(raw: Record<string, unknown>): Suggestion {
  const payload = (raw.payload ?? {}) as Record<string, unknown>;
  return {
    id: raw.id as string,
    type: raw.type as string,
    priority: raw.priority as number,
    title: (payload.title as string | undefined) ?? (raw.type as string),
    reason: (payload.reason as string | undefined) ?? "",
    ts: raw.ts as string,
    status: raw.status as Suggestion["status"],
  };
}
