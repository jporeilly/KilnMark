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
#   ./lab/lab.sh driver oracle [--yes]    download Oracle's JDBC driver (its licence is shown first)
#   ./lab/lab.sh pipelines                add the demo pipelines in lab/pipelines to a running Kilnmark
#   ./lab/lab.sh samples                  add the samples to a running Kilnmark's Library
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
s3|lab-s3|||9020|lab-bucket|S3-compatible storage (VersityGW): bucket lab-raw
sqlserver|lab-sqlserver|lab-sqlserver-init|lab-sqlserver-data|14330|lab-sqlserver|SQL Server 2022: database labdb (sales, hr, ops)
mariadb|lab-mariadb||lab-mariadb-data|3307|lab-mariadb|MariaDB 11.4: databases shop and stock (orders, inventory)
oracle|lab-oracle||lab-oracle-data|1522|lab-oracle|Oracle Database Free 23ai: schemas FLEET and FREIGHT (needs its driver)
mail|lab-mailpit|||8025|-|Mailpit: catches the emails Kilnmark sends (SMTP 1025), read them at http://localhost:8025
files||||-|lab-share|File share lab/files/share, read from disk (mounted at /srv/share)'

SQLSERVER_LICENCE='https://go.microsoft.com/fwlink/?linkid=857698'

# Oracle's JDBC driver, which Kilnmark does not ship: the version and checksum OracleConnector pins.
ORACLE_DRIVER_FILE='ojdbc11-23.26.3.0.0.jar'
ORACLE_DRIVER_URL='https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar'
ORACLE_DRIVER_SHA256='764cc3f88454d1be117e155d01ed617467b984c9c68f13090dd6083799d2bb8c'
ORACLE_LICENCE='https://www.oracle.com/downloads/licenses/oracle-free-license.html'

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

# What is listening on a port: a container publishing it, else a process; empty if free.
port_holder() {
    local port=$1 row
    row=$("$ENGINE" ps --format '{{.Names}}|{{.Ports}}' 2>/dev/null | grep -E "[]:]$port->" | head -n 1)
    if [ -n "$row" ]; then echo "the container ${row%%|*}"; return; fi
    if command -v lsof >/dev/null 2>&1; then
        row=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1 " (process id " $2 ")"}')
        if [ -n "$row" ]; then echo "$row"; return; fi
    fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then echo "another program"; fi
}

cmd_start() {
    local names n a port service holder args=()
    names=$(expand "$@") || exit 2
    for n in $names; do
        service=$(field "$(record "$n")" 2)
        if [ -z "$service" ]; then
            echo "$n: nothing to start; Kilnmark reads it from disk."
        fi
        port=$(field "$(record "$n")" 5)
        if [ -n "$service" ] && [ "${port#*[!0-9]}" = "$port" ] && [ -n "$port" ] && ! running "${service%% *}"; then
            holder=$(port_holder "$port")
            if [ -n "$holder" ]; then
                die "$n: port $port is already in use by $holder. Stop that, then start $n again (see 'Port already in use' in lab/README.md)."
            fi
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
        if [ "$n" = "oracle" ]; then
            echo "oracle: the first start creates the database and loads the data, a few minutes; watch it with: $0 logs oracle"
            echo "oracle: Kilnmark needs Oracle's JDBC driver: Settings > Drivers, or $0 driver oracle"
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

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Downloads Oracle's JDBC driver into lab/drivers after showing its licence, checks it, and copies it
# into a running Kilnmark container's drivers folder. Kilnmark itself can do the same from
# Settings > Drivers; this is for a server that cannot reach Maven Central, or a quicker set-up.
cmd_driver() {
    local yes=0 name="" a file answer id
    for a in "$@"; do
        case "$a" in
            --yes|-y|--force) yes=1 ;;
            *) name=$a ;;
        esac
    done
    [ "$name" = "oracle" ] || die "name the driver to download: $0 driver oracle"
    echo "Oracle's JDBC driver ($ORACLE_DRIVER_FILE) is under the Oracle Free Use Terms and Conditions,"
    echo "which you accept by downloading it: $ORACLE_LICENCE"
    echo "Kilnmark does not ship it; this downloads it for your own development and testing."
    if [ "$yes" -ne 1 ]; then
        if [ ! -t 0 ]; then
            die "not asking without a terminal: add --yes to accept the licence and go ahead."
        fi
        printf 'Type yes to accept the licence and download it: '
        read -r answer || answer=
        [ "$answer" = "yes" ] || { echo "Nothing downloaded."; exit 1; }
    fi
    mkdir -p "$LAB_DIR/drivers"
    file="$LAB_DIR/drivers/$ORACLE_DRIVER_FILE"
    if [ ! -f "$file" ] || [ "$(sha256_of "$file")" != "$ORACLE_DRIVER_SHA256" ]; then
        curl -fsSL -o "$file.part" "$ORACLE_DRIVER_URL" || { rm -f "$file.part"; die "the download failed."; }
        if [ "$(sha256_of "$file.part")" != "$ORACLE_DRIVER_SHA256" ]; then
            rm -f "$file.part"
            die "the download is not the expected file (its SHA-256 differs), so it was not kept."
        fi
        mv "$file.part" "$file"
    fi
    echo "Saved and checked: $file"
    id=$("$ENGINE" ps -q --filter "label=com.docker.compose.service=kilnmark" 2>/dev/null | head -n 1)
    if [ -n "$id" ]; then
        "$ENGINE" exec "$id" mkdir -p /var/lib/kilnmark/drivers \
            && "$ENGINE" cp "$file" "$id:/var/lib/kilnmark/drivers/$ORACLE_DRIVER_FILE" \
            && echo "Copied into the running Kilnmark's drivers folder; Settings > Drivers now says Installed." \
            || die "could not copy it into the Kilnmark container: copy it to /var/lib/kilnmark/drivers yourself."
    else
        echo "Kilnmark is not running in a container here: put the file in its drivers folder"
        echo "(kilnmark.drivers.dir, by default ~/.kilnmark/drivers)."
    fi
}

