# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 5 November 2026 (after #86-#93 merged).

## Now

1. **Flow controls** ([ADR 0041](../adr/0041-flow-nodes-for-resources-and-control.md),
   docs/design/flow-controls.md; owner, 5 November 2026): a node's `timeout` (built, C3) and
   `priority` (built, C5), then the `check-source` and `wait-for` nodes, then a node's `retries`, in
   that order. When it is finished, take `FlowDefinition`, its test, `FlowService` and `JobQueue` out
   of Error Prone's `excludedPaths` in `build.gradle.kts` and fix what it finds there (ADR 0042,
   decision 5).

## Next

2. **Hardening for regulated customers** (brief of 28 October; owner, 1 November: Kilnmark must be
   deployable in banks, EU and US federal and state, and meet DORA, NYDFS Part 500 and the rest).
   The order is in [Compliance](compliance.md#order-of-work). Shipped: the audit log (#58), HTTPS
   and security headers (#67), TLS to Kilnmark's own database (#68), SBOMs, scanning and signed
   releases (#69), encrypted connections to sources (ADR 0034), OIDC sign-in with MFA (ADR 0036,
   #78), access reviews and token policy (ADR 0037, #81), two people for the unmask grant
   (ADR 0038, #82), backup and restore with a runbook (ADR 0039, #83), the SSDF
   self-assessment (#84), the bundled Keycloak for customers without an identity provider
   (ADR 0014, #93), and static analysis with dependency verification (ADR 0042). Next:
   - the SSDF gaps: build provenance, a threat model, base images pinned by digest, a security
     analyser; the rest of the evidence pack when a customer's due diligence asks; a full export in
     open formats, for exit;
   - Generic JDBC drivers in a process of their own (ADR 0030, decision 5); new table names
     checked against the driver's longest table name, not its longest column name (ADR 0042);
   - change approval and environments;
   - `KILNMARK_DB_PASSWORD` in the dev compose, a JDBC `socketTimeout`, masking on the run quality
     view, a metric for preview slots;
   - audit events for data read with the unmask grant, and for MCP refusals (ADR 0029);
   - a FIPS mode when a federal customer needs it.
3. **Database tools** (owner, 5 November 2026): on a source, **Explore** (live schemas, tables,
   columns, keys and indexes, a masked preview of rows); a SQL generator (`CREATE TABLE`, `ALTER`,
   `CREATE INDEX` and `DROP` in each database's dialect); connection settings as tabs (General,
   Advanced, Options, Pooling, Partitioning); and operations that change a database, which the
   owner chose to allow with controls: a per-source **Allow changes** switch (off by default),
   designers and admins only, a preview of every statement, typing the table's name to confirm a
   drop or truncate, an audit event for each, and a second person's approval on production
   sources. Needs an ADR amending ADR 0028 first.
4. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope).
5. **Flows F4** ([ADR 0026](../adr/0026-flows.md), docs/design/flows.md): the diagram, run chart and
   editing on the diagram (#85, #86), resource control and the `for-each` node (ADR 0040, #89,
   #90), pages laid out as a pipeline's (#90-#92), and the sample flows in the Library (ADR 0043)
   shipped; then deleting a flow; the built-in scheduler.
6. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; Snowflake, Vertica and
   Exasol as connectors of their own, with the SQL that moves files refused (ADR 0030); then
   templates of a team's own ("save as template", after the Library's, ADR 0043), version history,
   and v0.1.0.

## Owner's items

- The release key (docs/development.md, "Releasing"), before the first signed release.
- The demo deck and the partnership status slide, last made for 0.0.8.
