"""Health check routes."""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
def health_check() -> dict:
    """Simple health check endpoint."""
    return {"status": "ok"}
