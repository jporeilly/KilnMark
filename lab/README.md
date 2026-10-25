# Reference lab

A small, fully synthetic data estate for Kilnmark to discover. It is what the regression suite
and every demo run against. Never put real or customer data here, or anything from a former
employer's labs.

## Start just what you need

Start Kilnmark first (`podman compose up -d` in the repository root: the lab joins its network),
then only the demo sources you want:

```
.\lab\lab.ps1 start sqlserver          # Windows (PowerShell)
./lab/lab.sh start postgres s3        # Linux and macOS
.\lab\lab.ps1 start all                # everything
```

| Command | What it does |
| --- | --- |
| `list` | Each demo source, whether it is running, its port and its source name in Kilnmark |
| `start <name...>` or `start all` | Starts them; the data loads on the first start |
| `stop <name...>` or `stop all` | Stops them and keeps their data |
| `status` | The lab's containers |
| `reset <name...>` | Deletes that source's data and loads it again, after asking (`-Force` in PowerShell, `--yes` in bash, to skip the question) |
| `logs <name>` | The last 100 lines of its containers' logs |
| `sources` | Where `lab/sources.csv` is, and how to import it |

Demo sources: `postgres`, `s3`, `sqlserver` and `files` (nothing to start: Kilnmark reads the
share from disk). An unknown name lists the right ones. The scripts use Podman if it is on the
PATH and Docker otherwise, need no administrator rights, and exit with a non-zero code when
something fails. `.\build.ps1 -Lab` starts every demo source after a build, and
`.\build.ps1 -Lab postgres,sqlserver` just those.

These are development scripts: they live in `lab/` and are never shipped with Kilnmark.

## Start and stop

Each demo source is a compose profile, so compose can do the same without the scripts:

```
podman compose -f lab/compose.yaml --profile sqlserver up -d   # one demo source
podman compose -f lab/compose.yaml --profile all up -d         # all of them
podman compose -f lab/compose.yaml --profile all down -v       # stop everything and wipe its data
```

(Docker: `docker compose` instead of `podman compose`.) A bare `up` with no profile now starts
nothing: before the profiles it started the whole lab, so add `--profile all` to old commands.

| Source | Where | Credentials |
| --- | --- | --- |
| PostgreSQL 18 | `localhost:5433`, database `lab` | `lab` / `lab` |
| S3-compatible (VersityGW) | `localhost:9000`, bucket `lab-raw`, path-style addressing | `labadmin` / `labadmin123` |
| File share | `lab/files/share` on disk | none |
| SQL Server 2022 Developer | `localhost,14330`, database `labdb` | `kiln_reader` / `Lab-Reader-2026` (read-only) |

