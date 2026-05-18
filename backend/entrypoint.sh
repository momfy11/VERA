#!/usr/bin/env bash
# Backend container entrypoint.
#
# Responsibilities, in order:
#   1. Wait for Postgres to actually accept connections (depends_on healthcheck
#      already does this, but be defensive — pg can be "healthy" yet still
#      refuse for a moment during user creation).
#   2. Create tables from the current SQLAlchemy models on a fresh DB
#      (idempotent — uses CREATE TABLE IF NOT EXISTS via Base.metadata.create_all).
#   3. Tell Alembic where we are:
#        - fresh DB → `alembic stamp head` (we just built schema directly from models)
#        - existing DB with alembic_version row → `alembic upgrade head` (apply pending migrations)
#   4. Enable pgvector extension + backfill embeddings (idempotent, best-effort —
#      backend still runs without it, semantic memory falls back to recency).
#   5. Exec uvicorn (PID 1) so signals propagate correctly.
#
# Designed to be safe to re-run on every container start.

set -euo pipefail

cd /app

echo "[entrypoint] Waiting for Postgres at $(python - <<'PY'
from urllib.parse import urlparse
import os
url = os.environ.get("DATABASE_URL", "")
u = urlparse(url.replace("postgresql+psycopg2://", "postgresql://", 1))
print(f"{u.hostname}:{u.port or 5432}")
PY
)..."

python - <<'PY'
import os, time, sys
from urllib.parse import urlparse
import psycopg2

url = os.environ["DATABASE_URL"].replace("postgresql+psycopg2://", "postgresql://", 1)
u = urlparse(url)
deadline = time.time() + 60
while time.time() < deadline:
    try:
        conn = psycopg2.connect(
            host=u.hostname, port=u.port or 5432,
            user=u.username, password=u.password,
            dbname=u.path.lstrip("/"),
            connect_timeout=3,
        )
        conn.close()
        print("[entrypoint] Postgres reachable", flush=True)
        sys.exit(0)
    except Exception as e:
        print(f"[entrypoint] pg not ready yet: {e}", flush=True)
        time.sleep(2)
print("[entrypoint] Postgres did not become reachable in 60s", file=sys.stderr)
sys.exit(1)
PY

echo "[entrypoint] Initialising tables (idempotent)..."
python -m backend.init_db

echo "[entrypoint] Reconciling Alembic state..."
cd /app/backend
HAS_VERSION=$(python - <<'PY'
import os, psycopg2
from urllib.parse import urlparse
url = os.environ["DATABASE_URL"].replace("postgresql+psycopg2://", "postgresql://", 1)
u = urlparse(url)
conn = psycopg2.connect(
    host=u.hostname, port=u.port or 5432,
    user=u.username, password=u.password,
    dbname=u.path.lstrip("/"),
)
cur = conn.cursor()
cur.execute("""
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'alembic_version'
    )
""")
table_exists = cur.fetchone()[0]
if not table_exists:
    print("none")
else:
    cur.execute("SELECT version_num FROM alembic_version LIMIT 1")
    row = cur.fetchone()
    print(row[0] if row else "none")
cur.close(); conn.close()
PY
)

if [ "$HAS_VERSION" = "none" ]; then
    echo "[entrypoint] Fresh DB — stamping alembic head"
    python -m alembic stamp head
else
    echo "[entrypoint] DB already at alembic revision $HAS_VERSION — running upgrade head"
    python -m alembic upgrade head
fi
cd /app

echo "[entrypoint] Enabling pgvector + backfilling embeddings (best effort)..."
python -m backend.scripts.enable_pgvector || \
    echo "[entrypoint] pgvector setup skipped (extension not available or already configured); semantic memory will fall back to recency"

echo "[entrypoint] Starting uvicorn..."
exec uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips='*'
