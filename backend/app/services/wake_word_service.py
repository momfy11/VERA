"""Wake word detection — openwakeword template matching with Whisper fallback.

Flow (with trained templates):
  client streams 16kHz int16 PCM → 2s window → openwakeword embedding →
  cosine similarity vs stored user templates → fire if similarity >= 0.75

Flow (no templates / openwakeword unavailable):
  same audio → Whisper tiny transcription → phrase match ("vera", "hey vera")
"""
from __future__ import annotations

import logging
import os
import re
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

SAMPLE_RATE = 16000
_TEMPLATES_PATH = Path(os.environ.get("WAKE_TEMPLATES_PATH", "/app/wake_models/templates.npy"))
_SIMILARITY_THRESHOLD = float(os.environ.get("WAKE_SIMILARITY_THRESHOLD", "0.75"))

_melspec_session = None
_embedding_session = None
_whisper_model = None

_templates_cache: list[np.ndarray] | None = None
_templates_mtime: float = 0.0


# ---------------------------------------------------------------------------
# ONNX sessions (openwakeword bundled models)
# ---------------------------------------------------------------------------

def _get_onnx_sessions():
    global _melspec_session, _embedding_session
    if _melspec_session is not None:
        return _melspec_session, _embedding_session
    try:
        import onnxruntime as ort
        import openwakeword
        import openwakeword.utils as _oww_utils

        pkg = Path(openwakeword.__file__).parent / "resources" / "models"
        mel_path = pkg / "melspectrogram.onnx"
        emb_path = pkg / "embedding_model.onnx"

        if not mel_path.exists() or not emb_path.exists():
            logger.info("openwakeword ONNX models not found — downloading...")
            _oww_utils.download_models()
            logger.info("openwakeword models downloaded")

        _melspec_session = ort.InferenceSession(
            str(mel_path),
            providers=["CPUExecutionProvider"],
        )
        _embedding_session = ort.InferenceSession(
            str(emb_path),
            providers=["CPUExecutionProvider"],
        )
        logger.info("openwakeword ONNX sessions loaded")
    except Exception as exc:
        logger.warning("openwakeword not available, will use Whisper: %r", exc)
        _melspec_session = None
        _embedding_session = None
    return _melspec_session, _embedding_session


def _compute_embedding(audio_float32: np.ndarray) -> np.ndarray | None:
    """Return 96-dim embedding for 1s float32 audio. None if unavailable."""
    mel_sess, emb_sess = _get_onnx_sessions()
    if mel_sess is None or emb_sess is None:
        return None
    try:
        # Pad / trim to exactly 16000 samples
        n = len(audio_float32)
        if n > SAMPLE_RATE:
            audio_float32 = audio_float32[:SAMPLE_RATE]
        elif n < SAMPLE_RATE:
            audio_float32 = np.pad(audio_float32, (0, SAMPLE_RATE - n))

        inp = audio_float32.reshape(1, -1).astype(np.float32)
        mel_name = mel_sess.get_inputs()[0].name
        mel_out = mel_sess.run(None, {mel_name: inp})[0]

        emb_name = emb_sess.get_inputs()[0].name
        emb_out = emb_sess.run(None, {emb_name: mel_out})[0]
        return emb_out.flatten()
    except Exception as exc:
        logger.warning("Embedding error: %r", exc)
        return None


# ---------------------------------------------------------------------------
# Template storage
# ---------------------------------------------------------------------------

def _load_templates() -> list[np.ndarray]:
    global _templates_cache, _templates_mtime
    if not _TEMPLATES_PATH.exists():
        return []
    mtime = _TEMPLATES_PATH.stat().st_mtime
    if _templates_cache is not None and mtime == _templates_mtime:
        return _templates_cache
    try:
        data = np.load(str(_TEMPLATES_PATH), allow_pickle=True)
        _templates_cache = [data[i] for i in range(len(data))]
        _templates_mtime = mtime
        logger.info("Loaded %d wake word templates", len(_templates_cache))
    except Exception as exc:
        logger.warning("Template load failed: %r", exc)
        _templates_cache = []
    return _templates_cache or []


