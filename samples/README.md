# Samples

Small pipelines and flows, one idea each, sorted by what they show; three business scenarios, each a
whole process of several pipelines and a flow; and synthetic sample data for them to read. They ship with Kilnmark: the server bundles them, and the release has the samples in
its `samples` folder. They write only under `samples/` in the file storage they are given.

## The Starter Pack

On a trial, demonstration or training install, the quickest way of all: an admin turns on the
**Starter Pack** under **Settings** (INSTALL.md, "Try Kilnmark with the Starter Pack"), and Kilnmark
does what follows in a database of its own, `kilnmark_samples`, with a folder of its own for the
files. Turning it off takes it all out again.

## The starter pack in Add samples

Otherwise, in the **Library**, on the **Samples** tab, choose **Add samples**, then under
**Starter pack** pick a database and a schema in it, and a file source for what the samples write
(both must allow writing), and choose **Add the starter pack**. Kilnmark then:

1. creates the sample tables in that schema, from its own sample data, with a pipeline called
   `sample-tables` (run it again to put them back as they were; adding the starter pack again is
   refused while they are being created, or once that pipeline has been changed);
2. adds every sample, reading those tables and writing its files to the file source;
3. sets the server variable `sample_region` to `EMEA` if it is not set, for
   `sample-server-variable`.

Every sample can then run. Through the API: `POST /api/v1/samples/tables` with
`{"source": "my-database", "schema": "public"}`, then `POST /api/v1/samples` with the sources and
`"schema": "public"`.

### The sample data

`data/` holds the sample data: twelve small tables of made-up customers, accounts, transactions,
invoices, orders, order lines, products, a supplier feed, the costs agreed for each product and the
billing periods the month-end close reads, as CSV files in a folder per group
(`crm`, `billing`, `shop`, `stock`). `data/generate.py` writes them, with a fixed seed; run it again
(`python samples/data/generate.py`) after changing it. The rows are shaped for the samples: a few
messy and repeated emails, missing emails and unknown segments for the quarantine gate (under 5%),
orders whose customer is not there, two shipping fees out of range for the gate that stops, and
repeated or badly shaped SKUs for the gate that warns and for the rows that fail, and five agreed
costs that the supplier feed moves by more than 20%, for the supplier intake scenario.

One table is large: `volume/sales.csv`, four million made-up till sales (about 216 MB of CSV). It is
not stored: Kilnmark writes it as it is read, from a fixed seed, so it is the same on every read and
adds nothing to the download. The volume sample reads it directly from the `sample-data` source.

Kilnmark reads them through a built-in, read-only source kind, **Kilnmark sample data**
(`sample-data`); the starter pack adds a source of that kind, called `sample-data`. In a database the
tables are named `<group>_<table>` in the one schema chosen (`crm_customers`, `shop_orders`, ...), so
only that schema is needed; adding the samples with it points each sample's `asset` there.

## Sources: placeholders

The samples never name a real source. They name three placeholders, and adding them maps each one
to a source you have saved:

| Placeholder | Stands for | Used by |
| --- | --- | --- |
| `crm-db` | A database with schemas `crm` (customers, customers_archive_2023, accounts) and `billing` (transactions, invoices, periods) | read and write, Parquet, read some columns and rows, read with a query (PostgreSQL with Allow SQL on), limit, compute, functions, dedupe, which row wins, window, union, quarantine gate; the flow that checks a source |
| `shop-db` | A database with schemas `shop` (customers, orders, order_lines) and `stock` (products, supplier_feed, product_costs) | filter, route, select, sort, lookup, join, which row wins, both aggregates, warn and stop gates, rows that fail, both parameter samples; the sql flow |
| `files` | File storage the samples write to, under `samples/`; it must allow writing | every pipeline, and the flow that waits for a file |

A placeholder you do not map skips the samples that use it. Each sample is checked against what
Kilnmark has scanned of your sources, so scan them first: a table a sample reads that the scan did
not find saves it as a draft, with problems saying what is missing. Open it and change what it reads.

## Templates

`templates/` holds templates: pipelines and flows to start your own from, such as copying a table,
loading a file, a quality gate, an incremental load and running pipelines in order. They are not
samples: they have `<...>` where your own source, table or pipeline goes, and they are never added
as they are. In Kilnmark, open the **Library**, **Templates**, and choose **Use this template**: it
opens in the editor as a new pipeline or flow of your own. `GET /api/v1/templates` lists them.

## Adding them with your own sources

