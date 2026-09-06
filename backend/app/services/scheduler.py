"""Proactive suggestion scheduler — Sprint 5.

Runs an in-process APScheduler that periodically evaluates time-of-day rules
and user memory to generate proactive suggestions.  Suggestions are saved to
the database and pushed over WebSocket to connected clients.

Rule taxonomy (Sprint 5 — no external integrations yet):
  morning_brief     — daily morning context summary (07:00–09:00)
  focus_block       — suggest a focus block mid-morning (09:00–10:30)
  midday_check      — end-of-morning wrap-up prompt (11:45–12:15)
  afternoon_recharge — post-lunch energy tip (13:30–14:30)
  end_of_day_review — EOD wrap-up (16:30–17:30)
  quiet_hours       — suppresses all rules when quiet_hours_json is active
"""
from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy.orm import Session

from backend.app.db import models
from backend.app.db.session import SessionLocal
from backend.app.services.memory import MemoryService

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Analytics system prompt (spontaneous learning engine)
# ---------------------------------------------------------------------------

_ANALYTICS_SYSTEM = """\
<system_instructions>
You are the background intelligence and learning engine for a proactive AI assistant (JARVIS-style).
Your task is to analyze patterns in the user's long-term memory and determine whether the assistant
should ask a clarifying question via a mobile notification to expand its preference database.

<question_categories>
Categorize every identified pattern into one of these three categories:

1. PREFERENCE (e.g., food habits, travel preferences, preferred brands, response formatting)
   - Goal: Learn what the user likes or dislikes in daily life.
   - Threshold: Triggers after a pattern repeats 2-3 times.

2. ROUTINE_AND_CONTEXT (e.g., workout times, commute habits, meeting prep timing)
   - Goal: Anticipate what the user needs at specific times.
   - Threshold: Requires clear time-based patterns (same time/day of week).

3. PROACTIVE_PERMISSION (e.g., "Would you like me to automatically check tickets/weather?")
   - Goal: Gain approval to act autonomously in the background going forward.
   - Threshold: Only when the user shows clear intent for a recurring task.
</question_categories>

<decision_rules>
For every pattern candidate evaluated, apply these strict rules:

1. Confidence Score Threshold:
   - Must be strictly between 0.50 and 0.75 to trigger a notification.
   - Score < 0.50: Too uncertain. Save to memory, DO NOT notify.
   - Score > 0.75: Confirmed enough. Assume true, DO NOT notify (act on it silently).

2. Daily Budget:
   - Maximum of 1 proactive notification allowed per 24-hour period.

3. Notification Constraints:
   - Body text: MAXIMUM 12 words.
   - Title text: MAXIMUM 4 words.
   - Action Buttons: Always provide exactly 2 quick-action choices.
</decision_rules>

<output_format>
Return ONLY a valid JSON object. No markdown, no filler text.

{
  "should_notify": boolean,
  "pattern_id": "string",
  "category": "PREFERENCE" | "ROUTINE_AND_CONTEXT" | "PROACTIVE_PERMISSION",
  "confidence_score": number,
  "reasoning": "Short explanation",
  "notification": {
    "title": "Max 4 words",
    "body": "Question under 12 words?",
    "action_yes": {"label": "Yes label", "target_confidence": 1.0},
    "action_no": {"label": "No label", "target_confidence": 0.1}
  }
}
</output_format>
</system_instructions>"""


# ---------------------------------------------------------------------------
# Suggestion rule definitions
# ---------------------------------------------------------------------------


class _Rule:
    """A single proactive suggestion rule.

    Parameters
    ----------
    rule_id:
        Unique machine-readable identifier (stored as suggestion ``type``).
    title:
        Short human-facing title shown in the suggestions feed.
    reason:
        Explanation shown beneath the title ("because …").
    hour_start / hour_end:
        UTC hour window during which this rule fires (inclusive start,
        exclusive end).
    daily:
        If True the rule fires at most once per calendar day per user.
    priority:
        0 = low, 5 = medium, 10 = high.
    """

    def __init__(
        self,
        *,
        rule_id: str,
        title: str,
        reason: str,
        hour_start: int,
        hour_end: int,
        daily: bool = True,
        priority: int = 5,
    ) -> None:
        self.rule_id = rule_id
        self.title = title
        self.reason = reason
        self.hour_start = hour_start
        self.hour_end = hour_end
        self.daily = daily
        self.priority = priority

    def is_active_now(self, now: datetime) -> bool:
        """Return True if the current UTC hour falls within the rule's window."""
        return self.hour_start <= now.hour < self.hour_end


