"""Logging helpers."""
from __future__ import annotations

import logging

from backend.app.core.config import settings


def configure_logging() -> None:
    """Configure a basic logger for the application."""
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
