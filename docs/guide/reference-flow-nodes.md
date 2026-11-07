# Reference: flow nodes

Every node a flow can use, with every setting it takes. A flow runs its nodes in the order its
connections say; how to build one, and the diagram, are in
[Building and running a flow](flows.md). For the other kinds of component, see the
[Components reference](components-reference.md).

Each entry gives what the node does, the controls it takes (a timeout, tries again, a priority),
the values it reports for conditions and `${node.value}` in later nodes, a table of its settings,
what to know about it, who may use it, and an example you can copy. The controls and values come
from the server's own definitions, so they are always those of this version. Every node also has an
`id` (small letters, digits, `-` and `_`, starting with a letter; never `vars`) and its `type`.

## The flow and its connections

A flow's own keys are `kilnmark` (always `flow/v1`), `name`, `description`, `parameters`,
`queue_timeout` (how long a node waits for a pipeline that is already running before it fails; 30m
when left out, 24h at most), `max_parallel` (how many of its nodes run at once, 1 to 50; no limit
when left out), `run` (how the flow's runs are tried, below), `nodes`, `connections` and `layout`
(where the diagram draws each node).

### The run block

`run:` says how each run of the flow is tried ([Trying a whole run again](flows.md#trying-a-whole-run-again)):

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `retries` | whole number | `0` | How many more tries a failed run gets, 0 to 5, before it fails and its owners are told. The server may allow fewer (`kilnmark.runs.max-retries`). |
| `retry_delay` | time | `1m` | The wait before the second try, `10s` to `1h`, doubled before each later try. Each wait is at most an hour, and up to a tenth more is added at random. Needs `retries`. |
| `retry_on` | choice | `any` | `any`: any failure a second try could change. `lost`: only a try lost with its worker or its connection to Kilnmark's database. Needs `retries`. |
| `timeout` | time | no limit | How long each try may take, `90s` to `7d`. Past it the try is stopped and fails; it may be tried again. |
| `warn_after` | time | none | How long a run may go before its owners are told it is taking longer than expected, `1m` to `7d`, less than `timeout`. It does not stop the run. |

A flow tried again carries on from where it failed: nodes that succeeded keep what they did, the
rest run again, and a for-each node runs only its items that did not succeed. A node with
`retries` of its own is never run again by the flow's retries (its own take their place), and with
`retries` above 0 and `retry_on: any`, every pipeline the flow runs again must replace or upsert
what it writes, or the flow is refused when saved. A cancel, a `stop` node, a quality gate that
stopped a pipeline and a try that would start after the schedule's next fire are never tried again.

```yaml
kilnmark: flow/v1
name: nightly-orders-retried
description: Load the orders; a failed run is tried twice more, carrying on from where it failed.

run:
  retries: 2
  retry_delay: 5m
  timeout: 4h
  warn_after: 2h

nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-by-country
    with: { country: GB }
```

A connection has `from`, `to` and `when`: `success` (the default), `failure`, `always`, or a
condition on what earlier nodes reported, such as `"load.score >= good"`. The condition language,
and why an unknown value takes neither branch, are in [Building and running a
flow](flows.md#writing-a-flow).

Who may do what: designers save flows, stewards run and cancel them, and viewers see them and their
runs. A flow with a `sql` node, or a `wait-for` node with a query, is saved only by a designer whose
token sees unmasked values.

A flow can run on a schedule of its own
([Running pipelines and flows on a schedule](schedules.md)): its runs, and the pipeline runs its
nodes start, act as the schedule, as steward or designer, never admin. An enterprise scheduler
starts a flow with `kilnmark-run`, which waits for the whole flow and exits with a code: a flow a
`stop` node ended as failed, or one with a failure no connection dealt with, counts as failed
([Running from an external scheduler](external-schedulers.md)). Where the enterprise scheduler
already orders the jobs, a flow can still hold the ordering that belongs inside Kilnmark: checks,
waits, notices and conditions on quality.

## run-pipeline

Runs a saved pipeline, by name, and waits for its run to end. The node succeeds when the run does.

[generated: flow-node run-pipeline]: #

- `timeout`: yes: its work is stopped past it
- Tried again (`retries`): yes
- Priority: yes
- Reports, for conditions and `${node.value}`: `status` (text), `duration_s` (number), `quarantined_share` (number), `rows_written` (number), `score` (number)

[end: flow-node run-pipeline]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `pipeline` | pipeline name | yes | | The saved pipeline to run. |
| `version` | whole number | no | the latest | The version to run. Left out, each run takes the latest saved version. |
| `with` | parameter: value | no | | Values for the pipeline's parameters. A value that is exactly one `${name}` keeps that value's kind; other text has the values put in as words. |
| `timeout` | time | no | no limit | How long the run may take once a worker takes it: `90s`, `30m`, `2h` or `1d`, at most `7d`. Past it the run is cancelled and the node fails. |
| `priority` | choice | no | `normal` | `high`, `normal` or `low`: which waiting runs a free worker takes first. |
| `retries` | whole number | no | `0` | How many more times to try after a failure, 0 to 5. Only for a pipeline whose writes replace or upsert. |
| `retry_delay` | time | no | `1m` | The wait before the first try again, doubled before each one after, each wait at most an hour: `10s` to `1h`. |

**What to know.** The run is an ordinary pipeline run, started as the person (or schedule) who
started the flow, with their role, so a write still needs a steward and a source that allows
writing. If the pipeline is already running, the node waits for it for up to the flow's
`queue_timeout`. Saving the flow checks that the pipeline exists, that a named version does, and
that `with` gives only parameters the pipeline takes, of the kind it takes them.

```yaml
kilnmark: flow/v1
name: nightly-orders
description: Load the orders, then tell the team if quality slipped.

nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-by-country
    with: { country: GB }
    timeout: 2h
    priority: high
    retries: 2
    retry_delay: 5m

  - id: tell
    type: notify
    severity: medium
    title: "The orders load scored ${load.score}"

connections:
  - { from: load, to: tell, when: "load.score < good" }
```

Related: [Stopping a node that takes too long](flows.md#stopping-a-node-that-takes-too-long),
[Trying a node again after a failure](flows.md#trying-a-node-again-after-a-failure),
[Choosing which runs start first](flows.md#choosing-which-runs-start-first).

## for-each

Runs one pipeline once for each item of a list, giving each run the item as one of the pipeline's
parameters, several at once.

[generated: flow-node for-each]: #

- `timeout`: yes: its work is stopped past it
- Tried again (`retries`): yes
- Priority: yes
- Reports, for conditions and `${node.value}`: `status` (text), `failed` (number), `items` (number), `succeeded` (number)

[end: flow-node for-each]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `pipeline` | pipeline name | yes | | The saved pipeline to run for each item. |
| `version` | whole number | no | the latest | The version to run. |
| `items` | list, or `"${name}"` | yes | | 1 to 500 texts or numbers, all of one kind, or one text list or number list parameter of the flow (at most 200 values). |
| `as` | parameter name | yes | | The pipeline's parameter each item fills, of the items' kind. |
| `with` | parameter: value | no | | Values for the pipeline's other parameters, as for `run-pipeline`. |
| `max_parallel` | whole number | no | `4` | How many items run at once, 1 to 50. |
| `timeout` | time | no | no limit | How long each item's run may take, on its own. |
| `priority` | choice | no | `normal` | `high`, `normal` or `low`, for each item's run. |
| `retries` | whole number | no | `0` | How many more times to try each failed item, 0 to 5. |
| `retry_delay` | time | no | `1m` | The wait before an item's first try again, doubled after. |

**What to know.** Every item runs, even when one fails; the node succeeds only if every item did.
When items run at once, each must write its own table or file: saving refuses a pipeline whose
write does not name the item's parameter in its target, unless `max_parallel` is 1. The node counts
once against the flow's own `max_parallel`.

```yaml
kilnmark: flow/v1
name: orders-per-country
description: The orders of each country, two at a time.

nodes:
  - id: countries
    type: for-each
    pipeline: orders-by-country
    items: [GB, IE, FR]
    as: country
    max_parallel: 2
    timeout: 30m

  - id: tell
    type: notify
    title: "${countries.succeeded} of ${countries.items} countries loaded"

connections:
  - { from: countries, to: tell, when: always }
```

Related: [Running one pipeline for each item](flows.md#running-one-pipeline-for-each-item).

## sql

Runs SQL statements on a PostgreSQL source, in order, in one transaction: to empty a staging table
before a load, say, or refresh a summary after one. It changes data and returns no rows.

[generated: flow-node sql]: #

- `timeout`: yes: its work is stopped past it
- Tried again (`retries`): yes
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text), `rows_affected` (number)

[end: flow-node sql]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `source` | saved source | yes | | A PostgreSQL source with Allow SQL and Allow writing on. |
| `statements` | list of SQL | yes | | 1 to 20 statements, each one statement of at most 10,000 characters, run in order. |
| `timeout` | time | no | no limit | How long the statements may take together. Past it the statement running is cancelled and everything rolls back. |
| `retries` | whole number | no | `0` | How many more times to try after the statements fail and roll back, 0 to 5. A failed commit is never tried again. |
| `retry_delay` | time | no | `1m` | The wait before the first try again, doubled after. |

**What to know.** Statements are written as they run: `${...}` is refused in them, since a value
put into SQL could change what it does. Each must start with `TRUNCATE`, `INSERT`, `UPDATE`,
`DELETE`, `MERGE`, `CREATE`, `DROP`, `ALTER`, `CALL`, `ANALYZE`, `REFRESH`, `COMMENT`, `REINDEX`,
`CLUSTER` or `LOCK`; never a query, transaction control, `SET`, `COPY`, `DO`, `GRANT` or `REVOKE`.
If one fails, all roll back, and the node's message names which one and its SQLSTATE, never the
database's words.

**Governance.** Saved only by a designer whose token sees unmasked values. At run time the login is
checked again: never a superuser, nor one that may read files or run programs. Each sql node that
starts is recorded in the audit log, without its statements.

```yaml
kilnmark: flow/v1
name: refresh-orders
description: Empty the staging table, load it, then analyse it.

nodes:
  - id: empty
    type: sql
    source: warehouse
    statements:
      - TRUNCATE sales.orders_staging
    timeout: 5m

  - id: load
    type: run-pipeline
    pipeline: orders-by-country

  - id: analyse
    type: sql
    source: warehouse
    statements:
      - ANALYZE sales.orders
    retries: 1

connections:
  - { from: empty, to: load }
  - { from: load, to: analyse }
```

Related: [Custom SQL](quality-rules.md#custom-sql-designers), which follows the same rules.

## check-source

Checks that a source can be reached and holds the tables or files the work needs, before anything
runs, reading no rows.

[generated: flow-node check-source]: #

- `timeout`: yes: its work is stopped past it
- Tried again (`retries`): no
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text), `missing` (number)

[end: flow-node check-source]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `source` | saved source | yes | | The source to check. |
| `tables` | list of names | no | | Tables as the catalogue names them, such as `sales.orders`. Database sources only. |
| `files` | list of paths | no | | Paths below the source's folder. File and object-store sources only. |
| `timeout` | time | no | `10m` | How long the check may take. |

**What to know.** With neither list it checks only that the source can be reached; together the
lists name at most 50. It fails naming what is missing, and reports `missing`; its messages hold
only the names you wrote, never what the source or its driver said.

```yaml
kilnmark: flow/v1
name: morning-orders
description: Load the orders only once everything they need is there.

nodes:
  - id: ready
    type: check-source
    source: sales-db
    tables: [sales.orders, crm.customers]
    timeout: 2m

  - id: load
    type: run-pipeline
    pipeline: orders-by-country

  - id: tell
    type: notify
    severity: high
    title: "${ready.missing} tables the load needs are missing"

connections:
  - { from: ready, to: load }
  - { from: ready, to: tell, when: failure }
```

Related: [Checking a source before the work starts](flows.md#checking-a-source-before-the-work-starts).

## wait-for

Waits until a file has arrived on a file or object-store source, or a query on a PostgreSQL source
is true. It looks at once, then every so often, and gives up after its timeout.

[generated: flow-node wait-for]: #

- `timeout`: yes: how long it waits before it gives up
- Tried again (`retries`): no
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text), `found` (number), `waited_s` (number)

[end: flow-node wait-for]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `source` | saved source | yes | | The source to look at. |
| `file` | path pattern | one of `file` or `query` | | Files below the source's folder; `*` and `?` in the last part only. |
| `at_least` | whole number | no | `1` | How many files must match, up to 10,000. |
| `query` | SQL | one of `file` or `query` | | One `SELECT` whose first value is true when ready. PostgreSQL with Allow SQL on. |
| `every` | time | no | `5m` | How long between looks: `30s` to `1h`. |
| `timeout` | time | no | `1h` | How long to wait before giving up, at most `24h`. |

**What to know.** Between looks it holds no connection and no worker. A query runs on a read-only
connection for at most 30 seconds, checked as a custom SQL rule is; nothing it returns is kept or
shown. A flow with a query is saved only by a designer whose token sees unmasked values, and each
run that waits on one is recorded in the audit log, without the query.

```yaml
kilnmark: flow/v1
name: when-orders-land
description: Load the orders once the day's file has arrived.

nodes:
  - id: arrived
    type: wait-for
    source: landing
    file: "incoming/orders-*.csv"
    at_least: 1
    every: 5m
    timeout: 2h

  - id: load
    type: run-pipeline
    pipeline: orders-from-files

  - id: tell
    type: notify
    severity: high
    title: "No orders file after ${arrived.waited_s} seconds"

connections:
  - { from: arrived, to: load }
  - { from: arrived, to: tell, when: failure }
```

Related: [Waiting until a file arrives or a query is true](flows.md#waiting-until-a-file-arrives-or-a-query-is-true).

## wait

Waits a while, holding no worker and no pipeline.

[generated: flow-node wait]: #

- `timeout`: no
- Tried again (`retries`): no
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text)

[end: flow-node wait]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `for` | time | yes | | How long: `90s`, `10m` or `2h`, at most `24h`. |

**What to know.** Cancelling the flow, or a `stop` node, ends the wait at once.

```yaml
kilnmark: flow/v1
name: load-then-settle
description: Give the warehouse ten minutes before the summary.

nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-by-country

  - id: settle
    type: wait
    for: 10m

  - id: summary
    type: run-pipeline
    pipeline: orders-per-customer

connections:
  - { from: load, to: settle }
  - { from: settle, to: summary }
```

## notify

Sends a notice, in the app and by email, to whoever follows flow notices, and ends at once.

[generated: flow-node notify]: #

- `timeout`: no
- Tried again (`retries`): no
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text)

[end: flow-node notify]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `severity` | choice | no | `info` | `info`, `low`, `medium` or `high`: how much it matters. |
| `title` | text | yes | | One line, at most 200 characters: the notice's subject. May hold `${...}`. |
| `message` | text | no | | At most 2,000 characters. May hold `${...}`. |

**What to know.** Values put into a notice are the flow's own (its parameters, server variables
and what earlier nodes reported); a value nothing measured reads as "unknown". Viewers do not see a
notice's text on a run, as it can hold server variables, which only stewards read.

```yaml
kilnmark: flow/v1
name: orders-with-notice
description: Say when the load failed.

nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-by-country

  - id: tell
    type: notify
    severity: high
    title: The orders load failed
    message: "It ran for ${load.duration_s} seconds. Open the run for the reason."

connections:
  - { from: load, to: tell, when: failure }
```

Related: [Notifications and email alerts](notifications.md).

## stop

Ends the flow: nothing more starts, nodes that have not started are skipped, and pipeline runs still
going are cancelled.

[generated: flow-node stop]: #

- `timeout`: no
- Tried again (`retries`): no
- Priority: no
- Reports, for conditions and `${node.value}`: `status` (text)

[end: flow-node stop]: #

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `status` | choice | no | `failed` | How the flow counts when this node ends it: `failed` or `succeeded`. |
| `message` | text | no | | The run's message. |

**What to know.** A `stop` node never waits for the flow's `max_parallel`, so a flow can always end.

```yaml
kilnmark: flow/v1
name: orders-or-stop
description: Stop before the summary if too many rows were set aside.

nodes:
  - id: load
    type: run-pipeline
    pipeline: orders-by-country

  - id: summary
    type: run-pipeline
    pipeline: orders-per-customer

  - id: halt
    type: stop
    status: failed
    message: Too many orders were set aside

connections:
  - { from: load, to: summary, when: "load.quarantined_share < 0.05" }
  - { from: load, to: halt, when: "load.quarantined_share >= 0.05" }
```
