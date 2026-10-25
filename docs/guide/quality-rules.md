# Setting data quality rules

This guide shows you how to check your data with quality rules in Kilnmark: what each check
does, how to set thresholds, how to try a rule before you save it, and what happens when a rule
fails.

## Before you start

- **Scan the source first.** Rules are built on an asset's profile, so an asset that has not been
  scanned has nothing to check against yet. Open the source and choose **Scan**.
- **You need the steward or admin role** to add, run or change rules. Viewers can see rules and
  their results. Your role is set by the API token you signed in with; ask an admin for a steward
  token if you need one.
- **Where rules live:**
  - On an asset's page, the **Quality** section shows its scores, value patterns, rules and
    results, drift and recommended rules.
  - The **Quality rules** page lists every rule on every asset and has the form for writing a
    new one. Open it from the navigation, or with **Manage rules for this asset** on an asset
    page, which filters the list to that asset.
  - The **Issues** page lists rules that fail or warn, if the rule asks for a task, and
    significant drift.

## The quickest way: let the profile write the rule

Every scan already tells Kilnmark what your data looks like. Three buttons on the asset page turn
that into rules in one click. Start here before writing rules by hand.

### 1. Accept a value pattern

Under **Value patterns**, pick a column. Each value is shown reduced to its shape: `A` for a
capital letter, `a` for a small letter, `9` for a digit, with punctuation kept as it is. For
example:

| Value | Shape |
| --- | --- |
| `SW1A 1AA` | `AA9A 9AA` |
| `2024-03-01` | `9999-99-99` |
| `jo@example.com` | `aa@aaaaaaa.aaa` |

The table shows each shape's share of the column, counted over every row, with example values if
your role may see data.

Choose **Accept** next to each shape that is correct. Kilnmark creates a validity rule for the
column that passes values in any accepted shape and fails everything else. Accept more shapes
later and the same rule covers them. **Withdraw** removes a shape from the rule.

> **Tip:** Look at **Other shapes** and its examples before accepting. That is where the
> mistakes usually are.

### 2. Add the suggested expression

Under the patterns, Kilnmark may show a **suggested expression**, a regular expression that covers
the main shapes, with how much of the column it covers. **Add as a rule** creates a *Matches
pattern* rule with it.

### 3. Add a recommended rule

At the foot of the Quality section, **Recommended rules** lists what the data already obeys:
columns with no nulls, columns where every value is unique, small sets of values, ranges, dates
that keep up to date, and steady row counts. Each comes with the reason it was suggested. **Add
rule** turns it into a rule, so that you notice when the data stops obeying it.

## Writing a rule yourself

Open **Quality rules** and fill in **Add a rule**.

1. **Asset URN.** The asset's identifier, shown at the foot of its page, for example
   `urn:kilnmark:lab:crm.customers`. It is filled in already if you came from **Manage rules for
   this asset**.
