"""Offline wake word detection via faster-whisper (Whisper tiny).

Audio pipeline:
  client streams 16kHz int16 PCM → buffer 1.5s → transcribe → match phrases

Latency: ~1.8s (1.5s buffer + ~0.3s transcription on CPU).
Model:    Whisper tiny (~40MB, downloaded once to WHISPER_CACHE_DIR).
Privacy:  fully local, no Google STT.
"""
from __future__ import annotations

import logging
import os
import re
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

SAMPLE_RATE = 16000
BUFFER_SECONDS = 1.5
BUFFER_SAMPLES = int(SAMPLE_RATE * BUFFER_SECONDS)

_CACHE_DIR = Path(os.environ.get("WHISPER_CACHE_DIR", "/app/.whisper_cache"))

_model = None


def _get_model():
    global _model
    if _model is not None:
        return _model
    from faster_whisper import WhisperModel
    _CACHE_DIR.mkdir(parents=True, exist_ok=True)
    logger.info("Loading Whisper tiny model (downloads ~40MB on first run)...")
    _model = WhisperModel(
        "tiny",
        device="cpu",
        compute_type="int8",
        download_root=str(_CACHE_DIR),
    )
    logger.info("Whisper tiny loaded")
    return _model


def _load_wake_phrases() -> list[str]:
    raw = os.environ.get("VITE_WAKE_PHRASES", "hey vera,vera")
    return [p.strip().lower() for p in raw.split(",") if p.strip()]


def _phrase_matches(transcript: str, phrases: list[str]) -> bool:
    norm = " " + re.sub(r"[^a-z0-9 ]", " ", transcript.lower()).strip() + " "
    for p in phrases:
        if (" " + p + " ") in norm:
            return True
    return False


def transcribe_and_match(audio_int16: bytes) -> bool:
    """Return True if wake phrase detected in audio chunk."""
    model = _get_model()
    phrases = _load_wake_phrases()

    audio = np.frombuffer(audio_int16, dtype=np.int16).astype(np.float32) / 32768.0

    segments, _ = model.transcribe(
        audio,
        vad_filter=False,          # VAD suppresses short words like "vera"
        beam_size=1,
        best_of=1,
        temperature=0.0,
        condition_on_previous_text=False,
    )
    transcript = " ".join(s.text for s in segments).strip()
    if transcript:
        logger.debug("Wake transcript: %r", transcript)
    return _phrase_matches(transcript, phrases)
