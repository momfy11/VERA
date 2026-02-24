"""WebSocket event schemas."""
from __future__ import annotations

from pydantic import BaseModel


class ClientHello(BaseModel):
    type: str = "client.hello"
    payload: dict


class VoiceVadStart(BaseModel):
    type: str = "voice.vad_start"
    payload: dict


class VoiceVadEnd(BaseModel):
    type: str = "voice.vad_end"
    payload: dict
