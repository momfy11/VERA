import type { Suggestion, SuggestionAction } from "./types";

export type LoginResponse = {
  user_id: string;
  session_token: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? "http://localhost:8000/api";

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
