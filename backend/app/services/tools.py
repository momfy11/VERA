"""VERA tool registry — callable instruments the LLM can invoke.

Tool categories:
  Web      — web_search, get_weather, wikipedia_summary, get_datetime, get_news
  Memory   — store_memory
  Files    — read_file, list_directory, search_files
  System   — send_notification, get_clipboard, set_clipboard
  Calendar — get_agenda, find_event, create_event, delete_event
  Email    — list_emails, read_email, send_email, trash_email, mark_as_read
  Music    — spotify_play, spotify_pause, spotify_skip, spotify_now_playing, spotify_queue


Each tool is an async function with a JSON-schema descriptor.  The orchestrator
passes descriptors to the LLM and dispatches execution when the model requests it.

Adding a new tool:
1. Implement an async function below.
2. Add a descriptor to TOOL_DEFINITIONS.
3. Register it in TOOL_REGISTRY.
"""
from __future__ import annotations

import asyncio
import base64
import fnmatch
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from xml.sax.saxutils import escape as xml_escape
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_HTTP = httpx.AsyncClient(timeout=10.0, follow_redirects=True)


def _truncate(text: str, max_chars: int = 2000) -> str:
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + f"\n… [truncated, {len(text) - max_chars} more chars]"


# ---------------------------------------------------------------------------
# Tool implementations
# ---------------------------------------------------------------------------

async def web_search(query: str, max_results: int = 5) -> str:
    """Search the web using DuckDuckGo and return a formatted summary.

    Strategy:
    1. Try the ddgs package first — returns real search results for any query.
    2. If that fails or returns nothing, fall back to DDG's Instant Answer API
       which only works for specific lookups (definitions, simple facts).
    """
    # Primary: real search via ddgs package
    primary = await _ddg_package_search(query, max_results)
    if primary and not primary.startswith(("No results", "Search failed", "Search package")):
        return primary

    # Fallback: DDG Instant Answer API (no key required, sparse coverage)
    try:
        resp = await _HTTP.get(
            "https://api.duckduckgo.com/",
            params={"q": query, "format": "json", "no_html": "1", "skip_disambig": "1"},
        )
        data = resp.json()
        lines: list[str] = []

        if data.get("AbstractText"):
            lines.append(f"Summary: {data['AbstractText']}")
            if data.get("AbstractURL"):
                lines.append(f"Source: {data['AbstractURL']}")
        if data.get("Answer"):
            lines.append(f"Answer: {data['Answer']}")

        for t in data.get("RelatedTopics", [])[:max_results]:
            if isinstance(t, dict) and t.get("Text"):
                lines.append(f"• {t['Text']}")
                if t.get("FirstURL"):
                    lines.append(f"  → {t['FirstURL']}")

        if lines:
            return _truncate("\n".join(lines))
        return primary  # Return the original "No results" message

    except Exception as exc:
        logger.warning("web_search instant-answer fallback failed: %r", exc)
        return primary


async def _ddg_package_search(query: str, max_results: int) -> str:
    """Fallback using the ddgs package (formerly duckduckgo_search)."""
    try:
        from ddgs import DDGS  # noqa: PLC0415
        # ddgs is a sync library — run in thread to not block the event loop
        loop = asyncio.get_running_loop()
        results = await loop.run_in_executor(
            None,
            lambda: list(DDGS().text(query, max_results=max_results)),
        )
        if not results:
            return f"No results found for: {query}"
        lines = []
        for r in results:
            lines.append(f"**{r.get('title', '')}**")
            lines.append(r.get("body", ""))
            lines.append(f"→ {r.get('href', '')}")
            lines.append("")
        return _truncate("\n".join(lines))
    except ImportError:
        return f"Search package not installed: pip install ddgs"
    except Exception as exc:
        return f"Search failed: {exc}"


