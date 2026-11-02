# Setting data quality rules

This guide shows you how to check your data with quality rules in Kilnmark: what each check
does, how to set thresholds, how to try a rule before you save it, and what happens when a rule
fails.

## Before you start

- **Scan the source first.** Rules are built on an asset's profile, so an asset that has not been
  scanned has nothing to check against yet. Open the source and choose **Scan**.
- **You need the steward role or above** to add, run or change rules (custom SQL rules: a designer
  whose token sees unmasked values). Viewers can see rules and their results. Your role is set by the API token you signed
  in with; ask an admin for a steward token if you need one.
- **Where rules live:**
  - On an asset's page, the **Quality** tab shows its overall score and a score for each
    dimension, the rules on it with their latest result and a bar for each of the last ten
    results, recommended rules, value patterns, drift and sample rows.
  - The **Quality rules** page lists every rule on every asset and has the form for writing a
    new one or editing one. Open it from the navigation, or with **Manage rules for this asset**
    on an asset page, which filters the list to that asset.
  - The **Issues** page lists rules that fail or warn, if the rule asks for a task, and
    significant drift.

## The quickest way: let the profile write the rule

Every scan already tells Kilnmark what your data looks like. Three places on an asset's
**Quality** tab turn that into rules in a click or two. Start here before writing rules by hand.

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

Beside the rules, **Recommended from the profile** lists what the data already obeys: columns
with no nulls, columns where every value is unique, small sets of values, ranges, patterns, dates
that keep up to date, and steady row counts. Each line shows the column, the check, how much of
the data it fits (*fits 100%*, or *not a row check* for freshness and row counts) and the reason
it was suggested.

Suggestions that fit every row are ticked already. Tick or untick the ones you want; the chips
above the list (**Completeness**, **Validity** and so on) show one dimension at a time, and
**Select all** ticks every suggestion shown. **Add N selected** adds them one by one, then one
message says which were added and which could not be, and why. Added rules run after the next
scan, or press **Run rules now**. A suggestion on a sensitive column whose values are hidden from
you cannot be ticked.

## Writing a rule yourself

Open **Quality rules** and fill in **Add a rule**. You don't need to know any identifiers, column
spellings or JSON: you choose from lists and fill in plain fields.

1. **Asset.** Type part of the table, view or file name under **Find an asset** and choose it from
   the matches, which show each asset's source and path. The chosen asset is shown with its URN
   underneath, for example `urn:kilnmark:sales-db:crm.customers`. You can also paste a URN into the box
   and choose **Use this URN**. The asset is chosen already if you came from **Manage rules for
   this asset**.
