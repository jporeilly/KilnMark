# Samples

Small pipelines and flows, one idea each, sorted by what they show, and synthetic sample data for
them to read. They ship with Kilnmark: the server bundles them, and the release has the samples in
its `samples` folder. They write only under `samples/` in the file storage they are given.

## The starter pack

The quickest way to try them: on the **Pipelines** page, choose **Add samples**, then under
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

`data/` holds the sample data: ten small tables of made-up customers, accounts, transactions,
invoices, orders, order lines, products and a supplier feed, as CSV files in a folder per group
(`crm`, `billing`, `shop`, `stock`). `data/generate.py` writes them, with a fixed seed; run it again
(`python samples/data/generate.py`) after changing it. The rows are shaped for the samples: a few
messy and repeated emails, missing emails and unknown segments for the quarantine gate (under 5%),
orders whose customer is not there, two shipping fees out of range for the gate that stops, and
repeated or badly shaped SKUs for the gate that warns.

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
| `crm-db` | A database with schemas `crm` (customers, customers_archive_2023, accounts) and `billing` (transactions, invoices) | read and write, limit, compute, dedupe, union, quarantine gate |
| `shop-db` | A database with schemas `shop` (customers, orders, order_lines) and `stock` (products, supplier_feed) | filter, route, select, lookup, both aggregates, warn and stop gates, both parameter samples |
| `files` | File storage the samples write to, under `samples/`; it must allow writing | every pipeline |

A placeholder you do not map skips the samples that use it. Each sample is checked against what
Kilnmark has scanned of your sources, so scan them first: a table a sample reads that the scan did
not find saves it as a draft, with problems saying what is missing. Open it and change what it reads.

## Adding them with your own sources

- **In Kilnmark:** on the **Pipelines** page, choose **Add samples** (stewards and admins), and under
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

Either way, a sample already there is left as it is, so your changes are kept. The flows in
`08-flows` are skipped until the flows API comes; read them, and run them through the API once it
does.

For `07-parameters/server-variable.yaml`, set the server variable it reads (as a steward or admin;
the starter pack does this for you):
`PUT /api/v1/variables/sample_region` with `{"type": "text", "value": "EMEA"}`. Without it, that
sample is saved as a draft whose problem says the variable is not set.

Then open **Pipelines**: every sample is named `sample-...`. Open one, look at it on the canvas,
preview a node, and choose **Run now**.

## The samples

| Folder | Sample | What it shows |
| --- | --- | --- |
| `01-read-and-write` | `sample-read-write-csv` | Read a table, write a CSV file (`mode: replace`) |
| | `sample-write-parquet` | Write Parquet (`format: parquet`) |
| | `sample-read-columns-where` | Read only some columns and rows: `columns`, `where`, and who applies it |
| `02-filter-and-route` | `sample-filter` | Keep rows meeting a condition; the rest through `rejected` |
| | `sample-route` | Send rows different ways, the first route that fits winning |
| | `sample-limit` | Pass on the first rows only, and stop reading early |
| `03-transform` | `sample-compute` | New and tidied columns with expressions |
| | `sample-select` | Keep, retype, rename and order columns |
| | `sample-dedupe` | One row per key; the repeats through `rejected` |
| `04-combine` | `sample-lookup` | Add columns from another input, like a join; no match through `rejected` |
| | `sample-union` | The rows of several inputs together |
| `05-aggregate` | `sample-aggregate` | One row per group: counts, sums and distinct counts |
| | `sample-catalogue-summary` | A summary per category, with an expression over the group |
| `06-quality` | `sample-gate-quarantine` | A quality gate setting failing rows aside, with a threshold |
| | `sample-gate-warn` | A gate that only records what failed |
| | `sample-gate-stop` | A gate that stops the run before anything is written (it is meant to) |
| `07-parameters` | `sample-orders-by-status` | Parameters: a text list in the read node's `where: status in ${statuses}`, a number, a label in the file name |
| | `sample-server-variable` | A server variable (`${vars.sample_region}`) as a parameter's default |
| `08-flows` | `sample-quality-decides` | A flow: a condition on what a gate set aside decides what runs next; notify and stop |
| | `sample-passing-values` | A flow passing values on: inheritance by name, `with:`, a node's value, wait and notify |
| `09-volume` | `sample-volume` | Four million rows through a compute, a lookup, a route and an aggregate, long enough to see where a run's time goes: the run's page shows each node's time, rows per second and which node held the others up |

Each file starts with what it shows and what to look for. The
[pipelines guide](../docs/guide/pipelines.md) and
[Pipeline nodes and expressions](../docs/guide/pipeline-nodes.md) describe every setting.

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
`shop-db` and write only to `files` under `samples/`, and start it with what it shows and what to
look for. The samples ship, so they never name the lab or a lab source; if a sample needs a new
placeholder, add it to `Samples.PLACEHOLDERS` (server) and to `lab/sample-sources.csv`. Read only
the tables in `data/`; if a sample needs rows of a new shape, or a new table, change
`data/generate.py` and run it (a new table also goes in `Samples.TABLES`).
`LabPipelinesTest` (server tests) checks every sample, mapped to the lab's sources, against the
lab's columns, and every flow against the sample pipelines; `SampleTablesTest` checks every sample
against the sample data's own columns, and `SampleTablesApiTest` runs them on it. So a sample that
stops working fails the build; `checkProductOnly` fails the release if a shipped sample, or the
sample data, names the lab.
