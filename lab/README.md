# Reference lab

A small, fully synthetic data estate for Kilnmark to discover. It is what the regression suite
and every demo run against. Never put real or customer data here, or anything from a former
employer's labs.

The lab is for development, testing and demos only. It is never shipped, and nothing that ships
(server and engine code, the web UI, production defaults, the release's `compose.yaml` or the user
guides) names it or depends on it: `./gradlew release` checks this
([ADR 0024](../docs/adr/0024-the-lab-is-not-the-product.md)). Lab walkthroughs belong here or in
[docs/development.md](../docs/development.md), never in `docs/guide`.

## A demo from start to finish

About 30 minutes the first time, most of it waiting for images to download. The commands are for
Windows PowerShell; on Linux and macOS use `./lab/lab.sh` with the same arguments, and `export`
instead of `$env:`.

1. **Start Kilnmark and the demo sources.** In the repository root:

   ```
   .\build.ps1 -Lab postgres,s3,mariadb,mail
   ```

   This builds Kilnmark, starts it on http://localhost:8780 and starts four demo sources: the
   PostgreSQL CRM and billing database, the S3 server with the `lab-raw` and `lab-warehouse`
   buckets, the MariaDB shop and Mailpit. If Kilnmark is already built, `podman compose up -d`
   then `.\lab\lab.ps1 start postgres s3 mariadb mail` does the same. `.\lab\lab.ps1 list` shows
   what is running.

2. **Sign in.** Use your admin token, or read the first one from the server (it lasts 24 hours;
   see "Sign in" in [docs/development.md](../docs/development.md)):

   ```
   podman compose exec kilnmark cat /var/lib/kilnmark/admin-token
   ```

   Keep it in `$env:KILNMARK_ADMIN_TOKEN` for step 5.

