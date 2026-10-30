# Samples

Small pipelines and flows, one idea each, sorted by what they show. They ship with Kilnmark as
templates: the server bundles them, and the release has them in its `samples` folder. They write
only under `samples/` in the file storage they are given.

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

## Adding them

- **In Kilnmark:** on the **Pipelines** page, choose **Add samples** (stewards and admins), pick one
  of your saved sources for each placeholder, and choose **Add samples**. Through the API:
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

For `07-parameters/server-variable.yaml`, set the server variable it reads (as a steward or admin):
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
placeholder, add it to `Samples.PLACEHOLDERS` (server) and to `lab/sample-sources.csv`.
`LabPipelinesTest` (server tests) checks every sample, mapped to the lab's sources, against the
lab's columns, and every flow against the sample pipelines, so a sample that stops working fails
the build; `checkProductOnly` fails the release if a shipped sample names the lab.
