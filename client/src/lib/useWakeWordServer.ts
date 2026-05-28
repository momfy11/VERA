/**
 * useWakeWordServer — server-side wake word detection via faster-whisper.
 *
 * Streams 16kHz int16 PCM to /ws/wake over WebSocket.
 * Server buffers 1.5s, transcribes with Whisper tiny, fires onDetected()
 * when "hey vera" / "vera" (or VITE_WAKE_PHRASES) matches.
 *
 * Advantages over Web Speech API:
 *   + Works on all browsers including Firefox and iOS Safari
 *   + No Google STT — fully private
 *   + Offline (after model download on first server start)
 */
import { useCallback, useEffect, useRef, useState } from "react";

const WS_BASE = (import.meta.env.VITE_WS_BASE as string | undefined) ?? "ws://localhost:8000";
const TARGET_RATE = 16000;

export type WakeServerStatus = "idle" | "connecting" | "listening" | "error";

type Options = {
  enabled: boolean;
  onDetected: () => void;
};

/** Downsample Float32 audio from browser rate to 16kHz, return Int16 PCM. */
function downsample(input: Float32Array, inputRate: number): Int16Array {
  const ratio = inputRate / TARGET_RATE;
  const len = Math.floor(input.length / ratio);
  const out = new Int16Array(len);
  for (let i = 0; i < len; i++) {
    const s = input[Math.floor(i * ratio)];
    out[i] = Math.max(-32768, Math.min(32767, s * 32768));
  }
  return out;
}

export function useWakeWordServer({ enabled, onDetected }: Options) {
  const [status, setStatus] = useState<WakeServerStatus>("idle");
  const wsRef = useRef<WebSocket | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const onDetectedRef = useRef(onDetected);
  useEffect(() => { onDetectedRef.current = onDetected; }, [onDetected]);
  const enabledRef = useRef(enabled);
  useEffect(() => { enabledRef.current = enabled; }, [enabled]);

  const stop = useCallback(() => {
    processorRef.current?.disconnect();
    processorRef.current = null;
    ctxRef.current?.close().catch(() => {});
    ctxRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (wsRef.current) {
      wsRef.current.onclose = null;
      wsRef.current.close(1000);
      wsRef.current = null;
    }
    setStatus("idle");
  }, []);

  const start = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setStatus("error");
      return;
    }
    setStatus("connecting");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;

      const ws = new WebSocket(`${WS_BASE}/ws/wake`);
      wsRef.current = ws;
      ws.binaryType = "arraybuffer";

      ws.onopen = () => {
        const ctx = new AudioContext();
        ctxRef.current = ctx;
        const source = ctx.createMediaStreamSource(stream);
        // 4096-sample buffer → ~93ms at 44.1kHz, sent to server at 16kHz
        const processor = ctx.createScriptProcessor(4096, 1, 1);
        processorRef.current = processor;

        let pending = new Int16Array(0);

        processor.onaudioprocess = (e) => {
          if (ws.readyState !== WebSocket.OPEN) return;
          const downsampled = downsample(e.inputBuffer.getChannelData(0), ctx.sampleRate);
          const combined = new Int16Array(pending.length + downsampled.length);
          combined.set(pending);
          combined.set(downsampled, pending.length);
          pending = combined;
          // Send in 1280-sample (80ms) chunks matching server expectation
          while (pending.length >= 1280) {
            ws.send(pending.slice(0, 1280).buffer);
            pending = pending.slice(1280);
          }
        };

        source.connect(processor);
        processor.connect(ctx.destination);
        setStatus("listening");
      };

      ws.onmessage = (e) => {
        try {
          const msg = JSON.parse(typeof e.data === "string" ? e.data : "{}");
          if (msg.detected) onDetectedRef.current();
        } catch { /* ignore */ }
      };

      ws.onerror = () => setStatus("error");

      ws.onclose = () => {
        // Auto-reconnect while still enabled
        if (enabledRef.current) {
          setTimeout(() => { if (enabledRef.current) start(); }, 3000);
        } else {
          stop();
        }
      };
    } catch {
      setStatus("error");
      stop();
    }
  }, [stop]);

  useEffect(() => {
    if (enabled) {
      start();
      return stop;
    }
    stop();
  }, [enabled, start, stop]);

  return { status };
}
