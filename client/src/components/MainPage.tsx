import { useEffect, useRef, useState } from "react";
import { SettingsPanel } from "./SettingsPanel";
import { StatusPill } from "./StatusPill";
import { SuggestionsPanel } from "./SuggestionsPanel";
import type { ChatMessage } from "../lib/types";
import { useVoiceSession } from "../lib/useVoiceSession";

type MainPageProps = {
  sessionStatus: "connecting" | "active";
  sessionToken: string | null;
  ws: WebSocket | null;
  messages: ChatMessage[];
  isTyping: boolean;
  onStop: () => void;
  onSend: (text: string) => void;
  onVadStart: () => void;
  onVadEnd: () => void;
};

export function MainPage({
  sessionStatus,
  sessionToken,
  ws,
  messages,
  isTyping,
  onStop,
  onSend,
  onVadStart,
  onVadEnd,
}: MainPageProps) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [input, setInput] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);
  const sessionActive = sessionStatus === "active";

  const { status: voiceStatus, error: voiceError, isRunning, interimTranscript, start, stop } = useVoiceSession({
    onVadStart,
    onVadEnd,
    onSpeechFinal: (text) => {
      if (sessionActive) onSend(text);
    },
  });

  useEffect(() => {
    if (!sessionActive && isRunning) stop();
  }, [sessionActive, isRunning, stop]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isTyping]);

  const handleSend = () => {
    if (!input.trim()) return;
    onSend(input.trim());
    setInput("");
  };

  return (
    <div className="main-page">
      <header className="main-topbar">
        <div className="main-topbar-brand">
          <span className="topbar-eyebrow">VERA</span>
        </div>
        <div className="main-topbar-right">
          <StatusPill
            label="Session"
            value={sessionActive ? "Active" : "Connecting"}
            tone={sessionActive ? "good" : "info"}
          />
          <StatusPill
            label="Mic"
            value={voiceStatus === "speaking" ? "Speaking" : voiceStatus === "listening" ? "Listening" : "Off"}
            tone={voiceStatus === "speaking" ? "good" : "warn"}
          />
          <button
            type="button"
            className="icon-btn"
            onClick={() => setSettingsOpen(true)}
            title="Settings"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
          <button type="button" className="button ghost" onClick={onStop}>
            End session
          </button>
        </div>
      </header>

      <div className="main-chat">
        {messages.map((msg) => (
          <div className={`chat-bubble ${msg.role}`} key={msg.id}>
            <strong>{msg.role === "user" ? "You" : "VERA"}</strong>
            <p>{msg.text}</p>
          </div>
        ))}
        {isTyping && (
          <div className="chat-bubble assistant">
            <strong>VERA</strong>
            <p>···</p>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="main-voicebar">
        <div className="voice-status">
          <span className={`voice-dot ${voiceStatus}`} />
          <span>{voiceStatus.toUpperCase()}</span>
        </div>
        {interimTranscript && (
          <span className="voice-interim">"{interimTranscript}"</span>
        )}
        {voiceError && <span className="error-text">{voiceError}</span>}
        <div className="voice-bar-actions">
          <button
            type="button"
            className="button"
            onClick={() => start().catch(console.error)}
            disabled={!sessionActive || isRunning}
          >
            Start voice
          </button>
          <button type="button" className="button ghost" onClick={stop} disabled={!isRunning}>
            Stop
          </button>
        </div>
      </div>

      <div className="main-input">
        <input
          placeholder="Type a message or press the mic…"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          disabled={!sessionActive}
        />
        <button type="button" className="button" onClick={handleSend} disabled={!sessionActive}>
          Send
        </button>
      </div>

      {settingsOpen && (
        <>
          <div className="settings-backdrop" onClick={() => setSettingsOpen(false)} />
          <div className="settings-drawer">
            <div className="settings-drawer-header">
              <h2>Settings</h2>
              <button
                type="button"
                className="icon-btn"
                onClick={() => setSettingsOpen(false)}
                title="Close"
              >
                ✕
              </button>
            </div>
            <div className="settings-drawer-body">
              <SuggestionsPanel sessionToken={sessionToken} ws={ws} />
              <SettingsPanel />
            </div>
          </div>
        </>
      )}
    </div>
  );
}
