# VERA — 2-Week POC Plan

Target: shippable proof-of-concept demo in 14 days. Working backend + frontend + voice + tools + memory + approval gates. NOT a feature dump — focus = "look how natural this is".

---

## Definition of Done (POC)

A 5-minute demo where you can:

1. Open VERA in browser → log in (or auto-resume from localStorage)
2. **Type chat** → VERA responds with markdown formatting
3. **Voice mode** → say "what's on my calendar today" → VERA acks ("Checking your calendar…") + speaks reply
4. **Wake word** → "Hey VERA, what's the weather in Stockholm" → triggers voice flow hands-free
5. **Tool use** → "send email to alice@example.com saying lunch at noon" → approval modal pops → click Allow → email sent
6. **Memory** → ask something past, VERA recalls a fact stored from earlier session
7. **Maps** → "directions to IKEA" → Google Maps opens new tab with route loaded
8. **PWA install** → click Install → VERA runs as standalone app
9. **End session** → state persists for next launch

---

## Critical path — Week 1

### Mon — unblock + verify

- [ ] **Fix `.env` duplicate keys** (5 min) — see "Critical .env bug" in COPILOT_HANDOFF.md
- [ ] Restart backend → confirm Gemini active in logs
- [ ] Run `cd client && npm run dev` → verify CORS allows localhost
- [ ] End-to-end test: text chat → tool call → reply

### Tue — pgvector for semantic memory

- [ ] Install pgvector extension (Docker easiest — `docs/MANUAL_SETUP.md` §7)
- [ ] Run `vera/Scripts/python -m backend.scripts.enable_pgvector`
- [ ] Verify in logs: `pgvector extension detected — semantic memory enabled`
- [ ] Test: store memory "I love sushi", later ask "what food do I like" → semantic match

### Wed — Mobile / PWA