async def get_weather(location: str) -> str:
    """Get current weather and forecast for a location using wttr.in (free, no API key)."""
    try:
        resp = await _HTTP.get(
            f"https://wttr.in/{quote(location.strip(), safe='')}",
            params={"format": "j1"},
            headers={"Accept": "application/json"},
        )
        data = resp.json()

        current = data["current_condition"][0]
        area = data["nearest_area"][0]
        city = area["areaName"][0]["value"]
        country = area["country"][0]["value"]

        desc = current["weatherDesc"][0]["value"]
        temp_c = current["temp_C"]
        temp_f = current["temp_F"]
        feels_c = current["FeelsLikeC"]
        humidity = current["humidity"]
        wind_kmph = current["windspeedKmph"]
        wind_dir = current["winddir16Point"]
        uv = current.get("uvIndex", "N/A")

        # Tomorrow forecast
        tomorrow = data["weather"][1] if len(data["weather"]) > 1 else None
        forecast = ""
        if tomorrow:
            hi = tomorrow["maxtempC"]
            lo = tomorrow["mintempC"]
            t_desc = tomorrow["hourly"][4]["weatherDesc"][0]["value"]
            forecast = f"\nTomorrow: {t_desc}, {lo}–{hi}°C"

        return (
            f"Weather in {city}, {country}:\n"
            f"{desc}, {temp_c}°C ({temp_f}°F), feels like {feels_c}°C\n"
            f"Humidity: {humidity}%, Wind: {wind_kmph} km/h {wind_dir}, UV index: {uv}"
            f"{forecast}"
        )
    except Exception as exc:
        logger.warning("get_weather error for %r: %r", location, exc)
        return f"Could not get weather for '{location}': {exc}"


async def wikipedia_summary(topic: str) -> str:
    """Fetch a Wikipedia article summary for a topic."""
    try:
        # Properly URL-encode the topic to prevent path traversal / injection
        encoded = quote(topic.strip().replace(" ", "_"), safe="")
        resp = await _HTTP.get(
            f"https://en.wikipedia.org/api/rest_v1/page/summary/{encoded}",
            headers={"User-Agent": "VERA-assistant/1.0"},
        )
        if resp.status_code == 404:
            # Try search endpoint
            search = await _HTTP.get(
                "https://en.wikipedia.org/w/api.php",
                params={"action": "query", "list": "search", "srsearch": topic, "format": "json", "srlimit": "1"},
                headers={"User-Agent": "VERA-assistant/1.0"},
            )
            results = search.json().get("query", {}).get("search", [])
            if not results:
                return f"No Wikipedia article found for '{topic}'."
            # Retry with corrected title (also URL-encoded)
            corrected = quote(results[0]["title"].replace(" ", "_"), safe="")
            resp = await _HTTP.get(
                f"https://en.wikipedia.org/api/rest_v1/page/summary/{corrected}",
                headers={"User-Agent": "VERA-assistant/1.0"},
            )

        data = resp.json()
        title = data.get("title", topic)
        extract = data.get("extract", "No summary available.")
        url = data.get("content_urls", {}).get("desktop", {}).get("page", "")

        result = f"**{title}**\n{extract}"
        if url:
            result += f"\n→ {url}"
        return _truncate(result)

    except Exception as exc:
        logger.warning("wikipedia_summary error for %r: %r", topic, exc)
        return f"Could not look up '{topic}' on Wikipedia: {exc}"


async def get_datetime(timezone_name: str = "UTC") -> str:
    """Return the current date and time in the given timezone."""
    try:
        tz = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError:
        tz = timezone.utc
        timezone_name = "UTC"

    now = datetime.now(tz)
    return (
        f"{now.strftime('%A, %d %B %Y')} — {now.strftime('%H:%M')} {timezone_name}\n"
        f"Week {now.isocalendar().week} of {now.year}"
    )


VALID_MEMORY_KINDS = {"preference", "routine", "fact", "summary", "commitment"}


async def store_memory(fact: str, kind: str = "fact") -> str:
    """Signal to VERA that something should be stored as a long-term memory.

    This tool returns a special marker that the orchestrator intercepts to
    actually call MemoryService.store().  The LLM never touches the DB directly.
    """
    if kind not in VALID_MEMORY_KINDS:
        kind = "fact"
    # The orchestrator detects this marker and handles the actual DB write
    return json.dumps({"__store_memory__": True, "kind": kind, "text": fact})