2. **Check.** The kind of rule. The line under the list says what fails. See [Choosing a
   check](#choosing-a-check).
3. **Column.** Choose the column from the list, which shows each column's name and type. Only
   columns that suit the check are listed: **Freshness** lists date and time columns, **In range**
   lists number, date and time columns. If none of the asset's columns suits the check, the form
   says so. Row count checks don't take a column, and for custom SQL it is optional.
4. **Name** (optional). How the rule appears in lists and alerts. If you leave it empty, Kilnmark
   names it after the column and check, for example *email not null*.
5. **Settings for this check.** Plain fields for the details of the check, such as the allowed
   values, one per line, or the regular expression. The table in [Choosing a
   check](#choosing-a-check) lists them.
6. **Thresholds.** How many failing rows are acceptable. See [Thresholds](#thresholds). Checks that
   pass or fail as a whole have none, and the form says so.
7. **When it warns or fails.** Open a task for stewards, call a webhook, or both. See [Alerts and
   tasks](#alerts-and-tasks).
8. Choose **Preview on the sample** to see what the rule would find. See [Trying a rule before you
   save it](#trying-a-rule-before-you-save-it).
9. Choose **Save rule**. The rule runs after the next scan, or straight away when you choose **Run
   now**.

If something is wrong, the form says what and where, and marks the field, for example *Allowed
values: type at least one value, one per line*. Some problems are found only by the server, which
names the setting it means, for example *params.regex: unbalanced bracket* or *field: the asset
has no field called emial*.

### Advanced: the parameters as JSON

Under the form, **Advanced: edit as JSON** shows the parameters your fields make, as the API takes
them. Choose **Edit as JSON** to type them yourself, for example to paste a rule from elsewhere.
The JSON is then used instead of the fields, which are set aside until you choose **Use the fields
instead**. In JSON, and when you send a rule to the API, backslashes in a regular expression are
doubled: `\s` is written `\\s`. In the **Pattern** field you type it once, as `\s`.

## Choosing a check

Each check is tagged with a quality dimension, which decides which score it affects.

| Check | What fails | What you fill in | Dimension |
| --- | --- | --- | --- |
| **Not null** | Empty values. With **Also count blank text as missing**, blank text such as `""` or `"  "` fails too | The switch | Completeness |
| **Unique** | Values that appear more than once | Either the column, or **A key of several columns together** and the key columns ticked | Uniqueness |
| **Allowed values** | Any value not in the list | The values, one per line: up to 1,000, matched exactly, capital letters included | Validity |
| **Matches pattern** | Any value that does not match the regular expression | The **Pattern**, for example `^[A-Z]{2}[0-9]{6}[A-D]$`. **Use the suggested expression** fills in the profile's suggestion when there is one | Validity |
| **In range** | Values below the minimum or above the maximum. Both limits count as inside | **Minimum**, **Maximum** or both: numbers for number columns (with a full stop for decimals), dates for date columns, and a date and time in UTC for date-and-time columns | Accuracy |
| **Freshness** | The whole check fails when the newest value in a date or time column is too old | **Newest value at most** so many **hours old** | Timeliness |
| **Row count** | The whole check fails when the asset has too few or too many rows | **Minimum rows**, **Maximum rows** or both, as whole numbers | Volume |
| **Row count change** | The whole check fails when the row count moved by too much since the last scan, up or down | **Largest change since the last scan**, in percent: `20` means 20% | Volume |
| **References** | Values that don't appear in a column of another asset in the same source: a foreign key that does not hold | **Asset referred to**, found by name in the same source, and its **Column referred to** | Referential integrity |
| **Custom SQL** | The rows your query returns. Designers who see unmasked values only; see [Custom SQL](#custom-sql-designers) | The **SQL**, for example `SELECT * FROM ${table} WHERE amount < 0` | Validity |

**Where each check runs.** On a database, a rule runs as SQL inside the database, so the data stays
where it is. On files, Kilnmark checks the rows while it reads them, in one pass. Two checks need
a database: **References**, and **Custom SQL**, which needs PostgreSQL.

**Changing the dimension.** A rule can be tagged with a different dimension through the API, for
example a pattern rule counted as Consistency. The form uses each check's usual dimension for new
rules. Editing a rule keeps its dimension unless you change its check.

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
- **on demand:** **Run rules now** on the asset's Quality tab runs all of its rules, and **Run now** on the
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
  "asset": "urn:kilnmark:sales-db:crm.customers",
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
- **On:** a switch. A rule switched off keeps its history but does not run. Use this instead of
  deleting a rule you may want back.
- **Edit:** opens the rule in the form, headed **Edit rule**. Change what you need and choose
  **Save changes**, or **Cancel** to leave it as it was. The rule keeps its results so far, and
  the next run uses the new settings. A rule stays on its asset: to check another asset, add a new
  rule there. Only designers who see unmasked values can edit custom SQL rules.

  On a column marked sensitive, the allowed values and range limits may be hidden from you. The
  form then shows **Hidden: this column is sensitive**, and saving keeps the hidden values. The
  check and column stay fixed, and the preview is off, until you choose **Replace the values** and
  type new ones. A webhook address hidden from your role is kept unless you type a new one.
- **Delete:** removes the rule and its results, after you confirm.

While a rule is open in the form, its **On** switch and **Delete** button wait, so saving cannot
undo them. If someone else changes the rule after you opened it, saving is refused rather than
undoing their change: choose **Load the rule as it is now** and make your change again.

Rules can also be changed through the API, with `PUT /api/v1/quality/rules/{id}`. Send the
rule's `updatedAt`, as you read it, in an `If-Match` header to have the change refused (409) if the
rule has changed since.

## Custom SQL (designers)

When no check in the library fits, a designer whose token sees unmasked values can write the query
that finds the failing rows. A custom query can read any column the source's login can see, so
admins, who see sensitive values masked, cannot write or run one
([ADR 0027](../adr/0027-unmasking-is-a-grant.md)). Its failing rows and error text are withheld
from everyone else while any table of the source has a column marked sensitive.

- It is **off by default, source by source.** A designer or an admin turns on **Allow SQL**
  (`allow_sql`) for the source: open it, choose **Edit**, and set it on the last step
  ([ADR 0028](../adr/0028-sql-in-reads-and-flow-actions.md)). The server-wide
  `kilnmark.quality.custom-sql` switch is retired and does nothing.
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

| Goal | Check | Column | Settings | Thresholds |
| --- | --- | --- | --- | --- |
| Every customer has an email | Not null | `email` | Also count blank text as missing: on | Fail above 0 |
| Emails look like emails | Matches pattern | `email` | Pattern: `^[^@\s]+@[^@\s]+\.[^@\s]+$` | Warn above 0.5, fail above 2 |
| UK postcodes are well formed | Matches pattern | `postcode` | Pattern: `^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$` | Warn above 1, fail above 5 |
| No duplicate order lines | Unique | (none) | A key of several columns together: `order_id` and `line_no` ticked | Fail above 0 |
| Status is a known value | Allowed values | `status` | `open`, `shipped` and `cancelled`, one per line | Fail above 0 |
| Amounts are sensible | In range | `amount` | Minimum 0, maximum 50000 | Number of rows, warn above 0, fail above 20 |
| Dates of birth are plausible | In range | `date_of_birth` | Minimum 1900-01-01, maximum 2026-12-31 | Fail above 0 |
| The nightly load arrived | Freshness | `loaded_at` | Newest value at most 26 hours old | (pass or fail) |
| The table is never empty | Row count | (none) | Minimum rows 1 | (pass or fail) |
| No sudden drop or spike in volume | Row count change | (none) | Largest change since the last scan 30% | (pass or fail) |
| Every order has a real customer | References | `customer_id` | Asset referred to: `customers`, column referred to: `customer_id` | Fail above 0 |

Through the API or the JSON view, the same settings are JSON parameters, for example
`{"regex": "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"}` for the email pattern (backslashes doubled),
`{"fields": ["order_id", "line_no"]}` for the key and `{"maxChange": 0.3}` for 30%.

## Sharing rules with other tools

An asset's schema and rules can be exported as an Open Data Contract Standard 3.1 contract, from
`GET /api/v1/quality/export/odcs?urn=...`. The interactive API documentation at `/api/docs` lets
you try it with your token.

## When something goes wrong

| Message | What to do |
| --- | --- |
| *Not profiled yet* | Scan the source, then come back |
| *field: the asset has no field called …* | The column was renamed or dropped since the form loaded. Choose it again from the list |
| *Parameters (JSON): not valid JSON* | Only in the JSON view. Keep the quotes and brackets, put text in double quotes and double each backslash, or choose **Use the fields instead** |
| *params.regex: …* | The regular expression does not compile, or is too costly to run. Test it in the preview |
| *freshness needs a date or timestamp field* | Pick a column whose type is a date or timestamp. A date stored as text needs a pattern rule instead |
| *references rules run in the database* | References need a database source. On files, use Allowed values |
| *… is not on this server's webhook allow-list* | Ask an administrator to add the address prefix |
| *this source does not allow SQL a person writes* | Ask a designer to turn on **Allow SQL** for the source, or use a library check |
| **Could not run** after a schema change | The column was renamed or dropped. Choose **Edit** and pick the new column, or switch the rule off |
