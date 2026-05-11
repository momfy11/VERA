# VERA Development Environment

## Prerequisites

- **Python 3.11+** (3.10 might work, untested)
- **Node.js 20+** (Vite 8 requires it)
- **Docker Desktop** (for Postgres + pgvector) — or local Postgres 16 with pgvector built from source
- **Chrome or Edge** (Web Speech API for STT, TTS, and wake word)

## Quick Start

### 1. Backend setup

```bash
# From repo root
python -m venv vera
vera/Scripts/pip install -r backend/requirements.txt
```

### 2. Postgres + pgvector via Docker

```bash
docker compose up -d postgres
# Container "vera-pgvector" on host port 5433 with pgvector extension preinstalled
```

### 3. Configure backend secrets

```bash
cp backend/.env.example backend/.env
```

Minimum required in `backend/.env`:

```
DATABASE_URL=postgresql+psycopg2://vera:vera@localhost:5433/vera
LLM_PROVIDER=gemini
LLM_MODEL=gemini-2.5-flash
GEMINI_API_KEY=<free at https://aistudio.google.com/app/apikey>
ALLOWED_ORIGINS=http://localhost:5173
```

### 4. Bootstrap schema

```bash
vera/Scripts/python -m backend.init_db
PYTHONIOENCODING=utf-8 vera/Scripts/python -m backend.scripts.enable_pgvector
```

The `PYTHONIOENCODING=utf-8` works around Windows cp1252 console limitation
when scripts print ✓/→ glyphs.

### 5. Start backend

```bash
vera/Scripts/uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000
```

Health: `curl http://localhost:8000/api/health` → `{"status":"ok"}`.

### 6. Start frontend

```bash
cd client
npm install --legacy-peer-deps
npm run dev
```

Open `http://localhost:5173` in Chrome or Edge.

### 7. Sign in

- **"Sign in with Google"** — opens an OAuth tab that auto-closes on success. Needs `backend/credentials/client_secret_*.json` present
- OR **"Start with email"** — fallback; any email works (no password)

### 8. (Optional) Seed demo data

```bash
vera/Scripts/python -m backend.scripts.demo_reset
```

## Whole-stack Docker (alternative)

Skip steps 4-7:

```bash
docker compose up -d --build
```

Logs: `docker compose logs -f`.

## Project structure

| Path | Purpose |
|---|---|
| `backend/` | FastAPI app, services, scripts |
| `client/` | React PWA |
| `docs/` | Design + setup docs |
| `logs/` | Runtime logs (`backend.log` resets on restart, `frontend.log` rotates 10MB×5) |
| `docker-compose.yml` | Local 3-service stack |
| `docker-compose.prod.yml` | Caddy + HTTPS overlay |
| `Caddyfile` | Reverse proxy config |
| `.env.production.example` | Public build-time deploy vars |

## Useful commands

```bash
# Backend smoke test
vera/Scripts/python -c "from backend.app.services.tools import TOOL_REGISTRY; print(len(TOOL_REGISTRY))"

# Frontend type check
cd client && npx tsc --noEmit

# Alembic — make a migration
cd backend && ../vera/Scripts/alembic revision --autogenerate -m "msg"
cd backend && ../vera/Scripts/alembic upgrade head

# Tail last 30 backend log lines
tail -n 30 logs/backend.log
```

## Env vars reference

### Backend (`backend/.env`)

| Var | Purpose |
|---|---|
| `LLM_PROVIDER` | `gemini` / `groq` / `ollama` / `mistral` |
| `LLM_MODEL` | model name per provider |
| `GEMINI_API_KEY` | required if provider=gemini |
| `GROQ_API_KEY` | required if provider=groq |
| `DATABASE_URL` | SQLAlchemy URL |
| `ALLOWED_ORIGINS` | comma-separated CORS allowlist (default `http://localhost:5173`) |
| `LOG_LEVEL` | DEBUG/INFO/WARNING/ERROR (default INFO) |
| `VERA_FS_ROOT` | base dir for file tools (default `$HOME`); set to `/app/sandbox` in prod |
| `EMBEDDING_PROVIDER` | `fastembed` (default) / `ollama` / `openai` |
| `EMBEDDING_MODEL` | model name |
| `EMBEDDING_DIM` | must match column dim in DB (default 384) |
| `OLLAMA_URL` | for embeddings or LLM provider |
| `OPENAI_API_KEY` | for embeddings only |
| `SPOTIFY_CLIENT_ID/SECRET` | optional |
| `NEWS_API_KEY` | optional |
| `SECRET_KEY` | session signing (`python -c "import secrets; print(secrets.token_hex(32))"`) |

### Frontend (`client/.env`)

| Var | Purpose |
|---|---|
| `VITE_API_BASE` | REST base (default `http://localhost:8000/api`) |
| `VITE_WS_BASE` | WS base (default `ws://localhost:8000`) |
| `VITE_WAKE_PHRASES` | comma-separated wake-word phrases (default `hey vera,vera`) |

## Documentation index

- `docs/MANUAL_SETUP.md` — credentials, OAuth, pgvector, cloud deploy
- `docs/POC_PLAN.md` — 2-week sprint plan
- `docs/COPILOT_HANDOFF.md` — full state for AI handoff
- `docs/VERA_ROADMAP.md` — phased feature roadmap
- `docs/WEBSOCKET_API.md` — WS event schema + REST routes
- `docs/CODE_REVIEW.md` — security audit + open issues
- `docs/VERA_requests.md` — VERA's own wishlist (raw conversation)

## Troubleshooting

| Symptom | Fix |
|---|---|
| CORS blocked at login | `.env` `ALLOWED_ORIGINS` wrong (duplicate keys take last value — see MANUAL_SETUP warning) |
| `Unknown PG numeric type: 16599` | Restart backend — pgvector type imported via `pgvector.sqlalchemy.Vector` |
| Alembic `InsufficientPrivilege` on ALTER | Tables owned by `postgres`, REASSIGN to `vera` (MANUAL_SETUP §12) |
| Web Speech doesn't work | Use Chrome or Edge (Firefox lacks SpeechRecognition) |
| Google token expires weekly | OAuth app in test mode — click Reconnect in Integrations panel |
| Spotify control returns 403 | App owner needs Premium subscription (Spotify policy) |
| Backend fails on startup with `import.meta.env` TS error | That's frontend — set VS Code interpreter to `vera/Scripts/python.exe` |
