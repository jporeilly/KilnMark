# Flows

Status: design, 29 October 2026. Round 4. Slices F1 (the engine, storage and the service) and F2
(parameters passed to pipelines, server variables, notify and wait nodes) are built, and so are the
`sql` node (ADR 0028, 2 November 2026), F3, the API and the pages (3 November 2026), and F4a, the
diagram and the run chart (4 November 2026); editing on the canvas (F4b) is marked **planned**
below. ADR 0026 records the decision.

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
    with: { region: "${region}", label: "nightly" }   # values for the pipeline's parameters
  - id: tell
    type: notify                # in the app and by email, to whoever follows flow notices
    severity: medium            # info (the default), low, medium or high
    title: "Clean set aside ${clean.quarantined_share} of the customers"
    message: The spend report did not run.
  - id: later
    type: wait
    for: 10m                    # up to 24h
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

- Node types: `run-pipeline` (a saved pipeline, by name and optionally version, with `with:` for its
  parameters), `stop` (ends the flow), `notify` (sends a notice), `wait` (waits a while) and `sql`
  (runs statements on a source; section 7).
- A connection has `from`, `to` and `when`: `success` (the default), `failure`, `always` or a
  condition.
- Flows written before the rename use `steps:` and `paths:` for these lists; the reader still
  accepts both.
