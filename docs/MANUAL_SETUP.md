# VERA — Manual Setup Required

These items cannot be automated by code alone. Each one needs credentials,
third-party accounts, or system configuration that only you can provide.

All API tiers / URLs verified 2026-05 — check the linked docs for current limits.

> **⚠ .env duplicate-keys warning**: pydantic's dotenv parser silently uses the LAST occurrence on duplicate keys. If you append a "production" block to your existing `.env` you'll override `LLM_PROVIDER`, `ALLOWED_ORIGINS`, etc. — frontend will lose CORS, Gemini will revert to whatever's in the second block. Edit, don't append.

---

## 0. LLM Provider Choice — pick one

VERA supports four providers (set `LLM_PROVIDER` in `backend/.env`):

| Provider | Free TPM | Speed | Tool calling | Setup |
|---|---|---|---|---|
| `gemini` ⭐ | **250,000 TPM** | Fast | Native, reliable | Get a free key from https://aistudio.google.com/app/apikey |
| `groq` | 8,000 TPM | Fastest | OK with gpt-oss-* | Existing setup |
| `ollama` | unlimited (local) | Slow on laptop | Model-dependent | Install Ollama, `ollama pull qwen2.5:7b` |
| `mistral` | low | Fast | OK | https://console.mistral.ai |

**Recommended: `gemini`** — 30× more headroom than Groq, free, native tool calling.

**To switch to Gemini:**
1. Get a free API key: https://aistudio.google.com/app/apikey (sign in with any Google account, click "Create API key")
2. Add to `backend/.env`:
   ```
   LLM_PROVIDER=gemini
   LLM_MODEL=gemini-2.5-flash
   GEMINI_API_KEY=your_key_here
   ```
3. Restart the backend

**To switch to Ollama (local):**
1. Install Ollama: https://ollama.com (download for Windows)
2. `ollama pull qwen2.5:7b` (or `llama3.1:8b`)
3. Add to `backend/.env`:
   ```
   LLM_PROVIDER=ollama
   LLM_MODEL=qwen2.5:7b
   OLLAMA_URL=http://localhost:11434
   ```

---

## 1. VS Code Python Interpreter (do this first — 2 min)

VS Code is pointing at the global Python instead of the VERA venv, which is
why `requirements.txt` shows bogus "not installed" hints.

**Fix:**
1. Press `Ctrl+Shift+P` → "Python: Select Interpreter"
2. Pick `./vera/Scripts/python.exe` (the venv, not system Python)

---

## 2. Web Speech API — Browser Compatibility

Voice STT only works in **Chrome, Edge, and Opera**. Firefox does not implement
`SpeechRecognition`. Safari has partial support behind a flag and only on macOS/iOS.

**What you need to know:**
- Use Chrome or Edge when testing voice features
- The voice bar will silently skip STT setup if the API is unavailable (VAD still works)
- Recognition is sent to **Google's servers** (Chrome/Edge) — not local. For privacy use Whisper (item 11)

**To change language:**
Edit `client/src/lib/useVoiceSession.ts` line 166:
```typescript
recognition.lang = "sv-SE";  // BCP-47 locale
```
Common: `en-US`, `en-GB`, `sv-SE`, `de-DE`, `fr-FR`, `es-ES`, `ja-JP`, `zh-CN`.

---

## 3. Google Calendar Integration

Requires a Google Cloud project + OAuth 2.0 (Desktop app type).

**Steps:**
1. Go to https://console.cloud.google.com
2. Create a project → APIs & Services → Library → enable **Google Calendar API**
3. APIs & Services → Credentials → Create Credentials → **OAuth client ID** → Desktop application
4. Download `credentials.json` → place in `backend/credentials/`
5. Add OAuth consent screen (External, test users = your email)
6. First run will open a browser to authorize, save `token.json`
7. Add to `backend/.env`:
   ```
   GOOGLE_CREDENTIALS_FILE=backend/credentials/credentials.json
   GOOGLE_TOKEN_FILE=backend/credentials/token.json
   ```
