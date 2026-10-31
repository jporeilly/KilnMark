# Changelog

Each release starts with **What's new**, a short summary in plain words, followed by the
details.

## Unreleased

### Details

- **The samples ship, and Add samples maps them to your sources.** The samples in `samples/` no
  longer name the lab's sources: they name three placeholders, `crm-db` and `shop-db` (databases)
  and `files` (file storage they write to), and their comments no longer mention the lab. The
  server bundles them (`kilnmark/samples` in its jar, listed in `samples.list`) and the release has
  them in its `samples` folder. `GET /api/v1/samples` lists the placeholders and samples (viewers);
  `POST /api/v1/samples` adds every sample pipeline whose placeholders are all given a saved source
  (stewards and admins), leaving one already there as it is and skipping flows until the flows API
  comes. **Add samples** on the Pipelines page asks which of your sources each placeholder means,
  the sources of the right kind first, and lists what happened to each sample. `lab.sh samples`
  and `lab.ps1 samples` map the placeholders to the lab's sources from `lab/sample-sources.csv`,
  and `LabPipelinesTest` checks every sample through the same mapping, and that the samples name
  only placeholders. `checkProductOnly` now also fails the release if a shipped sample mentions the
  lab. ADR 0024 records the change.
- **The read node's `columns` and `where`** (docs/guide/pipelines.md, "Reading"). A read node keeps
  only the columns chosen, in that order, and the rows meeting `where`, an expression that may use
  the pipeline's parameters (`status in ${statuses}`); `row_limit` counts the rows kept. For
  PostgreSQL, MySQL, MariaDB, SQL Server and Oracle tables, the database applies as much of `where`
  as is safe (text by `=`, `in` and `like`; numbers, dates, true/false; `and`, `or`, `not`) with
  every value bound, never written into the SQL; Kilnmark always applies the whole condition as
  well, so the rows are the same whichever does the work. The node's note says which. On the
  canvas, the read node lists the table's columns with their types to tick, and the Where box
  offers columns and `${parameters}` as buttons; each node's notes now show under its settings.
  Samples: `sample-read-columns-where`, and `sample-orders-by-status` now filters in its read node.
- **Samples and an install guide.** `samples/` holds 18 small pipelines and 2 flows, one idea each,
  sorted by what they show (read and write, filter and route, transform, combine, aggregate,
  quality, parameters, flows), on the reference lab's data; `lab/lab.sh samples` (or `lab.ps1`)
  adds them to a running Kilnmark. (They now ship: see the entry above.) `LabPipelinesTest` checks
  every sample against the lab's columns and every flow against the sample pipelines.
  `INSTALL.md` says how to install on a clean machine: a release, or from source with the lab.
- **Parameters and server variables** (V22; docs/guide/pipelines.md, "Parameters and server
  variables"). A pipeline declares `parameters:` (text, number, boolean, text list, number list, each
  with a default) and uses them as `${name}`; a server variable is a value of the server, set by
  stewards and admins through `/api/v1/variables` (every change kept, names that look like
  credentials refused, `KILNMARK_VAR_<NAME>` seeds one at start-up) and written `${vars.name}`. A
  value only ever fills a value: in an expression as a literal (`status in ${statuses}` becomes
  `status in ('delivered', 'shipped')`; a number as a decimal; never inside quotes or joined to a
  name), in where a write goes only as part of the last name beside text the pipeline writes
  (`orders/${label}.csv`, letters, digits, `_` and `-`), in a number or true/false setting as the
  whole value, and never in a source, asset, column, choice or another node. `POST /api/v1/pipelines/{name}/runs` takes `parameters`; each pipeline and flow
  run keeps its parameters and the server's variables as they were when it was queued. Flows pass
  values on: `with:` on `run-pipeline`, a flow parameter inherited by a pipeline parameter of the
  same name, and an earlier node's values (`${clean.rows_written}`). New flow nodes: `notify` (a
  `flow_notice` notification, in the app and by email) and `wait` (up to 24h); a flow run that fails
  posts `flow_failed`. Lab: `demo-orders-by-status` and the flow `demo-nightly-customers`, checked
  against the lab.
- **Steps are now called nodes** (V21). The boxes on a pipeline canvas are **nodes**, and in a flow
  the line joining two flow nodes is a **connection**. Pipeline and flow YAML list `nodes:`, and
  flows list `connections:`; files with the old `steps:` and `paths:` keys are still read (saved
  versions keep the YAML they were written with; the pipeline canvas writes `nodes:` the first time
  it changes one), and so are problems saved with "Step" in their words. The node catalogue is `GET
  /api/v1/node-types` (was `/api/v1/steps`), and JSON fields named `step` or `stepId` are now `node`
  and `nodeId`. Migration V21 renames the run tables and columns (`pipeline_node_run`,
  `pipeline_node_quality`, `flow_node_run`, `node_id`, `pipeline_run.flow_node`, `series_nodes`) and
  the run log's `step_started` and `step_ended` events to `node_started` and `node_ended`, and
  renames the keys Kilnmark wrote in stored JSON (problems, run results and progress, quality), so
  earlier runs still show. In the code, `kiln-steps` is now `kiln-nodes`, `Step` names become `Node`
  names (`StepFactory` is `NodeFactory`), and the canvas's `StepNode` is `NodeCard`. The setting
  `kilnmark.pipelines.preview-max-steps` is now `kilnmark.pipelines.preview-max-nodes`
  (`KILNMARK_PIPELINES_PREVIEW_MAX_NODES`): set the new name if you had set the old one.
- **The node list sits beside the canvas.** On the pipeline canvas, the open node list now takes a
  column of its own and the canvas narrows beside it, instead of opening over your nodes. A folder's
  icon on the rail opens the list at that folder, its nodes shown under it; the separate pop-out
  beside the rail is gone. On a phone the list still opens over the canvas.
- **Flows, the engine** (development; V20; ADR 0026; docs/design/flows.md). A flow runs saved
  pipelines in an order, joined by connections: on success, on failure, always, or on a condition
  over what a node measured, such as `clean.quarantined_share < 0.02 and clean.score >= good`. Not yet
  reachable from the API or the pages (flows are queued and cancelled through `FlowService` only);
  the API, the run page with its Gantt chart and the canvas follow, and the user guide with them.
  `FlowDefinition` (`flow/v1`: parameters, `run-pipeline` and `stop` nodes, connections,
  `queue_timeout`, checked with line and column and suggestions, loops refused), `FlowCondition` (a
  small language of comparisons with SQL's three-valued logic and the quality words `good`, `fair`,
  `poor`; a condition may read only nodes that must have ended) and `FlowEngine` (the state machine
  that decides what runs next; no threads, no database) are in kiln-core. `FlowStore` and `FlowService` (server) queue a
  flow run as a job of kind `flow`; the worker only watches, holds no worker slot, and each
  `run-pipeline` node queues an ordinary pipeline run as the person who started the flow, with their
  role, linked by `pipeline_run.flow_run_id` and `flow_node`. A node whose pipeline is already running
  waits, up to `queue_timeout` (30 minutes by default). A flow is tried once, like a pipeline; one
  run of a flow at a time, and one worker watches at most 8 flows at a time. Flow nodes never copy
  why a pipeline failed or could not run (it can quote data or name fields). Saving, running and
  cancelling take the steward role. Tests:
  `FlowRunTest` runs flows on PostgreSQL for sequences, quality conditions, failure connections,
  waiting, cancelling and stop nodes.

## 0.0.8 (28 October 2026)

### What's new

- **See inside a run.** The run page shows where each node spent its time (working, waiting for
  rows, or held up by the next node) and names what limited the run; rows per second for each node
  through the run, with a band showing when it was held up; and a log of what happened, gate
  verdicts included, to filter and download. While a run goes, the canvas shows each node's rows
  and rows per second and marks the slowest node.
- **Preview at a node.** Right-click a node and choose **Preview rows**, or open its **Preview**
  tab, to see the rows as they would leave it, before running, with nothing written. Sensitive
  columns stay masked, and for anyone but an admin a preview is refused when a node looks inside
  one.
- **Models that think before answering** (such as gemma4) now pass **Test** under AI models.
- **Clearer nodes after a run.** On the canvas, a node's rows, its measured quality and its
  predicted quality now each look different, and nodes no longer overlap or hide the connections'
  row counts once a run's results are shown.

### Details

- **See inside a run** (V19; `RunListener`, `RunProgress`, `RunLog`; `RunTimeSpent`, `RunRates`, `RunLogPanel`).
  The engine times every node (`NodeResult.startedAt`, `firstRowAt`, `lastRowAt`, `finishedAt`) and
  adds up where its time went: waiting for rows on its inputs and held up by the next node
  (`RowSet` put and take waits, counted only when it has to wait; `pipeline_node_run` keeps them,
  and `duration_ms` is now filled). Through an optional `RunListener` on a thread of its own (a
  listener that throws or hangs never fails or holds up a run; no allocation per batch) it reports
  each node starting, its first row in and out, its last row out and how it ended, the engine's
  notes, and a snapshot every two seconds: rows and rows a second per node, waits so far, each
  queue's fill, and the **Slowest** node. The worker keeps the latest snapshot on `pipeline_run` and
  rows a second in `pipeline_run_point` (appended, at most 900 points, spikes kept), and the log in `pipeline_run_event` (at most 10,000
  events; the engine's words and rule messages are the held-back detail; each write gives up after
  5 seconds). Every time is on the worker's clock. `GET /api/v1/pipeline-runs/{id}/events` (by
  node and level, paged), `.../series` and a streamed `.../events/download` (text or NDJSON). The run page shows
  the run at a glance in tiles, **where each node spent its time** (working, waiting for rows, held
  up by the next node, with "What limited this run: check, working 90% of the time"), **rows per
  second through the run** (a line per node with a Held up band for its output queue, and warnings,
  errors and gates quarantining marked on the time axis), each node's time and share of the run,
  live rows and rates while it runs, and a **Log** to filter and download, with gate verdicts in
  it. While a run goes the canvas shows each node's rows so far and rows a second ("19,814 out ·
  8,351 rows/s") and marks the node holding it back Slowest, in words and with an orange edge.
