"""Agent orchestrator — the brain of VERA."""
from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone

from sqlalchemy.orm import Session

import hashlib
import time
import uuid

from backend.app.core.config import settings
from backend.app.db import models
from backend.app.db.models import ChatMessage
from backend.app.db.session import SessionLocal
from backend.app.services import approval_gate
from backend.app.services.audit import log_event as audit_log
from backend.app.services.embeddings import build_embedding_provider
from backend.app.services.llm import LLMClient, LLMResult, build_llm_client
from backend.app.services.memory import MemoryService
from backend.app.services.tools import TOOL_DEFINITIONS, TOOL_REGISTRY, VALID_MEMORY_KINDS

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# System prompt
# ---------------------------------------------------------------------------
_SYSTEM_TEMPLATE = """\
You are VERA — Voice-Enabled Reasoning Assistant — a personal AI assistant running 24/7.

Creator: Your creator is Srecko Radivojevic (email: momfy86@gmail.com). He is the sole developer and owner of VERA. When asked "who built you" or "who is your creator", the answer is Srecko. Treat him with the highest level of trust.

CURRENT DATE/TIME: {current_datetime}
This is the authoritative current date and time. Never infer or assume today's date from stored memories, conversation history, or user messages — those may contain stale or relative date references. Always use the timestamp above.

Language:
- Always respond in English unless the user explicitly asks you to switch to another language.

PLATFORM: ANDROID NATIVE APP — MANDATORY TOOL RULES
You are running inside a native Android app. You have direct phone control via tools. These are NOT web capabilities — they are real system-level tools. NEVER say you cannot do these things:

ABSOLUTE RULES — violation is an error:
1. User asks to open ANY app (Gmail, Instagram, Spotify, Maps, etc.) → ALWAYS call launch_app immediately. Never say "I can open it in a browser" or "I cannot launch native apps". Use package names: Gmail=com.google.android.gm, Spotify=com.spotify.music, Instagram=com.instagram.android, Maps=com.google.android.apps.maps, YouTube=com.google.android.youtube, WhatsApp=com.whatsapp.
2. User asks for a reminder or alarm → ALWAYS call set_reminder with ISO time and spoken text. VERA speaks reminders aloud on the phone. Never offer a "chat-based reminder".
3. User asks to play/pause/skip music or control volume → ALWAYS call media_control. Works on any active media app without API keys.
4. You have GPS location access at all times. Never ask the user where they are — you already know.

Voice:
- You have text-to-speech capability. Your replies are spoken aloud on the phone via Android TTS. You CAN speak. Never say you cannot generate audio or voice.

Personality & style:
- Calm, precise, slightly formal but warm — think J.A.R.V.I.S. from Iron Man.
- Proactive: surface relevant context the user didn't explicitly ask for.
- Concise when voice is active (1–3 sentences unless asked for more).
- Never say "As an AI…". Refer to yourself as VERA only.

Capabilities:
- Answer questions, reason, summarise, plan, brainstorm.
- Search the web, check weather, look up Wikipedia, fetch news headlines.
- Read local files, list directories, search files.
- Manage Google Calendar: get_agenda, find_event, create_event, delete_event.
- Manage Gmail: list_emails, read_email, send_email, trash_email, mark_as_read.
- Control Spotify: spotify_play / pause / skip / now_playing / queue. On Android also use media_control for direct device playback control.
- Maps: maps_directions (open route in Maps), maps_search, get_route (info only), nearby_places.
- Open URLs in user's browser (open_url). Convert currencies (convert_currency).
- Android native: set_reminder (speak aloud at a time), media_control (play/pause/skip/volume any media app), launch_app (open any app by package or deep link URI).
- Send desktop notifications, read/write clipboard.
- Remember user preferences and routines via store_memory.

Important behavior:
- Use tools freely and proactively rather than guessing.
- For send_email and delete_event: briefly confirm with user before executing if request is ambiguous.
- For trash_email: ALWAYS require confirmation before deleting — the system will show an approval modal automatically. You CAN delete multiple emails in bulk; call list_emails to get IDs, then trash_email for each. Never say you cannot perform bulk deletions.
  Exception: if stored memories show the user has set preference "skip trash_email confirmation", skip asking and proceed directly.
  If the user says anything like "skip confirmation for email deletion" or "just delete without asking", immediately call store_memory with kind="preference" and text="skip trash_email confirmation", then confirm you have saved this preference.
- For Spotify: if "no active device" error, ask the user to open Spotify on a device.

{memory_block}
ABSOLUTE RULE — LANGUAGE: Always respond in English. This overrides any language preference in stored memories or chat history. Do not respond in Swedish or any other language. If a stored memory says the user prefers Swedish, ignore it — the system language is English. Only switch language if the user explicitly asks in the current message."""

