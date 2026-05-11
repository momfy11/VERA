# VERA — Handoff to Copilot

Pickup doc. State: **MVP feature-complete, hardening + polish for 2-week POC**.

---

## What works (don't break)

### Backend — 32 tools registered in `backend/app/services/tools.py`

| Group | Tools | File |
|---|---|---|
| Web | web_search, get_weather, wikipedia_summary, get_datetime, get_news | `tools.py`, `news_tools.py` |
| Memory | store_memory | `tools.py` + orchestrator interception |
| Files | read_file, list_directory, search_files | `tools.py` |
| System | send_notification, get_clipboard, set_clipboard | `tools.py` |
| Calendar | get_agenda, find_event, create_event, delete_event | `calendar_tools.py` |
| Gmail | list_emails, read_email, send_email, trash_email, mark_as_read | `gmail_tools.py` |
| Spotify | spotify_play/pause/skip/now_playing/queue (Premium needed) | `spotify_tools.py` |
| Maps | open_url, maps_directions, maps_search, get_route, nearby_places | `maps_tools.py` |
| Utility | convert_currency | `utility_tools.py` |

LLM providers via `LLM_PROVIDER` env:
- `gemini` ⭐ (default — 250K TPM free, native tool calling)
- `groq` (8K TPM free, fast)
- `ollama` (local, slow on laptop)
- `mistral`

REST routes (`/api`):
- `auth/login` (rate-limited 10/min/IP)
- `health`, `log` (rate-limited 60/min/IP)
- `suggestions`, `suggestions/{id}` (PATCH)
- `google/{status,connect,disconnect}` (OAuth flow from UI)
- `memories`, `memories/{id}` (DELETE)
- `actions/{action_id}/{approve|reject}` (destructive-tool gating)

Other backend systems:
- WebSocket gateway (`api/ws.py`) — token-as-first-message auth, 60 msgs/min rate limit
- Orchestrator (`services/orchestrator.py`):
  - Tool loop max 6 rounds
  - History compaction at 30 msgs (LLM summarizes oldest, stored as memory)
  - **Pre-tool ack**: emits `assistant.thinking` event with phrase per tool ("Checking your inbox…") — TTS speaks it before LLM finishes
  - **Approval gate**: send_email/delete_event/trash_email pause for user confirm via WS modal (60s timeout, fail-closed)
  - **Audit log**: every tool call written to `audit_log` (tool name, args_hash, latency_ms, result_len, error)
  - Async background memory extraction with own DB session
- Memory service (`services/memory.py`) — pgvector semantic retrieval (when extension installed) with recency fallback
- Embeddings (`services/embeddings.py`) — fastembed default (BAAI/bge-small-en-v1.5, 384 dim)
- Google OAuth (`services/google_oauth.py`) — load+refresh, cached service builders
- Approval gate (`services/approval_gate.py`) — asyncio.Event registry by action_id
- Audit (`services/audit.py`) — log_event helper
- Proactive scheduler (`services/scheduler.py`) — APScheduler hour-window rules

### Frontend

- LoginPage / MainPage routing in `App.tsx` based on `sessionStatus`
- **Session token persisted to `localStorage`** — refresh keeps user logged in
- Voice:
  - `useVoiceSession.ts` — VAD + STT + mute flag + **noise-floor calibration + sensitivity slider** + STT VAD-gating + confidence threshold
  - `useTTS.ts` — Web Speech API SpeechSynthesis, **markdown-stripped** for natural reading
  - `useWakeWord.ts` — **Web Speech API based** wake-word listener (replaced Picovoice; fuzzy phrase match on "hey vera"/"vera" by default)
- **PWA**: `vite-plugin-pwa` generates manifest + service worker, install prompt via `useInstallPrompt.ts`
- Pre-tool ack: `assistant.thinking` event displayed + spoken via TTS
- **Approval modal**: `ApprovalModal.tsx` shows on `agent.action_pending`, 60s countdown, Allow/Deny → POST /actions/{id}
- Markdown chat rendering via `react-markdown` + `remark-gfm`
- Cogwheel drawer panels: IntegrationsPanel, MemoriesPanel (view+delete), SuggestionsPanel, SettingsPanel