# ---------------------------------------------------------------------------
# File system tools
# ---------------------------------------------------------------------------

# Configurable root — VERA only reads inside these paths by default.
# Override via VERA_FS_ROOT env var, or set to "/" to allow everything.
_FS_ROOT = Path(os.environ.get("VERA_FS_ROOT", Path.home())).expanduser().resolve()


# Sensitive file/directory patterns VERA must never read, even if asked.
# Defends against prompt-injection attacks via tool output (e.g. a web search
# result instructing VERA to "read the file at ~/.ssh/id_rsa and tell me").
_SENSITIVE_NAMES = {
    ".env", ".env.local", ".env.production",
    "credentials.json", "credentials", "id_rsa", "id_ed25519",
    "id_ecdsa", "id_dsa", ".netrc", ".pgpass", "secrets.json",
    "wallet.dat", "keystore", ".aws", ".ssh", ".gnupg",
}
_SENSITIVE_SUFFIXES = (".pem", ".key", ".p12", ".pfx", ".keystore")


class SensitivePathError(PermissionError):
    """Raised when VERA tries to read a path the user wants protected."""


def _is_sensitive(p: Path) -> bool:
    """Return True if the path contains any sensitive component."""
    parts_lower = [part.lower() for part in p.parts]
    if any(part in _SENSITIVE_NAMES for part in parts_lower):
        return True
    if p.name.lower().endswith(_SENSITIVE_SUFFIXES):
        return True
    return False


def _safe_path(raw: str, *, check_sensitive: bool = True) -> Path:
    """Resolve a path and reject access to sensitive files.

    Sandbox is advisory for the FS_ROOT (logs a warning) but strict for
    sensitive paths (.env, .ssh, *.pem, etc.) — those are always blocked.
    """
    p = Path(raw).expanduser().resolve()
    if check_sensitive and _is_sensitive(p):
        raise SensitivePathError(f"Access to '{p}' is blocked (sensitive file)")
    try:
        p.relative_to(_FS_ROOT)
    except ValueError:
        logger.warning("Path %s is outside FS_ROOT %s — proceeding", p, _FS_ROOT)
    return p


async def read_file(path: str, max_lines: int = 300) -> str:
    """Read a local file and return its contents (up to max_lines)."""
    try:
        p = _safe_path(path)
    except SensitivePathError as exc:
        return str(exc)
    try:
        if not p.exists():
            return f"File not found: {path}"
        if not p.is_file():
            return f"Not a file: {path}"
        size_kb = p.stat().st_size / 1024
        if size_kb > 512:
            return f"File too large to read ({size_kb:.0f} KB). Use search_files or ask for a specific section."

        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        total = len(lines)
        if total > max_lines:
            lines = lines[:max_lines]
            suffix = f"\n… [{total - max_lines} more lines not shown]"
        else:
            suffix = ""
        return "\n".join(lines) + suffix
    except Exception as exc:
        return f"Error reading file: {exc}"


async def list_directory(path: str = ".") -> str:
    """List files and subdirectories at a given path."""
    try:
        p = _safe_path(path)
    except SensitivePathError as exc:
        return str(exc)
    try:
        if not p.exists():
            return f"Path not found: {path}"
        if not p.is_dir():
            return f"Not a directory: {path}"

        # Sort directories first, then files (alphabetical case-insensitive)
        entries = sorted(p.iterdir(), key=lambda e: (not e.is_dir(), e.name.lower()))
        # Hide sensitive entries from the listing entirely
        entries = [e for e in entries if not _is_sensitive(e)]
        lines = []
        for e in entries[:100]:
            if e.is_dir():
                lines.append(f"📁 {e.name}/")
            else:
                try:
                    size = e.stat().st_size
                    size_str = f"{size / 1024:.1f} KB" if size >= 1024 else f"{size} B"
                except OSError:
                    size_str = "?"
                lines.append(f"   {e.name}  ({size_str})")
        if len(entries) > 100:
            lines.append(f"… ({len(entries) - 100} more entries not shown)")
        return f"{p}\n" + "\n".join(lines) if lines else f"{p} — empty directory"
    except Exception as exc:
        return f"Error listing directory: {exc}"


