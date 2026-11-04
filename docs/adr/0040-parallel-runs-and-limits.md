# 0040 Parallel runs, and the limits around them

Status: Accepted (4 November 2026)

## Context

A flow is already a graph of what depends on what (ADR 0026): nodes with no connection between them
start together, and every pipeline a node runs is an ordinary queued run that any worker may take.
So a flow runs in parallel wherever it can, and the only order is the one its connections give.

What is missing is control over how much runs at once. A customer scaling up wants more running in
parallel; the same customer's database administrators want a promise that Kilnmark never opens
twenty sessions on their production database at once. Today:

- each worker runs `kilnmark.worker.concurrency` jobs at once (2 by default), shared by scans,
  quality checks and pipeline runs, and watches at most 8 flows (fixed in the code);
- a pipeline has at most one run queued or running, so one pipeline cannot run once per region or
  per file in parallel;
- nothing limits how many runs use one source at once, or how many nodes of one flow run at once.

The orchestrators customers know all put the same three layers of limits around a dependency
graph (docs/design/parallel-runs.md, "What other tools do"): a limit for the whole installation, a
limit per pipeline or flow, and a limit per shared resource (Airflow's and Dagster's pools,
Prefect's global concurrency limits, Argo's semaphores). Most also have a fan-out over a list with
its own cap (Airflow's mapped tasks, Azure Data Factory's ForEach with `batchCount`, Step Functions'
Map with `MaxConcurrency`).

## Decision

1. **The installation's limit is the workers' slots.** `kilnmark.worker.concurrency` stays the
   number of jobs (scans, quality checks, pipeline runs) one worker runs at once, and the number
   of flows one worker watches becomes a setting too, `kilnmark.worker.flows` (8 by default).
   Kilnmark scales up by raising these, and out by running more workers against the one database:
   the queue (`FOR UPDATE SKIP LOCKED`) already lets any number of workers share it, and this is
   now proven by a test. The installation guide says how to size them against the database's
   connections.

2. **A limit per source: at most N runs at once.** A source may set **Most runs at once**
   (`maxRuns`, empty for no limit). A pipeline run counts against every source it reads or writes,
   and a flow's `sql` node against its source. A run whose sources are full waits in the queue,
   without using an attempt, and its page says which source it is waiting for. The places are
   advisory locks taken on the run's own lease connection (as its target locks are), so a worker
   that dies frees them, and workers on other machines see them. Scans keep their own rule (one at a
   time per source) and do not count. This is the control a bank's database administrators ask for,
   tied to what Kilnmark already knows: its sources.

3. **A limit per flow: `max_parallel`.** A flow may say how many of its nodes run at once (1 to 50;
   no limit when left out). Nodes ready beyond it wait, in the order they are written, and start as
   others end. One run of a flow at a time stays the rule (ADR 0026).

4. **A `for-each` node.** It runs one pipeline once for each item of a list (a list written in the
   node, or a list parameter of the flow), passing the item as one of the pipeline's parameters,
   with at most `max_parallel` of them at once (4 by default, 50 at most) and at most 500 items.
   It succeeds when every item's run succeeds; it reports how many succeeded and failed, so a
   connection's condition can read them. Its runs are ordinary pipeline runs, so source limits and
   worker slots apply to them too.

5. **The items of one for-each node may run the same pipeline at once.** This is the one exception
   to "a pipeline has at most one run queued or running" (owner, 4 November 2026). The rule stays for
   everything else. What it protected, two runs writing the same table or file at once, stays
   protected by the lock each run takes on every asset it writes: a for-each over regions that
   writes one file per region runs in parallel; one that writes a single table runs its items as the
   lock allows, and an item that waits too long fails, saying so.

## Consequences

- A migration adds `max_runs` to sources (built instead as a common setting in the source's
  settings, with `allow_writing`: docs/design/parallel-runs.md, P2), and `flow_item` to pipeline runs so a run is found again
  by its flow run, node and item (the unique index moves from `(flow_run_id, flow_step)` to include
  the item).
- Waiting for a source's place is not a failure and uses no attempt: a pipeline job is still tried
  once. The run's page and the flow's node say what it waits for, never the source's settings.
- A for-each node with a source limit of 1 runs its items one at a time. That is the limit doing its
  job, and the guide says so.
- More parallel work needs more database connections: each running job holds one for its lease,
  and each watched flow one for its lock. The installation guide gives the sum.
- Not decided here: priorities between queued runs, rate limits (runs per minute), limits per
  pipeline, and queueing a flow run behind a running one rather than refusing it. They wait until a
  customer needs them, or the scheduler does.
