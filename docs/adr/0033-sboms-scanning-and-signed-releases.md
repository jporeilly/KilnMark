# 0033 SBOMs, vulnerability scanning and signed releases

Status: Accepted (3 November 2026)

## Context

A bank's vendor-risk team asks what a product is made of, whether it carries known vulnerabilities,
and how to tell that the files it received are the ones the vendor built. US federal agencies may
ask for an SBOM or a secure-development attestation (OMB M-26-05), and DORA expects the same of ICT
providers to EU banks. The compliance map ([compliance.md](../product/compliance.md), "Secure
development and supply chain") had none of it: CI built and tested every change, and the dependency
policy kept GPL out (ADR 0007), but a release was a folder with no list of its parts, nothing
checked them for vulnerabilities, and nothing proved where it came from.

## Decision

1. **An SBOM with every release**, CycloneDX JSON, in the release's `sbom` folder and beside it as
   release files:
   - `kilnmark-server.cdx.json`: every library on the server's runtime classpath, from the
     CycloneDX Gradle plugin (Apache-2.0, build time only). The plugin adds the repository's Git
     address; the build removes it, as the repository is private.
   - `kilnmark-ui.cdx.json`: the packages the web UI bundles (no development tools or types), from
     npm's own `npm sbom`, so no extra tool, kept to the packages the UI's licence notices list.
     `npm sbom --omit=dev` is not used: it drops a runtime package that a build tool also names as
     an optional peer (`yaml`, through Vite).
2. **Scanning in CI** with Trivy (Apache-2.0), downloaded from its release and checked against a
   pinned checksum, never through a third-party action. It runs on every pull request, on main,
   and every Monday, so a vulnerability published after a merge is found without waiting for one:
   - **Fails** on a high or critical vulnerability with a fix available in what Kilnmark ships (the
     two SBOMs). The fix is to raise the version; when the Quarkus platform holds a library back,
     the server's `securityPins` raise it over the platform (lz4-java and snappy-java on 3
     November 2026).
   - **Reports** on the base images' operating system layers (PostgreSQL's, Temurin's) in the run's
     summary without failing: their fixes come from the base image's maintainers, and a red build
     would block every change for something no change here can fix. Dependabot proposes newer base
     images monthly.
3. **Dependabot version updates**, monthly and grouped per ecosystem (Gradle, npm, GitHub Actions,
   base images), so updates arrive as at most four pull requests a month that CI and the scan
   check. The new workflows pin actions by commit, which Dependabot keeps current.
4. **Signed releases.** The owner pushes a version tag; a workflow builds the release, packages it
   (`.tar.gz` and `.zip`), adds the SBOMs and a `SHA256SUMS`, signs every file with cosign, checks
   each signature against the published public key (`config/release/cosign.pub`), and leaves a
   **draft** GitHub release for the owner to publish. Nothing is published by the workflow itself.
   - **A key of the owner's, not keyless signing.** Keyless signing records each signature in
     Sigstore's public transparency log with the identity of the workflow that made it, which names
     the repository; this one is private. The key pair is made once by the owner (`cosign
     generate-key-pair`); the private half and its password are repository secrets, the public half
     is committed. Without them a tag fails to release, with a message saying what is missing.
   - A pull request that changes the workflow rehearses it: a throwaway key, the same signing and
     verifying, and no release.
   - **Images are not signed yet**: a release ships Containerfiles, and the customer builds the
     images from the signed release files. When Kilnmark publishes images, they are signed with
     the same key.
5. **`SECURITY.md`**: which versions get fixes, how to report a vulnerability privately, and what
   happens next.

## Consequences

- A customer can list what a release contains, scan it with their own tools, and check every file
  with one public key (INSTALL.md, "Check a release").
- A vulnerability with a fix stops changes until it is fixed, including on a Monday with no change
  in progress; that is intended. One without a fix, or in a base image, is visible but not
  blocking.
- Signatures are off the public log, so a stolen key could sign without anyone seeing it there. The
  key is kept only in the repository's secrets, its password apart, and a key change is announced
  with the next release.
- Still to come from the compliance map: an SSDF self-assessment for federal customers, and signed
  images once images are published.