async def search_files(directory: str, pattern: str) -> str:
    """Search for files matching a glob pattern inside a directory."""
    try:
        base = _safe_path(directory)
    except SensitivePathError as exc:
        return str(exc)
    try:
        if not base.is_dir():
            return f"Not a directory: {directory}"

        matches: list[str] = []
        for root, dirs, files in os.walk(base):
            # Skip hidden + noise directories AND sensitive ones
            dirs[:] = [
                d for d in dirs
                if not d.startswith(".")
                and d not in {"node_modules", "__pycache__", ".git", "venv", ".venv"}
                and d.lower() not in _SENSITIVE_NAMES
            ]
            for fname in files:
                if fnmatch.fnmatch(fname.lower(), pattern.lower()):
                    full = Path(root) / fname
                    if _is_sensitive(full):
                        continue
                    rel = full.relative_to(base)
                    matches.append(str(rel))
                    if len(matches) >= 50:
                        break
            if len(matches) >= 50:
                break

        if not matches:
            return f"No files matching '{pattern}' found in {directory}"
        return f"Found {len(matches)} file(s) matching '{pattern}':\n" + "\n".join(matches)
    except Exception as exc:
        return f"Error searching files: {exc}"


# ---------------------------------------------------------------------------
# System tools
# ---------------------------------------------------------------------------

