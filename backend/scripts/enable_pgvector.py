"""Enable pgvector + backfill embeddings for existing memories.

Run once after installing pgvector (see docs/MANUAL_SETUP.md §7):

    vera/Scripts/python -m backend.scripts.enable_pgvector

What it does:
1. Verifies pgvector extension is installed
2. Applies migration 0002_pgvector.sql (idempotent — safe to re-run)
3. Generates embeddings for all memory_items where embedding_vector IS NULL
4. Reports how many memories were embedded

Idempotent: re-running only embeds new memories, never reprocesses existing ones.
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from sqlalchemy import text as sql_text

from backend.app.db.session import SessionLocal
from backend.app.services.embeddings import build_embedding_provider

MIGRATION_SQL = Path(__file__).parent.parent / "app" / "db" / "migrations" / "0002_pgvector.sql"


def check_pgvector(db) -> bool:
    """Return True if pgvector extension is installed."""
    try:
        result = db.execute(sql_text(
            "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'"
        )).first()
        return result is not None
    except Exception as exc:
        print(f"❌ Could not check pg_available_extensions: {exc}")
        return False


def apply_migration(db) -> None:
    """Apply 0002_pgvector.sql. Idempotent."""
    sql = MIGRATION_SQL.read_text(encoding="utf-8")
    print(f"→ Applying migration {MIGRATION_SQL.name}")
    # Split on ; for psycopg2 since execute() runs one statement at a time
    statements = [s.strip() for s in sql.split(";") if s.strip() and not s.strip().startswith("--")]
    for stmt in statements:
        # Strip leading --comment lines from each statement
        clean = "\n".join(line for line in stmt.splitlines() if not line.strip().startswith("--"))
        if clean.strip():
            db.execute(sql_text(clean))
    db.commit()
    print("✓ Migration applied")


async def backfill_embeddings(db, embedder) -> int:
    """Embed every memory_item with NULL embedding_vector. Returns count embedded."""
    rows = db.execute(sql_text(
        "SELECT id, text FROM memory_items WHERE embedding_vector IS NULL AND is_active = TRUE"
    )).fetchall()

    if not rows:
        print("✓ No memories need embedding (all already done)")
        return 0

    print(f"→ Embedding {len(rows)} memories...")
    count = 0
    for row in rows:
        try:
            vec = await embedder.embed(row.text[:2000])
            vec_literal = "[" + ",".join(f"{v:.6f}" for v in vec) + "]"
            db.execute(
                sql_text("UPDATE memory_items SET embedding_vector = CAST(:v AS vector) WHERE id = CAST(:id AS uuid)"),
                {"v": vec_literal, "id": str(row.id)},
            )
            count += 1
            if count % 10 == 0:
                print(f"  … {count}/{len(rows)}")
        except Exception as exc:
            print(f"  ⚠ Failed to embed memory {row.id}: {exc}")
    db.commit()
    print(f"✓ Embedded {count} memories")
    return count


async def main() -> int:
    db = SessionLocal()
    try:
        if not check_pgvector(db):
            print(
                "❌ pgvector extension is not available in this PostgreSQL.\n"
                "   See docs/MANUAL_SETUP.md §7 to install it first.\n"
                "   On Windows the easiest path is the Docker pgvector/pgvector:pg16 image."
            )
            return 1

        apply_migration(db)

        embedder = build_embedding_provider()
        if not embedder:
            print("⚠ No embedding provider available — skipping backfill")
            print("  Install fastembed: vera/Scripts/pip install fastembed")
            return 0

        await backfill_embeddings(db, embedder)
        print("\n✓ Done. Restart the backend to start using semantic memory.")
        return 0

    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
