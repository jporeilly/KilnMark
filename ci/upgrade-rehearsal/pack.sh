#!/bin/bash
# Packs the build under test as a release is packed, for the upgrade rehearsal (docs/development.md,
# "The upgrade rehearsal"). As root on the rehearsal's machine, where cosign is on the PATH:
#
#   pack.sh kilnmark-<version>.tar.gz OUT
#
# The archive is the release folder (./gradlew release, dist/release/kilnmark-<version>) as a
# tar.gz. Into OUT go the archive, the offline images file made from it as the release workflow
# makes it (both images built from the release folder, here with this machine's own Podman, so
# nothing is tagged in an engine other work shares), a signature for each from a key made for this
# run, and that key's public half, build-cosign.pub. The private half is deleted before this ends.
set -euo pipefail
umask 077
archive="${1:?the release folder as kilnmark-<version>.tar.gz}" out="${2:?the folder to pack into}"
[ -f "$archive" ] || { echo "There is no $archive." >&2; exit 2; }
v="$(basename "$archive" .tar.gz)"; v="${v#kilnmark-}"
registry=ghcr.io/jporeilly

tmp="$(mktemp -d /root/kilnmark-pack.XXXXXX)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"
tar -xzf "$archive" -C "$tmp"
src="$tmp/kilnmark-$v"
[ -f "$src/compose.yaml" ] || { echo "$archive does not hold kilnmark-$v/compose.yaml." >&2; exit 1; }

# The builds use this machine's own network (--network host): inside a container, as the
# rehearsal's machine is, a network of Podman's own for the build has no name server to ask.
echo "Building the images of Kilnmark $v ..."
podman build -q --network host -t "$registry/kilnmark-postgres:$v" -f "$src/containers/postgres/Containerfile" "$src/containers/postgres"
podman build -q --network host -t "$registry/kilnmark-server:$v" --build-arg APP_DIR=server -f "$src/containers/server/Containerfile" "$src"
# --multi-image-archive: without it Podman's save writes one image under both names
podman save --multi-image-archive "$registry/kilnmark-server:$v" "$registry/kilnmark-postgres:$v" | gzip > "$out/kilnmark-$v-images.tar.gz"
# both are in the file, each under its own name, as a load will find them
names="$(gzip -dc "$out/kilnmark-$v-images.tar.gz" | tar -xOf - manifest.json | jq -r '.[] | "\(.Config) \(.RepoTags | join(","))"')"
[ "$(printf '%s\n' "$names" | wc -l)" = 2 ] && grep -q "kilnmark-server:$v\$" <<< "$names" && grep -q "kilnmark-postgres:$v\$" <<< "$names" \
  || { echo "The images file does not hold the two images, each under its own name:" >&2; printf '%s\n' "$names" >&2; exit 1; }
# root's copies are not needed again: the install loads the file into the account's own Podman
podman rmi "$registry/kilnmark-server:$v" "$registry/kilnmark-postgres:$v" > /dev/null
cp "$archive" "$out/kilnmark-$v.tar.gz"

# Signed as a release is, with a key of this run's own (its password too), and checked.
password="$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
(cd "$tmp" && COSIGN_PASSWORD="$password" cosign generate-key-pair > /dev/null 2>&1)
for f in "kilnmark-$v.tar.gz" "kilnmark-$v-images.tar.gz"; do
  COSIGN_PASSWORD="$password" cosign sign-blob --yes --key "$tmp/cosign.key" --tlog-upload=false --use-signing-config=false \
    --bundle "$out/$f.sigstore.json" "$out/$f" > /dev/null 2>&1
  cosign verify-blob --key "$tmp/cosign.pub" --insecure-ignore-tlog=true --bundle "$out/$f.sigstore.json" "$out/$f" > /dev/null 2>&1 \
    || { echo "$f does not match the signature just made for it." >&2; exit 1; }
done
rm -f "$tmp/cosign.key"
cp "$tmp/cosign.pub" "$out/build-cosign.pub"
chmod 644 "$out"/*
ls -la "$out"
