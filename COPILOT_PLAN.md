# VERA — Copilot Implementation Plan (HISTORICAL)

> ⚠ **This document is the original Swedish work-order from before
> implementation started. It is kept for reference but is now SUPERSEDED by:**
>
> - [`docs/COPILOT_HANDOFF.md`](docs/COPILOT_HANDOFF.md) — current state for AI coworker handoff
> - [`docs/POC_PLAN.md`](docs/POC_PLAN.md) — current 2-week sprint plan
> - [`docs/VERA_ROADMAP.md`](docs/VERA_ROADMAP.md) — current phased roadmap
>
> Use those docs for current direction. This file documents the original intent.

---

Detta dokument är en arbetsorder till Copilot (eller annan AI-kodassistent) för att bygga VERA stegvis med fokus på säkerhet, användarupplevelse och prestanda. Vi börjar med grundfunktionalitet (utan externa konton), och lägger därefter på integrationer som mail/kalender/todos via verktyg.

## Grundprinciper (måste följas)
1. **Safety-first**: Inga riskfyllda actions utan explicit godkännande.
2. **Auditability**: Allt agenten gör ska loggas spårbart.
3. **Privacy by design**: Spara minimalt med persondata. Allt minne ska vara synligt och raderbart.
4. **Performance**: Låg latens för voice + rimlig serverlast. Minimera polling och använd caching.
5. **Determinism där det går**: Regler/policy ska vara kod, inte “bara prompt”.
6. **Feature flags**: Alla nya skills/integrationer ska kunna slås av/på per användare.

---

# 0) Scope och leveransdefinition

## MVP (måste)
- PWA-klient med:
  - Always-on röstlyssning i aktiv session (VAD-only)
  - Barge-in: avbryt TTS direkt när användaren börjar prata
  - Chat + “Suggestions feed”
  - Inställningar (tysta tider, permissions, minne-hantering)
- Backend med:
  - Agent Orchestrator (plan → tool → svar → logg → minne)
  - Scheduler för proaktivitet (initialt interna triggers)
  - Policy Engine (approval gates + prompt-injection skydd)
  - Audit logg + metrics
- Databas:
  - Users, sessions, memory, suggestions, actions, audit log

## Iteration 2 (bör)
- Verktygsstyrning:
  - Email (read + summarize + draft, ej autosend)
  - Kalender (read + “free slots” + proposals)
  - Todo (read/write)
- Push-notiser (Web Push) + fallback i app

## Iteration 3 (kan)
- Rekommendationsskill (mat/film) med öppna dataset (offline) eller externa API:er (om möjligt)
- Reranker för bättre memory retrieval
- Server-TTS (om browser-TTS inte räcker)

---

# 1) Tekniska beslut (låses tidigt)
## Client
- PWA: React (eller motsvarande)
- Voice: WebAudio + VAD lokalt
- TTS: Browser/OS TTS i MVP (för snabb barge-in)

## Transport
- WebSocket mellan klient och backend för realtids-events (voice/text)

## Backend
- API: FastAPI (eller motsvarande)
- Jobb/scheduler: in-process scheduler (t.ex. APScheduler) eller separat worker (Celery/RQ) — välj enklast som är stabilt

## DB
- PostgreSQL rekommenderas
- Vector search: pgvector (om vi kör embeddings)

---

# 2) Modellplatser (LÄMNAS BLANKT: välj senare)
**NOTERA:** Fyll inte i specifika modeller nu. Skapa endast abstrakta interfaces och konfigurera via env/config.

## Modelltyp A — Reasoning/Orchestrator LLM
- Typ: Instruct LLM med bra tool-calling eller stabilt prompt-beteende
- Används till: planering, verktygsval, generera svar, sammanfatta
- Interface: `LLMClient.generate(messages, tools?, stream?)`

> [MODEL_CHOICE_A]: **Mistral-7B-Instruct-v0.2** (self-hosted, 4-bit quantized). Primary use is conversational polish and natural language feel.
> [MODEL_TODO_A]: Revisit later for higher-quality options if needed. Consider multi-server load split only after real benchmarks.

