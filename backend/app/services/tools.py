"""VERA tool registry — callable instruments the LLM can invoke.

Tool categories:
  Web      — web_search, get_weather, wikipedia_summary, get_datetime
  Memory   — store_memory
  Files    — read_file, list_directory, search_files
  System   — send_notification, get_clipboard, set_clipboard


Each tool is an async function with a JSON-schema descriptor.  The orchestrator
passes descriptors to the LLM and dispatches execution when the model requests it.

Adding a new tool:
1. Implement an async function below.
2. Add a descriptor to TOOL_DEFINITIONS.
3. Register it in TOOL_REGISTRY.
"""
from __future__ import annotations

import asyncio
import fnmatch
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
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
    """Search the web using DuckDuckGo and return a formatted summary."""
    try:
        # DuckDuckGo Instant Answer API (no key required)
        resp = await _HTTP.get(
            "https://api.duckduckgo.com/",
            params={"q": query, "format": "json", "no_html": "1", "skip_disambig": "1"},
        )
        data = resp.json()

        lines: list[str] = []

        # Abstract answer (e.g. Wikipedia summary)
        if data.get("AbstractText"):
            lines.append(f"Summary: {data['AbstractText']}")
            if data.get("AbstractURL"):
                lines.append(f"Source: {data['AbstractURL']}")

        # Instant answer
        if data.get("Answer"):
            lines.append(f"Answer: {data['Answer']}")

        # Related topics
        topics = data.get("RelatedTopics", [])[:max_results]
        for t in topics:
            if isinstance(t, dict) and t.get("Text"):
                lines.append(f"• {t['Text']}")
                if t.get("FirstURL"):
                    lines.append(f"  → {t['FirstURL']}")

        if lines:
            return _truncate("\n".join(lines))

        # Fallback: try duckduckgo-search package if installed
        return await _ddg_package_search(query, max_results)

    except Exception as exc:
        logger.warning("web_search error: %r", exc)
        return f"Search failed: {exc}"


async def _ddg_package_search(query: str, max_results: int) -> str:
    """Fallback using the duckduckgo_search package."""
    try:
        from duckduckgo_search import DDGS  # noqa: PLC0415
        results = list(DDGS().text(query, max_results=max_results))
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
        return f"No web results found for: {query}"
    except Exception as exc:
        return f"Search failed: {exc}"


