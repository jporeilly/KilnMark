#!/bin/bash
# Kilnmark's control script for Linux (INSTALL.md, "A. Install a release"; ADR 0044). One script
# installs, starts, stops, upgrades and backs up Kilnmark with Podman or Docker:
#   sudo ./kilnmark.sh prepare [--user kilnmark]      once: the service account and /opt/kilnmark
#   ./kilnmark.sh install [--root DIR] [--offline IMAGES] [--auth] [--starter] [--skip-verify]   as that account
#   ./kilnmark.sh up | down | restart | status | logs | check | backup | restore | upgrade | ...
#   ./kilnmark.sh help
# Run with no command, it starts Kilnmark (up), so a menu entry or a double-click starts it.
# The install root holds versions/<version>/ (a release, never edited), current (which version
# runs), config/.env (this site's settings and secrets), certs/, backups/ and logs/. Data stays in
# the compose project's volumes (kilnmark_pgdata, kilnmark_keys, ...). No command prints a secret.
set -euo pipefail
umask 077

here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
registry="ghcr.io/jporeilly"
# The compose project: kilnmark, unless KILNMARK_PROJECT names another (a second install on one
# machine, or a developer's machine whose source checkout already uses kilnmark).
project="${KILNMARK_PROJECT:-kilnmark}"
default_root="/opt/kilnmark"

