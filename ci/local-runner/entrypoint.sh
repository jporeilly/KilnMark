#!/bin/bash
# Starts the runner. The runner's own files come from the image on every start, so the version pinned
# and checked in the Containerfile is the one that runs; only its registration (.runner,
# .credentials*) is kept in $RUNNER_BASE/runner. The first start registers it, with a registration
# token read from $RUNNER_BASE/token (valid for an hour) and deleted at once; it is never passed in
# the environment, where a job could read it from the container's settings. RUNNER_BASE
# (/srv/gh-runner by default) is mounted at the same path inside and outside the container; each
# runner on a machine has its own.
set -euo pipefail
base=${RUNNER_BASE:-/srv/gh-runner}
home=$base/runner
mkdir -p "$home"
# Whatever an earlier start (or an earlier, self-updating runner) left is replaced, not mixed in.
rm -rf "$home/bin" "$home/externals"
tar -xzf /opt/actions-runner.tar.gz -C "$home"
cd "$home"
if [ ! -f .runner ]; then
    token_file=$base/token
    [ -s "$token_file" ] || { echo "first start: put a registration token in $token_file (docs/development.md)" >&2; exit 1; }
    token=$(cat "$token_file")
    rm -f "$token_file"
    ./config.sh --unattended --replace --disableupdate \
        --url "${RUNNER_URL:-https://github.com/jporeilly/KilnMark}" \
        --token "$token" \
        --name "${RUNNER_NAME:-pc-kilnmark-linux}" \
        --labels "${RUNNER_LABELS:-kilnmark-linux}" \
        --work $base/_work
    unset token
fi
exec ./run.sh
