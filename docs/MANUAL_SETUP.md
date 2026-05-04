# VERA — Manual Setup Required

These items cannot be automated by code alone. Each one needs credentials,
third-party accounts, or system configuration that only you can provide.

All API tiers / URLs verified 2026-04 — check the linked docs for current limits.

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

## 11. Docker Compose (recommended)

A `docker-compose.yml` would let you start postgres + backend + frontend
with one command. Not yet created — example skeleton:

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_USER: vera
      POSTGRES_PASSWORD: vera
      POSTGRES_DB: vera
    volumes:
      - vera_pgdata:/var/lib/postgresql/data
    ports: ["5432:5432"]

  backend:
    build: ./backend
    env_file: ./backend/.env
    volumes:
      - ./backend:/app
    ports: ["8000:8000"]
    depends_on: [postgres]

  frontend:
    build: ./client
    ports: ["5173:5173"]
    volumes:
      - ./client:/app

volumes:
  vera_pgdata:
```

---

## 12. Alembic Migrations

Currently migrations are applied manually with raw SQL. Adopt Alembic:
```
./vera/Scripts/pip install alembic
cd backend
alembic init migrations
# Edit alembic.ini → set sqlalchemy.url to your db
# Edit migrations/env.py → import your Base.metadata
alembic revision --autogenerate -m "initial"
alembic upgrade head
```

---

## 13. CI Pipeline

No CI pipeline yet. A `.github/workflows/ci.yml` would run:
- `python -m py_compile` on all Python files
- `npx tsc --noEmit` on frontend
- `pytest` (once tests exist)

---

## 14b. Wake Word — Picovoice Porcupine ("Hey VERA")

Hands-free trigger. Runs in browser, low CPU, works in PWA install.

**Steps:**
1. Free signup at https://console.picovoice.ai (3 personal accounts allowed)
2. Copy your **AccessKey** from the console
3. Create `client/.env` if it doesn't exist, add:
   ```
   VITE_PICOVOICE_KEY=your_access_key_here
   VITE_WAKE_WORD=Jarvis
   ```
4. The English model file `porcupine_params.pv` is already in `client/public/` (downloaded during setup).
5. Restart frontend (`npm run dev`).
6. In VERA, click the **Hands-free** button in the top bar after starting a session.

**Built-in keywords (free):** Alexa, Americano, Blueberry, Bumblebee, Computer, Grapefruit, Grasshopper, Hey Google, Hey Siri, **Jarvis**, Okay Google, Picovoice, Porcupine, Terminator. Set `VITE_WAKE_WORD=` to one of these.

**Custom "Hey VERA" wake word (free for personal use):**
1. In Picovoice Console → **Porcupine** → train new keyword "Hey VERA"
2. Pick platform: **Web (WASM)**
3. Train (~15 sec) → download `.ppn` file
4. Place at `client/public/wake/hey-vera.ppn`
5. Set in `client/.env`:
   ```
   VITE_WAKE_WORD_PATH=/wake/hey-vera.ppn
   ```
6. Restart frontend.

**Troubleshooting:**
- "AccessKey rejected" → quota exhausted (3 accounts/personal). Make a new Picovoice account.
- Mic permission required — same prompt as STT.
- Won't run on http:// outside localhost — needs https or localhost (browser policy).

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

| # | Item | Effort | Required for |
|---|------|--------|--------------|
| 1 | VS Code interpreter | 2 min | IDE hints gone |
| 2 | Chrome/Edge for voice | 0 min | STT works |
| 3 | Google Calendar OAuth | 30 min | Calendar tool |
| 4 | Gmail OAuth | 20 min | Email tool |
| 5 | Spotify app | 10 min | Music control |
| 6 | NewsAPI key | 5 min | Live news |
| 7 | pgvector | 30 min (Docker) / 1 hr (manual) | Semantic memory |
| 8 | Production env vars | 10 min | Public deploy |
| 9 | Whisper local | 15 min | Offline STT |
| 10 | Notifications enabled | 1 min | Toast tool |
| 11 | Docker Compose | 1 hr | One-command startup |
| 12 | Alembic | 1 hr | Safe DB changes |
| 13 | CI pipeline | 2 hr | Automated checks |
| 14b | Picovoice wake word | 5-15 min | Hands-free "Hey VERA" |
| 14c | PWA install | 0 min | Add VERA to home screen |
| 15 | Home Assistant | 30 min | Smart home |
