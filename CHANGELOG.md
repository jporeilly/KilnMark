# Changelog

## Unreleased

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

- **Managing sources:** stewards can edit a source (display name, settings, password) and delete
  it. A source's name is its fixed ID, part of every asset reference, so it cannot change; the new
  display name can. A blank password keeps the stored one, unless the URL, endpoint or user name
  changes. Delete is refused while a scan waits or runs, and otherwise removes the source's assets,
  fields, profiles, runs and graph nodes in one transaction.
- **Settings every source has:** `description`, `ai_egress` (`local_only` by default, `allowed` or
  `none`), and `include` / `exclude` glob patterns (ignoring case) that choose tables or files for
  every kind of source. The folder connector's own `include` setting is now this common one, and
  now ignores case.
- **Test connection** for a saved source or a form not yet saved, within 15 seconds, in plain words
  and never repeating the password.
- **Choose what to scan from a list:** schemas (databases) or top-level folders (folders and
  buckets) with how many tables or files each holds, from metadata or a listing. Before a scan the
  UI warns if it would find nothing.
- **Bulk import, version 2:** run the same file again to create, update or leave sources unchanged
  (reported per row); `onError=skip` applies the right rows; `host`, `port` and `database` columns
  build the URL; `display_name` and the common settings as columns; `remapHosts=true` rewrites
  host names with the administrator's `kilnmark.sources.host-map` (for example localhost to
  host.docker.internal); `test=true` and `scan=true` test each source and queue scans, with
  progress the UI shows. Files in the earlier format still import. `lab/sources.csv` registers
  the lab.
- **Source page** in the web UI: settings (never the password), what it scans, run history with
  durations, attempts and errors, and edit, test, scan and delete.
- **Readiness and liveness:** `/q/health/ready` (database, migrations, worker) and
  `/q/health/live`, public and saying only UP or DOWN. The Compose service has a health check.
- **Security:** stewards' checks connect from the server, so an administrator can limit the hosts
  sources may reach with `kilnmark.sources.allowed-hosts`. JDBC URL properties that make a driver
  use the server's own identity or files (`integratedSecurity`, `authentication=ActiveDirectory...`,
  `sslkey`, `sslfactory`, `socketFactory`, `loggerFile`, any `...File`, `...Path`, `...Factory`,
  `...Class`) are refused. Checks give their place back only when they have really ended.
- **Changed:** bulk import's row statuses are now `created`, `updated`, `unchanged` and `failed`
  (they were `ok`, `created` and `error`), and a row naming an existing source updates it instead
  of failing. An update changes only the columns in the file.
- **Changed:** `include` and `exclude` patterns without a `.` match database table names only (with
  a `.`, `schema.table`); without a `/`, file names only.
- **Database:** migration V5 adds `data_source.display_name` and the import batch tables.
- **Fixed:** an S3 source with its own access key can be added through the API, the form and bulk
  import; the check that the secret goes with the key no longer misses the secret.

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
