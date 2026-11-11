# Flows

Status: design, 29 October 2026. Round 4. Slices F1 (the engine, storage and the service) and F2
(parameters passed to pipelines, server variables, notify and wait nodes) are built, and so are the
`sql` node (ADR 0028, 2 November 2026), F3, the API and the pages (3 November 2026), F4a, the
diagram and the run chart (since replaced by the nested run timeline, below), and F4b, editing on
the diagram (4 November 2026). A flow's
`max_parallel` and the `for-each` node (ADR 0040, slices P3 and P4 of parallel-runs.md) are built
(4 November 2026; section 8). ADR 0026 records the decision.

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
max_parallel: 4               # how many nodes run at once, 1 to 50 (no limit when left out)
parameters:
  limit_share: { type: number, default: 0.02 }
nodes:
  - id: clean
    type: run-pipeline
    pipeline: demo-customers-clean
    version: 3                 # optional; the pipeline's latest when left out
    timeout: 2h                # optional: stopped, and failed, past it (flow-controls.md, C3)
  - id: spend
    type: run-pipeline
    pipeline: demo-customer-spend
    with: { region: "${region}", label: "nightly" }   # values for the pipeline's parameters
  - id: regions
    type: for-each              # the pipeline once for each item (section 8)
    pipeline: sales-by-region
    items: [EMEA, APAC, AMER]   # or "${regions}", a text list or number list parameter
    as: region                  # the pipeline parameter each item fills
    max_parallel: 3             # items at once, 1 to 50 (4 when left out)
  - id: tell
    type: notify                # in the app and by email, to whoever follows flow notices
    severity: medium            # info (the default), low, medium or high
    title: "Too many customers set aside: see the clean pipeline's run"
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
notes: [ ... ]                 # notes on the canvas, for people; ignored when running
```

- Notes: a top-level `notes:` list and a `note:` on any node, as a pipeline's (pipelines.md
  section 2): checked for their shape and limits by `CanvasNotes`, never kept in `FlowDefinition`,
  so they never change a run. The diagram draws them with the pipeline canvas's `CanvasNotes`
  component; **Add a note** is under the diagram, beside Undo.

- Node types: `run-pipeline` (a saved pipeline, by name and optionally version, with `with:` for its
  parameters), `for-each` (a saved pipeline once for each item of a list; section 8), `stop` (ends
  the flow), `notify` (sends a notice), `wait` (waits a while) and `sql` (runs statements on a
  source; section 7).
- `max_parallel` (1 to 50) caps how many of the flow's nodes run at once; section 8.
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
  `<node>.rows_written` and `<node>.duration_s`; a `sql` node reports `<node>.rows_affected`; a
  `for-each` node `<node>.items`, `<node>.succeeded` and `<node>.failed` (numbers). A parameter is
  read by its name.
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

1. A flow run is queued as a job of kind `flow`, tried as many times as its `run: retries` allow
   (ADR 0053; once without them). The worker holds the job's own advisory
   lock and **no worker slot**: it only watches, and the pipeline runs it queues need the slots
   (ADR 0026). The locks of every flow a worker watches are on **one connection**
   (`JobQueue.FlowLeases`), opened for the first and given back when the last ends, and so are their
   sql nodes' places on sources; a place one flow holds is never granted to another on the same
   connection. Before, each watched flow held a connection of its own for its lock (efficiency
   review, 6 November 2026; `FlowLeasesTest` and `RunLifecycleTest` find one backend holding every
   flow's lock). So if that connection is lost, every flow on the worker loses its lock and stops as
   a flow does when its worker stops (item 5), where before only the flow on that connection did; the
   next flow opens a new one. One worker still watches at most `kilnmark.worker.flows` (8) flows at a time
   (ADR 0040): each takes connections now and then while it looks, and the pipeline runs they wait
   for need the rest. Further flow jobs wait in the queue.
2. About twice a second, at each look of the process's run watcher (docs/design/pipelines.md, "One
   run lifecycle"), it asks the engine what is ready and starts it (with `max_parallel`, only
   while fewer than that many nodes run; section 8). A `run-pipeline` node queues
   an ordinary pipeline run with the flow starter's name and role, linked to the flow run and node
   (`pipeline_run.flow_run_id`, `flow_node`; unique, so a node never starts its pipeline twice). If
   the pipeline already has a run queued or running the node waits ("Waiting for p to finish its
   current run") and asks again, giving up after `queue_timeout`.
3. It reads the finished pipeline run's status and measures (`PipelineStore.flowReport`) and tells
   the engine. The pipeline runs it watches are read by the run watcher, for every flow on the
   process in one query a look; whether the flow run is asked to stop, from its signal there. A failed node's message says that the pipeline failed (or could not be run) and where
   to look, never why: why can quote data and name sources and fields, and the pipeline's own page
   shows it to those who may see it.
4. A cancel (`FlowService.cancel`) stops starting, cancels the pipeline runs still going, and the
   flow ends cancelled. Someone cancelling a node's pipeline run by hand stops the flow the same way.
   A `stop` node does the same for nodes still going or waiting, and nodes that became ready in the
   same moment as the stop are not started.
5. If the worker stops cleanly, it cancels the pipeline runs its flow had started, marks the nodes
   failed and ends the try as lost: tried again when its `run:` allows (below), else failed, with the
   advice to check what those pipelines had written. If the worker dies, or loses its connection,
   recovery queues the job again while the run has tries left and another worker takes the run over
   (below); with none left, recovery marks the run failed and the nodes still running failed; the
   pipeline runs it had started are not cancelled, so check them.
6. When a run ends (or its worker stops cleanly), every node of the version has a row: nodes that
   never ran are recorded as skipped.

### Carrying on from a failure (ADR 0053)

A flow's `run:` block (docs/design/pipelines.md, "Run retries and owners") tries a failed run again;
each try starts from the nodes' rows of the try before (`CarryOn.plan`, pure, `CarryOnTest`):

- **Kept**: a node that succeeded, every node with a connection into it kept too, is restored into
  the engine as it ended (`FlowEngine.restore`), with its values, its start and its pipeline run;
  it is never run again. A node that failed and cannot be run again is kept as it failed when a
  `failure` or `always` connection handled it.
- **Run again**: every other node, as its connections fall this time, so parallel branches carry on
  each from its own state. Its row is removed first (`FlowStore.clearNodes`), so one not reached
  reads as skipped. A run-pipeline node's pipeline run starts after its earlier tries' (`flow_try`
  from the latest + 1, V35's unique index), and its own retries count from there (`tryBase`, so
  they never multiply with the flow's); a for-each node runs only its items whose latest run did not
  succeed.
- **Not tried again** (the plan's blocker, which ends the run, naming the node): a node that ran and
  is not kept that is not safe to run again: its pipeline's writes do not all replace or upsert
  (against the version it would run), a sql node that committed or whose commit is in doubt
  (`reported.in_doubt`), a stop node that ran. Tried again by itself only, also: a node with retries
  of its own that did not succeed (its tries stand), and one whose pipeline run a quality gate
  stopped or was refused (`pipeline_run.failed_as`). Run again from the failure, by hand, lets
  those two run again.
- **A takeover** (`FlowService.takeOver`, the run still running when a worker takes its job):
  every node that had ended is restored as it ended; a node that was running starts again where it
  was: a run-pipeline or for-each node finds its pipeline runs (`flowLatestTry`, `flowItemRuns`) and
  watches them, a wait or wait-for keeps its start; a sql node that was running is failed with
  `in_doubt`, since its statements ran on the worker that stopped. A node a later try runs again
  records in its row where its pipeline runs start (`try_base`; a for-each node's `item_bases`), and a
  node with no row whose pipeline ran in an earlier try is one that try runs again, so a takeover
  never counts an earlier try's failed run as this try's. `RunRetriesApiTest` builds runs left by a
  worker that stopped, on a first and a later try, recovers their jobs and sees another worker carry on.
- **Handing over.** A worker that stops, or loses its lease, while it watches a flow whose run may be
  taken over leaves it running, its pipeline runs going, and answers its job as waiting: put back in
  the queue (or, with the lease gone, by recovery after `lost-after`). Otherwise it stops what the try
  started (`abandon`: pipeline runs cancelled, a sql node failed and in doubt unless it is known to
  have rolled back), as it does after an internal error, and ends the try as lost.
- **Fencing.** Every end of a try (`RunTables.finishRun`, `retry`) checks that the run is still on
  that try, and a retry that the job is held by the worker that held it as the try began; a worker
  that finds its job no longer its own ends nothing and leaves the run to whoever takes it. So a
  worker that lost its job to another changes nothing of the other's try.
- **Left behind.** A flow run failed because its worker stopped with no tries left
  (`failOrphanedRuns`), or whose takeover was declined, has its sql nodes that were running marked
  in doubt and its pipeline runs still going cancelled. Run again from the failure is refused while
  any of its pipeline runs is going, and for a run with no recorded try (one that failed before V39,
  whose rows cannot say whether a sql node committed).
- **Run again from the failure** (`POST /api/v1/flow-runs/{id}/run-again`, `FlowService.runAgain`):
  stewards and above, never below the run's own role; a failed run only, with a recorded try; the
  plan made by hand,
  refused (409) with its blocker; then `RunTables.runAgain`, in one transaction with the flow's row
  locked as a queue locks it (one run at a time: 409): the failed try's row says who asked, the run
  is queued as its next try with `tries` = that try (so it is not tried again by itself), and its own
  job (one a flow run, V20) is queued again, tried once. Recorded as `flow.run_again`.

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
V29 adds `flow_version.sql_checked` (section 7). V31 adds `pipeline_run.flow_item` (section 8) and
moves the unique index to `(flow_run_id, flow_node, coalesce(flow_item, -1))`.

**Deleting a flow** ([ADR 0048](../adr/0048-deleting-a-flow.md), `FlowStore.deleteWithSchedules`)
leans on V20's foreign keys: deleting the `flow` row cascades to `flow_version`, from there to
`flow_run` and on to `flow_node_run`, and sets `pipeline_run.flow_run_id` to null, so the pipeline
runs a flow started stay, with what they wrote and recorded (their `flow_node`, `flow_item` and
`flow_try` stay as written, read by nothing once the flow run is gone). One transaction locks the
flow's schedules, then the flow's row, refuses (409) while a run is queued or running or the job of
one is still finishing, deletes the schedules with `DELETE … RETURNING` to record each
(docs/design/schedules.md, section 8), clears the flow's open problem for notifications
(`notify_state` key `flow:<name>`, so a flow saved later under the name is told of afresh), then
the flow. Each lock wait is limited to 5 seconds; past that nothing is deleted and the answer is 409
"try again". A run queued or a version saved just as the flow goes finds its row missing and is
answered 404, also inside a start with a request key, which then keeps no key. The runs' request
keys (V37) go with them by cascade, after the flow's row is taken, the order a keyed start takes
them. The runs' `job` rows stay, as a deleted pipeline's do. A failure is told after its run
is recorded as ended but before its job ends, which is why the delete waits for the job; a run
failed because its worker stopped (`failOrphanedRuns`) is told outside any job, so a delete in that
moment can still be followed by a failure told of the deleted flow, which leaves `flow:<name>`
open until that name's next success, or until it is purged. No node runs another flow yet; a
`run-flow` node (F2b) must decide what deleting a flow it names does.

## 5. Who may do what

- **Saving or deleting a flow takes a designer, running and cancelling one a steward**
  (`FlowService` checks, and the API's roles). A flow with a `sql` node is saved only by a designer whose token sees
  unmasked values (section 7); the API passes the caller's grant. Viewers see flows and their runs
  (F3), but not the values a run started with, nor node messages that quote them (a notice's title,
  the values a pipeline refused): those can hold server variables, which only stewards read. The
  pipeline runs a flow queues run as the person who started it, with their role, so a write node
  still needs a steward and a source that allows writing, and a flow cannot do what its starter could not **at the moment it starts**. The role is
  fixed when the run is queued: a token revoked afterwards does not stop a flow already running
  (cancel it), and a node may start up to `queue_timeout` after the flow did.
- Flows are checked again when they are run: pipelines may have been deleted or changed since the
  flow was saved.
- **Scheduled runs, built** ([ADR 0046](../adr/0046-scheduler.md),
  [schedules.md](schedules.md)): a flow that nobody starts runs as `schedule <id> (set by <name>)`,
  the designer or admin who last set the schedule, with the role the schedule gives it: steward or
  designer, never admin. Its pipeline runs carry the same name and role, and viewers never see the
  values it starts with.

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
  started. Deleting a flow came later (section 4, [ADR 0048](../adr/0048-deleting-a-flow.md)). The sample flows are added by Add samples, after the pipelines they run, and listed in the
  Library (ADR 0043).
- **F4a, built.** `FlowDiagram` (ui/src/flows): the flow read from its YAML on the page
  (`readFlowGraph`, which reads only what it draws; the server's check says whether it is valid),
  nodes as cards drawn as a pipeline's are (canvas.css `.cv-node`: type and icon, id, what it
  does, and `in` and `out` connectors), connections as labelled arrows from `out` to `in` (solid on
  success, red dashed on failure, dotted always, amber with the condition's text), laid out left to
  right by dagre with room made for each label, each connection drawn in square steps through the
  route dagre found (`route.ts`, `steps`), turning only in the gaps between columns, so none
  crosses a node it does not join (dagre routes from a box's middle, so its bends in those gaps,
  and the labels, move down to the connectors' height; a second connection between the same two
  nodes is bracketed round its label at its own height); two unseen points at the routes' corners
  make fitting the view take in a route that runs round the outside; `when` is read as the server reads it, in any case. The wheel
  scrolls the page, zoom is by buttons or pinch, and a list under it says every connection in
  words. On the flow page it
  follows the text 400 ms after typing stops, keeping the last flow it could read. On the run page
  the same diagram carries each node's status (in words and its outline), laid out once per
  version, and the run timeline (`FlowRunTimeline`, below) draws the run, nested as it went. Built on its own rather than on the
  pipeline run's timeline: a flow node has no rows or ports.
- **F4b, built.** Editing on the diagram, for designers on the flow page. Every change is a
  function of `edit.ts` (text in, text out, refused rather than guessed), written as a patch of the
  YAML with the pipeline canvas's `Patch` (pipelines/canvas/patch.ts), so only the lines that change
  are rewritten; `writeLayoutBlock` moved there so pipelines and flows share it. Adding a node (an id
  from its type or pipeline, `freshId`, with starting settings), removing one with its connections
  and position, connecting two (by dragging between handles or with a form; written as the list's
  other items are, flow mappings or blocks, with `when:` left out for on success and a condition
  double-quoted), changing `when` in the chosen connection's tools, removing a connection, and
  moving nodes. Refused before the server sees them: a connection to itself, one written twice (as
  the server tells them apart) and one that closes a loop. Lists written in flow style (`[ … ]`)
  are refused rather than rewritten. A connection is known by its place in the YAML list
  (`FlowConnection.index`). Dragging writes every node's position into `layout:`, so the picture
  stays as it was seen; with `layout:`, nodes are drawn where it says (those it does not name where
  dagre would put them) and connections are drawn as curves between the nodes' handles, those
  between the same two nodes bowed apart (`bowOffsets`), with labels over the nodes. **Lay out
  again** removes the block and its comment. Undo, under the diagram, steps back through the
  changes made there while the text is as the last one left it. Choosing works by click or by Enter
  on a focused node or connection, and the list in words has a Change button for each connection,
  which moves the focus to its tools. A node's settings are changed in the YAML (but see the panel's
  Settings tab, below).
- **Pages laid out as a pipeline's, built.** The flow page puts Diagram and YAML on tabs, with the
  pipeline page's panel under them (`PipelineDock`: Run, History, Versions). The run page gives a
  Summary and "Where each node spent its time" as a pipeline run does (`runFacts.ts`, pure). For
  them the run API adds, per run, `pipelineRuns` (asked for), `rowsWritten` (summed) and
  `qualityScore` (the lowest) over its pipeline runs, one grouped query for a list
  (`PipelineStore.flowTotals`); per run-pipeline node `pipelineRunTimes` (asked for, started,
  finished), all read in one query per run (`PipelineStore.flowRunTimes`); and per for-each item
  `requestedAt`. A node's time is split by those times: working while any of its pipeline runs ran,
  queued while none ran and one waited for a worker, waiting otherwise; every bar is drawn on the
  run's scale. A run-pipeline node's wait for a busy pipeline runs to its request, or to its end if
  it never asked; a for-each node's waits for a busy pipeline are not recorded apart from its other
  waits, and the page says so. Nothing is estimated: a node whose pipeline run times are not known
  (deleted, or from before they were recorded) has an unsplit bar, and the totals say of how many
  pipeline runs they speak. The flow page's panel keeps its open or collapsed state under its own
  key (`kilnmark.flow.panel`), and reads the latest run only while its Run or Nodes tab is shown.
  Run now shows the Run tab (`dock.show("run")`), whichever tab was showing, as the pipeline page
  does. Saving a version empties the diagram's undo steps and the line that says the last change.
- **Where the diagram starts.** The diagram is fitted on opening, never below half size
  (`fitViewOptions.minZoom`). A flow wider than its frame at half size would be centred, its first
  node off the left edge: `ShowTheStart` (`FlowDiagram.tsx`) then moves the view so the flow's left
  edge is 56 pixels in, clear of the zoom buttons (`startInView`, pure and tested). A flow that
  fits is left as fitted.
- **The panel's Nodes and Settings tabs, built.** The flow page's panel has the pipeline page's
  tabs in the same order: Nodes, Settings (only while a designer has a node or connection chosen),
  Run, History, Versions; a new flow has Nodes and Settings. Nodes is a table of every node, what it
  runs after and how, and its part in the latest run (none on an old version's page); a node's name
  chooses it. Under the table are the forms to add a node and to connect
  two, which were under the diagram. Choosing a node or connection, anywhere, shows Settings, even when it
  is already chosen; when nothing is chosen any more (a choice renamed or removed in the YAML is
  dropped) the panel goes back to the tab shown before. A node's settings that
  take one value are fields there, per type, as `FlowDefinition` reads them: `pipeline`, `version`,
  `as`, `max_parallel`, `severity`, `title`, `message`, `for`, `status`, `source`. They are
  written by `setNodeSettings` (`edit.ts`): in place when the setting is a single value, after the
  node's last pair otherwise, quoted when YAML would read the text as something else (in a node
  written on one line, also at a comma or bracket), a number as typed; blank removes it. A value
  written over several lines, and removing the key on the node's first line, are left to the YAML. Lists and mappings (`items`, `with`, `statements`) stay in the YAML.
  `FlowGraphNode.settings` carries each node's single-value settings for the form.
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

## 8. Parallel runs: `max_parallel` and the for-each node (ADR 0040)

Built 4 November 2026, as slices P3 and P4 of docs/design/parallel-runs.md.

**A flow's `max_parallel`** (P3). A top-level key, a whole number from 1 to 50, checked when saved;
no limit when left out. `FlowEngine` takes the limit and the nodes never held back: a node that
could start while that many run stays pending (not skipped) and starts, in the order the nodes are
written, when one ends. `stop` nodes are never held back, so a flow can always end; a stop node that
runs skips what was held. `ready()` first works out every skip (a skip can make other nodes ready),
then hands out places in written order, so a node that became ready only after a skip still goes
before a later one. A for-each node counts once, however many items it runs. The limit is in
the engine rather than in `FlowService.startReady`, because the engine marks a node running as it
hands it out; holding it there keeps the engine's states true and lets kiln-core test it.

**The `for-each` node** (P4). It runs one saved pipeline once for each item of a list:

```yaml
  - id: regions
    type: for-each
    pipeline: sales-by-region
    version: 2                    # optional, as for run-pipeline
    items: [EMEA, APAC, AMER]     # or "${regions}", a text list or number list parameter of the flow
    as: region                    # the pipeline parameter each item fills
    with: { label: nightly }      # other parameters, as a run-pipeline node's
    max_parallel: 3               # items running at once: 1 to 50, 4 when left out