# Rootless Podman needs the account's systemd session; sudo -iu and su do not set it up, but its
# runtime folder exists once lingering is on (prepare turns it on).
if [ "$(id -u)" -ne 0 ] && [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$(id -u)" ]; then
  export XDG_RUNTIME_DIR="/run/user/$(id -u)"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
fi

die() { echo "Error: $*" >&2; exit 1; }
note() { echo "$*"; }

usage() {
  cat <<'EOF'
Kilnmark control script.

  prepare [--user NAME] [--root DIR] [--engine podman|docker]
                       Once, with sudo: make the service account (kilnmark) that runs Kilnmark,
                       give it the container engine, and make the install root its own.
  install [--root DIR] [--offline IMAGES.tar.gz] [--auth] [--starter] [--build] [--skip-verify] [--no-start]
                       First setup: checks, folders, settings with new secrets, images, start.
                       --starter then turns the Starter Pack on (example data; with --auth,
                       example people too): for trials, demos and training, never production.
  up [--no-browser]    Start Kilnmark (and Keycloak when .env turns on the auth profile); wait
                       until it answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything, or one service.
  status               Version, containers, health, ports and the last backup.
  logs [SERVICE] [-f]  The last 200 lines of the logs, or follow them.
  check                Check the prerequisites and the settings, changing nothing.
  verify FILE [--key PUB]   Check a downloaded file's signature (cosign) against Kilnmark's key.
  load-images FILE [--skip-verify]   Load the offline images file (podman/docker load).
  pull [--skip-verify] Pull this version's images from ghcr.io and check their signatures.
  build                Build the images here from containers/, instead of pulling or loading.
  backup [DIR]         Back up the database and the keys (default: ROOT/backups).
  restore BACKUP [--yes] [--without-keys]   Put Kilnmark back to a backup.
  upgrade ARCHIVE [--offline IMAGES] [--no-backup] [--skip-verify]
                       Back up, unpack the new version beside this one, and start it.
  uninstall [--purge]  Stop Kilnmark. --purge also deletes its volumes (all data) and images.
  version | help
EOF
}

# --- where things are ------------------------------------------------------------------------

# The install root: this script's folder when it holds versions/, else two folders up when this
# script runs from versions/<version>/, else (a release folder not yet installed) none.
find_root() {
  if [ -n "${KILNMARK_ROOT:-}" ]; then printf '%s' "$KILNMARK_ROOT"; return; fi
  if [ -d "$here/versions" ]; then printf '%s' "$here"; return; fi
  if [ "$(basename "$(dirname "$here")")" = versions ]; then (cd "$here/../.." && pwd); return; fi
  printf ''
}
root="$(find_root)"

# The release a folder holds, from its compose.yaml's server image.
version_of() {
  sed -n 's/^ *image: .*\/kilnmark-server:\([^ ]*\) *$/\1/p' "$1/compose.yaml" 2>/dev/null | head -n 1
}

current_version() {
  [ -n "$root" ] || die "Kilnmark is not installed here. Run: ./kilnmark.sh install"
  [ -L "$root/current" ] || [ -d "$root/current" ] || die "No version is installed in $root."
  version_of "$root/current"
}

# --- engine and compose ------------------------------------------------------------------------

# Podman says on every compose command that it runs an external compose tool: not news here.
export PODMAN_COMPOSE_WARNING_LOGS=false

engine=""
pick_engine() {
  [ -z "$engine" ] || return 0
  if [ -n "${KILNMARK_ENGINE:-}" ]; then engine="$KILNMARK_ENGINE"
  elif command -v podman > /dev/null 2>&1; then engine=podman
  elif command -v docker > /dev/null 2>&1; then engine=docker
  else die "Neither Podman nor Docker is installed. Install one first (INSTALL.md, \"What to install first\")."
  fi
  "$engine" compose version > /dev/null 2>&1 \
    || die "'$engine compose' does not work. Install the compose plugin (Docker) or podman-compose (Podman)."
}

# A setting from config/.env as compose reads it; empty when it is not set.
setting() {
  local line value file="$root/config/.env"
  [ -f "$file" ] || { printf ''; return; }
  line="$( { grep -E "^[[:space:]]*$1[[:space:]]*=" "$file" || true; } | tail -n 1 | tr -d '\r')"
  value="${line#*=}"
  value="$(sed -e 's/^[[:space:]]*//' <<< "$value")"
  case "$value" in
    \"*) value="${value#\"}"; value="${value%%\"*}" ;;
    \'*) value="${value#\'}"; value="${value%%\'*}" ;;
    *) value="$(sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//' <<< "$value")" ;;
  esac
  printf '%s' "$value"
}

# compose, always the kilnmark project, with this site's .env and the current version's file.
compose=()
set_compose() {
  pick_engine
  # The real folder, not the current symlink: compose resolves relative paths in it (the
  # certificate folder, ../../certs/https) without following symlinks.
  local dir; dir="$(readlink -f "$root/current")"
  [ -f "$root/config/.env" ] || die "There is no $root/config/.env. Run: ./kilnmark.sh install"
  compose=("$engine" compose -p "$project" --env-file "$root/config/.env" -f "$dir/compose.yaml")
  local profiles; profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  # Some compose tools read COMPOSE_PROFILES from the env file and some do not: name each one.
  local p; IFS=',' read -r -a list <<< "$profiles"
  for p in "${list[@]:-}"; do [ -z "$p" ] || compose+=(--profile "$p"); done
}

image_names() { echo "$registry/kilnmark-server:$1 $registry/kilnmark-postgres:$1"; }

images_present() {
  local i
  for i in $(image_names "$1"); do "$engine" image inspect "$i" > /dev/null 2>&1 || return 1; done
}

# --- logging -----------------------------------------------------------------------------------

start_log() {
  [ -n "$root" ] && [ -d "$root/logs" ] || return 0
  local log; log="$root/logs/$1-$(date -u +%Y%m%dT%H%M%SZ).log"
  exec > >(tee -a "$log") 2>&1
}

# --- signatures --------------------------------------------------------------------------------

# Kilnmark's public key: --key, else KILNMARK_COSIGN_PUB, else the installed version's cosign.pub.
pubkey() {
  if [ -n "${key_override:-}" ]; then printf '%s' "$key_override"
  elif [ -n "${KILNMARK_COSIGN_PUB:-}" ]; then printf '%s' "$KILNMARK_COSIGN_PUB"
  elif [ -n "$root" ] && [ -f "$root/current/cosign.pub" ]; then printf '%s' "$root/current/cosign.pub"
  else printf '%s' "$here/cosign.pub"
  fi
}

cosign_bin() {
  if command -v cosign > /dev/null 2>&1; then echo cosign; return 0; fi
  return 1
}

# Checks FILE against FILE.sigstore.json with Kilnmark's public key. With --skip-verify, says so.
verify_file() {
  local file="$1" cos
  if [ "${skip_verify:-false}" = true ]; then
    echo "Not checking the signature of $(basename "$file") (--skip-verify)."
    return 0
  fi
  cos="$(cosign_bin)" || die "cosign 3 or later is needed to check $(basename "$file") (https://docs.sigstore.dev/cosign/system_config/installation/). Install it, or pass --skip-verify to go on unchecked."
  [ -f "$file.sigstore.json" ] || die "$(basename "$file").sigstore.json, its signature, is not beside it. Download it too, or pass --skip-verify."
  [ -f "$(pubkey)" ] || die "Kilnmark's public key, cosign.pub, was not found."
  "$cos" verify-blob --key "$(pubkey)" --insecure-ignore-tlog=true --bundle "$file.sigstore.json" "$file" > /dev/null 2>&1 \
    || die "$(basename "$file") does not match its signature: it is not the file Kilnmark signed. Nothing was changed."
  echo "Signature checked: $(basename "$file")"
}

# --- images ------------------------------------------------------------------------------------

load_images() {
  local file="$1"
  [ -f "$file" ] || die "There is no file $file."
  verify_file "$file"
  pick_engine
  echo "Loading the images from $(basename "$file") ..."
  "$engine" load -i "$file" || die "The images could not be loaded."
}

pull_images() {
  local v="$1" i digest cos
  pick_engine
  for i in $(image_names "$v"); do
    echo "Pulling $i ..."
    "$engine" pull "$i" || die "$i could not be pulled. Without internet access, use the offline images file: load-images."
    if [ "${skip_verify:-false}" = true ]; then continue; fi
    cos="$(cosign_bin)" || die "cosign 3 or later is needed to check the images' signatures. Install it, or pass --skip-verify."
    digest="$("$engine" image inspect --format '{{index .RepoDigests 0}}' "$i" 2>/dev/null || true)"
    [ -n "$digest" ] || die "Cannot tell the digest of $i, so its signature cannot be checked."
    "$cos" verify --key "$(pubkey)" --insecure-ignore-tlog=true "$digest" > /dev/null 2>&1 \
      || die "$i does not carry Kilnmark's signature. Do not run it; remove it with: $engine rmi $i"
    echo "Signature checked: $i"
  done
}

build_images() {
  local v="$1" dir="$root/versions/$1"
  pick_engine
  echo "Building the database image ..."
  "$engine" build -t "$registry/kilnmark-postgres:$v" -f "$dir/containers/postgres/Containerfile" "$dir/containers/postgres" \
    || die "The database image could not be built."
  echo "Building the server image ..."
  "$engine" build -t "$registry/kilnmark-server:$v" --build-arg APP_DIR=server -f "$dir/containers/server/Containerfile" "$dir" \
    || die "The server image could not be built."
}

# --- checks ------------------------------------------------------------------------------------

problems=()
warnings=()
# A port as config/.env sets it, else as the environment does (for install), else the default.
port() {
  local v=""; [ -n "$root" ] && v="$(setting "$1")"
  [ -n "$v" ] || v="${!1:-}"
  printf '%s' "${v:-$2}"
}
port_busy() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }

