"""Logging helpers."""
from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

from backend.app.core.config import settings

_LOG_DIR = Path(__file__).parent.parent.parent.parent / "logs"
BACKEND_LOG = _LOG_DIR / "backend.log"
FRONTEND_LOG = _LOG_DIR / "frontend.log"
_FMT = "%(asctime)s %(levelname)-8s %(name)s  %(message)s"
_DATE_FMT = "%Y-%m-%d %H:%M:%S"

# Dedicated frontend logger — written to frontend.log only
frontend_logger = logging.getLogger("frontend")


def configure_logging() -> None:
    """Configure console + file logging.

    backend.log  — cleared on every restart, captures all backend DEBUG+
    frontend.log — appended across restarts (one line per browser log call)
    """
    _LOG_DIR.mkdir(parents=True, exist_ok=True)

    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    fmt = logging.Formatter(_FMT, datefmt=_DATE_FMT)

    # ── Console (terminal) ────────────────────────────────────────────────────
    console = logging.StreamHandler()
    console.setLevel(level)
    console.setFormatter(fmt)

    # ── backend.log — mode="w" wipes the file on each restart ────────────────
    backend_file = logging.FileHandler(BACKEND_LOG, mode="w", encoding="utf-8")
    backend_file.setLevel(logging.DEBUG)
    backend_file.setFormatter(fmt)

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    root.addHandler(console)
    root.addHandler(backend_file)

    # ── frontend.log — rotated at 10 MB, keep last 5 backups ─────────────────
    frontend_file = RotatingFileHandler(
        FRONTEND_LOG, maxBytes=10 * 1024 * 1024, backupCount=5, encoding="utf-8",
    )
    frontend_file.setLevel(logging.DEBUG)
    frontend_file.setFormatter(logging.Formatter("%(asctime)s %(message)s", datefmt=_DATE_FMT))
    frontend_logger.addHandler(frontend_file)
    frontend_logger.propagate = False  # don't also write to backend.log

    # Silence very noisy libraries; keep uvicorn.access for HTTP status codes
    if level > logging.DEBUG:
        for noisy in ("sqlalchemy.engine", "httpx", "httpcore"):
            logging.getLogger(noisy).setLevel(logging.WARNING)
