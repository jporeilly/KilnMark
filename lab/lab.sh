#!/usr/bin/env bash
# Start, stop and reset the reference lab's demo data sources one at a time (Linux and macOS).
# The same commands as lab/lab.ps1 on Windows. A development script: it lives in lab/ and is never
# shipped with Kilnmark.
#
#   ./lab/lab.sh list                     the demo sources, whether each runs, port, source name
#   ./lab/lab.sh start <name...>|all      start them (each is a compose profile of the same name)
#   ./lab/lab.sh stop <name...>|all       stop them (their data is kept)
#   ./lab/lab.sh status                   the lab's containers
#   ./lab/lab.sh reset <name...>|all [--yes]   drop the data and load it again (asks first)
#   ./lab/lab.sh logs <name>              the last lines of its containers' logs
#   ./lab/lab.sh sources                  where lab/sources.csv is, and how to import it
#
# Uses podman if it is on the PATH, otherwise docker. Needs no administrator rights. Works with
# the bash 3.2 that macOS ships.
set -euo pipefail

LAB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMPOSE_FILE="$LAB_DIR/compose.yaml"
PROJECT=lab          # compose project name: containers and volumes are called lab_... / lab-...
NETWORK=kilnmark     # created by Kilnmark's own compose.yaml

# The demo sources, one per line:
#   name | long-running services | one-shot services | named volumes | port | source name in
#   Kilnmark (lab/sources.csv) | what it is
# The name is also the compose profile. A source with no services (files) needs nothing running.
# Keep this table the same as the one in lab.ps1.
SOURCES='postgres|lab-postgres|||5433|lab-db|PostgreSQL 18: database lab (crm, billing)
s3|lab-s3|||9000|lab-bucket|S3-compatible storage (VersityGW): bucket lab-raw
sqlserver|lab-sqlserver|lab-sqlserver-init|lab-sqlserver-data|14330|lab-sqlserver|SQL Server 2022: database labdb (sales, hr, ops)
mail|lab-mailpit|||8025|-|Mailpit: catches the emails Kilnmark sends (SMTP 1025), read them at http://localhost:8025
files||||-|lab-share|File share lab/files/share, read from disk (mounted at /srv/files)'

SQLSERVER_LICENCE='https://go.microsoft.com/fwlink/?linkid=857698'

die() { echo "lab: $*" >&2; exit 1; }

source_names() { printf '%s\n' "$SOURCES" | cut -d'|' -f1; }

# Prints the record of a source, or fails with the list of names.
record() {
    local line
    while IFS= read -r line; do
        if [ "${line%%|*}" = "$1" ]; then
            printf '%s\n' "$line"
            return 0
        fi
    done <<EOF
$SOURCES
EOF
    echo "lab: unknown demo source '$1'. Choose from: $(source_names | tr '\n' ' ')or all." >&2
    return 1
}

field() { printf '%s\n' "$1" | cut -d'|' -f"$2"; }

# Expands the names given (or "all") into source names, checking each one.
expand() {
    if [ $# -eq 0 ]; then
        die "name at least one demo source: $(source_names | tr '\n' ' ')or all."
    fi
    local n
    for n in "$@"; do
        if [ "$n" = "all" ]; then
            source_names
            return 0
        fi
    done
    for n in "$@"; do
        record "$n" >/dev/null || exit 2
        echo "$n"
    done
}

engine() {
    if command -v podman >/dev/null 2>&1; then
        echo podman
    elif command -v docker >/dev/null 2>&1; then
        echo docker
    else
        die "neither podman nor docker is on the PATH."
    fi
}

ENGINE=
case "${1:-help}" in help|-h|--help) ;; *) ENGINE=$(engine) ;; esac

compose() { "$ENGINE" compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }

