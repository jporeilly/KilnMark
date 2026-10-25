# Writing a connector

A connector teaches Kilnmark a kind of data source. The server knows nothing about any
particular kind: it asks the connector registry. Adding a connector is adding classes and one
line in a services file; nothing in the server changes.

## The interface

`io.kilnmark.kiln.connect.Connector` (in `kiln-core`):

| Method | Does |
| --- | --- |
| `kind()` | Stable identifier stored with each source: `postgresql`, `sqlserver`, `files`, `s3` |
| `displayName()` | For people: "SQL Server" |
| `settings()` | The settings a source takes, in form order (key, label, required, hint, multiple) |
| `validate(source)` | Problems with the settings without connecting, each starting `setting: ...` |
| `catalog(source)` | Every asset the source holds, with its fields; throws `ConnectorException` with a message a person can act on |
| `reader(source, asset, read)` | The first pipeline step that reads one asset as Arrow batches |
| `withContext(context)` | Optional (default: unchanged). Returns the connector set up with server-wide settings |
| `containerKind()` | Optional. The list setting holding the containers chosen for scanning, what they are called and what their counts count (default `containers`) |
| `containers(source)` | Optional. Every container with how many assets in it the patterns keep; cheap (metadata, a listing). The default reads the full catalogue, so override it |
| `containerOf(source, asset)` | Optional. The container an asset belongs to, as `containers` names it (default: the asset's `container`) |
| `test(source)` | Optional. Checks the source can be reached, as cheaply as possible (default: `containers`) |
| `test(source, timeout)`, `containers(source, timeout)` | Optional. The same, bounding the connector's own network waits by `timeout` (default: ignore it) |
| `addressKeys()` | Optional. The settings holding the network address (`url`, `endpoint`); the only ones bulk import's host map rewrites |
| `category()`, `family()`, `capabilities()`, `formats()`, `icon()`, `docsAnchor()` | Optional. Where the connector sits in the catalogue and gallery, and what it can do today (default: other, other, discover and read, none, the category, the kind) |
| `driver()` | Optional. A driver the customer must install; null (the default) means bundled. Installing drivers arrives with wave 1 |
| `example(key)` | Optional. A sample value for a setting, shown in forms |
| `assets(source, container)` | Optional. A container's tables or files without fields, cheaply (default: the full catalogue) |
| `asset(source, path)` | Optional. One asset with its fields, as the catalogue records it (default: the full catalogue) |
| `preview(source, asset, rows)` | Optional. The first rows, built on `reader` with a row limit, so every connector has it; override only to add details |
| `hosts(source)` | Optional. Every host the source would reach, for the server's allowed-hosts list (default: the hosts in `addressKeys` URLs) |
| `secretScope()` | Optional. Settings that decide where the secret goes; changing one needs the secret again (default: every setting) |
| `addressSettings(host, port, database)` | Optional. Settings built from a host, port and database, for bulk import (default: none) |
| `catalogWithRelationships(source)` | Optional (default: `catalog` with no relationships). The catalogue plus the relationships the source declares, such as foreign keys; the scan calls this one |
| `writer(source, write)` | Optional (default: refuses). The pipeline step that writes its `in` port's rows to the source; see [The writer step](#the-writer-step). List `WRITE` in `capabilities()` when you override it |

**Choosing assets.** `AssetFilter` (in `kiln-core`) holds the containers chosen and the `include`
and `exclude` patterns every source has. Use `AssetFilter.of(this, source)` while reading the
catalogue to skip what is not wanted before doing work (the JDBC connectors read columns and keys
only for tables kept; the file connectors never open a file left out). The server applies the
same filter to whatever `catalog` returns, so a connector that ignores it still scans only what
was chosen. Patterns holding the kind's separator (`ContainerKind.separator()`: `.` for
`schema.table`, `/` for files) match whole paths, others names only; matching never backtracks.

**Checks.** The server runs `test` and `containers` on a virtual thread with a time limit, and
interrupts it at the limit; it keeps the check's place until the thread ends. So bound your own
waits from the `timeout` you are given: the JDBC connectors pass connect, login and socket timeouts
to the driver, S3 a per-request timeout and a listing deadline. Never put the secret in a message.
`validate` should refuse settings that make a driver use the server's own identity or files (the
JDBC connectors refuse such URL properties).

