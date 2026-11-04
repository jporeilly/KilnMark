# SSDF self-assessment

How Kilnmark is built against NIST's Secure Software Development Framework (SSDF), SP 800-218
version 1.1, practice by practice: what is done, where the evidence is, and what is missing. It is a
self-assessment by the maintainer, not an audit by a third party. Assessed on 4 November 2026, at
the commit this file arrived in.

**Who asks for it.** US federal agencies may ask a vendor for a secure-development attestation
based on the SSDF (OMB M-26-05, case by case), and banks' vendor-risk teams ask the same questions
in their due diligence ([Compliance](compliance.md)). The CISA attestation form a federal buyer
may send is signed by the software's producer: this page is what such a signature would rest on.

**Version.** SSDF 1.1 (February 2022) is the final version. Version 1.2 (SP 800-218 Rev. 1) was
published as a draft in December 2025 and is listed as a draft by NIST when this was written. It
adds two practices, noted at the end; check
[NIST's page](https://csrc.nist.gov/pubs/sp/800/218/r1/ipd) for its status before quoting it.

**Scope.** Kilnmark's source repository, its builds and its releases. How a customer runs Kilnmark
(their hosts, network, identity provider, backups) is theirs, and is covered by the
[install guide](../../INSTALL.md) and the [compliance](compliance.md) page.

**How Kilnmark is made.** One maintainer, the owner, decides what is built, approves and merges
every change and tags every release. Code is written with AI coding assistants working under the
project's written rules ([CLAUDE.md](../../CLAUDE.md)): one branch and pull request per change,
merged only with CI green, and one independent review of every substantial change by a reviewer
given the change, not the author's conclusions. Security changes are reviewed again after each fix
until a review finds no way round them.

## Summary

Of the 42 tasks, 26 are met, 14 partly met and 2 not met (PO.2.2 and RV.3.2). What is missing, in the order worth doing:

**Repository settings, the owner's to change:**

1. **Protect `main`** (PS.1.1, PO.4.1): require a pull request and the four checks (build,
   heavy-databases, scan, release) before merging, and refuse force-pushes and deletion. Today the
   rule is written down and kept, but GitHub does not enforce it.
2. **Publish the release key** (PS.2.1): `config/release/cosign.pub` and its two secrets
   ([#80](https://github.com/jporeilly/KilnMark/pull/80)). Until then releases cannot be signed.
3. **Confirm Dependabot alerts and security updates are on** (RV.1.1): `dependabot.yml` relies on
   them for security fixes between the monthly updates.
4. **Signed commits** (PS.1.1), optional: merges made on GitHub are signed by it; commits made
   elsewhere are not.

**Build and process work, for the backlog:**

5. **Static analysis in CI** (PW.7.2, RV.1.2): a checker for Java (Error Prone or SpotBugs with its
   security rules) and lint rules for the web UI.
6. **Dependency verification for Gradle** (PW.4.4): checksums of every library, as the web UI's
   lock file already has.
7. **Build provenance** (PS.3.2): a signed statement of how each release was built (SLSA
   provenance), beside the SBOMs.
8. **A threat model** (PW.1.1), drawn from the decision records that already reason about attacks.
9. **A penetration test** (PW.8.2) by an outside firm, before the first bank runs Kilnmark.
10. **Root-cause analysis written down** (RV.3), for every vulnerability fixed.
11. **Training records** (PO.2.2), and a note on how the maintainer's workstation is secured
    (PO.5.2).

Done with this assessment: the build tool's download is checked against its published checksum,
and every action in CI is pinned to an exact commit (PO.3.2).

## Prepare the organisation (PO)

| Task | What Kilnmark does | Evidence | Status |
| --- | --- | --- | --- |
| PO.1.1 Security requirements for development | Rules for how work is done and what never bends: branches, green CI, reviews, no default secrets, no GPL code, the lab never shipped | [CLAUDE.md](../../CLAUDE.md), [CONTRIBUTING.md](../../CONTRIBUTING.md) | Met |
| PO.1.2 Security requirements for the software | The controls banks and regulators expect, each mapped to what Kilnmark does and what is missing | [Compliance](compliance.md), decision records 0027 to 0039 | Met |
| PO.1.3 Requirements for third-party software | Licences checked on every build against an allowed list, no GPL, versions kept current; borrowed code only under its licence, with its provenance | ADR 0007, `config/allowed-licenses.json`, `checkLicense`, [provenance](../legal/provenance.md), `.github/dependabot.yml` | Met |
| PO.2.1 Roles and responsibilities | The owner approves, merges and releases; the reviewer of each change is independent of its author | [CLAUDE.md](../../CLAUDE.md), "How work is done" | Met |
| PO.2.2 Role-based training | None recorded | | Not met |
| PO.2.3 Management commitment | The owner set the bank-readiness brief (1 November 2026) and the order of work | [Compliance](compliance.md#order-of-work), [backlog](backlog.md) | Met |
| PO.3.1 Specify the toolchain | Gradle wrapper, JDK 25 (Temurin), Node 22, Podman or Docker; versions in the repository | `gradle/wrapper`, `gradle/libs.versions.toml`, `.github/workflows` | Met |
| PO.3.2 Deploy and keep the toolchain secure | CI runs on fresh GitHub-hosted runners with read-only tokens; actions pinned to commits; Trivy, cosign and the Gradle distribution checked against checksums | `.github/workflows/*.yml`, `gradle/wrapper/gradle-wrapper.properties` | Met |
| PO.3.3 Tools produce evidence | CI logs and test reports per change; SBOMs and scan results per change and weekly; signatures and checksums per release | `.github/workflows/supply-chain.yml`, `release.yml` | Met |
| PO.4.1 Criteria for security checks | A change merges only with build, heavy-databases, scan and release green; the scan fails on high or critical vulnerabilities with a fix | [CLAUDE.md](../../CLAUDE.md), `supply-chain.yml` | Partly: kept by practice, not enforced by GitHub (item 1) |
| PO.4.2 Gather and keep the information | GitHub keeps every pull request, its checks and its review; the changelog and decision records keep the reasoning | Pull requests, [CHANGELOG](../../CHANGELOG.md), `docs/adr` | Met |
| PO.5.1 Separate and protect environments | Builds and releases run in CI, not on a workstation; the reference lab is test data only and a release check refuses anything from it | ADR 0024, `checkProductOnly` | Met |
| PO.5.2 Secure development endpoints | Not documented | | Partly (item 11) |

## Protect the software (PS)

| Task | What Kilnmark does | Evidence | Status |
| --- | --- | --- | --- |
| PS.1.1 Protect all forms of code | Private repository; changes only through pull requests by practice; secrets kept out of the repository and checked by GitGuardian on each pull request; no default secrets in what ships | `checkProductOnly` (secret rule), GitGuardian check | Partly: `main` unprotected, commits unsigned (items 1, 4) |
| PS.2.1 Integrity verification for users | Every release file signed with cosign and listed in a signed `SHA256SUMS`; the install guide says how to check | `release.yml`, [INSTALL.md](../../INSTALL.md), "Check a release" | Partly: waits for the release key (item 2) |
| PS.3.1 Archive each release | Each release is a tagged draft GitHub release with its files, signatures and SBOMs, kept by the owner | `release.yml` | Met |
| PS.3.2 Provenance of each release | CycloneDX SBOMs of the server's libraries and the web UI's packages in every release; borrowed code listed with its source | `sbom/` in each release, [provenance](../legal/provenance.md) | Partly: no build provenance (item 7) |

## Produce well-secured software (PW)

| Task | What Kilnmark does | Evidence | Status |
| --- | --- | --- | --- |
| PW.1.1 Risk modelling | Decision records reason about attackers for each security decision (who could reach what, and how it is refused) | ADRs 0027, 0028, 0030, 0034, 0036 to 0038 | Partly: no threat model of the whole (item 8) |
| PW.1.2 Track design decisions | Every decision hard to reverse has a record, with its reasons and consequences | `docs/adr` | Met |
| PW.1.3 Standard security features | Sign-in through the customer's provider (OpenID Connect, Quarkus), passwords sealed with AES-256-GCM from the Java runtime, TLS from the platform; no cryptography of Kilnmark's own | ADRs 0014, 0031, 0032, 0036; `SecretBox` | Met |
| PW.2.1 Review the design | The owner reviews decisions; an independent review of each substantial change includes its design | Pull request reviews | Met |
| PW.4.1 Acquire well-secured components | Libraries from Maven Central and npm, current versions, licence-checked; drivers customers add are pinned by checksum | ADR 0007, ADR 0025, `.github/dependabot.yml` | Met |
| PW.4.2 Build components only when needed | Kilnmark builds what no vetted library does; security functions come from libraries and the platform | PW.1.3 | Met |
| PW.4.4 Verify components | Every change and every week, the SBOMs are scanned for known vulnerabilities; the web UI's lock file holds each package's checksum | `supply-chain.yml`, `ui/package-lock.json` | Partly: no checksums for Gradle's libraries (item 6) |
| PW.5.1 Secure coding practices | Written rules: values seen only through `Sights`, SQL a person writes checked and run read-only, generic drivers only with vetted settings, errors that never echo a value or secret, an append-only audit log | [CLAUDE.md](../../CLAUDE.md), "Rules that never bend"; `SightsUseTest` | Met |
| PW.6.1 Compiler and build features | Java compiled with every lint warning as an error; TypeScript in strict mode | `build.gradle.kts` (`-Xlint:all -Werror`), `ui/tsconfig.json` | Met |
| PW.6.2 Decide the settings | As above, set once for every module | `build.gradle.kts` | Met |
| PW.7.1 Decide on review and analysis | Every substantial change gets an independent review; security changes until no bypass is found | [CLAUDE.md](../../CLAUDE.md) | Met |
| PW.7.2 Review and analyse the code | Reviews are done and their findings fixed in the same pull request, recorded in its description | Pull requests | Partly: no static analysis tool (item 5) |
| PW.8.1 Decide on testing | Every change ships with its tests; integration tests run against real databases | [CLAUDE.md](../../CLAUDE.md), "How work is done" | Met |
| PW.8.2 Test the code | Unit and integration tests on every change (about 180 test classes), databases in containers, security behaviour tested (refusals, masking, tokens, sign-in, the audit log); restore drills for backup and restore | `.github/workflows/ci.yml`, ADR 0039 | Partly: no penetration test or fuzzing (item 9) |
| PW.9.1 Secure baseline | The defaults are the safe ones, listed in the decision records and install guide | [INSTALL.md](../../INSTALL.md) | Met |
| PW.9.2 Secure defaults | Sign-in required (off only on a loopback address); no default password or token, and the release check refuses one; TLS to Kilnmark's own database or it does not start; security headers on every response; sensitive values masked except to designers holding the grant; sources read-only to pipelines, and no SQL a person writes, until an admin allows it | ADRs 0024, 0027, 0028, 0031, 0032; `application.properties` | Met |

## Respond to vulnerabilities (RV)

| Task | What Kilnmark does | Evidence | Status |
| --- | --- | --- | --- |
| RV.1.1 Gather information on vulnerabilities | Trivy scans of the SBOMs on every change and weekly, a report on the base images; Dependabot version updates monthly | `supply-chain.yml`, `.github/dependabot.yml` | Partly: confirm security alerts are on (item 3) |
| RV.1.2 Review the code for vulnerabilities | Independent reviews of each change look for them | Pull requests | Partly: no static analysis (item 5) |
| RV.1.3 Vulnerability disclosure policy | A private reporting address, timelines to acknowledge and decide, a disclosure date agreed with the reporter | [SECURITY.md](../../SECURITY.md) | Met |
| RV.2.1 Analyse each vulnerability | Reports are assessed for what an attacker can do and needs, as `SECURITY.md` asks reporters to say | [SECURITY.md](../../SECURITY.md) | Met |
| RV.2.2 Plan and make the fix | Fixes in order of severity, shipped in the next release with a changelog entry saying what was fixed | [SECURITY.md](../../SECURITY.md), [CHANGELOG](../../CHANGELOG.md) | Met |
| RV.3.1 Find the root cause | Done for review findings in the pull request that fixes them; not written down for each vulnerability | | Partly (item 10) |
| RV.3.2 Analyse root causes over time | Not done | | Not met |
| RV.3.3 Look for the same flaw elsewhere | Reviews ask where else a flaw applies (for example, every place a stored password is opened, ADR 0039) | Pull requests | Partly (item 10) |
| RV.3.4 Improve the process | Lessons become project rules (for example the writing and ports rules in CLAUDE.md) | [CLAUDE.md](../../CLAUDE.md) | Partly (item 10) |

## In the draft of version 1.2

Two practices the draft adds, to plan for once it is final:

- **PO.6, continuous improvement** of the development process: measured, reviewed and improved on a
  schedule. Today improvements are made as lessons arise (RV.3.4).
- **PS.4, reliable software updates**: updates customers can trust and apply safely. Releases are
  signed (PS.2.1); restoring the previous version is in the
  [backup runbook](../guide/backup-and-restore.md); an upgrade migrates the database at start.

## Keeping this current

Assess again with each release, and whenever a gap above is closed: change its status, and its
item in the summary. The [compliance](compliance.md) page links here.
