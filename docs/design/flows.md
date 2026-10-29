# Flows

Status: design, 29 October 2026. Round 4. Slice F1 (the engine, storage and the service) is built;
the rest (parameters passed to pipelines, notify and wait nodes, the API, the pages and the canvas)
is marked **planned** below. ADR 0026 records the decision.

A pipeline moves and checks data. A **flow** runs pipelines in an order, and decides what runs next
from how each ended and what it measured: carry on only if the gate set aside under 2% of the rows
and the output scored Good, else stop. A flow is saved as YAML, versioned, run by a worker, and
every run is recorded node by node.

Words: **flow** (what people see), **flow node**, **connection** (joins two flow nodes). **Job** is
the internal PostgreSQL work queue and never the name of a flow.

## 1. The format

```yaml
kilnmark: flow/v1
name: nightly-customers
description: Clean customers, then publish spend only if quality holds.
queue_timeout: 30m             # how long a node waits for a pipeline already running (default 30m)
parameters:
  limit_share: { type: number, default: 0.02 }
nodes:
  - id: clean
    type: run-pipeline
    pipeline: demo-customers-clean
    version: 3                 # optional; the pipeline's latest when left out
  - id: spend
    type: run-pipeline
    pipeline: demo-customer-spend
  - id: halt
    type: stop
    status: failed             # how the flow counts: failed (the default) or succeeded
    message: Too many customers were set aside
connections:
  - { from: clean, to: spend, when: "clean.quarantined_share < limit_share and clean.score >= good" }
  - { from: clean, to: halt,  when: "clean.quarantined_share >= limit_share" }
  - { from: clean, to: halt,  when: failure }
layout: { ... }                # where the canvas draws each node; ignored when running
```

- Node types in F1: `run-pipeline` (a saved pipeline, by name and optionally version) and `stop` (ends
  the flow). `notify` and `wait` are **planned** (F2).
- A connection has `from`, `to` and `when`: `success` (the default), `failure`, `always` or a
  condition.
- Flows written before the rename use `steps:` and `paths:` for these lists; the reader still
  accepts both.
- Names follow pipelines' (`[a-z][a-z0-9-]{0,62}`), node ids `[a-z][a-z0-9_-]{0,63}`; parameters
  `[a-z][a-z0-9_]{0,63}` of type `text`, `number` or `boolean`, up to 50. At most 200 nodes and
  1,000 connections. The YAML reader is the pipelines' (SnakeYAML Engine, YAML 1.2, no anchors or
  tags), and unknown keys are errors with the line, so typing mistakes are caught.
- Checked when saved, without opening anything: the format line, keys (with the closest suggested),
  ids, node settings, connections between existing nodes (no connection from a node to itself, none
  written twice), **no loops**, and every condition. The server then checks that each pipeline a node runs
  exists and, when a version is named, that it does. A flow with problems is saved as a draft and
  cannot run.

## 2. Conditions

A condition is a small language over what earlier nodes reported and the flow's parameters. It
never runs code. `FlowCondition` in kiln-core.

```
clean.quarantined_share < 0.02 and clean.score >= good
clean.status = 'failed' or (clean.rows_written = 0 and region != 'APAC')
```

- **Values.** `<node>.status` is `succeeded`, `failed` or `cancelled` for any node. A
  `run-pipeline` node also reports `<node>.score` (the lowest measured quality score of what it
  wrote, 0 to 1), `<node>.quarantined_share` (the largest share of rows any of its gates set aside),
  `<node>.rows_written` and `<node>.duration_s`. A parameter is read by its name.
- **Quality words** are the lowest score of a band, as the interface shows them: `good` is 0.95,
  `fair` 0.8, `poor` 0.
- **Syntax.** Numbers (`0.02`, `-1`), text in single quotes, `true` and `false`; `= != < <= > >=`;
  `and`, `or`, `not`; brackets. Comparisons do not chain. Both sides of a comparison are the same
  kind (number, text or true/false), checked when saved; true/false values compare only with `=` and
  `!=`; any other operator (`==`, `=>`) is refused rather than guessed at. At most 1,000 characters and
  32 levels of brackets and `not`. A parameter cannot be named `and`, `or`, `not`, `true`, `false`,
  `good`, `fair` or `poor`, which a condition reads as words of the language.
- **Unknown is not false.** A score nothing measured is unknown, and a comparison with an unknown
  value is unknown, with SQL's rules for `and`, `or` and `not`. A connection is taken only when its
  condition is **true**, so an unknown answer takes neither this connection nor its opposite; a flow
  with `< 0.02` on one connection and `>= 0.02` on the other will skip both when nothing was
  measured. Add a `failure`/`always` connection or write the condition so that it covers the case.
- **A condition reads only nodes that must have ended.** The node the connection leaves and the
  nodes before it, found from all the connections whatever order they are written in. Naming any
  other node is an error ("Unknown value 'c.score'") rather than a value that is sometimes missing.
- A condition connection is taken only when the node it leaves **succeeded**.

## 3. How a flow runs

**`FlowEngine`** (kiln-core) holds no threads and no database. It tracks each node as `PENDING`,
`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` or `SKIPPED`, and answers what can start:

