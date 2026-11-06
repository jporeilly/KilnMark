# Reference: connectors

Every connector Kilnmark ships, with what it can do and every setting it takes. A connector reaches
one kind of source: a database, a folder, a bucket. A **source** is a saved connection made with
one: its address, its login and, encrypted, its password or key. Pipelines and flows name the
source, never the credentials. For the other kinds of component, see the
[Components reference](components-reference.md).

Each entry gives the connector's kind (the `kind` a source is saved with) and where the **Add
connection** gallery lists it; what it can do; what it can do in flows; its driver; who may set one
up; its settings; and the settings a write node writing to it takes. All of that comes from the
connectors the server loads, so it matches this version. Then come how to sign in, notes on the
database or store, and a pipeline you can copy.

What the abilities mean:

- **Scan**: list the source's tables or files and profile them into the catalogue.
- **Read** and **Write**: read rows in a pipeline's `read` node, write them in its `write` node.
- **Preview**: show a table's or file's first rows, for stewards.
- **Explore**: show a database's schemas, tables, columns, keys and indexes, and generate their SQL
  ([Database tools](database-tools.md)).
- **Your own SQL**: SQL a person writes: a read node's `query`, custom SQL quality rules, and the
  flow `sql` and `wait-for` query nodes. Only with the source's Allow SQL on.

## Settings every source has

Every source has these besides its connector's own:

