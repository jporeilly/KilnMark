# Changelog

## Unreleased

- **Data quality** (docs/design/data-quality.md, build step 1). Profiling now keeps, over every
  row, each text column's value shapes (`SW1A 1AA` is `AA9A 9AA`; the top 20 with counts and the
  share outside them), detected formats (email, phone, UK postcode, NI number, IBAN, card numbers
  by Luhn, ISO dates and timestamps, numbers and booleans as text, JSON, padded values), blank
  values, and for numbers and dates quantiles every 5%, negatives, values beyond the quartile
  fences and dates after today. A few example values per shape are kept apart from the profile.
- **Samples:** every scan keeps a sample of whole rows (1,000 by default; source setting
  `sample_rows`), chosen by the hash of the primary key (or of the whole row), so the same rows
  are chosen every time; files use reservoir sampling seeded per asset. Samples are replaced on
  each scan and deleted with the asset. Source setting `keep_samples=false` keeps no values at
  all: no samples, examples, most common values or failing rows. Database tables larger than the
  source setting `table_sample_above_rows` are profiled from a `TABLESAMPLE`, and the profile says
  it is an estimate. Viewing samples, examples and failing rows needs the steward role; columns a
  steward marks sensitive are masked unless an admin unmasks them.
- **Automatic pre-analysis** after each scan, with no extra read: measures for completeness,
  uniqueness, validity, consistency, accuracy (estimated), timeliness and volume, each with its
  evidence; suggested regular expressions for a column's dominant shapes; stewards accept shapes
  with one click, which makes a validity rule.
- **Rules:** not null, unique, allowed values, matches pattern, in range, freshness, row count and
  its change, references and custom SQL, each tagged with a dimension, with warn and fail
  thresholds as a share or a count. Actions: status, a steward task (the new **Issues** list),
  and webhooks to URLs on the administrator's allow-list (`kilnmark.quality.webhook-allow-list`).
  They run after every scan and on demand through the job queue (jobs of kind `quality`): as SQL
  in PostgreSQL and SQL Server (custom SQL read-only and stopped after
  `kilnmark.quality.query-timeout`), and while streaming files. Results keep the query that lists
  the failing rows and a few failing rows. Rules preview against the sample before saving, and
  are recommended from the profile without AI.
- **Profile versions and drift:** a version per asset per scan; each is compared with the one
  before and with a chosen baseline for schema, volume, distribution (quantile shift, nulls,
  distinct values, most common values, shape shares) and freshness. Drift events have a severity,
  lower the scores and open steward tasks.
- **Scores** per column and dimension, rolled up to the asset and the source, with reasons.
- **Open formats** (ADR 0020): `GET /api/v1/quality/export/openlineage` and `/export/odcs` export
  an asset as an OpenLineage run event (schema, data quality metrics and assertions facets) and as
  an ODCS 3.1 data contract, validated against the published schemas in the tests.
- **Web UI:** a Quality section on asset pages (scores by dimension, value patterns with share
  bars and examples, accept a pattern, rules and results, drift, sample rows, recommended rules),
  a **Quality rules** page and an **Issues** page.
- **Database:** migration V9 adds profile versions, samples, rules, accepted patterns, quality
  runs, results, measures, scores, drift events and issues, a `sensitive` flag on fields, and
  lets the job queue hold jobs of other kinds.

- **Sign-in with API tokens:** every API call needs a token (`Authorization: Bearer km_...`)
  with a role: viewer (read), steward (also start scans) or admin (also add sources and manage
  tokens). Public without a token: `GET /api/v1/info`, `/q/health` and the API description
  `/q/openapi`. Tokens are shown once and stored only as SHA-256 hashes; they can expire and be
  revoked at once. The first start writes a 24-hour admin token to `admin-token` next to the
  secret key (use it to create a permanent one), or takes one from `KILNMARK_ADMIN_TOKEN`.
  `KILNMARK_AUTH_MODE=off` turns sign-in off for a lab, and only on a loopback address.
- **Web UI:** a sign-in page, sign-out, and an **API tokens** page for admins (create with a
  role and expiry, copy the new token once, revoke). Viewers no longer see controls they cannot
  use. Pages no longer scroll sideways on phones.