- A node with no connection into it starts at once; nodes with no connection between them start
  together.
- A node with connections into it waits until every one is **settled** (the node it leaves ended or
  was skipped), then starts if at least one is **taken**, and is skipped if none is. A skipped node
  settles its own connections as not taken, so a whole branch skips.
- A connection is taken when the node it leaves ended the way it asks: `success` after a success,
  `failure` after a failure, `always` after either, a condition after a success when it is true.
  A cancelled or skipped node takes no connection.
- **How the flow ends.** Cancelled if a person asked it to stop. Otherwise as a `stop` node that ran
  said. Otherwise cancelled if a node was cancelled, failed if a node failed with no connection to
  deal with it (a `failure` or `always` connection leaves it), succeeded if none did. A failure that
  a connection handles does not fail the flow.
- A `stop` node ends the flow: nothing more starts, nodes that have not started are skipped, and the
  pipeline runs still going are cancelled. Its message is the run's message.

**`FlowService`** (server) runs the engine on a worker:

1. A flow run is queued as a job of kind `flow`, tried once. The worker holds the job's own advisory
   lock and **no worker slot**: it only watches, and the pipeline runs it queues need the slots
   (ADR 0026). Because each watching flow holds a database connection for its lock, one worker
   watches at most 8 flows at a time (`JobWorker.MAX_FLOWS`); further flow jobs wait in the queue.
2. About twice a second it asks the engine what is ready and starts it. A `run-pipeline` node queues
   an ordinary pipeline run with the flow starter's name and role, linked to the flow run and node
   (`pipeline_run.flow_run_id`, `flow_node`; unique, so a node never starts its pipeline twice). If
   the pipeline already has a run queued or running the node waits ("Waiting for p to finish its
   current run") and asks again, giving up after `queue_timeout`.
3. It reads the finished pipeline run's status and measures (`PipelineStore.flowReport`) and tells
   the engine. A failed node's message says that the pipeline failed (or could not be run) and where
   to look, never why: why can quote data and name sources and fields, and the pipeline's own page
   shows it to those who may see it.
4. A cancel (`FlowService.cancel`) stops starting, cancels the pipeline runs still going, and the
   flow ends cancelled. Someone cancelling a node's pipeline run by hand stops the flow the same way.
   A `stop` node does the same for nodes still going or waiting, and nodes that became ready in the
   same moment as the stop are not started.
5. If the worker stops cleanly, it cancels the pipeline runs its flow had started, marks the nodes
   failed and the run failed (not repeated), with the advice to check what those pipelines had
   written. If the worker dies, recovery marks the run failed and the nodes still running failed; the
   pipeline runs it had started are not cancelled, so check them.
6. When a run ends (or its worker stops cleanly), every node of the version has a row: nodes that
   never ran are recorded as skipped.

## 4. Storage (V20, renamed in V21)

`flow` and `flow_version` as `pipeline` and `pipeline_version` (latest version, never-changed
versions, drafts with their problems); `flow_run` (status `queued`, `running`, `succeeded`, `failed`,
`cancelled`; who asked and with which role; cancel requested; error; the parameters the run started
with); `flow_node_run` (status including `skipped`; when it started and ended; the pipeline run it
started; a message; what it reported, as JSON); `pipeline_run.flow_run_id` and `flow_node`. A unique
index allows one queued or running run per flow. V20 named them `flow_step_run`, `step_id` and
`flow_step`; V21 renamed them to the names above.

## 5. Who may do what

- **Saving, running and cancelling a flow take the steward role** (`FlowService` checks; viewers see
  flows once the API and pages exist, **planned**). The pipeline runs it queues run as the person who
  started it, with their role, so a write node still needs a steward and a source that allows
  writing, and a flow cannot do what its starter could not **at the moment it starts**. The role is
  fixed when the run is queued: a token revoked afterwards does not stop a flow already running
  (cancel it), and a node may start up to `queue_timeout` after the flow did.
- Flows are checked again when they are run: pipelines may have been deleted or changed since the
  flow was saved.
- **Planned (item 4, the scheduler):** a flow that nobody starts runs as an identity an admin
  records when scheduling it, capped at a role.

## 6. Planned

- **F2.** Parameters passed down: `parameters:` on pipelines too, `with:` on `run-pipeline`, `${name}`
  in setting values (a parameter fills a setting's value only, never a node type, a source or an
  address); `notify` (in-app and email to a steward group) and `wait` nodes; a lab flow checked against
  the lab like the demo pipelines.
- **F3.** API (`/api/v1/flows`, `/api/v1/flow-runs`, validate, versions with `If-Match`, run, cancel)
  and the run page with a Gantt chart: a bar per flow node, coloured by status, skipped nodes hatched,
  each linked to its pipeline run (the Gantt component kept for this, see pipelines.md section 5).
- **F4.** The flow canvas: flow nodes as boxes, connections as labelled edges, a connection
  inspector, a live run overlay; editing as text patches as the pipeline canvas does.
- Not in this plan: calling a flow from a flow, a SQL check node, retries of a node.
