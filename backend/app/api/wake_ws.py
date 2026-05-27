"""WebSocket endpoint for offline wake word detection.

Client sends raw 16kHz int16 PCM bytes.
Server buffers 1.5s, transcribes with Whisper tiny, fires {"detected": true} on match.
"""
from __future__ import annotations

import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor

from fastapi import WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)

_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="wake_word")

CHUNK_BYTES = 1280 * 2         # 80ms of int16 PCM at 16kHz (sent by client)
BUFFER_BYTES = 16000 * 2 * 2  # 2s window — gives Whisper more context
STEP_BYTES   = 16000 * 2 * 1  # slide 1s at a time → 50% overlap, catches phrases at boundaries


async def wake_word_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    logger.info("Wake WS connected from %s", websocket.client)

    try:
        from backend.app.services.wake_word_service import transcribe_and_match
    except ImportError as exc:
        logger.error("faster-whisper not available: %r", exc)
        await websocket.close(code=1011, reason="wake word model unavailable")
        return

    # Warm up model in background (first call downloads ~40MB)
    loop = asyncio.get_event_loop()
    loop.run_in_executor(_executor, transcribe_and_match, b"\x00" * BUFFER_BYTES)

    buffer = bytearray()

    try:
        while True:
            try:
                data = await asyncio.wait_for(websocket.receive_bytes(), timeout=30.0)
            except asyncio.TimeoutError:
                continue

            buffer.extend(data)

            if len(buffer) >= BUFFER_BYTES:
                chunk = bytes(buffer[:BUFFER_BYTES])
                # Slide by STEP_BYTES (50% overlap) so phrases at chunk boundaries
                # are always captured in at least one transcription window.
                buffer = buffer[STEP_BYTES:]

                detected = await loop.run_in_executor(_executor, transcribe_and_match, chunk)
                if detected:
                    logger.info("Wake word detected")
                    await websocket.send_json({"detected": True})
                    # Clear buffer after detection to avoid double-firing
                    buffer = bytearray()

    except WebSocketDisconnect:
        logger.info("Wake WS disconnected")
    except Exception as exc:
        logger.error("Wake WS error: %r", exc)
