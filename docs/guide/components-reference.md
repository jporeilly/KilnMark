# Components reference

One place for every component Kilnmark supports: each pipeline node, flow node, connector,
quality rule and expression function, with what it does and every setting it takes. Use it when you
know what you want to build and need the exact key, its type or its default; the other guides
explain how to build and run pipelines and flows from the start.

![The components a pipeline or flow is built from: connectors reach the data, from which a source node reads and to which a sink node writes; transforms and a gate sit between them, checked with expressions and quality rules; flow nodes run the work, check and wait, and tell and end, joined by connections. Every settings table in the reference is made from the catalogue the server uses, and the build checks it.](images/component-families.svg)

## How to read an entry

Every entry has the same parts:

- **What it does**, in a sentence or two, and its **kind**: for pipeline nodes, a source (reads,
  with no inputs), a transform (changes, combines or routes rows), a gate (checks rows against
  quality rules) or a sink (writes, with no outputs).
- **Ports**: what a pipeline node reads from and writes to. In YAML, `input: tidy` reads the `out`
  port of the node `tidy`, and `input: tidy.rejected` its `rejected` port.
- **Settings**: a table with each setting's key, its type, whether it is required, its default and
  what it does. The **Parameters** column says what `${...}` may do in it: *yes* (a value in text
  or an expression), *whole value* (a number or true/false given as one `${name}`), *part of the
  name* (only part of the last name of where a write goes, beside text the pipeline fixes) or *no*
  (the setting names something a run must not choose, such as a source or another node).
- **What to know**: behaviour, limits and what is refused.
- **Governance**: the roles it takes, how sensitive values are masked, what is recorded in the
  audit log, and the source settings it needs (Allow writing, Allow SQL).
- **An example** you can copy: a whole pipeline or flow, checked by the build as saving checks it.
- **Related** guides.

For pipeline nodes and connectors, the ports, abilities and settings tables (keys, types, defaults
and descriptions) are made from the catalogue the server itself uses (the same one
`GET /api/v1/node-types` and `GET /api/v1/connectors` return), as are each flow node's controls and
values and each rule's facts, and the build fails when the pages and the catalogue differ. The
settings tables of flow nodes and rules are written by hand; the build checks that they name
exactly the keys Kilnmark reads, not their types or defaults.

## Starting what you build

