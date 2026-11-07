# Backlog

What is decided and not yet done, in the order it will be worked. Each item names where it was
decided. When an item ships, it moves to the changelog and leaves this list. Longer-range items
stay in [Plan & Scope](plan-and-scope.md) under "Beyond v0.1".

Last reviewed: 5 November 2026, evening (owner: "let's go" on this order, after #97-#103 merged:
timeout, priority, the Library, static analysis, flow node cards, the one-script install and the
run timelines). Updated 7 November 2026: item 1 (run retries and owners), the gaps under item 9,
audit reporting tables and dashboards about Kilnmark itself under item 18, from [research](../design/research/README.md).

## 1. Finish what is half built

1. **Run retries, owners, and Run again from the failure** (owner, 7 November 2026; research in
   [docs/design/research](../design/research/README.md), run options section 8). **Done**
   ([ADR 0053](../adr/0053-run-retries-and-owners.md), migration V39; docs/design/pipelines.md,
   "Run retries and owners", and docs/design/flows.md, "Carrying on from a failure"): a pipeline or
   flow job is now tried as many times as its run may be, so a lost flow is taken over.
   - **Retries on a pipeline or a flow**, however it was started: a number of tries before it
     fails, a delay that doubles to a limit, and which failures to try again (any, or only a lost
     worker). Only when trying again is safe, by node retries' rule; never a cancel; never past the
     schedule's next time; a retry is a new try of the same run, so schedules still fire at most
     once (ADR 0046); a node's own retries take the place of the run's.
   - **A flow carries on from where it failed**: nodes that succeeded keep their results, parallel
     branches carry on from their own state, a for-each node tries only its failed items.
   - **Run again from the failure**, by hand, on a failed flow run: the same version and
     parameters, the succeeded nodes kept, the rest run again; recorded against the first run and
     in the audit log. Pipelines start again in full, as in every tool; a long one becomes
     resumable as stages in a flow, or by incremental reads (item 9).
   - **Owners**: people or team addresses on a pipeline or flow, told when its retries run out,
     when it recovers and when it takes longer than expected; no extra rights and never the
     identity a run acts as; with no owner, admins are told. No alert on each retry.
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
   `sort` and `join` (two inputs of any size, spilling to disk) are done (#137, #140), and
   `window` (row numbers, ranks, running totals, lag and lead, spilling to disk), and more
   expression functions (`if`, padding, splitting, date arithmetic, `sha256` and others); next web
   calls (a REST connector, an `http`
   node and an `http` flow node, behind the allowed hosts an admin sets), JSON and XML, `pivot`
   and `unpivot`, change detection and dimension history, `sequence`, `mask`, generated rows, and
   file actions in flows. Nodes stay coarse: one per shape of work, single-value operations as
   functions. With them, **AI in pipelines** ([ADR 0050](../adr/0050-ai-in-pipelines.md)): an `ai`
   node (classify, extract, filter, score, summarise, translate, ask, diagnose, find personal
   data), with context from other records by keys or meaning (a fault inferred from a valve's
   history and engineers' notes), an `ai-summary` across a group, and an `embed` node, after `mask` so sensitive columns can be masked first; then
   explaining failed runs, drafting a
   pipeline from a description, suggesting quality rules, and run tools on the MCP server, as
   agents that propose and people approve ([ADR 0052](../adr/0052-agents.md)): failed runs first,
   then drafting a pipeline, stewardship overnight, outside agents through MCP, and agents people
   build.

   **The gaps other tools show** (owner, 7 November 2026; [node options, section
   3](../design/research/node-options.md#3-the-15-gaps-that-matter-most-for-banks)), first of all
   in nodes, in this order:
   1. **Rows that fail are kept**: an expression, cast or write that fails on a row sends it to an
      error port with its error code, column and reason, instead of stopping the run; and an
      **error tolerance** on the node (fail past N rows or a share), recorded with the run.
   2. **Which row wins, stated**: upsert, lookup and dedupe say which of two rows with the same key
      is kept (the latest by a column, the first, or an error), never arrival order unsaid.
   3. **Incremental reads by watermark**, the watermark recorded with each run so a load can be
      repeated exactly; change data capture later.
   4. **Write modes**: delete, update only, insert only, a conditional merge, and columns left
      untouched (such as `created_at`).
   5. **Range lookups and joins** (a rate valid on a transaction's date).
   6. **Schema drift chosen on the node** (fail, ignore new columns, add them) and a column
      contract on a write.
   7. **Partitioned reads and a fetch size**; aggregate and dedupe spilling to disk; first, last,
      median, percentile and standard deviation in aggregate.
   8. **Case-insensitive and locale-aware comparison and sorting**, and a random sample with a
      seed.
   History, change detection, window, JSON and XML, and masking are in the list above already.
   Each also closes a migration trap (research README, "Migration").
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
18. **Audit events** for data read with the unmask grant, and for MCP refusals (ADR 0029). And
    **audit reporting tables** (owner, 7 November 2026), for the standard audit reports banks are
    asked for: a stable, documented, read-only set of tables or views, versioned like the API,
    that a reporting tool can read without knowing Kilnmark's own schema. Needs an ADR first. At
    least:
    - **Runs**: every pipeline and flow run, with who or what started it, the version, its
      parameters, start, end, status, tries, and rows read, written and rejected per node, so loads
      can be reconciled (BCBS 239);
    - **Changes**: every saved version of a pipeline, flow, source, rule and schedule, by whom,
      with the approval once change approval exists (SOX change management, item 15);
    - **Access**: who holds which role and grant, sign-ins and refusals, and the access review over
      time (SOX access, NYDFS 500.7);
    - **Sensitive data**: reads with the unmask grant, and where marked data is (GDPR, NYDFS);
    - **Quality**: rule results over time, issues opened and closed;
    - the audit log itself, as a table a report can join to.
    With report templates for the common ones (run evidence, change log, access review,
    reconciliation), and the migration need of log tables that reports read (research, run options
    section 10). Kept as long as the audit log (no deletion; NYDFS asks for five years).

    On the same tables, **dashboards and reports about Kilnmark itself** (owner, 7 November
    2026: "analytics on the tool, not the data"), on a page of their own for admins and stewards:
    - **Runs**: success and failure rates, how long runs take and how that changes, queue waits,
      retries, the slowest and the most-failing pipelines and flows, and runs that took longer
      than expected;
    - **Schedules**: fires on time, late, skipped or paused, and the next day's load;
    - **Volume**: rows read, written and rejected over time, by pipeline, source and target;
    - **Quality**: rule results and issues over time, by source and owner;
    - **Capacity**: worker slots in use, queue length, memory and spilling, by hour;
    - **People and access**: who is active, sign-ins and refusals, tokens near expiry or unused,
      grants held;
    - **AI**: what the AI usage page shows today, over time.
    Each chart filters by time, pipeline, flow, source and owner, and downloads as CSV for an
    auditor. The same figures as metrics a monitoring system can collect (OpenTelemetry or
    Prometheus) are a separate choice, for an ADR.

    **With AI** (owner, 7 November 2026), on these figures only, never on data values (ADR 0027,
    ADR 0050), and proposing, never acting (ADR 0052):
    - **Asking in plain words** in the Ask panel ("which flows failed most last week?", "why are
      the nightly loads slower?"), as a new knowledge domain beside the documentation, answered
      from the reporting tables with the figures and a link to the chart;
    - **Spotting the unusual**: a run several times slower than its usual, rows read that drop or
      jump, a schedule that starts slipping, retries climbing, told to owners as one notice;
    - **A weekly summary** for each owner and for admins: what failed, what got slower, what
      recovered, what needs a look;
    - **Explaining a trend** on a chart, and suggesting what to look at, with the evidence.
19. **A full export in open formats** (for exit), and a FIPS mode when a federal customer needs it.
19a. **Kilnmark's own login off the superuser role**: it owns the server today (ADR 0032), so no
    grant can keep it out of `kilnmark_samples` (ADR 0051); a separate superuser for administration,
    used by `samples-db`, backup and restore only, would let Kilnmark's login be an ordinary owner.

## 5. Housekeeping

20. **The local CI runner on a Podman machine of its own**, without the Windows drives mounted,
    starting afresh for each job (ephemeral), and its PostgreSQL image under a CI-only tag
    (docs/development.md, "CI on a local runner"; review of #111).
21. **The server tests on Windows**: Testcontainers cannot reach Podman from the tool shell, so
    server tests run only in CI there.
22. **Refactoring, in small steps** (owner, 6 November 2026): each its own pull request that changes
    no behaviour, with every test green before and after. In order:
    1. **Done** (refactor 22.1, `io.kilnmark.server.runs`, with the three efficiency findings
       below; docs/design/pipelines.md, "One run lifecycle"). **One run lifecycle for pipelines and
       flows**: checking, queueing, request keys, schedules
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

## Later, not scheduled

Noted so they are not lost; low priority until a customer needs them.

- **OCR for scanned documents and images** (owner, 6 November 2026): scanned work orders,
  delivery notes and invoices are recorded today but not read (ADR 0017, "Left out, for
  later"). A short trial first: Tesseract through Tika's OCR parser, as a separate worker
  image, against a local vision model through the model runtime (ADR 0012), on synthetic
  scanned work orders in the lab, comparing accuracy, time per page and image size. The text
  then reaches the `ai` node's tasks like any document's (ADR 0050).
- **Names and places without a model** (owner, 6 November 2026): Apache OpenNLP's name finder
  as the fallback for `detect-personal-data` with AI off (ADR 0050, decision 6), which today
  finds account numbers by pattern and checksum but not names or places. First check the
  licences of its trained models against ADR 0007: the code is Apache-2.0, the models are
  trained on corpora with licences of their own.

## Owner's items

- The demo deck and the partnership status slide, last made for 0.0.8: refresh for the Foundation milestone.
