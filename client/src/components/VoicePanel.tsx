import { useEffect } from "react";
import { useVoiceSession, VoiceStatus } from "../lib/useVoiceSession";

type VoicePanelProps = {
  sessionActive: boolean;
  onVadStart: () => void;
  onVadEnd: () => void;
  onStatusChange: (status: VoiceStatus) => void;
  onSpeechFinal?: (text: string) => void;
};

export function VoicePanel({ sessionActive, onVadStart, onVadEnd, onStatusChange, onSpeechFinal }: VoicePanelProps) {
  const { status, error, isRunning, start, stop } = useVoiceSession({
    onVadStart,
    onVadEnd,
    onStatusChange,
    onSpeechFinal,
  });

  useEffect(() => {
    if (!sessionActive && isRunning) {
      stop();
    }
  }, [isRunning, sessionActive, stop]);

  return (
    <section className="panel">
      <h2>Voice Session</h2>
      <p>Start listening, then speak naturally. Barge-in cancels TTS immediately.</p>
      <div className="voice-status">
        <span className={`voice-dot ${status}`}></span>
        <span>{status.toUpperCase()}</span>
      </div>
      {error && <p className="error-text">{error}</p>}
      <div className="panel-actions">
        <button type="button" className="button" onClick={() => { start().catch(console.error); }} disabled={!sessionActive || isRunning}>
          Start voice
        </button>
        <button type="button" className="button ghost" onClick={stop} disabled={!isRunning}>
          Stop voice
        </button>
      </div>
      {!sessionActive && <p className="hint-text">Start a session to enable the mic.</p>}
    </section>
  );
}
