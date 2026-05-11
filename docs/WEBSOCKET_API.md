# WebSocket + REST API Reference

Updated 2026-05-07.

WebSocket events use JSON `{type, payload}`. REST endpoints use `X-Session-Token` header.

---

## WebSocket — Client → Server

| Type | Payload | Notes |
|---|---|---|
| `client.hello` | `{token}` | **MUST** be first frame after open. Auth via session token. 10s timeout to send. |
| `client.message` | `{text}` | User chat message. Max 4096 chars. |
| `stt.final` | `{text}` | Same as `client.message` but emitted from voice STT pipeline. Equivalent. |
| `voice.vad_start` | `{ts}` | User started speaking. Server replies with `assistant.tts_cancel` for barge-in. |
| `voice.vad_end` | `{ts}` | User stopped speaking. Currently no server reply. |

Rate limits: 60 messages / 60s sliding window per connection. Excess → `server.error: rate_limited`.

---

## WebSocket — Server → Client

| Type | Payload | Purpose |
|---|---|---|
| `server.hello` | `{ts, display_name}` | Auth success — frontend transitions to "active" |
| `server.error` | `{message, detail?}` | Errors. `message` ∈ {unauthorized, hello_timeout, rate_limited, message_too_long, invalid_json, invalid_payload, backend_init_failed, ...} |
| `assistant.text` | `{text}` | Final assistant reply (markdown-formatted) |
| `assistant.thinking` | `{text, tool}` | **Pre-tool ack** — emitted before each tool call ("Checking your inbox…"). UI shows transient italic + TTS speaks it |
| `assistant.tts_cancel` | `{ts}` | Sent on `voice.vad_start` so client can stop TTS for barge-in |
| `agent.suggestion` | `{id, type, priority, title, reason, ts, status}` | Proactive suggestion from scheduler |
| `agent.open_url` | `{url, label}` | Tool wants browser to open URL (Maps, websites). Frontend calls `window.open` |
| `agent.action_pending` | `{action_id, tool, summary, args, timeout_s}` | Destructive-tool gate — frontend shows approval modal |
| `agent.action_resolved` | `{action_id, decision}` | Backend confirms decision/timeout — frontend dismisses modal |

---

## REST API

All POST/PATCH/DELETE require `X-Session-Token: <token>` header. GET `/health` and POST `/auth/login` are unauthenticated.

### Auth

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/auth/login` | `{email, display_name?}` | `{user_id, session_token}` |

Rate-limited 10/min/IP. Email-only — no password. (Sign in with Google planned.)

### Health + Logging

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/health` | — | `{status: "ok"}` |
| POST | `/api/log` | `{level, message, source?}` | `{ok: true}` |

`/api/log` rate-limited 60/min/IP. `message` capped at 2000 chars. Newlines stripped.

### Suggestions

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/suggestions` | — | `{items: [SuggestionItem]}` |
| PATCH | `/api/suggestions/{id}` | `{action: "accepted"\|"rejected"\|"snoozed"}` | updated SuggestionItem |

Snooze creates a fresh row reappearing after 30 min.

### Memories

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/memories` | — | `{items: [MemoryItem]}` (active only, max 200, newest first) |
| DELETE | `/api/memories/{id}` | — | `{deleted: true}` |

DELETE is soft (sets `is_active=false`). User can only delete their own memories.

### Google OAuth

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/google/status` | — | `{connected, email, in_progress, error}` |
| POST | `/api/google/connect` | — | `{started: true}` (browser opens) |
| POST | `/api/google/disconnect` | — | `{disconnected: true}` |

`/google/connect` spawns OAuth flow in background thread. Frontend polls `/google/status` while `in_progress=true`.

### Approval Gate

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/actions/{action_id}/{decision}` | — | `{ok: true, status}` |

`decision` ∈ `{approve, reject}`. Action ownership verified by session token. 404 if not found, 409 if already resolved.

---

## Session Lifecycle

1. **Login** — Client POSTs `/api/auth/login` → receives `session_token`
2. **Persist** — Frontend writes token to `localStorage` (`vera.session_token`)
3. **WS connect** — Client opens `ws://host/ws` (no token in URL)
4. **Hello** — Client sends `{type: "client.hello", payload: {token}}` within 10 sec
5. **Auth** — Server validates, sends `server.hello` with display name OR `server.error: unauthorized`
6. **Loop** — Client/server exchange events as above
7. **Disconnect** — Client closes (intentional) → frontend keeps token. Server closes (unexpected) → frontend may auto-reconnect (planned, not yet wired)

Tokens never expire by design. End-session button clears localStorage + closes WS.

---

## Approval-Gate Flow (destructive tools)

```
User: "send email to alice@example.com saying hi"
  ↓
LLM emits tool_call(send_email, {...})
  ↓
Orchestrator: name in DESTRUCTIVE_TOOLS → pause
  ↓
Insert agent_actions row (pending) + register asyncio.Event
  ↓
Emit agent.action_pending WS event → modal shows
  ↓
[await event.wait() with 60s timeout]
  ↓
User clicks Allow → POST /api/actions/{id}/approve
  ↓
REST handler: update DB row + approval_gate.resolve() → wakes orchestrator
  ↓
Tool executes → result returned to LLM → assistant.text reply
  ↓
Emit agent.action_resolved → modal dismisses
```

Timeout = reject (fail closed). User declines → returns "User declined to send email to alice…" to LLM, which adapts its reply.

---

## Open Questions / Future

- WSS (TLS) — not configured for production yet
- Multi-worker uvicorn — `connection_manager` is in-process; would need Redis pub/sub
- Heartbeat / ping — not implemented; relies on TCP keepalive