```

- **Checked when saved** (kiln-core): `pipeline`, `items` and `as` are needed; `items` is a list of 1
  to 500 values, all texts or all numbers (never true/false, a list or `${...}`), or exactly one
  `${name}` naming a list parameter of the flow (which holds 1 to 200 values, `Parameters.MAX_LIST`);
  `as` is a parameter name and not also in `with:`; `max_parallel` 1 to 50. The server then checks
  that the pipeline exists (and the version), that `with:` suits it as for a run-pipeline node, and
  that it takes `as` as one value of the items' kind. The flow's own parameter of the same name as
  `as` is not passed on: the item fills it.
- **Each item writes its own asset** when items run at once. A run waits only
  `kilnmark.pipelines.wait-for-target` (30 seconds) for another writing the same asset, holding a
  worker slot, then fails ("Another pipeline run is writing ..."); so items sharing a target would
  not take turns, they would fail. With `max_parallel` above 1, saving refuses a node whose
  pipeline (the version it runs) has a write whose target, table or path, and source, do not name
  `${<as>}` (`FlowService.sharedTarget`): "Every item would write t, so they would fail waiting for
  each other: put ${region} in the write's target, or set max_parallel: 1". The check is skipped
  when the version cannot be read.
- **Runs.** Each item is an ordinary pipeline run, queued as the flow's starter with their role, with
  the node's values and the item in `as`, linked to the flow run, node and item
  (`pipeline_run.flow_item`, from 0; V31). Items start in the order listed while fewer than the
  node's `max_parallel` run, so with worker slots and source limits they share the queue with
  everything else. `PipelineStore.queueRun` keeps one run of a pipeline at a time, except between
  the items of one for-each node in one flow run (ADR 0040, decision 5). A person's Run now of the
  pipeline is refused with 409 while items run (only flow nodes wait); another flow's node waits for
  them, and the items wait while another run of the pipeline goes ("Waiting for p to finish its
  current run", giving up after `queue_timeout`). An item run whose flow run was deleted
  (`flow_run_id` set to null) still counts as another run. Before an item starts, the flow looks for its run by flow run, node and
  item (`PipelineStore.flowItemRun`), and the node finds the runs it already has when it starts
  (`flowItemRuns`); the unique index refuses a second run of an item whatever happens.
- **Ending.** Every item runs: a failed item does not stop the others, and those already started are
  never stopped by it. The node then succeeds when every item's run succeeded, and fails otherwise
  ("2 of 5 items failed; their pipeline runs say why"); an item that could not be queued counts as
  failed and its reason (the flow's own words, the values left out for viewers) is added; the node's
  row records those items (`reported.not_started`), so the run page shows them failed, with no
  pipeline run, and its count agrees with the message. A cancel of
  the flow, or a stop node, cancels the items still going, starts no more, and the node ends
  cancelled. Someone cancelling one item's run by hand does the same, and so stops the flow, as for a
  run-pipeline node. It reports `items`, `succeeded` and `failed` for conditions and `${...}`.
- **The worker stopping** cancels the items still going and fails the node, as for other nodes.
  A worker that takes the flow over finds its items' runs by flow run, node and item rather than
  starting them twice; a later try of the flow runs only its items that did not succeed (ADR 0053).
- **API and pages.** A for-each node's run (`NodeRunView.items`) lists each item: its place, its
  value (withheld from viewers when the items came from a run's parameter, which can hold server
  variables), its status (`pending` before it starts, `queued`, running, succeeded, failed or
  cancelled, `skipped` if the node ended without starting it) and its pipeline run with that run's
  times. The run page's row says "3 of 5 items done, 1 failed" and lists the items with links; the run
  chart draws the node's bar from its first item's start to its last item's end. The diagram says
  "Runs a pipeline for each item" and "sales-by-region for each of 3 items" (or "for each of
  regions"); the add-node form offers it with a pipeline, starting with `items: []` for the designer
  to fill.

## The run timeline (5 November 2026)

The flow run page's **Run timeline** replaced the run chart. It nests the run as it ran: the flow,
each node, a `for-each` node's items, and under a node or item that ran a pipeline, that pipeline
run's nodes. `ui/src/flows/runTree.ts` builds the rows (pure, unit tested): a pipeline run's nodes
are placed from when that run started (its own times measured from its worker's start), so they
sit inside their parent's bar. The page reads a pipeline run (`GET /api/v1/pipeline-runs/{id}`)
only when its row is open: every node's run at first, an item's when it is opened, and again while
it runs. Choosing a row shows what was recorded about it; nothing new is stored or sent. The
pipeline run page's timeline has the same look (docs/design/pipelines.md).