[generated: source settings]: #

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `description` | text |  | What this source holds and who looks after it. |
| `ai_egress` | choice | `local_only` | local_only: only models on servers marked local may see this source's metadata and data. allowed: any configured model. none: no model at all. One of `local_only`, `allowed`, `none`. |
| `include` | text, several |  | Only tables or files matching these patterns, ignoring case. cust* or *.csv match names; crm.* (a schema's tables) or raw/** (a folder's files) match paths. Leave empty for all. |
| `exclude` | text, several |  | Leave out tables or files matching these patterns, for example tmp_*, *_backup or archive/**. |
| `allow_writing` | true or false | `false` | true lets pipelines write tables or files to this source, with an account that has write rights; false keeps it read-only. Only designers and admins can change it. |
| `allow_sql` | true or false | `false` | PostgreSQL only: true lets designers whose token sees unmasked values write SQL that runs on this source (custom quality rules). Give the source a login that can read only what that SQL needs. false (the default) runs only the SQL Kilnmark builds. |
| `keep_samples` | true or false | `true` | true (the default) keeps sample rows, example values and failing rows for stewards to see; false keeps only shapes and statistics. |
| `sample_rows` | number |  | Rows sampled from each table or file for profiling, up to 10000. Leave empty for the server's setting. |
| `table_sample_above_rows` | number |  | Databases only: tables with more rows than this, at least 1000, are profiled from a sample of about that many rows. Leave empty to read every row. |
| `max_runs` | number |  | How many pipeline runs and flow sql nodes may use this source at the same time, from 1 to 100. Others wait their turn, without failing. Leave empty for no limit. |

[end: source settings]: #

**Governance.** Designers and admins add, change and delete sources; stewards test, scan, preview
and explore them; viewers see them. Turning **Allow writing** or **Allow SQL** on or off is for
designers and admins only, and each change is recorded in the audit log (`source.allow_writing`,
`source.allow_sql`), as are changes to `max_runs` and deleting a source. A source's password or key
is encrypted, never shown again and never sent to an address it was not saved for: changing the
address asks for it again. Which hosts sources may reach is the administrator's to set, and a
server can refuse sources that are not encrypted.

Runs started by a schedule or by `kilnmark-run` use sources exactly as runs started by a person:
the source's own login, Allow writing and Allow SQL, and its `max_runs`, under which further runs
wait their turn ([Running pipelines and flows on a schedule](schedules.md),
[Running from an external scheduler](external-schedulers.md)).

## Versions tested

Each connector's integration tests run against a real server in CI, pinned to these images:

| Connector | Tested against | Image |
| --- | --- | --- |
| PostgreSQL | PostgreSQL 18 | `docker.io/library/postgres:18` |
| SQL Server | SQL Server 2022 | `mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04` |
| MySQL | MySQL 8.4 | `docker.io/library/mysql:8.4.10` |
| MariaDB | MariaDB 11.4 | `docker.io/library/mariadb:11.4.12` |
| Oracle | Oracle Database 23ai Free | `docker.io/gvenzl/oracle-free:23.26.3-slim-faststart` |
| S3 object storage | an S3-compatible gateway | `ghcr.io/versity/versitygw:v1.10.0` |
| Generic JDBC | H2, through the drivers folder | (in the ordinary build) |

SQL Server, MySQL, MariaDB and Oracle run in CI's heavy-databases job. MySQL needs 8.0.19 or later
and MariaDB 10.6 or later; Oracle 19c or later. The build fails if the tests move to another image
and this table does not follow.

## PostgreSQL

[generated: connector postgresql]: #

Kind `postgresql`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | yes, with Allow SQL on |

- In flows: `check-source` can reach it and look for its tables; `wait-for` can wait for a query; a `sql` node can run on it, with Allow SQL and Allow writing on
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `url` | address | yes |  | For example jdbc:postgresql://db.example.com:5432/sales. |
| `username` | text | no |  | A read-only account is enough for scanning; pipelines that write to this source need a login with write rights. |
| `schemas` | text, several | no |  | Leave empty to scan every schema, or choose them from a list. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time (1,000; 5,000 for SQL Server inserts). |
| `text_length` | number | `0` | For create and replace: the length of text columns; 0 (the default) for unlimited. |
| `keep_grants` | true or false | `true` | For replace: repeat the old table's grants on the new one. |
| `allow_dropping_row_security` | true or false | `false` | For replace: replace a table with row-level security, whose policies the new table will not have. |
| `lock_timeout` | number | `30` | For replace: how long the swap waits for others using the old table. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector postgresql]: #

**Signing in.** A user name and password (the password as the source's secret). TLS is set in the
URL as PostgreSQL's driver documents it (`sslmode=verify-full`); the URL may not hold a password.

**What to know.** The only kind that runs SQL a person writes. Queries, custom rules and wait-for
queries run in a read-only transaction: for them, give the source a login that can read only what
that SQL needs. A flow `sql` node changes data, so its source also needs Allow writing and a login
that may make those changes, and nothing more. Kilnmark refuses to run such SQL under a superuser or a login that may read files or run
programs. Upsert needs a unique key on `key`.

```yaml
kilnmark: pipeline/v1
name: postgres-customers-copy
description: Customers from one PostgreSQL source into another.

nodes:
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers
    where: "status = 'active'"

  - id: out
    type: write
    input: customers
    source: warehouse
    table: crm.customers
    mode: replace
    keep_grants: true
```

Related: [Writing data from a pipeline](writing-data.md), [Custom SQL](quality-rules.md#custom-sql-designers).

## SQL Server

[generated: connector sqlserver]: #

Kind `sqlserver`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | no |

- In flows: `check-source` can reach it and look for its tables
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `url` | address | yes |  | For example jdbc:sqlserver://db.example.com:1433;databaseName=sales;encrypt=true. |
| `username` | text | no |  | A read-only account is enough for scanning; pipelines that write to this source need a login with write rights. |
| `schemas` | text, several | no |  | Leave empty to scan every schema, or choose them from a list. |
| `trust_server_certificate` | true or false | no | `false` | Only for test servers with a self-signed certificate. Off by default. With it off, the certificate must be valid. |
| `isolation` | choice | no | `read-uncommitted` | read-uncommitted (the default) takes no shared locks, so reading never blocks the application's writes, but may see changes not yet committed; snapshot reads a consistent view without locks if the database allows snapshot isolation; read-committed takes shared locks and can block writers. One of `read-uncommitted`, `snapshot`, `read-committed`. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time (1,000; 5,000 for SQL Server inserts). |
| `text_length` | number | `0` | For create and replace: the length of text columns; 0 (the default) for unlimited. |
| `keep_grants` | true or false | `true` | For replace: repeat the old table's grants on the new one. |
| `allow_dropping_row_security` | true or false | `false` | For replace: replace a table with row-level security, whose policies the new table will not have. |
| `lock_timeout` | number | `30` | For replace: how long the swap waits for others using the old table. |
| `bulk_copy` | true or false | `true` | Load tables this node creates through the driver's bulk copy. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector sqlserver]: #

**Signing in.** A SQL Server login and password. Microsoft's driver encrypts connections and checks
the server's certificate by default; `trust_server_certificate` is only for test servers with a
self-signed certificate, and turning it on asks for the password again.

**What to know.** `isolation` decides how profiling reads see the application's changes: the
default takes no shared locks, so a long read never holds up a production database. Geography,
geometry and hierarchyid columns are read as text.

```yaml
kilnmark: pipeline/v1
name: sqlserver-orders-copy
description: Orders kept in a second table on the same server.

nodes:
  - id: orders
    type: read
    source: finance-db
    asset: sales.orders

  - id: out
    type: write
    input: orders
    source: finance-db
    table: sales.orders_copy
    mode: replace
    bulk_copy: true
```

## MySQL

[generated: connector mysql]: #

Kind `mysql`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | no |

- In flows: `check-source` can reach it and look for its tables
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `host` | text | yes |  | The MySQL server's name or address. |
| `port` | number | no | `3306` | Leave empty for 3306, MySQL's usual port. |
| `database` | text | no |  | Leave empty to see every database as a schema, or give one database to keep the source to it. |
| `username` | text | yes |  | A login with SELECT is enough for scanning; pipelines that write to this source need a login with write rights. |
| `tls_mode` | choice | no | `preferred` | preferred (the default) encrypts when the server offers TLS, without checking its certificate; required always encrypts; verify-ca also checks the certificate against the server CA certificate; verify-full also checks the host name; disabled never encrypts. One of `preferred`, `disabled`, `required`, `verify-ca`, `verify-full`. |
| `server_ca` | text | no |  | For verify-ca and verify-full: the PEM certificate (-----BEGIN CERTIFICATE----- ...) of the authority that signed the server's certificate. Leave empty to use the certificates this server trusts. |
| `tinyint1_as_boolean` | true or false | no | `true` | true (the default) reads TINYINT(1) columns, which is how MySQL stores BOOLEAN, as true or false; false reads them as numbers. |
| `schemas` | text, several | no |  | Leave empty to scan every database, or choose them from a list. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time (1,000; 5,000 for tables the node creates). |
| `text_length` | number | `0` | For create and replace: VARCHAR columns of this length (at most 16,383); 0 (the default) for LONGTEXT. |
| `lock_timeout` | number | `30` | For replace: how long the swap waits for others using the old table. |
| `bulk_load` | true or false | `true` | Load tables this node creates with LOAD DATA LOCAL INFILE, sent from memory, when the server allows it (local_infile); otherwise, or when false, with batched inserts. |
| `allow_other_unique_keys` | true or false | `false` | For upsert: a row matching any unique index is updated, not only one matching the key, so a table with a unique index lacking a key column is refused. true allows it, accepting that such rows are updated. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector mysql]: #

**Signing in.** A login and password. `tls_mode` chooses encryption, from `disabled` to
`verify-full`; give `server_ca` for a certificate the server's trusted authorities did not sign.

**What to know.** Read through MariaDB Connector/J, which ships with Kilnmark; MySQL's own driver
is never used. The connector sets every driver property itself, so a source cannot make the driver
read local files or follow a redirect to another host. A MySQL source refuses a MariaDB server,
and the other way round. Upsert refuses a table with a unique index that lacks a key column
unless `allow_other_unique_keys` is true.

```yaml
kilnmark: pipeline/v1
name: mysql-orders-upsert
description: Orders kept up to date by key.

nodes:
  - id: orders
    type: read
    source: shop-db
    asset: sales.orders

  - id: out
    type: write
    input: orders
    source: shop-db
    table: sales.orders_current
    mode: upsert
    key: [order_id]
```

## MariaDB

[generated: connector mariadb]: #

Kind `mariadb`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | no |

- In flows: `check-source` can reach it and look for its tables
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `host` | text | yes |  | The MariaDB server's name or address. |
| `port` | number | no | `3306` | Leave empty for 3306, MariaDB's usual port. |
| `database` | text | no |  | Leave empty to see every database as a schema, or give one database to keep the source to it. |
| `username` | text | yes |  | A login with SELECT is enough for scanning; pipelines that write to this source need a login with write rights. |
| `tls_mode` | choice | no | `preferred` | preferred (the default) encrypts when the server offers TLS, without checking its certificate; required always encrypts; verify-ca also checks the certificate against the server CA certificate; verify-full also checks the host name; disabled never encrypts. One of `preferred`, `disabled`, `required`, `verify-ca`, `verify-full`. |
| `server_ca` | text | no |  | For verify-ca and verify-full: the PEM certificate (-----BEGIN CERTIFICATE----- ...) of the authority that signed the server's certificate. Leave empty to use the certificates this server trusts. |
| `tinyint1_as_boolean` | true or false | no | `true` | true (the default) reads TINYINT(1) columns, which is how MariaDB stores BOOLEAN, as true or false; false reads them as numbers. |
| `schemas` | text, several | no |  | Leave empty to scan every database, or choose them from a list. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time (1,000; 5,000 for tables the node creates). |
| `text_length` | number | `0` | For create and replace: VARCHAR columns of this length (at most 16,383); 0 (the default) for LONGTEXT. |
| `lock_timeout` | number | `30` | For replace: how long the swap waits for others using the old table. |
| `bulk_load` | true or false | `true` | Load tables this node creates with LOAD DATA LOCAL INFILE, sent from memory, when the server allows it (local_infile); otherwise, or when false, with batched inserts. |
| `allow_other_unique_keys` | true or false | `false` | For upsert: a row matching any unique index is updated, not only one matching the key, so a table with a unique index lacking a key column is refused. true allows it, accepting that such rows are updated. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector mariadb]: #

**Signing in.** As MySQL. Against MariaDB 11.4 and later, `verify-ca` and `verify-full` also accept
the server's own self-signed certificate when the driver can prove it with the login's password.

**What to know.** As MySQL, through the same connector and driver.

```yaml
kilnmark: pipeline/v1
name: mariadb-orders-copy
description: Orders kept in a second table.

nodes:
  - id: orders
    type: read
    source: stock-db
    asset: sales.orders

  - id: out
    type: write
    input: orders
    source: stock-db
    table: sales.orders_copy
    mode: replace
    bulk_load: false
```

## Oracle

[generated: connector oracle]: #

Kind `oracle`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | no |

- In flows: `check-source` can reach it and look for its tables
- Driver: installed by an admin (`com.oracle.database.jdbc:ojdbc11` 23.26.3.0.0, Oracle Free Use Terms and Conditions (FUTC); Kilnmark may not ship it)
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `host` | text | yes |  | The Oracle server's name or address. |
| `port` | number | no | `1521` | Leave empty for 1521, Oracle's usual port. |
| `service_name` | text | yes |  | The database service to connect to, for example FREEPDB1 or sales.example.com (ask the database's administrator; not a SID). |
| `username` | text | yes |  | A login with CREATE SESSION and SELECT on the tables is enough for scanning; pipelines that write to this source need a login with write rights. |
| `encryption` | choice | no | `accepted` | accepted (the default) encrypts when the server asks for Oracle's native encryption; required always uses it; tls connects over TCPS, checking the server's certificate against this server's trusted certificates and its name against the host. One of `accepted`, `required`, `tls`. |
| `schemas` | text, several | no |  | Leave empty to scan every schema, or choose them from a list. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time. |
| `text_length` | number | `0` | For create and replace: VARCHAR2 columns of this many characters (at most 4,000); 0 (the default) for CLOB. |
| `keep_grants` | true or false | `true` | For replace: repeat the old table's grants on the new one. |
| `allow_dropping_row_security` | true or false | `false` | For replace: replace a table with virtual private database policies, which the new table will not have. |
| `lock_timeout` | number | `30` | For replace: how long the swap waits for others using the old table. |
| `empty_text_as_null` | true or false | `false` | Oracle stores empty text as null. false (the default) refuses empty text, so nothing changes silently; true writes it as null. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector oracle]: #

**Signing in.** A login and password, to a service name (not a SID). `encryption` chooses Oracle's
native encryption or TLS. No TNS alias, wallet or driver property can be given.

**Driver.** Oracle's JDBC driver does not ship with Kilnmark: an admin accepts its licence under
**Settings › Drivers** and the server downloads the pinned version, or puts the file in the drivers
folder ([Installing database drivers](drivers.md)). Until then, a connection test says who installs
it.

**What to know.** Decimals are never rounded, and empty text, which Oracle stores as null, is
refused unless `empty_text_as_null` is true. Replace swaps the tables in one block that puts the
names back if any part of it fails.

```yaml
kilnmark: pipeline/v1
name: oracle-orders-copy
description: Orders into an Oracle table, empty text as null.

nodes:
  - id: orders
    type: read
    source: ledger-db
    asset: sales.orders

  - id: out
    type: write
    input: orders
    source: ledger-db
    table: sales.orders_copy
    mode: replace
    empty_text_as_null: true
```

## Generic JDBC

[generated: connector jdbc]: #

Kind `jdbc`, listed under databases (relational).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | yes | no |

- In flows: `check-source` can reach it and look for its tables
- Driver: the JDBC driver an admin registers on this server
- Who sets one up: admins only

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `driver` | text | yes |  | No JDBC driver is registered on this server yet: an admin registers one under Settings, JDBC drivers. |
| `url` | text | yes |  | The address as the driver takes it, starting as its URLs do, for example jdbc:db2://db.example.com:50000/SALES. No password in it: enter that as the source's password. |
| `username` | text | no |  | A read-only account is enough for scanning; pipelines that write to this source need a login with write rights. |
| `properties` | text | no |  | Further settings for the driver, as name=value separated by semicolons, for example sslConnection=true; currentSchema=SALES. Only the properties the driver was registered to take, in the URL or here; never a password, key or token. |
| `schemas` | text, several | no |  | Leave empty to scan every schema, or choose them from a list (catalogues, for databases that have no schemas). |
| `read_only` | true or false | no | `true` | On (the default), Kilnmark asks the driver for read-only connections and pipelines cannot write here. Turn it off, and Allow writing on, to let pipelines append to tables or create them. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `commit_every` | number | `0` | 0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A failure then keeps the rows already committed. |
| `batch_size` | number | `1000` | Rows sent to the database at a time. |
| `text_length` | number | `0` | For create: text columns of this many characters; 0 (the default) for the longest text type the driver lists. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector jdbc]: #

**Signing in.** A user name and password; the password is the source's secret, never in the URL
or `properties`.

**Driver.** For a database with no connector of its own: an admin registers its JDBC driver, with
the URL settings and properties it may take ([Databases without a connector of their
own](drivers.md#databases-without-a-connector-of-their-own)). Every setting in the URL and every
property must be on that list, and drivers that move files for ordinary SQL are refused.

The `driver` setting above is shown as on a server with no driver registered yet. Once an admin
registers one, the form offers the registered drivers to choose from.

**What to know.** Only admins add such a source or change its settings, since they go to code
Kilnmark does not know. Rows are read with `SELECT *` and quality rules run on the rows read, not in
the database. Writes are `append` and `create` only, with `read_only` off and Allow writing on.

```yaml
kilnmark: pipeline/v1
name: jdbc-orders-append
description: Orders appended to a table in a database reached through a registered driver.

nodes:
  - id: orders
    type: read
    source: legacy-db
    asset: sales.orders

  - id: out
    type: write
    input: orders
    source: legacy-db
    table: sales.orders_history
    mode: append
```

## Files in a folder

[generated: connector files]: #

Kind `files`, listed under files (local or network folder).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | no | no |

- Formats it reads: csv, tsv, json, jsonl, parquet, excel, documents
- In flows: `check-source` can reach it and look for its files; `wait-for` can wait for a file
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `path` | text | yes |  | The full path of a folder this server can read, inside one of the folders its administrator allows. |
| `recursive` | true or false | no | `true` | true (the default) or false. |
| `encoding` | text | no |  | Leave empty to detect (UTF-8, or windows-1252 when a file is not valid UTF-8); for example ISO-8859-15. |
| `delimiter` | text | no |  | Leave empty to detect it (comma, semicolon, tab or \|). Write tab as \t. |
| `header` | true or false | no | `true` | true (the default): the first row names the columns. false: it is data, and columns are named column_1, column_2 and so on. |
| `keep_text` | true or false | no | `true` | true (the default) stores documents' text in Kilnmark for search and reading. false stores only their properties (title, author, pages, language, words) and deletes text stored before at the next scan. |
| `read_text_on_demand` | true or false | no | `false` | Only when keep_text is false. true lets stewards view a document's text, read from the source when they open it and never stored; false (the default) shows only its properties. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `overwrite` | true or false | `false` | Replace a file of the same name; otherwise an existing file stops the write. |
| `delimiter` | text |  | CSV only: one character, or \t for a tab; by default the source's own delimiter, else a comma. A delimiter other than the source's is refused. |
| `compression` | choice | `snappy` | Parquet only: how pages are compressed. One of `snappy`, `zstd`, `gzip`, `none`. |
| `allow_unscanned` | true or false | `false` | Write even where scans of this source would not find the file. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector files]: #

**Signing in.** None: the server reads the folder itself. A folder source must lie inside one of
the folders the administrator allows (`kilnmark.files.roots`); with none set, folder sources are
refused, so adding a source can never expose the server's own files.

**What to know.** Every file is an asset; CSV, TSV, JSON, JSON lines and Parquet are read as rows,
other documents as text. Paths with `..`, absolute paths and links leading out of the folder are
refused, and hidden files are skipped. A write goes to a temporary name and is put in place at the
end.

```yaml
kilnmark: pipeline/v1
name: files-orders-to-parquet
description: A CSV file of orders kept as Parquet.

nodes:
  - id: orders
    type: read
    source: landing
    asset: incoming/orders.csv

  - id: out
    type: write
    input: orders
    source: files
    path: exports/orders.parquet
    mode: replace
```

## S3 object storage

[generated: connector s3]: #

Kind `s3`, listed under files (object storage).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | yes | yes | no | no |

- Formats it reads: csv, tsv, json, jsonl, parquet, excel, documents
- In flows: `check-source` can reach it and look for its files; `wait-for` can wait for a file
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

| Setting | Type | Required | Default | What it does |
| --- | --- | --- | --- | --- |
| `endpoint` | address | no |  | Leave empty for AWS. For S3-compatible storage, its URL, for example http://storage.example.com:9000. |
| `region` | text | no |  | For example eu-west-2; default us-east-1. |
| `bucket` | text | yes |  | The bucket to scan, for example landing-bucket. |
| `prefix` | text | no |  | Only objects whose keys start with this, for example raw/. |
| `access_key_id` | text | yes |  | The secret access key goes in the source's secret. |
| `path_style` | true or false | no | `false` | true for most S3-compatible storage (VersityGW, MinIO); false (the default) for AWS. |
| `encoding` | text | no |  | Leave empty to detect (UTF-8, or windows-1252 when a file is not valid UTF-8); for example ISO-8859-15. |
| `delimiter` | text | no |  | Leave empty to detect it (comma, semicolon, tab or \|). Write tab as \t. |
| `header` | true or false | no | `true` | true (the default): the first row names the columns. false: it is data, and columns are named column_1, column_2 and so on. |
| `keep_text` | true or false | no | `true` | true (the default) stores documents' text in Kilnmark for search and reading. false stores only their properties (title, author, pages, language, words) and deletes text stored before at the next scan. |
| `read_text_on_demand` | true or false | no | `false` | Only when keep_text is false. true lets stewards view a document's text, read from the source when they open it and never stored; false (the default) shows only its properties. |

A write node writing to this kind of source also takes:

| Setting | Type | Default | What it does |
| --- | --- | --- | --- |
| `overwrite` | true or false | `false` | Replace a file of the same name; otherwise an existing file stops the write. |
| `delimiter` | text |  | CSV only: one character, or \t for a tab; by default the source's own delimiter, else a comma. A delimiter other than the source's is refused. |
| `compression` | choice | `snappy` | Parquet only: how pages are compressed. One of `snappy`, `zstd`, `gzip`, `none`. |
| `allow_unscanned` | true or false | `false` | Write even where scans of this source would not find the file. |
| `profile_output` | true or false | `true` | Profile the rows in the same pass and record the profile with the written table or file. |
| `keep_values` | true or false | `true` | false keeps statistics without example values or sample rows; the source's own rule still applies. |

[end: connector s3]: #

**Signing in.** An access key ID and its secret access key (the source's secret). Only with
`kilnmark.s3.allow-default-credentials=true` may a source on AWS (no endpoint) use the server's own
AWS identity, never a source with another endpoint; then `access_key_id` may be left empty. Writing
needs `s3:PutObject`,
`s3:AbortMultipartUpload` and `s3:GetObject`.

**What to know.** Works with AWS and S3-compatible storage (`path_style: true` for most of the
latter). A write's `path` is below the source's `prefix`; large files are uploaded in parts,
completed only when the last row is written.

```yaml
kilnmark: pipeline/v1
name: s3-customers-to-csv
description: Customers from Parquet in a bucket to a CSV file beside them.

nodes:
  - id: customers
    type: read
    source: lake
    asset: incoming/customers.parquet

  - id: out
    type: write
    input: customers
    source: lake
    path: exports/customers.csv
    mode: replace
    delimiter: ";"
```

## Kilnmark sample data

[generated: connector sample-data]: #

Kind `sample-data`, listed under files (built in).

| Scan | Read | Write | Preview | Explore | Your own SQL |
| --- | --- | --- | --- | --- | --- |
| yes | yes | no | yes | no | no |

- Formats it reads: csv
- In flows: `check-source` can reach it and look for its files; `wait-for` can wait for a file
- Driver: ships with Kilnmark
- Who sets one up: designers and admins

It has no settings of its own.

[end: connector sample-data]: #

**What to know.** Made-up data that ships with Kilnmark, for the samples in the
[Library](library.md) and for trying things out. It reads only; write what you make from it to
another source.

```yaml
kilnmark: pipeline/v1
name: sample-products-copy
description: The sample products, as a CSV file.

nodes:
  - id: products
    type: read
    source: sample-data
    asset: stock/products.csv

  - id: out
    type: write
    input: products
    source: files
    path: exports/products.csv
    mode: replace
```
