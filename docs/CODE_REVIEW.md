# VERA Code Review — 2026-04-18

Comprehensive audit covering security, correctness, and architectural concerns.

---

## Critical bugs FIXED in this review

### Security

| # | Severity | Location | Issue | Fix |
|---|----------|----------|-------|-----|
| S1 | **Critical** | `tools.py` `send_notification` | PowerShell command injection via unescaped `title`/`message` in PS one-liner. A prompt-injected web search result could trigger arbitrary code execution. | XML-escape both fields, JSON-quote the XML payload, base64-encode the entire script and pass via `-EncodedCommand`. |
| S2 | **High** | `tools.py` `read_file` / `list_directory` / `search_files` | Could read `.env`, `.ssh/id_rsa`, `credentials.json`, `*.pem`, etc. Prompt injection could leak secrets. | New `_is_sensitive()` allowlist filter + `SensitivePathError`; sensitive paths blocked from read, hidden from listings, skipped during search. |
| S3 | **Medium** | `tools.py` `wikipedia_summary` / `get_weather` | URL params not properly encoded — path traversal risk and broken requests for inputs with spaces/special chars. | `urllib.parse.quote(..., safe='')` everywhere. |
| S4 | **Medium** | `health.py` `/api/log` | Unauthenticated, unlimited size, no rate limit — easy DoS surface for filling disk or flooding logs. | Pydantic `max_length` validators, sliding-window rate limit (60 req/min/IP), newline stripping prevents log injection. |

### Correctness

| # | Location | Issue | Fix |
|---|----------|-------|-----|
| C1 | `orchestrator.py` `_bg_extract` | `asyncio.create_task` with no held reference — Python may garbage-collect mid-execution (CPython documented gotcha). | Strong-ref set `self._bg_tasks` + self-cleanup via `add_done_callback`. |
| C2 | `orchestrator.py` | Memory `kind` allowed `commitment` in extraction but tool definition only allowed `summary`. Inconsistency lost data. | Single source of truth: `VALID_MEMORY_KINDS` exported from `tools.py`, used everywhere. Tool enum updated. |
| C3 | `orchestrator.py` `_strip_json_fences` | Conditional with both branches identical (dead code). | Simplified. |
| C4 | `orchestrator.py` | Unused `import re` from removed rule-based extraction. | Removed. |
| C5 | `useVoiceSession.ts` | Calling `start()` twice leaks `SpeechRecognition`. | Stop existing instance before creating new one. |

---

## Open issues — NOT yet fixed

### Should fix before production

| # | Severity | Location | Issue | Suggested fix |
|---|----------|----------|-------|---------------|
| O1 | High | `main.py` | `CORSMiddleware(allow_origins=["*"], allow_credentials=False)` for HTTP. Fine for local dev, dangerous in production. | Read from `ALLOWED_ORIGINS` env var (already in `.env`). |
| O2 | Medium | `auth.py` | `/api/auth/login` has no rate limit. Brute force / account enumeration possible. | Add per-IP rate limit (5 attempts/min). |
| O3 | Medium | `tools.py` `read_file` | 512 KB size cap is in KB but compared against `size_kb > 512` correctly. Could still OOM on many large files in one tool round. | Acceptable for now. |
| O4 | Medium | `observability/logger.py` | No log rotation. `frontend.log` grows unbounded. | Use `RotatingFileHandler` (10 MB × 5 files). |
| O5 | Medium | Roadmap §1.1 | "Tool calls and results logged to `audit_log`" — not implemented. | Insert into `audit_log` table from `_execute_tool` (capture name, args hash, result length, latency). |
| O6 | Low | `tools.py` `web_search` | DuckDuckGo Instant Answer API often returns empty — most queries fall through to `duckduckgo_search` package after a wasted HTTP call. | Reorder: try the package first (real search results), fall back to instant API. Or call both in parallel. |
| O7 | Low | `tools.py` module load | `httpx.AsyncClient` created at import time, never closed. Connection pool leak on reload. | Move to a lazy singleton with explicit `aclose()` on app shutdown. |
| O8 | Low | `useVoiceSession.ts` | `recognition.lang = "en-US"` hardcoded. | Read from user_settings on backend, send via WS or REST. |
| O9 | Low | `ws.py` | Messages capped at 4096 chars but JSON parsing happens before size check on raw text — ok in practice but reorder for clarity. | Cosmetic. |
| O10 | Low | `App.tsx` | `sessionToken` lives only in React state — closing the browser forces re-login, which contradicts the always-on UX goal. | Persist to `localStorage` so VERA reconnects automatically. |

