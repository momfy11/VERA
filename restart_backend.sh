#!/usr/bin/env bash
# Quick backend restart — copies .env and restarts the backend container only.
# Use this after changing API keys or backend config. No image rebuild.
# Usage: bash restart_backend.sh
set -euo pipefail

SERVER="root@46.62.225.46"
SSH_KEY="$HOME/.ssh/vera_deploy_ed25519"
SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no"
SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=no"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

echo "[restart] Copying backend/.env to server..."
$SCP backend/.env "$SERVER:/opt/VERA/backend/.env"

echo "[restart] Restarting backend container..."
$SSH "$SERVER" "cd /opt/VERA && $COMPOSE restart backend"

echo "[restart] Tailing logs (Ctrl+C to stop)..."
$SSH "$SERVER" "cd /opt/VERA && $COMPOSE logs --tail=30 -f backend"