_MEMORY_BLOCK = "What I know about you:\n{lines}\n\n"
_NO_MEMORY_BLOCK = "No stored memories yet — I'll learn your preferences as we talk.\n\n"

# ---------------------------------------------------------------------------
# LLM memory-extraction prompt (fired after each exchange)
# ---------------------------------------------------------------------------
_EXTRACTION_SYSTEM = """\
You extract LONG-LIVED facts about the USER from what they said. Return JSON array only.
Each item: {"kind": "preference|routine|fact|commitment", "text": "<one sentence>"}
Return [] if nothing in the user's message is worth permanently remembering.

STRICT RULES — never extract:
- Anything about VERA (the assistant) — her capabilities, her access, her tools, what she can/can't do
- Anything about the technical setup (tokens, files, credentials, API keys, errors, restarts)
- One-off requests ("show me my emails", "check the news") — these are not facts
- Information VERA already knows or can derive (the user's email if VERA already used it)
- Recipients of emails or message contents — those are transient, not user facts
- Things that are obviously transient ("user has 10 unread emails")

DO extract when the user reveals:
- Personal preferences ("I prefer X over Y", "I always use X")
- Recurring routines ("every morning I X", "I work at X")
- Stable personal facts ("my partner's name is X", "I live in X")
- Real commitments with deadlines ("I need to finish X by Friday")

Be conservative — when in doubt, return []. Each fact must be self-contained and useful in 6 months."""

# We pass ONLY the user's message — extracting from VERA's own replies created garbage facts
# like "VERA needs a token" that polluted future context.
_EXTRACTION_USER = "User just said: {user}"

# ---------------------------------------------------------------------------
# Summarization prompt
# ---------------------------------------------------------------------------
_SUMMARY_SYSTEM = """\
Summarise the following conversation into 3-5 bullet points that capture the most
important facts, decisions, and user preferences mentioned. Be extremely concise.
Return plain text bullets starting with •"""


def _compact_history(history: list[dict]) -> list[dict]:
    """Truncate verbose messages so the LLM request fits in TPM budget.

    Keeps recent turns intact, summarises old verbose ones (>500 chars) by
    truncating to the first 350 chars + "[…]" — preserves enough context for
    the model to remember "we discussed X" without re-sending whole tables.
    """
    KEEP_FULL = 6   # last N messages stay full
    TRUNC_AT = 500  # chars above which we truncate older messages
    KEEP_CHARS = 350
    out: list[dict] = []
    cutoff = max(0, len(history) - KEEP_FULL)
    for i, msg in enumerate(history):
        content = msg.get("content", "")
        if i < cutoff and isinstance(content, str) and len(content) > TRUNC_AT:
            new_msg = dict(msg)
            new_msg["content"] = content[:KEEP_CHARS] + " […]"
            out.append(new_msg)
        else:
            out.append(msg)
    return out


DESTRUCTIVE_TOOLS = {"send_email", "delete_event", "trash_email"}
APPROVAL_TIMEOUT_S = 60


def _summarize_destructive(name: str, args: dict) -> str:
    """Short user-facing summary for the approval modal."""
    if name == "send_email":
        to = str(args.get("to", "?"))
        subj = str(args.get("subject", ""))[:60]
        return f"Send email to {to} — '{subj}'"
    if name == "delete_event":
        return f"Cancel calendar event {args.get('event_id', '?')}"
    if name == "trash_email":
        return f"Move email {args.get('message_id', '?')} to trash"
    return f"Run {name}"


