# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 2 November 2026 (after #53-#60 merged).

## Now

1. **Generic JDBC tidy-ups** (review of #60, [ADR 0030](../adr/0030-generic-jdbc-vetted-settings.md)):
   - anchor the vetted `timeout` pattern to known names (`.*timeout(ms|_ms|seconds)?`);
   - vet SQL Server `instanceName`, so named instances without a port can be used;
   - the guide, the changelog and the registration error list every vetted kind (compression,
     `readonly`, `mode`, `create` are missing), and ADR 0030 says the MySQL and MariaDB settings
     are forced on their URLs, not on every connection.
2. **SQL nodes, slice 3** ([ADR 0028](../adr/0028-sql-in-reads-and-flow-actions.md)): the flow
   `sql` action, with its samples, templates and docs. Slices 1 (`allow_sql`) and 2 (a read's
   `query`) shipped in #53 and #56. Also from #56's reviews: a test that a version saved before
   `queries_checked` (V27) is refused when queued and when it starts, and moving the query check
   (`allowQueries`) into `PipelineService.create` and `save`, so no future caller can skip it.
3. **README screenshots** of the canvas panel (#55).

## Next

4. **Hardening for regulated customers** (brief of 28 October; owner, 1 November: Kilnmark must be
   deployable in banks, EU and US federal and state, and meet DORA, NYDFS Part 500 and the rest).
   The order is in [Compliance](compliance.md#order-of-work). The audit log shipped in #58; next:
   - HTTPS and security headers;
   - the supply chain: an SBOM with each release, signed releases, dependency and image scanning,
     `SECURITY.md`;
   - OIDC sign-in with MFA, brought forward from v0.2 ([ADR 0014](../adr/0014-authentication.md));
   - access reviews and token policy; backup and restore with a runbook; the evidence pack;
   - Generic JDBC drivers in a process of their own (ADR 0030, decision 5);
   - change approval and environments;
   - dev compose ports on 127.0.0.1 with `KILNMARK_DB_PASSWORD`, a JDBC `socketTimeout`, masking
     on the run quality view, a metric for preview slots;
   - audit events for data read with the unmask grant, and for MCP refusals (ADR 0029);
   - a FIPS mode when a federal customer needs it.
5. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope).
6. **Flows F3 and F4** ([ADR 0026](../adr/0026-flows.md), docs/design/flows.md): the flows API,
   then the flow canvas; resource control; the built-in scheduler.
7. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; Snowflake, Vertica and
   Exasol as connectors of their own, with the SQL that moves files refused (ADR 0030); then
   templates, version history, and v0.1.0.

## Owner's items

- The demo deck and the partnership status slide, last made for 0.0.8.