check_prerequisites() {
  local running="${1:-false}" v mem disk ports p
  if command -v podman > /dev/null 2>&1 || command -v docker > /dev/null 2>&1; then
    pick_engine
    if [ "$engine" = podman ]; then
      v="$(podman version --format '{{.Client.Version}}' 2> /dev/null || echo 0)"
      [ "${v%%.*}" -ge 5 ] 2> /dev/null || problems+=("Podman $v is too old: Kilnmark needs Podman 5 or later.")
    else
      docker info > /dev/null 2>&1 || problems+=("Docker is installed but not running, or your user may not use it (add it to the docker group).")
    fi
    mem="$("$engine" info --format '{{.Host.MemTotal}}' 2> /dev/null || "$engine" info --format '{{.MemTotal}}' 2> /dev/null || echo 0)"
    if [ "${mem:-0}" -gt 0 ] 2> /dev/null && [ "$mem" -lt $((4 * 1024 * 1024 * 1024 - 268435456)) ]; then
      warnings+=("The container engine has $((mem / 1024 / 1024)) MB of memory; Kilnmark wants 4 GB (5 GB with Keycloak).")
    fi
  else
    problems+=("Install Podman 5 or later (with podman-compose), or Docker with its compose plugin.")
  fi
  command -v curl > /dev/null 2>&1 || problems+=("Install curl: the health check uses it.")
  command -v tar > /dev/null 2>&1 || problems+=("Install tar.")
  cosign_bin > /dev/null || warnings+=("cosign is not installed: downloads and images cannot be checked against Kilnmark's signature (--skip-verify goes on without).")
  disk="$(df -Pk "${root:-$default_root}" 2> /dev/null | awk 'NR==2 {print $4}' || echo 0)"
  if [ "${disk:-0}" -gt 0 ] 2> /dev/null && [ "$disk" -lt $((10 * 1024 * 1024)) ]; then
    warnings+=("Less than 10 GB free for Kilnmark's folder; images, data and backups need more.")
  fi
  if [ "$running" = false ]; then
    ports="$(port KILNMARK_PORT 8780) $(port KILNMARK_HTTPS_PORT 8443) $(port KILNMARK_DB_PORT 5442)"
    for p in $ports; do port_busy "$p" && problems+=("Port $p is in use: free it, or set another port in config/.env (KILNMARK_PORT, KILNMARK_HTTPS_PORT, KILNMARK_DB_PORT).")
    done
  fi
  return 0
}

report_checks() {
  local w p
  for w in "${warnings[@]:-}"; do [ -z "$w" ] || echo "Warning: $w"; done
  if [ "${#problems[@]}" -gt 0 ]; then
    for p in "${problems[@]}"; do echo "Problem: $p" >&2; done
    return 1
  fi
  return 0
}

# Settings this version's .env.example names that config/.env does not mention at all.
new_settings() {
  local example="$1" env="$root/config/.env" k
  [ -f "$example" ] && [ -f "$env" ] || return 0
  for k in $(sed -n 's/^#\{0,1\} *\([A-Z][A-Z0-9_]*\)=.*/\1/p' "$example" | sort -u); do
    grep -qE "^#? *$k=" "$env" || echo "$k"
  done
}

# --- secrets and settings ----------------------------------------------------------------------

random_value() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }

# Sets KEY=VALUE in the file: replaces a "KEY=" or "# KEY=" line, or adds one at the end.
set_line() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXX")"
  # The value goes through the environment, never the command line, where other users could read it.
  KM_VALUE="$value" awk -v k="$key" '
    BEGIN { done = 0; v = ENVIRON["KM_VALUE"] }
    !done && ($0 ~ "^" k "=" || $0 ~ "^# *" k "=") { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }' "$file" > "$tmp"
  chmod 600 "$tmp" && mv "$tmp" "$file"
}

