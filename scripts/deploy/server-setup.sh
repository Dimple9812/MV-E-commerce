#!/usr/bin/env bash
# One-shot setup of MarketFlow on a fresh Ubuntu server (tested target: Oracle Cloud Always Free, Ampere A1, Ubuntu
# 22.04/24.04, 4 OCPU / 24 GB). Safe to run again: it updates the code and restarts what changed.
#
#   curl -fsSL https://raw.githubusercontent.com/Dimple9812/MV-E-commerce/main/scripts/deploy/server-setup.sh | bash
#
# What it does: installs Docker, adds swap, opens port 80 in the server's own firewall, clones the repository,
# creates .env with random secrets (only the first time), builds and starts the stack, waits for the gateway, loads
# the demo data and prints the public address. The cloud provider's firewall (Oracle: security list) must also allow
# TCP 80; that part is done in the provider's web console.
set -euo pipefail

REPO="${REPO:-https://github.com/Dimple9812/MV-E-commerce.git}"
DIR="${DIR:-$HOME/MV-E-commerce}"
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

say "Installing Docker (skipped if present)"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker >/dev/null

say "Adding 4 GB of swap (skipped if the server already has swap)"
if [ "$(swapon --show --noheadings | wc -l)" -eq 0 ]; then
  sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

say "Opening TCP port 80 in the server firewall"
# Oracle's Ubuntu images ship iptables rules that reject everything except SSH.
if ! sudo iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null; then
  sudo iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
fi
if command -v netfilter-persistent >/dev/null 2>&1; then sudo netfilter-persistent save >/dev/null 2>&1 || true; fi

say "Getting the code"
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull --ff-only; else git clone "$REPO" "$DIR"; fi
cd "$DIR"

say "Creating .env (only the first time)"
if [ ! -f .env ]; then
  ./scripts/init-env.sh
fi
set_env() {   # set_env KEY VALUE: replace or append one line of .env
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}
set_env FRONTEND_BIND 0.0.0.0
set_env FRONTEND_HOST_PORT 80

say "Building and starting MarketFlow (the first build takes 15-30 minutes)"
sudo docker compose up -d --build

say "Waiting for the gateway to become healthy"
for _ in $(seq 1 120); do
  if sudo docker compose ps gateway-service | grep -q '(healthy)'; then break; fi
  sleep 15
done
sudo docker compose ps

say "Loading demo data"
sudo docker run --rm --network host -v "$PWD":/app -w /app node:22-alpine node scripts/seed-demo.mjs \
  || echo "Seeding failed; run this script again in a few minutes (it is safe to repeat)."

IP="$(curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null || echo '<your server public IP>')"
say "Done. Open: http://$IP"
echo "Admin login: $(grep '^BOOTSTRAP_ADMIN_EMAIL=' .env | cut -d= -f2) / password: see BOOTSTRAP_ADMIN_PASSWORD in $DIR/.env"
echo "Demo users: ada@demo.marketflow.local, aurora@demo.marketflow.local ... password DemoPass2026!"
