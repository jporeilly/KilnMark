# 0026 Flows orchestrate pipelines; the scheduler comes later and is built in

Status: Accepted (29 October 2026)

## Context

A pipeline moves and checks data. Real work is several pipelines in a set order, where the next
step depends on how the last one ended and on what it measured: carry on only if a quality gate set
aside under 2% of the rows and the output scored Good, else tell the steward and stop. Kilnmark
has no way to say that, and ADR 0007 rules out an external orchestrator (Airflow, NiFi) as a
dependency.

The code already uses "job" for the PostgreSQL work queue (docs/development.md, "Workers and the job
queue"), so "job" cannot also be the name of what orchestrates pipelines.

## Decision

- **A flow** is a named, versioned YAML document (`kilnmark: flow/v1`) of **flow steps** joined by
  **paths**. It is saved, versioned and run like a pipeline (docs/design/flows.md). "Job" stays
  the internal queue and is never the name of a flow.
- **Paths** say when the next step runs: `success`, `failure`, `always`, or a **condition** over
  what an earlier step reported (its status, the lowest quality score of what it wrote, the largest
  share of rows a gate set aside, rows written, seconds taken) and the flow's parameters.
- **Conditions are a small purpose-built language**, not the row-expression language. Dotted names
  for what steps report, quality words (`good`, `fair`, `poor`) for the score bands, SQL's three-valued
  logic (an unknown value takes no path), checked before saving, never executing code.
- **A flow run is a job of kind `flow`.** The worker that claims it only decides what runs next
  and watches; every pipeline a flow step runs is an ordinary queued pipeline run that any worker
  may take, so flow steps with no path between them really run in parallel. The decision logic is
  a pure state machine (`FlowEngine`) in kiln-core, with no threads and no database.
- **Flows run as the person who started them.** The pipeline runs carry that person's name and
  role, fixed when the flow is queued; a flow cannot do what its starter could not when it started. Who runs a flow when nobody starts it (the
  scheduler) is decided with the scheduler.
- **A flow job holds no worker slot.** It only watches, and the pipeline runs it queues need slots
  of their own; holding one could fill every slot with flows waiting for runs that never start. For
  the same reason a worker watches at most 8 flows at a time, since each holds a database connection
  for its lock.
- **One run of a flow at a time**, as for pipelines (a unique index). A flow step that finds its
  pipeline already running waits, up to the flow's `queue_timeout`, instead of failing; the
  pipeline keeps its own rule of one run at a time.
- **A flow is tried once.** If its worker stops, the run is marked failed, as a pipeline's is: the
  pipelines it had started may have written. A pipeline run a flow step started is found again by
  `(flow run, step)`, so a step can never start its pipeline twice.
- **The scheduler is built in** (cron and event triggers that start flows), in a later release; no
  external scheduler is needed or used.

## Consequences

- Migration V20 adds `flow`, `flow_version`, `flow_run` and `flow_step_run`, and links
  `pipeline_run` to the flow run and step that started it.
- Pipelines gain optional parameters in a later step, so a flow can pass values down; a parameter
  may fill a setting's value but never choose a step type, a source or where to connect.
- A condition can read only the steps that must have ended before its path is decided, so it can
  never depend on a step that has not run.
- The flow format and the condition language are part of what users write; changing them in a way
  that breaks saved flows needs a new format version (`flow/v2`) and a new ADR.
