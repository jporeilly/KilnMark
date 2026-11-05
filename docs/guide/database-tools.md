# Database tools

A database source's page has tools for looking at the database itself, as it is now, without
leaving Kilnmark: **Explore** its schemas, tables and views, their columns, keys and indexes, and
the first rows of a table. More tools follow (ADR 0045): a SQL generator, connection settings on
tabs, and changes to a database behind controls.

## Explore a database

![Explore on a source's page: schemas, the tables of one, and a table's columns with its row estimate](images/source-explore.png)

Open **Sources**, choose a database source (PostgreSQL, SQL Server, MySQL, MariaDB, Oracle or
Generic JDBC) and scroll to **Explore**. Stewards, designers and admins see it; viewers do not.

1. Choose a schema. Each shows how many tables and views it holds.
2. Choose a table or view. Long lists come a page at a time.
3. Read the table on four tabs:
   - **Columns**: each column's name and type (with its length, or its digits before and after the
     point), whether it allows nulls, whether the database fills it (an identity or a generated
     column) or a default does when a row leaves it out, and whether it is in the primary key.
   - **Keys**: the primary key, the foreign keys that point from this table to others, and the
     tables whose foreign keys point here.
   - **Indexes**: each index with its columns in order, whether it is unique or holds the primary
     key, and its kind where the database says.
   - **Preview**: the first 50 rows.

The address keeps the schema and table you chose, so you can send a colleague a link to the same
table.

### What Explore shows, and what it does not

- **It is live.** Explore asks the database each time, so it shows tables a scan has not recorded
  yet, and nothing it shows is kept.
- **Row counts.** The table's line gives the database's own estimate, "About 1,204,000 rows (the
  database's estimate)". Databases update their estimates from time to time (PostgreSQL when it
  analyses a table), so a new or busy table can be off. **Count exactly** asks the database to count
  them now. On a big table a count can take a while; if it takes longer than people's checks are
  allowed (15 seconds unless your administrator changed it), it stops and the estimate stands.
- **Values are masked as everywhere.** The preview is the source's own preview: a column a
  steward marked sensitive shows `••••` to everyone without the grant to see unmasked values,
  admins included ([Signing in, roles and tokens](signing-in.md)). A table that has never been
  scanned has no marks yet, so scan and mark a table that holds personal data before people
  preview it.
- **No text that can hold a value.** A column's default is shown as "a default", never its text;
  an index on an expression shows as `(expression)`, and a partial index's condition is not shown.
  Each can quote a value from the data (`WHERE ssn = '…'`).
- **Errors give a code, not the database's message.** When the database refuses a count, the
  message gives its SQLSTATE or error code only, as its own text can quote a value.
- **Generic JDBC sources** show their schemas, tables, columns and primary key as a scan reads
  them, without foreign keys, indexes or counts: each driver places schemas and catalogues its own
  way.

### What it needs

The source's login must be able to read the database's catalogue (every login can see the tables
it has rights on) and, for the preview and **Count exactly**, to `SELECT` from the table. Explore
connects read-only and runs only queries Kilnmark writes; nothing you type goes into SQL. It shares
the time limit and the number of checks at once that **Test connection** has, so many people
exploring one source at the same moment wait their turn rather than load the database.
