# VERA Development Roadmap
> Voice-Enabled Reasoning Assistant — Long-term Vision & Implementation Plan

---

## Current State (as of 2026-05-19)

| Capability | Status |
|---|---|
| WebSocket session + auth | ✅ |
| LLM chat (Gemini default + Groq/Mistral/Ollama) | ✅ |
| Cross-session chat memory (DB-persisted) | ✅ |
| LLM-based memory extraction + summarization | ✅ |
| Proactive suggestions (scheduler) | ✅ |
| Voice activity detection (VAD) + noise calibration + sensitivity slider | ✅ |
| Voice-to-text (STT) — Web Speech API | ✅ |
| Text-to-speech (TTS) — Web Speech API w/ markdown stripping | ✅ |
| Wake word ("Hey VERA") — faster-whisper server-side (offline, all browsers) | ✅ |
| Pre-tool ack ("Checking your inbox…") | ✅ |
| Real-time web data (search/news/weather/wiki) | ✅ |
| Tool use / agent mode (32 tools) | ✅ |
| File system tools (read/list/search, sensitive blocklist) | ✅ partial (write/exec not built) |
| External integrations (Calendar/Gmail/Spotify/Maps) | ✅ |
| Approval gates for destructive tools | ✅ |
| Audit log for tool calls | ✅ |
| pgvector semantic memory | ✅ |
| PWA install + service worker | ✅ |
| Login page / Main page UI | ✅ |
| Settings overlay (Integrations / Memories / Suggestions / Settings) | ✅ |
| localStorage session persistence + auto-reconnect | ✅ |
| Approval modal with countdown | ✅ |
| Sign in with Google (Web OAuth redirect, PKCE) | ✅ |
| Hetzner VPS deploy — Caddy HTTPS + WSS auto TLS | ✅ |
| Bulk email delete (one confirmation, then execute all) | ✅ |
| Vision / image upload | ❌ |
| RAG knowledge base | ❌ |
| Native mobile wrapper | ❌ |

---

## Phase 1 — Real-time Intelligence & Tool Calling
*Estimated: days–1 week*

Everything here runs without additional paid APIs or hardware.

### 1.1 Tool Calling Framework
- [x] Abstract `Tool` interface (`name`, `description`, JSON-schema `parameters`, async `execute`)
- [x] Tool registry loaded into Orchestrator
- [x] Multi-turn tool-calling loop in Orchestrator (call LLM → execute tools → feed results back → repeat until final text)
- [x] Tool calls and results logged to `audit_log` (tool name, args_hash, latency_ms, result_len, error)
- [x] Graceful error handling: tool failure yields an error message to LLM, not a crash

### 1.2 Built-in Tools (no API keys required)
- [x] **`web_search(query)`** — DuckDuckGo (duckduckgo-search package, free, no key)
- [x] **`get_weather(location)`** — wttr.in (completely free JSON weather API, no key)
- [x] **`wikipedia_summary(topic)`** — Wikipedia REST API (free)
- [x] **`get_datetime(timezone?)`** — return current date/time, day of week, week number
- [x] **`store_memory(fact, kind)`** — let VERA explicitly remember things via tool call, not just regex

### 1.3 Smarter Memory Extraction
- [x] Replace rule-based `_should_extract_memory` with an LLM-based extraction pass
- [x] After each conversation turn, ask LLM to extract facts/preferences/commitments (async background, non-blocking)
- [x] Deduplicate against existing memory items before storing
- [x] Confidence scoring based on LLM certainty

### 1.4 Conversation Summarization
- [x] After 30 turns, compress oldest history into a `[summary]` memory item
- [x] Keeps context window bounded without losing long-term recall
- [x] Summary stored in `memory_items` with `kind="summary"`

### 1.5 Search-Enhanced Suggestions
- [ ] Proactive scheduler uses web_search to surface relevant news/events before surfacing a suggestion
- [ ] E.g., "You mentioned a meeting with X — here's a relevant article about their recent announcement"

