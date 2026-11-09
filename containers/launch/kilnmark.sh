#!/bin/bash
# Kilnmark's control script for Linux (INSTALL.md; ADR 0044, ADR 0051 decision 17). One script
# installs, starts, stops, upgrades and backs up Kilnmark with Podman or Docker:
#   sudo ./kilnmark.sh install [--answers install.conf] [--offline IMAGES]   the guided install
#   /opt/kilnmark/kilnmark.sh up | down | restart | status | logs | check | configure | backup | ...
#   /opt/kilnmark/kilnmark.sh help
# install, upgrade and uninstall run as root (with sudo); every other command runs as the account
# that runs Kilnmark (kilnmark unless install was told another), never as root. Run with no
# command, it starts Kilnmark (up), so a menu entry or a double-click starts it.
# The program folder (/opt/kilnmark, root's) holds versions/<version>/ (a release, never edited),
# current (which version runs), this script and data-folder.txt, which names the data folder
# (/var/opt/kilnmark, the account's): config/.env (this site's settings and secrets),
# config/install.conf (the install's answers), certs/, backups/ and logs/. Data stays in the compose
# project's volumes (kilnmark_pgdata, kilnmark_keys, ...). No command prints a secret.
set -euo pipefail
umask 077

here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
registry="ghcr.io/jporeilly"
default_prog="/opt/kilnmark"
# Variable data of a package in /opt belongs in /var/opt (the Filesystem Hierarchy Standard).
default_data="/var/opt/kilnmark"
# The compose project (set once the data folder is known, below): KILNMARK_PROJECT from the
# environment, else config/.env's, else kilnmark.
project="${KILNMARK_PROJECT:-}"
# Compose fills ${NAME} from this shell's environment before config/.env. These must mean what
# config/.env says, as this script reads them (ADR 0051), so a value exported here never counts.
unset KILNMARK_MODE KILNMARK_STARTER KILNMARK_STARTER_PEOPLE_ACTION \
  KILNMARK_PEOPLE_ACTION KILNMARK_PERSON_EMAIL KILNMARK_PERSON_FIRST_NAME KILNMARK_PERSON_LAST_NAME \
  KILNMARK_PERSON_GROUPS KILNMARK_INSTALL_ID KILNMARK_RUN_DIR KILNMARK_SECRETS_DIR KILNMARK_ENV_FILE \
  KILNMARK_OIDC_VIEWER_GROUPS KILNMARK_OIDC_STEWARD_GROUPS KILNMARK_OIDC_DESIGNER_GROUPS \
  KILNMARK_OIDC_ADMIN_GROUPS KILNMARK_OIDC_UNMASK_GROUPS KILNMARK_KEYCLOAK_BIND KILNMARK_HOST_ADDRESS