make_settings() {
  local example="$1" env="$root/config/.env"
  cp "$example" "$root/config/.env.example"
  if [ -f "$env" ]; then
    echo "Keeping the settings in $env."
    return 0
  fi
  cp "$example" "$env" && chmod 600 "$env"
  set_line "$env" KILNMARK_DB_PASSWORD "$(random_value)"
  set_line "$env" KILNMARK_ADMIN_TOKEN "km_$(random_value)"
  set_line "$env" KILNMARK_SAMPLES_DB_PASSWORD "$(random_value)"
  # Relative to compose.yaml in versions/<version>/, so the root can move.
  set_line "$env" KILNMARK_CERTS_DIR ../../certs/https
  # Other ports, when install was run with them set (KILNMARK_PORT=9780 ./kilnmark.sh install).
  local k
  for k in KILNMARK_PORT KILNMARK_HTTPS_PORT KILNMARK_DB_PORT KILNMARK_KEYCLOAK_PORT; do
    [ -z "${!k:-}" ] || set_line "$env" "$k" "${!k}"
  done
  if [ "${with_auth:-false}" = true ]; then
    set_line "$env" COMPOSE_PROFILES auth
    set_line "$env" KILNMARK_KEYCLOAK_DB_PASSWORD "$(random_value)"
    set_line "$env" KILNMARK_KEYCLOAK_ADMIN_PASSWORD "$(random_value)"
    set_line "$env" KILNMARK_OIDC_ENABLED true
    set_line "$env" KILNMARK_OIDC_ISSUER "http://keycloak:8080/realms/kilnmark"
    set_line "$env" KILNMARK_OIDC_CLIENT_ID kilnmark
    set_line "$env" KILNMARK_OIDC_CLIENT_SECRET "$(random_value)"
    set_line "$env" KILNMARK_OIDC_PROVIDER_NAME Keycloak
    set_line "$env" KILNMARK_OIDC_VIEWER_GROUPS kilnmark-viewers
    set_line "$env" KILNMARK_OIDC_STEWARD_GROUPS kilnmark-stewards
    set_line "$env" KILNMARK_OIDC_DESIGNER_GROUPS kilnmark-designers
    set_line "$env" KILNMARK_OIDC_ADMIN_GROUPS kilnmark-admins
    set_line "$env" KILNMARK_OIDC_UNMASK_GROUPS kilnmark-sensitive-data
    # Other ports: where browsers reach Keycloak and Kilnmark follows them, so sign-in never sends
    # people to another install's Keycloak on the default port.
    [ -z "${KILNMARK_KEYCLOAK_PORT:-}" ] || set_line "$env" KILNMARK_KEYCLOAK_URL "http://localhost:$KILNMARK_KEYCLOAK_PORT"
    [ -z "${KILNMARK_PORT:-}" ] || set_line "$env" KILNMARK_URL "http://localhost:$KILNMARK_PORT"
    # A trial install with Keycloak: the Starter Pack may add its example people (ADR 0051).
    [ "${with_starter:-false}" = true ] && set_line "$env" KILNMARK_STARTER allowed
  fi
  echo "Wrote $env with new secrets (readable by you only)."
}

# Secrets later versions added, and the Starter Pack's service account (ADR 0051), as config/.env
# now asks: run before every start, so upgrade and up keep them right. Each new secret is random.
# KILNMARK_KEYCLOAK_STARTER_SECRET is set only while KILNMARK_STARTER=allowed, with Keycloak on and
# the install not marked production, and emptied otherwise, so it reaches Kilnmark only then.
ensure_settings() {
  local env="$root/config/.env" profiles want=false
  [ -f "$env" ] || return 0
  if [ -z "$(setting KILNMARK_SAMPLES_DB_PASSWORD)" ]; then
    set_line "$env" KILNMARK_SAMPLES_DB_PASSWORD "$(random_value)"
    echo "Added KILNMARK_SAMPLES_DB_PASSWORD to config/.env: the Starter Pack's database login (ADR 0051)."
  fi
  profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  if [ "$(setting KILNMARK_STARTER)" = allowed ]; then
    if [ "$(setting KILNMARK_PRODUCTION)" = true ]; then
      echo "Warning: KILNMARK_STARTER=allowed is ignored: this install is marked production (KILNMARK_PRODUCTION=true)."
    elif [[ ",$profiles," != *",auth,"* ]]; then
      echo "Note: KILNMARK_STARTER=allowed needs the bundled Keycloak (the auth profile); the example people are not managed."
    else
      want=true
    fi
  fi
  if $want; then
    [ -n "$(setting KILNMARK_KEYCLOAK_STARTER_SECRET)" ] || set_line "$env" KILNMARK_KEYCLOAK_STARTER_SECRET "$(random_value)"
  elif [ -n "$(setting KILNMARK_KEYCLOAK_STARTER_SECRET)" ]; then
    set_line "$env" KILNMARK_KEYCLOAK_STARTER_SECRET ""
    echo "Emptied KILNMARK_KEYCLOAK_STARTER_SECRET: the Starter Pack's service account is removed from Keycloak at this start."
  fi
}

# --- start, stop, health -----------------------------------------------------------------------

wait_for() {
  local what="$1" url="$2" tries="$3" i
  echo "Waiting for $what ..."
  for i in $(seq 1 "$tries"); do
    if curl -k -L -s -f -o /dev/null "$url"; then return 0; fi
    sleep 2
  done
  return 1
}

local_url() {
  local port https; port="$(setting KILNMARK_PORT)"; https="$(setting KILNMARK_HTTPS_PORT)"
  if [ -n "$(setting KILNMARK_TLS_CERT_FILE)" ]; then echo "https://localhost:${https:-8443}"
  else echo "http://localhost:${port:-8780}"; fi
}

