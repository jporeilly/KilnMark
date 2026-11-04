# Building and running a flow

A pipeline moves and checks data. A **flow** runs pipelines in an order, and decides what runs next
from how each ended and what it measured: carry on only if a gate set aside under 2% of the rows,
else tell the stewards and stop. Each flow is saved as YAML, with every version, and every run is
recorded node by node.

## Before you start

- **Viewers** read flows and their runs.
- **Stewards** run flows, give their parameters values, and cancel runs.
- **Designers** add flows and save new versions. A flow with a `sql` node is saved only by a
  designer whose token sees unmasked values, since SQL can read and change any column its source's
  login can.

A flow runs saved pipelines, so build those first, under **Pipelines**. The pipelines a flow starts
run as the person who started the flow, with their role: a flow cannot do what its starter could
not.

## The Flows page

**Flows**, in the sidebar after Pipelines, lists every flow with its latest version, whether that
version can run, and its last run. Stewards see **Run now** beside each; designers see **New flow**.

## Writing a flow

Choose **New flow**. The page starts from a small flow that runs one of your pipelines and, if it
fails, sends a notice and stops. Change it to what you need; the name on its `name:` line is the
flow's, and is fixed once saved.

```yaml
kilnmark: flow/v1
name: nightly-customers
description: Clean customers, then total the orders only if quality holds.

parameters:
  limit_share: { type: number, default: 0.05 }

nodes:
  - id: clean
    type: run-pipeline
    pipeline: customers-clean

  - id: totals
    type: run-pipeline
    pipeline: order-totals

  - id: halt
    type: stop
    message: Too many customers were set aside

connections:
  - { from: clean, to: totals, when: "clean.quarantined_share < limit_share" }
  - { from: clean, to: halt, when: "clean.quarantined_share >= limit_share" }
  - { from: clean, to: halt, when: failure }
```

- **Nodes** do the work: `run-pipeline` runs a saved pipeline, `for-each` runs one once for each
  item of a list, `notify` sends a notice, `wait` waits a while, `stop` ends the flow, and `sql`
  runs statements on a PostgreSQL source.
- **Connections** say what runs after what, and when: `success` (the default), `failure`,
  `always`, or a condition on what an earlier node reported, such as its `score`,
  `quarantined_share`, `rows_written` or `status`.
- **Parameters** are values each run can change. One with no default must be given when the flow
  runs.

Nodes with no connection between them run at the same time. The full format, the condition
language and how a run decides what starts next are in the design notes (`docs/design/flows.md`). The sample flows that ship with Kilnmark, in `samples/08-flows`,
show one idea each.

## Running one pipeline for each item

A `for-each` node runs one pipeline once for each item of a list, giving each run the item as one of
the pipeline's parameters. Use it to load each region, each file or each year with the same
pipeline, several at once.

```yaml
kilnmark: flow/v1
name: nightly-regions
max_parallel: 2               # at most two of this flow's nodes run at once

nodes:
  - id: regions
    type: for-each
    pipeline: sales-by-region
    items: [EMEA, APAC, AMER]   # or "${regions}", a text list or number list parameter of the flow
    as: region                  # the pipeline's parameter that each item fills
    with: { label: nightly }    # its other parameters, as for run-pipeline
    max_parallel: 3             # items running at once; 4 when left out, 50 at most

  - id: tell
    type: notify
    title: "${regions.succeeded} of ${regions.items} regions loaded"

connections:
  - { from: regions, to: tell, when: "regions.failed = 0" }
```

- **Items** are 1 to 500 texts or numbers written in the node, all of one kind, or one list
  parameter of the flow, so each run of the flow can give its own list; a list parameter holds at
  most 200 values. `as` must be a parameter the pipeline takes, of the items' kind; saving the flow
  says so if it is not.
- **Each item is an ordinary pipeline run**, started as you, so it shows on the pipeline's own runs
  and waits for a free worker like any other. The items of one node may run the pipeline at the same
  time. While they run, **Run now** on that pipeline is refused (a run is already going); another
  flow's node that runs it waits for them, and the items wait for any other run of it.
- **Each item must write its own table or file.** A run waits only a short while for another
  writing the same one, then fails. So when items run at once, saving the flow refuses a pipeline
  whose write does not name the item's parameter in its target (here, `${region}` in the path), and
  says to put it there or to set the node's `max_parallel: 1`, which runs the items one after
  another.
- **Every item runs**, even when one fails. The node then succeeds if every item did, and fails if
  any failed; a connection's condition can read `regions.items`, `regions.succeeded` and
  `regions.failed`. An item the pipeline would not run with its value counts as failed, and the
  node's message says why. Cancelling the flow cancels the items still running and starts no more.
  Cancelling one item's pipeline run by hand cancels the whole flow, as cancelling a run-pipeline
  node's run does.
- **The flow's own `max_parallel`** (1 to 50) caps how many of its nodes run at once; nodes wait
  their turn in the order they are written. A for-each node counts once, however many items it
  runs, and a `stop` node never waits, so a flow can always end. Left out, there is no limit.

The sample `samples/08-flows/for-each-region.yaml` runs one pipeline for three regions, two at once.

## The diagram

Above the YAML, **Diagram** draws the flow as you write it: a box for each node, saying what it does
(the pipeline it runs, the notice it sends, how long it waits), and an arrow for each connection,
labelled with when it is taken:

- a solid arrow, **on success**: the next node runs when this one succeeds (the default);
- a red dashed arrow, **on failure**: it runs when this one fails;
- a dotted arrow, **always**: it runs whether this one succeeds or fails;
- an amber arrow with the condition: it runs when this one succeeds and the condition holds.

