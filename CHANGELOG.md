# Changelog

Each release starts with **What's new**, a short summary in plain words, followed by the
details.

## Unreleased

### What's new

- **Sign-in and roles.** Every user signs in with an API token, as a viewer, steward or admin.
  Admins manage tokens under **Settings**.
- **Adding connections is guided.** Pick a connector from a gallery grouped by category, test the
  connection, choose which schemas or folders to scan, and preview the data before scanning.
  Sources can be edited and deleted.
- **More kinds of source.** SQL Server, folders of files, and S3-compatible object storage. Excel
  sheets are read as tables.
- **Documents are read.** PDF, Word, Excel, PowerPoint and email files: their text is searchable
  and readable page by page on the asset page.
- **Browse and relationships.** A tree of sources, schemas, folders and tables, and schema
  diagrams showing how tables relate, from declared foreign keys and from the data itself.
- **Data quality.** Every scan scores each table in eight quality dimensions with no set-up. Value
  patterns, rules with previews, drift between scans, sample rows with sensitive columns masked,
  and an issue list for stewards. Contracts export in open formats (ODCS, OpenLineage).
- **AI, local first.** Add local or hosted models; each source decides what may leave the
  network. Search by meaning, ask questions in plain English, and connect outside AI assistants
  through the MCP server. Every model call is recorded.
- **For administrators.** A job queue, health checks, and interactive API documentation at
  `/api/docs`.

### Details

- **Data quality** (docs/design/data-quality.md, build step 1). Profiling now keeps, over every
  row, each text column's value shapes (`SW1A 1AA` is `AA9A 9AA`; the top 20 with counts and the
  share outside them), detected formats (email, phone, UK postcode, NI number, IBAN, card numbers
  by Luhn, ISO dates and timestamps, numbers and booleans as text, JSON, padded values), blank
  values, and for numbers and dates quantiles every 5%, negatives, values beyond the quartile
  fences and dates after today. A few example values per shape are kept apart from the profile.