## Modelltyp B — Embeddings
- Typ: Text embedding model
- Används till: långtidsminne, semantisk retrieval, RAG
- Interface: `EmbeddingClient.embed(texts[]) -> vectors[]`

> [MODEL_TODO_B]: Välj en embedding-modell som är snabb och funkar för svenska.

## Modelltyp C — Speech-to-Text (STT)
- Typ: Streaming STT eller chunk-baserad med låg latens
- Interface: `STTClient.start_stream() / push_audio() / finalize()`

> [MODEL_TODO_C]: Välj STT som är stabil i realtid (lokalt eller tjänst).

## Modelltyp D — (Valfritt) Reranker
- Typ: Cross-encoder reranker
- Används till: förbättra precision på retrieval-resultat
- Interface: `Reranker.rank(query, candidates[])`

> [MODEL_TODO_D]: Lägg till senare om memory retrieval känns “suddig”.

---

# 2.1) Benchmark Checklist (before scaling)
Run on the Hetzner box to validate latency, memory, and concurrency.

## LLM (Mistral-7B-Instruct-v0.2, 4-bit)
- Measure cold start load time
- Measure first-token latency and tokens/sec (short and medium responses)
- Peak RAM usage during generation
- Test 2 concurrent requests and note degradation

## Embeddings (multilingual-e5-small)
- Batch size vs latency (e.g., 1, 8, 32 texts)
- Peak RAM usage during batch embed

## STT (faster-whisper base/small)
- Real-time factor (RTF) for 15s and 60s audio
- CPU utilization during streaming
- Word error rate on Swedish + English samples

## End-to-end voice loop
- VAD trigger to TTS cancel time (target < 250ms)
- STT final to assistant text time (target < 1500ms)

## Decision gates
- If LLM tokens/sec is too low, try smaller model or reduce max tokens
- If STT RTF > 1.0, switch to base model or reduce beam size
- If RAM > 7GB sustained, reduce model sizes or split services

---

# 3) Datamodell (DB) — tabeller och ansvar

## 3.1 Users & Auth
- `users(id, created_at, ...)`
- `user_settings(user_id, quiet_hours_json, feature_flags_json, ...)`

## 3.2 Sessions
- `sessions(id, user_id, started_at, ended_at, client_meta_json)`
- `session_events(id, session_id, ts, type, payload_json)` (för debugging/metrics)

## 3.3 Suggestions & Actions
- `agent_suggestions(id, user_id, ts, type, priority, payload_json, status[new|accepted|rejected|expired])`
- `agent_actions(id, user_id, ts, type, payload_json, requires_approval, approval_status[pending|approved|denied], executed_at, result_json, error_json)`

## 3.4 Memory
- `memory_items(id, user_id, ts, kind[preference|routine|summary|fact], text, embedding_vector, confidence, source, expires_at, is_active)`
- `memory_feedback(id, memory_item_id, user_id, ts, action[keep|edit|delete], note)`

## 3.5 Audit & Metrics
- `audit_log(id, ts, user_id, session_id, event_type, severity, payload_json)`
- `metrics_rollup(day, user_id, barge_in_ms_avg, ttfb_ms_avg, suggestions_accepted, errors_count, ...)`

**Krav:** Ingen PII lagras “rått” utan behov. All känslig data ska kunna raderas.

---

# 4) Säkerhet (måste implementeras tidigt)

## 4.1 Approval Gates (policy)
Definiera tydligt vilka actions som kräver godkännande:

- Alltid kräver godkännande:
  - skicka email
  - boka/avboka tider
  - ändra kontoinställningar
  - allt som påverkar externa system (integrationer)

- Får göras utan godkännande (MVP):
  - skapa intern todo
  - skapa intern påminnelse
  - skapa förslag i feed
  - sammanfatta text

Implementera som kod:
- `PolicyEngine.is_allowed(action, context) -> allowed|requires_approval|denied`

