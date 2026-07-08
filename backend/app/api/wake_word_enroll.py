"""Wake word enrollment — upload voice samples, get embedding templates stored on server."""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, File, Header, HTTPException, UploadFile
from sqlalchemy.orm import Session

from backend.app.api.routes.suggestions import get_user_by_token
from backend.app.db.session import get_db
from backend.app.services.wake_word_service import clear_templates, save_template_from_audio, template_count

router = APIRouter()
logger = logging.getLogger(__name__)

MIN_AUDIO_BYTES = 8000   # at least 0.25s at 16kHz int16
MAX_AUDIO_BYTES = 256000  # cap at 8s


@router.post("/wake-word/enroll")
async def enroll_sample(
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
):
    """Upload one raw int16 PCM sample (16kHz mono) to add a voice template."""
    get_user_by_token(db, x_session_token)

    audio_bytes = await file.read()
    if len(audio_bytes) < MIN_AUDIO_BYTES:
        raise HTTPException(400, f"Audio too short (got {len(audio_bytes)} bytes, need {MIN_AUDIO_BYTES}+)")
    if len(audio_bytes) > MAX_AUDIO_BYTES:
        audio_bytes = audio_bytes[:MAX_AUDIO_BYTES]

    try:
        count = save_template_from_audio(audio_bytes)
    except RuntimeError as exc:
        raise HTTPException(503, str(exc))

    return {"status": "ok", "template_count": count}


@router.get("/wake-word/enroll")
def get_enrollment_status(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
):
    """Return number of stored wake word templates."""
    get_user_by_token(db, x_session_token)
    return {"template_count": template_count()}


@router.delete("/wake-word/enroll")
def clear_enrollment(
    db: Session = Depends(get_db),
    x_session_token: str | None = Header(default=None, alias="X-Session-Token"),
):
    """Delete all stored wake word templates (resets to Whisper fallback)."""
    get_user_by_token(db, x_session_token)
    clear_templates()
    return {"status": "cleared", "template_count": 0}
