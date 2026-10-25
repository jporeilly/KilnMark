# Pipelines

Status: design, 25 October 2026. Round 3, step 1: a first pipeline that runs. The canvas
(step 2) and the quality gate with predicted scores (step 3) build on this. ADR 0022 records
the decision to run pipelines as graphs.

A pipeline reads from sources, transforms the rows and writes them somewhere, measuring the data
as it flows. It is saved as YAML, versioned, run by a worker, and every run is recorded step by
step.

## 1. Pipelines are graphs

Today's runner takes steps in a line. Lookups, joins, unions and routing need more than one input
or output, so a pipeline is a directed acyclic graph:

- A **step** has named **ports**: inputs (`in`, or `main` and `lookup`) and outputs (`out`, or
  `out` and `rejected`). A source has no inputs; a write has no outputs.
- An **edge** joins one output port to one input port. One output may feed several inputs: the
  engine copies each batch to every reader (a tee), so each step owns what it takes.
- Each edge is a bounded `RowSet`, so back-pressure and per-step memory accounting work as they
  do today. A step that must see all its input before writing (aggregate, the lookup side of a
  lookup) has a memory limit of its own and fails with a plain message when it is exceeded.
- The graph is checked before a run: ids unique, every edge between existing ports, every
  required input connected, no cycles.
- A failing step stops the whole run, and the run reports the step where the failure started,
  as today.

The linear `Pipeline` and `Runner` stay for scanning and are run as a graph internally.

### The contract (kiln-core, `io.kilnmark.kiln.graph`)

- `PortRef(step, port)`, `Edge(from, to)`, `GraphStep(id, step, config)`,
  `PipelineGraph(name, steps, edges)` with `check()`.
- `StepContext` gains named ports, `input(port)` / `output(port)` with `inputPorts()` /
  `outputPorts()`, and structured settings, `config()`: the step's YAML settings as maps, lists,
  strings, numbers and booleans. `input()` / `output()` remain the `in` / `out` ports.
- `StepFactory`, found through `ServiceLoader` and registered with `StepFactories`: the step type,
  kind (source, transform, sink, gate), label, help, its input and output ports, a settings
  schema (`SettingSpec`) the canvas turns into the step's settings panel, `create(config)`, and
  optionally `outputSchema(port, inputSchemas, config)` so the canvas and validation know each
  step's columns before anything runs.
- `GraphRunner.run(graph, metadata)` returns a `RunResult` with rows per edge and per step.

Added while building the engine (round 3, step 1):

