# Flow controls

Status: design, 4 November 2026. ADR 0041 records the decision. Slices: C1 `check-source`, C2
`wait-for`, C3 `timeout`, C4 `retries`, C5 `priority`. Each is marked **planned** or **built**.
It builds on [Flows](flows.md) and [Parallel runs](parallel-runs.md).

## C1. `check-source` (planned)

```yaml
  - id: ready
    type: check-source
    source: warehouse
    tables: [sales.orders, sales.customers]   # optional: each must exist
    files: [incoming/today.csv]               # optional, on a file or object-store source
    timeout: 2m                               # optional (C3)
```

- Connects as the source's own test does (`SourceChecks.test`), then looks each table or file up
  through the connector's listing, never reading rows. A table name is `schema.table` as the
  catalogue writes it.
- Succeeds when all are there. Fails with "Could not reach warehouse" or "2 of 3 tables are
  missing: sales.orders, ..." (names the flow wrote; never the driver's text). Results: `missing`.
- Checked when saved: the source exists and the flow's starter may use it; at most 50 names.
- Runs on the flow's watcher, not in a worker slot; it holds a connection only while it checks.
- Pairs with `failure` connections: check, then notify and stop if anything is missing.

## C2. `wait-for` (planned)

```yaml
  - id: arrived
    type: wait-for
    source: landing                # a file or object-store source
    file: "incoming/orders-*.csv"  # a pattern below the source's folder: * and ?
    at_least: 1
    every: 5m
    timeout: 2h

  - id: loaded
    type: wait-for
    source: warehouse              # PostgreSQL, Allow SQL on
    query: "select max(loaded_at) > now() - interval '1 hour' from etl.batches"
    every: 2m
    timeout: 1h
```

- One of `file` or `query`. `every` 30 seconds to 1 hour (5 minutes by default); `timeout` up to
  24 hours (1 hour by default). The first check is at once.
- **File.** Lists the source's folder through its connector and counts names matching the pattern
  (no recursion past the pattern's own folders). Results: `found` (files matched).
- **Query.** Checked when saved as custom SQL rules are (ADR 0028): one read-only `SELECT`, on a
  PostgreSQL source with Allow SQL on, run on a read-only connection with a statement timeout of 30
  seconds; true when the first column of the first row is true. Nothing it returns is stored or
  shown. Results: `found` (1 or 0).
- Waits on the flow's watcher, holding no slot and no connection between checks. Cancelling the
  flow ends the wait. Results also: `waited_s`.

## C3. `timeout` (planned)

- On `run-pipeline`, `for-each` (each item), `sql` and `check-source`: a duration, at most 7 days.
- The watcher measures from when the node's run started (not while it waited for a slot or a
  source's place). Past it, a pipeline run is cancelled (`PipelineService.cancel`, as a person's
  cancel), a `sql` node's statement is stopped; the node fails: "Took longer than 2h and was
  stopped". A timed-out try may be retried (C4) when retries allow.

## C4. `retries` (planned)

- On `run-pipeline`, `for-each` and `sql`: `retries` 0 to 5, `retry_delay` 10s to 1h (1 minute by
  default), doubled after each try.
- **Pipelines.** Checked when saved: every `write` node of the pipeline's version uses `replace`
  or `upsert`, or the flow is refused, naming the write node ("retries need writes that replace or
  upsert: write-orders appends"). At run time a failed run is also retried when it failed before any
  write began, whatever its modes. A cancelled run (by a person, or the flow) is never retried.
- **SQL.** A `sql` node runs in one transaction, rolled back on failure, so it may always be
  retried.
- Each try is a new pipeline run linked to `(flow run, node, item, try)`; `pipeline_run.flow_try`
  (0 for the first). The run page lists every try with its link; the node's status is its last
  try's.

## C5. `priority` (planned)

- On `run-pipeline`, `for-each` and `sql`: `high`, `normal` (default), `low`, stored on the job as
  1, 0 or -1 (`job.priority`).
- `JobQueue.claim` orders by `priority DESC, run_after, id`. Jobs waiting for a source's place go
  back with a later `run_after`, so they do not hold the head of the queue.
- A person's **Run now** of a pipeline is `normal`.

## Order of work

C3 and C5 first (small, and used by the rest), then C1, C2, C4. Migration numbers follow the parallel
runs slices (V31, V32): these take the next free one when they merge.
