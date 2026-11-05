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

[ -f .env ] || failed "There is no .env in $PWD. Copy .env.example to .env and fill it in first (INSTALL.md, \"A. Install a release\")."

# A setting from .env as compose reads it: spaces around = and quotes around the value allowed, a
# comment after an unquoted value ignored; empty when it is not set.
setting() {
  local line value
  line="$( { grep -E "^[[:space:]]*$1[[:space:]]*=" .env || true; } | tail -n 1 | tr -d '\r')"
  value="${line#*=}"
  value="$(sed -e 's/^[[:space:]]*//' <<< "$value")"
  case "$value" in
    \"*) value="${value#\"}"; value="${value%%\"*}" ;;
    \'*) value="${value#\'}"; value="${value%%\'*}" ;;
    *) value="$(sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//' <<< "$value")" ;;
  esac
  printf '%s' "$value"
}
port="$(setting KILNMARK_PORT)"; port="${port:-8780}"
https_port="$(setting KILNMARK_HTTPS_PORT)"; https_port="${https_port:-8443}"
kc_port="$(setting KILNMARK_KEYCLOAK_PORT)"; kc_port="${kc_port:-8180}"
url="$(setting KILNMARK_URL)"; url="${url:-http://localhost:$port}"; url="${url%/}"
profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
# Where to ask whether it is ready: on this machine, never through a proxy. With a certificate in
# .env, Kilnmark may refuse plain HTTP from here, so ask over HTTPS.
local_url="http://localhost:$port"
[ -z "$(setting KILNMARK_TLS_CERT_FILE)" ] || local_url="https://localhost:$https_port"
auth=""
[[ ",$profiles," == *",auth,"* ]] && auth=1

if command -v podman > /dev/null && podman compose version > /dev/null 2>&1; then
  compose=(podman compose)
elif command -v docker > /dev/null && docker compose version > /dev/null 2>&1; then
  compose=(docker compose)
else
  failed "Neither podman compose nor docker compose could be used. Install one first (INSTALL.md, \"What to install first\")."
fi
# Some compose tools read COMPOSE_PROFILES from .env and some do not: name the profile too.
[ -z "$auth" ] || compose+=(--profile auth)

echo "Starting Kilnmark..."
"${compose[@]}" up -d || failed "Kilnmark could not start. See what went wrong with: ${compose[*]} logs"

# Waits until a page answers, for up to $3 two-second tries.
wait_for() {
  echo "Waiting for $1 ..."
  for _ in $(seq 1 "$3"); do
    if curl -k -L -s -f -o /dev/null "$2"; then return 0; fi
    sleep 2
  done
  return 1
}
wait_for Kilnmark "$local_url/q/health/ready" 90 \
  || failed "Kilnmark did not answer within 3 minutes. See why with: ${compose[*]} logs kilnmark"
if [ -n "$auth" ]; then
  wait_for Keycloak "http://localhost:$kc_port/realms/kilnmark" 150 \
    || failed "Keycloak did not answer within 5 minutes. See why with: ${compose[*]} logs keycloak keycloak-setup"
fi

echo "Kilnmark is ready at $url"
if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open > /dev/null; then
  # Apart from this terminal, so the browser stays when it closes.
  setsid xdg-open "$url" > /dev/null 2>&1 < /dev/null &
  sleep 1
fi
