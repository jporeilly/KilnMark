#!/bin/bash
# Checks previous-release.sh against the cases that matter; the upgrade workflow runs it first.
#   previous-release-test.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
failed=0
# $1 the build's version, $2 the previous release expected (or nothing), the rest the published tags
check() {
  local version="$1" want="$2" got; shift 2
  got="$(printf '%s\n' "$@" | bash "$here/previous-release.sh" "$version")"
  if [ "$got" != "$want" ]; then
    echo "previous-release.sh $version with [$*] says '${got}', not '${want}'" >&2
    failed=1
  fi
}
check 0.1.3 0.1.2 v0.1.2
# releases from before 0.1.2 are never upgraded from
check 0.1.3 0.1.2 v0.0.10 v0.1.1 v0.1.2
check 0.1.3 "" v0.0.10 v0.1.1
# the build's own release, and later ones, do not count
check 0.1.2 "" v0.1.1 v0.1.2
check 0.1.3 0.1.2 v0.1.2 v0.1.3 v0.1.4
# by version, not by text or order: 0.1.9 is before 0.1.10
check 0.2.0 0.1.10 v0.1.10 v0.1.2 v0.1.9
check 1.0.0 0.9.9 v0.9.9 v0.1.2 junk v1.0.0-rc1 v1.0.0
# nothing published
check 0.1.3 ""
if bash "$here/previous-release.sh" not-a-version < /dev/null > /dev/null 2>&1; then
  echo "previous-release.sh took 'not-a-version' as a version" >&2
  failed=1
fi
[ "$failed" -eq 0 ] && echo "previous-release.sh: every case as expected."
exit "$failed"
