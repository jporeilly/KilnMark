# Reference: quality rules

Every type of quality rule, with every setting it takes. A rule says what must hold of a table or
file: no empty emails, amounts in a range, a key that is unique. How to make one in **Quality**,
thresholds, alerts and recipes are in [Setting data quality rules](quality-rules.md). For the other
kinds of component, see the [Components reference](components-reference.md).

## Two places a rule lives

- **On an asset**, made in **Quality**, by accepting a suggestion from the profile, or through the
  API (`POST /api/v1/quality/rules`, whose JSON body each entry's example shows). It runs after
  each scan, or when run by hand: on a database as SQL inside the database, so the data stays where
  it is; on files while Kilnmark reads them. A gate also checks the asset's rules on the rows a
  pipeline reads.
- **On a gate node**, written in the pipeline under `rules:`, for that pipeline only. It checks the
  rows as they pass, with no second read, on every run, however it was started: by a person, a
  flow, a [schedule](schedules.md), or an [external scheduler](external-schedulers.md), whose
  `kilnmark-run` exits with code 1 when a stop gate fails the run. On a gate the keys are written in `snake_case`, as each
  entry's table shows. `references` and `custom_sql` run in a database, so they cannot go on a gate.

Each entry gives the rule's facts (its dimension, whether it needs a column, whether it gives a
share of failing rows or one result for the whole asset, and the keys it takes on a gate), made
from the server's own definitions; a table of its own settings; what to know; and an example. The
JSON examples are checked by the build as the API checks them, and the YAML as saving a pipeline
does.

A gate with one rule of each type it takes:

```yaml
kilnmark: pipeline/v1
name: orders-gate-rules
description: Every kind of rule a gate takes, on the orders.

nodes:
  - id: orders
    type: read
    source: sales-db
    asset: sales.orders

  - id: check
    type: gate
    input: orders
    on_fail: quarantine
    asset_rules: false
    rules:
      - {type: not_null, field: customer_id}
      - {type: unique, field: order_id}
      - {type: allowed_values, field: status, values: [new, paid, delivered, returned]}
      - {type: matches_pattern, field: country, regex: "^[A-Z]{2}$"}
      - {type: in_range, field: amount, min: 0, max: 100000, unit: count, fail_above: 10}
      - {type: freshness, field: ordered_at, max_age_hours: 24}
      - {type: row_count, min: 1}
      - {type: row_count_change, max_change: 0.2}

  - id: good
    type: write
    input: check
    source: warehouse
    table: sales.orders_checked
    mode: replace

  - id: aside
    type: write
    input: check.quarantine
    source: files
    path: quarantine/orders.csv
    mode: replace
```

## Settings every rule has

| On a gate | In the API | Type | Default | What it does |
| --- | --- | --- | --- | --- |
| `type` | `type` | choice | | The rule's type: one of the entries below. Required. |
| `field` | `field` | column | | The column checked. Required for the types that need one. |
| `name` | `name` | text | the column and type, such as `email not null` | How the rule appears in lists, alerts and run messages; at most 200 characters. |
| `dimension` | `dimension` | choice | the type's own | The quality dimension it counts towards, and so the score it affects. |
| `unit` | `thresholdUnit` | choice | `share` | `share` (of the rows checked, 0 to 1) or `count` (of rows). |
| `fail_above` | `failAbove` | number | `0` | The rule fails when failing rows are above this: by default any failing row fails it. |
| `warn_above` | `warnAbove` | number | none | The rule warns when failing rows are above this but not above `fail_above`. |
| | `assetUrn` | text | | The asset the rule belongs to, such as `urn:kilnmark:sales-db:crm.customers`. Required. |
| | `params` | object | | The type's own settings, under their API names. |
| | `actions` | object | | `{"task": true}` opens a steward task when the rule warns or fails; `{"webhook": "https://..."}` calls a webhook on the administrator's allowed list. |
| | `enabled` | true or false | `true` | Whether it runs. |

