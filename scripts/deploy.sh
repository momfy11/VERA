#!/usr/bin/env bash
# VERA — production bring-up on a fresh Linux host.
#
# Run as root from the repo root:
#   sudo bash scripts/deploy.sh
#
# Assumptions / what this script will do for you:
#   * Install Docker Engine + compose plugin if not already present
#   * Open firewall ports 80, 443 (and keep 22)
#   * Verify .env and backend/.env exist; fail loudly if they don't
#   * Pull base images, build app images, start the stack with the prod overlay
#   * Wait for backend healthcheck to flip green, then print the public URL
#
# Re-running is safe: every step is idempotent.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

say() { printf "\033[1;36m[deploy]\033[0m %s\n" "$*"; }
die() { printf "\033[1;31m[deploy:ERR]\033[0m %s\n" "$*" >&2; exit 1; }

if [ "$(id -u)" -ne 0 ]; then
    die "Run as root: sudo bash scripts/deploy.sh"
fi

# ── 1. Docker ──────────────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
    say "Installing Docker Engine + compose plugin (this can take a minute)..."
    apt-get update -y
    apt-get install -y ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    . /etc/os-release
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
        https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -y
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
else
    say "Docker already installed: $(docker --version)"
fi

# ── 2. Firewall ────────────────────────────────────────────────────────────
if command -v ufw >/dev/null 2>&1; then
    say "Opening 22/80/443 in ufw..."
    ufw allow 22/tcp  >/dev/null || true
    ufw allow 80/tcp  >/dev/null || true
    ufw allow 443/tcp >/dev/null || true
fi

# ── 3. .env sanity ─────────────────────────────────────────────────────────
[ -f .env ] || die "Missing .env at repo root. Copy .env.production.example → .env and fill in DOMAIN, secrets."
[ -f backend/.env ] || die "Missing backend/.env. Copy backend/.env.example → backend/.env and fill in API keys."

# Required keys in root .env
for k in DOMAIN PUBLIC_API_BASE PUBLIC_WS_BASE ALLOWED_ORIGINS POSTGRES_PASSWORD; do
    if ! grep -E "^${k}=" .env >/dev/null; then
        die ".env is missing $k — see .env.production.example"
    fi
done
if grep -q "change-me-to-a-long-random-string" .env; then
    die ".env still has the placeholder POSTGRES_PASSWORD. Generate one: openssl rand -hex 24"
fi

# Required keys in backend/.env
for k in LLM_PROVIDER SECRET_KEY GEMINI_API_KEY; do
    if ! grep -E "^${k}=" backend/.env >/dev/null; then
        say "WARN: backend/.env missing $k (deploy will continue, but the app may not work)"
    fi
done

# ── 4. Build + start ───────────────────────────────────────────────────────
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

say "Pulling base images..."
$COMPOSE pull postgres caddy || true   # build images for app are local

say "Building application images (this is the slow step on first run)..."
$COMPOSE build --pull

say "Starting stack..."
$COMPOSE up -d

# ── 5. Wait for health ─────────────────────────────────────────────────────
say "Waiting for backend healthcheck..."
deadline=$(($(date +%s) + 180))
while [ "$(date +%s)" -lt "$deadline" ]; do
    state=$($COMPOSE ps backend --format '{{.Status}}' 2>/dev/null || true)
    case "$state" in
        *"healthy"*) say "Backend healthy: $state"; break ;;
        *"unhealthy"*) say "ERR: backend reports unhealthy. Showing recent logs:"; $COMPOSE logs --tail=60 backend; exit 1 ;;
    esac
    sleep 3
done

# ── 6. Status summary ──────────────────────────────────────────────────────
domain=$(grep -E '^DOMAIN=' .env | head -1 | cut -d= -f2-)
say "Done."
say "Public URL:  https://$domain"
say "Caddy will issue / renew the TLS cert automatically (first time can take 30-60s)."
say "Tail backend logs:  $COMPOSE logs -f backend"
say "Tail caddy logs:    $COMPOSE logs -f caddy"
say "Stop the stack:     $COMPOSE down"
say "Stop + wipe data:   $COMPOSE down -v   (deletes postgres volume)"