- **In Kilnmark:** in the **Library**, on the **Samples** tab, choose **Add samples** (designers and admins), and under
  **Or choose your own sources** pick one of your saved sources for each placeholder, and choose
  **Add samples**. Once the sample tables have been created, the dialog offers to read them. Through
  the API:
  `GET /api/v1/samples` lists the placeholders and samples, and `POST /api/v1/samples` with
  `{"sources": {"crm-db": "my-database", "shop-db": "my-other-database", "files": "my-bucket"}}` adds
  them.
- **With the reference lab** (from source, see [lab/README.md](../lab/README.md)): start the lab
  (`./lab/lab.sh start all`, Windows `.\lab\lab.ps1 start all`), import `lab/sources.csv` under
  **Sources** and let the scans of `lab-db` and `lab-mariadb` finish. Then, with
  `KILNMARK_ADMIN_TOKEN` (or `KILNMARK_TOKEN`) set to an admin or steward token, run
  `./lab/lab.sh samples` (Windows: `.\lab\lab.ps1 samples`). It maps the placeholders to the lab's
  sources as [lab/sample-sources.csv](../lab/sample-sources.csv) says: `crm-db` to `lab-db`,
  `shop-db` to `lab-mariadb` and `files` to `lab-warehouse`.

Either way, a sample already there is left as it is, so your changes are kept. The flows are
added after the pipelines they run; a flow whose pipelines were not all added is
skipped, saying which it needs.

For `07-parameters/server-variable.yaml`, set the server variable it reads (as a steward or admin;
the starter pack does this for you):
`PUT /api/v1/variables/sample_region` with `{"type": "text", "value": "EMEA"}`. Without it, that
sample is saved as a draft whose problem says the variable is not set.

Then open **Pipelines**: every sample is named `sample-...`. Open one, look at it on the canvas,
preview a node, and choose **Run now**.

## The samples

**Who they are for:** data engineers learning how Kilnmark builds pipelines and flows, data stewards
seeing how quality checks and their evidence work, and anyone evaluating Kilnmark for banking or
other regulated work. Each sample's **Why it matters** says, in a few words, the real need it meets;
the full text is in each file and in the Library. The data is made up: no row comes from a real
bank or person.