8. Install:
   ```
   ./vera/Scripts/pip install google-api-python-client google-auth-oauthlib google-auth-httplib2
   ```

**Required scopes:**
- `https://www.googleapis.com/auth/calendar.readonly` — read events
- `https://www.googleapis.com/auth/calendar.events` — create/modify events

Then `get_agenda`, `create_event`, `find_free_slot` tools can be added.

---

## 4. Gmail Integration

Same Google Cloud project as Calendar. Add Gmail API + the new scopes.

**Steps:**
1. Same project → Library → enable **Gmail API**
2. Add scopes during the OAuth consent setup:
   - Read-only: `https://www.googleapis.com/auth/gmail.readonly`
   - Send: `https://www.googleapis.com/auth/gmail.send`
   - Modify: `https://www.googleapis.com/auth/gmail.modify` (mark read, archive)
3. Delete `token.json` and re-authorize so the new scopes are granted
4. Same package install as Calendar (no extra packages needed)

---

## 5. Spotify Integration

**Steps:**
1. Go to https://developer.spotify.com/dashboard → Create app
2. **Redirect URI must be `http://127.0.0.1:8888/callback`** — Spotify rejects `localhost` since 2022
3. Save the Client ID and Client Secret
4. Add to `backend/.env`:
   ```
   SPOTIFY_CLIENT_ID=your_client_id
   SPOTIFY_CLIENT_SECRET=your_client_secret
   SPOTIFY_REDIRECT_URI=http://127.0.0.1:8888/callback
   ```
5. Install: `./vera/Scripts/pip install spotipy`

**Required scopes:**
`user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private`

---

## 6. NewsAPI

**Free tier limits (verified 2026-04):**
- 100 requests/day, **development use only** (i.e. not on a public website)
- For production use, paid plan needed

**Steps:**
1. Register at https://newsapi.org/register
2. Get your API key from the dashboard
3. Add to `backend/.env`:
   ```
   d=your_key_here
   ```

