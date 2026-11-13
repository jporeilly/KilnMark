#!/bin/bash
# Which release the build under test must upgrade from (docs/development.md, "The upgrade
# rehearsal"): the newest published release that is lower than the build's own version, and no
# older than the first release later ones upgrade from.
#
#   previous-release.sh VERSION < TAGS
#
# VERSION is the build's (gradle.properties); TAGS are the published releases' tags, one a line
# (v0.1.2), drafts and pre-releases left out by the caller. Prints the previous release's version,
# or nothing when none applies (the build is the first such release, or nothing is published yet).
set -euo pipefail

# The first release that later ones upgrade from: installs from before it are installed afresh
# (docs/guide/install/upgrade.md, "From 0.1.1 or earlier"). This is the one place that says so.
first=0.1.2

version="${1:?the version of the build under test}"
number='^[0-9]+\.[0-9]+\.[0-9]+$'
[[ "$version" =~ $number ]] || { echo "$version is not a version (three numbers)." >&2; exit 2; }
# whether $1 is lower than $2, as versions
lower() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]; }

previous=""
while IFS= read -r tag || [ -n "$tag" ]; do
  tag="${tag%$'\r'}"; v="${tag#v}"
  [[ "$v" =~ $number ]] || continue
  lower "$v" "$version" || continue
  ! lower "$v" "$first" || continue
  if [ -z "$previous" ] || lower "$previous" "$v"; then previous="$v"; fi
done
printf '%s\n' "$previous"
