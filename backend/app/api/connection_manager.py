"""WebSocket connection manager.

Tracks all live WebSocket connections keyed by user_id so that
background services (scheduler, push notifications) can deliver
events to connected clients without going through a message broker.

This is an in-process registry — if you ever run multiple uvicorn
workers you will need to replace this with Redis pub/sub or similar.
"""
from __future__ import annotations

import json
import logging
from collections import defaultdict

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class ConnectionManager:
    """Registry of active WebSocket connections, keyed by user_id.

    One user can have multiple simultaneous connections (e.g. mobile + desktop).
    All active sockets for a user receive broadcasts.
    """

    def __init__(self) -> None:
        # user_id -> set of WebSocket objects
        self._connections: dict[str, set[WebSocket]] = defaultdict(set)

    def connect(self, user_id: str, websocket: WebSocket) -> None:
        """Register a new connection for the given user.

        Parameters
        ----------
        user_id:
            UUID string of the authenticated user.
        websocket:
            The accepted WebSocket instance.
        """
        self._connections[user_id].add(websocket)
        logger.debug("WS connected user=%s total_sockets=%d", user_id, len(self._connections[user_id]))

    def disconnect(self, user_id: str, websocket: WebSocket) -> None:
        """Remove a connection when the client disconnects.

        Parameters
        ----------
        user_id:
            UUID string of the authenticated user.
        websocket:
            The WebSocket instance to remove.
        """
        self._connections[user_id].discard(websocket)
        if not self._connections[user_id]:
            del self._connections[user_id]
        logger.debug("WS disconnected user=%s", user_id)

    async def send(self, user_id: str, event_type: str, payload: dict) -> None:
        """Send a JSON event to all active sockets for a user.

        Dead sockets are silently removed from the registry.

        Parameters
        ----------
        user_id:
            Target user UUID.
        event_type:
            The ``type`` field of the WebSocket message (e.g. ``agent.suggestion``).
        payload:
            The ``payload`` dict to send alongside the type.
        """
        message = json.dumps({"type": event_type, "payload": payload})
        dead: set[WebSocket] = set()

        for websocket in list(self._connections.get(user_id, set())):
            try:
                await websocket.send_text(message)
            except Exception:  # noqa: BLE001
                dead.add(websocket)

        for websocket in dead:
            self.disconnect(user_id, websocket)

    def active_user_ids(self) -> list[str]:
        """Return list of user_ids that currently have at least one live connection."""
        return list(self._connections.keys())


# Module-level singleton shared across the app
manager = ConnectionManager()
