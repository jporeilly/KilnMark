# Building and running a pipeline

This guide shows you how to build a pipeline in Kilnmark: where pipelines live, how their YAML is
written, how saving keeps every version, how to run one and how to read what a run did.

A pipeline reads rows from saved sources, changes them and writes them somewhere. Kilnmark checks
it as you write it, runs it on a worker, records every step, and adds what it wrote to the
catalogue with where it came from.

## Before you start

- **Your role.** Everyone signed in can see pipelines and their runs. You need the steward or
  admin role to add, change, run or delete them.
- **Sources.** Pipelines name saved sources; they never hold passwords. Add the sources first
  (see **Sources**), and scan the ones you read from, so Kilnmark knows their columns.
- **Writing needs permission.** Every source is read-only to pipelines until an admin turns on
  **Allow writing** for it: open the source, choose **Edit**, and set it on the last step. Ask an
  admin if a pipeline of yours writes to a source that does not allow it yet.

## The Pipelines page

Choose **Pipelines** in the navigation. The list shows each pipeline with its description, its
latest version (and whether that version has problems), and its last run with its status in
words: Queued, Running, Succeeded, Failed or Cancelled.

Stewards see **Run now** beside each pipeline, and **New pipeline** at the top.

## Starting a new pipeline

Choose **New pipeline**. The editor opens with a template that reads 100 customers from the lab
database (`lab-db`) and writes them as a CSV file to the lab's warehouse bucket (`lab-warehouse`),
the one lab source that allows writing. All the lab's data is made up.

1. Give the pipeline a **Name**: small letters, digits and dashes, such as `customer-billing`.
   The name cannot change once saved; the YAML's `name` line follows what you type.
2. Optionally give it a **Display name**, which is what people see.
3. Change the YAML (see below), watching the problems list under the editor.
4. Choose **Save pipeline**.

## YAML basics

A pipeline starts with the format line and its name, then lists its steps:

```yaml
kilnmark: pipeline/v1
name: customer-billing
description: Monthly spend per customer.
steps:
  - id: customers
    type: read
    source: lab-db
    asset: crm.customers

  - id: spend
    type: write
    input: customers
    source: warehouse
    table: billing.customer_spend
    mode: replace
```

- Each step has an **id** (small letters, digits, `-` and `_`) and a **type**. The other keys are
  the step's settings.
- **input** says which step feeds this one: `input: customers` takes that step's main output.
  `input: joined.rejected` takes another output port, here the rows a lookup could not match.
  A step with several inputs names each one: `inputs: {main: spend, lookup: customers}`.
- Steps can be listed in any order; the inputs decide the order of work.
- A key Kilnmark does not know is a problem, with its line, so typing mistakes are caught early.
- Comments start with `#`.

### Reading

The `read` step takes `source` (a saved source's name) and `asset`: a table or view as
`schema.table`, or a file's path inside the source. `row_limit` reads only the first rows, which
is handy while you try a pipeline out; 0 reads them all.

### Writing

The `write` step takes `source` and where to write: `table` for a database, `path` for a file
(or `target` for either). Files also take `format`: `csv` or `parquet`.

`mode` says what happens to what is already there:

| Mode | What it does |
| --- | --- |
| `create` | Writes a new table or file, and fails if it exists. The default. |
| `append` | Adds the rows to what is there. |
| `replace` | Writes the rows in place of what is there, swapping them in at the end. |
| `upsert` | Updates rows with the same `key` and adds the rest. Needs `key`, such as `key: [customer_id]`. |

A write step also takes the settings of the source's writer, such as `commit_every` for large
table writes, `overwrite` and `delimiter` for files, `compression` for Parquet, and
`profile_output: false` to skip profiling what is written. They are written beside the step's other
settings, as in [Writing data from a pipeline](writing-data.md), which describes them; a setting the
source's writer does not take is a problem, with its line.

The steps between reading and writing (filter, compute, select, lookup, aggregate, union, dedupe,
route and limit) are described in [Pipeline steps and expressions](pipeline-steps.md). The step
catalogue (`GET /api/v1/steps`) lists every type this server has, with the settings each takes.

## Checking as you type

A moment after you stop typing, Kilnmark checks the YAML. Under the editor it says how many
problems it found, and lists each one with its line and the step it is about. Lines with a problem
are marked with `!` beside their number.

- **Choose a problem** to move the cursor to its line.
- **Notes** do not stop a run. For example, a table that has not been scanned yet has columns
  Kilnmark only learns when the pipeline runs.
- In the editor, Tab indents by two spaces. Press Escape and then Tab to move on to the next
  control.

The **Steps** panel draws the pipeline as a graph: each step with its input ports on the left
and output ports on the right, and lines from port to port. Hover over or move the focus to a step
to see the columns leaving it. The table under the graph says the same in words; choose a step's
id there to go to its line.

## Saving versions

Every save keeps a new version; earlier ones never change.

1. Say what you changed under **What changed**, such as "Drop test customers".
2. Choose **Save new version**.

A pipeline with problems can be saved as a draft, so you can come back to it, but it cannot run
until they are fixed.

If someone else saved a version after you opened the page, Kilnmark refuses your save rather than
undo theirs unseen. Copy your text if you want to keep it, choose **Load the latest version**, and
make your change again.

The **Versions** list shows every version with who saved it, when and why. Open one to see its
YAML exactly as it was saved.

## Running a pipeline

Choose **Run now**, on the Pipelines page or the pipeline's own page. Kilnmark checks the latest
version again first, since a source may have changed, then queues the run for a worker. The run
appears under **Runs** as Queued, then Running.

A run uses the version that was latest when you asked, even if someone saves another while it
waits.

To stop a run, open it and choose **Cancel run**. A queued run is cancelled at once; a running one
stops within a few seconds. Rows already written stay written.

## Reading a run

Open a run from the **Runs** list. The page updates itself until the run ends, and says when its
status changes.

- **Summary:** the status, the version that ran, who asked, when it started and finished, how long
  it took and how many rows its writes received. A failed run says why, naming the step where the
  failure started.
- **Steps:** for each step, how it ended (Finished; Ended early, as a `limit` does once it has its
  rows; Stopped by the engine, because another step failed or the run was stopped; or Failed), the
  rows that came in and went out, with the count on each port when a step has several (for
  example, a filter's `out` and `rejected`), the most memory it held, and its error if it failed.
  A write that was stopped wrote nothing, unless it used `commit_every`.
- **What it wrote:** each table or file a write step wrote, with its mode and rows. Choose one to
  open it in the catalogue.
- **Lineage:** which assets each written asset came from.

## What Kilnmark records about what you write

When a run succeeds, each table or file it wrote is added to the catalogue, or updated there, as a
scan would add it: its columns, and for `create`, `replace` and files its row count and the
profile of each column, which the write measures as it writes the rows, so its quality scores
update too. The source's own rules on keeping example values apply. An `append` or `upsert` adds
to what was there, so Kilnmark records how many rows the run wrote and leaves the table's columns,
row count and profile as its last scan found them; scan the source to refresh them. If a profile
could not be made, the write still counts and the run says so.

Kilnmark also records the lineage: the written asset came from the assets the pipeline read. It is
kept even if the pipeline is later deleted, so the next pipeline that reads the written table
starts from what is known about it.

Messages never include a source's password, and pipelines connect only to the hosts your
administrator allows.
