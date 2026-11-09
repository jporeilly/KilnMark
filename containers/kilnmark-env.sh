#!/bin/bash
# Starts one of the release's services with its secrets (ADR 0051, decision 13). Compose runs it in
# place of the service's own entrypoint (compose.yaml, entrypoint), with that entrypoint and its
# arguments after it. It reads the file the control script wrote for this service alone, mounted
# read-only at /run/kilnmark/env, NAME=value a line, takes each value as it is, and puts it in the
# environment of this process and the service's own program only. So no secret is ever a setting of
# the container: compose tools put those on podman's command line (podman-compose does even for
# env_file), where any account on the machine can read them.
set -eu
f=/run/kilnmark/env
if [ ! -f "$f" ] || [ ! -r "$f" ]; then
  echo "kilnmark: this service's secrets ($f) are not there, so it did not start: start Kilnmark with its control script (kilnmark.sh up, or kilnmark.cmd up), which writes them." >&2
  exit 78
fi
n=0
while IFS= read -r line || [ -n "$line" ]; do
  n=$((n + 1))
  line="${line%$'\r'}"
  [ -n "$line" ] || continue
  name="${line%%=*}"
  if [ "$name" = "$line" ] || ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
    echo "kilnmark: line $n of $f is not NAME=value, so this service did not start." >&2
    exit 78
  fi
  export "$name=${line#*=}"
done < "$f"
unset f n line name
[ $# -gt 0 ] || { echo "kilnmark: no program to start was given." >&2; exit 78; }
exec "$@"
