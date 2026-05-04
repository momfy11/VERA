"""Google Calendar tools for VERA.

All functions return human-readable strings (or error messages) — never raise.
Each requires the OAuth token from google_authorize.py to exist.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from backend.app.services.google_oauth import GoogleAuthError, calendar_service

logger = logging.getLogger(__name__)


def _run(fn, *args, **kwargs):
    """Run a sync google-api call in a thread so we don't block the event loop."""
    loop = asyncio.get_running_loop()
    return loop.run_in_executor(None, lambda: fn(*args, **kwargs))


def _fmt_event(ev: dict) -> str:
    """Format a single calendar event for display."""
    summary = ev.get("summary", "(no title)")
    start = ev.get("start", {})
    when = start.get("dateTime") or start.get("date") or "?"
    # Trim ISO timezone for readability
    when = when.replace("T", " ").split("+")[0].split("Z")[0][:16]
    location = ev.get("location", "")
    loc_str = f" @ {location}" if location else ""
    return f"• {when}  {summary}{loc_str}"


async def get_agenda(days: int = 1) -> str:
    """List upcoming calendar events for the next N days (default: today)."""
    try:
        svc = calendar_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        now = datetime.now(timezone.utc)
        end = now + timedelta(days=max(1, min(days, 30)))
        result = await _run(
            lambda: svc.events().list(
                calendarId="primary",
                timeMin=now.isoformat(),
                timeMax=end.isoformat(),
                singleEvents=True,
                orderBy="startTime",
                maxResults=25,
            ).execute()
        )
        events = result.get("items", [])
        if not events:
            return f"No events in the next {days} day(s)."
        lines = [f"Agenda for the next {days} day(s):"]
        lines.extend(_fmt_event(ev) for ev in events)
        return "\n".join(lines)
    except Exception as exc:
        logger.warning("get_agenda error: %r", exc)
        return f"Calendar error: {exc}"


async def create_event(
    summary: str,
    start_iso: str,
    end_iso: str | None = None,
    description: str = "",
    location: str = "",
) -> str:
    """Create a calendar event.

    start_iso / end_iso are ISO 8601 strings, e.g. '2026-04-26T14:00:00+02:00'.
    If end_iso is omitted, defaults to start + 1 hour.
    """
    try:
        svc = calendar_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        if not end_iso:
            start_dt = datetime.fromisoformat(start_iso.replace("Z", "+00:00"))
            end_iso = (start_dt + timedelta(hours=1)).isoformat()
        body = {
            "summary": summary,
            "description": description,
            "location": location,
            "start": {"dateTime": start_iso},
            "end": {"dateTime": end_iso},
        }
        created = await _run(
            lambda: svc.events().insert(calendarId="primary", body=body).execute()
        )
        return f"Created: {summary} at {start_iso}\nLink: {created.get('htmlLink', '')}"
    except Exception as exc:
        logger.warning("create_event error: %r", exc)
        return f"Calendar error: {exc}"


async def delete_event(event_id: str) -> str:
    """Delete (cancel) a calendar event by its ID. Goes to Calendar's trash."""
    try:
        svc = calendar_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        await _run(
            lambda: svc.events().delete(calendarId="primary", eventId=event_id).execute()
        )
        return f"Deleted event {event_id} (recoverable from Calendar trash for ~30 days)."
    except Exception as exc:
        logger.warning("delete_event error: %r", exc)
        return f"Calendar error: {exc}"


async def find_event(query: str, days: int = 14) -> str:
    """Search upcoming events by keyword. Returns matching events with their IDs."""
    try:
        svc = calendar_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        now = datetime.now(timezone.utc)
        end = now + timedelta(days=days)
        result = await _run(
            lambda: svc.events().list(
                calendarId="primary",
                q=query,
                timeMin=now.isoformat(),
                timeMax=end.isoformat(),
                singleEvents=True,
                orderBy="startTime",
                maxResults=10,
            ).execute()
        )
        events = result.get("items", [])
        if not events:
            return f"No events matching '{query}' in the next {days} days."
        lines = [f"Events matching '{query}':"]
        for ev in events:
            lines.append(f"{_fmt_event(ev)}\n  id: {ev['id']}")
        return "\n".join(lines)
    except Exception as exc:
        logger.warning("find_event error: %r", exc)
        return f"Calendar error: {exc}"
