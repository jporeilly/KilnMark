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
# Compose fills ${NAME} from this shell's environment before config/.env. These must mean what
# config/.env says, as this script reads them (ADR 0051), so a value exported here never counts.
unset KILNMARK_MODE KILNMARK_STARTER KILNMARK_STARTER_PEOPLE_ACTION \
  KILNMARK_PEOPLE_ACTION KILNMARK_PERSON_EMAIL KILNMARK_PERSON_FIRST_NAME KILNMARK_PERSON_LAST_NAME \
  KILNMARK_PERSON_GROUPS KILNMARK_INSTALL_ID KILNMARK_RUN_DIR KILNMARK_ENV_FILE

# Rootless Podman needs the account's systemd session; sudo -iu and su do not set it up, but its
# runtime folder exists once lingering is on (prepare turns it on).
if [ "$(id -u)" -ne 0 ] && [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$(id -u)" ]; then
  export XDG_RUNTIME_DIR="/run/user/$(id -u)"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
fi

die() { echo "Error: $*" >&2; exit 1; }
note() { echo "$*"; }

# However this script ends, the private folders it made go: the one-offs' secrets
# (write_run_secrets) and a request's (api_call, starter_on).
starter_work="" run_secrets=""
cleanup() {
  [ -z "$starter_work" ] || rm -rf "$starter_work"
  [ -z "$run_secrets" ] || rm -rf "$run_secrets"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<'EOF'
Kilnmark control script.

  prepare [--user NAME] [--root DIR] [--engine podman|docker]
                       Once, with sudo: make the service account (kilnmark) that runs Kilnmark,
                       give it the container engine, and make the install root its own.
  install [--root DIR] [--offline IMAGES.tar.gz] [--auth] [--starter] [--build] [--skip-verify] [--no-start]
                       First setup: checks, folders, settings with new secrets, images, start.
                       A production install, unless --starter: a Starter Pack install, for
                       training and development only, which never becomes a production one,
                       always with the bundled Keycloak; the Starter Pack's example data is
                       turned on, and starter people adds the example people. A production
                       install asks how people sign in (--auth: the bundled Keycloak). With
                       the bundled Keycloak, install offers to add Kilnmark's first admin.
  people add | people import FILE | people activate EMAIL | people list | people expire
                       With the bundled Keycloak, in either mode: add one person of your own (any
                       of Kilnmark's groups, admins included; install asks for the first admin
                       this way), add people from a CSV file (no admins; on production no one who
                       sees sensitive values, and each waits, disabled, for people activate; the
                       example is people.example.csv), activate one, or list who is there. A
                       temporary password is typed (or piped in), never shown, and goes unused for
                       KILNMARK_PEOPLE_ACTIVATION_HOURS (72) at most: people expire, which a timer
                       runs every hour, disables anyone past it. Someone already there is left
                       as they are.
  starter people [--new-password | --remove]
                       On a Starter Pack install, the five example people in the bundled Keycloak
                       (with KILNMARK_STARTER=allowed): add them, give them all a new password, or
                       remove them. You type the password (or pipe it in): it is never shown.
  up [--no-browser]    Start Kilnmark (and Keycloak when .env turns on the auth profile); wait
                       until it answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything (a stop, then up), or one service.
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

# A setting from config/.env as compose reads it (an export in front too); empty when it is not set.
setting() {
  local line value file="$root/config/.env"
  [ -f "$file" ] || { printf ''; return; }
  line="$( { grep -E "^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=" "$file" || true; } | tail -n 1 | tr -d '\r')"
  value="${line#*=}"
  value="$(sed -e 's/^[[:space:]]*//' <<< "$value")"
  case "$value" in
    \"*) value="${value#\"}"; value="${value%%\"*}" ;;
    \'*) value="${value#\'}"; value="${value%%\'*}" ;;
    *) value="$(sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//' <<< "$value")" ;;
  esac
  printf '%s' "$value"
}

# The deployment mode (ADR 0051): production, or starter-pack for training and development, in
# ASCII lower case, as the server reads it. The server refuses anything else, and so does this.
check_mode() {
  case "$(setting KILNMARK_MODE)" in
    production|starter-pack) ;;
    *) die "KILNMARK_MODE in config/.env is production or starter-pack: Kilnmark does not start with anything else. Nothing was changed." ;;
  esac
}
starter_mode() { [ "$(setting KILNMARK_MODE)" = starter-pack ]; }
# Whether the Starter Pack's example data (its database) may be here: on a Starter Pack install.
# Kept apart from the example people (starter_mode), which only a Starter Pack install has.
samples_allowed() { starter_mode; }

# The deployment mode Kilnmark recorded in its own database at its first start (ADR 0051), read with
# the database's own tools: what decides, not config/.env alone. Empty when nothing is recorded, or
# the database does not answer.
recorded_mode() { recorded_setting deployment_mode; }
# A setting Kilnmark recorded in its own database ($1: deployment_mode, or install_id, the install's
# own id, made at its first start); empty when there is none, or the database does not answer.
recorded_setting() {
  set_compose
  "${compose[@]}" exec -T postgres psql -U kilnmark -d kilnmark -X -q -At \
    -c "SELECT value FROM kilnmark.server_setting WHERE key = '$1'" 2> /dev/null | tr -d '[:space:]' || true
}

# The people commands act only where Kilnmark's own record says the same mode as config/.env:
# refused when they differ, or when the database does not answer. keycloak-setup is then handed
# the recorded mode (people_mode), never config/.env's alone.
people_mode="" mode_problem=""
check_people_mode() {
  local recorded; recorded="$(recorded_mode)"
  people_mode="" mode_problem=""
  if [ -z "$recorded" ]; then
    mode_problem="Kilnmark's database does not answer, or has no deployment mode recorded, so nobody was made or changed. Start Kilnmark with $root/kilnmark.sh up, then try again."
  elif [ "$recorded" != "$(setting KILNMARK_MODE)" ]; then
    mode_problem="KILNMARK_MODE in config/.env differs from the mode Kilnmark recorded at its first start, so nobody was made or changed: set it back."
  else
    people_mode="$recorded"
  fi
}
require_people_mode() { check_people_mode; [ -z "$mode_problem" ] || die "$mode_problem"; }

