# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 5 November 2026, evening (owner: "let's go" on this order, after #97-#103 merged:
timeout, priority, the Library, static analysis, flow node cards, the one-script install and the
run timelines).

## 1. Finish what is half built

1. **Flow controls, the rest** ([ADR 0041](../adr/0041-flow-nodes-for-resources-and-control.md),
   docs/design/flow-controls.md; owner, 5 November 2026): `timeout` (C3), `priority` (C5), the
   `check-source` node (C1), the `wait-for` node (C2) and a node's `retries` (C4) are built, and
   `FlowDefinition`, its test, `FlowService` and `JobQueue` came out of Error Prone's
   `excludedPaths` with what it found fixed (ADR 0042, decision 5). Done.
2. **Dependency updates**: Dependabot's Gradle group (#76) less the MariaDB driver 3.x, which sets
   `session_track_system_variables` to NULL on connect and fails against the MariaDB the tests
   use; hold it until that is understood. Each update also updates
   `gradle/verification-metadata.xml` (ADR 0042).
3. **The scheduler's pages, and deleting a flow** (Flows F4, [ADR 0026](../adr/0026-flows.md),
   [ADR 0046](../adr/0046-scheduler.md)): the scheduler's server, API and pages are built, for
   pipelines and flows (docs/design/schedules.md), and deleting a flow is built
   ([ADR 0048](../adr/0048-deleting-a-flow.md)). External schedulers are supported too (built,
   [ADR 0047](../adr/0047-external-schedulers.md)): Control-M, AutoSys, cron and Windows Task
   Scheduler start pipelines and flows with `kilnmark-run`, wait for them, and get an exit code; a
   retry with the same request key never runs the work twice. Done.

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
   sources. Decided in [ADR 0045](../adr/0045-database-tools.md) (amends ADR 0028); the design and
   its order (Explore, the SQL generator, settings on tabs, operations) are in
   [docs/design/database-tools.md](../design/database-tools.md).
9. **More nodes** (owner, 6 November 2026; [ADR 0049](../adr/0049-more-nodes-and-how-coarse-they-are.md)).
   `sort` and `join` (two inputs of any size, spilling to disk) are done (#137, #140); next
   `window` and more expression functions; then web calls (a REST connector, an `http`
   node and an `http` flow node, behind the allowed hosts an admin sets), JSON and XML, `pivot`
   and `unpivot`, change detection and dimension history, `sequence`, `mask`, generated rows, and
   file actions in flows. Nodes stay coarse: one per shape of work, single-value operations as
   functions. With them, **AI in pipelines** ([ADR 0050](../adr/0050-ai-in-pipelines.md)): an `ai`
   node (classify, extract, summarise, translate, find personal data) and an `embed` node, after
   `mask` so sensitive columns can be masked first; then explaining failed runs, drafting a
   pipeline from a description, suggesting quality rules, and run tools on the MCP server, as
   agents that propose and people approve ([ADR 0052](../adr/0052-agents.md)): failed runs first,
   then drafting a pipeline, stewardship overnight, outside agents through MCP, and agents people
   build.
10. **Optimise** (brief of 28 October): a run-page button to optimise, canvas suggestions,
   pre-run checks, flags on slow pipelines. Run tools on the MCP server fold in here (parked item
   in Plan & Scope). **Benchmarks come first** (owner, 6 November 2026; planned in
   [docs/design/benchmarks.md](../design/benchmarks.md)): a data generator and `lab bench`, the
   copy, file-load and join workloads at 1 and 10 million rows on the owner's server, a nightly
   check that fails on a 15% slowdown, then the rest of the workloads, two benchmark machines and
   a comparison with the tool a customer runs today. What they find decides what to optimise.
11. **Connectors** (brief of 28 October): SFTP/FTP, SMB, Azure Blob, REST; Snowflake, Vertica and
   Exasol as connectors of their own, with the SQL that moves files refused (ADR 0030).
12. **Templates of a team's own** ("save as template", after the Library's, ADR 0043), and
    comparing a pipeline's or flow's versions; then the Foundation milestone.
