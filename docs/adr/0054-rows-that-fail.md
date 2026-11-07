# 0054 Rows that fail: an errors port, with a tolerance

Status: Accepted (7 November 2026)

## Context

Today one bad value stops a run. A cast of `'12a'` to a number, a division by zero, or a value too
long for its column ends the whole pipeline, and the rows that were fine are not written either.
Every comparable tool lets a node send the rows that fail to an output of their own, with what
went wrong, and lets a person say how many failures a run may have before it stops
(docs/design/research/node-options.md, section 2 and gaps 1 and 2). Banks need both: every
rejected row kept, with its reason, and rows read reconciled with rows written and rows rejected.

Kilnmark already sets some rows aside: a filter's `rejected` port (rows the condition is false
for), a lookup's `unmatched: rejected`, a dedupe's `rejected`, and a quality gate's `quarantine`
with `max_failing_share`. Those are rows a rule chose, not rows that failed. Rows that fail need
their own port, so a pipeline can tell "did not match" from "could not be worked out".

Two constraints shape the design. Error messages never echo a value (CLAUDE.md, "Writing"), and
a value marked sensitive stays masked wherever it is shown (ADR 0027). Rows that fail carry
values, by definition, and their reasons are shown on the run page and in files.

## Decision

1. **An `errors` output port** on `compute`, `select`, `filter`, `route` and `write`. A row that
   fails there goes to `errors` instead of stopping the run, when something is connected to the
   port. With nothing connected, a node behaves as before: the first failure stops the run, with
   the same message.

2. **Which failures count**, per node:
   - `compute`: an expression that fails on the row (a cast, division by zero, a number too large,
     a date out of range, a text too long).
   - `select`: a `cast` that fails on the row.
   - `filter`: the condition fails on the row. A condition that is false or empty still sends the
     row to `rejected`, as before.
   - `route`: a route's condition fails on the row.
   - `write` (databases): a value Kilnmark checks before it is sent and the table would refuse:
     an empty key column for upsert, a text longer than its column, a decimal with more digits
     than its column keeps, a time with more digits of a second than its column keeps, a value of
     a type the column cannot take. These rows never reach the database.
   - Not row failures, so still fatal: a setting or column that is wrong for the whole input
     (caught when the pipeline is checked, or on the first batch), a failure not known to be about
     one row's value (such as a wrong argument for every row), memory limits, a source that cannot
     be reached, and a refusal by the database itself, including an empty value for a column that
     must be filled.

3. **Writes report the database's own refusals at batch level, for now.** A constraint the
   database enforces (a unique index, a foreign key, a check) is found only when a batch is sent,
   and in PostgreSQL a failed statement ends the transaction. Finding the bad row means a
   savepoint per batch and splitting the failed batch in halves until single rows remain, with a
   different cost and behaviour per database (`COPY` cannot be split once sent). That comes later
   (backlog item 9, point 1, second part). Until then such a refusal stops the write, as today,
   and the run's error says so.

4. **Finding the rows that fail.** Expressions run over a batch of rows at once. When a batch
   fails and the errors port is connected, the node splits it in halves, and each half again,
   until it has the single rows that fail: about `k × log₂(n)` tries for `k` bad rows in a batch
   of `n`, and nothing extra for a batch that works. It then works out the rows that passed in one
   go. A batch that fails only as a whole (no single row fails) stops the run as before.

5. **An error row** is the row as it arrived at the node, with four columns added at the end:
   - `error_node`: the node's id;
   - `error_column`: the column being worked out (compute), cast (select) or written (write); for
     a condition, the setting (`condition`, or `routes.<name>`);
   - `error_code`: a fixed word, such as `cast_failed`, `division_by_zero`, `out_of_range`,
     `too_long`, `key_missing`, `does_not_fit`. Only failures the expression engine marks as about
     one row's value carry a code; any other stops the run;
   - `error_message`: what went wrong and what to do, from a fixed list of wordings, **never** text
     built from the value. The value is in the row's own column, where its mark applies.
   A row that already has these columns (it failed at an earlier node, and was routed on) has them
   replaced by the latest failure.

6. **Marks follow the row.** Error rows keep their columns' names, so ADR 0027's marks reach them
   as they reach any other port: a column marked sensitive is masked in the error rows a viewer
   sees, in a preview of the errors port, and in what a write of them records. The added columns
   hold no value, but which value failed, and how, says something about it: `error_column`,
   `error_code` and `error_message` are marked when the node worked the failure out from a marked
   column (an expression, cast or condition that uses one; for a write, any marked column it is
   given). `error_node` names only the node.

7. **An error tolerance on the node:** `max_errors` (a count) and `max_error_share` (0 to 1).
   - Judged over the whole run, as a gate's `max_failing_share` is. The count is checked as rows
     fail, since it only grows; the share is checked when the node's input ends.
   - Past either limit the run fails, saying how many rows failed out of how many.
   - With neither set, every failing row goes to `errors` and the run carries on.
   - Without a connected `errors` port the tolerance has nothing to count: the first failure
     stops the run.
   - A write with `commit_every` commits rows before its share can be judged, so it may not set
     `max_error_share`; with `max_errors`, rows committed before the limit is passed stay, as with
     any failure of such a write.

8. **The run records it.** Rows in and out per port are already recorded for every node
   (`NodeResult.rowsInByPort` and `rowsOutByPort`), so rows read, written and set aside on `errors`
   are in the run report, the run page and the API. Each node with failing rows also adds one line
   to the run's log: how many rows came in, went out, and went to `errors`, against its limits. No
   database change.

9. **On the canvas** the `errors` port is drawn as `rejected` and `quarantine` are: one more
   output port, connected like any other.

10. **A preview behaves as the run.** A preview runs only the nodes up to the one previewed; an
    errors port that the pipeline reads from beyond them is drained in the preview, so a failing
    row is set aside there as in the run, rather than stopping the preview.

## Consequences

- A pipeline converted from a tool whose units send failing rows elsewhere can now keep doing so
  (research README, "Migration").
- A run with a tolerance can succeed with rows set aside; anyone reading only its status must also
  read its counts. The run page shows them per port.
- Nodes that fail per batch pay for the splitting only on batches that fail.
- A write still stops on the database's own refusals until the savepoint work is done.
- Existing messages that quoted values (a write's decimal and time checks, and the databases'
  own value checks: whole numbers out of range, years, NaN, Oracle's digits and characters) no
  longer do, in errors and in run failures alike.
- Later: the same port on `lookup` (a key that cannot be compared), `join`, `window`, `aggregate`
  (a value that overflows), and the database's own refusals on `write`.