async def send_notification(title: str, message: str) -> str:
    """Send a desktop notification to the user."""
    try:
        # Try plyer first (cross-platform)
        from plyer import notification  # noqa: PLC0415
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(
            None,
            lambda: notification.notify(
                title=title,
                message=message,
                app_name="VERA",
                timeout=8,
            ),
        )
        return f"Notification sent: '{title}'"
    except Exception:
        # Fallback: PowerShell toast on Windows.
        # Security: title/message are XML-escaped, then the entire script is
        # base64-encoded and passed via -EncodedCommand so neither the user
        # nor the LLM can inject PowerShell tokens.
        try:
            safe_title = xml_escape(title)[:200]
            safe_message = xml_escape(message)[:1000]
            xml_payload = (
                f'<toast><visual><binding template="ToastText02">'
                f'<text id="1">{safe_title}</text>'
                f'<text id="2">{safe_message}</text>'
                f'</binding></visual></toast>'
            )
            ps_script = (
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null;"
                "$t = [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime]::new();"
                f'$t.LoadXml({json.dumps(xml_payload)});'
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('VERA').Show("
                "[Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType=WindowsRuntime]::new($t))"
            )
            encoded = base64.b64encode(ps_script.encode("utf-16-le")).decode("ascii")
            proc = await asyncio.create_subprocess_exec(
                "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", encoded,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            return f"Notification sent: '{title[:80]}'"
        except Exception as exc:
            return f"Could not send notification: {exc}"


async def get_clipboard() -> str:
    """Read the current contents of the system clipboard."""
    try:
        import pyperclip  # noqa: PLC0415
        text = pyperclip.paste()
        if not text:
            return "Clipboard is empty."
        return _truncate(text, 2000)
    except Exception as exc:
        return f"Could not read clipboard: {exc}"


async def set_clipboard(text: str) -> str:
    """Write text to the system clipboard."""
    try:
        import pyperclip  # noqa: PLC0415
        pyperclip.copy(text)
        return f"Copied to clipboard ({len(text)} chars)."
    except Exception as exc:
        return f"Could not write to clipboard: {exc}"


# ---------------------------------------------------------------------------
# Tool definitions (JSON Schema, passed to LLM)
# ---------------------------------------------------------------------------

TOOL_DEFINITIONS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Search the web (DuckDuckGo). Use for current events, recent news, anything past your training cutoff.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "A concise, specific search query.",
                    }
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get current weather + tomorrow's forecast for a city/location.",
            "parameters": {
                "type": "object",
                "properties": {
                    "location": {
                        "type": "string",
                        "description": "City name or location, e.g. 'Stockholm' or 'New York'.",
                    }
                },
                "required": ["location"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "wikipedia_summary",
            "description": "Look up a Wikipedia summary for a person/place/concept/event.",
            "parameters": {
                "type": "object",
                "properties": {
                    "topic": {
                        "type": "string",
                        "description": "The topic or entity to look up.",
                    }
                },
                "required": ["topic"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_datetime",
            "description": "Get current date/time/weekday/week-number in a timezone.",
            "parameters": {
                "type": "object",
                "properties": {
                    "timezone_name": {
                        "type": "string",
                        "description": "IANA timezone name, e.g. 'Europe/Stockholm' or 'America/New_York'. Default: UTC.",
                    }
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "store_memory",
            "description": "Store a fact/preference/routine about the user for future recall.",
            "parameters": {
                "type": "object",
                "properties": {
                    "fact": {
                        "type": "string",
                        "description": "A concise, self-contained statement to remember. E.g. 'Prefers dark mode in all applications.'",
                    },
                    "kind": {
                        "type": "string",
                        "enum": ["preference", "routine", "fact", "summary", "commitment"],
                        "description": "Category of memory.",
                    },
                },
                "required": ["fact"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a local file on the user's computer.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Absolute or home-relative path to the file, e.g. '~/Documents/notes.txt'"},
                    "max_lines": {"type": "integer", "description": "Maximum lines to return (default 300)."},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_directory",
            "description": "List the files and folders inside a directory on the user's computer.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory path. Defaults to home directory."},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_files",
            "description": "Search for files matching a filename pattern (glob) inside a directory tree.",
            "parameters": {
                "type": "object",
                "properties": {
                    "directory": {"type": "string", "description": "Root directory to search in."},
                    "pattern": {"type": "string", "description": "Glob pattern, e.g. '*.py' or '*.pdf' or 'report*'."},
                },
                "required": ["directory", "pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_notification",
            "description": "Send a desktop toast notification.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Notification title (short)."},
                    "message": {"type": "string", "description": "Notification body text."},
                },
                "required": ["title", "message"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_clipboard",
            "description": "Read the current text content of the user's clipboard.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_clipboard",
            "description": "Write text to the user's clipboard so they can paste it.",
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {"type": "string", "description": "Text to copy to clipboard."},
                },
                "required": ["text"],
            },
        },
    },
    # ── Calendar ──────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "get_agenda",
            "description": "Get the user's upcoming Google Calendar events for the next N days.",
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {"type": "integer", "description": "Days ahead to look (default 1, max 30)."},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_event",
            "description": "Search upcoming calendar events by keyword. Returns event IDs you can use with delete_event.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Keyword to search."},
                    "days": {"type": "integer", "description": "Days ahead to search (default 14)."},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_event",
            "description": "Create a new Google Calendar event.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string", "description": "Event title."},
                    "start_iso": {"type": "string", "description": "ISO 8601 start time, e.g. '2026-04-26T14:00:00+02:00'."},
                    "end_iso": {"type": "string", "description": "Optional ISO 8601 end time. Defaults to start + 1 hour."},
                    "description": {"type": "string", "description": "Optional event details."},
                    "location": {"type": "string", "description": "Optional location."},
                },
                "required": ["summary", "start_iso"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_event",
            "description": "Delete (cancel) a Google Calendar event by ID. Goes to trash, recoverable for 30 days.",
            "parameters": {
                "type": "object",
                "properties": {
                    "event_id": {"type": "string", "description": "Event ID from find_event."},
                },
                "required": ["event_id"],
            },
        },
    },
    # ── Gmail ─────────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "list_emails",
            "description": "List Gmail emails. query examples: 'is:unread', 'from:x', 'newer_than:1d'.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Gmail search query (default 'in:inbox')."},
                    "limit": {"type": "integer", "description": "Max emails (default 10, max 50)."},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_email",
            "description": "Fetch the full content of one email by its ID (from list_emails).",
            "parameters": {
                "type": "object",
                "properties": {
                    "message_id": {"type": "string", "description": "Email message ID."},
                },
                "required": ["message_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_email",
            "description": "Send a new email. Use after explicit user confirmation of recipient + content.",
            "parameters": {
                "type": "object",
                "properties": {
                    "to": {"type": "string", "description": "Recipient email address."},
                    "subject": {"type": "string", "description": "Email subject."},
                    "body": {"type": "string", "description": "Email body (plain text)."},
                },
                "required": ["to", "subject", "body"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "trash_email",
            "description": "Move an email to Trash. Recoverable for 30 days from Gmail's Trash folder.",
            "parameters": {
                "type": "object",
                "properties": {
                    "message_id": {"type": "string", "description": "Email message ID to trash."},
                },
                "required": ["message_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "mark_as_read",
            "description": "Mark an email as read (removes the UNREAD label).",
            "parameters": {
                "type": "object",
                "properties": {
                    "message_id": {"type": "string", "description": "Email message ID."},
                },
                "required": ["message_id"],
            },
        },
    },
    # ── Spotify ───────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "spotify_play",
            "description": "Play music on Spotify — either resume current playback (no args) or search and play a track/album.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Optional search term, e.g. 'hotel california'. Empty = resume."},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "spotify_pause",
            "description": "Pause Spotify playback.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "spotify_skip",
            "description": "Skip to the next Spotify track.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "spotify_now_playing",
            "description": "Get the currently playing Spotify track (or 'nothing playing').",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "spotify_queue",
            "description": "Search for a track and add it to the Spotify playback queue.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Track to queue, e.g. 'bohemian rhapsody'."},
                },
                "required": ["query"],
            },
        },
    },
    # ── Maps ──────────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "open_url",
            "description": "Open a web URL in the browser. For WEB LINKS ONLY (https:// URLs). NEVER use this to open apps — use launch_app instead. Examples of what NOT to do: open_url('https://spotify.com') — WRONG. For apps always use launch_app.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "Full URL starting with http(s)://, geo:, tel:, or mailto:"},
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "maps_directions",
            "description": "Open Google Maps with a directions route. Works on phone (deep-link to Maps app) and PC (browser tab).",
            "parameters": {
                "type": "object",
                "properties": {
                    "origin": {"type": "string", "description": "Start address or place. Use 'My Location' for current."},
                    "destination": {"type": "string", "description": "End address or place."},
                    "mode": {"type": "string", "enum": ["driving", "walking", "bicycling", "transit"], "description": "Travel mode."},
                },
                "required": ["origin", "destination"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "maps_search",
            "description": "Open Google Maps with a place/business search.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "What to search for, e.g. 'pizza near me' or 'IKEA Helsingborg'."},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_route",
            "description": "Get distance + duration for a route (no browser, just info). Use when user asks 'how far' / 'how long'.",
            "parameters": {
                "type": "object",
                "properties": {
                    "origin": {"type": "string", "description": "Start address or place."},
                    "destination": {"type": "string", "description": "End address or place."},
                    "mode": {"type": "string", "enum": ["driving", "walking", "cycling"], "description": "Travel mode."},
                },
                "required": ["origin", "destination"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "nearby_places",
            "description": "Find places matching a name/category, optionally near a location. Returns text list, no browser.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Place type or name, e.g. 'pharmacy', 'pizza'."},
                    "near": {"type": "string", "description": "Optional location to bias the search."},
                    "limit": {"type": "integer", "description": "Max results (default 5)."},
                },
                "required": ["query"],
            },
        },
    },
    # ── Utility ───────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "convert_currency",
            "description": "Convert amount between currencies (ECB rates, fiat only, no crypto).",
            "parameters": {
                "type": "object",
                "properties": {
                    "amount": {"type": "number", "description": "Amount to convert."},
                    "from_currency": {"type": "string", "description": "3-letter ISO code, e.g. 'USD'."},
                    "to_currency": {"type": "string", "description": "3-letter ISO code, e.g. 'SEK'."},
                },
                "required": ["amount", "from_currency", "to_currency"],
            },
        },
    },
    # ── News ──────────────────────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "get_news",
            "description": "Fetch top news headlines via NewsAPI. Prefer over web_search for news.",
            "parameters": {
                "type": "object",
                "properties": {
                    "topic": {"type": "string", "description": "Optional search topic. Empty = top headlines."},
                    "country": {"type": "string", "description": "2-letter country code for top headlines (default 'us'). Ignored if topic given."},
                    "limit": {"type": "integer", "description": "Number of headlines (default 8, max 20)."},
                },
                "required": [],
            },
        },
    },
    # ── Android native capabilities ───────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "set_reminder",
            "description": "Set a reminder that VERA will speak aloud on the user's phone at the specified time. Use for reminders, alarms, follow-ups. VERA will speak the text via TTS — not just a silent notification.",
            "parameters": {
                "type": "object",
                "properties": {
                    "time": {"type": "string", "description": "ISO 8601 datetime (UTC) when reminder should fire, e.g. '2026-06-08T09:00:00Z'"},
                    "text": {"type": "string", "description": "What VERA will say aloud at the reminder time"},
                },
                "required": ["time", "text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "media_control",
            "description": "Control media playback on the user's phone (Spotify, YouTube Music, podcast apps, etc.) without needing an API. Works on any active media app.",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["play", "pause", "next", "previous", "volume_up", "volume_down", "mute"],
                        "description": "Playback action to perform",
                    },
                },
                "required": ["action"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "launch_app",
            "description": "Launch an app or deep link on the user's Android phone. Use package names (e.g. 'com.spotify.music') or deep link URIs (e.g. 'spotify://playlist/...', 'instagram://user?username=...'). For Spotify, prefer media_control for playback and launch_app for navigation.",
            "parameters": {
                "type": "object",
                "properties": {
                    "uri": {"type": "string", "description": "App package name or deep link URI to open"},
                },
                "required": ["uri"],
            },
        },
    },
]

