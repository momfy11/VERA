"""Suggestion schemas."""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class SuggestionItem(BaseModel):
    id: str
    ts: datetime
    type: str
    priority: int
    payload: dict
    status: str


class SuggestionListResponse(BaseModel):
    items: list[SuggestionItem]
