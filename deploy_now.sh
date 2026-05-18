#!/usr/bin/env bash
# VERA -- full deploy from Git Bash
# Usage (from repo root):  bash deploy_now.sh
set -euo pipefail

SERVER="root@46.62.225.46"
SSH_KEY="$HOME/.ssh/vera_deploy_ed25519"

SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no"
SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=no"

say()  { printf "\033[1;36m[deploy]\033[0m %s\n" "$*"; }
die()  { printf "\033[1;31m[ERROR]\033[0m  %s\n" "$*" >&2; exit 1; }

# --------------------------------------------------------------------------
# 0. Sanity checks
# --------------------------------------------------------------------------
say "Checking prerequisites..."
[ -f "$SSH_KEY" ]             || die "SSH key not found at $SSH_KEY"
[ -f "backend/.env" ]         || die "backend/.env not found -- run from repo root (cd ~/repos/VERA)"
[ -f "scripts/deploy.sh" ]    || die "scripts/deploy.sh not found -- run from repo root"
say "OK"

# --------------------------------------------------------------------------
# 1. Test SSH first
# --------------------------------------------------------------------------
say "Testing SSH connection..."
# Try key auth first (no password); if fails, install key via password (one-time)
if ! ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o BatchMode=yes "$SERVER" "echo OK" 2>/dev/null; then
    say "Key auth not set up -- installing public key now (enter password once)..."
    cat "${SSH_KEY}.pub" | ssh -o StrictHostKeyChecking=no "$SERVER" \
        "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && chmod 700 ~/.ssh"
    say "Public key installed -- future runs need no password"
fi
$SSH "$SERVER" "echo OK" || die "SSH failed -- check key path: $SSH_KEY"
say "SSH works"

# --------------------------------------------------------------------------
# 2. Git -- untrack backend/.env and push
# --------------------------------------------------------------------------
say "Fixing git tracking for backend/.env..."
if git ls-files --error-unmatch backend/.env 2>/dev/null; then
    # Try to remove from index; if lock exists, warn and continue
    git rm --cached backend/.env 2>/dev/null || say "WARN: git lock active -- skipping untrack (run 'git rm --cached backend/.env' manually later)"
fi

say "Staging and committing..."
git add -A 2>/dev/null || say "WARN: git add failed (lock) -- pushing whatever is staged"
git diff --cached --quiet && git status --short | grep -q . \
    && git commit -m "prod: Docker stack, Caddy HTTPS, deploy script, PWA WebAPK" 2>/dev/null \
    || say "Nothing new to commit or lock active -- continuing"

say "Pushing to GitHub..."
git push origin main && say "GitHub up to date" || say "WARN: push failed -- continuing with direct file copy"

# --------------------------------------------------------------------------
# 3. Pack repo and ship to server
# --------------------------------------------------------------------------
say "Creating repo tarball..."
TARBALL="/tmp/vera_deploy_$$.tar.gz"
tar -czf "$TARBALL" \
    --exclude=".git" \
    --exclude="client/node_modules" \
    --exclude="vera" \
    --exclude="__pycache__" \
    --exclude="*.pyc" \
    --exclude="client/dist" \
    -C "$(dirname "$(pwd)")" "$(basename "$(pwd)")"
SIZE=$(du -sh "$TARBALL" | cut -f1)
say "Tarball created ($SIZE) -- uploading..."

$SCP "$TARBALL" "$SERVER:/tmp/vera_deploy.tar.gz"
rm -f "$TARBALL"
say "Repo uploaded"

# --------------------------------------------------------------------------
# 4. Copy secrets
# --------------------------------------------------------------------------
say "Copying backend/.env..."
$SCP backend/.env "$SERVER:/tmp/vera_backend.env"

CRED_FILE="$(ls backend/credentials/credentials.json 2>/dev/null || ls backend/credentials/client_secret_*.json 2>/dev/null | head -1 || true)"
if [ -n "$CRED_FILE" ] && [ -f "$CRED_FILE" ]; then
    say "Copying Google OAuth credentials ($CRED_FILE)..."
    $SCP "$CRED_FILE" "$SERVER:/tmp/vera_credentials.json"
else
    say "No Google credentials found -- skipping"
fi

# --------------------------------------------------------------------------
# 5. Write setup script, copy and run on server
# --------------------------------------------------------------------------
say "Writing server setup script..."
SETUP_SCRIPT="/tmp/vera_setup_$$.sh"

cat > "$SETUP_SCRIPT" << 'ENDBASH'
#!/usr/bin/env bash
set -euo pipefail
say() { printf "[server] %s\n" "$*"; }
die() { printf "[ERROR]  %s\n" "$*" >&2; exit 1; }

# -- Docker ------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    say "Installing Docker..."
    apt-get update -y -qq
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    . /etc/os-release
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -y -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
    say "Docker installed: $(docker --version)"
