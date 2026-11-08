#!/bin/sh
# Keeps the Starter Pack's login (ADR 0051), kilnmark_samples, to its own database: these lines at
# the top of pg_hba.conf let it in to kilnmark_samples over TLS only, and refuse it everywhere else,
# whatever the grants say. Run at every start by kilnmark-tls.sh with the file as $1, and, for a new
# data folder, from docker-entrypoint-initdb.d. Does nothing when the file is not there yet; changes
# nothing when the lines are already first.
set -eu
hba=${1:-${PGDATA:-}/pg_hba.conf}
[ -f "$hba" ] || exit 0
begin="# kilnmark-samples: begin (kilnmark-hba.sh keeps these first)"
end="# kilnmark-samples: end"
block="$begin
hostssl kilnmark_samples kilnmark_samples all scram-sha-256
hostssl all              kilnmark_samples all reject
host    all              kilnmark_samples all reject
local   all              kilnmark_samples     reject
$end"
if [ "$(head -n 6 "$hba")" = "$block" ]; then exit 0; fi
tmp=$(mktemp)
{ printf '%s\n' "$block"; awk -v b="$begin" -v e="$end" '$0 == b { skip = 1 } !skip { print } $0 == e { skip = 0 }' "$hba"; } > "$tmp"
# in place, so the file keeps its owner and mode
cat "$tmp" > "$hba"
rm -f "$tmp"