# Runs compose without showing what it prints (container ids and names) unless it fails, when
# all of it is shown. For up and down, whose own lines say what happened.
compose_quietly() {
  local out
  if out="$("${compose[@]}" "$@" 2>&1)"; then return 0; fi
  printf '%s\n' "$out" >&2
  return 1
}

cmd_up() {
  local browser=true v url profiles
  for a in "$@"; do case "$a" in --no-browser) browser=false ;; *) die "Unknown option $a." ;; esac; done
  v="$(current_version)"; ensure_settings; set_compose
  images_present "$v" || die "The images of Kilnmark $v are not here. Get them with one of: ./kilnmark.sh pull, ./kilnmark.sh load-images FILE, ./kilnmark.sh build"
  echo "Starting Kilnmark $v ..."
  compose_quietly up -d || die "Kilnmark could not start. See why with: ./kilnmark.sh logs"
  wait_for Kilnmark "$(local_url)/q/health/ready" 90 \
    || die "Kilnmark did not answer within 3 minutes. See why with: ./kilnmark.sh logs kilnmark"
  profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  if [[ ",$profiles," == *",auth,"* ]]; then
    local kc; kc="$(setting KILNMARK_KEYCLOAK_PORT)"
    wait_for Keycloak "http://localhost:${kc:-8180}/realms/kilnmark" 150 \
      || die "Keycloak did not answer within 5 minutes. See why with: ./kilnmark.sh logs keycloak"
  fi
  url="$(setting KILNMARK_URL)"; url="${url:-$(local_url)}"; url="${url%/}"
  echo "Kilnmark $v is ready at $url"
  if $browser && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open > /dev/null; then
    setsid xdg-open "$url" > /dev/null 2>&1 < /dev/null &
    sleep 1
  fi
}

cmd_down() {
  set_compose
  echo "Stopping Kilnmark ..."
  compose_quietly down || die "Kilnmark could not be stopped cleanly. See: ./kilnmark.sh status"
  echo "Kilnmark is stopped. Its data is kept."
}

cmd_status() {
  local v last
  v="$(current_version)"; set_compose
  echo "Kilnmark $v in $root, with $engine"
  "${compose[@]}" ps || true
  if curl -k -s -f -o /dev/null "$(local_url)/q/health/ready"; then echo "Health: ready at $(local_url)"
  else echo "Health: not answering at $(local_url)"; fi
  last="$(ls -1d "$root"/backups/kilnmark-* 2> /dev/null | tail -n 1 || true)"
  if [ -n "$last" ]; then echo "Last backup: $(basename "$last")"; else echo "Last backup: none in $root/backups"; fi
}

# --- install, upgrade, uninstall ---------------------------------------------------------------

make_folders() {
  mkdir -p "$root/versions" "$root/config" "$root/certs/https" "$root/backups" "$root/logs" \
    || die "Cannot create $root. Prepare it first (sudo ./kilnmark.sh prepare), or choose another with --root."
  chmod 700 "$root/config" "$root/certs" "$root/backups"
}

# Copies a release folder into versions/<version>, unless that version is there already.
place_release() {
  local src="$1" v="$2"
  if [ -d "$root/versions/$v" ]; then
    echo "Kilnmark $v is already in $root/versions/$v."
  else
    cp -a "$src" "$root/versions/$v.part" && mv "$root/versions/$v.part" "$root/versions/$v"
    echo "Placed Kilnmark $v in $root/versions/$v."
  fi
  use_version "$v"
}