# Adds each pipeline in a folder (lab/pipelines) to a running Kilnmark through its API. Needs an
# admin or steward token in KILNMARK_ADMIN_TOKEN (or KILNMARK_TOKEN); KILNMARK_URL defaults to
# http://localhost:8780. A pipeline that is already there is left as it is. Flows are skipped.
cmd_pipelines() {
    local folder=$1 command=$2 mapping=${3:-}
    local url=${KILNMARK_URL:-http://localhost:8780} token=${KILNMARK_TOKEN:-${KILNMARK_ADMIN_TOKEN:-}} file body code
    [ -n "$token" ] || die "set KILNMARK_ADMIN_TOKEN (or KILNMARK_TOKEN) to an admin or steward token first."
    command -v python3 >/dev/null 2>&1 || die "this needs python3, to send each file's YAML as JSON."
    while IFS= read -r file; do
        if grep -q '^kilnmark: *flow/' "$file"; then
            echo "Skipped  $(basename "$file") (a flow: flows are added once the flows API comes)"
            continue
        fi
        body=$(python3 - "$file" "$command" "$mapping" <<'PY'
import csv, json, re, sys
y = open(sys.argv[1], encoding="utf-8").read()
if sys.argv[3]:
    with open(sys.argv[3], encoding="utf-8", newline="") as f:
        to = {r["placeholder"]: r["lab_source"] for r in csv.DictReader(f)}
    line = re.compile(r"^(\s*(?:-\s+)?source:\s*)([\x22\x27]?)([A-Za-z0-9_.-]+)\2(\s*(?:#.*)?)$", re.M)
    y = line.sub(lambda m: m.group(1) + m.group(2) + to.get(m.group(3), m.group(3)) + m.group(2) + m.group(4), y)
n = [l.split(":", 1)[1].strip() for l in y.splitlines() if l.startswith("name:")][0]
print(json.dumps({"name": n, "yaml": y, "message": "Added by lab/lab.sh " + sys.argv[2]}))
PY
)
        code=$(printf '%s' "$body" | curl -s -o /dev/null -w '%{http_code}' -X POST "$url/api/v1/pipelines" \
            -H "Authorization: Bearer $token" -H "Content-Type: application/json" --data-binary @-) || code=000
        case "$code" in
            201) echo "Added    $(basename "$file")" ;;
            409) echo "Already there: $(basename "$file") (left as it is)" ;;
            401|403) die "Kilnmark refused the token ($code): it needs the steward or admin role." ;;
            000) die "could not reach Kilnmark at $url: is it running?" ;;
            *) die "Kilnmark answered $code for $(basename "$file")." ;;
        esac
    done < <(find "$folder" -name '*.yaml' -type f | sort)
    echo "Open Pipelines in Kilnmark ($url) to see them."
}

# Adds the samples (samples/, never samples/templates) to a running Kilnmark the way its Library
# does, through POST /api/v1/samples, so they are marked as samples and stay off the Pipelines and
# Flows pages (ADR 0043), and the sample flows come too. The samples name their sources by
# placeholders (crm-db, shop-db, files); lab/sample-sources.csv maps each to its lab source. Needs a
# designer or admin token in KILNMARK_ADMIN_TOKEN (or KILNMARK_TOKEN).
cmd_samples() {
    local mapping=$1
    local url=${KILNMARK_URL:-http://localhost:8780} token=${KILNMARK_TOKEN:-${KILNMARK_ADMIN_TOKEN:-}} body out code
    [ -n "$token" ] || die "set KILNMARK_ADMIN_TOKEN (or KILNMARK_TOKEN) to a designer or admin token first."
    command -v python3 >/dev/null 2>&1 || die "this needs python3, to send the mapping as JSON."
    body=$(python3 - "$mapping" <<'PY'
import csv, json, sys
with open(sys.argv[1], encoding="utf-8", newline="") as f:
    print(json.dumps({"sources": {r["placeholder"]: r["lab_source"] for r in csv.DictReader(f)}}))
PY
)
    out=$(mktemp)
    code=$(printf '%s' "$body" | curl -s -o "$out" -w '%{http_code}' -X POST "$url/api/v1/samples" \
        -H "Authorization: Bearer $token" -H "Content-Type: application/json" --data-binary @-) || code=000
    case "$code" in
        200) python3 - "$out" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
for s in d["samples"]:
    why = f" ({s['reason']})" if s.get("reason") else ""
    print(f"{s['outcome'].capitalize():8} {s['name']}{why}")
print(f"{d['added']} added.")
PY
            ;;
        401|403) rm -f "$out"; die "Kilnmark refused ($code): it needs a designer or admin token, and the samples offered." ;;
        000) rm -f "$out"; die "could not reach Kilnmark at $url: is it running?" ;;
        *) rm -f "$out"; die "Kilnmark answered $code." ;;
    esac
    rm -f "$out"
    echo "Open the Library in Kilnmark ($url) to see them."
}

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
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
    driver) cmd_driver "$@" ;;
    pipelines) cmd_pipelines "$LAB_DIR/pipelines" pipelines ;;
    samples) cmd_samples "$LAB_DIR/sample-sources.csv" ;;
    help|-h|--help) usage ;;
    *) usage >&2; echo >&2; die "unknown command '$command'." ;;
esac
