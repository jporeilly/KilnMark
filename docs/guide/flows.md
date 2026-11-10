# Building and running a flow

A pipeline moves and checks data. A **flow** runs pipelines in an order, and decides what runs next
from how each ended and what it measured: carry on only if a gate set aside under 2% of the rows,
else tell the stewards and stop. Each flow is saved as YAML, with every version, and every run is
recorded node by node.

## Before you start

- **Viewers** read flows and their runs.
- **Stewards** run flows, give their parameters values, and cancel runs.
- **Designers** add flows, save new versions and delete flows, and with **admins** give flows
  schedules ([Running pipelines and flows on a schedule](schedules.md)). A flow with a `sql` node is saved only by a
  designer whose token sees unmasked values, since SQL can read and change any column its source's
  login can.

A flow runs saved pipelines, so build those first, under **Pipelines**. The pipelines a flow starts
run as the person who started the flow, with their role: a flow cannot do what its starter could
not.

## The Flows page

**Flows**, in the sidebar after Pipelines, lists every flow with its latest version, whether that
version can run, and its last run. Stewards see **Run now** beside each; designers see **New flow**.

A flow's own page is laid out as a pipeline's: its name with **Run now** (and **Delete**, for
designers and admins), then the flow on two tabs,
**Diagram** and **YAML**, which show and edit the same flow. Under them, a panel with the pipeline
page's tabs:

- **Nodes**: every node, what it runs after, and how it went in the latest run;
- **Settings**: the node or connection chosen on the diagram, while one is chosen (designers only);
- **Run**: the latest run's summary, as its run page gives it, kept current while it goes;
- **History**: each run with its status, version, when it was asked for and by whom, how long it
  took, and the rows its pipeline runs wrote;
- **Schedules**: the times it runs on its own, with the next and the last
  ([Running pipelines and flows on a schedule](schedules.md));
- **Versions**: every saved version.

The problems, the parameters and saving come last, whichever tab is shown. **Collapse** folds the
panel to its tabs.

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
  item of a list, `sql` runs statements on a PostgreSQL source, `check-source` checks a source
  holds what the work needs, `wait-for` waits for a file or a query, `wait` waits a while, `notify`
  sends a notice, and `stop` ends the flow.