# compose, always the kilnmark project, with this site's .env and the current version's file.
compose=()
set_compose() {
  # a compose project's name, as compose and the hourly expiry's timer take it
  [[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] \
    || die "KILNMARK_PROJECT is lower-case letters, digits, - and _, starting with a letter or a digit. Nothing was changed."
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
  # The Starter Pack's database is made only on a Starter Pack install (ADR 0051).
  ! samples_allowed || compose+=(--profile starter-pack)
}

image_names() { echo "$registry/kilnmark-server:$1 $registry/kilnmark-postgres:$1"; }

# The few secrets each one-off needs (keycloak-setup: Keycloak's admin password and the client
# secret; samples-db: the database's password and the samples login's), each set in a file of its
# own, in a new folder only this account can open under config/run, for one run: compose mounts
# them read-only (KILNMARK_RUN_DIR, relative to compose.yaml), never config/.env whole. Gone once
# the run is over (drop_run_secrets), and however this script ends; one a killed run left goes a day on.
run_secret_line() {
  local v; v="$(setting "$1")"
  if [[ "$v" != *"'"* ]]; then printf "%s='%s'\n" "$1" "$v"
  elif [[ "$v" != *'"'* ]]; then printf '%s="%s"\n' "$1" "$v"
  else die "$1 in config/.env holds both kinds of quote mark, which compose tools do not all read alike: choose another. Nothing was changed."
  fi
}
write_run_secrets() {
  local base="$root/config/run"
  [ -z "$run_secrets" ] || return 0
  mkdir -p "$base" && chmod 700 "$base" || die "Could not make $base for the one-offs' secrets."
  find "$base" -mindepth 1 -maxdepth 1 -type d -name 'r.*' -mmin +1440 -exec rm -rf {} + 2> /dev/null || true
  run_secrets="$(mktemp -d "$base/r.XXXXXXXX")" || die "Could not make a private folder in $base."
  { run_secret_line KILNMARK_KEYCLOAK_ADMIN_PASSWORD; run_secret_line KILNMARK_OIDC_CLIENT_SECRET; } > "$run_secrets/keycloak-setup.env"
  { run_secret_line KILNMARK_DB_PASSWORD; run_secret_line KILNMARK_SAMPLES_DB_PASSWORD; } > "$run_secrets/samples-db.env"
  # readable inside the containers, whichever user each runs as: the folder keeps everyone else out
  chmod 644 "$run_secrets/keycloak-setup.env" "$run_secrets/samples-db.env"
  export KILNMARK_RUN_DIR="../../config/run/$(basename "$run_secrets")"
}
drop_run_secrets() {
  [ -z "$run_secrets" ] || rm -rf "$run_secrets"
  run_secrets=""
  unset KILNMARK_RUN_DIR
}

# --- the hourly expiry of unused temporary passwords (ADR 0051) -----------------------------------

# With the bundled Keycloak, people expire runs every hour from a systemd timer of this account (a
# system one when this runs as root), so a temporary password left unused is disabled at most an
# hour after its window, whether or not Kilnmark starts again. up sets it up, and removes it without
# the bundled Keycloak; uninstall removes it. KILNMARK_SYSTEMCTL, KILNMARK_SYSTEMD_DIR and
# KILNMARK_TEST_UID are for the tests only, and count only with their marker, which no release sets.
expiry_unit="kilnmark-$project-people-expire"
test_hooks() { [ "${KILNMARK_TEST_HOOKS:-}" = only-in-kilnmark-tests ]; }
# who runs this, for the timer
expiry_uid() { if test_hooks && [ -n "${KILNMARK_TEST_UID:-}" ]; then printf '%s' "$KILNMARK_TEST_UID"; else id -u; fi; }
expiry_systemctl() {
  local sc=systemctl
  if test_hooks && [ -n "${KILNMARK_SYSTEMCTL:-}" ]; then sc="$KILNMARK_SYSTEMCTL"; fi
  if [ "$(expiry_uid)" -eq 0 ]; then "$sc" "$@"; else "$sc" --user "$@"; fi
}
expiry_dir() {
  if test_hooks && [ -n "${KILNMARK_SYSTEMD_DIR:-}" ]; then printf '%s' "$KILNMARK_SYSTEMD_DIR"
  elif [ "$(expiry_uid)" -eq 0 ]; then printf '%s' /etc/systemd/system
  else printf '%s' "${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  fi
}
# The install root as the timer's unit names it: its canonical path, once, so the folders checked
# are the ones the unit runs from; empty when it holds anything a unit file would read as more than a
# path (a space, systemd's % specifiers, quotes, a backslash, a control character).
# A path as this shell's own: on Git Bash, where the tests run this edition too, a Windows path as
# its POSIX one, so a folder and where a link leads are named alike (no Linux has cygpath).
posix_path() {
  if [[ "$1" =~ ^[A-Za-z]:[\\/] ]] && command -v cygpath > /dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}
root_real() {
  local r; r="$(readlink -f "$(posix_path "$root")" 2> /dev/null || true)"
  if [[ "$r" =~ ^/[A-Za-z0-9._/+@,=-]*$ ]]; then printf '%s' "$r"; fi
}
# Why $1 may be changed by someone other than $want (a uid), or nothing: it must be theirs (or
# root's, where $2 is "or-root"), never a link, and writable by neither its group nor others.
owned_problem() {
  local info
  if [ -L "$1" ]; then echo "$1 is a link, which an hourly run would follow"; return 0; fi
  info="$(stat -c '%u %a' "$1" 2> /dev/null || echo "x 777")"
  if { [ "${info%% *}" != "$want" ] && { [ "${2:-}" != or-root ] || [ "${info%% *}" != 0 ]; }; } \
    || (( (8#${info##* } & 8#022) != 0 )); then
    echo "$1 may be changed by someone other than the account that runs Kilnmark (it is not that account's own, or others may write it)"
  fi
}
# Why someone other than the account that runs Kilnmark may change what an hourly run uses, or
# nothing (ADR 0051, decision 15): the timer runs $root/kilnmark.sh as this account (as root, a
# system timer), and that run reads the current version's compose.yaml and keycloak-setup's script,
# config/.env, and writes to logs/. Each, and every folder from the root down to it, must be this
# account's own (root's, as root), no link but current, and writable by nobody else; current must
# lead to a folder directly in versions/. Above the root, so nobody can move it away and put
# another in its place, every folder up to / must be root's or the account's and writable by nobody
# else; a folder others may write (/tmp) only where it is sticky and the next one down is root's or
# the account's, and never as root (as sshd's StrictModes asks).
tree_problem() {
  local want as_root=0 r cur v rel rest p seen=" " why info o m child dir
  # as root (the timer is a system unit), root must own it all; else the account that runs this
  if [ "$(expiry_uid)" -eq 0 ]; then want=0; as_root=1; else want="$(id -u)"; fi
  r="$(root_real)"
  [ -n "$r" ] || { echo "the install root's path holds a character a systemd unit would read as more than a path (a space, %, a quote, a backslash or a control character)"; return 0; }
  # the link itself only by its owner (a link's own permissions mean nothing)
  info="$(stat -c '%u' "$r/current" 2> /dev/null || echo x)"
  [ -L "$r/current" ] && [ "$info" = "$want" ] \
    || { echo "$r/current may be changed by someone other than the account that runs Kilnmark (it is not that account's own link to a version)"; return 0; }
  cur="$(readlink -f "$r/current" 2> /dev/null || true)"
  v="${cur#"$r/versions/"}"
  if [ -z "$cur" ] || [ "$v" = "$cur" ] || [ -z "$v" ] || [[ "$v" == */* ]] || [ ! -d "$cur" ]; then
    echo "$r/current does not lead to a version in $r/versions"; return 0
  fi
  for rel in kilnmark.sh "versions/$v/compose.yaml" "versions/$v/containers/keycloak/setup.sh" config/.env logs; do
    [ "$rel" != logs ] || [ -e "$r/logs" ] || [ -L "$r/logs" ] || continue
    p="$r" rest="$rel"
    while :; do
      if [[ "$seen" != *" $p "* ]]; then
        why="$(owned_problem "$p")"
        [ -z "$why" ] || { echo "$why"; return 0; }
        seen="$seen$p "
      fi
      [ -n "$rest" ] || break
      p="$p/${rest%%/*}"
      case "$rest" in */*) rest="${rest#*/}" ;; *) rest="" ;; esac
    done
  done
  above_problem "$r"
}
# Why a folder above $1 may be changed by someone other than root or the account ($want, and
# $as_root, the caller's), who could move $1 away and put another in its place, or nothing: each
# up to / must be root's or the account's and writable by neither group nor others; one others may
# write (/tmp) only where it is sticky and the next one down is root's or the account's, and never
# as root (as sshd's StrictModes asks).
above_problem() {
  local child="$1" dir info o m
  dir="$(dirname "$child")"
  while :; do
    info="$(stat -c '%u %a' "$dir" 2> /dev/null || echo "x 777")"
    o="${info%% *}" m=$((8#${info##* }))
    if { [ "$o" != 0 ] && [ "$o" != "$want" ]; } || { (( (m & 8#022) != 0 )) \
      && { [ "$as_root" = 1 ] || (( (m & 8#1000) == 0 )) || ! [[ " 0 $want " == *" $(stat -c '%u' "$child" 2> /dev/null || echo x) "* ]]; }; }; then
      echo "$dir, a folder above $1, may be changed by someone other than root or the account that runs Kilnmark, who could move what is in it away and put another in its place"
      return 0
    fi
    case "$dir" in /|.) return 0 ;; esac
    child="$dir" dir="$(dirname "$dir")"
  done
}
# Why the engine a run uses, or the folder the timer's units are in, may be changed by someone other
# than root or the account, or nothing (ADR 0051, decision 15): each must be root's or the
# account's, writable by nobody else, and so must every folder above it (where KILNMARK_ENGINE is a
# link, the link's own folders too). $1 "make": the units' folder is made first, as up needs it.
tools_problem() {
  local want as_root=0 e t d why
  if [ "$(expiry_uid)" -eq 0 ]; then want=0; as_root=1; else want="$(id -u)"; fi
  e="${KILNMARK_ENGINE:-}"
  [ -n "$e" ] || e="$(command -v podman 2> /dev/null || command -v docker 2> /dev/null || true)"
  [ -z "$e" ] || [[ "$e" == */* ]] || [[ "$e" =~ ^[A-Za-z]: ]] || e="$(command -v "$e" 2> /dev/null || true)"
  if [ -n "$e" ]; then
    e="$(posix_path "$e")"
    if [ -L "$e" ]; then why="$(above_problem "$e")"; [ -z "$why" ] || { echo "$why"; return 0; }; fi
    t="$(readlink -f "$e" 2> /dev/null || true)"
    why="$(owned_problem "${t:-$e}" or-root)"; [ -z "$why" ] || { echo "$why"; return 0; }
    why="$(above_problem "${t:-$e}")"; [ -z "$why" ] || { echo "$why"; return 0; }
  fi
  d="$(posix_path "$(expiry_dir)")"
  [ "${1:-}" != make ] || mkdir -p "$d" 2> /dev/null || true
  if [ -e "$d" ] || [ -L "$d" ]; then
    why="$(owned_problem "$d" or-root)"; [ -z "$why" ] || { echo "$why"; return 0; }
    why="$(above_problem "$d")"; [ -z "$why" ] || { echo "$why"; return 0; }
  fi
}
# Why KILNMARK_ENGINE may not go into the timer's unit, or nothing: only the full path of podman or
# docker, with nothing a unit file or a shell would read as more.
engine_problem() {
  local e="${KILNMARK_ENGINE:-}"
  [ -n "$e" ] || return 0
  if test_hooks; then
    [[ "$e" =~ ^[A-Za-z0-9._/:-]+$ ]] && [ -f "$e" ] || echo "KILNMARK_ENGINE is not a program's path"
    return 0
  fi
  if ! [[ "$e" =~ ^/[A-Za-z0-9._/-]+$ ]] || [ ! -f "$e" ] || [ ! -x "$e" ] || ! [[ "$(basename "$e")" =~ ^(podman|docker)$ ]]; then
    echo "KILNMARK_ENGINE is not the full path of podman or docker"
  fi
}
expiry_timer_set() {
  local dir service timer engine_line="" why r want
  if [ "$(expiry_uid)" -eq 0 ]; then want=0; else want="$(id -u)"; fi
  why="$(tree_problem)"
  [ -n "$why" ] || why="$(engine_problem)"
  # the engine the unit names, and the folder its units are in, as what the run uses
  [ -n "$why" ] || why="$(tools_problem make)"
  dir="$(expiry_dir)"
  if [ -n "$why" ]; then
    expiry_timer_remove
    echo "Warning: the hourly expiry of unused temporary passwords was not set up: $why. Make it so, or run Kilnmark as its own account (sudo ./kilnmark.sh prepare), then run up again. Each start expires them too." >&2
    return 0
  fi
  r="$(root_real)"
  [ -z "${KILNMARK_ENGINE:-}" ] || engine_line="
Environment=KILNMARK_ENGINE=$KILNMARK_ENGINE"
  service="[Unit]
Description=Kilnmark ($project): disable people whose temporary password went unused
[Service]
Type=oneshot
Environment=KILNMARK_PROJECT=$project$engine_line
ExecStart=$r/kilnmark.sh people expire"
  timer="[Unit]
Description=Kilnmark ($project): expire unused temporary passwords every hour
[Timer]
OnCalendar=hourly
AccuracySec=1min
Persistent=true
[Install]
WantedBy=timers.target"
  if [ "$(cat "$dir/$expiry_unit.service" 2> /dev/null)" = "$service" ] \
    && [ "$(cat "$dir/$expiry_unit.timer" 2> /dev/null)" = "$timer" ] \
    && expiry_systemctl is-enabled "$expiry_unit.timer" > /dev/null 2>&1; then
    return 0
  fi
  if printf '%s\n' "$service" > "$dir/$expiry_unit.service" \
    && printf '%s\n' "$timer" > "$dir/$expiry_unit.timer" \
    && expiry_systemctl daemon-reload > /dev/null 2>&1 \
    && expiry_systemctl enable --now "$expiry_unit.timer" > /dev/null 2>&1; then
    echo "Set up the hourly expiry of unused temporary passwords: the systemd timer $expiry_unit.timer."
  else
    echo "Warning: the hourly expiry of unused temporary passwords could not be set up (a systemd timer). Run $root/kilnmark.sh people expire every hour yourself (from cron, say); each start expires them too." >&2
  fi
}
expiry_timer_remove() {
  local dir; dir="$(expiry_dir)"
  [ -f "$dir/$expiry_unit.timer" ] || [ -f "$dir/$expiry_unit.service" ] || return 0
  expiry_systemctl disable --now "$expiry_unit.timer" > /dev/null 2>&1 || true
  rm -f "$dir/$expiry_unit.timer" "$dir/$expiry_unit.service"
  expiry_systemctl daemon-reload > /dev/null 2>&1 || true
  echo "Removed the hourly expiry of unused temporary passwords (the systemd timer $expiry_unit.timer)."
}
expiry_timer_state() {
  local dir; dir="$(expiry_dir)"
  if [ -f "$dir/$expiry_unit.timer" ] && expiry_systemctl is-enabled "$expiry_unit.timer" > /dev/null 2>&1; then
    echo "hourly (the systemd timer $expiry_unit.timer)"
  else
    echo "not set up"
  fi
}
auth_on() { [[ ",$(setting COMPOSE_PROFILES | tr -d '[:space:]')," == *",auth,"* ]]; }

images_present() {
  local i
  for i in $(image_names "$1"); do "$engine" image inspect "$i" > /dev/null 2>&1 || return 1; done
}

# --- logging -----------------------------------------------------------------------------------

# podman-compose 1.0.x (Ubuntu 24.04's) prints each podman command it runs, with every setting of
# the container on it (-e NAME=value: database passwords, Keycloak's admin password, the client
# secret), joined with spaces or as a Python list. Those lines are never shown or kept in logs/:
# filtered here for every compose call this script shows, whether or not it logs.
compose_command_line="podman.*[[:space:]'\",[](-e|--env)([[:space:]=,'\"]|\$)"
hide_compose_commands() { grep --line-buffered -v -E "$compose_command_line" || true; }

start_log() {
  [ -n "$root" ] && [ -d "$root/logs" ] && [ ! -L "$root/logs" ] || return 0
  # what the scripts said, this account's only (an install from before kept it open)
  chmod 700 "$root/logs" 2> /dev/null || true
  local log; log="$root/logs/$1-$(date -u +%Y%m%dT%H%M%SZ).log"
  # the hourly expiry keeps one log a day, not one a run
  [ "${2:-}" != daily ] || log="$root/logs/$1-$(date -u +%Y%m%d).log"
  # never through a link, which would write wherever it leads (as root, for a system timer)
  if [ -L "$log" ] || { [ -e "$log" ] && [ ! -f "$log" ]; }; then
    die "$log is not a plain file, so nothing was logged or done: remove it, then run this again."
  fi
  exec > >(hide_compose_commands | tee -a "$log") 2>&1
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
  # The deployment mode (ADR 0051), fixed at the first start: --starter makes a Starter Pack install,
  # for training and development, which never becomes a production one.
  if [ "${with_starter:-false}" = true ]; then
    set_line "$env" KILNMARK_MODE starter-pack
    set_line "$env" KILNMARK_SAMPLES_DB_PASSWORD "$(random_value)"
  else
    set_line "$env" KILNMARK_MODE production
  fi
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

# Settings later versions added, as config/.env now asks: run before every start, so upgrade and up
# keep them right. Each new secret is random.
ensure_settings() {
  local env="$root/config/.env" files hours
  [ -f "$env" ] || return 0
  # Every install since deployment modes (ADR 0051) has one; an install from before them (0.1.1 or
  # earlier, a preview) is never upgraded.
  [ -n "$(setting KILNMARK_MODE)" ] \
    || die "This install predates deployment modes (release 0.1.1 or earlier, a preview), so it cannot be upgraded: install Kilnmark afresh. Back up first anything you mean to keep by hand. Nothing was changed."
  check_mode
  # Each is for one run of keycloak-setup, set by this script only: in config/.env every start would take it.
  for one_run in KILNMARK_STARTER_PEOPLE_ACTION KILNMARK_PEOPLE_ACTION KILNMARK_PERSON_EMAIL KILNMARK_PERSON_FIRST_NAME \
    KILNMARK_PERSON_LAST_NAME KILNMARK_PERSON_GROUPS KILNMARK_INSTALL_ID KILNMARK_RUN_DIR; do
    [ -z "$(setting "$one_run")" ] || die "$one_run is for one run of the control script only: delete it from config/.env. Nothing was changed."
  done
  # A Starter Pack install signs in with the bundled Keycloak, where its example people are (ADR 0051).
  if starter_mode && [[ ",$(setting COMPOSE_PROFILES | tr -d '[:space:]')," != *",auth,"* ]]; then
    die "A Starter Pack install (KILNMARK_MODE=starter-pack) needs the bundled Keycloak, where its example people are: put auth in COMPOSE_PROFILES in config/.env, with the Keycloak settings install writes. Nothing was changed."
  fi
  hours="$(setting KILNMARK_PEOPLE_ACTIVATION_HOURS)"
  if [ -n "$hours" ] && { ! [[ "$hours" =~ ^[0-9]{1,3}$ ]] || [ "$hours" -lt 1 ] || [ "$hours" -gt 720 ]; }; then
    die "KILNMARK_PEOPLE_ACTIVATION_HOURS in config/.env is a whole number of hours from 1 to 720. Nothing was changed."
  fi
  if samples_allowed && [ -z "$(setting KILNMARK_SAMPLES_DB_PASSWORD)" ]; then
    set_line "$env" KILNMARK_SAMPLES_DB_PASSWORD "$(random_value)"
    echo "Added KILNMARK_SAMPLES_DB_PASSWORD to config/.env: the Starter Pack's database login (ADR 0051)."
  fi
  # A folder of your own, by its full path: a bare name (keys, pgdata) would be a volume of Kilnmark's own.
  files="$(setting KILNMARK_FILES_DIR)"
  if [ -n "$files" ] && [ "${files#/}" = "$files" ]; then
    die "KILNMARK_FILES_DIR in config/.env is the full path of your own folder, starting with /. Nothing was changed."
  fi
  # Upgrading: an earlier version's compose.yaml edited to mount a folder of your own at /srv/files.
  if [ -z "$files" ] && grep -sqE '^[[:space:]]*-[[:space:]]*[^#[:space:]][^#]*:/srv/files:ro' "$root"/versions/*/compose.yaml; then
    echo "Warning: an earlier version mounted a folder of your own at /srv/files. Now set KILNMARK_FILES_DIR in" >&2
    echo "config/.env to its full path: Kilnmark sees it read-only at /srv/share, so point its file sources there." >&2
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
  printf '%s\n' "$out" | hide_compose_commands >&2
  return 1
}

cmd_up() {
  local browser=true v url profiles
  for a in "$@"; do case "$a" in --no-browser) browser=false ;; *) die "Unknown option $a." ;; esac; done
  v="$(current_version)"; ensure_settings; set_compose
  images_present "$v" || die "The images of Kilnmark $v are not here. Get them with one of: ./kilnmark.sh pull, ./kilnmark.sh load-images FILE, ./kilnmark.sh build"
  echo "Starting Kilnmark $v ..."
  write_run_secrets
  hand_install_id
  compose_quietly up -d || die "Kilnmark could not start. See why with: ./kilnmark.sh logs"
  wait_for Kilnmark "$(local_url)/q/health/ready" 90 \
    || die "Kilnmark did not answer within 3 minutes. See why with: ./kilnmark.sh logs kilnmark"
  after_one_offs
  if auth_on; then expiry_timer_set; else expiry_timer_remove; fi
  url="$(setting KILNMARK_URL)"; url="${url:-$(local_url)}"; url="${url%/}"
  echo "Kilnmark $v is ready at $url"
  if $browser && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open > /dev/null; then
    setsid xdg-open "$url" > /dev/null 2>&1 < /dev/null &
    sleep 1
  fi
}

# The install's id and mode, as the server recorded them, handed to keycloak-setup at every start
# to keep on the realm (a restore checks a backup's Keycloak database by them). Read with the
# database started first; before the server's first start there is none yet (after_one_offs then
# stamps the realm). A mode in config/.env other than the recorded one stops here: the server
# would refuse it anyway, and the realm must never carry the wrong one.
hand_install_id() {
  local i recorded
  unset KILNMARK_INSTALL_ID
  auth_on || return 0
  compose_quietly up -d postgres || die "Kilnmark's database could not start. See why with: ./kilnmark.sh logs postgres"
  for i in $(seq 1 60); do
    "${compose[@]}" exec -T postgres pg_isready -h 127.0.0.1 -U kilnmark -d kilnmark > /dev/null 2>&1 && break
    sleep 2
  done
  recorded="$(recorded_mode)"
  if [ -n "$recorded" ] && [ "$recorded" != "$(setting KILNMARK_MODE)" ]; then
    die "KILNMARK_MODE in config/.env differs from the mode Kilnmark recorded at its first start: set it back. Kilnmark was not started."
  fi
  KILNMARK_INSTALL_ID="$(recorded_setting install_id)"
  export KILNMARK_INSTALL_ID
}

# The one-offs this start ran: waits for them (keycloak-setup tells Kilnmark what it did), then
# their secrets go. After the server's first start, the realm is stamped with the id it made.
after_one_offs() {
  if auth_on; then
    local kc id; kc="$(setting KILNMARK_KEYCLOAK_PORT)"
    wait_for Keycloak "http://localhost:${kc:-8180}/realms/kilnmark" 150 \
      || die "Keycloak did not answer within 5 minutes. See why with: ./kilnmark.sh logs keycloak"
    people_after_up
    if [ -z "${KILNMARK_INSTALL_ID:-}" ]; then
      id="$(recorded_setting install_id)"
      if [ -n "$id" ]; then
        local stamped
        if stamped="$(KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID="$id" \
          "${compose[@]}" run --rm -T keycloak-setup < /dev/null 2>&1 | hide_compose_commands)"; then
          record_own_people "$stamped" stamp
        else
          echo "Warning: Keycloak's realm was not told which install it belongs to; the next start tells it. See: ./kilnmark.sh logs keycloak-setup" >&2
        fi
      fi
    fi
  fi
  if samples_allowed && ! one_off_done samples-db; then
    echo "Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: ./kilnmark.sh logs samples-db" >&2
  fi
  drop_run_secrets
}

# Everything: a stop, then up, so the one-offs run again with this start's settings. One service:
# compose restarts it (never a one-off, which runs at every up).
cmd_restart() {
  local s
  if [ $# -eq 0 ]; then cmd_down; cmd_up --no-browser; return; fi
  for s in "$@"; do
    case "$s" in keycloak-setup|samples-db) die "$s runs once at every start: restart everything (./kilnmark.sh restart), or run up." ;; esac
  done
  set_compose
  "${compose[@]}" restart "$@"
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
  ! auth_on || echo "People expiry: $(expiry_timer_state)"
  last="$(ls -1d "$root"/backups/kilnmark-* 2> /dev/null | tail -n 1 || true)"
  if [ -n "$last" ]; then echo "Last backup: $(basename "$last")"; else echo "Last backup: none in $root/backups"; fi
}

# --- install, upgrade, uninstall ---------------------------------------------------------------

make_folders() {
  mkdir -p "$root/versions" "$root/config" "$root/certs/https" "$root/backups" "$root/logs" \
    || die "Cannot create $root. Prepare it first (sudo ./kilnmark.sh prepare), or choose another with --root."
  chmod 700 "$root/config" "$root/certs" "$root/backups" "$root/logs"
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

# How people sign in to a production install, asked at a terminal (without a terminal, or with
# --auth, nothing is asked): the organisation's own identity provider, the bundled Keycloak, or API
# tokens only. Only the bundled Keycloak changes what install writes; the others are config/.env's.
own_provider=false
ask_sign_in() {
  local answer
  [ -t 0 ] || return 0
  cat <<'EOF'
How will people sign in to Kilnmark?
  1  Your organisation's own identity provider (recommended for banks and other regulated organisations)
  2  The bundled Keycloak, which this install sets up, with Kilnmark's first admin
  3  API tokens only, for now
EOF
  read -r -p "1, 2 or 3 [1]: " answer
  case "${answer:-1}" in
    1) own_provider=true ;;
    2) with_auth=true ;;
    3) ;;
    *) die "Answer 1, 2 or 3. Nothing was installed." ;;
  esac
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
  # A Starter Pack install always has the bundled Keycloak: its example people live there.
  if [ "${with_starter:-false}" = true ]; then with_auth=true; fi
  [ "${with_auth:-false}" = true ] || ask_sign_in
  make_folders
  start_log install
  place_release "$here" "$v"
  make_settings "$root/versions/$v/.env.example"
  get_images "$v"
  boot_start
  # Asked before the start, which takes a while, so nobody waits to answer.
  local first_admin=false
  people_soft=true
  if $start && [ "${with_auth:-false}" = true ] && ask_person kilnmark-admins; then first_admin=true; fi
  if $start; then
    "$root/kilnmark.sh" up --no-browser < /dev/null
    ! $first_admin || make_person || true
    if [ "${with_starter:-false}" = true ]; then
      starter_on
      [ "${with_auth:-false}" != true ] \
        || echo "Add the example people, with a password you type: $root/kilnmark.sh starter people"
    fi
  fi
  if $own_provider; then
    cat <<EOF

To sign in with your organisation's identity provider, fill in the KILNMARK_OIDC_ settings in
$root/config/.env, then run $root/kilnmark.sh restart (INSTALL.md, "Sign in with your identity provider").
EOF
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
  local old new tmp src recorded
  old="$(current_version)"
  # An install from before deployment modes is never upgraded (ADR 0051): refused before anything
  # changes, by config/.env and by what the database recorded.
  ensure_settings
  recorded="$(recorded_mode)"
  [ -n "$recorded" ] || die "Kilnmark's database has no deployment mode recorded (or does not answer: start it with ./kilnmark.sh up first). An install from before deployment modes (release 0.1.1 or earlier, a preview) cannot be upgraded: install Kilnmark afresh. Nothing was changed."
  [ "$recorded" = "$(setting KILNMARK_MODE)" ] \
    || die "KILNMARK_MODE in config/.env differs from the mode Kilnmark recorded at its first start: set it back. Nothing was changed."
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
  expiry_timer_remove
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
  # An image another install on this machine still runs is kept: removing it, or its name, would
  # stop that install starting again.
  local i kept=""
  for i in $(image_names "$v"); do
    if [ -n "$("$engine" ps -a --filter "ancestor=$i" --format '{{.Names}}' 2> /dev/null || true)" ]; then
      kept="$kept $i"
    else
      "$engine" rmi "$i" > /dev/null 2>&1 || true
    fi
  done
  [ -z "$kept" ] || echo "Kept the images$kept: other containers on this machine use them (another install, say)."
  echo "Kilnmark's containers, volumes and images are deleted. $root is kept: delete it yourself if you wish."
}

# --- the Starter Pack (ADR 0051) ----------------------------------------------------------------

# Waits for a one-off service (samples-db) of this start to finish; false when it failed or did not
# finish in 5 minutes. By compose's labels, which Docker and Podman both set.
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

# Turns the Starter Pack's example data on through Kilnmark's own API, as Settings > Starter Pack
# does: Kilnmark does the work. The admin token goes to curl in a file in a new folder only this
# account can open, never on a command line; the folder goes however the script ends.
starter_on() {
  local answer code
  set_compose
  one_off_done samples-db \
    || echo "Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: ./kilnmark.sh logs samples-db"
  starter_work="$(mktemp -d)" || die "Could not make a private folder for the request."
  {
    cat <<EOF
header = "Authorization: Bearer $(setting KILNMARK_ADMIN_TOKEN)"
EOF
  } > "$starter_work/curl.conf"
  echo "Turning the Starter Pack on ..."
  code="$(curl -k -s --config "$starter_work/curl.conf" -X POST --max-time 600 -o "$starter_work/answer" \
    -w '%{http_code}' "$(local_url)/api/v1/starter-pack")" || code=000
  answer="$(cat "$starter_work/answer" 2> /dev/null || true)"
  rm -rf "$starter_work"; starter_work=""
  if [ "$code" != 200 ]; then
    echo "The Starter Pack was not turned on (HTTP $code): $(sed -n 's/^{"error":"\(.*\)"}$/\1/p' <<< "$answer")" >&2
    echo "Turn it on later under Settings > Starter Pack." >&2
    return 0
  fi
  # Each step, one a line: "step: outcome".
  grep -o '"step":"[^"]*","outcome":"[^"]*"' <<< "$answer" | sed 's/"step":"\([^"]*\)","outcome":"\([^"]*\)"/  \1: \2/'
  echo "The Starter Pack is on: example data, for training and development only. Turn it off under Settings > Starter Pack."
}

# keycloak-setup runs at every start: waits for it, and tells Kilnmark what it did: anyone it
# disabled for an unused temporary password, and on a Starter Pack install what it did to the example
# people (removed them once they are no longer allowed).
people_after_up() {
  local id out
  if ! one_off_done keycloak-setup; then
    starter_mode || die "keycloak-setup refused or did not finish. See why with: ./kilnmark.sh logs keycloak-setup"
    echo "Warning: keycloak-setup did not finish well. See why with: ./kilnmark.sh logs keycloak-setup" >&2
  fi
  id="$("$engine" ps -a -q --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=keycloak-setup" | sed -n 1p)"
  [ -n "$id" ] || return 0
  # Only its latest run: it says "started" first.
  out="$("$engine" logs "$id" 2>&1 | hide_compose_commands \
    | awk '/^keycloak-setup: started/ { buf = "" } { buf = buf $0 "\n" } END { printf "%s", buf }')"
  record_own_people "$out" expire
  # anyone the pack's removal only took out of its group, not one of the example people
  sed -n 's/^keycloak-setup: \(took .*\)$/\1/p' <<< "$out"
  starter_mode || return 0
  record_people "$out"
}

# A request to Kilnmark's own API as the admin: the token goes to curl in a file in a new folder
# only this account can open, never on a command line; the folder goes however the script ends.
# $1 method, $2 path, $3 JSON body or empty; prints the HTTP code, the answer in $api_answer.
api_answer=""
# Why something other than this account's (or root's) may be answering on Kilnmark's port $1, or
# nothing (ADR 0051): every socket listening on it, on the loopback or all addresses, must be this
# account's or root's (rootless Podman's port forwarder is the account's, Docker's and rootful
# Podman's root's). The kernel lists every socket's owner to everyone (/proc/net/tcp); where it
# does not (Git Bash, where the tests run this edition too), nothing can be checked.
listener_problem() {
  local f hex addr st uid me
  me="$(id -u)" hex="$(printf '%04X' "$1")"
  if [ ! -r /proc/net/tcp ]; then
    test_hooks && return 0
    echo "who listens on port $1 cannot be told here"; return 0
  fi
  for f in /proc/net/tcp /proc/net/tcp6; do
    [ -r "$f" ] || continue
    while read -r _ addr _ st _ _ _ uid _; do
      [ "$st" = 0A ] && [ "${addr##*:}" = "$hex" ] || continue
      case "${addr%:*}" in
        0100007F|00000000|00000000000000000000000000000000|00000000000000000000000001000000|0000000000000000FFFF00000100007F) ;;
        *) continue ;;
      esac
      [ "$uid" = "$me" ] || [ "$uid" = 0 ] || { echo "a program of another account listens on port $1, where Kilnmark should"; return 0; }
    done < <(tail -n +2 "$f")
  done
}
api_call() {
  local code url why
  # the admin token only to a listener of this account's or root's, at 127.0.0.1
  url="$(local_url)"
  why="$(listener_problem "${url##*:}")"
  if [ -n "$why" ]; then
    echo "Warning: $why: the admin token was not sent." >&2
    printf '000'
    return 0
  fi
  # run in a subshell ($(api_call ...)), which has traps of its own: only its own folder goes there
  trap '[ -z "$starter_work" ] || rm -rf "$starter_work"' EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  starter_work="$(mktemp -d)" || die "Could not make a private folder for the request."
  {
    cat <<EOF
header = "Authorization: Bearer $(setting KILNMARK_ADMIN_TOKEN)"
header = "Content-Type: application/json"
EOF
  } > "$starter_work/curl.conf"
  printf '%s' "${3:-}" > "$starter_work/body"
  # -k for Kilnmark's own certificate, whose name need not be localhost: the listener was checked above
  code="$(curl -k -s --config "$starter_work/curl.conf" -X "$1" --max-time 600 -o "$starter_work/answer" \
    --data-binary "@$starter_work/body" -w '%{http_code}' "${url/localhost/127.0.0.1}$2")" || code=000
  api_answer="$(cat "$starter_work/answer" 2> /dev/null || true)"
  rm -rf "$starter_work"; starter_work=""
  printf '%s' "$code"
}

# Tells Kilnmark what keycloak-setup did to the example people (its output, $1), so Kilnmark's
# audit log holds it (starter.people): Kilnmark holds no Keycloak credential to find out itself.
record_people() {
  local action pattern people code
  for action in add new-password remove; do
    case "$action" in
      add) pattern='^keycloak-setup: added \([a-z.]*@kilnmark\.com\) (.*$' ;;
      new-password) pattern='^keycloak-setup: gave \([a-z.]*@kilnmark\.com\) the new password$' ;;
      remove) pattern='^keycloak-setup: removed \([a-z.]*@kilnmark\.com\) (a Starter Pack example person)$' ;;

    esac
    people="$(sed -n "s/$pattern/\"\1\"/p" <<< "$1" | paste -sd, -)"
    [ -n "$people" ] || continue
    code="$(api_call POST /api/v1/starter-pack/people "{\"action\":\"$action\",\"people\":[$people]}")"
    [ "$code" = 204 ] || echo "Warning: Kilnmark did not record this in its audit log (HTTP $code); Keycloak's admin events have it." >&2
  done
}

# The password for the example people: typed twice with the echo off when this runs at a terminal,
# else one line on standard input (a pipe). Never shown, never on a command line, never in a file.
read_people_password() {
  local first second
  if [ -t 0 ]; then
    read -r -s -p "A password for the example people (12 characters or more, not shown): " first; echo >&2
    read -r -s -p "The same again: " second; echo >&2
    [ "$first" = "$second" ] || die "The two did not match. Nothing was changed."
  else
    IFS= read -r first || true
    first="${first%$'\r'}"
  fi
  [ "${#first}" -ge 12 ] || die "The example people's password is 12 characters or more. Nothing was changed."
  [[ "$first" != *[[:cntrl:]]* ]] || die "The password holds a control character (a tab, say), which it may not. Nothing was changed."
  people_password="$first"
}

# The Starter Pack's five example people, made and removed by keycloak-setup with Keycloak's own
# admin: Kilnmark holds no Keycloak credential. Their password is the one you type (or pipe in),
# handed to that one run of keycloak-setup on its standard input: never shown, never in its
# environment, a command line, config/.env or logs/. People already there keep theirs unless
# --new-password. What setup did goes to Kilnmark's audit log.
people_password=""
cmd_starter() {
  local what="${1:-}" action=add a profiles password=""
  [ $# -eq 0 ] || shift
  [ "$what" = people ] || die "Say what: ./kilnmark.sh starter people [--new-password | --remove]"
  for a in "$@"; do
    case "$a" in
      --new-password) action=new-password ;;
      --remove) action=remove ;;
      *) die "Unknown option $a: only --new-password and --remove." ;;
    esac
  done
  current_version > /dev/null; ensure_settings
  profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  [[ ",$profiles," == *",auth,"* ]] \
    || die "The example people need the bundled Keycloak: the auth profile (INSTALL.md, \"Keycloak, if you have no identity provider\")."
  starter_mode || die "This is a production install: it never has the Starter Pack's example people. Nothing was changed."
  if [ "$action" != remove ]; then
    [ "$(setting KILNMARK_STARTER)" = allowed ] \
      || die "Set KILNMARK_STARTER=allowed in config/.env first: the example people are only for trials and training."
    read_people_password
  fi
  [ "$(recorded_mode)" = starter-pack ] \
    || { people_password=""; die "Kilnmark's own record does not say this is a Starter Pack install (or its database does not answer: start it with ./kilnmark.sh up). Nothing was changed."; }
  set_compose
  echo "Asking keycloak-setup to $( [ "$action" = remove ] && echo remove || echo add ) the example people ..."
  local out id ok=true
  id="$(recorded_setting install_id)"
  write_run_secrets
  out="$(printf '%s\n' "$people_password" | KILNMARK_STARTER_PEOPLE_ACTION="$action" KILNMARK_MODE=starter-pack \
    KILNMARK_INSTALL_ID="$id" "${compose[@]}" run --rm -T keycloak-setup 2>&1 | hide_compose_commands)" || ok=false
  drop_run_secrets
  if ! $ok; then
    people_password=""
    printf '%s\n' "$out"
    die "keycloak-setup did not finish: see what it said above. Nothing more was done."
  fi
  people_password=""
  printf '%s\n' "$out"
  record_people "$out"
  if [ "$action" = remove ]; then
    echo "The example people the Starter Pack made are removed from Keycloak; anyone else is left as they are."
  else
    echo "The example people $( [ "$action" = new-password ] && echo "now all sign in" || echo "added just now sign in" ) with the password you gave."
    [ "$action" = new-password ] || echo "(Any that were there already keep theirs: --new-password gives them all a new one.)"
  fi
}

# People of your own in the bundled Keycloak, in either mode (ADR 0051, decision 10): made by one
# run of keycloak-setup with Keycloak's own admin, never by Kilnmark, which holds no Keycloak
# credential. Each is given a temporary password, so Keycloak has them choose their own at their
# first sign-in, and the one-time code applies as for everyone. Never in the Starter Pack's group,
# so its removal never touches them; never at kilnmark.com. The password is typed with the echo
# off, or piped in: never shown, never in config/.env, a command line or logs/; it goes to
# keycloak-setup on its standard input. Kilnmark's own sign-in is not this: Keycloak's own admin
# (KILNMARK_KEYCLOAK_ADMIN) signs in to Keycloak's console only.
person_email="" person_first="" person_last="" person_groups="" person_password="" people_soft=false
# Nobody made: the people command stops; install (people_soft) says so and goes on.
people_no() {
  person_password=""
  if [ "$people_soft" = true ]; then
    echo "$1 Add them later with: $root/kilnmark.sh people add" >&2
  else
    die "$1"
  fi
}
# Asks for one person: at a terminal, the password twice with the echo off; else lines on standard
# input (email, first name, last name, groups unless $1 gives them, password). $1: the groups
# install's first admin gets (kilnmark-admins), or nothing to ask. False when nobody is to be made.
# The quick checks here save typing a password for nothing; keycloak-setup checks it all again.
ask_person() {
  local again
  if [ -t 0 ]; then
    if [ -n "$1" ]; then echo "Kilnmark's first admin: a person of your own, who signs in to Kilnmark with Keycloak."; fi
    read -r -p "Their email, which is their user name$( [ -z "$1" ] || echo ' (Enter to skip)'): " person_email
  else
    IFS= read -r person_email || true
  fi
  person_email="$(printf '%s' "${person_email%$'\r'}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
  if [ -z "$person_email" ]; then
    if [ -n "$1" ]; then
      echo "No first admin was made. Add people later with $root/kilnmark.sh people add (or people import, or Keycloak's console: INSTALL.md, \"Keycloak, if you have no identity provider\")."
    else
      people_no "No email was given. Nobody was made."
    fi
    return 1
  fi
  [[ "$person_email" =~ ^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$ ]] \
    || { people_no "That is not an email address. Nobody was made."; return 1; }
  if [[ "$person_email" == *@kilnmark.com ]] || [[ "$person_email" == *.kilnmark.com ]]; then
    people_no "Use an address of your own organisation: kilnmark.com addresses are the Starter Pack's example people. Nobody was made."
    return 1
  fi
  if [ -t 0 ]; then
    read -r -p "Their first name: " person_first
    read -r -p "Their last name: " person_last
    if [ -n "$1" ]; then person_groups="$1"; else
      read -r -p "Their groups, separated by ; (kilnmark-viewers, kilnmark-stewards, kilnmark-designers, kilnmark-admins, kilnmark-sensitive-data): " person_groups
    fi
    read -r -s -p "A temporary password for them, 12 characters or more (not shown; they choose their own at their first sign-in): " person_password; echo >&2
    read -r -s -p "The same again: " again; echo >&2
    [ "$person_password" = "$again" ] || { people_no "The two did not match. Nobody was made."; return 1; }
  else
    IFS= read -r person_first || true
    IFS= read -r person_last || true
    if [ -n "$1" ]; then person_groups="$1"; else IFS= read -r person_groups || true; fi
    IFS= read -r person_password || true
  fi
  person_first="${person_first%$'\r'}"; person_last="${person_last%$'\r'}"; person_groups="${person_groups%$'\r'}"
  person_password="${person_password%$'\r'}"
  [ -n "$person_first" ] && [ -n "$person_last" ] || { people_no "A person needs a first and a last name. Nobody was made."; return 1; }
  [ -n "${person_groups//[[:space:];,]/}" ] || { people_no "A person needs at least one of Kilnmark's groups. Nobody was made."; return 1; }
  [ "${#person_password}" -ge 12 ] || { people_no "The temporary password is 12 characters or more. Nobody was made."; return 1; }
  [[ "$person_password" != *[[:cntrl:]]* ]] \
    || { people_no "The password holds a control character (a tab, say), which it may not. Nobody was made."; return 1; }
}

# A temporary password, typed twice with the echo off, or one line on standard input: $1 says
# whose. Never shown. Each person chooses their own at their first sign-in.
ask_temporary_password() {
  local again
  if [ -t 0 ]; then
    read -r -s -p "A temporary password for $1, 12 characters or more (not shown): " person_password; echo >&2
    read -r -s -p "The same again: " again; echo >&2
    [ "$person_password" = "$again" ] || { person_password=""; die "The two did not match. Nothing was changed."; }
  else
    IFS= read -r person_password || true
    person_password="${person_password%$'\r'}"
  fi
  [ "${#person_password}" -ge 12 ] || { person_password=""; die "The temporary password is 12 characters or more. Nothing was changed."; }
  [[ "$person_password" != *[[:cntrl:]]* ]] \
    || { person_password=""; die "The password holds a control character (a tab, say), which it may not. Nothing was changed."; }
}

# Kilnmark must answer, as its admin, before anyone is made: a person is never made without being
# recorded in its audit log.
require_kilnmark_answers() {
  local code; code="$(api_call GET "/api/v1/audit?limit=1" "")"
  [ "$code" = 200 ] || die "Kilnmark does not answer (HTTP $code), so nobody was made or changed: a person is never made without a record in its audit log. Start it with ./kilnmark.sh up, then try again."
}

# Tells Kilnmark what keycloak-setup did ($1, its output; $2 add, import, activate or expire, for an
# ordinary start), so Kilnmark's audit log holds it: auth.person_added for each person made, with
# their groups, auth.people_imported for each import, auth.person_activated, and
# auth.person_disabled for each person disabled for an unused temporary password. Never a password.
record_own_people() {
  local made kept refused activated disabled code
  made="$(sed -n 's/^keycloak-setup: made \([a-z0-9._%+@-]*\) (\([a-z;-]*\)): \(they choose their own password\|waiting to be activated\).*$/{"person":"\1","groups":"\2"}/p' <<< "$1" | paste -sd, -)"
  kept="$(sed -n 's/^keycloak-setup: left \([a-z0-9._%+@-]*\) as they are: someone with this address is here already.*$/"\1"/p' <<< "$1" | paste -sd, -)"
  refused="$(sed -n 's/^keycloak-setup: people import: made [0-9]*, already there [0-9]*, refused \([0-9]*\).*$/\1/p' <<< "$1" | tail -n 1)"
  activated="$(sed -n 's/^keycloak-setup: activated \([a-z0-9._%+@-]*\): they choose their own password.*$/"\1"/p' <<< "$1" | paste -sd, -)"
  disabled="$(sed -n 's/^keycloak-setup: disabled \([a-z0-9._%+@-]*\): their temporary password was not used.*$/"\1"/p' <<< "$1" | paste -sd, -)"
  # what an ordinary run of setup did, before the command's own work: its own record
  [ -z "$disabled" ] || post_disabled "$disabled"
  if grep -q '^keycloak-setup: stamped the realm' <<< "$1"; then
    code="$(api_call POST /api/v1/auth/people '{"via":"stamp"}')"
    [ "$code" = 204 ] || echo "Warning: Kilnmark did not record the realm's stamp in its audit log (HTTP $code)." >&2
  fi
  case "$2" in
    expire|stamp) return 0 ;;
    activate) [ -n "$activated" ] || return 0
      code="$(api_call POST /api/v1/auth/people "{\"via\":\"activate\",\"activated\":[$activated]}")" ;;
    *) [ -n "$made$kept" ] || [ "$2" = import ] || return 0
      code="$(api_call POST /api/v1/auth/people "{\"via\":\"$2\",\"made\":[$made],\"kept\":[$kept],\"refused\":${refused:-0}}")" ;;
  esac
  # Those given a temporary password just now wait, disabled, for this record: enabled only once
  # Kilnmark has it (fail closed), so a run cut short leaves nobody able to sign in unrecorded.
  local todo kept_out="$people_out" p left=""
  todo="$(sed -n 's/^keycloak-setup: made \([a-z0-9._%+@-]*\) (.*): they choose their own password.*$/\1/p; s/^keycloak-setup: activated \([a-z0-9._%+@-]*\): .*$/\1/p' <<< "$1")"
  if [ "$code" != 204 ]; then
    echo "Warning: Kilnmark did not record this in its audit log (HTTP $code); Keycloak's admin events have it." >&2
    [ -z "$todo" ] || echo "So they stay disabled, until Kilnmark answers: then give each a temporary password with people activate EMAIL." >&2
    return 0
  fi
  [ -n "$todo" ] || return 0
  run_people_setup enable <<< "$todo" || true
  for p in $todo; do grep -qxF "keycloak-setup: enabled $p" <<< "$people_out" || left="$left $p"; done
  people_out="$kept_out"
  [ -z "$left" ] || echo "Warning: recorded, but not enabled:$left. Give each a temporary password with people activate EMAIL." >&2
}