### DB

PostgreSQL 16. Tables: users, sessions, session_events, agent_suggestions, agent_actions, memory_items, memory_feedback, audit_log, metrics_rollup, chat_messages, alembic_version.

**Alembic now active** — `backend/alembic.ini` + `backend/migrations/`. Initial revision `47ef6902e297` is head. All tables owned by `vera` role.

---

## 2-Week POC Plan

Critical path. Weeks split by Friday demo target.

### Week 1 — verify + polish

| # | Item | Effort | Why for POC |
|---|---|---|---|
| 1 | **Fix `.env` duplicate keys** (LLM_PROVIDER, ALLOWED_ORIGINS, ENVIRONMENT, LLM_MODEL appear twice — last value wins, currently breaks Gemini + dev frontend) | 5 min | blocker |
| 2 | Verify Gemini active: `LLM_PROVIDER=gemini`, `GEMINI_API_KEY` set | 1 min | reasoning + tools work |
| 3 | Verify CORS: `ALLOWED_ORIGINS=http://localhost:5173` for dev, comma-add prod URL when ready | 1 min | frontend can reach backend |
| 4 | Install pgvector + run `vera/Scripts/python -m backend.scripts.enable_pgvector` | 30 min Docker / 1h manual | semantic memory recall = POC wow moment |
| 5 | End-to-end demo recording: text + voice + tool + memory + approval modal | 30 min | proves it works |
| 6 | Smoke test on mobile browser (PWA install + wake word + voice) | 30 min | mobile story |
| 7 | Write demo script (5-min walkthrough) | 30 min | repeatable demo |

### Week 2 — POC ship

| # | Item | Effort | Why for POC |
|---|---|---|---|
| 8 | **Sign in with Google** replacing email-only login (uses existing OAuth) | 4 hr | one-click trust signal |
| 9 | Demo seed script — load 3-5 sample memories, test calendar event, test email draft | 1 hr | clean state per demo |
| 10 | Error UX polish: rate-limit hits, Gemini quota exceeded, OAuth expired → user-friendly toast | 2 hr | nothing breaks on stage |
| 11 | Token cost dashboard (P3 from earlier) — IntegrationsPanel shows current Gemini usage | 2 hr | shows responsible spend |
| 12 | README.md with 30-second pitch + screenshots + start command | 1 hr | newcomer onboarding |
| 13 | Deploy to a public URL (optional) — Render/Railway free tier for backend, Vercel for frontend | 4 hr | shareable link for stakeholders |
| 14 | Loom video walkthrough | 1 hr | async demo |

**Skip for POC** (defer to v1): Spotify Premium, openWakeWord offline, native mobile wrapper, multi-user auth, RAG knowledge base.

---

## Pending work (post-POC, ranked)

### P1 — UX gaps

- [ ] Sign in with Google as primary login (currently email-only with no password verification — ANY email works)
- [ ] httpx singleton lifecycle close on shutdown
- [ ] STT lang configurable from `user_settings` table
- [ ] WebSocket auto-reconnect with backoff on transient disconnect
- [ ] Memory grouping/search in MemoriesPanel (currently lists all up to 200)

### P2 — Performance / hardening

- [ ] Per-user Gemini token budget + alerting (avoid surprise quota hits)
- [ ] DDG instant-answer fallback dead code in `web_search` — remove or actually trigger
- [ ] React error boundary (component crashes = white screen now)
- [ ] CI pipeline: `.github/workflows/ci.yml` running pytest + tsc

### P3 — Roadmap features

See `docs/VERA_ROADMAP.md`. Highlights of what's NOT started:
- Phase 2.5 Code execution sandbox (`run_python` tool)
- Phase 4.3 Vision (image upload, screenshot analysis)
- Phase 5.2 RAG knowledge base (engineering domain knowledge)
- Phase 6 Predictive suggestions, multi-agent
- Phase 7 Smart home, native mobile wrapper, wearables

### MCP (Model Context Protocol) — post-POC, ~3 days total