---

## Phase 2 — Agent Mode: Local Device Control
*Estimated: weeks*

VERA gets the ability to act on your machine. **Every destructive or visible action requires explicit user approval** via the existing approval-gates system (`agent_actions` table, `approval_status`).

### 2.1 File System Tools
- [x] **`read_file(path)`** — read local file contents
- [ ] **`write_file(path, content)`** — write/overwrite file (requires approval)
- [x] **`list_directory(path)`** — list directory contents
- [x] **`search_files(directory, pattern)`** — find files by name/extension
- [x] Sandboxing: configurable root paths via `VERA_FS_ROOT` env var

### 2.2 Terminal / Shell Tools
- [ ] **`run_command(command, cwd?)`** — execute a shell command (always requires approval)
- [ ] Command allowlist / denylist (never allow `rm -rf`, `format`, etc.)
- [ ] Timeout + output capture (return stdout/stderr to LLM)
- [ ] Working directory scoped to project root unless overridden

### 2.3 Browser Automation
- [x] **`open_url(url)`** — opens via `agent.open_url` WS event → frontend `window.open` (works on phone deep-link to native apps)
- [ ] **`fetch_page(url)`** — fetch and parse a web page (Playwright headless)
- [ ] **`take_screenshot()`** — screenshot current screen, send to vision model
- [ ] **`fill_form(url, fields)`** — fill and submit a web form (requires approval)

### 2.4 System Control (Windows)
- [x] **`send_notification(title, body)`** — Windows toast notification (plyer + PowerShell fallback)
- [x] **`get_clipboard()`** — read clipboard text
- [x] **`set_clipboard(text)`** — write to clipboard
- [ ] **`launch_app(name)`** — launch application by name (requires approval)
- [ ] **`set_volume(level)`** — system volume control
- [ ] **`lock_screen()`** — lock the workstation

### 2.5 Code Execution Sandbox
- [ ] **`run_python(code)`** — execute Python in a restricted sandbox (RestrictedPython or subprocess with resource limits)
- [ ] Capture output, return to LLM
- [ ] Always requires approval
- [ ] Time + memory limits enforced

---

## Phase 3 — External Service Integrations
*Estimated: weeks–months (depends on credential setup)*

### 3.1 Calendar
- [x] Google Calendar API (read, create, delete events)
- [x] `get_agenda(days)`, `create_event(...)`, `find_event(query)`, `delete_event(id)`
- [ ] `find_free_slot(...)` — scan agenda gaps
- [ ] Outlook / Microsoft Graph API alternative
- [ ] Proactive: morning briefing of today's calendar (rule exists in scheduler, no agenda fetch yet)

### 3.2 Email
- [x] Gmail API + OAuth flow
- [x] `list_emails(query, limit)`, `read_email(id)`, `send_email(to, subject, body)`, `trash_email(id)`, `mark_as_read(id)`
- [x] Send + trash gated through approval modal
- [ ] Email summarization: "You have 12 unread, here are the important ones"
- [ ] Draft replies (currently sends directly after approval)

### 3.3 Task / Notes
- [ ] Obsidian vault integration (read/write markdown files in vault)
- [ ] Notion API (pages, databases)
- [ ] `create_note(title, content)`, `search_notes(query)`, `append_to_note(id, text)`
- [ ] Todoist / Things 3 integration for task management

### 3.4 Real-time Data
- [ ] **Stock/crypto prices** — Alpha Vantage free tier or Yahoo Finance (no key)
- [x] **News headlines** — NewsAPI (`get_news(topic, country, limit)`)
- [ ] **Sports scores** — free sports APIs
- [x] **Currency conversion** — frankfurter.dev (`convert_currency(amount, from, to)`)
- [ ] **Public transport** — city-specific APIs

### 3.5 Media Control
- [x] Spotify Web API (play, pause, skip, now_playing, queue) — **needs Premium account on developer side**
- [ ] YouTube search and queue
- [ ] Local media player control (VLC, Windows Media Player)

