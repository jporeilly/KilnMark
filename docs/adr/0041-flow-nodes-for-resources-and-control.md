# 0041 Flow nodes that check resources, and controls on how nodes run

Status: Accepted (4 November 2026)

## Context

A pipeline that starts before what it needs is ready fails halfway: its source is down, the file
it reads has not arrived, a table it joins is missing. And a node that runs too long, fails on a
passing network fault, or waits behind less urgent work has nothing to say about it. Orchestrators
answer both with the same few tools: nodes that check or wait for a resource before the work starts
(Airflow's sensors, Azure Data Factory's Validation and Get Metadata activities), and settings on a
node for its timeout, its retries and its priority (Step Functions' `Retry` and `TimeoutSeconds`,
Argo's `retryStrategy` and `activeDeadlineSeconds`, Airflow's `retries`, `execution_timeout` and
`priority_weight`).

The owner chose these five on 4 November 2026: `check-source`, `wait-for`, timeout, retries and
priority. They build on ADR 0026 (flows) and ADR 0040 (parallel runs and limits).

## Decision

1. **A `check-source` node** checks, without reading any data, that a source can be reached and,
   when asked, that named tables or files are there. It succeeds or fails at once; a failure says
   which check failed, in the flow's own words, and never the source's settings or the driver's
   message (which can name hosts and users). It reports `missing` (a number) for conditions.

2. **A `wait-for` node** waits until something is ready, checking every `every` (5 minutes by
   default, at least 30 seconds) for at most `timeout` (1 hour by default, at most 24 hours), then
   succeeds, or fails saying it gave up. What it waits for is one of:
   - **a file**: one or more files matching a pattern appear under a file or object-store source's
     folder (`file: incoming/*.csv`, with `at_least: 1`); or
   - **a query**: a SQL query on a PostgreSQL source returns a row whose first value is true, under
     every rule a person's SQL follows (ADR 0028: Allow SQL on, checked as custom SQL rules are, on
     a read-only connection). Its result is never shown or stored; only whether it was true.
   It holds no worker slot while it waits, as a flow does not, and reports `found` (files matched,
   or 1 for a true query) and `waited_s`.

3. **`timeout` on a node.** `run-pipeline`, `for-each` (for each item), `sql` and `check-source`
   nodes may say how long they may take (`timeout: 2h`, at most 7 days). A pipeline run past it is
   cancelled, as a person's cancel would, and the node fails saying it took too long.

4. **`retries` on a node.** `run-pipeline`, `for-each` (each item on its own) and `sql` nodes may
   be tried again: `retries: 2` (at most 5), `retry_delay: 1m` (10 seconds to 1 hour, doubling each
   time, 1 minute by default). A retry never writes twice:
   - a pipeline run is tried again only if it failed before writing anything (no asset written or
     write begun), or every write in its version replaces or upserts (`replace`, `upsert`); a
     pipeline with an `append` or `create` write is refused when the flow is saved;
   - a `sql` node is tried again only when its transaction was rolled back, which is how it fails.
   Each try is its own run, linked to the node with its try number, and the node's page lists them.
   A cancelled run is never tried again.

5. **`priority` on a node.** `run-pipeline`, `for-each` and `sql` nodes may be `high`, `normal`
   (the default) or `low`. A worker takes the queued job with the highest priority first, then the
   oldest. Scans and quality checks are `normal`. Priority chooses among jobs that may start; it
   never overrides a source's limit or a flow's `max_parallel`, and a low job still runs when
   nothing higher waits.

## Consequences

- A migration gives jobs a priority (the claim orders by it, then by when they are due), and links a
  pipeline run to its try number.
- The pipeline's write modes decide whether a node may have retries; the flow's check says so when
  it is saved, naming the write node.
- `wait-for` with a query runs a person's SQL on a schedule; the audit log records the flow being
  saved, as for `sql` nodes, and the guide says that its query is read-only.
- Not decided here: waiting for another flow's run, waiting on a table's freshness from the
  catalogue, approval by a person, and running a flow from a flow. They wait for a customer who
  needs them.