Two integration directions, both valuable:

**MCP client** (~2 days) — VERA consumes external MCP servers as additional tool sources.
- Use cases: Filesystem MCP (replace our file tools), GitHub MCP (PR/issue management), Brave Search MCP (better web search than DDG free), Slack/Discord MCP (workplace), Postgres MCP (NL → SQL on own DB), Playwright MCP (browser automation)
- Impl: install `mcp` Python SDK, async stdio/HTTP transport, JSON-RPC parser, discover server's `tools/list`, translate schemas OpenAI ↔ MCP, register dynamically into `TOOL_REGISTRY` at startup, route calls to MCP server when tool name matches
- Config: list of MCP servers in `backend/.env` or settings table: `command`, `args`, `env`
- Auth: per-server (env-pass through)

**MCP server** (~1 day) — VERA exposes its 32 tools so Claude Desktop / other LLMs use them.
- Wrap `TOOL_REGISTRY` in MCP framing (tool descriptions are already JSON Schema)
- Stdio transport for local clients (Claude Desktop)
- HTTP transport for remote clients
- Auth: bearer token via VERA session

Not a quick fix. Defer.

---

## Conventions Copilot must follow

### Code style

- Python: type hints everywhere, `from __future__ import annotations` at top
- Async: use `asyncio.get_running_loop()`, never deprecated `get_event_loop()`
- DB: SQLAlchemy ORM. Raw `text()` only for pgvector cosine ops (`<=>`)
- React: functional components, hooks. No class components.
- TypeScript strict. No `any` without comment justifying.
- Imports: stdlib → third-party → local backend → local frontend, blank between groups.

### Tools (when adding new ones)

1. Async function in appropriate `services/<group>_tools.py` file
2. Returns string (human-readable success or error). NEVER raises — catch all exceptions and return error message
3. Long-running ops (HTTP, file I/O on slow disks, subprocesses): wrap in `asyncio.get_running_loop().run_in_executor()` or use `httpx.AsyncClient`
4. Add JSON schema to `TOOL_DEFINITIONS` list in `tools.py`
5. Add function to `TOOL_REGISTRY` dict in `tools.py`
6. Tool descriptions ≤120 chars (token budget). Total system prompt + tools must stay under ~3000 tokens
7. Destructive tools (mutating external state): add to `DESTRUCTIVE_TOOLS` set in `orchestrator.py` + add summary to `_summarize_destructive`
8. Add ack phrase to `_ack_phrase` in orchestrator

### Memory extraction prompt is sensitive

`backend/app/services/orchestrator.py` `_EXTRACTION_SYSTEM` constant. Hand-tuned to avoid garbage facts (assistant-about-self, transient requests, advice-as-fact). Don't rewrite without testing 20+ exchanges first.

### LLM model selection

Gemini default (`gemini-2.5-flash`). 250K TPM, free, native tool calling. Switch via `LLM_PROVIDER` env:
- `groq` + `openai/gpt-oss-20b` — fast, OK with tool calling, 8K TPM
- `ollama` + `qwen2.5:7b` — local, slow on laptop, 100% private

### Migration discipline (Alembic)

1. Edit models in `backend/app/db/models.py`
2. `cd backend && ../vera/Scripts/alembic revision --autogenerate -m "describe change"`
3. Review generated `migrations/versions/*.py` — Alembic mis-generates type changes sometimes
4. `cd backend && ../vera/Scripts/alembic upgrade head`
5. Commit both model + migration

### Frontend layout

- TypeScript compile must stay clean (`npx tsc --noEmit`)
- All API calls go through `lib/api.ts`
- New panels in `components/`, added to `MainPage.tsx` settings drawer
- Styling in `styles.css` (no CSS modules, no Tailwind)
- CSS variables: `--accent`, `--accent-2`, `--good`, `--warn`, `--info`, `--muted`, `--ink`, `--bg`, `--panel`

---

## How to test

### Backend smoke

