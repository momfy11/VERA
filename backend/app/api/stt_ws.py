"""WebSocket endpoint for server-side STT via faster-whisper.

Client streams 16kHz int16 PCM while VAD is active.
On {"type": "flush"}: transcribe buffered audio, return {"text": "...", "is_final": true}.
Reuses the Whisper tiny model already loaded by wake_word_service.
"""
from __future__ import annotations

import asyncio
import json
import logging
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from fastapi import WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)

_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt")

MIN_FLUSH_BYTES = 16000 * 2 // 2   # 0.5 s minimum before transcribing
MAX_BUFFER_BYTES = 16000 * 2 * 30  # 30 s cap


def _transcribe(audio_int16: bytes) -> str:
    from backend.app.services.wake_word_service import _get_model
    model = _get_model()
    audio = np.frombuffer(audio_int16, dtype=np.int16).astype(np.float32) / 32768.0
    segments, _ = model.transcribe(
        audio,
        language="en",
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 200},
        beam_size=3,
        best_of=1,
        temperature=0.0,
    )
    return " ".join(s.text for s in segments).strip()


async def stt_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    logger.info("STT WS connected from %s", websocket.client)

    try:
        from backend.app.services.wake_word_service import _get_model
    except ImportError as exc:
        logger.error("faster-whisper not available: %r", exc)
        await websocket.close(code=1011, reason="STT model unavailable")
        return

    loop = asyncio.get_event_loop()
    loop.run_in_executor(_executor, _get_model)  # warm up (no-op if already loaded)

    buffer = bytearray()

    try:
        while True:
            try:
                msg = await asyncio.wait_for(websocket.receive(), timeout=30.0)
            except asyncio.TimeoutError:
                continue

            if "bytes" in msg and msg["bytes"]:
                buffer.extend(msg["bytes"])
                if len(buffer) > MAX_BUFFER_BYTES:
                    buffer = buffer[-MAX_BUFFER_BYTES:]

            elif "text" in msg and msg["text"]:
                try:
                    cmd = json.loads(msg["text"])
                except json.JSONDecodeError:
                    continue

                if cmd.get("type") == "clear":
                    buffer.clear()

                elif cmd.get("type") == "flush" and len(buffer) >= MIN_FLUSH_BYTES:
                    chunk = bytes(buffer)
                    buffer.clear()
                    text = await loop.run_in_executor(_executor, _transcribe, chunk)
                    if text:
                        logger.debug("STT transcript: %r", text)
                    await websocket.send_json({"text": text, "is_final": True})
                elif cmd.get("type") == "flush":
                    buffer.clear()
                    await websocket.send_json({"text": "", "is_final": True})

    except WebSocketDisconnect:
        logger.info("STT WS disconnected")
    except Exception as exc:
        logger.error("STT WS error: %r", exc)
