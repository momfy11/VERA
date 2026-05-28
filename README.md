# VERA — Voice-Enabled Reasoning Assistant

Voice-first personal assistant. 32 tools. Persistent memory across sessions.
Proactive suggestions. PWA installable. Approval gates for destructive actions.

Live: **https://46-62-225-46.sslip.io**

---

## Quick start (local dev)

```bash
git clone <repo> && cd VERA

# Backend deps
python -m venv vera && vera/Scripts/pip install -r backend/requirements.txt

# Postgres (pgvector) via Docker
docker compose up -d postgres

# Edit backend/.env — minimum: GEMINI_API_KEY (free at https://aistudio.google.com/app/apikey)
cp backend/.env.example backend/.env

# Bootstrap schema
vera/Scripts/python -m backend.init_db

# Backend
vera/Scripts/uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000

# Frontend (new terminal)
cd client && npm install --legacy-peer-deps && npm run dev
```

Open `http://localhost:5173`.

Full Docker (builds + runs everything):
```bash
docker compose up -d --build
```

---

## What VERA can do

| Group | Tools |
|---|---|
| Web | web_search, get_weather, wikipedia_summary, get_datetime, get_news |
| Memory | store_memory (auto + explicit), semantic recall via pgvector |
| Files | read_file, list_directory, search_files (sensitive-path blocklist) |
| System | send_notification, get_clipboard, set_clipboard |
| Calendar | get_agenda, find_event, create_event, delete_event |
| Gmail | list_emails, read_email, send_email, trash_email, mark_as_read |
| Spotify | play, pause, skip, now_playing, queue (Premium required) |
| Maps | open_url, maps_directions, maps_search, get_route, nearby_places |
| Utility | convert_currency |

Destructive tools (`send_email`, `delete_event`, `trash_email`) pause for user
approval via a 60-second timeout modal before executing. Users can opt out of
individual confirmations by telling VERA to skip them — stored as a memory.

---

## Architecture

| Layer | Stack |
|---|---|
| Frontend | React 18 + Vite, TypeScript, PWA (vite-plugin-pwa) |
| Voice | Web Speech API VAD + STT (en-US) + TTS (en-US), mic sensitivity slider, echo prevention |
| Image input | Camera capture button + Ctrl+V paste, resized to 1024 px, sent as base64 |
| Backend | FastAPI + uvicorn, WebSocket gateway (token-as-first-message auth) |
| LLM | Gemini 2.5 Flash via OpenAI-compatible endpoint — multimodal (text + images) |
| Memory | PostgreSQL + pgvector (cosine HNSW), fastembed BAAI/bge-small-en-v1.5, 384 dim |
| Auth | Email entry or Google Sign-in (reuses Calendar/Gmail OAuth scope) |
| Sessions | Token issued on login, 30-day expiry, invalidated on logout |
| Deploy | Docker Compose + Caddy reverse proxy (auto Let's Encrypt HTTPS) |

---

## Auth

Two flows:
- **Email** — enter any email address, no password, new user created on first login
- **Google** — OAuth consent screen, grants Calendar + Gmail + profile scopes in one step

First login shows a tips message. Session token stored in browser localStorage, expires after 30 days or on manual logout.

---

## Documentation

- [`docs/MANUAL_SETUP.md`](docs/MANUAL_SETUP.md) — env vars, API keys, OAuth, pgvector, cloud deploy
- [`docs/VERA_ROADMAP.md`](docs/VERA_ROADMAP.md) — phased feature roadmap
- [`docs/WEBSOCKET_API.md`](docs/WEBSOCKET_API.md) — WS event schema + REST routes
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — local dev notes
- [`docs/DEPLOY.md`](docs/DEPLOY.md) — production deploy (Hetzner + Docker + Caddy)

---

## Security

- Destructive tool calls gated behind approval modal with 60s auto-reject
- File-read tools blocklist `.env`, `.ssh`, `*.pem`, `id_rsa`, `credentials.json`
- Login endpoint rate-limited (10 req/min/IP)
- Session tokens expire after 30 days; logout invalidates immediately (`ended_at`)
- Audit log per tool call (name, args hash, latency, result length, error)
- CORS allowlist from env (not wildcard in prod)
- HSTS + X-Frame-Options + X-Content-Type-Options via Caddy in production

---

## Status

| Feature | State |
|---|---|
| 32 tools registered | ✅ |
| Gemini 2.5 Flash LLM + multimodal image input | ✅ |
| Cross-session memory (pgvector semantic search) | ✅ |
| Voice STT + TTS (en-US) | ✅ |
| PWA + service worker + install prompt | ✅ |
| Approval gates + email draft preview | ✅ |
| Memory editing UI | ✅ |
| Proactive suggestions | ✅ |
| Session expiry + logout | ✅ |
| First-login welcome | ✅ |
| Audit log | ✅ |
| Docker Compose (local + prod with Caddy) | ✅ |
| Google Sign-in | ✅ |
| Wake word ("Hey VERA") | ⏳ planned for native Android app |
| MCP integration | ⏳ post-POC |
| RAG knowledge base | ⏳ post-POC |
| Native mobile app | ⏳ planned |