else
    say "Docker already present: $(docker --version)"
fi

# -- Firewall ----------------------------------------------------------------
if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp  >/dev/null 2>&1 || true
    ufw allow 80/tcp  >/dev/null 2>&1 || true
    ufw allow 443/tcp >/dev/null 2>&1 || true
fi

# -- Unpack repo -------------------------------------------------------------
REPO_DIR="/opt/VERA"
say "Unpacking repo..."
if [ -d "$REPO_DIR" ]; then
    [ -f "$REPO_DIR/.env" ]         && cp "$REPO_DIR/.env"         /tmp/vera_root.env.bak  || true
    [ -f "$REPO_DIR/backend/.env" ] && cp "$REPO_DIR/backend/.env" /tmp/vera_bk.env.bak    || true
    rm -rf "$REPO_DIR"
fi
mkdir -p /opt
tar -xzf /tmp/vera_deploy.tar.gz -C /opt
[ -d "$REPO_DIR" ] || die "Repo not found at $REPO_DIR after unpack"
cd "$REPO_DIR"

# Restore existing .env files on re-deploy
[ -f /tmp/vera_root.env.bak ] && cp /tmp/vera_root.env.bak .env         || true
[ -f /tmp/vera_bk.env.bak   ] && cp /tmp/vera_bk.env.bak  backend/.env || true

# -- Root .env ---------------------------------------------------------------
if [ ! -f .env ]; then
    say "Creating root .env..."
    cp .env.production.example .env
    PG_PWD=$(openssl rand -hex 24)
    sed -i "s|change-me-to-a-long-random-string|${PG_PWD}|" .env
    say "Generated POSTGRES_PASSWORD"
else
    say "Root .env already present"
    if grep -q "change-me-to-a-long-random-string" .env; then
        PG_PWD=$(openssl rand -hex 24)
        sed -i "s|change-me-to-a-long-random-string|${PG_PWD}|" .env
    fi
fi
PG_PWD=$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)

# -- backend/.env ------------------------------------------------------------
if [ -f /tmp/vera_backend.env ]; then
    say "Installing backend/.env..."
    cp /tmp/vera_backend.env backend/.env
    sed -i "s|DATABASE_URL=.*|DATABASE_URL=postgresql+psycopg2://vera:${PG_PWD}@postgres:5432/vera|" backend/.env
    sed -i "s|ENVIRONMENT=development|ENVIRONMENT=production|"                                        backend/.env
    sed -i "s|ALLOWED_ORIGINS=.*|ALLOWED_ORIGINS=https://46-62-225-46.sslip.io|"                      backend/.env
    say "backend/.env ready"
elif [ ! -f backend/.env ]; then
    say "WARNING: no backend/.env -- fill in API keys manually after deploy"
    cp backend/.env.example backend/.env
    sed -i "s|DATABASE_URL=.*|DATABASE_URL=postgresql+psycopg2://vera:${PG_PWD}@postgres:5432/vera|" backend/.env
fi

# -- Google credentials ------------------------------------------------------
mkdir -p backend/credentials
if [ -f /tmp/vera_credentials.json ]; then
    say "Installing Google credentials..."
    cp /tmp/vera_credentials.json backend/credentials/credentials.json
fi

# -- Deploy ------------------------------------------------------------------
say "Running deploy.sh..."
bash scripts/deploy.sh

# -- Summary -----------------------------------------------------------------
PG_PWD=$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)
echo ""
echo "============================================================"
echo "  VERA is live at: https://46-62-225-46.sslip.io"
echo "============================================================"
echo ""
echo "  DataGrip SSH tunnel (run on your laptop, keep open):"
echo ""
echo "    ssh -i ~/.ssh/vera_deploy_ed25519 -N -L 5433:localhost:5432 root@46.62.225.46"
echo ""
echo "  DataGrip connection:"
echo "    Host:      localhost"
echo "    Port:      5433"
echo "    Database:  vera"
echo "    User:      vera"
echo "    Password:  $PG_PWD"
echo ""
echo "============================================================"
ENDBASH

say "Uploading setup script..."
$SCP "$SETUP_SCRIPT" "$SERVER:/tmp/vera_server_setup.sh"
rm -f "$SETUP_SCRIPT"

say "Running server setup (3-5 min first time -- Docker image builds)..."
$SSH $SERVER "bash /tmp/vera_server_setup.sh"

echo ""
echo "============================================================"
echo "  All done! https://46-62-225-46.sslip.io"
echo ""
echo "  Mobile: open URL in Chrome -> 3-dot menu -> Add to Home Screen"
echo ""
echo "  DataGrip tunnel (open new terminal, keep it running):"
echo "  ssh -i ~/.ssh/vera_deploy_ed25519 -N -L 5433:localhost:5432 root@46.62.225.46"
echo "  Then connect DataGrip: localhost:5433 / vera / vera / <password above>"
echo "============================================================"