These are lab-only credentials for local testing (the SQL Server logins are under
[SQL Server](#sql-server)). The `lab` login owns the lab database, which is fine for a lab; for
real sources, register a read-only login that is not a superuser (see
"Security" in docs/development.md): data quality rules run as that login, and custom SQL rules
can read anything it can see.

Kilnmark reads folders only inside the ones its administrator allows. The main `compose.yaml`
mounts `lab/files/share` read-only at `/srv/files` and allows it, so in the compose stack add a
source of kind **files** with path `/srv/files`. Running the server outside a container, set
`KILNMARK_FILES_ROOTS` to the full path of `lab/files/share` and use that path.

## Register the lab in one go

`lab/sources.csv` lists the lab sources as you see them from this machine (`localhost:5433`,
`localhost:9000`, `localhost:14330`); `./lab/lab.sh sources` prints its path. Sources you have
not started are still added, but their connection test fails until you start them. In Kilnmark,
open **Sources > Import sources from a file**, choose the file, tick **Rewrite host names for this
server** and, if you like, **Test each connection** and **Queue a scan**, then press **Import**. The compose stack's host map (`KILNMARK_SOURCES_HOST_MAP`, by default
`localhost=host.docker.internal`) turns `localhost` into the address of this machine as the container
sees it. Or from a shell:

```
curl -X POST "http://localhost:8780/api/v1/sources/import?remapHosts=true&test=true&scan=true" \
     -H "Authorization: Bearer $KILNMARK_ADMIN_TOKEN" -H "Content-Type: text/csv" --data-binary @lab/sources.csv
```

Run it again whenever you like: sources already there are left unchanged, or updated to match the file.

**One click for SQL Server.** The lab's SQL Server has a self-signed certificate, which Microsoft's
driver refuses by default, and the CSV cannot carry the driver option (bulk import turns `;` in
settings into `,`, so a SQL Server URL does not survive a `url` column). After importing, open
`lab-sqlserver`, add `;trustServerCertificate=true` to the end of its JDBC URL, save, and press
**Test**. Importing the file again puts the URL back without it, so add it again afterwards.

## What is in it

**Database** (`lab/postgres`, loaded on first start, same rows every time):

| Table | Rows | Notes |
| --- | --- | --- |
| `crm.customers` | 5,000 | Names, email, phone, date of birth, postcode, NI number, segment |
| `crm.accounts` | 7,500 | Declared foreign key to customers |
| `crm.customers_archive_2023` | 3,094 | Stale copy of older customers |
| `billing.transactions` | 100,000 | `customer_id` with **no** foreign key |
| `billing.invoices` | 20,000 | Customer id stored as text in `cust_ref` |

`SELECT lab.make_big_table(10000000);` creates a 10 million-row table for the profiling memory test.

**Bucket** (`lab/files/bucket`, served as bucket `lab-raw`):

- `exports/customers_export_2025.csv`: a CSV export of 2,000 customers (a copy of database data)
- `raw/transactions/transactions_2025-q1.ndjson`: 5,000 transactions as JSON lines
- `finance/invoices_2024_2025.parquet`: all invoices as Parquet

**File share** (`lab/files/share`): a complaint letter full of personal data, and a campaign plan
with none.

**Documents** (`documents/` in both the share and the bucket, the same five files in each), made by
`python3 lab/make-documents.py` (standard library only; run it again after changing it):

| File | What it is |
| --- | --- |
| `remote-working-policy.pdf` | Two pages, with title, author and creation date |
| `onboarding-guide.docx` | A Word document with headings, title and author |
| `depot-budget-2025.xlsx` | Sheets `Expenses` (18 rows) and `Headcount` (4 rows) are tables; `Notes` is not |
| `2025-04-delivery-schedule.eml` | A message with from, to and cc, and two attachments: a Markdown schedule and the policy PDF |
| `team-handbook.md` | Markdown with a `#` title |

`python3 lab/make-documents.py --fixtures` also remakes the legacy Word file the tests use
(`kiln-plugins/src/test/resources/documents/memo.doc`); it needs LibreOffice, since nothing in the
standard library writes .doc.

**SQL Server** (`lab/sqlserver/labdb.sql`, database `labdb`, loaded on first start, same rows
every time): a small field-service company that sells and services equipment.

| Table | Rows | Notes |
| --- | --- | --- |
| `hr.departments` | 8 | Unique `department_code` |
| `hr.employees` | 250 | Personal data. `IDENTITY` key, computed `full_name`, self-referencing `manager_id`, `money` salary, `uniqueidentifier`, unique index on email (filtered) |
| `sales.customers` | 400 | Personal data (contact name, email, phone). `datetimeoffset`, `decimal`, `bit`, unique index on `customer_code` |
| `sales.products` | 40 | `money` price, `xml` specification |
| `sales.orders` | 1,500 | Foreign keys to customers and employees, check constraint on `status` |
| `sales.order_lines` | 3,000 | Two-column primary key, persisted computed `line_total` |
| `sales.v_order_totals` | 1,500 | A view: one row per order with its total |
| `ops.equipment` | 500 | `varbinary` firmware hash, `datetime2`, unique serial number |
| `ops.work_orders` | 1,000 | Personal data (site contact). `xml` checklist, `nvarchar(max)` notes, keys from 100001 |
| `ops.customer_import_staging` | 203 | Personal data. A heap with no primary key, loaded from customer files |

Tables with personal data say so in their description (`MS_Description`), and their columns carry
SQL Server sensitivity classifications. Two first names use non-ASCII letters (Zoë, Søren).

## What discovery should find (the answer key)

| Finding | Where |
| --- | --- |
| Email, phone, date of birth, postcode, NI number | `crm.customers`, the CSV export, the complaint letter |
| Declared key on `customer_id` | `crm.accounts` to `crm.customers` |
| Undeclared join on `customer_id` | `billing.transactions` to `crm.customers` (proposed, confidence 0.99) |
| Renamed key: `cust_ref` holds customer ids as text | `billing.invoices`, the Parquet file |
| Duplicates of customer data | `crm.customers_archive_2023`, `exports/customers_export_2025.csv` |
| No personal data | `marketing/2024-q4-campaign-plan.md` |
| Duplicate documents | Each file in `share/documents` is also in the bucket's `documents/`; the policy PDF is also attached to the message |
| Business contacts only (example.com, .org, .net addresses) | `documents/2025-04-delivery-schedule.eml` |
| Name, email, phone, date of birth, salary | SQL Server `hr.employees`; contact name, email and phone in `sales.customers`, `ops.customer_import_staging` and (site contact) `ops.work_orders` |
| Null emails | SQL Server: 10 in `hr.employees`, 23 in `sales.customers`, 11 in `ops.customer_import_staging` |
| Duplicate keys, no primary key | SQL Server `ops.customer_import_staging`: customer codes `C0000040`, `C0000120` and `C0000360` twice each |
| Out-of-range dates | SQL Server: `hire_date` 2099-12-31 (employees 37, 142, 219), `date_of_birth` 1900-01-01 (employees 88, 176), `ordered_at` in 1900 (orders 404, 1212) and 2207 (order 777), `closed_at` before `opened_at` (work orders 100013, 100111, 100500, 100875) |
| Inconsistent phone formats | SQL Server: `(201) 555-0123`, `201-555-0123`, `+1 201 555 0123`, `201.555.0123` and `2015550123` side by side in every phone column |
| Declared keys | SQL Server: orders to customers and employees, lines to orders and products, equipment and work orders to customers |
| Copy of customer data | SQL Server `ops.customer_import_staging` (half of `sales.customers`) |

## SQL Server

Started with `.\lab\lab.ps1 start sqlserver` (or `./lab/lab.sh start sqlserver`). It is
Microsoft's SQL Server 2022 Developer image, the same tag the SQL Server tests pin.

- **Licence.** The image runs only with `ACCEPT_EULA=Y`, which `lab/compose.yaml` sets: starting
  the `sqlserver` demo source (or `all`) accepts Microsoft's licence terms for SQL Server
  Developer edition, which is free for development and testing and never for production
  ([terms](https://go.microsoft.com/fwlink/?linkid=857698)). If you do not accept them, leave
  `sqlserver` out.
- **Memory.** About 2 GB (`MSSQL_MEMORY_LIMIT_MB=2048`); SQL Server will not start with less than
  2 GB available. Podman's WSL machine gets half of the PC's memory by default; if Kilnmark and
  the lab do not fit, raise `memory=` under `[wsl2]` in `%UserProfile%\.wslconfig`, then
  `wsl --shutdown` and `podman machine start`. The image is x86-64 only.
- **First start.** The image is about 630 MB to download. After that the server takes about a
  minute to start and the data under 30 seconds to load: `list` shows `running (loading data)`
  until it is done, and `logs sqlserver` ends with `labdb is ready`. A few `Login failed for user
  'sa'` lines near the start of the server's log are the seed job and health check knocking before
  the server is ready; they stop by themselves. Later starts take seconds and skip the load.
- **How it loads.** SQL Server images run no start-up scripts, so a one-shot service,
  `lab-sqlserver-init`, waits for the server and runs `lab/sqlserver/labdb.sql` with the image's
  own `sqlcmd` (`lab/sqlserver/seed.sh`). It skips the load when `labdb` is there, and loads again
  if an earlier load stopped half way.
- **Reset.** `.\lab\lab.ps1 reset sqlserver` deletes the `lab_lab-sqlserver-data` volume and loads
  `labdb` again.

| Login | Password | Can |
| --- | --- | --- |
| `kiln_reader` | `Lab-Reader-2026` | Read every table and view (`db_datareader`), view definitions and sensitivity classifications. What Kilnmark scans with |
| `kiln_writer` | `Lab-Writer-2026` | Create, fill and drop tables in schema `warehouse` only, for pipeline writes |
| `sa` | `Lab-Admin-2026` | Everything; for the seed job and for you |

These passwords are for the lab only. From Kilnmark the server is
`jdbc:sqlserver://lab-sqlserver:1433;databaseName=labdb;trustServerCertificate=true`; from this
machine it is `localhost,14330` (in SQL Server Management Studio or Azure Data Studio, tick
**Trust server certificate**). The port is 14330 so it never clashes with a SQL Server you already
run. The database allows snapshot isolation, so the connector's `isolation` setting can be tried
with `snapshot`.

## Profiles: one per demo source

Every service in `lab/compose.yaml` belongs to two profiles: the demo source's name and `all`.

```
  lab-sqlserver:
    profiles: ["sqlserver", "all"]
```

A source's set-up or seed jobs share its profile (`lab-sqlserver-init` is in `sqlserver`). The
names so far: `postgres`, `s3`, `sqlserver`, with `mariadb` (MariaDB on port 3307) and `mail`
(Mailpit) coming. A source that needs nothing running, like `files`, has no profile. Ports avoid
8080, 8780 and 3306.

Adding a demo source:

1. Add its services to `lab/compose.yaml` as one block, with `profiles: ["<name>", "all"]`, a
   named volume for its data, and a seed that runs on the first start and does nothing after.
2. Add a line to the source table at the top of both `lab/lab.ps1` and `lab/lab.sh`: name,
   services, one-shot services, volumes, port, source name and description.
3. Add a row to `lab/sources.csv`, a section to this README, and a row to the coverage table below.

## Lab coverage

Every connector ships with a lab dataset (see "Definition of done" in
[docs/design/connectors.md](../docs/design/connectors.md)). The full connector list and waves are
in [docs/design/connector-catalogue.md](../docs/design/connector-catalogue.md).

| Connector | Lab service | Port | Source name | Status |
| --- | --- | --- | --- | --- |
| PostgreSQL | `lab-postgres` (profile `postgres`) | 5433 | `lab-db` | Present |
| S3-compatible object storage | `lab-s3`, VersityGW (profile `s3`) | 9000 | `lab-bucket` | Present |
| Local or network folder | none: `lab/files/share` on disk | none | `lab-share` | Present |
| Delimited, JSON and Parquet files | the bucket's `exports/`, `raw/` and `finance/` | 9000 | `lab-bucket` | Present |
| Documents and Excel | `documents/` in the file share (and the bucket) | none | `lab-share` | Present |
| SQL Server | `lab-sqlserver` (profile `sqlserver`) | 14330 | `lab-sqlserver` | Present |
| MySQL and MariaDB | MariaDB (profile `mariadb`) | 3307 | to come | On another branch |
| Oracle | | | | Lab dataset due with the connector (wave 1) |
| Generic JDBC | | | | Lab dataset due with the connector (wave 1) |
| SMB / Windows share | | | | Lab dataset due with the connector (wave 1) |
| SFTP and FTP(S) | | | | Lab dataset due with the connector (wave 1) |
| Azure Blob Storage and ADLS Gen2 | | | | Lab dataset due with the connector (wave 1): an emulator |
| XML, fixed width, Avro, compressed files | | | | Lab dataset due with the connector (wave 1) |
| REST / HTTP JSON | | | | Lab dataset due with the connector (wave 1): a mock API |
| Apache Kafka | | | | Lab dataset due with the connector (wave 1) |
| MQTT | | | | Lab dataset due with the connector (wave 1) |

## AI evaluation

`tests/ai-eval/golden.json` is a small synthetic golden set over the lab's PostgreSQL tables: 15
search queries with the tables that answer them, and 6 questions with the search plan they should
give. To score a running Kilnmark with the models it is configured to use, add the lab as a source
called `lab`, choose the AI models (see "AI models" in [docs/development.md](../docs/development.md)),
scan it, then:

```
KILNMARK_TOKEN=km_... ./gradlew :server:aiEval                      # server on http://localhost:8780
./gradlew :server:aiEval -Pkilnmark.eval.url=http://host:8780 -Pkilnmark.eval.source=lab
```

It writes `results.json` and `results.md` to `dist/build/server/ai-eval`: recall at 5 and mean
reciprocal rank (MRR) for keyword and hybrid search, and plan accuracy for asking in plain English.
It is not part of the normal build. The lab has only five tables, so MRR says more than recall.

## Synthetic identifiers

Phone numbers use Ofcom's 07700 900xxx range reserved for drama (the SQL Server data uses the North
American 555-0100 to 555-0199 range, also reserved for fiction), National Insurance numbers use
the QQ prefix that HMRC never issues, and emails use example.com, .org and .net. People's and
companies' names in the SQL Server data are made up from short invented lists.

## Licences

VersityGW, the S3 server, is Apache 2.0. It serves `lab/files/bucket` as a bucket directly, so
there is nothing to upload. (MinIO's images stopped being publicly available in 2026.) It is a
local test tool and is never shipped with Kilnmark
([ADR 0007](../docs/adr/0007-dependency-policy.md)).

SQL Server is Microsoft's, used under its Developer edition licence, which starting the
`sqlserver` demo source accepts (see [SQL Server](#sql-server)). The image is pulled from
Microsoft's registry and is never shipped with Kilnmark either.