A pipeline or flow runs when someone chooses **Run now**, when a flow's `run-pipeline` or
`for-each` node runs it, on one of Kilnmark's own schedules
([Running pipelines and flows on a schedule](schedules.md)), or from an enterprise scheduler such
as Control-M, AutoSys or cron through `kilnmark-run`, which waits for the run and exits with a code
saying how it went ([Running from an external scheduler](external-schedulers.md)). However it
starts, a run is checked as **Run now** checks it and acts with the role of whoever started it (a
schedule's runs as steward or designer, never admin), so everything the entries below say about
roles, sources and Allow writing holds for it.

## Pipeline nodes

A pipeline reads from sources, changes the rows, checks them and writes them, in one pass. The
entries are in [Reference: pipeline nodes](reference-pipeline-nodes.md); how to build a pipeline is
in [Building and running a pipeline](pipelines.md).

[generated: index nodes]: #

| Node | Kind | In the palette | What it does |
| --- | --- | --- | --- |
| [`read`](reference-pipeline-nodes.md#read) | source | Read a table or file, in Read | Reads a table, view or file from a saved source. |
| [`aggregate`](reference-pipeline-nodes.md#aggregate) | transform | Aggregate, in Aggregate | Groups rows that share values and summarises each group with count, sum, min, max, avg or count_distinct. |
| [`compute`](reference-pipeline-nodes.md#compute) | transform | Compute columns, in Transform | Adds or replaces columns, each worked out from an expression. |
| [`dedupe`](reference-pipeline-nodes.md#dedupe) | transform | Remove duplicates, in Filter and route | Keeps the first row for each key. |
| [`filter`](reference-pipeline-nodes.md#filter) | transform | Filter rows, in Filter and route | Keeps the rows where a condition is true. |
| [`join`](reference-pipeline-nodes.md#join) | transform | Join, in Combine | Joins rows of the left and right inputs whose key columns are equal: inner, left, right or full. |
| [`limit`](reference-pipeline-nodes.md#limit) | transform | Limit rows, in Filter and route | Passes on only the first rows, for trying a pipeline out on a little data. |
| [`lookup`](reference-pipeline-nodes.md#lookup) | transform | Look up, in Combine | Adds columns from the lookup input to each main row with the same key, like a join. |
| [`route`](reference-pipeline-nodes.md#route) | transform | Route rows, in Filter and route | Sends each row to the first route whose condition it meets, in order. |
| [`select`](reference-pipeline-nodes.md#select) | transform | Select columns, in Transform | Keeps or drops columns, and renames, reorders and changes the type of the columns kept. |
| [`sort`](reference-pipeline-nodes.md#sort) | transform | Sort rows, in Transform | Orders rows by one or more columns, each ascending or descending. |
| [`union`](reference-pipeline-nodes.md#union) | transform | Union, in Combine | Appends the rows of all its inputs, matching columns by name. |
| [`window`](reference-pipeline-nodes.md#window) | transform | Window functions, in Transform | Adds row numbers, ranks, running totals and the previous or next row's values, worked out over the rows of each group in order. |
| [`gate`](reference-pipeline-nodes.md#gate) | gate | Quality gate, in Quality | Checks rows against quality rules as they pass, with no second read: the rules of the asset read upstream and any written here. |
| [`write`](reference-pipeline-nodes.md#write) | sink | Write to a table or file, in Write | Writes rows to a database table or a file in a saved source. |

[end: index nodes]: #

## Flow nodes

A flow runs pipelines and other work in an order, joined by connections. The entries are in
[Reference: flow nodes](reference-flow-nodes.md); how to build a flow is in
[Building and running a flow](flows.md).

| Node | What it does |
| --- | --- |
| [`run-pipeline`](reference-flow-nodes.md#run-pipeline) | Runs a saved pipeline and waits for it. |
| [`for-each`](reference-flow-nodes.md#for-each) | Runs one pipeline once for each item of a list, several at once. |
| [`sql`](reference-flow-nodes.md#sql) | Runs SQL statements on a PostgreSQL source, in one transaction. |
| [`check-source`](reference-flow-nodes.md#check-source) | Checks a source can be reached and holds the tables or files the work needs. |
| [`wait-for`](reference-flow-nodes.md#wait-for) | Waits until a file arrives or a query is true. |
| [`wait`](reference-flow-nodes.md#wait) | Waits a while. |
| [`notify`](reference-flow-nodes.md#notify) | Sends a notice, in the app and by email. |
| [`stop`](reference-flow-nodes.md#stop) | Ends the flow, as failed or succeeded. |

## Connectors

A connector reaches one kind of source: a database, a folder or a bucket. A source is a saved
connection made with one, holding its address and, encrypted, its password or key; pipelines and
flows name the source, never the credentials. The entries are in
[Reference: connectors](reference-connectors.md), with the settings every source has and the
versions each database is tested against.

[generated: index connectors]: #

| Connector | Kind | Scan | Read | Write | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- | --- |
| [Files in a folder](reference-connectors.md#files-in-a-folder) | `files` | yes | yes | yes | no | no |
| [Generic JDBC](reference-connectors.md#generic-jdbc) | `jdbc` | yes | yes | yes | yes | no |
| [MariaDB](reference-connectors.md#mariadb) | `mariadb` | yes | yes | yes | yes | no |
| [MySQL](reference-connectors.md#mysql) | `mysql` | yes | yes | yes | yes | no |
| [Oracle](reference-connectors.md#oracle) | `oracle` | yes | yes | yes | yes | no |
| [PostgreSQL](reference-connectors.md#postgresql) | `postgresql` | yes | yes | yes | yes | yes |
| [S3 object storage](reference-connectors.md#s3-object-storage) | `s3` | yes | yes | yes | no | no |
| [Kilnmark sample data](reference-connectors.md#kilnmark-sample-data) | `sample-data` | yes | yes | no | no | no |
| [SQL Server](reference-connectors.md#sql-server) | `sqlserver` | yes | yes | yes | yes | no |

[end: index connectors]: #

*Your own SQL* means SQL a person writes: a read node's `query`, custom SQL quality rules, and the
flow `sql` and `wait-for` query nodes, each only with the source's Allow SQL on.

## Quality rules

A quality rule says what must hold of a table or file: no empty emails, amounts in a range, a key
that is unique. Rules are kept on an asset, made in **Quality** or through the API, or written on a
gate node for one pipeline. The entries are in [Reference: quality rules](reference-quality-rules.md);
thresholds, alerts and recipes are in [Setting data quality rules](quality-rules.md).

[generated: index rules]: #

| Rule | Dimension | Needs a column | On a gate |
| --- | --- | --- | --- |
| [`not_null`](reference-quality-rules.md#not_null) | Completeness | yes | yes |
| [`unique`](reference-quality-rules.md#unique) | Uniqueness | yes, or several in `fields` | yes |
| [`allowed_values`](reference-quality-rules.md#allowed_values) | Validity | yes | yes |
| [`matches_pattern`](reference-quality-rules.md#matches_pattern) | Validity | yes | yes |
| [`in_range`](reference-quality-rules.md#in_range) | Accuracy (estimated) | yes | yes |
| [`freshness`](reference-quality-rules.md#freshness) | Timeliness | yes | yes |
| [`row_count`](reference-quality-rules.md#row_count) | Volume | no | yes |
| [`row_count_change`](reference-quality-rules.md#row_count_change) | Volume | no | yes |
| [`references`](reference-quality-rules.md#references) | Referential integrity | yes | no |
| [`custom_sql`](reference-quality-rules.md#custom_sql) | Validity | optional | no |

[end: index rules]: #

## Expression functions

`filter`, `compute`, `aggregate` and `route`, and a read node's `where`, share one small expression
language, much like SQL. Its values, operators, types, functions and limits are in
[Pipeline nodes and expressions](pipeline-nodes.md#expressions):

[generated: index functions]: #

- [Functions](pipeline-nodes.md#functions), for text, numbers, dates and regular expressions: `abs`, `ceil`, `coalesce`, `concat`, `date_part`, `date_trunc`, `floor`, `format_date`, `length`, `lower`, `now`, `nullif`, `parse_date`, `parse_timestamp`, `regex_match`, `regex_replace`, `replace`, `round`, `substring`, `today`, `trim` and `upper`.
- [Aggregate functions](pipeline-nodes.md#aggregate), for an aggregate node's values: `avg`, `count`, `count_distinct`, `max`, `min` and `sum`.
- [Window functions](pipeline-nodes.md#window), for a window node's columns: `avg`, `count`, `dense_rank`, `lag`, `lead`, `max`, `min`, `rank`, `row_number` and `sum`.

[end: index functions]: #

And, written in the language itself:

- [Types](pipeline-nodes.md#types) and `cast`, [empty values](pipeline-nodes.md#empty-values-null)
  and [limits](pipeline-nodes.md#limits).

The build checks that the function tables there list every function the language has, and no
other.
