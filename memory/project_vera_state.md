---
name: VERA project current state
description: Current implementation status as of 2026-05-10 — POC feature-complete
type: project
---

VERA is a JARVIS-style always-on personal AI assistant (voice + proactive
suggestions + 32 tools). POC is feature-complete locally; cloud deploy
templates ready (Caddy + Docker Compose). Target POC ship in 2 weeks.

**Why:** User wants a JARVIS-like assistant — voice, memory, proactive,
acts on user's behalf with approval gates.

**How to apply:** When suggesting next steps, point at `docs/POC_PLAN.md`
for current sprint. Avoid rebuilding things already shipped. For new
features see `docs/VERA_ROADMAP.md` priority table.

## What's live (as of 2026-05-10)

### Backend
- FastAPI + WebSocket gateway with token-as-first-message auth
- PostgreSQL 16 + pgvector (Docker on host port 5433)
- Alembic migrations active; initial revision `47ef6902e297` is head
- **4 LLM providers**: Gemini (default, 250K TPM free), Groq, Ollama, Mistral
- Orchestrator with tool-loop (max 6 rounds), history compaction at 30 msgs, async background memory extraction, pre-tool ack via `assistant.thinking` events, approval gate via `agent.action_pending`, audit log per tool call
- **32 tools** registered: web, memory, files, system, calendar, gmail, spotify, maps, news, currency
- Destructive tools (`send_email`, `delete_event`, `trash_email`) gated through approval modal (60s timeout, fail-closed)
- Memory service with hybrid retrieval (semantic via pgvector cosine HNSW + confidence + recency decay)
- Embeddings via fastembed (BAAI/bge-small-en-v1.5, 384 dim)
- Audit log writes for every tool call (name, args_hash, latency_ms, result_len, error)
- "Sign in with Google" using existing Desktop OAuth client, auto-closing OAuth tab, fetches real display name via userinfo endpoint
- Rate-limited login (10/min/IP) and `/api/log` (60/min/IP)
- CORS allow_origins from `ALLOWED_ORIGINS` env (not wildcard)
- Log rotation: `frontend.log` via RotatingFileHandler (10MB × 5)

### Frontend (PWA)
- React + Vite + TypeScript strict
- LoginPage with "Sign in with Google" + email fallback
- MainPage: top bar (status pills, hands-free toggle, install button, cogwheel), full-height chat (markdown rendered), voice bar (sensitivity slider, interim transcript), text input
- Voice: VAD + STT + TTS via Web Speech API, mute during TTS (350ms grace), noise-floor calibration, sensitivity slider persisted to localStorage, STT VAD-gating, confidence threshold
- Wake word: Web Speech API based (NOT Picovoice — Picovoice console blocks personal accounts), fuzzy phrase match "hey vera"/"vera"
- TTS: markdown stripped so VERA reads naturally
- PWA install prompt + iOS Add-to-Home-Screen hint
- Session token persisted in localStorage — refresh keeps logged in
- Cogwheel drawer: IntegrationsPanel (Google), MemoriesPanel (view+delete), SuggestionsPanel, SettingsPanel
- Approval modal with 60s countdown for destructive tools
- Toast notifications for rate-limit, quota, and other errors

### Deploy
- `docker-compose.yml` — local 3-service stack
- `docker-compose.prod.yml` overlay — adds Caddy reverse proxy
- `Caddyfile` — auto Let's Encrypt HTTPS + WSS routing
- `.env.production.example` template
- Hetzner deploy instructions in `docs/MANUAL_SETUP.md` §11b

## Still not done (post-POC backlog)

- "Sign in with Google" on cloud deploy — Web OAuth client (not Desktop) may be needed if running headless
- httpx singleton lifecycle close on shutdown (P2)
- STT lang configurable from user_settings table (P2)
- WebSocket auto-reconnect with backoff (P1)
- Memory search/filter in MemoriesPanel
- React error boundary
- Per-user LLM token budget + alerting
- MCP client + server (3 days post-POC)
- RAG knowledge base
- Vision / image upload
- Code execution sandbox
- openWakeWord offline (replace Web Speech wake)
- Native iOS/Android wrapper
- CI pipeline
- Pytest suite (no tests yet)

## Critical files

- `backend/app/services/orchestrator.py` — main brain (tool loop + approval + audit + memory)
- `backend/app/services/tools.py` — 32-tool registry
- `backend/app/services/llm.py` — provider abstraction (Mistral / Groq / OpenAI-compatible for Gemini+Ollama)
- `backend/app/services/google_oauth.py` — OAuth + `run_oauth_with_autoclose`
- `backend/app/services/memory.py` — pgvector semantic retrieval
- `backend/app/services/approval_gate.py` — asyncio.Event registry
- `backend/app/db/models.py` — `embedding_vector` uses `pgvector.sqlalchemy.Vector(384)`
- `client/src/App.tsx` — top-level state + WS handlers
- `client/src/components/MainPage.tsx` — main UI shell
- `client/src/lib/useVoiceSession.ts` — VAD + STT + mute + sensitivity
- `client/src/lib/useWakeWord.ts` — Web Speech wake-word

## Common gotchas (from `docs/COPILOT_HANDOFF.md`)

1. WebSocket routing in FastAPI silently fails — `_NoCORSForWebSocket` dispatches directly
2. React StrictMode double-mounts effects — `intentionalCloseRef` survives cleanup-rerun
3. `asyncio.create_task` orphans — held in `_bg_tasks` set
4. PowerShell command injection — `send_notification` uses base64 `-EncodedCommand`
5. Sensitive paths blocked — `_safe_path` rejects `.env`/`.ssh`/`*.pem`
6. Tool descriptions count toward TPM — keep ≤120 chars each
7. `store_memory` returns marker, orchestrator handles DB write
8. Spotify needs Premium for app owner — friendly error shown
9. Test-mode Google tokens expire weekly — UI reconnect button
10. Approval gate timeout = reject (fail-closed)
11. `agent.open_url` fires LIVE mid-turn (not deferred)
12. Wake word and main voice share mic — wake auto-pauses during session
13. `.env` parser uses LAST value on duplicate keys
14. Picovoice removed — Web Speech wake word replaces it

## Env vars summary

Backend essentials:
```
DATABASE_URL=postgresql+psycopg2://vera:vera@localhost:5433/vera
LLM_PROVIDER=gemini
LLM_MODEL=gemini-2.5-flash
GEMINI_API_KEY=<key>
ALLOWED_ORIGINS=http://localhost:5173
```

Optional: `NEWS_API_KEY`, `SPOTIFY_CLIENT_ID/SECRET`, `EMBEDDING_PROVIDER`, `VERA_FS_ROOT`, `SECRET_KEY`.