Server-wide settings that people adding sources must not control (which folders may be read,
whether the server's own cloud identity may be used) reach a connector as a `ConnectorContext`:
the server builds it from its configuration and calls `Connectors.load(context)`, which gives it
to each connector's `withContext` once and keeps the instance returned. Connectors stay immutable;
`Connectors.load()` with no context leaves every connector at its safest defaults.

A source arrives as a `SourceConfig`: name, kind, settings and the decrypted secret. Never log it
or put it in an exception message; its `toString` hides the secret.

An `AssetInfo` is one table, view or file: a `path` unique within the source (it becomes the URN
path, `urn:kilnmark:<source>:<path>`), a `kind`, a `container` (schema, folder or bucket), the
fields, any primary key, `bounded` (true when reading it in full may be expensive: the scan then
reads `kilnmark.scan.view-row-limit` rows), and `hints` the reader needs.

A `RelationshipInfo` is a relationship the source declares between two of its assets, by path:
`fromPath` and `fromFields` refer to `toPath` and `toFields`, field for field in key order, with
the constraint's `name`. Both assets must be in the same catalogue. Relationships Kilnmark infers
from names and values are not the connector's business: the server proposes those after the scan
([ADR 0019](../adr/0019-relationships.md)).

## The reader step

The reader is an ordinary Kiln `Step` with no input:

- each `process` call puts one Arrow batch on `context.output()`, allocated from
  `context.allocator()`, and returns `false` after the last;
- an empty asset still sends one empty batch, so the schema is known;
- when it finishes it emits a `ROW_COUNT` event for `asset-urn` with attributes `rows` and
  `complete` (false when a row limit cut the read short);
- it closes everything it opened in `close()`, which runs even after a failure.

Use the engine's field kinds (`JdbcColumn.Kind`: boolean, int16, int32, int64, float32,
float64, decimal, text, binary, date, time, timestamp, timestamp_tz) and the Arrow types
`JdbcColumn` maps them to, so the profiler treats every source alike. Timestamps are
microseconds. Types with no Arrow equivalent are read as text rather than dropped.

## The writer step

A connector that can write overrides `writer(source, WriteOptions)` and adds `Capability.WRITE`
to `capabilities()`. The pipeline service calls it for a `write` step after checking that the
source allows writing and that its hosts are allowed, so the connector checks only what is its
own: the target, the mode and the settings. Refuse what is wrong at once with a
`ConnectorException` whose message starts with the setting (`table: ...`, `path: ...`,
`mode: ...`), before connecting to anything.

`WriteOptions` holds the `target` (`schema.table`, or a path inside the source), the `mode`
(`create`, `append`, `replace` or `upsert`), the upsert `key`, the file `format`, the step's
further settings as written in the YAML (`options`; read them with `WriteSettings`, which ignores
keys it is not asked for) and the `assetUrn` the written asset is recorded under.

The step is an ordinary Kiln `Step` with an input and no output:

- each `process` call takes one batch from `context.input()` (the `in` port), writes it and closes
  it; the end of the input (`null`) is where it commits or puts the file in place;
- nothing is visible until then: one transaction, a temporary file renamed at the end, or an
  upload completed at the end. `close()` runs after a failure too, and must roll back, delete or
  abort what was started;
