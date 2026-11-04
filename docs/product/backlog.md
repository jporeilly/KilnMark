# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 3 November 2026 (after #61-#69 merged).

## Now

1. **README screenshots** of the canvas panel (#55).

## Next

2. **Hardening for regulated customers** (brief of 28 October; owner, 1 November: Kilnmark must be
   deployable in banks, EU and US federal and state, and meet DORA, NYDFS Part 500 and the rest).
   The order is in [Compliance](compliance.md#order-of-work). Shipped: the audit log (#58), HTTPS
   and security headers (#67), TLS to Kilnmark's own database (#68), SBOMs, scanning and signed
   releases (#69); in review: encrypted connections to sources (ADR 0034). Next:
   - OIDC sign-in with MFA, brought forward from v0.2 ([ADR 0014](../adr/0014-authentication.md)):
     in review (ADR 0036); then the Keycloak profile for customers without a provider;
   - access reviews and token policy (ADR 0037, #81); separation of duties for the unmask grant
     (ADR 0038, #82); backup and restore with a runbook: in review (ADR 0039);
   - the SSDF self-assessment: in review; its gaps (static analysis, Gradle dependency
     verification, build provenance, a threat model) join this list; the rest of the evidence pack
     when a customer's due diligence asks; a full export in open formats, for exit;
   - Generic JDBC drivers in a process of their own (ADR 0030, decision 5);
   - change approval and environments;
   - `KILNMARK_DB_PASSWORD` in the dev compose, a JDBC `socketTimeout`, masking on the run quality
     view, a metric for preview slots;
   - audit events for data read with the unmask grant, and for MCP refusals (ADR 0029);
   - a FIPS mode when a federal customer needs it.
3. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope).
4. **Flows F4** ([ADR 0026](../adr/0026-flows.md), docs/design/flows.md): the flow canvas and the
   run chart; adding the sample flows from Add samples; deleting a flow; resource control; the
   built-in scheduler. F3 (the API and the pages) is in review.
5. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; Snowflake, Vertica and
   Exasol as connectors of their own, with the SQL that moves files refused (ADR 0030); then
   templates, version history, and v0.1.0.

## Owner's items

- The release key (docs/development.md, "Releasing"), before the first signed release.
- The demo deck and the partnership status slide, last made for 0.0.8.