- **Connections** say what runs after what, and when: `success` (the default), `failure`,
  `always`, or a condition on what an earlier node reported, such as its `score`,
  `quarantined_share`, `rows_written` or `status` ([Conditions](#conditions), below).
- **Parameters** are values each run can change. One with no default must be given when the flow
  runs.

Every node type, with each setting it takes, its type and default, and an example, is in
[Reference: flow nodes](reference-flow-nodes.md).

Nodes with no connection between them run at the same time. The sample flows that ship with
Kilnmark, in `samples/08-flows`, show one idea each, and each says in the Library what it shows, what to look for and what it needs:
`sample-retries` tries a run and a node again only where it is safe, and `sample-checks-and-waits`
checks a source and waits for a file before the work, with a priority on each pipeline. The
business scenarios, in `samples/10-scenarios`, put them together for a whole process: a nightly
load that stops on quality, a partner's file held for review, and a month-end close that waits for
a query and runs SQL ([Business scenarios](scenarios.md)).

## Conditions

A connection with a condition is taken when the node it leaves succeeded and the condition is
true. A condition is a small language over what earlier nodes reported and the flow's parameters;
it never runs code:

```text
clean.quarantined_share < 0.02 and clean.score >= good
clean.status = 'failed' or (clean.rows_written = 0 and region != 'APAC')
```

- **Values.** `<node>.status` is `succeeded`, `failed` or `cancelled` for every node. The other
  values each node reports, such as a `run-pipeline` node's `score` (the lowest measured quality
  of what it wrote, 0 to 1) or a `for-each` node's `failed`, are listed in its entry in
  [Reference: flow nodes](reference-flow-nodes.md). A parameter is read by its name.
- **Quality words** are the lowest score of a band, as the app shows them: `good` is 0.95, `fair`
  0.8 and `poor` 0.
- **Writing it.** Numbers (`0.02`, `-1`), text in single quotes, `true` and `false`; `=`, `!=`,
  `<`, `<=`, `>` and `>=`; `and`, `or` and `not`; brackets. Comparisons do not chain, and both
  sides of one are the same kind (number, text or true or false), checked when the flow is saved;
  true and false compare only with `=` and `!=`. Any other operator, such as `==` or `<>`, is
  refused rather than guessed at. A condition is at most 1,000 characters, with at most 32 levels
  of brackets and `not`.
- **Unknown is not false.** A score nothing measured is unknown, and so is a comparison with it,
  with SQL's rules for `and`, `or` and `not`. A connection is taken only when its condition is
  true, so an unknown answer takes neither it nor its opposite: a flow with `< 0.02` on one
  connection and `>= 0.02` on the other skips both when nothing was measured. Add a `failure` or
  `always` connection, or write the condition so that it covers the case.
- **Only nodes that must have ended.** A condition may name the node the connection leaves and
  the nodes before it. Naming any other node is a problem when the flow is saved ("Unknown value
  'c.score'"), rather than a value that is sometimes missing.

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

## Stopping a node that takes too long

A `run-pipeline`, `for-each` or `sql` node may say how long its work may take, with `timeout`. Use it
so a load that hangs, or a statement stuck behind a lock, fails the flow in good time rather than
holding up everything after it.

![What a node's timeout does: time spent ready, waiting for a worker or a source is not counted; once a worker takes the node's work, the timeout counts while it runs. If it ends within the timeout it ends as it ended, succeeded or failed. Past the timeout it is stopped (its pipeline run cancelled, or its SQL rolled back), the node fails saying it took longer than its timeout, and its failure connection is taken. A for-each node gives each item's run the whole timeout; an item stopped for it counts as failed.](images/flow-timeout.svg)

```yaml
nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-load
    timeout: 2h                 # 90s, 30m, 2h or 1d; 7d at most; no limit when left out

  - id: regions
    type: for-each
    pipeline: sales-by-region
    items: [EMEA, APAC, AMER]
    as: region
    timeout: 30m                # for each item's run, on its own

  - id: tidy
    type: sql
    source: warehouse
    statements: ["DELETE FROM staging.orders"]
    timeout: 5m

  - id: tell
    type: notify
    severity: high
    title: The orders load failed or took too long

connections:
  - { from: load, to: tell, when: failure }
```

- **The time counts from when the work starts**: when a worker takes the pipeline run, or when a
  `sql` node's statements start. Time spent waiting for a worker, for a free place on a source, or
  for another run of the same pipeline to finish does not count.
- **Past it, the work is stopped.** A pipeline run is cancelled, as **Cancel** on its page would
  (what it had already written stays written); a `sql` node's statement is cancelled and its
  transaction rolled back, so nothing it ran is kept. The node then **fails**, saying "Took longer
  than 2h and was stopped". It is not cancelled, so a `failure` or `always` connection from it is
  taken, as above, and the flow carries on as for any other failure.
- **For a `for-each` node**, each item's run has the whole timeout to itself. An item stopped for it
  counts as failed; the others still run, and the node fails once they have ended, its message
  naming the first item that took too long.
- The node waits for the stopped work to end before it fails, so nothing after it starts while its
  pipeline may still be writing. A run that succeeds just as it is stopped counts as succeeded.
- Saving the flow checks the value: a time in seconds (`s`), minutes (`m`), hours (`h`) or days
  (`d`), more than nothing and at most 7 days. Only these three node types take one: a `wait` node
  has `for`, and `notify` and `stop` nodes end at once.
- On the diagram, choose the node and set **Timeout** on its Settings tab; leave it blank for none.

![The diagram of the flow nightly-takings with the run-pipeline node takings chosen: its Settings tab shows Pipeline sample-volume, a blank Version, Timeout 2s with the hint "Stopped and failed past it: 30m, 2h, 1d. Blank for none.", Priority Normal, and blank Tries again and Wait before trying again](images/flow-node-timeout.png)

On the run's page the node shows **Failed** with **Timed out** beside it, and its message says how
long it was allowed:

![A run of nightly-takings in which the takings node timed out: the diagram outlines takings red as Failed, its notice node too-slow green as Succeeded through the on failure connection, and done as Skipped; the run timeline has a red bar for takings, chosen, with its details below (Failed, took 2.9 s, "Took longer than 2s and was stopped; its pipeline run was cancelled", reported timed_out true); the Nodes table shows takings as Failed and Timed out, with a link to its pipeline run](images/flow-run-timed-out.png)

## Trying a node again after a failure

A `run-pipeline`, `for-each` or `sql` node may be tried again when it fails, with `retries`. Use it
for failures that pass on their own: a database restarting, a network drop, a lock held a little too
long.

![How a node is tried again: each try is a pipeline run of its own. If it succeeds, the node succeeds. If a person or the flow cancelled it, it is never tried again. If it failed or timed out and it has tries left, and trying again is safe, the node waits its retry_delay, doubled before each try, then tries again; otherwise it fails, saying after how many tries. Safe means a sql node whose statements failed, as they were rolled back, and a pipeline only if its writes replace or upsert; a sql commit that failed is never tried again. A for-each node tries each failed item on its own.](images/flow-retries.svg)

```yaml
nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-load       # every write replaces or upserts
    retries: 3                  # up to 3 more tries; 0 to 5
    retry_delay: 2m             # wait 2m, then 4m, then 8m; 10s to 1h, 1m when left out
    timeout: 30m                # each try has the whole timeout

  - id: regions
    type: for-each
    pipeline: sales-by-region
    items: [EMEA, APAC, AMER]
    as: region
    retries: 1                  # each failed item is tried once more, on its own

  - id: tidy
    type: sql
    source: warehouse
    statements: ["DELETE FROM staging.orders"]
    retries: 2
```

- **A try that fails or takes longer than its timeout is tried again**, after `retry_delay`, which
  doubles before each try after, each wait at most an hour. The node shows **Running** while it waits, with a message such as
  "Try 1 of 4 failed; trying again in 2m". Only once its last try fails does the node fail, saying
  "..., after 4 tries", and its `failure` connection is taken.
- **A cancel is never tried again**: a pipeline run cancelled on its page, or a flow cancelled or
  stopped, ends the node as it would without retries.
- **Trying again must not write twice.** A pipeline that appends, or creates its target, would write
  its rows a second time. So saving a flow refuses `retries` on a node whose pipeline has a `write`
  node that does not `replace` or `upsert`, naming it ("... must replace or upsert: out appends").
  Before each try again, the version the failed run used is checked once more, as a node with no
  `version` runs the latest, which may have changed since: if it now appends, the node fails.
- **A `sql` node** is tried again after its statements fail, as its transaction was rolled back. If
  the commit itself fails (the connection dropped as it committed), whether anything changed is not
  known, so it is never tried again: the node fails and says to check first.
- **Each try is a pipeline run of its own.** The node shows its last try. On the run's page, choose
  the node (or a for-each item) in the timeline to see **Tries**: each try, how it ended, and a link
  to its pipeline run. A for-each item counts as its last try ended, and the node tries each failed
  item on its own while the others go on; an item waiting for its next try shows **Pending**. The
  run's totals (pipeline runs, rows written) count every try.
- An item waiting for its next try counts as failed if the node stops early: the flow is cancelled,
  or it gave up waiting for another run of its pipeline (the flow's `queue_timeout`, which a try
  again waits for too).
- On the diagram, choose the node and set **Tries again** and **Wait before trying again** on its
  Settings tab.

## Trying a whole run again

A flow's `run:` block tries a failed run again, carrying on from where it failed. Use it for a
flow whose failures pass on their own, and for a worker that stops while it runs.

![How a run is tried again: a try that fails is tried again only when it may be: never a cancel, a refusal, a quality gate, a sql commit in doubt or writes that would repeat, never past the schedule's next fire, and only while tries are left; it waits retry_delay, doubled each time and at most an hour, holding no worker; a flow's next try keeps the nodes that succeeded and runs the rest; after the last try, one notice goes to the owners](images/run-retries.svg)

```yaml
run:
  retries: 2          # 2 more tries after a failed one; 0 to 5
  retry_delay: 5m     # wait 5m, then 10m; 10s to 1h, each wait at most 1h
  retry_on: any       # any (the default), or lost: only a run lost with its worker
  timeout: 4h         # each try; 90s to 7d
  warn_after: 2h      # tell the owners once if a run is still going; less than timeout
```

- **A retry is a new try of the same run.** While it waits the run shows **Queued**, and its page
  says "Retrying: try 2 of 3, next at 14:05". It holds no worker. A schedule never fires twice for
  it, and a fire that comes while it waits is skipped, as one that comes while a run is going is.
  `kilnmark-run` waits across the tries and ends with the last one's outcome.
- **It carries on from where it failed.** Nodes that succeeded keep what they did, and later nodes
  read their values as before. The rest run again, as their connections fall this time, so
  parallel branches carry on each from where it was. A for-each node runs only its items that did
  not succeed. Each node's pipeline run on a later try is a pipeline run of its own.
- **What is never tried again**: a cancel; a flow a `stop` node ended; a node whose pipeline a
  quality gate stopped, or whose run was refused; a `sql` node whose commit failed or that
  committed; a node whose pipeline would write twice. With `retries` above 0 and `retry_on: any`,
  saving refuses a flow whose pipelines append ("... every write in orders-load must replace or
  upsert: out appends"), unless the node has retries of its own. And never a try that would start
  after the schedule's next fire: the run fails, saying "the next scheduled run is due first".
- **Node retries take the place of the flow's.** A node with `retries` of its own is never run
  again by the flow's retries: it used its tries. If such a node failed and nothing handled it, the
  flow is not tried again. So a node is tried at most 1 + its own retries, never that times the
  flow's.
- **A worker that stops**, or loses its connection to Kilnmark's database, leaves its run. After
  `kilnmark.runs.lost-after` (2 minutes), another worker takes it over while it has tries left:
  nodes that had ended stay as they ended, and the pipeline runs still going are watched to their
  end, never started again. With `retry_on: lost`, only such a run is tried again, so a flow whose
  pipelines append may use it. A pipeline run that was on the stopped worker itself is lost too, and
  its node fails unless it has `retries` of its own: give them to nodes whose pipelines replace or
  upsert.
- **Nobody is told about a try that is tried again.** After the last try, one **Flow run failed**
  goes to the flow's owners and to those who chose it, saying how many tries were made and why no
  more. When a run then succeeds, **Flow run recovered** is told. See [Owners](#owners).
- `timeout` is for each try: past it the try is stopped, as a cancel would stop it, and fails; it
  may be tried again. `warn_after` tells the owners once, a run, and stops nothing.
- The run's page lists every try under **Tries**: how it ended, when, and what came next.

## Run again from the failure

A failed flow run can be run again by hand from where it failed: open it and choose **Run again
from the failure** (stewards and above, never someone whose role is below the run's own).

![A failed run of the flow stock-check: Run again from the failure beside its name; the summary says one node succeeded, one failed and one was skipped, and why it failed (the node fees, which runs the pipeline sample-gate-stop); under Tries, try 1 failed and was not tried again, as the flow has no retries](images/flow-run-again.png)

- It is one more try of the same run, with the same version, values and server variables, acting
  as the run did. Nodes that succeeded keep what they did; the rest run again, and a for-each node
  runs only its items that did not succeed. A node with retries of its own runs again with them.
- It is refused, saying which node and why, when a node that would run again is not safe to: its
  pipeline appends, or a `sql` node committed or its commit is in doubt. It is refused too while
  another run of the flow is queued or running, while pipeline runs it started are still going, and
  for a run that failed before this version of Kilnmark, which did not record how each try ended.
- It is recorded against the run (**Tries** says who asked) and in the [audit log](audit-log.md).
  A try started by hand is not tried again by itself.
- A pipeline has no Run again from the failure: it starts again from its beginning. To carry on
  part-way, make its stages the nodes of a flow.

## Owners

Each flow has **owners**: the people, or team addresses, who answer for it. They are told when its
run fails after its last try, when it runs again successfully after a failure that was told, and
when a run takes longer than its `run: warn_after`. The flow's page shows them under **Owners**, and
designers and admins choose **Change owners**. Who can be an owner, and how they are told, is in
[Owners of pipelines and flows](notifications.md#owners-of-pipelines-and-flows).

## Choosing which runs start first

When more pipeline runs wait than there are workers free, a `run-pipeline` or `for-each` node's
`priority` says which start first: `high`, `normal` (the default) or `low`.

```yaml
nodes:
  - id: close-of-day
    type: run-pipeline
    pipeline: ledger-close
    priority: high              # starts before normal and low runs waiting with it

  - id: archive
    type: for-each
    pipeline: archive-month
    items: [2024, 2025]
    as: year
    priority: low               # each item's run waits behind the rest
```

![The order runs leave the queue: four runs waiting, queued in the order low, normal, normal, high; a worker takes the high one first, then the two normal ones in the order they were queued, then the low one](images/flow-priority.svg)

- **A worker takes the waiting run with the highest priority first**, and of those the one queued
  first. Scans, quality checks and a person's **Run now** are normal.
- **Priority only chooses among runs that may start.** It never lets a run past a source's limit
  on runs at once (**Most runs at once**), a flow's `max_parallel` or a for-each node's, and it never
  stops a run already going. A low run starts whenever no higher run is ready to start; there is no
  ageing, so while high runs keep coming, low ones keep waiting. Use `high` for the few runs that
  must not wait.
- Only nodes that queue pipeline runs take one: a `sql` node runs on its flow's own watcher, never
  waiting in the queue, so saving refuses `priority` on it, and on any other node type.
- On the diagram, choose the node and set **Priority** on its Settings tab. A node of high or low
  priority says so under its name.

![The sample flow sample-checks-and-waits on the diagram: a check-source node, then accounts, a run-pipeline node saying "sample-read-write-csv, high priority", a wait for its file, then customers saying "sample-dedupe, low priority", with a notice on each failure. The accounts node is chosen, and its Settings tab shows Pipeline sample-read-write-csv, a blank Version and Timeout, and Priority High](images/flow-node-priority.png)

## Checking a source before the work starts

A `check-source` node checks that a source can be reached and holds the tables or files the work
needs, before anything runs. Put it first, with a `failure` connection to a notice, so a source that
is down or a file that has not arrived stops the flow at once, saying why, instead of failing a
pipeline halfway.

```yaml
nodes:
  - id: ready
    type: check-source
    source: warehouse
    tables: [sales.orders, sales.customers]   # as the catalogue names them: schema.table
    timeout: 2m                               # optional

  - id: arrived
    type: check-source
    source: landing                           # a file or object-store source
    files: [incoming/orders.csv]              # paths below the source's folder

  - id: load
    type: run-pipeline
    pipeline: orders-load

  - id: tell
    type: notify
    severity: high
    title: Not everything the load needs is there; it did not run

connections:
  - { from: ready, to: arrived }
  - { from: arrived, to: load }
  - { from: ready, to: tell, when: failure }
```

![What a check-source node does: it connects to the source as its test does; if it cannot, the node fails saying it could not reach the source. Then it looks up each table or file the node names, reading no rows. If all are there it succeeds with missing 0 and the next node runs; if any is missing it fails, naming them and reporting missing, and its failure connection is taken](images/flow-check-source.svg)

- **It keeps and shows no data.** It connects as the source's **Test** button does, then looks
  each name up as a scan would: a table's columns from the database's own lists, never a row; a
  file's columns from its start, as a scan reads it (up to 1 MiB). It holds a connection only while
  it checks (a database is logged in to once for each table), and no worker: it runs beside the
  flow, as a `sql` node does.
- **It succeeds when everything it names is there**, and fails when something is not: "2 of 3
  tables are missing on warehouse: sales.orders, sales.returns". A source it cannot reach fails it
  with "Could not reach warehouse". Its messages hold only the names you wrote, never what the
  source or its driver said, which can name hosts and users.
- **`missing`** is how many were not there, for conditions and notices (`${ready.missing}`). It
  has no value when the source could not be reached, as nothing was looked for.
- With neither `tables` nor `files`, it checks only that the source can be reached. Together the
  two lists name at most 50.
- **Checked when saved:** the source must exist; `tables` need a database source, and `files` a
  file or object-store source.
- It takes a `timeout`, as in [Stopping a node that takes too long](#stopping-a-node-that-takes-too-long);
  with none, a check that hangs is stopped after 10 minutes. A file path copied from the catalogue
  (`%23` for `#`) finds the file, as a read node does.

![A run of morning-orders: the check-source node arrived, "2 files on starter-samples-files", is outlined red as Failed; its on failure connection led to the notice tell, which succeeded, and the load node was skipped. The run timeline shows the same, and the Nodes table says arrived failed with "1 of 2 files is missing on starter-samples-files: incoming/orders-today.csv"](images/flow-check-source-run.png)

## Waiting until a file arrives or a query is true

A `wait-for` node waits until something is ready, then lets the flow go on: a file has arrived on a
file or object-store source, or a query on a PostgreSQL source has become true. It looks at once,
then every so often, and gives up after its timeout.

```yaml
nodes:
  - id: arrived
    type: wait-for
    source: landing                      # a file or object-store source
    file: "incoming/orders-*.csv"        # * and ? in the file's name only
    at_least: 1                          # how many must match (1 when left out)
    every: 5m                            # 30s to 1h (5m when left out)
    timeout: 2h                          # up to 24h (1h when left out)

  - id: loaded
    type: wait-for
    source: warehouse                    # PostgreSQL, with Allow SQL on
    query: "select max(loaded_at) > now() - interval '1 hour' from etl.batches"
    every: 2m

  - id: load
    type: run-pipeline
    pipeline: orders-load

  - id: tell
    type: notify
    severity: high
    title: "The orders had not arrived after ${arrived.waited_s} seconds"

connections:
  - { from: arrived, to: loaded }
  - { from: loaded, to: load }
  - { from: arrived, to: tell, when: failure }
```

![How a wait-for node waits: it looks at once; if what it waits for is ready it succeeds, reporting found and waited_s. If not, it waits its every, holding no worker and no connection, and looks again. Once its timeout has passed it gives up and fails. Cancelling the flow ends the wait at once](images/flow-wait-for.svg)

- **A file:** it counts the files below the source's folder whose path matches the pattern, `*`
  and `?` standing for any characters but `/`, so `incoming/orders-*.csv` never counts a file in
  `incoming/old/`. It reads only names, and counts only the files the source's own include and
  exclude patterns keep. On an object store with a prefix, write the pattern below the prefix.
  `found` is how many matched; it succeeds once at least `at_least` do.
- **A query:** SQL you write, so it follows the same rules as a custom SQL quality rule: one
  `SELECT` (or `WITH`), on a PostgreSQL source with **Allow SQL** on, run on a read-only connection
  for at most 30 seconds, under a login that is not a superuser. It is ready when the first value
  of its first row is true; nothing it returns is kept or shown, except that whether it was true
  (`found`, 1 or 0) shows on the run to whoever may see the run. Saving a flow with one needs a
  designer who may see unmasked values, as for a `sql` node, and each run that waits on one is
  recorded in the audit log, without the query.
- **Between looks it holds no connection and no pipeline worker:** only the flow's own watcher
  keeps watching, as it does for a `wait` node. A look that cannot be made (the source did not
  answer, or did not list its files in time) is tried again at the next one; one that never can (the
  source was deleted, or no longer allows SQL) fails the node at once.
- It reports `found` and `waited_s` (seconds), for conditions and notices. Cancelling the flow, or
  a `stop` node, ends the wait at once.

## The diagram

The **Diagram** tab draws the flow as you write it. Each node is a card, as on a pipeline's
canvas: its type, with an icon, its id, what it does (the pipeline it runs, the notice it sends,
how long it waits), and its connectors, `in` on the left and `out` on the right. Each connection is
an arrow from one node's `out` to another's `in`, labelled with when it is taken:

- a solid arrow, **on success**: the next node runs when this one succeeds (the default);
- a red dashed arrow, **on failure**: it runs when this one fails;
- a dotted arrow, **always**: it runs whether this one succeeds or fails;
- an amber arrow with the condition: it runs when this one succeeds and the condition holds.

The diagram is drawn from left to right, with each arrow in square steps round the nodes it
passes, turning only in the gaps between them. It
follows the text a moment after you stop typing; while the YAML cannot be read, it says so and
keeps showing the flow as it last could be read. Zoom with the buttons in its corner; **The
connections in words**, under it, lists every connection as a sentence.

## Changing the flow on the diagram

Designers can change the flow on the diagram, and in the panel under it, as well as in the YAML.
Each change is written into the YAML at once, changing only the lines it must, so your comments and
spacing stay as they are. Nothing is saved until you save a new version.

The panel under the diagram has the same tabs as a pipeline's:

- **Nodes** lists every node: what it does, what it runs after and when, and how it went in the
  flow's latest run (its status, when it started, how long it took, the rows its pipeline wrote and
  a link to that pipeline run). Choose a node's name to show its settings. Under the table are the
  forms to add a node and to connect two.
- **Settings** shows the node or connection chosen on the diagram or in the table, while one is
  chosen.
- **Run**, **History** and **Versions** show the latest run, every run and every saved version.
- **Schedules** shows its schedules; designers and admins add and change them there.

![The page of the sample flow sample-quality-decides: the diagram, with the node check, which runs a pipeline, joined from its out connector to a notice and to a second pipeline by two amber connections labelled with their conditions, and to a stop node by a red dashed connection on failure that runs round the nodes between; the notice joins the stop node on success. Under it the Nodes tab, a table giving each node, what it does, what it runs after, and its status, start, time and result in the latest run](images/flow-diagram-edit.png)

- **Add a node.** In the **Nodes** tab, choose its type (and, for one that runs a pipeline, the
  pipeline: every saved pipeline but `sample-tables`, which Kilnmark keeps to copy the sample data),
  then **Add node**. It gets an id from its type or pipeline, and the settings it starts
  with, and its settings open; the problems under the flow say what else it needs, such as a `sql`
  node's source.
- **Connect two nodes.** Drag from a node's `out` connector to another node: that connection is taken
  when the first succeeds. Or, in the **Nodes** tab, choose **From**, **To** and when it is taken,
  then **Connect**. A connection that would join a node to itself, make a loop or repeat one already
  there is refused, and the note under the diagram says why.
- **Change a node's settings.** Choose it on the diagram (click it, or Tab to it and press Enter)
  or by its name in the **Nodes** tab. The **Settings** tab shows the settings its type takes one
  value for: the pipeline and version it runs, the parameter a `for-each` node's items fill and how
  many run at once, a notice's severity, title and message, how long to wait, how a `stop` node ends
  the run, and a `sql` node's source. Change them and choose **Change**; a setting left blank is
  removed. Lists and mappings (a `for-each` node's items, the parameters given with `with`, a `sql`
  node's statements) are changed in the YAML.
- **Change or remove a connection.** Choose it on the diagram, or choose **Change** beside it in
  **The connections in words**. The **Settings** tab shows when it is taken, including its
  condition, to change, or **Remove connection**. **Remove node**, in a node's settings, removes its
  connections too.
- **Move a node.** Drag it, or choose it and use the arrow keys. The diagram then keeps every node where you see it, in a `layout:`
  block at the end of the YAML; connections between the same two nodes are drawn apart, and labels
  are drawn over the nodes. **Lay out again** removes the block, and the diagram lays the flow out
  itself.
- **Undo** puts back what the last change on the diagram changed, while the YAML is as that change
  left it. To go back to the saved version, choose **Undo my changes** under the flow.

The `layout:` block only says where the diagram draws each node: a flow runs the same however it
is drawn.

### Notes on the canvas

A flow's diagram has notes as a pipeline's canvas does ([Notes on the
canvas](pipelines.md#notes-on-the-canvas)): sticky notes anywhere on the diagram, and a short note
on any node, shown under its name. Kilnmark ignores them when it runs the flow, so a note never
changes a run or its check.

- **Add a note**, under the diagram beside **Undo**, adds one above the chosen node, attached to it,
  or above the diagram when no node is chosen, and opens its editor. Type what it says, then press
  Escape or choose **Done**.
- Double-click a note, or press Enter on it, to edit it, change its colour or the node it is
  attached to. Drag it, or its corner, to move or resize it, or use Shift and the arrow keys (with
  Ctrl as well to resize). Delete removes it. Each change is one step for **Undo**.
- **A node's note** is in **Note** in the node's **Settings** tab.

The text, its limits and the YAML are as a pipeline's: a `notes:` list after the connections, and
`note:` on a node. Notes are never masked: **never paste data values into a note**. Designers and
admins change them; everyone else reads them.

```yaml
nodes:
  - id: merge
    type: run-pipeline
    note: Goes on only while few rows are set aside
    pipeline: sample-onboarding-merge

# Notes on the canvas, for people. Kilnmark ignores this list when it runs the flow.
notes:
  - text: Give it a schedule under **Schedules** to run it every night.
    node: merge
    y: -170
```

## Checking as you type

The YAML is checked a moment after you stop typing: its shape, its connections (no loops), its
conditions, and that every pipeline it runs exists. Problems are listed under the flow with their
line; choose one to move to that line in the YAML tab. Lines with a problem are marked in the margin.

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

The **Versions** tab, in the panel under the flow, lists every version with who saved it, when and why. Open
one to see its YAML exactly as it was saved.

## Running a flow

Choose **Run now**, on the Flows page or the flow's own page. A flow with parameters asks for their
values first, each filled with its default: change what you need and choose **Run**. Lists are
written with commas between the values.

Kilnmark checks the latest saved version again first, since a pipeline may have changed, then
queues the run. Unsaved changes on the page are not part of it. Only one run of a flow is queued or
running at a time.

To run a flow at set times, such as every night, give it a schedule in the **Schedules** tab under
the diagram (designers and admins): see
[Running pipelines and flows on a schedule](schedules.md). The schedule form asks for the values of
the flow's parameters as Run now does. A run a schedule started shows `schedule <id> (set by
<name>)` as who asked for it.

To run a flow from your organisation's scheduler (Control-M, AutoSys, cron, Windows Task
Scheduler), so that the job waits for the run and ends with its outcome, see
[Running from an external scheduler](external-schedulers.md).

## Reading a run

Open a run from the **History** tab on the flow's page, or the last run from the line under its name.
A run page is laid out as a pipeline run's, in this order:

- **Summary**: its status, the version that ran, who started it, when it was asked for, started and
  finished, and how long it took; its nodes by how they stand ("2 succeeded, 1 skipped"); how many
  pipeline runs it asked for, the rows they wrote (so far, while it runs), and the lowest quality
  score among them (each pipeline run's score is already the lowest of what it wrote). If the run
  failed, or a stop node gave a message, it is said under the summary.
- **Values it ran with**, for stewards and above. Viewers do not see them, nor the messages that
  quote them, since they can hold server variables.
- **Where each node spent its time**: four tiles give the run at a glance: its **wall-clock time**,
  the **most nodes at once**, the **longest node** and its share of the run, and the time its
  pipeline runs were **queued for a worker**, added up. A line under them says what the run waited
  for: workers; a pipeline busy with another run (a pipeline runs once at a time), counted until a
  node asked for its run or, if it gave up, until it ended; time a `for-each` node had no item
  running or queued (Kilnmark does not record whether it waited for a busy pipeline or between
  items, and says so); its `wait` nodes; and, when several ran together, the most pipeline runs at
  once. Then a bar per node that started, each as long as its time on the run's scale, so bars
  compare, and split three ways, each with its own pattern:
  - **Working**: its pipeline running (for a `for-each` node, any of its items), or its own work,
    such as a `sql` node's statements or a notice;
  - **Queued for a worker**: none of its pipeline runs running, and one waiting for a worker;
  - **Waiting**: the rest, such as a `wait` node waiting, a node waiting for its pipeline to finish
    another run, or the moment the flow takes to see a pipeline run end.

  Beside each bar, its share working and what it did. **Show where each node spent its time as a
  table** gives the same as figures. All of it comes from the times Kilnmark records for each node
  and each pipeline run. A node whose pipeline run times are not known (its pipeline run was
  deleted, or the run is from before these were recorded) has a plain bar, not split; the tiles and
  the line then add up what is known and say of how many pipeline runs.
- the **diagram** of that version, each node outlined and labelled with how it stands in the run,
  kept current while the run goes;
- the **run timeline**: the whole run on one time line from its start, nested as it ran. One
  sentence says when it started, how long it took and how it ended. Under the flow's own row is a
  row for each node, with the icon its type has on the diagram (a raised hand for a `stop`), the
  pipeline it runs beside its name, and how long
  it took at the end of its bar. A node that ran a pipeline opens (▾) into that pipeline run's
  nodes, placed from when the run started; a `for-each` node opens into a row per item, its value
  beside it, and each item into its pipeline's nodes. Bars are soft violet for the flow, teal for
  its nodes and blue for a pipeline's nodes; red when it failed, striped while it runs; a skipped
  node says so. **Choose a row** (its name, or anywhere on its bar) for everything recorded about
  it below the chart: its status, start, end and time, the pipeline and its run, the time it
  waited for a worker, its message and what it reported, an item's value, or a pipeline node's
  rows, quality and error. An open pipeline run that is queued says it is waiting to start, one stopped
  before its nodes started says they were not timed, and one that could not be read offers to try
  again;
- **Nodes**: every node of that version in order: its status (Not started, Running, Succeeded,
  Failed, Cancelled or Skipped), when it started, how long it took, and what happened, such as the
  rows a pipeline wrote and its score, with a link to its **pipeline run**, where its nodes, rows
  and errors are. For a `for-each` node, how far its items have got ("3 of 5 items done, 1
  failed") and each item with its status and a link to its pipeline run; an item that could not be
  started is shown failed, with no run, and the node's message says why. A node, or an item, that
  took longer than its `timeout` is shown **Failed** with **Timed out** beside it; the timed-out
  item's link opens its cancelled pipeline run. On the run timeline its bar runs from its first
  item's start to its last item's end. Viewers see the items' values when they
  are written in the flow, not when they came from the run's values.

![The Diagram, Run timeline and Nodes panels of a run page that succeeded: the diagram shows each node as a card, the two pipeline nodes outlined green as Succeeded and the notice and the stop node dashed as Skipped, each with its status in its card; the run timeline nests the flow, its two pipeline nodes (each with its pipeline's name and time) and, under each, its pipeline's nodes, with the flow's details below; the Nodes table gives each node's status, start, time taken, rows written and score, with a link to its pipeline run](images/flow-run-page.png)

A node's message says that a pipeline failed and where to look, never why: why can quote values
from the data, and the pipeline run's own page shows it to those who may see it.

A node is **Skipped** when no connection into it was taken: the flow went another way.

A source can limit how many runs use it at once (**Most runs at once**, see
[Sources that take a few runs at a time](pipelines.md#sources-that-take-a-few-runs-at-a-time)). A
`sql` node on a full source stays Running and says "Waiting for a free place on source warehouse";
it starts when a place is free, and a run cancelled meanwhile never starts it. A pipeline run a
node started waits the same way, on its own page.

To stop a run, open it and choose **Cancel run** (stewards). Nothing more starts, and the pipeline
runs it started that are still going are cancelled. What they had already written stays written.

## Deleting a flow

Designers and admins can delete a flow they no longer need. Open it and choose **Delete**, beside
**Run now**. The page asks first, naming the flow and saying what goes and what stays:

![The page of the flow morning-orders after Delete was chosen: under its name, with Run now and Delete beside it, a warning asks "Delete the flow morning-orders? Its versions, its runs with what each node did, and its schedules are deleted. The pipeline runs it started stay, with what they wrote and the lineage they recorded. This cannot be undone.", with the buttons Delete morning-orders and Cancel, the focus on Cancel](images/flow-delete-confirm.png)

![What deleting a flow does: the flow and every version of it, its runs with what each node did, and its schedules (each recorded in the audit log) are deleted; the pipeline runs it started stay in their pipelines' history, with the tables and files they wrote, and their lineage and quality results in the catalogue. Deleting is refused while a run of the flow is queued or running](images/flow-delete.svg)

- **Deleted:** the flow, every version of it, its runs with what each node did, and its
  schedules. Each schedule deleted this way is recorded in the [audit log](audit-log.md) as
  **Schedule deleted**, saying it went with the flow.
- **Kept:** the pipeline runs it started, in their pipelines' history, with what they wrote, the
  lineage they recorded and their quality results. They no longer say which flow run started them.

Choose **Delete** followed by the flow's name to delete it; you are taken back to the Flows list, or to the
Library for a sample. Choose **Cancel** to keep it. Nothing can bring a deleted flow back, but its
YAML can be saved again under the same name as a new flow, starting at version 1.

A flow cannot be deleted while a run of it is queued or running: the page says so. Cancel the run,
or wait for it to finish, then delete. For a moment after a run ends, and while a scheduled run of
the flow is starting, the page may say the flow is busy: nothing was deleted, so try again. If you
have changed the flow on the page without saving, those changes go too, and the warning says so. A sample from the Library is deleted the same way; the
Library then shows it as not added, and **Add samples** adds it again.

Through the API: `DELETE /api/v1/flows/<name>` (designers and admins) answers 204 when the flow is
deleted, 404 when there is no such flow, and 409 while a run is queued or running.
