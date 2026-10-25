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
  `inputs: {port: id[.port]}` connects several.
- Steps are listed in any order; the edges decide the order of work.
- Unknown keys are errors, with the line number, so typing mistakes are caught.
- Secrets never appear: steps name saved sources, and the source holds the credentials.

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
