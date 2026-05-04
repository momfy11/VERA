"""Memory service — store and retrieve user preference/fact memory items.

Phase 5.1: pgvector + embeddings. Semantic retrieval when pgvector is
available + an embedding provider is configured. Falls back to recency
ranking when either is missing — never crashes.

Retrieval ranking is hybrid:
    score = cosine_similarity * 0.7 + confidence * 0.2 + recency_decay * 0.1
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from sqlalchemy import text as sql_text
from sqlalchemy.orm import Session

from backend.app.db import models

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# pgvector availability detection — checked once on first use, cached
# ---------------------------------------------------------------------------

_pgvector_checked = False
_pgvector_available = False


def is_pgvector_available(db: Session) -> bool:
    """Return True if the postgres pgvector extension is installed.

    Cached after first call. If pgvector is missing, semantic search is
    disabled and retrieve() falls back to recency ranking.
    """
    global _pgvector_checked, _pgvector_available
    if _pgvector_checked:
        return _pgvector_available
    _pgvector_checked = True
    try:
        result = db.execute(sql_text(
            "SELECT 1 FROM pg_extension WHERE extname = 'vector'"
        )).first()
        _pgvector_available = result is not None
        if _pgvector_available:
            logger.info("pgvector extension detected — semantic memory enabled")
        else:
            logger.warning(
                "pgvector extension NOT installed — semantic memory disabled. "
                "See docs/MANUAL_SETUP.md §7 to enable."
            )
        return _pgvector_available
    except Exception as exc:
        logger.warning("pgvector check failed: %r — assuming not available", exc)
        db.rollback()
        _pgvector_available = False
        return False


# ---------------------------------------------------------------------------
# MemoryService
# ---------------------------------------------------------------------------

class MemoryService:
    """Manages long-term memory items for a user.

    Memory items are plain-text facts or preferences extracted during
    conversations.  They are persisted in ``memory_items`` and injected
    as context into the LLM system prompt.
    """

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    def retrieve(
        self,
        db: Session,
        limit: int = 15,
        query_embedding: list[float] | None = None,
    ) -> list[str]:
        """Return relevant memory items as ``"[kind] text"`` strings.

        If ``query_embedding`` is provided AND pgvector is installed, ranks
        by hybrid (semantic similarity + confidence + recency).
        Otherwise falls back to confidence-then-recency ranking.
        """
        if query_embedding is not None and is_pgvector_available(db):
            try:
                return self._retrieve_semantic(db, query_embedding, limit)
            except Exception as exc:
                logger.warning("Semantic retrieval failed (%r) — falling back to recency", exc)
                db.rollback()
        return self._retrieve_recency(db, limit)

    def _retrieve_recency(self, db: Session, limit: int) -> list[str]:
        """Confidence + recency ranking (no embedding needed)."""
        now = datetime.now(timezone.utc)
        rows = (
            db.query(models.MemoryItem)
            .filter(
                models.MemoryItem.user_id == self.user_id,
                models.MemoryItem.is_active.is_(True),
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
        return [f"[{r.kind}] {r.text}" for r in rows]

    def _retrieve_semantic(
        self, db: Session, query_embedding: list[float], limit: int,
    ) -> list[str]:
        """Hybrid ranking: cosine similarity + confidence + recency decay.

        Uses pgvector's <=> cosine-distance operator. Returns memories
        ordered by descending hybrid score.

        The query computes:
            similarity = 1 - (embedding <=> query)        # 0..1
            recency    = exp(-age_days / 30)              # 1 → 0 over ~3 months
            score      = similarity*0.7 + confidence*0.2 + recency*0.1

        Items without embeddings are excluded from semantic ranking.
        """
        # Format Python list as a pgvector literal: '[0.1,0.2,...]'
        vec_literal = "[" + ",".join(f"{v:.6f}" for v in query_embedding) + "]"

        rows = db.execute(
            sql_text("""
                SELECT
                    kind,
                    text,
                    (
                        (1 - (embedding_vector::vector <=> CAST(:q AS vector))) * 0.7
                        + COALESCE(confidence, 0) * 0.2
                        + GREATEST(0, 1 - EXTRACT(EPOCH FROM (NOW() - ts)) / (30 * 86400)) * 0.1
                    ) AS score
                FROM memory_items
                WHERE user_id = CAST(:uid AS uuid)
                  AND is_active = TRUE
                  AND embedding_vector IS NOT NULL
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY score DESC
                LIMIT :limit
            """),
            {"q": vec_literal, "uid": self.user_id, "limit": limit},
        ).fetchall()

        return [f"[{r.kind}] {r.text}" for r in rows]

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
        embedding: list[float] | None = None,
    ) -> models.MemoryItem:
        """Persist a new memory item, optionally with a precomputed embedding.

        Caller is expected to compute the embedding asynchronously and pass it
        in — this keeps store() itself non-blocking.
        """
        item = models.MemoryItem(
            user_id=self.user_id,
            kind=kind,
            text=text,
            source=source,
            confidence=confidence,
            is_active=True,
            embedding_vector=embedding,
        )
        db.add(item)
        db.commit()
        db.refresh(item)
        logger.info(
            "Stored memory [%s] for user %s%s",
            kind, self.user_id,
            " (with embedding)" if embedding else "",
        )
        return item

    def deactivate(self, db: Session, memory_item_id: str) -> bool:
        """Soft-delete a memory item by marking it inactive."""
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
