"""Approval-gate registry — blocks destructive tool calls until user approves.

Flow:
  orchestrator._execute_tool() detects destructive tool
    → calls register(action_id) to get an asyncio.Event
    → emits WS agent.action_pending event to client
    → awaits the Event (with timeout)
  REST POST /api/actions/{id}/{approve|reject}
    → calls resolve(action_id, decision) which sets the Event
  orchestrator wakes, reads decision, executes (or skips) tool
  Both sides call cleanup(action_id) when done
"""
from __future__ import annotations

import asyncio

# Module-level registries — only one orchestrator process per uvicorn worker
# so a plain dict is fine. If you scale to multi-worker, replace with Redis pub/sub.
_events: dict[str, asyncio.Event] = {}
_decisions: dict[str, str] = {}
_owners: dict[str, str] = {}


def register(action_id: str, user_id: str) -> asyncio.Event:
    """Create a fresh Event for an action_id and return it for awaiting."""
    ev = asyncio.Event()
    _events[action_id] = ev
    _owners[action_id] = user_id
    return ev


def resolve(action_id: str, decision: str, user_id: str) -> bool:
    """Mark an action as approved/rejected. Wakes the orchestrator coroutine.

    Returns True on success, False if action_id unknown OR not owned by this user.
    """
    if action_id not in _events:
        return False
    if _owners.get(action_id) != user_id:
        return False
    _decisions[action_id] = decision
    _events[action_id].set()
    return True


def get_decision(action_id: str) -> str | None:
    """Return 'approved' / 'rejected' / None if no decision yet."""
    return _decisions.get(action_id)


def cleanup(action_id: str) -> None:
    """Drop registry entries — call once both sides are done."""
    _events.pop(action_id, None)
    _decisions.pop(action_id, None)
    _owners.pop(action_id, None)
