# Writing data from a pipeline

A pipeline ends in one or more `write` steps. Each writes the rows it receives to a table in a
database (PostgreSQL, SQL Server, MySQL, MariaDB, Oracle, or another database through Generic JDBC) or to a CSV or Parquet file in a folder or S3 bucket. This
guide explains the modes, the file formats, the rights a write needs, and what `replace` keeps.

## Before you start

- **The source must allow writing.** Sources are read-only until an admin ticks **Allow writing**
  on the source. A pipeline that writes to a read-only source is refused before it runs.
- **The write uses the source's own login.** Kilnmark never writes with an identity of its own,
  so the login saved with the source needs the rights described under
  [Rights the login needs](#rights-the-login-needs). Scanning only needs a read-only login; if
  your source uses one, ask for a second source with a login that can write.
- **Nothing appears until the write has finished.** A table write is one transaction; a file is
  written under a temporary name and put in place at the end. If the run fails or you cancel
  it, the table or file is as it was before. The one exception is `commit_every`, below.

## Writing to a table

```yaml
  - id: out
    type: write
    input: joined
    source: warehouse
    table: billing.customer_spend
    mode: replace
```

Give the table as `schema.table`. Names are used exactly as you write them, capitals included,
so `Billing.Customers` and `billing.customers` are different tables in PostgreSQL.

### Modes

| Mode | The table | What happens |
| --- | --- | --- |
| `create` | must not exist | Kilnmark creates it from the input's columns and fills it |
| `append` | must exist | The rows are added. Every input column must exist in the table and fit its type |
| `replace` | may exist | A new table is filled, then swapped in place of the old one in one step |
| `upsert` | must exist | Each row is inserted, or updates the row with the same `key` |

**create** chooses a column type for each input column:

| Input column | PostgreSQL | SQL Server | MySQL and MariaDB | Oracle |
| --- | --- | --- | --- | --- |
| whole numbers | `smallint`, `integer`, `bigint` | `smallint`, `int`, `bigint` | `smallint`, `int`, `bigint` | `NUMBER(5)`, `NUMBER(10)`, `NUMBER(19)` |
| decimals | `numeric(p,s)` | `decimal(p,s)` | `decimal(p,s)` | `NUMBER(p,s)` |
| floating point | `real`, `double precision` | `real`, `float` | `float`, `double` | `BINARY_FLOAT`, `BINARY_DOUBLE` |
| text | `text` | `nvarchar(max)` | `longtext` (UTF-8) | `CLOB` |
| true or false | `boolean` | `bit` | `tinyint(1)` | `BOOLEAN` (23ai), `NUMBER(1)` before |
| bytes | `bytea` | `varbinary(max)` | `longblob` | `BLOB` |
| dates and times | `date`, `time`, `timestamp`, `timestamptz` | `date`, `time(6)`, `datetime2(6)`, `datetimeoffset(6)` | `date`, `time(6)`, `datetime(6)`, `datetime(6)` in UTC | `DATE`, `VARCHAR2(15)`, `TIMESTAMP(6)`, `TIMESTAMP(6) WITH TIME ZONE` |

Lists and other nested values are written as text. Every column allows nulls; add keys and
indexes yourself afterwards if you need them. To give text columns a length, set
`text_length: 200` (at most 4000 on SQL Server and Oracle, 16,383 on MySQL and MariaDB).

**append** checks the table before sending any row and lists every problem at once, for
example "the input's column flag is boolean but the table's column flag is integer". A column fits
when the table can hold every value it might have: a wider whole number, a date into a timestamp,
or anything except bytes into text. Whole numbers and decimals go into any decimal column, even a
narrower one (`decimal(12,4)` into `numeric(10,2)`): each value is then checked as it is written,
and a value that would have to be rounded, or has too many digits before the point, stops the
write with its row named ("Row 2: the value 1.2345 in column amount has more decimal places than
the column's 2"). Nothing is rounded. The run stops there, and nothing from it stays in the
table, since the write is one transaction; with `commit_every`, the rows committed before that
batch stay. This holds for any declared decimal column, beyond 38 digits too, and for PostgreSQL's
negative scales (`numeric(5,-2)` takes whole hundreds). PostgreSQL, SQL Server, MySQL, MariaDB and Oracle behave the same.
Text is never turned into numbers or dates: add a `select` step to cast columns first. Columns the
input lacks are left to their defaults, so a column that is `NOT NULL` without a default must be in
the input. Two input columns whose names differ only in capitals cannot both go to one column. Text
longer than a column holds is refused with the row and column named. On PostgreSQL, a table with
row-level security that applies to the login is filled row by row, so its policies are checked for
every row (and the write is slower).

**upsert** needs `key`, the columns that identify a row:

```yaml
    mode: upsert
    key: [customer_id]
```

- On PostgreSQL the table needs a unique index or primary key on exactly the key columns, which
  is how the database finds the existing row. If there is none, the message gives the
  `CREATE UNIQUE INDEX` statement to ask your database administrator for.
- On SQL Server each row is merged with `MERGE … WITH (HOLDLOCK)`, so two pipelines upserting at
  the same time cannot both insert the same key. A unique index on the key keeps the table fast.
- On MySQL and MariaDB each row is written with `INSERT … ON DUPLICATE KEY UPDATE`, so the table
  needs a unique index or primary key on exactly the key columns; if there is none, the message
  gives the `ALTER TABLE … ADD UNIQUE` statement to ask for. These databases update the row that
  *any* unique index matches, not only the key's: if the table has another unique index that
  lacks one of the key columns, a row could overwrite a different row than the one with its key.
  Upsert then stops before writing and names that index. Set `allow_other_unique_keys: true` only
  if updating such rows is what you want.
- On Oracle each row is merged with `MERGE`, and the table needs a unique index or primary key on
  exactly the key columns; if there is none, the message gives the `ALTER TABLE … ADD UNIQUE`
  statement to ask for.
- Rows with an empty key column are refused. If the input holds the same key twice, the last row
  wins.

### What replace keeps

`replace` creates the new table under a temporary name (`kiln_new_…`), fills it, and then, in the
same transaction, renames the old table out of the way, gives the new table its name and drops
the old one. Anyone reading the table sees the old rows until that moment and the new rows after
it, and never an empty or half-filled table. The swap waits up to 30 seconds for people using the
old table to finish (set `lock_timeout` in seconds to change it); if they do not, the run stops and
the old table is unchanged.

| Kept | Not kept |
| --- | --- |
| The rows you wrote, with the column types `create` would choose | Indexes, primary keys and other constraints |
| Grants on the table (set `keep_grants: false` to skip them) | Triggers and comments |
| The owner, where the login may hand the table over; otherwise the owner keeps full rights on it | Row-level security policies (only with `allow_dropping_row_security: true`) |
| Publications `FOR ALL TABLES` or `FOR TABLES IN SCHEMA`, which take in the new table themselves | Publications naming the table (refused, below), replication and change data capture on SQL Server (refused) |

On PostgreSQL the new table goes back to the old owner when the source's login may give it away
(a superuser, or a login that can act as the owner); otherwise the login owns it and the old owner
is granted every privilege on it. On SQL Server a table owned through its schema keeps that owner,
and a table with an owner of its own gives that owner `CONTROL` of the new table.

`replace` refuses, before writing anything, when dropping the old table would break something or
be refused, and names what is in the way:

- the target is a view, a partitioned table or a foreign table rather than a plain table;
- it is a partition, or takes part in inheritance (as parent or child);
- views, foreign keys from other tables, functions written in SQL or taking its rows, columns of
  its type in other tables, or other tables' defaults using a sequence it owns depend on it (on SQL
  Server: views and functions created `WITH SCHEMABINDING`, and foreign keys; other views follow
  the new table by name, so run `sp_refreshview` on those that use `SELECT *`);
- a publication names it for logical replication (on SQL Server: it is published for replication,
  or change data capture tracks it). Replace would leave the publication without the table;
  remove it from the publication first, and add the new table afterwards;
- it has row-level security (on SQL Server, a security policy). The new table would not have the
  policies, but would get the same grants, so everyone granted access would see every row. Set
  `allow_dropping_row_security: true` if that is what you want, and add the policies again
  afterwards.

If you need to keep indexes, constraints or policies, empty the table yourself and use `append`.

If the table does not exist yet, `replace` fills a new table and gives it the name at the end.

### Large writes

A write is one transaction, however many rows. For very large writes you can commit in parts:

```yaml
    commit_every: 1000000
```

Kilnmark then commits after each batch that brings the count to that many rows. If the run fails
part-way, **the rows already committed stay in the table**, and the message says how many. With
`replace`, the swap still happens only at the end: a failure leaves the old table as it was and
drops the half-filled new one.

Other settings: `batch_size` (rows sent to the database at a time; 1,000, or 5,000 for SQL Server
inserts) and, on SQL Server, `bulk_copy: false` to turn off the driver's bulk loading, which is
used only for tables the step creates.

### MySQL and MariaDB

Tables are written the same way, with these differences, which come from how MySQL and MariaDB
work:

- **Creating and replacing commit by themselves.** These databases cannot undo a `CREATE TABLE` or
  `DROP TABLE`, so `create` and `replace` fill the new table under a temporary name (`kiln_new_…`)
  and give it its name at the end with one `RENAME TABLE`, which swaps both names at once. If the
  run fails, the half-filled table is dropped and the old one is unchanged.
- **What replace keeps.** Grants on the table, since MySQL keeps them by table name. Not kept:
  indexes, keys, comments, `AUTO_INCREMENT` and column defaults. Replace refuses a view, a
  sequence, a system-versioned or partitioned table, a table other tables' foreign keys refer to,
  a table with triggers (they would be dropped with it), and a table stored by an engine other
  than InnoDB, Aria or MyISAM (such as FEDERATED, CONNECT, SPIDER, MERGE or MEMORY), naming what is
  in the way. The login must hold the `TRIGGER` privilege itself (not through a role) so it can see
  whether there are triggers; otherwise replace is refused. A foreign key on a table the login has
  no rights on cannot be seen beforehand (unless the login has `PROCESS`): if one stops the old
  table being dropped, the old table is put back, the replace fails, and nothing changes. If the old
  table cannot be dropped for another reason, the new rows stay and the run carries a note naming
  the leftover `kiln_old_…` table for an administrator to drop.
- **Timestamps with a time zone** are written as `datetime(6)` holding UTC, since MySQL's
  `TIMESTAMP` ends in 2038; they read back as timestamps without a zone. A `TIME` column reads
  back as text, since MySQL times run from -838:59:59 to 838:59:59; only times and text can be
  written to it. A `YEAR` column takes whole numbers from 1901 to 2155.
- **Fractions of a second are never rounded.** A value with more digits of a second than a
  `DATETIME(p)`, `TIMESTAMP(p)` or `TIME(p)` column keeps stops the write, naming the row; so does
  one for a PostgreSQL `timestamp(p)` or `time(p)` column.
- **Large writes.** Tables the step creates are loaded in bulk with `LOAD DATA LOCAL INFILE`, sent
  from memory, when the server allows it (`local_infile`, off by default on MySQL 8); otherwise,
  or with `bulk_load: false`, with batched inserts. Values that do not fit fail the write rather
  than being cut short: Kilnmark writes with its own SQL mode (`STRICT_ALL_TABLES`,
  `NO_ZERO_IN_DATE`, `NO_ZERO_DATE`, `ERROR_FOR_DIVISION_BY_ZERO`, `NO_ENGINE_SUBSTITUTION`), not the
  server's, so modes such as `EMPTY_STRING_IS_NULL` or `TIME_TRUNCATE_FRACTIONAL` never change what
  is written.
- **Names in lower case.** On a server with `lower_case_table_names` set (Windows and macOS servers,
  and some Linux ones), the table is written and recorded in lower case, as the server stores it.
- **The source's database.** A source set to one `database` writes only there; give the target as
  `database.table`.

### Oracle

Writing to Oracle needs its driver, which an admin installs once (see "Installing database
drivers"). Tables are written as above, with these differences:

- **Names.** A plain name written in lower case, such as `sales.order_totals`, is the upper-case
  `SALES.ORDER_TOTALS` Oracle makes of unquoted names, unless a table with the lower-case name
  already exists. Columns created from the input are named the same way.
- **Creating and replacing commit by themselves**, as on MySQL: the new table is filled under a
  temporary name and given its name at the end. Replace renames the old table away, the new one
  in, and drops the old one in one step that puts the names back if anything fails. Oracle cannot
  swap two names at once, so for that instant someone reading the table may get an error saying
  it does not exist.
- **What replace keeps.** Grants on the table (set `keep_grants: false` to skip them). Not kept:
  indexes, keys, triggers, comments and defaults. Replace refuses a view, a materialized view, a
  partitioned or temporary table, a table with triggers or a materialized view log, and a table
  other tables' foreign keys refer to. With virtual private database policies it needs
  `allow_dropping_row_security: true`.
- **Empty text.** Oracle stores empty text (and an empty value in a `RAW` or `BLOB` column) as
  null, so a write with one stops and names the row, unless you set `empty_text_as_null: true`. An
  upsert key column can never be empty, whatever that setting says: as null it would match no
  row.
- **Character sets.** On a database whose character set is not Unicode (such as `WE8MSWIN1252`),
  Oracle would store a character it lacks as `?`. Kilnmark refuses such a row and names the
  character instead; `NVARCHAR2` and `NCLOB` columns use the national character set, which usually
  holds everything. Columns sized in bytes (`VARCHAR2(20 BYTE)`) are checked in bytes.
- **Nothing is rounded.** Besides decimals, a timestamp with more digits of a second than the
  column keeps (a `DATE` keeps whole seconds) stops the write, as does a decimal with more than 38
  significant digits, more digits than a `FLOAT(b)` column keeps, or outside the range of a
  `NUMBER`. Floating-point input does not go into a `FLOAT(b)` column, which is a decimal type: cast
  it to a decimal first. A time of day is written as text, `09:30:00.5`, since Oracle has no time of
  day type.
- **The login.** Writing to the login's own schema needs `CREATE TABLE` and a quota on its
  tablespace; writing to another schema needs `CREATE ANY TABLE`, `INSERT ANY TABLE`,
  `ALTER ANY TABLE` and `DROP ANY TABLE`.

### Generic JDBC

A Generic JDBC source ([Databases without a connector of their own](drivers.md#databases-without-a-connector-of-their-own)) can be written to
with **`append`** and **`create`** only, and only when its **Read only** setting is off as well as
**Allow writing** on.

- `replace` and `upsert` are refused, with a message saying so. Swapping in a new table safely, or
  updating a row or inserting it without two runs getting in each other's way, needs SQL that each
  database writes its own way, and Kilnmark will not guess. Empty the table yourself and `append`,
  write to a new table with `create`, or use the database's own connector where there is one.
- Rows are sent as batches of one `INSERT` with every value as a parameter. Settings: `commit_every`,
  `batch_size` and `text_length`.
- `create` chooses each column's type from the types the driver lists. If the database has no type
  for a column (for example no timestamp with a time zone), the write stops before anything is
  created and says which column to convert.
- Every value is checked against the column it goes to, also in a table the step just created: a
  decimal that would need rounding, text or bytes longer than the column, more digits of a second
  than the column keeps, or a number outside a `TINYINT` or unsigned column stops the write, naming
  the row. A column whose size the driver does not say, or whose type Kilnmark does not know, stops
  an append before any row is sent.
- On databases where creating a table commits by itself, the new table can be seen, empty or partly
  filled, until the write ends; if the write fails, Kilnmark drops it again.
- The login needs `INSERT` on the table, and for `create` the right to create tables in the schema.
  Kilnmark cannot check rights the same way on every database, so a missing right shows as the
  database's own refusal.

## Writing to a file

```yaml
  - id: orphans
    type: write
    input: joined.rejected
    source: file-share
    path: quarantine/orphan-spend.csv
    format: csv
```

- **`path`** is relative to the source's folder (or, for S3, below the source's prefix: with the
  prefix `raw/`, `path: out/spend.csv` writes the object `raw/out/spend.csv`). This differs from a
  `read` step, whose `asset` is the asset's path as the catalogue shows it: for S3 the whole key,
  prefix included (`asset: raw/out/spend.csv`). Kilnmark records a written file under that same
  catalogue path, so a later `read` or scan finds it. Folders
  that do not exist are created. Paths that are absolute, contain `..`, `//`, a backslash or
  control characters, or name a hidden file or folder (starting with a dot) are refused, and so
  is a path that would lead out of the folder through a link.
- **`format`** is `csv` or `parquet`. Leave it out to take it from the file name; if you give it,
  the file name must end in the same extension.
- **`overwrite`**: an existing file is refused unless you set `overwrite: true`, or use
  `mode: replace`. A replaced file changes in one step: readers see the old file or the new one.
- **Somewhere scans look.** Kilnmark records the file it writes as an asset, so it refuses a path
  that scans of the source would never find: a sub-folder of a source that does not include
  sub-folders, or a folder or name outside the source's chosen folders and include and exclude
  patterns. Set `allow_unscanned: true` to write there anyway.

On S3, small files are uploaded in one request at the end, larger ones in parts that are
completed only when the last row is written. If the run fails, the upload is abandoned and S3
discards the parts. The access key needs permission to write objects (`s3:PutObject` and
`s3:AbortMultipartUpload`) and to check whether one exists (`s3:GetObject`).

### CSV

Files are UTF-8 with a header row, as RFC 4180 describes: each record ends with CRLF, and a value
holding the delimiter, a quote or a line break is quoted, with quotes inside doubled. An empty
value is a null; an empty piece of text is written `""`, so the two stay apart when Kilnmark reads
the file again. Dates and times are ISO 8601 (`2025-01-31`, `2025-01-31 09:30:00`, and
`2025-01-31 09:30:00Z` in UTC); decimals are written in full, never in scientific notation.

A CSV file must read back as written, so it follows the source's own settings: it uses the
source's `delimiter` when the source has one (a comma otherwise), and Kilnmark refuses a different
`delimiter`, a source set to read CSV files without a header row, and a source reading another
encoding than UTF-8. Choose Parquet, or another source, in those cases. On a source without its
own delimiter, set `delimiter: ";"` (or `"\t"` for tabs) for spreadsheets that expect another one.

Kilnmark records the CSV file's columns with the types a scan will infer from its text (a column
of digits is a whole number, whatever it was written from), so scanning the file later shows no
change.

### Parquet

Columns keep their types: whole numbers, decimals (up to 38 digits), floating point, text, bytes,
dates, times and timestamps (in UTC when the input's are). Pages are compressed with Snappy by
default; set `compression` to `zstd`, `gzip` or `none` if the tool reading the file needs it.
The files follow the Apache Parquet format and have been checked with Kilnmark's own reader and
with pyarrow.

## Rights the login needs

| Mode | PostgreSQL | SQL Server | MySQL and MariaDB | Oracle |
| --- | --- | --- | --- | --- |
| `create` | `CREATE` on the schema | `CREATE TABLE` in the database and `ALTER` on the schema | `CREATE`, `INSERT`, `ALTER` and `DROP` on the database | `CREATE TABLE` in its own schema; the `ANY TABLE` privileges for another |
| `append` | `INSERT` on the table | `INSERT` on the table | `INSERT` on the table | `INSERT` on the table |
| `upsert` | `INSERT`, `UPDATE` and `SELECT` on the table | `INSERT`, `UPDATE` and `SELECT` on the table | `INSERT` and `UPDATE` on the table | `INSERT`, `UPDATE` and `SELECT` on the table |
| `replace` | as `create`, and owning the table (or belonging to its owning role) | as `create`, and `ALTER` on the table | as `create`, and `ALTER` and `DROP` on the table | as `create` |

Grants on some columns only count: the database then checks each column as rows go in. Append
reads the table's columns from the database's description of it, so a login that may only insert
can append. On MySQL and MariaDB, rights that come from a role cannot be seen beforehand, so with a role
active the database decides as the rows go in. Kilnmark checks these before writing, except, on SQL Server, the right to repeat the
old table's grants after a replace, and to give a table's own owner `CONTROL` of the new one: a
login without it fails at that point and nothing changes. If something is missing, the run stops with a message such
as "the login kilnmark_writer cannot write to billing.customer_spend in warehouse: ask a database
administrator to grant INSERT on billing.customer_spend". Pass it on as it is.

## What Kilnmark records

When a write creates or replaces a table, or writes a file, Kilnmark profiles the rows as it
writes them, in the same pass, and once the write has succeeded registers (or updates) the target
as an asset with that profile: its columns and the type each was written as, the row count, and
each column's statistics and value patterns, as a scan would record them. The next pipeline that
reads it starts from that metadata. Set `profile_output: false` to skip the profile, or
`keep_values: false` to keep statistics without example values.

An `append` or `upsert` adds rows to a table that holds others too, so Kilnmark records only how
many rows the run wrote; the table's columns, row count and profile stay as its last scan found
them until it is scanned again.
