# Writing data from a pipeline

A pipeline ends in one or more `write` steps. Each writes the rows it receives to a table in a
database (PostgreSQL or SQL Server) or to a CSV or Parquet file in a folder or S3 bucket. This
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

| Input column | PostgreSQL | SQL Server |
| --- | --- | --- |
| whole numbers | `smallint`, `integer`, `bigint` | `smallint`, `int`, `bigint` |
| decimals | `numeric(p,s)` | `decimal(p,s)` |
| floating point | `real`, `double precision` | `real`, `float` |
| text | `text` | `nvarchar(max)` |
| true or false | `boolean` | `bit` |
| bytes | `bytea` | `varbinary(max)` |
| dates and times | `date`, `time`, `timestamp`, `timestamptz` | `date`, `time(6)`, `datetime2(6)`, `datetimeoffset(6)` |

Lists and other nested values are written as text. Every column allows nulls; add keys and
indexes yourself afterwards if you need them. To give text columns a length, set
`text_length: 200` (at most 4000 on SQL Server).

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
negative scales (`numeric(5,-2)` takes whole hundreds). PostgreSQL and SQL Server behave the same.
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

| Mode | PostgreSQL | SQL Server |
| --- | --- | --- |
| `create` | `CREATE` on the schema | `CREATE TABLE` in the database and `ALTER` on the schema |
| `append` | `INSERT` on the table | `INSERT` on the table |
| `upsert` | `INSERT`, `UPDATE` and `SELECT` on the table | `INSERT`, `UPDATE` and `SELECT` on the table |
| `replace` | as `create`, and owning the table (or belonging to its owning role) | as `create`, and `ALTER` on the table |

Grants on some columns only count: the database then checks each column as rows go in. Append
reads the table's columns from the database's description of it, so a login that may only insert
can append. Kilnmark checks these before writing, except, on SQL Server, the right to repeat the
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
