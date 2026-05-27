import { useEffect, useRef, useState } from "react";
import { LoginPage } from "./components/LoginPage";
import { MainPage } from "./components/MainPage";
import { ToastContainer, pushToast } from "./components/Toasts";
import { login } from "./lib/api";
import { initLogger } from "./lib/logger";
import type { ChatMessage } from "./lib/types";
import { createSessionSocket, WsMessage } from "./lib/ws";

// Intercept all console.error / console.warn and forward to frontend.log
initLogger();

const MAX_MESSAGE_LENGTH = 2_000;
const TOKEN_STORAGE_KEY = "vera.session_token";
const MESSAGES_STORAGE_KEY = "vera.messages";
const MAX_STORED_MESSAGES = 60;

function loadMessages(): ChatMessage[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(MESSAGES_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as ChatMessage[];
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch { /* ignore */ }
  return [];
}

function buildGreeting(displayName: string): string {
  const firstName = displayName.split(/\s+/)[0];
  const name = firstName.charAt(0).toUpperCase() + firstName.slice(1).toLowerCase();
  const h = new Date().getHours();
  const tod = h < 12 ? "morning" : h < 17 ? "afternoon" : "evening";
  const pools: Record<string, string[]> = {
    morning: [
      `Good morning, ${name}. What's on the agenda?`,
      `Morning, ${name}. Ready when you are.`,
      `Good morning, ${name}. What do you need?`,
    ],
    afternoon: [
      `Good afternoon, ${name}. What can I help with?`,
      `Hey ${name}, what's on your mind?`,
      `Afternoon, ${name}. What do you need?`,
    ],
    evening: [
      `Good evening, ${name}. Still at it?`,
      `Evening, ${name}. What's on your plate?`,
      `Hey ${name}. What do you need tonight?`,
    ],
  };
  const pool = pools[tod];
  return pool[Math.floor(Math.random() * pool.length)];
}

// Handle Google OAuth callback redirect — runs once at module load.
// Google redirects to /?token=... or /?auth_error=... after sign-in.
let _oauthError: string | null = null;
if (typeof window !== "undefined") {
  const _p = new URLSearchParams(window.location.search);
  const _tok = _p.get("token");
  const _err = _p.get("auth_error");
  if (_tok) window.localStorage.setItem(TOKEN_STORAGE_KEY, _tok);
  if (_err) _oauthError = decodeURIComponent(_err);
  if (_tok || _err) window.history.replaceState({}, "", "/");
}

export default function App() {
  // Restore previous session from localStorage so closing the browser
  // doesn't force re-login — VERA is meant to feel always-on.
  const initialToken = typeof window !== "undefined"
    ? window.localStorage.getItem(TOKEN_STORAGE_KEY)
    : null;
  const [sessionStatus, setSessionStatus] = useState<"idle" | "connecting" | "active" | "error">(
    initialToken ? "connecting" : "idle",
  );
  const [sessionToken, setSessionToken] = useState<string | null>(initialToken);
  const [sessionError, setSessionError] = useState<string | null>(_oauthError);
  const [ws, setWs] = useState<WebSocket | null>(null);
  const [isTyping, setIsTyping] = useState(false);
  const [thinkingText, setThinkingText] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<import("./components/ApprovalModal").PendingAction | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>(loadMessages);

  // Persist messages so chat history survives page reloads / app restarts.
  useEffect(() => {
    window.localStorage.setItem(
      MESSAGES_STORAGE_KEY,
      JSON.stringify(messages.slice(-MAX_STORED_MESSAGES)),
    );
  }, [messages]);

  // Increment to force the socket useEffect to re-run (auto-reconnect).
  const [reconnectKey, setReconnectKey] = useState(0);
  // Track whether the session was closed intentionally so onclose
  // can distinguish a user-initiated stop from an unexpected disconnect.
  const intentionalCloseRef = useRef(false);

  useEffect(() => {
    if (!sessionToken) return;

    setSessionStatus("connecting");
    const socket = createSessionSocket();
    setWs(socket);

    socket.onopen = () => {
      socket.send(
        JSON.stringify({ type: "client.hello", payload: { token: sessionToken } })
      );
    };

    socket.onclose = () => {
      setWs(null);
      setIsTyping(false);
      if (intentionalCloseRef.current) {
        intentionalCloseRef.current = false;
        setSessionStatus("idle");
      } else {
        // Network drop / app backgrounded — keep token, reconnect after 3s
        setSessionStatus("connecting");
        setTimeout(() => setReconnectKey((k) => k + 1), 3000);
      }
    };

    socket.onerror = () => {
      setSessionError("Cannot reach server. Make sure the backend is running.");
    };

    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data as string) as WsMessage;

        if (message.type === "server.hello") {
          setSessionStatus("active");
          const name = message.payload.display_name as string | undefined;
          if (name) {
            setMessages((prev) => {
              // Only greet on first-ever session (only the welcome placeholder exists).
              // Returning users already have history — no greeting needed.
              // Only greet when no history (first ever use or after clear).
              if (prev.length > 0) return prev;
              return [
                {
                  id: crypto.randomUUID(),
                  role: "assistant" as const,
                  text: buildGreeting(name),
                },
              ];
            });
          }
          return;
        }

        if (message.type === "assistant.text" && typeof message.payload.text === "string") {
          setIsTyping(false);
          setThinkingText(null);
          setMessages((prev) => [
            ...prev,
            { id: crypto.randomUUID(), role: "assistant", text: message.payload.text as string },
          ]);
          return;
        }

        // Pre-tool ack — shown above typing indicator + spoken via TTS
        if (message.type === "assistant.thinking" && typeof message.payload.text === "string") {
          setThinkingText(message.payload.text as string);
          return;
        }

        // Destructive tool gate — show modal, user clicks Allow/Deny
        if (message.type === "agent.action_pending" && typeof message.payload.action_id === "string") {
          setPendingAction(message.payload as unknown as import("./components/ApprovalModal").PendingAction);
          return;
        }
        if (message.type === "agent.action_resolved") {
          // Backend timed out or already-resolved confirmation — close modal
          setPendingAction(null);
          return;
        }

        // Maps / open-URL events from VERA tools — open in new tab/Maps app
        if (message.type === "agent.open_url" && typeof message.payload.url === "string") {
          const url = message.payload.url as string;
          // _blank → new tab on PC, deep-link to native app on mobile
          window.open(url, "_blank", "noopener,noreferrer");
          return;
        }

        if (message.type === "server.error" && typeof message.payload.message === "string") {
          const msg = message.payload.message as string;
          setIsTyping(false);
          if (msg === "unauthorized" || msg === "hello_timeout") {
            window.localStorage.removeItem(TOKEN_STORAGE_KEY);
            setSessionToken(null);
            setSessionStatus("error");
            setSessionError("Session rejected. Please log in again.");
          } else if (msg === "rate_limited") {
            pushToast("warn", "You're sending messages too fast — slow down.");
          } else if (msg === "message_too_long") {
            pushToast("warn", "Message too long (4096 char max).");
          } else if (msg === "backend_init_failed") {
            pushToast("error", `Backend init failed: ${(message.payload as any).detail ?? ""}`);
          } else {
            // Quota / network / LLM provider errors all surface here
            pushToast("error", msg);
          }
        }
      } catch {
        // Non-JSON or malformed — ignore silently
      }
    };

    return () => {
      // Mark as intentional so onclose doesn't wipe the session token.
      // React StrictMode fires this cleanup immediately on first mount then
      // re-runs the effect — without this flag the token would be cleared.
      intentionalCloseRef.current = true;
      socket.close();
    };
  }, [sessionToken, reconnectKey]);

  const handleStartSession = async (email: string, displayName?: string) => {
    setSessionError(null);
    setSessionStatus("connecting");
    try {
      const response = await login(email, displayName);
      window.localStorage.setItem(TOKEN_STORAGE_KEY, response.session_token);
      setSessionToken(response.session_token);
    } catch (err) {
      setSessionStatus("error");
      setSessionError(err instanceof Error ? err.message : "Login failed");
    }
  };

  const handleStopSession = () => {
    intentionalCloseRef.current = true;
    window.localStorage.removeItem(TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(MESSAGES_STORAGE_KEY);
    ws?.close();
    setSessionToken(null);
    setSessionStatus("idle");
    setIsTyping(false);
    setMessages([]);
  };

  const handleSendText = (text: string) => {
    const trimmed = text.trim().slice(0, MAX_MESSAGE_LENGTH);
    if (!trimmed) return;

    setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: "user", text: trimmed }]);

    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setSessionError("Not connected — please start a new session.");
      return;
    }

    setIsTyping(true);
    ws.send(JSON.stringify({ type: "client.message", payload: { text: trimmed } }));
  };

  const handleVadStart = () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "voice.vad_start", payload: { ts: new Date().toISOString() } }));
    }
  };

  const handleVadEnd = () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "voice.vad_end", payload: { ts: new Date().toISOString() } }));
    }
  };

  const handleClearHistory = () => {
    window.localStorage.removeItem(MESSAGES_STORAGE_KEY);
    setMessages([]);
  };

  if (sessionStatus === "idle" || sessionStatus === "error") {
    return (
      <>
        <LoginPage
          status={sessionStatus}
          onStart={handleStartSession}
          onSessionToken={(token) => {
            window.localStorage.setItem(TOKEN_STORAGE_KEY, token);
            setSessionToken(token);
            setSessionStatus("connecting");
            setSessionError(null);
          }}
          error={sessionError}
        />
        <ToastContainer />
      </>
    );
  }

  return (
    <>
      <MainPage
        sessionStatus={sessionStatus}
        sessionToken={sessionToken}
        ws={ws}
        messages={messages}
        isTyping={isTyping}
        thinkingText={thinkingText}
        pendingAction={pendingAction}
        onActionResolved={() => setPendingAction(null)}
        onStop={handleStopSession}
        onSend={handleSendText}
        onVadStart={handleVadStart}
        onVadEnd={handleVadEnd}
        onClearHistory={handleClearHistory}
      />
      <ToastContainer />
    </>
  );
}
