/**
 * useWakeWord — always-on Web Speech API wake-word detector.
 *
 * Free, no API key. Runs a parallel SpeechRecognition that transcribes
 * everything and fires onDetected() when transcript contains any trigger
 * phrase (case-insensitive, fuzzy whole-word match).
 *
 * Tradeoffs vs Picovoice / openWakeWord:
 * + Zero deps, no model file, no signup, works in Chrome/Edge today
 * - Google receives every audio second (web speech runs on Google servers)
 * - Internet required, no offline
 * - Chrome/Edge only (Firefox lacks SpeechRecognition)
 *
 * Trigger phrases configured via VITE_WAKE_PHRASES (comma-separated, default
 * "hey vera,vera"). Match is fuzzy: "hey, vera...", "vera!", "hey-vera" all hit.
 *
 * Important: this listener should be paused while the main voice session is
 * active (caller controls via `enabled`) — otherwise both grab the mic and
 * Chrome throws "InvalidStateError".
 */
import { useCallback, useEffect, useRef, useState } from "react";

type Status = "idle" | "listening" | "error" | "unsupported";

type WakeWordOptions = {
  enabled: boolean;
  onDetected: () => void;
  /** Override trigger phrases. Lowercase. Default: ["hey vera", "vera"] */
  phrases?: string[];
  /** Speech recognition language code. Default: "en-US". */
  lang?: string;
};

declare global {
  interface Window {
    SpeechRecognition?: new () => any;
    webkitSpeechRecognition?: new () => any;
  }
}

const SR =
  (typeof window !== "undefined" &&
    (window.SpeechRecognition || window.webkitSpeechRecognition)) ||
  null;

function loadEnvPhrases(): string[] {
  const raw = (import.meta.env.VITE_WAKE_PHRASES as string | undefined) ?? "hey vera,vera";
  return raw.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
}

function transcriptHits(transcript: string, phrases: string[]): boolean {
  // Normalise: lowercase + collapse non-alnum into single spaces. So "Hey, VERA!"
  // becomes "hey vera". Then plain substring check on a space-padded haystack
  // ensures word boundaries.
  const norm = " " + transcript.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim() + " ";
  for (const p of phrases) {
    const padded = " " + p + " ";
    if (norm.includes(padded)) return true;
  }
  return false;
}

export function useWakeWord({
  enabled,
  onDetected,
  phrases,
  lang = "en-US",
}: WakeWordOptions) {
  const [status, setStatus] = useState<Status>(SR ? "idle" : "unsupported");
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<any>(null);
  const runningRef = useRef(false);
  const errorCountRef = useRef(0);

  // Stable callback so listener isn't torn down on every parent re-render
  const onDetectedRef = useRef(onDetected);
  useEffect(() => { onDetectedRef.current = onDetected; }, [onDetected]);

  const phrasesList = phrases && phrases.length > 0 ? phrases.map((p) => p.toLowerCase()) : loadEnvPhrases();
  const phrasesRef = useRef(phrasesList);
  useEffect(() => { phrasesRef.current = phrasesList; });

  const stop = useCallback(() => {
    if (recognitionRef.current && runningRef.current) {
      try { recognitionRef.current.stop(); } catch { /* ignore */ }
    }
    runningRef.current = false;
    errorCountRef.current = 0;
    setStatus("idle");
  }, []);

  const start = useCallback(() => {
    if (!SR) {
      setStatus("unsupported");
      setError("Web Speech API not supported in this browser (use Chrome or Edge)");
      return;
    }
    if (runningRef.current) return;

    try {
      const recognition = new SR();
      recognitionRef.current = recognition;
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = lang;

      recognition.onresult = (event: any) => {
        errorCountRef.current = 0;
        // Scan only newly-finalised results to avoid retriggering on partials
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const r = event.results[i];
          const text = r[0]?.transcript ?? "";
          // For wake-word, also accept interim — faster trigger, less precise
          if (transcriptHits(text, phrasesRef.current)) {
            onDetectedRef.current();
            // Stop after detection — caller will restart us when voice session ends
            try { recognition.stop(); } catch { /* ignore */ }
            return;
          }
        }
      };

      recognition.onerror = (event: any) => {
        if (event.error === "no-speech" || event.error === "aborted") return;
        errorCountRef.current += 1;
        if (errorCountRef.current <= 3) setError(`Wake: ${event.error}`);
      };

      recognition.onend = () => {
        runningRef.current = false;
        // Auto-restart while still enabled and not too many errors in a row
        if (recognitionRef.current === recognition && errorCountRef.current < 5) {
          try {
            recognition.start();
            runningRef.current = true;
          } catch { /* will be restarted by next enable cycle */ }
        } else if (errorCountRef.current >= 5) {
          setStatus("error");
        }
      };

      recognition.start();
      runningRef.current = true;
      setStatus("listening");
      setError(null);
    } catch (err) {
      setStatus("error");
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [lang]);

  useEffect(() => {
    if (enabled) {
      start();
      return () => { stop(); };
    }
    stop();
  }, [enabled, start, stop]);

  return { status, error };
}