- [ ] Open VERA on phone browser (same WiFi → use laptop's local IP)
- [ ] Verify PWA install button appears
- [ ] Install → confirm app icon on home screen
- [ ] Test voice + wake word from phone
- [ ] Note any layout breaks; fix

### Thu — Wake word polish

- [ ] Test default `hey vera` trigger phrase in noisy environment
- [ ] If false-positives → tighten `useWakeWord.ts` (substring → exact-with-pause)
- [ ] If miss-rate high → add aliases via `VITE_WAKE_PHRASES=hey vera,vera,computer`
- [ ] Document recommended phrases in MANUAL_SETUP

### Fri — Demo dry run + recording

- [ ] Walk through 5-min demo script (below)
- [ ] Loom / OBS recording
- [ ] Identify any awkward UX → punch list for week 2

---

## Critical path — Week 2

### Mon — Sign in with Google

Replace email-only login with real OAuth. Existing Google credentials already cover scopes.

- [ ] Backend `auth/login` accepts Google ID token, verifies with `google.oauth2.id_token`
- [ ] Frontend `LoginPage.tsx` adds "Sign in with Google" button (uses Google Identity Services script)
- [ ] On success: backend creates/looks up user by email, issues VERA session token same as before
- [ ] Keep old email-only login as fallback for dev
- [ ] Test full flow

### Tue — Error UX polish

- [ ] Gemini quota hit → show toast "AI quota exceeded — try in a minute" (currently shows raw error)
- [ ] Google OAuth expired → toast "Google session expired — reconnect" with link to settings
- [ ] WebSocket disconnect → auto-reconnect with backoff (currently dies until refresh)
- [ ] Mic permission denied → friendly inline message
- [ ] Spotify Premium required → already friendly via earlier fix

### Wed — Demo seed + docs

- [ ] `backend/scripts/demo_reset.py` → wipes user's chat + memories, inserts 3-5 seed memories ("user is named Erik", "lives in Stockholm", "morning standup at 9")
- [ ] `README.md` rewrite — 30-second pitch + screenshots + start command
- [ ] Demo script as separate `docs/DEMO_SCRIPT.md` with verbatim phrases to say

### Thu — Optional public deploy

- [ ] Backend → Render (free tier) or Railway
- [ ] Frontend → Vercel
- [ ] Tighten CORS to deploy domain
- [ ] HTTPS automatic on both
- [ ] Test from outside local network

### Fri — Demo day

- [ ] Final dry run
- [ ] Send Loom + URL to stakeholders
- [ ] Collect feedback → next sprint backlog

---

## Demo script (5 minutes)

**0:00 — Open VERA, log in**
> "This is VERA, a voice-first personal assistant. I haven't used it for a few hours so let me reopen it…"
> [VERA auto-resumes from localStorage]

**0:30 — Text chat with tool**
> Type: "what's the weather in Stockholm tomorrow"
> [VERA: "Checking the weather in Stockholm…" → table with forecast]

**1:00 — Voice mode**
> Click "Start voice"
> Say: "what's on my calendar today"
> [VERA acks aloud, then speaks 3 events]

**1:30 — Hands-free wake**
> Click "Hands-free"
> Say: "Hey VERA, send a quick reminder to Erik that lunch is at noon"
> [Approval modal pops with email summary]
> Click Allow
> [VERA speaks confirmation]

**2:30 — Memory**
> "Remember that I prefer espresso over filter coffee"
> [VERA confirms]
> Ask later: "what's my coffee preference"
> [VERA recalls — pulled from semantic memory]

**3:00 — Maps**
> "Open directions to the IKEA in Helsingborg"
> [New tab opens with Google Maps route]

**3:30 — Memory editing UI**
> Click cogwheel → Memories panel
> Show stored facts → delete one → confirm gone

**4:00 — Mobile / PWA**
> Switch to phone (mirrored)
> Open same URL → install prompt
> Install → home screen icon → launch full-screen app
> Use voice from phone

**4:30 — Architecture wrap**
> "Backend is FastAPI + Postgres. LLM is Gemini 2.5 Flash on free tier. 32 tools registered including Calendar, Gmail, Maps, Spotify, weather, news, currency, file system, clipboard. Destructive actions like sending email or deleting events go through an approval gate."

**5:00 — Done**

---

## What we're explicitly NOT building for POC

(All real features but scope-cut for the 2-week window. Move to v1.)

- Spotify playback control (needs Premium account)
- openWakeWord offline (Web Speech good enough)
- Native iOS/Android wrapper (PWA install covers it)
- Multi-user / household mode
- RAG knowledge base
- Vision / image upload
- Approval-gate UI for write_file / run_command tools (those tools don't exist yet)
- CI pipeline
- Wake-word truly background while screen off (browser limitation, native wrapper required)
- Field-level encryption for memories
- Token cost dashboard

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Gemini free quota exhausted mid-demo | Med | High | Switch to paid tier ($1-3/mo) before demo, OR keep Groq as fallback `LLM_PROVIDER` |
| Google test-mode token expires day-of | Med | High | Reconnect via UI button on demo morning |
| pgvector install fails on Windows | Med | Med | Skip — recency fallback works, just less impressive |
| Mic doesn't work in browser | Low | High | Test 30 min before demo; backup = text-only mode |
| Wake word false-triggers on TV | Med | Low | Demonstrate sensitivity slider as feature |
| Backend crash on stage | Low | High | Restart in 3 sec; mention "this is dev mode" |

---

## Daily checklist before demo

- [ ] Backend running, no errors in last 50 log lines
- [ ] Frontend running, TypeScript clean (`npx tsc --noEmit`)
- [ ] Postgres up, alembic at head
- [ ] Google OAuth valid (run `vera/Scripts/python -c "from backend.app.services.google_oauth import load_credentials; print(load_credentials().valid)"`)
- [ ] Gemini key works: send a test ping
- [ ] Test mic in browser
- [ ] Run `demo_reset.py` for clean state
