#!/bin/sh
# kilnmark-run: start a Kilnmark pipeline or flow from a scheduler (Control-M, AutoSys, IBM Workload
# Automation, cron, a systemd timer) and wait for it to end, with an exit code the scheduler can act
# on (docs/guide/external-schedulers.md, ADR 0047). POSIX sh and curl only.
#
#   kilnmark-run.sh pipeline|flow NAME [--param NAME=VALUE]... [--version N] [--timeout 2h]
#                   [--key REQUEST_KEY] [--cancel-on-timeout]
#
# Settings, from the environment:
#   KILNMARK_URL         Kilnmark's address, for example https://kilnmark.example.com:8443
#   KILNMARK_TOKEN_FILE  a file holding a steward API token, readable only by the job's account.
#                        Never on the command line or in an environment value: process lists and
#                        job logs show those.
#   KILNMARK_CA_FILE     optional: the private CA that signed Kilnmark's certificate (PEM).
#
# Exit codes: 0 succeeded, 1 failed, 2 could not start (refused), 3 timed out waiting, 4 cancelled,
# 5 could not reach the server, or bad usage. The token is never printed.

set -u
umask 077

me="kilnmark-run"
EX_OK=0 EX_FAILED=1 EX_REFUSED=2 EX_TIMEOUT=3 EX_CANCELLED=4 EX_UNREACHABLE=5
# The longest one request asks to wait, in seconds; the server may cut it shorter (kilnmark.api.max-wait).
chunk=900
# Tries in a row before a server that cannot be reached counts as gone.
tries=5

say() { printf '%s\n' "$*"; }
err() { printf '%s: %s\n' "$me" "$*" >&2; }

usage() {
  cat >&2 <<'EOF'
Usage: kilnmark-run.sh pipeline|flow NAME [--param NAME=VALUE]... [--version N] [--timeout 2h]
                       [--key REQUEST_KEY] [--cancel-on-timeout]

Starts a run of the pipeline or flow, waits until it ends, and exits with:
  0 succeeded, 1 failed, 2 could not start, 3 timed out waiting, 4 cancelled,
  5 could not reach the server or bad usage.

--param NAME=VALUE   A value for one of its parameters. A number, true or false is sent as
                     such; [..] as a JSON list; "..." as text; anything else as text.
--version N          Run version N (the latest by default).
--timeout DURATION   Stop waiting after this long: 90 (seconds), 30s, 10m, 2h or 1d. The run is
                     left going unless --cancel-on-timeout. No limit by default.
--key REQUEST_KEY    The request key (1 to 200 printable characters). A retry with the same key
                     within 24 hours gets the run the first try started, never a second one.
                     Made up and printed when left out.
--cancel-on-timeout  Cancel the run when the timeout is reached.

Reads KILNMARK_URL, KILNMARK_TOKEN_FILE (a file holding the API token) and, optionally,
KILNMARK_CA_FILE (the private CA that signed Kilnmark's certificate).
EOF
  exit "$EX_UNREACHABLE"
}

nl='
'

# JSON text for a value: backslashes and double quotes escaped. Control characters are refused.
json_text() {
  case $1 in
    *"$nl"*|*"$(printf '\r')"*|*"$(printf '\t')"*) err "a value holds a line break or a tab; leave them out"; exit "$EX_UNREACHABLE" ;;
  esac
  printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
}

# A parameter's value as JSON: a number, true or false as such; [..] and "..." as written; else text.
json_value() {
  case $1 in
    true|false) printf '%s' "$1"; return ;;
    \[*\]|\"*\") printf '%s' "$1"; return ;;
  esac
  if printf '%s' "$1" | grep -Eq '^-?[0-9]+(\.[0-9]+)?$'; then
    printf '%s' "$1"
  else
    json_text "$1"
  fi
}

# A duration in seconds: 90, 30s, 10m, 2h or 1d.
seconds() {
  case $1 in
    ''|*[!0-9smhd]*) return 1 ;;
  esac
  n=${1%[smhd]}
  case $n in ''|*[!0-9]*) return 1 ;; esac
  case $1 in
    *d) echo $((n * 86400)) ;;
    *h) echo $((n * 3600)) ;;
    *m) echo $((n * 60)) ;;
    *) echo "$n" ;;
  esac
}

# ---- arguments ----

[ $# -ge 2 ] || usage
kind=$1 name=$2
shift 2
case $kind in pipeline|flow) ;; *) err "the first argument is pipeline or flow"; usage ;; esac
case $name in ''|*[!a-z0-9-]*) err "a name is small letters, digits and dashes"; exit "$EX_UNREACHABLE" ;; esac