- **Job queue:** scans are queued in PostgreSQL and run by any process with the worker role, so
  api and worker can run apart. Workers wake on `LISTEN/NOTIFY` and also poll, run
  `kilnmark.worker.concurrency` scans at a time (default 2), never scan one source twice at
  once (asking again returns the scan in progress), recover scans left by a worker that
  stopped, and finish running scans before shutting down (Compose now allows 60 s for this).
- **Database:** migration V4 adds the `api_token` and `job` tables, and marks scans left waiting
  or running by an earlier version as failed, so they can be started again.
- **Sources:** SQL Server (kind `sqlserver`): tables and views in user schemas,
  with `MS_Description` descriptions and primary keys; every SQL Server type read, with
  `datetimeoffset` as a UTC timestamp, `money` as an exact decimal, and geography, geometry and
  hierarchyid as text. Reads take no shared locks by default (setting `isolation`:
  `read-uncommitted`, `snapshot` or `read-committed`). Uses Microsoft's JDBC driver (MIT).
- **Sources:** a JDBC URL holding a password is refused; enter it as the source's password, which
  is encrypted.
- **Fixed:** a schema whose name holds `_` (such as `a_b`) no longer picks up the columns of a
  same-named table in a schema it matches as a pattern (`axb`); PostgreSQL schemas named `sys` or
  `guest` are scanned.
- **Fixed:** a read stopped early by a failing step now ends at once instead of reading the rest
  of the table.
- **Files and object storage:** two new kinds of source. `files` scans a folder the server can
  read; `s3` scans a bucket on AWS or any S3-compatible store (VersityGW, MinIO, Ceph). CSV, TSV,
  JSON, JSON lines and Parquet files are catalogued with their fields and profiled like tables;
  every other file is recorded as a document (size and time) without being read.
- **Administrators decide what sources may reach:** folder sources only inside the folders listed
  in `kilnmark.files.roots` (none by default, so folder sources are off until set); S3 sources need
  their own access key unless `kilnmark.s3.allow-default-credentials` lets sources on AWS use the
  server's identity.
- CSV: delimiter (including Excel's `sep=` line), quote and encoding found automatically (UTF-8,
  or windows-1252 when a file is not valid UTF-8; a source's `encoding` setting overrides); column
  types (integer, decimal, boolean, date, time, timestamp) inferred from the first 1,000 rows. A
  value that does not parse later in the file is read as null and counted (`unparsed`), never a
  failed scan.
- Objects are streamed: a catalogue reads only the first megabyte of text files and the footer of
  Parquet files, and a sampled read stops the transfer when it has enough rows.
- A corrupt, too deeply nested or unsupported file (a Parquet codec such as Brotli) is recorded as
  not readable with the reason, and the scan goes on.
- Parquet is read without Hadoop, through Hardwood, with one shared, bounded decoding pool
  (`kilnmark.files.parquet-threads`, `kilnmark.files.parquet-chunk-mb`); nested values become JSON
  text. S3 through the AWS SDK's S3 module only (ADR 0015). 19.3 MB of jars added.
- **Fixed:** profile statistics above about 9.2 trillion are no longer capped.

## 0.0.2 (24 October 2026)

The first working slice: point Kilnmark at PostgreSQL databases, scan them, and search what it
found.

- **Sources:** register PostgreSQL databases one at a time, or many at once from a CSV or JSON
  file (checked row by row, all or nothing, with a dry run). Passwords are encrypted with
  AES-256-GCM before they reach the database; the key lives outside it. Bulk files can refer to
  passwords as `env:VARIABLE` or `secret:NAME` instead of holding them.
- **Scans:** every table and view, with columns, types, comments and primary keys; each one read
  through the Kiln engine and profiled as it streams (rows, nulls, distinct values, ranges,
  lengths, most common values). Two tables at a time; one failing does not stop the scan. Assets
  that disappear from a source are marked removed.
- **Search:** tables, views and columns by name or description, tolerant of partial and misspelt
  words.
- **Web UI:** search from anywhere (Ctrl+K), sources with scan status, asset pages with every
  column's profile. WCAG 2.2 AA automated checks pass in light and dark themes.
- **Kiln engine:** steps on virtual threads joined by bounded Arrow row sets, per-step memory
  accounting, back-pressure, clean failure and leak reporting.
- **Platform:** Java 25 (ADR 0013), Business Source License 1.1 (ADR 0008), sign-in plan
  (ADR 0014). No sign-in yet: lab use only.

## 0.0.1 (24 October 2026)

Scaffolding: build, CI, containers, reference lab and decision records.
