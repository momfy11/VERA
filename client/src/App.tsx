import { useEffect, useRef, useState } from "react";
import { LoginPage } from "./components/LoginPage";
import { MainPage } from "./components/MainPage";
import { login } from "./lib/api";
import { initLogger } from "./lib/logger";
import type { ChatMessage } from "./lib/types";
import { createSessionSocket, WsMessage } from "./lib/ws";

// Intercept all console.error / console.warn and forward to frontend.log
initLogger();

const MAX_MESSAGE_LENGTH = 2_000;

export default function App() {
  const [sessionStatus, setSessionStatus] = useState<"idle" | "connecting" | "active" | "error">("idle");
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [ws, setWs] = useState<WebSocket | null>(null);
  const [isTyping, setIsTyping] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      text: "Good morning. I can help plan your focus blocks and review priorities.",
    },
  ]);

  // Track whether the session was closed intentionally so onclose
  // can distinguish a user-initiated stop from an unexpected disconnect.
  const intentionalCloseRef = useRef(false);

  useEffect(() => {
    if (!sessionToken) return;

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
        setSessionToken(null);
        setSessionStatus("error");
        setSessionError("Connection lost. Please start a new session.");
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
            setMessages((prev) => [
              ...prev,
              {
                id: crypto.randomUUID(),
                role: "assistant",
                text: `Hello, ${name}. I'm VERA — ready when you are.`,
              },
            ]);
          }
          return;
        }

        if (message.type === "assistant.text" && typeof message.payload.text === "string") {
          setIsTyping(false);
          setMessages((prev) => [
            ...prev,
            { id: crypto.randomUUID(), role: "assistant", text: message.payload.text as string },
          ]);
          return;
        }

        if (message.type === "server.error" && typeof message.payload.message === "string") {
          const msg = message.payload.message as string;
          setIsTyping(false);
          if (msg === "unauthorized" || msg === "hello_timeout") {
            setSessionToken(null);
            setSessionStatus("error");
            setSessionError("Session rejected. Please log in again.");
          } else if (msg === "rate_limited") {
            setSessionError("You're sending messages too fast — slow down.");
          } else {
            setSessionError(msg);
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
  }, [sessionToken]);

  const handleStartSession = async (email: string, displayName?: string) => {
    setSessionError(null);
    setSessionStatus("connecting");
    try {
      const response = await login(email, displayName);
      setSessionToken(response.session_token);
    } catch (err) {
      setSessionStatus("error");
      setSessionError(err instanceof Error ? err.message : "Login failed");
    }
  };

  const handleStopSession = () => {
    intentionalCloseRef.current = true;
    ws?.close();
    setSessionToken(null);
    setSessionStatus("idle");
    setIsTyping(false);
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

  if (sessionStatus === "idle" || sessionStatus === "error") {
    return (
      <LoginPage
        status={sessionStatus}
        onStart={handleStartSession}
        error={sessionError}
      />
    );
  }

  return (
    <MainPage
      sessionStatus={sessionStatus}
      sessionToken={sessionToken}
      ws={ws}
      messages={messages}
      isTyping={isTyping}
      onStop={handleStopSession}
      onSend={handleSendText}
      onVadStart={handleVadStart}
      onVadEnd={handleVadEnd}
    />
  );
}
