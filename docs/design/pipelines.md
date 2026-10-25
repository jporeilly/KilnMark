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
  graph's order: the rows that edge's reader was given. `StepResult` gains `rowsIn`,
  `rowsInByPort` and `rowsOutByPort`; `rowsOut` is the sum over connected output ports, each port
  counted once however many steps read it. Rows dropped because a reader stopped early, or because
  a step failed, are not counted (`RowSet.rows()` counts rows when they are taken). The old
  constructors remain. `RunResult.step(name)` finds one step.
- `StepContext.inputSchema(port)` and `inputSchema(port, index)` (for a port taking many): the
  columns arriving on an input, as worked out when the pipeline was checked, or `null` when they
  were not known before the run. A step can then act on an input that sends no batch: an
  aggregate of no rows writes SQL's one row, a union adds the columns of an empty input, a lookup
  knows the columns of an empty lookup side. The compiler puts them on `GraphStep.inputSchemas`
  (keyed `in`, `main`, `in[0]`, …; a new record component, the three-argument constructor
  remains) and the runner hands them to the step.
- In a graph run `hasInput()` and `hasOutput()` mean the `in` and `out` ports, as they did for a
  step in a line; `inputPorts()` and `outputPorts()` list every connected port.
- `RowSet.fail(cause)` never waits: it drops batches still queued (the run is failing) to make
  room for the end, so a failure always reaches the next step. `finish()` still waits for room.
  `RowSet.put` closes its batch if interrupted while waiting. Added for the engine: `discard`
  (drop a batch without counting it), `isCancelled`, and `progress`, `readerWaiting` and
  `writerWaiting` for the stall watchdog.

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
  inputs. Each step counts its readers that have not finished, over all its ports and the readers
  behind a tee; when the last finishes, the step is stopped without failing. Until then, what it
  still writes to a finished reader is read and dropped (a tee simply stops copying to it).
- **Failures.** A failing step first cancels its inputs (the steps before it stop, not blamed),
  then fails its outputs without waiting (the steps after it end, not blamed), along every edge,
  so the whole connected graph stops even if a step after it is stuck. The steps before it are
  also interrupted, so one waiting on a database or a file stops too. Steps not joined to it at
  all are stopped. The run's error is the first failure in time; a step that did not free its
  memory is named when nothing failed first (a step stopped mid-batch is only logged). Running out
  of memory names the run's limit ("ran out of memory: the run may hold 256 MB of Arrow memory in
  all"). A tee that cannot copy is reported against the writing step.
- **Stalls.** If no batch moves through any row set for the stall timeout (120 seconds unless
  set on `KilnGraphRunner` or `Runner`) while every running step is waiting on a row set, the run
  fails: "The run stalled: no rows moved for 120 seconds while every running step waited for
  another. Waiting: seq (to read from p1.out->seq.a); p2 (to write to p2.out->seq.b) …". A step
  busy outside the row sets (a slow query) is never taken for a stall.
- **Interruption** of the calling thread stops every step and waits for every thread ever
  started before `run` throws `InterruptedException`; no thread starts once a stop has begun.
- The linear `Runner` builds a graph (`a.out -> b.in -> ...`, settings as configuration) and runs
  it with `KilnGraphRunner`. One difference from before: if one step fails and another leaks, the
  run reports the failure, not whichever step came first in the line. A step that ends before its
  input does no longer leaves the step before it waiting for ever.
- **Known limit.** A step that reads one input to its end before another (a lookup reads its
  lookup side first) stalls when both inputs come from the same step and more rows are waiting
  than the edges hold. The compiler adds a note to such pipelines (also when the inputs of a port
  taking many share a step, in case the step reads them one after another); reading the source
  twice avoids it, and the stall watchdog ends such a run with a plain message instead of hanging.
- **Outcomes.** Each `StepResult` has an `outcome`: `FINISHED`, `ENDED_EARLY` (it stopped before
  its input ended, or every step reading it had finished), `STOPPED` (the engine stopped it
  because another step failed, the run stalled or was stopped) or `FAILED`. A UI shows a stopped
  writer as stopped, not as a clean finish. The older constructors derive it from `error`.
- If anything fails while the run is starting (building a step's context, starting a thread),
  the steps already started are stopped and waited for before the failure is thrown. Decimal
  settings are turned into text in full only when that is short, otherwise in scientific form
  (`1E-999999999`), so a number can never become a string of a billion zeros.

### Writing a step for graphs

- Read with `input(port)` or, for a port taking many, `inputs(port)` with `RowSet.takeAny`; write
  with `emit(port, batch)`, which drops rows for an unconnected port.
- A step that polls with a timeout counts as waiting only while it waits; work between polls (a
  commit, a remote call) is never taken for a stall.
- **Close what you hold in `close()` or a `finally`.** When another step fails, the steps before
  it are interrupted wherever they are, and every step can be stopped mid-batch. A batch taken but
  not yet handed on must still be closed, or the step is reported (or, when stopped, logged) for
  not freeing its memory.
- **Interruption closes sockets.** Interrupting a thread blocked in socket or channel I/O closes
  that socket. A step using a connection pool must validate connections before reuse, and must not
  return a connection to the pool after it was interrupted mid-call. Writers should roll back in
  `close()` when the step did not finish.
- `inputSchema(port)` gives an input's columns when they were known before the run, so a step can
  act correctly on an input that sends no batch.

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
  are text, quoted values are always text, `${VAR}` is never substituted. Decimals are read as
  exact `BigDecimal`s. The text written is kept beside each value
  (`StepDefinition.configText`), and mapping keys are always the text written (`007` stays
  `007`).
- Settings reach the step in the form their kind needs (`Compiled.configs()`): a setting that is
  text (text, expression, column, source, asset, choice, and the values of columns, expressions,
  conditions and column maps) holds exactly what was written, so `asset: 2024.10` is `"2024.10"`
  and `007` is `"007"`; a number is a whole number (Integer or Long) or a BigDecimal, never
  not-a-number, infinite, beyond a long or a double, or with more than 1,000 digits or its decimal
  point more than 1,000 places away (`1e-999999999` is refused). `settings()` at run time is the text of
  those values.
- Refused, each with its line and column: files over 1,000,000 characters, more than one
  document, anchors and aliases (a pipeline is written out in full, so the canvas can rewrite it),
  tags, duplicate keys, keys that are lists or mappings, nesting deeper than 64, more than
  100,000 values, and characters YAML forbids. Values echoed in messages are cut to 80 characters.
- `PipelineDefinition.parse(yaml)` reads the format line, names, steps, ids, inputs and settings
  with their positions. `PipelineCompiler(stepFactories).compile(yaml)` checks it against the step
  types and returns `Compiled`: `problems()` (line, column, step id, message), `notes()`,
  `schemas()` (columns per step and output port) and `graph()`. Checking never creates a step or
  opens a connection; `graph()` creates new steps for one run, and closes those already made if one
  fails.
- `compile(PipelineDefinition)`, for a pipeline built in code, also checks what reading does: the
  name, step ids (pattern and uniqueness) and that each step has a type.
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

## 7. Next

- **Step 2, the canvas:** drag steps from the catalogue, connect ports, a settings panel built
  from each step's settings schema, YAML side by side kept in sync, data previews at any step,
  keyboard operation.
- **Step 3, quality:** the gate step, predicted versus measured scores, AI suggestions,
  version diffs.
- Later: scheduling, parameters, the importer, push-down to SQL.
