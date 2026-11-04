# Compliance: running Kilnmark in banks

Kilnmark has to be deployable in banks and other regulated financial firms, in the EU and in US
federal and state settings, and help them meet the rules they answer to. This page maps those rules
to what Kilnmark does, what is missing, and the order the gaps are closed. It is a product plan,
not legal advice: each customer's own compliance team decides what applies to them.

Last reviewed: 1 November 2026.

## How the rules reach Kilnmark

Kilnmark is software the customer runs on its own servers or cloud account (ADR 0001, ADR 0002).
So the obligations below fall on the customer, and Kilnmark's job is to give them the **controls**
those obligations need and the **evidence** their examiners and vendor-risk teams ask for.

If Kilnmark is ever offered as a hosted service, its operator also becomes an ICT third-party
service provider under DORA, and a "service provider" in US third-party risk terms. That brings
contract terms (DORA Article 30), audit and access rights for the customer and its supervisor,
exit plans, incident help, and in the US usually a SOC 2 Type II report. None of that is planned;
it is listed under [If Kilnmark is hosted](#if-kilnmark-is-hosted) so the cost is known.

## The rules in scope

| Rule | Who it binds | What it asks of software like Kilnmark |
| --- | --- | --- |
| **DORA** (EU 2022/2554) and its RTS on ICT risk management (2024/1774) | EU financial entities | Logging of access, changes and operations, protected from change and deletion, with failures of logging detected and clocks synchronised (RTS Art. 12); access control and strong authentication; encryption; backup and restore; ICT change management; resilience testing |
| **NYDFS 23 NYCRR Part 500** | Banks, insurers and others licensed in New York | MFA for every user of every system since 1 Nov 2025 (500.12); audit trails to detect and respond to cybersecurity events, kept at least five years (500.6); limited, reviewed access privileges (500.7); encryption of nonpublic information (500.15); an asset inventory (500.13) |
| **US interagency guidance on third-party risk** (Fed, FDIC, OCC, June 2023) and the **FFIEC** IT handbooks | US banks | Due diligence on vendors: security practices, vulnerability management, resilience, the right to audit; ongoing monitoring |
| **GLBA Safeguards Rule** and the interagency safeguards guidelines | US financial institutions | Access controls, encryption, MFA, logging and monitoring of activity on customer information |
| **NIST SP 800-53 Rev. 5**, and **FedRAMP** for cloud services | US federal agencies; often used as the control catalogue by banks too | Audit events and records (AU-2, AU-3, AU-6, AU-9, AU-11), identification and authentication (IA-2 with MFA), least privilege (AC-6), configuration management, FIPS 140-3 validated cryptography (SC-13) |
| **OMB M-26-05** (replacing M-22-18 and M-23-16) | US federal agencies buying software | Agencies decide case by case whether to ask for a secure-development attestation (NIST SSDF) or an SBOM, so a vendor should be able to provide both |
| **SOX** IT general controls | US listed companies | Change management and access control for systems feeding financial reporting: who changed a pipeline, when, and who approved it |
| **PCI DSS v4** | Anyone storing card data | Logging (Req. 10), masking of card numbers when shown (Req. 3.4.1), strong authentication |
| **GDPR**, and US state privacy laws (CCPA/CPRA and others) | Personal data | Purpose limitation, data minimisation, records of processing, the ability to find and erase a person's data |

## Controls: what Kilnmark has and what is missing

| Control | Rules | Kilnmark today | Gap | Priority |
| --- | --- | --- | --- | --- |
| **Audit trail** of changes and refused access, append-only, sent to a SIEM | DORA RTS 12; NYDFS 500.6; 800-53 AU-2/3/9; PCI 10; SOX | The audit log (ADR 0029, PR #58): every change through the API and every refusal, events for tokens, marks, sources, Allow writing and Allow SQL; append-only; JSON lines to syslog | Reading data is not yet an event (designers' unmasked previews); no retention rule (NYDFS needs at least five years: keep everything until one is set); no alert when the database copy cannot be written beyond the error line; sign-in events from the identity provider are recorded (ADR 0036) | 1 |
| **Sign-in through the customer's identity provider, with MFA** | NYDFS 500.12; GLBA; 800-53 IA-2; DORA | OIDC sign-in with the customer's provider (ADR 0036): roles and the unmask grant from group claims, MFA required from `amr`/`acr`, sessions of at most 8 hours with `max_age`, sign-ins audited; API tokens for scripts | Token expiry required by policy; a server-side session store so signing out ends every copy of a session; the optional Keycloak profile (ADR 0014) | 2 |
| **Least privilege and access reviews** | NYDFS 500.7; 800-53 AC-5, AC-6; SOX | Four roles; seeing sensitive values is a separate grant held only by designers (ADR 0027); admins see masked values; a longest token lifetime the admin can require, and the access review (Settings, Access review; CSV for auditors; each view and download audited) (ADR 0037); two people signed in with the identity provider for the grant on designer tokens, when required, told apart by the provider's fixed id; for people's own sign-ins, whoever approves the unmask group at the provider (ADR 0038) | One person with two accounts at the provider could still give it: the provider's control | 3 |
| **Masking of sensitive values** | GLBA; PCI 3.4.1; GDPR | Marks, masks everywhere values are shown, marks carried through pipelines, AI never sees unmasked values | Masking on the run quality view (backlog); shapes such as card numbers shown as last four, not fully masked | 4 |
| **Encryption in transit** | NYDFS 500.15; DORA; 800-53 SC-8 | HTTPS on Kilnmark itself, HSTS and security headers (ADR 0031); TLS required to Kilnmark's own database (ADR 0032); `kilnmark.sources.require-tls` refuses sources whose connections could go unencrypted (ADR 0034) | A setting that also requires the server's certificate to be checked (verify-full, TCPS); Generic JDBC sources with TLS (connectors of their own) | 2 |
| **Encryption at rest** | NYDFS 500.15; DORA; PCI | Source passwords sealed with AES-256-GCM; the rest relies on the customer's disk encryption | Document the disk-encryption expectation; key rotation for the secret key | 5 |
| **FIPS 140-3 validated cryptography** | US federal (800-53 SC-13, FedRAMP) | Java's own providers | A FIPS mode: a validated provider (such as Bouncy Castle FIPS) and approved algorithms only; a FIPS build of the image | 6 (federal only) |
| **Backup, restore and exit** | DORA Art. 11 and 12 (backup and recovery), Art. 28(8) (exit strategies); FFIEC | Pipelines are YAML; everything is in one PostgreSQL database and a keys volume. `backup.sh` and `restore.sh` (ADR 0039), the keys kept apart from the dumps, the restore recorded in the audit log; a runbook with a quarterly restore test, proven from a destroyed machine; recovery point is the time since the last backup (daily to start), recovery time minutes for a small database | A full export in open formats, for exit; the customer's own recovery targets, measured in their restore tests | 3 |
| **Change management** | SOX; DORA; 800-53 CM | Every pipeline save is a version with its author and message; runs record the version they ran | Approval before a version can run in production (four-eyes), environments (dev, test, prod) and promotion between them | 4 |
| **Time synchronisation** | DORA RTS 12 | Times are UTC from the host clock | Document NTP on the host; record the clock source in a health check | 6 |
| **Resilience** | DORA; FFIEC | One image; jobs recover after a crash | Running two servers for failover; documented limits; load test results | 5 |
| **Data protection: find and erase** | GDPR; state privacy laws | Search across sources; marks | A "where is this person's data" report from marks and search; erasure stays in the source systems | 6 |
| **Third-party database drivers** (Generic JDBC) | 800-53 CM-7 (least functionality), SC-39 (process isolation); vendor due diligence | Network database servers only; only vetted driver settings (ADR 0030, PR #60); drivers that move files for SQL refused | Drivers run in the server's own process | 2 (vetted settings, done); isolation 4 |
| **Secure development and supply chain** | OMB M-26-05 (SSDF on request); bank due diligence | CI on every change; no GPL drivers; dependency policy (ADR 0007); CycloneDX SBOMs with each release, Trivy scanning on every change and weekly, monthly Dependabot updates, signed release files with a draft release for the owner, `SECURITY.md` (ADR 0033); the build tool and CI actions pinned by checksum; an [SSDF self-assessment](ssdf-self-assessment.md) (26 of 42 tasks met, 14 partly) | Its gaps, in its order: `main` protected and the release key (owner); static analysis, Gradle dependency verification, build provenance, a threat model, a penetration test; signed images once images are published | 2 |
| **Evidence pack** for vendor-risk teams | Interagency guidance; FFIEC; DORA register of information | Docs and ADRs in the repository; the [SSDF self-assessment](ssdf-self-assessment.md) | A security whitepaper, the controls above as a mapped table (this page), a hardening guide, and penetration-test results | 3 |

## Order of work

This replaces "Hardening" in the [backlog](backlog.md):

1. **The audit log** (PR #58). Then audit events for data read with the unmask grant.
2. **HTTPS and security headers; supply chain**: SBOM, signed releases, scanning, `SECURITY.md`.
3. **OIDC sign-in with MFA from the customer's provider** (ADR 0014), brought forward from v0.2:
   it is the largest gap against NYDFS 500.12, and every bank will ask.
4. **Access reviews and token policy; backup and restore with a runbook; the evidence pack.**
5. **Generic JDBC drivers in a process of their own** (ADR 0030), with only the network hosts a
   source names; until then only vetted settings (PR #60).
6. **Change approval and environments**; masking shapes; the remaining hardening items (dev
   compose ports on 127.0.0.1 with `KILNMARK_DB_PASSWORD`, JDBC `socketTimeout`, the run quality
   view, a metric for preview slots).
7. **FIPS mode**, when a federal customer needs it.

## If Kilnmark is hosted

Only if Kilnmark is ever run as a service for customers:

- DORA Article 30 contract terms: where data is processed, access to and return of data on exit,
  incident help at a known cost, cooperation with supervisors, service levels, and for critical
  functions unrestricted audit and access rights, participation in threat-led penetration tests,
  and an exit transition period.
- An entry in each EU customer's register of information.
- In the US, a SOC 2 Type II report, and FedRAMP authorisation for federal agencies.

## Sources

- [DORA Article 30, contract provisions](https://www.springlex.eu/en/packages/dora/dora-regulation/article-30/)
- [DORA RTS on ICT risk management, Article 12, logging](https://www.springlex.eu/en/packages/dora/rts-rmf-regulation/article-12/)
- [23 NYCRR 500.6, audit trail](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.6)
- [NYDFS Part 500 requirements effective 1 November 2025](https://hlc.com/en/publications/nydfs-final-set-of-cybersecurity-requirements-under-amended-part-500-take-effect-november-1-2025)
- [FedRAMP 20x key security indicators: monitoring, logging and auditing](https://fedramp.gov/2026/providers/20x/key-security-indicators/monitoring-logging-and-auditing/)
- [FedRAMP audit and accountability controls](https://fedramp.gov/2026/reference/controls/audit-and-accountability/)
- [OMB M-26-05 and the end of mandatory software attestations](https://www.dwt.com/blogs/privacy--security-law-blog/2026/02/omb-changes-course-on-software-security)
- [Interagency guidance on third-party relationships](https://www.smartsuite.com/frameworks/us-interagency-guidance-on-third-party-relationships-risk-management-2023)
- [Bouncy Castle FIPS 140-3 certification](https://bouncycastle.org/resources/bouncy-castle-crypto-api-attains-fips-140-3-certification)
