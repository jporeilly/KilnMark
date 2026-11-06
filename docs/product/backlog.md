# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 5 November 2026, evening (owner: "let's go" on this order, after #97-#103 merged:
timeout, priority, the Library, static analysis, flow node cards, the one-script install and the
run timelines).

## 1. Finish what is half built

1. **Flow controls, the rest** ([ADR 0041](../adr/0041-flow-nodes-for-resources-and-control.md),
   docs/design/flow-controls.md; owner, 5 November 2026): `timeout` (C3), `priority` (C5) and the
   `check-source` node (C1) are built; then the `wait-for` node, then a node's `retries`. When it is
   finished, take `FlowDefinition`, its test, `FlowService` and `JobQueue` out of Error Prone's
   `excludedPaths` in `build.gradle.kts` and fix what it finds there (ADR 0042, decision 5).
2. **Dependency updates**: Dependabot's Gradle group (#76) less the MariaDB driver 3.x, which sets
   `session_track_system_variables` to NULL on connect and fails against the MariaDB the tests
   use; hold it until that is understood. Each update also updates
   `gradle/verification-metadata.xml` (ADR 0042).
3. **The flow scheduler, and deleting a flow** (Flows F4, [ADR 0026](../adr/0026-flows.md),
   docs/design/flows.md): flows cannot yet run on a schedule, which nightly work needs.

## 2. Get a release out

4. **The first signed release** (owner's tag; docs/development.md, "Releasing"). After it, make the
   two image packages on GitHub's registry public (ADR 0044).
5. **The control script on Ubuntu with Podman 5**, and installing by pulling images from the
   registry: neither has been run yet (ADR 0044).
6. **A Windows install rehearsal in CI** (owner, 5 November 2026): a job that installs Kilnmark on
   Windows 11 with `kilnmark.ps1` and runs it through start, check, backup, restore, upgrade and
   uninstall, as the release rehearsal does on Linux (ADR 0044). It runs on GitHub's
   `windows-latest`, a clean Windows each time; if GitHub's runners stay unavailable, on a Windows
   self-hosted runner inside a VM (Hyper-V or Windows Sandbox), never on the owner's own desktop,
   where jobs would run on the host itself.
7. **The owner's install at `C:\Kilnmark`** moved onto the new layout (INSTALL.md, "From 0.0.9").

## 3. Product

8. **Database tools** (owner, 5 November 2026): on a source, **Explore** (live schemas, tables,
   columns, keys and indexes, a masked preview of rows); a SQL generator (`CREATE TABLE`, `ALTER`,
   `CREATE INDEX` and `DROP` in each database's dialect); connection settings as tabs (General,
   Advanced, Options, Pooling, Partitioning); and operations that change a database, which the
   owner chose to allow with controls: a per-source **Allow changes** switch (off by default),
   designers and admins only, a preview of every statement, typing the table's name to confirm a
   drop or truncate, an audit event for each, and a second person's approval on production
   sources. An ADR amending ADR 0028 comes first.
9. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope).
10. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; Snowflake, Vertica and
   Exasol as connectors of their own, with the SQL that moves files refused (ADR 0030).
11. **Templates of a team's own** ("save as template", after the Library's, ADR 0043), and
    comparing a pipeline's or flow's versions; then v0.1.0.

## 4. Hardening, as customers ask

The order is in [Compliance](compliance.md#order-of-work) (brief of 28 October; owner, 1 November:
Kilnmark must be deployable in banks, EU and US federal and state, and meet DORA, NYDFS Part 500
and the rest). Shipped so far: the audit log (#58), HTTPS and security headers (#67), TLS to
Kilnmark's own database (#68), SBOMs, scanning and signed releases (#69), encrypted connections to
sources (ADR 0034), OIDC sign-in with MFA (ADR 0036, #78), access reviews and token policy
(ADR 0037, #81), two people for the unmask grant (ADR 0038, #82), backup and restore (ADR 0039,
#83), the SSDF self-assessment (#84), the bundled Keycloak (ADR 0014, #93), static analysis with
dependency verification (ADR 0042, #99) and signed images with an offline install (ADR 0044, #102).

12. **The SSDF gaps**: build provenance, a threat model, a security analyser; the rest of the
    evidence pack when a customer's due diligence asks.
13. **Change approval and environments**, which also gives database tools their second person.
14. **Generic JDBC drivers in a process of their own** (ADR 0030, decision 5); new table names
    checked against the driver's longest table name, not its longest column name (ADR 0042).
15. **Small items**: a JDBC `socketTimeout`, masking on the run quality view, a metric for preview
    slots, `KILNMARK_DB_PASSWORD` in the dev compose.
16. **Audit events** for data read with the unmask grant, and for MCP refusals (ADR 0029).
17. **A full export in open formats** (for exit), and a FIPS mode when a federal customer needs it.

## 5. Housekeeping

18. **The local CI runner on a Podman machine of its own**, without the Windows drives mounted,
    starting afresh for each job (ephemeral), and its PostgreSQL image under a CI-only tag
    (docs/development.md, "CI on a local runner"; review of #111).
19. **The server tests on Windows**: Testcontainers cannot reach Podman from the tool shell, so
    server tests run only in CI there.

## Owner's items

- The demo deck and the partnership status slide, last made for 0.0.8: refresh for 0.1.0.