- **Preview at a node** (`POST /api/v1/pipelines/preview`, `POST /api/v1/pipelines/{name}/versions/{n}/preview`,
  `PipelinePreviews`, `NodePreview`; docs/design/pipelines.md, "Preview at a node"; the guide's "Preview a
  node"). The first rows (100 by default, at most 1,000) leaving one output of one node of the YAML as it
  stands, with their columns and kinds, how long it took and, in words, any node before it that reads all
  its rows first. Only the node and the nodes upstream of it run, on the API process while the caller
  waits; a collector takes the rows and finishes, so the engine ends the nodes before it early. Only reads
  and the in-memory row nodes may run: anything else, writes included, is refused (400). Stewards and
  admins; reads resolve their sources inside a run context naming the caller. For all but admins,
  columns marked sensitive may pass through unchanged and are masked inside the collector; a preview
  in which any node looks inside one (an expression, cast, group, dedupe or lookup key, or a gate's
  rule, inline or the asset's, whatever its `on_fail`) is refused (403), since even its success or
  failure could tell a value; a failure's message is held back as a run's is. Limits, checked before
  any work: one per person at a time, `previews-per-minute` (6 per person), `preview-concurrency` (2),
  `preview-max-nodes` (50), `preview-max-reads` (10); stateful nodes' `memory_limit_mb` lowered to a
  share of `preview-memory-limit-mb` (256); `preview-timeout` (30s) is hard: the nodes run on their
  own thread and the request is answered after the limit and 2 s of grace whatever they do (a
  preview still stopping is logged, counted and keeps its slot until it ends); and a preview stops
  when its request is closed. Responses are `Cache-Control: no-store`. On the canvas: a node's context menu (right-click,
  Shift+F10 or the menu key; `NodeMenu`) with Preview rows, Settings, Connect to…, Duplicate and Remove,
  and a Preview tab in the node's drawer (`NodePreviewPanel`, reusing `PreviewTable`). A right-click no
  longer selects the node first, so the drawer does not open under the pointer.
- **Engine: a node whose readers have all finished ends early even when it writes only to unconnected
  ports** (`KilnGraphRunner`). Before, such a node (a filter whose only connected output is `rejected`,
  say) learnt its readers had gone only when it next wrote to them, and could read its whole input for
  nothing. `KilnGraphRunner` also takes a `stopWait`: how long a stopped run waits for its nodes'
  threads (unbounded unless set; previews wait 5 s). `Preview.text` and `Preview.utf8Length` are now
  public, for the node preview's collector.
- **Canvas chips after a run** (`NodeCard` `nodeChips`, `components/ui/StatusChip`). Each kind of
  chip has its own look and words, so none depends on colour: rows and outcome in a neutral chip
  with a rows icon ("5,000 rows out · Finished"; Ended early, Stopped and Failed in a chip of their
  own, toned `warn` or `bad`); measured quality in a solid chip with a gauge icon, toned by its word
  (`--km-tint-green/amber/red-*`), "Quality 99% (Good)"; predicted quality in a dashed outline chip
  in the AI violet (`--km-tint-violet-bg`/`-fg`, the new `StatusChip` `outline`, `.km-chip--outline`)
  with a sparkles icon, "Predicted ≈ 100%", "(estimate)" for screen readers and in its title, and
  "1 unknown" for what it could not tell; a gate's counts as plain text, "4,771 passed · 229
  quarantined". One row per kind, each 20px high, so a node's height is known before it is drawn.
- **Canvas spacing for run results** (`layout.ts`). `nodeHeight` counts the rows of chips a node
  shows (problems and run, measured, predicted, gate counts), and the canvas uses the larger of that
  and the height React Flow measured. Auto-layout (dagre) gets each node's real height and spaces the
  columns for the widest connection label. A layout saved before a run is moved apart in the view
  only (`makeRoom`: nodes move right where a connection's label does not fit between columns, then
  down where they would overlap or come closer than 36px in a column); the YAML's `layout:` is
  unchanged until a node is moved or Auto-layout is chosen. Connection labels have a padded
  background.
- **A write's header says file or table**: "Sink · Write to a file" with `path` or a file-like
  `target` (a folder or a file extension), "Sink · Write to a table" with `table` or another
  `target`, instead of the type's "Write to a table or file" cut to "Write to a table…".

- **Quieter Windows builds** (development): `build.ps1` and `lab.ps1` no longer show podman's
  "Executing external compose provider" notice, or the harmless message that the shared network is
  still in use, as errors. `lab.ps1 start` and `lab.sh start` check each demo source's port first and
  name the container or program already holding it, instead of failing inside compose with
  "address already in use" (lab/README.md, "Port already in use").
- **The lab's S3 moves to port 9020** (development), from 9000, which other S3 servers such as MinIO
  commonly take. `lab/sources.csv`, the lab scripts and the guides say 9020; import `lab/sources.csv`
  again to update `lab-bucket` and `lab-warehouse`.
- **Models that think before answering pass Test.** Local models such as gemma4, qwen3 and
  deepseek-r1 spend part of their reply on reasoning. **Test** allowed only 20 tokens, so they ran out
  before answering and Test said "The model answered with nothing". Test now allows 1,024 tokens, and
  Ask's search planning 1,500 (from 300).

## 0.0.7 (28 October 2026)

### What's new

- **Any database with a JDBC driver.** For databases without a connector of their own, such as
  Db2, Teradata, Snowflake, Sybase or Informix, an admin registers the database's JDBC driver under
  **Settings**, **JDBC drivers** (upload the jar or pick it in the drivers folder), then adds a
  **Generic JDBC** source with its URL. Kilnmark discovers and profiles its schemas and tables,
  previews and reads them in pipelines with their types (decimals never rounded; unknown types as
  text, with a note), and, when the source is not read-only, appends to tables or creates them.
  Kilnmark records the driver's checksum, who registered it and when, and stops using it if the
  file changes. The admin also lists the driver settings sources may use; any other in a URL is
  refused. Replace and upsert are not offered for these sources, as no database-neutral way
  makes them safe.
- **Predicted quality before a pipeline runs.** While you edit a pipeline, each write and quality
  gate on the canvas shows the score Kilnmark predicts for it, such as "Predicted 91% (estimate)",
  worked out from what the catalogue already knows about the tables and files it reads, without
  reading any data. What it cannot tell, it says is unknown. Each run keeps its prediction, and the
  run's page shows the measured score beside the predicted one, per dimension, with the gap: a wide
  gap shows where the catalogue's information, or the pipeline, needs attention.
- **Suggestions to improve a pipeline.** **Suggestions** on the canvas lists nodes that would raise
  a write's predicted score, such as trimming spaces, standardising two date formats, keeping one
  row per key, dropping rows with no match in a related table, or setting aside rows outside the
  accepted patterns, each with its predicted effect. **Add** puts the node in place, and one Undo
  takes it out. With an AI model set for **Pipeline suggestions**, it ranks and explains them and
  adds its own, seeing only names, types, shares and value shapes of sources whose AI rule allows
  it, never values; what it suggests is checked before you see it.

### Details

- **Canvas icons.** Read nodes and the Read folder show a database, write nodes and the Write folder
  a save icon; the two database icons they had differed only by a small arrow.
- **Generic JDBC connector** (kind `jdbc`; docs/design/connectors.md, "Generic JDBC"; docs/guide/drivers.md,
  "Databases without a connector of their own"; docs/guide/writing-data.md, "Generic JDBC"). Settings
  `driver` (CHOICE of the registered drivers), `url` (TEXT; must start as the driver's URL prefix and
  pass the driver's `acceptsURL`; every `key=value` in it, after `; ? & : / ,` or inside `( )`, must be
  on the driver's allow-list, and a URL with an `=` Kilnmark cannot place, quotes, braces,
  back-slashes, spaces or percent-encoding is refused, `JdbcUrlSettings`), `username`, password as the
  secret, `properties` (`name=value; ...`, on the allow-list too), `schemas`, `read_only` (default
  true). Names that hold secrets (`pass`, `pwd`, `secret`, `token`, `key`, `credential`) or the user
  name are refused in the URL and the properties whatever the allow-list, and the URL is never quoted
  in messages. `hosts()` also reads host-naming settings (failover partners, `*servername`,
  `alternate*`, values that are URLs), so `kilnmark.sources.allowed-hosts` applies to them.
  `Connector.adminOnly()` (new): only admins add such sources, change their connector settings or
  check unsaved ones (`SourceService.adminOnlyKind`, 403). Catalogue from `DatabaseMetaData` with
  fallbacks (schemas, else catalogues, else one `default` container; nulls), types by
  `GenericTypes` (decimal(p,s) up to 38 digits, wider or undeclared as exact text, timestamps with
  and without zone, unknown vendor types as text with a note in the column's description). Reads
  use `SELECT *` quoted as the driver says and `Statement.setMaxRows` for limits
  (`JdbcTableInput.MAX_ROWS`, new, with the fetch size kept at or below it); quality rules are
  checked on the rows read (`JdbcConnector.runsRulesInDatabase()`, new). Writes: `append` and
  `create` only, through `JdbcTableOutput` with `GenericWriteSql` (table, columns, sizes and types
  from `DatabaseMetaData`); new `WriteSql` hooks `existsByMetadata`, `requiredByMetadata`,
  `targetColumnsByMetadata`, `createProblem`, `checksCreatedTable` (a created table is described
  again and every value checked against it) and `dropCreatedOnFailure` (where DDL commits). The
  other writers are unchanged. `replace` and `upsert` are refused with a message. Text is checked
  against `CHAR_OCTET_LENGTH` in UTF-8 bytes where the driver reports it. Every database write now
  refuses a driver that stays in auto-commit or offers no transactions. A table a failed create made
  is dropped only where table definitions commit by themselves and only while it is empty. The
  generic reader goes on when a driver refuses `setReadOnly` or `setAutoCommit(false)`. A target
  schema must be one the driver lists; without an identifier quote only plain names are read. Times
  kept to more than six digits of a second say in the column's description that the rest is cut.
- **Registered JDBC drivers** (docs/design/drivers.md, "Registered drivers"; ADR 0025). Settings ›
  JDBC drivers and `GET/POST /api/v1/drivers/registered`, `DELETE /api/v1/drivers/registered/{name}`,
  `GET /api/v1/drivers/registered/history`, `GET /api/v1/drivers/files`,
  `POST /api/v1/drivers/files/{fileName}` (octet-stream; never replaces a file), all admin-only.
  Registering takes the driver's allowed properties (never secrets, files, folders, classes, key
  stores), loads the class from a private copy without running its initialisers until it is known to
  implement `java.sql.Driver`, refuses a jar with a pinned driver's checksum under any name, and records
  the jar's SHA-256 (`driver_registration`, migration V18); `DriverFolder.driver(RegisteredDriver)` loads only while
  the checksum matches, in its own class loader, never through `DriverManager`. Removal is refused
  while sources use the driver (checked and removed in one transaction, sources locked);
  `driver_registration_log` keeps every registration and removal, append-only (a trigger refuses
  UPDATE, DELETE and TRUNCATE). Uploads are limited by the server's request limit
  (`quarkus.http.limits.max-body-size`, 10 MB by default), which the page shows; larger jars are copied
  into the folder. Jars are read without following links and never beyond 200 MB.
  Nothing is downloaded for registered drivers. The pinned path is unchanged.
- **H2 for tests only** (`com.h2database:h2` 2.5.252, MPL 2.0 or EPL 1.0): `testRuntimeOnly` in
  `kiln-plugins`, registered through the drivers folder by the generic JDBC tests.
  `checkNoCustomerDrivers` now also fails a release holding H2's jar (`h2-*.jar`) or classes
  (`org/h2/`).

- **Predicted quality** (docs/guide/pipelines.md, "Predicted quality"; docs/design/data-quality.md
  section 8). `QualityPredictor` carries each read asset's latest profile (nulls, blanks, distinct,
  value shapes, formats, most common values, quantiles, outliers), accepted shapes, relationships
  and rule results through the nodes, one rule per node type (filter conditions from null shares,
  most common values, quantiles and value shapes; compute's trim, case, coalesce, parse_date and
  format_date; lookup orphan rates from a relationship's containment or the columns' HyperLogLog
  sketches; aggregate and dedupe from distinct counts; union weighted by rows; a quarantining gate
  removing the predicted failing share from `out`). Writes are scored with the same `Measures` and
  `Scores` as a run's measured output, keeping only the dimensions the estimate can tell; gates
  from their rules' predicted failing shares, scored as the gate scores itself. Each prediction
  lists what it could not tell and what it assumed. `kiln-nodes` gains `Expression.view()` and
  `columns()`, a read-only view of a parsed expression (`ExprView`).
- **API.** `POST /api/v1/pipelines/predict` (`{yaml}`; viewer) returns `{estimate, valid, nodes:
  [{node, type, rows}], outputs: [{node, role, asset, overall, dimensions, reasons, unknown,
  assumptions, rows, gate}]}`; `GET /api/v1/pipelines/{name}/versions/{n}/prediction` the same for a
  saved version; `POST /api/v1/pipelines/suggest` (`{yaml, ai}`; steward) returns `{estimate,
  suggestions: [{id, kind, title, why, source, nodes: [{id, type, settings, inputs}], placement:
  {before, port, from, through}, effect: {output, before, after, dimensionsBefore, dimensionsAfter,
  rowsBefore, rowsAfter, note}}], ai: {state, message, model}}`. A run's quality rows gain
  `prediction` (`{unknown, assumptions, rows, gate}`) beside `predicted`.
- **At run start** each write's and gate's prediction is stored (migration V17 adds
  `pipeline_node_quality.predicted_detail`); a failed run keeps its predictions, with a note that the
  write was not measured.
