import { useCallback, useEffect, useState } from "react";
import { clearWakeEnrollment, getWakeStatus, uploadWakeSample } from "../lib/api";

const TARGET = 10;
const RECORD_SECS = 2;

async function recordPcm16(durationSecs: number): Promise<ArrayBuffer> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
  });

  const ctx = new AudioContext();
  const src = ctx.createMediaStreamSource(stream);
  const processor = ctx.createScriptProcessor(4096, 1, 1);
  const chunks: Float32Array[] = [];

  processor.onaudioprocess = (e) => {
    chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  };

  src.connect(processor);
  processor.connect(ctx.destination);

  await new Promise<void>((r) => setTimeout(r, durationSecs * 1000));

  processor.disconnect();
  src.disconnect();
  stream.getTracks().forEach((t) => t.stop());

  const totalFrames = chunks.reduce((s, c) => s + c.length, 0);
  const combined = new Float32Array(totalFrames);
  let offset = 0;
  for (const chunk of chunks) { combined.set(chunk, offset); offset += chunk.length; }

  // Resample to 16kHz
  const targetFrames = Math.round((totalFrames * 16000) / ctx.sampleRate);
  const offCtx = new OfflineAudioContext(1, targetFrames, 16000);
  const buf = offCtx.createBuffer(1, totalFrames, ctx.sampleRate);
  buf.copyToChannel(combined, 0);
  const offSrc = offCtx.createBufferSource();
  offSrc.buffer = buf;
  offSrc.connect(offCtx.destination);
  offSrc.start();
  const rendered = await offCtx.startRendering();

  await ctx.close();

  const float32 = rendered.getChannelData(0);
  const int16 = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    int16[i] = Math.max(-32768, Math.min(32767, Math.round(float32[i] * 32767)));
  }
  return int16.buffer;
}

type Props = { sessionToken: string | null };

export function WakeWordPanel({ sessionToken }: Props) {
  const [count, setCount] = useState<number | null>(null);
  const [countdown, setCountdown] = useState(0);
  const [recording, setRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const [statusMsg, setStatusMsg] = useState("");

  useEffect(() => {
    if (!sessionToken) return;
    getWakeStatus(sessionToken)
      .then((r) => setCount(r.template_count))
      .catch(() => setCount(0));
  }, [sessionToken]);

  const doRecord = useCallback(async () => {
    if (!sessionToken || busy) return;
    setBusy(true);
    setStatusMsg("");

    try {
      for (let i = 3; i >= 1; i--) {
        setCountdown(i);
        await new Promise<void>((r) => setTimeout(r, 1000));
      }
      setCountdown(0);
      setRecording(true);
      setStatusMsg(`Say your wake phrase now…`);

      const pcm = await recordPcm16(RECORD_SECS);

      setRecording(false);
      setStatusMsg("Uploading…");

      const result = await uploadWakeSample(sessionToken, pcm);
      setCount(result.template_count);
      setStatusMsg(
        result.template_count >= TARGET
          ? "Training complete — wake word is ready!"
          : `Sample ${result.template_count}/${TARGET} saved.`,
      );
    } catch (err) {
      setRecording(false);
      setCountdown(0);
      setStatusMsg(`Error: ${err instanceof Error ? err.message : "recording failed"}`);
    }
    setBusy(false);
  }, [sessionToken, busy]);

  const doClear = useCallback(async () => {
    if (!sessionToken) return;
    await clearWakeEnrollment(sessionToken).catch(() => {});
    setCount(0);
    setStatusMsg("Templates cleared.");
  }, [sessionToken]);

  const n = count ?? 0;
  const pct = Math.min(100, (n / TARGET) * 100);
  const done = n >= TARGET;

  return (
    <section className="panel">
      <h2>Wake Word Training</h2>
      <p>Record your wake phrase so VERA recognises your voice — much more accurate than generic speech detection.</p>

      {/* Progress bar */}
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.82rem", color: "var(--muted)" }}>
          <span>{n} / {TARGET} samples</span>
          {done && <span style={{ color: "var(--good)", fontWeight: 600 }}>Active</span>}
        </div>
        <div style={{ height: 6, borderRadius: 999, background: "rgba(31,29,26,0.1)", overflow: "hidden" }}>
          <div
            style={{
              height: "100%",
              width: `${pct}%`,
              background: done ? "var(--good)" : "var(--accent)",
              borderRadius: 999,
              transition: "width 0.3s",
            }}
          />
        </div>
      </div>

      {/* Countdown */}
      {countdown > 0 && (
        <div style={{ textAlign: "center", fontSize: "3.5rem", fontWeight: 700, color: "var(--accent)", lineHeight: 1 }}>
          {countdown}
        </div>
      )}

      {/* Recording indicator */}
      {recording && (
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="rec-dot" />
          <span style={{ fontSize: "0.9rem", fontWeight: 600 }}>Recording…</span>
        </div>
      )}

      {statusMsg && (
        <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--muted)" }}>{statusMsg}</p>
      )}

      <div className="panel-actions">
        <button
          type="button"
          className="button"
          onClick={() => void doRecord()}
          disabled={busy || !sessionToken}
        >
          {n === 0 ? "Start Recording" : n < TARGET ? `Record Sample ${n + 1}` : "Record Extra"}
        </button>
        {n > 0 && (
          <button type="button" className="button ghost" onClick={() => void doClear()} disabled={busy}>
            Clear All
          </button>
        )}
      </div>

      <ul style={{ margin: 0, padding: "0 0 0 18px", fontSize: "0.82rem", color: "var(--muted)", display: "flex", flexDirection: "column", gap: 3 }}>
        <li>Record 10 samples — say the phrase naturally each time</li>
        <li>Try different tones, distances from the mic</li>
        <li>Server learns your voice pattern (not just the words)</li>
        <li>Detection improves dramatically vs generic speech recognition</li>
      </ul>
    </section>
  );
}
