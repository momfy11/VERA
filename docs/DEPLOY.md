# VERA — Production deployment

Target: Hetzner Cloud (Ubuntu 22.04 / 24.04) at `46.62.225.46`. Should work
on any Debian/Ubuntu host with at least 2 GB RAM and 20 GB disk. The same
flow applies to DigitalOcean, Linode, Vultr, etc.

End state: VERA serving at `https://46-62-225-46.sslip.io` with auto TLS,
HTTPS-only (HTTP redirects), and the PWA installable on phone.

---

## 0. What gets deployed

```
┌─────────── host (your Hetzner box) ──────────────────────────────┐
│                                                                   │
│  :80 / :443  ─► caddy ─► /api/* ─► backend  (uvicorn :8000)       │
│                          /ws    ─► backend                        │
│                          /*     ─► frontend (nginx :80 in-image)  │
│                                                                   │
│              backend ◄────► postgres (pgvector/pgvector:pg16)     │
│                            stored in named volume vera_pgdata     │
└───────────────────────────────────────────────────────────────────┘
```

Caddy auto-issues + renews a Let's Encrypt certificate. Frontend is built at
image-build time with the public API/WS URLs baked into the JS bundle.

---

## 1. Prepare locally (one-time)

Make sure the work I did in this session is in `main`:

```powershell
cd C:\Users\momfy\repos\VERA
git add -A
git commit -m "deploy: backend entrypoint, postgres pgvector init, prod overlay, deploy.sh"
git push origin main
```

The repo is private (`git@github.com:momfy11/VERA.git`), so on the server
you'll either clone over HTTPS with a Personal Access Token or set up an
SSH deploy key — both options are covered below.

---

## 2. SSH to the server

```bash
ssh -i ~/.ssh/vera_deploy_ed25519 root@46.62.225.46
```

---

## 3. Give GitHub a way to read this repo

### Option A — SSH deploy key (recommended)

On the server:

```bash
ssh-keygen -t ed25519 -f /root/.ssh/vera_deploy -N ""
cat /root/.ssh/vera_deploy.pub
```

Copy the printed public key. In GitHub:

1. Open https://github.com/momfy11/VERA/settings/keys
2. **Add deploy key** → name "Hetzner prod", paste the key, leave "Allow
   write access" **unchecked**, save.

Then tell the server's SSH client to use this key for github.com:

```bash
cat >> /root/.ssh/config <<'EOF'
Host github.com
    HostName github.com
    User git
    IdentityFile /root/.ssh/vera_deploy
    IdentitiesOnly yes
EOF
chmod 600 /root/.ssh/config
ssh -T git@github.com   # should say "Hi momfy11/VERA! You've successfully authenticated"
```

### Option B — HTTPS + Personal Access Token

If you'd rather skip SSH, create a fine-scoped PAT on GitHub (Settings →
Developer settings → Tokens (classic) → "repo" scope, read-only is enough)
and clone with it embedded in the URL once:

```bash
git clone https://<your-pat>@github.com/momfy11/VERA.git
```

Don't paste the PAT into shell history — use `read -s PAT` first.

---

## 4. Clone the repo on the server

```bash
cd /opt
git clone git@github.com:momfy11/VERA.git
cd VERA
```

(Substitute the HTTPS URL if you used Option B.)

---

## 5. Configure secrets

Two env files. Both are gitignored — they only live on the server.

### 5a. Root `.env` (compose-time vars)

```bash
cp .env.production.example .env
nano .env
```

Set at minimum:

| Key | Value |
|---|---|
| `DOMAIN` | `46-62-225-46.sslip.io` |
| `PUBLIC_API_BASE` | `https://46-62-225-46.sslip.io/api` |
| `PUBLIC_WS_BASE` | `wss://46-62-225-46.sslip.io` |
| `ALLOWED_ORIGINS` | `https://46-62-225-46.sslip.io` |
| `POSTGRES_PASSWORD` | output of `openssl rand -hex 24` |

> Why sslip.io? You don't own a domain yet and Let's Encrypt won't issue a
> cert for a bare IP. sslip.io is a public wildcard DNS that resolves any
> IP-shaped subdomain back to that IP. So `46-62-225-46.sslip.io` →
> `46.62.225.46`. No DNS configuration required. Replace with your real
> domain later — just edit `.env` and re-deploy.

### 5b. `backend/.env` (runtime secrets)

```bash
cp backend/.env.example backend/.env
nano backend/.env
```

At minimum:

| Key | Value |
|---|---|
| `LLM_PROVIDER` | `gemini` |
| `LLM_MODEL` | `gemini-2.5-flash` |
| `GEMINI_API_KEY` | your key from https://aistudio.google.com/apikey |
| `SECRET_KEY` | `openssl rand -hex 32` |
| `ENVIRONMENT` | `production` |
| `NEWS_API_KEY` | optional, for news tool |
| `SPOTIFY_*` | optional, for Spotify tools |

