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

- **Nodes** do the work: `run-pipeline` runs a saved pipeline, `notify` sends a notice, `wait`
  waits a while, `stop` ends the flow, and `sql` runs statements on a PostgreSQL source.
- **Connections** say what runs after what, and when: `success` (the default), `failure`,
  `always`, or a condition on what an earlier node reported, such as its `score`,
  `quarantined_share`, `rows_written` or `status`.
- **Parameters** are values each run can change. One with no default must be given when the flow
  runs.

The full format, the condition language and how a run decides what starts next are in the design
notes (`docs/design/flows.md`). The sample flows that ship with Kilnmark, in `samples/08-flows`,
show one idea each.

## The diagram

Above the YAML, **Diagram** draws the flow as you write it: a box for each node, saying what it does
(the pipeline it runs, the notice it sends, how long it waits), and an arrow for each connection,
labelled with when it is taken:

- a solid arrow, **on success**: the next node runs when this one succeeds (the default);
- a red dashed arrow, **on failure**: it runs when this one fails;
- a dotted arrow, **always**: it runs whether this one succeeds or fails;
- an amber arrow with the condition: it runs when this one succeeds and the condition holds.

The diagram is drawn from left to right, with each arrow routed round the nodes it passes. It
follows the text: while the YAML cannot be read, it says so and waits. Zoom with the buttons in its
corner; **The connections in words**, under it, lists every connection as a sentence. To change the
flow, change the YAML: editing on the diagram comes later.

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

Open a run from the flow's **Runs** list. The run page shows:

- its status, the version that ran, who started it, and, for stewards and above, the values it ran
  with (viewers do not see them, nor the messages that quote them, since they can hold server
  variables);
- the **diagram** of that version, each node outlined and labelled with how it stands in the run,
  kept current while the run goes;
- the **run chart**: a bar for each node on one time line from the run's start, green when it
  succeeded, red when it failed, blue while it runs; a skipped node is hatched across its row. A
  bar for a node that ran a pipeline opens that pipeline run;
- every node of that version in order: its status (Not started, Running, Succeeded, Failed,
  Cancelled or Skipped), when it started, how long it took, and what happened, such as the rows a
  pipeline wrote and its score;
- for a node that ran a pipeline, a link to that **pipeline run**, where its nodes, rows and errors
  are.

A node's message says that a pipeline failed and where to look, never why: why can quote values
from the data, and the pipeline run's own page shows it to those who may see it.

A node is **Skipped** when no connection into it was taken: the flow went another way.

To stop a run, open it and choose **Cancel run** (stewards). Nothing more starts, and the pipeline
runs it started that are still going are cancelled. What they had already written stays written.

## What is not here yet

- Editing a flow on the diagram: for now, edit its YAML.
- Deleting a flow.
- Adding the sample flows from **Add samples**: for now, copy one from `samples/08-flows` into a
  new flow.
- Running flows on a schedule.