Rules that give one result for the whole asset (freshness and the two row counts) pass or fail,
and ignore `unit`, `warn_above` and `fail_above`. Empty values pass every check but `not_null`.

**Governance.** Stewards make, change and run rules on assets; designers write gates in pipelines.
A rule's failing rows and example values are shown only to those who may see the asset's values,
masked where a column is marked sensitive, and kept only while the source keeps samples
(`keep_samples`). A custom SQL rule needs a designer whose token sees unmasked values, and a
PostgreSQL source with Allow SQL on.

## not_null

Fails rows whose value is empty.

[generated: rule not_null]: #

- Dimension: Completeness (`completeness`)
- Column: yes
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: yes, with `blank_is_missing` (`blankIsMissing` in the API)

[end: rule not_null]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `blank_is_missing` | `blankIsMissing` | true or false | no | `false` | Counts blank text, such as `""` or `"  "`, as empty too. |

```json
{
  "assetUrn": "urn:kilnmark:sales-db:crm.customers",
  "type": "not_null",
  "field": "email",
  "params": {"blankIsMissing": true},
  "failAbove": 0.01,
  "warnAbove": 0
}
```

## unique

Fails values that appear more than once.

[generated: rule unique]: #

- Dimension: Uniqueness (`uniqueness`)
- Column: yes, or several in `fields`
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: yes, with `fields`

[end: rule unique]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `fields` | `fields` | list of columns | no | | A key of several columns together, in place of `field`. |

**What to know.** On an asset, every row of a repeated key fails. On a gate, the first row with a
key passes and each repeat fails. To find repeats, a gate keeps each key it has seen as a short
fingerprint, about 96 bytes whatever the key's length, up to 1,048,576 different keys (about
96 MB). Past that it stops checking for repeats: the rule shows **Could not run**, says how many
repeats it had found and set aside before then, and the rows after it are not checked for repeats.
Use the asset's rule, which runs in the database, for larger tables.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "unique",
  "params": {"fields": ["order_id", "customer_id"]}
}
```

## allowed_values

Fails any value not in the list.

[generated: rule allowed_values]: #

- Dimension: Validity (`validity`)
- Column: yes
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: yes, with `values`

[end: rule allowed_values]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `values` | `values` | list of text | yes | | 1 to 1,000 values, matched exactly, capital letters included. |

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "allowed_values",
  "field": "status",
  "params": {"values": ["new", "paid", "delivered", "returned"]}
}
```

## matches_pattern

Fails any value that does not match a regular expression.

[generated: rule matches_pattern]: #

- Dimension: Validity (`validity`)
- Column: yes
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: yes, with `regex`

[end: rule matches_pattern]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `regex` | `regex` | text | yes | | A regular expression of 1 to 1,000 characters; use `^` and `$` to match the whole value. |

**What to know.** Patterns that could take practically for ever on unlucky text are refused, as in
[expressions](expressions.md#regular-expressions-are-kept-safe). In JSON, a backslash is
doubled: `\s` is written `\\s`.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:crm.customers",
  "type": "matches_pattern",
  "field": "postcode",
  "name": "postcode looks like one",
  "params": {"regex": "^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$"}
}
```

## in_range

Fails values below a minimum or above a maximum; both limits count as inside.

[generated: rule in_range]: #

- Dimension: Accuracy (estimated) (`accuracy`)
- Column: yes
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: yes, with `max`, `min`

[end: rule in_range]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `min` | `min` | number, date or time | one of `min` or `max` | | The lowest allowed value. |
| `max` | `max` | number, date or time | one of `min` or `max` | | The highest allowed value. |

**What to know.** Numbers for number columns, dates for date columns, and a date and time in UTC
for date-and-time columns.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "in_range",
  "field": "amount",
  "params": {"min": 0, "max": 100000},
  "thresholdUnit": "count",
  "failAbove": 10
}
```

## freshness

Fails, as a whole, when the newest value of a date or time column is too old.