2. **Check.** The kind of rule. See [Choosing a check](#choosing-a-check).
3. **Column.** The column the rule checks, spelled exactly as on the asset page. Row count checks
   and custom SQL don't need one.
4. **Name** (optional). How the rule appears in lists and alerts. If you leave it empty, Kilnmark
   names it after the column and check, for example *email not null*.
5. **Parameters.** The details of the check, written as JSON. The box is filled with an example
   for the check you chose. Edit the values and keep the shape.
6. **Thresholds.** How many failing rows are acceptable. See [Thresholds](#thresholds).
7. **When it warns or fails.** Open a task for stewards, call a webhook, or both. See [Alerts and
   tasks](#alerts-and-tasks).
8. Choose **Preview on the sample** to see what the rule would find. See [Trying a rule before you
   save it](#trying-a-rule-before-you-save-it).
9. Choose **Save rule**. The rule runs after the next scan, or straight away when you choose **Run
   now**.

If something is wrong, the form says what and where, for example *params.regex: unbalanced
bracket* or *field: the asset has no field called emial*.

## Choosing a check

Each check is tagged with a quality dimension, which decides which score it affects.

| Check | What fails | Parameters (example) | Dimension |
| --- | --- | --- | --- |
| **Not null** | Empty values. With `blankIsMissing`, blank text such as `""` or `"  "` fails too | `{"blankIsMissing": true}` | Completeness |
| **Unique** | Values that appear more than once. For a key of several columns, list them in `fields` and leave **Column** empty | `{}` or `{"fields": ["order_id", "line_no"]}` | Uniqueness |
| **Allowed values** | Any value not in the list (up to 1,000 values, matched exactly, case included) | `{"values": ["Retail", "Trade", "Staff"]}` | Validity |
| **Matches pattern** | Any value that does not match the regular expression | `{"regex": "^[A-Z]{2}[0-9]{6}[A-D]$"}` | Validity |
| **In range** | Values below `min` or above `max`. Both limits count as inside. Give one or both. Dates are written `2024-01-31` | `{"min": 0, "max": 10000}` | Accuracy |
| **Freshness** | The whole check fails when the newest value in a date or timestamp column is older than `maxAgeHours` | `{"maxAgeHours": 24}` | Timeliness |
| **Row count** | The whole check fails when the asset has fewer than `min` or more than `max` rows | `{"min": 1}` | Volume |
| **Row count change** | The whole check fails when the row count moved by more than `maxChange` since the last scan. `0.2` means 20% | `{"maxChange": 0.2}` | Volume |
| **References** | Values that don't appear in a column of another asset in the same source: a foreign key that does not hold | `{"asset": "urn:kilnmark:lab:crm.customers", "field": "customer_id"}` | Referential integrity |
| **Custom SQL** | The rows your query returns. Admins only; see [Custom SQL](#custom-sql-admins) | `{"sql": "SELECT * FROM ${table} WHERE amount < 0"}` | Validity |

**Where each check runs.** On a database, a rule runs as SQL inside the database, so the data stays
where it is. On files, Kilnmark checks the rows while it reads them, in one pass. Two checks need
a database: **References**, and **Custom SQL**, which needs PostgreSQL.

**Changing the dimension.** A rule can be tagged with a different dimension through the API, for
example a pattern rule counted as Consistency. The form uses each check's usual dimension.

**Times.** Timestamps stored without a time zone are read as UTC.

## Thresholds

A rule counts its failing rows and compares the result with two limits.

- **Measured as:** *Percentage of rows* (the share of rows checked that fail) or *Number of rows*.
- **Fail above:** the rule fails when failing rows are *above* this. `0`, the default, means any
  failing row fails the rule.
- **Warn above** (optional): the rule warns when failing rows are above this but not above the
  fail limit. Leave it empty for no warning. It cannot be higher than the fail limit.

Percentages are typed as percentages: `5` means 5%.

| You want | Measured as | Warn above | Fail above |
| --- | --- | --- | --- |
| No exceptions at all | Percentage | (empty) | `0` |
| A few bad rows are tolerable, many are not | Percentage | `1` | `5` |
| Up to ten known bad rows, then fail | Number of rows | (empty) | `10` |
| Hear early, fail late | Number of rows | `0` | `100` |

**Freshness, Row count and Row count change** give one result for the whole asset, so they simply
pass or fail and ignore these limits.

## Trying a rule before you save it

**Preview on the sample** runs the rule against the asset's stored sample, about 1,000 rows by
default, and shows the result straight away, for example *Would fail 3.1% of sampled rows*, with
some of the failing rows. Use it to catch a wrong regular expression or a forgotten value before
the rule goes live.

- The sample is the same set of rows on every scan, so a preview today and one tomorrow are
  comparable.
- Columns marked sensitive are masked in the failing rows unless you may see them unmasked.
- If the source keeps no samples (its `keep_samples` setting is off), there is nothing to preview
  against. Save the rule and use **Run now** instead.
- The preview is a guide, not the result. The real run checks every row.

## Running rules and reading results

Rules run:

- **after every scan** of the asset's source;
- **on demand:** **Run rules now** on the asset page runs all of its rules, and **Run now** on the
  Quality rules page runs one. Runs go through the job queue, so results appear when a worker has
  finished. The asset page waits and refreshes by itself.

Each result shows:

| Status | Meaning |
| --- | --- |
| **Pass** | Failing rows are within the limits |
| **Warning** | Above *Warn above*, not above *Fail above* |
| **Fail** | Above *Fail above* |
| **Could not run** | The check did not complete, for example because the column was dropped, the query timed out or the database refused it. The reason is shown under the status |
| **Not run** | Saved but not run yet |

Next to the status:

- **Failing rows**, for example *312 of 48,210*.
- **Query for the failing rows** on database rules. Copy it into your own SQL tool to see every
  failing row.
- An **estimate** tag when the asset was too large to read in full and was sampled at the source.
  The numbers are then estimated from a sample, and the asset page says from how many rows.

### How rules affect scores

Every asset has a score per dimension, rolled up into an overall score. Without any rules, the
scores come from the automatic measures of the profile. A rule's latest result then sets its
column's score in the rule's dimension to the share of rows that passed. Drift between scans lowers
the dimension it touches by 5%, 15% or 30% for low, medium and high severity. The **Why** column
next to each score gives the main reasons.

## Alerts and tasks

Under **When it warns or fails**:

- **Open a task in the stewards' issue list.** A warning opens a medium task, a failure a high one,
  and a rule that could not run a low one. The task stays open while the rule keeps failing, and
  closes by itself once the rule passes again.
- **Webhook URL.** Kilnmark posts the result to this address when the rule warns, fails or cannot
  run, and once more when it recovers. Only addresses on the administrator's allow-list are
  accepted (`kilnmark.quality.webhook-allow-list`). A failed call is not retried; the next run
  calls again if the problem continues. The message looks like this:

```json
{
  "event": "quality.result",
  "resultId": 981,
  "ruleId": 42,
  "rule": "email matches pattern",
  "type": "matches_pattern",
  "dimension": "validity",
  "asset": "urn:kilnmark:lab:crm.customers",
  "field": "email",
  "status": "fail",
  "previousStatus": "pass",
  "failing": 312,
  "total": 48210,
  "observedAt": "2026-10-25T06:00:00Z"
}
```

When a rule could not run, `failing` and `total` are replaced by `error`, which gives the reason.

## Managing rules

On the **Quality rules** page, each rule has:

- **Run now:** runs it at once.
- **Switch off / Switch on:** a rule that is off keeps its history but does not run. Use this
  instead of deleting a rule you may want back.
- **Delete:** removes the rule and its results, after you confirm.

To change a rule's settings in this release, add the corrected rule, then delete the old one.
Editing in place is coming to the form; the API supports it already (`PUT /api/v1/quality/rules/{id}`).

## Custom SQL (admins)

When no check in the library fits, an admin can write the query that finds the failing rows.

- It is **off by default.** An administrator turns it on with `kilnmark.quality.custom-sql=true`.
- **PostgreSQL sources only.** There, Kilnmark can hold the query to a read-only transaction.
- Write **one `SELECT` that returns the failing rows.** `${table}` is replaced with the asset's
  table, and `${column}` with the rule's column when you give one:

  ```sql
  SELECT * FROM ${table} WHERE delivered_on < ordered_on
  ```

- It runs with a time limit (`kilnmark.quality.query-timeout`, 60 seconds by default) and is rolled
  back afterwards.
- Kilnmark refuses to run it under a database login with superuser or file and program access.
  Give every source a **read-only login**, because a custom query can read anything that login can
  see.

## Recipes

| Goal | Check | Column | Parameters | Thresholds |
| --- | --- | --- | --- | --- |
| Every customer has an email | Not null | `email` | `{"blankIsMissing": true}` | Fail above 0 |
| Emails look like emails | Matches pattern | `email` | `{"regex": "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"}` | Warn above 0.5, fail above 2 |
| UK postcodes are well formed | Matches pattern | `postcode` | `{"regex": "^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$"}` | Warn above 1, fail above 5 |
| No duplicate order lines | Unique | (empty) | `{"fields": ["order_id", "line_no"]}` | Fail above 0 |
| Status is a known value | Allowed values | `status` | `{"values": ["open", "shipped", "cancelled"]}` | Fail above 0 |
| Amounts are sensible | In range | `amount` | `{"min": 0, "max": 50000}` | Number of rows, warn above 0, fail above 20 |
| Dates of birth are plausible | In range | `date_of_birth` | `{"min": "1900-01-01", "max": "2026-12-31"}` | Fail above 0 |
| The nightly load arrived | Freshness | `loaded_at` | `{"maxAgeHours": 26}` | (pass or fail) |
| The table is never empty | Row count | (empty) | `{"min": 1}` | (pass or fail) |
| No sudden drop or spike in volume | Row count change | (empty) | `{"maxChange": 0.3}` | (pass or fail) |
| Every order has a real customer | References | `customer_id` | `{"asset": "urn:kilnmark:lab:crm.customers", "field": "customer_id"}` | Fail above 0 |

Backslashes in a regular expression are doubled inside JSON: `\s` is written `\\s`.

## Sharing rules with other tools

An asset's schema and rules can be exported as an Open Data Contract Standard 3.1 contract, from
`GET /api/v1/quality/export/odcs?urn=...`. The interactive API documentation at `/api/docs` lets
you try it with your token.

## When something goes wrong

| Message | What to do |
| --- | --- |
| *Not profiled yet* | Scan the source, then come back |
| *field: the asset has no field called …* | Check the column's spelling on the asset page. Names are case sensitive |
| *Parameters: not valid JSON* | Keep the quotes and brackets of the example. Text goes in double quotes |
| *params.regex: …* | The regular expression does not compile, or is too costly to run. Test it in the preview |
| *freshness needs a date or timestamp field* | Pick a column whose type is a date or timestamp. A date stored as text needs a pattern rule instead |
| *references rules run in the database* | References need a database source. On files, use Allowed values |
| *… is not on this server's webhook allow-list* | Ask an administrator to add the address prefix |
| *custom SQL rules are turned off on this server* | Ask an administrator, or use a library check |
| **Could not run** after a schema change | The column was renamed or dropped. Switch the rule off, or add it again for the new column |
