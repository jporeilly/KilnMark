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

### As built (`kiln-steps`, `io.kilnmark.kiln.steps.expr`)

- **Parsing.** A hand-written lexer and recursive-descent parser; no `eval`, no reflection, no
  regular expressions over the expression text. Keywords are case-insensitive; bare column names
  are case-sensitive and cannot be keywords (quote them: `"date"`). Precedence, loosest first:
  `or`, `and`, `not`, comparisons / `is null` / `in` / `between` / `like`, `||`, `+ -`,
  `* / %`, unary minus. Comparisons do not chain (`a = b = c` and `x between 1 and 2 between …`
  need brackets). Limits: 10,000 characters, nesting 64 deep (brackets, calls, `case`, `cast`,
  `in`, `not`, minus), at most 1,000 operations inside each other (so a chain like `a + b + …`
  cannot overflow the stack) and 10,000 parts in a compiled expression. Every node is compiled
  once (a `between` operand, a simple `case` operand), so checking and evaluation stay linear in
  the expression's size.
- **Types.** `boolean`, `int64` (every Arrow integer), `float64` (every Arrow float), `decimal(p,s)`
  (up to 38 digits), `utf8` (also large and view text), `date`, `timestamp` (microseconds, UTC;
  any Arrow unit or time zone reads as the same instant, and text without an offset is UTC), and
  the type of a bare `null`. Dates and timestamps are limited to years -9999 to 9999 (literals,
  parsing and date arithmetic), so every date is also a timestamp; arithmetic that still leaves the
  range is a plain error. Other Arrow types (binary, lists, structs, times) pass through steps
  untouched but cannot be used in expressions. Results are written as `Int64`, `Float64`,
  `Decimal(38,s)`, `Utf8`, `Date(DAY)` and `Timestamp(MICROSECOND, "UTC")`.
- **Implicit conversion only widens**: `int64` → `decimal` → `float64`, and `date` → `timestamp`
  (midnight UTC); `null` becomes anything. Everything else needs `cast`. `int64 / int64` gives a
  `float64` (`7 / 2` is `3.5`). Decimal results keep precision 38 with scale: `+ - %` the larger
  scale, `*` the sum of the scales, `/` at least 6; every result scale is capped at 18, rounded half
  up. Decimal literals over 38 digits are refused. Whole-number
  overflow and division by zero are errors (guard with `nullif`); `date ± n` adds days and
  `date - date` counts them.
- **Nulls** follow SQL: three-valued `and`/`or`/`not`, `in` with a null item gives null rather
  than false, `filter` and `route` treat null as not true, `coalesce`, `nullif` and `concat`
  (which skips nulls) are the exceptions to null-in, null-out.
- **Evaluation** works a whole batch at a time into plain Java arrays, then writes each result to
  an Arrow vector from the step's allocator. A row mask passed down the tree means `case`,
  `and`, `or` and `coalesce` only work out the rows that need it, so
  `case when x <> 0 then 10 / x end` never divides by zero and a guarded `cast` never fails.
- **Casts** from text are strict: whole numbers for `int64`; plain decimals with an optional
  exponent for `float64` and `decimal` (no hexadecimal, `NaN` or suffixes, and the size checked
  before anything is built); `true/false/yes/no/1/0` for `boolean`; ISO forms for dates and
  timestamps. Digits are ASCII only, and a float that would be infinite is refused. A failed cast
  is an error that never quotes the value (it may be sensitive);
  `parse_date` and `parse_timestamp` give null instead.
- **Functions as built:** `trim lower upper length substring replace coalesce nullif abs round
  floor ceil concat regex_match regex_replace now today date_trunc date_part parse_date
  parse_timestamp format_date`. `now()` is fixed when the step starts. Date patterns use
  `DateTimeFormatter` letters, read strictly (31 February is rejected) and must be written out;
  so must `date_trunc` / `date_part` units and `round`'s digits, so mistakes are found before a run.
