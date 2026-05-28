# VERA Client (PWA)

React 18 + Vite + TypeScript. Markdown-rendered chat. Web Speech API STT/TTS (en-US).
Camera capture + clipboard paste image input. PWA installable. Mic sensitivity calibration.
Approval modal for destructive tool calls.

## Setup

```bash
cd client
npm install --legacy-peer-deps   # Vite 8 vs plugin-react peer conflict — harmless
```

## Dev server

```bash
npm run dev
```

Opens at `http://localhost:5173`. Auto-proxies API to `http://localhost:8000`
via env vars in `vite-env.d.ts` (override with `client/.env`).

## Build

```bash
npm run build
```

Output → `client/dist/`. Includes `manifest.webmanifest`, `sw.js` (workbox),
`registerSW.js`. Served by `client/Dockerfile` via nginx.

## TypeScript

```bash
npx tsc --noEmit
```

Must stay clean (no errors).

## Component tree

```
src/
  main.tsx                  — entry, mounts <App>
  App.tsx                   — top-level state, WS handlers, routes Login↔Main
  components/
    LoginPage.tsx           — "Sign in with Google" + email fallback
    MainPage.tsx            — top bar, chat, voice bar, settings drawer, ApprovalModal
    ApprovalModal.tsx       — Allow/Deny for destructive tools, 60s countdown
    IntegrationsPanel.tsx   — Google connect/disconnect status
    MemoriesPanel.tsx       — list + delete VERA's stored memories
    SuggestionsPanel.tsx    — proactive suggestions
    SettingsPanel.tsx       — toggles (placeholder)
    StatusPill.tsx          — session/mic status indicators
    Toasts.tsx              — transient error notifications
  lib/
    api.ts                  — REST wrappers
    ws.ts                   — createSessionSocket
    types.ts
    logger.ts               — console.error/warn → POST /api/log
    useVoiceSession.ts      — VAD + STT + mute + sensitivity + noise calibration
    useTTS.ts               — Web Speech API TTS + markdown stripping (en-US)
    useWakeWordServer.ts    — server-side wake word via faster-whisper (disabled pending native app)
    useInstallPrompt.ts     — PWA install button helper
    stripMarkdown.ts        — strip md syntax for natural TTS
  styles.css                — single global stylesheet
public/
  favicon.svg
  icons/                    — 192/512/maskable PNGs for PWA install
```

## Env (`client/.env`)

Optional overrides:
```
VITE_API_BASE=http://localhost:8000/api
VITE_WS_BASE=ws://localhost:8000
```

For production builds, see `docker-compose.prod.yml` for build-time injection.

## Production build (Docker)

```bash
docker build \
  -f client/Dockerfile \
  --build-arg VITE_API_BASE=https://yourdomain.com/api \
  --build-arg VITE_WS_BASE=wss://yourdomain.com \
  -t vera-client .
```

Or via compose: `docker compose -f docker-compose.yml -f docker-compose.prod.yml build frontend`.
