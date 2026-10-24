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