- **Samples:** every scan keeps a sample of whole rows (1,000 by default; source setting
  `sample_rows`), chosen by the hash of the primary key (or of the whole row), so the same rows
  are chosen every time; files use reservoir sampling seeded per asset. Samples are replaced on
  each scan and deleted with the asset; cells are cut at 200 characters and a sample holds at most
  `kilnmark.quality.sample-max-mb` (8 MiB). Source setting `keep_samples=false` keeps no sample
  rows, example values, most common values or failing rows (numeric statistics such as min, max
  and quantiles are still kept), and removes those already stored as soon as it is seen. Database
  tables larger than the source setting `table_sample_above_rows` are profiled from a
  `TABLESAMPLE`; the profile and any rule read through it say they are estimates, and the
  catalogue keeps the database's row estimate. Viewing samples, examples, failing rows and the
  values in measures, drift and exports needs the steward role; viewers get words, counts and
  shares. Columns a steward marks sensitive are masked (in samples, examples, failing rows, the
  asset's profile and exports) unless an admin unmasks them; only an admin can unmark one.
- **Automatic pre-analysis** after each scan, with no extra read: measures for completeness,
  uniqueness, validity, consistency, accuracy (estimated), timeliness and volume, each with its
  evidence; suggested regular expressions for a column's dominant shapes; stewards accept shapes
  with one click, which makes a validity rule.
- **Rules:** not null, unique, allowed values, matches pattern, in range, freshness, row count and
  its change, references and custom SQL, each tagged with a dimension, with warn and fail
  thresholds as a share or a count. Actions: status, a steward task (the new **Issues** list),
  and webhooks to URLs on the administrator's allow-list (`kilnmark.quality.webhook-allow-list`).
  They run after every scan and on demand through the job queue (jobs of kind `quality`): as SQL
  in PostgreSQL and SQL Server, each check in its own transaction (read-only on PostgreSQL) with a
  time limit (`kilnmark.quality.query-timeout`), and while streaming files (regular expressions
  bounded in time and length). Regular expressions are limited to a subset Java and PostgreSQL read
  alike. Results keep the query that lists the failing rows and a few failing rows; database error
  text is shown to stewards only. Rules preview against the sample before saving, and are
  recommended from the profile without AI.
- **Custom SQL rules are off by default** (`kilnmark.quality.custom-sql=true` turns them on) and
  only admins write, change or run them. They run only on PostgreSQL, never under a login that is
  a superuser or may read server files, run programs or signal other sessions, as one checked
  SELECT (no functions that touch files, locks, settings, sequences or other sessions) in a
  read-only transaction with a statement timeout. They can still read anything the source's login
  can see, beyond the source's schemas: give sources a read-only login.
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
- **Rule form for stewards:** the form on the Quality rules page finds the asset by name (or takes
  a pasted URN) and offers the columns that suit the check, with their types. Each check has plain
  fields instead of JSON: allowed values one per line, a pattern typed as it is (with the profile's
  suggested expression one click away), minimum and maximum as numbers or dates, hours, rows, a
  percentage, and the referred-to asset found by name in the same source. The JSON parameters stay
  under "Advanced: edit as JSON", which can take over from the fields. Rules are edited in place
  (**Edit**, saved with `PUT /api/v1/quality/rules/{id}`), keeping their results and any dimension
  set through the API. A `PUT` with `If-Match` set to the rule's `updatedAt` is refused (409) when
  someone else changed the rule since, and the form sends it, so an edit never undoes another
  unseen. `GET /api/v1/search` takes `source` to keep to one source's assets. Values held back
  for a sensitive column (`{"hidden": true}`) are shown as hidden and sent back unchanged unless
  replaced; admins load rules unmasked, and recommendations without their values have no
  one-click Add.
- **Documentation in the app:** the guides in `docs/guide` and this changelog (as What's new) are
  bundled into the web UI at build time and shown under **Documentation**, so they work offline.
  Each section has its own address, `#/docs/<page>/<section>`, from its heading as GitHub makes
  anchors. The navigation shows the running version with a link to What's new. Markdown is
  rendered with marked 18.1.0 (MIT); HTML inside it is shown as text, never run.
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
- **AI models (ADR 0016):** admins add models under **AI models**: local ones (llama.cpp's
  llama-server, vLLM, Ollama or any OpenAI-compatible server, marked local) and hosted ones with an
  API key (OpenAI, Azure OpenAI, Anthropic, Google Gemini). No restart needed. API keys are
  encrypted and never shown again (only their last four characters), and are asked for again when
  a model's address moves to another host. Link-local and cloud metadata addresses are refused. **Test** says in plain words
  whether a model works and what to fix. Each feature (search by meaning, asking in plain English,
  describe, classify) uses the model an admin chooses, or is off and works without AI.
- **What may leave the network:** every model call passes one check. A source's AI rule
  (`ai_egress`: `local_only` by default, `allowed` or `none`) decides which models may see it, and
  hosted models can be forbidden for the whole server by an admin, or by whoever runs it
  (`KILNMARK_AI_HOSTED_MODELS=forbidden`); then a model counts as local only if its address is on
  a local network (`KILNMARK_AI_LOCAL_HOSTS`). A refused call never reaches the model and says why.
- **AI usage:** every model call is recorded (feature, model, source, tokens, time, outcome, who
  asked), refused ones too, without prompt or answer text; admins see totals by day and model.
  Records are kept 90 days (`kilnmark.ai.usage-retention`).
- **Search by meaning:** after each scan, assets (names, descriptions, fields and types, never
  values) are embedded with the chosen embedding model into pgvector, skipping unchanged ones; the
  scan's run says how many. Search combines words and meaning (reciprocal rank fusion), shows how
  each result matched, and falls back to words, saying why (without naming the model). A search
  waits about 10 seconds at most for the model, and skips one that failed in the last 30 seconds.
- **Ask a question:** the search page has an "Ask a question" mode. A chat model turns the
  question into a search (words, kinds, sources, field names), which is shown with the results,
  including anything it could not use. Without a chat model the question is searched as typed.
- **MCP server:** outside AI assistants can search and read the catalogue at `/mcp` (Streamable
  HTTP) with a viewer API token: `search_assets`, `get_asset`, `list_sources`. Assistants see
  sources whose AI rule is `allowed`; tokens an admin marks as used by a local assistant also see
  `local_only` ones; `none` sources are never shown.
- **Compose:** profiles `ai` (CPU) and `ai-cuda` (NVIDIA GPU) run llama-server with a small chat
  model and an embedding model; `ai-download` fetches them into the `models` volume from fixed
  revisions, checked by SHA-256. Images pinned by tag and digest.
- **AI evaluation:** `./gradlew :server:aiEval` scores a running server's search (recall, MRR)
  and question answering against a synthetic golden set over the lab, and fails if a score falls
  below the baseline; not part of the build.
- **Database:** migration V6 adds `model_profile`, `ai_route`, `ai_setting`, `ai_usage` and
  `asset_embedding`, and `api_token.local_assistant`. 2.7 MB of jars added (LangChain4j provider modules, Quarkus MCP server), all
  Apache-2.0.

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
- **Security:** host names come from the database driver's own URL parser; URL properties naming
  another host (`PGHOST`, `server`, `address`...), loosening time limits (`socketTimeout`,
  `loginTimeout`...), using `pg_service.conf` (`service`), or any SQL Server `authentication` but
  `SqlPassword` are refused, whatever braces, quotes or encoding they are written with.
- **Changed:** bulk import's row statuses are now `created`, `updated`, `unchanged` and `failed`
  (they were `ok`, `created` and `error`), and a row naming an existing source updates it instead
  of failing. An update changes only the columns in the file.
- **Changed:** `include` and `exclude` patterns without a `.` match database table names only (with
  a `.`, `schema.table`); without a `/`, file names only.
- **Add a connection:** a gallery of connectors by category, with tabs, search and what each can
  do (discover, read; write and stream to come), including planned connectors and their wave
  (`kilnmark.connectors.show-planned`). Choosing one opens a four-step panel beside it: connect
  (host, port and database build a database URL), test, choose what to scan, save or save and
  scan. Edit uses the same panel. Connectors declare their category, family, capabilities,
  formats, icon and docs section, and a driver descriptor (all bundled today).
- **Preview** any table or file before scanning: the first 100 rows (up to 1,000), read live and
  never stored. Files show what was detected; new file settings `delimiter` and `header` correct
  it for previews and scans.
- **Sources list:** filter and group by category; file sources show the formats found, with counts.
- **Database:** migration V5 adds `data_source.display_name` and the import batch tables.
- **Fixed:** an S3 source with its own access key can be added through the API, the form and bulk
  import; the check that the secret goes with the key no longer misses the secret.

- **Browse:** a new **Browse** page shows the catalogue as a tree: source, then schema, folder or
  bucket prefix, then tables, views and files, with counts, kind labels and icons. Children load
  as a node opens, a hundred at a time; a filter box narrows the tree by name; the tree follows
  the ARIA tree pattern for the keyboard. Choosing a schema lists what it holds and draws it.
- **Schema diagrams:** tables as boxes with their columns (keys marked PK and FK) and lines for
  relationships: declared keys solid, proposals dashed with their confidence, rejected ones hidden.
  Pan, zoom, fit, automatic layout; at most 60 tables, with a filter or one table and its
  neighbours (one or two steps, across schemas and sources) for larger schemas. The same
  relationships are listed in a table beside it. Uses @xyflow/react and @dagrejs/dagre (MIT,
  ADR 0019), loaded only when a diagram is shown.
- **Relationships:** scans of PostgreSQL and SQL Server record declared foreign keys, including
  multi-column keys in key order and keys between schemas. After every scan Kilnmark proposes
  joins nobody declared, within and across sources, from column names, types, unique targets and
  value containment estimated from stored HyperLogLog sketches; on the lab,
  `billing.transactions.customer_id` to `crm.customers`. Stewards accept or reject proposals on
  the new **Relationships** page, the asset page or below a diagram; decisions survive rescans.
- **Asset page:** a summary with source and schema links, the asset's relationships in and out,
  and per-column statistics: open a column for nulls, distinct values, quartiles, mean, a small
  histogram, lengths and most common values.
- **API:** `GET /api/v1/browse/sources`, `.../browse/sources/{name}/containers`,
  `.../browse/sources/{name}/assets?container=`, `GET /api/v1/diagram` (a container, an asset and
  its neighbours, or chosen assets), `GET /api/v1/relationships`, `GET /api/v1/relationships/{id}`,
  and `POST /api/v1/relationships/{id}/accept` or `/reject` (steward).
- **Profiles:** numbers, dates and times gain a ten-bin histogram; key-like columns (identifier
  names and single-column primary keys) keep their distinct-value sketch (about 8 KiB) beside the
  profile for discovery.
- **Database:** migration V8 adds the `relationship` table and `profile.hll`.
- **Connectors:** `Connector.catalogWithRelationships` (a default method) returns the catalogue
  with the relationships a source declares, and warnings; `AssetInfo` is unchanged. Foreign keys
  that cannot be read leave the scan going, with a warning in its detail.

- Settings: API tokens, AI models and AI usage move under one Settings section, admins only.
- Interactive API documentation at `/api/docs`, served by the server itself (works offline), from
  the OpenAPI document at `/q/openapi`. Calls made from it need an API token (Authorize button).

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
