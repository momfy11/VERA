# VERA — Handoff to Copilot

Pickup doc. MVP shipped. List below = what's done, what's pending, conventions.

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
| Spotify | spotify_play/pause/skip/now_playing/queue | `spotify_tools.py` |
| Maps | open_url, maps_directions, maps_search, get_route, nearby_places | `maps_tools.py` |
| Utility | convert_currency | `utility_tools.py` |

LLM providers: Gemini (default, 250K TPM free), Groq, Ollama, Mistral. Selected via `LLM_PROVIDER` env.

Other backend systems:
- WebSocket gateway (`api/ws.py`) — token-as-first-message auth, 60 msgs/min rate limit
- Orchestrator (`services/orchestrator.py`) — tool loop max 6 rounds, history compaction at 30 msgs, async background memory extraction with own DB session
- Memory service (`services/memory.py`) — pgvector semantic retrieval w/ recency fallback when extension missing
- Embeddings (`services/embeddings.py`) — fastembed default (BAAI/bge-small-en-v1.5, 384 dim)
- Google OAuth (`services/google_oauth.py`) — load+refresh, cached service builders
- Proactive scheduler (`services/scheduler.py`) — APScheduler hour-window rules

### Frontend

- LoginPage / MainPage routing in `App.tsx` based on `sessionStatus`
- Session token persisted to localStorage — refresh keeps user logged in
- Voice: `useVoiceSession.ts` (VAD + STT + mute flag) + `useTTS.ts` (markdown-stripped TTS)
- **Wake word**: `useWakeWord.ts` (Picovoice Porcupine) — hands-free trigger
- **PWA**: `vite-plugin-pwa` generates manifest + service worker, install prompt via `useInstallPrompt.ts`
- Pre-tool ack: `assistant.thinking` event speaks "Checking your inbox…" before tool execution
- Markdown chat rendering via `react-markdown` + `remark-gfm`
- Cogwheel drawer: IntegrationsPanel + SuggestionsPanel + SettingsPanel

### DB

PostgreSQL. Tables: users, sessions, session_events, agent_suggestions, agent_actions, memory_items, memory_feedback, audit_log, metrics_rollup, chat_messages.

Migrations in `backend/app/db/migrations/`: `0001_init.sql`, `0002_pgvector.sql`. No Alembic — applied manually or via `backend/scripts/enable_pgvector.py`.

---

## Pending work (priority order)

### P0 — fix before next polish pass

- [ ] **Spotify Premium gate** — only owner of Spotify Developer app w/ Premium can call playback API. Either:
  - User upgrades to Premium ($10/mo), OR
  - Drop Spotify tools from `TOOL_REGISTRY` to remove dead capability
- [ ] **OAuth app verification or extended test-mode** — current weekly token expiry annoying. Either:
  - Submit OAuth app for Google verification (slow, weeks), OR
  - Document weekly reconnect ritual (have UI button now in IntegrationsPanel)
- [ ] **`audit_log` table never written** — roadmap §1.1 says tool calls log there. Add insert in `Orchestrator._execute_tool` after each tool return. Capture: tool_name, args_hash (sha256, no raw args = privacy), result_len, latency_ms, user_id, session_id.

### P1 — UX gaps

- [ ] **Login with Google** replacing email-only login
  - Currently `LoginPage.tsx` takes email + display name only, no real auth
  - Backend `auth/login` issues a session_token from email — no password, no verification
  - Refactor: use Google OAuth as primary auth. User signs in with Google → backend gets profile email → creates/looks up VERA user → issues session_token. Same Google creds also unlock Calendar+Gmail (one OAuth, two purposes).
  - Files to touch: `LoginPage.tsx`, `api/routes/auth.py`, possibly new `services/google_login.py`
