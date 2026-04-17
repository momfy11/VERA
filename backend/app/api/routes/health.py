"""Health check and utility routes."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

from backend.app.observability.logger import frontend_logger

router = APIRouter()

_LEVEL_MAP = {
    "debug": frontend_logger.debug,
    "info": frontend_logger.info,
    "warn": frontend_logger.warning,
    "warning": frontend_logger.warning,
    "error": frontend_logger.error,
}


class FrontendLogEntry(BaseModel):
    level: str = "info"
    message: str
    source: str | None = None


@router.get("/health")
def health_check() -> dict:
    """Simple health check endpoint."""
    return {"status": "ok"}


@router.post("/log")
def frontend_log(entry: FrontendLogEntry) -> dict:
    """Receive a log entry from the browser and write it to frontend.log."""
    log_fn = _LEVEL_MAP.get(entry.level.lower(), frontend_logger.info)
    source = f" [{entry.source}]" if entry.source else ""
    log_fn("[browser]%s %s", source, entry.message)
    return {"ok": True}
