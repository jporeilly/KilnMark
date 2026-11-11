#!/bin/bash
# Runs the upgrade rehearsal on a machine of its own (docs/development.md, "The upgrade rehearsal"):
# one privileged container from ci/upgrade-rehearsal/Containerfile, a fresh Ubuntu with systemd and
# rootless Podman, started through the engine this runner already uses and removed at the end,
# however the run ends. Kilnmark's containers run inside it, under the account install makes, so
# its project, ports, volumes and images are that machine's alone: nothing is published on, tagged
# in or left on the runner or the engine other jobs share.
#
#   machine.sh run --previous DIR --release DIR --key PUB --cosign FILE [--expect-migration N]
#   machine.sh remove
#
#   --previous DIR   the previous release as downloaded (rehearse.sh says which files)
#   --release DIR    the build under test: the release folder, dist/release/kilnmark-<version>
#   --key PUB        Kilnmark's public key, config/release/cosign.pub
#   --cosign FILE    the cosign program (for Linux), which the control script checks signatures with
#
# The engine is docker unless KILNMARK_CI_ENGINE says another (podman, on a developer's machine).
# The machine's name comes from the workflow run (GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT), so "remove"
# in a later step finds it; KILNMARK_UPGRADE_KEEP=1 leaves it running to look at, and says its name.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
engine="${KILNMARK_CI_ENGINE:-docker}"
label="io.kilnmark.ci=upgrade-rehearsal"
name="kilnmark-upgrade-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"

remove() {
  "$engine" rm -f -v "$name" > /dev/null 2>&1 || true
}
# Machines an earlier run left because it was killed with its runner: this rehearsal's own, by their
# label, and older than three hours (a run takes well under one).
remove_stale() {
  local id made
  for id in $("$engine" ps -a -q --filter "label=$label" 2> /dev/null || true); do
    made="$("$engine" inspect --format '{{.Created}}' "$id" 2> /dev/null || true)"
    made="$(date -d "${made%% +*}" +%s 2> /dev/null || echo 0)"
    if [ "$made" -gt 0 ] && [ $(( $(date +%s) - made )) -gt 10800 ]; then
      echo "Removing a rehearsal machine an earlier run left: $id"
      "$engine" rm -f -v "$id" > /dev/null 2>&1 || true
    fi
  done
}
in_machine() { "$engine" exec "$name" "$@"; }

command="${1:-}"; [ $# -eq 0 ] || shift
case "$command" in
  remove) remove; echo "The rehearsal's machine $name is removed (or was never there)."; exit 0 ;;
  run) ;;
  *) echo "Say run or remove (see the top of this script)." >&2; exit 2 ;;
esac

previous="" release="" key="" cosign="" migration=""
while [ $# -gt 0 ]; do
  case "$1" in
    --previous) previous="${2:?}"; shift ;;
    --release) release="${2:?}"; shift ;;
    --key) key="${2:?}"; shift ;;
    --cosign) cosign="${2:?}"; shift ;;
    --expect-migration) migration="${2:?}"; shift ;;
    *) echo "Unknown option $1." >&2; exit 2 ;;
  esac
  shift
done
[ -d "$previous" ] && [ -f "$release/compose.yaml" ] && [ -f "$key" ] && [ -f "$cosign" ] \
  || { echo "Say --previous DIR, --release DIR, --key PUB and --cosign FILE (see the top of this script)." >&2; exit 2; }
version="$(basename "$release")"; version="${version#kilnmark-}"

tmp="$(mktemp -d)"
finish() {
  local rc=$?
  trap - EXIT
  rm -rf "$tmp"
  if [ "${KILNMARK_UPGRADE_KEEP:-}" = 1 ]; then
    echo "Kept the rehearsal's machine (KILNMARK_UPGRADE_KEEP=1): $engine exec -it $name bash; remove it with: $0 remove"
  else
    remove
  fi
  exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

remove_stale
remove

echo "Building the rehearsal's machine image ..."
image="$("$engine" build -q -t localhost/kilnmark-upgrade-machine:latest -f "$here/Containerfile" "$here")"
[ -n "$image" ] || { echo "The machine image could not be built." >&2; exit 1; }

# systemd as the container's first process. Podman sets a container up for it by itself; Docker
# needs the host's cgroups handed over and its own temporary file systems.
flags=(--privileged)
if [ "$(basename "$engine")" != podman ] && ! "$engine" version 2> /dev/null | grep -qi podman; then
  flags+=(--cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw --tmpfs /run --tmpfs /run/lock --tmpfs /tmp)
fi
echo "Starting the rehearsal's machine $name ..."
"$engine" run -d --name "$name" --label "$label" --hostname kilnmark-upgrade "${flags[@]}" "$image" > /dev/null
state=""
for _ in $(seq 1 60); do
  state="$(in_machine systemctl is-system-running 2> /dev/null || true)"
  case "$state" in running|degraded) break ;; esac
  sleep 2
done
case "$state" in
  running|degraded) echo "The machine is up (systemd: $state)." ;;
  *) echo "The machine's systemd did not come up (it says: ${state:-nothing})." >&2; "$engine" logs "$name" 2>&1 | tail -n 30 >&2; exit 1 ;;
esac
# As on a real machine, where / is a shared mount: rootless Podman warns on every command otherwise.
in_machine mount --make-rshared /

echo "Copying the releases into the machine ..."
tar -czf "$tmp/kilnmark-$version.tar.gz" -C "$(dirname "$release")" "kilnmark-$version"
in_machine mkdir -p /root/in/previous /root/in/packed
"$engine" cp "$previous/." "$name:/root/in/previous/"
"$engine" cp "$tmp/kilnmark-$version.tar.gz" "$name:/root/in/kilnmark-$version.tar.gz"
"$engine" cp "$key" "$name:/root/in/cosign.pub"
"$engine" cp "$cosign" "$name:/usr/local/bin/cosign"
for f in rehearse.sh pack.sh; do "$engine" cp "$here/$f" "$name:/root/in/$f"; done
in_machine chmod 755 /usr/local/bin/cosign
in_machine chown -R root:root /root/in /usr/local/bin/cosign

in_machine bash /root/in/pack.sh "/root/in/kilnmark-$version.tar.gz" /root/in/packed
in_machine bash /root/in/rehearse.sh --previous /root/in/previous --build /root/in/packed --key /root/in/cosign.pub \
  --build-key /root/in/packed/build-cosign.pub ${migration:+--expect-migration "$migration"}
