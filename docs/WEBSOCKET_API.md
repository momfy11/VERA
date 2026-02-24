"""Document the WebSocket event schema and API endpoints."""

# WebSocket Events

Events are sent as JSON-RPC–style messages with `type` and `payload` fields.

## Client → Server

- **client.hello**: Initial handshake with metadata
- **client.message**: User text input from chat
- **voice.vad_start**: User starts speaking (VAD triggered)
- **voice.vad_end**: User stops speaking
- **stt.partial**: Partial transcript from STT
- **stt.final**: Final transcript, ready for processing

## Server → Client

- **server.hello**: Server acknowledges connection
- **assistant.text**: Response text (stream as needed)
- **assistant.tts_start**: TTS playback starting
- **assistant.tts_cancel**: Cancel ongoing TTS playback
- **agent.suggestion**: Proactive suggestion payload
- **server.error**: Error message

---

# REST API Endpoints

## Auth

- **POST** `/api/auth/login` – Create/fetch user and issue session token
  - Request: `{ email, display_name? }`
  - Response: `{ user_id, session_token }`

## Health

- **GET** `/api/health` – Server status
  - Response: `{ status }`

## Suggestions

- **GET** `/api/suggestions` – List latest suggestions for user
  - Header: `X-Session-Token: <token>`
  - Response: `{ items: [...] }`

---

# Session Management

1. Client POSTs `/api/auth/login` with email
2. Server returns `session_token`
3. Client connects to `/ws?token=<session_token>`
4. Client passes token in WS query param
5. Server validates token and yields user context for the session