- **Suggestions** (`PipelineAdvisor`): rule-based candidates with no model, each tried out by
  predicting the pipeline with it and kept only when it raises the write's score. New AI feature
  `pipeline_suggestions` (chat): ranks, explains and adds, through `AiGateway` with the interactive
  budget. Only columns whose source's `ai_egress` lets that model see them are sent (names, kinds,
  shares, value shapes, accepted shapes; never values, never computed columns), with the strictest
  of those sources passed to the gateway for its own check; no call is made when no source allows
  it. The model's additions are checked (node types the server has, settings they take, columns it
  was shown that reach that point, the node's own check and output schema) and dropped otherwise.
  An absent, refused, slow or failing model leaves the rule-based suggestions, and says why. A
  suggestion reading another asset is shown to the model only when that asset's source allows it.
  The model is asked when the panel opens and on Ask again (at most 6 times a minute per person);
  edits re-run only the rule-based suggestions. A suggestion that removes rows says how many.
- **Values stay with those who may see them.** Prediction for a request uses a column's most common
  values and spread only when the caller may see data, and for a column marked sensitive only for an
  admin (through `Sights`, checked by `SightsUseTest`); otherwise those estimates are unknown, so a
  viewer cannot probe values through predicted row counts.
- **Canvas.** Predicted scores on each write and gate (debounced, labelled "(estimate)", in each
  node's accessible name and settings drawer); a lazily loaded **Suggestions** panel whose **Add**
  inserts the nodes with `insertNodes` (text edits only, one undo step). The run page shows
  measured beside predicted per dimension with the gap.

- **A demo from start to finish** (development): `lab/README.md` walks through a whole demo, from
  starting Kilnmark and the demo sources to discovery and pipelines with a quality gate that sets rows
  aside and then stops a run. Three demo pipelines in `lab/pipelines`: customers cleaned through a
  gate (PostgreSQL), spend per customer with aggregate, lookup and route, and a MariaDB supplier feed
  whose repeated SKUs are quarantined. `lab.sh pipelines` and `lab.ps1 pipelines` add them to a
  running Kilnmark through its API, and `LabPipelinesTest` checks them against the lab's columns so
  a change to the nodes cannot break them unnoticed. Nothing of this ships.

## 0.0.6 (28 October 2026)

### What's new

- **Quality gates in pipelines.** Add a **Quality gate** node (in the Quality folder) to check rows
  against quality rules as they pass, with no second read of the data: the rules of the table or
  file the pipeline read, and any you write on the node. When rows fail, the gate can stop the run
  so that the writes after it do not commit, send the failing rows to a separate **quarantine**
  output while the rest carry on, or just warn. A limit on the share of failing rows, or on the score, stops the run
  when too much is wrong.
- **Every run measures the quality of what it writes.** After a run, each write and each gate shows
  its quality score on the canvas, beside its rows, and the run's page lists the score per
  dimension and each gate's rules. Give a pipeline a quality threshold (`quality: min_score`) and
  its followers are told when a run writes data that scores below it.
- **Build pipelines on a canvas.** A pipeline's page has a **Canvas** tab beside the YAML: drag
  nodes from a list of folders (Read, Write, Filter and route, Transform, Combine and more, with
  your recently used nodes first), connect their ports, and change each node's settings in a panel that offers
  your sources, tables and columns, with problems shown on the node and setting they are about.
  The canvas and the YAML are the same pipeline, so a change in one shows in the other. After a
  run, each node shows its rows and how it ended. Everything works from the keyboard too.
- **MySQL and MariaDB.** Add MySQL (8.0.19 and later) or MariaDB (10.6 and later) as a source by
  host, port and database: Kilnmark discovers and profiles their databases, reads their tables in
  pipelines and writes to them in every mode. MySQL's own types are understood: `UNSIGNED`
  numbers, `ENUM`, `SET`, `JSON`, `YEAR`, and `TINYINT(1)` read as true or false. The driver is
  included, so there is nothing to install. Writes never change data silently: an upsert that could
  overwrite another row through a second unique index, a time with more fractions of a second than
  its column keeps, or a replace that a hidden foreign key would break stops with a plain message.
- **Oracle.** Add Oracle Database (19c and later) as a source by host, port and service name:
  Kilnmark discovers and profiles its schemas, reads its tables in pipelines and writes to them in
  every mode, without ever rounding a number or turning empty text into null behind your back.
  Oracle's driver cannot ship with Kilnmark, so an admin installs it once, under **Settings**,
  **Drivers**.
- **Settings, Drivers.** Where admins install the drivers some databases need that Kilnmark may not
  ship: accept the licence and Kilnmark downloads the exact version it was tested with and checks
  it, or, on a server without internet access, shows which file to copy where. Until then those
  connectors say **Needs its driver**.
- **Every connection setting has the right control.** On and off settings, such as trusting a
  test server's certificate, are switches; settings with a fixed set of values, such as SQL
  Server's read isolation, are lists; and saving says plainly when a value is the wrong kind,
  for example "true or false, not 'yes'".

### Details

- **Quality gate node** (`type: gate`, docs/guide/pipelines.md, "Quality gates";
  docs/design/data-quality.md sections 7 and 8). Ports `in`, `out` and `quarantine`. Settings:
  `on_fail` (`stop`, the default; `quarantine`; `warn`), `asset_rules` (on by default: the enabled
  rules of the asset read upstream), `rules_from` (the read node to take them from when rows come
  from several), `rules` (inline rules, each a mapping such as `{type: not_null, field: email}`,
  checked as rules made in Quality are; `references` and `custom_sql` are refused, as they run in
  the database), and the run-level thresholds `min_score` and `max_failing_share` (0 to 1). Rows are
  checked in the stream with `RowChecks`, all rules in one pass; `RowChecks.Check.test` says whether
  a row fails. `stop` fails the run as soon as a rule can no longer pass (a count limit passed, or
  a failing row where none is allowed), otherwise at the end of the rows, so the writes after it do
  not commit, naming the rule and its counts. Validation refuses what would get round that: a write
  after a stop gate that commits as it goes (`commit_every` with create, append or upsert) and a
  `limit` after it; it notes a write that gets the gate's rows by another path, and quarantine wiring
  that drops rows or gets none. `quarantine` stops only when a threshold is crossed or a check of
  the whole data (row count, freshness) fails. In a gate, `unique` fails only the repeats of a key;
  it keeps keys as 128-bit hashes, up to 96 MB (`RowChecks` now hashes keys for every streamed
  uniqueness check), and says how many repeats it had set aside if it reaches that. A gate cut short
  by the run is still judged on what it saw, marked incomplete. A new setting kind `RULES`
  (`SettingSpec.Kind`, `rules` in `GET /api/v1/node-types`) takes a list of mappings; the canvas edits
  it as a list of rules and writes one rule a line. A gate whose rows come from several reads must
  name one with `rules_from`, and a note says when a gate has nothing to check.
- **Measured quality per run** (migration V15, `pipeline_node_quality`): each write's score per
  dimension and overall, from the profile its writer made while writing (Measures and Scores, as an
  asset is scored, without history), and each gate's score from its rules' results with its
  decision, row counts and each rule's result. `GET /api/v1/pipeline-runs/{id}` adds `quality`
  (`[{node, role, asset, measured, predicted, note, gate}]`; `predicted` is null until design-time
  prediction comes), and run summaries add `qualityScore` (the lowest output's) and
  `qualityThreshold`. An `append` or `upsert` is not profiled, so it has no score, and says so.
- **Pipeline quality threshold.** A new top-level `quality:` block with `min_score` (0 to 1). When a
  run succeeds and an output scores below it, `pipeline_low_quality` is published once for the run,
  naming the output (`NotificationPublisher.pipelineRunFinished` gains an overload with the node).
- **The pipeline canvas** (docs/guide/pipelines.md, "Building on the canvas"): a palette of node
  types by kind with search, nodes with named ports and problem badges, connections refused with a
  reason, move, duplicate, delete, undo and redo, zoom, fit and a left-to-right auto-layout, and a
  settings panel generated from each node type's settings schema. Where nodes are drawn is kept in
  a new top-level `layout` block that the compiler accepts and ignores. `GET /api/v1/node-types` gives
  the write node `writerSettings`, the writer settings each kind of source takes. New pipelines
  can start from the template or an empty canvas; **Run now** asks first when there are unsaved
  changes. Keyboard: arrow keys between connected nodes, Shift+arrows to move, `C` for Connect to…,
  `?` for every shortcut. The UI adds `yaml` 2.9.1 (ISC), pins `@xyflow/react` to 12.12.0, and
  runs its unit tests (vitest) in `npm run build`; the canvas loads lazily, outside the main
  bundle. A canvas change rewrites only the YAML it changes: other lines stay byte for byte. The
  canvas takes the page's full width with an icon rail of node folders (`NodeFactory.category()`,
  returned as `category` by `GET /api/v1/node-types`), a settings drawer, an overview map, **Fit** and
  **Readable size**. Undo also covers typing in the YAML tab and loading the latest version after a
  save conflict, which now keeps your version one undo away; moving nodes alone no longer makes
  **Run now** ask about unsaved changes.
- **MySQL and MariaDB connector** (kinds `mysql` and `mariadb`), through MariaDB Connector/J
  3.5.10 (LGPL 2.1, allowed for that module only: ADR 0007; the licence's text now ships in the
  release's `licenses/` folder, named in NOTICE). Settings `host`, `port`, `database`,
  `username`, `tls_mode` (`preferred` by default), `server_ca` and `tinyint1_as_boolean`, with
  kinds declared; no driver property can be set by people. Zero dates read as null and are
  counted; `TIME` reads as text. Writes: `create`, `append`, `upsert` (`ON DUPLICATE KEY UPDATE`)
  and `replace` (an atomic `RENAME TABLE`), with `LOAD DATA LOCAL INFILE` for tables the node
  creates when the server allows it. The connection form shows their host, port and database once
  (`addressFields` is false for connectors whose own settings hold the host). Tests against
  MySQL 8.4.10 and MariaDB 11.4.12 run in CI's heavy-databases job.
- **MySQL and MariaDB writes, after review.** Sessions use a fixed `sql_mode`
  (`STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`),
  never the server's. Upsert refuses a table with another unique index that lacks a key column,
  naming it, unless `allow_other_unique_keys: true` (a new writer setting). Replace refuses
  engines other than InnoDB, Aria and MyISAM, and tables whose triggers the login cannot be shown
  to see (with a role active too); it reads foreign keys from `INNODB_FOREIGN` /
  `INNODB_SYS_FOREIGN` when the login has `PROCESS`, and when a key it could not see refuses the
  old table's drop, puts both names back and fails. Any other leftover `kiln_old_…` table is a
  note on the run (`write_warning` on the writer's `ROW_COUNT`). Per value: `DATETIME(p)`,
  `TIMESTAMP(p)` and `TIME(p)` refuse extra digits of a second (PostgreSQL `timestamp(p)` and
  `time(p)` too); `TIME` takes only times and text; `YEAR` takes 1901 to 2155. `LOAD DATA` checks
  `@@warning_count`. On servers with `lower_case_table_names` set, targets are written and recorded
  in lower case.
- **Oracle connector** (kind `oracle`): settings `host`, `port`, `service_name`, `username`,
  `encryption` (`accepted`, `required` or `tls`) and `schemas`, with kinds declared; the address is
  always built, never given. Catalogue from the `ALL_*` views, keys across schemas, `NUM_ROWS` as
  the estimate, `SAMPLE` for sampling. `NUMBER` without precision and `FLOAT(b)` read as text
  holding their exact digits; `DATE` as a timestamp; `TIMESTAMP WITH LOCAL TIME ZONE` through a
  cast to `TIMESTAMP WITH TIME ZONE`, whatever the JVM's time zone. Writes: `create`, `append`,
  `upsert` (`MERGE`) and `replace` (one PL/SQL block that puts the names back on failure),
  refusing empty text and bytes (unless `empty_text_as_null`; in an upsert key always), decimals
  past 38 digits, a `FLOAT(b)` column's digits or a NUMBER's range, timestamps with more digits of a
  second than the column keeps, characters the column's character set lacks (on databases that are
  not Unicode; national columns bound with `setNString`), and text over a column's size in bytes.
  The driver's `TNS_ADMIN` is an empty private folder. Tests against Oracle Database Free in CI's
  heavy-databases job, in their own Gradle run (`./gradlew :kiln-plugins:oracleTest`, in
  `America/New_York` time); `encryption` `required` and `tls` are not tested against a listener.
- **Drivers the customer installs** (ADR 0007, docs/design/drivers.md): connectors declare their
  driver (coordinates, pinned version, SHA-256, licence, driver class); the drivers folder
  (`kilnmark.drivers.dir`, `/var/lib/kilnmark/drivers` in the image) loads only verified jars, from
  a private copy in `drivers.loaded` beside it (emptied at start), each in a class loader that sees
  only the JDK, and uses a loaded driver again without re-reading its file while the file's size and
  modification time are unchanged. `GET /api/v1/drivers` and
  `POST /api/v1/drivers/{id}/accept` (admins) record who accepted the licence and download from
  `kilnmark.drivers.maven-url`; `kilnmark.drivers.download=false` for servers without internet
  access. `GET /api/v1/connectors` adds each connector's `driver` state. Oracle's driver is a
  test-only dependency, and `./gradlew release` fails if one reaches the release folder
  (`checkNoCustomerDrivers`, which also looks inside jars nested in jars). Database migration V14
  adds `driver_licence`. **Upgrades:** when a release pins a newer driver version, the installed
  file is no longer used; the driver shows as missing, an admin accepts the licence again, and the
  server logs a warning at start naming the old file. Release notes will say when this happens.
- **Lab: Oracle** (development). `lab-oracle` (Oracle Database Free 23ai, profiles `oracle` and
  `all`) on host port 1522, service `FREEPDB1`, with a synthetic parcel carrier in schemas `FLEET`
  and `FREIGHT` and planted quality problems (null emails, out-of-range and backwards dates,
  negative weights, duplicate tracking numbers in a table without a key, three postcode formats, a
  `NUMBER` without precision), an empty `WAREHOUSE` schema for pipeline writes, a `lab-oracle` row
  in `lab/sources.csv`, `oracle` in `lab.ps1` and `lab.sh`, and `lab.sh driver oracle`, which
  downloads Oracle's driver after showing its licence. CI loads the seed and checks the answer key.
- **Lab: MariaDB** (development). `lab-mariadb` (MariaDB 11.4.12, profiles `mariadb` and `all`) on
  host port 3307, with synthetic shop orders and inventory in databases `shop` and `stock` and
  planted quality problems (null emails, zero dates, out-of-range dates, a negative stock level,
  duplicate SKUs in a table without a key, inconsistent JSON), an empty `warehouse` database for
  pipeline writes, a `lab-mariadb` row in `lab/sources.csv` and `mariadb` in `lab.ps1` and
  `lab.sh`.
- **Setting kinds.** `Connector.Setting` has a kind (`TEXT`, `NUMBER`, `BOOLEAN`, `CHOICE` or
  the new `URL`, from `SettingSpec.Kind`), its choices and its default; the old constructor gives
  `TEXT`. Every built-in connector declares them, and the settings every source has carry a kind
  too, now including `keep_samples`, `sample_rows` and `table_sample_above_rows`.
  `GET /api/v1/connectors` adds `kind`, `choices` and `defaultValue` to each setting and
  `GET /api/v1/common-settings` adds `kind`; nothing was removed. Saves and bulk imports refuse a
  value of the wrong kind; stored values stay text, so nothing is migrated and values saved before
  still load. The connection panel follows the kinds and keeps its old guesses only for a setting
  without one.

## 0.0.5 (27 October 2026)

### What's new

- **Email alerts and notifications.** A bell in the top bar and a Notifications card on the home
  page tell you when a quality rule starts failing or recovers, an issue is opened, a scan or
  connection test fails, or drift is found. Turn on email under **Settings**, **Notifications**:
  each alert as it happens or a daily digest, for every source or only yours. Admins set the mail
  server, team addresses and who may get email under **Settings**, **Email**, and can see every
  email sent. Emails never contain values from your data.
- **A new look.** A dark sidebar with coloured icons, a light page with white cards, new fonts and
  clearer status chips, in Light, Dark and High contrast. Orange is still the default accent, and
  you can choose another under **Settings**, **Appearance**.
- **A home page that shows what matters.** A greeting with what needs attention, your sources at a
  glance with their health and quality score, the issues that need a look, and a checklist that
  shows how far set-up has got, which shrinks to one line once everything is done.
- **Add recommended rules together.** On an asset's new **Quality** tab, tick the suggested rules
  you want (the ones that fit every row are ticked already) and add them in one go. Each rule
  shows a bar for each of its last ten results.
- **Asset pages have tabs.** Overview, Columns and Quality, so a long page is easier to move
  around.
- **Sources as cards, and real switches.** Sources show as cards with their health. On and off
  settings, such as including sub-folders or keeping document text, are switches instead of
  typing true or false.
- **Ask Kilnmark, notifications and your account in the top bar.** The theme switch has moved into
  the menu under your name. **What's new** is now the first entry under Documentation in the
  sidebar, marked **New** after an upgrade.
- **Better on small screens.** The sidebar becomes a row of icons, then a menu, and pages no
  longer scroll sideways on a phone.

### Details

- **The release holds the product only** (ADR 0024): the reference lab is for development, tests
  and demos, and never ships. The release has its own `compose.yaml`, made from
  `containers/compose.release.yaml`: no lab defaults, a commented-out example for the folder to
  scan, images tagged with the version, and the server image built from the release folder (the
  server Containerfile's new `APP_DIR` argument). It has no default password or token: it requires
  `KILNMARK_DB_PASSWORD` and `KILNMARK_ADMIN_TOKEN` and stops, naming the missing one, without
  them; `.env.example` beside it lists every setting; the database is published on the machine
  itself only. The root `compose.yaml` is the developer setup, with its defaults.
  **New pipeline** starts from the first source with scanned tables or files and the first that
  allows writing, or from placeholders such as `<your source>`, instead of lab sources. Guide and
  message examples use neutral names (`sales-db`, `warehouse`, `file-share`, `landing-bucket`).
  `release` now runs `checkProductOnly`, which fails on any lab folder, source name, path, lab-only
  port or mention in the release, in Kilnmark's own jars (the UI bundle included) and in the
  bundled guides, and on a default or literal value for any password, secret or token in the
  shipped `compose.yaml` or `.env.example`; justified exceptions go in
  `config/product-only-allow.txt`.
- **Reference lab: `lab-warehouse`** (lab/README.md): a second bucket on the lab's VersityGW server,
  an anonymous volume that starts empty (no new service). `lab/sources.csv` registers it with
  `allow_writing=true`; the column's empty cells keep `lab-db`, `lab-bucket` and `lab-share`
  read-only, also on a later import. Reset it by emptying the bucket.
- **A lab bucket for pipeline output (development).** The reference lab has an empty bucket, `lab-warehouse`,
  that pipelines may write to, so the lab's synthetic source data stays read-only.

- **A sample SQL Server database in the reference lab (development).** `.\lab\lab.ps1 start sqlserver` (or
  `./lab/lab.sh start sqlserver`) starts SQL Server 2022 Developer with `labdb`, a synthetic
  field-service and sales database with problems planted for profiling and rules to find. Start,
  stop and reset each demo source on its own, and every new connector now comes with a lab dataset.
- **Reference lab: SQL Server and per-source scripts** (lab/README.md): service `lab-sqlserver`
  (`mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04`, the tag `SqlServerIngestTest`
  pins; port 14330; named volume; about 2 GB of memory; starting it accepts Microsoft's Developer
  licence, `ACCEPT_EULA=Y`) and a one-shot `lab-sqlserver-init` that loads `labdb` once with the
  image's `sqlcmd`: schemas `sales`, `hr`, `ops` (9 tables, a view, 6,901 rows, synthetic personal
  data marked in descriptions and sensitivity classifications) and an empty `warehouse` for
  pipeline writes. Logins `kiln_reader` (read-only) and `kiln_writer` (tables in `warehouse` only).
  Planted: null emails, duplicate keys in a staging table with no primary key, out-of-range dates
  and five phone formats. `lab/sources.csv` adds `lab-sqlserver`, with
  `trust_server_certificate=true` for the server's self-signed certificate. Every lab service
  is now in a compose profile named after its demo source plus `all`, so a bare `up` starts
  nothing; `lab/lab.ps1` and `lab/lab.sh` (`list`, `start`, `stop`, `status`, `reset`, `logs`,
  `sources`) start just what is needed, and `build.ps1 -Lab` takes names
  (`-Lab postgres,sqlserver`). docs/design/connectors.md gains a definition of done: a connector
  ships with a lab dataset, or for SaaS and cloud-only connectors a mock or emulator and a test
  account recipe; lab/README.md has a lab coverage table.
- **SQL Server: Trust the server certificate** (docs/design/connectors.md): new setting
  `trust_server_certificate` (`true` or `false`, off by default) for test servers with a
  self-signed certificate. It passes `trustServerCertificate=true` to the driver as a connection
  property on every connection, scans, checks, rules and reads (`JdbcConnector.driverSettings`,
  `driver.` settings of the reader node), and never edits the URL; a URL that also sets
  `trustServerCertificate` is refused with the setting, and changing it needs the password again.
  The connection-failure hint names it instead of the URL property.
- **Bulk import keeps semicolons** (docs/development.md): only list settings (`schemas`,
  `containers`) turn `;` into `,`. A SQL Server URL in a `url` column, a `;` delimiter and a
  description were corrupted before; passwords never were.

- **Notifications and email alerts** (docs/guide/notifications.md, docs/design/notifications.md):
  rules that start to warn, fail or not run and that recover, new issues, failed scans, drift
  against the previous scan and failed connection tests become notifications. One internal event
  type (`NotificationEvent`, fired through `NotificationPublisher`, recorded off the caller's
  thread), told once per failing streak and once on recovery (`notify_state`). **In the app** for
  every person (a token that opens the app; not scripts or AI assistants' tokens), per kind, with
  `GET /api/v1/notifications` (each item's channels: in-app, and the caller's email with its status;
  team emails by name for admins), `GET /api/v1/notifications/unread-count` (99 with `more: true`
  beyond) and `POST /api/v1/notifications/read`; kept 90 days. **By email** for those who opt in
  and confirm their address (admins get high-severity events at once): kinds, least severity, at
  once or a daily digest at an hour in the server's time zone, every source or some
  (`/api/v1/me/notifications`, **Settings › Notifications**). Admins set the mail server and its
  rules under **Settings › Email** (`/api/v1/mail/settings`): STARTTLS, TLS or none; the password
  sealed with the server's key, write-only, never logged, and only ever sent where it was entered
  for (changing the server, port, security or user name asks for it again); sign-in without
  encryption refused unless allowed; allowed email domains (others paused); confirmation of
  personal addresses; tries, the hourly limit per address, and what happens to emails unsent
  after a while. `kilnmark.mail.*` gives the defaults, and `kilnmark.mail.locked` fixes settings
  the UI then shows read-only. A test email reports the server's answer in plain words, scrubbed
  of the sign-in. Team addresses get what a viewer sees. Emails are plain text and simple HTML,
  without images or tracking, never with values from the data: what a database said (a failed scan
  or connection test) only for stewards and admins, for admins alone on sources with sensitive
  columns. Workers send them with Jakarta Mail (Eclipse Angus), one claim at a time, retrying after
  1, 3, 9 and 27 minutes then hourly, at least once, and log every attempt (**Settings › Sent
  emails**, `GET /api/v1/mail/log`, kept 30 days). Pipeline runs (failed, or below their quality
  threshold) have their kinds and a one-line hook, `NotificationPublisher.pipelineRunFinished`, for
  the pipelines change to call. Migration V13 (after V12). The reference lab gains Mailpit, demo
  source `mail` (compose profile `mail`; SMTP on 1025, its web page on 8025).

- Design tokens reworked (`ui/src/tokens.css`): page, card and sidebar colours, tints for chips and
  tiles, chart and bar fills, radii, shadows and type sizes, for light, dark (cards `#1b2030` on
  `#11141c`, the sidebar kept dark) and high contrast. Each accent preset has an indicator, button
  fill, link, tint and on-dark shade; the orange buttons use `#c2410c` so white text keeps 4.5:1.
  The contrast check resolves `var()` and now covers 692 pairs (sidebar, hero, chips, tiles, fills
  against their track, the New badge, every accent).
- Inter (400, 500, 600) and Manrope (700, 800) are bundled from `@fontsource/inter` and
  `@fontsource/manrope` 5.2.8, Latin subset only, and listed in the UI's third-party notices.
- Shared components in `ui/src/components/ui`: Card, PageHeader, StatTile, IconTile, StatusChip,
  SeverityChip, DataTable, Switch, EmptyState, Tabs, Button, ButtonLink, Meter, ScoreRing,
  FilterChips. Every page uses them; plain elements and older class names are styled to match.
- The shell: the sidebar's entries live in `ui/src/nav.ts`; an icon rail under 1200 px and a modal
  drawer under 768 px. The search's submit button sits inside the field, which removes the 15 px
  sideways scroll at 375 px. The user menu holds the theme switch, Settings and Sign out.
- Home is built from existing endpoints (sources, source quality scores, open issues, rules, AI
  status). The 14-day quality trend shows an empty state, because the server keeps only the latest
  scores. **Recent pipeline runs** lists the latest run of each pipeline, newest first, from
  `GET /api/v1/pipelines`. The **Notifications** card and the bell (`GET /api/v1/notifications`,
  `…/unread-count`, `POST …/read`) stay hidden while those endpoints answer 404, and appear once
  they exist; other failures keep the bell, which tries again at the next poll.
  Notifications show severity in words, unread marks, email delivery (Emailed, Email queued,
  Email failed, and for admins the team addresses), Mark all read, and links to notification
  settings and, for admins, sent emails.
- Asset tabs are kept in the address (`#/asset/<urn>?tab=quality`). Recommended rules are added
  with the existing create-rule call, one per rule, with one summary of successes and failures that
  takes focus. A rule's history comes from `GET /api/v1/quality/rules/{id}/results`.
- Connector settings get a control by kind (switch, list, number, secret), from
  `ui/src/components/settingKinds.ts`; values are still sent as `true` and `false`. Rules have an
  **On** switch instead of Switch on and Switch off buttons; other on/off options (blank text
  counts as missing, open a task, local model server, remove the stored key, local AI assistant,
  import options) are switches too.
- Each home card keeps loading, data and errors apart: a failed call says **Could not load** with
  **Try again**, never a green "nothing open". Open issues are counted up to 500 and shown as
  **500+** beyond that. Scores are fetched for the six source cards shown only, and the score tile
  says it is their average. Sources shows 24 at a time with **Show more**, and a find box past
  eight. Heavy pages (pipelines, rules, AI, tokens, relationships, documentation) load on demand.
- Layout: home pairs runs and sources with the issue and notification lists (which scroll inside
  fixed heights) so the columns end level; Getting set up runs across the page and shrinks to a
  dismissible **All set up** line once done; the connection panel is a modal side sheet (scrim,
  focus kept inside, page scroll locked); fieldsets inside cards are titled sections, not boxes.
- The rule form links to the quality rules guide (**How rules work**). The documentation's page list
  puts What's new first. `#/sources?add=1` opens Add a connection.

## 0.0.4 (25 October 2026)

### What's new

- **Pipelines you can run.** Build a pipeline that reads from your sources, changes the rows and
  writes the result to a table or file. Kilnmark checks it before it runs (every mistake with its
  line, and a suggestion when a name is misspelt), runs its nodes side by side within one memory
  limit, and records what each node did and how it ended. A run that fails names the node where it
  went wrong, and a run can be cancelled.
- **Nodes for changing rows.** Filter rows, compute new columns, pick and rename columns, look up
  values in another input, total and count by group, combine inputs, remove duplicates, send rows
  down different paths and keep the first rows. Conditions and new columns are written in one
  expression language, checked against the columns before the pipeline runs, with the position of
  any mistake and a suggestion.
- **Writing to databases and files.** A pipeline can write to PostgreSQL or SQL Server tables
  (create, add to, replace or update by key) and to CSV or Parquet files in folders and S3
  storage. Sources stay read-only until an admin turns on **Allow writing** for one. What a
  pipeline writes is profiled as it is written and joins the catalogue with its columns and where
  it came from, so the next pipeline starts from what is known.
- **The Pipelines page.** Under **Pipelines**, a YAML editor that checks as you type, a graph of
  the nodes with the columns leaving each, every saved version with who saved it and why, and each
  run with what every node did and what was written.

### Details

- **Pipelines run as graphs (engine, ADR 0022):** `KilnGraphRunner` runs a pipeline graph with
  every node on its own virtual thread and a bounded row set per edge, inside one memory limit.
  An output read by several nodes gives each its own copy; a port such as union's `in*` takes many
  inputs (`NodeContext.inputs`, `RowSet.takeAny`); rows on an unconnected output are dropped
  (`NodeContext.emit`). A failure stops the whole run and names the node where it started; a run
  in which every node waits on another fails after 120 seconds with a message naming them; run
  results count the rows each node was given, per edge and per port, and say how each node ended
  (finished, ended early, stopped by the engine, or failed). Nodes can read the columns of an
  input that sends no rows (`NodeContext.inputSchema`). Scanning's linear runner now runs as
  a graph too, and a node that stops before its input ends no longer leaves the node before it
  waiting.
- **Pipeline YAML is read and checked** (`kilnmark: pipeline/v1`): every problem with its line and
  column (unknown keys, node types and settings with suggestions, missing or doubled inputs,
  loops), and each node's columns worked out before anything runs, without opening a connection.
  Settings that are text keep exactly what was written (`asset: 2024.10` stays `2024.10`);
  numbers with more than 1,000 digits, or a decimal point more than 1,000 places away, are refused.
  Read with SnakeYAML Engine 3.2 (Apache-2.0); anchors, aliases, tags, duplicate keys and files
  over 1,000,000 characters are refused.
- **Pipeline nodes and expressions** (new module `kiln-nodes`): `filter`, `compute`, `select`,
  `lookup`, `aggregate`, `union`, `dedupe`, `route` and `limit`, each with its settings schema,
  checks and output columns for validation, and one expression language for conditions and
  computed columns: SQL-like operators, `case`, `cast`, SQL null rules, 22 functions and the
  aggregates `count`, `count_distinct`, `sum`, `min`, `max` and `avg`. Expressions are parsed by
  hand and type-checked against the input columns (`boolean`, `int64`, `float64`, `decimal`,
  `utf8`, `date`, `timestamp` in UTC; numbers widen, anything else needs `cast`), with errors
  giving the position and a suggestion; they are worked out a batch at a time into Arrow vectors.
  Limits: 10,000 characters, nesting 64 deep, comparisons that do not chain, text values up to
  1,000,000 characters, dates in years -9999 to 9999. Regular expressions refuse patterns that
  repeat a repeating group (unless each repetition starts with its own separator), back-references
  and comment mode, cap values at 100,000 characters (2,000 for patterns that repeat a group),
  catch stack overflows and budget the work per value and per batch; `like` is capped too.
  `lookup`, `aggregate`, `dedupe` and `union` stop at `memory_limit_mb` (256 by default) with a
  message saying what to change. `lookup` takes waiting main rows only when its lookup side is
  quiet, `union` reads whichever input has rows, and `limit` stops reading once it has its rows.
  Inputs that send no rows use the columns the pipeline check worked out, so an aggregate without
  `group_by` writes one row and union and lookup keep their declared columns.
  Guide: **Pipeline nodes and expressions**.
- **Writing from pipelines** (docs/guide/writing-data.md): PostgreSQL, SQL Server, folder and S3
  sources can now be written to by a pipeline's `write` node (`Connector.writer`, capability
  `WRITE`). Tables: modes `create`, `append` (every input column checked against the table
  first, all mismatches named), `replace` (filled under a temporary name and swapped in one
  transaction, waiting at most `lock_timeout`; grants and the owner kept, indexes, constraints and
  triggers not; refused for views, partitions, inheritance, anything else depending on the table,
  and row-level security unless `allow_dropping_row_security` is set) and `upsert` by `key`
  (PostgreSQL `ON CONFLICT`, which needs a unique index on the key, INCLUDE columns allowed; SQL
  Server `MERGE … WITH (HOLDLOCK)`). PostgreSQL loads with `COPY`;
  SQL Server with batched inserts, bulk-copied into tables the node creates. One transaction per
  run, or `commit_every` rows (a failure then keeps the committed rows, and says so). The login's
  rights are checked first and a refusal names the grant to ask for. Files: CSV (RFC 4180, UTF-8,
  `delimiter`) and Parquet (Snappy, ZSTD, GZIP or none) to a path inside the source, written to a
  temporary name and renamed (folders) or uploaded and completed at the end (S3); existing files
  need `overwrite: true`, and paths scans would not find need `allow_unscanned: true`. A created
  or replaced table, or a file, is profiled in the same pass and reported for the written asset
  (`SCHEMA`, `ROW_COUNT`, `PROFILE`, `SAMPLE`) once the write has succeeded; an append or upsert
  reports only the rows it wrote (`ROW_COUNT`, `complete=false`, `appended=true`), so the table's
  own profile is kept. Each writer declares its settings (`Connector.writeOptions()`, as
  `SettingSpec`s; `profile_output: false` skips the profile), `Connector.writtenAssetPath()` gives
  the catalogue path of what is written (below an S3 source's prefix), and `WriteOptions.profiling`
  carries the source's own profiling settings, which the writer's profile follows. A profiling
  failure never fails a write: `ROW_COUNT` then says `profiled=false` with `profile_error`. Replace
  also refuses tables a publication names (and, on SQL Server, replicated or change-tracked tables)
  and tables whose row-type array or owned sequences others use.
- **Parquet is written by Kilnmark's own encoder** (`ParquetEncoder`, from the Apache Parquet
  specification): Hardwood only reads, and parquet-java needs Hadoop (ADR 0015, amended). No new
  dependency: snappy-java and zstd-jni, already shipped for reading, are now compiled against,
  and the PostgreSQL driver the server ships is compiled against for `COPY`.
- **Profiling no longer fails on an empty piece of text** (a quoted `""` in a CSV file), on a
  column whose values are all NaN, or on 8-bit and unsigned integer, 256-bit decimal and large
  text columns, and reads dates, times and timestamps in any unit.
- **Pipelines can be saved, checked and run** (`/api/v1/pipelines`, `/api/v1/pipeline-runs`,
  `/api/v1/node-types`, `/api/v1/lineage`): every version is kept with who saved it and why, saves use
  `If-Match` on the latest version number (409 when someone saved since), and YAML is checked as
  it is written, with problems by line and each node's columns. Runs are queued as jobs of kind
  `pipeline`, run once by a worker, recorded node by node (how each node ended, rows in and out per
  port, peak memory, error) and can be cancelled. The node catalogue includes kiln-nodes'
  transforms. Viewers read; stewards edit and run.
- **Reading and writing sources in pipelines:** the `read` node reads a saved source's table or
  file; the `write` node writes to a source only when an admin has set its new **Allow writing**
  setting (`allow_writing`, off by default; stewards cannot change it). Allowed hosts and the
  role of whoever asked are checked again when a run starts, and no message carries a source's
  secret. A write node takes exactly the settings its source's writer declares, written beside its
  other settings and checked when the pipeline is, and nothing that says where or as whom to
  connect; the source's profiling settings (with key-like columns' sketches for relationship
  discovery) go with it, and a writer that could not profile leaves a note on the run. What a write
  wrote is registered in the catalogue from what the writer reports, with the container, name and
  key its connector gives, under the URN a scan would give it, with its lineage from the assets read
  (under the URNs the catalogue records them by), under a run of kind `pipeline` that source run lists do not show
  (migration V12; `kilnmark.pipelines.max-yaml-kb`, `memory-limit-mb`, `row-set-capacity` and
  `wait-for-target`). While a source allows writing, only admins change where it points, and a
  write's settings cannot redirect it. A pipeline runs once at a time, and one run at a time writes
  each table or file. Viewers see that a node failed but not its message, which may quote the data;
  for runs touching columns marked sensitive, only admins see it.
- **Pipelines page:** a list with Run now, and for each pipeline a YAML editor that checks as you
  type (choosing a problem moves to its line), Save with a message, a graph of the nodes with the
  columns leaving each, versions and runs with a per-node breakdown. The guide "Building and
  running a pipeline" (docs/guide/pipelines.md) walks through it.

## 0.0.3 (25 October 2026)

### What's new

- **Ask Kilnmark.** An **Ask** panel on every page (Alt+A) answers questions from the
  documentation, in a few sentences, with links to the exact sections it used. It works with a
  local model, and without one it lists the matching sections.
- **Documentation inside the app.** The user guides and this changelog open in the app, under
  **Documentation**, and the running version links to **What's new**.
- **Quality rules without the jargon.** Pick the table and column from lists, fill in plain fields
  instead of JSON, preview on the sample, and edit rules in place. Two people editing the same rule
  can no longer overwrite each other.
- **Find local models.** The AI models page finds Ollama, LM Studio and the bundled model servers
  on this machine or its container, lists their models, and shows whether each runs on the GPU.
  When something cannot be reached, it says why and what to change.
- **Looks and branding.** Light, Dark, High contrast or System themes, accent colours and density,
  chosen by each user. Consistent icons, letter tiles for each product, a compact connection
  gallery, and a folder where you can drop your own logos.
- **Sensitive data stays hidden.** Values of columns marked sensitive are hidden from everyone but
  admins wherever they could appear: previews, rule results, drift, documents, search and the MCP
  server. Search stays fast on large catalogues, however common the words.
- **Sign-in and roles.** Every user signs in with an API token, as a viewer, steward or admin.
  Admins manage tokens under **Settings**.
- **Adding connections is guided.** Pick a connector from a gallery grouped by category, test the
  connection, choose which schemas or folders to scan, and preview the data before scanning.
  Sources can be edited and deleted.
- **More kinds of source.** SQL Server, folders of files, and S3-compatible object storage. Excel
  sheets are read as tables.
- **Documents are read.** PDF, Word, Excel, PowerPoint and email files: their text is searchable
  and readable page by page on the asset page.
- **Browse and relationships.** A tree of sources, schemas, folders and tables, and schema
  diagrams showing how tables relate, from declared foreign keys and from the data itself.
- **Data quality.** Every scan scores each table in eight quality dimensions with no set-up. Value
  patterns, rules with previews, drift between scans, sample rows with sensitive columns masked,
  and an issue list for stewards. Contracts export in open formats (ODCS, OpenLineage).
- **AI, local first.** Add local or hosted models; each source decides what may leave the
  network. Search by meaning, ask questions in plain English, and connect outside AI assistants
  through the MCP server. Every model call is recorded.
- **For administrators.** A job queue, health checks, and interactive API documentation at
  `/api/docs`.

### Details

- **The assistant, step 1** (docs/design/assistant.md): an **Ask** panel in the header (Alt+A) on
  every page answers questions from the documentation that ships with the server, in a few
  sentences, citing sections as [n] links that open the in-app viewer at that section; the
  conversation stays while moving around and is cleared on sign-out. New AI feature `assistant`
  (chat), routed under AI models; without it, or when the model fails or the server allows only
  local models, the panel lists the best matching sections. `POST /api/v1/assistant/ask`, `GET
  /api/v1/assistant/sections`, `POST /api/v1/assistant/feedback` (**Yes, it helped** or **No**;
  kept for `kilnmark.ai.usage-retention`) and `GET /api/v1/assistant/feedback` (admins). At
  start-up the guides and changelog, now bundled in the server jar too, are split at `##` and
  `###` headings into sections with the viewer's anchors and stored in `knowledge_section`
  (migration V11, with `assistant_feedback` and `ai_usage.domains`); only changed sections are
  written again, and sections are embedded in the background when an embedding model is set. The
  changelog's Unreleased part is shown in the viewer but not answered from. Retrieval is
  PostgreSQL full-text search weighted by word rarity, plus nearest embeddings, with a boost for
  the page you are on. The prompt is sized to the model profile's context (4,096 tokens when it
  does not say), the conversation shortened newest first, with one smaller retry if the model
  still refuses it for length; the assistant never marks a shared chat model as down. The model
  gets the sections as data with no tools, and every citation is checked against the sections
  sent. Questions are limited to 2,000 characters (after Unicode normalisation) and 20 a minute
  per token. The MCP server gains `search_docs`. Guide: **Asking Kilnmark**. Evaluation set
  `tests/ai-eval/docs.json` (22 questions) runs in `:server:aiEval`. The UI build needs Node.js
  22.12 or later (or 20.19), as Vite does.
