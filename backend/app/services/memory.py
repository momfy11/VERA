"""Memory service — store and retrieve user preference/fact memory items.

Sprint 4 implementation: DB-backed CRUD with simple recency-based retrieval.
Embeddings + semantic search will be layered on in a later sprint when
pgvector is enabled and an embedding model is benchmarked.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from backend.app.db import models

logger = logging.getLogger(__name__)


class MemoryService:
    """Manages long-term memory items for a user.

    Memory items are plain-text facts or preferences extracted during
    conversations.  They are persisted in the ``memory_items`` table and
    injected as context into the LLM system prompt.

    Parameters
    ----------
    user_id:
        UUID of the user whose memory this service manages.
    """

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    def retrieve(self, db: Session, limit: int = 15) -> list[str]:
        """Return the most recent active memory items as plain strings.

        Only returns items that are active and not yet expired, ordered by
        confidence (descending) then recency.

        Parameters
        ----------
        db:
            SQLAlchemy session.
        limit:
            Maximum number of items to return.

        Returns
        -------
        list[str]
            Each item formatted as ``"[kind] text"`` for easy prompt injection.
        """
        now = datetime.now(timezone.utc)

        rows = (
            db.query(models.MemoryItem)
            .filter(
                models.MemoryItem.user_id == self.user_id,
                models.MemoryItem.is_active.is_(True),
                # Exclude expired items (NULL expires_at means never expires)
                (models.MemoryItem.expires_at.is_(None))
                | (models.MemoryItem.expires_at > now),
            )
            .order_by(
                models.MemoryItem.confidence.desc(),
                models.MemoryItem.ts.desc(),
            )
            .limit(limit)
            .all()
        )

        return [f"[{row.kind}] {row.text}" for row in rows]

    # ------------------------------------------------------------------
    # Write
    # ------------------------------------------------------------------

    def store(
        self,
        db: Session,
        *,
        kind: str,
        text: str,
        source: str = "conversation",
        confidence: float = 0.8,
    ) -> models.MemoryItem:
        """Persist a new memory item for the user.

        Parameters
        ----------
        db:
            SQLAlchemy session.
        kind:
            Category: ``preference``, ``routine``, ``fact``, or ``summary``.
        text:
            Human-readable statement to remember (e.g. "Prefers brief answers").
        source:
            Where this memory came from (e.g. ``"conversation"``, ``"explicit"``).
        confidence:
            0.0–1.0 confidence score; higher items are retrieved first.

        Returns
        -------
        models.MemoryItem
            The newly created and committed memory item.
        """
        item = models.MemoryItem(
            user_id=self.user_id,
            kind=kind,
            text=text,
            source=source,
            confidence=confidence,
            is_active=True,
        )
        db.add(item)
        db.commit()
        db.refresh(item)
        logger.info("Stored memory item [%s] for user %s", kind, self.user_id)
        return item

    def deactivate(self, db: Session, memory_item_id: str) -> bool:
        """Soft-delete a memory item by marking it inactive.

        Parameters
        ----------
        db:
            SQLAlchemy session.
        memory_item_id:
            UUID of the memory item to deactivate.

        Returns
        -------
        bool
            ``True`` if the item was found and deactivated, ``False`` if not found.
        """
        item = (
            db.query(models.MemoryItem)
            .filter(
                models.MemoryItem.id == memory_item_id,
                models.MemoryItem.user_id == self.user_id,
            )
            .first()
        )
        if not item:
            return False
        item.is_active = False
        db.commit()
        return True
