# VERA — Voice-Enabled Reasoning Assistant

Voice-first personal assistant. 32 tools. Memory across sessions. Proactive
suggestions. PWA installable. Approval gates for destructive actions.

> **POC status:** feature-complete for local + Docker. Cloud deploy templates ready.
> See [docs/POC_PLAN.md](docs/POC_PLAN.md) for the 2-week sprint plan.

---

## 30-second start

```bash
git clone <repo> && cd VERA

# Backend deps
python -m venv vera && vera/Scripts/pip install -r backend/requirements.txt

# Postgres (pgvector) via Docker
docker compose up -d postgres

# Edit backend/.env — minimum: GEMINI_API_KEY (free at https://aistudio.google.com/app/apikey)
cp backend/.env.example backend/.env

# Bootstrap schema + extensions
vera/Scripts/python -m backend.init_db
PYTHONIOENCODING=utf-8 vera/Scripts/python -m backend.scripts.enable_pgvector

# Backend
vera/Scripts/uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000

# Frontend (new terminal)
cd client && npm install --legacy-peer-deps && npm run dev
```

Open `http://localhost:5173` → "Sign in with Google" or use email entry.

Or full Docker:
```bash
docker compose up -d --build
```

---

## What VERA can do (32 tools)

| Group | Tools |
|---|---|
| Web | web_search, get_weather, wikipedia_summary, get_datetime, get_news |
| Memory | store_memory (auto + explicit) + semantic recall via pgvector |
| Files | read_file, list_directory, search_files (sensitive-path blocklist) |
| System | send_notification, get_clipboard, set_clipboard |
| Calendar | get_agenda, find_event, create_event, delete_event |
| Gmail | list_emails, read_email, send_email, trash_email, mark_as_read |
| Spotify | spotify_play / pause / skip / now_playing / queue (Premium required) |
| Maps | open_url, maps_directions, maps_search, get_route, nearby_places |
| Utility | convert_currency |

Destructive tools (`send_email`, `delete_event`, `trash_email`) pause for user
approval via an in-app modal before executing.

---

## Architecture

| Layer | Stack |
|---|---|
| Frontend | React + Vite, TypeScript, PWA (vite-plugin-pwa), Web Speech API (STT + TTS + wake-word "Hey VERA") |
| Backend | FastAPI + uvicorn, WebSocket gateway, async tool-calling loop |
| LLM | Gemini 2.5 Flash (default, 250K TPM free) / Groq / Ollama / Mistral, all via OpenAI-compatible interface |
| Memory | PostgreSQL + pgvector (cosine HNSW), fastembed for embeddings (BAAI/bge-small-en-v1.5, 384 dim) |
| Auth | Email-only OR "Sign in with Google" (reuses Calendar/Gmail OAuth) |
| Voice | Browser VAD + STT + TTS, mic sensitivity calibration, echo prevention, wake-word listener |
| Deploy | Docker Compose (dev + prod overlay), Caddy reverse proxy with auto Let's Encrypt |

---

## Documentation

- [`docs/MANUAL_SETUP.md`](docs/MANUAL_SETUP.md) — env vars, API keys, OAuth, pgvector, cloud deploy
- [`docs/POC_PLAN.md`](docs/POC_PLAN.md) — 2-week sprint plan with day-by-day tasks
- [`docs/COPILOT_HANDOFF.md`](docs/COPILOT_HANDOFF.md) — full state for AI coworker handoff
- [`docs/VERA_ROADMAP.md`](docs/VERA_ROADMAP.md) — phased feature roadmap
- [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) — security audit + open issues
- [`docs/WEBSOCKET_API.md`](docs/WEBSOCKET_API.md) — WS event schema + REST routes
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — local dev setup

---

## Security highlights

- All destructive tool calls (send_email, delete_event, trash_email) gated behind a 60s-timeout approval modal
- File-read tools refuse `.env`, `.ssh`, `*.pem`, `id_rsa`, `credentials.json` via path allowlist
- PowerShell-based notification fallback uses base64 `-EncodedCommand` (no injection surface)
- Login endpoint rate-limited (10/min/IP); browser log endpoint same
- Audit log row per tool call (name, args_hash, latency, result_len, error)
- CORS allowlist from env (not wildcard in prod)
- Production deploy includes HSTS + X-Frame-Options + X-Content-Type-Options via Caddy

---

## Status

| Area | State |
|---|---|
| 32 tools registered + tested | ✅ |
| LLM tool-calling loop (Gemini default, 4 providers) | ✅ |
| Cross-session memory (DB-persisted, semantic via pgvector) | ✅ |
| Voice (VAD + STT + TTS + wake word) | ✅ |
| PWA + service worker + install prompt | ✅ |
| Approval gates UI | ✅ |
| Memory editing UI | ✅ |
| Audit log | ✅ |
| Alembic migrations | ✅ |
| Docker Compose (local + prod with Caddy) | ✅ |
| Sign in with Google | ✅ (real name via userinfo, auto-close OAuth tab) |
| MCP integration | ⏳ post-POC |
| RAG knowledge base | ⏳ post-POC |
| Native mobile wrapper | ⏳ post-POC |