# Who was disabled ($1: quoted addresses, joined by commas), recorded 500 at a time: Kilnmark takes
# at most 1000 people in one record.
post_disabled() {
  local all chunk="" n=0 p code
  IFS=, read -r -a all <<< "$1"
  for p in "${all[@]}" ""; do
    if [ -n "$p" ]; then chunk="${chunk:+$chunk,}$p"; n=$((n + 1)); fi
    if [ -n "$chunk" ] && { [ "$n" -eq 500 ] || [ -z "$p" ]; }; then
      code="$(api_call POST /api/v1/auth/people "{\"via\":\"expire\",\"disabled\":[$chunk]}")"
      [ "$code" = 204 ] || echo "Warning: Kilnmark did not record who was disabled in its audit log (HTTP $code); Keycloak's admin events have it." >&2
      chunk="" n=0
    fi
  done
}

# One run of keycloak-setup for the people command: $1 the action; its standard input is this
# function's. It is handed the mode Kilnmark recorded (check_people_mode), never config/.env's alone,
# and the install's id. Its output in people_out; false when it failed.
people_out=""
run_people_setup() {
  local id rc=0
  [ -n "$people_mode" ] || die "Kilnmark's own record of its deployment mode was not read, so nobody was made or changed."
  set_compose
  id="$(recorded_setting install_id)"
  write_run_secrets
  people_out="$(KILNMARK_PEOPLE_ACTION="$1" KILNMARK_PERSON_EMAIL="$person_email" \
    KILNMARK_PERSON_FIRST_NAME="$person_first" KILNMARK_PERSON_LAST_NAME="$person_last" \
    KILNMARK_PERSON_GROUPS="$person_groups" KILNMARK_MODE="$people_mode" KILNMARK_INSTALL_ID="$id" \
    "${compose[@]}" run --rm -T keycloak-setup 2>&1 | hide_compose_commands)" || rc=$?
  drop_run_secrets
  return "$rc"
}

