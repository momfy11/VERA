# VERA Backend

FastAPI + uvicorn. SQLAlchemy + Alembic + PostgreSQL (pgvector).
Async tool-calling LLM orchestrator. WebSocket gateway with token-as-first-message auth.

## Quickstart

```bash
# From repo root
python -m venv vera
vera/Scripts/pip install -r backend/requirements.txt

# Start Postgres (pgvector) — port 5433 to avoid host clash
docker compose up -d postgres

# Configure secrets
cp backend/.env.example backend/.env
# Edit: GEMINI_API_KEY (required), GROQ_API_KEY, NEWS_API_KEY, etc.

# Bootstrap schema (idempotent)
vera/Scripts/python -m backend.init_db

# Enable pgvector + apply 0002 migration
PYTHONIOENCODING=utf-8 vera/Scripts/python -m backend.scripts.enable_pgvector

# Run dev server with autoreload
vera/Scripts/uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000
```

API at `http://localhost:8000`. Health: `GET /api/health`.

## Migrations (Alembic — active)

Head: `47ef6902e297_initial`.

```bash
cd backend

# Add new revision after editing models.py
../vera/Scripts/alembic revision --autogenerate -m "describe change"

# Review generated file in migrations/versions/ — Alembic mis-generates type changes sometimes

# Apply
../vera/Scripts/alembic upgrade head

# Inspect current state
../vera/Scripts/alembic current
```

## OAuth credentials (Google)

Place the client-secret JSON downloaded from Cloud Console at
`backend/credentials/client_secret_*.json` (any name matching the glob —
auto-discovered). Token gets saved as `backend/credentials/token.json` after
first sign-in.

CLI fallback for first-run authorization:
```bash
vera/Scripts/python -m backend.scripts.google_authorize
```

UI-driven version: log in once via email or click "Sign in with Google" — the
OAuth tab auto-closes on success.

## Scripts

| Script | Purpose |
|---|---|
| `backend/init_db.py` | Create DB + tables from `models.py` (idempotent) |
| `backend/scripts/enable_pgvector.py` | Apply pgvector migration + backfill embeddings |
| `backend/scripts/google_authorize.py` | One-time CLI Google OAuth (UI does this now) |
| `backend/scripts/demo_reset.py` | Wipe + seed memories for clean demo state |

## Layout

```
backend/
  alembic.ini
  Dockerfile              — Python 3.11 production image
  migrations/             — Alembic env + revisions
  app/
    api/
      routes/             — auth, google, actions, memories, health, suggestions
      ws.py               — WebSocket gateway
      connection_manager.py
    services/
      orchestrator.py     — tool loop + memory + approval gate + audit
      llm.py              — Mistral/Groq/Gemini/Ollama (OpenAI-compatible)
      tools.py            — registry + JSON schema for 32 tools
      memory.py           — pgvector semantic + recency hybrid
      embeddings.py       — fastembed/ollama/openai providers
      audit.py
      approval_gate.py    — asyncio.Event registry per pending action
      google_oauth.py     — shared auth + auto-close OAuth flow
      calendar_tools.py / gmail_tools.py / spotify_tools.py /
      news_tools.py / maps_tools.py / utility_tools.py
      scheduler.py        — APScheduler proactive rules
    core/config.py        — pydantic-settings (reads .env)
    db/
      models.py           — SQLAlchemy ORM (pgvector type for embedding_vector)
      session.py
      migrations/         — legacy raw SQL (kept; enable_pgvector script reads 0002)
    observability/
      logger.py           — RotatingFileHandler for frontend.log
  scripts/
  init_db.py
  requirements.txt
```

## Required env (`backend/.env`)

Minimum for local dev:
```
DATABASE_URL=postgresql+psycopg2://vera:vera@localhost:5433/vera
LLM_PROVIDER=gemini
LLM_MODEL=gemini-2.5-flash
GEMINI_API_KEY=<your key>
ALLOWED_ORIGINS=http://localhost:5173
```

Full list in `docs/MANUAL_SETUP.md`.

## Smoke test

```bash
vera/Scripts/python -c "
from backend.app.services.tools import TOOL_REGISTRY
from backend.app.services.orchestrator import DESTRUCTIVE_TOOLS
print('Tools:', len(TOOL_REGISTRY))
print('Destructive (gated):', sorted(DESTRUCTIVE_TOOLS))
"
```

Expected: `Tools: 32`, `Destructive: ['delete_event', 'send_email', 'trash_email']`.