# Container ids of one compose service (running or not).
# Found by the labels compose tools put on containers; failing that, by podman-compose's
# container name (lab_<service>_1).
containers() {
    local ids
    ids=$("$ENGINE" ps -a -q --filter "label=com.docker.compose.project=$PROJECT" \
        --filter "label=com.docker.compose.service=$1")
    if [ -z "$ids" ]; then
        ids=$("$ENGINE" ps -a -q --filter "name=${PROJECT}_$1_")
    fi
    printf '%s\n' "$ids" | sed '/^$/d'
}

running() {
    local id
    for id in $(containers "$1"); do
        if [ "$("$ENGINE" inspect --format '{{.State.Running}}' "$id" 2>/dev/null)" = "true" ]; then
            return 0
        fi
    done
    return 1
}

state_of() {
    local rec=$1 services oneshot s up=0 total=0 created=0 id code
    services=$(field "$rec" 2)
    oneshot=$(field "$rec" 3)
    if [ -z "$services" ]; then
        echo "on disk"
        return
    fi
    for s in $services; do
        total=$((total + 1))
        if [ -n "$(containers "$s")" ]; then created=$((created + 1)); fi
        if running "$s"; then up=$((up + 1)); fi
    done
    if [ "$up" -eq "$total" ]; then
        for s in $oneshot; do
            for id in $(containers "$s"); do
                if running "$s"; then
                    echo "running (loading data)"
                    return
                fi
                code=$("$ENGINE" inspect --format '{{.State.ExitCode}}' "$id" 2>/dev/null || echo 0)
                if [ "$code" != "0" ]; then
                    echo "running (data load failed: see logs)"
                    return
                fi
            done
        done
        echo "running"
    elif [ "$up" -gt 0 ]; then
        echo "partly running"
    elif [ "$created" -gt 0 ]; then
        echo "stopped"
    else
        echo "not started"
    fi
}

need_network() {
    if ! "$ENGINE" network inspect "$NETWORK" >/dev/null 2>&1; then
        die "network '$NETWORK' does not exist. Start Kilnmark first ($ENGINE compose up -d in the repository root), or create it: $ENGINE network create $NETWORK"
    fi
}

# compose --profile arguments for the sources that have services.
profile_args() {
    local n rec
    for n in "$@"; do
        rec=$(record "$n")
        if [ -n "$(field "$rec" 2)" ]; then printf -- '--profile\n%s\n' "$n"; fi
    done
}

cmd_list() {
    local name rec
    printf '%-10s %-34s %-6s %-14s %s\n' NAME STATE PORT SOURCE WHAT
    for name in $(source_names); do
        rec=$(record "$name")
        printf '%-10s %-34s %-6s %-14s %s\n' "$name" "$(state_of "$rec")" "$(field "$rec" 5)" \
            "$(field "$rec" 6)" "$(field "$rec" 7)"
    done
}