- it reports only after success, through `WriteReport`, as
  [pipelines.md, "What a write reports"](pipelines.md#what-a-write-reports) sets out: for a write
  that leaves the asset holding exactly what was written, `SCHEMA`, `ROW_COUNT` and the profile
  (computed in the same pass with `ProfileStep`'s own code); for rows added to an existing table,
  a `ROW_COUNT` with `complete=false` and `appended=true` only;
- it takes the columns from the batches with `WriteColumn`, which turns any Arrow type into the
  engine's kinds (`JdbcColumn.Kind`) and gives each value as one Java type per kind, so every
  writer handles the same set; types with no kind of their own are written as text;
- messages say what to do: which column does not fit, which grant to ask for, which setting to
  change.

The built-in writers: `JdbcTableOutput` (PostgreSQL and SQL Server; the SQL is in `WriteSql`, the
matching of input to table in `WritePlan`) and `FileOutput` (folders and S3; `FileTarget` checks
paths, `CsvEncoder` and `ParquetEncoder` write the formats, and each `FileStore` writes with
`create(path, overwrite)`, which returns an `Upload` to commit or abort). The decisions are in
[pipelines.md, section 4](pipelines.md#4-steps-in-step-1) and the guide for people is
[Writing data from a pipeline](../guide/writing-data.md).

## JDBC databases

A database with a JDBC driver extends `JdbcConnector`, which supplies the `url`, `username` and
`schemas` settings, the catalogue from JDBC metadata, the reader, the schema listing (two metadata
calls), a connection test, and `addressSettings` from its `addressUrl`. It must give its URL prefix,
an example URL and the SQL that reads a table (`selectSql`), and may override:

- `quote` for its identifier quoting (SQL Server: `[name]`, `]` doubled);
- `isSystemSchema` for the schemas that belong to the database itself, which a scan of every
  schema skips (PostgreSQL: `pg_catalog`, `pg_toast*`, `pg_temp*`, ...; SQL Server: `sys`,
  `INFORMATION_SCHEMA`, `guest` and the `db_*` roles, ignoring case). Each database lists its
  own, so a PostgreSQL schema called `sys` is still scanned;
- `schemaPattern` to escape a schema name for `getTables` and `getColumns`, which take LIKE
  patterns: by default the driver's search-string escape before `_`, `%` and the escape itself;
  SQL Server brackets them (`[_]`, `[%]`, `[[]`). The catalogue also keeps only rows whose
  schema is exactly the one asked for;
- `selectSql(AssetInfo, rowLimit)` to read particular columns differently (SQL Server reads
  geography, geometry and hierarchyid through `ToString()`, so they arrive as text people can
  read);
- `readForeignKeys` to read declared keys differently; by default `getImportedKeys` table by
  table (PostgreSQL: once per schema), multi-column keys kept together in `KEY_SEQ` order, keys to
  tables outside the schemas read left out. `catalogWithRelationships` reads tables and keys on
  one connection; if the keys cannot be read, it returns `CatalogInfo.withoutRelationships`
  (`relationshipsRead` false, with a warning): the scan keeps the declared relationships it
  recorded before and adds the warning to the run's detail;
- `readCatalog` to add what the driver's metadata leaves out (SQL Server's driver reports no
  comments, so its connector reads `MS_Description` extended properties in one query);
- `readSettings` for extra `JdbcTableInput` settings, such as the transaction isolation level;
- `explain` to add what to do about connection failures the database is known for.

`validate` refuses a URL holding a password (`;password=` or `?password=` / `&password=`): the
URL is stored and shown as plain text, while the password field is encrypted.

Check what the driver actually reports, in result metadata and in `getColumns`, before mapping
types: they can differ (Microsoft's driver reports geography as `VARBINARY` in the catalogue and
as its own code in results), so `JdbcColumn.map` matches type names where codes are not enough.
Text columns are read with `getObject`, and bytes found there (a `sql_variant` holding
`varbinary`) are written as hex, `0xCAFE`.

When a read stops early (a later step failed), `JdbcTableInput` cancels the query before closing
it: some drivers, SQL Server's among them, otherwise read every remaining row while closing.

### SQL Server

The `isolation` setting chooses how profiling reads see the application's changes:

| Value | Effect |
| --- | --- |
| `read-uncommitted` (default) | No shared locks: a long read never blocks writers, but may see rows not yet committed |
| `snapshot` | A consistent view without locks; needs `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON` |
| `read-committed` | Shared locks, as most applications read; a long read can block writers |

Profiles are statistics, so the default trades exactness on rows being changed for never
holding up a production database.

## Files and object storage

Two connectors share `io.kilnmark.kiln.plugins.files`: `files` (a folder the server can read:
`path`, `recursive`, `encoding`, `delimiter`, `header`; which files comes from the common `include` and `exclude` settings
and the chosen top-level folders) and `s3` (`endpoint`, `region`, `bucket`, `prefix`,
`access_key_id`, `path_style`, `encoding`; the secret is the secret access key). Libraries and the
reasons for them are in [ADR 0015](../adr/0015-file-formats.md).

- **What the server allows.** A folder source must be inside one of the folders listed in the
  server setting `kilnmark.files.roots` (comma-separated). It is checked with links resolved when
  the source is validated and again every time it is opened; with no roots, folder sources are
  refused, so adding a source can never expose the server's own files. An S3 source needs its own
  access key: only with `kilnmark.s3.allow-default-credentials=true` may a source on AWS (no
  endpoint) use the server's own AWS identity, and never a source with another endpoint, which
  would receive requests signed with it. Endpoints holding a user name or password are refused.
- **Assets.** Every file is an asset. Its path is the path within the folder (with `/`) or the
  object key; its container is the folder, or the bucket and folder. CSV, TSV, JSON, JSON lines
  (`.jsonl`, `.ndjson`) and Parquet have kind `file` and are read; anything else has kind
  `document`, with `size`, `modified` and (S3) `owner` in its hints. Documents whose text Kilnmark
  reads (see [Documents](#documents)) are `readable`; others (images, archives) are not. Files are never
  `bounded`: the reader streams them. A file that cannot be catalogued (corrupt, nested too deeply,
  a Parquet codec this server lacks, a first JSON line longer than the sample) is recorded as not
  readable with the reason as its description; it does not stop the scan.
- **Sampling.** The catalogue reads only the first 1 MiB of a text file (a ranged GET on S3) and
  infers types from its first 1,000 rows; for Parquet it reads only the footer.
- **Encoding.** A byte-order mark (UTF-8, UTF-16) decides; otherwise UTF-8 if the sample is valid
  UTF-8, else windows-1252. The choice is stored in the `encoding` hint; a source's `encoding`
  setting overrides it.
- **CSV.** The first row is the header (blank names become `column_N`, repeats get `_2`); a first
  line `sep=;` (Excel's) names the delimiter and is skipped. Otherwise the delimiter (`,` `;` tab
  `|`) is the one that gives the header the most fields and keeps the same count in the rows
  below. The quote is `"`; it is `'` only when no field starts with `"`, every field that starts
  with `'` is closed by a `'` followed by the delimiter or the end of a line, and rows stay as
  even as with `"`. So `'+44 7700 900001` and `'tis` are text. An empty unquoted field is null;
  `""` is an empty string.
- **Type inference** (CSV fields and JSON values): a column gets a type only if every sampled
  value fits it, tried in order boolean (`true/false/t/f/yes/no`), int64 (no leading zeros, so
  phone numbers and codes like `007` stay text), decimal(38, largest scale seen), float64
  (scientific notation, or more than 18 decimal places), date (`yyyy-mm-dd`), time, timestamp
  (ISO 8601, `T` or space) and timestamp_tz (every value has an offset: `Z`, `+01`, `+0100`,
  `+01:00`); otherwise text. JSON strings only become dates and times: numbers in quotes stay text.
- **Values that do not parse.** The reader never fails a file because a later value does not fit
  its column's type: the value is read as null and counted, and `ROW_COUNT` carries the count as
  `unparsed`. Decimals with more places than the column's scale are rounded half-even; ones too
  large for it are unparsed. A timestamp_tz value without an offset is taken as UTC.
- **JSON.** Each object's own fields are columns, in the order first seen in the sample; nested
  objects and arrays are kept as JSON text. Fields that first appear after the sample are not read.
  JSON lines that are not valid JSON, or longer than 1 MiB, are skipped (read past without being
  kept) and counted as `skipped`; a syntax error in a `.json` document fails the read, naming the
  line.
- **Parquet** keeps its own types: 8- and 16-bit integers are int16, unsigned ones are widened,
  UTC-adjusted timestamps (and legacy INT96) are timestamp_tz, decimals up to 38 digits are
  decimal, strings, enums, JSON and UUIDs are text. Nested groups (structs, lists, maps) are text
  holding JSON; bytes inside them are written `0xCAFE`, as the JDBC connectors write bytes.
- **Safety.** The folder connector refuses paths with `..`, absolute paths and links that resolve
  outside the folder, both when listing and when reading; hidden files and folders are skipped.
- **Stopping.** A read that ends early (a row limit, or a failing later step) closes its stream or
  aborts its S3 requests, including Parquet range requests still in flight, rather than draining
  the rest of the object.
- **Changed files.** If a CSV header no longer matches the catalogue, the reader infers the
  columns again rather than misreading them.

### Documents

Libraries, limits and the reasons for them are in [ADR 0017](../adr/0017-documents.md).

- **Formats.** PDF, Word, Excel and PowerPoint (OOXML and legacy), OpenDocument, EPUB, RTF, HTML,
  XML, mail (`.eml`, `.msg`), plain text and Markdown, by extension (`DocumentTypes`).
- **Reading.** For a `document` asset, `reader` returns `DocumentInput`: it copies a remote file to a
  private temporary folder (local files are read in place), reads it with Tika on a document thread
  within the limits (`DocumentRuntime`), and reports one `DOCUMENT` event (`DocumentEvents`
  attributes: content type, pages, words, characters, language, title, author, dates, owner, tags,
  mail headers, page boundaries, notes, or `readable=false` with a `problem`) and the text's chunks
  as Arrow rows (`DocumentEvents.CHUNK_SCHEMA`). A document that cannot be read is never a failed
  step.
- **Attachments.** A mail message's attachments get `DOCUMENT` events of their own, with URN
  `<message urn>#attachments/<n>/<file name>` and the attribute `parent`; the scan records each as
  an asset inside the message.
- **Sheets.** The catalogue opens each workbook (`.xlsx`, `.xlsm`, `.xls`) and adds one asset of
  kind `sheet` per sheet, right after the workbook: path `<workbook>#<sheet>`, container the
  workbook, hint `parent`. Sheets with a header row have fields and are read by `SheetInput` like
  any table; others are `readable=false` with the reason as their description.
- **Setting `keep_text`** (both connectors, default `true`): `false` reads documents for their
  profile but sends no chunks, so no text is stored. **`read_text_on_demand`** (default `false`)
  lets stewards read such a source's text on the asset page, read from the source each time.
- **Asset paths** escape `%` and `#` (`FileSourceConnector.urnPath`) so `#` only separates a file
  from a sheet or attachment inside it; the `file` hint keeps the real path, which readers use.
- **Failures that say nothing about the document** (busy, out of time, stopped, source not reachable)
  carry `passing=true` on the `DOCUMENT` event; the scan then keeps what it stored before. A workbook
  whose sheets could not be listed gets the hint `sheets-problem`, and the scan keeps its sheets.
- **Server settings** (`kilnmark.documents.*`) reach the connectors through the `ConnectorContext`.

## Sampling and data quality

**Use a read-only login that is not a superuser for every database source.** Data quality rules
run as that login; custom SQL rules (off by default, admins only) can read anything it can see,
beyond the schemas the source lists, and refuse to run under a superuser or a login that may read
server files, run programs or signal other sessions. See the security section of
[the development guide](../development.md#security-give-every-source-a-read-only-login).

The scan's profile step (`ProfileStep`) also keeps value shapes, formats and quantiles, and with
values allowed a sample of rows: settings `keep-values`, `examples`, `sample-size`, `sample-mode`
(`key`, `row` or `reservoir`), `sample-key` and `sample-seed`. It reports the rows and the example
values per shape as one `SAMPLE` metadata event, which the server stores under the source's
`keep_samples` setting. Database connectors (`JdbcConnector`) also provide:

- `dialect()`: how the database quotes names and values, limits rows, matches regular expressions
  (none on SQL Server, so pattern rules there are checked while streaming) and makes a
  transaction read-only; data quality rules are written through it;
- `connect(source)`: a connection for checks run in the database;
- `estimateRowsSql` and `tableSampleClause`: with the source setting `table_sample_above_rows`, a
  table the database estimates above that many rows is read through `TABLESAMPLE SYSTEM`
  (repeatable) at the percentage that gives about that many rows, and its `ROW_COUNT` event says
  `sampled`, `sample_percent` and `estimated_rows`. The scan stores the estimate as the asset's row
  count, and rules streamed through such a reader are marked as estimates.

## Registering it

List the class in `kiln-plugins/src/main/resources/META-INF/services/io.kilnmark.kiln.connect.Connector`.
The UI's add-source form, bulk import (one CSV column per setting) and `GET /api/v1/connectors`
pick it up from `settings()`.

## Testing it

- Unit tests for `validate` and type mapping, and for a writer's SQL or encoding (`WriteSqlTest`,
  `FileWriteTest`).
- An integration test with Testcontainers against the real system: catalogue, a read through
  `Runner` with `ProfileStep`, a row limit, and a clear failure for a missing asset. Follow
  `PostgresIngestTest`.
- A writer: an integration test writing each mode and reading back with the connector's own
  reader, a permission error, and a failure that must leave nothing behind (`PostgresWriteTest`,
  `SqlServerWriteTest`, `S3WriteTest`).
- Keep test containers small; CI runs on a 2-CPU runner.

## Rules

- Libraries yes, platforms no ([ADR 0007](../adr/0007-dependency-policy.md)); Apache-2.0, MIT or
  BSD licences only, and say why in the commit if a dependency is large.
- Code adapted from Kettle 10.2 keeps its Apache header and is logged in
  [provenance.md](../legal/provenance.md).
