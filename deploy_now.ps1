# VERA -- one-shot local push + server deploy
# Run from repo root:  .\deploy_now.ps1
#
# Steps:
#   1. Untrack backend/.env from git (keeps API keys off GitHub)
#   2. Commit all changes and push to origin/main
#   3. Tar the repo and scp it to the server (no GitHub auth needed on server)
#   4. Copy backend/.env + Google credentials to server
#   5. Write a setup script, scp it, run it on the server
#   6. Print live URL + DataGrip connection details

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$SERVER   = "root@46.225.81.127"
$SSH_KEY  = "$env:USERPROFILE\.ssh\vera_deploy"
$SSH_OPTS = @("-i", $SSH_KEY, "-o", "StrictHostKeyChecking=no")

function Log  { param([string]$msg) Write-Host "[deploy] $msg" -ForegroundColor Cyan }
function Warn { param([string]$msg) Write-Host "[warn]   $msg" -ForegroundColor Yellow }
function Die  { param([string]$msg) Write-Host "[ERROR]  $msg" -ForegroundColor Red; exit 1 }

# --------------------------------------------------------------------------
# 0. Sanity checks
# --------------------------------------------------------------------------
Log "Checking prerequisites..."
if (-not (Test-Path $SSH_KEY))            { Die "SSH key not found at $SSH_KEY" }
if (-not (Test-Path "backend\.env"))      { Die "backend\.env not found -- run from repo root" }
if (-not (Test-Path "scripts\deploy.sh")) { Die "scripts\deploy.sh not found -- run from repo root" }
if (-not (Get-Command git  -ErrorAction SilentlyContinue)) { Die "git not in PATH" }
if (-not (Get-Command ssh  -ErrorAction SilentlyContinue)) { Die "ssh not in PATH" }
if (-not (Get-Command scp  -ErrorAction SilentlyContinue)) { Die "scp not in PATH" }
Log "All prerequisites OK"

# --------------------------------------------------------------------------
# 1. Untrack backend/.env from git
# --------------------------------------------------------------------------
Log "Untracking backend/.env from git index..."
$tracked = & git ls-files "backend/.env" 2>$null
if ($tracked) {
    & git rm --cached "backend/.env"
    Log "Removed from git index (file kept on disk)"
} else {
    Log "Already untracked -- nothing to do"
}

# --------------------------------------------------------------------------
# 2. Commit + push
# --------------------------------------------------------------------------
Log "Staging all changes..."
& git add -A

$dirty = & git status --porcelain
if ($dirty) {
    Log "Committing..."
    & git commit -m "prod: Docker stack, Caddy HTTPS, deploy script, PWA WebAPK"
    Log "Committed"
} else {
    Log "Nothing to commit -- working tree clean"
}

Log "Pushing to origin/main..."
& git push origin main
Log "GitHub is up to date"

# --------------------------------------------------------------------------
# 3. Pack repo and ship to server
# --------------------------------------------------------------------------
Log "Creating repo tarball..."
$tarball = Join-Path $env:TEMP "vera_deploy.tar.gz"
$repoParent = Split-Path (Get-Location).Path -Parent
$repoName   = Split-Path (Get-Location).Path -Leaf

