#!/bin/bash
# =============================================================================
# deploy_update.sh — Pull and restart the app container from ECR
#
# Usage:
#   ./deploy_update.sh <image-tag>
#
# Example:
#   ./deploy_update.sh v1.01
#
# Prerequisites:
#   - docker-compose.prod.yml already exists at /home/bitnami/
#   - AWS CLI configured with the ecr-puller profile
#   - setup_server.sh has been run once before the first deploy
# =============================================================================
set -euo pipefail

REGISTRY="${ECR_REGISTRY:?ECR_REGISTRY is not set (e.g. export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com)}"
REPO="${ECR_REPO:?ECR_REPO is not set (e.g. export ECR_REPO=your-org/your-app)}"
REGION="${AWS_REGION:?AWS_REGION is not set (e.g. export AWS_REGION=us-east-1)}"
TAG="${1:?Usage: ./deploy_update.sh <image-tag>  (e.g. ./deploy_update.sh v1.01)}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
ok()  { echo "[$(date '+%H:%M:%S')] ✓ $*"; }

COMPOSE_FILE="/home/bitnami/docker-compose.prod.yml"

# ─── 1. ECR login ─────────────────────────────────────────────────────────────
log "Authenticating to ECR..."
aws ecr get-login-password --region "$REGION" --profile ecr-puller \
    | docker login --username AWS --password-stdin "$REGISTRY"
ok "ECR auth OK"

# ─── 2. Update image tag in compose file ──────────────────────────────────────
log "Setting image tag to $TAG in $COMPOSE_FILE..."
sed -i "s|$REGISTRY/$REPO:.*|$REGISTRY/$REPO:$TAG|" "$COMPOSE_FILE"
ok "Image tag updated"

# ─── 3. Pull + restart app only ───────────────────────────────────────────────
log "Pulling new image (tag: $TAG)..."
docker-compose -f "$COMPOSE_FILE" pull app

log "Restarting app container..."
docker-compose -f "$COMPOSE_FILE" up -d --no-deps app
ok "Container restarted"

# ─── 4. Cleanup ───────────────────────────────────────────────────────────────
docker image prune -f

# ─── 5. Tail logs ─────────────────────────────────────────────────────────────
echo ""
ok "Deploy complete — tailing logs (Ctrl+C to stop)"
sleep 2
docker logs -f iot-app