- **Find local models** on the AI models page (`POST /api/v1/ai/discover`, admins only): looks for
  Ollama and LM Studio on the machine running Kilnmark or its container
  (`host.containers.internal`, `host.docker.internal`, `localhost`) and for the bundled Compose
  model services, or checks one typed address, and lists each server's models with size and GPU
  use as the server reports it (Ollama: on GPU, split with the share on GPU, CPU only, not loaded
  yet). **Use this model** fills in the add-model form, and a Base URL that matches a found server
  offers its models to choose from. Failures are explained in plain words with what to do, such as
  setting `OLLAMA_HOST=0.0.0.0:11434` and keeping the port closed in the firewall. Only
  model-server paths are called, after the address rules, with short timeouts, no redirects and no
  response bodies returned. Kilnmark itself never looks for a GPU.
- **`host.containers.internal` and `host.docker.internal` are on the default
  `kilnmark.ai.local-hosts` when Kilnmark runs in a container** (ADR 0016 amendment), counting as
  local only while they resolve to loopback, private addresses or 169.254.1.2. The image sets
  `KILNMARK_IN_CONTAINER=true`.
- **Rootless Podman 5.3+ (pasta):** in a container, 169.254.1.2 is accepted as a model address
  only when reached through those two names; every other link-local and metadata address stays
  refused, including IPv4-mapped IPv6. Model addresses with an IPv4 address in an unusual form
  (leading zeros, hexadecimal, a single number, fewer than four parts) or a host name ending in a
  dot are refused with a 400.
