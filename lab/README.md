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
| S3-compatible (MinIO) | `localhost:9000`, bucket `lab-raw`; console on `:9001` | `labadmin` / `labadmin123` |
| File share | `lab/files/share` on disk | none |

These are lab-only credentials for local testing.

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

**Bucket** (`lab/files/bucket`, uploaded to `lab-raw`):

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

MinIO is AGPLv3. It is used only as a local test tool and is never shipped with Kilnmark
([ADR 0007](../docs/adr/0007-dependency-policy.md)).