# Makes the person ask_person asked for, then records it. Keycloak and Kilnmark must be up.
make_person() {
  local ok=true
  require_kilnmark_answers
  check_people_mode
  if [ -n "$mode_problem" ]; then people_no "$mode_problem"; return 1; fi
  echo "Asking keycloak-setup to add $person_email ..."
  # Fed by a process substitution, not a pipe, so people_out is set here; printf is a builtin.
  run_people_setup add < <(printf '%s\n' "$person_password") || ok=false
  person_password=""
  printf '%s\n' "$people_out"
  if ! $ok; then record_own_people "$people_out" add; people_no "keycloak-setup stopped: see what it said above."; return 1; fi
  record_own_people "$people_out" add
  if grep -qF "keycloak-setup: made $person_email (" <<< "$people_out"; then
    echo "$person_email signs in to Kilnmark with that password, then chooses their own and sets up a one-time code."
  else
    echo "$person_email was here already, so was left as they are: nothing was changed."
  fi
}

cmd_people() {
  local what="${1:-}" profiles file ok=true
  [ $# -eq 0 ] || shift
  current_version > /dev/null; ensure_settings
  profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  [[ ",$profiles," == *",auth,"* ]] \
    || die "This install does not use the bundled Keycloak: add people in your organisation's own identity provider, or with API tokens only, make tokens under API tokens. The people commands are for the bundled Keycloak (the auth profile)."
  case "$what" in
    add)
      [ $# -eq 0 ] || die "people add takes no options: it asks."
      require_kilnmark_answers
      require_people_mode
      ask_person "" || return 0
      make_person ;;
    import)
      file="${1:-}"
      [ $# -eq 1 ] && [ -f "$file" ] || die "Say which file: ./kilnmark.sh people import people.csv (an example: containers/keycloak/people.example.csv in the release)."
      require_kilnmark_answers
      require_people_mode
      # A class on a Starter Pack install shares one temporary password; on production nobody is
      # given one: each waits, disabled, for people activate. By the recorded mode.
      if [ "$people_mode" = starter-pack ]; then ask_temporary_password "everyone in the file"; else person_password=""; fi
      echo "Asking keycloak-setup to add the people in the file ..."
      if [ "$people_mode" = starter-pack ]; then
        run_people_setup import < <(printf '%s\n' "$person_password"; cat -- "$file") || ok=false
      else
        run_people_setup import < "$file" || ok=false
      fi
      person_password=""
      printf '%s\n' "$people_out"
      record_own_people "$people_out" import
      $ok || die "keycloak-setup stopped: see what it said above (how many it made, if any, before it stopped)."
      if [ "$people_mode" = starter-pack ]; then
        echo "Each person made signs in with that password, then chooses their own and sets up a one-time code."
      else
        echo "Each person made waits, disabled, until you give them a temporary password of their own: $root/kilnmark.sh people activate EMAIL"
      fi ;;
    activate)
      [ $# -eq 1 ] || die "Say whom: ./kilnmark.sh people activate EMAIL"
      person_email="$(printf '%s' "$1" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
      [[ "$person_email" =~ ^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$ ]] || die "That is not an email address. Nothing was changed."
      require_kilnmark_answers
      require_people_mode
      ask_temporary_password "$person_email"
      echo "Asking keycloak-setup to activate $person_email ..."
      run_people_setup activate < <(printf '%s\n' "$person_password") || ok=false
      person_password=""
      printf '%s\n' "$people_out"
      record_own_people "$people_out" activate
      $ok || die "Nobody was activated: see what keycloak-setup said above."
      echo "$person_email signs in to Kilnmark with that password, then chooses their own and sets up a one-time code." ;;
    list)
      [ $# -eq 0 ] || die "people list takes no options."
      require_people_mode
      run_people_setup list < /dev/null || { printf '%s\n' "$people_out"; die "keycloak-setup did not finish: see what it said above."; }
      record_own_people "$people_out" expire
      sed -n 's/^keycloak-setup: \(disabled .*\)$/\1/p; s/^keycloak-setup: person \(.*\)$/\1/p; s/^keycloak-setup: people: \(.*\)$/people: \1/p' <<< "$people_out"
      echo "(status=waiting-to-be-activated: give them a temporary password with people activate EMAIL; must-choose-password=yes: they have not yet signed in with it.)" ;;
    expire)
      # Every hour, from the timer up sets up; by hand too. Nothing to do while Keycloak is stopped:
      # nobody signs in then, and each start expires them first.
      [ $# -eq 0 ] || die "people expire takes no options."
      # the tree it runs from was checked first (expire_guard)
      local kcp; kcp="$(setting KILNMARK_KEYCLOAK_PORT)"
      if ! curl -s -f -o /dev/null --max-time 10 "http://localhost:${kcp:-8180}/realms/kilnmark"; then
        echo "Keycloak does not answer, so nothing was expired now: each start of Kilnmark expires unused temporary passwords too."
        return 0
      fi
      require_people_mode
      run_people_setup expire < /dev/null || { printf '%s\n' "$people_out"; die "keycloak-setup did not finish: see what it said above."; }
      record_own_people "$people_out" expire
      sed -n 's/^keycloak-setup: \(disabled .*\)$/\1/p' <<< "$people_out"
      echo "people expire: done." ;;
    *) die "Say what: ./kilnmark.sh people add | people import FILE | people activate EMAIL | people list | people expire" ;;
  esac
}