- [ ] **Approval gates UI for destructive tools** (`send_email`, `delete_event`, `trash_email`, `set_clipboard`)
  - Currently relies on system prompt instructing VERA to confirm verbally
  - Real impl: when LLM emits one of these tool calls, instead of executing, send `agent.action_pending` WS event to client → client shows approve/reject modal → response sent back as next WS message → orchestrator either executes or returns "User declined"
  - Use existing `agent_actions` table (already has `requires_approval`, `approval_status` columns)
- [ ] **Persist `sessionToken` to localStorage** — `App.tsx` only keeps it in React state. Closing browser forces re-login. Read on mount, write after login, clear on End Session.
- [ ] **Memory editing UI** — let user view + delete stored memories. Currently invisible to user. Add to IntegrationsPanel or SettingsPanel: list memories grouped by kind, delete button per row, hits new `DELETE /api/memories/{id}` endpoint that calls `MemoryService.deactivate`.

### P2 — Performance / hardening

- [ ] **Rate limit `/api/auth/login`** — currently unlimited, brute force possible. Add per-IP sliding window (5/min).
- [ ] **CORS from env** — `main.py` has `allow_origins=["*"]` hardcoded. Read `settings.allowed_origins` (already in env) and split on comma.
- [ ] **Log rotation** — `logs/frontend.log` grows unbounded. Replace `FileHandler(mode='a')` with `RotatingFileHandler(maxBytes=10MB, backupCount=5)` in `observability/logger.py`.
- [ ] **Connection pool leak** — `tools.py` line 38 creates `httpx.AsyncClient` at import time, never closed. Move to lazy singleton, close on FastAPI lifespan shutdown.
- [ ] **DDG Instant Answer fallback dead code** — `web_search` tries package first now, fallback to instant API rarely fires. Either remove or keep as belt-and-braces.
- [ ] **STT lang hardcoded** — `useVoiceSession.ts` line 166: `recognition.lang = "en-US"`. Make configurable via user_settings table + WS message on session start.
- [ ] **Embedding background backfill** — when user adds pgvector after using VERA for a while, existing memories have no embeddings. `backend/scripts/enable_pgvector.py` does this once. Consider running on scheduler nightly to catch missed embeddings.

### P3 — Roadmap features (not yet started)

See `docs/VERA_ROADMAP.md` for full roadmap. Highlights of what's not started:

- Phase 2.5 Code execution sandbox (`run_python` tool)
- Phase 3.5 Spotify (blocked on Premium issue above) + YouTube
- Phase 4.3 Vision (image upload, screenshot analysis)
- Phase 5.2 RAG knowledge base (engineering domain knowledge)
- Phase 6 Predictive suggestions, multi-agent
- Phase 7 Smart home, mobile app, wearables

---

## Conventions Copilot must follow

### Code style

- Python: type hints everywhere, `from __future__ import annotations` at top of every file
- Async: use `asyncio.get_running_loop()`, NOT deprecated `get_event_loop()`
- DB: SQLAlchemy ORM, never raw SQL except for pgvector cosine ops (use `text()`)
- React: functional components, hooks. No class components.
- TypeScript strict. Never use `any` without comment justifying why.
- Imports: standard lib → third-party → local backend → local frontend, blank line between groups.

### Tools (when adding new ones)

1. Async function in appropriate `services/<group>_tools.py` file
2. Returns string (human-readable success or error). NEVER raises — catch all exceptions and return error message.
3. Long-running ops (HTTP, file I/O on slow disks, subprocesses): wrap in `asyncio.get_running_loop().run_in_executor()` or use `httpx.AsyncClient`
4. Add JSON schema definition to `TOOL_DEFINITIONS` list in `tools.py`
5. Add function to `TOOL_REGISTRY` dict in `tools.py`
6. Tool descriptions = ≤120 chars (token budget). System prompt + 26 tools must stay ≤4000 tokens.
7. For destructive tools: add to system prompt's "Important behavior" block telling VERA to confirm before calling

### Memory extraction prompt is sensitive

