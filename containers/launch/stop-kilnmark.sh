#!/bin/bash
# Stops Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth. Its data stays: the
# volumes are kept, and start-kilnmark.sh starts it again where it left off.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

failed() {
  echo "$1" >&2
  # Opened from the menu, the terminal closes at once: keep it open to read why.
  if [ -t 0 ]; then read -r -p "Press Enter to close. " _ || true; fi
  exit 1
}

if command -v podman > /dev/null && podman compose version > /dev/null 2>&1; then
  compose=(podman compose)
elif command -v docker > /dev/null && docker compose version > /dev/null 2>&1; then
  compose=(docker compose)
else
  failed "Neither podman compose nor docker compose could be used."
fi
# Keycloak's services stop too when the profile is on (some compose tools need it named).
profiles="$( { grep -E '^[[:space:]]*COMPOSE_PROFILES[[:space:]]*=' .env 2> /dev/null || true; } | tail -n 1 \
  | cut -d= -f2- | sed -e 's/[[:space:]]#.*$//' | tr -d '\r"'"'"'[:space:]')"
[[ ",$profiles," == *",auth,"* ]] && compose+=(--profile auth)

echo "Stopping Kilnmark..."
"${compose[@]}" down || failed "Kilnmark could not be stopped cleanly. See: ${compose[*]} ps"
echo "Kilnmark is stopped. Its data is kept."
sleep 2