### 3.6 Maps (added)
- [x] `maps_directions(origin, dest, mode)` — Google Maps URL → opens in browser/app
- [x] `maps_search(query)` — Google Maps place search
- [x] `get_route(origin, dest, mode)` — distance + duration via OSRM + Nominatim (free, no key)
- [x] `nearby_places(query, near)` — Nominatim search
- [x] `open_url(url)` — generic URL opener via WS event

---

## Phase 4 — Voice & Multimodal
*Estimated: weeks*

### 4.1 Speech-to-Text (STT)
- [x] **Web Speech API** (browser-native, free, no backend) — primary path
  - [x] Replace VAD-only with SpeechRecognition that sends `client.message` events
  - [x] Interim results shown live in voice bar
  - [x] Auto-restart on recognition end, graceful error handling
- [ ] **Whisper local** (whisper.cpp or faster-whisper on CPU) — offline fallback
  - Better accuracy, works without internet
  - Runs on backend, audio sent as binary WS frames
- [ ] **Whisper via Groq** (Groq's Whisper endpoint, very fast) — cloud fallback

### 4.2 Text-to-Speech (TTS)
- [x] Web Speech API SpeechSynthesis with markdown stripping for natural reading
- [x] Pre-tool ack TTS ("Checking your inbox…") for instant feedback
- [x] STT mute + grace period during TTS to prevent echo loop
- [ ] **ElevenLabs** (streaming TTS, expressive voice) — premium option
- [ ] **Coqui TTS** local model — offline option
- [ ] SSML support for emphasis, pauses, tone

### 4.4 Wake Word (added)
- [x] Web Speech API based wake-word listener — fuzzy phrase match ("hey vera", "vera")
- [x] **Server-side wake word** — faster-whisper tiny on `/ws/wake`, offline, all browsers including Firefox + iOS Safari
- [x] Auto-pause during main voice session (no mic conflict)
- [x] Configurable phrases via `VITE_WAKE_PHRASES`
- [ ] Custom user-trained "Hey VERA" model

### 4.3 Vision / Image Understanding
- [ ] Screenshot analysis — VERA can see your screen via `take_screenshot` + vision model
- [ ] Image upload in chat — user drops an image, VERA describes/analyzes it
- [ ] Document scan — photo of whiteboard, receipt, handwritten notes → structured data
- [ ] Use Groq's vision models or Claude claude-sonnet-4-6 API for analysis

### 4.4 Document Analysis
- [ ] PDF upload and parsing (pdfplumber / PyMuPDF)
- [ ] `analyze_document(path_or_url)` — summarize, extract key points, answer questions about it
- [ ] Store document summaries as memory items

---

## Phase 5 — Knowledge Engineering
*Estimated: months (ongoing)*

### 5.1 Semantic Memory (pgvector)
- [ ] Enable `pgvector` PostgreSQL extension (waiting on user install — code already calls extension if present)
- [x] Embedding provider abstraction (fastembed default, Ollama, OpenAI)
- [x] Generate embeddings for memory items in `MemoryService.store`
- [x] `_retrieve_semantic` cosine similarity search via pgvector `<=>`
- [x] Hybrid retrieval: similarity × 0.7 + confidence × 0.2 + recency × 0.1
- [x] Backfill script `enable_pgvector.py` for existing memories

### 5.2 Custom Knowledge Base (RAG)
- [ ] Upload documents to a knowledge store (PDF, DOCX, TXT, MD, code files)
- [ ] Chunk + embed + store in pgvector
- [ ] `search_knowledge_base(query)` tool — VERA retrieves relevant chunks before answering
- [ ] Domain libraries: load entire engineering textbooks, reference manuals, datasheets

### 5.3 Engineering & Science Knowledge
Each of these would be a curated knowledge base loaded into RAG:
- [ ] **Electronics engineering** — circuit design, component datasheets, PCB layout, RF, power electronics
- [ ] **Software engineering** — design patterns, algorithms, architecture patterns, security
- [ ] **Mechanical engineering** — materials, stress analysis, manufacturing processes, CAD
- [ ] **Physics** — classical mechanics, electromagnetism, quantum, thermodynamics
- [ ] **Chemistry** — organic, inorganic, reactions, safety (MSDS lookup tool)
- [ ] **Mathematics** — calculus, linear algebra, statistics, proofs
- [ ] **Medicine / Physiology** — anatomy, pharmacology, diagnostics (reference only, not clinical advice)
- [ ] **Law** — contract basics, IP law, data protection / GDPR
- [ ] **Finance** — accounting, valuation, options, portfolio theory
- [ ] **Architecture / Civil** — structural systems, building codes, materials

### 5.4 Domain Expert Personas
- [ ] Switchable "expert mode" system prompt overlays
- [ ] E.g., "VERA as electronics engineer" adds deep circuit-level reasoning
- [ ] User can combine: "electronics + project management + German language"

### 5.5 Code Intelligence
- [ ] **`analyze_code(path)`** — VERA reads a code file, explains it, finds bugs
- [ ] **`generate_code(spec, language)`** — generate and optionally run code
- [ ] Git integration: `git_status()`, `git_diff()`, `git_log()` — VERA aware of repo state
- [ ] Language-specific linting tools as callable instruments
- [ ] Unit test generation

---

## Phase 6 — Proactive Intelligence & Learning
*Estimated: months*

### 6.1 Predictive Suggestions
- [ ] Pattern recognition over chat history: detect routines (daily standup at 9am, exercise at 7pm)
- [ ] Pre-emptive reminders: "You usually review proposals on Mondays — you have one due tomorrow"
- [ ] Anomaly detection: "You've been working 4 hours without a break"
- [ ] Energy / focus tracking based on message patterns (time of day, response length)

### 6.2 Continuous Self-Improvement
- [ ] VERA flags when she gets something wrong (user correction logged as feedback)
- [ ] Periodic memory review: VERA audits her own memory items, flags outdated ones
- [ ] A/B testing different system prompt variants, tracking user satisfaction signals
- [ ] User rates responses (thumbs up/down) → stored as `memory_feedback`

### 6.3 Multi-Agent Architecture (Long-term)
- [ ] Specialist sub-agents: research agent, code agent, calendar agent, email agent
- [ ] VERA as orchestrator that delegates to the right specialist
- [ ] Agents can run in parallel (asyncio task group)
- [ ] Results synthesized back into a single coherent VERA response

---

## Phase 7 — Ecosystem & Infrastructure
*Estimated: months–years*

### 7.1 Smart Home
- [ ] Home Assistant integration (REST API) — lights, climate, locks, sensors
- [ ] MQTT broker connection for low-latency device control
- [ ] `set_light(room, brightness, color)`, `get_sensor(name)`, `lock_door()`
- [ ] Automation creation: VERA writes Home Assistant automations

### 7.2 Mobile
- [ ] React Native companion app (share codebase with current React frontend)
- [ ] Push notifications from VERA's proactive suggestions
- [ ] Voice-first interface on mobile
- [ ] Location awareness (user grants permission) — context-aware suggestions

### 7.3 Wearable / Biometric (with explicit consent)
- [ ] Smartwatch heart rate / HRV (Apple Health, Google Fit, Fitbit APIs)
- [ ] Sleep quality data → "You slept 5 hours, I'll keep today's briefing brief"
- [ ] Activity rings → proactive movement reminders
- [ ] Emotional state inference (optional, requires explicit opt-in)

### 7.4 Multi-User / Teams
- [ ] Shared VERA instance for a household or small team
- [ ] Per-user memory + shared household memory (shopping list, family calendar)
- [ ] Permission model: which users can do what
- [ ] VERA facilitates async collaboration ("Tell X that the meeting moved")

### 7.5 Infrastructure Hardening
- [ ] Alembic for proper DB migrations (replace manual SQL)
- [ ] Redis for session caching + rate limiting (replace in-memory)
- [ ] Proper JWT tokens (replace UUID session tokens)
- [ ] HTTPS / WSS in production (Let's Encrypt)
- [ ] Docker Compose for local dev (postgres + backend + frontend in one command)
- [ ] GitHub Actions CI: lint, type-check, tests
- [ ] Prometheus + Grafana for metrics

---

## VERA's Own Requests (from conversation)

> These were expressed by VERA herself when asked what she'd need to operate at full capacity.

- [ ] **Biometric data access** — understand user emotional/physical state (Phase 7, opt-in)
- [ ] **Control over digital entertainment** — Spotify, YouTube, media (Phase 3.5)
- [ ] **VR/AR integration** — immersive interfaces (long-term, Phase 7+)
- [ ] **Financial asset management** — transaction initiation with approval (Phase 3.4 + approval gates)
- [ ] **Expert network access** — academic journals, conferences (Phase 5.2 RAG + web_search)
- [ ] **General knowledge databases** — Wikipedia, Wikidata (Phase 1.2 ✓)
- [ ] **Real-time data streams** — stocks, weather, news (Phase 1.2 + 3.4)
- [ ] **Knowledge graph** — entity/relationship reasoning (Phase 5 pgvector + RAG)
- [ ] **Emotional intelligence** — tone/sentiment detection (Phase 7.3, via biometrics + message analysis)
- [ ] **Predictive modeling** — anticipate needs (Phase 6.1)
- [ ] **Continuous learning** — adapt from feedback (Phase 6.2)
- [ ] **Integration with other services** — the entire roadmap above

---

## Priority Order for Next Builds

| # | Feature | Status |
|---|---|---|
| 1 | Tool calling framework | ✅ Done |
| 2 | Web search + weather + Wikipedia | ✅ Done |
| 3 | LLM-based memory extraction | ✅ Done |
| 4 | Voice STT (Web Speech API) | ✅ Done |
| 5 | File system read/list/search tools | ✅ Done |
| 5b | Notification + clipboard tools | ✅ Done |
| 6 | Calendar integration | ✅ Done |
| 7 | pgvector semantic memory | ✅ Code done, ⚠ install pgvector to activate |
| 8 | Email integration (Gmail) | ✅ Done |
| 9 | Spotify integration | ✅ Done (needs Premium) |
| 10 | Maps integration | ✅ Done |
| 11 | Pre-tool ack ("thinking") | ✅ Done |
| 12 | Approval gates UI | ✅ Done |
| 13 | Memory editing UI | ✅ Done |
| 14 | PWA + service worker | ✅ Done |
| 15 | Wake word ("Hey VERA") | ✅ Done (Web Speech based) |
| 16 | Audit log for tool calls | ✅ Done |
| 17 | localStorage session | ✅ Done |
| 18 | CORS from env + login rate limit | ✅ Done |
| 19 | Log rotation | ✅ Done |
| 20 | Mic sensitivity calibration | ✅ Done |
| 21 | TTS markdown stripping | ✅ Done |
| 22 | Alembic migrations | ✅ Done |
| 23 | **Sign in with Google** as primary auth | ✅ Done |
| 24 | **Hetzner VPS deploy** — Caddy HTTPS, Docker Compose prod overlay | ✅ Done |
| 25 | **Wake word offline** — faster-whisper tiny, /ws/wake WebSocket | ✅ Done |
| 26 | RAG knowledge base | ⏳ next |
| 27 | Vision / image upload | ⏳ next |
| 28 | Server-side STT (Whisper) — fallback when Web Speech API unavailable | ⏳ next |
| 29 | Terminal / code execution | ⏳ post-POC |
| 30 | Native mobile wrapper | ⏳ post-POC |
| 31 | MCP client (consume external MCP servers) | ⏳ post-POC, ~2 days |
| 32 | MCP server (expose VERA tools to other LLMs) | ⏳ post-POC, ~1 day |