**Free alternatives:**
- [GNews](https://gnews.io) — 100 req/day free, no signup for basic
- RSS feeds — direct parsing of Reuters/BBC/Al Jazeera via `feedparser` Python package
- [Currents API](https://currentsapi.services) — 600 req/day free

---

## 7. pgvector — Semantic Memory Search

Required for Phase 5 (embedding-based retrieval instead of recency).

**On Windows — easiest path is Docker:**
```yaml
# Replace the postgres service in docker-compose.yml with:
postgres:
  image: pgvector/pgvector:pg16
  environment:
    POSTGRES_USER: vera
    POSTGRES_PASSWORD: vera
    POSTGRES_DB: vera
  ports:
    - "5432:5432"
```

**Or install on existing Windows postgres:**
1. Stop your local postgres service
2. Download the precompiled DLL from https://github.com/pgvector/pgvector-windows/releases
3. Copy `vector.dll` → `C:\Program Files\PostgreSQL\16\lib\`
4. Copy `vector.control` and `vector--*.sql` → `C:\Program Files\PostgreSQL\16\share\extension\`
5. Restart postgres
6. In psql:
   ```sql
   CREATE EXTENSION vector;
   ```

**After installing pgvector**, run the migration + backfill script:
```
vera/Scripts/python -m backend.scripts.enable_pgvector
```
This applies `0002_pgvector.sql`, generates embeddings for any existing
memories, and is safe to re-run (idempotent).

**Embedding provider — set in `backend/.env`:**

| Provider | Dim | Setup | Default? |
|---|---|---|---|
| `fastembed` | 384 | nothing — auto-installs ONNX model on first use (~33 MB) | ✅ default |
| `ollama` | 768 | install [Ollama](https://ollama.com), then `ollama pull nomic-embed-text` | |
| `openai` | 1536 | needs `OPENAI_API_KEY` in `.env`, ~$0.02 per 1M tokens | |

To switch provider, set in `backend/.env`:
```
EMBEDDING_PROVIDER=fastembed       # or ollama / openai
EMBEDDING_MODEL=BAAI/bge-small-en-v1.5
EMBEDDING_DIM=384                   # MUST match the vector(N) column in DB
```

If you change `EMBEDDING_DIM`, edit `backend/app/db/migrations/0002_pgvector.sql`
to match the new dim, drop the column, and re-run the migration script.

**Graceful fallback:** if pgvector isn't installed OR the embedder can't
initialize, VERA logs a warning and falls back to recency-based memory
ranking. Nothing crashes — semantic search just stays off until both pieces
are in place.

---

## 8. Production Environment Variables

For production (exposed to network), `backend/.env` must include:
```
# Tighten origins
ALLOWED_ORIGINS=https://your-domain.com
ENVIRONMENT=production

# Generate with: python -c "import secrets; print(secrets.token_hex(32))"
SECRET_KEY=<generated-hex>

# Where VERA can browse files (default: home dir)
VERA_FS_ROOT=C:\Users\momfy

# Optional: override LLM
LLM_PROVIDER=groq
LLM_MODEL=llama-3.3-70b-versatile
```

Also see [CODE_REVIEW.md](CODE_REVIEW.md) for production-readiness gaps
(no JWT, no HTTPS config, no auth on `/api/log` token, etc.).

---

## 9. Whisper Local STT (offline + privacy)

Web Speech API sends your voice to Google. Whisper runs entirely locally.

**Steps:**
1. Install: `./vera/Scripts/pip install faster-whisper`
2. First run downloads the model on demand
3. Add to `backend/.env`:
   ```
   WHISPER_MODEL=base   # tiny | base | small | medium | large-v3
   ```

**Model sizes (verified):**
| Model | Size | RAM | Speed | Accuracy |
|-------|------|-----|-------|----------|
| tiny | 39 MB | ~1 GB | very fast | OK |
| base | 74 MB | ~1 GB | fast | good |
| small | 244 MB | ~2 GB | medium | very good |
| medium | 769 MB | ~5 GB | slow | excellent |
| large-v3 | 1550 MB | ~10 GB | very slow | best |

For voice on a typical laptop: **`base` is the sweet spot.**

Implementation: backend route at `/api/transcribe` accepting audio binary, call from frontend instead of Web Speech API.

---

## 10. Windows Toast Notifications

`plyer` is installed and works. If notifications never appear:

**Manual fix path:**
1. `./vera/Scripts/pip install windows-toasts` (newer than win10toast)
2. The PowerShell fallback in `tools.py` should always work as a backstop

**Permission requirement:**
Notifications must be enabled in Windows Settings → System → Notifications → "Get notifications from apps and other senders" must be ON.

---

## 11. Docker Compose — see §11 (below summary table) for current recipe.

Old skeleton replaced by working files in repo root. Skip to §11 below.

---

## 12. Alembic Migrations ✅ adopted

Already wired. Initial revision `47ef6902e297` is current head.

**To add a new migration after editing models:**
```bash
cd backend
../vera/Scripts/alembic revision --autogenerate -m "add new column"
# Review generated file in migrations/versions/ — Alembic mis-generates type changes sometimes
../vera/Scripts/alembic upgrade head
```

**Common issue: `InsufficientPrivilege` on ALTER TABLE.** Tables created by manual SQL or `init_db.py` are owned by `postgres`. `vera` role can't ALTER them. Fix once:
```bash
"/c/Program Files/PostgreSQL/16/bin/psql.exe" -U postgres -d vera
```
```sql
DO $$ DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
    EXECUTE 'ALTER TABLE public.'||quote_ident(r.tablename)||' OWNER TO vera';
  END LOOP;
END $$;
```

---

## 13. CI Pipeline

No CI pipeline yet. A `.github/workflows/ci.yml` would run:
- `python -m py_compile` on all Python files
- `npx tsc --noEmit` on frontend
- `pytest` (once tests exist)

---

## 11. Docker Compose ✅ ready

Three services in `docker-compose.yml`:
- `postgres` — pgvector/pgvector:pg16 on host port 5433
- `backend` — uvicorn FastAPI
- `frontend` — Vite build → nginx serve

**Local dev (whole stack via Docker):**
```bash
docker compose up -d
# frontend at http://localhost:5173
# backend at http://localhost:8000
# postgres at localhost:5433
```

**Hybrid (Postgres in Docker, backend+frontend on host):**
```bash
docker compose up -d postgres
# Update backend/.env: DATABASE_URL=postgresql+psycopg2://vera:vera@localhost:5433/vera
cd backend && ../vera/Scripts/uvicorn backend.app.main:app --reload
cd client && npm run dev
```

---

## 11b. Cloud Deploy (production)

Caddy in front for HTTPS + WSS via auto Let's Encrypt. Stack: `docker-compose.yml` + `docker-compose.prod.yml` overlay.

**On a fresh Linux VM (Hetzner / DigitalOcean / Hetzner / Fly machine):**

```bash
# 1. Install Docker
curl -fsSL https://get.docker.com | sh

# 2. Clone + configure
git clone https://github.com/<you>/VERA.git
cd VERA
cp .env.production.example .env
# Edit .env — set DOMAIN, PUBLIC_API_BASE, PUBLIC_WS_BASE, ALLOWED_ORIGINS

cp backend/.env.example backend/.env  # if you have one, else hand-write
# Add: GEMINI_API_KEY, NEWS_API_KEY, SPOTIFY_*, GOOGLE_CREDENTIALS_FILE, etc.

# 3. DNS — point DOMAIN A record at this server's public IP

# 4. Boot
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 5. Watch Caddy issue cert (first 30s)
docker compose logs -f caddy
```

After Caddy reports `certificate obtained successfully`, browse to `https://$DOMAIN`.

**Required ports open on the VM:** 80 (Caddy HTTP→HTTPS redirect + ACME), 443 (HTTPS+WSS).

**Things to swap before exposing publicly:**
- `POSTGRES_PASSWORD` from default `vera` → strong random
- Backend `SECRET_KEY` → fresh `python -c "import secrets; print(secrets.token_hex(32))"`
- `VERA_FS_ROOT=/app/sandbox` (NOT `C:\` or `/`) so the file-read tools can't traverse the host
- Spotify redirect URI in Spotify Developer Console + `.env` → `https://$DOMAIN/spotify/callback` (when wired)
- Google OAuth: add `https://$DOMAIN` to authorized redirect URIs if switching to Web app type

**Backups** — `pg_dump` cron, see §11c below.

---

## 11c. Postgres backups (production)

```bash
# Daily dump @ 03:00, keep 7 days
crontab -e
# 0 3 * * * docker exec vera-postgres-1 pg_dump -U vera vera | gzip > /backups/vera-$(date +\%F).sql.gz
# 0 4 * * * find /backups -name 'vera-*.sql.gz' -mtime +7 -delete
```

Restore:
```bash
gunzip -c vera-YYYY-MM-DD.sql.gz | docker exec -i vera-postgres-1 psql -U vera vera
```

---

## 14b. Wake Word — Web Speech API (free, no signup)

Hands-free "Hey VERA" trigger. Uses browser Web Speech API as always-on
detector. No API key, no model file, no signup.

**Steps:**
1. Optional: create `client/.env` to override default trigger phrases:
   ```
   VITE_WAKE_PHRASES=hey vera,vera,computer
   ```
   Defaults to `hey vera,vera`. Comma-separated, lowercase, fuzzy match.
2. Restart frontend (`npm run dev`).
3. In VERA, click **Hands-free** button in top bar (appears after session start).

**How it works:**
- Parallel `SpeechRecognition` runs continuously alongside main voice session
- Transcribes everything; when transcript contains a trigger phrase, fires `start()` on main voice session
- Wake listener pauses while main session active (avoid mic conflict)

**Tradeoffs:**
- ✅ Free, no signup, works in Chrome/Edge today
- ❌ Audio sent to Google Speech servers (cloud, not local)
- ❌ Internet required
- ❌ Chrome/Edge only (Firefox lacks SpeechRecognition)

**Privacy upgrade path (future):** swap `useWakeWord.ts` for an offline ML
wake-word model. Best free option: [openWakeWord](https://github.com/dscripka/openWakeWord)
(Apache 2.0, has pretrained "hey jarvis"). Requires writing audio
preprocessing + ONNX runtime in browser, OR streaming mic to backend Python
which runs the model. Not turnkey — medium effort.

**Picovoice Porcupine note:** their console (console.picovoice.ai) now blocks
personal Gmail signups, requires company email. We removed the integration.

---

## 14c. PWA Install — Add VERA to Home Screen

After running frontend with `npm run dev` or `npm run build && npm run preview`:

**Desktop Chrome/Edge:**
- Click the **Install** button in VERA's top bar (appears after first session)
- Or click the install icon in the browser address bar

**Android Chrome:**
- Open VERA in Chrome
- Menu → "Add to Home screen" or "Install app"
- Once installed, opens like native app, shows up in app drawer

**iOS Safari:**
- Open VERA in Safari (not Chrome — iOS Chrome can't install PWAs)
- Share button → "Add to Home Screen"

Once installed: launches in its own window, no browser UI, runs faster, gets app icon. Wake word + voice work the same.

**For background mic on iOS/Android:** browsers will suspend the mic when app isn't focused. True always-on requires either:
- Native iOS/Android wrapper (Capacitor/React Native shell)
- Keeping the PWA in foreground with screen-on
- Picovoice's iOS/Android native SDK

Document this as a future native-wrapper item if always-listening-while-screen-locked is needed.

---

## 15. Smart Home — Home Assistant

If you run [Home Assistant](https://www.home-assistant.io):
1. Settings → Profile → Long-Lived Access Tokens → create one
2. Add to `backend/.env`:
   ```
   HOMEASSISTANT_URL=http://192.168.1.x:8123
   HOMEASSISTANT_TOKEN=eyJ...
   ```
3. REST API docs: https://developers.home-assistant.io/docs/api/rest/

---

## Summary Table

| # | Item | Effort | Required for | Status |
|---|------|--------|--------------|--------|
| 0 | LLM provider key (Gemini default) | 5 min | All AI features | needed |
| 1 | VS Code interpreter | 2 min | IDE hints gone | one-click |
| 2 | Chrome/Edge for voice | 0 min | STT works | use right browser |
| 3 | Google Calendar OAuth | 30 min | Calendar tool | ✅ done |
| 4 | Gmail OAuth (same flow) | 0 min | Email tool | ✅ done |
| 5 | Spotify app | 10 min | Music control | done; needs Premium |
| 6 | NewsAPI key | 5 min | Live news | ✅ done |
| 7 | pgvector | 5 min via Docker | Semantic memory | ✅ done (container on 5433) |
| 8 | Production env vars | 10 min | Public deploy | template `.env.production.example` |
| 9 | Whisper local | 15 min | Offline STT | optional |
| 10 | Notifications enabled | 1 min | Toast tool | done if Win toggle on |
| 11 | Docker Compose (full stack) | ✅ ready | One-command startup | `docker compose up -d` |
| 11b | Cloud deploy (Caddy + HTTPS) | 30 min on fresh VM | Public-internet access | ✅ ready, follow §11b |
| 11c | Postgres backups | 5 min cron | Disaster recovery | template provided |
| 12 | Alembic | 0 min | Safe DB changes | ✅ done |
| 13 | CI pipeline | 2 hr | Automated checks | not done |
| 14b | Wake word (Web Speech) | 0 min | Hands-free "Hey VERA" | ✅ done, no signup |
| 14c | PWA install | 0 min | Add VERA to home screen | click Install in app |
| 15 | Home Assistant | 30 min | Smart home | optional |
