# Rows that fail

A row *fails* when a node cannot work it out: a cast of `'12a'` to a number, a division by zero, a
number too large for its type, a date out of range. This page says what happens to such rows and
how to keep a run going while setting them aside. A row that breaks a quality rule is another
matter: that is for a [quality gate](quality-gates.md).

## The errors port

Without an `errors` port connected, the first row that fails stops the run. Connect something to
the node's `errors` port (a write to a quarantine file, say) and failing rows go there instead,
while the others carry on:

```yaml
- id: amounts
  type: compute
  input: feed
  columns:
    amount_pence: "cast(amount as int64) * 100"
  max_error_share: 0.01

- id: aside
  type: write
  input: amounts.errors
  source: files
  path: quarantine/feed-errors.csv
  mode: replace
```

| Node | A row fails when |
| --- | --- |
| `compute` | an expression fails on it |
| `select` | a `cast` fails on it |
| `filter` | the condition fails on it (a condition that is false or empty still sends the row to `rejected`) |
| `route` | a route's condition fails on it |

A `write` to a database has an `errors` port too, for the rows the table would refuse: see
[Writing data from a pipeline](writing-data.md#rows-the-table-would-refuse).

Rows sent to any other port that nothing reads are dropped; `errors` is the exception, as above.

## An error row

An error row is the row as it arrived at the node, with four columns added at the end:

| Column | What it holds |
| --- | --- |
| `error_node` | The node's id. |
| `error_column` | The column being worked out (compute) or cast (select); for a condition, `condition` or `routes.<name>`. |
| `error_code` | `cast_failed`, `division_by_zero`, `out_of_range`, `too_long`, `too_slow` or `too_complex`. |
| `error_message` | What went wrong and what to do. It never holds the value: the value is in the row's own column. |

The row keeps its columns' names, so a column marked sensitive stays marked in the error rows: it
is masked wherever they are shown, and a write of them carries the mark to what it writes. When
the failure came from a marked column, `error_column`, `error_code` and `error_message` are marked
too, since which value failed, and how, says something about it. A row that already has the four
columns (it failed at an earlier node and was routed on) takes the latest failure.

## The error tolerance

Two settings stop the run when too many rows fail, judged over the whole run:

| Setting | What it does | Default |
| --- | --- | --- |
| `max_errors` | Stop the run when more than this many rows fail. | No limit |
| `max_error_share` | Stop the run when more than this share of the rows fail, from 0 to 1, judged when the input ends. | No limit |

The tolerance counts the rows sent to `errors`, so it applies only with the port connected. The
run page shows each node's rows in and out per port, `errors` among them, and each node with
failing rows adds a line to the run's log, such as "12 rows of 48,000 failed and went to the errors
port (limits: max_error_share 1.00%)", so rows read, written and set aside can be reconciled.

## How the failing rows are found

A node works on a batch of rows at once, so when a batch fails the node splits it in halves, and
each failing half again, until it has the single rows that fail. A batch that works costs nothing
extra.

- A failure that is not about one row's value (a setting that is wrong for every row, or a wrong
  argument such as field 0 for `split_part`) still stops the run.
- A failure on every row's value (a division by zero everywhere, say) sends every row to `errors`:
  set `max_error_share` to stop such a run.
- A preview of a node whose `errors` port the pipeline reads sets failing rows aside as the run
  does.

To stop rows failing in the first place, see [Types](expressions.md#types) for strict casts and
`nullif` for division.