_RULES: list[_Rule] = [
    _Rule(
        rule_id="morning_brief",
        title="Good morning — ready to review your day?",
        reason="Starting your day with a quick plan helps you stay on track.",
        hour_start=7,
        hour_end=9,
        priority=8,
    ),
    _Rule(
        rule_id="focus_block",
        title="Consider a focused work block now",
        reason="Mid-morning is typically peak cognitive performance time.",
        hour_start=9,
        hour_end=11,
        priority=7,
    ),
    _Rule(
        rule_id="midday_check",
        title="How's your morning been? Quick wrap-up?",
        reason="A brief review before lunch helps consolidate progress.",
        hour_start=11,
        hour_end=12,
        priority=5,
    ),
    _Rule(
        rule_id="afternoon_recharge",
        title="Post-lunch energy dip — light task or short break?",
        reason="Cognitive performance often dips 13:00–14:30.",
        hour_start=13,
        hour_end=15,
        priority=4,
    ),
    _Rule(
        rule_id="end_of_day_review",
        title="End-of-day review — what got done today?",
        reason="Closing out the day with a quick review improves retention and planning.",
        hour_start=16,
        hour_end=18,
        priority=7,
    ),
]


# ---------------------------------------------------------------------------
# Quiet-hours check
# ---------------------------------------------------------------------------


def _is_quiet_hours(user_settings: models.UserSettings | None, now: datetime) -> bool:
    """Return True if the current time falls inside the user's quiet hours.

    quiet_hours_json format: ``{"enabled": true, "start": 22, "end": 7}``
    Start and end are UTC hours (integers).

    Parameters
    ----------
    user_settings:
        The user's settings row; may be None if no settings saved yet.
    now:
        Current UTC datetime.
    """
    if not user_settings:
        return False
    qh: dict = user_settings.quiet_hours_json or {}
    if not qh.get("enabled"):
        return False

    start_hour: int = int(qh.get("start", 22))
    end_hour: int = int(qh.get("end", 7))
    current_hour = now.hour

    if start_hour <= end_hour:
        # Same-day window (e.g. 09:00–17:00)
        return start_hour <= current_hour < end_hour
    else:
        # Overnight window (e.g. 22:00–07:00)
        return current_hour >= start_hour or current_hour < end_hour


# ---------------------------------------------------------------------------
# Suggestion creation
# ---------------------------------------------------------------------------


def _already_suggested_today(
    db: Session, user_id: str, rule_id: str, today: datetime
) -> bool:
    """Return True if a suggestion of this rule type was already created today.

    Parameters
    ----------
    db:
        SQLAlchemy session.
    user_id:
        Target user UUID.
    rule_id:
        Rule identifier to check (stored as suggestion ``type``).
    today:
        Current date (only the date portion is compared).
    """
    today_start = today.replace(hour=0, minute=0, second=0, microsecond=0)
    existing = (
        db.query(models.AgentSuggestion)
        .filter(
            models.AgentSuggestion.user_id == user_id,
            models.AgentSuggestion.type == rule_id,
            models.AgentSuggestion.ts >= today_start,
        )
        .first()
    )
    return existing is not None


def _create_suggestion(
    db: Session,
    user_id: str,
    rule: _Rule,
    memory_context: list[str],
) -> models.AgentSuggestion:
    """Persist a new suggestion row and return it.

    Parameters
    ----------
    db:
        SQLAlchemy session.
    user_id:
        Target user UUID.
    rule:
        The rule that fired.
    memory_context:
        Retrieved memory items to attach as context payload.
    """
    suggestion = models.AgentSuggestion(
        user_id=user_id,
        type=rule.rule_id,
        priority=rule.priority,
        status="new",
        payload_json={
            "title": rule.title,
            "reason": rule.reason,
            "memory_context": memory_context[:3],  # top 3 relevant memories
        },
    )
    db.add(suggestion)
    db.commit()
    db.refresh(suggestion)
    return suggestion