`backend/app/services/orchestrator.py` `_EXTRACTION_SYSTEM` constant. Hand-tuned to avoid garbage facts. Don't rewrite without testing 20+ exchanges first. Anti-patterns currently blocked: assistant-about-self facts, technical setup mentions, transient one-off requests.

### LLM model selection

Gemini is default (`gemini-2.5-flash`). 250K TPM, free, native tool calling. If switching for testing:
- Groq: 8K TPM only, fast. Use `openai/gpt-oss-20b` for reliability with tool calling.
- Ollama: local, slow on laptop but private. Use `qwen2.5:7b` for tool calling.

### Migration discipline

No Alembic yet. Adding a column means:
1. Write SQL in new `migrations/000N_<name>.sql`
2. Update `models.py` mapper
3. Document apply step in MANUAL_SETUP.md
4. Idempotent (`CREATE ... IF NOT EXISTS`, `ALTER ... IF NOT EXISTS`)

### Frontend layout

- TypeScript compile must stay clean (`npx tsc --noEmit`)
- All API calls go through `lib/api.ts`
- New panels go in `components/`, added to `MainPage.tsx` settings drawer
- Styling in `styles.css` (no CSS modules, no Tailwind)
- Use existing CSS variables: `--accent`, `--accent-2`, `--good`, `--warn`, `--info`, `--muted`, `--ink`, `--bg`, `--panel`

---

## How to test

### Backend smoke test

```bash
cd c:/Users/momfy/repos/VERA
vera/Scripts/python -c "
from backend.app.services.tools import TOOL_REGISTRY
from backend.app.services.orchestrator import Orchestrator
print('Tools:', len(TOOL_REGISTRY))
"
```

Expected: `Tools: 26`

### Tool unit test (non-destructive subset)

See test loop in conversation history (test all 14 read-only tools). Add `backend/tests/test_tools.py` proper pytest later.

### Frontend type check

```bash
cd client && npx tsc --noEmit
```

Expected: no output, exit 0.

### End-to-end

1. Start postgres
2. `uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000`
3. `cd client && npm run dev`
4. Browser to http://localhost:5173
5. Login with email
6. Click cogwheel → Integrations → Connect Google
7. Test in chat: "what's on my calendar today", "show me unread emails", "weather in stockholm"

---

## Critical environment

- `backend/.env` — has all keys (Gemini, Groq, Mistral, NewsAPI, Spotify). DO NOT commit. Already in `.gitignore`.
- `backend/credentials/client_secret_*.json` — Google OAuth client secret (Desktop app type)
- `backend/credentials/token.json` — created by OAuth flow, refreshed automatically
- `backend/credentials/spotify_token.json` — created by spotipy on first call
- `logs/backend.log` — backend log, mode='w' resets on restart
- `logs/frontend.log` — frontend log, mode='a' grows forever (P2 fix above)

---

## Files map

