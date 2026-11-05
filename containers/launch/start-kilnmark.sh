#!/bin/bash
# Starts Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth, waits until it answers,
# then opens it in the browser when there is a desktop. Run it from anywhere, or use the Kilnmark
# entry create-shortcut.sh puts in the applications menu. Needs Podman (with podman compose) or
# Docker (with docker compose; your user in the docker group).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

failed() {
  echo "$1" >&2
  # Opened from the menu, the terminal closes at once: keep it open to read why.
  if [ -t 0 ]; then read -r -p "Press Enter to close. " _ || true; fi
  exit 1
}

[ -f .env ] || failed "There is no .env in $PWD. Copy .env.example to .env and fill it in first (INSTALL.md, \"Install\")."

# A setting from .env, as written; empty when it is not set.
setting() { { grep -E "^$1=" .env || true; } | tail -n 1 | cut -d= -f2- | tr -d '\r'; }
port="$(setting KILNMARK_PORT)"
url="$(setting KILNMARK_URL)"
url="${url:-http://localhost:${port:-8780}}"
kc_port="$(setting KILNMARK_KEYCLOAK_PORT)"
kc_url="$(setting KILNMARK_KEYCLOAK_URL)"
kc_url="${kc_url:-http://localhost:${kc_port:-8180}}"
profiles="$(setting COMPOSE_PROFILES)"

if command -v podman > /dev/null && podman compose version > /dev/null 2>&1; then
  compose=(podman compose)
elif command -v docker > /dev/null && docker compose version > /dev/null 2>&1; then
  compose=(docker compose)
else
  failed "Neither podman compose nor docker compose was found. Install one first (INSTALL.md, \"Requirements\")."
fi

echo "Starting Kilnmark..."
"${compose[@]}" up -d || failed "Kilnmark could not start. See what went wrong with: ${compose[*]} logs"

# Waits until a page answers, for up to $3 two-second tries.
wait_for() {
  echo "Waiting for $1 at $2 ..."
  for _ in $(seq 1 "$3"); do
    if curl -k -L -s -f -o /dev/null "$2"; then return 0; fi
    sleep 2
  done
  return 1
}
wait_for Kilnmark "$url/q/health/ready" 90 \
  || failed "Kilnmark did not answer within 3 minutes. See why with: ${compose[*]} logs kilnmark"
if [[ ",$profiles," == *",auth,"* ]]; then
  wait_for Keycloak "$kc_url/realms/kilnmark" 150 \
    || failed "Keycloak did not answer within 5 minutes. See why with: ${compose[*]} logs keycloak keycloak-setup"
fi

echo "Kilnmark is ready at $url"
if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open > /dev/null; then
  xdg-open "$url" > /dev/null 2>&1 &
  sleep 1
fi
