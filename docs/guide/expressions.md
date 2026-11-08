# Expressions

`filter`, `compute`, `aggregate`, `route` and `window` nodes, and a `read` node's `where`, use one
small expression language, much like SQL. It is checked against the columns before the pipeline
runs, so a typing mistake or a type mismatch is reported with its position and, often, a
suggestion:

```text
Node tidy: column full_name: unknown column 'frist_name' at 7; did you mean first_name?
```

Expressions only ever compute values: they cannot run code, read files or reach the network. Each
node's settings are in [Reference: pipeline nodes](reference-pipeline-nodes.md); what happens to a
row whose expression fails is in [Rows that fail](rows-that-fail.md).

## Values

| Write | For |
| --- | --- |
| `amount`, `"order date"` | A column. Quote names with spaces, capitals you want to keep as typed, or that are words of the language (`"date"`, `"end"`). Names are case-sensitive. |
| `'text'`, `'it''s'` | Text; double a quote inside it. |
| `42`, `-7` | A whole number. |
| `12.50` | An exact decimal. |
| `1.5e3` | A floating-point number. |
| `true`, `false`, `null` | True, false and empty. |
| `date '2024-01-31'` | A date. |
| `timestamp '2024-01-31 09:30:00'` | A time, in UTC unless it ends with an offset such as `+01:00` or `Z`. |

## Operators

From loosest to tightest:

| Operators | Meaning |
| --- | --- |
| `or` | Either is true. |
| `and` | Both are true. |
| `not` | The opposite. |
| `=`, `<>` (or `!=`), `<`, `<=`, `>`, `>=` | Comparisons. Text compares character by character in Unicode order. |
| `is null`, `is not null` | Whether a value is empty. |
| `x in (a, b, c)`, `not in` | Whether `x` equals any of them. |
| `x between a and b`, `not between` | `x >= a and x <= b`. |
| `x like 'A%'`, `not like` | Text pattern: `%` is any run of characters, `_` one character, `\%` a real percent sign. Case matters. |
| `\|\|` | Joins text. |
| `+`, `-` | Add, subtract. A date plus or minus a whole number moves it by days; a date minus a date counts the days between. |
| `*`, `/`, `%` | Multiply, divide, remainder. Dividing two whole numbers gives a float: `7 / 2` is `3.5`. |
| `-x` | Negative. |

Choices:

```sql
if(amount >= 1000, 'large', 'small')
case when amount >= 1000 then 'large' when amount >= 100 then 'medium' else 'small' end
case status when 'A' then 'active' when 'C' then 'closed' end
```

`if(condition, a, b)` gives `a` where the condition is true and `b` where it is false or empty; for
more than two choices, use `case`. A `case` without `else` gives empty when nothing matches. Both
work out only the branch a row takes, so `if(qty <> 0, total / qty, null)` never divides by zero.
The results must be of one kind of value: numbers widen to one type, as in arithmetic.

## Empty values (null)

Empty values follow SQL's rules:

- Arithmetic, comparisons and most functions with an empty input give empty: `null + 1` and
  `null = null` are both empty, not true.
- `false and null` is false and `true or null` is true; otherwise `and`, `or` and `not` with an
  empty value give empty.
- `x in (1, null)` is true when `x` is 1 and empty otherwise.
- `filter` and `route` treat empty as not true.
- Use `is null`, `coalesce` and `nullif` to handle empty values on purpose.

## Types

| Type | Holds |
| --- | --- |
| `boolean` | True or false. |
| `int64` | Whole numbers. Every integer column reads as this. |
| `float64` | Floating-point numbers. |
| `decimal(p,s)` | Exact decimals with up to 38 digits, `s` of them after the point. |
| `utf8` | Text. |
| `date` | A calendar day. |
| `timestamp` | An instant, to the microsecond, always in UTC. |

Numbers widen on their own when mixed (`int64` to `decimal` to `float64`), and a date compared
with a timestamp counts as midnight UTC. Anything else needs `cast(x as type)`, for example
`cast(customer_id as utf8)` or `cast(amount as decimal(10,2))`. Other names work too: `integer`,
`bigint`, `double`, `numeric`, `text`, `string`, `varchar`.

Casting text is strict: `cast('12a' as int64)` stops the run. To handle bad values, test them
first, as in `case when regex_match(code, '^[0-9]+$') then cast(code as int64) end`, or use
`parse_date` and `parse_timestamp`, which give empty instead.

Whole numbers that overflow, and division by zero, stop the run. Guard a division with `nullif`:
`total / nullif(qty, 0)`.

## Functions