# ---------------------------------------------------------------------------
# Core tick logic (called by APScheduler every minute)
# ---------------------------------------------------------------------------


async def _evaluate_rules(connection_manager_ref) -> None:  # type: ignore[type-arg]
    """Evaluate all rules for all active users and push suggestions.

    Called every minute by APScheduler.  Only checks users who currently
    have an active WebSocket connection so we don't waste DB queries on
    offline users (they will see suggestions on next login via REST).

    Parameters
    ----------
    connection_manager_ref:
        The module-level ``ConnectionManager`` singleton from
        ``backend.app.api.connection_manager``.
    """
    now = datetime.now(timezone.utc)
    active_user_ids = connection_manager_ref.active_user_ids()

    if not active_user_ids:
        return

    db = SessionLocal()
    try:
        for user_id in active_user_ids:
            try:
                await _evaluate_for_user(db, connection_manager_ref, user_id, now)
            except Exception as exc:  # noqa: BLE001
                logger.error("Scheduler error for user %s: %r", user_id, exc)
    finally:
        db.close()


async def _evaluate_for_user(
    db: Session,
    connection_manager_ref,  # type: ignore[type-arg]
    user_id: str,
    now: datetime,
) -> None:
    """Run all rules for a single user.

    Parameters
    ----------
    db:
        SQLAlchemy session.
    connection_manager_ref:
        The ``ConnectionManager`` singleton.
    user_id:
        Target user UUID string.
    now:
        Current UTC datetime.
    """
    user_settings = (
        db.query(models.UserSettings)
        .filter(models.UserSettings.user_id == user_id)
        .first()
    )

    if _is_quiet_hours(user_settings, now):
        logger.debug("Quiet hours active for user %s — skipping", user_id)
        return

    # Retrieve memory for context enrichment
    memory_svc = MemoryService(user_id=user_id)
    memory_items = memory_svc.retrieve(db, limit=5)

    for rule in _RULES:
        if not rule.is_active_now(now):
            continue
        if rule.daily and _already_suggested_today(db, user_id, rule.rule_id, now):
            continue

        suggestion = _create_suggestion(db, user_id, rule, memory_items)
        logger.info(
            "Created suggestion type=%s for user=%s id=%s",
            rule.rule_id,
            user_id,
            suggestion.id,
        )

        # Push over WebSocket immediately if user is connected
        await connection_manager_ref.send(
            user_id,
            "agent.suggestion",
            {
                "id": suggestion.id,
                "type": suggestion.type,
                "priority": suggestion.priority,
                "title": suggestion.payload_json.get("title", ""),
                "reason": suggestion.payload_json.get("reason", ""),
                "ts": suggestion.ts.isoformat(),
                "status": suggestion.status,
            },
        )


# ---------------------------------------------------------------------------
# Proactive learning analytics (runs every 4 hours)
# ---------------------------------------------------------------------------


async def _run_learning_analytics(connection_manager_ref) -> None:  # type: ignore[type-arg]
    """For each connected user with enough memories, call Claude to detect
    patterns and optionally push a proactive question notification.

    Budget: max 1 proactive_question per user per 24h.
    Fires only for users with >= 3 memory items (not enough signal otherwise).
    """
    now = datetime.now(timezone.utc)
    active_user_ids = connection_manager_ref.active_user_ids()
    if not active_user_ids:
        return

    try:
        from backend.app.services.llm import build_llm_client  # noqa: PLC0415
        llm = build_llm_client()
    except Exception as exc:
        logger.warning("Learning analytics: LLM unavailable — %r", exc)
        return

    db = SessionLocal()
    try:
        for user_id in active_user_ids:
            try:
                await _learning_for_user(db, connection_manager_ref, llm, user_id, now)
            except Exception as exc:  # noqa: BLE001
                logger.error("Learning analytics error for user %s: %r", user_id, exc)
    finally:
        db.close()