[generated: rule freshness]: #

- Dimension: Timeliness (`timeliness`)
- Column: yes
- Result: pass or fail for the whole asset; thresholds do not apply
- On a gate: yes, with `max_age_hours` (`maxAgeHours` in the API)

[end: rule freshness]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `max_age_hours` | `maxAgeHours` | number | yes | | How old the newest value may be, in hours. |

**What to know.** The column must hold dates or times; timestamps stored without a time zone are
read as UTC. On a gate with no rows, there is no newest value, so it fails.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "freshness",
  "field": "ordered_at",
  "params": {"maxAgeHours": 24}
}
```

## row_count

Fails, as a whole, when there are too few or too many rows.

[generated: rule row_count]: #

- Dimension: Volume (`volume`)
- Column: no
- Result: pass or fail for the whole asset; thresholds do not apply
- On a gate: yes, with `max`, `min`

[end: rule row_count]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `min` | `min` | whole number | one of `min` or `max` | | The fewest rows allowed. |
| `max` | `max` | whole number | one of `min` or `max` | | The most rows allowed. |

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "row_count",
  "params": {"min": 1, "max": 5000000}
}
```

## row_count_change

Fails, as a whole, when the row count moved too far since the last scan, up or down.

[generated: rule row_count_change]: #

- Dimension: Volume (`volume`)
- Column: no
- Result: pass or fail for the whole asset; thresholds do not apply
- On a gate: yes, with `max_change` (`maxChange` in the API)

[end: rule row_count_change]: #

| On a gate | In the API | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- | --- |
| `max_change` | `maxChange` | number | yes | | The largest change allowed, as a share: `0.2` for 20%. The form takes it in percent. |

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "row_count_change",
  "params": {"maxChange": 0.2}
}
```

## references

Fails values that do not appear in a column of another asset in the same source: a foreign key that
does not hold.

[generated: rule references]: #

- Dimension: Referential integrity (`integrity`)
- Column: yes
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: no; it runs in the source's database, from the quality pages or the API

[end: rule references]: #

| In the API | Type | Required | What it does |
| --- | --- | --- | --- |
| `asset` | text | yes | The URN of the asset referred to, in the same source. |
| `field` | column | yes | Its column the values must appear in. |

**What to know.** It runs as SQL inside the database, so it needs a database source.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "references",
  "field": "customer_id",
  "params": {"asset": "urn:kilnmark:sales-db:crm.customers", "field": "customer_id"}
}
```

## custom_sql

Fails the rows a query you write returns.

[generated: rule custom_sql]: #

- Dimension: Validity (`validity`)
- Column: optional
- Result: a count or share of failing rows, compared with its thresholds
- On a gate: no; it runs in the source's database, from the quality pages or the API

[end: rule custom_sql]: #

| In the API | Type | Required | What it does |
| --- | --- | --- | --- |
| `sql` | text | yes | One `SELECT` that returns the failing rows, at most 10,000 characters. `${table}` is replaced with the asset's table, and `${column}` with the rule's column when it has one. |

**What to know.** PostgreSQL sources only, with Allow SQL on. It runs read-only, with a time limit
(`kilnmark.quality.query-timeout`, 60 seconds by default), and is rolled back afterwards; never
under a superuser or a login that may read files or run programs. Braces, `{` and `}`, are refused
outside quotes and comments, but for `${table}` and `${column}` as written: the database driver
would read `{fn ...}` and the like as instructions to rewrite the SQL. Write an array as `'{1,2}'`.

**Governance.** Only a designer whose token sees unmasked values writes or runs one, since it can
read any column its login can. Its failing rows and error text are withheld from everyone else
while any table of the source has a column marked sensitive.

```json
{
  "assetUrn": "urn:kilnmark:sales-db:sales.orders",
  "type": "custom_sql",
  "name": "no negative orders",
  "params": {"sql": "SELECT * FROM ${table} WHERE amount < 0"}
}
```