13. **Business calendars for schedules (holidays, working days, last working day of the month)**,
    for the built-in scheduler (ADR 0046). External schedulers bring their own calendars (ADR 0047).

## 4. Hardening, as customers ask

The order is in [Compliance](compliance.md#order-of-work) (brief of 28 October; owner, 1 November:
Kilnmark must be deployable in banks, EU and US federal and state, and meet DORA, NYDFS Part 500
and the rest). Shipped so far: the audit log (#58), HTTPS and security headers (#67), TLS to
Kilnmark's own database (#68), SBOMs, scanning and signed releases (#69), encrypted connections to
sources (ADR 0034), OIDC sign-in with MFA (ADR 0036, #78), access reviews and token policy
(ADR 0037, #81), two people for the unmask grant (ADR 0038, #82), backup and restore (ADR 0039,
#83), the SSDF self-assessment (#84), the bundled Keycloak (ADR 0014, #93), static analysis with
dependency verification (ADR 0042, #99) and signed images with an offline install (ADR 0044, #102).

14. **The SSDF gaps**: build provenance, a threat model, a security analyser; the rest of the
    evidence pack when a customer's due diligence asks.
15. **Change approval and environments**, which also gives database tools their second person.
    With it, marks keyed by the database's identity rather than the source record, and password references
    (`env:`, `secret:`) for admins only (ADR 0045, "Related gap").
16. **Generic JDBC drivers in a process of their own** (ADR 0030, decision 5); new table names
    checked against the driver's longest table name, not its longest column name (ADR 0042).
17. **Small items**: a JDBC `socketTimeout`, masking on the run quality view, a metric for preview
    slots, `KILNMARK_DB_PASSWORD` in the dev compose. **Job retention**: the job table keeps every
    job ever queued (done and failed too), so it only grows; deleting old finished jobs, and how
    long to keep them, needs the owner's decision first, as it deletes records (efficiency review,
    6 November 2026; V38 indexed its lookups by run meanwhile). **Indexes on the other foreign
    keys** that V38 left out (it took the five the review named): `profile.run_id` (ON DELETE
    CASCADE, not led by any index, so deleting a source still reads `profile` once per scan run),
    and the ON DELETE SET NULL keys `ai_usage.profile_id` and `source_id`,
    `accepted_pattern.rule_id`, `drift_event.compared_with` and `quality_issue.drift_event_id`.
18. **Audit events** for data read with the unmask grant, and for MCP refusals (ADR 0029).
19. **A full export in open formats** (for exit), and a FIPS mode when a federal customer needs it.

## 5. Housekeeping

20. **The local CI runner on a Podman machine of its own**, without the Windows drives mounted,
    starting afresh for each job (ephemeral), and its PostgreSQL image under a CI-only tag
    (docs/development.md, "CI on a local runner"; review of #111).
21. **The server tests on Windows**: Testcontainers cannot reach Podman from the tool shell, so
    server tests run only in CI there.
22. **Refactoring, in small steps** (owner, 6 November 2026): each its own pull request that changes
    no behaviour, with every test green before and after. In order:
    1. **One run lifecycle for pipelines and flows**: checking, queueing, request keys, schedules
       and deleting are written twice today, in `PipelineService` (about 2,000 lines) and
       `FlowService` (about 2,400), and every change to one had to be made to the other
       (#124, #128 and the scheduler). First, before the `sort` and `join` nodes (item 9), which
       touch the same code. It also takes the efficiency review's findings in that code (6
       November 2026), not fixed on their own: the flow watcher (each watched flow holds a database
       connection and asks after its runs on a timer), runs asking the database whether they are
       cancelled while they work, and the lock a schedule's fire holds while it queues its run.
    2. **`ui/src/api.ts` split by area** (about 2,100 lines of every API type and call), so UI
       changes stop meeting in one file.
    3. **One form-field building block and form styles for the whole UI**: the node panel's
       layout faults (#133) came from fields laid out separately and from app-wide fieldset rules
       leaking into forms; the panel layout sweep starts this.
    4. **`FlowService` and `QualityPredictor` (about 2,600 lines) split** into focused parts.

## Owner's items

- The demo deck and the partnership status slide, last made for 0.0.8: refresh for the Foundation milestone.