params="" version="" timeout="" key="" keyed=no cancel=no
while [ $# -gt 0 ]; do
  case $1 in
    --param)
      [ $# -ge 2 ] || usage
      case $2 in *=*) ;; *) err "--param takes NAME=VALUE"; exit "$EX_UNREACHABLE" ;; esac
      pname=${2%%=*} pvalue=${2#*=}
      [ -n "$pname" ] || { err "--param takes NAME=VALUE"; exit "$EX_UNREACHABLE"; }
      params="$params${params:+,}$(json_text "$pname"):$(json_value "$pvalue")"
      shift 2 ;;
    --version)
      [ $# -ge 2 ] || usage
      case $2 in ''|*[!0-9]*) err "--version takes a whole number"; exit "$EX_UNREACHABLE" ;; esac
      version=$2; shift 2 ;;
    --timeout)
      [ $# -ge 2 ] || usage
      timeout=$(seconds "$2") || { err "--timeout takes a duration such as 90, 30s, 10m, 2h or 1d"; exit "$EX_UNREACHABLE"; }
      shift 2 ;;
    --key)
      [ $# -ge 2 ] || usage
      key=$2 keyed=yes; shift 2 ;;
    --cancel-on-timeout) cancel=yes; shift ;;
    -h|--help) usage ;;
    *) err "unknown option: $1"; usage ;;
  esac
done

if [ "$keyed" = yes ]; then
  if [ -z "$key" ] || [ ${#key} -gt 200 ] || printf '%s' "$key" | LC_ALL=C grep -q '[^ -~]'; then
    err "--key takes 1 to 200 printable characters"; exit "$EX_UNREACHABLE"
  fi
else
  key="$me-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
fi

# ---- settings ----

base=${KILNMARK_URL:-}
base=${base%/}
case $base in
  https://?*) ;;
  http://127.0.0.1|http://127.0.0.1:*|http://localhost|http://localhost:*|http://\[::1\]|http://\[::1\]:*) ;;
  http://*) err "KILNMARK_URL must start with https:// (plain http only to this machine): the token would cross the network unprotected"; exit "$EX_UNREACHABLE" ;;
  *) err "set KILNMARK_URL to Kilnmark's address, for example https://kilnmark.example.com:8443"; exit "$EX_UNREACHABLE" ;;
esac
case $base in *[\"\\\ ]*) err "KILNMARK_URL holds a character an address cannot"; exit "$EX_UNREACHABLE" ;; esac

tokens=${KILNMARK_TOKEN_FILE:-}
if [ -z "$tokens" ] || [ ! -r "$tokens" ]; then
  err "set KILNMARK_TOKEN_FILE to a file holding a steward API token, readable by this account"; exit "$EX_UNREACHABLE"
fi
token=$(tr -d ' \t\r\n' < "$tokens")
case $token in
  km_*) case ${token#km_} in ''|*[!A-Za-z0-9_-]*) token="" ;; esac ;;
  *) token="" ;;
esac
[ -n "$token" ] || { err "the file KILNMARK_TOKEN_FILE names does not hold a Kilnmark API token (km_...)"; exit "$EX_UNREACHABLE"; }

command -v curl >/dev/null 2>&1 || { err "curl is needed and was not found"; exit "$EX_UNREACHABLE"; }

work=$(mktemp -d 2>/dev/null) || work="${TMPDIR:-/tmp}/$me.$$"
[ -d "$work" ] || mkdir -m 700 "$work" || { err "could not make a folder for temporary files"; exit "$EX_UNREACHABLE"; }
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT TERM

# The token goes to curl in a file of its own (umask 077), never on curl's command line.
{
  printf 'header = "Authorization: Bearer %s"\n' "$token"
  printf 'silent\nshow-error\nconnect-timeout = 20\n'
  if [ -n "${KILNMARK_CA_FILE:-}" ]; then
    [ -r "$KILNMARK_CA_FILE" ] || { err "KILNMARK_CA_FILE names a file this account cannot read"; exit "$EX_UNREACHABLE"; }
    printf 'cacert = "%s"\n' "$(printf '%s' "$KILNMARK_CA_FILE" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  fi
} > "$work/curl.conf"
token=""

# ---- the API ----

# call METHOD PATH MAX_SECONDS [BODY]: sets $code (HTTP status, or 000 when the server could not be
# reached) and leaves the body in $work/body and the headers in $work/head. Runs come back in the
# plain-text form (one "name: value" a line); anything else, and every refusal, as JSON.
call() {
  : > "$work/body"; : > "$work/head"
  case $2 in */cancel) accept=application/json ;; *) accept=text/plain ;; esac
  if [ $# -ge 4 ]; then
    printf '%s' "$4" > "$work/request"
    code=$(curl --config "$work/curl.conf" -X "$1" --max-time "$3" -o "$work/body" -D "$work/head" \
      -w '%{http_code}' -H "Accept: $accept" -H "Content-Type: application/json" -H "Idempotency-Key: $key" \
      --data-binary @"$work/request" "$base$2") || code=000
  else
    code=$(curl --config "$work/curl.conf" -X "$1" --max-time "$3" -o "$work/body" -D "$work/head" \
      -w '%{http_code}' -H "Accept: $accept" "$base$2") || code=000
  fi
  case $code in [1-5][0-9][0-9]) ;; *) code=000 ;; esac
}

