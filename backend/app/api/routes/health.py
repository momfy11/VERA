"""Health check and frontend log forwarder."""
from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from backend.app.observability.logger import frontend_logger

router = APIRouter()

# Map log level → frontend_logger method
_LEVEL_MAP = {
    "debug": frontend_logger.debug,
    "info": frontend_logger.info,
    "warn": frontend_logger.warning,
    "warning": frontend_logger.warning,
    "error": frontend_logger.error,
}

# Per-IP sliding-window rate limit: 60 entries / 60 s
_RATE_LIMIT = 60
_WINDOW_SECONDS = 60
_recent_calls: dict[str, deque[float]] = defaultdict(deque)


def _rate_limited(ip: str) -> bool:
    now = time.monotonic()
    bucket = _recent_calls[ip]
    cutoff = now - _WINDOW_SECONDS
    while bucket and bucket[0] < cutoff:
        bucket.popleft()
    if len(bucket) >= _RATE_LIMIT:
        return True
    bucket.append(now)
    return False


class FrontendLogEntry(BaseModel):
    """Log entry posted by the browser."""
    level: str = "info"
    message: str = Field(..., max_length=2000)
    source: str | None = Field(default=None, max_length=200)


@router.get("/health")
def health_check() -> dict:
    """Simple health check endpoint."""
    return {"status": "ok"}


@router.post("/log")
def frontend_log(entry: FrontendLogEntry, request: Request) -> dict:
    """Receive a log entry from the browser and write it to frontend.log."""
    ip = (request.client.host if request.client else "unknown")
    if _rate_limited(ip):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")

    log_fn = _LEVEL_MAP.get(entry.level.lower(), frontend_logger.info)
    source = f" [{entry.source}]" if entry.source else ""
    # Strip newlines so injected entries can't fake other log lines
    message = entry.message.replace("\n", " ").replace("\r", " ")
    log_fn("[browser]%s %s", source, message)
    return {"ok": True}
