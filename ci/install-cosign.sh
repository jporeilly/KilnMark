#!/bin/bash
# Puts cosign into a folder, for the workflows that sign and check releases (release.yml,
# upgrade.yml): downloaded from its own release and checked against the checksum below, with no
# third-party action. This is the one place that says which cosign CI uses.
#
#   ci/install-cosign.sh DIR
set -euo pipefail
version="3.1.4"
sha256="424e2c97d0b45fd7a6b608a87f090f35633f4c23ece5f092d48bba11aaf4bbfa"

dir="${1:?the folder to put cosign in}"
mkdir -p "$dir"
curl -sSfL -o "$dir/cosign" "https://github.com/sigstore/cosign/releases/download/v${version}/cosign-linux-amd64"
echo "${sha256}  $dir/cosign" | sha256sum -c -
chmod +x "$dir/cosign"