```
backend/
  app/
    api/
      routes/
        auth.py           — POST /api/auth/login (email-only)
        google.py         — GET/POST /api/google/{status,connect,disconnect}
        health.py         — GET /api/health, POST /api/log
        suggestions.py    — GET /api/suggestions, PATCH /api/suggestions/{id}
      ws.py               — WebSocket gateway (token-as-first-msg)
      connection_manager.py
    core/config.py        — pydantic Settings
    db/
      models.py           — SQLAlchemy ORM
      session.py          — get_db, SessionLocal
      migrations/         — raw SQL files
    services/
      orchestrator.py     — main brain, tool loop, memory extract bg task
      llm.py              — LLMClient ABC + Mistral/Groq/OpenAICompatible (Gemini/Ollama)
      memory.py           — MemoryService, semantic retrieval w/ recency fallback
      embeddings.py       — fastembed/ollama/openai providers
      tools.py            — 26 tool definitions + registry
      calendar_tools.py
      gmail_tools.py
      spotify_tools.py
      news_tools.py
      google_oauth.py     — shared Google auth + service builders
      scheduler.py        — APScheduler proactive rules
    main.py               — FastAPI app + WS bypass middleware
  scripts/
    google_authorize.py   — one-time Google OAuth (CLI fallback)
    enable_pgvector.py    — apply 0002_pgvector + backfill embeddings

client/src/
  components/
    LoginPage.tsx
    MainPage.tsx
    IntegrationsPanel.tsx
    SettingsPanel.tsx
    SuggestionsPanel.tsx
    StatusPill.tsx
    ChatPanel.tsx (legacy)
    VoicePanel.tsx (legacy)
    SessionPanel.tsx (legacy)
  lib/
    api.ts                — REST wrappers
    ws.ts                 — createSessionSocket
    types.ts
    logger.ts             — console.error/warn → POST /api/log
    useVoiceSession.ts    — VAD + STT + setMuted
    useTTS.ts             — Web Speech API + markdown stripping
    stripMarkdown.ts
  App.tsx
  styles.css

docs/
  VERA_ROADMAP.md         — phased plan
  MANUAL_SETUP.md         — credentials & manual config steps
  CODE_REVIEW.md          — security audit findings
  WEBSOCKET_API.md        — WS message taxonomy
  DEVELOPMENT.md          — local dev setup
  VERA_requests.md        — VERA's own wishlist (raw)
  COPILOT_HANDOFF.md      — this file
```

---

## WebSocket event types (push from server)

| Type | Payload | Purpose |
|---|---|---|
| `server.hello` | `{ts, display_name}` | Auth success, sent after client.hello |
| `server.error` | `{message}` | Auth/rate/etc errors |
| `assistant.text` | `{text}` | Final assistant reply |
| `assistant.tts_cancel` | `{ts}` | Tell client to stop speaking (barge-in) |
| `agent.suggestion` | `{id, type, priority, title, reason, ts, status}` | Proactive suggestion |
| `agent.open_url` | `{url, label}` | Tool wants browser to open URL (Maps, websites) |

To add a new server-push event type:
1. Append to orchestrator's `_pending_events` from inside `_execute_tool`
2. Frontend handler in `App.tsx` `socket.onmessage`

---

## Hot gotchas (read before touching)

1. **WebSocket routing in FastAPI silently fails** — that's why `main.py` has `_NoCORSForWebSocket` middleware that dispatches WS scope directly to `websocket_endpoint`. Don't try to "fix" by switching to `@app.websocket()` — it doesn't work.

2. **React StrictMode double-mounts effects** — `App.tsx` `useEffect` cleanup sets `intentionalCloseRef.current = true` to survive the cleanup-then-rerun cycle. Don't remove that ref.

3. **`asyncio.create_task` orphans** — Python's GC can collect tasks with no held reference. `Orchestrator._bg_tasks` set holds refs. Pattern: `task = asyncio.create_task(...); s.add(task); task.add_done_callback(s.discard)`.

4. **PowerShell command injection** — `send_notification` PS fallback uses base64 `-EncodedCommand`. Never inline title/message into PS command string.

5. **Sensitive paths blocked** — `read_file`/`list_directory`/`search_files` reject `.env`, `.ssh`, `.aws`, `*.pem`, `id_rsa`, etc. via `_is_sensitive()`. If adding new file tools, call `_safe_path()` first.

6. **Memory extraction is fire-and-forget** — runs in background task with own DB session. Errors swallowed (debug log only). Don't make orchestrator wait on it.

7. **Tool descriptions count toward TPM** — total tool defs ≈ 2200 tokens currently. Adding a tool means budget for ~80 tokens of description max. Keep terse.

8. **`store_memory` tool returns marker, not result** — orchestrator intercepts the JSON marker `{"__store_memory__": true, ...}` and calls `MemoryService.store` itself. LLM never touches DB directly.

9. **Spotify needs Premium for owner** — Spotify Web API rejects all playback control if app owner is on free tier. Not a code bug.

10. **Test-mode Google tokens expire weekly** — refresh token only valid 7 days for unverified OAuth apps. Click Reconnect in IntegrationsPanel when stale.