3. **Register the demo sources and scan them.** **Sources > Import sources from a file**, choose
   `lab\sources.csv`, tick **Rewrite host names for this server**, **Test each connection** and
   **Queue a scan**, and press **Import** (more in [Register the lab in one go](#register-the-lab-in-one-go)).
   SQL Server and Oracle show a failed connection test because they are not started; that is
   expected. Wait until **Sources** shows the scans of `lab-db`, `lab-bucket` and `lab-mariadb` as
   finished, a minute or two.

4. **Show what discovery found.** [What discovery should find](#what-discovery-should-find-the-answer-key)
   is the answer key. Good stops:
   - **Browse > lab-db > crm.customers**: personal data found in the columns (email, phone, date
     of birth, NI number), each column's profile, and its quality score.
   - **Relationships**: the join from `billing.transactions` to `crm.customers` that no foreign
     key declares, proposed with its confidence; accept it.
   - **Quality** on `crm.customers`: **Recommended from the profile** lists rules the data already
     obeys; add a not-null rule on `email` and run it to see the 1% or so of customers with no email.
   - **Search** (Ctrl+K): type `email` to find every email column in the lab, in both databases,
     the CSV export and the bucket.

5. **Add the demo pipelines.** With the token from step 2:

   ```
   .\lab\lab.ps1 pipelines
   ```

   **Pipelines** now lists `demo-customers-clean`, `demo-customer-spend` and `demo-supplier-feed`
   (see [Demo pipelines](#demo-pipelines)). To add one by hand instead, choose **New pipeline**, open the
   **YAML** tab and paste the file.

6. **Run the quality gate.** Open `demo-customers-clean`. On the **Canvas** tab, it reads
   `crm.customers`, tidies the emails, checks every row at the gate **check**, and writes the rows
   that pass to `demo/customers_clean.parquet` and the rest to `demo/customers_quarantine.csv`, both
   in the `lab-warehouse` bucket. Select **check** to show its rules in the settings panel. Choose
   **Run now**. When the run finishes, about 4,750 customers have passed and about 250 are
   quarantined (no email or no date of birth), and each write shows its measured quality: high for
   the clean file, low for the quarantine. **Runs > Run 1** says the same in a table, with each
   rule's result.

7. **Stop a run at the gate.** Select **check**, set **When rows fail** (`on_fail`) to **stop**, save
   with the message "Stop on bad rows", and run again. (Kilnmark notes that `aside` gets no rows from
   a stop gate; that is expected here.) The run fails, naming the rule and its counts, and neither
   file is written: the two files from the first run stay as they were.
   With email set up (step 9), a **Pipeline run failed** email arrives too. Set it back to
   **quarantine** afterwards.

8. **The other two pipelines.**
   - `demo-customer-spend` adds up 100,000 transactions per customer (aggregate: 2,500 customers
     have transactions, 40 each), adds each customer's name and segment (lookup), and sends the big
     spenders, £12,000 or more, to a file of their own (route).
   - `demo-supplier-feed` reads the MariaDB shop's supplier price feed, which has no primary key and
     three SKUs twice, and quarantines the repeats: the first row of each SKU passes.

   After any run, **Browse > lab-warehouse** lists the files the pipelines wrote, with where each came
   from.

9. **Email alerts (optional).** Follow [Try email notifications](#try-email-notifications), then
   under **Settings > Notifications** choose **Pipeline run failed**, and repeat step 7. Mailpit at http://localhost:8025 shows the email.

10. **Reset for the next demo.** Empty the warehouse bucket (see
    [Somewhere for pipelines to write](#somewhere-for-pipelines-to-write)), then
    `.\lab\lab.ps1 stop all`. The demo sources keep their data; `.\lab\lab.ps1 reset <name>` loads it
    again from scratch.

## Demo pipelines

`lab/pipelines` holds pipelines made for the lab. `.\lab\lab.ps1 pipelines` (or `./lab/lab.sh
pipelines`) adds them all to a running Kilnmark through its API. It needs an admin or steward
token in `KILNMARK_ADMIN_TOKEN` (or `KILNMARK_TOKEN`), and `KILNMARK_URL` when Kilnmark is not
on http://localhost:8780. A pipeline that is already there is left as it is, so changes you make
in Kilnmark are kept. The bash script needs `python3`.

| File | Pipeline | Demo sources | What it shows |
| --- | --- | --- | --- |
| `customers-clean.yaml` | `demo-customers-clean` | `postgres`, `s3` | A quality gate setting failing rows aside (`on_fail: quarantine`), measured quality per output; change it to `stop` to see a gate stop a run |
| `customer-spend.yaml` | `demo-customer-spend` | `postgres`, `s3` | Aggregate, lookup and route, two reads feeding one pipeline, Parquet and CSV output |
| `supplier-feed.yaml` | `demo-supplier-feed` | `mariadb`, `s3` | A gate on another database: `unique` quarantining repeated keys in a table with no primary key |

All three read sources that `lab/sources.csv` registers and write only to the `lab-warehouse`
bucket, under `demo/`. They need the sources scanned first, so that Kilnmark knows their columns.
`LabPipelinesTest` (server tests) checks each file against the lab's columns, so a change to the
steps that would break a demo fails the build. Add a demo by putting a file here whose `name`
starts with `demo-`.

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
| `driver oracle` | Downloads Oracle's JDBC driver after showing its licence, checks it and copies it into a running Kilnmark (see [Oracle](#oracle)) |
| `pipelines` | Adds the demo pipelines in `lab/pipelines` to a running Kilnmark (see [Demo pipelines](#demo-pipelines)) |

Demo sources: `postgres`, `s3`, `sqlserver`, `mariadb`, `oracle`, `mail` and `files` (nothing to start: Kilnmark reads the
share from disk). An unknown name lists the right ones. The scripts use Podman if it is on the
PATH and Docker otherwise, need no administrator rights, and exit with a non-zero code when
something fails. `.\build.ps1 -Lab` starts every demo source after a build, and
`.\build.ps1 -Lab postgres,sqlserver` just those.

These are development scripts: they live in `lab/` and are never shipped with Kilnmark.

### Port already in use

Each demo source publishes a fixed port on this machine (the table below). `start` checks it first
and stops with the name of whatever already holds it, for example `s3: port 9000 is already in use
by the container minio`: another container or program is listening there. Stop that one, then start
the demo source again. To see for yourself:

```
podman ps --format "{{.Names}}  {{.Ports}}"                       # a container publishing it
Get-NetTCPConnection -LocalPort 9000 -State Listen |
  ForEach-Object { Get-Process -Id $_.OwningProcess }             # Windows: the program
lsof -nP -iTCP:9000 -sTCP:LISTEN                                  # Linux and macOS
```

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
| S3-compatible (VersityGW) | `localhost:9000`, buckets `lab-raw` and `lab-warehouse`, path-style addressing | `labadmin` / `labadmin123` |
| File share | `lab/files/share` on disk | none |
| Mail server (Mailpit), demo source `mail` | SMTP `localhost:1025`, web page http://localhost:8025 | none (any user name and password are accepted) |
| SQL Server 2022 Developer | `localhost,14330`, database `labdb` | `kiln_reader` / `Lab-Reader-2026` (read-only) |
| MariaDB 11.4 | `localhost:3307`, databases `shop` and `stock` | `kiln_reader` / `Lab-Reader-2026` (read-only) |
| Oracle Database Free 23ai | `localhost:1522`, service `FREEPDB1`, schemas `FLEET` and `FREIGHT` | `kiln_reader` / `Lab-Reader-2026` (read-only) |

These are lab-only credentials for local testing (the SQL Server logins are under
[SQL Server](#sql-server), the MariaDB ones under [MariaDB](#mariadb), the Oracle ones under
[Oracle](#oracle)). The `lab` login owns the lab database, which is fine for a lab; for
real sources, register a read-only login that is not a superuser (see
"Security" in docs/development.md): data quality rules run as that login, and custom SQL rules
can read anything it can see.

Kilnmark reads folders only inside the ones its administrator allows. The developer
`compose.yaml` at the root of the repository mounts `lab/files/share` read-only at `/srv/files` and allows it, so in the compose stack add a
source of kind **files** with path `/srv/files`. Running the server outside a container, set
`KILNMARK_FILES_ROOTS` to the full path of `lab/files/share` and use that path.

## Try email notifications

Mailpit, the demo source `mail`, catches every email Kilnmark sends and shows it on a web page;
nothing is delivered anywhere. It has the compose profile `mail` (and `all`), so it starts only
when asked:

```
podman compose -f lab/compose.yaml --profile mail up -d     # Mailpit alone (lab.sh / lab.ps1: start mail)
podman compose -f lab/compose.yaml --profile all up -d      # the whole lab with it
```

In Kilnmark, as an admin, open **Settings > Email** and enter:

| Field | Value |
| --- | --- |
| SMTP server | `lab-mailpit` (from the compose stack), or `localhost` for a server outside a container |
| Port | `1025` |
| Security | None |
| User name / password | empty: with security None, Kilnmark refuses to send a password unless **Allow sign-in without encryption** is on |
| From address | `kilnmark@example.com` |
| Address of Kilnmark | `http://localhost:8780` |

Press **Save mail settings**, then **Send a test email** to any example.com address, and open
http://localhost:8025 to read it. Then choose emails for yourself under **Settings >
Notifications**: give an example.com address, open the confirmation email in Mailpit and follow its
link (or type its code), then add the lab with a wrong password and test its connection, for
example, and the alert arrives in Mailpit. Use example.com addresses only; Mailpit keeps the last 500 emails until
the lab is stopped with `down -v`.

## Register the lab in one go

`lab/sources.csv` lists the lab sources as you see them from this machine (`localhost:5433`,
`localhost:9000`, `localhost:14330`, `localhost:3307`, `localhost:1522`; `lab-warehouse` is a bucket on the same server as `lab-bucket`); `./lab/lab.sh sources` prints its path. Sources you have
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

The lab's SQL Server has a self-signed certificate, which Microsoft's driver refuses by default,
so its row sets `trust_server_certificate` to `true` (**Trust the server certificate** on the
source's page). Nothing needs editing after the import. Only turn that setting on for test servers.

## Somewhere for pipelines to write

The lab's data is what discovery is tested against, so `lab-db`, `lab-bucket`, `lab-share`, `lab-sqlserver`, `lab-mariadb` and `lab-oracle` stay
read-only to pipelines: the file registers them without **Allow writing**. Pipelines write to
`lab-warehouse` instead, a second bucket on the same VersityGW server, with the same endpoint and
credentials as `lab-bucket`.

- **It starts empty.** It is a volume of the S3 container, not a folder in the repository, so
  nothing a pipeline writes lands in `lab/files`. It holds no synthetic data and is not part of the
  answer key below.
- **Writing is already allowed.** `lab/sources.csv` sets `allow_writing` to `true` for
  `lab-warehouse` only, and import is for admins, who are the only ones allowed to set it. Importing
  the file again sets it again, and turns it off on the read-only sources if someone turned
  it on.
- **Files go to the bucket.** There is no writable folder: the file share is mounted read-only, and
  a folder the server (a non-root user in its container) could write would need its owner changed
  on this machine. Write CSV and Parquet files to `lab-warehouse` with a `path`, such as
  `exports/customer-sample.csv`.

To reset it, empty the bucket, then scan `lab-warehouse` so the catalogue drops the files that are
gone:

```
podman compose -f lab/compose.yaml exec lab-s3 sh -c 'rm -rf /data/lab-warehouse/*'
# or with any S3 client, for example the AWS CLI:
AWS_ACCESS_KEY_ID=labadmin AWS_SECRET_ACCESS_KEY=labadmin123 AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url http://localhost:9000 s3 rm s3://lab-warehouse --recursive
```

Stopping the lab with `down -v` and starting it again also gives an empty bucket.

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

**MariaDB** (`lab/mariadb/01-shop.sql`, databases `shop` and `stock`, loaded on first start, same
rows every time): an online shop selling home and garden goods, using MySQL's own types (`UNSIGNED`
integers, `ENUM`, `SET`, `JSON`, `YEAR`, `TINYINT(1)`, `DATETIME(3)`, `BLOB`).

| Table | Rows | Notes |
| --- | --- | --- |
| `shop.customers` | 600 | Personal data (name, email, phone, date of birth, postcode). `TINYINT(1)` and `BOOLEAN` flags, `ENUM` loyalty tier, unique customer code |
| `shop.orders` | 2,000 | Foreign key to customers. `ENUM` status, `SET` channel, `DATETIME(3)`, unsigned `DECIMAL` |
| `shop.order_lines` | 4,000 | Two-column primary key; `product_id` names a product in `stock` with no declared key |
| `shop.v_order_totals` | 2,000 | A view: one row per order with its total |
| `stock.warehouses` | 4 | `YEAR` opened |
| `stock.products` | 120 | `SET` tags, `JSON` attributes, `YEAR` launch year, `BLOB` image for a few, unique SKU |
| `stock.stock_levels` | 480 | Two-column key, foreign keys to products and warehouses |
| `stock.stock_movements` | 3,000 | Receipts, picks, transfers and adjustments, with signed quantities |
| `stock.supplier_feed` | 123 | A heap with no primary key, loaded from a supplier |

The database `warehouse` is empty, for pipelines to write to with the `kiln_writer` login.

**Oracle** (`lab/oracle/01-logistics.sql`, schemas `FLEET` and `FREIGHT`, loaded on first start,
same rows every time): a parcel carrier, using Oracle's own types (`NUMBER` with and without
precision, `NUMBER(*,0)`, a negative scale, `BINARY_DOUBLE`, `DATE` with a time of day,
`TIMESTAMP WITH TIME ZONE`, `INTERVAL`, `NVARCHAR2`, `CHAR`, `CLOB`, `BLOB`, `RAW`, `BOOLEAN`).

| Table | Rows | Notes |
| --- | --- | --- |
| `FLEET.DEPOTS` | 12 | `NUMBER(8,-2)` floor area, `BOOLEAN` hub flag |
| `FLEET.VEHICLES` | 150 | Foreign key to depots, unique registration, `BINARY_DOUBLE`, `INTERVAL`, `RAW` |
| `FLEET.DRIVERS` | 300 | Personal data (name, email, phone, date of birth, licence number). `NVARCHAR2`, `CLOB` notes |
| `FREIGHT.CARRIERS` | 25 | `BLOB` logo for a few |
| `FREIGHT.SHIPMENTS` | 5,000 | Foreign keys to carriers and, across schemas, to `FLEET.VEHICLES`. `DATE`, `TIMESTAMP WITH TIME ZONE` in three zones, `NUMBER` without precision |
| `FREIGHT.SHIPMENT_EVENTS` | 20,000 | `shipment_id` with no declared key |
| `FREIGHT.SCAN_STAGING` | 1,003 | A heap with no primary key, loaded from a scanner feed |
| `FREIGHT.LATE_SHIPMENTS` | 557 | A view: shipments not delivered yet, or delivered more than three days after they left |

The schema `WAREHOUSE` is empty: the `warehouse` login writes its pipelines' tables there.

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
| Name, email, phone, date of birth, postcode | MariaDB `shop.customers` |
| Null emails | MariaDB: 16 in `shop.customers` (customer ids divisible by 37) |
| Zero dates, read as null | MariaDB `shop.customers.date_of_birth` 0000-00-00 for customers 50, 150 and 250 |
| Out-of-range dates | MariaDB `shop.orders.ordered_at` in 1900 (orders 404 and 1212) and 2099 (order 777) |
| Negative stock | MariaDB `stock.stock_levels`: product 13 in warehouse 2 holds -4 |
| Duplicate keys, no primary key | MariaDB `stock.supplier_feed`: SKUs `SKU-00040`, `SKU-00080` and `SKU-00120` twice each |
| Inconsistent JSON | MariaDB `stock.products.attributes`: `weight_g` in grams for most, `weight` as text ("1.2 kg") for products 7, 70 and 107 |
| Declared keys | MariaDB: orders to customers, lines to orders, stock levels and movements to products and warehouses |
| Undeclared join across databases | MariaDB `shop.order_lines.product_id` to `stock.products` (proposed) |
| True or false columns | MariaDB `TINYINT(1)` columns (`is_active`, `gift_wrap`, `discontinued`, `in_stock`) read as booleans |
| Name, email, phone, date of birth, licence number | Oracle `FLEET.DRIVERS` |
| Null emails | Oracle: carriers 7, 14 and 21 in `FREIGHT.CARRIERS` |
| Out-of-range dates | Oracle `FREIGHT.SHIPMENTS.SHIPPED_ON` in 1900 (shipments 404 and 1212) and 2099 (shipment 777); `DELIVERED_AT` before `SHIPPED_ON` (shipments 13, 113 and 2500) |
| Negative weights | Oracle `FREIGHT.SHIPMENTS.WEIGHT_KG`: -1.5 for shipments 50, 150 and 250 |
| Duplicate keys, no primary key | Oracle `FREIGHT.SCAN_STAGING`: `TRK0000040`, `TRK0000080` and `TRK0000120` twice each |
| Inconsistent formats | Oracle `FREIGHT.SHIPMENTS.POSTCODE`: `AB1 2CD`, `ab12cd` and `AB1-2CD` |
| Exact numbers without a declared precision | Oracle `FREIGHT.SHIPMENTS.DECLARED_VALUE` (`NUMBER`), read as text holding its digits |
| Declared keys | Oracle: vehicles and drivers to depots, shipments to carriers, and across schemas shipments to `FLEET.VEHICLES` |
| Undeclared join | Oracle `FREIGHT.SHIPMENT_EVENTS.SHIPMENT_ID` to `FREIGHT.SHIPMENTS` (proposed) |

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
`jdbc:sqlserver://lab-sqlserver:1433;databaseName=labdb` with **Trust the server certificate** set
to `true`; from this machine it is `localhost,14330` (in SQL Server Management Studio or Azure Data
Studio, tick **Trust server certificate**). The port is 14330 so it never clashes with a SQL Server
you already run. The database allows snapshot isolation, so the connector's `isolation` setting can be tried
with `snapshot`.

## MariaDB

Started with `.\lab\lab.ps1 start mariadb` (or `./lab/lab.sh start mariadb`). It is the official
MariaDB 11.4 image, the same tag the MariaDB tests pin. Kilnmark's `mariadb` kind reads it; the
`mysql` kind reads MySQL 8.0.19 and later through the same driver.

- **Port 3307.** From this machine the server is `localhost:3307`, so it never clashes with a MySQL
  or MariaDB you already run on 3306. From Kilnmark, in the compose stack, it is host
  `lab-mariadb`, port `3306`.
- **First start.** The image is about 110 MB to download. The data loads in a few seconds on the
  first start (`logs mariadb` shows `ready for connections` twice: once for the load, once for
  the server); later starts skip it. TLS is off, so the source's default TLS mode (`preferred`)
  connects without it.
- **Reset.** `.\lab\lab.ps1 reset mariadb` deletes the `lab_lab-mariadb-data` volume and loads
  the data again.

| Login | Password | Can |
| --- | --- | --- |
| `kiln_reader` | `Lab-Reader-2026` | Read `shop` and `stock` (`SELECT`, `SHOW VIEW`). What Kilnmark scans with |
| `kiln_writer` | `Lab-Writer-2026` | Create, fill and drop tables in database `warehouse` only, for pipeline writes |
| `root` | `Lab-Admin-2026` | Everything |

These passwords are for the lab only. `lab/sources.csv` registers it as `lab-mariadb` with host
`localhost`, port `3307` and the databases `shop` and `stock`.

## Oracle

Started with `.\lab\lab.ps1 start oracle` (or `./lab/lab.sh start oracle`). It is Oracle Database
Free 23ai in the community image `gvenzl/oracle-free`, the same version the Oracle tests pin.

- **The driver.** Kilnmark does not ship Oracle's JDBC driver: its licence, the Oracle Free Use
  Terms and Conditions, does not allow it in a product that is sold. Install it as a customer
  would, in Kilnmark under **Settings > Drivers** (accept the licence, then **Accept and
  download**), or with `./lab/lab.sh driver oracle` (`.\lab\lab.ps1 driver oracle`), which shows
  the licence, downloads the pinned version into `lab/drivers` (not committed), checks its
  checksum and copies it into a running Kilnmark container. Until then the source's connection test
  says it needs its driver.
- **Port 1522.** From this machine the server is `localhost:1522`, service `FREEPDB1`, so it never
  clashes with an Oracle you already run on 1521. From Kilnmark, in the compose stack, it is host
  `lab-oracle`, port `1521`.
- **First start.** The image is about 850 MB to download. The first start creates the database and
  then loads the data, a few minutes in all: `logs oracle` ends with `DATABASE IS READY TO USE!`.
  Later starts take seconds. It needs about 2 GB of memory.
- **Reset.** `.\lab\lab.ps1 reset oracle` deletes the `lab_lab-oracle-data` volume, so the next start
  creates the database and loads the data again.

| Login | Password | Can |
| --- | --- | --- |
| `kiln_reader` | `Lab-Reader-2026` | Read the tables and the view of `FLEET` and `FREIGHT`. What Kilnmark scans with |
| `warehouse` | `Lab-Writer-2026` | Create, fill and drop tables in its own schema `WAREHOUSE`, for pipeline writes |
| `system` | `Lab-Admin-2026` | Everything in the database |

These passwords are for the lab only. `lab/sources.csv` registers it as `lab-oracle` with host
`localhost`, port `1522`, service `FREEPDB1` and the schemas `FLEET` and `FREIGHT`.

## Profiles: one per demo source

Every service in `lab/compose.yaml` belongs to two profiles: the demo source's name and `all`.

```
  lab-sqlserver:
    profiles: ["sqlserver", "all"]
```

A source's set-up or seed jobs share its profile (`lab-sqlserver-init` is in `sqlserver`). The
names so far: `postgres`, `s3`, `sqlserver`, `mariadb`, `oracle` and `mail` (Mailpit). A source that needs nothing running, like `files`, has no profile. Ports avoid
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
| MySQL and MariaDB | `lab-mariadb`, MariaDB 11.4 (profile `mariadb`) | 3307 | `lab-mariadb` | Present (MySQL 8.4 is covered by the tests) |
| Oracle | `lab-oracle`, Oracle Database Free 23ai (profile `oracle`) | 1522 | `lab-oracle` | Present (its driver: Settings > Drivers or `lab.sh driver oracle`) |
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
there is nothing to upload. (MinIO's images stopped being publicly available in 2026.) Mailpit,
the mail catcher, is MIT. Both are local test tools and are never shipped with Kilnmark
([ADR 0007](../docs/adr/0007-dependency-policy.md)).

MariaDB's server is GPL 2.0. It runs only in the lab's container, as a separate program Kilnmark
talks to over the network, and is never shipped with Kilnmark; the driver Kilnmark ships is the
LGPL MariaDB Connector/J ([ADR 0007](../docs/adr/0007-dependency-policy.md)).

Oracle Database Free is Oracle's, free to use under Oracle's terms; the lab runs it from
the community image `gvenzl/oracle-free`, a local test tool that is never shipped. Oracle's JDBC
driver is under the Oracle Free Use Terms and Conditions; Kilnmark never ships it, and
`lab.sh driver oracle` shows those terms before downloading it.

SQL Server is Microsoft's, used under its Developer edition licence, which starting the
`sqlserver` demo source accepts (see [SQL Server](#sql-server)). The image is pulled from
Microsoft's registry and is never shipped with Kilnmark either.
