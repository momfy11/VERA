"""Agent orchestrator — the brain of VERA."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.db.models import ChatMessage
from backend.app.services.llm import LLMClient, LLMResult, build_llm_client
from backend.app.services.memory import MemoryService
from backend.app.services.tools import TOOL_DEFINITIONS, TOOL_REGISTRY

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# System prompt
# ---------------------------------------------------------------------------
_SYSTEM_TEMPLATE = """\
You are VERA — Voice-Enabled Reasoning Assistant — a personal AI assistant running 24/7.

Personality & style:
- Calm, precise, slightly formal but warm — think J.A.R.V.I.S. from Iron Man.
- Proactive: surface relevant context the user didn't explicitly ask for.
- Concise when voice is active (1–3 sentences unless asked for more).
- Never say "As an AI…". Refer to yourself as VERA only.

Capabilities:
- Answer questions, reason, summarise, plan, brainstorm.
- Search the web, check weather, look up Wikipedia — use tools freely and proactively.
- Read local files and list directories when asked.
- Send desktop notifications when it would be helpful.
- Remember user preferences and routines (injected as memories below).
- Use store_memory when the user asks you to remember something, or when you learn a clear fact worth keeping.

Current date/time: {current_datetime}

{memory_block}"""

_MEMORY_BLOCK = "What I know about you:\n{lines}\n\n"
_NO_MEMORY_BLOCK = "No stored memories yet — I'll learn your preferences as we talk.\n\n"

# ---------------------------------------------------------------------------
# LLM memory-extraction prompt (fired after each exchange)
# ---------------------------------------------------------------------------
_EXTRACTION_SYSTEM = """\
You extract memorable facts from a conversation exchange.
Return ONLY a JSON array of objects, each with "kind" and "text".
kinds: preference | routine | fact | commitment
Return [] if nothing is worth storing.
Be concise — each text should be one sentence max.
Examples:
[{"kind":"preference","text":"Prefers dark mode"},{"kind":"routine","text":"Morning standup at 09:00"}]"""

_EXTRACTION_USER = "User said: {user}\nAssistant replied: {reply}"

# ---------------------------------------------------------------------------
# Summarization prompt
# ---------------------------------------------------------------------------
_SUMMARY_SYSTEM = """\
Summarise the following conversation into 3-5 bullet points that capture the most
important facts, decisions, and user preferences mentioned. Be extremely concise.
Return plain text bullets starting with •"""


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
    ) -> None:
        self._db = db
        self._user_id = user_id
        self._session_id = session_id
        self._display_name = display_name or "there"
        self._memory = MemoryService(user_id=user_id)
        self._llm: LLMClient = build_llm_client()
        self._max_history = settings.llm_history_turns * 2
        self._history: list[dict] = self._load_history()

    # ------------------------------------------------------------------
    # Public
    # ------------------------------------------------------------------

    async def handle_text(self, text: str) -> str:
        memory_items = self._memory.retrieve(self._db)
        system_prompt = self._build_system_prompt(memory_items)
        working: list[dict] = list(self._history) + [{"role": "user", "content": text}]

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

        # Persist both turns
        self._persist_message("user", text)
        self._persist_message("assistant", reply)

        # Update in-memory history
        self._history.append({"role": "user", "content": text})
        self._history.append({"role": "assistant", "content": reply})

        # Compress history if it's growing too long
        if len(self._history) >= self._SUMMARIZE_AT:
            await self._summarize_history()
        elif len(self._history) > self._max_history:
            self._history = self._history[-self._max_history:]

        # LLM-based memory extraction runs in background — doesn't block the reply
        asyncio.create_task(self._llm_extract_memories(text, reply))

        logger.debug("reply sent (%d msgs, %d tool rounds) user=%s", len(self._history), tool_rounds, self._user_id)
        return reply

    # ------------------------------------------------------------------
    # Tool execution
    # ------------------------------------------------------------------

    async def _execute_tool(self, name: str, arguments: dict) -> str:
        fn = TOOL_REGISTRY.get(name)
        if fn is None:
            return f"Unknown tool: {name}"
        try:
            raw = await fn(**arguments)  # type: ignore[operator]
            if name == "store_memory":
                try:
                    marker = json.loads(raw)
                    if marker.get("__store_memory__"):
                        self._memory.store(
                            self._db,
                            kind=marker["kind"],
                            text=marker["text"],
                            source="vera_tool",
                            confidence=0.9,
                        )
                        return f"Stored: [{marker['kind']}] {marker['text']}"
                except Exception:
                    pass
            return str(raw)
        except Exception as exc:
            logger.warning("Tool %r error: %r", name, exc)
            return f"Tool error: {exc}"

    # ------------------------------------------------------------------
    # Memory extraction (LLM-based, async background)
    # ------------------------------------------------------------------

    async def _llm_extract_memories(self, user_text: str, reply: str) -> None:
        """Ask the LLM to pull memorable facts from the last exchange."""
        try:
            prompt = _EXTRACTION_USER.format(
                user=user_text[:600],
                reply=reply[:600],
            )
            raw = await self._llm.generate(
                messages=[{"role": "user", "content": prompt}],
                system=_EXTRACTION_SYSTEM,
            )
            items = json.loads(raw.strip())
            if not isinstance(items, list):
                return
            existing = self._memory.retrieve(self._db, limit=50)
            existing_texts = {e.split("] ", 1)[-1].lower() for e in existing}
            for item in items[:5]:
                kind = item.get("kind", "fact")
                text = str(item.get("text", "")).strip()
                if not text or len(text) < 8:
                    continue
                if text.lower() in existing_texts:
                    continue
                self._memory.store(
                    self._db,
                    kind=kind if kind in {"preference", "routine", "fact", "commitment"} else "fact",
                    text=text,
                    source="llm_extraction",
                    confidence=0.85,
                )
                logger.info("LLM extracted memory [%s]: %s", kind, text)
        except Exception as exc:
            logger.debug("Memory extraction skipped: %r", exc)

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
            self._memory.store(
                self._db,
                kind="summary",
                text=f"Earlier conversation summary:\n{summary}",
                source="auto_summary",
                confidence=0.7,
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