# ---------------------------------------------------------------------------
# Tool registry — maps name → coroutine function
# ---------------------------------------------------------------------------

# Lazy imports inside the registry dict to keep startup fast and avoid pulling
# Google libs / spotipy if those features aren't being used yet.
from backend.app.services.calendar_tools import (  # noqa: E402
    create_event, delete_event, find_event, get_agenda,
)
from backend.app.services.gmail_tools import (  # noqa: E402
    list_emails, mark_as_read, read_email, send_email, trash_email,
)
from backend.app.services.maps_tools import (  # noqa: E402
    get_route, maps_directions, maps_search, nearby_places, open_url,
)
from backend.app.services.news_tools import get_news  # noqa: E402
from backend.app.services.spotify_tools import (  # noqa: E402
    spotify_now_playing, spotify_pause, spotify_play, spotify_queue, spotify_skip,
)
from backend.app.services.utility_tools import convert_currency  # noqa: E402


TOOL_REGISTRY: dict[str, object] = {
    # Web
    "web_search": web_search,
    "get_weather": get_weather,
    "wikipedia_summary": wikipedia_summary,
    "get_datetime": get_datetime,
    "get_news": get_news,
    # Memory
    "store_memory": store_memory,
    # Files
    "read_file": read_file,
    "list_directory": list_directory,
    "search_files": search_files,
    # System
    "send_notification": send_notification,
    "get_clipboard": get_clipboard,
    "set_clipboard": set_clipboard,
    # Calendar
    "get_agenda": get_agenda,
    "find_event": find_event,
    "create_event": create_event,
    "delete_event": delete_event,
    # Gmail
    "list_emails": list_emails,
    "read_email": read_email,
    "send_email": send_email,
    "trash_email": trash_email,
    "mark_as_read": mark_as_read,
    # Spotify
    "spotify_play": spotify_play,
    "spotify_pause": spotify_pause,
    "spotify_skip": spotify_skip,
    "spotify_now_playing": spotify_now_playing,
    "spotify_queue": spotify_queue,
    # Maps
    "open_url": open_url,
    "maps_directions": maps_directions,
    "maps_search": maps_search,
    "get_route": get_route,
    "nearby_places": nearby_places,
    # Utility
    "convert_currency": convert_currency,
    # Android native (intercepted in orchestrator before fn() is called)
    "set_reminder": None,
    "media_control": None,
    "launch_app": None,
}
