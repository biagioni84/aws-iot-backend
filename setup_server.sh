#!/bin/bash
# =============================================================================
# setup_server.sh — One-time server configuration for tunnel support
#
# Run this ONCE after the first deploy, or after the server is rebuilt.
# Safe to re-run (all steps are idempotent).
#
# What it does:
#   1. Detects the spring user GID from the deployed image
#   2. Finds or creates a shared group matching that GID
#   3. Adds tunneluser to it and sets authorized_keys permissions
#   4. Configures sshd for tunneluser
# =============================================================================
set -euo pipefail

REGISTRY="${ECR_REGISTRY:?ECR_REGISTRY is not set (e.g. export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com)}"
REPO="${ECR_REPO:?ECR_REPO is not set (e.g. export ECR_REPO=your-org/your-app)}"
REGION="${AWS_REGION:?AWS_REGION is not set (e.g. export AWS_REGION=us-east-1)}"
TAG="${1:?Usage: ./setup_server.sh <image-tag>  (e.g. ./setup_server.sh v1.01)}"
IMAGE="$REGISTRY/$REPO:$TAG"
TUNNEL_USER="tunneluser"
SSHD_CONF="/etc/ssh/sshd_config"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
ok()  { echo "[$(date '+%H:%M:%S')] ✓ $*"; }

# ─── 1. ECR login ────────────────────────────────────────────────────────────
log "Authenticating to ECR..."
aws ecr get-login-password --region "$REGION" --profile ecr-puller \
    | docker login --username AWS --password-stdin "$REGISTRY"
ok "ECR auth OK"

# ─── 2. Detect spring GID ────────────────────────────────────────────────────
log "Detecting spring GID from $IMAGE..."
SPRING_GID=$(docker run --rm --entrypoint id "$IMAGE" -g spring)
ok "spring GID = $SPRING_GID"

# ─── 3. Group setup ──────────────────────────────────────────────────────────
log "Configuring shared group for GID=$SPRING_GID..."

# If GID is already taken by an existing group, reuse it instead of failing
SHARED_GROUP=$(getent group | awk -F: "\$3==$SPRING_GID {print \$1}" | head -1)

if [ -n "$SHARED_GROUP" ]; then
    ok "GID $SPRING_GID already owned by group '$SHARED_GROUP' — reusing it"
else
    sudo groupadd -g "$SPRING_GID" springapp
    SHARED_GROUP="springapp"
    ok "Created group springapp (gid=$SPRING_GID)"
fi

if id -nG "$TUNNEL_USER" | grep -qw "$SHARED_GROUP"; then
    ok "$TUNNEL_USER already in $SHARED_GROUP"
else
    sudo usermod -aG "$SHARED_GROUP" "$TUNNEL_USER"
    ok "Added $TUNNEL_USER to $SHARED_GROUP"
fi

# ─── 4. authorized_keys permissions ─────────────────────────────────────────
log "Configuring authorized_keys..."

sudo mkdir -p "/home/$TUNNEL_USER/.ssh"
sudo touch "/home/$TUNNEL_USER/.ssh/authorized_keys"
sudo chown "$TUNNEL_USER:$SHARED_GROUP" \
    "/home/$TUNNEL_USER/.ssh" \
    "/home/$TUNNEL_USER/.ssh/authorized_keys"
sudo chmod 770 "/home/$TUNNEL_USER/.ssh"
sudo chmod 660 "/home/$TUNNEL_USER/.ssh/authorized_keys"
ok "Permissions set (dir=770, file=660, group=$SHARED_GROUP)"

# ─── 5. sshd_config ──────────────────────────────────────────────────────────
log "Configuring sshd..."

if grep -q "Match User $TUNNEL_USER" "$SSHD_CONF"; then
    ok "sshd Match User block already present"
else
    sudo tee -a "$SSHD_CONF" > /dev/null << 'EOF'

Match User tunneluser
    StrictModes no
    AllowTcpForwarding yes
    GatewayPorts clientspecified
    PermitTTY no
    ForceCommand echo 'Tunnel only'
EOF
    ok "Added Match User tunneluser to sshd_config"
fi

# Reload sshd — try both service names (sshd on RHEL, ssh on Debian/Ubuntu)
sudo systemctl reload sshd 2>/dev/null || sudo systemctl reload ssh 2>/dev/null
ok "sshd reloaded"

echo ""
ok "Server setup complete. You can now run deploy_update.sh."