You can copy `backend/.env` from your dev machine if it already has all keys
filled in (`scp -i ~/.ssh/vera_deploy_ed25519 backend/.env root@46.62.225.46:/opt/VERA/backend/.env`).

### 5c. Google OAuth client secret (optional, only for Gmail/Calendar tools)

The Google Cloud OAuth Desktop-app client secret lives at
`backend/credentials/credentials.json`. Upload it from your dev machine:

```powershell
# from your laptop, not the server
scp -i ~/.ssh/vera_deploy_ed25519 `
    C:\Users\momfy\repos\VERA\backend\credentials\credentials.json `
    root@46.62.225.46:/opt/VERA/backend/credentials/credentials.json
```

You'll authorize Google from the running UI's *Integrations* panel after
the stack is up.

---

## 6. Run the deploy script

```bash
cd /opt/VERA
bash scripts/deploy.sh
```

The script will:

1. Install Docker Engine + compose plugin (skipped if already installed)
2. Open ports 22/80/443 in `ufw` (skipped if `ufw` not present)
3. Verify `.env` and `backend/.env` look sane
4. Build the backend + frontend images (~3-5 minutes first time)
5. Start postgres, backend, frontend, caddy
6. Wait for the backend healthcheck to flip green
7. Print the public URL

If the script aborts, the message will tell you why (e.g. "still has the
placeholder POSTGRES_PASSWORD").

---

## 7. Verify

```bash
curl https://46-62-225-46.sslip.io/api/health
# expect: {"status":"ok"}
```

Then in a browser on your phone:

1. Open `https://46-62-225-46.sslip.io`.
2. Log in.
3. Tap the **Install VERA** button (Android Chrome) or Share → Add to
   Home Screen (iOS Safari).
4. Launch from the home-screen icon.

If the first request hangs ~30-60 seconds, Caddy is still issuing the
Let's Encrypt cert — give it a moment.

---

## 8. Day-2 ops

```bash
cd /opt/VERA
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

# Tail logs
$COMPOSE logs -f backend
$COMPOSE logs -f caddy

# Update to a new git revision
git pull
$COMPOSE build --pull
$COMPOSE up -d                 # only changed services restart

# Restart just one service
$COMPOSE restart backend

# Stop everything (keeps data)
$COMPOSE down

# Destroy data (postgres volume too)
$COMPOSE down -v
```

---

## 9. Switching to a real domain later

When you have one, e.g. `vera.example.com`:

1. Add an `A` record pointing the domain at `46.62.225.46`.
2. Edit `/opt/VERA/.env` and update `DOMAIN`, `PUBLIC_API_BASE`,
   `PUBLIC_WS_BASE`, `ALLOWED_ORIGINS` to the new hostname.
3. Rebuild + restart:
   ```bash
   $COMPOSE build frontend
   $COMPOSE up -d
   ```
   (Frontend has to rebuild because the public URL is baked into the JS
   bundle. Backend just restarts.)

Caddy will provision a new cert on first hit. Sslip.io cert sits unused.

---

## 10. Troubleshooting

**`docker compose` build fails on `npm ci`.**  Likely Vite / rolldown
native binary mismatch if you have a partially-restored `node_modules`.
Inside Docker this shouldn't happen, but if it does:
`docker compose build --no-cache frontend`.

**Cert issuance fails: "no valid A record".**  sslip.io subdomain must
exactly match the IP. `46-62-225-46.sslip.io` resolves to `46.62.225.46`.
Verify: `dig +short 46-62-225-46.sslip.io`.

**Cert issuance fails: "port 80 already in use".**  Something else on the
host is bound to :80. `ss -tlnp | grep :80`. Usually nginx or apache from
an earlier install — stop / disable it.

**`alembic upgrade head` errors about missing tables.**  This means the
backend container has run before but against a different DB version. The
entrypoint script handles fresh DBs by stamping head and running the
ALTER-only migration over the existing tables. If you see this on a fresh
volume, drop the volume and try again: `$COMPOSE down -v && bash scripts/deploy.sh`.

**Backend says `pgvector setup skipped`.**  Best-effort warning. The
postgres image includes pgvector and the init SQL runs `CREATE EXTENSION
vector;` on first start. The backend will still work — semantic memory
falls back to recency-based retrieval. To force-enable on an existing volume:
`$COMPOSE exec postgres psql -U vera -d vera -c 'CREATE EXTENSION IF NOT EXISTS vector;'`

**iOS standalone shows blank screen on launch.**  Service worker hadn't
finished installing on first load. Open the URL in Safari, wait ~10s, then
re-add to home screen.

**WebSocket fails with `1006` close code.**  Caddy is in front, so check
that you're using `wss://` (not `ws://`) in `PUBLIC_WS_BASE`, that the
frontend bundle was actually built with that URL (`grep -r wss /opt/VERA`
inside the running frontend container), and that the Caddy `handle /ws`
block is present.
