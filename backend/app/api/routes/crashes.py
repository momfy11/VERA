"""Crash report ingestion endpoint.

POST /api/crashes
  No auth required — crash may happen before/after login.
  Accepts JSON with stacktrace + device info, stores to DB.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Header
from pydantic import BaseModel

from backend.app.db.session import SessionLocal
from backend.app.db import models

logger = logging.getLogger(__name__)
router = APIRouter()


class CrashReport(BaseModel):
    stacktrace: str
    app_version: str = ""
    android_version: str = ""
    device_model: str = ""


@router.post("/crashes", status_code=201)
def ingest_crash(body: CrashReport, x_session_token: str | None = Header(default=None, alias="X-Session-Token")):
    db = SessionLocal()
    try:
        # Resolve user if session token present — optional
        user_id: str | None = None
        if x_session_token:
            session = (
                db.query(models.Session)
                .filter(models.Session.session_token == x_session_token)
                .first()
            )
            if session:
                user_id = str(session.user_id)

        crash = models.AppCrash(
            app_version=body.app_version[:32],
            android_version=body.android_version[:32],
            device_model=body.device_model[:128],
            stacktrace=body.stacktrace[:16_000],
            user_id=user_id,
        )
        db.add(crash)
        db.commit()
        logger.warning(
            "Crash report from %s %s (%s): %s",
            body.device_model, body.app_version, body.android_version,
            body.stacktrace[:200],
        )
        return {"ok": True}
    finally:
        db.close()