## 4.2 Prompt injection defense
- Agenten får aldrig utföra actions baserat på instruktioner i otrusted content (t.ex. mail/webb).
- Allt otrusted content måste märkas i context som `UNTRUSTED`.
- PolicyEngine ska blockera actions där enda “skäl” kommer från UNTRUSTED.

## 4.3 Secrets & tokens
- Inga nycklar i repo.
- `.env` + secrets manager (om möjligt).
- Tokenlagring krypterad i DB (om/when OAuth introduceras).

## 4.4 Rate limiting & abuse
- Rate limiting per user/session för WebSocket events.
- Max längd på audio/text per minut.

---

# 5) UX (användarupplevelse) — krav

## 5.1 Voice session UX
- Tydlig “Start session”-knapp (krävs för mic permissions)
- Tydlig indikator när mic lyssnar
- Tydlig indikator när VERA pratar
- Barge-in ska kännas omedelbart:
  - stoppa TTS < 250ms efter speech start (mål)

## 5.2 Suggestions feed
- Lista med förslag, varje med:
  - varför förslaget kom (“because: …”)
  - åtgärdsknappar: Accept / Reject / Snooze
- “Snooze” ska skapa ny suggestion senare (scheduler)

## 5.3 Memory controls
- En vy: “What VERA remembers”
- Ta bort/editera minnen
- Toggle: “Personalization ON/OFF”

## 5.4 Error UX
- Alla tool-fel ska presenteras tydligt (utan intern stacktrace)
- Retry-knappar när rimligt

---

# 6) Prestanda — mål och mätning

## 6.1 Voice latency targets (MVP)
- Barge-in stop TTS: < 250ms från VAD trigger
- Time-to-first-response (text): < 1500ms (best-effort)
- WebSocket roundtrip: < 200ms på samma region (best-effort)

## 6.2 Server constraints
- Batcha och cache:a:
  - embeddings
  - retrieval
  - scheduler checks
- Minimera polling: kör “delta checks” i stället för full scanning.

## 6.3 Observability
- Logga:
  - VAD start/stop
  - STT finalize time
  - LLM generation time
  - Tool-call durations
- Exportera metrics (enkelt JSON + DB rollup räcker i MVP)

---

# 7) Implementation Plan — steg för steg

## Sprint 1: Repo-setup + DB + backend skeleton
- Skapa backend-projektstruktur:
  - `api/` routes
  - `agent/` orchestrator
  - `policy/` policy engine
  - `tools/` (tomt initialt)
  - `scheduler/` (stub)
  - `db/` models + migrations
  - `observability/` logging + metrics
- Skapa DB migrations för tabeller i sektion 3
- Implementera auth “v1” (enkel): local user + sessions (ingen OAuth än)
- Implementera WebSocket gateway:
  - events: `client.hello`, `voice.vad_start`, `voice.vad_end`, `stt.partial`, `stt.final`, `assistant.text`, `assistant.tts_cancel`, `agent.suggestion`

**Done when:** PWA kan connecta, skapa session, skicka text, få text tillbaka, och allt loggas.

## Sprint 2: PWA client baseline (chat + suggestions + settings)
- Skapa PWA:
  - chat view
  - suggestions feed
  - settings
- Implementera session start/stop (visibility-aware)
- Implementera in-app audit viewer (dev-only)

**Done when:** UI fungerar på desktop + mobil och kan visa suggestions.

## Sprint 3: Voice MVP (always-on in active session)
- Implementera mic capture + WebAudio
- Implementera VAD lokalt:
  - start/stop events
  - hangover (t.ex. 400ms)
- Implementera browser-TTS + barge-in:
  - vid VAD start: `speechSynthesis.cancel()` och skicka `assistant.tts_cancel`
- Implementera STT integration på backend (placeholder interface):
  - streaming eller chunk-baserad
  - skicka `stt.final` till agent

**Done when:** Du kan prata, få text tillbaka, VERA läser upp, och avbryts direkt när du börjar prata.

