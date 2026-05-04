"""Gmail tools for VERA.

All functions return human-readable strings (or error messages) — never raise.
Permanent delete is intentionally NOT exposed — only soft-trash via gmail.modify.
"""
from __future__ import annotations

import asyncio
import base64
import logging
from email.mime.text import MIMEText

from backend.app.services.google_oauth import GoogleAuthError, gmail_service

logger = logging.getLogger(__name__)


def _run(fn, *args, **kwargs):
    loop = asyncio.get_running_loop()
    return loop.run_in_executor(None, lambda: fn(*args, **kwargs))


def _header(headers: list[dict], name: str) -> str:
    """Get a single header value by name (case-insensitive)."""
    for h in headers:
        if h.get("name", "").lower() == name.lower():
            return h.get("value", "")
    return ""


def _decode_body(payload: dict) -> str:
    """Recursively pull the first text/plain (or text/html) part out of a message payload."""
    mime = payload.get("mimeType", "")
    if mime.startswith("text/"):
        data = payload.get("body", {}).get("data", "")
        if data:
            decoded = base64.urlsafe_b64decode(data).decode("utf-8", errors="replace")
            # Trim huge bodies
            return decoded[:5000] + ("…" if len(decoded) > 5000 else "")
    for part in payload.get("parts", []) or []:
        decoded = _decode_body(part)
        if decoded:
            return decoded
    return ""


async def list_emails(query: str = "", limit: int = 10) -> str:
    """List recent emails matching a Gmail search query.

    Examples of query syntax:
      from:alice@example.com
      is:unread
      has:attachment
      newer_than:1d
      subject:"meeting notes"
      label:inbox is:unread
    """
    try:
        svc = gmail_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        result = await _run(
            lambda: svc.users().messages().list(
                userId="me",
                q=query or "in:inbox",
                maxResults=max(1, min(limit, 50)),
            ).execute()
        )
        msgs = result.get("messages", [])
        if not msgs:
            return f"No emails matching '{query or 'in:inbox'}'."

        lines = [f"Found {len(msgs)} email(s) matching '{query or 'in:inbox'}':"]
        for m in msgs:
            full = await _run(
                lambda mid=m["id"]: svc.users().messages().get(
                    userId="me", id=mid, format="metadata",
                    metadataHeaders=["From", "Subject", "Date"],
                ).execute()
            )
            headers = full.get("payload", {}).get("headers", [])
            sender = _header(headers, "From")[:60]
            subject = _header(headers, "Subject")[:80]
            snippet = full.get("snippet", "")[:120]
            lines.append(f"\n• id: {m['id']}\n  from: {sender}\n  subject: {subject}\n  preview: {snippet}")
        return "\n".join(lines)
    except Exception as exc:
        logger.warning("list_emails error: %r", exc)
        return f"Gmail error: {exc}"


async def read_email(message_id: str) -> str:
    """Fetch the full content of one email by its message ID."""
    try:
        svc = gmail_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        msg = await _run(
            lambda: svc.users().messages().get(userId="me", id=message_id, format="full").execute()
        )
        headers = msg.get("payload", {}).get("headers", [])
        sender = _header(headers, "From")
        subject = _header(headers, "Subject")
        date = _header(headers, "Date")
        body = _decode_body(msg.get("payload", {})) or "(no body)"
        return (
            f"From:    {sender}\n"
            f"Subject: {subject}\n"
            f"Date:    {date}\n\n"
            f"{body}"
        )
    except Exception as exc:
        logger.warning("read_email error: %r", exc)
        return f"Gmail error: {exc}"


async def send_email(to: str, subject: str, body: str) -> str:
    """Send a new email."""
    try:
        svc = gmail_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        msg = MIMEText(body)
        msg["to"] = to
        msg["subject"] = subject
        raw = base64.urlsafe_b64encode(msg.as_bytes()).decode("utf-8")
        sent = await _run(
            lambda: svc.users().messages().send(userId="me", body={"raw": raw}).execute()
        )
        return f"Email sent to {to} (id: {sent.get('id', '?')})"
    except Exception as exc:
        logger.warning("send_email error: %r", exc)
        return f"Gmail error: {exc}"


async def trash_email(message_id: str) -> str:
    """Move an email to trash. Recoverable for 30 days from Gmail's Trash folder."""
    try:
        svc = gmail_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        await _run(
            lambda: svc.users().messages().trash(userId="me", id=message_id).execute()
        )
        return f"Moved email {message_id} to Trash (recoverable for 30 days)."
    except Exception as exc:
        logger.warning("trash_email error: %r", exc)
        return f"Gmail error: {exc}"


async def mark_as_read(message_id: str) -> str:
    """Mark an email as read (removes the UNREAD label)."""
    try:
        svc = gmail_service()
    except GoogleAuthError as exc:
        return str(exc)
    try:
        await _run(
            lambda: svc.users().messages().modify(
                userId="me", id=message_id,
                body={"removeLabelIds": ["UNREAD"]},
            ).execute()
        )
        return f"Marked {message_id} as read."
    except Exception as exc:
        logger.warning("mark_as_read error: %r", exc)
        return f"Gmail error: {exc}"