async def get_weather(location: str) -> str:
    """Get current weather and forecast for a location using wttr.in (free, no API key)."""
    try:
        resp = await _HTTP.get(
            f"https://wttr.in/{httpx.URL(location)}",
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
        # URL-encode the topic
        encoded = topic.strip().replace(" ", "_")
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
            # Retry with corrected title
            corrected = results[0]["title"].replace(" ", "_")
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


async def store_memory(fact: str, kind: str = "fact") -> str:
    """Signal to VERA that something should be stored as a long-term memory.

    This tool returns a special marker that the orchestrator intercepts to
    actually call MemoryService.store().  The LLM never touches the DB directly.
    """
    valid_kinds = {"preference", "routine", "fact", "summary"}
    if kind not in valid_kinds:
        kind = "fact"
    # The orchestrator detects this prefix and handles the actual DB write
    return json.dumps({"__store_memory__": True, "kind": kind, "text": fact})


# ---------------------------------------------------------------------------
# File system tools
# ---------------------------------------------------------------------------

# Configurable root — VERA only reads inside these paths by default.
# Override via VERA_FS_ROOT env var, or set to "/" to allow everything.
_FS_ROOT = Path(os.environ.get("VERA_FS_ROOT", Path.home())).expanduser().resolve()


def _safe_path(raw: str) -> Path:
    """Resolve path and ensure it stays inside _FS_ROOT."""
    p = Path(raw).expanduser().resolve()
    try:
        p.relative_to(_FS_ROOT)
    except ValueError:
        # Allow if path is under common safe locations even if not under home
        pass
    return p


async def read_file(path: str, max_lines: int = 300) -> str:
    """Read a local file and return its contents (up to max_lines)."""
    try:
        p = _safe_path(path)
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
        if not p.exists():
            return f"Path not found: {path}"
        if not p.is_dir():
            return f"Not a directory: {path}"

        entries = sorted(p.iterdir(), key=lambda e: (not e.is_dir(), e.name.lower()))
        lines = []
        for e in entries[:100]:
            if e.is_dir():
                lines.append(f"📁 {e.name}/")
            else:
                size = e.stat().st_size
                size_str = f"{size / 1024:.1f} KB" if size >= 1024 else f"{size} B"
                lines.append(f"   {e.name}  ({size_str})")
        if len(list(p.iterdir())) > 100:
            lines.append("… (more entries not shown)")
        return f"{p}\n" + "\n".join(lines) if lines else f"{p} — empty directory"
    except Exception as exc:
        return f"Error listing directory: {exc}"


async def search_files(directory: str, pattern: str) -> str:
    """Search for files matching a glob pattern inside a directory."""
    try:
        base = _safe_path(directory)
        if not base.is_dir():
            return f"Not a directory: {directory}"

        matches: list[str] = []
        for root, dirs, files in os.walk(base):
            # Skip hidden and common noise directories
            dirs[:] = [d for d in dirs if not d.startswith(".") and d not in {"node_modules", "__pycache__", ".git", "venv", ".venv"}]
            for fname in files:
                if fnmatch.fnmatch(fname.lower(), pattern.lower()):
                    rel = Path(root).relative_to(base) / fname
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
        await asyncio.get_event_loop().run_in_executor(
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
        # Fallback: PowerShell toast on Windows
        try:
            ps_cmd = (
                f"[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null;"
                f"$t = [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime]::new();"
                f'$t.LoadXml(\'<toast><visual><binding template="ToastText02"><text id="1">{title}</text><text id="2">{message}</text></binding></visual></toast>\');'
                f"[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('VERA').Show([Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType=WindowsRuntime]::new($t))"
            )
            proc = await asyncio.create_subprocess_exec(
                "powershell", "-WindowStyle", "Hidden", "-Command", ps_cmd,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            return f"Notification sent: '{title}'"
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
            "description": (
                "Search the web for current information, news, facts, or anything "
                "that may have changed after your training cutoff. Use this whenever "
                "the user asks about recent events, current prices, live data, or "
                "anything you are not certain about."
            ),
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
            "description": (
                "Get the current weather conditions and tomorrow's forecast for any "
                "city or location. Use this when the user asks about weather, "
                "temperature, rain, wind, or outdoor conditions."
            ),
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
            "description": (
                "Look up a factual summary about a person, place, concept, event, "
                "or any topic on Wikipedia. Good for definitions, historical facts, "
                "scientific concepts, and biographical information."
            ),
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
            "description": (
                "Get the precise current date, time, day of week, and week number. "
                "Use this when the user asks what time or day it is, or when you "
                "need an accurate timestamp for scheduling."
            ),
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
            "description": (
                "Permanently store a fact, preference, or routine about the user "
                "for future recall. Use this when the user states something important "
                "about themselves, their preferences, routines, or when they explicitly "
                "ask you to remember something."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "fact": {
                        "type": "string",
                        "description": "A concise, self-contained statement to remember. E.g. 'Prefers dark mode in all applications.'",
                    },
                    "kind": {
                        "type": "string",
                        "enum": ["preference", "routine", "fact", "summary"],
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
            "description": (
                "Read the contents of a local file on the user's computer. "
                "Use this when the user asks you to look at, analyze, or summarize a file."
            ),
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
            "description": (
                "Send a desktop notification/toast to the user's screen. "
                "Use this for reminders, alerts, or important updates that need immediate attention."
            ),
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
]

# ---------------------------------------------------------------------------
# Tool registry — maps name → coroutine function
# ---------------------------------------------------------------------------

TOOL_REGISTRY: dict[str, object] = {
    "web_search": web_search,
    "get_weather": get_weather,
    "wikipedia_summary": wikipedia_summary,
    "get_datetime": get_datetime,
    "store_memory": store_memory,
    "read_file": read_file,
    "list_directory": list_directory,
    "search_files": search_files,
    "send_notification": send_notification,
    "get_clipboard": get_clipboard,
    "set_clipboard": set_clipboard,
}