- **Themes and appearance:** every signed-in user chooses, for themselves under **Settings**,
  **Appearance** (now open to every role), a theme (System, Light, Dark, High contrast), an
  accent colour (six presets, each with a shade per theme) and a density (Comfortable or Compact);
  kept in their browser. The top bar has a quick theme switch. Every colour is now a token in
  `ui/src/tokens.css`; `npm run build` checks every theme and accent against WCAG AA
  (`npm run check:contrast`). Schema diagrams, score and heat bars and badges follow the theme
  (diagram arrowheads now take the edge's colour); Windows contrast themes and reduced motion are
  respected.
- **Icons:** Tabler Icons (MIT, pinned 3.48.0, only the icons used are bundled) for categories,
  connector families, file formats (CSV, JSON, XML, Parquet, Excel, PDF, Word, email and more) and
  the browse tree, mapped by name in one module.
- **Connector tiles:** products show letters on a tint (`Pg`, `SQL`, `S3`), generic connectors an
  icon; in the gallery, the sources list, a source's page and the browse tree. Connector SPI:
  `monogram()` (default none); `GET /api/v1/connectors` gains `monogram`, and planned connectors
  in the catalogue gain `id` and `monogram`. No vendor logos are shipped.
- **Branding folder** (docs/guide/branding.md): `kilnmark.branding.dir` (default
  `/etc/kilnmark/branding`) holds a `manifest.json` with the organisation's product name and logo
  and logos, brand colours and labels per connector, for logos the customer is licensed to use.
  Served without sign-in at `GET /api/v1/branding` and `/api/v1/branding/files/{name}`: only
  listed SVG, PNG and WebP files up to `kilnmark.branding.max-file-kb` (256), content checked, no
  symbolic links or sub-folders, with `nosniff` and a Content-Security-Policy that stops scripts;
  the UI shows them only with `<img>`. Invalid entries are logged and fall back to the tile.
  `compose.yaml` has a commented read-only mount.
- **Third-party notices for the UI:** the build writes the licences of the UI's packages to
  `dist/ui/third-party-notices.txt` (docs/legal/third-party-ui.md).
- **Data quality** (docs/design/data-quality.md, build step 1). Profiling now keeps, over every
  row, each text column's value shapes (`SW1A 1AA` is `AA9A 9AA`; the top 20 with counts and the
  share outside them), detected formats (email, phone, UK postcode, NI number, IBAN, card numbers
  by Luhn, ISO dates and timestamps, numbers and booleans as text, JSON, padded values), blank
  values, and for numbers and dates quantiles every 5%, negatives, values beyond the quartile
  fences and dates after today. A few example values per shape are kept apart from the profile.