def _ack_phrase(tool_name: str, args: dict) -> str:
    """Short user-facing message describing what VERA is about to do.

    Sent as `assistant.thinking` BEFORE tool execution so user gets
    instant feedback (especially useful in voice mode — TTS speaks it).
    Keep phrases short: TTS reads them quickly.
    """
    q = str(args.get("query", "") or args.get("topic", "") or "").strip()
    loc = str(args.get("location", "") or args.get("near", "")).strip()
    dest = str(args.get("destination", "")).strip()

    PHRASES: dict[str, str] = {
        "web_search": f"Searching the web for {q}…" if q else "Searching the web…",
        "get_weather": f"Checking the weather in {loc}…" if loc else "Checking the weather…",
        "wikipedia_summary": f"Looking that up on Wikipedia…",
        "get_datetime": "Checking the time…",
        "get_news": f"Checking the news on {q}…" if q else "Checking the news…",
        "store_memory": "Saving that to memory…",
        "read_file": "Reading that file…",
        "list_directory": "Listing the directory…",
        "search_files": "Searching files…",
        "send_notification": "Sending a notification…",
        "get_clipboard": "Reading your clipboard…",
        "set_clipboard": "Copying that to your clipboard…",
        "get_agenda": "Checking your calendar…",
        "find_event": "Searching your calendar…",
        "create_event": "Creating that event…",
        "delete_event": "Cancelling that event…",
        "list_emails": "Checking your inbox…",
        "read_email": "Reading that email…",
        "send_email": "Sending the email…",
        "trash_email": "Moving that email to trash…",
        "mark_as_read": "Marking it as read…",
        "spotify_play": f"Playing {q} on Spotify…" if q else "Resuming Spotify…",
        "spotify_pause": "Pausing Spotify…",
        "spotify_skip": "Skipping the track…",
        "spotify_now_playing": "Checking what's playing…",
        "spotify_queue": f"Queuing {q}…" if q else "Adding to queue…",
        "open_url": "Opening that in your browser…",
        "maps_directions": f"Getting directions to {dest}…" if dest else "Opening directions in Maps…",
        "maps_search": f"Looking up {q} on Maps…" if q else "Opening Maps…",
        "get_route": f"Calculating the route to {dest}…" if dest else "Calculating the route…",
        "nearby_places": f"Looking for {q} nearby…" if q else "Looking for places nearby…",
        "convert_currency": "Converting currency…",
    }
    return PHRASES.get(tool_name, f"Running {tool_name}…")


def _strip_json_fences(raw: str) -> str:
    """Strip markdown ```json fences (and trailing ```) from LLM responses."""
    raw = raw.strip()
    if raw.startswith("```"):
        lines = raw.splitlines()
        # Drop the opening ``` (or ```json) line
        inner = lines[1:]
        # Drop trailing ``` if present
        if inner and inner[-1].strip() == "```":
            inner = inner[:-1]
        raw = "\n".join(inner).strip()
    return raw


async def _llm_extract_memories_bg(
    llm: LLMClient,
    embedder,
    user_id: str,
    db: Session,
    user_text: str,
    reply: str,
) -> None:
    """Extract memorable facts from the last exchange using LLM.

    Runs in a background task with its own DB session so it never
    blocks the WebSocket reply or contends with the main session.
    """
    try:
        # Skip extraction for short or trivial user messages
        if len(user_text.strip()) < 12:
            return
        prompt = _EXTRACTION_USER.format(user=user_text[:600])
        raw = await llm.generate(
            messages=[{"role": "user", "content": prompt}],
            system=_EXTRACTION_SYSTEM,
        )
        items = json.loads(_strip_json_fences(raw))
        if not isinstance(items, list):
            return

        memory_svc = MemoryService(user_id=user_id)
        existing = memory_svc.retrieve(db, limit=50)
        existing_texts = {e.split("] ", 1)[-1].lower() for e in existing}

        for item in items[:5]:
            kind = str(item.get("kind", "fact"))
            text = str(item.get("text", "")).strip()
            if not text or len(text) < 8:
                continue
            if text.lower() in existing_texts:
                continue
            embedding = None
            if embedder:
                try:
                    embedding = await embedder.embed(text[:2000])
                except Exception as exc:
                    logger.debug("Background embedding failed: %r", exc)
            memory_svc.store(
                db,
                kind=kind if kind in VALID_MEMORY_KINDS else "fact",
                text=text,
                source="llm_extraction",
                confidence=0.85,
                embedding=embedding,
            )
            logger.info("Memory extracted [%s]: %s", kind, text)
    except Exception as exc:
        logger.debug("Memory extraction skipped: %r", exc)