- **Regular expressions** are safe on any data and pattern. Refused: a repeated group that itself
  repeats (`(a+)+`, `(\w+\s?)*`), with one exception: an unbounded repeat (`+` or `*`, never a
  counted `{n}` or `{n,m}`, which defeats the matcher's own caching) of a group that starts with
  an ASCII punctuation separator nothing else in the group can match, such as `(\.[\w-]+)+`.
  Each repetition must then begin at the separator, so there is only one way to split the text.
  The test is deliberately strict: no `_` separator (which `\w` matches), no alternatives in the
  group, no unescaped `.`, no negated or nested classes, no class range with any non-letter or
  non-digit between its ends (`[0-z]` holds `: ; < = > ? @ [ \ ] ^ _` and the backtick), and no
  escape that can spell or stand for many characters (`\x`, `\0`, `\c`, `\N`, Unicode escapes,
  `\p`, `\W` and the like). Alternatives are refused only inside such an excepted group: a plain
  `(a|b)*` is accepted, but run on values of at most 2,000 characters. Back-references and comment
  mode `(?x)` are refused too. Values over 100,000 characters are an error, and over 2,000 for any
  pattern that repeats a group (Java's matcher recurses per repetition); a stack overflow in the
  matcher is caught and reported. The matcher reads text through a wrapper that counts character
  reads: 10 million per value (roughly a fifth of a second) and 250 million per batch for each
  function call. Compiled patterns are cached, 128 per call. `regex_match` finds the pattern
  anywhere (anchor with `^ $`). `like` is matched without regular expressions, with the same value
  cap and a budget of 50 million steps per value.
- **Text results** (`||`, `concat`, `replace`, `regex_replace`) are capped at 1,000,000 characters
  per value, checked while they are built, and at 64,000,000 characters per batch for each
  operator or call, so a modest result repeated over a full batch cannot exhaust memory. Text compares in Unicode code point order (the order of
  UTF-8 bytes). `trim` strips characters Java counts as whitespace (spaces, tabs, line breaks and
  the like, but not the no-break space U+00A0).
- **Aggregates** (`aggregate` only) may be combined in expressions with group-by columns
  (`sum(price * qty) / count(*)`); a column outside an aggregate must be grouped by, and
  aggregates cannot nest. `count_distinct` is exact, counted against the step's memory limit.
- **Errors** say what, where (character position from 1) and, when there is one, a suggestion:
  `column full_name: unknown column 'frist_name' at 7; did you mean first_name?`. Validation adds
  `Step tidy: ` in front; at run time the runner adds `Step tidy (compute) failed: `.

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

The row steps live in the `kiln-steps` module (`io.kilnmark.kiln.steps`), one `StepFactory` each,
found through `ServiceLoader`. Settings, with defaults:

- `filter`: `condition`. `compute`: `columns` (name → expression, worked out in order; each sees
  the columns computed before it; a new name is added at the end, an existing one replaced in
  place). `select`: `keep` or `drop`, `rename`, `order`, `cast` (column → type); every setting
  names columns as they arrive. `cast` is declared as kind `EXPRESSIONS` (a map of names to free
  text) for want of a column-to-type kind in `SettingSpec`.
- `lookup`: `on` (main column → lookup column), `keep` (default: every lookup column but the
  keys), `join` (`left`), `unmatched` (`keep` for left, `drop` for inner, or `rejected`),
  `duplicates` (`first` or `error`), `memory_limit_mb` (256). The lookup side is held as its
  Arrow batches plus a hash table from key to row. Keys match by value across number types
  (1, 1.0 and 1.00) and dates match timestamps at midnight; null keys never match. While the
  lookup side loads, main batches that are ready are read and held (counted against the memory
  limit) once the lookup side has been quiet for 250 ms, until it moves again, so a main input fed by the same step
  as the lookup input does not stall the run, while a merely slow lookup side does not pull main
  into memory. An empty lookup side uses the columns the pipeline check worked out
  (`StepContext.inputSchema`).
- `aggregate`: `group_by` (none: one row for everything, even with no rows), `values` (name →
  expression of aggregates), `memory_limit_mb` (256). Groups are emitted in the order first
  seen, 8,192 rows a batch; nulls form one group. With no input batch at all the columns come
  from `StepContext.inputSchema`, so an aggregate without `group_by` still writes SQL's one row;
  only when those are unknown is nothing written.
- `union`: `strict` (false), `memory_limit_mb` (256). Inputs are read with `RowSet.takeAny`.
  Columns are matched by name in the order first seen across the inputs as listed; a missing
  column is null; differing types widen as expressions do (int32 and int64 to int64, whole numbers
  and decimals to decimal …) or are an error naming both. The output's columns come from the
  inputs' columns as the pipeline check worked them out (`StepContext.inputSchema("in", i)`);
  only when those are unknown do batches wait in memory until every input has sent its first
  batch or ended (an input that then sends nothing adds no columns).
- `dedupe`: `key` (default: whole rows), `memory_limit_mb` (256); nulls equal each other.
- `route`: `routes` (port → condition, in order; port names are lower case, not `in` or `out`).
- `limit`: `rows`. Stops reading once it has its rows; the runner ends the steps before it that
  feed nothing else.

Memory: a step closes every batch it takes or hands it on, and splits batches by copying only
when rows go to more than one port (a batch whose rows all go one way is handed on whole).
Memory limits are checked after each batch, with messages that say what to change.

Writing (built in round 3, step 1; the guide is [Writing data from a pipeline](../guide/writing-data.md)):

- **The contract.** A `write` step is `Connector.writer(source, WriteOptions)`: target, mode, key,
  format, further settings (`options`), the asset URN and the source's profiling settings
  (`profiling`; see "What a write reports" in section 5). Connectors that write list
  `Capability.WRITE`, declare the settings they take in `Connector.writeOptions()` (`SettingSpec`s
  with kind, default and help; the service passes only those keys), and say in
  `Connector.writtenAssetPath(source, write)` which catalogue path the written asset has: the table
  (`schema.table`), or for files the path below the source's prefix, escaped as the catalogue
  escapes it (`raw/` + `out/a.csv` on a prefixed S3 source). The pipeline service checks that the
  source allows writing and that its hosts are allowed before it asks for the step; the step reads
  its `in` port and has no outputs.
- **Databases** (PostgreSQL, SQL Server), target `schema.table`, names used exactly and always
  quoted, values always bound or streamed, never pasted into SQL:
  - `create` makes the table from the input's columns and fails if it exists. Types: int16
    smallint, int32 integer/int, int64 bigint, decimal numeric/decimal(p,s), float real and
    double precision/float, text text/nvarchar(max) (or `text_length`), binary bytea/varbinary(max),
    date, time, timestamp timestamp/datetime2(6), timestamp_tz timestamptz/datetimeoffset(6).
    Anything else (lists, structs) is written as text.
  - `append` needs the table. Before any row is sent, every input column is matched by name and
    must fit its column (wider integers, decimals with room, dates into timestamps, anything but
    bytes into text); required columns the input lacks, and two input names (differing in case)
    meeting one column, are named too, all in one message. On PostgreSQL a table whose row-level
    security applies to the login is loaded with `INSERT` instead of `COPY` (which RLS refuses).
  - `replace` always creates and fills `kiln_new_<random>`, then in the same transaction renames
    the old table to `kiln_old_<random>`, gives the new table its name and drops the old one (or,
    when there was no table, only renames the new one, so a failure never leaves a half-filled
    target). PostgreSQL's DDL is transactional; SQL Server renames with `sp_rename` inside the
    transaction. The swap waits at most `lock_timeout` seconds (default 30) for the locks it needs.
    Readers see the old rows until the commit.
    - Kept: the old table's grants (re-granted on the new table; `keep_grants: false` to skip) and
      its owner: on PostgreSQL `ALTER TABLE … OWNER TO` when the login may (a superuser, or able to
      `SET ROLE` to the owner on 16+, a member before, with the owner allowed to create in the
      schema); otherwise the owner is granted all privileges on the new table, which the login
      then owns. On SQL Server a table owned through its schema keeps that owner; one with an owner
      of its own gets `GRANT CONTROL` for that owner.
    - Not kept: indexes, constraints, triggers, comments, row-level security policies.
    - Refused before anything is written: a target that is not a plain table (a view, a
      partitioned or foreign table); a partition, an inheritance parent or child; any object that
      depends in the normal way on the table, its row type, the array of its row type or a sequence
      it owns, and is not part of it (views, foreign keys from other tables, functions with SQL
      bodies or taking its rows, columns of its type, other tables' defaults using its sequences),
      found through `pg_depend`; a publication naming the table (`pg_publication_rel`;
      publications `FOR ALL TABLES` or `FOR TABLES IN SCHEMA` take in the new table by themselves);
      on SQL Server schema-bound views and functions, foreign keys, replication and change data
      capture. A
      table with row-level security (or a SQL Server security policy) is refused unless the step
      sets `allow_dropping_row_security: true`, since the repeated grants would then see every row.
  - `upsert` with `key`: PostgreSQL `INSERT … ON CONFLICT (key) DO UPDATE`, which needs a unique
    index or primary key on exactly the key columns (checked first, with the `CREATE UNIQUE INDEX`
    to ask for); SQL Server one `MERGE … WITH (HOLDLOCK)` per row. Empty keys are refused; a
    repeated key's last row wins.
  - Speed: PostgreSQL `COPY … FROM STDIN` as CSV for create, append and replace; batches of
    prepared statements (1,000 rows a batch; 5,000 for SQL Server inserts) for upsert and for SQL
    Server, whose driver bulk-copies
    batches into tables the step created (`bulk_copy`; not for append, since bulk copy skips
    constraints and triggers).
  - One transaction per run. `commit_every: N` commits after each batch that reaches N rows; a
    failure then leaves the rows already committed, and the message says how many (a replace
    still swaps only at the end, and drops its half-filled table).
  - The login's rights are checked first (`has_any_column_privilege`, `has_schema_privilege`,
    `HAS_PERMS_BY_NAME`, counting column grants) and refused writes are explained: "the login lab
    cannot write to billing.customer_spend in warehouse: ask a database administrator to grant
    INSERT on billing.customer_spend". Append reads the table's columns from a prepared statement's
    description without running it, so an insert-only login can append. Re-granting on SQL Server
    (including `GRANT CONTROL` for a table's own owner) is not checked first: a login without the
    right to grant fails at that statement, and the transaction is rolled back. A row a row-level
    security policy refuses is reported as such, not as a missing grant.
  - Timestamps are written in UTC; decimals keep their digits; text too long for a column is
    refused with the row and column before the database truncates or fails.
- **Files** (folders and S3), `format` csv or parquet (or from the extension), `path` relative to
  the source: absolute paths, `..`, `//`, backslashes, control characters, hidden names and a
  wrong extension are refused, and the stores check again with links resolved. A local file is
  written to a hidden temporary file in its folder, flushed, and renamed into place (linked, when
  `overwrite` is false, so a file that appeared meanwhile is not replaced). An S3 object is one PUT
  when small, otherwise a multipart upload (parts of 8 MiB, growing to 32 and 128 MiB after 1,000
  and 5,000 parts, so objects to about 780 GB stay within 10,000 parts) completed at the end and
  aborted on failure or cancellation. Existing files are refused unless `overwrite: true` (or mode
  `replace`). A path a scan of the source would not find (a sub-folder of a source that is not
  recursive, or outside its containers, include and exclude) is refused unless
  `allow_unscanned: true`, since the asset registered for it would never be scanned again. Folders
  are checked by path and then used: someone able to write inside an allowed folder on the server
  could swap a checked folder for a link in between, so allowed folders should be writable only by
  Kilnmark and trusted people.
  - CSV: RFC 4180, UTF-8, header row, CRLF, configurable `delimiter`; a null is an empty field and
    an empty string `""`, so Kilnmark's reader tells them apart; ISO 8601 dates and times.
  - Parquet: Kilnmark's own streaming writer (Hardwood cannot write; parquet-java needs Hadoop,
    ADR 0015): flat optional columns, PLAIN values, RLE definition levels, pages of at most 1 MiB
    or 20,000 values, row groups of at most 64 MiB or 2^20 rows, Snappy by default
    (`compression`: zstd, gzip, none) through the codec jars reading already uses, and
    `created_by` naming the Kilnmark version. Logical types as the reader maps them back. Checked
    against Hardwood in every build and against pyarrow where it is installed (not on CI).
- **Profile as written.** The writer profiles each batch with `ProfileStep`'s own code as it
  writes it and reports only after the commit (or once the file is in place); see "What a write
  reports" in section 5. `profile_output: false` turns profiling off; `keep_values: false` keeps no
  example values.

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

### What a write reports

The contract between the writers and the pipeline service. The writer owns the profile of what it
writes; the service adds no profile step of its own. It catalogues the asset under
`urn:kilnmark:<source>:<Connector.writtenAssetPath(source, write)>`, and passes that URN as
`WriteOptions.assetUrn`. A writer emits metadata events for that URN only after the write has
succeeded; a failed or cancelled write emits nothing (with `commit_every`, rows already committed
remain, and the step's error says so).

**Profiling settings.** `WriteOptions.profiling` is a map the service fills from the source's own
profiling settings, exactly as it builds the profile step's settings for a scan of that source,
under the same keys: `keep-values`, `examples`, `sample-size`, `sample-mode`, `sample-key`,
`sample-seed`, `sample-max-bytes` and `sketch-fields` (the fields whose distinct-value sketch is
kept). It never comes from the YAML. The step's own `keep_values: false` can keep fewer values than
the source allows, never more; `profile_output: false` turns profiling off. An empty map means the
profile step's defaults.

| Write | Events | Meaning for the service |
| --- | --- | --- |
| Table `create`, `replace`; any file | `SCHEMA` (`columns`: JSON array of `{name, kind, type}`, `type` as written; `target`, `mode` or `format`, `location`), `ROW_COUNT` (`rows`, `complete=true`, `written=true`), `PROFILE` per column, `SAMPLE` when values are kept | The asset now holds exactly these rows: register or update it, replace its fields, row count and profile, apply the source's sample controls to `SAMPLE` |
| Table `append`, `upsert` | `ROW_COUNT` only (`rows` written, `complete=false`, `written=true`, `appended=true`, `mode`) | Rows were added to or merged into an existing table: record the run's lineage and row count, but keep the table's fields, row count and profile from its last scan; a scan refreshes them |

Columns, kinds and profile attributes are those a scan reports (`JdbcColumn.Kind` names,
`ColumnStats` attributes), so the service stores them the same way.

**Profiling never fails a write.** If profiling fails (settings it cannot read, or an error while
profiling or computing the profile), the write completes, `ROW_COUNT` is still emitted and carries
`profiled=false` and `profile_error` (the reason, in words a person can read), and no `PROFILE` or
`SAMPLE` events follow. The service turns `profile_error` into a note on the run and keeps the
asset's previous profile.

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