def save_template_from_audio(audio_int16: bytes) -> int:
    """Compute embedding from raw int16 PCM bytes, append to templates.

    Returns total template count after saving.
    Raises RuntimeError if openwakeword is not available.
    """
    global _templates_cache, _templates_mtime

    audio = np.frombuffer(audio_int16, dtype=np.int16).astype(np.float32) / 32768.0

    # Use middle second to skip any click / silence at edges
    total = len(audio)
    start = max(0, total // 2 - SAMPLE_RATE // 2)
    segment = audio[start:start + SAMPLE_RATE]

    embedding = _compute_embedding(segment)
    if embedding is None:
        raise RuntimeError("openwakeword ONNX models unavailable — cannot compute embedding")

    _TEMPLATES_PATH.parent.mkdir(parents=True, exist_ok=True)
    templates = _load_templates()
    templates.append(embedding)
    np.save(str(_TEMPLATES_PATH), np.array(templates))

    _templates_cache = templates
    _templates_mtime = _TEMPLATES_PATH.stat().st_mtime
    logger.info("Wake word template saved (%d total)", len(templates))
    return len(templates)


def clear_templates() -> None:
    """Delete all stored wake word templates."""
    global _templates_cache, _templates_mtime
    if _TEMPLATES_PATH.exists():
        _TEMPLATES_PATH.unlink()
    _templates_cache = []
    _templates_mtime = 0.0
    logger.info("Wake word templates cleared")


def template_count() -> int:
    return len(_load_templates())


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------

def _cosine_sim(a: np.ndarray, b: np.ndarray) -> float:
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))


def _template_match(audio_int16: bytes, templates: list[np.ndarray]) -> bool:
    audio = np.frombuffer(audio_int16, dtype=np.int16).astype(np.float32) / 32768.0
    total = len(audio)
    best = 0.0
    # Slide 1-second windows with 0.5s step
    step = SAMPLE_RATE // 2
    for start in range(0, max(1, total - SAMPLE_RATE + 1), step):
        segment = audio[start:start + SAMPLE_RATE]
        embedding = _compute_embedding(segment)
        if embedding is None:
            return _whisper_match(audio_int16)
        for tmpl in templates:
            best = max(best, _cosine_sim(embedding, tmpl))
    logger.debug("Wake similarity=%.3f threshold=%.3f", best, _SIMILARITY_THRESHOLD)
    return best >= _SIMILARITY_THRESHOLD


def _whisper_match(audio_int16: bytes) -> bool:
    global _whisper_model
    try:
        if _whisper_model is None:
            from faster_whisper import WhisperModel
            cache = Path(os.environ.get("WHISPER_CACHE_DIR", "/app/.whisper_cache"))
            cache.mkdir(parents=True, exist_ok=True)
            logger.info("Loading Whisper tiny...")
            _whisper_model = WhisperModel(
                "tiny", device="cpu", compute_type="int8", download_root=str(cache)
            )
        phrases = [p.strip().lower() for p in os.environ.get("VITE_WAKE_PHRASES", "hey vera,vera").split(",") if p.strip()]
        audio = np.frombuffer(audio_int16, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _ = _whisper_model.transcribe(
            audio, vad_filter=False, beam_size=1, best_of=1,
            temperature=0.0, condition_on_previous_text=False,
        )
        transcript = " ".join(s.text for s in segments).strip()
        if transcript:
            logger.debug("Wake transcript: %r", transcript)
        norm = " " + re.sub(r"[^a-z0-9 ]", " ", transcript.lower()).strip() + " "
        return any((" " + p + " ") in norm for p in phrases)
    except Exception as exc:
        logger.warning("Whisper fallback error: %r", exc)
        return False


def transcribe_and_match(audio_int16: bytes) -> bool:
    """Detect wake word. Template matching if trained, else Whisper."""
    templates = _load_templates()
    if templates:
        return _template_match(audio_int16, templates)
    return _whisper_match(audio_int16)
