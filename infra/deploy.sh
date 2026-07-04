#!/usr/bin/env bash
# Run this ON the Oracle Cloud VM (Ubuntu). Installs Docker if missing,
# pulls the latest code, and (re)starts the full stack.
set -euo pipefail

REPO_URL="https://github.com/jiseong1484/stockAnomolyDetection.git"
APP_DIR="$HOME/stockAnomolyDetection"

if ! command -v docker &>/dev/null; then
  echo "==> Installing Docker..."
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl gnupg
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo usermod -aG docker "$USER"
  echo "==> Docker installed. Log out and back in (or run 'newgrp docker') so the group change applies, then re-run this script."
  exit 0
fi

# Oracle's Ubuntu images firewall everything but SSH by default at the OS level,
# separate from the VCN Security List/NSG rule you must add in the OCI console.
if ! sudo iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null; then
  echo "==> Opening port 8080 in the local firewall..."
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
  sudo netfilter-persistent save 2>/dev/null || true
fi

if [ ! -d "$APP_DIR" ]; then
  echo "==> Cloning repo..."
  git clone "$REPO_URL" "$APP_DIR"
else
  echo "==> Pulling latest..."
  git -C "$APP_DIR" pull
fi

cd "$APP_DIR/infra"

if [ ! -f .env ]; then
  echo "ERROR: infra/.env not found on this VM. Copy .env.example to .env and fill in real secrets, then re-run." >&2
  exit 1
fi

echo "==> Building and starting stack..."
docker compose up -d --build
docker compose ps