# A field of the run as last read (the plain-text form: one "name: value" a line).
field() { sed -n "s/^$1: //p" "$work/run" | head -n 1 | tr -d '\r'; }

# The error a refusal gave, from {"error": "..."}, or the HTTP status.
refusal() {
  m=$(sed -n 's/^{"error":"\(.*\)"}$/\1/p' "$work/body" | sed -e 's/\\"/"/g' -e 's/\\\\/\\/g' | head -n 1)
  printf '%s' "${m:-HTTP $code}"
}

retry_after() {
  r=$(sed -n 's/^[Rr]etry-[Aa]fter: *\([0-9][0-9]*\).*/\1/p' "$work/head" | head -n 1)
  echo "${r:-5}"
}

now() { date +%s; }
start=$(now)

# How long the next wait may be: the chunk, or what is left before the timeout (0 when it is up).
next_wait() {
  if [ -z "$timeout" ]; then echo "$chunk"; return; fi
  left=$((start + timeout - $(now)))
  if [ "$left" -le 0 ]; then echo 0; elif [ "$left" -lt "$chunk" ]; then echo "$left"; else echo "$chunk"; fi
}

finish() {
  status=$(field status)
  message=$(field message)
  say "Status: $status"
  [ -z "$message" ] || say "Message: $message"
  case $status in
    succeeded) exit "$EX_OK" ;;
    failed) exit "$EX_FAILED" ;;
    cancelled) exit "$EX_CANCELLED" ;;
    *) err "the run ended with a status this script does not know: $status"; exit "$EX_FAILED" ;;
  esac
}

timed_out() {
  say "Status: $(field status) (still going after the timeout)"
  if [ "$cancel" = yes ]; then
    call POST "/api/v1/$kind-runs/$id/cancel" 60
    case $code in
      200) say "Cancel: asked; the run stops within a few seconds" ;;
      409) say "Cancel: the run had already ended" ;;
      *) err "could not cancel the run: $(refusal)" ;;
    esac
  else
    say "The run is left going. Its page shows how it ends."
  fi
  exit "$EX_TIMEOUT"
}

# ---- start ----

case $kind in pipeline) plural=pipelines ;; flow) plural=flows ;; esac
body="{\"parameters\":{$params}${version:+,\"version\":$version}}"
say "Request key: $key"

failures=0
while :; do
  w=$(next_wait)
  [ "$w" -gt 0 ] || { err "timed out before the run could be started"; exit "$EX_TIMEOUT"; }
  call POST "/api/v1/$plural/$name/runs?wait=$w" $((w + 60)) "$body"
  case $code in
    200|202) cp "$work/body" "$work/run"; break ;;
    503)
      a=$(retry_after); failures=0
      say "Kilnmark is busy; asking again in $a seconds"
      sleep "$a" ;;
    400|401|403|404|409|422)
      err "could not start the $kind: $(refusal)"; exit "$EX_REFUSED" ;;
    *)
      failures=$((failures + 1))
      if [ "$failures" -ge "$tries" ]; then
        err "could not reach Kilnmark at $base ($(refusal)); the request key above is safe to use again"
        exit "$EX_UNREACHABLE"
      fi
      err "no answer from Kilnmark ($(refusal)); trying again in 10 seconds with the same key"
      sleep 10 ;;
  esac
done

id=$(field id)
case $id in ''|*[!0-9]*) err "Kilnmark's answer had no run id"; exit "$EX_UNREACHABLE" ;; esac
if grep -qi '^idempotent-replayed: *true' "$work/head"; then
  say "Run: $id (started earlier with this request key)"
else
  say "Run: $id"
fi
say "Page: $base/#/$kind-runs/$id"

# ---- wait ----

failures=0
while :; do
  if [ "$code" = 200 ] && [ "$(field ended)" = true ]; then
    finish
  fi
  w=$(next_wait)
  [ "$w" -gt 0 ] || timed_out
  call GET "/api/v1/$kind-runs/$id?wait=$w" $((w + 60))
  case $code in
    200) failures=0; cp "$work/body" "$work/run" ;;
    503) a=$(retry_after); sleep "$a"; code=0 ;;
    401|403|404)
      err "could not read run $id: $(refusal)"; exit "$EX_UNREACHABLE" ;;
    *)
      failures=$((failures + 1))
      if [ "$failures" -ge "$tries" ]; then
        err "lost Kilnmark at $base while waiting ($(refusal)); run $id may still be going"
        exit "$EX_UNREACHABLE"
      fi
      sleep 10; code=0 ;;
  esac
done
