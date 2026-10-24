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

## Files and object storage

Two connectors share `io.kilnmark.kiln.plugins.files`: `files` (a folder the server can read:
`path`, `include` glob, `recursive`) and `s3` (`endpoint`, `region`, `bucket`, `prefix`,
`access_key_id`, `path_style`; the secret is the secret access key). Libraries and the reasons
for them are in [ADR 0015](../adr/0015-file-formats.md).

- **Assets.** Every file is an asset. Its path is the path within the folder (with `/`) or the
  object key; its container is the folder, or the bucket and folder. CSV, TSV, JSON, JSON lines
  (`.jsonl`, `.ndjson`) and Parquet have kind `file` and are read; anything else has kind
  `document` and `readable=false`, with `size` and `modified` in its hints. Files are never
  `bounded`: the reader streams them. A file that cannot be catalogued (a corrupt Parquet file,
  say) is recorded as not readable with the reason as its description; it does not stop the scan.
- **Sampling.** The catalogue reads only the first 1 MiB of a text file (a ranged GET on S3) and
  infers types from its first 1,000 rows; for Parquet it reads only the footer.
- **CSV.** The first row is the header (blank names become `column_N`, repeats get `_2`). The
  delimiter (`,` `;` tab `|`) is the one that gives the header the most fields and keeps the same
  count in the rows below; the quote is `"` unless fields only start with `'`. UTF-8, with a
  UTF-8 or UTF-16 byte-order mark skipped. An empty unquoted field is null; `""` is an empty string.
- **Type inference** (CSV fields and JSON values): a column gets a type only if every sampled
  value fits it, tried in order boolean (`true/false/t/f/yes/no`), int64 (no leading zeros, so
  phone numbers and codes like `007` stay text), decimal(38, largest scale seen), float64
  (scientific notation, or more than 18 decimal places), date (`yyyy-mm-dd`), time, timestamp
  (ISO 8601, `T` or space) and timestamp_tz (every value has an offset: `Z`, `+01`, `+0100`,
  `+01:00`); otherwise text. JSON strings only become dates and times: numbers in quotes stay text.
- **Values that do not parse.** The reader never fails a file because a later value does not fit
  its column's type: the value is read as null and counted, and `ROW_COUNT` carries the count as
  `unparsed`. Decimals with more places than the column's scale are rounded half-even; ones too
  large for it are unparsed. A timestamp_tz value without an offset is taken as UTC. JSON lines
  that are not valid JSON are skipped and counted as `skipped`; a syntax error in a `.json`
  document fails the read, naming the line.
- **JSON.** Each object's own fields are columns, in the order first seen in the sample; nested
  objects and arrays are kept as JSON text. Fields that first appear after the sample are not read.
- **Parquet** keeps its own types: 8- and 16-bit integers are int16, unsigned ones are widened,
  UTC-adjusted timestamps (and legacy INT96) are timestamp_tz, decimals up to 38 digits are
  decimal, strings, enums, JSON and UUIDs are text, and nested groups are read as text.
- **Safety.** The folder connector refuses paths with `..`, absolute paths and links that resolve
  outside the folder, both when listing and when reading; hidden files and folders are skipped.
- **Changed files.** If a CSV header no longer matches the catalogue, the reader infers the
  columns again rather than misreading them.

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
