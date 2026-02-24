"""Auth-related schemas."""
from __future__ import annotations

from pydantic import BaseModel


class LoginRequest(BaseModel):
    email: str
    display_name: str | None = None


class LoginResponse(BaseModel):
    user_id: str
    session_token: str