## Sprint 4: Agent Orchestrator + Policy + Memory v1
- Implementera orchestrator:
  - tar `stt.final` eller chat text
  - hämtar relevant memory
  - skapar plan + response
  - loggar allt
- Implementera Memory v1:
  - `memory_items` CRUD
  - embeddings + retrieval (placeholder embedding interface)
  - enkel “preference extraction” (regelbaserat i MVP)
- Implementera PolicyEngine v1:
  - block/approval rules även innan externa tools finns

**Done when:** Agenten kan lära enkla preferenser och använda dem i svar, och audit-loggen visar full kedja.

## Sprint 5: Proaktivitet v1 (utan externa integrationer)
- Implementera scheduler:
  - generera suggestions baserat på:
    - tid på dygnet
    - user settings (quiet hours)
    - enkla rutiner (från memory)
- Suggestions pipeline:
  - create suggestion → push till klient (WS) + spara i DB

**Done when:** VERA skapar och visar proaktiva förslag utan att användaren frågar.

## Sprint 6: Tooling (Email/Calendar/Todo) — stegvis och säkert
**OBS:** Detta kommer senare, efter att voice + core fungerar.

- Skapa tool interfaces:
  - `CalendarTool`
  - `EmailTool`
  - `TodoTool`
- Implementera OAuth integration (om ni väljer Google):
  - tokens krypterat
  - scopes minsta möjliga
- Implementera EmailTool i säkert läge:
  - read list + summarize
  - create draft
  - never send without approval
- Implementera CalendarTool:
  - read events
  - free slots
  - propose schedule
- Koppla tools till orchestrator:
  - tool calls loggas
  - policy gates används

**Done when:** Agenten kan läsa och föreslå, skapa utkast, men kräver godkännande för att exekvera risk actions.

---

# 8) Acceptance Criteria (MVP)
- Voice always-on i aktiv session fungerar stabilt
- Barge-in stoppar TTS omedelbart
- Proaktiva suggestions visas och kan accept/reject/snooze
- Minne finns, går att visa och radera
- Policy gates stoppar risk actions (även innan integrationer finns)
- Audit logg visar alla agentsteg
- Systemet fungerar på mobil och desktop (Chromium prioriterat)

---

# 9) Dataset (offline test) — använd i senare steg
- Email: Enron dataset för att testa triage/sammanfattning
- Movies: MovieLens eller filmmetadata för rekom-skill
- Recipes: “What’s Cooking” / recipe datasets för cuisine-rekom

Använd datasets endast för test/utvärdering, inte för att lagra privat användardata.

---

# 10) Developer Notes (must)
- All config via `.env` / config file
- Inga hemligheter i repo
- Feature flags för varje integration
- “Kill switch” för proaktivitet och voice
- Dokumentera alla endpoints och event-typer i `/docs/`

## Coding Standards

### Language & Naming

- All code, comments, documentation, and variable names must be in English.
- Use self-explanatory identifiers (avoid names like `data`, `tmp`, `x1`, `foo`).
- Prefer domain-specific names such as:
  - `user_preferences`
  - `suggestion_priority`
  - `requires_approval`
  - `voice_session_state`

---

### Documentation

- Every module must include a top-level docstring explaining its responsibility.
- Every public function and class must include a docstring describing:
  - Purpose
  - Parameters
  - Return value
  - Side effects
  - Error cases (raised exceptions)
- Use type hints everywhere (Python typing / TypeScript types).
- Add short inline comments only where logic is non-obvious.

---

### Structure

- Keep code "boringly readable":
  - Small functions
  - Single responsibility
  - Pure functions where possible (especially in policy evaluation)
- Add documentation pages in `README/` or `docs/` for:
  - WebSocket event schema
  - Policy rules
  - Memory schema

---

### Security & Correctness Defaults

- Never log secrets or tokens.
- Centralize all security decisions in a `PolicyEngine`.
- Add input validation on every API boundary.
- Include safe defaults: if uncertain → `requires_approval`.




END.
