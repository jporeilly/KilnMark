# Writing a connector

A connector teaches Kilnmark a kind of data source. The server knows nothing about any
particular kind: it asks the connector registry. Adding a connector is adding classes and one
line in a services file; nothing in the server changes.

## The interface

`io.kilnmark.kiln.connect.Connector` (in `kiln-core`):

| Method | Does |
| --- | --- |
| `kind()` | Stable identifier stored with each source: `postgresql`, `sqlserver`, `mysql`, `mariadb`, `files`, `s3` |
| `displayName()` | For people: "SQL Server" |
| `settings()` | The settings a source takes, in form order (key, label, required, hint, multiple, kind, choices, default); see [Setting kinds](#setting-kinds) |
| `validate(source)` | Problems with the settings without connecting, each starting `setting: ...` |
| `catalog(source)` | Every asset the source holds, with its fields; throws `ConnectorException` with a message a person can act on |
| `reader(source, asset, read)` | The first pipeline node that reads one asset as Arrow batches |
| `withContext(context)` | Optional (default: unchanged). Returns the connector set up with server-wide settings |
| `containerKind()` | Optional. The list setting holding the containers chosen for scanning, what they are called and what their counts count (default `containers`) |
| `containers(source)` | Optional. Every container with how many assets in it the patterns keep; cheap (metadata, a listing). The default reads the full catalogue, so override it |
| `containerOf(source, asset)` | Optional. The container an asset belongs to, as `containers` names it (default: the asset's `container`) |
| `test(source)` | Optional. Checks the source can be reached, as cheaply as possible (default: `containers`) |
| `test(source, timeout)`, `containers(source, timeout)` | Optional. The same, bounding the connector's own network waits by `timeout` (default: ignore it) |
| `addressKeys()` | Optional. The settings holding the network address (`url`, `endpoint`); the only ones bulk import's host map rewrites |
| `category()`, `family()`, `capabilities()`, `formats()`, `icon()`, `docsAnchor()` | Optional. Where the connector sits in the catalogue and gallery, and what it can do today (default: other, other, discover and read, none, the category, the kind) |
| `driver()` | Optional. A driver the customer must install ([drivers.md](drivers.md)); null (the default) means bundled. Such a connector gets the installed drivers in `withContext` and connects through `JdbcConnector.connectUrl` |
| `example(key)` | Optional. A sample value for a setting, shown in forms |
| `assets(source, container)` | Optional. A container's tables or files without fields, cheaply (default: the full catalogue) |
| `asset(source, path)` | Optional. One asset with its fields, as the catalogue records it (default: the full catalogue) |
| `preview(source, asset, rows)` | Optional. The first rows, built on `reader` with a row limit, so every connector has it; override only to add details |
| `hosts(source)` | Optional. Every host the source would reach, for the server's allowed-hosts list (default: the hosts in `addressKeys` URLs) |
| `unencryptedReason(source)` | Why a connection could cross the network unencrypted, naming the setting to change (never the host), or null when every connection is encrypted or the source reaches no network; asked when `kilnmark.sources.require-tls` is on (ADR 0034). The default cannot tell, so a server requiring TLS refuses the connector's sources until it says |
| `secretScope()` | Optional. Settings that decide where the secret goes; changing one needs the secret again (default: every setting) |
| `addressSettings(host, port, database)` | Optional. Settings built from a host, port and database, for bulk import (default: none) |
| `catalogWithRelationships(source)` | Optional (default: `catalog` with no relationships). The catalogue plus the relationships the source declares, such as foreign keys; the scan calls this one |
| `writer(source, write)` | Optional (default: refuses). The pipeline node that writes its `in` port's rows to the source; see [The writer node](#the-writer-node). List `WRITE` in `capabilities()` when you override it |
| `writeOptions()` | Optional (default: none). The settings a write node takes, as `SettingSpec`s (key, kind, default, help); the service passes only these keys in `WriteOptions.options` |
| `writtenAssetPath(source, write)` | Optional (default: the target). The catalogue path of the written asset, as a scan would give it |

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

### Setting kinds

Every `Connector.Setting` has a kind, which decides the control the connection form shows and how
a save checks the value. The kinds are those of `SettingSpec.Kind` that fit a value stored as
text, plus `URL`:

| Kind | Control | A save accepts |
| --- | --- | --- |
| `TEXT` | A text box | Anything |
| `NUMBER` | A number box | A whole or decimal number (`5000`, `2.5`) |
| `BOOLEAN` | A switch | `true` or `false`, in any case; not `yes`, `on` or `1` |
| `CHOICE` | A list | One of `choices`, in any case |
| `URL` | A text box for an address | A scheme and no spaces (`https://...`, `jdbc:...`) |

Declare them with `Setting.text`, `Setting.url`, `Setting.number`, `Setting.bool` and
`Setting.choice`, giving the default where there is one (`Setting.bool("header", ..., true, ...)`).
The five-argument constructor still works and gives `TEXT` with no default. A `multiple` setting
has each comma-separated value checked. There is no secret kind on purpose: settings are stored and
shown as plain text, so a password, key or token goes in the source's secret, which is encrypted.

Values are still stored as text, so declaring a kind needs no migration, and values saved before
kinds load as they are; only a save checks them. The kind check runs before the connector's own
`validate`, which still checks what a kind cannot say (a delimiter of one character, a URL of the
right database). `GET /api/v1/connectors` gives each setting's `kind` in lower case, its `choices`
and its `defaultValue`; the settings every source has (`GET /api/v1/common-settings`) carry a
`kind` too.

The built-in connectors declare:

| Connector | Setting kinds |
| --- | --- |
| PostgreSQL | `url` URL; `username`, `schemas` TEXT |
| Oracle | `host` TEXT; `port` NUMBER (1521); `service_name`, `username`, `schemas` TEXT; `encryption` CHOICE (`accepted`, `required`, `tls`; default `accepted`) |
| MySQL, MariaDB | `host` TEXT; `port` NUMBER (3306); `database`, `username`, `server_ca`, `schemas` TEXT; `tls_mode` CHOICE (`preferred`, `disabled`, `required`, `verify-ca`, `verify-full`; default `preferred`); `tinyint1_as_boolean` BOOLEAN (true) |
| SQL Server | as PostgreSQL; `trust_server_certificate` BOOLEAN (false); `isolation` CHOICE (`read-uncommitted`, `snapshot`, `read-committed`; default `read-uncommitted`) |
| Folder | `path`, `encoding`, `delimiter` TEXT; `recursive` BOOLEAN (true); `header` BOOLEAN (true); `keep_text` BOOLEAN (true); `read_text_on_demand` BOOLEAN (false) |
| S3 | `endpoint` URL; `region`, `bucket`, `prefix`, `access_key_id`, `encoding`, `delimiter` TEXT; `path_style` BOOLEAN (false); `header`, `keep_text`, `read_text_on_demand` as for folders |
| Every source | `description`, `include`, `exclude` TEXT; `ai_egress` CHOICE; `allow_writing` BOOLEAN (false); `keep_samples` BOOLEAN (true); `sample_rows` NUMBER (0 to 10,000); `table_sample_above_rows` NUMBER (databases); `max_runs` NUMBER (1 to 100, ADR 0040) |

## The reader node

The reader is an ordinary Kiln `Node` with no input:

- each `process` call puts one Arrow batch on `context.output()`, allocated from
  `context.allocator()`, and returns `false` after the last;
- an empty asset still sends one empty batch, so the schema is known;
- when it finishes it emits a `ROW_COUNT` event for `asset-urn` with attributes `rows` and
  `complete` (false when a row limit cut the read short);
- it closes everything it opened in `close()`, which runs even after a failure.

In a pipeline graph, a node can be interrupted when another node fails, and interrupting a thread
blocked in socket I/O closes the socket: readers and writers must close any batch they hold in
`close()` or a `finally`, validate pooled connections before reuse, and roll back in `close()` when
they did not finish (docs/design/pipelines.md, "Writing a node for graphs").

Use the engine's field kinds (`JdbcColumn.Kind`: boolean, int16, int32, int64, float32,
float64, decimal, text, binary, date, time, timestamp, timestamp_tz) and the Arrow types
`JdbcColumn` maps them to, so the profiler treats every source alike. Timestamps are
microseconds. Types with no Arrow equivalent are read as text rather than dropped.

## The writer node

A connector that can write overrides `writer(source, WriteOptions)` and adds `Capability.WRITE`
to `capabilities()`. The pipeline service calls it for a `write` node after checking that the
source allows writing and that its hosts are allowed, so the connector checks only what is its
own: the target, the mode and the settings. Refuse what is wrong at once with a
`ConnectorException` whose message starts with the setting (`table: ...`, `path: ...`,
`mode: ...`), before connecting to anything.

`WriteOptions` holds the `target` (`schema.table`, or a path inside the source), the `mode`
(`create`, `append`, `replace` or `upsert`), the upsert `key`, the file `format`, the node's
further settings as written in the YAML (`options`, limited to the keys `writeOptions()` declares;
read them with `WriteSettings`, whose `withProfiling` adds the `profile_output` and `keep_values`
settings every writer takes), the `assetUrn` the written asset is recorded under, and the
source's `profiling` settings, which the writer hands to its profile.

The node is an ordinary Kiln `Node` with an input and no output:

- each `process` call takes one batch from `context.input()` (the `in` port), writes it and closes
  it; the end of the input (`null`) is where it commits or puts the file in place;
- nothing is visible until then: one transaction, a temporary file renamed at the end, or an
  upload completed at the end. `close()` runs after a failure too, and must roll back, delete or
  abort what was started;
- it reports only after success, through `WriteReport`, as
  [pipelines.md, "What a write reports"](pipelines.md#what-a-write-reports) sets out: for a write
  that leaves the asset holding exactly what was written, `SCHEMA`, `ROW_COUNT` and the profile
  (computed in the same pass with `ProfileNode`'s own code); for rows added to an existing table,
  a `ROW_COUNT` with `complete=false` and `appended=true` only;
- it takes the columns from the batches with `WriteColumn`, which turns any Arrow type into the
  engine's kinds (`JdbcColumn.Kind`) and gives each value as one Java type per kind, so every
  writer handles the same set; types with no kind of their own are written as text;
- messages say what to do: which column does not fit, which grant to ask for, which setting to
  change.

The built-in writers: `JdbcTableOutput` (PostgreSQL, SQL Server, MySQL and MariaDB; the SQL is in `WriteSql`, the
matching of input to table in `WritePlan`) and `FileOutput` (folders and S3; `FileTarget` checks
paths, `CsvEncoder` and `ParquetEncoder` write the formats, and each `FileStore` writes with
`create(path, overwrite)`, which returns an `Upload` to commit or abort). The decisions are in
[pipelines.md, section 4](pipelines.md#4-nodes-in-step-1) and the guide for people is
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
- `driverSettings` for driver properties that come from the source's settings rather than its
  URL (SQL Server's `trustServerCertificate`), added to every connection;
- `explain` to add what to do about connection failures the database is known for.

`validate` refuses a URL holding a password (`;password=` or `?password=` / `&password=`): the
URL is stored and shown as plain text, while the password field is encrypted.

Check what the driver actually reports, in result metadata and in `getColumns`, before mapping
types: they can differ (Microsoft's driver reports geography as `VARBINARY` in the catalogue and
as its own code in results), so `JdbcColumn.map` matches type names where codes are not enough.
Text columns are read with `getObject`, and bytes found there (a `sql_variant` holding
`varbinary`) are written as hex, `0xCAFE`.

When a read stops early (a later node failed), `JdbcTableInput` cancels the query before closing
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

Microsoft's driver encrypts connections and checks the server's certificate by default; `encrypt`
in the URL works as the driver documents it. The `trust_server_certificate` setting (**Trust the
server certificate**, `true` or `false`, off by default) is only for test servers with a
self-signed certificate, such as the reference lab's: when it is `true` the connector passes
`trustServerCertificate=true` to the driver as a connection property, for scans, checks, rules and
reads alike, and never edits the URL. A URL that sets `trustServerCertificate` itself still works,
but not together with the setting. Turning it on asks for the password again, since a server
whose certificate is not checked could be another server. Connectors add driver properties like
this by overriding `driverSettings`; the reader node receives them as `driver.<name>` settings.

### MySQL and MariaDB

Two kinds, `mysql` and `mariadb`, share `MySqlConnector` (`MariaDbConnector` sets the product).
Both use MariaDB Connector/J, which speaks to either; MySQL's own driver is GPL and is never
used. Each kind refuses a server of the other product (with a hint to add it as that kind) and
servers older than MySQL 8.0.19 or MariaDB 10.6.

**Settings** are the connector's own, not a JDBC URL: `host`, `port` (3306 by default),
`database` (empty: every database the login sees is a schema; set: the source keeps to it, and
writes elsewhere are refused), `username`, the password as the source's secret, `tls_mode`,
`server_ca` (a PEM certificate for `verify-ca` and `verify-full`), `tinyint1_as_boolean` and
`schemas` (the databases to scan). So the connection form shows them as fields, and its host,
port and database fields that build a URL for other databases are off (`addressFields` false
in `GET /api/v1/connectors`). No driver property can be given: the connector sets them all, so
a source cannot make the driver read local files, follow a server's redirect to another host or
run statements of its own.

| `tls_mode` | Effect |
| --- | --- |
| `preferred` (default) | Encrypts when the server offers TLS, without checking its certificate; connects without TLS when the server has none |
| `disabled` | Never encrypts |
| `required` | Always encrypts, without checking the certificate |
| `verify-ca` | Encrypts and checks the certificate against `server_ca` (or the certificates the server trusts) |
| `verify-full` | As `verify-ca`, and checks the host name |

Without `server_ca`, `verify-ca` and `verify-full` check the certificate against the certificates
Kilnmark's server trusts. Against MariaDB 11.4 and later they also accept the server's own
self-signed certificate when the driver can prove it with the login's password (MariaDB's
zero-configuration TLS: the server signs the certificate's fingerprint with the password hash,
for logins using `mysql_native_password`, `ed25519` or `parsec`); a man in the middle without the
password cannot pass. Against MySQL, or with any other login, give `server_ca` for a certificate
those authorities did not sign.

**Types** (`MySqlTypes`): integers by size, `UNSIGNED` widened (`INT UNSIGNED` to int64, `BIGINT
UNSIGNED` to decimal(20,0)); `TINYINT(1)` as boolean while `tinyint1_as_boolean` is true;
`DECIMAL` up to 38 digits as decimal, wider as text; `YEAR` as int16; `DATE`, `DATETIME`
(wall-clock) and `TIMESTAMP` (an instant: sessions use time zone `+00:00`), read from the
server's text, never through `java.sql` types; zero and impossible dates (`0000-00-00`) as null,
counted in the read's `ROW_COUNT` as `zero_dates`; `TIME` as text, since it is a duration from
-838:59:59 to 838:59:59; `JSON`, `ENUM` and `SET` as text; spatial types as well-known text through
`ST_AsText`; anything else as text. The catalogue comes from `information_schema` (`MySqlCatalog`):
table and column comments, primary keys and declared foreign keys, including keys to other
databases the source scans.

**Reads** stream in a read-only transaction at the server's isolation level (InnoDB's consistent
snapshot). **Writes** follow the writer contract: `create`, `append`, `upsert` (`INSERT … ON
DUPLICATE KEY UPDATE`) and `replace` (fill `kiln_new_…`, then one atomic `RENAME TABLE`). Their
DDL commits by itself, so a failed create or replace drops the table it made. Tables the node
creates are loaded with `LOAD DATA LOCAL INFILE` streamed from memory when the server allows
`local_infile`, otherwise with batched inserts; after each load `@@warning_count` must be 0.
Sessions set a fixed `sql_mode` (`STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,
ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`), never the server's, whose modes could change
data silently. Rights are checked from `information_schema`'s privilege views before any row is
written.

Because `ON DUPLICATE KEY UPDATE` updates the row any unique index matches, upsert refuses a
table with a unique index that lacks a key column (prefix and expression parts count as lacking
it), naming the index, unless `allow_other_unique_keys` is true. Replace also refuses tables of
engines other than InnoDB, Aria and MyISAM, and, unless the login itself holds `TRIGGER`, tables
whose triggers it cannot see. Foreign keys from tables the login cannot see are looked up in
`INNODB_FOREIGN` (MariaDB: `INNODB_SYS_FOREIGN`) when it has `PROCESS`; otherwise, if the old
table's `DROP` fails with 1451, 1217 or 3730 after the swap, one `RENAME TABLE` puts both names
back (the key follows the table) and the replace fails. Any other failure to drop `kiln_old_…`
keeps the new rows and puts a `write_warning` on the `ROW_COUNT` event, which the server shows as
a note on the run. Values checked per row: DATETIME(p), TIMESTAMP(p) and TIME(p) refuse more
digits of a second than p; TIME columns take only times and text; YEAR takes 1901 to 2155. On a
server with `lower_case_table_names` not 0 the target is written, and its asset recorded, in
lower case.

### Oracle

Kind `oracle`, Oracle Database 19c and later, through Oracle's JDBC driver (`ojdbc11`), which
Kilnmark does not ship: the customer installs it ([drivers.md](drivers.md)). The connector uses
only the standard JDBC interfaces and reaches the driver through `JdbcDrivers`; without it, a
connection test says who installs it.

**Settings** are the connector's own: `host`, `port` (1521), `service_name` (a service, not a
SID), `username`, the password as the source's secret, `encryption` and `schemas`. The address is
built as EZConnect (`jdbc:oracle:thin:@//host:port/service`, `@tcps://` for TLS); no driver
property, TNS alias or wallet can be given, so a source cannot point the driver at this server's
files or another host. `encryption` is `accepted` (the default: native encryption when the server
asks), `required` (native encryption and checksums always) or `tls` (TCPS, the certificate checked
against the server's Java trust store and its name against the host). Sessions use time zone
`+00:00`, ISO date formats and `.` as the decimal point, so dates in rules compare as written.
The driver's `oracle.net.tns_admin` is an empty private folder, so no `ojdbc.properties` or
`tnsnames.ora` of the environment's choosing feeds it. `required` and `tls` are not tested against a
real listener (the CI database offers neither TCPS nor required native encryption): the
properties are set as Oracle documents them, and only `accepted` is exercised end to end.

**Catalogue** from the `ALL_*` views (`OracleCatalog`), so a login sees what it is granted: users
as schemas (Oracle's own, `ORACLE_MAINTAINED`, are left out of a scan of every schema), tables and
views with comments, the optimiser's `NUM_ROWS` as the row estimate, primary keys, unique
constraints, and foreign keys, also across schemas. Names are used as the dictionary spells them,
quoted, so `"odd name"` and lower-case names work; a schema written in lower case in `schemas`
finds the upper-case user.

**Types** (`OracleTypes`), never rounded: `NUMBER(p)` by size (int16, int32, int64, decimal beyond
18 digits); `NUMBER(p,s)` as decimal, a negative scale as decimal(p−s,0); `NUMBER` without
precision as text holding its exact digits (it can hold 38 digits at any scale, which no fixed
decimal type can); `FLOAT(b)` (with `DOUBLE PRECISION` and `REAL`, which are FLOAT) as text too,
since it is a decimal NUMBER of up to 38 digits at any scale; `BINARY_DOUBLE` as float64 and
`BINARY_FLOAT` as float32; `DATE` (which keeps a time) and `TIMESTAMP` as timestamp;
`TIMESTAMP WITH TIME ZONE` as timestamp_tz, and `WITH LOCAL TIME ZONE` too, selected as
`CAST(… AS TIMESTAMP(9) WITH TIME ZONE)` so the driver never applies the JVM's time zone (the
integration tests run in `America/New_York` to prove it);
`BOOLEAN` as boolean; character types, `CLOB`, intervals, `ROWID`, `JSON`, `XMLTYPE` (through
`XMLSERIALIZE`) and object types as text; `RAW` and `BLOB` as binary; `BFILE` as null. Reads use
`FETCH FIRST n ROWS ONLY` and, for sampling, `SAMPLE (p) SEED (7)`.

**Writes** (`OracleWriteSql`) follow the writer contract. DDL commits by itself, so `create` and
`replace` fill `kiln_new_…` and rename it at the end; replace swaps in one PL/SQL block that
renames the old table away, the new one in, and drops the old one (`PURGE`), putting the names
back if any step fails. Oracle cannot rename two tables at once, so for that instant a reader may
not find the table. Grants are repeated on the new table (`keep_grants`). Upsert is one `MERGE` a
row and needs a unique key. Plain lower-case names are created in upper case, as Oracle stores
unquoted names. Nothing changes silently: decimals are never rounded (also past 38 significant
digits, or a `FLOAT(b)` column's ceil(b × 0.30103), or outside a NUMBER's range of 1E-130 to
1E126), a value with more digits of a second than a `DATE` or `TIMESTAMP(n)` keeps is refused, and
empty text and empty bytes, which Oracle stores as null, are refused unless `empty_text_as_null`
is true, and in an upsert key always. Text is checked against the column's character set
(`NLS_CHARACTERSET`, or `NLS_NCHAR_CHARACTERSET` for NVARCHAR2, NCHAR and NCLOB, which are bound
with `setNString`): on a database that is not Unicode, a character the set lacks would be stored as
`?`, so the row is refused, naming the character; a set Kilnmark does not know is taken to hold
ASCII only. Text columns are checked against their size in bytes (`DATA_LENGTH`, in the column's
character set; `CHAR_USED` = `B` columns in bytes only), which also holds the 4,000-byte limit of
`MAX_STRING_SIZE=STANDARD`.
Rights are checked from `SESSION_PRIVS`, `ALL_TAB_PRIVS`, `ALL_COL_PRIVS` and `ROLE_TAB_PRIVS`
before a row is written. Replace refuses views, materialized views, partitioned and temporary
tables, tables with triggers or materialized view logs, and tables other tables' foreign keys
refer to; one the login cannot see stops the drop, and the names are put back.

Its tests run against Oracle Database Free (`gvenzl/oracle-free`, pinned) in CI's heavy-databases
job, in a Gradle run of their own (`./gradlew :kiln-plugins:oracleTest`).

### Generic JDBC

Kind `jdbc`, any database with a JDBC driver Kilnmark has no connector of its own for (Db2,
Teradata, Snowflake, Sybase, Informix, H2), through a driver an admin registered
([drivers.md](drivers.md#registered-drivers), [ADR 0025](../adr/0025-registered-jdbc-drivers.md)).
`GenericJdbcConnector`, `GenericCatalog`, `GenericTypes`, `GenericWriteSql`.

**Settings:** `driver` (CHOICE of the registered drivers, read each time the form asks; free text
when none is registered, with a hint to register one), `url` (TEXT: must start `jdbc:`, start as
the registration's URL prefix, and be accepted by the driver's own `acceptsURL`), `username`, the
password as the source's secret (SecretBox, like every connector), `properties` (TEXT, `name=value`
separated by `;` or new lines; passed with every connection, never written into the URL), `schemas`
and `read_only` (BOOLEAN, default true).

Every setting in the URL (`JdbcUrlSettings`: `key=value` after `;`, `?`, `&`, `:`, `/` or `,`, or
inside `( )`) and every property must be on the driver's **allow-list**, given at registration; a
URL holding an `=` no such setting accounts for, or quotes, braces, back-slashes, spaces or
percent-encoding, is refused. Secrets (pass, pwd, secret, token, key, credential) and the user name
are refused in both whatever the list, and the URL is never quoted in a message. The same check runs
again when a connection is opened. `Connector.adminOnly()` is true: only admins add such a source,
change these settings or check an unsaved one. `hosts` gives the URL's hosts and those its settings
and properties name (failover partners, `*servername`, `alternate*`, values that are URLs), or a
placeholder no allow-list holds when the URL's host or settings cannot be read.

**Catalogue** from `DatabaseMetaData`: containers are the schemas of the connection's catalogue,
or its catalogues when the driver reports no schemas, or one container `default` (`GenericCatalog.Level`).
Table types are those `getTableTypes` lists that are tables or views (not system tables, aliases,
synonyms, temporary tables); when it lists none, every row is read and the same test applied. Nulls
fall back: a missing type is a table, a missing position the driver's order, an unknown nullability
allows nulls, a missing size or scale counts as none, primary keys a driver cannot give are left
out. Foreign keys (`getImportedKeys`) are read where tables sit in schemas. Well-known system
schemas (SQL standard, Db2, Teradata, Derby, Sybase, Informix) are skipped in a scan of everything.

**Types** (`GenericTypes`), the same from the catalogue and from a result, as `JdbcColumn` maps
them for the dedicated connectors, and never rounded:

| JDBC type (or type name) | Engine type | Note |
| --- | --- | --- |
| `BIT` (one bit), `BOOLEAN` | boolean | `BIT(n)` with several bits: text, with a note |
| `TINYINT`, `SMALLINT` | int16 | `SMALLINT UNSIGNED` int32 |
| `INTEGER` | int32 | `INTEGER UNSIGNED` int64 |
| `BIGINT` | int64 | `BIGINT UNSIGNED` decimal(20,0) |
| `REAL` | float32 | |
| `FLOAT`, `DOUBLE` | float64 | |
| `DECIMAL`, `NUMERIC` with precision 1 to 38 and scale 0 to precision | decimal(p,s) | read as `BigDecimal`, never a double; a value wider than declared stops the read |
| `DECIMAL`, `NUMERIC` otherwise (no precision, more than 38 digits, negative scale) | text, exact digits | note on the column |
| `DATE` | date | |
| `TIME` | time | `TIME WITH TIME ZONE`: text, with a note |
| `TIMESTAMP` | timestamp (wall clock) | |
| `TIMESTAMP WITH TIME ZONE` (or a name saying so, `TIMESTAMPTZ`, `TIMESTAMP_TZ`, `DATETIMEOFFSET`), `WITH LOCAL TIME ZONE`, `TIMESTAMP_LTZ` | timestamp_tz (UTC) | |
| `CHAR`, `VARCHAR`, `LONGVARCHAR`, `N…` variants, `CLOB`, `NCLOB`, `SQLXML`, `ROWID` | text | |
| `BINARY`, `VARBINARY`, `LONGVARBINARY`, `BLOB` | binary | |
| anything else: `OTHER` (JSON, intervals), `ARRAY`, `STRUCT`, `JAVA_OBJECT`, vendor codes | text, as the driver writes it | note naming the type |

Values are read as `java.time` types; a driver that refuses them is read through the `java.sql`
types from then on (zoned values with a UTC calendar, never the JVM's zone). Times kept to more than
six digits of a second (Db2's `TIMESTAMP(12)`) are read to the microsecond, and the column's
description says the rest is cut.

**Reading** is `SELECT *` from the table, its name quoted as `getIdentifierQuoteString` says and
built once connected; previews use `Statement.setMaxRows` (no SQL row limit works everywhere).
A driver that refuses `setReadOnly` or `setAutoCommit(false)` is read anyway. Without an identifier
quote, only plain names (letters, digits, `_`) are read. Tables are never sampled. Profiling and
preview work as for the other JDBC sources. Data quality
rules are checked on the rows read, not as SQL in the database (`JdbcConnector.runsRulesInDatabase()`
is false), so no vendor SQL is guessed.

**Writes** (`GenericWriteSql`, through `JdbcTableOutput`) are `append` and `create` only, with
batches of one prepared `INSERT` binding every value, and only when `read_only` is off (and the
source allows writing). `checkWrite` checks without connecting: settings, `read_only`, the mode, no
key, no format, the table's name. `writeOptions` is `commit_every`, `batch_size`, `text_length` and
the profiling settings. Everything else comes from `DatabaseMetaData`: whether the table exists,
its columns (`COLUMN_SIZE`, `DECIMAL_DIGITS`) and which need a value, the column types a create
uses (`getTypeInfo`; a kind with no type, such as a timestamp with time zone on a database without
one, refuses the create; time types ask for six digits of a second where the type keeps them), name
lengths and quoting. A created table is described again once made, so every value is checked
against the columns the database actually made, as for an append: decimals never rounded, text and
bytes never cut (text also in UTF-8 bytes against `CHAR_OCTET_LENGTH` where the driver reports it,
for columns sized in bytes; a driver that reports the character length there, as H2 does, makes this
stricter than it needs to be), fractions of a second the column does not keep, `TINYINT` and
unsigned ranges. A column whose size the driver does not report, or of a type Kilnmark does not know,
is refused before a row is written. The target schema must be one the driver lists. A driver that
stays in auto-commit or offers no transactions is refused (for every database writer). Where table
definitions commit by themselves (`dataDefinitionCausesTransactionCommit`), a failed create drops the
table again, but only when no rows were committed (`commit_every`) and the table is empty, so rows
another writer put there are never lost; until the write ends, the new table is visible to others. The `ROW_COUNT` event carries `profiled`,
`profile_error` and `write_warning` as for every writer (`WriteReport`).

`replace` is refused: swapping a table in safely needs renaming, grants and transactional table
definitions, which no SQL does the same way everywhere. `upsert` is refused: an update-or-insert
without races needs `MERGE` or its like with the right locking, which differs between databases.
Both say so, and point at append, create or a dedicated connector.

Its tests (`GenericJdbcConnectorTest`) register H2 through the drivers folder and run in the
ordinary build.

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
- **Stopping.** A read that ends early (a row limit, or a failing later node) closes its stream or
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
  node.
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

The scan's profile node (`ProfileNode`) also keeps value shapes, formats and quantiles, and with
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
pick it up from `settings()`. Give every setting its kind ([Setting kinds](#setting-kinds)): the
form then shows switches, lists and numbers without guessing.

## Testing it

- Unit tests for `validate` and type mapping, and for a writer's SQL or encoding (`WriteSqlTest`,
  `FileWriteTest`).
- An integration test with Testcontainers against the real system: catalogue, a read through
  `Runner` with `ProfileNode`, a row limit, and a clear failure for a missing asset. Follow
  `PostgresIngestTest`.
- A writer: an integration test writing each mode and reading back with the connector's own
  reader, a permission error, and a failure that must leave nothing behind (`PostgresWriteTest`,
  `SqlServerWriteTest`, `S3WriteTest`).
- Keep test containers small; CI runs on a 2-CPU runner.
- A lab dataset for the connector lives only in `lab/` (its compose service, seed data and row in
  `lab/sources.csv`) and in test resources: never in main code, defaults, examples or the user
  guides. `./gradlew release` checks this ([ADR 0024](../adr/0024-the-lab-is-not-the-product.md)).

## Definition of done

A connector is done when:

- Its tests above pass in CI.
- **It ships with a lab dataset.** The reference lab ([lab/README.md](../../lab/README.md)) gets
  synthetic data for it: a compose service in `lab/compose.yaml` with a profile named after the
  source (plus `all`) and a seed that loads on first start, or files under `lab/files`. It also
  gets a row in `lab/sources.csv`, a line in the source tables of `lab/lab.ps1` and `lab/lab.sh`,
  a README section saying what is in the data and what discovery should find, and its row in the
  README's lab coverage table. All of it is synthetic: invented names, example.com addresses,
  numbers from ranges reserved for fiction.
- **SaaS and cloud-only connectors** that cannot run locally ship instead with a documented mock or
  emulator in the lab (an emulator for a cloud store, a mock server for an API) and a recipe for a
  free test account: how to create it, what to load into it, and which settings the source needs.

## Rules

- Libraries yes, platforms no ([ADR 0007](../adr/0007-dependency-policy.md)); Apache-2.0, MIT or
  BSD licences only, and say why in the commit if a dependency is large. The one exception is the
  MariaDB Connector/J driver (LGPL 2.1, that module only, shipped unmodified as its own jar); no
  GPL driver, not even in tests.
- Code adapted from Kettle 10.2 keeps its Apache header and is logged in
  [provenance.md](../legal/provenance.md).