- `StepContext.inputs(port)`: every row set arriving on a port, in the order the pipeline lists
  them. A factory port named with a trailing `*` (union's `in*`) is connected, read and named in
  edges without the `*`, as `in`. `input(port)` on a port with several inputs is an error that
  points to `inputs(port)`.
- `StepContext.emit(port, batch)`: hands the batch to the port, or closes it when nothing is
  connected. `output(port)` returns `null` for an unconnected port; in a graph run it does so for
  any name, since the compiler has already checked the edges against the step type's ports.
- `RowSet.takeAny(rowSets, allocator)` returns the next batch from whichever input has one, with
  its index, or `null` once all have ended; `RowSet.poll(allocator, timeout, unit)` and
  `RowSet.atEnd()` underneath it. A step with several inputs that does not care about order
  (union) must read them this way: reading one to its end first stalls a pipeline whose inputs
  share a step upstream (a route followed by a union), because that step waits for room on the
  input not being read.
- `RunResult.edges()`: `EdgeResult(fromStep, fromPort, toStep, toPort, rows)` per edge, in the
  graph's order. `StepResult` gains `rowsIn`, `rowsInByPort` and `rowsOutByPort`; `rowsOut` is
  the sum over connected output ports, each port counted once however many steps read it. The
  old constructors remain. `RunResult.step(name)` finds one step.

### Running a graph (`KilnGraphRunner`)

- Every step runs on its own virtual thread; each edge is a bounded `RowSet` of the configured
  capacity; each step's memory is a child allocator inside the run's one limit, as before.
- **One output, several readers (the tee).** The step writes to one row set. A copying thread
  takes each batch and gives every reader but the last a copy (its buffers copied whole, so
  nested columns copy too) and the last the original; each reader then moves its batch into its
  own allocator. Copies waiting to be read are accounted to the writing step, in a child of its
  allocator, as any batch waiting on an edge is. One slow reader holds the others back by at most
  the row set capacity.
- **A step that ends early** (it stops before its input ends, like a `limit`) releases its
  inputs: a step writing only to it is stopped without failing; a step that also writes
  elsewhere goes on, and what it still writes to the finished step is read and dropped. A tee
  whose readers have all finished stops in the same way.
- **Failures.** A failing step fails its outputs (the steps after it end, not blamed) and
  cancels its inputs (the steps before it stop, not blamed), along every edge, so the whole
  connected graph stops. Steps not joined to it at all are stopped too. The run's error is the
  first failure in time; a step that did not free its memory is named when nothing failed first.
  A tee that cannot copy (the memory limit) is reported against the writing step.
- **Interruption** of the calling thread stops every step and waits for all of them before
  `run` throws `InterruptedException`.
- The linear `Runner` builds a graph (`a.out -> b.in -> ...`, settings as configuration) and runs
  it with `KilnGraphRunner`. One difference from before: if one step fails and another leaks, the
  run reports the failure, not whichever step came first in the line. A step that ends before its
  input does no longer leaves the step before it waiting for ever.
- **Known limit.** A step that reads one input to its end before another (a lookup reads its
  lookup side first) stalls when both inputs come from the same step and more rows are waiting
  than the edges hold. The compiler adds a note to such pipelines; reading the source twice
  avoids it.

## 2. The YAML format

```yaml
kilnmark: pipeline/v1
name: customer-billing
description: Monthly spend per customer, with orphan transactions set aside.
steps:
  - id: customers
    type: read
    source: lab-db
    asset: crm.customers

  - id: transactions
    type: read
    source: lab-db
    asset: billing.transactions

  - id: tidy
    type: compute
    input: customers
    columns:
      full_name: "trim(first_name) || ' ' || trim(last_name)"
      email: "lower(trim(email))"

  - id: spend
    type: aggregate
    input: transactions
    group_by: [customer_id]
    values:
      total: "sum(amount)"
      last_seen: "max(txn_at)"

  - id: joined
    type: lookup
    inputs: { main: spend, lookup: tidy }
    on: { customer_id: customer_id }
    keep: [full_name, email]
    unmatched: rejected        # rows with no customer go to the 'rejected' port

  - id: orphans
    type: write
    input: joined.rejected
    source: lab-share
    path: quarantine/orphan-spend.csv
    format: csv

  - id: out
    type: write
    input: joined
    source: warehouse
    table: billing.customer_spend
    mode: replace              # create | append | replace | upsert (with key)
```

- `input: <id>` means that step's `out` port; `input: <id>.<port>` names another port;
  `inputs: {port: id[.port]}` connects several. `input` connects the step's only input port; a
  list (`input: [a, b, c]`, or a list under `inputs`) connects several steps to a port that takes
  many.
- Steps are listed in any order; the edges decide the order of work.
- Unknown keys are errors, with the line number, so typing mistakes are caught.
- Secrets never appear: steps name saved sources, and the source holds the credentials.

### Reading and checking (`io.kilnmark.kiln.yaml`)

- YAML is read with **SnakeYAML Engine 3.2** (YAML 1.2, Apache-2.0; its licence is listed in the
  generated third-party notices). Kilnmark builds its own tree from the parser's events, so no
  object is ever constructed from a tag. Scalars follow the YAML 1.2 core schema: `yes` and `on`
  are text, quoted values are always text, `${VAR}` is never substituted.
- Refused, each with its line and column: files over 1,000,000 characters, more than one
  document, anchors and aliases (a pipeline is written out in full, so the canvas can rewrite it),
  tags, duplicate keys, keys that are lists or mappings, nesting deeper than 64 and more than
  100,000 values.
- `PipelineDefinition.parse(yaml)` reads the format line, names, steps, ids, inputs and settings
  with their positions. `PipelineCompiler(stepFactories).compile(yaml)` checks it against the step
  types and returns `Compiled`: `problems()` (line, column, step id, message), `notes()`,
  `schemas()` (columns per step and output port) and `graph()`. Checking never creates a step or
  opens a connection; `graph()` creates new steps for one run, and closes those already made if one
  fails.
- Checks: the format line; unknown top-level keys; step ids (`[a-z][a-z0-9_-]{0,63}`, unique);
  unknown step types, suggesting the closest; settings against the step type's `SettingSpec`s
  (unknown keys with a suggestion, required, kind, choices) and then the factory's `check`, whose
  messages point at the setting they start with; inputs naming unknown steps or ports; every input
  port connected; a single-input port not fed twice, nor a port fed twice by the same step;
  loops, naming the steps in them.
- Columns are then worked out in the order rows flow with each factory's `outputSchema`. A port
  that takes many inputs is given them keyed `in[0]`, `in[1]`, … in the order listed. When a step
  returns null (columns unknown before the run), the steps after it are not checked against
  columns, and one note on that step names them. A step with a problem of its own is skipped
  silently.
- The server registers `read` and `write` itself; the compiler works with any `StepFactories`.

## 3. Expressions

`filter`, `compute`, `aggregate`, `route` and later the gate use one small expression language,
parsed and checked once, evaluated a batch at a time on Arrow vectors. It never runs code.

- SQL-like: column names (quoted with `"` when needed), literals (`'text'`, numbers, `true`,
  `false`, `null`, `date '2024-01-31'`, `timestamp '2024-01-31 09:30:00'`), `+ - * / %`, `||`,
  comparisons, `and or not`, `is [not] null`, `in (...)`, `between`, `like`, `case when … then …
  else … end`, and `cast(x as type)`.
- Functions: `trim lower upper length substring replace coalesce nullif abs round floor ceil
  concat regex_match regex_replace (with time and size limits) now today date_trunc
  date_part parse_date parse_timestamp format_date`. Aggregates in `aggregate` only: `count
  count_distinct sum min max avg`.
- SQL null rules. Types are checked against the input schema before the run: an unknown column or
  a type mismatch is an error naming the step, the expression and the position.
- Later, a read followed by filters and computes on the same database can be pushed down as SQL.

## 4. Steps in step 1

| Type | Ports | What it does |
| --- | --- | --- |
| `read` | → `out` | Reads a table, view or file from a saved source (existing readers) |
| `filter` | `in` → `out`, `rejected` | Keeps rows where the condition is true; others to `rejected` if connected |
| `compute` | `in` → `out` | Adds or replaces columns from expressions |
| `select` | `in` → `out` | Keeps, drops, renames, reorders and casts columns |
| `lookup` | `main`, `lookup` → `out`, `rejected` | Adds columns from the lookup side by key; unmatched rows kept (left), dropped (inner) or sent to `rejected` |
| `aggregate` | `in` → `out` | Groups and summarises |
| `union` | `in` (many) → `out` | Appends inputs, matching columns by name |
| `dedupe` | `in` → `out`, `rejected` | Keeps the first row per key |
| `route` | `in` → named outputs, `out` | Sends each row to the first matching condition's port, others to `out` |
| `limit` | `in` → `out` | First N rows, for trying a pipeline out |
| `write` | `in` → | Writes to a database table or a file in a saved source |

Writing:

- **Databases** (PostgreSQL, SQL Server): modes `create` (fails if the table exists), `append`,
  `replace` (in one transaction: write a new table, then swap) and `upsert` (by key). Batched
  inserts or COPY, a transaction per run unless the target is too big, then per batch with a
  clear message. The write needs a login with write rights; sources are read-only unless an
  admin ticks **Allow writing** on the source.
- **Files** (folders and S3): CSV and Parquet, to a path inside the source, never outside it.
  Writes go to a temporary name and are renamed at the end, so readers never see half a file.

## 5. Running and recording

- Pipelines are stored with every saved version (who, when, message, YAML). Saving checks the
  YAML; an invalid pipeline can be saved as a draft but not run.
- **Run** queues a job; a worker compiles the YAML of that exact version into a graph, resolves
  sources (credentials, allowed hosts, write permission) and runs it.
- Each run records its pipeline version, status, timings, and per step: rows in and out per port,
  peak memory, duration and the error if it failed. A run can be cancelled.
- **Quality on the way:** every `write` profiles what it writes in the same pass and registers or
  updates the target as an asset, with lineage from the assets that were read (OpenLineage
  events), so the next pipeline downstream starts from metadata. Rows per port show where rows
  went.
- Runs respect the same rules as scans: allowed hosts, source egress for AI, masking of sensitive
  columns in previews.

### How the server runs and records a pipeline (decisions)

- **Storage (V12).** `pipeline` (fixed name, display name, description, latest version),
  `pipeline_version` (never changed once saved: YAML, message, author, whether it was valid and its
  problems), `pipeline_run` (one version, status `queued`, `running`, `succeeded`, `failed` or
  `cancelled`, who asked and with which role, error, a JSON result), `pipeline_step_run` (rows in
  and out, rows per port, peak memory, error) and `pipeline_lineage` (written asset from read
  asset, kept when the pipeline is deleted). Deleting a pipeline deletes its versions and runs;
  refused while a run is queued or running.
- **Saving.** Names are `[a-z][a-z0-9-]{0,62}` and never change; the YAML's `name` must match.
  YAML over `kilnmark.pipelines.max-yaml-kb` (256 KB) is refused. `PUT` saves the next version;
  its ETag is the latest version's number and `If-Match` with an older number is refused with 409,
  as rule edits are.
- **Queueing.** `POST .../runs` checks the version again (a source may have stopped allowing
  writes since it was saved) and queues a job of kind `pipeline` with the run, in one
  transaction. A pipeline job is tried once, never repeated on its own: its writes may have begun.
  It holds an advisory lock of its own (keyed by the job), not a source lock, so runs may overlap
  scans of the sources they read; recovery leaves a job alone while its lock is held, and marks
  the run failed when the job is given up.
- **On the worker.** The worker compiles that exact version inside a run context naming who asked
  and their role. The server's `read` and `write` step factories resolve saved sources, decrypt
  their secrets and re-check the allowed hosts only then; `write` also re-checks `allow_writing`
  and that the role is steward or admin. Readers get their connector's own settings (which may
  hold the secret) through a wrapper, never through the YAML or the graph's config. Every message
  a run records has the secrets it resolved blanked out.
