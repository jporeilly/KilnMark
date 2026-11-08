# Checking a release

Check each file you downloaded before unpacking or loading it. Use
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/) 3.0 or later (earlier
versions cannot read the signatures) and Kilnmark's public key: `cosign.pub`, from the release's
owner or `config/release/cosign.pub` in the repository.

```
cosign verify-blob --key cosign.pub --insecure-ignore-tlog=true \
  --bundle kilnmark-<version>.tar.gz.sigstore.json kilnmark-<version>.tar.gz
```

`Verified OK` means the file is the one Kilnmark signed. The signatures are deliberately kept off
Sigstore's public log, which is what `--insecure-ignore-tlog` acknowledges; the key is the proof.

- `sha256sum -c SHA256SUMS --ignore-missing` checks the files against the signed list of checksums.
- The SBOMs, `kilnmark-<version>-server.cdx.json` and `-ui.cdx.json` (also in the release's `sbom`
  folder), list every library and package it contains, for your own scanners.
- Install, and later `upgrade`, check the offline images file and later releases themselves, with
  the `cosign.pub` the release carries; `--skip-verify` (`-SkipVerify`) goes on without, and the
  log says so.
- Pulled images are signed by digest:
  `cosign verify --key cosign.pub --insecure-ignore-tlog=true ghcr.io/jporeilly/kilnmark-server@sha256:...`

A known limit: the signed `SHA256SUMS` covers the archives, not each file of an install, so the
files a run uses are not checked against a signature before each run. Once nobody but root (or the
Administrators) may write the program folder, nobody else can change them
([ADR 0051](../../adr/0051-starter-pack.md), decision 15).