# --- backup and restore (the scripts beside compose.yaml) --------------------------------------

# backup.sh and restore.sh split KILNMARK_COMPOSE on spaces, which is why the root has none.
compose_line() { echo "${compose[*]}"; }

# on when the bundled Keycloak is (the auth profile): backup.sh and restore.sh take its database too.
keycloak_on() {
  if [[ ",$(setting COMPOSE_PROFILES | tr -d '[:space:]')," == *",auth,"* ]]; then echo on; else echo off; fi
}

cmd_backup() {
  local dest="${1:-$root/backups}"
  current_version > /dev/null; set_compose
  mkdir -p "$dest"
  (cd "$root/current" && KILNMARK_COMPOSE="$(compose_line)" KILNMARK_KEYCLOAK="$(keycloak_on)" sh ./backup.sh "$dest")
}

cmd_restore() {
  current_version > /dev/null; set_compose
  # its last step starts Kilnmark: the one-offs get their secrets for that run only
  write_run_secrets
  (cd "$root/current" && KILNMARK_COMPOSE="$(compose_line)" KILNMARK_KEYCLOAK="$(keycloak_on)" \
    KILNMARK_EXPECTED_MODE="$(setting KILNMARK_MODE)" sh ./restore.sh "$@")
  case " $* " in *" --yes "*) after_one_offs ;; esac
  drop_run_secrets
}