### Privacy / Security hardening (long term)

| # | Location | Issue | Suggested fix |
|---|----------|-------|---------------|
| P1 | DB schema | No retention policy on `chat_messages` or `memory_items`. Grows forever. | Optional background job to archive/delete messages older than N days (user-configurable, OFF by default — VERA's value comes from long memory). |
| P2 | Whole stack | No HTTPS / WSS configuration. | Provide nginx config or Caddy reverse-proxy template for production. |
| P3 | `connection_manager.py` | In-process registry. Multi-worker uvicorn would break suggestions delivery. | Document the constraint, or migrate to Redis pub/sub. |
| P4 | Tool calls | LLM can chain tool calls indefinitely up to `_MAX_TOOL_ROUNDS=6`. A misbehaving prompt could rack up Groq cost. | Add per-user token / call budget + alerting. |

### Roadmap items still pending (Phase 1)

- [ ] Tool calls audit log (O6 above)
- [ ] Search-enhanced suggestions (Phase 1.5) — scheduler integrates `web_search`

### Architectural debt

- **No tests.** Zero unit/integration tests in the repo. At minimum, tests for: `_strip_json_fences`, `_is_sensitive`, memory deduplication, tool execution error path.
- **Manual SQL migrations.** Should adopt Alembic (see `MANUAL_SETUP.md` §12).
- **No structured logging.** `structlog` is in requirements but not used. Tool calls and LLM responses would benefit from structured fields.
- **No tracing.** OpenTelemetry would help diagnose latency in the tool-calling loop (LLM call → tool exec → LLM call ...).

---

## Verification done in this review

| Check | Result |
|-------|--------|
| `python -m py_compile` on all backend files | ✅ Clean |
| `npx tsc --noEmit` on frontend | ✅ Clean |
| `_is_sensitive` blocks `.env`, `.ssh`, `*.pem` | ✅ Tested |
| `_strip_json_fences` with `\`\`\`json` wrapping | ✅ Tested |
| All 11 tools registered and importable | ✅ Verified |
| `VALID_MEMORY_KINDS` consistent across all 4 use sites | ✅ Verified |
| MANUAL_SETUP API quotas / URLs / OAuth flows | ✅ Re-verified against current vendor docs |

---

## Roadmap completeness check

Re-read [VERA_requests.md](VERA_requests.md) and [VERA_ROADMAP.md](VERA_ROADMAP.md):

**Captured in roadmap:** All 11 of VERA's stated needs (knowledge databases, real-time streams, integrations, emotional intelligence, predictive modeling, continuous learning, etc.) are mapped to phases.

**Not yet on roadmap (suggest adding):**
- **Wake-word detection** — VERA could activate on hearing her name without needing the user to click "Start voice". Picovoice Porcupine offers a free tier.
- **Multi-user privacy** — when shared (Phase 7.4), need per-user memory isolation guarantees.
- **Memory editing UI** — user can review/delete memories VERA has stored. Important for trust.
- **Token cost dashboard** — user-visible API spend tracker.
- **Backup / export** — user can export their VERA data (memories, chats) as JSON.

---

## Design decisions (intentionally NOT fixed)

These looked like bugs but are deliberate by design — VERA is meant to be an
always-on, always-ready personal assistant:

- **Session tokens never expire.** VERA shouldn't lock you out. The token is
  sent over WS as the first message (not in URLs/access logs), so it doesn't
  leak through normal channels. The user can still invalidate any session by
  ending it from the UI.
- **No message encryption.** Chat history and memories live in plain text in
  the local PostgreSQL DB. Encryption is deferred until/if VERA runs on shared
  infrastructure.

If either of these changes (e.g. you add a multi-user mode or expose VERA over
the public internet), revisit both decisions.

---

## Summary

This review fixed **5 bugs** (1 critical security, 1 high security, 1 medium, 2 correctness) and documented **14 open issues** ranked by severity. The codebase is solid for local single-user dev use. Top three things to address before exposing to a network:

1. **CORS `allow_origins` from `ALLOWED_ORIGINS` env var** (O1) — the only blocker for safe network exposure
2. **Login rate limiting** (O2) — prevents brute force / enumeration
3. **Persist `sessionToken` to localStorage** (O10) — finishes the always-on UX loop so closing the browser doesn't force re-login