| Function | Gives |
| --- | --- |
| `if(condition, a, b)` | `a` where the condition is true, otherwise `b` (see [Operators](#operators)). |
| `trim(s)` | `s` without spaces, tabs, line breaks or other whitespace at either end (a no-break space is kept). |
| `lower(s)`, `upper(s)` | `s` in small or capital letters. |
| `length(s)` | The number of characters. |
| `substring(s, start)`, `substring(s, start, length)` | Part of `s`, counting characters from 1. |
| `replace(s, from, to)` | `s` with every `from` replaced by `to`. |
| `lpad(s, n)`, `lpad(s, n, fill)`, `rpad(s, n)`, `rpad(s, n, fill)` | `s` filled on the left or right with `fill` (a space unless given) to `n` characters: `lpad('42', 5, '0')` is `00042`. Longer text is cut to its first `n` characters; an empty `fill` leaves `s` as it is. |
| `left(s, n)`, `right(s, n)` | The first or last `n` characters; with a negative `n`, all but the last or first `-n`. |
| `split_part(s, separator, n)` | Field `n` of `s` split at each `separator`, counting from 1, or from the end with -1 for the last; empty text when there is no such field. `split_part(email, '@', 2)` is the domain. 0 stops the run. |
| `starts_with(s, x)`, `ends_with(s, x)`, `contains(s, x)` | Whether `s` starts with, ends with or holds `x`. Case matters. |
| `concat(a, b, ...)` | The values joined as text, of any type, skipping empty ones. |
| `coalesce(a, b, ...)` | The first value that is not empty. |
| `nullif(a, b)` | Empty when `a` equals `b`, otherwise `a`. |
| `greatest(a, b, ...)`, `least(a, b, ...)` | The largest or smallest value, widened to one type as in arithmetic; empty values are skipped, and the result is empty only when all are. |
| `abs(x)` | `x` without its sign. |
| `round(x)`, `round(x, digits)` | `x` rounded half away from zero; `digits` may be negative (`round(1234, -2)` is 1200). |
| `floor(x)`, `ceil(x)` | `x` rounded down or up to a whole number. |
| `regex_match(s, pattern)` | Whether the regular expression is found in `s`; use `^` and `$` to match all of it. |
| `regex_replace(s, pattern, replacement)` | `s` with every match replaced; `$1` in the replacement is the first group. |
| `now()` | The time the node started, the same for every row. |
| `today()` | Today's date in UTC. |
| `date_trunc('unit', d)` | The start of the year, quarter, month, week (Monday), day, hour, minute or second holding `d`. |
| `date_part('unit', d)` | A part of `d` as a whole number: `year`, `quarter`, `month`, `week` (ISO), `day`, `day_of_week` (1 is Monday), `day_of_year`, `hour`, `minute`, `second`. |
| `parse_date(s)`, `parse_date(s, 'pattern')` | Text read as a date, or empty when it does not fit. |
| `parse_timestamp(s)`, `parse_timestamp(s, 'pattern')` | Text read as a timestamp (UTC unless the text has an offset), or empty. |
| `format_date(d, 'pattern')` | A date or timestamp as text, in UTC. |
| `date_add(d, n, 'unit')` | `d` moved by `n` (negative to go back) years, quarters, months, weeks, days, hours, minutes or seconds, as a calendar moves: `date_add(date '2024-01-31', 1, 'month')` is 29 February. A date takes year to day; cast it to a timestamp for hours. Leaving years -9999 to 9999 stops the run. |
| `date_diff('unit', a, b)` | How many whole units from `a` to `b` (negative when `b` is earlier), in the same units: `date_diff('month', date '2024-01-31', date '2024-02-29')` is 0, and `date_diff('day', ...)` 29. A date counts from its midnight beside a timestamp. |
| `sha256(s)` | The SHA-256 hash of the text (its UTF-8 bytes) as 64 small hexadecimal digits, for comparing values or spotting changes without keeping the text. Cast other values first: `sha256(cast(id as utf8))`. A hash of a column marked sensitive is marked too. |

Functions take empty (null) values as operators do: an empty argument gives an empty result, except
`if`, `coalesce`, `nullif`, `concat`, `greatest` and `least`, which say what they do with them.
Function names are not case-sensitive (`IF` and `if` are one). There is no `md5`: it is not safe
for anything that matters, so `sha256` is the hash to use. The sample `sample-functions` uses most
of these functions on the sample invoices and customers.

Date patterns use letters such as `yyyy` (year), `MM` (month), `MMM` (Jan), `dd` (day), `HH`
(hour), `mm` (minute), `ss` (second) and `XXX` (offset); put other words in single quotes, as in
`'dd/MM/yyyy'` or `'yyyy-MM-dd''T''HH:mm'`. Patterns and units (for `date_trunc`, `date_part`,
`date_add` and `date_diff`) must be written out in quotes, so mistakes are found before the
pipeline runs. Dates are read strictly: 31 February is empty, not 3 March.

## Aggregate functions

An [`aggregate`](reference-pipeline-nodes.md#aggregate) node's values use these:

| Function | Gives |
| --- | --- |
| `count(*)` | The number of rows. |
| `count(x)` | The number of rows where `x` is not empty. |
| `count_distinct(x)` | The number of different non-empty values of `x`, counted exactly. |
| `sum(x)` | The total of `x`; empty when every value is empty. |
| `min(x)`, `max(x)` | The smallest or largest value: numbers, text, dates, timestamps. |
| `avg(x)` | The average of the non-empty values (a decimal for decimals, otherwise a float). |

A value can combine aggregates and group-by columns in an expression, such as
`sum(amount) / count(*)` or `upper(region) || ':'`. A column used outside an aggregate must be in
`group_by`, and aggregates cannot be inside each other.

## Window functions

Each new column of a [`window`](reference-pipeline-nodes.md#window) node is one of these:

| Function | Gives |
| --- | --- |
| `row_number()` | The row's place in its group: 1, 2, 3, ... |
| `rank()` | The row's place, rows with equal `order_by` values sharing one, with a gap after them: 1, 1, 3. |
| `dense_rank()` | As `rank`, without the gaps: 1, 1, 2. |
| `count(*)`, `count(x)` | The rows so far in the group, or those where `x` is not empty. |
| `sum(x)` | The running total of `x`; empty until the first value that is not empty. |
| `avg(x)` | The average of the values so far (a decimal for decimals, otherwise a float). |
| `min(x)`, `max(x)` | The smallest or largest value so far. |
| `lag(x)`, `lag(x, offset)`, `lag(x, offset, default)` | `x` from the row `offset` rows before in the group (1 unless you say), or `default` when there is none (empty unless you give one). |
| `lead(x)`, `lead(x, offset)`, `lead(x, offset, default)` | The same, from the row after. |

- The arguments are expressions, so `sum(price * quantity)` and `lag(upper(status))` work. A
  window column is one function: to work more out from it, such as `opening + balance`, add a
  `compute` node after the window node.
- Running totals take the rows so far, this one included, one row at a time. Rows with equal
  `order_by` values get different running totals, in the order they arrived; add a column to
  `order_by`, such as an id, to make the order exact. (SQL's default for a running total gives tied
  rows the same total; `rank` and `dense_rank` are the functions here that treat ties alike.)
- Sums, counts, averages, minimums and maximums have the types an `aggregate` gives. A `lag` or
  `lead` has the type of its value, widened to hold its default; the offset is a whole number from
  0 to 1,000, written out, and the default must be of the same kind of value (cast one if not).
- `lag`, `lead`, `row_number`, `rank` and `dense_rank` work only in a window node.

## Regular expressions are kept safe

Some regular expressions can take practically for ever on unlucky text. To keep every pipeline
running:

- Patterns that repeat a group which itself repeats, such as `(a+)+` or `(\w+\s?)*`, are refused.
  They can almost always be simplified: `(a+)+` matches the same as `a+`, and `(a|b)*` as
  `[ab]*`. One kind is allowed: a group repeated with `+` or `*` that starts with a punctuation
  separator nothing else in it can match, as in `^[\w-]+(\.[\w-]+)+$` for host names. A group
  repeated a counted number of times, such as `(\.[a-z]+){2,5}`, must not repeat inside.
- Back-references such as `\1` and comment mode `(?x)` are refused.
- Text longer than 100,000 characters is an error, and longer than 2,000 characters for patterns
  that repeat a group.
- Each value gets a fixed amount of work (roughly a fifth of a second), and each batch of rows a
  larger one. A pattern that needs more stops the run with a message naming the expression.
- `like` has the same 100,000-character limit and a work limit of its own.

## Limits

- An expression may be up to 10,000 characters long.
- It may be nested up to 64 levels deep: brackets, function calls, `case`, `cast`, `in (...)`,
  `not` and a leading minus each count as a level.
- It may have at most 1,000 operations inside each other, counting a chain such as `a + b + c`
  as one operation inside the next.
- Comparisons do not chain: write `a = b and b = c`, or use brackets.
- A text value may grow to at most 1,000,000 characters (`||`, `concat`, `replace`,
  `regex_replace`, `lpad`, `rpad`), and each of them may produce at most 64,000,000 characters for
  one batch of rows.
- Dates and timestamps run from year -9999 to 9999.

For longer logic, split it over several `compute` columns.