| Folder | Sample | What it shows | Why it matters |
| --- | --- | --- | --- |
| `01-read-and-write` | `sample-read-write-csv` | Read a table, write a CSV file (`mode: replace`) | Handing a table to another team as a file, with the rows counted |
| | `sample-write-parquet` | Write Parquet (`format: parquet`) | A typed, compressed file for a data lake or analytics platform |
| | `sample-read-columns-where` | Read only some columns and rows: `columns`, `where`, and who applies it | Data minimisation (GDPR; HIPAA's minimum necessary in US healthcare): unused personal data stays at the source |
| | `sample-read-query` | Read with one `SELECT` (`query`); needs a PostgreSQL `crm-db` with Allow SQL on | Letting the database join and count, without a way round masking |
| `02-filter-and-route` | `sample-filter` | Keep rows meeting a condition; the rest through `rejected` | Picking out a segment, with nothing lost without trace |
| | `sample-route` | Send rows different ways, the first route that fits winning | Different handling by size or kind, such as large payments looked at twice |
| | `sample-limit` | Pass on the first rows only, and stop reading early | A cheap first run on a few rows |
| `03-transform` | `sample-compute` | New and tidied columns with expressions | Untidy names and emails made alike once, for every match after |
| | `sample-select` | Keep, retype, rename and order columns | A file or table in the layout another system expects |
| | `sample-dedupe` | One row per key; the repeats through `rejected` | Duplicate customers (or patients) break single-customer views that KYC reporting often relies on |
| | `sample-sort` | Rows in order: several columns, largest first, empty values last | Statements and reports in a set order, the same on every run |
| | `sample-functions` | Tidy values and work out new ones: `lpad`, `split_part`, `left` and `right`, `date_add` and `date_diff`, `greatest` and `least`, `if`, `contains` and `starts_with`, and a `sha256` of the email as a key, which stays marked sensitive | A hashed email or patient identifier as a join key, still marked sensitive |
| | `sample-which-row` | Which row of a key wins: `duplicates: latest` with `duplicates_by` on a dedupe (the latest customer per email) and on a lookup (each product's latest delivery price) | The current version of a record, such as a customer's latest address |
| | `sample-window` | A running balance per account, and each account's latest row: row number 1, then a filter | Each account's latest and running balance, for statements and end-of-day positions |
| `04-combine` | `sample-lookup` | Add columns from another input, like a join; no match through `rejected` | Reference data added, with a missing reference shown, not left empty |
| | `sample-union` | The rows of several inputs together | Current and archived records together, for a review or remediation |
| | `sample-join` | Two inputs side by side by key: a full join, with the rows that have no partner | Reconciling two systems: what is on one side and missing on the other |
| `05-aggregate` | `sample-aggregate` | One row per group: counts, sums and distinct counts | Totals that add up to the detail, as aggregation reviews check |
| | `sample-catalogue-summary` | A summary per category, with an expression over the group | A first look at a dataset, and a check that a load looks right |
| `06-quality` | `sample-gate-quarantine` | A quality gate setting failing rows aside, with a threshold | Bad rows kept for review, so data-quality controls have evidence |
| | `sample-gate-warn` | A gate that only records what failed | A faulty feed loaded anyway, with a list to send back |
| | `sample-gate-stop` | A gate that stops the run before anything is written (it is meant to) | Data that must never be loaded wrong, such as fees customers are charged |
| | `sample-rows-that-fail` | Rows whose cast fails set aside on the `errors` port, into a quarantine file, with a tolerance | Every rejected row kept with its reason, so a load can be reconciled; one bad value no longer stops the run |
| `07-parameters` | `sample-orders-by-status` | Parameters: a text list in the read node's `where: status in ${statuses}`, a number, a label in the file name | One pipeline instead of many near-copies, so a change is reviewed once |
| | `sample-server-variable` | A server variable (`${vars.sample_region}`) as a parameter's default | One version for development, test and production |
| `08-flows` | `sample-quality-decides` | A flow: a condition on what a gate set aside decides what runs next; notify and stop | Reports wait when their inputs fail their checks |
| | `sample-passing-values` | A flow passing values on: inheritance by name, `with:`, a node's value, wait and notify | Every pipeline in a batch on the same business date or batch name |
| | `sample-sql-before-and-after` | A flow's `sql` nodes before and after a pipeline, one transaction each, with `rows_affected` in a condition (needs `shop-db` with Allow SQL and Allow writing) | A warehouse table emptied and refreshed around a load, repeatably |
| | `sample-retries` | Trying again only where it is safe: a `run:` block (`retries`, `retry_delay`, `retry_on: lost`, `timeout`, `warn_after`) and a node's own `timeout` and `retries`, which never multiply with the flow's | Overnight batches through brief outages, never loading anything twice |
| | `sample-checks-and-waits` | A `check-source` node before the work, a `wait-for` node waiting for a file, and `priority` on two pipelines | A clear notice at the start when what a batch needs has not arrived |
| | `sample-for-each-region` | A `for-each` node: `sample-server-variable` once for each of three regions, two at once, each writing its own file; a condition on `regions.failed` | The same load for each region, legal entity, branch or area |
| `09-volume` | `sample-volume` | Four million rows through a compute, a lookup, a route and an aggregate, long enough to see where a run's time goes: the run's page shows each node's time, rows per second and which node held the others up | Trying the platform on volumes like your own, for example when moving from another ETL tool |
| `10-scenarios` | `sample-onboarding-merge` | Customer onboarding: three customer tables in one golden record per email, the latest winning; invalid emails quarantined for the steward | One current record per customer, which KYC and consent reporting often rely on, with the rows that could not be matched kept for review |
| | `sample-onboarding-pseudonymise` | Customer onboarding: a copy for analytics with hashes, an age band and a postcode area in place of the identifiers, after a gate that checks the golden file's own rules | A typical GDPR safeguard: analysts get data they need without who it is about |
| | `sample-onboarding-record` | Customer onboarding: a record of each load, counted by source system | How many records a nightly load produced, and when, without reading the data |
| | `sample-onboarding-nightly` | Customer onboarding, the flow: checks the tables, merges, then the analytics copy and the record; tells the stewards and stops when too many rows were set aside | A nightly load that stops and says so before anything downstream uses it |
| | `sample-supplier-feed-arrives` | Supplier intake: stands in for the supplier, delivering its feed to the incoming folder | A partner's file landing in a shared folder at a time nobody controls |
| | `sample-supplier-check` | Supplier intake: each SKU's latest delivery looked up among the products and their agreed costs; unknown products and costs that are not positive quarantined, costs moved by more than 20% sent for price review | A partner's prices checked before they reach invoices or valuations |
| | `sample-supplier-load` | Supplier intake: the agreed cost of every product, with the accepted new costs in place | Only checked data changes the prices a business relies on |
| | `sample-supplier-intake` | Supplier intake, the flow: waits for the file, checks it, and loads it only if nothing was held for review; otherwise tells the reviewers | A person decides what happens to what a check would not accept |
| | `sample-close-reconcile` | Month-end close: one region's invoices matched with its customers' payments; paid, underpaid and unmatched | Billed against received, a core month-end control, per region so each exception has an owner |
| | `sample-close-summary` | Month-end close: every region's exceptions in one worklist, largest first, and a summary by region and kind | The summary a close is signed off on, with each exception listed for someone to clear |
| | `sample-close-month-end` | Month-end close, the flow: waits for a query on the billing periods table to be true, reconciles each region with `for-each`, then marks the month closed with a `sql` node (needs `crm-db` with Allow SQL and Allow writing) | A close that starts only when the invoices are out, and is marked done only when every region reconciled |
The scenarios in `10-scenarios` are whole business processes rather than one idea each: each has
several pipelines and the flow that runs them, and a step for a data steward. Who does what in each,
with the Starter Pack's example people, is in [Business scenarios](../docs/guide/scenarios.md).

The [pipelines guide](../docs/guide/pipelines.md),
[Reference: pipeline nodes](../docs/guide/reference-pipeline-nodes.md),
[Expressions](../docs/guide/expressions.md) and
[Reference: flow nodes](../docs/guide/reference-flow-nodes.md) describe every setting.

## How each sample explains itself

Each sample and template starts with a comment in four parts, and the **Library** shows them on
the sample's row (**About this sample**) and on the template's card:

```yaml
# Filter: keep some rows, set the rest aside (a Kilnmark sample).
# Keeps the active gold and platinum shop customers in one file. Every other customer goes to a
# second file through the filter's rejected output, so no row is dropped without trace.
# Why it matters: Picking out a segment, such as active premium customers for a campaign or a
# review, is everyday work. Keeping the rest in their own file means nothing is lost without trace,
# which reviewers often ask to see.
# Look for: the filter's two outputs, out and rejected, and how many rows each received.
# Needs: shop-db (the shop schema) and files.
kilnmark: pipeline/v1
```

- The first line is the title.
- **What it shows** (for a template, what it is for): the lines after the title, two or three
  plain sentences.
- **Why it matters**: the real need it meets and who typically has it, starting
  `# Why it matters:`, one to three sentences. Lead with banking and other regulated work. Say
  "often", "typically" or "for example": never state a purpose as fact for an organisation. Name a
  regulation only where the link is direct and well known (BCBS 239 for aggregating risk data,
  GDPR for keeping personal data to a minimum, SOX for evidence of changes, HIPAA for health
  data in the US), and never a clause. For
  a template, say who typically starts from it, and why.
- **Look for**: what to notice when it runs, starting `# Look for:`.
- **Needs**: the placeholders, settings and rights it relies on, starting `# Needs:`.

A part runs until the next one starts, and its lines are joined into sentences. A line indented
under the `#` (such as an API call to copy) keeps a line of its own. The comment is the one place an
explanation is written: `GET /api/v1/samples` and `GET /api/v1/templates` give each one's
`explanation` as read from it, and `SamplesExplainedTest` fails the build if a sample or template
lacks any of the four parts.

## Try it with other values

`sample-orders-by-status` takes values through the API:

```
POST /api/v1/pipelines/sample-orders-by-status/runs
{"parameters": {"statuses": ["shipped", "delivered"], "min_fee": 5, "label": "both"}}
```

It writes `samples/parameters/orders_both.csv`. A value only ever fills a value: try
`"label": "../other"` and the run is refused, as the label may hold only letters, digits, `_` and
`-` in where a write goes.

## Adding a sample

Put a file in the folder for what it shows, name it `sample-...`, read only from `crm-db` and
`shop-db` and write only to `files` under `samples/`, and start it with its title, what it shows,
`# Why it matters:`, `# Look for:` and `# Needs:` (above), and add it to the table above. The
samples ship, so they never name the lab or a lab source; if a sample needs a new placeholder, add it to `Samples.PLACEHOLDERS` (server) and to `lab/sample-sources.csv`. Read only
the tables in `data/`; if a sample needs rows of a new shape, or a new table, change
`data/generate.py` and run it (a new table also goes in `Samples.TABLES`).
`LabPipelinesTest` (server tests) checks every sample, mapped to the lab's sources, against the
lab's columns, and every flow against the sample pipelines; `SampleTablesTest` checks every sample
against the sample data's own columns, and `SampleTablesApiTest` runs them on it. So a sample that
stops working fails the build; `checkProductOnly` fails the release if a shipped sample, or the
sample data, names the lab.