The diagram is drawn from left to right, with each arrow routed round the nodes it passes. It
follows the text a moment after you stop typing; while the YAML cannot be read, it says so and
keeps showing the flow as it last could be read. Zoom with the buttons in its corner; **The
connections in words**, under it, lists every connection as a sentence.

## Changing the flow on the diagram

Designers can change the flow on the diagram as well as in the YAML. Each change is written into
the YAML below at once, changing only the lines it must, so your comments and spacing stay as they
are. Nothing is saved until you save a new version.

![The Diagram section of a flow's page: a node that runs a pipeline joined to a notice and to a second pipeline by two amber connections, each labelled with its condition on the share of customers set aside; under it, the forms to add a node (type and pipeline) and to connect two nodes (from, to and when it is taken)](images/flow-diagram-edit.png)

- **Add a node.** Under the diagram, choose its type (and, for one that runs a pipeline, the
  pipeline), then **Add node**. It gets an id from its type or pipeline, and the settings it starts
  with; the problems under the YAML say what else it needs, such as a `sql` node's source.
- **Connect two nodes.** Drag from a node's right edge to another node: that connection is taken
  when the first succeeds. Or choose **From**, **To** and when it is taken under the diagram, then
  **Connect**. A connection that would join a node to itself, make a loop or repeat one already
  there is refused, and the note under the diagram says why.
- **Change or remove a node or connection.** Choose it on the diagram (click it, or Tab to it and
  press Enter), or choose **Change** beside a connection in **The connections in words**. Its tools
  appear under the diagram: change when a connection is taken, including its condition, or remove
  it. Removing a node removes its connections too. A node's settings are changed in the YAML.
- **Move a node.** Drag it, or choose it and use the arrow keys. The diagram then keeps every node where you see it, in a `layout:`
  block at the end of the YAML; connections between the same two nodes are drawn apart, and labels
  are drawn over the nodes. **Lay out again** removes the block, and the diagram lays the flow out
  itself.
- **Undo** puts back what the last change on the diagram changed, while the YAML is as that change
  left it. To go back to the saved version, choose **Undo my changes** under the YAML.

The `layout:` block only says where the diagram draws each node: a flow runs the same however it
is drawn.

## Checking as you type

The YAML is checked a moment after you stop typing: its shape, its connections (no loops), its
conditions, and that every pipeline it runs exists. Problems are listed under the editor with their
line; choose one to move to that line. Lines with a problem are marked in the margin.

The parameters the flow declares are listed under the problems, saying which must be given.

## Saving versions

Every save keeps a new version; earlier ones never change.

1. Say what you changed under **What changed**.
2. Choose **Save new version**.

A flow with problems can be saved as a draft, so you can come back to it, but it cannot run until
they are fixed.

If someone else saved a version after you opened the page, Kilnmark refuses your save rather than
undo theirs unseen. Your text stays on the page. **Load the latest version and keep my text** loads
theirs, and **Put my changes back** brings yours back, to compare or to make your change again.

**Versions**, at the foot of the page, lists every version with who saved it, when and why. Open
one to see its YAML exactly as it was saved.

## Running a flow

Choose **Run now**, on the Flows page or the flow's own page. A flow with parameters asks for their
values first, each filled with its default: change what you need and choose **Run**. Lists are
written with commas between the values.

Kilnmark checks the latest saved version again first, since a pipeline may have changed, then
queues the run. Unsaved changes on the page are not part of it. Only one run of a flow is queued or
running at a time.

## Reading a run

Open a run from the flow's **Runs** list.

![A run page: the run succeeded; its diagram shows the two pipeline nodes outlined green as Succeeded and the notice node dashed as Skipped; the run chart has a bar for each pipeline node, one after the other, and a hatched row for the skipped one; the Nodes table gives each node's status, start, time taken, rows written and score, with a link to its pipeline run](images/flow-run-page.png)

The run page shows:

- its status, the version that ran, who started it, and, for stewards and above, the values it ran
  with (viewers do not see them, nor the messages that quote them, since they can hold server
  variables);
- the **diagram** of that version, each node outlined and labelled with how it stands in the run,
  kept current while the run goes;
- the **run chart**: a bar for each node on one time line from the run's start, with its status
  beside it: green when it succeeded, red when it failed, striped blue while it runs; a skipped
  node is hatched across its row. A bar for a node that ran a pipeline opens that pipeline run;
- every node of that version in order: its status (Not started, Running, Succeeded, Failed,
  Cancelled or Skipped), when it started, how long it took, and what happened, such as the rows a
  pipeline wrote and its score;
- for a node that ran a pipeline, a link to that **pipeline run**, where its nodes, rows and errors
  are;
- for a `for-each` node, how far its items have got ("3 of 5 items done, 1 failed") and each item
  with its status and a link to its pipeline run; an item that could not be started is shown failed,
  with no run, and the node's message says why. On the run chart its bar runs from its first
  item's start to its last item's end. Viewers see the items' values when they are written in the
  flow, not when they came from the run's values.

A node's message says that a pipeline failed and where to look, never why: why can quote values
from the data, and the pipeline run's own page shows it to those who may see it.

A node is **Skipped** when no connection into it was taken: the flow went another way.

To stop a run, open it and choose **Cancel run** (stewards). Nothing more starts, and the pipeline
runs it started that are still going are cancelled. What they had already written stays written.

## What is not here yet

- Changing a node's settings on the diagram: for now, change them in the YAML.
- Deleting a flow.
- Adding the sample flows from **Add samples**: for now, copy one from `samples/08-flows` into a
  new flow.
- Running flows on a schedule.
