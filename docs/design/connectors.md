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
