/**
 * useVoiceSession — VAD + Web Speech API speech-to-text
 *
 * Two parallel layers:
 *   1. WebAudio AnalyserNode  → VAD (detects speech start/end for barge-in)
 *   2. SpeechRecognition API  → STT (transcribes speech into final text)
 *
 * When the browser speaks (TTS), the VAD fires onVadStart which cancels
 * SpeechSynthesis immediately — barge-in still works.
 *
 * The caller receives onSpeechFinal(text) for every recognized utterance.
 * If the browser doesn't support SpeechRecognition, STT is silently skipped
 * and only VAD runs (same as before).
 */
import { useCallback, useEffect, useRef, useState } from "react";

export type VoiceStatus = "idle" | "listening" | "speaking" | "error";

type VoiceSessionOptions = {
  onVadStart?: () => void;
  onVadEnd?: () => void;
  onStatusChange?: (status: VoiceStatus) => void;
  onSpeechFinal?: (text: string) => void;
  /** Manual floor for VAD threshold; auto-calibrated up from noise. Default 0.04. */
  threshold?: number;
  /** Hangover before VAD declares speech-end. Default 400ms. */
  hangoverMs?: number;
  /** Sensitivity 0..1. Higher = more permissive (catches quieter speech, also more false positives). Default 0.5. */
  sensitivity?: number;
  /** Drop STT results below this Web-Speech confidence. Default 0.55. */
  confidenceThreshold?: number;
  /** Drop STT results when no VAD-active period in this window before final. Default 2000ms. */
  vadGateMs?: number;
};

type VoiceSessionState = {
  status: VoiceStatus;
  error: string | null;
  isRunning: boolean;
  interimTranscript: string;
  start: () => Promise<void>;
  stop: () => void;
  /**
   * Temporarily ignore STT results — used to prevent VERA's own TTS from
   * being transcribed and looped back as a user message.
   * Pass true when TTS starts, false (after a small grace period) when it ends.
   */
  setMuted: (muted: boolean) => void;
  /** Set sensitivity (0..1). Higher = catches quieter speech, more false positives. */
  setSensitivity: (s: number) => void;
  /** Current calibrated noise floor (RMS). Useful for showing live meter in UI. */
  noiseFloor: number;
};

// Browser SpeechRecognition shim (not yet in all TypeScript lib defs)
declare global {
  interface Window {
    SpeechRecognition?: new () => any;
    webkitSpeechRecognition?: new () => any;
  }
}

const SpeechRecognitionImpl =
  (typeof window !== "undefined" &&
    (window.SpeechRecognition || window.webkitSpeechRecognition)) ||
  null;

