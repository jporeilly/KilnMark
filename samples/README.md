# Samples

Small pipelines and flows, one idea each, sorted by what they show. They run on the reference
lab's synthetic data and write only to the lab's warehouse bucket, under `samples/`. Like the lab,
they are never shipped: they are for learning and demonstrating Kilnmark on a developer machine.

## Before you start

1. Start the lab and add its sources, as [lab/README.md](../lab/README.md) describes:
   `./lab/lab.sh start all` (Windows: `.\lab\lab.ps1 start all`), import `lab/sources.csv` under
   **Sources**, and let the scans of `lab-db` and `lab-mariadb` finish, so Kilnmark knows their
   columns.
2. For `07-parameters/server-variable.yaml`, set the server variable it reads (as a steward or admin):
   `PUT /api/v1/variables/sample_region` with `{"type": "text", "value": "EMEA"}`. Without it, that
   sample is saved as a draft whose problem says the variable is not set.
3. Add the samples to Kilnmark. With `KILNMARK_ADMIN_TOKEN` (or `KILNMARK_TOKEN`) set to an admin or
   steward token, run `./lab/lab.sh samples` (Windows: `.\lab\lab.ps1 samples`). A sample already
   there is left as it is, so your changes are kept. The flows in `08-flows` are skipped until the
   flows API comes; read them, and run them through the API once it does.

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

Put a file in the folder for what it shows, name it `sample-...`, read only lab sources and write
only to `lab-warehouse` under `samples/`, and start it with what it shows and what to look for.
`LabPipelinesTest` (server tests) checks every sample against the lab's columns, and every flow
against the sample pipelines, so a sample that stops working fails the build.