# Makes VERSION the running one: current points at it, and the control script and the menu
# launchers at the root are its own, so shortcuts made from the root survive upgrades.
use_version() {
  local f
  # Copied beside and renamed over: a running script must never be rewritten in place, since bash
  # goes on reading it from where it was.
  for f in kilnmark.sh start-kilnmark.sh stop-kilnmark.sh create-shortcut.sh kilnmark.png; do
    if [ -f "$root/versions/$1/$f" ]; then
      cp "$root/versions/$1/$f" "$root/.$f.new" && mv -f "$root/.$f.new" "$root/$f"
    fi
  done
  chmod 755 "$root"/*.sh
  ln -sfn "versions/$1" "$root/current"
}

get_images() {
  local v="$1"
  if [ -n "${offline:-}" ]; then load_images "$offline"
  elif images_present "$v"; then echo "The images of Kilnmark $v are already here."
  elif [ "${build:-false}" = true ]; then build_images "$v"
  else pull_images "$v"
  fi
  images_present "$v" || die "The images of Kilnmark $v are still missing after that."
}

# Rootless Podman starts containers at boot only through its podman-restart user service.
boot_start() {
  [ "$engine" = podman ] && [ "$(id -u)" -ne 0 ] || return 0
  if systemctl --user enable podman-restart.service > /dev/null 2>&1; then
    if [ "$(loginctl show-user "$(id -un)" -p Linger --value 2> /dev/null)" != yes ]; then
      echo "To start Kilnmark at boot without you signing in, run once: sudo loginctl enable-linger $(id -un)"
    fi
  else
    echo "Note: Kilnmark will not start by itself at boot (podman-restart.service could not be enabled)."
  fi
}

# Run once as root: the account Kilnmark runs as, with just what it needs, and its install root.
cmd_prepare() {
  local user=kilnmark eng=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --user) user="${2:?--user needs a name}"; shift ;;
      --root) root="${2:?--root needs a folder}"; shift ;;
      --engine) eng="${2:?--engine needs podman or docker}"; shift ;;
      *) die "Unknown option $1. See: ./kilnmark.sh help" ;;
    esac
    shift
  done
  [ "$(id -u)" -eq 0 ] || die "Run prepare with sudo: sudo ./kilnmark.sh prepare"
  [ -f "$here/compose.yaml" ] || die "Run prepare from the unpacked release folder, kilnmark-<version>."
  local v; v="$(version_of "$here")"
  root="${root:-$default_root}"
  case "$root" in *[[:space:]]*|"/") die "Choose a folder without spaces for --root." ;; /*) ;; *) die "--root must be a full path." ;; esac
  # Checked before anything is made, so a refused prepare leaves no account behind.
  if [ -e "$root" ] || [ -L "$root" ]; then
    die "$root exists already. prepare makes a new install root only; upgrade an installed Kilnmark as $user (./kilnmark.sh upgrade), or choose another --root."
  fi
  if [ -z "$eng" ]; then
    if command -v podman > /dev/null 2>&1; then eng=podman
    elif command -v docker > /dev/null 2>&1; then eng=docker
    else die "Install Podman 5 or later, or Docker with its compose plugin, first."; fi
  fi
  if id "$user" > /dev/null 2>&1; then
    echo "The account $user exists already: keeping it."
  else
    useradd --system --create-home --home-dir "/var/lib/$user" --shell /bin/bash --comment "Kilnmark" "$user" \
      || die "The account $user could not be made."
    echo "Made the account $user (no password: reach it with sudo -iu $user)."
  fi
  if [ "$eng" = docker ]; then
    usermod -aG docker "$user" || die "Could not add $user to the docker group."
    echo "$user may use Docker (the docker group). Note: the docker group can act as root on this"
    echo "machine; prefer rootless Podman where that matters, and keep the account's sudo access to admins."
  else
    # Rootless Podman maps each container's users onto a range of the account's own ids.
    local f start
    for f in /etc/subuid /etc/subgid; do
      if ! grep -q "^$user:" "$f" 2> /dev/null; then
        start=$(awk -F: '{ if ($2 + $3 > m) m = $2 + $3 } END { print (m > 100000 ? m : 100000) }' "$f" 2> /dev/null || echo 100000)
        echo "$user:$start:65536" >> "$f"
      fi
    done
    loginctl enable-linger "$user" || echo "Note: lingering could not be turned on; Kilnmark will not start at boot."
    echo "$user may use rootless Podman, and its containers start at boot (lingering on)."
  fi
  # Only a new folder: root never writes into, or hands over, a tree another account could have
  # shaped (links planted in it, or files that are not Kilnmark's). Later versions arrive by upgrade.
  if [ -e "$root" ] || [ -L "$root" ]; then
    die "$root exists already. prepare makes a new install root only; upgrade an installed Kilnmark as $user (./kilnmark.sh upgrade), or choose another --root."
  fi
  mkdir -p "$(dirname "$root")"
  mkdir -m 700 "$root"
  mkdir "$root/versions" "$root/downloads"
  cp -a "$here" "$root/versions/$v"
  chown -R -h "$user:$user" "$root"
  chmod 750 "$root"
  cat <<EOF

The account $user owns $root, and Kilnmark $v is in $root/versions/$v.
Put the offline images file, if you have one, in $root/downloads (owned by $user). Then, as $user:
  sudo -iu $user $root/versions/$v/kilnmark.sh install [--offline $root/downloads/kilnmark-$v-images.tar.gz]
EOF
}

cmd_install() {
  local start=true
  while [ $# -gt 0 ]; do
    case "$1" in
      --root) root="${2:?--root needs a folder}"; shift ;;
      --offline) offline="${2:?--offline needs the images file}"; shift ;;
      --auth) with_auth=true ;;
      --starter) with_starter=true ;;
      --build) build=true ;;
      --skip-verify) skip_verify=true ;;
      --no-start) start=false ;;
      *) die "Unknown option $1. See: ./kilnmark.sh help" ;;
    esac
    shift
  done
  [ -f "$here/compose.yaml" ] || die "Run install from the unpacked release folder, kilnmark-<version>."
  local v; v="$(version_of "$here")"
  [ -n "$v" ] || die "Cannot tell this release's version from its compose.yaml."
  root="${root:-$default_root}"
  case "$root" in *[[:space:]]*) die "Choose a folder without spaces for --root." ;; /*) ;; *) root="$PWD/$root" ;; esac
  [ -n "${offline:-}" ] && offline="$(readlink -f "$offline")"
  echo "Installing Kilnmark $v in $root"
  check_prerequisites
  report_checks || die "Fix the problems above, then run install again."
  make_folders
  start_log install
  place_release "$here" "$v"
  make_settings "$root/versions/$v/.env.example"
  get_images "$v"
  boot_start
  if $start; then
    "$root/kilnmark.sh" up --no-browser
    if [ "${with_starter:-false}" = true ]; then starter_on; fi
  fi
  cat <<EOF

Kilnmark $v is installed in $root.
  Sign in with the admin token: it is KILNMARK_ADMIN_TOKEN in $root/config/.env.
  Then make a token of your own under API tokens, and keep it safe.
  Control it with: $root/kilnmark.sh up | down | status | backup | help
  Back it up every day, for example from cron: $root/kilnmark.sh backup
EOF
}

cmd_upgrade() {
  local archive="" backup=true
  while [ $# -gt 0 ]; do
    case "$1" in
      --offline) offline="${2:?--offline needs the images file}"; shift ;;
      --no-backup) backup=false ;;
      --skip-verify) skip_verify=true ;;
      --*) die "Unknown option $1." ;;
      *) archive="$1" ;;
    esac
    shift
  done
  [ -n "$archive" ] && [ -f "$archive" ] || die "Say which release to upgrade to: ./kilnmark.sh upgrade kilnmark-<version>.tar.gz"
  local old new tmp src
  old="$(current_version)"
  start_log upgrade
  verify_file "$archive"
  tmp="$(mktemp -d "$root/versions/.unpack.XXXX")"
  tar -xzf "$archive" -C "$tmp" || die "$(basename "$archive") could not be unpacked."
  src="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  new="$(version_of "$src")"
  [ -n "$new" ] || die "Cannot tell the new release's version."
  [ "$new" != "$old" ] || { rm -rf "$tmp"; die "Kilnmark $old is already the running version."; }
  if [ "$(printf '%s\n%s\n' "$old" "$new" | sort -V | tail -n 1)" != "$new" ]; then
    rm -rf "$tmp"; die "$new is older than the running $old: upgrade only goes forward (go back with restore)."
  fi
  if $backup; then cmd_backup; fi
  [ -d "$root/versions/$new" ] || mv "$src" "$root/versions/$new"
  rm -rf "$tmp"
  get_images "$new"
  local added; added="$(new_settings "$root/versions/$new/.env.example")"
  cp "$root/versions/$new/.env.example" "$root/config/.env.example"
  use_version "$new"
  if ! "$root/kilnmark.sh" up --no-browser; then
    echo "Kilnmark $new did not start: stopping it and starting $old again." >&2
    "$root/kilnmark.sh" down || true
    use_version "$old"
    if "$root/kilnmark.sh" up --no-browser; then
      echo "Kilnmark $old runs again. See why $new did not start in logs/." >&2
    else
      echo "Kilnmark $old does not start either: $new may have changed its database already." >&2
      echo "Put it back to the backup taken above: ./kilnmark.sh restore <that backup> --yes" >&2
    fi
    exit 1
  fi
  if [ -n "$added" ]; then
    echo "Settings new in $new (see $root/config/.env.example): $(echo "$added" | tr '\n' ' ')"
  fi
  # Keep the two newest versions.
  ls -1 "$root/versions" | grep -v '^\.' | sort -V | head -n -2 | while read -r d; do
    [ "$d" = "$new" ] || [ "$d" = "$old" ] || rm -rf "${root:?}/versions/$d"
  done
  echo "Upgraded Kilnmark from $old to $new."
}

cmd_uninstall() {
  local purge=false
  for a in "$@"; do case "$a" in --purge) purge=true ;; *) die "Unknown option $a." ;; esac; done
  local v; v="$(current_version)"; set_compose
  if ! $purge; then
    "${compose[@]}" down
    echo "Kilnmark is stopped and will not start again by itself. Its data and $root are kept."
    echo "To delete the data too: ./kilnmark.sh uninstall --purge"
    return 0
  fi
  echo "This deletes all of Kilnmark's data: its database, the key to stored passwords, and Keycloak's."
  echo "Back up first (./kilnmark.sh backup) if you might want any of it. Type DELETE to go on:"
  local answer; read -r answer
  [ "$answer" = DELETE ] || die "Nothing was deleted."
  "${compose[@]}" --profile auth --profile ai down -v
  "$engine" rmi $(image_names "$v") > /dev/null 2>&1 || true
  echo "Kilnmark's containers, volumes and images are deleted. $root is kept: delete it yourself if you wish."
}

# --- the Starter Pack (ADR 0051) ----------------------------------------------------------------

# Turns the Starter Pack on through Kilnmark's own API, as Settings > Starter Pack does: Kilnmark
# does the work. The admin token goes to curl in a file only this account can read, never on a
# command line; the people's password, when there is one, is shown here once and kept nowhere.
# Waits for a one-off service (samples-db, keycloak-setup) of this start to finish; false when it
# failed or did not finish in 5 minutes. By compose's labels, which Docker and Podman both set.
one_off_done() {
  local i state
  for i in $(seq 1 150); do
    state="$("$engine" ps -a --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.service=$1" --format '{{.Status}}' 2> /dev/null | head -n 1)"
    case "$state" in
      Exited\ \(0\)*) return 0 ;;
      Exited*) return 1 ;;
    esac
    sleep 2
  done
  return 1
}

starter_on() {
  local work answer code pw profiles
  set_compose
  one_off_done samples-db \
    || echo "Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: ./kilnmark.sh logs samples-db"
  profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  if [[ ",$profiles," == *",auth,"* ]]; then
    one_off_done keycloak-setup \
      || echo "Warning: keycloak-setup did not finish well, so the example people may not be added. See: ./kilnmark.sh logs keycloak-setup"
  fi
  work="$(mktemp -d)" || die "Could not make a private folder for the request."
  {
    cat <<EOF
header = "Authorization: Bearer $(setting KILNMARK_ADMIN_TOKEN)"
EOF
  } > "$work/curl.conf"
  echo "Turning the Starter Pack on ..."
  code="$(curl -k -s --config "$work/curl.conf" -X POST --max-time 600 -o "$work/answer" -w '%{http_code}' \
    "$(local_url)/api/v1/starter-pack")" || code=000
  answer="$(cat "$work/answer" 2> /dev/null || true)"
  rm -rf "$work"
  if [ "$code" != 200 ]; then
    echo "The Starter Pack was not turned on (HTTP $code): $(sed -n 's/^{"error":"\(.*\)"}$/\1/p' <<< "$answer")" >&2
    echo "Turn it on later under Settings > Starter Pack." >&2
    return 0
  fi
  # Each step, one a line: "step: outcome".
  grep -o '"step":"[^"]*","outcome":"[^"]*"' <<< "$answer" | sed 's/"step":"\([^"]*\)","outcome":"\([^"]*\)"/  \1: \2/'
  pw="$(sed -n 's/.*"peoplePassword":"\([A-Za-z0-9_-]*\)".*/\1/p' <<< "$answer")"
  echo "The Starter Pack is on: example data and accounts, not for production. Turn it off under Settings > Starter Pack."
  # Straight to the terminal: what this script says otherwise goes to logs/ too.
  if [ -n "$pw" ] && { : > /dev/tty; } 2> /dev/null; then
    {
      echo "The example people (vera.lane, sam.okafor, dana.reyes, priya.shah and alex.morgan, @kilnmark.com) sign in"
      echo "with this password, shown only now and kept nowhere, not even in logs/: $pw"
    } > /dev/tty
  elif [ -n "$pw" ]; then
    echo "The example people's password is shown on a terminal only, never in logs/: turn the pack on again under"
    echo "Settings > Starter Pack to give them a new one, shown there."
  fi
}

