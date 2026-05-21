#!/usr/bin/env bash
# Block Boss installer for Raspberry Pi OS 64-bit (ARM64).
# Installs Box64, the Bedrock Dedicated Server, Java + BedrockConnect, and Python deps.
set -euo pipefail

HOME_DIR="${BLOCK_BOSS_HOME:-$HOME/block_boss}"
BDS_DIR="$HOME_DIR/bedrock-server"
BC_DIR="$HOME_DIR/bedrockconnect"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> Block Boss install into $HOME_DIR"
mkdir -p "$HOME_DIR" "$BDS_DIR" "$BC_DIR" "$HOME_DIR/backups" "$HOME_DIR/logs"

# 1. System packages
sudo apt-get update
sudo apt-get install -y curl unzip openjdk-17-jre-headless ca-certificates

# 2. Box64 (ARM64) — confirm current install steps at https://github.com/ptitSeb/box64
if ! command -v box64 >/dev/null 2>&1; then
  echo ">> Installing Box64"
  sudo apt-get install -y git build-essential cmake
  tmp="$(mktemp -d)"; git clone https://github.com/ptitSeb/box64 "$tmp"
  cmake -S "$tmp" -B "$tmp/build" -DRPI5ARM64=1 -DCMAKE_BUILD_TYPE=RelWithDebInfo
  make -C "$tmp/build" -j4
  sudo make -C "$tmp/build" install
fi

# 3. Bedrock Dedicated Server — download the current Linux build from minecraft.net
#    (URL changes per release; set BDS_URL to the latest Linux server zip).
if [ ! -f "$BDS_DIR/bedrock_server" ]; then
  : "${BDS_URL:?Set BDS_URL to the current Bedrock Dedicated Server Linux zip URL from minecraft.net/download/server/bedrock}"
  echo ">> Downloading Bedrock Dedicated Server"
  curl -fSL "$BDS_URL" -o /tmp/bds.zip
  unzip -o /tmp/bds.zip -d "$BDS_DIR"
  chmod +x "$BDS_DIR/bedrock_server"
fi

# enable allowlist in server.properties
if grep -q '^allow-list=' "$BDS_DIR/server.properties" 2>/dev/null; then
  sed -i 's/^allow-list=.*/allow-list=true/' "$BDS_DIR/server.properties"
else
  echo 'allow-list=true' >> "$BDS_DIR/server.properties"
fi

# 4. BedrockConnect — Pugmatt's build (confirm latest jar at github.com/Pugmatt/BedrockConnect)
if [ ! -f "$BC_DIR/BedrockConnect.jar" ]; then
  : "${BEDROCKCONNECT_URL:?Set BEDROCKCONNECT_URL to the latest BedrockConnect jar release}"
  echo ">> Downloading BedrockConnect"
  curl -fSL "$BEDROCKCONNECT_URL" -o "$BC_DIR/BedrockConnect.jar"
fi
# allow binding DNS port 53 without root
sudo setcap 'cap_net_bind_service=+ep' "$(readlink -f "$(command -v java)")" || true

# 5. Python deps via uv
cd "$REPO_DIR"
command -v uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh
uv venv
uv pip install -r requirements.txt

echo ">> Done. Install the service with:"
echo "   sudo cp deploy/block-boss.service /etc/systemd/system/block-boss@.service"
echo "   sudo systemctl enable --now block-boss@$USER"