```bash
cd c:/Users/momfy/repos/VERA
vera/Scripts/python -c "
from backend.app.services.tools import TOOL_REGISTRY
from backend.app.services.orchestrator import Orchestrator, DESTRUCTIVE_TOOLS
print('Tools:', len(TOOL_REGISTRY))
print('Destructive (gated):', DESTRUCTIVE_TOOLS)
"
```

Expected: `Tools: 32` and `{'send_email', 'delete_event', 'trash_email'}`.

### Frontend type check

```bash
cd client && npx tsc --noEmit
```

Expected: no output, exit 0.

### End-to-end

1. Start postgres
2. `cd backend && ../vera/Scripts/uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000`
3. `cd client && npm run dev`
4. Browser → http://localhost:5173
5. Login with email
6. Settings → Integrations → Connect Google → approve
7. Chat: "what's on my calendar today" — VERA emits thinking ack, calls `get_agenda`, returns events
8. Chat: "send email to alice@example.com saying hi" — approval modal pops, click Allow
9. Click **Hands-free** → say "Hey VERA, what's the weather in Stockholm" → wake fires → VERA replies + speaks

---

## Critical environment

- `backend/.env` — all keys (Gemini, Groq, Mistral, NewsAPI, Spotify). DO NOT commit. **Currently has duplicate keys — fix before next demo.**
- `backend/credentials/client_secret_*.json` — Google OAuth client secret (Desktop app type)
- `backend/credentials/token.json` — created by OAuth flow, refreshed automatically
- `backend/credentials/spotify_token.json` — created by spotipy on first call
- `client/.env` — optional `VITE_WAKE_PHRASES=` to override default trigger phrases
- `logs/backend.log` — backend log, mode='w' resets on restart
- `logs/frontend.log` — frontend log, RotatingFileHandler 10MB × 5 backups

---

## Files map

```
backend/
  alembic.ini             — Alembic config (sqlalchemy.url hardcoded for dev)
  migrations/
    env.py                — Alembic env, imports init_db.Base
    versions/             — generated migrations (head: 47ef6902e297_initial)
  app/
    api/
      routes/
        auth.py           — POST /api/auth/login (10/min/IP rate-limited)
        google.py         — Google OAuth status/connect/disconnect
        health.py         — /api/health, /api/log
        suggestions.py    — GET/PATCH /api/suggestions
        memories.py       — GET /api/memories, DELETE /api/memories/{id}
        actions.py        — POST /api/actions/{id}/{approve|reject}
      ws.py               — WebSocket gateway (token-as-first-msg)
      connection_manager.py
    core/config.py        — pydantic Settings
    db/
      models.py           — SQLAlchemy ORM
      session.py          — get_db, SessionLocal
      migrations/         — raw SQL files (legacy, superseded by alembic)
    services/
      orchestrator.py     — main brain, tool loop, thinking acks, approval gate, audit
      llm.py              — LLMClient ABC + Mistral/Groq/OpenAICompatible (Gemini/Ollama)
      memory.py           — MemoryService, semantic retrieval w/ recency fallback
      embeddings.py       — fastembed/ollama/openai providers
      tools.py            — 32 tool definitions + registry
      calendar_tools.py
      gmail_tools.py
      spotify_tools.py
      news_tools.py
      maps_tools.py       — open_url, directions, search, route, nearby
      utility_tools.py    — convert_currency
      google_oauth.py     — shared Google auth + service builders
      audit.py            — log_event helper for audit_log table
      approval_gate.py    — asyncio.Event registry per action_id
      scheduler.py        — APScheduler proactive rules
    main.py               — FastAPI app + WS bypass middleware + CORS from env
  scripts/
    google_authorize.py   — one-time Google OAuth (CLI fallback, UI does this now)
    enable_pgvector.py    — apply 0002_pgvector + backfill embeddings
  init_db.py              — manual schema bootstrap (legacy; alembic preferred)

client/src/
  components/
    LoginPage.tsx
    MainPage.tsx          — main app shell, top bar, voice, settings drawer
    IntegrationsPanel.tsx — Google connect/disconnect status
    MemoriesPanel.tsx     — list + delete VERA's stored memories
    ApprovalModal.tsx     — destructive-tool Allow/Deny + countdown
    SettingsPanel.tsx
    SuggestionsPanel.tsx
    StatusPill.tsx
    ChatPanel.tsx (legacy, unused — main chat in MainPage)
    VoicePanel.tsx (legacy)
    SessionPanel.tsx (legacy)
  lib/
    api.ts                — REST wrappers
    ws.ts                 — createSessionSocket
    types.ts
    logger.ts             — console.error/warn → POST /api/log
    useVoiceSession.ts    — VAD + STT + mute + sensitivity + calibration
    useTTS.ts             — Web Speech API + markdown stripping
    useWakeWord.ts        — Web Speech API based wake-word listener
    useInstallPrompt.ts   — PWA install button helper
    stripMarkdown.ts
  App.tsx                 — top-level state, WS handlers, routes Login↔Main
  styles.css
```

