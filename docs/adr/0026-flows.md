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

- **A flow** is a named, versioned YAML document (`kilnmark: flow/v1`) of **flow nodes** joined by
  **connections**. It is saved, versioned and run like a pipeline (docs/design/flows.md). "Job" stays
  the internal queue and is never the name of a flow.
- **Connections** say when the next node runs: `success`, `failure`, `always`, or a **condition**
  over what an earlier node reported (its status, the lowest quality score of what it wrote, the
  largest share of rows a gate set aside, rows written, seconds taken) and the flow's parameters.
- **Conditions are a small purpose-built language**, not the row-expression language. Dotted names
  for what nodes report, quality words (`good`, `fair`, `poor`) for the score bands, SQL's three-valued
  logic (an unknown value takes no connection), checked before saving, never executing code.
- **A flow run is a job of kind `flow`.** The worker that claims it only decides what runs next
  and watches; every pipeline a flow node runs is an ordinary queued pipeline run that any worker
  may take, so flow nodes with no connection between them really run in parallel. The decision
  logic is a pure state machine (`FlowEngine`) in kiln-core, with no threads and no database.
- **Flows run as the person who started them.** The pipeline runs carry that person's name and
  role, fixed when the flow is queued; a flow cannot do what its starter could not when it started. Who runs a flow when nobody starts it (the
  scheduler) is decided with the scheduler: see [ADR 0046](0046-scheduler.md).
- **A flow job holds no worker slot.** It only watches, and the pipeline runs it queues need slots
  of their own; holding one could fill every slot with flows waiting for runs that never start. For
  the same reason a worker watches at most 8 flows at a time, since each holds a database connection
  for its lock.
- **One run of a flow at a time**, as for pipelines (a unique index). A flow node that finds its
  pipeline already running waits, up to the flow's `queue_timeout`, instead of failing; the
  pipeline keeps its own rule of one run at a time.
- **A flow is tried once.** If its worker stops, the run is marked failed, as a pipeline's is: the
  pipelines it had started may have written. A pipeline run a flow node started is found again by
  `(flow run, node)`, so a node can never start its pipeline twice.
- **The scheduler is built in** (cron and event triggers that start flows), in a later release; no
  external scheduler is needed or used. Cron schedules, for pipelines and flows, are
  [ADR 0046](0046-scheduler.md).

## Consequences

- Migration V20 adds `flow`, `flow_version`, `flow_run` and `flow_step_run`, and links
  `pipeline_run` to the flow run and step that started it (`flow_step`). V21 renames them to
  `flow_node_run` and `flow_node`.
- Pipelines gain optional parameters in a later step, so a flow can pass values down; a parameter
  may fill a setting's value but never choose a node type, a source or where to connect. Built in
  F2 (V22), with **server variables** beside them: values of the server, not of a pipeline, written
  `${vars.name}`, so one YAML runs unchanged on a test server and a live one. Parameters are inputs a
  pipeline or flow declares and each run may change; variables are the environment, set by stewards
  and admins, and never secrets. Runs keep the values they started with.
- A condition can read only the nodes that must have ended before its connection is decided, so it
  can never depend on a node that has not run.
- The flow format and the condition language are part of what users write; changing them in a way
  that breaks saved flows needs a new format version (`flow/v2`) and a new ADR.
