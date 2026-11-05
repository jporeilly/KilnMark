# Supply chain

What Kilnmark is made of, how it is checked, and how a customer knows a release is the one we
built. The decisions are in [ADR 0033](../adr/0033-sboms-scanning-and-signed-releases.md); the
controls they answer are in [Compliance](../product/compliance.md).

## SBOMs

| File | What it lists | Made by |
| --- | --- | --- |
| `sbom/kilnmark-server.cdx.json` | Every library on the server's runtime classpath | `./gradlew :server:cyclonedxDirectBom` (CycloneDX Gradle plugin), in `dist/build/server/sbom` |
| `sbom/kilnmark-ui.cdx.json` | The packages the web UI bundles, without development tools | `./gradlew :server:uiSbom` (`ui/scripts/ui-sbom.mjs`: npm's SBOM, kept to the packages the licence notices list), in `dist/ui-sbom` |

`./gradlew release` makes both and puts them in the release folder. They are CycloneDX JSON, which
most scanners and vendor-risk tools read.

## Scanning

`.github/workflows/supply-chain.yml` runs Trivy on every pull request, on main and every Monday:

- **What Kilnmark ships** (the two SBOMs): a high or critical vulnerability with a fix fails the
  run. Raise the version in `gradle/libs.versions.toml` or `ui/package.json`. When the Quarkus
  platform pins the library, add it to `securityPins` in `server/build.gradle.kts`, which raises it
  over the platform, and take it out again when the platform catches up.
- **The base images** (Kilnmark's PostgreSQL image and Temurin's JRE image): reported in the run's
  summary, never failing it. Dependabot proposes newer base images monthly.

To scan locally, with Trivy installed: `trivy sbom --severity HIGH,CRITICAL --ignore-unfixed
dist/build/server/sbom/kilnmark-server.cdx.json`.

Dependabot (`.github/dependabot.yml`) opens one grouped pull request a month for each of Gradle,
npm, GitHub Actions and the base images, with minor and patch versions only. A major version changes
APIs, so it gets a pull request of its own, written by a person with the code changes it needs.

## What the build checks

[ADR 0042](../adr/0042-static-analysis-and-dependency-verification.md):

- **Every library the build downloads** is checked against `gradle/verification-metadata.xml`:
  its sha256 checksum, and its signature when it is signed (the signers' keys are in
  `gradle/verification-keyring.keys`). The web UI's packages are checked the same way by npm, from
  the checksums in `ui/package-lock.json`. A dependency change updates the metadata in the same pull
  request ([Development](../development.md#dependency-verification)).
- **The Gradle wrapper's jar** is checked in CI against Gradle's published checksums, and the
  Gradle distribution it downloads against `distributionSha256Sum`.
- **The Java code** is read by Error Prone in every compile; a finding fails the build.

## Releases

`.github/workflows/release.yml`, on a version tag the owner pushes (`v` and the version in
`gradle.properties`):

1. builds the release folder (`./gradlew release`);
2. packages it as `kilnmark-<version>.tar.gz` and `.zip`, with the two SBOMs beside them and a
   `SHA256SUMS` of all four;
3. signs every file with the release key (cosign, kept off the public transparency log), writing
   `<file>.sigstore.json` beside each;
4. verifies every signature against `config/release/cosign.pub`;
5. creates a draft GitHub release with all of it, for the owner to publish.

A pull request that changes the workflow, or the builds that make the release's files, rehearses
steps 1 to 4 with a throwaway key.
[Development](../development.md#releasing) says how the owner sets up the key; INSTALL.md says how a
customer checks a release.