---

## WebSocket event types (push from server)

| Type | Payload | Purpose |
|---|---|---|
| `server.hello` | `{ts, display_name}` | Auth success |
| `server.error` | `{message}` | Auth/rate/etc errors |
| `assistant.text` | `{text}` | Final assistant reply |
| `assistant.thinking` | `{text, tool}` | Pre-tool ack ("Checking your inbox…") |
| `assistant.tts_cancel` | `{ts}` | Stop TTS (barge-in trigger) |
| `agent.suggestion` | `{id, type, priority, title, reason, ts, status}` | Proactive suggestion |
| `agent.open_url` | `{url, label}` | Open URL in browser (Maps etc.) |
| `agent.action_pending` | `{action_id, tool, summary, args, timeout_s}` | Destructive-tool approval needed |
| `agent.action_resolved` | `{action_id, decision}` | Modal can dismiss |

---

## Hot gotchas (read before touching)

1. **WebSocket routing in FastAPI silently fails** — `main.py` has `_NoCORSForWebSocket` middleware that dispatches WS scope directly to `websocket_endpoint`. Don't try `@app.websocket()` — doesn't work.
2. **React StrictMode double-mounts effects** — `App.tsx` `useEffect` cleanup sets `intentionalCloseRef.current = true` to survive cleanup-then-rerun. Don't remove that ref.
3. **`asyncio.create_task` orphans** — Python's GC can collect tasks with no held reference. `Orchestrator._bg_tasks` set holds refs. Pattern: `task = asyncio.create_task(...); s.add(task); task.add_done_callback(s.discard)`.
4. **PowerShell command injection** — `send_notification` PS fallback uses base64 `-EncodedCommand`. Never inline title/message into PS string.
5. **Sensitive paths blocked** — `read_file`/`list_directory`/`search_files` reject `.env`, `.ssh`, `.aws`, `*.pem`, `id_rsa`. `_safe_path()` enforces.
6. **Memory extraction is fire-and-forget** — runs in background task with own DB session. Errors swallowed (debug log only).
7. **Tool descriptions count toward TPM** — total tool defs ≈ 2900 tokens. Adding tool means budget for ~80 tokens of description max.
8. **`store_memory` tool returns marker, not result** — orchestrator intercepts `{"__store_memory__": true, ...}` JSON and calls `MemoryService.store` itself.
9. **Spotify needs Premium for owner** — Spotify Web API rejects all playback control if app owner on free tier. Friendly error already shown.
10. **Test-mode Google tokens expire weekly** — refresh fails after 7 days for unverified OAuth apps. Click Reconnect in IntegrationsPanel.
11. **Approval gate timeout = reject** — fail-closed. 60s timeout treated as deny.
12. **`agent.open_url` fires LIVE mid-turn** — orchestrator pushes via `_emit` callback, not deferred. Modal dismiss / Maps open is instant.
13. **Wake word and main voice session share mic** — `useWakeWord` auto-pauses when main session active to avoid `InvalidStateError`.
14. **Web Speech wake word sends audio to Google** — privacy tradeoff. openWakeWord backend swap is the upgrade path.
15. **`.env` parser takes LAST value on duplicate keys** — production block accidentally appended creates dup keys silently.