- **Profiling writes.** A write in mode `create` or `replace` gets a hidden profile step in front
  of it, so what is written is profiled in the same pass. After a successful run each write's
  target is registered as the scan registers what it finds (asset, fields from the connector, row
  count, profiles, quality profile version and evaluation) under a finished run of kind
  `pipeline` on the written source; source run lists show scans only. `append` and `upsert` only
  add to a target, so its profile is left as it was, with a note to scan it. Lineage links each
  written asset to every read asset upstream of it (`GET /api/v1/lineage?urn=`).
- **Cancelling.** A queued run is cancelled at once and its job taken off the queue; a running one
  is interrupted within about half a second. A worker shutting down marks its runs failed rather
  than queueing them again.

## 6. API and UI in step 1

- `GET/POST /api/v1/pipelines`, `GET/PUT/DELETE /api/v1/pipelines/{name}` (PUT saves a new
  version), `GET .../versions`, `GET .../versions/{n}`, `POST .../validate` (YAML in, problems
  out with line numbers, and each step's output columns), `POST .../runs`, `GET .../runs`,
  `GET /api/v1/pipeline-runs/{id}`, `POST /api/v1/pipeline-runs/{id}/cancel`,
  `GET /api/v1/steps` (the step catalogue with settings schemas).
- Roles: viewers read; stewards edit and run; writes to a source also need that source to allow
  writing.
- UI: a **Pipelines** page (list, last run, run now), a pipeline page with a YAML editor that
  validates as you type and shows problems by line, a read-only graph of the steps (the existing
  diagram library), versions, and runs with a per-step breakdown.

### Step catalogue and seams (decisions)

- `GET /api/v1/steps` lists every `StepFactory`: those on the class path (`ServiceLoader`) and the
  server's `read` and `write`. `write` takes `target`, or `table` and `path` as other names for
  it, and `mode` defaults to `create`, which never overwrites.
- The server reaches the compiler through `PipelineCompilerPort` (adapter `KilnPipelineCompiler`)
  and the runner as a `GraphRunner` bean, both produced in `PipelineCompilers`; tests replace
  the runner with an alternative bean.
- The YAML editor is a plain textarea with line numbers for now; the canvas replaces it in step 2.

## 7. Next

- **Step 2, the canvas:** drag steps from the catalogue, connect ports, a settings panel built
  from each step's settings schema, YAML side by side kept in sync, data previews at any step,
  keyboard operation.
- **Step 3, quality:** the gate step, predicted versus measured scores, AI suggestions,
  version diffs.
- Later: scheduling, parameters, the importer, push-down to SQL.
