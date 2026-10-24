# Reference lab

A small, fully synthetic data estate for Kilnmark to discover. It is what the regression suite
and every demo run against. Never put real or customer data here, or anything from a former
employer's labs.

## Start and stop

```
podman compose -f lab/compose.yaml up -d     # Docker: docker compose -f lab/compose.yaml up -d
podman compose -f lab/compose.yaml down -v   # stop and wipe
```

| Source | Where | Credentials |
| --- | --- | --- |
| PostgreSQL 18 | `localhost:5433`, database `lab` | `lab` / `lab` |
| S3-compatible (VersityGW) | `localhost:9000`, bucket `lab-raw`, path-style addressing | `labadmin` / `labadmin123` |
| File share | `lab/files/share` on disk | none |

These are lab-only credentials for local testing.

Kilnmark reads folders only inside the ones its administrator allows. The main `compose.yaml`
mounts `lab/files/share` read-only at `/srv/files` and allows it, so in the compose stack add a
source of kind **files** with path `/srv/files`. Running the server outside a container, set
`KILNMARK_FILES_ROOTS` to the full path of `lab/files/share` and use that path.

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

## What discovery should find (the answer key)

| Finding | Where |
| --- | --- |
| Email, phone, date of birth, postcode, NI number | `crm.customers`, the CSV export, the complaint letter |
| Undeclared join on `customer_id` | `billing.transactions` to `crm.customers` |
| Renamed key: `cust_ref` holds customer ids as text | `billing.invoices`, the Parquet file |
| Duplicates of customer data | `crm.customers_archive_2023`, `exports/customers_export_2025.csv` |
| No personal data | `marketing/2024-q4-campaign-plan.md` |

## Synthetic identifiers

Phone numbers use Ofcom's 07700 900xxx range reserved for drama, National Insurance numbers use
the QQ prefix that HMRC never issues, and emails use example.com, .org and .net.

## Licences

VersityGW, the S3 server, is Apache 2.0. It serves `lab/files/bucket` as a bucket directly, so
there is nothing to upload. (MinIO's images stopped being publicly available in 2026.) It is a
local test tool and is never shipped with Kilnmark
([ADR 0007](../docs/adr/0007-dependency-policy.md)).
