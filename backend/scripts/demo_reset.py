"""Reset to a clean demo state for a single user.

Wipes chat history, suggestions, audit log, and stored memories — then
inserts a small set of seed memories so VERA has interesting context to recall.

Usage:
    vera/Scripts/python -m backend.scripts.demo_reset           # use first user
    vera/Scripts/python -m backend.scripts.demo_reset <email>   # specific user
"""
from __future__ import annotations

import sys

from sqlalchemy import text as sql_text

from backend.app.db.session import SessionLocal
from backend.app.db.models import (
    AgentAction,
    AgentSuggestion,
    AuditLog,
    ChatMessage,
    MemoryItem,
    User,
)
from backend.app.services.embeddings import build_embedding_provider
from backend.app.services.memory import MemoryService

import asyncio


# Seed memories — phrased as user facts so they survive the new extraction prompt
SEED_MEMORIES = [
    ("preference", "User wants VERA to be calm, concise, and proactive — JARVIS-style."),
    ("preference", "Prefers replies in plain English, avoid jargon."),
    ("fact",       "Lives in Helsingborg, Sweden."),
    ("routine",    "Morning standup at 09:00 on weekdays."),
    ("commitment", "Aims to ship VERA POC within 2 weeks."),
]


async def main() -> int:
    target_email = sys.argv[1] if len(sys.argv) > 1 else None

    db = SessionLocal()
    try:
        if target_email:
            user = db.query(User).filter(User.email == target_email).first()
            if not user:
                print(f"❌ No user with email {target_email}")
                return 1
        else:
            user = db.query(User).order_by(User.created_at.asc()).first()
            if not user:
                print("❌ No users in DB. Log in once via the UI first.")
                return 1

        print(f"→ Resetting demo state for: {user.email} (user_id={user.id})")

        # Wipe per-user transient data
        for cls, label in [
            (ChatMessage,      "chat_messages"),
            (AgentSuggestion,  "agent_suggestions"),
            (AgentAction,      "agent_actions"),
            (AuditLog,         "audit_log"),
        ]:
            count = db.query(cls).filter(cls.user_id == user.id).delete(synchronize_session=False)
            print(f"  cleared {label}: {count}")

        # Soft-delete all current memories
        memcount = (
            db.query(MemoryItem)
            .filter(MemoryItem.user_id == user.id, MemoryItem.is_active.is_(True))
            .update({MemoryItem.is_active: False}, synchronize_session=False)
        )
        print(f"  deactivated {memcount} memories")

        db.commit()

        # Insert seed memories with embeddings (if embedder available)
        embedder = build_embedding_provider()
        svc = MemoryService(user_id=user.id)
        for kind, text in SEED_MEMORIES:
            embedding = None
            if embedder:
                try:
                    embedding = await embedder.embed(text)
                except Exception as exc:
                    print(f"  ⚠ embedding failed for '{text}': {exc}")
            svc.store(db, kind=kind, text=text, source="demo_seed",
                      confidence=0.95, embedding=embedding)
            print(f"  + [{kind}] {text}")

        print(f"\n✓ Demo state ready for {user.email}")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
