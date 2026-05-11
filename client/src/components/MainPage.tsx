import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ApprovalModal, type PendingAction } from "./ApprovalModal";
import { IntegrationsPanel } from "./IntegrationsPanel";
import { MemoriesPanel } from "./MemoriesPanel";
import { SettingsPanel } from "./SettingsPanel";
import { StatusPill } from "./StatusPill";
import { SuggestionsPanel } from "./SuggestionsPanel";
import type { ChatMessage } from "../lib/types";
import { useInstallPrompt } from "../lib/useInstallPrompt";
import { useTTS } from "../lib/useTTS";
import { useVoiceSession } from "../lib/useVoiceSession";
import { useWakeWord } from "../lib/useWakeWord";

type MainPageProps = {
  sessionStatus: "connecting" | "active";
  sessionToken: string | null;
  ws: WebSocket | null;
  messages: ChatMessage[];
  isTyping: boolean;
  thinkingText: string | null;
  pendingAction: PendingAction | null;
  onActionResolved: () => void;
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
  thinkingText,
  pendingAction,
  onActionResolved,
  onStop,
  onSend,
  onVadStart,
  onVadEnd,
}: MainPageProps) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [input, setInput] = useState("");
  const [handsFree, setHandsFree] = useState(false);
  // Mic sensitivity 0..1. Restored from localStorage so user's tuning sticks.
  const [sensitivity, setSensitivityState] = useState<number>(() => {
    if (typeof window === "undefined") return 0.5;
    const stored = window.localStorage.getItem("vera.mic_sensitivity");
    const n = stored ? Number(stored) : 0.5;
    return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0.5;
  });
  const bottomRef = useRef<HTMLDivElement>(null);
  const sessionActive = sessionStatus === "active";
  const { canInstall, installed, isIOS, promptInstall } = useInstallPrompt();

  const { status: voiceStatus, error: voiceError, isRunning, interimTranscript, start, stop, setMuted, setSensitivity, noiseFloor } = useVoiceSession({
    sensitivity,
    onVadStart,
    onVadEnd,
    onSpeechFinal: (text) => {
      if (sessionActive) onSend(text);
    },
  });

  // Speak VERA's replies while voice mode is active.
  // Mute STT during TTS so VERA's voice doesn't echo back as a user message.
  const { speak: speakNow } = useTTS({
    enabled: isRunning,
    messages,
    onSpeakStart: () => setMuted(true),
    onSpeakEnd: () => setMuted(false),
  });

  // Speak the thinking ack the moment it arrives so the user knows VERA is
  // working before the full reply lands. Queues naturally before the reply.
  const lastThinkingRef = useRef<string | null>(null);
  useEffect(() => {
    if (!isRunning || !thinkingText) return;
    if (thinkingText === lastThinkingRef.current) return;
    lastThinkingRef.current = thinkingText;
    speakNow(thinkingText);
  }, [thinkingText, isRunning, speakNow]);

  // Wake-word listener — runs even when voice session is OFF so user can
  // hands-free trigger VERA. Disable while voice session is already active so
  // Porcupine and our STT don't fight over the mic.
  const wakeEnabled = handsFree && sessionActive && !isRunning;
  const wake = useWakeWord({
    enabled: wakeEnabled,
    onDetected: () => {
      // Start the voice session so STT picks up the user's actual command
      void start();
    },
  });

  useEffect(() => {
    if (!sessionActive && isRunning) stop();
  }, [sessionActive, isRunning, stop]);

  // Push sensitivity changes into the hook live (no restart needed)
  useEffect(() => {
    setSensitivity(sensitivity);
    window.localStorage.setItem("vera.mic_sensitivity", String(sensitivity));
  }, [sensitivity, setSensitivity]);

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
          {sessionActive && (
            <button
              type="button"
              className={`button ${handsFree ? "" : "ghost"}`}
              onClick={() => setHandsFree(!handsFree)}
              title={
                wake.status === "listening"
                  ? "Listening for wake word — say it to activate VERA"
                  : wake.status === "error"
                    ? `Wake word error: ${wake.error}`
                    : "Toggle hands-free mode (wake word)"
              }
            >
              {handsFree ? "Hands-free: on" : "Hands-free"}
              {wake.status === "listening" && " 👂"}
            </button>
          )}
          {canInstall && !installed && (
            <button
              type="button"
              className="button ghost"
              onClick={() => void promptInstall()}
              title="Install VERA as an app"
            >
              Install
            </button>
          )}
          {isIOS && !installed && (
            <span className="hint-text" title="iOS: Share → Add to Home Screen">📲</span>
          )}
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
            {msg.role === "assistant" ? (
              <div className="markdown-body">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.text}</ReactMarkdown>
              </div>
            ) : (
              <p>{msg.text}</p>
            )}
          </div>
        ))}
        {isTyping && (
          <div className="chat-bubble assistant thinking-bubble">
            <strong>VERA</strong>
            <p className="thinking-text">{thinkingText ?? "···"}</p>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="main-voicebar">
        <div className="voice-status">
          <span className={`voice-dot ${voiceStatus}`} />
          <span>{voiceStatus.toUpperCase()}</span>
        </div>
        {isRunning && (
          <label className="mic-sensitivity" title={`Mic sensitivity. Noise floor: ${noiseFloor.toFixed(3)}`}>
            <span className="hint-text">Mic</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={sensitivity}
              onChange={(e) => setSensitivityState(Number(e.target.value))}
            />
          </label>
        )}
        {handsFree && wake.status === "listening" && !isRunning && (
          <span className="hint-text">Wake word listening…</span>
        )}
        {handsFree && wake.status === "error" && (
          <span className="error-text">Wake: {wake.error}</span>
        )}
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
              <IntegrationsPanel sessionToken={sessionToken} />
              <MemoriesPanel sessionToken={sessionToken} />
              <SuggestionsPanel sessionToken={sessionToken} ws={ws} />
              <SettingsPanel />
            </div>
          </div>
        </>
      )}

      <ApprovalModal
        pending={pendingAction}
        sessionToken={sessionToken}
        onResolved={onActionResolved}
      />
    </div>
  );
}
