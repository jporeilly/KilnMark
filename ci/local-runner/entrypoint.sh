#!/bin/bash
# Starts the runner. The first start registers it with GitHub, using RUNNER_TOKEN (a registration
# token, valid for an hour); its settings are kept in /srv/gh-runner/runner, so later starts, after
# a restart of the container or the machine, need no token.
set -euo pipefail
home=/srv/gh-runner/runner
if [ ! -f "$home/.runner" ]; then
    : "${RUNNER_TOKEN:?first start: set RUNNER_TOKEN to a registration token (see docs/development.md)}"
    mkdir -p "$home"
    tar -xzf /opt/actions-runner.tar.gz -C "$home"
    cd "$home"
    ./config.sh --unattended --replace \
        --url "${RUNNER_URL:-https://github.com/jporeilly/KilnMark}" \
        --token "$RUNNER_TOKEN" \
        --name "${RUNNER_NAME:-pc-kilnmark-linux}" \
        --labels "${RUNNER_LABELS:-kilnmark-linux}" \
        --work /srv/gh-runner/_work
fi
unset RUNNER_TOKEN
cd "$home"
exec ./run.sh
