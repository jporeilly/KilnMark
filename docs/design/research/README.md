# What other tools offer: nodes and runs

Research of 7 November 2026, after 0.1.1, for two pieces of work: more nodes (ADR 0049) and
run-level retries with owners. About twenty tools were compared from their own documentation,
including an earlier data integration tool and its open-source successor, called Tool P and Tool H
here. Their sources name them, so those sources are kept outside the repository (ADR 0006).
Nothing from any tool was copied: settings are described by what they do.

The two reports hold the full tables, with a source for every claim:

- [Node options](node-options.md): every node kind's settings, settings that apply across nodes,
  the 15 gaps that matter most for banks, and a migration map from Tool P and Tool H.
- [Run options](run-options.md): retries, timeouts and deadlines, alerts, and owners, a
  recommended option set for Kilnmark, and a migration map for jobs.

## In one page

**What most tools have that Kilnmark lacks**, the five that matter most to a bank:

1. **Rows that fail are kept, not fatal.** An expression, a cast or a write that fails on one row
   sends that row to an error output, with its error code, column and reason. Kilnmark stops the
   run, except at a quality gate, a filter's rejected port and a lookup's unmatched rows.
2. **A stated error tolerance**: fail when more than N rows, or more than a share, are rejected.
3. **A stated rule for which row wins.** Today an upsert keeps the last of two rows with the same
   key, and lookup and dedupe keep the first to arrive, without saying so.
4. **Incremental reads by watermark**, recorded with each run so a load can be repeated exactly.
5. **More write modes**: delete, update only, insert only, a conditional merge, and columns left
   untouched.

The other ten are in [node options, section 3](node-options.md#3-the-15-gaps-that-matter-most-for-banks).
Five of the fifteen are already planned in ADR 0049: history (type 2), change detection, window,
JSON and XML, and masking.

**What users expect of runs:**

- Retries are off by default. A count means extra tries after the first, and the wait doubles,
  up to a limit.
- A retried run carries on from where it failed, at the level of the orchestration graph, not
  inside a data flow. Pipelines in every tool start again; flows (and their equivalents) do not
  need to.
- One alert, after the last try, never one per retry.
- An owner is not the identity a run acts as, and work with no owner alerts someone.
- The pitfalls they document: retries that write twice, retries multiplied across levels, retry
  storms, and timeouts nobody can see.

**Migration from Tool P and Tool H**: the traps that change results on conversion.

- **Commit size is reversed.** There, 0 means no transaction; in Kilnmark, one transaction.
- **Grouping and unique rows over sorted input** only combine rows that sit next to each other.
  Kilnmark combines every row.
- **Database lookups** there can see changes made during the run and follow the database's
  collation. Kilnmark reads once and matches exactly.
- **Sort order**: Kilnmark has no case or locale option yet.
- **Failing rows sent to another node** would stop a converted run until gap 1 is closed.
- **For a person to convert by hand**: scripting, SQL inside a pipeline, metadata injection,
  sub-pipelines; in jobs, sub-jobs, file operations, SQL on databases other than PostgreSQL, and
  variables set during a run.

## What was decided

Owner, 7 November 2026:

- **Run retries, owners, and Run again from the failure** for flows: next, in the
  [backlog](../../product/backlog.md), item 1.
- **The gaps** go into the backlog under more nodes (item 9), with rows that fail, the error
  tolerance and which row wins first.
- **Audit reporting tables** for standard audit reports, and **dashboards and reports about
  Kilnmark itself** (runs, schedules, volume, quality, capacity, people and access, AI) on the same
  tables: backlog item 18.
