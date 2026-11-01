# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 1 November 2026.

## Now

1. **SQL nodes** ([ADR 0028](../adr/0028-sql-in-reads-and-flow-actions.md)). Slice 1, the
   `allow_sql` gate, is #53. Slice 2: a `read` with a `query` (PostgreSQL describes its columns;
   `EXPLAIN` names the tables it reads for lineage; marks of every named table apply). Slice 3: the
   flow `sql` action. Each slice ships with its samples, templates and docs.
2. **Canvas panel** under the canvas, with tabs: Settings and Preview only while a node is
   selected (a click opens Settings), a Run tab that follows Run now live, then Suggestions, Nodes,
   Runs and Versions. Branch `canvas-bottom-panels`. The flow canvas (F4) uses the same panel.
3. **Follow-ups from the reviews of ADR 0027 and 0028:**
   - tests for an import whose rows overlap each other, and for AI suggestions masking a marked
     column for a designer with the unmask grant;
   - mask when a prediction catalogue has no `Sights` at all, and refuse AI suggestions then;
   - recognise S3 access-point aliases and ARNs as their bucket in the overlap guard;
   - stop an admin-only generic JDBC source from reading server files (H2, SQLite, DuckDB URLs);
   - offer custom SQL in the rule form only where the source has Allow SQL on;
   - the writing-data guide still says only admins turn on Allow writing (designers do too).
4. **README screenshots**, once the canvas panel is merged.

## Next

5. **Hardening** (brief of 28 October): the audit log first (tokens with the unmask grant, marks
   cleared, sources deleted, Allow SQL and Allow writing changed; ADR 0027 relies on it), then
   HTTPS, security headers, dev compose ports on 127.0.0.1 with `KILNMARK_DB_PASSWORD`, a JDBC
   `socketTimeout`, masking on the run quality view, and a metric for preview slots.
6. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope).
7. **Flows F3 and F4** ([ADR 0026](../adr/0026-flows.md), docs/design/flows.md): the flows API,
   then the flow canvas; resource control; the built-in scheduler.
8. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; then templates, version
   history, and v0.1.0.

## Owner's items

- The demo deck and the partnership status slide, last made for 0.0.8.
- Revoke the GitHub tokens pasted into the chat of 29 October.
