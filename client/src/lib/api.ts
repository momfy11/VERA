export type LoginResponse = {
  user_id: string;
  session_token: string;
};

const API_BASE = "http://localhost:8000/api";

export async function login(email: string, displayName?: string): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, display_name: displayName })
  });

  if (!response.ok) {
    throw new Error("Login failed");
  }

  return response.json();
}