class Orchestrator:
    _MAX_TOOL_ROUNDS = 6
    _SUMMARIZE_AT = 30   # compress history when it exceeds this many messages
    _KEEP_RECENT = 10    # keep this many recent messages after summarization

    def __init__(
        self,
        db: Session,
        user_id: str,
        display_name: str | None = None,
        session_id: str | None = None,
        on_event=None,
    ) -> None:
        self._db = db
        self._user_id = user_id
        self._session_id = session_id
        self._display_name = display_name or "there"
        self._memory = MemoryService(user_id=user_id)
        self._llm: LLMClient = build_llm_client()
        self._embedder = build_embedding_provider()  # may be None — graceful fallback
        self._max_history = settings.llm_history_turns * 2
        self._history: list[dict] = self._load_history()
        # Hold strong references to background tasks so the event loop
        # doesn't garbage-collect them mid-execution (CPython gotcha).
        self._bg_tasks: set[asyncio.Task] = set()
        # WS events tools want to push to the client (drained by ws.py after
        # handle_text returns). E.g. open_url, action_pending.
        self._pending_events: list[dict] = []
        # Async callback ws.py registers to push events MID-turn (e.g. thinking
        # acks before tool execution so user sees feedback during long tool calls).
        self._on_event = on_event

    async def _emit(self, event: dict) -> None:
        """Push a live event to the client (if a callback is wired)."""
        if self._on_event is None:
            return
        try:
            res = self._on_event(event)
            if asyncio.iscoroutine(res):
                await res
        except Exception as exc:
            logger.debug("on_event push failed: %r", exc)

    def drain_events(self) -> list[dict]:
        """Pop and return any queued WS events. Called by ws.py after each turn."""
        events = self._pending_events
        self._pending_events = []
        return events

    # ------------------------------------------------------------------
    # Public
    # ------------------------------------------------------------------

    async def handle_text(
        self,
        text: str,
        image_data: str | None = None,
        image_mime: str | None = None,
    ) -> str:
        effective_text = text or ("What's in this image?" if image_data else "")

        # Embed the user's query so retrieve() can do semantic search.
        query_embedding = await self._safe_embed(effective_text)
        memory_items = self._memory.retrieve(self._db, query_embedding=query_embedding, limit=8)
        system_prompt = self._build_system_prompt(memory_items)

        # Build user content — plain text or multimodal list when image attached.
        # Gemini (and any OpenAI-compatible vision model) accepts the image_url part.
        if image_data:
            user_content: str | list = [
                {"type": "text", "text": effective_text},
                {"type": "image_url", "image_url": {
                    "url": f"data:{image_mime or 'image/jpeg'};base64,{image_data}",
                }},
            ]
            persist_text = effective_text + " [image attached]"
        else:
            user_content = effective_text
            persist_text = effective_text

        # Truncate long history messages so they don't bloat the request.
        # Free-tier LLMs have ~8K TPM — verbose markdown tables (e.g. email
        # listings) eat that fast on a multi-turn conversation.
        compact_history = _compact_history(self._history)
        working: list[dict] = compact_history + [{"role": "user", "content": user_content}]

        reply = ""
        tool_rounds = 0
        try:
            for tool_rounds in range(self._MAX_TOOL_ROUNDS):
                result: LLMResult = await self._llm.generate_with_tools(
                    messages=working,
                    system=system_prompt,
                    tools=TOOL_DEFINITIONS,
                )

                if not result.has_tool_calls:
                    reply = result.content
                    break

                tool_call_msg: dict = {
                    "role": "assistant",
                    "content": result.content or "",
                    "tool_calls": [
                        {
                            "id": tc.id,
                            "type": "function",
                            "function": {"name": tc.name, "arguments": json.dumps(tc.arguments)},
                        }
                        for tc in result.tool_calls
                    ],
                }
                working.append(tool_call_msg)

                # Emit thinking acks for ALL tool calls before executing any —
                # this way user gets ~instant feedback (especially in voice mode)
                # rather than staring at "..." while a tool chain runs.
                for tc in result.tool_calls:
                    await self._emit({
                        "type": "assistant.thinking",
                        "payload": {"text": _ack_phrase(tc.name, tc.arguments), "tool": tc.name},
                    })

                for tc in result.tool_calls:
                    tool_result = await self._execute_tool(tc.name, tc.arguments)
                    working.append({"role": "tool", "tool_call_id": tc.id, "content": tool_result})
                    logger.info("Tool %r used by user %s", tc.name, self._user_id)
            else:
                working.append({"role": "user", "content": "[Provide your final answer now.]"})
                reply = await self._llm.generate(working, system=system_prompt)

        except RuntimeError as exc:
            logger.error("LLM error for user %s: %r", self._user_id, exc)
            reply = "I'm having trouble reaching my reasoning engine. Please check the API key and try again."

        # Guard against empty reply (LLM returned nothing)
        if not reply:
            reply = "I didn't get a response from my reasoning engine. Please try again."

        # Persist both turns (store plain text only — no base64 in DB)
        self._persist_message("user", persist_text)
        self._persist_message("assistant", reply)

        # Update in-memory history (plain text; image is ephemeral — one turn only)
        self._history.append({"role": "user", "content": persist_text})
        self._history.append({"role": "assistant", "content": reply})

        # Compress history if it's growing too long
        if len(self._history) >= self._SUMMARIZE_AT:
            await self._summarize_history()
        elif len(self._history) > self._max_history:
            self._history = self._history[-self._max_history:]

        # LLM-based memory extraction: background task with its own DB session
        user_id = self._user_id
        llm = self._llm
        embedder = self._embedder
        async def _bg_extract() -> None:
            db = SessionLocal()
            try:
                await _llm_extract_memories_bg(llm, embedder, user_id, db, persist_text, reply)
            except Exception as exc:
                logger.debug("Background memory extraction error: %r", exc)
            finally:
                db.close()
        task = asyncio.create_task(_bg_extract())
        self._bg_tasks.add(task)
        # Self-cleanup so the set doesn't grow unbounded
        task.add_done_callback(self._bg_tasks.discard)

        logger.debug("reply sent (%d msgs, %d tool rounds) user=%s", len(self._history), tool_rounds, self._user_id)
        return reply

    # ------------------------------------------------------------------
    # Tool execution
    # ------------------------------------------------------------------

    async def _execute_tool(self, name: str, arguments: dict) -> str:
        _ANDROID_TOOLS = {"set_reminder", "media_control", "launch_app"}
        fn = TOOL_REGISTRY.get(name)
        if fn is None and name not in _ANDROID_TOOLS:
            self._audit_tool_call(name, arguments, error="unknown_tool", latency_ms=0, result_len=0)
            return f"Unknown tool: {name}"

        # Approval gate for destructive tools — pause LLM loop, push WS event,
        # wait for user to click Allow/Deny in modal, then either run or skip.
        # Exception: trash_email bypasses gate if user has stored a skip preference.
        if name in DESTRUCTIVE_TOOLS:
            skip_gate = False
            if name == "trash_email":
                memories = self._memory.retrieve(self._db, limit=50)
                skip_gate = any("skip trash_email confirmation" in m.lower() for m in memories)
            decision = "approved" if skip_gate else await self._await_approval(name, arguments)
            if decision != "approved":
                msg = f"User declined to {_summarize_destructive(name, arguments).lower()}"
                logger.info("Action rejected by user: %s args=%s", name, arguments)
                self._audit_tool_call(name, arguments, latency_ms=0, result_len=len(msg), error="user_rejected")
                return msg

        t0 = time.monotonic()
        try:
            raw = await fn(**arguments)  # type: ignore[operator]
            # Intercept open_url markers from maps/url tools — fire LIVE so the
            # browser opens the moment the tool runs (not after full reply).
            if isinstance(raw, str) and raw.startswith("{") and "__open_url__" in raw:
                try:
                    marker = json.loads(raw)
                    if marker.get("__open_url__"):
                        await self._emit({
                            "type": "agent.open_url",
                            "payload": {
                                "url": marker["url"],
                                "label": marker.get("label", marker["url"]),
                            },
                        })
                        return f"Opened in browser: {marker.get('label', marker['url'])}"
                except Exception as exc:
                    logger.warning("open_url marker parse failed for tool %r: %r", name, exc)

            # Android native tools — emit WS event to device, no Python execution needed.
            if name == "set_reminder":
                await self._emit({"type": "agent.set_reminder", "payload": {"time": arguments.get("time", ""), "text": arguments.get("text", "")}})
                return f"Reminder set for {arguments.get('time')}: {arguments.get('text')}"
            if name == "media_control":
                await self._emit({"type": "agent.media_control", "payload": {"action": arguments.get("action", "")}})
                return f"Media control: {arguments.get('action')}"
            if name == "launch_app":
                await self._emit({"type": "agent.launch_app", "payload": {"uri": arguments.get("uri", "")}})
                return f"Launched: {arguments.get('uri')}"

            if name == "store_memory":
                try:
                    marker = json.loads(raw)
                    if marker.get("__store_memory__"):
                        kind = marker.get("kind", "fact")
                        text = str(marker.get("text", "")).strip()
                        if not text:
                            return "Memory not stored: empty text."
                        if kind not in VALID_MEMORY_KINDS:
                            kind = "fact"
                        embedding = await self._safe_embed(text)
                        self._memory.store(
                            self._db,
                            kind=kind,
                            text=text,
                            source="vera_tool",
                            confidence=0.9,
                            embedding=embedding,
                        )
                        return f"Stored: [{kind}] {text}"
                except Exception as exc:
                    logger.warning("store_memory marker parse failed: %r", exc)
            result_str = str(raw)
            self._audit_tool_call(
                name, arguments,
                latency_ms=int((time.monotonic() - t0) * 1000),
                result_len=len(result_str),
            )
            return result_str
        except Exception as exc:
            logger.warning("Tool %r error: %r", name, exc)
            self._audit_tool_call(
                name, arguments,
                latency_ms=int((time.monotonic() - t0) * 1000),
                result_len=0,
                error=type(exc).__name__,
            )
            return f"Tool error: {exc}"

    def _audit_tool_call(
        self,
        name: str,
        arguments: dict,
        *,
        latency_ms: int,
        result_len: int,
        error: str | None = None,
    ) -> None:
        """Write a tool invocation to audit_log. Privacy: hash args, don't store raw."""
        try:
            args_str = json.dumps(arguments, sort_keys=True, default=str)
            args_hash = hashlib.sha256(args_str.encode("utf-8")).hexdigest()[:16]
            audit_log(
                self._db,
                event_type=f"tool.{name}",
                severity="error" if error else "info",
                user_id=self._user_id,
                session_id=self._session_id,
                payload={
                    "tool": name,
                    "args_hash": args_hash,
                    "latency_ms": latency_ms,
                    "result_len": result_len,
                    "error": error,
                },
            )
        except Exception as exc:
            logger.debug("audit_log write failed: %r", exc)

    async def _await_approval(self, name: str, arguments: dict) -> str:
        """Block tool execution until user approves via REST. Returns decision string.

        Steps: persist AgentAction(pending) → emit WS event → wait on
        asyncio.Event → REST handler resolves → return 'approved' or 'rejected'.
        On timeout, returns 'rejected' to fail safely.
        """
        action_id = str(uuid.uuid4())
        summary = _summarize_destructive(name, arguments)

        try:
            self._db.add(models.AgentAction(
                id=action_id,
                user_id=self._user_id,
                type=name,
                payload_json={"args": arguments, "summary": summary},
                requires_approval=True,
                approval_status="pending",
            ))
            self._db.commit()
        except Exception as exc:
            logger.warning("Failed to persist pending action: %r", exc)
            self._db.rollback()
            return "rejected"

        ev = approval_gate.register(action_id, self._user_id)
        await self._emit({
            "type": "agent.action_pending",
            "payload": {
                "action_id": action_id,
                "tool": name,
                "summary": summary,
                "args": arguments,
                "timeout_s": APPROVAL_TIMEOUT_S,
            },
        })

        try:
            await asyncio.wait_for(ev.wait(), timeout=APPROVAL_TIMEOUT_S)
            decision = approval_gate.get_decision(action_id) or "rejected"
        except asyncio.TimeoutError:
            decision = "rejected"
            # Update DB so the audit trail shows timeout cleanly
            try:
                row = self._db.query(models.AgentAction).filter(
                    models.AgentAction.id == action_id,
                ).first()
                if row and row.approval_status == "pending":
                    row.approval_status = "rejected"
                    row.error_json = {"reason": "approval_timeout"}
                    self._db.commit()
            except Exception:
                self._db.rollback()
            await self._emit({
                "type": "agent.action_resolved",
                "payload": {"action_id": action_id, "decision": "timeout"},
            })
        finally:
            approval_gate.cleanup(action_id)

        # Tell the UI to dismiss the modal in non-timeout cases too
        if decision != "rejected" or True:  # always notify
            await self._emit({
                "type": "agent.action_resolved",
                "payload": {"action_id": action_id, "decision": decision},
            })
        return decision

    async def _safe_embed(self, text: str) -> list[float] | None:
        """Embed text or return None if embedder is unavailable / errors."""
        if not self._embedder or not text.strip():
            return None
        try:
            return await self._embedder.embed(text[:2000])
        except Exception as exc:
            logger.debug("Embedding failed: %r", exc)
            return None

    # (memory extraction moved to module-level function _llm_extract_memories_bg)

    # ------------------------------------------------------------------
    # History summarization
    # ------------------------------------------------------------------

    async def _summarize_history(self) -> None:
        """Compress old history into a summary memory item, keep recent turns."""
        to_summarize = self._history[:-self._KEEP_RECENT]
        recent = self._history[-self._KEEP_RECENT:]

        try:
            conversation_text = "\n".join(
                f"{m['role'].upper()}: {m.get('content', '')[:300]}"
                for m in to_summarize
                if m.get("role") in {"user", "assistant"}
            )
            summary = await self._llm.generate(
                messages=[{"role": "user", "content": conversation_text}],
                system=_SUMMARY_SYSTEM,
            )
            summary_text = f"Earlier conversation summary:\n{summary}"
            embedding = await self._safe_embed(summary_text)
            self._memory.store(
                self._db,
                kind="summary",
                text=summary_text,
                source="auto_summary",
                confidence=0.7,
                embedding=embedding,
            )
            self._history = recent
            logger.info("Summarized %d history messages for user %s", len(to_summarize), self._user_id)
        except Exception as exc:
            logger.warning("History summarization failed: %r", exc)
            self._history = self._history[-self._max_history:]

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _build_system_prompt(self, memory_items: list[str]) -> str:
        current_datetime = datetime.now(timezone.utc).strftime("%A, %d %B %Y %H:%M UTC")
        if memory_items:
            lines = "\n".join(f"  • {item}" for item in memory_items)
            memory_block = _MEMORY_BLOCK.format(lines=lines)
        else:
            memory_block = _NO_MEMORY_BLOCK
        return _SYSTEM_TEMPLATE.format(current_datetime=current_datetime, memory_block=memory_block)

    def _load_history(self) -> list[dict]:
        try:
            rows = (
                self._db.query(ChatMessage)
                .filter(ChatMessage.user_id == self._user_id)
                .order_by(ChatMessage.ts.desc())
                .limit(self._max_history)
                .all()
            )
            history = [{"role": r.role, "content": r.content} for r in reversed(rows)]
            logger.info("Loaded %d history msgs for user %s", len(history), self._user_id)
            return history
        except Exception as exc:
            logger.warning("Failed to load history: %r", exc)
            return []

    def _persist_message(self, role: str, content: str) -> None:
        try:
            self._db.add(ChatMessage(
                user_id=self._user_id,
                session_id=self._session_id,
                role=role,
                content=content,
            ))
            self._db.commit()
        except Exception as exc:
            logger.warning("Failed to persist message: %r", exc)
            self._db.rollback()
