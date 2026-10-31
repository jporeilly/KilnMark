# 0028 SQL a person writes runs in two places only: a read's query, and a flow's SQL action

Status: Accepted (31 October 2026)

## Context

Kilnmark keeps data access declarative. A `read` names a table and columns and the engine builds
the SQL; the expression language never runs code (docs/design/pipelines.md). That is what lets
Kilnmark know every column a pipeline touches (lineage, and marks that follow the data), keep
masking out of reach of a renamed column, and refuse a parameter that picks a table. The flows
design therefore left out a SQL node ("Not in this plan: a SQL check node").

The one exception built so far is custom SQL quality rules (docs/guide/quality-rules.md): off by
default, designer-only, PostgreSQL only, one `SELECT` checked by `SqlTokens.checkCustomSql`, run in
a read-only transaction with a timeout, and never under a login with superuser, file or program
access. A review on 31 October 2026 showed its rows had to be masked whenever any asset of the
source has a mark, because such SQL can read any table the login can.

The migration audit (31 October 2026) ranks the absence of SQL as gap one and two of fifteen. In
other data integration tools nearly every job reads with a hand-written query, and runs statements
before or after a load: truncate, drop and rebuild indexes, call a procedure. Without SQL most
imported work arrives as "report and rewrite", and migration templates stay thin.

## Decision

SQL that a person writes runs in exactly two shapes, and nowhere else: never in `compute`,
`filter` or any node in the middle of a pipeline, and never from a parameter or variable.

1. **A read with a query.** A `read` node may give `query` instead of `asset`: one `SELECT` or
   `WITH` statement. It stays a read: no side effects, a read-only transaction, the source's
   statement timeout. Before it runs, Kilnmark parses it enough to name every table and column it
   reads, and refuses what it cannot parse, rather than guess. Lineage records those tables; the
   marks of every one of them apply to the read's output, as if each were read whole. A `${name}`
   value in the query is bound as a parameter, never pasted into the text, so it can fill a value
   and never a table or column.
2. **A SQL action in flows.** A flow node `sql` runs one or more statements on a source, before or
   after its pipelines: truncate, DDL, a procedure. It is an action, not a data node: it reports a
   status and rows affected and never returns rows, so connections can follow it (`success`,
   `failure`, a condition on `rows_affected`) but nothing downstream can read from it. It runs in
   one transaction, with the source's timeout, and only when the source allows writing.

Both shapes are gated the same way, and custom SQL quality rules move onto this gate as its third
use rather than keeping one of their own:

- **A source opts in.** `allow_sql`, beside `allow_writing`, off by default; a designer or an admin
  turns it on, the same roles that turn on writing. The server-wide `kilnmark.quality.custom-sql`
  switch is retired in favour of it.
- **Designers write it**, as they write custom rules; stewards run what is built. The statements
  are saved and versioned with the pipeline or flow, so a change is a new version with a message.
- **Marked columns are checked before running.** A read query that names a marked column, or a
  table whose marks cannot be resolved, is refused for a caller without the unmask grant (ADR 0027),
  as a preview of such a node is. A SQL action's statements never return rows, so marks do not
  apply to its output; its text is still kept out of run logs shown to callers without the grant,
  since a statement can quote values.
- **The database login is checked**, as for custom rules: never a superuser, nor one with file,
  program or cross-database access. Every source that allows SQL should have a login limited to
  what the SQL needs.
- **PostgreSQL first.** The checker and the read-only transaction exist for PostgreSQL. Other
  databases follow one at a time, each with its own statement checker; until then a source of
  another kind cannot turn `allow_sql` on.
- **Logged.** Each run of a SQL action and each query read is in the run's record with who asked,
  and turning `allow_sql` on is logged like turning on writing.

## Consequences

- Migrations can import the most common input and the most common job entries, instead of
  reporting them. The audit's gaps one and two close; gap three (run outputs) is unchanged.
- Lineage for a query read is only as good as the parser. What it cannot parse is refused with the
  reason, and a designer rewrites the query or uses an ordinary read; Kilnmark never records a
  read it did not understand.
- Masking stays whole: a query read inherits the marks of every table it names, so a renamed or
  computed column in the SQL cannot show a marked value to someone without the grant.
- The "never runs code" rule of the expression language stands. SQL is not an expression; it has
  its own two homes, each opted into per source, each checked, each versioned.
- A SQL action can do anything the login can. The opt-in, the login check, the versioning and the
  log are the controls; a limited login is the one that matters most, and the guide says so.
- The flows design's "Not in this plan: a SQL check node" is superseded by this record. The flow
  node is an action, not a check: a check (rows in a table, say) is a pipeline with a `read` and a
  `gate`, as before.
