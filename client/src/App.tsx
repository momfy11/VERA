import { useEffect, useState } from "react";
import { ChatPanel } from "./components/ChatPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { SessionPanel } from "./components/SessionPanel";
import { SuggestionsPanel } from "./components/SuggestionsPanel";
import { StatusPill } from "./components/StatusPill";
import { VoicePanel } from "./components/VoicePanel";
import { login } from "./lib/api";
import { ChatMessage } from "./lib/types";
import { createSessionSocket, WsMessage } from "./lib/ws";

export default function App() {
  const [sessionStatus, setSessionStatus] = useState<"idle" | "connecting" | "active" | "error">("idle");
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [voiceStatus, setVoiceStatus] = useState<"idle" | "listening" | "speaking" | "error">("idle");
  const [ws, setWs] = useState<WebSocket | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      text: "Good morning. I can help plan your focus blocks and review priorities.",
    },
  ]);

  useEffect(() => {
    if (!sessionToken) {
      return;
    }

    const socket = createSessionSocket(sessionToken);
    setWs(socket);

    socket.onopen = () => {
      setSessionStatus("active");
    };

    socket.onclose = () => {
      setSessionStatus("idle");
      setWs(null);
    };

    socket.onerror = () => {
      setSessionStatus("error");
      setSessionError("WebSocket error");
    };

    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data) as WsMessage;
        if (message.type === "assistant.text" && typeof message.payload.text === "string") {
          setMessages((prev) => [
            ...prev,
            { id: crypto.randomUUID(), role: "assistant", text: message.payload.text },
          ]);
        }
        if (message.type === "server.error" && typeof message.payload.message === "string") {
          setSessionError(message.payload.message);
        }
      } catch {
        return;
      }
    };

    return () => {
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
    ws?.close();
    setSessionToken(null);
    setSessionStatus("idle");
  };

  const handleSendText = (text: string) => {
    if (!text.trim()) {
      return;
    }

    setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: "user", text }]);

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "client.message", payload: { text } }));
    }
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

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <p className="eyebrow">Voice-enabled Evolving Reasoning Assistant</p>
          <h1>VERA Control Room</h1>
          <p className="subhead">
            Always-on voice, proactive suggestions, and privacy-first memory controls.
          </p>
        </div>
        <div className="status-stack">
          <StatusPill
            label="Session"
            value={sessionStatus === "active" ? "Active" : "Idle"}
            tone={sessionStatus === "active" ? "good" : "warn"}
          />
          <StatusPill
            label="Mic"
            value={voiceStatus === "speaking" ? "Speaking" : voiceStatus === "listening" ? "Listening" : "Off"}
            tone={voiceStatus === "speaking" ? "good" : "warn"}
          />
          <StatusPill label="Policy" value="Approval Gates" tone="info" />
        </div>
      </header>

      <main className="grid">
        <SessionPanel status={sessionStatus} onStart={handleStartSession} onStop={handleStopSession} error={sessionError} />
        <VoicePanel
          sessionActive={sessionStatus === "active"}
          onVadStart={handleVadStart}
          onVadEnd={handleVadEnd}
          onStatusChange={setVoiceStatus}
        />
        <ChatPanel messages={messages} onSend={handleSendText} disabled={sessionStatus !== "active"} />
        <SuggestionsPanel />
        <SettingsPanel />
      </main>
    </div>
  );
}