- Names follow pipelines' (`[a-z][a-z0-9-]{0,62}`), node ids `[a-z][a-z0-9_-]{0,63}`; parameters
  `[a-z][a-z0-9_]{0,63}` of type `text`, `number`, `boolean`, `text list` or `number list`, up to 50
  (pipelines.md, "Parameters and server variables"; a flow's may have no default, and a run must then
  give the value; a list parameter is for a pipeline's `in (...)`, never a condition). At most 200 nodes and
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
  `<node>.rows_written` and `<node>.duration_s`; a `sql` node reports `<node>.rows_affected`. A
  parameter is read by its name.
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

### Values passed on (F2)

- **What `${...}` reads in a flow:** the flow's parameters (`${region}`), server variables
  (`${vars.target_schema}`) and the values of nodes that have always run when this one starts: the
  nodes on every way into it (for each connection in, its node and the nodes always run before that
  one, in common; a branch may skip the others, so they cannot be read): `${clean.status}`, and for a
  `run-pipeline` node `${clean.rows_written}`, `score`, `quarantined_share` and `duration_s`. Checked
  when saved. A value nothing measured (a score with no rules) has none: `with:` then fails the node,
  saying so, and a notice reads it as "unknown". A node cannot be called `vars`.
- **A pipeline parameter's value, first match wins:** the node's `with:`; else the flow's parameter of
  the same name (inherited, when both declare it: the server checks they are of one kind); else the
  pipeline's default, which may be a server variable. A `with:` value that is exactly one `${name}`
  keeps that value's kind (a number, a list); any other text has the values put in as words. The
  server checks `with:` against the pipeline's parameters when the flow is saved and again when it
  runs.
- **A flow run keeps its values:** `flow_run.parameters` and `flow_run.variables` (all the server's
  variables when it was queued). The pipeline runs it starts get the same variables, so one flow run
  reads one set of them throughout.
- **`notify`** posts a `flow_notice` notification (in the app and by email, to whoever follows that
  kind, at the node's severity) with the run's values put in its title and message, and ends at once.
  A flow run that fails posts `flow_failed`, told once until a run of the flow succeeds. Both name the
  flow's own words, never a pipeline's error.
- **`wait`** holds no worker slot and no pipeline: it ends after `for`, or as cancelled when the flow
  is cancelled or stopped. If the worker stops while it waits, the run fails like any other.

## 4. Storage (V20, renamed in V21)

`flow` and `flow_version` as `pipeline` and `pipeline_version` (latest version, never-changed
versions, drafts with their problems); `flow_run` (status `queued`, `running`, `succeeded`, `failed`,
`cancelled`; who asked and with which role; cancel requested; error; the parameters the run started
with); `flow_node_run` (status including `skipped`; when it started and ended; the pipeline run it
started; a message; what it reported, as JSON); `pipeline_run.flow_run_id` and `flow_node`. A unique
index allows one queued or running run per flow. V20 named them `flow_step_run`, `step_id` and
`flow_step`; V21 renamed them to the names above. V22 adds `flow_run.variables` and
`pipeline_run.parameters` and `variables`, and the server variables (`variable`, `variable_change`).
V29 adds `flow_version.sql_checked` (section 7).

## 5. Who may do what

- **Saving a flow takes a designer, running and cancelling one a steward** (`FlowService` checks,
  and the API's roles). A flow with a `sql` node is saved only by a designer whose token sees
  unmasked values (section 7); the API passes the caller's grant. Viewers see flows and their runs
  (F3), but not the values a run started with, nor node messages that quote them (a notice's title,
  the values a pipeline refused): those can hold server variables, which only stewards read. The
  pipeline runs a flow queues run as the person who started it, with their role, so a write node
  still needs a steward and a source that allows writing, and a flow cannot do what its starter could not **at the moment it starts**. The role is
  fixed when the run is queued: a token revoked afterwards does not stop a flow already running
  (cancel it), and a node may start up to `queue_timeout` after the flow did.
- Flows are checked again when they are run: pipelines may have been deleted or changed since the
  flow was saved.
- **Planned (item 4, the scheduler):** a flow that nobody starts runs as an identity an admin
  records when scheduling it, capped at a role.

## 6. Planned

- **F2, built.** Parameters on pipelines, server variables, `with:`, inheritance by name, node
  values, `notify` and `wait`, the lab flow (`lab/flows`, checked with the demo pipelines).
- **Next: the read node's `columns:` and `where:`**, done by the database (a value as a bound
  parameter), with lab examples.
- **F2b.** Projects (`${project.x}`, values and variables scoped to a project), pipeline `outputs:`
  (values a run works out, such as the latest date it wrote, read by later nodes) and a `run-flow`
  node that gives a child flow its parameters.
- **F3, built.** The API (`FlowResource`, `FlowRunResource`: `/api/v1/flows` to list, add, read,
  check (new and saved), save with `If-Match` (409 on an older version), list versions and runs, and
  run with parameter values; `/api/v1/flow-runs/{id}` with every node of the run's version, those not
  yet started shown `pending`, and its cancel) and the pages: Flows in the sidebar after Pipelines, a
  flow page with the YAML editor, checked as it is typed, Run now with a dialog for parameter values,
  and a run page listing each node's status, time and message, linked to the pipeline run it
  started. No delete: a flow's runs and the pipeline runs they started stay linked, and deleting
  waits until what that means is decided. The sample flows are not yet added by Add samples.
- **F4a, built.** `FlowDiagram` (ui/src/flows): the flow read from its YAML on the page
  (`readFlowGraph`, which reads only what it draws; the server's check says whether it is valid),
  nodes as boxes with what each does, connections as labelled arrows (solid on success, red dashed
  on failure, dotted always, amber with the condition's text), laid out left to right by dagre
  with room made for each label and each connection drawn along the route dagre found, so none
  crosses a node it does not join. Where the `layout:` block places every node it is drawn there,
  with plain arrows. Read only: the wheel scrolls the page, zoom is by buttons or pinch, and a list
  under it says every connection in words. On the run page the same diagram carries each node's
  status (in words and its outline), and `FlowRunChart` draws the run: a bar per node from the
  run's start (`flowChart`), coloured by status, running bars growing to now, a skipped row
  hatched, a bar that started a pipeline run linking to it. Built on its own rather than on the
  pipeline run's timeline: a flow node has no rows or ports.
- **F4b.** Editing on the canvas: adding and removing nodes, drawing connections, a connection
  inspector for `when`, dragging into `layout:`; every edit a text patch of the YAML, as the
  pipeline canvas does.
- Not in this plan: retries of a node. A SQL *check* stays a pipeline with a `read` and a `gate`.

## 7. The sql node (ADR 0028)

```yaml
  - id: empty
    type: sql
    source: warehouse            # a saved PostgreSQL source with Allow SQL and Allow writing on
    statements:                  # 1 to 20, each one statement of at most 10,000 characters
      - TRUNCATE staging.orders
      - DELETE FROM staging.log WHERE at < now() - interval '30 days'
```

An action, not a data node: it changes data or tables and never returns rows, so nothing downstream
reads from it. Connections follow it as any node (`success`, `failure`, a condition on
`<node>.rows_affected`, the rows its statements changed in total).

- **Statements are written as they run.** `${...}` is refused in them: a value put into SQL could
  change what it does. Each must start with `TRUNCATE`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`,
  `CREATE`, `DROP`, `ALTER`, `CALL`, `ANALYZE`, `REFRESH`, `COMMENT`, `REINDEX`, `CLUSTER` or `LOCK`,
  and be one statement (`SqlTokens.checkAction`). Never a query, transaction control, `SET`, `COPY`,
  `DO`, `GRANT` or `REVOKE`; never, unquoted, `RETURNING` or the words that create or change logins,
  rights, code that runs later or ways out of the database (`ROLE`, `USER`, `EXTENSION`, `SERVER`,
  `FUNCTION`, `TRIGGER`, `OWNER`, `PRIVILEGES`, `SYSTEM`, `VIEW` (outside `REFRESH MATERIALIZED
  VIEW`), `ROUTINE`, ...); and never `pg_settings` (its rules change settings) or the functions custom rules
  refuse (files, other sessions or databases, settings, locks, and `ts_stat` and `ts_rewrite`, which
  run a query held in a string), sequences apart. `E'...'` strings and `U&"..."` names are refused,
  and the text may hold no control character but PostgreSQL's whitespace. The tokenizer reads
  comments, whitespace, dollar-quote tags and identifiers as PostgreSQL does, and on top of it the
  PostgreSQL driver's own parser must find exactly one statement. This is a first line of defence:
  the login check and the source's opt-ins below are what hold.
- **Sent as one prepared statement**, over the extended protocol, which PostgreSQL refuses to read as
  several statements; a source set to `preferQueryMode` is refused (the simple protocol runs every
  statement a text holds). `standard_conforming_strings` is turned on for the transaction, and the
  driver is asked before every statement that it is still on: everything is checked as PostgreSQL
  reads it with that setting on, and with it off a backslash could end a string elsewhere and hide a
  statement (`PostgresConnector.conformingStrings`). Query reads and custom rules do the same. A `?` in a
  statement is read as a parameter, so it fails: write jsonb's `?` operators as their functions.
- **Checked when saved and when it runs:** the source exists, is PostgreSQL (the only kind for now)
  and allows both SQL and writing; every statement passes the check. At run time the allowed hosts
  are checked again, then the login: never a superuser, nor one with file, program, signal or
  cross-database access (`RuleRunner.privileged`, as for custom rules). Every source that allows SQL
  should have a login limited to what its SQL needs.
- **One transaction.** The statements run in order on one connection, with
  `kilnmark.pipelines.sql-statement-timeout` (default 30 minutes) as each one's timeout, and commit
  together. If one fails, all roll back, and the node's message says which statement failed and its
  SQLSTATE: never the database's message or the statement, which can quote values. Cancelling the
  flow, or a stop node, cancels the statement running and rolls back.
- **It runs beside the flow**, on a thread of its own, so the flow keeps watching its other nodes
  while it runs.
- **Who writes it.** A flow with a sql node is saved only by a designer whose token sees unmasked
  values (`FlowService.save` with `mayUnmask`; admins see values masked, so not them): SQL can read
  and change any column its login can. Stewards run what is built. Versions saved before this check
  (`flow_version.sql_checked`, V29, false) that hold a sql node are refused when queued and when they
  start, until saved again: before, `sql` was an unknown type, so anyone who saves flows could save
  one as a draft.
- **Recorded.** Each sql node that starts is an audit event `flow.sql` (who started the flow, the
  flow, run and node, the source, how many statements and the version), and its node run says how many
  statements ran and rows changed.