cmd_start() {
    local names n a args=()
    names=$(expand "$@") || exit 2
    for n in $names; do
        if [ -z "$(field "$(record "$n")" 2)" ]; then
            echo "$n: nothing to start; Kilnmark reads it from disk."
        fi
        if [ "$n" = "sqlserver" ]; then
            echo "sqlserver: starting it accepts Microsoft's SQL Server Developer licence terms: $SQLSERVER_LICENCE"
        fi
    done
    while IFS= read -r a; do
        if [ -n "$a" ]; then args+=("$a"); fi
    done <<EOF
$(
    # shellcheck disable=SC2086
    profile_args $names
)
EOF
    if [ ${#args[@]} -eq 0 ]; then return 0; fi
    need_network
    compose "${args[@]}" up -d || die "starting failed: see the messages above."
    for n in $names; do
        if [ "$n" = "sqlserver" ]; then
            echo "sqlserver: the first start takes a minute or two after the download; watch it with: $0 logs sqlserver"
        fi
    done
}

cmd_stop() {
    local names n s ids
    names=$(expand "$@") || exit 2
    for n in $names; do
        if [ -z "$(field "$(record "$n")" 2)" ]; then
            echo "$n: nothing to stop."
            continue
        fi
        for s in $(field "$(record "$n")" 2) $(field "$(record "$n")" 3); do
            ids=$(containers "$s")
            if [ -n "$ids" ]; then
                # shellcheck disable=SC2086
                "$ENGINE" stop $ids >/dev/null || die "could not stop $s."
            fi
        done
        echo "$n: stopped."
    done
}

cmd_status() {
    "$ENGINE" ps -a --filter "label=com.docker.compose.project=$PROJECT" \
        --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' || die "could not list the lab's containers."
}

cmd_reset() {
    local yes=0 names=() n s v ids list answer
    for n in "$@"; do
        case "$n" in
            --yes|-y|--force) yes=1 ;;
            *) names+=("$n") ;;
        esac
    done
    list=$(expand ${names[@]+"${names[@]}"}) || exit 2
    if [ "$yes" -ne 1 ]; then
        echo "This deletes the demo data of: $(printf '%s\n' "$list" | tr '\n' ' ')and loads it again."
        if [ ! -t 0 ]; then
            die "not asking without a terminal: add --yes to go ahead."
        fi
        printf 'Type yes to go ahead: '
        read -r answer || answer=
        [ "$answer" = "yes" ] || { echo "Nothing changed."; exit 1; }
    fi
    for n in $list; do
        if [ -z "$(field "$(record "$n")" 2)" ]; then
            echo "$n: nothing to reset; its files are in git (git checkout -- lab/files restores them)."
            continue
        fi
        for s in $(field "$(record "$n")" 2) $(field "$(record "$n")" 3); do
            ids=$(containers "$s")
            if [ -n "$ids" ]; then
                # -v also removes the container's anonymous volumes (PostgreSQL's data)
                # shellcheck disable=SC2086
                "$ENGINE" rm -f -v $ids >/dev/null || die "could not remove $s."
            fi
        done
        for v in $(field "$(record "$n")" 4); do
            if "$ENGINE" volume inspect "${PROJECT}_$v" >/dev/null 2>&1; then
                "$ENGINE" volume rm "${PROJECT}_$v" >/dev/null || die "could not remove volume ${PROJECT}_$v."
            fi
        done
        echo "$n: data removed."
    done
    # shellcheck disable=SC2086
    cmd_start $list
}

cmd_logs() {
    [ $# -eq 1 ] || die "name one demo source: $(source_names | tr '\n' ' ')"
    local rec s id found=0
    rec=$(record "$1") || exit 2
    for s in $(field "$rec" 2) $(field "$rec" 3); do
        for id in $(containers "$s"); do
            found=1
            echo "== $s"
            "$ENGINE" logs --tail 100 "$id" 2>&1 || true
        done
    done
    if [ "$found" -eq 0 ]; then
        if [ -z "$(field "$rec" 2)" ]; then
            echo "$1 runs nothing, so it has no logs."
        else
            die "$1 has not been started: $0 start $1"
        fi
    fi
}

cmd_sources() {
    echo "$LAB_DIR/sources.csv"
    cat <<'EOF'

In Kilnmark: Sources > Import sources from a file, choose that file, tick "Rewrite host names
for this server" (and, if you like, "Test each connection" and "Queue a scan"), then Import.
Only the demo sources you have started will connect; import again whenever you like.
EOF
}

usage() {
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    echo
    echo "Demo sources: $(source_names | tr '\n' ' ')"
}

command=${1:-help}
[ $# -gt 0 ] && shift
case "$command" in
    list) cmd_list ;;
    start) cmd_start "$@" ;;
    stop) cmd_stop "$@" ;;
    status) cmd_status ;;
    reset) cmd_reset "$@" ;;
    logs) cmd_logs "$@" ;;
    sources) cmd_sources ;;
    help|-h|--help) usage ;;
    *) usage >&2; echo >&2; die "unknown command '$command'." ;;
esac