& tar -czf $tarball `
    "--exclude=.git" `
    "--exclude=client/node_modules" `
    "--exclude=vera" `
    "--exclude=__pycache__" `
    "--exclude=*.pyc" `
    "--exclude=client/dist" `
    -C $repoParent $repoName

if ($LASTEXITCODE -ne 0) { Die "tar failed" }
$sizeMB = [math]::Round((Get-Item $tarball).Length / 1MB, 1)
Log "Tarball created ($sizeMB MB) -- uploading to server..."

& scp @SSH_OPTS $tarball "${SERVER}:/tmp/vera_deploy.tar.gz"
if ($LASTEXITCODE -ne 0) { Die "scp of tarball failed" }
Remove-Item $tarball -Force
Log "Repo uploaded"

# --------------------------------------------------------------------------
# 4. Copy secrets
# --------------------------------------------------------------------------
Log "Copying backend/.env to server..."
& scp @SSH_OPTS "backend\.env" "${SERVER}:/tmp/vera_backend.env"
if ($LASTEXITCODE -ne 0) { Die "scp of backend/.env failed" }

$credFile = "backend\credentials\credentials.json"
$hasCreds = Test-Path $credFile
if ($hasCreds) {
    Log "Copying Google OAuth credentials..."
    & scp @SSH_OPTS $credFile "${SERVER}:/tmp/vera_credentials.json"
    if ($LASTEXITCODE -ne 0) { Warn "Google credentials upload failed -- skipping"; $hasCreds = $false }
} else {
    Warn "No Google credentials found -- skipping (upload later if needed)"
}

# --------------------------------------------------------------------------
# 5. Write server setup script to a temp file, scp it, then run it
# --------------------------------------------------------------------------
Log "Writing server bootstrap script..."
$setupScript = Join-Path $env:TEMP "vera_server_setup.sh"

$bashScript = @'
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
    say "Opening ports 22/80/443..."
    ufw allow 22/tcp  >/dev/null 2>&1 || true
    ufw allow 80/tcp  >/dev/null 2>&1 || true
    ufw allow 443/tcp >/dev/null 2>&1 || true
fi

# -- Unpack repo -------------------------------------------------------------
REPO_DIR="/opt/VERA"
say "Unpacking repo to $REPO_DIR..."

# Back up existing .env files before wiping
if [ -d "$REPO_DIR" ]; then
    [ -f "$REPO_DIR/.env" ]         && cp "$REPO_DIR/.env"         /tmp/vera_root.env.bak  || true
    [ -f "$REPO_DIR/backend/.env" ] && cp "$REPO_DIR/backend/.env" /tmp/vera_bk.env.bak    || true
    rm -rf "$REPO_DIR"
fi

mkdir -p /opt
tar -xzf /tmp/vera_deploy.tar.gz -C /opt

# The tarball was packed from the parent dir so it unpacks as VERA/
[ -d "$REPO_DIR" ] || die "Repo not found at $REPO_DIR after unpack"
say "Repo unpacked"

cd "$REPO_DIR"

# Restore backed-up .env files if this is a re-deploy
[ -f /tmp/vera_root.env.bak ] && cp /tmp/vera_root.env.bak .env         && say "Restored root .env from backup"    || true
[ -f /tmp/vera_bk.env.bak   ] && cp /tmp/vera_bk.env.bak  backend/.env && say "Restored backend/.env from backup" || true

# -- Root .env ---------------------------------------------------------------
if [ ! -f .env ]; then
    say "Creating root .env from template..."
    cp .env.production.example .env
    PG_PWD=$(openssl rand -hex 24)
    sed -i "s|change-me-to-a-long-random-string|${PG_PWD}|" .env
    say "POSTGRES_PASSWORD set to random value"
else
    say "Root .env already present"
    if grep -q "change-me-to-a-long-random-string" .env; then
        PG_PWD=$(openssl rand -hex 24)
        sed -i "s|change-me-to-a-long-random-string|${PG_PWD}|" .env
        say "Replaced placeholder POSTGRES_PASSWORD"
    fi
fi

PG_PWD=$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)

# -- backend/.env ------------------------------------------------------------
if [ -f /tmp/vera_backend.env ]; then
    say "Installing backend/.env..."
    cp /tmp/vera_backend.env backend/.env
    sed -i "s|DATABASE_URL=.*|DATABASE_URL=postgresql+psycopg2://vera:${PG_PWD}@postgres:5432/vera|" backend/.env
    sed -i "s|ENVIRONMENT=development|ENVIRONMENT=production|"                                        backend/.env
    sed -i "s|ALLOWED_ORIGINS=.*|ALLOWED_ORIGINS=https://46-225-81-127.sslip.io|"                    backend/.env
    say "backend/.env installed and patched for production"
elif [ ! -f backend/.env ]; then
    say "WARNING: no backend/.env -- copying example; fill in API keys manually"
    cp backend/.env.example backend/.env
    sed -i "s|DATABASE_URL=.*|DATABASE_URL=postgresql+psycopg2://vera:${PG_PWD}@postgres:5432/vera|" backend/.env
fi

# -- Google credentials ------------------------------------------------------
mkdir -p backend/credentials
if [ -f /tmp/vera_credentials.json ]; then
    say "Installing Google OAuth credentials..."
    cp /tmp/vera_credentials.json backend/credentials/credentials.json
fi

# -- Deploy ------------------------------------------------------------------
say "Running deploy.sh..."
bash scripts/deploy.sh

# -- Print summary -----------------------------------------------------------
PG_PWD=$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)
echo ""
echo "============================================================"
echo "  VERA is live at: https://46-225-81-127.sslip.io"
echo "============================================================"
echo ""
echo "  DataGrip SSH tunnel -- run this on your laptop first,"
echo "  keep the terminal open while using DataGrip:"
echo ""
echo "    ssh -i ~/.ssh/hetzner_key -N -L 5433:localhost:5432 root@46.225.81.127"
echo ""
echo "  DataGrip connection settings:"
echo "    Host:      localhost"
echo "    Port:      5433"
echo "    Database:  vera"
echo "    User:      vera"
echo "    Password:  $PG_PWD"
echo ""
echo "============================================================"
'@

Set-Content -Path $setupScript -Value $bashScript -Encoding UTF8

Log "Uploading server bootstrap script..."
& scp @SSH_OPTS $setupScript "${SERVER}:/tmp/vera_server_setup.sh"
if ($LASTEXITCODE -ne 0) { Die "scp of setup script failed" }
Remove-Item $setupScript -Force

Log "Running server bootstrap (this takes 3-5 min on first run for Docker image builds)..."
& ssh @SSH_OPTS $SERVER "bash /tmp/vera_server_setup.sh"
if ($LASTEXITCODE -ne 0) { Die "Server bootstrap failed -- check output above" }

# --------------------------------------------------------------------------
# 6. Done
# --------------------------------------------------------------------------
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  Done! VERA is live at:" -ForegroundColor Green
Write-Host "  https://46-225-81-127.sslip.io" -ForegroundColor White
Write-Host "" -ForegroundColor Green
Write-Host "  Mobile install (Android Chrome):" -ForegroundColor Green
Write-Host "    1. Open the URL above on your phone" -ForegroundColor White
Write-Host "    2. Tap the 3-dot menu -> Add to Home Screen" -ForegroundColor White
Write-Host "    3. Launches as a standalone WebAPK (no browser bar)" -ForegroundColor White
Write-Host "" -ForegroundColor Green
Write-Host "  DataGrip -- open a NEW terminal and run (keep it open):" -ForegroundColor Green
Write-Host "  ssh -i ~/.ssh/hetzner_key -N -L 5433:localhost:5432 root@46.225.81.127" -ForegroundColor Yellow
Write-Host "  Then connect DataGrip to localhost:5433 / db: vera / user: vera" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Green
