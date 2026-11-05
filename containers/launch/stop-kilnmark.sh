#!/bin/bash
# Stops Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth. Its data stays: the
# volumes are kept, and start-kilnmark.sh starts it again where it left off.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
if command -v podman > /dev/null && podman compose version > /dev/null 2>&1; then
  compose=(podman compose)
elif command -v docker > /dev/null && docker compose version > /dev/null 2>&1; then
  compose=(docker compose)
else
  echo "Neither podman compose nor docker compose was found." >&2
  exit 1
fi
echo "Stopping Kilnmark..."
"${compose[@]}" down
echo "Kilnmark is stopped. Its data is kept."
sleep 2