async def _learning_for_user(db, connection_manager_ref, llm, user_id: str, now: datetime) -> None:  # type: ignore[type-arg]
    # Check daily budget — only 1 proactive question per 24h
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    already_asked = (
        db.query(models.AgentSuggestion)
        .filter(
            models.AgentSuggestion.user_id == user_id,
            models.AgentSuggestion.type == "proactive_question",
            models.AgentSuggestion.ts >= today_start,
        )
        .first()
    )
    if already_asked:
        return

    # Load memories — need >= 3 to have meaningful patterns
    memory_svc = MemoryService(user_id=user_id)
    memories = memory_svc.retrieve(db, limit=30)
    if len(memories) < 3:
        return

    # Build memory context for the LLM
    memory_text = json.dumps(memories, ensure_ascii=False)
    user_prompt = (
        f"Here are the user's stored memories (kind + text):\n{memory_text}\n\n"
        "Analyze these for patterns. Return the JSON decision object."
    )

    raw = await llm.generate(
        messages=[{"role": "user", "content": user_prompt}],
        system=_ANALYTICS_SYSTEM,
    )

    # Strip markdown fences if present
    clean = raw.strip()
    if clean.startswith("```"):
        clean = clean.split("```")[1]
        if clean.startswith("json"):
            clean = clean[4:]
    clean = clean.strip()

    try:
        result = json.loads(clean)
    except Exception as exc:
        logger.warning("Learning analytics: JSON parse failed for user %s: %r — raw=%r", user_id, exc, raw[:200])
        return

    if not result.get("should_notify"):
        logger.debug("Learning analytics: no notification for user %s (score=%.2f)", user_id, result.get("confidence_score", 0))
        return

    confidence = float(result.get("confidence_score", 0))
    if not (0.50 < confidence < 0.75):
        logger.debug("Learning analytics: confidence %.2f out of notify range for user %s", confidence, user_id)
        return

    notification = result.get("notification", {})
    if not notification.get("title") or not notification.get("body"):
        return

    # Persist as AgentSuggestion for tracking and answer lookup
    suggestion = models.AgentSuggestion(
        user_id=user_id,
        type="proactive_question",
        priority=6,
        status="new",
        payload_json={
            "pattern_id": result.get("pattern_id", ""),
            "category": result.get("category", "PREFERENCE"),
            "confidence_score": confidence,
            "reasoning": result.get("reasoning", ""),
            "notification": notification,
            "action_yes": notification.get("action_yes", {"label": "Yes", "target_confidence": 1.0}),
            "action_no": notification.get("action_no", {"label": "No", "target_confidence": 0.1}),
        },
    )
    db.add(suggestion)
    db.commit()
    db.refresh(suggestion)

    logger.info(
        "Proactive learning question for user %s: %r (score=%.2f)",
        user_id, notification.get("body"), confidence,
    )

    # Push to Android app via WebSocket
    await connection_manager_ref.send(
        user_id,
        "agent.proactive_question",
        {
            "question_id": suggestion.id,
            "title": notification["title"],
            "body": notification["body"],
            "action_yes_label": notification.get("action_yes", {}).get("label", "Yes"),
            "action_no_label": notification.get("action_no", {}).get("label", "No"),
        },
    )


# ---------------------------------------------------------------------------
# Scheduler lifecycle
# ---------------------------------------------------------------------------


class ProactiveScheduler:
    """Wraps APScheduler for clean startup and shutdown from FastAPI lifespan.

    Usage::

        scheduler = ProactiveScheduler()
        scheduler.start()   # call in app startup
        scheduler.stop()    # call in app shutdown
    """

    def __init__(self) -> None:
        self._scheduler = AsyncIOScheduler(timezone="UTC")

    def start(self) -> None:
        """Start the background scheduler."""
        from backend.app.api.connection_manager import manager  # noqa: PLC0415

        self._scheduler.add_job(
            _evaluate_rules,
            trigger="interval",
            minutes=1,
            args=[manager],
            id="proactive_suggestions",
            replace_existing=True,
        )
        self._scheduler.add_job(
            _run_learning_analytics,
            trigger="interval",
            hours=4,
            args=[manager],
            id="learning_analytics",
            replace_existing=True,
        )
        self._scheduler.start()
        logger.info("ProactiveScheduler started — evaluating rules every 60 s, learning analytics every 4 h")

    def stop(self) -> None:
        """Gracefully shut down the scheduler."""
        if self._scheduler.running:
            self._scheduler.shutdown(wait=False)
            logger.info("ProactiveScheduler stopped")


# Module-level singleton
proactive_scheduler = ProactiveScheduler()
