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

- **Documents are read, not just listed:** the `files` and `s3` sources read the text of PDF,
  Word, Excel, PowerPoint (current and 97-2003 formats), OpenDocument, EPUB, RTF, HTML, XML, mail
  (`.eml`, `.msg`), plain text and Markdown files, with Apache Tika. Each document gets a profile:
  content type, pages, words, characters, language, title, author, created and modified dates, and
  for S3 the object's owner and tags. A mail message shows from, to, cc, date and subject, and each
  attachment is an asset of its own. No OCR yet (ADR 0017).
- **Excel sheets as tables:** each sheet with a header row is a table inside its workbook, read with
  POI's streaming reader and profiled like any table, with typed columns. Other sheets (notes) are
  recorded with the reason.
- **Search finds words in documents,** ranked below name matches, with a highlighted snippet
  (`snippet` in search results, text with highlight positions, never markup).
- **Reading documents:** the asset page shows a document's profile and its text page by page (slide
  by slide, sheet by sheet), with find; `GET /api/v1/documents/text`, `/find` and `/chunks`.
- **Documents are untrusted input:** limits on size, characters, time (a document still being read
  after 60 seconds is abandoned), nesting depth and zip bombs; one bad document is marked not
  readable with the reason and the scan goes on. Settings `kilnmark.documents.*`.
- **Sources:** setting `keep_text` (default `true`); `false` keeps only documents' properties, and
  their text is read from the source again when someone opens it.
- **Database:** migration V7 adds `document` (profiles), `document_chunk` (text in chunks, searched
  through a `tsvector`) and `asset.parent_urn` (sheets and attachments inside their file), all
  deleted with their asset. The text of documents no longer in their source is deleted on the scan
  that notices.
- **Lab:** synthetic documents (PDF, Word, Excel, mail, Markdown) in the share and the bucket, made
  by `lab/make-documents.py`. 27.4 MB of jars added (ADR 0017).
- **Documents, after review:** Office files are checked for parts that really expand past
  `kilnmark.documents.max-part-mb` (64 MB) before any parser opens them, and sheet rows stop at
  16,384 cells. Healthy documents wait for a reader instead of failing; a read that fails for a
  passing reason (busy, out of time, stopped, source not reachable) keeps what the last scan stored.
  Sheet and attachment names lose control characters. `%` and `#` in file names are escaped in URNs,
  so a file cannot pass for a sheet or attachment. `keep_text=false` now hides text too, unless
  `read_text_on_demand=true` lets stewards read it from the source. Find copes with letters whose case
  changes their length (Turkish İ). Persian, Urdu, Catalan, Marathi and Nepali are no longer
  mistaken for their neighbours.

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
