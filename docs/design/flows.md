# Flows

Status: design, 29 October 2026. Round 4. Slice F1 (the engine, storage and the service) is built;
the rest (parameters passed to pipelines, notify and wait steps, the API, the pages and the canvas)
is marked **planned** below. ADR 0026 records the decision.

A pipeline moves and checks data. A **flow** runs pipelines in an order, and decides what runs next
from how each ended and what it measured: carry on only if the gate set aside under 2% of the rows
and the output scored Good, else stop. A flow is saved as YAML, versioned, run by a worker, and
every run is recorded step by step.

Words: **flow** (what people see), **flow step**, **path** (joins two flow steps). **Job** is the
internal PostgreSQL work queue and never the name of a flow.

## 1. The format

```yaml
kilnmark: flow/v1
name: nightly-customers
description: Clean customers, then publish spend only if quality holds.
queue_timeout: 30m             # how long a step waits for a pipeline already running (default 30m)
parameters:
  limit_share: { type: number, default: 0.02 }
steps:
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
paths:
  - { from: clean, to: spend, when: "clean.quarantined_share < limit_share and clean.score >= good" }
  - { from: clean, to: halt,  when: "clean.quarantined_share >= limit_share" }
  - { from: clean, to: halt,  when: failure }
layout: { ... }                # where the canvas draws each step; ignored when running
```

- Step types in F1: `run-pipeline` (a saved pipeline, by name and optionally version) and `stop` (ends
  the flow). `notify` and `wait` are **planned** (F2).
- A path has `from`, `to` and `when`: `success` (the default), `failure`, `always` or a condition.
- Names follow pipelines' (`[a-z][a-z0-9-]{0,62}`), step ids `[a-z][a-z0-9_-]{0,63}`; parameters
  `[a-z][a-z0-9_]{0,63}` of type `text`, `number` or `boolean`, up to 50. At most 200 steps and 1,000
  paths. The YAML reader is the pipelines' (SnakeYAML Engine, YAML 1.2, no anchors or tags), and
  unknown keys are errors with the line, so typing mistakes are caught.
- Checked when saved, without opening anything: the format line, keys (with the closest suggested),
  ids, step settings, paths between existing steps (no path from a step to itself, none written
  twice), **no loops**, and every condition. The server then checks that each pipeline a step runs
  exists and, when a version is named, that it does. A flow with problems is saved as a draft and
  cannot run.

## 2. Conditions

A condition is a small language over what earlier steps reported and the flow's parameters. It
never runs code. `FlowCondition` in kiln-core.

```
clean.quarantined_share < 0.02 and clean.score >= good
clean.status = 'failed' or (clean.rows_written = 0 and region != 'APAC')
```

- **Values.** `<step>.status` is `succeeded`, `failed` or `cancelled` for any step. A
  `run-pipeline` step also reports `<step>.score` (the lowest measured quality score of what it
  wrote, 0 to 1), `<step>.quarantined_share` (the largest share of rows any of its gates set aside),
  `<step>.rows_written` and `<step>.duration_s`. A parameter is read by its name.
- **Quality words** are the lowest score of a band, as the interface shows them: `good` is 0.95,
  `fair` 0.8, `poor` 0.
- **Syntax.** Numbers (`0.02`, `-1`), text in single quotes, `true` and `false`; `= != < <= > >=`;
  `and`, `or`, `not`; brackets. Comparisons do not chain. Both sides of a comparison are the same
  kind (number, text or true/false), checked when saved; true/false values compare only with `=` and
  `!=`; any other operator (`==`, `=>`) is refused rather than guessed at. At most 1,000 characters and
  32 levels of brackets and `not`. A parameter cannot be named `and`, `or`, `not`, `true`, `false`,
  `good`, `fair` or `poor`, which a condition reads as words of the language.
- **Unknown is not false.** A score nothing measured is unknown, and a comparison with an unknown
  value is unknown, with SQL's rules for `and`, `or` and `not`. A path is taken only when its
  condition is **true**, so an unknown answer takes neither this path nor its opposite; a flow with
  `< 0.02` on one path and `>= 0.02` on the other will skip both when nothing was measured. Add a
  `failure`/`always` path or write the condition so that it covers the case.
- **A condition reads only steps that must have ended.** The step the path leaves and the steps
  before it, found from all the paths whatever order they are written in. Naming any other step is
  an error ("Unknown value 'c.score'") rather than a value that is sometimes missing.
- A condition path is taken only when the step it leaves **succeeded**.

## 3. How a flow runs

**`FlowEngine`** (kiln-core) holds no threads and no database. It tracks each step as `PENDING`,
`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` or `SKIPPED`, and answers what can start:

- A step with no path into it starts at once; steps with no path between them start together.
- A step with paths into it waits until every one is **settled** (the step it leaves ended or was
  skipped), then starts if at least one is **taken**, and is skipped if none is. A skipped step
  settles its own paths as not taken, so a whole branch skips.
- A path is taken when the step it leaves ended the way it asks: `success` after a success,
  `failure` after a failure, `always` after either, a condition after a success when it is true.
  A cancelled or skipped step takes no path.
- **How the flow ends.** Cancelled if a person asked it to stop. Otherwise as a `stop` step that ran
  said. Otherwise cancelled if a step was cancelled, failed if a step failed with no path to deal with
  it (a `failure` or `always` path leaves it), succeeded if none did. A failure that a path handles
  does not fail the flow.
- A `stop` step ends the flow: nothing more starts, steps that have not started are skipped, and the
  pipeline runs still going are cancelled. Its message is the run's message.

**`FlowService`** (server) runs the engine on a worker:

1. A flow run is queued as a job of kind `flow`, tried once. The worker holds the job's own advisory
   lock and **no worker slot**: it only watches, and the pipeline runs it queues need the slots
   (ADR 0026). Because each watching flow holds a database connection for its lock, one worker
   watches at most 8 flows at a time (`JobWorker.MAX_FLOWS`); further flow jobs wait in the queue.
2. About twice a second it asks the engine what is ready and starts it. A `run-pipeline` step queues
   an ordinary pipeline run with the flow starter's name and role, linked to the flow run and step
   (`pipeline_run.flow_run_id`, `flow_step`; unique, so a step never starts its pipeline twice). If
   the pipeline already has a run queued or running the step waits ("Waiting for p to finish its
   current run") and asks again, giving up after `queue_timeout`.
3. It reads the finished pipeline run's status and measures (`PipelineStore.flowReport`) and tells
   the engine. A failed step's message says that the pipeline failed (or could not be run) and where
   to look, never why: why can quote data and name sources and fields, and the pipeline's own page
   shows it to those who may see it.
4. A cancel (`FlowService.cancel`) stops starting, cancels the pipeline runs still going, and the
   flow ends cancelled. Someone cancelling a step's pipeline run by hand stops the flow the same way.
   A `stop` step does the same for steps still going or waiting, and steps that became ready in the
   same moment as the stop are not started.
5. If the worker stops cleanly, it cancels the pipeline runs its flow had started, marks the steps
   failed and the run failed (not repeated), with the advice to check what those pipelines had
   written. If the worker dies, recovery marks the run failed and the steps still running failed; the
   pipeline runs it had started are not cancelled, so check them.
6. When a run ends (or its worker stops cleanly), every step of the version has a row: steps that
   never ran are recorded as skipped.

## 4. Storage (V20)

`flow` and `flow_version` as `pipeline` and `pipeline_version` (latest version, never-changed
versions, drafts with their problems); `flow_run` (status `queued`, `running`, `succeeded`, `failed`,
`cancelled`; who asked and with which role; cancel requested; error; the parameters the run started
with); `flow_step_run` (status including `skipped`; when it started and ended; the pipeline run it
started; a message; what it reported, as JSON); `pipeline_run.flow_run_id` and `flow_step`. A unique
index allows one queued or running run per flow.

## 5. Who may do what

- **Saving, running and cancelling a flow take the steward role** (`FlowService` checks; viewers see
  flows once the API and pages exist, **planned**). The pipeline runs it queues run as the person who
  started it, with their role, so a write step still needs a steward and a source that allows
  writing, and a flow cannot do what its starter could not **at the moment it starts**. The role is
  fixed when the run is queued: a token revoked afterwards does not stop a flow already running
  (cancel it), and a step may start up to `queue_timeout` after the flow did.
- Flows are checked again when they are run: pipelines may have been deleted or changed since the
  flow was saved.
- **Planned (item 4, the scheduler):** a flow that nobody starts runs as an identity an admin
  records when scheduling it, capped at a role.

## 6. Planned

- **F2.** Parameters passed down: `parameters:` on pipelines too, `with:` on `run-pipeline`, `${name}`
  in setting values (a parameter fills a setting's value only, never a step type, a source or an
  address); `notify` (in-app and email to a steward group) and `wait` steps; a lab flow checked against
  the lab like the demo pipelines.
- **F3.** API (`/api/v1/flows`, `/api/v1/flow-runs`, validate, versions with `If-Match`, run, cancel)
  and the run page with a Gantt chart: a bar per flow step, coloured by status, skipped steps hatched,
  each linked to its pipeline run (the Gantt component kept for this, see pipelines.md section 5).
- **F4.** The flow canvas: flow steps as nodes, paths as labelled edges, a path inspector, a live run
  overlay; editing as text patches as the pipeline canvas does.
- Not in this plan: calling a flow from a flow, a SQL check step, retries of a step.
