# 0056 Rows in one input and not the other: semi and anti joins, except and intersect

Status: Accepted (11 November 2026)

## Context

"Which rows are in this input and not in that one?" is asked of every pair of systems: customers in
the archive and not in today's table, orders in the ledger and not in the feed. Until now it took a
`select` of the key on one side and a `lookup` with `unmatched: rejected`, because a lookup refuses
to add columns whose names the main input already has, or a full `join` and a `filter`.

There are two questions under the one sentence, and SQL answers them with different tools:

- **By key**: "the same record" is an id, or a cleaned email. SQL: `WHERE [NOT] EXISTS (...)`, a
  semi or anti join.
- **By whole row**: "the same row" is every column equal. SQL: `INTERSECT` and `EXCEPT` (`MINUS`).

They differ most in how they treat nulls, so the choice must be made on purpose and written down.
The fuller `compare` node (new, changed, deleted and unchanged by key; ADR 0049) is not this: it
stays planned.

## Decision

1. **`join` gains `how: semi` and `how: anti`.** `semi` keeps each left row that has at least one
   match on the right; `anti` keeps each left row that has none. Only the left's columns come out,
   as they are, so no name can clash and `right_prefix` is not used. A left row comes out once at
   most, however many right rows match it.

2. **Nulls in semi and anti are the join's: a null key never matches** (not even another null), as
   in every other kind of join and in SQL's `EXISTS`. So a left row whose key has a null is never in
   `semi` and always in `anti`, and a null among the right's keys hides nothing. This is SQL's
   `NOT EXISTS`, and deliberately not SQL's `NOT IN`, under which one null on the right makes every
   row vanish: a trap, and no use to anyone finding missing records.

3. **`union` gains `mode`**: `all` (the default, and what a union did before: append), `except` and
   `intersect`. `except` keeps the rows of the **first** input found in **no** other input;
   `intersect` those found in **every** other input. The first input is the first in
   `input: [...]`. Only the first input's rows and columns come out, with their types.

4. **Whole rows are compared, or the columns of `compare_on`.** Without `compare_on`, every input
   must have the same column names (in any order): a column only one input has would make every row
   differ, so it is refused when the pipeline is checked, with the advice to name the columns to
   compare. With `compare_on`, only those columns need be in every input. A column is compared by
   value, as a join's keys are (`1`, `1.0` and `1.00` are equal; a date equals a timestamp at
   midnight; text never equals a number, which is refused when checked).

5. **Nulls in except and intersect are equal to each other**, as in SQL's `EXCEPT` and `INTERSECT`
   (and `DISTINCT`, and Kilnmark's `dedupe`): two rows that are empty in the same column, and equal
   elsewhere, are the same row. So the same data can give different answers from an anti join and an
   except when a compared column is empty. That is SQL's own difference, and the guide's recipe says
   so: by key, a row with an empty key is always "not there"; by row, an empty value is a value.

6. **Rows are not folded: one difference from SQL.** SQL's `EXCEPT` and `INTERSECT` also remove
   repeats from what they return. Here each row of the first input comes out or does not, so a row
   that is in the first input twice comes out twice (or not at all). Reasons: with `compare_on` the
   rows that agree on the compared columns can differ elsewhere, and folding them would have to
   choose one without a rule; `dedupe` already does that, with a rule the designer states; and a
   pipeline whose rows in equal rows out plus rows found can be reconciled by counting. Add a
   `dedupe` after it for SQL's exact result. (`EXCEPT ALL`, which subtracts counts, is not offered.)

7. **How they run.** Both reuse the join's engine: each input is sorted by its key or compared
   columns within `memory_limit_mb`, on disk past it, and the sorted streams are walked together.
   Any number of rows can be compared. Rows come out in the order of the key or compared columns,
   empty values last. A semi or anti join never holds the right's rows; except and intersect never
   hold the other inputs' rows.

8. **Sensitive columns.** A preview by someone who may not see unmasked values is refused when a
   semi or anti join matches on a column marked sensitive (as every join's keys are), and when an
   except or intersect compares one (as a `dedupe` on one is): which rows come out says whether the
   other input holds the same values. A semi or anti join passes on no mark of the right's; an
   except or intersect keeps every input's marks on what it writes, more than it need, never less.

## Consequences

- The recipe "Rows in one input and not the other" in the pipelines guide says when to use which,
  and the sample `sample-only-in-archived` shows the anti join.
- A union's settings now have `mode` and `compare_on`; a union with neither behaves as before.
- Quality prediction does not predict the rows of an except or intersect, nor of any join: which
  rows match is known only when it runs.
- The canvas shows which input a comparing union keeps on its card; changing which is first means
  changing the order in `input: [...]`.

## Alternatives considered

- **A new `difference` node.** One more node to learn for what SQL users already know by name; the
  two questions would still need two settings.
- **`NOT IN` nulls for anti.** Faithful to one SQL form, and a known trap.
- **Folding repeats as SQL does.** See point 6.
- **Treating a column one input lacks as empty.** A union in `mode: all` does that, rightly; in a
  comparison it would silently make every row differ.