# Rootless Podman needs the account's systemd session; sudo -iu and su do not set it up, but its
# runtime folder exists once lingering is on (install turns it on).
if [ "$(id -u)" -ne 0 ] && [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$(id -u)" ]; then
  export XDG_RUNTIME_DIR="/run/user/$(id -u)"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
fi

# --- progress: [n/N] steps, each OK or FAILED, with the time it took ---------------------------
step_n=0 step_total=0 step_title="" step_began=""
now() { if [ -n "${EPOCHREALTIME:-}" ]; then printf '%s' "${EPOCHREALTIME/,/.}"; else date +%s; fi; }
took() { awk -v a="$step_began" -v b="$(now)" 'BEGIN { printf "%.1f s", (b - a < 0 ? 0 : b - a) }'; }
steps() { step_total="$1"; step_n="${2:-0}"; }
step() { step_n=$((step_n + 1)); step_title="$1"; step_began="$(now)"; echo "[$step_n/$step_total] $1"; }
step_ok() { [ -z "$step_title" ] || echo "[$step_n/$step_total] OK ($(took)): $step_title"; step_title=""; }
# A failure inside a step ends it FAILED, with the time it took and one line saying why.
die() {
  if [ -n "$step_title" ]; then
    echo "[$step_n/$step_total] FAILED ($(took)): $step_title: $*" >&2
    step_title=""
  else
    echo "Error: $*" >&2
  fi
  exit 1
}
note() { echo "$*"; }

# However this script ends, the private folders it made go: the one-offs' secrets
# (write_run_secrets) and a request's (api_call, starter_on).
starter_work="" run_secrets=""
cleanup() {
  [ -z "$starter_work" ] || rm -rf "$starter_work"
  [ -z "$run_secrets" ] || rm -rf "$run_secrets"
  [ -z "${images_tmp:-}" ] || rm -rf "$images_tmp"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<'EOF'
Kilnmark control script.

Installing (as root, with sudo: it asks for it and says why):
  install [--answers FILE] [--starter] [--program-dir DIR] [--data-dir DIR] [--user NAME]
          [--offline IMAGES.tar.gz] [--build] [--skip-verify] [--no-start] [--dry-run]
                       The guided install: it asks the questions (the mode, how people sign in,
                       the email domain, the host name, the ports, TLS, the first admin), each
                       with its default and checked as you answer, or reads them from an answers
                       file (install.conf.example says every key; it holds no secrets). Then:
                       the service account, the program folder (/opt/kilnmark, root's), the data
                       folder (/var/opt/kilnmark, the account's), settings with new secrets, the
                       images, the start and the first admin, each step OK or FAILED.
                       --dry-run checks the answers and the prerequisites and changes nothing.
  upgrade ARCHIVE [--offline IMAGES] [--no-backup] [--skip-verify]
                       Back up, place the new version beside this one, and start it.
  uninstall [--purge]  Stop Kilnmark, remove its hourly timer and the program folder. --purge
                       also deletes its volumes, images and the data folder (backups too).

Every day, as the account that runs Kilnmark (never as root):
  up [--no-browser]    Start Kilnmark (and Keycloak with the bundled Keycloak); wait until it
                       answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything (a stop, then up), or one service.
  status               Version, folders, containers, health and the last backup.
  logs [SERVICE] [-f]  The last 200 lines of the logs, or follow them.
  compose ARGS ...     Run compose as Kilnmark runs it (its project, settings, secrets and
                       profiles), for a command by hand: compose ps, compose exec postgres ...
  check                Check the prerequisites and the install, for its mode, changing nothing:
                       each problem with the command that fixes it.
  configure [KEY=VALUE ...]
                       Show the install's answers, or change one: checked, written to
                       config/.env, and only what it affects run again. The mode never changes.
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
  verify FILE [--key PUB]   Check a downloaded file's signature (cosign) against Kilnmark's key.
  load-images FILE [--skip-verify]   Load the offline images file (podman/docker load).
  pull [--skip-verify] Pull this version's images from ghcr.io and check their signatures.
  build                Build the images here from containers/, instead of pulling or loading.
  backup [DIR]         Back up the database and the keys (default: the data folder's backups).
  restore BACKUP [--yes] [--without-keys]   Put Kilnmark back to a backup.
  version | help
EOF
}

# --- where things are ------------------------------------------------------------------------

# The program folder: this script's folder when it holds versions/, else two folders up when this
# script runs from versions/<version>/, else (a release folder not yet installed) none.
find_root() {
  if [ -n "${KILNMARK_ROOT:-}" ]; then printf '%s' "$KILNMARK_ROOT"; return; fi
  if [ -d "$here/versions" ]; then printf '%s' "$here"; return; fi
  if [ "$(basename "$(dirname "$here")")" = versions ]; then (cd "$here/../.." && pwd); return; fi
  printf ''
}
root="$(find_root)"

# The data folder, as data-folder.txt in the program folder names it (install writes it; only root
# may change it): empty when Kilnmark is not installed here.
find_data() {
  local d=""
  [ -n "$root" ] && [ -f "$root/data-folder.txt" ] || { printf ''; return; }
  IFS= read -r d < "$root/data-folder.txt" || true
  printf '%s' "${d%$'\r'}"
}
data="$(find_data)"

# The release a folder holds, from its compose.yaml's server image.
version_of() {
  sed -n 's/^ *image: .*\/kilnmark-server:\([^ ]*\) *$/\1/p' "$1/compose.yaml" 2>/dev/null | head -n 1
}

current_version() {
  [ -n "$root" ] || die "Kilnmark is not installed here. Install it with: sudo ./kilnmark.sh install"
  [ -L "$root/current" ] || [ -d "$root/current" ] || die "No version is installed in $root."
  [ -n "$data" ] || die "$root/data-folder.txt does not name Kilnmark's data folder: install Kilnmark afresh (sudo ./kilnmark.sh install)."
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
    || die "'$engine compose' does not work: Podman needs podman-compose ($(pkg_install podman-compose)), Docker its compose plugin ($(pkg_install docker-compose-plugin)). Then run this again."
}

# A setting from config/.env as compose reads it (an export in front too); empty when it is not set.
setting() {
  local line value file="$data/config/.env"
  [ -n "$data" ] || { printf ''; return; }
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
[ -n "$project" ] || project="$(setting KILNMARK_PROJECT)"
project="${project:-kilnmark}"

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
    -c "SELECT value FROM kilnmark.server_setting WHERE key = '$1'" < /dev/null 2> /dev/null | tr -d '[:space:]' || true
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
  [ -f "$data/config/.env" ] || die "There is no $data/config/.env. Install Kilnmark with: sudo ./kilnmark.sh install"
  write_service_secrets
  compose=("$engine" compose -p "$project" --env-file "$compose_env" -f "$dir/compose.yaml")
  # HTTPS's certificate and key, in the data folder: by its full path, which compose takes as it is
  export KILNMARK_CERTS_DIR="$data/certs/https"
  local profiles; profiles="$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  # Some compose tools read COMPOSE_PROFILES from the env file and some do not: name each one.
  local p; IFS=',' read -r -a list <<< "$profiles"
  for p in "${list[@]:-}"; do [ -z "$p" ] || compose+=(--profile "$p"); done
  # The Starter Pack's database is made only on a Starter Pack install (ADR 0051).
  ! samples_allowed || compose+=(--profile starter-pack)
}

# A value set_line writes as it is and setting reads back the same: no space, quote mark, #, $,
# backtick, backslash or control character. For the client secret, which is typed or piped in.
# (A regular expression held in a variable: bash versions read a backslash in a bracket pattern
# differently.)
plain_value() { local re='[[:space:][:cntrl:]"'"'"'#$`\\]'; [[ -n "$1" && ! "$1" =~ $re ]]; }
# The secrets of the services that keep running (ADR 0051, decision 13): never a setting of their
# containers, which compose tools put on podman's command line (podman-compose puts an env_file's
# lines there too), where other users of the machine can read them. A file for each service,
# NAME=value a line, as setting reads config/.env, which compose mounts read-only into that service
# alone (/run/kilnmark/env) and containers/kilnmark-env.sh reads into that program's environment.
# In config/run/services, a folder this account's alone (700), each file readable inside its
# container whichever user that runs as (644: the folder keeps everyone else out), written afresh
# from config/.env before every compose command. They hold nothing config/.env does not. Never
# written as root (check may run so). A command that stops or looks (down, status, logs, check,
# uninstall's stop) never fails over them: what it does needs no secret.
# Compose itself reads config/.env without its secrets (compose.env beside them, $compose_env):
# compose.yaml names none, and a secret compose would read as more (a $) can then never stop
# compose, down and stop included. A secret holding $ or a backtick is refused by its name, as
# set_line refuses one.
compose_env=""
stopping_command() { case "${command:-}" in down|stop|status|logs|check|_stop-for-uninstall) return 0 ;; *) return 1 ;; esac; }
service_secret_line() {
  local v; v="$(setting "$2")"
  [ -z "$v" ] || printf '%s=%s\n' "$1" "$v"
}
service_secrets_for=""
write_service_secrets() {
  local dir="$data/config/run/services" sig f tmp s v why=""
  export KILNMARK_SECRETS_DIR="$dir"
  # as root (check), the files the account wrote, never written: compose still finds them
  if [ "$(my_uid)" -eq 0 ]; then
    if [ -f "$dir/compose.env" ] && [ ! -L "$dir/compose.env" ]; then compose_env="$dir/compose.env"; else compose_env="$data/config/.env"; fi
    return 0
  fi
  sig="$(cksum < "$data/config/.env")"
  if [ "$sig" = "$service_secrets_for" ] && [ -d "$dir" ]; then compose_env="$dir/compose.env"; return 0; fi
  mkdir -p "$dir" && chmod 700 "$data/config/run" "$dir" && [ ! -L "$dir" ] \
    || die "Could not make $dir, this account's alone, for the services' secrets. Nothing was started."
  # config/.env as compose reads it: every line but a secret's (a name ending _PASSWORD, _SECRET or _TOKEN)
  tmp="$(mktemp "$dir/.compose.XXXXXX")" || die "Could not write $dir/compose.env. Nothing was started."
  grep -v -E '^[[:space:]]*(export[[:space:]]+)?[A-Za-z0-9_]*(_PASSWORD|_SECRET|_TOKEN)[[:space:]]*=' "$data/config/.env" > "$tmp" || true
  chmod 600 "$tmp" && mv -f "$tmp" "$dir/compose.env" || die "Could not write $dir/compose.env. Nothing was started."
  compose_env="$dir/compose.env"
  for s in KILNMARK_DB_PASSWORD KILNMARK_ADMIN_TOKEN; do
    [ -n "$why" ] || [ -n "$(setting "$s")" ] \
      || why="$s is not set in $data/config/.env: Kilnmark has no database password or admin token without it. Put back the line install wrote. Nothing was started."
  done
  for s in KILNMARK_DB_PASSWORD KILNMARK_ADMIN_TOKEN KILNMARK_SAMPLES_DB_PASSWORD KILNMARK_OIDC_CLIENT_SECRET \
    KILNMARK_KEYCLOAK_DB_PASSWORD KILNMARK_KEYCLOAK_ADMIN_PASSWORD; do
    [ -n "$why" ] || [[ "$(setting "$s")" != *[\$\`]* ]] \
      || why="$s in config/.env holds \$ or \`, which compose tools would read as the start of another setting's name: put back the value install made, or one without them. Nothing was started."
  done
  if [ -n "$why" ]; then
    # what stops or looks needs no secret, and never fails over one
    ! stopping_command || return 0
    die "$why"
  fi
  for f in postgres kilnmark keycloak-db keycloak; do
    tmp="$(mktemp "$dir/.$f.XXXXXX")" || die "Could not write the services' secrets in $dir. Nothing was started."
    case "$f" in
      postgres) service_secret_line POSTGRES_PASSWORD KILNMARK_DB_PASSWORD ;;
      kilnmark) service_secret_line QUARKUS_DATASOURCE_PASSWORD KILNMARK_DB_PASSWORD
        service_secret_line KILNMARK_ADMIN_TOKEN KILNMARK_ADMIN_TOKEN
        service_secret_line KILNMARK_SAMPLES_DB_PASSWORD KILNMARK_SAMPLES_DB_PASSWORD
        service_secret_line QUARKUS_OIDC_CREDENTIALS_SECRET KILNMARK_OIDC_CLIENT_SECRET ;;
      keycloak-db) service_secret_line POSTGRES_PASSWORD KILNMARK_KEYCLOAK_DB_PASSWORD ;;
      keycloak) service_secret_line KC_DB_PASSWORD KILNMARK_KEYCLOAK_DB_PASSWORD
        service_secret_line KC_BOOTSTRAP_ADMIN_PASSWORD KILNMARK_KEYCLOAK_ADMIN_PASSWORD ;;
    esac > "$tmp" || { rm -f "$tmp"; exit 1; }
    # readable inside the container, whichever user it runs as: the folder keeps everyone else out
    chmod 644 "$tmp" && mv -f "$tmp" "$dir/$f.env" || die "Could not write the services' secrets in $dir. Nothing was started."
  done
  service_secrets_for="$sig"
}

image_names() { echo "$registry/kilnmark-server:$1 $registry/kilnmark-postgres:$1"; }

# The few secrets each one-off needs (keycloak-setup: Keycloak's admin password and the client
# secret; samples-db: the database's password and the samples login's), each set in a file of its
# own, in a new folder only this account can open under config/run, for one run: compose mounts
# them read-only (KILNMARK_RUN_DIR, by its full path), never config/.env whole. Gone once the run is
# over (drop_run_secrets), and however this script ends; one a killed run left goes a day on.
run_secret_line() {
  local v; v="$(setting "$1")"
  if [[ "$v" != *"'"* ]]; then printf "%s='%s'\n" "$1" "$v"
  elif [[ "$v" != *'"'* ]]; then printf '%s="%s"\n' "$1" "$v"
  else die "$1 in config/.env holds both kinds of quote mark, which compose tools do not all read alike: choose another. Nothing was changed."
  fi
}
write_run_secrets() {
  local base="$data/config/run"
  [ -z "$run_secrets" ] || return 0
  mkdir -p "$base" && chmod 700 "$base" || die "Could not make $base for the one-offs' secrets."
  find "$base" -mindepth 1 -maxdepth 1 -type d -name 'r.*' -mmin +1440 -exec rm -rf {} + 2> /dev/null || true
  run_secrets="$(mktemp -d "$base/r.XXXXXXXX")" || die "Could not make a private folder in $base."
  { run_secret_line KILNMARK_KEYCLOAK_ADMIN_PASSWORD; run_secret_line KILNMARK_OIDC_CLIENT_SECRET; } > "$run_secrets/keycloak-setup.env"
  { run_secret_line KILNMARK_DB_PASSWORD; run_secret_line KILNMARK_SAMPLES_DB_PASSWORD; } > "$run_secrets/samples-db.env"
  # readable inside the containers, whichever user each runs as: the folder keeps everyone else out
  chmod 644 "$run_secrets/keycloak-setup.env" "$run_secrets/samples-db.env"
  export KILNMARK_RUN_DIR="$run_secrets"
}
drop_run_secrets() {
  [ -z "$run_secrets" ] || rm -rf "$run_secrets"
  run_secrets=""
  unset KILNMARK_RUN_DIR
}

# --- the hourly expiry of unused temporary passwords (ADR 0051) -----------------------------------

# With the bundled Keycloak, people expire runs every hour from a systemd timer of the account that
# runs Kilnmark (never root's: no day-to-day command runs as root), so a temporary password left
# unused is disabled at most an hour after its window, whether or not Kilnmark starts again. up sets
# it up, and removes it without the bundled Keycloak; uninstall removes it. KILNMARK_SYSTEMCTL,
# KILNMARK_SYSTEMD_DIR and KILNMARK_TEST_UID are for the tests only, and count only with their
# marker, which no release sets.
expiry_unit="kilnmark-$project-people-expire"
test_hooks() { [ "${KILNMARK_TEST_HOOKS:-}" = only-in-kilnmark-tests ]; }
# who runs this
my_uid() { if test_hooks && [ -n "${KILNMARK_TEST_UID:-}" ]; then printf '%s' "$KILNMARK_TEST_UID"; else id -u; fi; }
expiry_systemctl() {
  local sc=systemctl
  if test_hooks && [ -n "${KILNMARK_SYSTEMCTL:-}" ]; then sc="$KILNMARK_SYSTEMCTL"; fi
  "$sc" --user "$@"
}
expiry_dir() {
  if test_hooks && [ -n "${KILNMARK_SYSTEMD_DIR:-}" ]; then printf '%s' "$KILNMARK_SYSTEMD_DIR"
  else printf '%s' "${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  fi
}
# A path as this shell's own: on Git Bash, where the tests run this edition too, a Windows path as
# its POSIX one, so a folder and where a link leads are named alike (no Linux has cygpath).
posix_path() {
  if [[ "$1" =~ ^[A-Za-z]:[\\/] ]] && command -v cygpath > /dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}
# A folder by its canonical path, once, so the folders checked are the ones a run uses; empty when
# it holds anything a unit file would read as more than a path (systemd's % specifiers, a quote, a
# backslash, a control character). A space is fine: the unit quotes the path.
real_path() {
  local r; r="$(readlink -f "$(posix_path "$1")" 2> /dev/null || true)"
  if [[ "$r" =~ ^/[A-Za-z0-9._/+@,=\ -]*$ ]]; then printf '%s' "$r"; fi
}
root_real() { real_path "$root"; }
# Why $1 may be changed by someone other than $want (a uid) or root, or nothing: it must be theirs,
# never a link, and writable by neither its group nor others.
owned_problem() {
  local info
  if [ -L "$1" ]; then echo "$1 is a link, which an hourly run would follow"; return 0; fi
  info="$(stat -c '%u %a' "$1" 2> /dev/null || echo "x 777")"
  if { [ "${info%% *}" != "$want" ] && [ "${info%% *}" != 0 ]; } || (( (8#${info##* } & 8#022) != 0 )); then
    echo "$1 may be changed by someone other than root or the account that runs Kilnmark (it is neither's own, or others may write it)"
  fi
}
# Why someone other than root or the account that runs Kilnmark may change what an hourly run uses,
# or nothing (ADR 0051, decisions 15 and 17): the timer runs kilnmark.sh from the program folder as
# the account, and that run reads data-folder.txt, the current version's compose.yaml and
# keycloak-setup's script and kilnmark-env.sh there, and config/.env in the data folder, and writes to its logs/. Each,
# and every folder from its root down to it, must be root's or the account's, no link but current,
# and writable by nobody else; current must be a link of theirs to a folder directly in versions/.
# Above each root, so nobody can move it away and put another in its place, every folder up to /
# must be root's or the account's and writable by nobody else; a folder others may write (/tmp)
# only where it is sticky and the next one down is root's or the account's.
tree_problem() {
  local want r d cur v item base rel rest p seen=" " why info
  want="$(id -u)"
  r="$(root_real)"
  [ -n "$r" ] || { echo "the program folder's path holds a character a systemd unit would read as more than a path (%, a quote, a backslash or a control character)"; return 0; }
  d="$(real_path "$data")"
  [ -n "$d" ] || { echo "the data folder's path holds a character a systemd unit would read as more than a path (%, a quote, a backslash or a control character)"; return 0; }
  # the link itself only by its owner (a link's own permissions mean nothing)
  info="$(stat -c '%u' "$r/current" 2> /dev/null || echo x)"
  [ -L "$r/current" ] && { [ "$info" = "$want" ] || [ "$info" = 0 ]; } \
    || { echo "$r/current may be changed by someone other than root or the account that runs Kilnmark (it is not a link of theirs to a version)"; return 0; }
  cur="$(readlink -f "$r/current" 2> /dev/null || true)"
  v="${cur#"$r/versions/"}"
  if [ -z "$cur" ] || [ "$v" = "$cur" ] || [ -z "$v" ] || [[ "$v" == */* ]] || [ ! -d "$cur" ]; then
    echo "$r/current does not lead to a version in $r/versions"; return 0
  fi
  for item in "$r|kilnmark.sh" "$r|data-folder.txt" "$r|versions/$v/compose.yaml" "$r|versions/$v/containers/keycloak/setup.sh" \
    "$r|versions/$v/containers/kilnmark-env.sh" "$d|config/.env" "$d|logs"; do
    base="${item%%|*}" rel="${item#*|}"
    [ "$rel" != logs ] || [ -e "$d/logs" ] || [ -L "$d/logs" ] || continue
    p="$base" rest="$rel"
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
  why="$(above_problem "$r")"; [ -z "$why" ] || { echo "$why"; return 0; }
  above_problem "$d"
}
# Why a folder above $1 may be changed by someone other than root or the account ($want, the
# caller's), who could move $1 away and put another in its place, or nothing: each up to / must be
# root's or the account's and writable by neither group nor others; one others may write (/tmp)
# only where it is sticky and the next one down is root's or the account's (as sshd's StrictModes
# asks).
above_problem() {
  local child="$1" dir info o m
  dir="$(dirname "$child")"
  while :; do
    info="$(stat -c '%u %a' "$dir" 2> /dev/null || echo "x 777")"
    o="${info%% *}" m=$((8#${info##* }))
    if { [ "$o" != 0 ] && [ "$o" != "$want" ]; } || { (( (m & 8#022) != 0 )) \
      && { (( (m & 8#1000) == 0 )) || ! [[ " 0 $want " == *" $(stat -c '%u' "$child" 2> /dev/null || echo x) "* ]]; }; }; then
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
  local want e t d why
  want="$(id -u)"
  e="${KILNMARK_ENGINE:-}"
  [ -n "$e" ] || e="$(command -v podman 2> /dev/null || command -v docker 2> /dev/null || true)"
  [ -z "$e" ] || [[ "$e" == */* ]] || [[ "$e" =~ ^[A-Za-z]: ]] || e="$(command -v "$e" 2> /dev/null || true)"
  if [ -n "$e" ]; then
    e="$(posix_path "$e")"
    if [ -L "$e" ]; then why="$(above_problem "$e")"; [ -z "$why" ] || { echo "$why"; return 0; }; fi
    t="$(readlink -f "$e" 2> /dev/null || true)"
    why="$(owned_problem "${t:-$e}")"; [ -z "$why" ] || { echo "$why"; return 0; }
    why="$(above_problem "${t:-$e}")"; [ -z "$why" ] || { echo "$why"; return 0; }
  fi
  d="$(posix_path "$(expiry_dir)")"
  [ "${1:-}" != make ] || mkdir -p "$d" 2> /dev/null || true
  if [ -e "$d" ] || [ -L "$d" ]; then
    why="$(owned_problem "$d")"; [ -z "$why" ] || { echo "$why"; return 0; }
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
  local dir service timer engine_line="" why r
  why="$(tree_problem)"
  [ -n "$why" ] || why="$(engine_problem)"
  # the engine the unit names, and the folder its units are in, as what the run uses
  [ -n "$why" ] || why="$(tools_problem make)"
  dir="$(expiry_dir)"
  if [ -n "$why" ]; then
    expiry_timer_remove
    echo "Warning: the hourly expiry of unused temporary passwords was not set up: $why. Make it so ($root/kilnmark.sh check says how), then run up again. Each start expires them too." >&2
    return 0
  fi
  r="$(root_real)"
  [ -z "${KILNMARK_ENGINE:-}" ] || engine_line="
Environment=KILNMARK_ENGINE=$KILNMARK_ENGINE"
  # the script's path quoted, for a folder with a space in it
  service="[Unit]
Description=Kilnmark ($project): disable people whose temporary password went unused
[Service]
Type=oneshot
Environment=KILNMARK_PROJECT=$project$engine_line
ExecStart=\"$r/kilnmark.sh\" people expire"
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
hide_compose_commands() { grep --line-buffered -v -E "$compose_command_line|^[0-9a-f]{12,64}$" || true; }

start_log() {
  [ -n "$data" ] && [ -d "$data/logs" ] && [ ! -L "$data/logs" ] || return 0
  # what the scripts said, this account's only (install, as root, made it so)
  [ "$(my_uid)" -eq 0 ] || chmod 700 "$data/logs" 2> /dev/null || true
  local log; log="$data/logs/$1-$(date -u +%Y%m%dT%H%M%SZ).log"
  # the hourly expiry keeps one log a day, not one a run
  [ "${2:-}" != daily ] || log="$data/logs/$1-$(date -u +%Y%m%d).log"
  # never through a link, which would write wherever it leads (as root, for a system timer)
  if [ -L "$log" ] || { [ -e "$log" ] && [ ! -f "$log" ]; }; then
    die "$log is not a plain file, so nothing was logged or done: remove it, then run this again."
  fi
  log_path="$log"
  # As root (install, once the data folder is the account's), the log is written by the account,
  # with its own rights: root never writes through a path in the account's folder.
  if [ "$(my_uid)" -eq 0 ] && [ -n "$ins_user" ]; then
    exec > >(hide_compose_commands | as_account tee -a -- "$log") 2>&1
  else
    exec > >(hide_compose_commands | tee -a "$log") 2>&1
  fi
}
log_path=""

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

# What check found: each problem and warning with the command that fixes it, and what passed.
problems=() fixes=() warnings=() wfixes=() passed=()
problem() { problems+=("$1"); fixes+=("${2:-}"); }
warning() { warnings+=("$1"); wfixes+=("${2:-}"); }
passed() { passed+=("$1"); }

# A port as the install's answers or config/.env set it, else as the environment does, else the default.
port() {
  local v="${ans[$3]:-}"
  [ -n "$v" ] || { [ -n "$data" ] && v="$(setting "$1")"; }
  [ -n "$v" ] || v="${!1:-}"
  printf '%s' "${v:-$2}"
}
# Whether something answers on the port. A connection that neither opens nor is refused within two
# seconds counts as nothing there: WSL's mirrored networking can leave one to a free port hanging.
port_busy() {
  if command -v timeout > /dev/null 2>&1; then timeout 2 bash -c 'exec 3<> "/dev/tcp/127.0.0.1/$0"' "$1" 2> /dev/null
  else (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; fi
}
# A free port near $1, for a fix to name.
free_port() {
  local p=$(( $1 + 1 )) i
  for i in $(seq 1 200); do
    [ "$p" -le 65535 ] || p=20000
    port_busy "$p" || { printf '%s' "$p"; return; }
    p=$((p + 1))
  done
  printf '%s' "$1"
}
# The command that installs these packages here.
pkg_install() {
  if command -v apt-get > /dev/null 2>&1; then printf 'sudo apt-get install -y %s' "$*"
  elif command -v dnf > /dev/null 2>&1; then printf 'sudo dnf install -y %s' "$*"
  elif command -v zypper > /dev/null 2>&1; then printf 'sudo zypper install -y %s' "$*"
  else printf "install %s with your distribution's package manager" "$*"
  fi
}
# Whether this runs in WSL 2 (Linux under Windows), and WSL's version as Windows reports it (empty
# when Windows' programs cannot be run from here). KILNMARK_TEST_WSL and KILNMARK_WSL are the tests'.
in_wsl() {
  if test_hooks && [ -n "${KILNMARK_TEST_WSL:-}" ]; then [ "$KILNMARK_TEST_WSL" = yes ]; return; fi
  grep -qi microsoft /proc/sys/kernel/osrelease 2> /dev/null
}
wsl_version() {
  local w=wsl.exe
  if test_hooks && [ -n "${KILNMARK_WSL:-}" ]; then w="$KILNMARK_WSL"; fi
  "$w" --version 2> /dev/null | tr -d '\0\r' | sed -n 's/^WSL[^0-9]*\([0-9][0-9.]*\).*$/\1/p' | head -n 1 || true
}
# The fix for a WSL that breaks Podman: WSL 2.7.11, from its own release, installed on Windows.
wsl_fix='on Windows, in PowerShell: Invoke-WebRequest https://github.com/microsoft/WSL/releases/download/2.7.11/wsl.2.7.11.0.x64.msi -OutFile $env:TEMP\wsl.msi; Start-Process msiexec.exe -Verb RunAs -Wait -ArgumentList "/i $env:TEMP\wsl.msi"; wsl --shutdown'

# The prerequisites: the engine and its compose, its version, cgroups, memory, disk, the tools, and
# (when nothing runs) free ports. $1: whether Kilnmark is running; $2: whether it has the bundled
# Keycloak (5 GB of memory, not 4).
check_prerequisites() {
  local running="${1:-false}" keycloak="${2:-false}" v mem disk p seen=" " cg ctl want_mem wv
  if [ -n "${KILNMARK_ENGINE:-}" ] || command -v podman > /dev/null 2>&1 || command -v docker > /dev/null 2>&1; then
    if [ -n "${KILNMARK_ENGINE:-}" ]; then engine="$KILNMARK_ENGINE"
    elif command -v podman > /dev/null 2>&1; then engine=podman; else engine=docker; fi
    if "$engine" compose version > /dev/null 2>&1; then passed "$engine compose works"
    elif [[ "$engine" == *docker* ]]; then problem "'$engine compose' does not work." "$(pkg_install docker-compose-plugin)"
    else problem "'$engine compose' does not work: Podman needs podman-compose." "$(pkg_install podman-compose)"
    fi
    if [[ "$engine" != *docker* ]]; then
      v="$("$engine" version --format '{{.Client.Version}}' 2> /dev/null | head -n 1 || true)"
      if [[ "$v" =~ ^([0-9]+)\. ]] && [ "${BASH_REMATCH[1]}" -ge 5 ]; then passed "Podman $v"
      else problem "Podman ${v:-of an unknown version} is too old: Kilnmark needs Podman 5 or later (Ubuntu 25.04 and later, Debian 13, RHEL 9.5 and later have it)." "$(pkg_install podman podman-compose)"
      fi
      cg="$("$engine" info --format '{{.Host.CgroupsVersion}}' 2> /dev/null | head -n 1 || true)"
      ctl="$("$engine" info --format '{{.Host.CgroupControllers}}' 2> /dev/null | head -n 1 || true)"
      if [ "$cg" = v1 ]; then
        problem "The machine uses cgroups v1: rootless Podman needs cgroups v2." \
          "$(in_wsl && echo 'on Windows: add kernelCommandLine = cgroup_no_v1=all under [wsl2] in %UserProfile%\.wslconfig, then wsl --shutdown' || echo 'sudo grubby --update-kernel=ALL --args=systemd.unified_cgroup_hierarchy=1 (or the same in GRUB_CMDLINE_LINUX), then reboot')"
      elif [ "$cg" = v2 ] && [[ " ${ctl//[\[\]]/ } " != *" pids "* ]]; then
        if in_wsl; then problem "Podman cannot use cgroups' pids controller, so no container starts (WSL 3.0.x breaks crun's pids controller: Podman needs WSL 2.7.11)." "$wsl_fix"
        else problem "Podman cannot use cgroups' pids controller here, so no container starts: systemd does not hand it to this account." \
          "sudo mkdir -p /etc/systemd/system/user@.service.d && printf '[Service]\\nDelegate=cpu cpuset io memory pids\\n' | sudo tee /etc/systemd/system/user@.service.d/delegate.conf && sudo systemctl daemon-reload, then sign in again"
        fi
      elif [ "$cg" = v2 ]; then passed "cgroups v2, with the pids controller"
      fi
    elif ! "$engine" info > /dev/null 2>&1; then
      problem "Docker is installed but not running, or this account may not use it." "sudo systemctl enable --now docker && sudo usermod -aG docker $(id -un)"
    fi
    mem="$("$engine" info --format '{{.Host.MemTotal}}' 2> /dev/null || "$engine" info --format '{{.MemTotal}}' 2> /dev/null || echo 0)"
    mem="$(printf '%s' "$mem" | head -n 1 | tr -dc '0-9')"
    want_mem=4; [ "$keycloak" != true ] || want_mem=5
    if [ -n "$mem" ] && [ "$mem" -gt 0 ] 2> /dev/null && [ "$mem" -lt $((want_mem * 1024 * 1024 * 1024 - 268435456)) ]; then
      warning "The container engine has $((mem / 1024 / 1024)) MB of memory; Kilnmark wants $want_mem GB$( [ "$keycloak" = true ] && echo ' with the bundled Keycloak')." \
        "$(in_wsl && echo 'on Windows: set memory=8GB under [wsl2] in %UserProfile%\.wslconfig, then wsl --shutdown' || echo 'give this machine more memory')"
    elif [ -n "$mem" ] && [ "$mem" -gt 0 ] 2> /dev/null; then passed "$((mem / 1024 / 1024)) MB of memory for containers"
    fi
  else
    problem "Neither Podman nor Docker is installed." "$(pkg_install podman podman-compose)"
  fi
  if in_wsl; then
    wv="$(wsl_version)"
    case "$wv" in
      2.7.11|2.7.11.*) passed "WSL $wv" ;;
      "") warning "Cannot tell WSL's version from here (Windows' programs cannot be run): Podman needs WSL 2.7.11, and WSL 3.0.x breaks crun's pids controller." "on Windows: wsl --version" ;;
      3.*|2.9.*) problem "WSL $wv breaks crun's pids controller, so no Podman container starts: Podman needs WSL 2.7.11." "$wsl_fix" ;;
      *) warning "WSL $wv is not the one Podman is known to work with here: WSL 2.7.11 (3.0.x breaks crun's pids controller)." "$wsl_fix" ;;
    esac
  fi
  if command -v curl > /dev/null 2>&1; then passed "curl"; else problem "curl is missing: the health check uses it." "$(pkg_install curl)"; fi
  if command -v tar > /dev/null 2>&1; then passed "tar"; else problem "tar is missing." "$(pkg_install tar)"; fi
  cosign_bin > /dev/null || warning "cosign is not installed: downloads and images cannot be checked against Kilnmark's signature (--skip-verify goes on without)." \
    "install cosign 3 or later: https://docs.sigstore.dev/cosign/system_config/installation/"
  p="${data:-${ans[data_dir]:-$default_data}}"
  while [ ! -d "$p" ] && [ "$p" != / ]; do p="$(dirname "$p")"; done
  disk="$(df -Pk "$p" 2> /dev/null | awk 'NR==2 {print $4}' || echo 0)"
  if [ "${disk:-0}" -gt 0 ] 2> /dev/null && [ "$disk" -lt $((10 * 1024 * 1024)) ]; then
    warning "Less than 10 GB free for Kilnmark's data folder; images, data and backups need more." "${engine:-podman} system prune (removes unused images), or free space on that disk"
  elif [ "${disk:-0}" -gt 0 ] 2> /dev/null; then passed "$((disk / 1024 / 1024)) GB free for the data folder"
  fi
  if [ "$running" = false ]; then
    for p in "$(port KILNMARK_PORT 8780 port)" "$(port KILNMARK_HTTPS_PORT 8443 https_port)" "$(port KILNMARK_DB_PORT 5442 db_port)" \
      "$( [ "$keycloak" = true ] && port KILNMARK_KEYCLOAK_PORT 8180 keycloak_port)"; do
      [ -n "$p" ] && [[ "$seen" != *" $p "* ]] || continue
      seen="$seen$p "
      if port_busy "$p"; then problem "Port $p is in use." "$(port_fix "$p")"; else passed "port $p is free"; fi
    done
  fi
  return 0
}
# The command that moves Kilnmark off a busy port: configure on an install, else the answer.
port_fix() {
  local key=port alt; alt="$(free_port "$1")"
  [ "$1" != "$(port KILNMARK_HTTPS_PORT 8443 https_port)" ] || key=https_port
  [ "$1" != "$(port KILNMARK_DB_PORT 5442 db_port)" ] || key=db_port
  [ "$1" != "$(port KILNMARK_KEYCLOAK_PORT 8180 keycloak_port)" ] || key=keycloak_port
  if [ -n "$data" ] && [ -f "$data/config/install.conf" ]; then printf '%s/kilnmark.sh configure %s=%s' "$root" "$key" "$alt"
  else printf 'answer %s for %s (in an answers file: %s=%s)' "$alt" "$key" "$key" "$alt"
  fi
}

# The install itself, by its mode (ADR 0051): who runs this, who owns the folders, what is running.
check_install() {
  local running="$1" mode user why last age want recorded kc
  mode="$(setting KILNMARK_MODE)"
  user="$(answer_of user)"; user="${user:-kilnmark}"
  local group; group="$(id -gn "$user" 2> /dev/null || echo "$user")"
  case "$mode" in
    production|starter-pack) passed "a $mode install" ;;
    *) problem "KILNMARK_MODE in config/.env is not production or starter-pack: Kilnmark does not start." "set KILNMARK_MODE back to the mode it was installed with in $data/config/.env" ;;
  esac
  if [ "$(my_uid)" -eq 0 ]; then
    warning "check runs as root here: Kilnmark's day-to-day commands run as $user, never as root." "sudo -iu $user \"$root/kilnmark.sh\" check"
  elif [ "$(id -un)" != "$user" ]; then
    warning "Kilnmark runs as $user, not as $(id -un), so this account cannot start or change it." "sudo -iu $user \"$root/kilnmark.sh\" check"
  fi
  # the program folder root's, written by nobody else; the data folder the account's alone
  why="$(folder_problem "$root" 0 755)"
  if [ -z "$why" ]; then passed "the program folder is root's, and nobody else may change it"
  else problem "$why" "sudo chown -R root:root \"$root\" && sudo chmod -R go-w \"$root\""; fi
  why="$(folder_problem "$data" "$(id -u "$user" 2> /dev/null || echo x)" 700 config/.env)"
  if [ -z "$why" ]; then passed "the data folder is $user's alone"
  else problem "$why" "sudo chown -R $user:$group \"$data\" && sudo chmod 700 \"$data\" \"$data/config\" \"$data/backups\" \"$data/logs\" && sudo chmod 600 \"$data/config/.env\""; fi
  if auth_on && [ "$(my_uid)" -ne 0 ]; then
    # the second line of defence: what the hourly run uses, as it checks it
    why="$(tree_problem)"
    if [ -z "$why" ]; then passed "nobody else may change what the hourly run uses"
    else problem "$why." "sudo chown -R root:root \"$root\" && sudo chmod -R go-w \"$root\" && sudo chown -R $user:$group \"$data\" && sudo chmod -R go-w \"$data\""; fi
  fi
  if [ "$mode" = starter-pack ]; then
    auth_on || problem "A Starter Pack install needs the bundled Keycloak (auth in COMPOSE_PROFILES): it does not start without." "put COMPOSE_PROFILES=auth back in $data/config/.env, then $root/kilnmark.sh up"
    [ "$(setting KILNMARK_STARTER)" = allowed ] \
      || warning "KILNMARK_STARTER is not allowed in config/.env, so the example people cannot be added (and are removed at the next start)." "add KILNMARK_STARTER=allowed to $data/config/.env"
  elif [ "$mode" = production ]; then
    [ -z "$(setting KILNMARK_STARTER)" ] || problem "KILNMARK_STARTER is set on a production install, which never has the example people." "delete the line KILNMARK_STARTER from $data/config/.env"
    if ! auth_on && [ "$(setting KILNMARK_OIDC_ENABLED)" = true ]; then
      [ -n "$(setting KILNMARK_OIDC_ISSUER)" ] && [ -n "$(setting KILNMARK_OIDC_CLIENT_ID)" ] && [ -n "$(setting KILNMARK_OIDC_CLIENT_SECRET)" ] \
        && passed "sign-in with your identity provider is set up" \
        || problem "Sign-in with your identity provider lacks its issuer, client id or client secret." "$root/kilnmark.sh configure identity=own"
    fi
  fi
  if [ "$(answer_of host)" != localhost ] && [ -n "$(answer_of host)" ] && [ "$(answer_of tls)" = off ]; then
    warning "Kilnmark is reached as $(answer_of host) over plain HTTP: its sign-in cookies need HTTPS." "$root/kilnmark.sh configure tls=on tls_cert=FILE tls_key=FILE (or tls=proxy behind a proxy that ends TLS)"
  fi
  if [ "$running" = true ]; then
    passed "Kilnmark answers at $(local_url)"
    recorded="$(recorded_mode)"
    if [ -n "$recorded" ] && [ "$recorded" != "$mode" ]; then
      problem "KILNMARK_MODE in config/.env ($mode) differs from the mode Kilnmark recorded at its first start ($recorded)." "set KILNMARK_MODE=$recorded in $data/config/.env"
    fi
    if auth_on; then
      kc="$(setting KILNMARK_KEYCLOAK_PORT)"
      if curl -s -f -o /dev/null --max-time 10 "http://127.0.0.1:${kc:-8180}/realms/kilnmark"; then passed "Keycloak answers"
      else problem "Keycloak does not answer." "$root/kilnmark.sh restart, then $root/kilnmark.sh logs keycloak"; fi
    fi
  else
    warning "Kilnmark is not running." "$root/kilnmark.sh up"
  fi
  if auth_on && [ "$(my_uid)" -ne 0 ]; then
    want="$(expiry_timer_state)"
    echo "People expiry: $want"
    [ "$want" != "not set up" ] || warning "The hourly expiry of unused temporary passwords is not set up." "$root/kilnmark.sh up"
  fi
  last="$(ls -1d "$data"/backups/kilnmark-* 2> /dev/null | tail -n 1 || true)"
  if [ -z "$last" ]; then warning "There is no backup in $data/backups." "$root/kilnmark.sh backup"
  else
    age=$(( $(date +%s) - $(stat -c %Y "$last" 2> /dev/null || echo 0) ))
    if [ "$age" -gt 93600 ]; then warning "The last backup is more than a day old." "$root/kilnmark.sh backup (every day, from cron)"
    else passed "a backup from the last day"; fi
  fi
}
# Why a folder is not as install leaves it, or nothing: owned by uid $2 and nobody else may write
# it (mode no wider than $3); $4 more files in it to check the same way.
folder_problem() {
  local dir="$1" uid="$2" mode="$3" f info
  shift 3
  for f in "$dir" "$@"; do
    [ "$f" = "$dir" ] || f="$dir/$f"
    [ -e "$f" ] || { echo "$f is missing"; return 0; }
    [ ! -L "$f" ] || { echo "$f is a link"; return 0; }
    info="$(stat -c '%u %a' "$f" 2> /dev/null || echo "x 777")"
    [ "${info%% *}" = "$uid" ] || { echo "$f is not owned by $( [ "$uid" = 0 ] && echo root || echo "the account that runs Kilnmark")"; return 0; }
    (( (8#${info##* } & 8#022) == 0 )) || { echo "$f may be written by others than its owner"; return 0; }
    [ "$f" != "$dir" ] || (( (8#${info##* } & ~8#$mode & 8#777) == 0 )) || { echo "$f is open wider than $mode"; return 0; }
  done
}

report_checks() {
  local i
  for i in "${!passed[@]}"; do echo "OK: ${passed[$i]}"; done
  for i in "${!warnings[@]}"; do
    echo "Warning: ${warnings[$i]}"
    [ -z "${wfixes[$i]}" ] || echo "  Fix: ${wfixes[$i]}"
  done
  if [ "${#problems[@]}" -gt 0 ]; then
    for i in "${!problems[@]}"; do
      echo "Problem: ${problems[$i]}" >&2
      [ -z "${fixes[$i]}" ] || echo "  Fix: ${fixes[$i]}" >&2
    done
    return 1
  fi
  return 0
}

cmd_check() {
  local running=false keycloak=false
  [ $# -eq 0 ] || die "check takes no options."
  if [ -n "$data" ] && [ -f "$data/config/.env" ]; then
    curl -k -s -f -o /dev/null --max-time 10 "$(local_url | sed 's/localhost/127.0.0.1/')/q/health/ready" && running=true
    ! auth_on || keycloak=true
    echo "Checking Kilnmark in $root (data in $data), a $(setting KILNMARK_MODE) install"
    check_prerequisites "$running" "$keycloak"
    check_install "$running"
    local added; added="$(new_settings "$root/current/.env.example")"
    [ -z "$added" ] || echo "Settings in this version's .env.example that config/.env does not mention: $(echo "$added" | tr '\n' ' ')"
  else
    echo "Checking this machine for a Kilnmark install"
    [ "$(my_uid)" -eq 0 ] || echo "(install runs as root: it asks for sudo, and says why)"
    check_prerequisites false false
  fi
  if report_checks; then echo "All checks passed."; else return 1; fi
}

# Settings this version's .env.example names that config/.env does not mention at all.
new_settings() {
  local example="$1" env="$data/config/.env" k
  [ -f "$example" ] && [ -f "$env" ] || return 0
  for k in $(sed -n 's/^#\{0,1\} *\([A-Z][A-Z0-9_]*\)=.*/\1/p' "$example" | sort -u); do
    grep -qE "^#? *$k=" "$env" || echo "$k"
  done
}

# --- secrets and settings ----------------------------------------------------------------------

random_value() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }

# Sets KEY=VALUE in the file: replaces a "KEY=" or "# KEY=" line, or adds one at the end. Never a
# value with $ or a backtick: compose reads ${NAME} in config/.env as another setting's value, which
# would hand that one (the admin token, say) to whatever reads this one.
set_line() {
  local file="$1" key="$2" value="$3" tmp
  [[ "$value" != *[\$\`]* ]] || die "$key was not written to config/.env: its value holds \$ or \`, which compose would read as the start of another setting's name."
  tmp="$(mktemp "$file.XXXX")"
  # The value goes through the environment, never the command line, where other users could read it.
  KM_VALUE="$value" awk -v k="$key" '
    BEGIN { done = 0; v = ENVIRON["KM_VALUE"] }
    !done && ($0 ~ "^" k "=" || $0 ~ "^# *" k "=") { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }' "$file" > "$tmp"
  chmod 600 "$tmp" && mv "$tmp" "$file"
}

# Settings later versions added, as config/.env now asks: run before every start, so upgrade and up
# keep them right. Each new secret is random.
ensure_settings() {
  local env="$data/config/.env" files hours
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
    if curl -k -L -s -f --max-time 10 -o /dev/null "${url/localhost/127.0.0.1}"; then return 0; fi
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
  boot_start
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
    "${compose[@]}" exec -T postgres pg_isready -h 127.0.0.1 -U kilnmark -d kilnmark < /dev/null > /dev/null 2>&1 && break
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
    wait_for Keycloak "http://127.0.0.1:${kc:-8180}/realms/kilnmark" 150 \
      || die "Keycloak did not answer within 5 minutes. See why with: ./kilnmark.sh logs keycloak"
    people_after_up
    if [ -z "${KILNMARK_INSTALL_ID:-}" ]; then
      id="$(recorded_setting install_id)"
      if [ -n "$id" ]; then
        local stamped
        if stamped="$(KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID="$id" \
          "${compose[@]}" run --rm --no-deps -T keycloak-setup < /dev/null 2>&1 | hide_compose_commands)"; then
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
  last="$(ls -1d "$data"/backups/kilnmark-* 2> /dev/null | tail -n 1 || true)"
  if [ -n "$last" ]; then echo "Last backup: $(basename "$last")"; else echo "Last backup: none in $data/backups"; fi
}

# --- the install's answers (ADR 0051, decision 17) ---------------------------------------------

# Each answer install asks for, or reads from an answers file (install.conf.example says each). An
# answers file holds no secrets: secrets are generated, or typed (hidden) or piped in when asked.
declare -A ans=() ans_line=()
# answers configure leaves unchecked because the change is not to them (the certificate's files)
skip_keys=" "
answer_keys=(mode identity email_domain host port https_port db_port keycloak_port tls tls_cert tls_key
  oidc_issuer oidc_client_id oidc_provider_name oidc_viewer_groups oidc_steward_groups oidc_designer_groups
  oidc_admin_groups oidc_unmask_groups admin_email admin_first_name admin_last_name project program_dir data_dir user operator)
# Answers no configure changes: each needs a new install.
fixed_keys=" mode project program_dir data_dir user "
answer_default() {
  case "$1" in
    mode) echo production ;;
    identity) if [ "$(answer_of mode)" = starter-pack ]; then echo bundled; else echo own; fi ;;
    host) echo localhost ;;
    port) echo 8780 ;;
    https_port) echo 8443 ;;
    db_port) echo 5442 ;;
    keycloak_port) echo 8180 ;;
    tls) echo off ;;
    oidc_provider_name) echo "your organisation" ;;
    oidc_viewer_groups) echo kilnmark-viewers ;;
    oidc_steward_groups) echo kilnmark-stewards ;;
    oidc_designer_groups) echo kilnmark-designers ;;
    oidc_admin_groups) echo kilnmark-admins ;;
    oidc_unmask_groups) echo kilnmark-sensitive-data ;;
    project) echo kilnmark ;;
    program_dir) echo "$default_prog" ;;
    data_dir) echo "$default_data" ;;
    user) echo kilnmark ;;
    *) echo "" ;;
  esac
}
answer_of() { if [ -n "${ans[$1]+x}" ]; then printf '%s' "${ans[$1]}"; else answer_default "$1"; fi; }
known_key() { [[ " ${answer_keys[*]} " == *" $1 "* ]]; }
# Whether an answer means anything with the others: the identity provider's for own, Keycloak's and
# the first admin's for bundled, the certificate's for tls=on.
relevant() {
  case "$1" in
    oidc_*) [ "$(answer_of identity)" = own ] ;;
    keycloak_port|admin_email) [ "$(answer_of identity)" = bundled ] ;;
    admin_first_name|admin_last_name) [ "$(answer_of identity)" = bundled ] && [ -n "$(answer_of admin_email)" ] ;;
    tls_cert|tls_key) [ "$(answer_of tls)" = on ] ;;
    # the Windows edition's: who runs Kilnmark there
    operator) return 1 ;;
    *) return 0 ;;
  esac
}
trim() { local v="$1"; v="${v#"${v%%[![:space:]]*}"}"; v="${v%"${v##*[![:space:]]}"}"; printf '%s' "$v"; }
local_host() { [[ "$1" == localhost || "$1" == 127.0.0.1 ]]; }

# Why an answer cannot be taken, or nothing. Never the value itself.
answer_problem() {
  local k="$1" v="$2" d
  # Compose reads ${NAME} in config/.env as another setting's value (the admin token, say), which
  # the unauthenticated sign-in page would then show as the provider's name: no answer holds one.
  if [[ "$v" == *[\$\`]* ]]; then echo "$k may not hold \$ or \`, which compose would read as the start of another setting's name"; return 0; fi
  case "$k" in
    mode) [[ "$v" =~ ^(production|starter-pack)$ ]] || echo "mode is production or starter-pack" ;;
    identity)
      if ! [[ "$v" =~ ^(bundled|own)$ ]]; then echo "identity is bundled (Kilnmark's own Keycloak) or own (your organisation's identity provider)"
      elif [ "$v" = own ] && [ "$(answer_of mode)" = starter-pack ]; then echo "a Starter Pack install signs in with the bundled Keycloak only: identity is bundled"
      fi ;;
    email_domain)
      if [ -n "$v" ] && ! [[ "$v" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; then echo "email_domain is a domain name in lower case, such as bank.example"
      elif [[ "$v" == kilnmark.com || "$v" == *.kilnmark.com ]]; then echo "kilnmark.com is the Starter Pack's example people's domain: give your organisation's"
      fi ;;
    host)
      local_host "$v" || [[ "$v" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$ ]] || [[ "$v" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] \
        || echo "host is a host name in lower case (kilnmark.bank.example), an IPv4 address or localhost" ;;
    port|https_port|db_port|keycloak_port)
      if ! [[ "$v" =~ ^[0-9]{1,5}$ ]] || [ "$v" -lt 1 ] || [ "$v" -gt 65535 ]; then echo "$k is a port number from 1 to 65535"; fi ;;
    tls) [[ "$v" =~ ^(off|on|proxy)$ ]] || echo "tls is off, on (Kilnmark ends TLS with your certificate) or proxy (a proxy in front ends it)" ;;
    tls_cert) { [ -f "$v" ] && [ -r "$v" ] && grep -q 'BEGIN CERTIFICATE' "$v" 2> /dev/null; } || echo "tls_cert is the full path of a PEM certificate (with its chain) that install can read" ;;
    tls_key) { [ -f "$v" ] && [ -r "$v" ] && grep -q 'PRIVATE KEY' "$v" 2> /dev/null; } || echo "tls_key is the full path of the certificate's PEM private key, which install can read" ;;
    oidc_issuer) [[ "$v" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^[:space:]\"\'\\]*)?$ ]] || echo "oidc_issuer is your identity provider's issuer URL, starting https://" ;;
    oidc_client_id) [[ "$v" =~ ^[A-Za-z0-9._:/@-]+$ ]] || echo "oidc_client_id is the client id your identity provider gave Kilnmark" ;;
    oidc_provider_name|oidc_*_groups)
      if [ -z "$v" ] || [[ "$v" =~ [[:cntrl:]\"\'\\] ]]; then echo "$k is needed, with no quote, backslash or control character"; fi ;;
    admin_email)
      [ -n "$v" ] || return 0
      if ! [[ "$v" =~ ^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$ ]]; then echo "admin_email is an email address in lower case"
      elif [[ "$v" == *@kilnmark.com || "$v" == *.kilnmark.com ]]; then echo "kilnmark.com addresses are the Starter Pack's example people: give one of your own organisation"
      else
        d="$(answer_of email_domain)"
        [ -z "$d" ] || [[ "$v" == *@"$d" || "$v" == *."$d" ]] || echo "admin_email is not at email_domain"
      fi ;;
    admin_first_name|admin_last_name)
      if [ -z "$v" ] || [[ "$v" =~ [[:cntrl:]] ]]; then echo "$k is needed for the first admin"; fi ;;
    project) [[ "$v" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || echo "project is lower-case letters, digits, - and _, starting with a letter or a digit" ;;
    program_dir|data_dir)
      if [[ "$v" != /* ]] || [ "$v" = / ]; then echo "$k is a full path, starting with /"
      elif ! [[ "$v" =~ ^/[A-Za-z0-9._/+@,=\ -]*$ ]]; then echo "$k may hold letters, digits, spaces and . _ / + @ , = - only: compose and systemd read anything else as more than a path"
      fi ;;
    user) [[ "$v" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] && [ "$v" != root ] || echo "user is the name of the account that runs Kilnmark, in lower case, never root" ;;
  esac
}
# Why the answers do not go together, or nothing; one line each.
answers_problems() {
  local k p seen=" " busy="${1:-}" a b
  for k in port https_port db_port keycloak_port; do
    relevant "$k" || continue
    p="$(answer_of "$k")"
    [[ "$seen" != *" $p "* ]] || echo "$k is a port another answer uses already"
    seen="$seen$p "
    if [ "$busy" = ports ] && [[ "$p" =~ ^[0-9]+$ ]] && port_busy "$p"; then echo "$k: port $p is in use (port $(free_port "$p") is free)"; fi
  done
  if ! local_host "$(answer_of host)" && [ "$(answer_of tls)" = off ]; then
    echo "tls: people sign in over HTTPS only, so from other machines Kilnmark needs tls on or proxy (or host localhost)"
  elif ! local_host "$(answer_of host)" && [ "$(answer_of identity)" = bundled ] && [ "$(answer_of tls)" != proxy ]; then
    echo "tls: from other machines the bundled Keycloak is reached through a proxy that ends TLS (its realm asks for HTTPS), so tls is proxy (or host localhost)"
  fi
  a="$(answer_of program_dir)"; b="$(answer_of data_dir)"
  if [ "${a%/}" = "${b%/}" ] || [[ "${b%/}/" == "${a%/}/"* ]] || [[ "${a%/}/" == "${b%/}/"* ]]; then
    echo "program_dir and data_dir are two folders, neither inside the other"
  fi
}
# Every answer that means something, checked; each problem one line, by key (and line in a file).
validate_answers() {
  local k why errs=() at
  for k in "${answer_keys[@]}"; do
    relevant "$k" || continue
    [[ "$skip_keys" != *" $k "* ]] || continue
    why="$(answer_problem "$k" "$(answer_of "$k")")"
    [ -z "$why" ] || { at=""; [ -z "${ans_line[$k]:-}" ] || at=" (line ${ans_line[$k]})"; errs+=("$k$at: $why"); }
  done
  while IFS= read -r why; do [ -z "$why" ] || errs+=("$why"); done < <(answers_problems "${1:-}")
  [ "${#errs[@]}" -eq 0 ] && return 0
  for why in "${errs[@]}"; do echo "Answer: $why" >&2; done
  return 1
}

# Reads an answers file: KEY=VALUE lines, # comments; refuses a secret, an unknown key or a key twice.
read_answers() {
  local file="$1" n=0 line k v errs=() why
  [ -f "$file" ] && [ -r "$file" ] || die "There is no answers file that install can read there."
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1)); line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#.*)?$ ]] && continue
    if ! [[ "$line" =~ ^[[:space:]]*([a-z_]+)[[:space:]]*=(.*)$ ]]; then errs+=("line $n is not KEY=VALUE"); continue; fi
    k="${BASH_REMATCH[1]}" v="$(trim "${BASH_REMATCH[2]}")"
    if [[ "$k" =~ (password|secret|token) ]]; then errs+=("line $n: $k is a secret, and an answers file holds none: install generates them, or asks"); continue; fi
    known_key "$k" || { errs+=("line $n: $k is not an answer install knows (install.conf.example lists them)"); continue; }
    [ -z "${ans_line[$k]:-}" ] || { errs+=("line $n: $k is given twice"); continue; }
    case "$v" in \"*\") v="${v:1:${#v}-2}" ;; \'*\') v="${v:1:${#v}-2}" ;; esac
    ans[$k]="$v" ans_line[$k]="$n"
  done < "$file"
  if [ "${#errs[@]}" -gt 0 ]; then
    for why in "${errs[@]}"; do echo "Answers file: $why" >&2; done
    die "The answers file has the problems above, so nothing was changed."
  fi
}
# The answers this install was made with, from config/install.conf in the data folder.
load_installed_answers() {
  local f="$data/config/install.conf"
  [ -f "$f" ] || return 1
  read_answers "$f"
  ans_line=()
}
# config/install.conf: the answers that mean something, never a secret.
write_answers() {
  local file="$1" k
  {
    echo "# Kilnmark's install answers (no secrets). configure changes them; the mode never changes."
    echo "# Every key is explained in install.conf.example, in the release."
    for k in "${answer_keys[@]}"; do if relevant "$k"; then echo "$k=$(answer_of "$k")"; fi; done
  } > "$file.new" && chmod 600 "$file.new" && mv -f "$file.new" "$file"
}

# Asks one question at a terminal, with its default, until the answer is one install can take.
ask() {
  local k="$1" q="$2" d v why
  d="$(answer_of "$k")"
  while :; do
    read -r -p "$q [${d:-none}]: " v || die "No answer was given, so nothing was installed."
    v="$(trim "$v")"; [ -n "$v" ] || v="$d"
    [ "$v" != none ] || [ -n "$d" ] || v=""
    ans[$k]="$v"
    why="$(answer_problem "$k" "$v")"
    if [ -z "$why" ] && [[ "$k" == *port ]] && port_busy "$v"; then why="port $v is in use (port $(free_port "$v") is free)"; fi
    [ -n "$why" ] || return 0
    echo "  $why. Try again." >&2
    unset "ans[$k]"
  done
}
# A secret typed twice with the echo off at a terminal, else one line on standard input: in
# $secret_value, never shown or written anywhere but config/.env (the client secret) or Keycloak.
secret_value=""
read_secret() {
  local q="$1" again
  if [ -t 0 ]; then
    read -r -s -p "$q (not shown): " secret_value; echo >&2
    read -r -s -p "The same again: " again; echo >&2
    [ "$secret_value" = "$again" ] || { secret_value=""; die "The two did not match. Nothing was changed."; }
  else
    IFS= read -r secret_value || true
    secret_value="${secret_value%$'\r'}"
  fi
  [[ "$secret_value" != *[[:cntrl:]]* ]] || { secret_value=""; die "It holds a control character (a tab, say), which it may not. Nothing was changed."; }
}

# The identity provider's client secret, asked before anything changes. It goes into config/.env and
# Kilnmark's own file of secrets (write_service_secrets), so it holds nothing compose would read as
# more than itself.
read_client_secret() {
  read_secret "The client secret your identity provider gave Kilnmark"
  [ -n "$secret_value" ] || die "The client secret is needed for sign-in with your identity provider. Nothing was changed."
  plain_value "$secret_value" || { secret_value=""; die "The client secret holds a space, a quote mark, #, \$, \` or \\, which compose tools do not all read alike: ask your identity provider for another. Nothing was changed."; }
}

# The questions, each with its default and checked as it is answered.
ask_answers() {
  echo "Answer each question, or press Enter for the default in [brackets]."
  ask mode "Mode: production, for real work, or starter-pack, for training and development only (it never becomes production)"
  if [ "$(answer_of mode)" = starter-pack ]; then
    ans[identity]=bundled
    echo "A Starter Pack install signs in with the bundled Keycloak, where its example people are."
  else
    ask identity "How people sign in: own (your organisation's identity provider, recommended for banks) or bundled (Kilnmark's own Keycloak)"
  fi
  ask email_domain "Your organisation's email domain, which people's addresses end in (Enter for none)"
  ask host "The host name people reach Kilnmark on"
  ask tls "TLS: off, on (Kilnmark ends TLS with your certificate) or proxy (a proxy in front ends it)"
  if [ "$(answer_of tls)" = on ]; then
    ask tls_cert "Your certificate, with its chain: the full path of its PEM file"
    ask tls_key "Its private key: the full path of its PEM file"
  fi
  ask port "Kilnmark's port$( [ "$(answer_of tls)" = on ] && echo ' for plain HTTP, which it sends on to HTTPS')"
  # published whether or not TLS is on, so it must be free either way
  ask https_port "Kilnmark's HTTPS port$( [ "$(answer_of tls)" = on ] || echo ', published for when TLS is on')"
  ask db_port "The port of Kilnmark's own database, on this machine only"
  if [ "$(answer_of identity)" = bundled ]; then
    ask keycloak_port "Keycloak's port, on this machine only"
    ask admin_email "Kilnmark's first admin, a person of your own: their email, which is their user name (Enter to skip)"
    if [ -n "$(answer_of admin_email)" ]; then
      ask admin_first_name "Their first name"
      ask admin_last_name "Their last name"
    fi
  else
    ask oidc_issuer "Your identity provider's issuer URL"
    ask oidc_client_id "The client id it gave Kilnmark"
    ask oidc_provider_name "Its name, for the sign-in button (Sign in with ...)"
    ask oidc_admin_groups "Its group (or groups, comma-separated) of Kilnmark's admins"
    ask oidc_designer_groups "Its group of designers"
    ask oidc_steward_groups "Its group of stewards"
    ask oidc_viewer_groups "Its group of viewers"
    ask oidc_unmask_groups "Its group of designers who see sensitive values"
  fi
}
show_answers() {
  local k
  for k in "${answer_keys[@]}"; do if relevant "$k"; then echo "  $k=$(answer_of "$k")"; fi; done
}

# The secrets the answers need, asked before anything changes: the identity provider's client
# secret (own), or the first admin's temporary password (bundled, with a first admin).
person_password=""
ask_install_secrets() {
  if [ "$(answer_of identity)" = own ]; then
    read_client_secret
  elif [ -n "$(answer_of admin_email)" ]; then
    if [ -t 0 ]; then
      read_secret "A temporary password for the first admin, 12 characters or more (they choose their own at their first sign-in)"
    else
      read_secret "The first admin's temporary password"
    fi
    person_password="$secret_value" secret_value=""
    [ "${#person_password}" -ge 12 ] || { person_password=""; die "The first admin's temporary password is 12 characters or more (pipe it in on one line, or leave admin_email empty). Nothing was changed."; }
  fi
}

# --- secrets and settings from the answers ----------------------------------------------------

# Removes KEY from the file, as a commented line.
unset_line() {
  local file="$1" key="$2" tmp
  grep -qE "^[[:space:]]*(export[[:space:]]+)?$key[[:space:]]*=" "$file" || return 0
  tmp="$(mktemp "$file.XXXX")"
  awk -v k="$key" '$0 ~ "^[[:space:]]*(export[[:space:]]+)?" k "[[:space:]]*=" { print "# " k "="; next } { print }' "$file" > "$tmp"
  chmod 600 "$tmp" && mv "$tmp" "$file"
}
# COMPOSE_PROFILES with the auth profile ($1 on) or without it (off), keeping the others.
set_auth_profile() {
  local env="$1" list p out=""
  IFS=',' read -r -a list <<< "$(setting COMPOSE_PROFILES | tr -d '[:space:]')"
  for p in "${list[@]:-}"; do [ -z "$p" ] || [ "$p" = auth ] || out="${out:+$out,}$p"; done
  [ "$2" != on ] || out="${out:+$out,}auth"
  if [ -n "$out" ]; then set_line "$env" COMPOSE_PROFILES "$out"; else unset_line "$env" COMPOSE_PROFILES; fi
}
new_secret() { [ -n "$(setting "$2")" ] || set_line "$1" "$2" "$3"; }
# config/.env from the answers: every setting an answer decides, written again each time, and each
# secret new where there is none ($1 "new-client": the bundled Keycloak's client secret afresh).
write_env() {
  local env="$data/config/.env" scheme url kc host tlsm
  [ -f "$env" ] || { cp "$root/current/.env.example" "$env"; chmod 600 "$env"; }
  new_secret "$env" KILNMARK_DB_PASSWORD "$(random_value)"
  new_secret "$env" KILNMARK_ADMIN_TOKEN "km_$(random_value)"
  set_line "$env" KILNMARK_MODE "$(answer_of mode)"
  [ "$(answer_of mode)" != starter-pack ] || new_secret "$env" KILNMARK_SAMPLES_DB_PASSWORD "$(random_value)"
  set_line "$env" KILNMARK_PROJECT "$(answer_of project)"
  if [ -n "$(answer_of email_domain)" ]; then set_line "$env" KILNMARK_EMAIL_DOMAIN "$(answer_of email_domain)"; else unset_line "$env" KILNMARK_EMAIL_DOMAIN; fi
  set_line "$env" KILNMARK_PORT "$(answer_of port)"
  set_line "$env" KILNMARK_HTTPS_PORT "$(answer_of https_port)"
  set_line "$env" KILNMARK_DB_PORT "$(answer_of db_port)"
  host="$(answer_of host)" tlsm="$(answer_of tls)"
  case "$tlsm" in
    on) url="https://$host$( [ "$(answer_of https_port)" = 443 ] || echo ":$(answer_of https_port)")"
      set_line "$env" KILNMARK_TLS_CERT_FILE /etc/kilnmark/tls/server.crt
      set_line "$env" KILNMARK_TLS_KEY_FILE /etc/kilnmark/tls/server.key
      set_line "$env" KILNMARK_HTTP_PLAIN redirect
      unset_line "$env" KILNMARK_OIDC_BEHIND_TLS_PROXY ;;
    proxy) url="https://$host"
      unset_line "$env" KILNMARK_TLS_CERT_FILE; unset_line "$env" KILNMARK_TLS_KEY_FILE; unset_line "$env" KILNMARK_HTTP_PLAIN
      set_line "$env" KILNMARK_HTTP_HSTS always
      set_line "$env" KILNMARK_OIDC_BEHIND_TLS_PROXY true ;;
    *) url="http://$host:$(answer_of port)"
      unset_line "$env" KILNMARK_TLS_CERT_FILE; unset_line "$env" KILNMARK_TLS_KEY_FILE; unset_line "$env" KILNMARK_HTTP_PLAIN
      unset_line "$env" KILNMARK_OIDC_BEHIND_TLS_PROXY ;;
  esac
  set_line "$env" KILNMARK_URL "$url"
  set_line "$env" KILNMARK_OIDC_ENABLED true
  if [ "$(answer_of identity)" = bundled ]; then
    set_auth_profile "$env" on
    new_secret "$env" KILNMARK_KEYCLOAK_DB_PASSWORD "$(random_value)"
    new_secret "$env" KILNMARK_KEYCLOAK_ADMIN_PASSWORD "$(random_value)"
    if [ "${1:-}" = new-client ] || [ -z "$(setting KILNMARK_OIDC_CLIENT_SECRET)" ]; then set_line "$env" KILNMARK_OIDC_CLIENT_SECRET "$(random_value)"; fi
    set_line "$env" KILNMARK_KEYCLOAK_PORT "$(answer_of keycloak_port)"
    # Keycloak is published on this machine only: from other machines, a proxy that ends TLS
    if local_host "$host"; then kc="http://localhost:$(answer_of keycloak_port)"; else kc="https://$host:$(answer_of keycloak_port)"; fi
    set_line "$env" KILNMARK_KEYCLOAK_URL "$kc"
    if local_host "$host"; then set_line "$env" KILNMARK_KEYCLOAK_BIND 127.0.0.1; else set_line "$env" KILNMARK_KEYCLOAK_BIND 0.0.0.0; fi
    set_line "$env" KILNMARK_OIDC_ISSUER "http://keycloak:8080/realms/kilnmark"
    set_line "$env" KILNMARK_OIDC_CLIENT_ID kilnmark
    set_line "$env" KILNMARK_OIDC_PROVIDER_NAME Keycloak
    set_line "$env" KILNMARK_OIDC_VIEWER_GROUPS kilnmark-viewers
    set_line "$env" KILNMARK_OIDC_STEWARD_GROUPS kilnmark-stewards
    set_line "$env" KILNMARK_OIDC_DESIGNER_GROUPS kilnmark-designers
    set_line "$env" KILNMARK_OIDC_ADMIN_GROUPS kilnmark-admins
    set_line "$env" KILNMARK_OIDC_UNMASK_GROUPS kilnmark-sensitive-data
    # a Starter Pack install's example people may be added (ADR 0051)
    if [ "$(answer_of mode)" = starter-pack ]; then set_line "$env" KILNMARK_STARTER allowed; else unset_line "$env" KILNMARK_STARTER; fi
  else
    set_auth_profile "$env" off
    unset_line "$env" KILNMARK_STARTER
    set_line "$env" KILNMARK_OIDC_ISSUER "$(answer_of oidc_issuer)"
    set_line "$env" KILNMARK_OIDC_CLIENT_ID "$(answer_of oidc_client_id)"
    [ -z "$secret_value" ] || set_line "$env" KILNMARK_OIDC_CLIENT_SECRET "$secret_value"
    set_line "$env" KILNMARK_OIDC_PROVIDER_NAME "$(answer_of oidc_provider_name)"
    set_line "$env" KILNMARK_OIDC_VIEWER_GROUPS "$(answer_of oidc_viewer_groups)"
    set_line "$env" KILNMARK_OIDC_STEWARD_GROUPS "$(answer_of oidc_steward_groups)"
    set_line "$env" KILNMARK_OIDC_DESIGNER_GROUPS "$(answer_of oidc_designer_groups)"
    set_line "$env" KILNMARK_OIDC_ADMIN_GROUPS "$(answer_of oidc_admin_groups)"
    set_line "$env" KILNMARK_OIDC_UNMASK_GROUPS "$(answer_of oidc_unmask_groups)"
  fi
  secret_value=""
}
# The certificate and key for tls=on, into certs/https: readable inside the container (the folder
# above, certs, keeps everyone else on this machine out). At install this runs as root, reading
# the files the answers name with root's rights: whoever runs sudo install is root already, so
# naming a file of root's gains them nothing (unlike Windows, where the elevated step never reads
# them). It runs before the data folder becomes the account's. configure runs it as the account.
place_certs() {
  [ "$(answer_of tls)" = on ] || return 0
  cp "$(answer_of tls_cert)" "$data/certs/https/server.crt" && cp "$(answer_of tls_key)" "$data/certs/https/server.key" \
    || die "The certificate or its key could not be copied into $data/certs/https."
  chmod 755 "$data/certs/https" && chmod 644 "$data/certs/https/server.crt" "$data/certs/https/server.key"
}

# --- install, upgrade, uninstall (as root) ---------------------------------------------------

# install, upgrade and uninstall change root's folders: as anyone else, this says why and runs
# itself again with sudo.
need_root() {
  [ "$(my_uid)" -eq 0 ] && return 0
  echo "$1 writes $default_prog and $default_data (or the folders you named), which only root may change, so it runs as root: sudo asks for your password."
  command -v sudo > /dev/null 2>&1 || die "Run it as root (su -c), as sudo is not here."
  exec sudo -- "$BASH" "$here/kilnmark.sh" "$@"
}
# Day-to-day commands run as the account that runs Kilnmark, never as root.
not_root() {
  [ "$(my_uid)" -ne 0 ] && return 0
  local user=""
  # the data folder's owner, as upgrade takes it: root reads nothing the account may write
  [ -z "$data" ] || [ ! -d "$data" ] || [ -L "$data" ] || user="$(stat -c %U "$data" 2> /dev/null || true)"
  [ "$user" != root ] && [ "$user" != UNKNOWN ] || user=""
  die "$1 runs as the account that runs Kilnmark, never as root: sudo -iu ${user:-kilnmark} \"$root/kilnmark.sh\" $1"
}
# Runs a command as the account that runs Kilnmark ($ins_user), with its own session (rootless Podman).
ins_user=""
as_account() {
  local uid home
  uid="$(id -u "$ins_user")" home="$(getent passwd "$ins_user" | cut -d: -f6)"
  (cd / && runuser -u "$ins_user" -- env -i HOME="$home" USER="$ins_user" LOGNAME="$ins_user" \
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" LANG="${LANG:-C.UTF-8}" \
    XDG_RUNTIME_DIR="/run/user/$uid" DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" \
    KILNMARK_PROJECT="$project" ${KILNMARK_ENGINE:+KILNMARK_ENGINE="$KILNMARK_ENGINE"} "$@")
}
# A step run as the account: what it says is shown, and a failure ends the step with its own reason.
account_step() {
  local out rc=0 why
  out="$(mktemp)"
  as_account "$@" 2>&1 | tee "$out" || rc=$?
  why="$(sed -n 's/^Error: //p; s/^\[[0-9]*\/[0-9]*\] FAILED ([^)]*): //p' "$out" | tail -n 1)"
  rm -f "$out"
  [ "$rc" -eq 0 ] || die "${why:-it stopped: see what it said above}"
}

# Makes VERSION the running one: current points at it, and the control script and the menu
# launchers in the program folder are its own, so shortcuts made from there survive upgrades.
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
  chmod 644 "$root/kilnmark.png" 2> /dev/null || true
  ln -sfn "versions/$1" "$root/current"
}
# A release folder copied into the program folder, root's and read-only to everyone else.
place_version() {
  local src="$1" v="$2"
  if [ -d "$root/versions/$v" ]; then echo "Kilnmark $v is in $root/versions/$v already."
  else
    cp -a "$src" "$root/versions/$v.part" && mv "$root/versions/$v.part" "$root/versions/$v" \
      || die "The release could not be copied into $root/versions."
  fi
  chown -R -h root:root "$root/versions/$v" && chmod -R u+rwX,go+rX,go-w "$root/versions/$v" \
    || die "$root/versions/$v could not be made root's alone."
}

get_images() {
  local v="$1"
  if [ "${offline:-}" = - ]; then
    pick_engine
    echo "Loading the images ..."
    "$engine" load || die "The images could not be loaded."
  elif [ -n "${offline:-}" ]; then load_images "$offline"
  elif images_present "$v"; then echo "The images of Kilnmark $v are already here."
  elif [ "${build:-false}" = true ]; then build_images "$v"
  else pull_images "$v"
  fi
  images_present "$v" || die "The images of Kilnmark $v are still missing after that."
}

# Rootless Podman starts containers at boot only through its podman-restart user service.
boot_start() {
  [ "$engine" = podman ] && [ "$(id -u)" -ne 0 ] || return 0
  systemctl --user is-enabled podman-restart.service > /dev/null 2>&1 && return 0
  if systemctl --user enable podman-restart.service > /dev/null 2>&1; then
    echo "Kilnmark's containers start at boot (podman-restart.service)."
    if [ "$(loginctl show-user "$(id -un)" -p Linger --value 2> /dev/null)" != yes ]; then
      echo "To start Kilnmark at boot without this account signed in, run once: sudo loginctl enable-linger $(id -un)"
    fi
  else
    echo "Note: Kilnmark will not start by itself at boot (podman-restart.service could not be enabled)."
  fi
}

# The account Kilnmark runs as: made (a system account, no password), or kept when it is there,
# with what rootless Podman needs (id ranges, lingering) or the docker group.
make_account() {
  local user="$1" f start i uid
  if id "$user" > /dev/null 2>&1; then
    echo "The account $user is there already: Kilnmark runs as it."
  else
    useradd --system --create-home --home-dir "/var/lib/$user" --shell /bin/bash --comment "Kilnmark" "$user" \
      || die "The account $user could not be made."
    echo "Made the account $user (no password: reach it with sudo -iu $user)."
  fi
  if [[ "${KILNMARK_ENGINE:-$(command -v podman || echo docker)}" == *docker* ]]; then
    usermod -aG docker "$user" || die "$user could not be added to the docker group."
    echo "$user may use Docker (the docker group, which can act as root on this machine: prefer rootless Podman where that matters)."
    return 0
  fi
  for f in /etc/subuid /etc/subgid; do
    if ! grep -q "^$user:" "$f" 2> /dev/null; then
      start=$(awk -F: '{ if ($2 + $3 > m) m = $2 + $3 } END { print (m > 100000 ? m : 100000) }' "$f" 2> /dev/null || echo 100000)
      (umask 022; echo "$user:$start:65536" >> "$f")
    fi
  done
  loginctl enable-linger "$user" || die "Lingering could not be turned on for $user, which rootless Podman needs to run without them signed in."
  uid="$(id -u "$user")"
  for i in $(seq 1 30); do [ -d "/run/user/$uid" ] && break; sleep 1; done
  [ -d "/run/user/$uid" ] || die "$user's session (/run/user/$uid) did not start: is systemd running? (WSL: systemd=true under [boot] in /etc/wsl.conf)"
  echo "$user runs rootless Podman, and its containers start at boot (lingering on)."
}

cmd_install() {
  local start=true dry=false answers="" opt_starter=false v n=0 t k
  local -a opts=("$@")
  while [ $# -gt 0 ]; do
    case "$1" in
      --answers) answers="${2:?--answers needs the answers file}"; shift ;;
      --starter) with_starter=true ;;
      --program-dir) ans[program_dir]="${2:?--program-dir needs a folder}"; shift ;;
      --data-dir) ans[data_dir]="${2:?--data-dir needs a folder}"; shift ;;
      --user) ans[user]="${2:?--user needs an account}"; shift ;;
      --offline) offline="${2:?--offline needs the images file}"; shift ;;
      --build) build=true ;;
      --skip-verify) skip_verify=true ;;
      --no-start) start=false ;;
      --dry-run) dry=true ;;
      *) die "Unknown option $1. See: ./kilnmark.sh help" ;;
    esac
    shift
  done
  [ -f "$here/compose.yaml" ] || die "Run install from the unpacked release folder, kilnmark-<version>."
  v="$(version_of "$here")"
  [ -n "$v" ] || die "Cannot tell this release's version from its compose.yaml."
  $dry || need_root install "${opts[@]}"
  [ -z "${offline:-}" ] || offline="$(readlink -f "$offline")"
  if [ -n "$answers" ]; then
    local cli_prog="${ans[program_dir]-}" cli_data="${ans[data_dir]-}" cli_user="${ans[user]-}"
    read_answers "$answers"
    # what the command line says wins over the file
    [ -z "$cli_prog" ] || ans[program_dir]="$cli_prog"
    [ -z "$cli_data" ] || ans[data_dir]="$cli_data"
    [ -z "$cli_user" ] || ans[user]="$cli_user"
  fi
  if [ "${with_starter:-false}" = true ]; then
    [ -z "${ans[mode]+x}" ] || [ "${ans[mode]}" = starter-pack ] || die "--starter makes a Starter Pack install, and the answers say another mode: choose one. Nothing was changed."
    ans[mode]=starter-pack
  fi
  echo "Installing Kilnmark $v"
  if [ -z "$answers" ]; then
    [ -t 0 ] || die "There is no terminal to ask the questions at: give the answers in a file (--answers install.conf; install.conf.example says each)."
    ask_answers
    echo "Install with these answers:"
    show_answers
    local yes; read -r -p "Go on? [Y/n]: " yes || yes=n
    [[ "${yes:-y}" =~ ^[Yy] ]] || die "Nothing was installed."
  fi
  # the steps: the answers, the prerequisites, the account, the program folder, the data folder and
  # the images; then the start, the first admin and the Starter Pack where they apply
  t=7
  if $start; then
    t=$((t + 1))
    [ -z "$(answer_of admin_email)" ] || [ "$(answer_of identity)" != bundled ] || t=$((t + 1))
    [ "$(answer_of mode)" != starter-pack ] || t=$((t + 1))
  fi
  $dry && t=2
  steps "$t"
  step "Check the answers"
  validate_answers ports || die "Some answers cannot be taken (above), so nothing was changed."
  ins_user="$(answer_of user)" project="$(answer_of project)" root="$(answer_of program_dir)" data="$(answer_of data_dir)"
  root="${root%/}" data="${data%/}"
  expiry_unit="kilnmark-$project-people-expire"
  $dry || ask_install_secrets
  step_ok
  step "Check the prerequisites"
  check_prerequisites false "$( [ "$(answer_of identity)" = bundled ] && echo true || echo false)"
  report_checks || die "Fix the problems above (each says how), then run install again."
  step_ok
  if $dry; then
    echo "Install would place Kilnmark $v in $root (root's), keep its data in $data ($ins_user's), and run it as $ins_user, with these answers:"
    show_answers
    echo "Nothing was changed (--dry-run)."
    return 0
  fi
  # never under a folder someone else may change, nor into, or over, one someone else may have shaped
  check_folders_above
  if [ -e "$root/versions" ] || [ -e "$root/kilnmark.sh" ]; then
    die "Kilnmark is installed in $root already: upgrade it ($root/kilnmark.sh upgrade), or uninstall it first."
  fi
  if [ -e "$data" ] || [ -L "$data" ]; then
    die "$data is there already: uninstall the earlier install with --purge, or remove it, or choose another data folder (--data-dir). Nothing was changed."
  fi
  if [ -L "$root" ] || { [ -e "$root" ] && { [ "$(stat -c %u "$root")" != 0 ] || [ -n "$(ls -A "$root")" ]; }; }; then
    die "$root is there already and is not an empty folder of root's: choose another program folder (--program-dir). Nothing was changed."
  fi
  step "Make the account $ins_user, which runs Kilnmark"
  make_account "$ins_user"
  step_ok
  step "Place Kilnmark $v in $root"
  (umask 022; mkdir -p "$(dirname "$root")") && { [ -d "$root" ] || mkdir -m 755 "$root"; } && mkdir -m 755 "$root/versions" \
    || die "$root could not be made."
  chmod 755 "$root"
  place_version "$here" "$v"
  use_version "$v"
  printf '%s\n' "$data" > "$root/data-folder.txt"
  chown -h root:root "$root" "$root/versions" "$root/current" "$root"/*.sh "$root/data-folder.txt" && chmod 644 "$root/data-folder.txt"
  step_ok
  step "Check the account's Podman (rootless: its cgroups, its memory)"
  account_step "$root/kilnmark.sh" check < /dev/null
  step_ok
  step "Make the data folder $data, with new secrets"
  (umask 022; mkdir -p "$(dirname "$data")") && mkdir -m 700 "$data" && mkdir -m 700 "$data/config" "$data/backups" "$data/logs" "$data/certs" \
    && mkdir -m 755 "$data/certs/https" || die "$data could not be made."
  cp "$root/current/.env.example" "$data/config/.env.example"
  write_env
  write_answers "$data/config/install.conf"
  place_certs
  # Until here the data folder is root's alone; from here it is the account's, and root writes
  # nothing more in it: the account writes the log (start_log) and everything else.
  chown -R -h "$ins_user:$(id -gn "$ins_user")" "$data" || die "$data could not be made $ins_user's."
  echo "Wrote $data/config/.env with new secrets ($ins_user's alone) and $data/config/install.conf (the answers)."
  step_ok
  # what install says from here, in the data folder's logs, written by the account
  start_log install
  step "Get the images"
  if [ -n "${offline:-}" ]; then
    offline_copy
    account_step "$root/kilnmark.sh" _images --stdin < "$offline_checked"
    rm -rf "$images_tmp"; images_tmp=""
  else
    account_step "$root/kilnmark.sh" _images ${build:+--build} $( [ "${skip_verify:-false}" = true ] && echo --skip-verify) < /dev/null
  fi
  step_ok
  if $start; then
    step "Start Kilnmark"
    account_step "$root/kilnmark.sh" up --no-browser < /dev/null
    step_ok
    if [ -n "$(answer_of admin_email)" ] && [ "$(answer_of identity)" = bundled ]; then
      step "Add the first admin"
      account_step "$root/kilnmark.sh" people add < <(printf '%s\n' "$(answer_of admin_email)" "$(answer_of admin_first_name)" "$(answer_of admin_last_name)" kilnmark-admins "$person_password")
      person_password=""
      step_ok
    fi
    if [ "$(answer_of mode)" = starter-pack ]; then
      step "Turn the Starter Pack on"
      account_step "$root/kilnmark.sh" _starter-on < /dev/null
      step_ok
    fi
  fi
  person_password=""
  cat <<EOF

Kilnmark $v is installed: the program in $root, its data in $data, run by the account $ins_user.
  Sign in with the admin token, KILNMARK_ADMIN_TOKEN in $data/config/.env; then make a token of
  your own under API tokens, and keep it safe.
  Every day, as $ins_user: sudo -iu $ins_user "$root/kilnmark.sh" up | down | status | check | backup | help
  Change an answer later: sudo -iu $ins_user "$root/kilnmark.sh" configure KEY=VALUE
  Back it up every day, for example from $ins_user's crontab: "$root/kilnmark.sh" backup
EOF
  [ "$(answer_of mode)" != starter-pack ] || echo "  Add the example people, with a password you type: sudo -iu $ins_user \"$root/kilnmark.sh\" starter people"
  if [ "$(answer_of identity)" = own ]; then
    echo "  Register Kilnmark in your identity provider with the redirect URI $(setting KILNMARK_URL)/api/v1/auth/login."
  fi
}

# Why a folder above $1 (the program or the data folder, which root makes, writes or removes) may be
# changed by anyone but root, or nothing: each one that is there, from $1's parent up to /, must be
# root's, no link, and writable by neither its group nor others, a sticky one (/tmp) too. Someone
# who could swap one of them for a link, between root's check and root's write, would have root
# write, chown or remove wherever they liked.
root_above_problem() {
  local dir info
  dir="$(dirname "$1")"
  while :; do
    if [ -L "$dir" ]; then echo "$dir, a folder above $1, is a link"; return 0; fi
    if [ -e "$dir" ]; then
      info="$(stat -c '%u %a' "$dir" 2> /dev/null || echo "x 777")"
      if [ "${info%% *}" != 0 ] || (( (8#${info##* } & 8#022) != 0 )); then
        echo "$dir, a folder above $1, may be changed by someone other than root"; return 0
      fi
    fi
    case "$dir" in /|.) return 0 ;; esac
    dir="$(dirname "$dir")"
  done
}
# Root works in the program and data folders only where nobody else may change a folder above them.
check_folders_above() {
  local why
  why="$(root_above_problem "$root")"
  [ -z "$why" ] || die "$why: root works in the program folder only where nobody else may change a folder above it (as /opt). Make each folder above it root's and writable by root alone (sudo chown root:root FOLDER && sudo chmod go-w FOLDER), or, for an install, choose another program folder (--program-dir). Nothing was changed."
  why="$(root_above_problem "$data")"
  [ -z "$why" ] || die "$why: root works in the data folder only where nobody else may change a folder above it (as /var/opt). Make each folder above it root's and writable by root alone (sudo chown root:root FOLDER && sudo chmod go-w FOLDER); or, with Kilnmark stopped, move the data folder under one that is (sudo mv it, then write its new path in $root/data-folder.txt); or, for an install, choose another data folder (--data-dir). Nothing was changed."
}

# Whether $2 (a folder) has room for a copy of $1, or dies saying so: the folder, and the space.
room_for() {
  local need free
  need="$(stat -c %s "$1" 2> /dev/null || echo 0)"
  free="$(df -P -k "$2" 2> /dev/null | awk 'NR == 2 { print $4 * 1024 }')"
  [ -n "$free" ] || return 0
  [ "$free" -ge "$need" ] || die "There is not room for a copy of $(basename "$1") in $2: it needs $(( (need + 1048575) / 1048576 )) MB, and $(( free / 1048576 )) MB are free. Free that much on its file system, then run this again."
}

# The offline images file, copied into a folder of root's first, with its signature: the copy is
# what is checked and what the account loads, so nothing can be put in its place between the two.
images_tmp="" offline_checked=""
offline_copy() {
  local copy
  images_tmp="$(mktemp -d "$root/.images.XXXXXX")" || die "Could not make a folder of root's in $root to check the images file."
  copy="$images_tmp/$(basename "$offline")"
  room_for "$offline" "$images_tmp"
  cp -- "$offline" "$copy" || die "$(basename "$offline") could not be copied to check it."
  [ ! -f "$offline.sigstore.json" ] || cp -- "$offline.sigstore.json" "$copy.sigstore.json" \
    || die "$(basename "$offline").sigstore.json could not be copied to check it."
  verify_file "$copy"
  offline_checked="$copy"
}

# The install's account, as root takes it for upgrade and uninstall: the data folder's owner, never
# what the account itself may write (install.conf is the account's), and refused when install.conf
# names another. Root then runs the account's parts as that account only.
installed_user() {
  local named
  [ -n "$data" ] && [ -d "$data" ] && [ ! -L "$data" ] \
    || die "$data, the data folder data-folder.txt names, is not a folder (or is a link): install Kilnmark afresh. Nothing was changed."
  ins_user="$(stat -c %U "$data" 2> /dev/null || true)"
  if [ -z "$ins_user" ] || [ "$ins_user" = root ] || [ "$ins_user" = UNKNOWN ] || ! id "$ins_user" > /dev/null 2>&1; then
    die "$data is not owned by an account of this machine other than root, as install leaves it: make it the account's that runs Kilnmark (sudo chown -R ACCOUNT: \"$data\"), then run this again. Nothing was changed."
  fi
  load_installed_answers || die "$data/config/install.conf, the install's answers, is missing: Kilnmark was not installed by this release's install."
  named="$(answer_of user)"
  [ "$named" = "$ins_user" ] \
    || die "$data/config/install.conf names another account than $ins_user, who owns $data: Kilnmark runs as the data folder's owner. Set user=$ins_user there, or make $data the other account's, then run this again. Nothing was changed."
}

cmd_upgrade() {
  local archive="" backup=true old new tmp src recorded v
  local -a opts=("$@")
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
  [ -n "$archive" ] && [ -f "$archive" ] || die "Say which release to upgrade to: sudo $root/kilnmark.sh upgrade kilnmark-<version>.tar.gz"
  need_root upgrade "${opts[@]}"
  archive="$(readlink -f "$archive")"; [ -z "${offline:-}" ] || offline="$(readlink -f "$offline")"
  old="$(current_version)"
  check_folders_above
  installed_user
  steps "$( $backup && echo 6 || echo 5)"
  step "Check the new release"
  # An install from before deployment modes is never upgraded (ADR 0051): refused before anything
  # changes, by config/.env and by what the database recorded.
  recorded="$(as_account "$root/kilnmark.sh" _recorded-mode < /dev/null)" || die "Kilnmark's settings or database refused the upgrade (above). Nothing was changed."
  [ -n "$recorded" ] || die "Kilnmark's database has no deployment mode recorded (or does not answer: start it first). An install from before deployment modes (release 0.1.1 or earlier, a preview) cannot be upgraded: install Kilnmark afresh. Nothing was changed."
  [ "$recorded" = "$(setting KILNMARK_MODE)" ] \
    || die "KILNMARK_MODE in config/.env differs from the mode Kilnmark recorded at its first start: set it back. Nothing was changed."
  # The archive copied into a folder of root's first, with its signature: the copy is what is
  # checked and what is unpacked, so nothing can be put in its place between the two.
  tmp="$(mktemp -d "$root/versions/.unpack.XXXX")"
  local copy; copy="$tmp/$(basename "$archive")"
  ( room_for "$archive" "$tmp" ) || { rm -rf "$tmp"; exit 1; }
  cp -- "$archive" "$copy" || { rm -rf "$tmp"; die "$(basename "$archive") could not be copied to check it."; }
  [ ! -f "$archive.sigstore.json" ] || cp -- "$archive.sigstore.json" "$copy.sigstore.json" \
    || { rm -rf "$tmp"; die "$(basename "$archive").sigstore.json could not be copied to check it."; }
  ( verify_file "$copy" ) || { rm -rf "$tmp"; exit 1; }
  tar -xzf "$copy" -C "$tmp" || { rm -rf "$tmp"; die "$(basename "$archive") could not be unpacked."; }
  rm -f "$copy" "$copy.sigstore.json"
  src="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  new="$(version_of "$src")"
  [ -n "$new" ] || { rm -rf "$tmp"; die "Cannot tell the new release's version."; }
  [ "$new" != "$old" ] || { rm -rf "$tmp"; die "Kilnmark $old is already the running version."; }
  if [ "$(printf '%s\n%s\n' "$old" "$new" | sort -V | tail -n 1)" != "$new" ]; then
    rm -rf "$tmp"; die "$new is older than the running $old: upgrade only goes forward (go back with restore)."
  fi
  step_ok
  if $backup; then
    step "Back up Kilnmark $old"
    account_step "$root/kilnmark.sh" backup < /dev/null
    step_ok
  fi
  step "Place Kilnmark $new in $root"
  place_version "$src" "$new"
  rm -rf "$tmp"
  step_ok
  step "Get the images"
  if [ -n "${offline:-}" ]; then offline_copy; account_step "$root/kilnmark.sh" _images --version "$new" --stdin < "$offline_checked"; rm -rf "$images_tmp"; images_tmp=""
  else account_step "$root/kilnmark.sh" _images --version "$new" $( [ "${skip_verify:-false}" = true ] && echo --skip-verify) < /dev/null; fi
  step_ok
  step "Switch to Kilnmark $new"
  local added; added="$(new_settings "$root/versions/$new/.env.example")"
  # by the account, with its own rights: root never writes through a path in the account's folder,
  # where a link could lead anywhere
  as_account cp -- "$root/versions/$new/.env.example" "$data/config/.env.example" < /dev/null \
    || die "$data/config/.env.example could not be written by $ins_user."
  use_version "$new"
  step_ok
  step "Start Kilnmark $new"
  if ! as_account "$root/kilnmark.sh" up --no-browser < /dev/null; then
    echo "Kilnmark $new did not start: stopping it and starting $old again." >&2
    as_account "$root/kilnmark.sh" down < /dev/null || true
    use_version "$old"
    if as_account "$root/kilnmark.sh" up --no-browser < /dev/null; then
      die "Kilnmark $new did not start, so $old runs again. See why $new did not start in $data/logs."
    fi
    die "Kilnmark $old does not start either: $new may have changed its database already. Put it back to the backup taken above: $root/kilnmark.sh restore <that backup> --yes"
  fi
  step_ok
  [ -z "$added" ] || echo "Settings new in $new (see $data/config/.env.example): $(echo "$added" | tr '\n' ' ')"
  # Keep the two newest versions.
  for v in $(ls -1 "$root/versions" | grep -v '^\.' | sort -V | head -n -2); do
    [ "$v" = "$new" ] || [ "$v" = "$old" ] || rm -rf "${root:?}/versions/$v"
  done
  echo "Upgraded Kilnmark from $old to $new."
}

cmd_uninstall() {
  local purge=false answer
  local -a opts=("$@")
  for a in "$@"; do case "$a" in --purge) purge=true ;; *) die "Unknown option $a." ;; esac; done
  current_version > /dev/null
  need_root uninstall "${opts[@]}"
  check_folders_above
  installed_user
  if $purge; then
    echo "This deletes all of Kilnmark's data: its database, the key to stored passwords, Keycloak's, and $data with every backup in it."
    echo "Back up first, somewhere else, if you might want any of it. Type DELETE to go on:"
    IFS= read -r answer || answer=""
    [ "${answer%$'\r'}" = DELETE ] || die "Nothing was deleted."
  fi
  steps "$( $purge && echo 3 || echo 2)"
  step "Stop Kilnmark and remove its hourly timer"
  account_step "$root/kilnmark.sh" _stop-for-uninstall $( $purge && echo --purge) < /dev/null
  step_ok
  step "Remove the program folder $root"
  rm -rf "${root:?}" || die "$root could not be removed."
  step_ok
  if $purge; then
    step "Remove the data folder $data"
    # rm takes a link as a link, never following it, here and inside
    [ ! -L "$data" ] || die "$data is a link: nothing more was removed."
    rm -rf "${data:?}" || die "$data could not be removed."
    step_ok
    echo "Kilnmark is uninstalled, and its data deleted. The account $ins_user is kept; remove it with: sudo userdel -r $ins_user"
  else
    echo "Kilnmark is uninstalled. Its data (volumes) and $data (settings, backups, logs) are kept: uninstall --purge deletes them, or install afresh with another data folder."
  fi
}

# The account's part of uninstall: no timer, nothing running; with --purge, no volumes nor images.
cmd_stop_for_uninstall() {
  local purge=false i kept="" v
  for a in "$@"; do case "$a" in --purge) purge=true ;; *) die "Unknown option $a." ;; esac; done
  v="$(current_version)"; set_compose
  expiry_timer_remove
  if ! $purge; then
    compose_quietly down || true
    echo "Kilnmark is stopped and will not start again by itself. Its data is kept."
    return 0
  fi
  "${compose[@]}" --profile auth --profile ai down -v
  # An image another install on this machine still runs is kept: removing it, or its name, would
  # stop that install starting again.
  for i in $(image_names "$v"); do
    if [ -n "$("$engine" ps -a --filter "ancestor=$i" --format '{{.Names}}' 2> /dev/null || true)" ]; then
      kept="$kept $i"
    else
      "$engine" rmi "$i" > /dev/null 2>&1 || true
    fi
  done
  [ -z "$kept" ] || echo "Kept the images$kept: other containers on this machine use them (another install, say)."
  echo "Kilnmark's containers, volumes and images are deleted."
}

# --- configure: change an answer later (ADR 0051, decision 17) --------------------------------

cmd_configure() {
  local a k v changed=() restart=false person=false identity_was why running=false certs=false
  current_version > /dev/null
  load_installed_answers || die "$data/config/install.conf, the install's answers, is missing, so configure cannot tell what install chose. Nothing was changed."
  if [ $# -eq 0 ]; then
    echo "This install's answers ($data/config/install.conf):"
    show_answers
    echo "Change one with: $root/kilnmark.sh configure KEY=VALUE (install.conf.example says each)."
    return 0
  fi
  identity_was="$(answer_of identity)"
  # running is judged by the settings it runs with now, before they change
  curl -k -s -f -o /dev/null --max-time 10 "$(local_url)/q/health/ready" && running=true
  steps 3
  step "Check the new answers"
  for a in "$@"; do
    [[ "$a" =~ ^([a-z_]+)=(.*)$ ]] || die "Say each change as KEY=VALUE, such as port=9780."
    k="${BASH_REMATCH[1]}" v="$(trim "${BASH_REMATCH[2]}")"
    [[ ! "$k" =~ (password|secret|token) ]] || die "$k is a secret: configure asks for one when an answer needs it, and none is given on a command line."
    known_key "$k" || die "$k is not an answer install knows (install.conf.example lists them)."
    [[ "$v" != *[\$\`]* ]] || die "$k may not hold \$ or \`, which compose would read as the start of another setting's name. Nothing was changed."
    if [[ "$fixed_keys" == *" $k "* ]] && [ "$v" != "$(answer_of "$k")" ]; then
      case "$k" in
        mode) die "An install's mode never changes (ADR 0051): for the other mode, install Kilnmark afresh. Nothing was changed." ;;
        *) die "$k is fixed at install: changing it needs a new install. Nothing was changed." ;;
      esac
    fi
    [ "$v" = "$(answer_of "$k")" ] && continue
    ans[$k]="$v"
    changed+=("$k")
  done
  [ "${#changed[@]}" -gt 0 ] || { step_ok; echo "Nothing changed: those are the answers already."; return 0; }
  for k in "${changed[@]}"; do
    relevant "$k" || die "$k means nothing with this install's other answers (with identity=$(answer_of identity) and tls=$(answer_of tls)). Nothing was changed."
    if [[ "$k" == *port ]] && port_busy "$(answer_of "$k")"; then
      die "$k: port $(answer_of "$k") is in use (port $(free_port "$(answer_of "$k")") is free). Nothing was changed."
    fi
  done
  # the certificate's files are checked, and copied, only when the change is to them: install read
  # them as root, and the account need not be able to read them again
  if [[ " ${changed[*]} " == *" tls "* || " ${changed[*]} " == *" tls_cert "* || " ${changed[*]} " == *" tls_key "* ]]; then certs=true; fi
  $certs || skip_keys=" tls_cert tls_key "
  # the first admin, made at install, is checked again only when the change is to them
  [[ " ${changed[*]} " == *" admin_"* ]] || skip_keys="$skip_keys admin_email admin_first_name admin_last_name "
  validate_answers || die "Some answers cannot be taken (above), so nothing was changed."
  # what a change needs before anything is written: a client secret for your own identity provider
  if [ "$(answer_of identity)" = own ] && { [ "$identity_was" != own ] || [[ " ${changed[*]} " == *" oidc_client_id "* ]]; }; then
    read_client_secret
  fi
  for k in "${changed[@]}"; do
    case "$k" in
      email_domain) ;;
      admin_email|admin_first_name|admin_last_name) person=true ;;
      *) restart=true ;;
    esac
  done
  if $person; then
    [ -n "$(answer_of admin_email)" ] || die "admin_email is needed to add a first admin. Nothing was changed."
    ask_install_secrets
  fi
  step_ok
  step "Write the settings"
  ! $certs || place_certs
  if [ "$identity_was" != "$(answer_of identity)" ] && [ "$(answer_of identity)" = bundled ]; then write_env new-client; else write_env; fi
  write_answers "$data/config/install.conf"
  echo "Wrote $data/config/.env and $data/config/install.conf: ${changed[*]}."
  step_ok
  step "Run again what the change affects"
  if $restart; then
    if $running; then
      echo "Restarting Kilnmark, so every container takes the new settings ..."
      cmd_down
      cmd_up --no-browser
    else
      echo "Kilnmark is not running: the change takes effect at its next start ($root/kilnmark.sh up)."
    fi
  fi
  if $person; then
    $running || die "Kilnmark is not running, so the first admin was not added: start it ($root/kilnmark.sh up), then run configure again."
    run_first_admin
  fi
  $restart || $person || echo "Nothing to run again: ${changed[*]} takes effect as it is."
  step_ok
}
# configure's first admin: people add, with the answers, the password typed (or piped in) once.
run_first_admin() {
  person_email="$(answer_of admin_email)" person_first="$(answer_of admin_first_name)" person_last="$(answer_of admin_last_name)"
  person_groups=kilnmark-admins
  make_person
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
# does: Kilnmark does the work. Through api_call, as every request with the admin token: only to a
# listener of this account's, at 127.0.0.1, the token in a file, never on a command line.
starter_on() {
  local answer code answer_file
  set_compose
  one_off_done samples-db \
    || echo "Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: kilnmark.sh logs samples-db"
  echo "Turning the Starter Pack on ..."
  answer_file="$(mktemp)" || die "Could not make a private file for the answer."
  code="$(KM_ANSWER_FILE="$answer_file" api_call POST /api/v1/starter-pack "")"
  answer="$(cat "$answer_file" 2> /dev/null || true)"
  rm -f "$answer_file"
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
  if grep -q "^keycloak-setup: this Keycloak's realm .* is not this install's" <<< "$out"; then
    sed -n "s/^keycloak-setup: \(this Keycloak's realm .*\)$/\1/p" <<< "$out" >&2
    compose_quietly down || true
    die "keycloak-setup refused this Keycloak's realm (above), so Kilnmark was stopped again: no sign-in goes to a realm that is not this install's."
  fi
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
# Whether the kernel shows no socket listening on port $1, on the loopback or all addresses; false
# where it cannot be told (no /proc/net/tcp).
nothing_listens() {
  local f hex addr st
  [ -r /proc/net/tcp ] || return 1
  hex="$(printf '%04X' "$1")"
  for f in /proc/net/tcp /proc/net/tcp6; do
    [ -r "$f" ] || continue
    while read -r _ addr _ st _; do
      [ "$st" = 0A ] && [ "${addr##*:}" = "$hex" ] && return 1
    done < <(tail -n +2 "$f")
  done
  return 0
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
  # the answer for a caller that needs it (this runs in a subshell): a file of its own
  [ -z "${KM_ANSWER_FILE:-}" ] || cp "$starter_work/answer" "$KM_ANSWER_FILE" 2> /dev/null || true
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
    || die "The example people need the bundled Keycloak: the auth profile (docs/guide/install/production-bundled-keycloak.md)."
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
    KILNMARK_INSTALL_ID="$id" "${compose[@]}" run --rm --no-deps -T keycloak-setup 2>&1 | hide_compose_commands)" || ok=false
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
      echo "No first admin was made. Add people later with $root/kilnmark.sh people add (or people import, or Keycloak's console: docs/guide/install/production-bundled-keycloak.md)."
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
  todo="$(sed -n 's/^keycloak-setup: made \([a-z0-9._%+@-]*\) ([a-z;-]*): they choose their own password.*$/\1/p; s/^keycloak-setup: activated \([a-z0-9._%+@-]*\): they choose their own password.*$/\1/p' <<< "$1")"
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
    "${compose[@]}" run --rm --no-deps -T keycloak-setup 2>&1 | hide_compose_commands)" || rc=$?
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
      local kcp rc=0; kcp="$(setting KILNMARK_KEYCLOAK_PORT)"
      # only a refused connection is "not running", or one that timed out with nothing listening
      # on the port (WSL's mirrored network drops a closed port's connections rather than refuse
      # them): anything else answering there goes on to setup, which checks the realm itself
      curl -s -o /dev/null --max-time 10 "http://127.0.0.1:${kcp:-8180}/realms/kilnmark" || rc=$?
      if [ "$rc" -eq 7 ] || { [ "$rc" -eq 28 ] && nothing_listens "${kcp:-8180}"; }; then
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

# The compose command for backup.sh and restore.sh, a word a line (KILNMARK_COMPOSE_LINES), so a
# folder with a space in its path stays one word.
compose_lines() { printf '%s\n' "${compose[@]}"; }

# on when the bundled Keycloak is (the auth profile): backup.sh and restore.sh take its database too.
keycloak_on() {
  if [[ ",$(setting COMPOSE_PROFILES | tr -d '[:space:]')," == *",auth,"* ]]; then echo on; else echo off; fi
}

cmd_backup() {
  local dest="${1:-$data/backups}"
  current_version > /dev/null; set_compose
  mkdir -p "$dest"
  (cd "$root/current" && KILNMARK_COMPOSE_LINES="$(compose_lines)" KILNMARK_KEYCLOAK="$(keycloak_on)" sh ./backup.sh "$dest")
}

cmd_restore() {
  current_version > /dev/null; set_compose
  # its last step starts Kilnmark: the one-offs get their secrets for that run only
  write_run_secrets
  (cd "$root/current" && KILNMARK_COMPOSE_LINES="$(compose_lines)" KILNMARK_KEYCLOAK="$(keycloak_on)" \
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
  local why d log want
  [ -n "$root" ] && [ -n "$data" ] || die "Kilnmark is not installed here. Install it with: sudo ./kilnmark.sh install"
  why="$(tree_problem)"
  [ -n "$why" ] || why="$(tools_problem)"
  [ -n "$why" ] || return 0
  logger -t "kilnmark-people-expire" -p user.warning "people expire was refused: $why" > /dev/null 2>&1 || true
  # and a line in the day's log, where logs/ passes the same check and that log is a plain file
  want="$(id -u)"
  d="$(real_path "$data")"
  if [ -n "$d" ] && [ -d "$d/logs" ] && [ -z "$(owned_problem "$d/logs")" ]; then
    log="$d/logs/people-expire-$(date -u +%Y%m%d).log"
    if [ ! -L "$log" ] && { [ ! -e "$log" ] || [ -f "$log" ]; }; then
      printf '%s people expire was refused: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$why" >> "$log" 2> /dev/null || true
    fi
  fi
  die "people expire was refused: $why. Make it so, then run it again."
}

main() {
command="${1:-up}"
[ $# -eq 0 ] || shift
# Day-to-day commands run as the account that runs Kilnmark, never as root.
case "$command" in
  install|upgrade|uninstall|check|version|help|-h|--help) ;;
  *) not_root "$command" ;;
esac
case "$command" in
  install) cmd_install "$@" ;;
  upgrade) cmd_upgrade "$@" ;;
  uninstall) cmd_uninstall "$@" ;;
  up|start) start_log up; cmd_up "$@" ;;
  down|stop) start_log down; cmd_down ;;
  restart) start_log restart; cmd_restart "$@" ;;
  status) cmd_status ;;
  logs)
    set_compose
    follow=(); svc=()
    for a in "$@"; do case "$a" in -f) follow=(-f) ;; *) svc+=("$a") ;; esac; done
    "${compose[@]}" logs --tail 200 "${follow[@]}" "${svc[@]}" ;;
  check) load_installed_answers || true; cmd_check "$@" ;;
  # compose as this install runs it (its project, settings, secrets and profiles), for a command
  # by hand: kilnmark.sh compose ps, logs, exec, ...
  compose) current_version > /dev/null; set_compose; "${compose[@]}" "$@" ;;
  configure) start_log configure; cmd_configure "$@" ;;
  verify)
    file=""; while [ $# -gt 0 ]; do case "$1" in --key) key_override="${2:?}"; shift ;; *) file="$1" ;; esac; shift; done
    [ -n "$file" ] || die "Say which file: kilnmark.sh verify FILE"
    verify_file "$file" ;;
  load-images)
    file=""; for a in "$@"; do case "$a" in --skip-verify) skip_verify=true ;; *) file="$a" ;; esac; done
    [ -n "$file" ] || die "Say which file: kilnmark.sh load-images kilnmark-<version>-images.tar.gz"
    load_images "$file" ;;
  pull)
    for a in "$@"; do case "$a" in --skip-verify) skip_verify=true ;; *) die "Unknown option $a." ;; esac; done
    pull_images "$(current_version)" ;;
  build) build_images "$(current_version)" ;;
  backup) start_log backup; cmd_backup "$@" ;;
  starter) start_log starter; cmd_starter "$@" ;;
  people) if [ "${1:-}" = expire ]; then expire_guard; start_log people-expire daily; else start_log people; fi; cmd_people "$@" ;;
  restore) start_log restore; cmd_restore "$@" ;;
  # the account's parts of install, upgrade and uninstall, which run as root
  _images)
    v="$(current_version)"
    while [ $# -gt 0 ]; do
      case "$1" in --stdin) offline=- ;; --build) build=true ;; --skip-verify) skip_verify=true ;;
        --version) v="${2:?}"; shift ;; *) die "Unknown option $1." ;; esac
      shift
    done
    get_images "$v" ;;
  _starter-on) current_version > /dev/null; starter_on ;;
  _recorded-mode) current_version > /dev/null; ensure_settings; set_compose; recorded_mode ;;
  _stop-for-uninstall) start_log uninstall; cmd_stop_for_uninstall "$@" ;;
  version) if [ -n "$root" ] && [ -n "$data" ]; then echo "Kilnmark $(current_version)"; else echo "Kilnmark $(version_of "$here") (not installed)"; fi ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
}
main "$@"; exit $?
