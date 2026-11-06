# Reference: pipeline nodes

Every node a pipeline can use, with every setting it takes. A pipeline reads rows from sources,
changes them, checks them and writes them, in one pass; how to build and run one is in
[Building and running a pipeline](pipelines.md). For the other kinds of component, see the
[Components reference](components-reference.md).

Each entry gives what the node does and its kind, the ports it reads from and writes to, a table
of its settings, what to know about it, who may use it, and a whole pipeline you can copy. The
first part of each entry, up to the settings table, comes from the node catalogue the server uses
(`GET /api/v1/node-types`), so it always matches this version. The **Parameters** column says what
`${...}` may do in a setting, as [Parameters and server
variables](pipelines.md#parameters-and-server-variables) explains.

## Keys every node has

| Key | What it does |
| --- | --- |
| `id` | The node's name in the pipeline: a small letter, then small letters, digits, `-` and `_`, at most 64 characters. Required. |
| `type` | The node's type: one of the entries below. Required. |
| `input` | The node that feeds this one: `input: tidy` reads the `out` port of `tidy`, `input: tidy.rejected` its `rejected` port. A `union` lists several: `input: [north, south]`. |
| `inputs` | For a node with named inputs, each input and the node feeding it: `inputs: { main: orders, lookup: customers }`. |

Rows sent to a port that nothing reads are dropped. Every other key of a node is one of its
settings; a key the node does not take is a problem, with its line.

Who may do what, for every node: designers save pipelines, stewards run them, and viewers see them
and their runs. A run acts as the person who started it, or as the schedule that did
([Running pipelines and flows on a schedule](schedules.md)), so a pipeline that writes needs each
target source to allow writing. An enterprise scheduler starts a pipeline with `kilnmark-run`,
whose exit code says whether the run succeeded, failed (a stop gate fails it) or was cancelled
([Running from an external scheduler](external-schedulers.md)).

## read

Reads a table, view or file from a saved source, or the rows of one query.

[generated: node read]: #

**Read a table or file**: a source node, in the palette's Read folder. Reads a table, view or file from a saved source. The source holds the credentials, so the pipeline only names it.

- Reads from: nothing
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `source` | saved source | yes |  | no | The saved source to read from. |
| `asset` | table or file | no |  | no | A table or view as schema.table, or a file's path inside the source. Leave out to run a query. |
| `query` | text | no |  | no | One SELECT run as written, in place of a table (PostgreSQL sources with Allow SQL on). Its columns are known when it runs. |
| `columns` | list of columns | no |  | no | Only these columns, in this order; all of them when left out. A database reads only these. |
| `where` | expression | no |  | yes | Only the rows meeting this condition, such as status = 'delivered'. A database applies what it can while reading, with each value bound; Kilnmark checks every row that arrives. |
| `row_limit` | number | no | `0` | whole value | Reads at most this many rows (after where); 0 reads them all. Handy while trying a pipeline out. |

[end: node read]: #

**What to know.**

- `asset` is a table or view as `schema.table`, or a file's path inside the source as the
  catalogue shows it. A read gives `asset` or `query`, not both.
- With `columns` and `where`, a database reads only those columns and applies as much of the
  condition as it safely can, each value bound, never written into the SQL. Kilnmark checks every
  row that arrives against the whole condition anyway. Files, and generic JDBC sources, read every
  row and Kilnmark applies `where`. The node's note on the canvas says which.
- `row_limit` counts the rows that meet `where`; 0 reads them all.
- A `query` is one `SELECT` or `WITH`, checked as custom SQL rules are, run on a read-only
  connection, and never under a login that is a superuser or may read the server's files. Its
  columns are known only when it runs. Parameters are not filled in a query.

**Governance.** A query can read any column its login can, so only a designer who may see unmasked
values saves or previews a pipeline with one, and only on a PostgreSQL source with Allow SQL on.
Every column a query returns is treated as marked sensitive: masked for everyone else, and marked on
what a write writes from it. Values read from a table keep the table's own marks.

```yaml
kilnmark: pipeline/v1
name: orders-by-country
description: One country's delivered orders, in a table of their own.

parameters:
  country: { type: text, default: GB }

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders
    columns: [order_id, customer_id, amount, country, status]
    where: "country = ${country} and status = 'delivered'"

  - id: out
    type: write
    input: orders
    source: warehouse
    table: sales.orders_${country}
    mode: replace
```

Reading with a query:

```yaml
kilnmark: pipeline/v1
name: orders-per-country-query
description: Orders counted per country by the database.

nodes:
  - id: counts
    type: read
    source: sales-db
    query: >-
      SELECT country, count(*) AS orders
      FROM sales.orders GROUP BY country
    row_limit: 1000

  - id: out
    type: write
    input: counts
    source: files
    path: exports/orders-per-country.csv
    mode: replace
```

Related: [Reading](pipelines.md#reading), [Reading with a query](pipelines.md#reading-with-a-query),
[Reference: connectors](reference-connectors.md).

## write

Writes the rows it receives to a table or a file in a saved source.

[generated: node write]: #

**Write to a table or file**: a sink node, in the palette's Write folder. Writes rows to a database table or a file in a saved source. The source must allow writing, which a designer or an admin sets; what is written is registered in the catalogue with its lineage.

- Reads from: `in`
- Writes to: nothing

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `source` | saved source | yes |  | no | The saved source to write to. |
| `target` | table or file | no |  | no | A table as schema.table, or a path inside the source for files. |
| `table` | text | no |  | part of the name | Another name for target, for a table. |
| `path` | text | no |  | part of the name | Another name for target, for a file. |
| `mode` | choice | no | `create` | no | How rows are written: create fails if the table or file exists; append adds rows; replace swaps in the new rows at the end; upsert updates rows with the same key and adds the rest. One of `create`, `append`, `replace`, `upsert`. |
| `key` | list of columns | no |  | no | The columns that identify a row, for upsert. |
| `format` | choice | no |  | no | For files: csv or parquet. Taken from the path's extension when left out. One of `csv`, `parquet`. |

The writer settings, which only some kinds of source take:

| Setting | Type | Default | Sources that take it | What it does |
| --- | --- | --- | --- | --- |
| `overwrite` | true or false | `false` | `files`, `s3` | Replace a file of the same name; otherwise an existing file stops the write. |
| `delimiter` | text |  | `files`, `s3` | CSV only: one character, or \t for a tab; by default the source's own delimiter, else a comma. A delimiter other than the source's is refused. |
| `compression` | choice | `snappy` | `files`, `s3` | Parquet only: how pages are compressed. One of `snappy`, `zstd`, `gzip`, `none`. |
| `allow_unscanned` | true or false | `false` | `files`, `s3` | Write even where scans of this source would not find the file. |
| `profile_output` | true or false | `true` | `files`, `jdbc`, `mariadb`, `mysql`, `oracle`, `postgresql`, `s3`, `sqlserver` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | `files`, `jdbc`, `mariadb`, `mysql`, `oracle`, `postgresql`, `s3`, `sqlserver` | Whether the profile keeps example values and sample rows; off keeps the statistics only. The source's own rule still applies. |
| `commit_every` | number | `0` | `jdbc`, `mariadb`, `mysql`, `oracle`, `postgresql`, `sqlserver` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | `jdbc`, `mariadb`, `mysql`, `oracle`, `postgresql`, `sqlserver` | Differs by kind of source: see each one in [Reference: connectors](reference-connectors.md). |
| `text_length` | number | `0` | `jdbc`, `mariadb`, `mysql`, `oracle`, `postgresql`, `sqlserver` | Differs by kind of source: see each one in [Reference: connectors](reference-connectors.md). |
| `lock_timeout` | number | `30` | `mariadb`, `mysql`, `oracle`, `postgresql`, `sqlserver` | For replace: how long the swap waits for others using the old table. |
| `bulk_load` | true or false | `true` | `mariadb`, `mysql` | Load tables this node creates with LOAD DATA LOCAL INFILE, sent from memory, when the server allows it (local_infile); otherwise, or when false, with batched inserts. |
| `allow_other_unique_keys` | true or false | `false` | `mariadb`, `mysql` | For upsert: a row matching any unique index is updated, not only one matching the key, so a table with a unique index lacking a key column is refused. true allows it, accepting that such rows are updated. |
| `keep_grants` | true or false | `true` | `oracle`, `postgresql`, `sqlserver` | For replace: repeat the old table's grants on the new one. |
| `allow_dropping_row_security` | true or false | `false` | `oracle`, `postgresql`, `sqlserver` | Differs by kind of source: see each one in [Reference: connectors](reference-connectors.md). |
| `empty_text_as_null` | true or false | `false` | `oracle` | Oracle stores empty text as null. false (the default) refuses empty text, so nothing changes silently; true writes it as null. |
| `bulk_copy` | true or false | `true` | `sqlserver` | Load tables this node creates through the driver's bulk copy. |

[end: node write]: #

**What to know.**

- Give the target as `table` (`schema.table`) for a database, `path` for a file, or `target` for
  either. A file's `format` is `csv` or `parquet`, taken from its extension when left out.
- `mode`: `create` writes a new table or file and fails if it exists; `append` adds rows to what is
  there; `replace` fills a new table or file and swaps it in at the end; `upsert` updates rows with
  the same `key` and adds the rest. Each source kind's writer may refuse a mode: generic JDBC
  sources take `append` and `create` only, and files `create` and `replace` only, as files are
  written whole.
- Nothing appears until the write has finished: a table write is one transaction, and a file is
  written under a temporary name and put in place at the end, unless `commit_every` says otherwise.
- The writer settings depend on the target source's kind: a setting the source's writer does not
  take is a problem. Where the writers taking a setting describe it differently, the table says so;
  each connector's own are in [Reference: connectors](reference-connectors.md).
- What a create, replace or file write writes is profiled in the same pass and recorded in the
  catalogue with its lineage; an append or upsert records only how many rows it wrote.

**Governance.** The source must allow writing (`allow_writing`), which only designers and admins
turn on, and the write uses the source's own login, so that login needs write rights. A parameter
can fill only part of the last name of `table` or `path`, never choose the schema, the folder or
the source. Columns marked sensitive upstream are marked on what is written.

```yaml
kilnmark: pipeline/v1
name: orders-from-files
description: The day's orders from a file, upserted into the warehouse and kept as Parquet.

nodes:
  - id: orders
    type: read
    source: landing
    asset: incoming/orders.csv

  - id: table
    type: write
    input: orders
    source: warehouse
    table: sales.orders
    mode: upsert
    key: [order_id]
    commit_every: 50000

  - id: file
    type: write
    input: orders
    source: lake
    path: exports/orders.parquet
    mode: replace
    compression: zstd
```

Related: [Writing data from a pipeline](writing-data.md), with the modes, the column types a
create chooses and the rights each mode needs.

## gate

Checks the rows passing through it against quality rules, in the same pass that moves them.

[generated: node gate]: #

**Quality gate**: a gate node, in the palette's Quality folder. Checks rows against quality rules as they pass, with no second read: the rules of the asset read upstream and any written here. Stops the run, sets failing rows aside on the quarantine port, or lets them through with a warning.

- Reads from: `in`
- Writes to: `out`, `quarantine`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `on_fail` | choice | no | `stop` | no | What to do when rows fail: stop fails the run, so the writes after the gate do not commit; quarantine sends failing rows to the quarantine port and the rest on; warn lets every row through and records what failed. One of `stop`, `quarantine`, `warn`. |
| `asset_rules` | true or false | no | `true` | whole value | Checks the enabled quality rules of the table or file read upstream. |
| `rules_from` | text | no |  | no | When rows come from more than one read node: the id of the one whose asset's rules to check. |
| `rules` | list of rules | no |  | no | Rules for this pipeline only, each with a type, a column and its own settings. |
| `min_score` | number | no |  | whole value | Stops the run (with stop or quarantine) when the rows score below this, from 0 to 1. |
| `max_failing_share` | number | no |  | whole value | Stops the run (with stop or quarantine) when more than this share of rows fail a rule, from 0 to 1. |

[end: node gate]: #

**What to know.**

- Its rules are the enabled rules of the asset read upstream (unless `asset_rules: false`; with
  several read nodes upstream, name one in `rules_from`) and any written under `rules`, each a
  mapping with a `type`, usually a `field`, and the type's own keys, as
  [Reference: quality rules](reference-quality-rules.md) lists them. At most 100 rules on a gate.
- `on_fail: stop` fails the run when a rule fails, so the writes after the gate do not commit;
  `quarantine` sends failing rows to `quarantine` and the rest on; `warn` lets every row through and
  records what failed.
- `max_failing_share` and `min_score` judge the rows as a whole, with `stop` and `quarantine`.
- A stop gate refuses a later write that commits as rows arrive (`commit_every` with `create`,
  `append` or `upsert`) and a later `limit`.
- `references` and `custom_sql` rules run in a database, so they cannot go on a gate.

**Governance.** Rows set aside on `quarantine` keep their columns' marks. What failed is recorded
with the run, and counts towards the scores of what the run writes.

```yaml
kilnmark: pipeline/v1
name: customers-checked
description: Customers with an email and a known country; the rest set aside.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: check
    type: gate
    input: customers
    on_fail: quarantine
    rules:
      - {type: not_null, field: email}
      - {name: known country, type: allowed_values, field: country, values: [GB, IE, FR]}
    max_failing_share: 0.05

  - id: good
    type: write
    input: check
    source: warehouse
    table: crm.customers_clean
    mode: replace

  - id: aside
    type: write
    input: check.quarantine
    source: files
    path: quarantine/customers.csv
    mode: replace
```

Related: [Quality gates](pipelines.md#quality-gates), [What a gate does when rows
fail](pipelines.md#what-happens-when-rows-fail).

## filter

Keeps the rows where a condition is true.

[generated: node filter]: #

**Filter rows**: a transform node, in the palette's Filter and route folder. Keeps the rows where a condition is true. Rows where it is false or empty (null) go to the rejected port, if something is connected to it.

- Reads from: `in`
- Writes to: `out`, `rejected`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `condition` | expression | yes |  | yes | The rows to keep, for example status = 'active' and amount > 0. |

[end: node filter]: #

**What to know.** Rows where the condition is false or empty (null) go to `rejected`; if nothing
reads `rejected`, they are dropped. The condition is an [expression](pipeline-nodes.md#expressions),
checked against the columns before the pipeline runs.

```yaml
kilnmark: pipeline/v1
name: active-customers
description: Active customers in credit, and the rest apart.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: active
    type: filter
    input: customers
    condition: "status = 'active' and balance > 0"

  - id: kept
    type: write
    input: active
    source: files
    path: exports/active-customers.csv
    mode: replace

  - id: others
    type: write
    input: active.rejected
    source: files
    path: exports/other-customers.csv
    mode: replace
```

Related: [filter](pipeline-nodes.md#filter).

## compute

Adds or replaces columns, each worked out from an expression.

[generated: node compute]: #

**Compute columns**: a transform node, in the palette's Transform folder. Adds or replaces columns, each worked out from an expression. Later expressions can use columns computed earlier in the list.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `columns` | names and expressions | yes |  | yes | Each column's name and the expression that works it out, for example full_name: trim(first_name) \|\| ' ' \|\| trim(last_name). |

[end: node compute]: #

**What to know.** A name that is already a column replaces it where it stands; a new name is added
at the end. Expressions are worked out in the order written, and each can use the columns computed
before it.

```yaml
kilnmark: pipeline/v1
name: customer-names
description: Tidy names and emails.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: tidy
    type: compute
    input: customers
    columns:
      first_name: "trim(first_name)"
      full_name: "first_name || ' ' || trim(last_name)"
      email: "lower(trim(email))"

  - id: out
    type: write
    input: tidy
    source: warehouse
    table: crm.customer_names
    mode: replace
```

Related: [compute](pipeline-nodes.md#compute), [Functions](pipeline-nodes.md#functions).

## select

Keeps or drops columns, renames them, puts them in order and changes their type.

[generated: node select]: #

**Select columns**: a transform node, in the palette's Transform folder. Keeps or drops columns, and renames, reorders and changes the type of the columns kept. Every setting names columns as they arrive.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `keep` | list of columns | no |  | no | Only these columns, in this order. Leave empty to keep them all. |
| `drop` | list of columns | no |  | no | Columns to leave out. Use either keep or drop, not both. |
| `rename` | column to column | no |  | no | New names: each column as it arrives, and its new name. |
| `order` | list of columns | no |  | no | Columns to put first, in this order; the rest follow as they were. |
| `cast` | names and expressions | no |  | yes | Columns to convert, and the type for each: boolean, int64, float64, decimal(p,s), utf8, date or timestamp. |

[end: node select]: #

**What to know.** Every setting names columns as they arrive. They apply in this order: keep
or drop, change type, rename, order. Casting text is strict: a value that does not fit stops the
run.

```yaml
kilnmark: pipeline/v1
name: customer-shape
description: The columns a report needs, named as it wants them.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: shape
    type: select
    input: customers
    drop: [created_at]
    rename: { last_name: surname }
    order: [customer_id, first_name]
    cast: { customer_id: utf8, balance: "decimal(12,2)" }

  - id: out
    type: write
    input: shape
    source: files
    path: exports/customers.csv
    mode: replace
```

Related: [select](pipeline-nodes.md#select), [Types](pipeline-nodes.md#types).

## lookup

Adds columns from the `lookup` input to each row of the `main` input with the same key, like a
join.

[generated: node lookup]: #

**Look up**: a transform node, in the palette's Combine folder. Adds columns from the lookup input to each main row with the same key, like a join. Rows with no match are kept, dropped or sent to the rejected port.

- Reads from: `main`, `lookup`
- Writes to: `out`, `rejected`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `on` | column to column | yes |  | no | Key columns: each main column and the lookup column it must equal. |
| `keep` | list of columns | no |  | no | Lookup columns to add to main rows. Leave empty to add every lookup column except the keys. |
| `join` | choice | no | `left` | no | What happens to main rows with no match: left keeps them, inner drops them. One of `left`, `inner`. |
| `unmatched` | choice | no |  | no | What to do with a main row that has no match: keep it, drop it, or send it to the rejected port. Overrides join when set. One of `keep`, `drop`, `rejected`. |
| `duplicates` | choice | no | `first` | no | When the lookup side has a key more than once: use the first row, or stop with an error. One of `first`, `error`. |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory the lookup side (and main rows waiting for it) may take. |

[end: node lookup]: #

**What to know.** The lookup input is read in full and held in memory first, up to
`memory_limit_mb`; then main rows stream through. Keys match by value (`1`, `1.0` and `1.00`
match), empty keys never match, and text never matches a number. An added column must not have the
same name as a main column.

```yaml
kilnmark: pipeline/v1
name: orders-with-customers
description: Each order with its customer's name; orders with no customer apart.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: customers
    type: read
    source: sales-db
    asset: crm.customers
    columns: [customer_id, first_name, last_name]

  - id: joined
    type: lookup
    inputs: { main: orders, lookup: customers }
    on: { customer_id: customer_id }
    keep: [first_name, last_name]
    unmatched: rejected

  - id: out
    type: write
    input: joined
    source: warehouse
    table: sales.orders_named
    mode: replace

  - id: orphans
    type: write
    input: joined.rejected
    source: files
    path: quarantine/orders-without-customer.csv
    mode: replace
```

Related: [lookup](pipeline-nodes.md#lookup).

## aggregate

Groups rows that share values and summarises each group.

[generated: node aggregate]: #

**Aggregate**: a transform node, in the palette's Aggregate folder. Groups rows that share values and summarises each group with count, sum, min, max, avg or count_distinct. Without group_by, summarises all rows into one.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `group_by` | list of columns | no |  | no | The columns whose values make a group. Leave empty for one row summarising everything. |
| `values` | names and expressions | yes |  | yes | Each output column's name and its summary, for example total: sum(amount). |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory the groups may take while they are collected. |

[end: node aggregate]: #

**What to know.** Without `group_by` it summarises all rows into one, even when there are none. A
value can combine [aggregate functions](pipeline-nodes.md#aggregate) and group-by columns; a column
used outside an aggregate must be in `group_by`. Groups are held in memory up to
`memory_limit_mb`, and come out in the order they were first seen.

```yaml
kilnmark: pipeline/v1
name: orders-per-customer
description: Each customer's orders and spend.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: spend
    type: aggregate
    input: orders
    group_by: [customer_id]
    values:
      orders: "count(*)"
      total: "sum(amount)"
      last_order: "max(ordered_at)"

  - id: out
    type: write
    input: spend
    source: warehouse
    table: sales.orders_per_customer
    mode: replace
```

Related: [aggregate](pipeline-nodes.md#aggregate).

## union

Appends the rows of all its inputs, matching columns by name.

[generated: node union]: #

**Union**: a transform node, in the palette's Combine folder. Appends the rows of all its inputs, matching columns by name. Columns an input lacks are left empty (null).

- Reads from: `in` (any number of edges)
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `strict` | true or false | no | `false` | whole value | Fail unless every input has exactly the same columns, with the same types. |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory batches may take while the union waits for every input's first batch. |

[end: node union]: #

**What to know.** Columns come out in the order they first appear across the inputs. A column
missing from an input is empty in its rows; a column whose type differs is widened to one that
holds both, and other differences stop the pipeline before it runs. With `strict: true`, every input
must have exactly the same columns and types.

```yaml
kilnmark: pipeline/v1
name: all-customers
description: Today's customers and the archive, together.

nodes:
  - id: current
    type: read
    source: sales-db
    asset: crm.customers

  - id: archived
    type: read
    source: sales-db
    asset: crm.customers_archive

  - id: everyone
    type: union
    input: [current, archived]

  - id: out
    type: write
    input: everyone
    source: warehouse
    table: crm.all_customers
    mode: replace
```

Related: [union](pipeline-nodes.md#union).

## dedupe

Keeps the first row for each key; later rows with the same key go to `rejected`.

[generated: node dedupe]: #

**Remove duplicates**: a transform node, in the palette's Filter and route folder. Keeps the first row for each key. Later rows with the same key go to the rejected port, if something is connected to it.

- Reads from: `in`
- Writes to: `out`, `rejected`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `key` | list of columns | no |  | no | The columns that make rows duplicates of each other. Leave empty to compare whole rows. |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory the keys seen so far may take. |

[end: node dedupe]: #

**What to know.** Empty values count as equal to each other. Without `key`, whole rows are
compared. The keys seen so far are held in memory up to `memory_limit_mb`.

```yaml
kilnmark: pipeline/v1
name: one-per-email
description: One customer for each email address.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: unique
    type: dedupe
    input: customers
    key: [email]

  - id: out
    type: write
    input: unique
    source: warehouse
    table: crm.customers_by_email
    mode: replace

  - id: repeats
    type: write
    input: unique.rejected
    source: files
    path: quarantine/repeated-emails.csv
    mode: replace
```

Related: [dedupe](pipeline-nodes.md#dedupe).

## route

Sends each row to the first route whose condition it meets; rows that meet none go to `out`.

[generated: node route]: #

**Route rows**: a transform node, in the palette's Filter and route folder. Sends each row to the first route whose condition it meets, in order. Rows that meet none go to the out port.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `routes` | names and conditions | yes |  | yes | Each route's port name and its condition, checked in order, for example large: amount > 1000. |

[end: node route]: #

**What to know.** Each route's name is an output port of its own, besides `out`, that other nodes
read as `input: <node>.<route>`. Port names use small letters, digits, `-` and `_`, and cannot be
`in` or `out`. Routes are tried in the order written.

```yaml
kilnmark: pipeline/v1
name: orders-by-size
description: Large and overseas orders apart from the rest.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: by_size
    type: route
    input: orders
    routes:
      large: "amount >= 10000"
      overseas: "country <> 'GB'"

  - id: large
    type: write
    input: by_size.large
    source: files
    path: review/large-orders.csv
    mode: replace

  - id: overseas
    type: write
    input: by_size.overseas
    source: files
    path: review/overseas-orders.csv
    mode: replace

  - id: rest
    type: write
    input: by_size
    source: warehouse
    table: sales.orders_home
    mode: replace
```

Related: [route](pipeline-nodes.md#route).

## limit

Passes on only the first rows, for trying a pipeline out on a little data.

[generated: node limit]: #

**Limit rows**: a transform node, in the palette's Filter and route folder. Passes on only the first rows, for trying a pipeline out on a little data.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `rows` | number | yes |  | whole value | How many rows to pass on. |

[end: node limit]: #

**What to know.** Once it has its rows it stops reading, and nodes before it that feed nothing else
stop too. A `limit` after a gate with `on_fail: stop` is refused: the gate would never see the end
of its rows. To read fewer rows from a source, a read node's `row_limit` does it at the source.

```yaml
kilnmark: pipeline/v1
name: orders-sample
description: The first thousand orders, to try a pipeline out.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: sample
    type: limit
    input: orders
    rows: 1000

  - id: out
    type: write
    input: sample
    source: files
    path: samples/orders.csv
    mode: replace
```

Related: [limit](pipeline-nodes.md#limit).

## sort

Orders rows by one or more columns, each ascending or descending.

[generated: node sort]: #

**Sort rows**: a transform node, in the palette's Transform folder. Orders rows by one or more columns, each ascending or descending. Rows with equal values keep their order. Past its memory limit it sorts on disk, so any number of rows can be sorted.

- Reads from: `in`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `by` | columns in order | yes |  | no | Columns to order by, the first deciding most. Add desc for largest first, and nulls first to put empty values first (they go last by default). |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory rows may take while they are sorted. Past it, sorted parts are written to disk and merged. |

[end: node sort]: #

**What to know.** Each entry in `by` is a column, then perhaps `desc` (largest first; `asc` is the
default), then perhaps `nulls first` (empty values go last by default, whichever the direction).
Values order as conditions compare them: numbers by value, text by Unicode code point with no
collation (so `B` comes before `a`, and accented letters after `z`), dates and timestamps in time
(to the nanosecond), whole numbers by value (unsigned ones too), `false` before `true`, and a floating-point NaN after every other number. Rows with equal values
keep the order they arrived in. Nothing is passed on until every row has arrived. Rows are held up
to `memory_limit_mb`; past it they are sorted in parts, written to a private temporary folder on
the server (encrypted, with a key only the running node holds) and merged at the end, so any number
of rows can be sorted; the run's log says when that happened and how much was written. The folder
is deleted when the node ends, fails or is stopped, and a file damaged while there fails the run.

```yaml
kilnmark: pipeline/v1
name: orders-sorted
description: Orders, by country and then largest first.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: ordered
    type: sort
    input: orders
    by: [country, amount desc, ordered_at nulls first]

  - id: out
    type: write
    input: ordered
    source: files
    path: exports/orders-sorted.csv
    mode: replace
```

Related: [sort](pipeline-nodes.md#sort).

## join

Joins the rows of a `left` and a `right` input whose key columns are equal, as SQL joins them.

[generated: node join]: #

**Join**: a transform node, in the palette's Combine folder. Joins rows of the left and right inputs whose key columns are equal: inner, left, right or full. Either input may be larger than memory. To add a few columns from a small table, a lookup is quicker.

- Reads from: `left`, `right`
- Writes to: `out`

| Setting | Type | Required | Default | Parameters | What it does |
| --- | --- | --- | --- | --- | --- |
| `on` | column to column | yes |  | no | Key columns: each left column and the right column it must equal. |
| `how` | choice | no | `inner` | no | Which rows are kept: inner keeps the pairs of rows that match; left also keeps left rows with no match, right keeps right rows with none, and full keeps both, with the other side's columns empty. One of `inner`, `left`, `right`, `full`. |
| `right_prefix` | text | no | `right_` | no | Put in front of the name of a right column that the left already has (ignoring case), so both are kept. |
| `memory_limit_mb` | number | no | `256` | whole value | The most memory the two inputs may take while they are sorted. Past it, they are sorted on disk. |

[end: node join]: #

**What to know.** `inner` keeps the pairs of rows that match; `left` also keeps left rows with no
match, `right` right rows with none, and `full` both, with the other side's columns empty. Every
pair comes out: a key on three left rows and two right rows gives six. Keys match by value, as a
lookup's do (`1`, `1.0` and `1.00` match; a date matches a timestamp at midnight; a float matches
by its shortest decimal, so a very large one may not match the whole number it holds; timestamps
match to the nanosecond); text never matches a number; an empty (null) key matches nothing, not even another empty key. Every column of
both inputs comes out with its type, the left's first; a right column whose name a left column
already has, ignoring case, is renamed with `right_prefix` (`right_customer_id`). Rows come out in
order of their keys, rows with an empty key last. Both inputs are sorted by their keys within
`memory_limit_mb` between them, and on disk past it, so either may be larger than memory; the run's
log says when that happened. To add a few columns from a small table, a `lookup` is quicker.

```yaml
kilnmark: pipeline/v1
name: orders-and-customers
description: Every order with its customer, and the orders with none.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: customers
    type: read
    source: sales-db
    asset: crm.customers
    columns: [customer_id, first_name, last_name]

  - id: joined
    type: join
    inputs: { left: orders, right: customers }
    on: { customer_id: customer_id }
    how: left

  - id: out
    type: write
    input: joined
    source: warehouse
    table: sales.orders_with_customers
    mode: replace
```

Related: [join](pipeline-nodes.md#join).
