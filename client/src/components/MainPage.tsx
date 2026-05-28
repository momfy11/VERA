import { useCallback, useEffect, useRef, useState, type ChangeEvent } from "react";
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
import { useWakeWordServer } from "../lib/useWakeWordServer";

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
  onSend: (text: string, imageData?: string, imageMime?: string) => void;
  onVadStart: () => void;
  onVadEnd: () => void;
  onClearHistory: () => void;
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
  onClearHistory,
}: MainPageProps) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [input, setInput] = useState("");
  const [pendingImage, setPendingImage] = useState<{ data: string; mime: string; preview: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [handsFree, setHandsFree] = useState(false);
  // Mic sensitivity 0..1. Restored from localStorage so user's tuning sticks.
  const [sensitivity, setSensitivityState] = useState<number>(() => {
    if (typeof window === "undefined") return 0.5;
    const stored = window.localStorage.getItem("vera.mic_sensitivity");
    const n = stored ? Number(stored) : 0.5;
    return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0.5;
  });
  const [ttsEnabled, setTtsEnabled] = useState<boolean>(() => {
    if (typeof window === "undefined") return true;
    const s = window.localStorage.getItem("vera.tts_enabled");
    return s === null ? true : s === "true";
  });
  const [ttsRate, setTtsRate] = useState<number>(() => {
    if (typeof window === "undefined") return 1.0;
    const s = window.localStorage.getItem("vera.tts_rate");
    const n = s ? Number(s) : 1.0;
    return Number.isFinite(n) ? Math.max(0.5, Math.min(2, n)) : 1.0;
  });
  const [lang, setLangState] = useState<string>(() => {
    if (typeof window === "undefined") return "sv-SE";
    return window.localStorage.getItem("vera.lang") ?? "sv-SE";
  });
  const handleLangChange = useCallback((v: string) => {
    setLangState(v);
    window.localStorage.setItem("vera.lang", v);
  }, []);
  const handleTtsEnabled = useCallback((v: boolean) => {
    setTtsEnabled(v);
    window.localStorage.setItem("vera.tts_enabled", String(v));
  }, []);
  const handleTtsRate = useCallback((v: number) => {
    setTtsRate(v);
    window.localStorage.setItem("vera.tts_rate", String(v));
  }, []);
  const bottomRef = useRef<HTMLDivElement>(null);
  const sessionActive = sessionStatus === "active";
  const { canInstall, installed, isIOS, promptInstall } = useInstallPrompt();

  const { status: voiceStatus, error: voiceError, isRunning, interimTranscript, start, stop, setMuted, setSensitivity, noiseFloor } = useVoiceSession({
    sensitivity,
    lang,
    onVadStart,
    onVadEnd,
    onSpeechFinal: (text) => {
      if (sessionActive) onSend(text);
    },
  });

  // Speak VERA's replies while voice mode is active.
  // Mute STT during TTS so VERA's voice doesn't echo back as a user message.
  const { speak: speakNow } = useTTS({
    enabled: isRunning && ttsEnabled,
    messages,
    rate: ttsRate,
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
  const wake = useWakeWordServer({
    enabled: wakeEnabled,
    onDetected: () => {
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
    if (!input.trim() && !pendingImage) return;
    onSend(input.trim(), pendingImage?.data, pendingImage?.mime);
    setInput("");
    setPendingImage(null);
  };

  const processImageFile = useCallback((file: File) => {
    const mime = file.type || "image/jpeg";
    const img = new Image();
    const objectUrl = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(objectUrl);
      const MAX = 1024;
      const scale = Math.min(1, MAX / Math.max(img.width, img.height));
      const w = Math.round(img.width * scale);
      const h = Math.round(img.height * scale);
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      canvas.getContext("2d")!.drawImage(img, 0, 0, w, h);
      const dataUrl = canvas.toDataURL(mime, 0.82);
      const base64 = dataUrl.split(",")[1];
      setPendingImage({ data: base64, mime, preview: dataUrl });
    };
    img.src = objectUrl;
  }, []);

  const handleImagePick = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (fileInputRef.current) fileInputRef.current.value = "";
    if (file) processImageFile(file);
  };

  const handlePaste = useCallback((e: React.ClipboardEvent<HTMLInputElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of Array.from(items)) {
      if (item.type.startsWith("image/")) {
        const file = item.getAsFile();
        if (file) {
          e.preventDefault();
          processImageFile(file);
          return;
        }
      }
    }
  }, [processImageFile]);

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
          {/* Hands-free / wake word hidden — to be rebuilt in native Android app */}
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
        {pendingImage && (
          <div className="image-preview-wrap">
            <img src={pendingImage.preview} alt="Attached" className="image-preview-thumb" />
            <button
              type="button"
              className="image-preview-remove"
              onClick={() => setPendingImage(null)}
              title="Remove image"
            >✕</button>
          </div>
        )}
        <div className="main-input-row">
          <input
            type="file"
            accept="image/*"
            capture="environment"
            ref={fileInputRef}
            style={{ display: "none" }}
            onChange={handleImagePick}
          />
          <button
            type="button"
            className="icon-btn"
            onClick={() => fileInputRef.current?.click()}
            disabled={!sessionActive}
            title="Attach a photo"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
              <circle cx="12" cy="13" r="4"/>
            </svg>
          </button>
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
            onPaste={handlePaste}
            disabled={!sessionActive}
          />
          <button type="button" className="button" onClick={handleSend} disabled={!sessionActive}>
            Send
          </button>
        </div>
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
              <SettingsPanel
                ttsEnabled={ttsEnabled}
                onTtsEnabledChange={handleTtsEnabled}
                ttsRate={ttsRate}
                onTtsRateChange={handleTtsRate}
                lang={lang}
                onLangChange={handleLangChange}
                onClearHistory={onClearHistory}
              />
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