# --- main --------------------------------------------------------------------------------------

# All in one function, read whole before it runs: upgrade replaces this file under a running copy.
# people expire runs as this account every hour: never what someone else may have changed (ADR 0051,
# decision 15). Checked before anything of the install is read or written, its log too; a refusal
# reads nothing more (never config/.env, nor the admin token in it), and goes to the system's
# journal (a timer's run goes there anyway) and to standard error.
expire_guard() {
  local why r log want as_root=0
  [ -n "$root" ] || die "Kilnmark is not installed here. Run: ./kilnmark.sh install"
  why="$(tree_problem)"
  [ -n "$why" ] || why="$(tools_problem)"
  [ -n "$why" ] || return 0
  logger -t "kilnmark-people-expire" -p user.warning "people expire was refused: $why" > /dev/null 2>&1 || true
  # and a line in the day's log, where logs/ passes the same check and that log is a plain file
  if [ "$(expiry_uid)" -eq 0 ]; then want=0; as_root=1; else want="$(id -u)"; fi
  r="$(root_real)"
  if [ -n "$r" ] && [ -d "$r/logs" ] && [ -z "$(owned_problem "$r/logs")" ]; then
    log="$r/logs/people-expire-$(date -u +%Y%m%d).log"
    if [ ! -L "$log" ] && { [ ! -e "$log" ] || [ -f "$log" ]; }; then
      printf '%s people expire was refused: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$why" >> "$log" 2> /dev/null || true
    fi
  fi
  die "people expire was refused: $why. Make it so, then run it again."
}

main() {
command="${1:-up}"
[ $# -eq 0 ] || shift
case "$command" in
  prepare) cmd_prepare "$@" ;;
  install) cmd_install "$@" ;;
  up|start) start_log up; cmd_up "$@" ;;
  down|stop) start_log down; cmd_down ;;
  restart) start_log restart; cmd_restart "$@" ;;
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
      if auth_on; then
        expiry="$(expiry_timer_state)"
        echo "People expiry: $expiry"
        [ "$expiry" != "not set up" ] || warnings+=("The hourly expiry of unused temporary passwords is not set up: ./kilnmark.sh up sets it up (a systemd timer).")
      fi
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
  starter) start_log starter; cmd_starter "$@" ;;
  people) if [ "${1:-}" = expire ]; then expire_guard; start_log people-expire daily; else start_log people; fi; cmd_people "$@" ;;
  restore) start_log restore; cmd_restore "$@" ;;
  upgrade) cmd_upgrade "$@" ;;
  uninstall) cmd_uninstall "$@" ;;
  version) if [ -n "$root" ]; then echo "Kilnmark $(current_version)"; else echo "Kilnmark $(version_of "$here") (not installed)"; fi ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
}
main "$@"; exit $?
