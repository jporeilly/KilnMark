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

## JDBC databases

A database with a JDBC driver extends `JdbcConnector`, which supplies the `url`, `username` and
`schemas` settings, the catalogue from JDBC metadata and the reader. It must give its URL prefix,
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
`path`, `include` glob, `recursive`, `encoding`) and `s3` (`endpoint`, `region`, `bucket`, `prefix`,
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

## Registering it

List the class in `kiln-plugins/src/main/resources/META-INF/services/io.kilnmark.kiln.connect.Connector`.
The UI's add-source form, bulk import (one CSV column per setting) and `GET /api/v1/connectors`
pick it up from `settings()`.

## Testing it

- Unit tests for `validate` and type mapping.
- An integration test with Testcontainers against the real system: catalogue, a read through
  `Runner` with `ProfileStep`, a row limit, and a clear failure for a missing asset. Follow
  `PostgresIngestTest`.
- Keep test containers small; CI runs on a 2-CPU runner.

## Rules

- Libraries yes, platforms no ([ADR 0007](../adr/0007-dependency-policy.md)); Apache-2.0, MIT or
  BSD licences only, and say why in the commit if a dependency is large.
- Code adapted from Kettle 10.2 keeps its Apache header and is logged in
  [provenance.md](../legal/provenance.md).
