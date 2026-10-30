# Building and running a pipeline

This guide shows you how to build a pipeline in Kilnmark: where pipelines live, how their YAML is
written, how saving keeps every version, how to run one and how to read what a run did.

A pipeline reads rows from saved sources, changes them and writes them somewhere. Kilnmark checks
it as you write it, runs it on a worker, records every node, and adds what it wrote to the
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

Stewards see **Run now** beside each pipeline, and **Add samples** and **New pipeline** at the top.

## Starting a new pipeline

Choose **New pipeline**. The pipeline opens on the canvas with a template that reads the first 100
rows of a table or file from one of your sources and writes them to a source that allows writing:
the first of each that you have, by name. Where you have no such source yet, the template shows a
placeholder in angle brackets, such as `<your source>`, which the check reports until you replace
it with a source's name. To start from nothing instead, choose **Start with an empty canvas**;
**Start from the template** brings the template back.

1. Give the pipeline a **Name**: small letters, digits and dashes, such as `customer-billing`.
   The name cannot change once saved; the YAML's `name` line follows what you type.
2. Optionally give it a **Display name**, which is what people see.
3. Build it on the canvas (see [Building on the canvas](#building-on-the-canvas)) or change its
   YAML (see below), watching the problems list under both.
4. Choose **Save pipeline**.

## Adding the samples

Kilnmark comes with samples: small pipelines, one idea each, such as reading some columns and
rows, filtering and routing, lookups, totals, quality gates and parameters. Each is named
`sample-...` and starts with comments on what it shows and what to look for. They name their
sources by placeholders rather than real sources:

| Placeholder | Stands for |
| --- | --- |
| `crm-db` | A database with schemas `crm` (customers, customers_archive_2023, accounts) and `billing` (transactions, invoices) |
| `shop-db` | A database with schemas `shop` (customers, orders, order_lines) and `stock` (products, supplier_feed) |
| `files` | File storage the samples write to, under `samples/`; an admin must turn on **Allow writing** for it |

1. On the **Pipelines** page, choose **Add samples** (stewards and admins).
2. For each placeholder, choose one of your saved sources, or leave it as **Don't add the samples
   that use it**. The dialog says how many samples use each.
3. Choose **Add samples**. The dialog lists what happened to each: added, already here (a sample
   whose name is taken is left as it is, so your changes are kept), or not added and why.

Each sample is checked against what Kilnmark has scanned of your sources, so scan them first: a
table a sample reads that the scan did not find saves it as a draft with problems. Open it and
change its `asset` to one of yours, or its columns. The sample flows are not added yet; they are
in the release's `samples` folder to read. Through the API, `GET /api/v1/samples` lists the
placeholders and samples, and `POST /api/v1/samples` with
`{"sources": {"crm-db": "my-database", "files": "my-bucket"}}` adds them.

## Building on the canvas

The **Canvas** and **YAML** tabs show the same pipeline: a change on the canvas rewrites only the
lines it changes in the YAML, so your comments, layout and every other setting stay exactly as
they were, and a change to the YAML redraws the canvas. Kilnmark remembers which tab you used last.

The canvas uses the page's full width. On the left is the node list, which **Nodes** in the canvas
toolbar hides and shows again; the pipeline fills the rest, with an overview in its corner for finding your way
around a large one. The selected node's settings open in a panel over the right of the canvas (below
it on a narrow screen), and close with its **×** or when nothing is selected.

### Adding and connecting nodes

- **Add a node** by dragging its type from the list onto the canvas, or by choosing it: with a node
  selected, the new node is added after it and reads from it.
- **The node list** is in folders: Read, Write, Filter and route, Transform, Combine, Aggregate,
  Quality, AI and Other, each with how many types it holds, and **Recently used** first with the
  last five types you added. **Find a node** searches every folder at once (the folders with a
  match open by themselves). The list sits beside the canvas, which narrows to make room, so it never covers your nodes (on a phone it opens over the canvas). Folders
  holding the nodes your pipeline uses start open, and Kilnmark remembers in this browser which
  folders you open.
- **Each node** shows its type, its id, a line summing up its settings, its input ports on the
  left and output ports on the right, and how many problems it has. Hover over a node, or move the
  focus to it, to see the columns leaving it.
- **Connect nodes** by dragging from an output port to an input port. A connection Kilnmark cannot
  make is refused with the reason: a node reading from itself, a loop, or an input that already
  reads from another node (combine rows with a union node instead). A solid line leaves a node's
  main output, `out`; a line from any other output (a filter's or lookup's `rejected`, a gate's
  `quarantine`, a route's named routes) is dashed and labelled with that output's name.
- **One output feeding several nodes gives each of them every row.** Connect a node's `out` to an
  aggregate and to a write, and the aggregate totals all the rows while the write writes all of
  them. Rows are never shared out between the nodes. To send different rows different ways, say
  which way each goes: a **route** node sends each row down the first route whose condition it
  meets (and the rest through `out`), a **filter** keeps the rows meeting its condition and sends
  the others through `rejected`, and a quality gate can set failing rows aside through
  `quarantine`.
- **Remove a connection** with **Disconnect** beside it in the settings of the node it feeds, or
  click it and press Delete.
- **Move** a node by dragging it. **Auto-layout** lays the nodes out again from left to right.
  **Fit** shows the whole pipeline, however small that makes it; **Readable size** shows it at a
  size you can read, from its first nodes if it is too wide for that. The overview in the corner
  shows where you are: drag in it to move around.
- **Duplicate** copies a node with its settings and inputs; **Delete node** removes it and its
  connections. **Undo** and **Redo** step back and forward through your changes, including a burst
  of typing in the YAML tab and loading the latest version; they are off while the canvas cannot
  change the pipeline.

Where each node is drawn is kept at the end of the YAML, in a `layout` block. Kilnmark ignores it
when it runs the pipeline, so moving nodes never changes what a pipeline does, and **Run now** does
not ask about unsaved changes that only move nodes (saving them suggests the message "Moved
nodes"):

```yaml
# Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.
layout:
  customers: {x: 40, y: 120}
  tidy: {x: 320, y: 120}
```

### Settings

Select a node to see its settings. Each has its help and, where there is one, its default; a
setting left at its default is left out of the YAML.

- On and off settings are switches; numbers, choices and text are fields and lists.
- **Source** lists your saved sources; for a write, those that do not allow writing say so.
  **Table or file** suggests the tables and files of the chosen source.
- Column settings offer the columns arriving at the node, as the last check worked them out, so
  you choose rather than type. Before the columns are known you can type names.
- Expressions (a filter's condition, computed columns, aggregate values, routes) have the arriving
  columns as buttons below them: choosing one puts its name where the cursor is.
- A write node shows the settings of its source's writer, such as **Commit every N rows** for a
  database or **Overwrite** for files, once you choose the source.
- **Node id** renames the node, and the nodes reading from it follow.

Problems the check finds show on the node (as a count), under the setting they are about, and in
the problems list under the canvas. Choosing a problem in the list selects its node and moves to
the setting. If the YAML cannot be read at all (a typing mistake in the YAML tab), the canvas shows
the last pipeline that could be read, does not let you change it, and says so; fix the YAML to go
on.

### After a run

Once a run has finished, each node on the canvas shows the rows it passed on and how it ended
(Finished, Ended early, Stopped by the engine or Failed), and each connection its rows. Each write
and each quality gate also shows its measured quality, as a percentage and a word (Good, Fair or
Poor), and a gate what it did with the rows, such as "900 passed · 100 quarantined" (see
[Quality gates](#quality-gates)), beside the score Kilnmark predicted for it (see
[Predicted quality](#predicted-quality)). The nodes table under the canvas, and the node's settings panel
with its score per dimension and each rule's result, say the same in words.

Each kind has its own look, and its own words, so none depends on colour: the rows (and
"Finished") in a grey chip with a rows icon, with Ended early, Stopped or Failed in a chip of its
own; the measured score in a solid chip with a gauge icon, green, amber or red as its word says;
the prediction in a violet chip with a dashed edge and a sparkles icon, **Predicted ≈ 97%**, as it
is an estimate; and a gate's counts as plain text. Each is on a line of its own.

The canvas makes room for these lines and for the connections' labels: where a layout saved before
the run is too tight, it moves nodes apart on the screen only, and the YAML's `layout:` stays as it
was. **Auto-layout** saves a layout with room for them, and moving a node saves where it is shown.

**While a run goes**, the canvas looks again every two seconds. Each node shows its rows so far
and how many it is handling a second ("19,814 out · 8,351 rows/s"), or "Waiting for rows", and
each connection the rows that have crossed it. The node holding the run back says **Slowest**, in
a chip of its own with an hourglass, and has an orange edge: rows are queuing in front of it while
the nodes after it keep up, so it is the one to look at first (a slow query in a `read`, a
`lookup` against a large table, a `write` to a busy database).

**Run now** runs the saved version. If you have changes that are not saved, Kilnmark says so first
and asks you to confirm; save them to run them.

### Preview a node

To see the rows leaving a node before you run anything, right-click it (Ctrl+click on a Mac) and
choose **Preview rows**,
or select it and open the **Preview** tab beside its settings. From the keyboard, press Shift+F10
(or the menu key) on a node for the same menu. For a node with more than one output, such as a
quality gate's **out** and **quarantine** or a filter's **out** and **rejected**, choose the
**Output**; choose how many **Rows** to see (100 unless you choose another number, at most 1,000).

The preview runs the pipeline as it is on the canvas, saved or not, but only the node and the nodes
before it. Nothing is written: writes never run in a preview, and a gate's quarantined rows go
nowhere. It shows the columns with their kinds, the rows, and how long it took. Each node before it
stops as soon as the preview has its rows, so a preview of a large table is quick, unless a node
before it has to read all its rows first: an aggregate, or a lookup's lookup side. The preview then
says so, and a preview stops after 30 seconds. To try such a pipeline out, set a **Row limit** on
the read node for a while.

Previews follow the same rules as previewing a source:

- They are for stewards and admins. Each person runs one preview at a time and up to six a minute,
  and a server runs two at once. A preview runs at most 50 nodes, 10 of them reads; a node that holds
  rows (an aggregate, a dedupe, a lookup or a union) is given a share of the preview's memory,
  whatever its own `memory_limit_mb` says.
- Sources are reached as a run reaches them, with their saved credentials and the allowed hosts.
- Columns marked sensitive can pass through a preview, kept, renamed or carried along, and are
  masked (••••) for everyone but admins. But no node before the one you preview may look at their
  values: a calculation or condition that uses one, a cast, a group, dedupe or lookup key on one,
  or a quality gate with a rule on one. For everyone but admins such a preview is refused, and
  says which node and column, because the rows shown, a value worked out from it, or even whether
  the preview works, could give the values away. Preview the node before it, or ask an admin.
- When a node fails, its message may quote values from the rows; if anything read has columns
  marked sensitive, only admins see it, as with a run's errors.

The rows are read when you ask and not kept. After you change the pipeline, the preview says it is
out of date; choose **Preview again**.

### Using the keyboard

Everything on the canvas can be done without a mouse. Press Tab to move from node to node (each
node is described when it gets the focus), and on to its settings. Press `?` on the canvas for this
list:

| Keys | What they do |
| --- | --- |
| Tab, Shift+Tab | Move between nodes, and on to the settings |
| Arrow keys | Move to a connected node: right to the next, left to the one before; up and down to the node above or below |
| Shift+arrow keys | Move the node on the canvas |
| Alt+arrow keys | Pan the canvas |
| A | Open the node list at **Find a node**; type, Tab to the type and press Enter to add it after the selected node |
| Arrow keys in the node list | Up and down through the folders and nodes; Right opens a folder, Left closes it; Enter adds the node |
| C | **Connect to…**: a list of the nodes this node can send rows to or read rows from |
| Enter | Go to the node's settings |
| Shift+F10 or the menu key | The node's menu: **Preview rows**, **Settings**, **Connect to…**, **Duplicate**, **Remove** |
| Delete or Backspace | Remove the node (or a connection you clicked) |
| Ctrl+D | Duplicate the node |
| Ctrl+Z; Ctrl+Shift+Z or Ctrl+Y | Undo; redo |
| + and - | Zoom in and out |
| F | Fit the whole pipeline in view |
| R | Readable size |
| L | Lay the nodes out again |
| Escape | Clear the selection, or close a list |

On a Mac, use Cmd for Ctrl. These keys work on the canvas and its tools, not while you are in the
settings or the node list. Connections are not in the Tab order: remove one with **Disconnect**
in the settings, and add one with **Connect to…**. What each change did is announced to screen readers, the nodes are
also listed as a table under the canvas, and the canvas does not animate when your system asks for
reduced motion.

## YAML basics

A pipeline starts with the format line and its name, then lists its nodes:

```yaml
kilnmark: pipeline/v1
name: customer-billing
description: Monthly spend per customer.
nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: spend
    type: write
    input: customers
    source: warehouse
    table: billing.customer_spend
    mode: replace
```

- Each node has an **id** (small letters, digits, `-` and `_`) and a **type**. The other keys are
  the node's settings.
- An optional `quality:` block sets the pipeline's quality threshold (see
  [Quality of what a run writes](#quality-of-what-a-run-writes)).
- **input** says which node feeds this one: `input: customers` takes that node's main output.
  `input: joined.rejected` takes another output port, here the rows a lookup could not match.
  A node with several inputs names each one: `inputs: {main: spend, lookup: customers}`.
- Nodes can be listed in any order; the inputs decide the order of work.
- A key Kilnmark does not know is a problem, with its line, so typing mistakes are caught early.
- Comments start with `#`.

### Reading

The `read` node takes `source` (a saved source's name) and `asset`: a table or view as
`schema.table`, or a file's path inside the source. `row_limit` reads only the first rows, which
is handy while you try a pipeline out; 0 reads them all.

`columns` and `where` read only what you need:

```yaml
  - id: orders
    type: read
    source: sales-db
    asset: shop.orders
    columns: [order_id, customer_id, status, shipping_fee]   # in this order; leave out to read all
    where: "status in ${statuses} and shipping_fee >= ${min_fee}"
```

- `columns` lists the columns kept, in the order you want them. On the canvas, tick them in the
  read node's **Columns** list, which shows each column of the table with its type; the order you
  tick them in is the order they come out.
- `where` is a condition, written as in a `filter` node. It may use any column of the table, chosen
  or not, and the pipeline's parameters as `${name}`. The canvas offers both as buttons under the
  box. A parameter's value always goes in as a value, never as part of a name.
- With `where`, `row_limit` counts the rows that meet the condition.
- **Who does the work.** For a database table, Kilnmark asks the database to do as much of the
  `where` as it safely can, so fewer rows travel; each value is sent separately from the SQL (bound),
  never written into it. Kilnmark then checks every row against the whole condition anyway, so the
  rows are the same whichever does the work, even where the database compares text differently
  (ignoring case, say). Text compared with `=`, `in` or `like`, numbers, dates and true/false go to
  the database; functions such as `lower(...)`, date-times (time zones differ) and arithmetic stay with
  Kilnmark. So do columns the database stores differently from how Kilnmark reads them, such as
  JSON, spatial or uuid columns read as text. Files, and sources added through a generic JDBC
  driver, read every row and Kilnmark applies `where`.
- The read node's note, under its settings on the canvas and with the check's notes on the YAML
  tab, says which: "applied by the database while reading", "applied in part by the database"
  (naming what Kilnmark does), or "applied by Kilnmark as the rows arrive".

The samples `sample-read-columns-where` and `sample-orders-by-status` show both (see
[Adding the samples](#adding-the-samples)).

### Writing

The `write` node takes `source` and where to write: `table` for a database, `path` for a file
(or `target` for either). Files also take `format`: `csv` or `parquet`.

`mode` says what happens to what is already there:

| Mode | What it does |
| --- | --- |
| `create` | Writes a new table or file, and fails if it exists. The default. |
| `append` | Adds the rows to what is there. |
| `replace` | Writes the rows in place of what is there, swapping them in at the end. |
| `upsert` | Updates rows with the same `key` and adds the rest. Needs `key`, such as `key: [customer_id]`. |

A write node also takes the settings of the source's writer, such as `commit_every` for large
table writes, `overwrite` and `delimiter` for files, `compression` for Parquet, and
`profile_output: false` to skip profiling what is written. They are written beside the node's other
settings, as in [Writing data from a pipeline](writing-data.md), which describes them; a setting the
source's writer does not take is a problem, with its line.

The nodes between reading and writing (filter, compute, select, lookup, aggregate, union, dedupe,
route and limit) are described in [Pipeline nodes and expressions](pipeline-nodes.md). The quality
gate is described below. The node
catalogue (`GET /api/v1/node-types`) lists every type this server has, with the settings each takes.

## Parameters and server variables

A pipeline can take values when it runs, so one pipeline serves many runs: the orders in a status,
the rows since a date, a file named after the run. There are two kinds of value, written in two ways:

| | Parameter | Server variable |
| --- | --- | --- |
| What it is | An input the pipeline (or a flow) declares, like a function's argument | A value of the server, the same for every pipeline and flow on it |
| Where it is set | In the YAML: `parameters:`, each with a type and a default | On the server, by a steward or admin (`/api/v1/variables`) |
| Written as | `${status}` | `${vars.target_schema}` |
| Changes | With each run: whoever runs it, or the flow running it, can give another value | When the environment does: a new schema, another bucket |
| Kinds | text, number, boolean, text list, number list | text, number, boolean |

The same YAML runs unchanged on a test server and on the live one: each server holds its own
variables, and each run its own parameter values.

### Declaring parameters

```yaml
parameters:
  statuses: { type: text list, default: [delivered], description: The order statuses to keep }
  min_fee:  { type: number, default: 0 }
  label:    { type: text, default: "${vars.default_label}" }
nodes:
  - id: wanted
    type: filter
    input: orders
    condition: "status in ${statuses} and shipping_fee >= ${min_fee}"
  - id: out
    type: write
    input: wanted
    source: warehouse
    path: orders/${label}.csv
```

- Every pipeline parameter has a default, so the pipeline also runs on its own. A default may be a
  server variable, as `label`'s is: the variable's value, unless the run gives another.
- Names start with a lower-case letter and use a-z, 0-9 and `_`. `and`, `or`, `not`, `true`,
  `false`, `good`, `fair` and `poor` are taken.
- Inside `{ }`, write a value that is `${...}` in quotes: YAML reads `{` as the start of a mapping.

### Where a value can go, and how it goes in

A parameter fills a value, never anything else. It can never choose what a node reads or writes
to: not a node's type, its inputs, its source, its table or file to read, its columns or a choice,
nor another node (`rules_from`).

| Setting | How the value goes in | Example |
| --- | --- | --- |
| An expression (`condition`, `columns` of compute, `routes`, ...) | As a value of the expression language: text in quotes, a number as a decimal, a list as `('a', 'b')` | `status in ${statuses}` becomes `status in ('delivered')` |
| Where a write goes (`table`, `path`) | As part of the last name only, beside text the pipeline writes there, holding only letters, digits, `_` and `-`: the pipeline fixes the schema or folder and the rest of the name | `orders/${label}.csv`, `sales.orders_${region}` (not `${table}`, `${folder}/x.csv` or a value with `.` or `/`) |
| Other text (`delimiter`, ...) | As text, holding only letters, digits, spaces, `_`, `-` and `.` (never `..`) | `delimiter: ${sep}` |
| A number or true/false (`rows`, `max_failing_share`, `strict`, ...) | As the whole value | `rows: ${limit}` |

- In an expression, write `${status}` without quotes: it goes in quoted already, and quoting it
  again is a problem. A value can never become part of the expression: `x' or 1=1` is compared
  as that text. Nor can it join a name: `amount_${year}` is a problem (put a space or an operator
  between them), so a value never chooses a column.
- A number goes into an expression as a decimal (`10` as `10.0`), so what the expression works out
  has the same type whatever the value. Numbers have at most 15 digits before the point and 30 in
  all; a list has at most 200 values (and an expression, lists included, at most 10,000 characters).
- `$${` writes a plain `${`. A pipeline with no parameters that names no server variable keeps
  `${...}` as plain text, as before.
- Everything is checked with the values the run will get: a wrong kind (a number where text is
  compared), an unknown name (with the closest suggested) or a value that does not fit is a problem
  before anything runs.

### Running with values

Choose **Run now** to run with the defaults. Through the API, give values in the run's body:

```
POST /api/v1/pipelines/orders-by-status/runs
{ "parameters": { "statuses": ["shipped", "delivered"], "label": "both" } }
```

A run keeps the values it started with, its parameters and the server variables as they were when
it was queued, and uses those however the variables change before it runs. A value of the wrong
kind, or a parameter the pipeline does not take, is refused at once.

### Server variables

Stewards and admins set them with `PUT /api/v1/variables/{name}` (`{ "type": "text", "value":
"sales_prod", "description": "..." }`) and delete them with `DELETE`; everyone with the steward role
can list them. Every change is kept, with who made it and when, also after a variable is deleted
(`GET /api/v1/variables/{name}/changes`). A Settings page for them follows with the flows pages.

Server variables are never secrets: they are shown to stewards and recorded with every run. A name
that looks like a credential (`password`, `token`, `secret`, `api_key` and the like) is refused.
Passwords and keys belong in a source, which holds them encrypted.

## Quality gates

A `gate` node checks the rows passing through it against quality rules, in the same pass that moves
them: nothing is read twice. Its rules are

- **the asset's own rules**: the enabled rules of the table or file read upstream, as stewards set
  them in **Quality** (on unless you set `asset_rules: false`). When rows reach the gate from more
  than one read node, name the one whose rules apply with `rules_from: <read node id>`;
- **rules written on the gate**, for this pipeline only, under `rules`.

```yaml
  - id: check
    type: gate
    input: customers
    on_fail: quarantine          # stop (the default), quarantine or warn
    rules:
      - {type: not_null, field: email}
      - {name: known country, type: allowed_values, field: country, values: [GB, IE, FR]}
      - {type: in_range, field: amount, min: 0, max: 10000, fail_above: 0.01}
    max_failing_share: 0.05      # stop the run if more than 5% of rows fail a rule

  - id: good
    type: write
    input: check                 # the rows that passed
    source: warehouse
    table: crm.customers_clean
    mode: replace

  - id: aside
    type: write
    input: check.quarantine      # the rows that failed
    source: warehouse
    table: quarantine.customers
    mode: append
```

On the canvas, the gate is in the **Quality** folder, with two outputs: `out` and `quarantine`. Its
settings panel lists the rules written on it, each with its check, its column and the check's own
settings.

### Writing rules on a gate

Each rule has a `type` and, except for checks of the whole data, a `field` (a column arriving at the
gate). The checks and their own settings are those of rules made in **Quality**:

| Type | Fails a row when | Its settings |
| --- | --- | --- |
| `not_null` | The value is empty | `blank_is_missing: true` counts blank text as empty too |
| `unique` | The value was seen in an earlier row: the first row with it passes, each repeat fails | `fields: [a, b]` for a key of several columns, instead of `field` |
| `allowed_values` | The value is not in the list | `values` |
| `matches_pattern` | The value does not match the regular expression | `regex` |
| `in_range` | The value is below `min` or above `max` | `min`, `max` (either may be left out) |
| `freshness` | (Whole data) The latest date or time is older than allowed | `max_age_hours` |
| `row_count` | (Whole data) There are fewer than `min` or more than `max` rows | `min`, `max` |
| `row_count_change` | (Whole data) The row count changed by more than `max_change` (0.2 for 20%) since the asset's last scan | `max_change` |

Empty values pass every check but `not_null`. Every rule may also have a `name` (by default its
column and type, such as "email not null"), a `dimension`, and its own limit: `fail_above` is how
many rows may fail before the rule fails, as a share of the rows checked (0.01 for 1%) or, with
`unit: count`, a number of rows; it is 0 when left out, so any failing row fails the rule.
`warn_above` makes the rule warn below that. Rules that run in a database, `references` and
`custom_sql`, cannot check rows in a pipeline; make them rules on the asset instead.

A gate counts only the repeats of a key as failing for `unique`, so its count, its result, its score
and the rows it sets aside agree. (A `unique` rule run on the asset in **Quality** counts every row of
a repeated key.) To find repeats, a gate keeps each key it has seen as a short fingerprint, about 96
bytes whatever the key's length, up to 1,048,576 different keys (about 96 MB). Past that it stops
checking for repeats: the rule shows **Could not run**, says how many repeats it had found and set
aside before then, and the rows after it are not checked for repeats. Use the asset's `unique` rule,
which runs in the database, for larger tables.

### What happens when rows fail

`on_fail` decides:

| `on_fail` | Rows | The run |
| --- | --- | --- |
| `stop` (the default) | All go on to `out` | Fails when any rule fails by its own limit, or a threshold below is crossed. It stops as soon as a rule cannot pass any more (a failing row where none may fail, or more failing rows than its count allows); otherwise when the rows end. The writes after the gate have not reached the end of their rows, so they do not commit. |
| `quarantine` | Rows failing any rule go to `quarantine`; the rest to `out` | Goes on, unless a threshold below is crossed, or a check of the whole data (row count, freshness) fails: no row can be set aside for that. |
| `warn` | All go on to `out` | Goes on whatever fails. What failed is recorded with the run. |

A stop gate holds back only the writes after it, and only because they commit at the end of their
rows. So Kilnmark refuses, with a problem at the setting:

- a write after a stop gate with `commit_every` in mode `create`, `append` or `upsert`, which commits
  as the rows arrive. (`replace` swaps the new rows in at the end, so it may keep `commit_every`.)
- a `limit` after a stop gate: it ends the rows early, so the gate never sees their end.

And it notes, without stopping the pipeline:

- a write that gets the same rows by another path, not through the gate (read → gate → good, and
  read → raw): it is written even when the gate stops the run;
- `on_fail: quarantine` with nothing connected to `quarantine`: the failing rows are dropped;
- something connected to `quarantine` when `on_fail` is `stop` or `warn`: it never gets rows, and a
  write there in mode `replace` or `create` empties or creates an empty table on every run.

Two thresholds judge the rows as a whole, with `stop` and `quarantine` (with `warn` they are
recorded only):

- `max_failing_share`: the run stops when more than this share of the rows (0 to 1) fail at least
  one rule. A share equal to it is allowed.
- `min_score`: the run stops when the gate's score (below) is under this (0 to 1). A score equal
  to it is allowed.

A run the gate stopped says why, naming the rule and its counts, for example "The quality gate
stopped the run: rule 'email not null' failed on 120 of 9,000 rows (1.3%), more than its limit of 1%".
If a rule's column does not arrive at the gate, the rule cannot run: it is shown as **Could not
run** and does not fail the rows. A gate with no rows to check passes every check of rows, but a
`row_count` with a `min` fails, and so does `freshness`: there is no latest value to be recent.
If the run ends the gate's rows before it sees their end (another node failed), it shows what it
checked so far and says it saw only the first rows.

The gate always uses the rules as they are when the run starts. It checks every row: it does not yet
reuse a recent result of the asset's rules when the data has not changed.

## Quality of what a run writes

Every run measures quality as it goes, without reading anything again:

- **Each gate** gets a score from its rules' results: for each rule, the share of rows that passed,
  by the rule's dimension (completeness, validity and so on), and overall the mean of the
  dimensions.
- **Each write** gets a score from the profile made of the rows as they were written (see
  [What Kilnmark records about what you write](#what-kilnmark-records-about-what-you-write)): the
  same measures the catalogue makes after a scan, such as empty values, repeated keys and competing
  formats, per dimension and overall. Measures that need history, such as freshness and volume,
  are left out: the rows of one run have none. An `append` or `upsert`, and a write with
  `profile_output: false`, is not profiled, so it has no score, and the run says so.

The run's page lists them under **Quality**: each write and gate with its score as a percentage and
a word, its score per dimension, and for a gate what it decided, its row counts and each rule's
result. The run's summary shows the lowest score of what it wrote.

To be told when a run writes data below a level, give the pipeline a threshold:

```yaml
kilnmark: pipeline/v1
name: customer-billing
quality:
  min_score: 0.95
nodes:
  ...
```

When a run succeeds but a write scores below `min_score`, everyone following the pipeline's
notifications is told, once for that run, naming the write (**Pipeline quality below threshold**, see
[Notifications and email alerts](notifications.md)). The threshold only tells; to stop a run on
quality, use a gate.

## Predicted quality

Before a pipeline runs, Kilnmark predicts the quality of each write and gate from what the catalogue
already knows about the tables and files it reads: their profiles (empty values, distinct values,
value shapes, formats, most common values, spread), accepted patterns, relationships and rule
results. No data is read. The estimate is carried through the nodes, each with its own rule, and
each write is then scored with the same measures a run uses for what it wrote, so the prediction
and the measurement compare directly.

On the canvas, each write and gate shows **Predicted ≈ 91%**, in a violet chip with a dashed edge (an estimate), a moment after you stop
editing; select it to see the score per dimension, what a gate is expected to do with each rule,
and what the estimate could not tell or had to assume. A read node that has not been scanned has
no profile, so what follows it is **unknown**: scan its source first. Kilnmark never fills a gap
with a guess; a dimension it cannot judge has no score. The chip says how many things the estimate
could not tell ("1 unknown"); the node's settings list them.

The estimate uses a column's values (its most common values and its spread) only when you may see
them, as the Quality pages decide: not at all for viewers, and for a column marked sensitive only
for admins. Otherwise what depends on them, such as how many rows `salary <= 50000` keeps, is
unknown, so the estimate never gives the values away.

How each node carries the estimate:

| Node | Rule |
| --- | --- |
| `read` | The asset's latest profile; with `row_limit`, the first rows are taken to be like the rest |
| `filter`, `route` | `is [not] null` drops the known share of empty values; `=`, `<>`, `in` use the column's most common values when they account for every value (for a number or time, a value not among them is unknown, since it may be written another way); `<`, `>`, `between` use its quantiles, and bounds on one column joined with `and` make one range; `regex_match` tests each value shape with several letters and digits, and is unknown when they disagree or the pattern names particular letters or digits; `not` leaves out the rows where its test is empty, as SQL does; other conditions joined with `and` and `or` are combined as if independent, which the estimate says. Anything else keeps an unknown share of rows. The columns not tested are taken to be spread the same in the rows kept |
| `compute` | A copied column keeps its profile; `trim` removes padding, `lower`/`upper` change case, `coalesce(x, 'v')` fills the empty values, `parse_date` empties the values in other shapes, `format_date` writes one shape. Arithmetic and joined text are empty when any part is. Other expressions are unknown |
| `select` | Kept and renamed columns keep their profile; a cast keeps its empty values |
| `lookup` | Rows with no match are the main key's empty values plus the share of its distinct values missing from the lookup side, from the stored relationship or the two columns' sketches. `keep` leaves those rows with empty lookup columns; `drop` and `rejected` take them out. With no relationship or sketches, unknown |
| `aggregate` | One row per distinct value of a single group column; each group is unique. Several group columns, or an input whose rows are unknown: the row count is unknown |
| `union` | Rows add up; each column's shares are weighted by its inputs' rows, a column an input lacks is empty there. Uniqueness is not predicted |
| `dedupe` | One row per distinct key value; the key becomes unique. Whole rows: unchanged when a column is already unique |
| `limit` | At most its rows, taken to be like the rest |
| `gate` | Each rule's failing share from the column as it arrives (empty values, distinct values, most common values, value shapes, quantiles); for one of the asset's own rules on a column that arrives unchanged, its last result when the profile cannot tell. `quarantine` takes the failing share out of `out` (rules counted as failing independently), and what the rules guarantee holds there (no empty values for a not-null rule) |

When a run starts, each write's and gate's prediction is stored with it. The run's page then shows
**measured beside predicted**, per dimension, with the gap and what it says: a wide gap means the
catalogue's metadata, or the pipeline, needs attention (a source changed since its last scan, or a
node does something the estimate could not see).

### Suggestions

**Suggestions** on the canvas toolbar lists nodes that would raise a write's predicted score, each
with its predicted effect (an estimate), such as "Predicted 89% → 94% (+5 points, estimate)":

- trim the spaces from padded text columns;
- standardise a column whose dates are written in several formats (to `yyyy-MM-dd`; dates such as
  `01/02/2024` are read day first);
- keep one row per key when a key repeats;
- drop the rows whose key has no match in the related table, with a lookup (the score does not
  measure this, so the suggestion says how many rows it leaves out);
- set aside, with a quarantining gate, rows outside a column's accepted patterns, or without a key.

A suggestion that takes rows out (a dedupe, a gate, a lookup that drops rows with no match) also says
how many fewer rows reach the write, since fewer rows can score higher. Choose **Add** to insert a
suggestion's nodes before the write: the YAML changes only where the nodes go, and one **Undo** takes
it out again. Suggestions are for stewards and admins.

When an admin sets a model for **Pipeline suggestions** under **AI models**, it ranks and explains
the suggestions and may add its own. It sees metadata only (asset and column names, kinds, shares
and value shapes, never a value from the data) and only of sources whose AI rule lets that model
see them: a source set to `none` is never shown, and one set to `local_only` only to a local
model. What it suggests is checked before it is shown: only node types this server has, settings
those nodes take, and columns that reach that point; anything else is left out, and the panel says
how many. A node that would read another table is only shown to the model when that table's source allows it.
The model is asked when the panel opens and when you choose **Ask again**, at most six times a minute
for each person; after an edit or an **Add**, only Kilnmark's own suggestions are worked out again.
Without a model, or when it is refused, slow or fails, the suggestions are Kilnmark's own and the
panel says why. Turn **Ask the AI model too** off to skip it.

## Checking as you type

A moment after you stop typing, or after a change on the canvas, Kilnmark checks the pipeline.
Under the canvas or the editor it says how many problems it found, and lists each one with its line
and the node it is about. In the editor, lines with a problem are marked with `!` beside their
number.

- **Choose a problem** to move the cursor to its line (on the canvas: to select its node).
- **Notes** do not stop a run. For example, a table that has not been scanned yet has columns
  Kilnmark only learns when the pipeline runs.
- In the editor, Tab indents by two spaces. Press Escape and then Tab to move on to the next
  control.

Beside the YAML, **Nodes** draws the pipeline as a read-only graph: each node with its input ports
on the left and output ports on the right, and lines from port to port. Hover over or move the
focus to a node to see the columns leaving it. **Nodes as a table** says the same in words; choose
a node's id there to go to its line (or, on the canvas, to select it).

## Saving versions

Every save keeps a new version; earlier ones never change.

1. Say what you changed under **What changed**, such as "Drop test customers".
2. Choose **Save new version**.

A pipeline with problems can be saved as a draft, so you can come back to it, but it cannot run
until they are fixed.

If someone else saved a version after you opened the page, Kilnmark refuses your save rather than
undo theirs unseen. Your changes stay on the page. **Show my version in the YAML tab** shows them as
text; **Load the latest and keep mine as an undo step** loads the latest version in their place,
and **Undo** brings yours back, to compare or to make your change again.

The **Versions** list shows every version with who saved it, when and why. Open one to see its
YAML exactly as it was saved.

## Running a pipeline

Choose **Run now**, on the Pipelines page or the pipeline's own page. Kilnmark checks the latest
version again first, since a source may have changed, then queues the run for a worker. The run
appears under **Runs** as Queued, then Running.

A run uses the version that was latest when you asked, even if someone saves another while it
waits. A pipeline with parameters runs with their defaults; see
[Parameters and server variables](#parameters-and-server-variables) to give other values.

To stop a run, open it and choose **Cancel run**. A queued run is cancelled at once; a running one
stops within a few seconds. Rows already written stay written.

## Reading a run

Open a run from the **Runs** list. The page updates itself until the run ends, and says when its
status changes.

- **Summary:** the status, the version that ran, who asked, when it started and finished, how long
  it took and how many rows its writes received. A failed run says why, naming the node where the
  failure started.
- **The run at a glance:** four tiles: its **wall-clock time**, the **most nodes at once**, the
  **longest node** (while it runs, the slowest node now) and **what limited this run**. Every time
  on the page counts from when the worker started on the run, by the worker's own clock.
- **Where each node spent its time:** one bar per node, its whole time from start to end split into
  **Working** (solid), **Waiting for rows** (light hatching: the nodes before it had nothing for it)
  and **Held up by the next node** (amber cross-hatching: the node after it was not keeping up, so
  there was no room to hand rows on; this is back-pressure). At the right, how much of its time it
  was working and what it did: a gate's verdict (Passed, Quarantined 229, Stopped the run), a
  write's measured quality (Quality 99% (Good)), else its rows. Above the bars one line says **what
  limited this run**: of the nodes that others were held up by, the one working the largest share
  of its time, such as "What limited this run: check, working 90% of the time". When nothing was
  held up it says the run was limited by reading its source. Speed that node up (a simpler rule, a
  smaller lookup, a faster target) and the run gets faster; speeding up the others does little.
  **Show where each node spent its time as a table** gives the same in words. While the run goes
  this is drawn from its latest progress.
- **Rows per second through the run:** a line per node of the rows it handled each second, from
  the snapshots taken every two seconds, with its peak at the right. Under it, an amber band shows
  how full its output queue was (**Held up**: the taller and stronger, the fuller; full means the
  node was waiting for the next one), and the words "output queue full 78% of the time". Numbered
  marks on the time axis show the run's warnings and errors, and a gate starting to set rows aside,
  listed under the chart. Point at a line, or focus it and use the arrow keys, to read the time,
  rows a second and how full the queue was at that point. **Show rows a second as a table** gives
  each node's peak, average and how often its output queue was full.
- **Nodes:** for each node, how it ended (Finished; Ended early, as a `limit` does once it has its
  rows; Stopped by the engine, because another node failed or the run was stopped; or Failed), the
  rows that came in and went out, with the count on each port when a node has several (for
  example, a filter's `out` and `rejected`), the most memory it held, how long it took and its
  share of the run's time, and its error if it failed. A write that was stopped wrote nothing, unless
  it used `commit_every`. While the run goes, the table shows each node's rows so far and rows a
  second instead.
- **Log:** what happened, in order: the run starting, each node starting, its first row in and
  out, its last row out and how it ended, a gate starting to set rows aside and its verdict ("Gate
  check quarantined 229 rows (email format: 229)", the rules that failed and their counts; a rule's
  own message, which can quote the data, is its detail), warnings and errors, and the run's notes.
  The log is read again from the start when the run ends, so nothing written at the last moment is
  missed. Choose a node,
  or **Warnings and errors** or **Errors only**, to narrow it, and **Download as text** (or
  **NDJSON**, one JSON object a line) to save it with the same choices. Where the engine's own
  message could quote values from your data, it shows only to those who may see why the run
  failed (stewards, or admins when the run touched columns marked sensitive); others see that it
  was held back. A run keeps up to 10,000 events; the log says if it was cut short.
- **What it wrote:** each table or file a write node wrote, with its mode and rows. Choose one to
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