# --- backup and restore (the scripts beside compose.yaml) --------------------------------------

# backup.sh and restore.sh split KILNMARK_COMPOSE on spaces, which is why the root has none.
compose_line() { echo "${compose[*]}"; }

cmd_backup() {
  local dest="${1:-$root/backups}"
  current_version > /dev/null; set_compose
  mkdir -p "$dest"
  (cd "$root/current" && KILNMARK_COMPOSE="$(compose_line)" sh ./backup.sh "$dest")
}

cmd_restore() {
  current_version > /dev/null; set_compose
  (cd "$root/current" && KILNMARK_COMPOSE="$(compose_line)" sh ./restore.sh "$@")
}

# --- main --------------------------------------------------------------------------------------

# All in one function, read whole before it runs: upgrade replaces this file under a running copy.
main() {
command="${1:-up}"
[ $# -eq 0 ] || shift
case "$command" in
  prepare) cmd_prepare "$@" ;;
  install) cmd_install "$@" ;;
  up|start) start_log up; cmd_up "$@" ;;
  down|stop) start_log down; cmd_down ;;
  restart) set_compose; "${compose[@]}" restart "$@" ;;
  status) cmd_status ;;
  logs)
    set_compose
    follow=(); svc=()
    for a in "$@"; do case "$a" in -f) follow=(-f) ;; *) svc+=("$a") ;; esac; done
    "${compose[@]}" logs --tail 200 "${follow[@]}" "${svc[@]}" ;;
  check)
    running=false; [ -n "$root" ] && curl -k -s -f -o /dev/null "$(local_url)/q/health/ready" && running=true
    check_prerequisites "$running"
    if [ -n "$root" ] && [ -f "$root/config/.env" ]; then
      added="$(new_settings "$root/current/.env.example")"
      [ -z "$added" ] || echo "Settings in this version's .env.example that config/.env does not mention: $(echo "$added" | tr '\n' ' ')"
    fi
    report_checks && echo "All checks passed." ;;
  verify)
    file=""; while [ $# -gt 0 ]; do case "$1" in --key) key_override="${2:?}"; shift ;; *) file="$1" ;; esac; shift; done
    [ -n "$file" ] || die "Say which file: ./kilnmark.sh verify FILE"
    verify_file "$file" ;;
  load-images)
    file=""; for a in "$@"; do case "$a" in --skip-verify) skip_verify=true ;; *) file="$a" ;; esac; done
    [ -n "$file" ] || die "Say which file: ./kilnmark.sh load-images kilnmark-<version>-images.tar.gz"
    load_images "$file" ;;
  pull)
    for a in "$@"; do case "$a" in --skip-verify) skip_verify=true ;; *) die "Unknown option $a." ;; esac; done
    pull_images "$(current_version)" ;;
  build) build_images "$(current_version)" ;;
  backup) start_log backup; cmd_backup "$@" ;;
  restore) start_log restore; cmd_restore "$@" ;;
  upgrade) cmd_upgrade "$@" ;;
  uninstall) cmd_uninstall "$@" ;;
  version) if [ -n "$root" ]; then echo "Kilnmark $(current_version)"; else echo "Kilnmark $(version_of "$here") (not installed)"; fi ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
}
main "$@"; exit $?
