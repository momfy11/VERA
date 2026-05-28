/**
 * useTTS — speak text via Web Speech API SpeechSynthesis.
 *
 * Speaks any new assistant message when voice mode is active.
 * Tracks last-spoken message id to avoid re-speaking on re-render.
 * Fires onSpeakStart / onSpeakEnd so the caller can mute STT during TTS
 * (otherwise the mic picks up VERA's voice and loops it back).
 */
import { useCallback, useEffect, useRef } from "react";
import { stripMarkdown } from "./stripMarkdown";
import type { ChatMessage } from "./types";

type UseTTSOptions = {
  enabled: boolean;
  messages: ChatMessage[];
  rate?: number;
  pitch?: number;
  onSpeakStart?: () => void;
  onSpeakEnd?: () => void;
  /** Extra ms to keep STT muted after TTS ends (audio echo dies down). */
  graceMs?: number;
};

export function useTTS({
  enabled,
  messages,
  rate = 1,
  pitch = 1,
  onSpeakStart,
  onSpeakEnd,
  graceMs = 1500,
}: UseTTSOptions) {
  const lastSpokenIdRef = useRef<string | null>(null);
  const supportedRef = useRef<boolean>(
    typeof window !== "undefined" && "speechSynthesis" in window,
  );
  const graceTimerRef = useRef<number | null>(null);

  const speak = useCallback(
    (text: string) => {
      if (!supportedRef.current) return;
      const spoken = stripMarkdown(text);
      if (!spoken.trim()) return;
      if (graceTimerRef.current !== null) {
        window.clearTimeout(graceTimerRef.current);
        graceTimerRef.current = null;
      }
      const utterance = new SpeechSynthesisUtterance(spoken);
      utterance.lang = "en-US";
      utterance.rate = rate;
      utterance.pitch = pitch;
      utterance.onstart = () => onSpeakStart?.();
      utterance.onend = () => {
        graceTimerRef.current = window.setTimeout(() => {
          onSpeakEnd?.();
          graceTimerRef.current = null;
        }, graceMs);
      };
      utterance.onerror = () => {
        onSpeakEnd?.();
      };
      window.speechSynthesis.speak(utterance);
    },
    [rate, pitch, onSpeakStart, onSpeakEnd, graceMs],
  );

  const cancel = useCallback(() => {
    if (supportedRef.current) {
      window.speechSynthesis.cancel();
    }
    if (graceTimerRef.current !== null) {
      window.clearTimeout(graceTimerRef.current);
      graceTimerRef.current = null;
    }
    onSpeakEnd?.();
  }, [onSpeakEnd]);

  useEffect(() => {
    if (!enabled || !supportedRef.current) return;
    if (messages.length === 0) return;
    const last = messages[messages.length - 1];
    if (last.role !== "assistant") return;
    if (last.id === lastSpokenIdRef.current) return;
    lastSpokenIdRef.current = last.id;
    speak(last.text);
  }, [enabled, messages, speak]);

  useEffect(() => {
    if (!enabled) cancel();
  }, [enabled, cancel]);

  return { speak, cancel, supported: supportedRef.current };
}
