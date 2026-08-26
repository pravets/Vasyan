#!/usr/bin/env bash
# Headless Forge 1.20.1 server for behavior tests (CI + local /tmp/fgtest).
# Usage: bash scripts/behavior/setup_server.sh <dir>
set -euo pipefail

DIR="${1:?usage: setup_server.sh <dir>}"
FORGE_VERSION="1.20.1-47.2.0"
FORGE_INSTALLER="forge-${FORGE_VERSION}-installer.jar"
FORGE_URL="https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VERSION}/${FORGE_INSTALLER}"

mkdir -p "$DIR"
cd "$DIR"

if [ ! -f "libraries/net/minecraftforge/forge/${FORGE_VERSION}/unix_args.txt" ]; then
  echo "Installing Forge ${FORGE_VERSION}..."
  if [ ! -f "$FORGE_INSTALLER" ]; then
    curl -sSL -o "$FORGE_INSTALLER" "$FORGE_URL"
  fi
  java -jar "$FORGE_INSTALLER" --installServer
fi

echo "eula=true" > eula.txt

cat > server.properties <<EOF
online-mode=false
level-type=minecraft\:flat
view-distance=4
simulation-distance=4
max-tick-time=0
spawn-protection=0
enable-rcon=true
rcon.port=25575
rcon.password=vasyan_test
motd=vasyan behavior test
EOF

mkdir -p mods config

# LLM config: point at an unreachable endpoint on purpose - the mod's
# LLMFallbackHandler kicks in and produces a deterministic "mine" task
# (no network, no keys, stable across CI and local runs).
cat > config/vasyan-common.toml <<EOF
[llm]
	providerChain = ["custom"]
	failoverRetrySeconds = 60
	timeoutSeconds = 5

[llm.members.custom]
	baseUrl = "http://127.0.0.1:1/v1"
	apiKey = "behavior-test"
	model = "behavior-test"
EOF

echo "Server ready in $DIR"
