---
name: VERA project current state
description: Sprint 4 implementation status — what's done, what's stub, what's next
type: project
---

VERA is a JARVIS-style always-on personal AI assistant (voice + proactive suggestions). Sprint 1-3 infra was done; Sprint 4 brain is now implemented.

**Why:** User wants a JARVIS-like assistant that can do anything — remember preferences, respond through voice, act proactively.

**How to apply:** When suggesting next steps, prioritize the Sprint 4→5→6 sequence. Don't suggest rearchitecting what's already done.

## What's live (as of 2026-04-05)

### Backend
- FastAPI + WebSocket gateway (`/ws`) — auth via token in query param
- PostgreSQL schema: users, sessions, memory_items, audit_log, suggestions, actions, metrics
- LLM layer: abstract `LLMClient` → `MistralClient` + `GroqClient` (swap via `LLM_PROVIDER` env)
- Real `Orchestrator`: memory retrieval → system prompt → LLM call → rolling history (20 turns)
- `MemoryService`: DB-backed store/retrieve, confidence-ranked, expiry-aware
- Rule-based memory extraction from user messages (preference triggers)
- Global exception handlers, correct `datetime.now(timezone.utc)` throughout
- DB indexes on all hot query paths

### Frontend (PWA)
- React + Vite, all panels wired: Chat, Voice (VAD), Session, Suggestions (mock), Settings (mock)
- WebSocket client with proper cleanup
- VAD hook with barge-in (stops TTS on voice detect)
- Chat auto-scroll + Enter key support
- API/WS base URLs read from `VITE_API_BASE` / `VITE_WS_BASE` env vars

## Still stubs / not done
- STT pipeline: VAD fires but audio never transcribed — no `STTClient` yet
- `SuggestionsPanel`: shows mock data, not connected to DB
- `SettingsPanel`: toggles not persisted
- `Scheduler` (Sprint 5): empty `tick()` — no proactive suggestions generated yet
- Email / Calendar / Todo tools (Sprint 6)
- pgvector + embedding model for semantic memory search
- OAuth for external integrations
- Rate limiting middleware

## LLM config
- Provider: Mistral (free tier) — set `MISTRAL_API_KEY` in `.env`
- Alt: Groq (`LLM_PROVIDER=groq`, `GROQ_API_KEY=...`) — faster, better for voice latency
- Model configurable via `LLM_MODEL` env var

## Key files
- `backend/app/services/llm.py` — LLM abstraction + provider impls
- `backend/app/services/orchestrator.py` — VERA brain
- `backend/app/services/memory.py` — memory CRUD
- `backend/app/api/ws.py` — WebSocket gateway
- `backend/app/core/config.py` — all settings
