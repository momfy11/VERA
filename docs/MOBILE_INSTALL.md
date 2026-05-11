# Installing VERA on your phone (before deploying)

You can install VERA as a Progressive Web App on your phone while it's still
running on your laptop — no server deployment needed. This is the recommended
way to test the mobile experience (PWA install, wake word, voice) before you
ship to a public URL.

There are two viable approaches:

| Approach | Works on iOS? | Works on Android Chrome install prompt? | Setup cost |
|---|---|---|---|
| **A. LAN over plain HTTP** | Page loads, but no SW, no install | Page loads, no install prompt | 0 |
| **B. HTTPS tunnel (cloudflared / ngrok)** | ✅ full install via Share menu | ✅ full install button | ~5 min |

PWAs can only register a service worker (and therefore the install prompt
only appears) when served from `localhost` or over HTTPS. Your phone is
neither, so for a real install **use approach B**.

Approach A is still useful for a quick "does the layout work on my phone"
sanity check.

---

## A. Quick sanity check over LAN (no install)

Already partially set up — `vite.config.ts` now binds to `0.0.0.0`.

1. Find your laptop's LAN IP from PowerShell:

   ```powershell
   ipconfig | Select-String "IPv4"
   ```

   Pick the one on your Wi-Fi adapter, e.g. `192.168.1.42`.

2. Tell the frontend where the backend is. Create `client/.env.local`:

   ```ini
   VITE_API_BASE=http://192.168.1.42:8000/api
   VITE_WS_BASE=ws://192.168.1.42:8000
   ```

3. Start backend bound to all interfaces:

   ```powershell
   cd backend
   ..\vera\Scripts\uvicorn backend.app.main:app --host 0.0.0.0 --port 8000
   ```

4. Start frontend (already binds to 0.0.0.0 after the vite.config update):

   ```powershell
   cd client
   npm run dev
   ```

5. On your phone (same Wi-Fi), open `http://192.168.1.42:5173`.

Backend CORS in development now also accepts any LAN IP origin
(`192.168.*`, `10.*`, `172.16-31.*`), so the API + WebSocket calls will work.

You will **not** see an install prompt this way — Chrome blocks PWA install
over plain HTTP. Use approach B for that.

---

## B. Install on phone via HTTPS tunnel (recommended)

Cloudflare Tunnel is free and needs no signup. We'll expose the production
build (faster than dev mode and exactly what you'll deploy).

### One-time setup

Install `cloudflared` on Windows (one of):

```powershell
winget install --id Cloudflare.cloudflared
# or
choco install cloudflared
```

### Each session

You'll run **four** terminals. Backend and frontend each get their own tunnel.

**1. Backend**

```powershell
cd backend
..\vera\Scripts\uvicorn backend.app.main:app --host 0.0.0.0 --port 8000
```

**2. Backend tunnel**

```powershell
cloudflared tunnel --url http://localhost:8000
```

Copy the printed URL, e.g.

```
https://random-words-xyz.trycloudflare.com
```

**3. Frontend** — first put the backend tunnel URL into `client/.env.local`:

```ini
VITE_API_BASE=https://random-words-xyz.trycloudflare.com/api
VITE_WS_BASE=wss://random-words-xyz.trycloudflare.com
```

Then build and serve the production bundle:

```powershell
cd client
npm run build
npm run preview
```

(`preview` already binds to 0.0.0.0 thanks to the vite.config update,
serving the built bundle on port 4173.)

**4. Frontend tunnel**

```powershell
cloudflared tunnel --url http://localhost:4173
```

Copy the printed URL, e.g. `https://other-words-abc.trycloudflare.com`.

### Install on phone

1. Open the **frontend tunnel URL** on your phone.
2. **Android Chrome:** Tap the "Install VERA" button in the top bar
   (rendered by `useInstallPrompt`), or use the kebab menu → "Install app".
3. **iOS Safari:** Tap the Share button → "Add to Home Screen". (iOS doesn't
   fire `beforeinstallprompt`, so the in-app install button doesn't appear —
   that's why the index.html already has `apple-mobile-web-app-capable` and
   the apple-touch-icon.)
4. Launch from the home-screen icon. It opens standalone (no browser chrome)
   and the wake word + voice should work after granting mic permissions.

Both tunnel URLs are already CORS-allowed by the dev backend
(`*.trycloudflare.com`, `*.ngrok-free.app`, `*.loca.lt` are all in the
regex). The backend will log `CORS allow_origins=… regex=True` on startup.

### Notes / gotchas

- **Tunnel URLs change every session.** When you restart `cloudflared`, both
  URLs change. You have to update `client/.env.local` and rebuild. For
  permanent URLs, sign in to Cloudflare and create a named tunnel — same
  command, but the URL stays.
- **Don't tunnel a dev server long-term over the internet.** It's fine for
  installing onto your own phone for a few hours. Don't paste the URL in a
  public Slack.
- **Mic permissions** are origin-bound. If the tunnel URL changes, the
  phone will re-prompt for microphone access.
- **Service worker caching.** After updating the build, on the phone do a
  hard refresh (Chrome: long-press reload → "Reload from origin"; iOS:
  delete the home-screen app and re-add).

---

## Troubleshooting

**"Install" button doesn't appear on Android.**  Open Chrome DevTools (chrome://inspect on laptop, USB-debug
the phone), check Application → Manifest. Common causes: not HTTPS, manifest
missing `start_url` / `display: standalone`, or the SW didn't register
(check Application → Service Workers).

**WebSocket fails with `wss://` upgrade error.**  Make sure
`VITE_WS_BASE` uses `wss://` (not `ws://`) for the cloudflared URL.
HTTPS pages can only open secure WebSockets.

**CORS error in console.**  Restart the backend after editing `.env`. Check
the startup log line `CORS allow_origins=[...] regex=True`. If `regex=False`
your `ENVIRONMENT` isn't `development`.

**iOS standalone mode shows white screen on launch.**  Service worker hadn't
finished installing on first load. Open the page in Safari again, wait a few
seconds, then re-add to home screen.