- **Samples:** every scan keeps a sample of whole rows (1,000 by default; source setting
  `sample_rows`), chosen by the hash of the primary key (or of the whole row), so the same rows
  are chosen every time; files use reservoir sampling seeded per asset. Samples are replaced on
  each scan and deleted with the asset; cells are cut at 200 characters and a sample holds at most
  `kilnmark.quality.sample-max-mb` (8 MiB). Source setting `keep_samples=false` keeps no sample
  rows, example values, most common values or failing rows (numeric statistics such as min, max
  and quantiles are still kept), and removes those already stored as soon as it is seen. Database
  tables larger than the source setting `table_sample_above_rows` are profiled from a
  `TABLESAMPLE`; the profile and any rule read through it say they are estimates, and the
  catalogue keeps the database's row estimate. Viewing samples, examples, failing rows and the
  values in measures, drift and exports needs the steward role; viewers get words, counts and
  shares. Columns a steward marks sensitive are masked (in samples, examples, failing rows, the
  asset's profile and exports) unless an admin unmasks them; only an admin can unmark one.
- **Automatic pre-analysis** after each scan, with no extra read: measures for completeness,
  uniqueness, validity, consistency, accuracy (estimated), timeliness and volume, each with its
  evidence; suggested regular expressions for a column's dominant shapes; stewards accept shapes
  with one click, which makes a validity rule.
- **Rules:** not null, unique, allowed values, matches pattern, in range, freshness, row count and
  its change, references and custom SQL, each tagged with a dimension, with warn and fail
  thresholds as a share or a count. Actions: status, a steward task (the new **Issues** list),
  and webhooks to URLs on the administrator's allow-list (`kilnmark.quality.webhook-allow-list`).
  They run after every scan and on demand through the job queue (jobs of kind `quality`): as SQL
  in PostgreSQL and SQL Server, each check in its own transaction (read-only on PostgreSQL) with a
  time limit (`kilnmark.quality.query-timeout`), and while streaming files (regular expressions
  bounded in time and length). Regular expressions are limited to a subset Java and PostgreSQL read
  alike. Results keep the query that lists the failing rows and a few failing rows; database error
  text is shown to stewards only. Rules preview against the sample before saving, and are
  recommended from the profile without AI.
- **Custom SQL rules are off by default** (`kilnmark.quality.custom-sql=true` turns them on) and
  only admins write, change or run them. They run only on PostgreSQL, never under a login that is
  a superuser or may read server files, run programs or signal other sessions, as one checked
  SELECT (no functions that touch files, locks, settings, sequences or other sessions) in a
  read-only transaction with a statement timeout. They can still read anything the source's login
  can see, beyond the source's schemas: give sources a read-only login.
- **Profile versions and drift:** a version per asset per scan; each is compared with the one
  before and with a chosen baseline for schema, volume, distribution (quantile shift, nulls,
  distinct values, most common values, shape shares) and freshness. Drift events have a severity,
  lower the scores and open steward tasks.
- **Scores** per column and dimension, rolled up to the asset and the source, with reasons.
- **Open formats** (ADR 0020): `GET /api/v1/quality/export/openlineage` and `/export/odcs` export
  an asset as an OpenLineage run event (schema, data quality metrics and assertions facets) and as
  an ODCS 3.1 data contract, validated against the published schemas in the tests.
- **Web UI:** a Quality section on asset pages (scores by dimension, value patterns with share
  bars and examples, accept a pattern, rules and results, drift, sample rows, recommended rules),
  a **Quality rules** page and an **Issues** page.
- **Rule form for stewards:** the form on the Quality rules page finds the asset by name (or takes
  a pasted URN) and offers the columns that suit the check, with their types. Each check has plain
  fields instead of JSON: allowed values one per line, a pattern typed as it is (with the profile's
  suggested expression one click away), minimum and maximum as numbers or dates, hours, rows, a
  percentage, and the referred-to asset found by name in the same source. The JSON parameters stay
  under "Advanced: edit as JSON", which can take over from the fields. Rules are edited in place
  (**Edit**, saved with `PUT /api/v1/quality/rules/{id}`), keeping their results and any dimension
  set through the API. A `PUT` with `If-Match` set to the rule's `updatedAt` is refused (409) when
  someone else changed the rule since, and the form sends it, so an edit never undoes another
  unseen. `GET /api/v1/search` takes `source` to keep to one source's assets. Values held back
  for a sensitive column (`{"hidden": true}`) are shown as hidden and sent back unchanged unless
  replaced; admins load rules unmasked, and recommendations without their values have no
  one-click Add.
- **Documentation in the app:** the guides in `docs/guide` and this changelog (as What's new) are
  bundled into the web UI at build time and shown under **Documentation**, so they work offline.
  Each section has its own address, `#/docs/<page>/<section>`, from its heading as GitHub makes
  anchors. The navigation shows the running version with a link to What's new. Markdown is
  rendered with marked 18.1.0 (MIT); HTML inside it is shown as text, never run.
- **Database:** migration V9 adds profile versions, samples, rules, accepted patterns, quality
  runs, results, measures, scores, drift events and issues, a `sensitive` flag on fields, and
  lets the job queue hold jobs of other kinds.
- **Sensitive columns, after review:** every value drawn from a column marked sensitive is masked
  for anyone who may not unmask it, stewards included, through one check per asset: measure
  evidence (range, fences, usual values, latest timestamp), drift detail, the allowed values and
  range bounds of its rules and recommendations, failing-rows queries and database error text, the
  ODCS export's rule values, the profile's histogram bounds, live previews of the catalogued table
  or file, and the MCP server's `get_asset`, which always masks. Shapes, counts and shares stay. Drift,
  rules, results and recommendations take `unmask=true` from an admin. Embedded text and prompts were
  checked: names, types and descriptions only, never values.
  - A rule on a sensitive column cannot be previewed by anyone who may not unmask it, and its results
    give counts only (no failing rows or query): failing rows would show which rows hold which values.
    Custom SQL's text, like its failing query, is hidden while the asset has any sensitive column. A
    freshness result on a sensitive timestamp says only whether the latest value is within the limit.
  - Saving a rule as shown keeps what was masked: parameters `{"hidden": true}` keep the stored ones
    (same column and type only, otherwise 400) and webhook `"(set)"` keeps the stored webhook; both
    markers are refused on new rules and previews.
  - Live previews find marks by the saved source and the connector's own path, and files and objects
    are read only under the path the listing gives them (no `a//b`, `./` or `../` spellings). For a
    source with marks, only admins may change how it is read (folders, patterns, header rows,
    formats) or preview such changes; stewards still preview new connections and edit descriptions.
  - A workbook's text holds its sheets' cells: when a sheet has a sensitive column, the workbook's
    text, find, chunks and search snippets are for admins only, and others' searches do not match
    its text at all; the same up the chain (a mail whose attached workbook's sheet is marked).
  - Marks are kept apart from the column rows a scan replaces (migration V10, `sensitive_column`), so a
    dropped or renamed column keeps its mark and the values stored under its name stay masked; a
    detected rename carries the mark to the new name.
  - One request-scoped `Sights` bean decides masking for every endpoint and MCP tool, and a test over
    the source tree fails if one shows values without it.
- **Freshness rules** count the values they could not read as a date or time, and say so.
- **Webhooks, after review:** a webhook path may not hold a semicolon, raw or encoded (`%3b`), so a
  segment such as `..;x`, which some servers read as `..` once they drop its parameters, cannot
  climb out of an allowed prefix. Percent-escapes in the path are allowed only for letters, digits,
  `-`, `_` and `~` (so no `%25`, `%00` or overlong UTF-8 such as `%c0%ae`), the URL must be printable
  ASCII, and a written-out default port (`:443`, `:80`) matches an allow-list entry without one.
  Checked when a rule is saved and again before each call; a call refused then is noted on the
  rule's result.
- **Custom SQL, after review:** refused under a login that may reach other databases (one that may
  execute dblink's functions or use a foreign server) and on any database with a user mapping or a
  foreign table, which a view owned by another role can reach, since a read-only transaction does
  not hold on the other side. The refusal names the fix (for dblink, a revoke of its own functions by
  name, never of a whole schema). dblink and postgres_fdw function names are refused in the SQL as
  well.
- **Allowed hosts, after review:** a saved source's hosts are checked against
  `kilnmark.sources.allowed-hosts` again just before a scan, quality run, preview, listing, test or
  on-demand read connects, not only when the source is saved, so a narrowed list applies at once;
  the job fails saying which host is no longer allowed.
- **Search, after review:** keyword search stays quick however common the words ("id", "the", a word
  in most documents). It gathers a fixed number of candidates through indexes (exact names, the
  nearest names in order of distance, matching words, alike field names, a sample of matching
  document chunks) and ranks only those, instead of ranking every match before the limit; snippets
  are chosen from a document's first matching chunks. Where more match alike than are taken, a
  bounded pool (four times as many) is taken through the index, then the first of it by URN, so
  matches that sit at the end of the table cannot make a branch walk it; matches are found through
  the indexes only. A search kept to sources is kept to them in every branch, before any limit.
  Exact names now always come first, then the closest whole names. Migration V10 adds GiST trigram
  indexes on asset names and expression indexes for exact names, has the asset words index take
  entries at once (no pending list), and adds `sensitive_column`. Developer databases that applied
  an earlier draft of V10 (`V10__bounded_search`) need `flyway repair` or a fresh database.

- **Sign-in with API tokens:** every API call needs a token (`Authorization: Bearer km_...`)
  with a role: viewer (read), steward (also start scans) or admin (also add sources and manage
  tokens). Public without a token: `GET /api/v1/info`, `/q/health` and the API description
  `/q/openapi`. Tokens are shown once and stored only as SHA-256 hashes; they can expire and be
  revoked at once. The first start writes a 24-hour admin token to `admin-token` next to the
  secret key (use it to create a permanent one), or takes one from `KILNMARK_ADMIN_TOKEN`.
  `KILNMARK_AUTH_MODE=off` turns sign-in off for a lab, and only on a loopback address.
- **Web UI:** a sign-in page, sign-out, and an **API tokens** page for admins (create with a
  role and expiry, copy the new token once, revoke). Viewers no longer see controls they cannot
  use. Pages no longer scroll sideways on phones.
- **Job queue:** scans are queued in PostgreSQL and run by any process with the worker role, so
  api and worker can run apart. Workers wake on `LISTEN/NOTIFY` and also poll, run
  `kilnmark.worker.concurrency` scans at a time (default 2), never scan one source twice at
  once (asking again returns the scan in progress), recover scans left by a worker that
  stopped, and finish running scans before shutting down (Compose now allows 60 s for this).
- **Database:** migration V4 adds the `api_token` and `job` tables, and marks scans left waiting
  or running by an earlier version as failed, so they can be started again.
- **Sources:** SQL Server (kind `sqlserver`): tables and views in user schemas,
  with `MS_Description` descriptions and primary keys; every SQL Server type read, with
  `datetimeoffset` as a UTC timestamp, `money` as an exact decimal, and geography, geometry and
  hierarchyid as text. Reads take no shared locks by default (setting `isolation`:
  `read-uncommitted`, `snapshot` or `read-committed`). Uses Microsoft's JDBC driver (MIT).
- **Sources:** a JDBC URL holding a password is refused; enter it as the source's password, which
  is encrypted.
- **Fixed:** a schema whose name holds `_` (such as `a_b`) no longer picks up the columns of a
  same-named table in a schema it matches as a pattern (`axb`); PostgreSQL schemas named `sys` or
  `guest` are scanned.
- **Fixed:** a read stopped early by a failing node now ends at once instead of reading the rest
  of the table.
- **Files and object storage:** two new kinds of source. `files` scans a folder the server can
  read; `s3` scans a bucket on AWS or any S3-compatible store (VersityGW, MinIO, Ceph). CSV, TSV,
  JSON, JSON lines and Parquet files are catalogued with their fields and profiled like tables;
  every other file is recorded as a document (size and time) without being read.
- **Administrators decide what sources may reach:** folder sources only inside the folders listed
  in `kilnmark.files.roots` (none by default, so folder sources are off until set); S3 sources need
  their own access key unless `kilnmark.s3.allow-default-credentials` lets sources on AWS use the
  server's identity.
- CSV: delimiter (including Excel's `sep=` line), quote and encoding found automatically (UTF-8,
  or windows-1252 when a file is not valid UTF-8; a source's `encoding` setting overrides); column
  types (integer, decimal, boolean, date, time, timestamp) inferred from the first 1,000 rows. A
  value that does not parse later in the file is read as null and counted (`unparsed`), never a
  failed scan.
- Objects are streamed: a catalogue reads only the first megabyte of text files and the footer of
  Parquet files, and a sampled read stops the transfer when it has enough rows.
- A corrupt, too deeply nested or unsupported file (a Parquet codec such as Brotli) is recorded as
  not readable with the reason, and the scan goes on.
- Parquet is read without Hadoop, through Hardwood, with one shared, bounded decoding pool
  (`kilnmark.files.parquet-threads`, `kilnmark.files.parquet-chunk-mb`); nested values become JSON
  text. S3 through the AWS SDK's S3 module only (ADR 0015). 19.3 MB of jars added.
- **Fixed:** profile statistics above about 9.2 trillion are no longer capped.
- **AI models (ADR 0016):** admins add models under **AI models**: local ones (llama.cpp's
  llama-server, vLLM, Ollama or any OpenAI-compatible server, marked local) and hosted ones with an
  API key (OpenAI, Azure OpenAI, Anthropic, Google Gemini). No restart needed. API keys are
  encrypted and never shown again (only their last four characters), and are asked for again when
  a model's address moves to another host. Link-local and cloud metadata addresses are refused. **Test** says in plain words
  whether a model works and what to fix. Each feature (search by meaning, asking in plain English,
  describe, classify) uses the model an admin chooses, or is off and works without AI.
- **What may leave the network:** every model call passes one check. A source's AI rule
  (`ai_egress`: `local_only` by default, `allowed` or `none`) decides which models may see it, and
  hosted models can be forbidden for the whole server by an admin, or by whoever runs it
  (`KILNMARK_AI_HOSTED_MODELS=forbidden`); then a model counts as local only if its address is on
  a local network (`KILNMARK_AI_LOCAL_HOSTS`). A refused call never reaches the model and says why.
- **AI usage:** every model call is recorded (feature, model, source, tokens, time, outcome, who
  asked), refused ones too, without prompt or answer text; admins see totals by day and model.
  Records are kept 90 days (`kilnmark.ai.usage-retention`).
- **Search by meaning:** after each scan, assets (names, descriptions, fields and types, never
  values) are embedded with the chosen embedding model into pgvector, skipping unchanged ones; the
  scan's run says how many. Search combines words and meaning (reciprocal rank fusion), shows how
  each result matched, and falls back to words, saying why (without naming the model). A search
  waits about 10 seconds at most for the model, and skips one that failed in the last 30 seconds.
- **Ask a question:** the search page has an "Ask a question" mode. A chat model turns the
  question into a search (words, kinds, sources, field names), which is shown with the results,
  including anything it could not use. Without a chat model the question is searched as typed.
- **MCP server:** outside AI assistants can search and read the catalogue at `/mcp` (Streamable
  HTTP) with a viewer API token: `search_assets`, `get_asset`, `list_sources`. Assistants see
  sources whose AI rule is `allowed`; tokens an admin marks as used by a local assistant also see
  `local_only` ones; `none` sources are never shown.
- **Compose:** profiles `ai` (CPU) and `ai-cuda` (NVIDIA GPU) run llama-server with a small chat
  model and an embedding model; `ai-download` fetches them into the `models` volume from fixed
  revisions, checked by SHA-256. Images pinned by tag and digest.
- **AI evaluation:** `./gradlew :server:aiEval` scores a running server's search (recall, MRR)
  and question answering against a synthetic golden set over the lab, and fails if a score falls
  below the baseline; not part of the build.
- **Database:** migration V6 adds `model_profile`, `ai_route`, `ai_setting`, `ai_usage` and
  `asset_embedding`, and `api_token.local_assistant`. 2.7 MB of jars added (LangChain4j provider modules, Quarkus MCP server), all
  Apache-2.0.

- **Managing sources:** stewards can edit a source (display name, settings, password) and delete
  it. A source's name is its fixed ID, part of every asset reference, so it cannot change; the new
  display name can. A blank password keeps the stored one, unless the URL, endpoint or user name
  changes. Delete is refused while a scan waits or runs, and otherwise removes the source's assets,
  fields, profiles, runs and graph nodes in one transaction.
- **Settings every source has:** `description`, `ai_egress` (`local_only` by default, `allowed` or
  `none`), and `include` / `exclude` glob patterns (ignoring case) that choose tables or files for
  every kind of source. The folder connector's own `include` setting is now this common one, and
  now ignores case.
- **Test connection** for a saved source or a form not yet saved, within 15 seconds, in plain words
  and never repeating the password.
- **Choose what to scan from a list:** schemas (databases) or top-level folders (folders and
  buckets) with how many tables or files each holds, from metadata or a listing. Before a scan the
  UI warns if it would find nothing.
- **Bulk import, version 2:** run the same file again to create, update or leave sources unchanged
  (reported per row); `onError=skip` applies the right rows; `host`, `port` and `database` columns
  build the URL; `display_name` and the common settings as columns; `remapHosts=true` rewrites
  host names with the administrator's `kilnmark.sources.host-map` (for example localhost to
  host.docker.internal); `test=true` and `scan=true` test each source and queue scans, with
  progress the UI shows. Files in the earlier format still import. `lab/sources.csv` registers
  the lab.
- **Source page** in the web UI: settings (never the password), what it scans, run history with
  durations, attempts and errors, and edit, test, scan and delete.
- **Readiness and liveness:** `/q/health/ready` (database, migrations, worker) and
  `/q/health/live`, public and saying only UP or DOWN. The Compose service has a health check.
- **Security:** stewards' checks connect from the server, so an administrator can limit the hosts
  sources may reach with `kilnmark.sources.allowed-hosts`. JDBC URL properties that make a driver
  use the server's own identity or files (`integratedSecurity`, `authentication=ActiveDirectory...`,
  `sslkey`, `sslfactory`, `socketFactory`, `loggerFile`, any `...File`, `...Path`, `...Factory`,
  `...Class`) are refused. Checks give their place back only when they have really ended.
- **Security:** host names come from the database driver's own URL parser; URL properties naming
  another host (`PGHOST`, `server`, `address`...), loosening time limits (`socketTimeout`,
  `loginTimeout`...), using `pg_service.conf` (`service`), or any SQL Server `authentication` but
  `SqlPassword` are refused, whatever braces, quotes or encoding they are written with.
- **Changed:** bulk import's row statuses are now `created`, `updated`, `unchanged` and `failed`
  (they were `ok`, `created` and `error`), and a row naming an existing source updates it instead
  of failing. An update changes only the columns in the file.
- **Changed:** `include` and `exclude` patterns without a `.` match database table names only (with
  a `.`, `schema.table`); without a `/`, file names only.
- **Add a connection:** a gallery of connectors by category, with tabs, search and what each can
  do (discover, read; write and stream to come), including planned connectors and their wave
  (`kilnmark.connectors.show-planned`). Choosing one opens a four-step panel beside it: connect
  (host, port and database build a database URL), test, choose what to scan, save or save and
  scan. Edit uses the same panel. Connectors declare their category, family, capabilities,
  formats, icon and docs section, and a driver descriptor (all bundled today).
- **Preview** any table or file before scanning: the first 100 rows (up to 1,000), read live and
  never stored. Files show what was detected; new file settings `delimiter` and `header` correct
  it for previews and scans.
- **Sources list:** filter and group by category; file sources show the formats found, with counts.
- **Database:** migration V5 adds `data_source.display_name` and the import batch tables.
- **Fixed:** an S3 source with its own access key can be added through the API, the form and bulk
  import; the check that the secret goes with the key no longer misses the secret.

- **Browse:** a new **Browse** page shows the catalogue as a tree: source, then schema, folder or
  bucket prefix, then tables, views and files, with counts, kind labels and icons. Children load
  as a node opens, a hundred at a time; a filter box narrows the tree by name; the tree follows
  the ARIA tree pattern for the keyboard. Choosing a schema lists what it holds and draws it.
- **Schema diagrams:** tables as boxes with their columns (keys marked PK and FK) and lines for
  relationships: declared keys solid, proposals dashed with their confidence, rejected ones hidden.
  Pan, zoom, fit, automatic layout; at most 60 tables, with a filter or one table and its
  neighbours (one or two steps, across schemas and sources) for larger schemas. The same
  relationships are listed in a table beside it. Uses @xyflow/react and @dagrejs/dagre (MIT,
  ADR 0019), loaded only when a diagram is shown.
- **Relationships:** scans of PostgreSQL and SQL Server record declared foreign keys, including
  multi-column keys in key order and keys between schemas. After every scan Kilnmark proposes
  joins nobody declared, within and across sources, from column names, types, unique targets and
  value containment estimated from stored HyperLogLog sketches; on the lab,
  `billing.transactions.customer_id` to `crm.customers`. Stewards accept or reject proposals on
  the new **Relationships** page, the asset page or below a diagram; decisions survive rescans.
- **Asset page:** a summary with source and schema links, the asset's relationships in and out,
  and per-column statistics: open a column for nulls, distinct values, quartiles, mean, a small
  histogram, lengths and most common values.
- **API:** `GET /api/v1/browse/sources`, `.../browse/sources/{name}/containers`,
  `.../browse/sources/{name}/assets?container=`, `GET /api/v1/diagram` (a container, an asset and
  its neighbours, or chosen assets), `GET /api/v1/relationships`, `GET /api/v1/relationships/{id}`,
  and `POST /api/v1/relationships/{id}/accept` or `/reject` (steward).
- **Profiles:** numbers, dates and times gain a ten-bin histogram; key-like columns (identifier
  names and single-column primary keys) keep their distinct-value sketch (about 8 KiB) beside the
  profile for discovery.
- **Database:** migration V8 adds the `relationship` table and `profile.hll`.
- **Connectors:** `Connector.catalogWithRelationships` (a default method) returns the catalogue
  with the relationships a source declares, and warnings; `AssetInfo` is unchanged. Foreign keys
  that cannot be read leave the scan going, with a warning in its detail.

- Settings: API tokens, AI models and AI usage move under one Settings section, admins only.
- Interactive API documentation at `/api/docs`, served by the server itself (works offline), from
  the OpenAPI document at `/q/openapi`. Calls made from it need an API token (Authorize button).

- **Documents are read, not just listed:** the `files` and `s3` sources read the text of PDF,
  Word, Excel, PowerPoint (current and 97-2003 formats), OpenDocument, EPUB, RTF, HTML, XML, mail
  (`.eml`, `.msg`), plain text and Markdown files, with Apache Tika. Each document gets a profile:
  content type, pages, words, characters, language, title, author, created and modified dates, and
  for S3 the object's owner and tags. A mail message shows from, to, cc, date and subject, and each
  attachment is an asset of its own. No OCR yet (ADR 0017).
- **Excel sheets as tables:** each sheet with a header row is a table inside its workbook, read with
  POI's streaming reader and profiled like any table, with typed columns. Other sheets (notes) are
  recorded with the reason.
- **Search finds words in documents,** ranked below name matches, with a highlighted snippet
  (`snippet` in search results, text with highlight positions, never markup).
- **Reading documents:** the asset page shows a document's profile and its text page by page (slide
  by slide, sheet by sheet), with find; `GET /api/v1/documents/text`, `/find` and `/chunks`.
- **Documents are untrusted input:** limits on size, characters, time (a document still being read
  after 60 seconds is abandoned), nesting depth and zip bombs; one bad document is marked not
  readable with the reason and the scan goes on. Settings `kilnmark.documents.*`.
- **Sources:** setting `keep_text` (default `true`); `false` keeps only documents' properties, and
  their text is read from the source again when someone opens it.
- **Database:** migration V7 adds `document` (profiles), `document_chunk` (text in chunks, searched
  through a `tsvector`) and `asset.parent_urn` (sheets and attachments inside their file), all
  deleted with their asset. The text of documents no longer in their source is deleted on the scan
  that notices.
- **Lab:** synthetic documents (PDF, Word, Excel, mail, Markdown) in the share and the bucket, made
  by `lab/make-documents.py`. 27.4 MB of jars added (ADR 0017).
- **Documents, after review:** Office files are checked for parts that really expand past
  `kilnmark.documents.max-part-mb` (64 MB) before any parser opens them, and sheet rows stop at
  16,384 cells. Healthy documents wait for a reader instead of failing; a read that fails for a
  passing reason (busy, out of time, stopped, source not reachable) keeps what the last scan stored.
  Sheet and attachment names lose control characters. `%` and `#` in file names are escaped in URNs,
  so a file cannot pass for a sheet or attachment. `keep_text=false` now hides text too, unless
  `read_text_on_demand=true` lets stewards read it from the source. Find copes with letters whose case
  changes their length (Turkish İ). Persian, Urdu, Catalan, Marathi and Nepali are no longer
  mistaken for their neighbours.

## 0.0.2 (24 October 2026)

The first working slice: point Kilnmark at PostgreSQL databases, scan them, and search what it
found.

- **Sources:** register PostgreSQL databases one at a time, or many at once from a CSV or JSON
  file (checked row by row, all or nothing, with a dry run). Passwords are encrypted with
  AES-256-GCM before they reach the database; the key lives outside it. Bulk files can refer to
  passwords as `env:VARIABLE` or `secret:NAME` instead of holding them.
- **Scans:** every table and view, with columns, types, comments and primary keys; each one read
  through the Kiln engine and profiled as it streams (rows, nulls, distinct values, ranges,
  lengths, most common values). Two tables at a time; one failing does not stop the scan. Assets
  that disappear from a source are marked removed.
- **Search:** tables, views and columns by name or description, tolerant of partial and misspelt
  words.
- **Web UI:** search from anywhere (Ctrl+K), sources with scan status, asset pages with every
  column's profile. WCAG 2.2 AA automated checks pass in light and dark themes.
- **Kiln engine:** nodes on virtual threads joined by bounded Arrow row sets, per-node memory
  accounting, back-pressure, clean failure and leak reporting.
- **Platform:** Java 25 (ADR 0013), Business Source License 1.1 (ADR 0008), sign-in plan
  (ADR 0014). No sign-in yet: lab use only.

## 0.0.1 (24 October 2026)

Scaffolding: build, CI, containers, reference lab and decision records.