export function useVoiceSession(options: VoiceSessionOptions = {}): VoiceSessionState {
  const {
    onVadStart, onVadEnd, onStatusChange, onSpeechFinal,
    threshold = 0.04,
    hangoverMs = 400,
    sensitivity = 0.4,         // stricter by default — TV bleed common
    confidenceThreshold = 0.6, // drop garbled transcripts more aggressively
    vadGateMs = 1800,
  } = options;

  const [status, setStatus] = useState<VoiceStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const [interimTranscript, setInterimTranscript] = useState("");
  const [noiseFloor, setNoiseFloor] = useState(0);

  // VAD refs
  const streamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const rafIdRef = useRef<number | null>(null);
  const lastVoiceAtRef = useRef<number | null>(null);
  const speakingRef = useRef(false);
  // Effective threshold = max(manualFloor, noiseFloor * gain). Computed live.
  const noiseFloorRef = useRef(0);
  const sensitivityRef = useRef(sensitivity);
  const setSensitivity = useCallback((s: number) => {
    sensitivityRef.current = Math.max(0, Math.min(1, s));
  }, []);

  // STT refs
  const recognitionRef = useRef<any>(null);
  const sttRunningRef = useRef(false);
  const sttErrorCountRef = useRef(0);
  // Drop STT results while TTS is speaking (prevents echo feedback loop)
  const mutedRef = useRef(false);
  const setMuted = useCallback((muted: boolean) => {
    mutedRef.current = muted;
  }, []);

  const updateStatus = useCallback(
    (next: VoiceStatus) => {
      setStatus(next);
      onStatusChange?.(next);
    },
    [onStatusChange],
  );

  const stop = useCallback(() => {
    // Stop VAD
    if (rafIdRef.current) {
      cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = null;
    }
    analyserRef.current = null;
    speakingRef.current = false;
    lastVoiceAtRef.current = null;

    if (audioContextRef.current) {
      audioContextRef.current.close();
      audioContextRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }

    // Stop STT
    if (recognitionRef.current && sttRunningRef.current) {
      try { recognitionRef.current.stop(); } catch { /* ignore */ }
    }
    sttRunningRef.current = false;
    sttErrorCountRef.current = 0;
    noiseFloorRef.current = 0;
    setNoiseFloor(0);
    setInterimTranscript("");

    updateStatus("idle");
  }, [updateStatus]);

  const start = useCallback(async () => {
    if (status === "listening" || status === "speaking") return;

    try {
      setError(null);

      // ── VAD setup ────────────────────────────────────────────────────
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;

      const audioContext = new AudioContext();
      audioContextRef.current = audioContext;
      const source = audioContext.createMediaStreamSource(stream);
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 1024;
      analyserRef.current = analyser;
      source.connect(analyser);

      const buffer = new Uint8Array(analyser.fftSize);
      updateStatus("listening");

      // Noise-floor calibration window. We sample background RMS for the first
      // ~1 sec, then keep tracking it slowly while no speech to adapt to drift
      // (e.g. TV gets louder, fan kicks on). The effective VAD threshold is
      // max(manualFloor, noiseFloor * gain) where gain is set from sensitivity.
      const calibStart = performance.now();
      const CALIB_MS = 1000;
      let calibSum = 0;
      let calibCount = 0;
      let calibrated = false;
      noiseFloorRef.current = 0;

      const loop = () => {
        if (!analyserRef.current) return;
        analyserRef.current.getByteTimeDomainData(buffer);
        let sum = 0;
        for (let i = 0; i < buffer.length; i++) {
          const n = (buffer[i] - 128) / 128;
          sum += n * n;
        }
        const rms = Math.sqrt(sum / buffer.length);
        const now = performance.now();

        // ── Calibration phase: just collect, don't trigger VAD ──────────────
        if (!calibrated) {
          calibSum += rms;
          calibCount += 1;
          if (now - calibStart >= CALIB_MS) {
            const avg = calibCount > 0 ? calibSum / calibCount : 0;
            noiseFloorRef.current = avg;
            setNoiseFloor(avg);
            calibrated = true;
          } else {
            rafIdRef.current = requestAnimationFrame(loop);
            return;
          }
        }

        // Slow drift tracking when not speaking — exponential moving average
        if (!speakingRef.current) {
          noiseFloorRef.current = noiseFloorRef.current * 0.995 + rms * 0.005;
          // Don't push to state every frame — only every ~500ms to spare React
          if (Math.floor(now / 500) !== Math.floor((now - 16) / 500)) {
            setNoiseFloor(noiseFloorRef.current);
          }
        }

        // sensitivity ∈ [0..1] → gain ∈ [5..1.6]. Lower sens = stricter (higher gain).
        const s = sensitivityRef.current;
        const gain = 5 - s * 3.4;
        const effThreshold = Math.max(threshold, noiseFloorRef.current * gain);

        if (rms > effThreshold) {
          lastVoiceAtRef.current = now;
          if (!speakingRef.current) {
            speakingRef.current = true;
            speechSynthesis.cancel();
            updateStatus("speaking");
            onVadStart?.();
          }
        } else if (speakingRef.current && lastVoiceAtRef.current !== null) {
          if (now - lastVoiceAtRef.current > hangoverMs) {
            speakingRef.current = false;
            updateStatus("listening");
            onVadEnd?.();
          }
        }

        rafIdRef.current = requestAnimationFrame(loop);
      };
      rafIdRef.current = requestAnimationFrame(loop);

      // ── STT setup ────────────────────────────────────────────────────
      if (SpeechRecognitionImpl && onSpeechFinal) {
        // Defensive: if a previous recognition instance is still alive, stop it
        if (recognitionRef.current && sttRunningRef.current) {
          try { recognitionRef.current.stop(); } catch { /* ignore */ }
        }
        const recognition = new SpeechRecognitionImpl();
        recognitionRef.current = recognition;
        recognition.continuous = true;
        recognition.interimResults = true;
        recognition.lang = "en-US";

        recognition.onerror = (event: any) => {
          // "no-speech" and "aborted" are normal continuous-mode events
          if (event.error === "no-speech" || event.error === "aborted") return;
          sttErrorCountRef.current += 1;
          if (sttErrorCountRef.current <= 3) {
            setError(`Speech recognition: ${event.error}`);
          }
        };

        recognition.onresult = (event: any) => {
          sttErrorCountRef.current = 0; // reset error count on any successful result
          // Drop everything while TTS is speaking — prevents the echo feedback loop
          // where VERA's voice gets transcribed and looped back as a user message.
          if (mutedRef.current) {
            setInterimTranscript("");
            return;
          }
          let interim = "";
          for (let i = event.resultIndex; i < event.results.length; i++) {
            const result = event.results[i];
            if (result.isFinal) {
              const alt = result[0];
              const text = (alt.transcript || "").trim();
              const conf = typeof alt.confidence === "number" ? alt.confidence : 1;

              // Gate 1: VAD must have detected loud-enough speech recently.
              // Without this, the TV across the room transcribes itself.
              const lastVad = lastVoiceAtRef.current;
              const recent = lastVad !== null && performance.now() - lastVad < vadGateMs;
              if (!recent) {
                setInterimTranscript("");
                continue;
              }
              // Gate 2: confidence threshold drops noisy/garbled transcripts
              if (conf < confidenceThreshold) {
                setInterimTranscript("");
                continue;
              }
              if (text) {
                setInterimTranscript("");
                onSpeechFinal(text);
              }
            } else {
              interim += result[0].transcript;
            }
          }
          setInterimTranscript(interim);
        };

        recognition.onend = () => {
          sttRunningRef.current = false;
          // Auto-restart only while VAD is still active and error count is low
          if (analyserRef.current && sttErrorCountRef.current < 5) {
            try {
              recognition.start();
              sttRunningRef.current = true;
            } catch { /* recognition already starting */ }
          } else if (sttErrorCountRef.current >= 5) {
            setError("Speech recognition stopped after repeated errors.");
          }
        };

        recognition.start();
        sttRunningRef.current = true;
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Microphone error");
      updateStatus("error");
    }
  }, [hangoverMs, onVadEnd, onVadStart, onSpeechFinal, status, threshold, updateStatus]);

  useEffect(() => () => stop(), [stop]);

  return {
    status,
    error,
    isRunning: status === "listening" || status === "speaking",
    interimTranscript,
    start,
    stop,
    setMuted,
    setSensitivity,
    noiseFloor,
  };
}
