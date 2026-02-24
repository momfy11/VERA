import { useCallback, useEffect, useRef, useState } from "react";

type VoiceStatus = "idle" | "listening" | "speaking" | "error";

type VoiceSessionOptions = {
  onVadStart?: () => void;
  onVadEnd?: () => void;
  onStatusChange?: (status: VoiceStatus) => void;
  threshold?: number;
  hangoverMs?: number;
};

type VoiceSessionState = {
  status: VoiceStatus;
  error: string | null;
  isRunning: boolean;
  start: () => Promise<void>;
  stop: () => void;
};

export function useVoiceSession(options: VoiceSessionOptions = {}): VoiceSessionState {
  const { onVadStart, onVadEnd, onStatusChange, threshold = 0.03, hangoverMs = 400 } = options;
  const [status, setStatus] = useState<VoiceStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const rafIdRef = useRef<number | null>(null);
  const lastVoiceAtRef = useRef<number | null>(null);
  const speakingRef = useRef<boolean>(false);

  const updateStatus = useCallback(
    (next: VoiceStatus) => {
      setStatus(next);
      onStatusChange?.(next);
    },
    [onStatusChange]
  );

  const stop = useCallback(() => {
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
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }

    updateStatus("idle");
  }, [updateStatus]);

  const start = useCallback(async () => {
    if (status === "listening" || status === "speaking") {
      return;
    }

    try {
      setError(null);
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

      const loop = () => {
        if (!analyserRef.current) {
          return;
        }

        analyserRef.current.getByteTimeDomainData(buffer);
        let sumSquares = 0;
        for (let i = 0; i < buffer.length; i += 1) {
          const normalized = (buffer[i] - 128) / 128;
          sumSquares += normalized * normalized;
        }
        const rms = Math.sqrt(sumSquares / buffer.length);
        const now = performance.now();

        if (rms > threshold) {
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "Microphone error");
      updateStatus("error");
    }
  }, [hangoverMs, onVadEnd, onVadStart, status, threshold, updateStatus]);

  useEffect(() => () => stop(), [stop]);

  return {
    status,
    error,
    isRunning: status === "listening" || status === "speaking",
    start,
    stop,
  };
}

export type { VoiceStatus };
