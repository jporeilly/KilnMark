# Research: what other data tools let users set on their nodes, and where Kilnmark falls short

Researched on 7 November 2026, for Kilnmark after 0.1.1. The summary and what was decided are in
[README.md](README.md).

**What Kilnmark has today** comes from three files: `docs/guide/reference-pipeline-nodes.md`,
`docs/guide/pipeline-nodes.md` (expressions), and `docs/guide/writing-data.md` together with
`reference-connectors.md` and `reference-flow-nodes.md`. **What is planned** comes from ADR 0049
(more nodes) and ADR 0050 (AI in pipelines).

**What other tools have** comes from each vendor's official documentation. Every claim cites a
numbered source, `[Sn]`, and the full URLs are listed in "Sources" near the end. Rows about Tool P
and Tool H (an earlier data integration tool and its open-source successor) carry no citation:
their sources name those tools, so they are kept outside the repository.

## How to read this

**Tools**, abbreviated in the tables:

| Abbreviation | Tool |
| --- | --- |
| SS | SSIS data flow |
| INF | Informatica PowerCenter / IDMC |
| TAL | Talend |
| KN | KNIME |
| ALX | Alteryx Designer |
| ADF | Azure Data Factory mapping data flows |
| NIFI | Apache NiFi |
| MAT | Matillion (Data Productivity Cloud, and Matillion ETL) |
| DBT | dbt |
| SPK | Apache Spark and Databricks (Delta, Auto Loader, Lakeflow) |
| GLUE | AWS Glue Studio |
| TP | Tool P (the earlier tool) |
| TH | Tool H (its open-source successor) |

**The Kilnmark column** says yes, partly, no, or planned. "Planned" names the item in ADR 0049's
order, for example "planned 0049-6.2" for `window`.

**How sure each source is:**

- **Informatica:** docs.informatica.com refused automated reading (HTTP 403). The INF rows come from
  search-engine extracts of the official pages cited, not from reading the pages themselves.
- **Alteryx:** help.alteryx.com pages render empty to automated reading, so the ALX rows are also
  from search-engine extracts of the cited pages.
- **KNIME:** the current hub pages show no option text, so some KN rows come from KNIME's older
  official node descriptions.
- **Matillion:** its Data Productivity Cloud component pages now live at docs.maia.ai, which the
  docs.matillion.com links redirect to. Its Sample component page returned 404.
- **Everything else** was read on the official pages cited.
- **Rule for what was left out:** a setting the researchers could not confirm on an official page
  is not claimed.

---

## 1. Per node kind

### 1.1 read

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Table or query | Read a named table or view, or the result of a query | SS [S19], INF [S25], ADF [S5], NIFI [S82], MAT [S97], SPK [S131], TP, TH | partly: `asset` or `query`; a query only on PostgreSQL with Allow SQL on |
| Stored procedure as source | Rows come from a procedure | ADF [S5] | no |
| Column list and pushed-down filter | Read only some columns and rows, filtered in the database | INF [S25], NIFI [S82], MAT [S97], SPK pushDownPredicate [S131] | yes: `columns`, `where` (bound values, checked again in Kilnmark) |
| Source-side sort, distinct, SQL override | ORDER BY or DISTINCT, or a replacement query, at the source | INF [S25], TP, TH | no (query only on PostgreSQL) |
| Incremental read by watermark | Keep the largest value of a column between runs and read only newer rows | ADF incremental column [S5], NIFI Maximum-value Columns [S82], GLUE job bookmarks [S154][S155], MAT incremental load [S98][S99], DBT incremental models [S121] | no |
| Watermark start and reset | Where the first run starts; read from the beginning; rewind | ADF [S5], NIFI Initial Load Strategy [S82], GLUE pause, rewind and reset [S154] | no |
| Native change data capture | Read inserts, updates and deletes from the database's change data capture | ADF [S5] | no |
| Partitioned (parallel) reads | Split a large read into ranges read in parallel | SPK partitionColumn and numPartitions [S131], GLUE hashfield and hashpartitions [S156], NIFI Partition Size [S83], ADF source partitioning [S4], MAT concurrency [S97] | no |
| Fetch size | Rows the driver fetches per round trip | NIFI [S82], SPK [S131], MAT [S97], ADF batch size [S5] | no |
| Isolation level | Locking behaviour of the read | ADF [S5] | no (read-only connection) |
| Query time limit | Stop a slow SELECT | NIFI Max Wait Time [S82], SPK queryTimeout [S131] | partly: flow-node `timeout`, not per read |
| Session set-up SQL | SQL run when the session opens | SPK sessionInitStatement [S131] | no |
| Row limit or sampling | Read at most N rows, or a sample | ADF sampling [S4], TH limit size, SPK pushDownLimit [S131] | yes: `row_limit` |
| Run the query once per incoming row | Parameterised query for each row | TP, TH | no |
| Allow schema drift | New source columns flow through without being declared | ADF [S4] | no: columns are checked before the run |
| Validate schema | Fail when the source no longer matches the declared columns and types | ADF [S4] | partly: a saved pipeline is checked against the catalogue before it runs |
| Infer drifted column types | New columns get an inferred type rather than text | ADF [S4], SPK Auto Loader inferColumnTypes [S134] | no |
| Schema evolution modes | Add the new columns; rescue them into one column; fail; or ignore them | SPK Auto Loader [S134] | no |
| Bad-record mode for files | Keep bad rows in a corrupt-record column, drop them, or fail fast | SPK PERMISSIVE / DROPMALFORMED / FAILFAST [S132], Databricks badRecordsPath [S133] | no: a malformed file fails |
| Per-field format, null-if and trim on file read | Date and number masks, a token read as null, trimming | NIFI CSVReader [S84], SPK nullValue, dateFormat and ignoreLeadingWhiteSpace [S132], TP, TH | partly: encoding, header and delimiter on the source; parsing is done in `compute` |
| Skip lines | Ignore the first N lines | ADF [S4] | no |

### 1.2 write

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Append | Add rows | all | yes: `append` |
| Create, failing if the target exists | | SPK errorifexists [S135], KN [S64] | yes: `create` (the default) |
| Replace or overwrite | Swap in the new rows | SPK overwrite [S135], MAT Rewrite Table [S102], ALX Overwrite Table (Drop) [S70], KN [S64] | yes: `replace` (a new table swapped in; grants kept) |
| Ignore if the target exists | | SPK [S135] | no |
| Truncate, then load | Empty the table and keep its definition | ADF table action [S5], TAL [S49], MAT [S100], INF [S26], SPK truncate [S131], TP, TH | partly: `replace` swaps in a new table; the flow `sql` node can truncate, on PostgreSQL only |
| Drop and re-create | | ADF recreate [S5], TAL [S49], ALX [S70] | partly: `replace` |
| Create if not exists | | TAL [S49] | no |
| Upsert or merge by key | | ADF [S1][S8], TAL insert or update [S49], NIFI UPSERT [S85], MAT Table Update [S101], INF update else insert [S26], DBT merge [S120], SPK MERGE [S136], ALX update, insert if new [S70], KN DB Merge [S64], TP, TH | yes: `upsert` with `key` |
| Update only | | ADF [S1], TAL [S49], NIFI [S85], INF [S26], TH | no |
| Delete by key | | ADF [S8], TAL [S49], NIFI [S85], INF [S26], MAT when matched delete [S101], SPK [S136], TP, TH | no |
| Per-row operation set by a condition | Each row marked insert, update, delete or reject by an expression | ADF Alter Row [S8], INF Update Strategy and data-driven loads [S27][S28], NIFI Statement Type Record Path [S85] | no |
| Merge clauses with conditions | When matched AND condition update or delete; when not matched insert; when not matched by source delete | SPK [S136], MAT [S101] | no |
| Columns a merge updates, or leaves alone | Update some columns only | DBT merge_update_columns and merge_exclude_columns [S120], TH update flag per column, ADF skip writing key columns [S8] | no |
| Insert only if new, never update | | TH "don't perform any updates" | no |
| Replace a slice | Atomic overwrite of the rows matching a condition, or of whole partitions | SPK replaceWhere [S138], DBT insert_overwrite [S120] | no |
| Delete then insert | Replace the rows that share keys | DBT delete+insert [S120], MAT [S101] | no |
| Type 2 history (SCD) | Keep versions with valid-from and valid-to dates | DBT snapshots [S123], SS SCD [S23], TP, TH | planned 0049-6.7 |
| Hard-delete handling in history | Ignore, close the version, or add a deleted flag | DBT hard_deletes [S123] | no |
| Commit interval | Commit every N rows | SS maximum insert commit size [S11], INF commit interval [S41], TAL commit every [S49], TP, TH | yes: `commit_every` |
| Batch size | Rows sent per round trip | SS rows per batch [S11], ADF [S5], TAL [S49], NIFI [S85], KN [S64], SPK [S131] | yes: `batch_size` |
| Bulk load | Fast-load or bulk path | SS fast load [S11], INF bulk [S26], TP, TH | yes: `bulk_load`, `bulk_copy` |
| Table lock, check constraints, keep identity, keep nulls | Fast-load switches | SS [S11] | partly: `lock_timeout` for replace only |
| Pre and post SQL | SQL before and after the load | ADF [S5], NIFI [S85], ALX [S70], INF (source pre and post SQL) [S25], DBT hooks [S128] | partly: the flow `sql` node, on PostgreSQL only, by policy (ADR 0045) |
| Transaction choice | One transaction, or batches | ADF transaction commit [S5] | yes: `commit_every: 0` means one transaction |
| Validate the target schema | Fail if a column is missing or its type differs | ADF [S1] | yes: append checks every column before writing |
| Add new columns to the target | Evolve the target table | DBT on_schema_change [S121], SPK mergeSchema and MERGE WITH SCHEMA EVOLUTION [S137], ADF allow schema drift on the sink [S1] | no |
| Column contract | Declared names and types, enforced at build | DBT contract [S124] | partly: append checks fit; no declared contract on the node |
| Fix type mismatches | Cast values to the target's types | MAT [S100] | no (by design: text is never turned into numbers) |
| Grants | Set grants on what is written | DBT grants [S129] | partly: `keep_grants` for replace |
| Rows that fail in the database | Continue on error and output the rejected rows, with the operation, error code and message | ADF [S5], TAL die on error off plus a rejects link [S49], NIFI failure and retry relationships [S85], KN append status and error columns [S64], INF reject file [S26], TH ignore insert errors | no: the whole write stops |
| Report success with errors | | ADF [S5] | no |
| Update failure: warn or error | | ALX [S71] | no |
| Unmatched fields and columns | Ignore, warn or fail when the input and the table differ | NIFI [S85] | partly: fails |
| Table name taken from a field; partition over tables | | TH, ALX [S71] | no |
| Return generated keys | | TH | no |
| Files: maximum records per file | Split the output into several files | ALX [S71] | no |
| Write order of sinks | Order the writes, or run them as parallel groups | ADF custom sink ordering [S1] | no (each write commits at the end) |

### 1.3 filter

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| One condition as an expression | | all | yes: `condition` |
| Builder conditions combined with AND or OR | | MAT [S103], TAL [S50], GLUE [S160] | partly: written as an expression |
| Output for false rows | | ALX T/F [S67], TP, TH, KN include or exclude [S61] | yes: `rejected` |
| Missing-value matching | Treat null as a match | KN [S61], MAT null, blank, null or blank [S103] | partly: `is null` in the expression |
| Filter by row-number range | | KN [S61] | no |
| Case-insensitive operators (ILIKE, regex) | | MAT [S103], GLUE regex [S160] | partly: `regex_match`; no ILIKE |

### 1.4 compute (derived columns)

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Named expressions, later ones using earlier ones | | ADF, GLUE [S162], MAT [S104], NIFI UpdateRecord [S87], TP, TH | yes: `columns` |
| Column patterns | Apply one expression to many columns by name or type | ADF [S7], KN [S59] | no |
| Values from earlier or later rows | | ALX Multi-Row Formula [S76], MAT Lead/Lag [S113], SPK [S143] | planned 0049-6.2 |
| Surrogate keys and sequences | Start, increment, end, cycle | INF [S40], MAT [S105], GLUE Identifier [S163], TP, TH | planned 0049-6.8 |
| `if`, `case`, padding, splitting, date arithmetic, hashing | | most tools | partly: `case` exists; the rest is planned 0049-6.3 |
| An expression error sends the row to an error output | | SS [S18], TP, TH | no: it stops the run |

### 1.5 select, rename, cast

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Keep, drop, rename, reorder | | ADF, GLUE [S158], ALX [S72], TP, TH | yes |
| Change type | | all | yes: `cast` |
| Length, precision and scale | | ALX [S72], MAT [S118], TP, TH | partly: `decimal(p,s)`; text length only on write (`text_length`) |
| Conversion mask, locale, decimal and grouping symbols, lenient parsing | | TP, TH, SS [S24] | partly: `parse_date` and `parse_timestamp` with a pattern; no number masks; not lenient |
| Too-narrow conversion gives null and a warning | | ALX [S72] | no: strict, so the run stops |
| Rule-based mapping | Select columns by name pattern or type | ADF [S1], KN [S59] | no |
| Resolve an ambiguous type | Cast, split into a column per type, or match the catalogue | GLUE ResolveChoice [S159] | no |

### 1.6 lookup

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Full cache, held in memory | | SS [S12], TAL load once [S43], TP, TH | yes (always) |
| Partial cache, least-used rows evicted | | SS [S12], TH cache size in rows | no |
| No cache: query per row | | SS [S12], TAL reload at each row [S43], TP, TH | no |
| Persistent or shared cache | Reuse one cache across pipelines | SS cache file [S12], ADF cache sink and cached lookup [S1][S2] | no |
| Dynamic cache, updated as rows pass | | INF NewLookupRow [S32] | no |
| No match: keep, drop or reject | | SS fail, ignore or redirect [S12], TAL inner-join reject [S46], NIFI matched and unmatched [S91], ADF isMatch() [S2] | yes: `unmatched` keep, drop or rejected |
| No match: fail the run | | SS [S12] | no |
| Default values on no match | | TP, TH | partly: `coalesce` in a later `compute` |
| Several matches: first, last, any, all, or error | | INF [S29][S30], ADF match multiple, or first / last / any [S2], TAL unique, first or all matches (scenario pages [S44]) | partly: `duplicates` first or error |
| Order that decides which match is "first" | | ADF sort conditions [S2], TH order by | no: arrival order decides |
| Non-equi or range conditions | <, >, BETWEEN; date-effective rates | ADF [S2], TH comparators | no |
| Lookup query override | | INF [S31], SS [S12] | no |
| Matching rule | The engine's (case-, space- and precision-sensitive) or the database's | SS [S12], TH | partly: engine only; case-sensitive; numbers match by value |
| Memory limit or cache size | | SS [S12], KN [S65] | yes: `memory_limit_mb` |
| Spill the lookup side to disk | | TAL store temp data on disk [S45] | no |

### 1.7 join

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Inner, left, right, full | | all | yes: `how` |
| Cross (cartesian) | | ADF [S3], SPK [S140] | no |
| Semi and anti joins | | SPK [S140], GLUE [S164] | partly: `lookup` with `unmatched` |
| Non-equi or OR conditions | | ADF custom cross join [S3] | no |
| Fuzzy join | Similarity threshold and a score column | ADF [S3], SS Fuzzy Lookup [S20], GLUE FindMatches [S168] | no |
| Broadcast or strategy hints | | ADF broadcast auto, fixed or off [S3], SPK BROADCAST, MERGE, SHUFFLE_HASH and SHUFFLE_REPLICATE_NL [S141] | no (one sort-merge join; `lookup` is the small-side choice) |
| Unmatched rows to separate outputs | | KN [S58], ALX L/J/R [S66] | no |
| Match all or any of the key pairs | | KN [S57] | no |
| Treat nulls as equal | | SS [S13] | no: null never matches |
| Clashing column names | Prefix, suffix, or drop duplicates | KN [S57], GLUE [S164] | yes: `right_prefix` |
| Join by record position | | ALX [S66] | no |
| Sorted input required | | SS [S13], INF option [S34], TP, TH | not needed: Kilnmark sorts, and spills to disk |
| Cache size and spill | | INF data and index cache [S34] | yes: `memory_limit_mb`, then disk |

### 1.8 aggregate

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Group by and aggregate expressions | | all | yes |
| count, sum, min, max, avg, count distinct | | all | yes |
| first, last, median, percentile, standard deviation, variance, list and concatenate | | TAL [S51], GLUE [S166], TH, ADF first() and last() [S7] | no |
| Ignore nulls per aggregate | | TAL [S51], KN [S60] | partly: SQL rules (aggregates skip nulls) |
| Rollup, cube, grouping sets | | MAT [S107] | no |
| Column patterns | Same aggregate on many columns | ADF [S7], KN [S59] | no |
| Sorted input (streams with little memory) | | INF [S35], TP, TH | no |
| Expected group count or scale hint | | SS keys scale [S14] | no |
| Financial precision, overflow checks | | TAL [S51] | partly: decimal type; overflow stops the run |
| Keep detail rows beside the totals | | TH include all rows | no |
| Always return a row with no input | | TH | yes, when there is no `group_by` |
| Spill to disk | | INF [S34], KN [S65] | no: memory limit only |

### 1.9 union

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| By name | | ADF [S9] | yes (the only way) |
| By position | | ADF [S9], GLUE (same schema required) [S165] | no |
| Missing columns become null | | MAT All Columns [S108] | yes |
| Only overlapping columns | | MAT [S108] | no |
| Cast to a common type, or fail | | MAT [S108] | yes: widening, or `strict` |
| Distinct (UNION rather than UNION ALL) | | GLUE [S165], MAT [S108] | no (add a `dedupe`) |
| Add a column naming each row's input | | MAT [S108] | no |

### 1.10 dedupe

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Key columns, or the whole row | | GLUE [S167], TAL [S53], ALX [S68] | yes |
| Duplicates to their own output | | ALX D [S68], TAL [S53], NIFI [S93], TH | yes: `rejected` |
| Which row survives: first, last, min or max of a column, or by an order | | KN [S63], ADF aggregate with first() or last() [S7], INF Rank [S38] | partly: first in arrival order only |
| Case-insensitive keys | | TAL [S53], TH | no |
| Mark duplicates rather than remove them | | KN [S63] | no |
| Count duplicates | | TAL [S53], TH counter | no |
| Spill to disk | | TAL use of disk [S53] | no |
| Approximate (Bloom filter) | | NIFI [S93] | no |
| Dedupe as part of sort | | SS [S15], INF distinct [S36], TH | no |

### 1.11 route (conditional split)

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Ordered conditions, first match wins | | SS [S17], ADF [S10] | yes |
| All matching outputs: a row may go to several | | ADF [S10], GLUE [S161] | no |
| Default output | | SS [S17], ADF [S10], INF [S37], GLUE [S161] | yes: `out` |
| Route by one column's value (switch or case) | | TP, TH | partly: one condition per value |
| SQL per output | | NIFI QueryRecord [S86] | partly: an expression per route |
| Partition into groups by value | | NIFI PartitionRecord [S88] | no |

### 1.12 sort

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Columns, ascending or descending | | all | yes |
| Nulls first or last | | ADF [S6], SPK [S142], MAT [S111], KN [S62], INF null treated low [S36] | yes (last by default) |
| Case-insensitive | | ADF [S6], SS [S16], INF [S36], TH per field | no |
| Locale or collation, dictionary order | | SS locale and comparison flags [S16], ALX dictionary order [S69] | no: Unicode code point |
| Alphanumeric or natural order ("Row2" before "Row10") | | KN [S62] | no |
| Numeric, alphabetic or date sort type | | TAL [S52] | partly: decided by the column type |
| Sort by a computed value | | ADF [S6] | no (compute the value first) |
| Remove duplicates on sort | | SS [S15], INF [S36], TH | no |
| Memory size, temporary folder, compression | | INF [S36], TAL [S52], TH | partly: `memory_limit_mb`; the spill folder is private and encrypted |
| Sort only within partitions | | ADF [S6] | no |
| Threads | | SS [S15] | no |

### 1.13 limit and sample

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| First N | | ALX [S73], NIFI [S94] | yes: `limit`, and `row_limit` on read |
| Last N, skip N, 1 in N, ranges | | ALX [S73], TAL [S55], NIFI [S94] | no |
| Random percentage or N, with a seed | | SS [S21], ALX [S74], NIFI [S94], SPK [S148][S149] | no |
| Reservoir sampling | | NIFI [S94], TH | no |
| Sample by group | | ALX [S73] | no |
| Rows not selected to a second output | | SS [S21] | no |

### 1.14 window and rank

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Partition and order | | MAT [S111][S112], SPK [S143] | planned 0049-6.2 |
| Row number, rank, dense rank, percent rank, cumulative distribution, ntile | | MAT [S111], SPK [S143] | planned |
| Top or bottom N per group | | INF [S38] | planned |
| Frame (rows or range, bounds) | | MAT [S112], SPK [S143] | planned |
| Lead and lag with an offset; IGNORE NULLS | | MAT [S113], SPK [S143] | planned |
| Values for missing neighbour rows | | ALX [S76] | planned |

### 1.15 pivot and unpivot

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Pivot column, values to columns, aggregate | | MAT [S114], SPK [S144], GLUE [S169], SS [S22], KN [S60] | planned 0049-6.6 |
| Pivot keys that are not listed: ignore and report, or fail | | SS [S22] | planned |
| Unpivot names and values columns | | MAT [S115], GLUE [S170], SPK [S145] | planned |
| Include or exclude nulls on unpivot | | SPK [S145], GLUE [S170] | planned |
| Totals on pivot | | KN [S60] | planned |

### 1.16 normalise, split rows, nested data

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Split a delimited field into rows | | TAL tNormalize [S54], ALX Text To Columns [S77], TP, TH | planned 0049-6.5 |
| Split into columns, with extra-value handling | | ALX [S77], MAT [S116] | partly: `substring` and `regex_replace`; a split function is planned 0049-6.3 |
| Explode arrays, keeping rows whose array is empty (outer) | | SPK [S146], GLUE [S171], MAT [S117] | planned 0049-6.5 |
| Flatten nested structures to a depth | | GLUE [S172] | planned |
| Trim values, drop empty values, drop duplicate items | | TAL [S54] | planned |

### 1.17 type conversion and cleansing

| Setting | What it does | Tools | Kilnmark |
| --- | --- | --- | --- |
| Schema compliance check: type, length, nullable, date pattern, with rejects carrying an error code and message | | TAL [S56], NIFI ValidateRecord [S89] | partly: `gate` rules, with failing rows quarantined |
| Strict or coercing type checks | | NIFI [S89] | strict only |
| Treat empty text as null | | TAL [S56], ALX [S75], Oracle in Kilnmark | partly: `nullif`; Oracle `empty_text_as_null` |
| Replace nulls with blank or 0; remove all-null rows and columns | | ALX [S75], GLUE [S153] | partly: `coalesce` |
| Change case, remove unwanted characters | | ALX [S75] | partly: lower and upper; no title case |
| Impute missing values | | GLUE FillMissingValues [S173] | no |
| Find personal data, then redact, partly redact or hash it | | GLUE [S174] | planned: ADR 0050 detect-personal-data, and `mask` 0049-6.9 |
| Data quality rules with warn, drop or fail | | SPK expectations [S150], GLUE DQDL [S175], DBT tests with warn_if and error_if [S127] | yes: `gate` with stop, quarantine or warn, `min_score`, `max_failing_share` |
| Store failing rows | | DBT store_failures [S126], GLUE row-level outcomes [S175] | yes: `quarantine` port |
| Detect changes: new, changed, deleted, identical | | MAT [S110], TH | planned 0049-6.7 |

---

## 2. Across nodes

| Topic | What other tools offer | Tools | Kilnmark today |
| --- | --- | --- | --- |
| Error output per node | Rows that fail a conversion, an expression or a write go to an error output, not the run's end | SS (per column: fail, ignore or redirect, with errors and truncations set apart) [S18]; TAL rejects links [S49][S56]; NIFI failure and retry relationships [S85]; KN error columns [S64]; ADF rejected rows from the sink [S5]; TP and TH (an error output on most units) | partly: `rejected` on filter, lookup and dedupe, and `quarantine` on gate. An expression, cast or write error stops the run |
| Fields added to error rows | Error code, the column that failed, the message, an error count | SS ErrorCode and ErrorColumn [S18]; TAL [S56]; ADF operation, code and message [S5]; GLUE error records [S177]; TP, TH | no (a gate's quarantine records which rules failed, in the run) |
| Maximum errors before failing | An absolute count, or a share of rows | INF stop on errors [S41]; GLUE stageThreshold and totalThreshold [S177]; ALX conversion error limit [S79]; DBT error_if and warn_if [S127]; TP and TH (count, percentage, rows read before the percentage is judged) | partly: gate `max_failing_share` only |
| Redirect, ignore or fail | Three choices per node, or per column | SS [S18]; ADF continue on error [S5]; TAL die on error [S49]; KN fail or ignore [S64] | partly: gate stop, quarantine or warn |
| Retry and back-off for rows | | NIFI [S95] | no (flow nodes retry) |
| Schema drift | Allow, validate, infer, evolve the target, rescue unknown columns | ADF [S4][S1]; DBT on_schema_change [S121]; SPK [S134][S137]; NIFI unmatched behaviour [S85]; GLUE catalogue update behaviour [S157] | strict: checked before the run; append refuses mismatches. No setting to choose a different behaviour |
| Type coercion | Lenient versus strict conversion; automatic type fixes | NIFI strict type checking [S89]; MAT fix data type mismatches [S100]; ALX conversion errors become null [S72]; GLUE ResolveChoice [S159] | numbers widen; text never converts silently; a bad cast stops the run |
| Nulls | Nulls first or last; nulls equal in joins; ignore nulls in aggregates and windows; include nulls on unpivot and explode | SPK [S142][S143][S145]; SS [S13][S14]; GLUE [S170][S171]; MAT [S110] | SQL rules; sort nulls last by default; join keys never match on null; dedupe treats nulls as equal |
| Memory and spill | Buffer sizes, cache sizes, temporary folders, a memory policy per node | SS buffers and temp paths [S19]; INF caches [S34][S42]; TAL [S45][S52][S53]; KN per-node memory policy [S65]; ALX sort and join memory [S80]; SPK memory fraction [S152] | `memory_limit_mb` on six nodes; spills only on `sort` and `join` |
| Parallelism | Engine threads; copies of a node; partitions; repartition hints | SS EngineThreads [S19]; NIFI concurrent tasks [S95]; SPK shuffle partitions, AQE and hints [S151][S141]; GLUE autobalance [S176]; DBT concurrent batches [S122]; TP and TH (several copies of a node) | nodes run at the same time, with back-pressure between them. No per-node copies or partitions |
| Logging and row counts | Rows per node; error counts; a log level; row-count variables | SS progress and row count [S19]; TAL NB_LINE and stat catcher [S50][S53]; NIFI record.count and bulletins [S88][S95]; SPK expectation metrics [S150]; GLUE metrics [S175]; ALX conversion errors in the results [S81] | strong: rows in and out per port, peak memory, time split, a timeline, rows per second, the log (with an NDJSON download), and gate verdicts |
| Back-pressure | Queue limits between nodes | NIFI 10,000 objects or 1 GB [S95] | yes (the run page shows "held up") |

---

## 3. The 15 gaps that matter most for banks

1. **No error output for rows that fail on expressions, casts or writes.** Today one bad value or
   one constraint violation stops the run. Banks must keep every rejected row, with its error code,
   its column and the reason, and reconcile rows read against rows written and rows rejected.
   Most tools offer this [S18][S5][S49][S85].
2. **No maximum number or share of errors, outside a gate.** Without a stated tolerance, a run can
   only fail or succeed. Control owners want "fail if more than N rows, or more than x%, are
   rejected", recorded in the run [S41][S177][S79].
3. **No incremental reads by watermark, and no change data capture.** Daily deltas from core
   systems would mean full re-reads. The watermark used should be recorded with each run, so a load
   can be repeated exactly [S5][S82][S154].
4. **No history (type 2 SCD) as a write mode.** Point-in-time reporting, and restating what was
   known on a date, needs versioned rows with valid-from and valid-to dates [S123][S23]. It is
   planned as 0049-6.7.
5. **No change detection.** Comparing two loads into new, changed, deleted and unchanged rows is the
   basis of reconciliation and of change reports to regulators [S110]. It is planned as 0049-6.7.
6. **No deterministic choice of which row wins.** Three places decide by arrival order: upsert
   (when the input repeats a key, the last row wins silently), lookup (`first`) and dedupe (the
   first row). Auditors need the rule stated, for example the latest row by a timestamp, or an
   error [S2][S29][S63].
7. **No delete, update-only, insert-only or conditional merge, and no way to leave some columns
   untouched.** These are needed for erasure requests, for corrections, and for keeping audit
   columns such as `created_at` [S8][S136][S120].
8. **No range (non-equi) lookup or join.** Rates, prices and limits valid on the transaction date
   (valid-from and valid-to) are everyday banking lookups [S2][S3].
9. **No window or rank node.** Running balances, the latest record per account, top N, and
   comparing a row with the one before it [S111][S143]. It is planned as 0049-6.2.
10. **No JSON or XML parsing or building.** Payment messages (for example ISO 20022 XML) and
    API results are nested [S171][S117]. It is planned as 0049-6.5.
11. **No explicit choice of schema-drift behaviour, and no column contract on a write.** Kilnmark
    is strict today, which is safe, but a bank wants the choice (fail, ignore new columns, add
    them) declared on the node and recorded, with a contract on the target [S121][S124][S134].
12. **No masking, hashing or tokenising node.** Test copies of production data need it [S174].
    It is planned as 0049-6.9, and ADR 0050 adds detection.
13. **No partitioned (parallel) reads and no fetch size.** Without them, large overnight extracts
    may not fit the batch window [S131][S156][S82].
14. **Aggregate and dedupe hold everything in memory, with no spill, and aggregate has few
    functions.** It lacks first, last, median, percentile and standard deviation, which risk and
    finance reports use [S51][S166].
15. **No case-insensitive or locale-aware comparison, and no reproducible sampling.** Sorting,
    dedupe and key matching cannot ignore case or follow a locale [S16][S36]. There is no random
    sample with a seed for audit testing and model validation [S21][S74].

Near misses, still worth doing:

- pivot and unpivot (planned 0049-6.6);
- union by position;
- route to all matching outputs;
- default values on a lookup miss;
- a lookup cache shared across pipelines;
- fuzzy matching (name screening, which banks often do in a specialist tool).

---

## 4. Migration map: Tool P and Tool H to Kilnmark

**Scope.** Both tools share almost all their data units and settings, as Tool H descends from
Tool P, so one map covers both. Where they differ, the row says so.

**How units are named.** By their plain meaning, not by the tools' own labels.

**Where the facts come from.** Their documentation, summarised; nothing is copied. Their sources are
kept outside the repository.

**What the Kilnmark column means.**

- **yes:** a direct equivalent exists.
- **partly:** an equivalent exists, and the row says what is missing.
- **no equivalent:** the row names the ADR 0049 item that will cover it, where one does.

### 4.1 Inputs and outputs

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| Table input (SQL) | partly: `read` with `asset`, `columns` and `where`; or `query` | A query is allowed only on PostgreSQL with Allow SQL on, so a query against another database must be rewritten as `asset` plus `where`. Variables are not filled in a query. The source tools' limit maps to `row_limit` |
| Table input run once per incoming row | no equivalent | Rewrite as a `join` or `lookup` |
| Text or CSV file input | partly: `read` on a files or S3 source (encoding, header, delimiter) | Per-field format masks, null-if values, defaults, trim type, "repeat last value", fixed-width layouts, and skipping bad lines or files have no equivalent. Parse in `compute` (`parse_date`, `trim`, `nullif`) |
| Spreadsheet input | partly | Spreadsheet files are catalogued but not read as rows (only CSV, TSV, JSON, JSON lines and Parquet are). Convert them to CSV first |
| JSON input | partly | JSON and JSON lines are read as rows, but nested paths cannot be picked out; that is planned as 0049-6.5 |
| XML input | no equivalent | Planned as 0049-6.5 |
| Table output | partly: `write` with `append` or `create` | **Commit size inverts:** the source tools use no transaction when it is 0, while Kilnmark's `commit_every: 0` means one transaction. Batch inserts map to `batch_size`. Truncate maps to `replace`, which swaps in a new table rather than emptying the old one, or to the flow `sql` node on PostgreSQL. Ignoring insert errors, a table name taken from a field, partitioning over tables, and returning generated keys have no equivalent |
| Insert or update by key | partly: `write` with `upsert` and `key` | Key comparators other than `=`, the per-column "don't update" flag, and insert-only mode have no equivalent. Kilnmark needs a unique index on the key, while the source tools look the key up instead. When the input repeats a key, Kilnmark keeps the last row |
| Update only | no equivalent | `upsert` also inserts missing rows. Change detection is planned as 0049-6.7 |
| Delete by key | no equivalent | Not planned yet. **Gap** |
| Text file output | partly: `write` to a file (CSV or Parquet) | Appending to an existing file has no equivalent (files are written whole), nor do splitting every N rows, number and date masks, or a header and footer of the tools' kind. Format values in `compute` first |
| Spreadsheet output | no equivalent | |
| JSON or XML output | no equivalent | Planned as 0049-6.5 |
| Bulk loader for a database | yes: `bulk_load` (MySQL and MariaDB) and `bulk_copy` (SQL Server) | Only for tables the node creates |
| Execute SQL inside a pipeline | no equivalent, by policy (ADR 0045) | The flow `sql` node covers PostgreSQL before or after a run. **Flag for a person** |

### 4.2 Lookups and joins

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| Stream lookup (in-memory) | yes: `lookup` | Kilnmark matches across numeric types by value and never matches text to a number. Check which duplicate the source job relied on, since Kilnmark takes the first. The memory-saving modes are not needed |
| Database lookup | partly: `lookup` fed by a `read` | Kilnmark loads the whole lookup side, like the tools' "load all data" mode. Other cache modes are missing. **Uncached or partly cached lookups see changes made during the run; Kilnmark reads a snapshot first.** Comparators other than `=`, choosing among several matches by an order, and defaults on a miss have no equivalent (use `coalesce`). Fail on several matches maps to `duplicates: error`; dropping rows that do not match maps to `unmatched: drop`. **Matching changes:** a database lookup follows the database's collation, often case-insensitive and ignoring trailing spaces, while Kilnmark's is exact |
| Database join (a parameterised query per row) | no equivalent | Rewrite as `join`, or `lookup` |
| Merge join (sorted inputs) | yes: `join` | No upstream sort is needed. Output comes in key order, with null keys last, and null keys never match. If the source job joined unsorted input, its results were wrong, so the converted counts may differ |
| Cartesian join with a condition | no equivalent | Non-equi joins are not planned. **Gap** |
| Fuzzy match | no equivalent | Not planned. **Flag for a person** |

### 4.3 Rows: filter, sort, dedupe, route, sample

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| Filter rows (true and false outputs) | yes: `filter` (`out` and `rejected`) | Translate the condition into an expression. Null counts as false in both |
| Switch or case on one field | partly: `route` | One condition per value; "contains" becomes `like '%x%'`; the default target becomes `out`. The source tools' value comparison follows the field's type and format, so check text against numbers |
| Sort rows | partly: `sort` | **No case-insensitive or locale (collation) ordering**: Kilnmark orders by Unicode code point, so mixed-case or accented text orders differently. Nulls go last unless `nulls first` is set. Check each job's null ordering. "Only unique rows" becomes `sort` and then `dedupe`. Temporary folder and compression settings are not needed |
| Unique rows (sorted input) | partly: `dedupe` | **Changes behaviour:** the source tools remove only consecutive duplicates, while Kilnmark removes all of them. Ignoring case and adding a counter have no equivalent. Redirected duplicates map to `rejected` |
| Unique rows (hash set) | yes: `dedupe` | |
| Sample rows (ranges) or reservoir sampling | partly: `limit` (first N) | Ranges and random samples have no equivalent |
| Append streams; sorted merge | yes: `union` (by name), then `sort` for sorted merge | The source tools require the same layout and append by position. Kilnmark matches by name, so check the names |
| Abort the run on a row | partly | A `gate` with `on_fail: stop`, or a `filter` feeding a stop gate |
| Blocking until all rows have arrived; dummy | not needed | |

### 4.4 Calculations and strings

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| Calculator (built-in operations) | partly: `compute` | Date add and difference, padding, hashing, and many others come in planned 0049-6.3. Length and precision on results become `cast` |
| Formula | yes: `compute` | Translate the syntax |
| String operations (trim, case, padding, initial capitals, remove special characters) | partly: `compute` | `trim`, `lower` and `upper` exist; padding is planned 0049-6.3; there is no initial-capitals function. Remove characters with `regex_replace`. Kilnmark's `trim` keeps a no-break space |
| Replace in string | yes: `replace`, `regex_replace` | Back-references are refused, and some regular expressions are refused for safety |
| Split field into columns | partly | `substring` or `regex_replace`; a split function is planned 0049-6.3 |
| Split field into rows | no equivalent | Planned as 0049-6.5 |
| Value mapper | yes: `compute` with `case` | Check the source's "default" for unmapped values |
| Null if; if null then a value; replace nulls | yes: `nullif`, `coalesce` | **Empty text against null:** check whether the source server treated empty text as null. Kilnmark keeps them apart |
| Add constants | yes: `compute` | |
| Add sequence; row numbers | no equivalent | Sequences are planned as 0049-6.8; row numbers as 0049-6.2 |
| System values (dates, run identifiers) | partly | `now()`, `today()` and parameters. Check for others, such as the run id or the host |
| Select and alter fields (rename, remove, retype) | partly: `select` | Keep, drop, rename, order and cast exist. Conversion masks, lenient parsing, locale, time zone, decimal and grouping symbols, and rounding type have no equivalent; dates can be parsed with a pattern in `compute` |
| Data validator | partly: `gate` | Rules map onto quality rules; rows that fail go to `quarantine` |
| Scripting (JavaScript, a Java class) | no equivalent, by design (ADR 0049 decision 5) | **Flag for a person**: rewrite as expressions, or as a web call (planned 0049-6.4) |

### 4.5 Shaping and history

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| Group by (sorted input); group by in memory | partly: `aggregate` | **Changes behaviour:** the sorted-input form groups only consecutive rows, while Kilnmark groups all of them, so unsorted inputs give different counts. Median, percentile, standard deviation, first and last, and concatenation have no equivalent, nor does "include all rows" (aggregate, then `lookup` back). Line numbers per group come with `window` (0049-6.2). There is no spill to disk |
| Row normaliser (unpivot) | no equivalent | Planned as 0049-6.6 |
| Row denormaliser (pivot) | partly | Write `aggregate` with a `case` inside each aggregate, one value per column, if the expression checker accepts it; otherwise wait for 0049-6.6 |
| Analytic query (lead and lag) | no equivalent | Planned as 0049-6.2 |
| Compare two streams (identical, changed, new, deleted) | no equivalent | Planned as 0049-6.7 |
| Synchronise after a compare (insert, update or delete by flag) | no equivalent | Planned as 0049-6.7, but delete is missing. **Gap** |
| Dimension lookup and update (type 1 and type 2 history) | no equivalent | Planned as 0049-6.7 (history as a write mode) and 6.8 (surrogate keys). The cache and preload settings, the technical key method and the "unknown" row have no equivalent yet |
| Combination lookup and update (key per unique combination) | no equivalent | Partly covered by 0049-6.8 |
| Generate rows | no equivalent | Planned as 0049-6.10 |

### 4.6 Web services and structure

| Unit (plain meaning) | Kilnmark | What changes, or is missing |
| --- | --- | --- |
| REST or HTTP client; HTTP post; web service lookup | no equivalent | Planned as 0049-6.4, behind the allowed-hosts list |
| Sub-pipeline (mapping); pipeline executor; metadata injection | no equivalent | **Flag for a person.** Flows and `for-each` cover some executor uses; injection has no counterpart |
| Copy rows to the next pipeline in a flow, and get them there | partly | Write to a table or file, then read it in the next pipeline |
| Write to log | partly | The run log records rows per node; there is no custom message per row |

### 4.7 Run settings that change behaviour on conversion

| Topic | In the source tools | In Kilnmark | What a converter must do |
| --- | --- | --- | --- |
| Error handling to another node | Most units can send failing rows to another node, with fields for the error count, the description, the fields and the codes, and a maximum count or percentage of errors | Only filter, lookup, dedupe and gate set rows aside | Report every redirect. Where the redirect fed a write, warn that the converted run will stop instead |
| Several copies of a node | Runs several threads of one node | Nodes run at the same time; there are no copies | Drop the setting and record it |
| Number, integer and decimal types | Floating-point number, 64-bit integer, and a big decimal | float64, int64 and `decimal(p,s)` | Keep floating point where the source used it, but **flag money held as floating point** |
| Dates | The date type carries a time; the source documentation describes locale and time-zone settings on conversion | Timestamps are always UTC, to the microsecond | **Flag for a person** wherever a mask, a locale or a time zone is set |
| Lenient date and number parsing | A choice on conversion | Strict only (31 February is empty) | Flag where lenient parsing is on |

### 4.8 Units that block conversion most often, ranked

The ranking judges how often a unit is used in typical jobs against how far Kilnmark is from it.

1. Scripting units (JavaScript, a Java class): no equivalent, by design.
2. Error handling to another node on calculations, conversions and outputs.
3. Database lookups that rely on cache-off behaviour, comparators or an order, and per-row
   database joins.
4. Dimension lookup and update (history) and sequences: planned 0049-6.7 and 6.8.
5. Sub-pipelines, executors and metadata injection.
6. JSON and XML input and output, and splitting fields into rows: planned 0049-6.5.
7. REST and HTTP units: planned 0049-6.4.
8. Table input with SQL against non-PostgreSQL sources, and SQL executed inside a pipeline.
9. Analytic query and row numbers: planned 0049-6.2.
10. Row normaliser and denormaliser: planned 0049-6.6.
11. Compare streams, and synchronise including deletes: planned 0049-6.7, with delete missing.
12. Update-only and delete outputs.
13. Field metadata changes with masks, locales and lenient parsing.

### 4.9 Cannot be converted safely: flag for a person

- **Code and SQL:** scripts, and SQL run inside a pipeline.
- **Dynamic structure:** metadata injection, table names taken from a field, and partitioning over
  tables.
- **Order-dependent results:** group by and unique rows on unsorted input, where the source gave
  consecutive-only results and someone may depend on them.
- **Ordering and matching rules:** sorts and comparisons that depended on case-insensitive or locale
  ordering; lookups whose matching followed a database collation; lookups with the cache off that
  read a table written in the same run.
- **Transactions:** outputs with a commit size of 0, where the source used no transaction and
  Kilnmark will use one.
- **Error redirects:** any unit whose errors were redirected or ignored.
- **Matching:** fuzzy matching.
- **Formats:** date conversions with a time zone or locale, empty text treated as null, and money
  held in floating point.
- **History:** dimension updates with "punch-through" (type 1 across all versions) or custom
  start dates.

---

## Sources

Every `[Sn]` above points here. All of these are official vendor documentation pages.

### Azure Data Factory

- S1 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-sink
- S2 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-lookup
- S3 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-join
- S4 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-source
- S5 https://learn.microsoft.com/en-us/azure/data-factory/connector-azure-sql-database (mapping data flow source and sink settings; error row handling)
- S6 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-sort
- S7 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-aggregate
- S8 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-alter-row
- S9 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-union
- S10 https://learn.microsoft.com/en-us/azure/data-factory/data-flow-conditional-split

### SSIS

- S11 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/ole-db-destination
- S12 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/lookup-transformation
- S13 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/merge-join-transformation
- S14 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/aggregate-transformation
- S15 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/sort-transformation
- S16 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/comparing-string-data
- S17 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/conditional-split-transformation
- S18 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/error-handling-in-data
- S19 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/data-flow-performance-features
- S20 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/fuzzy-lookup-transformation
- S21 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/percentage-sampling-transformation
- S22 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/pivot-transformation
- S23 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/slowly-changing-dimension-transformation
- S24 https://learn.microsoft.com/en-us/sql/integration-services/data-flow/transformations/data-conversion-transformation

### Informatica (from search extracts; the pages refuse automated reading)

- S25 https://docs.informatica.com/data-integration/powercenter/10-5-6/transformation-guide/source-qualifier-transformation/source-qualifier-transformation-properties.html
- S26 https://docs.informatica.com/data-integration/powercenter/10-4-0/workflow-basics-guide/targets/working-with-relational-targets/target-properties.html
- S27 https://docs.informatica.com/data-integration/powercenter/10-4-0/workflow-basics-guide/sources/working-with-relational-sources/defining-the-treat-source-rows-as-property.html
- S28 https://docs.informatica.com/data-integration/powercenter/10-5-8/transformation-guide/update-strategy-transformation/flagging-rows-within-a-mapping/lookup-and-update-strategy-transformations.html
- S29 https://docs.informatica.com/data-integration/powercenter/10-5/transformation-guide/lookup-transformation/lookup-condition/handling-multiple-matches.html
- S30 https://docs.informatica.com/integration-cloud/data-integration/current-version/transformations/lookup-transformation/lookup-object/lookup-object-properties/multiple-match-policy-restrictions.html
- S31 https://docs.informatica.com/data-integration/powercenter/10-5-7/transformation-guide/lookup-transformation/lookup-query/overriding-the-lookup-query/guidelines-for-overriding-the-lookup-query.html
- S32 https://docs.informatica.com/data-integration/powercenter/10-4-0/transformation-guide/dynamic-lookup-cache/dynamic-lookup-properties/newlookuprows.html
- S33 https://docs.informatica.com/data-integration/powercenter/10-5/transformation-guide/joiner-transformation/defining-the-join-type.html
- S34 https://docs.informatica.com/data-integration/powercenter/10-5/advanced-workflow-guide/session-caches/joiner-caches/configuring-the-cache-sizes-for-a-joiner-transformation.html
- S35 https://docs.informatica.com/data-integration/powercenter/10-5/transformation-guide/aggregator-transformation/using-sorted-input.html
- S36 https://docs.informatica.com/data-integration/powercenter/10-5-1/transformation-guide/sorter-transformation/sorter-transformation-properties.html
- S37 https://docs.informatica.com/data-integration/powercenter/10-4-0/transformation-guide/router-transformation/working-with-groups/output-groups/the-default-group.html
- S38 https://docs.informatica.com/data-integration/powercenter/10-5/transformation-guide/rank-transformation/rank-transformation-overview.html
- S39 https://docs.informatica.com/data-integration/powercenter/10-4-0/transformation-guide/rank-transformation/ports-in-a-rank-transformation/rank-index.html
- S40 https://docs.informatica.com/data-integration/powercenter/10-5/transformation-guide/sequence-generator-transformation/sequence-generator-transformation-properties.html
- S41 https://docs.informatica.com/integration-cloud/data-integration/current-version/tasks/data-integration-tasks/advanced-session-properties.html
- S42 https://docs.informatica.com/data-integration/powercenter/10-5/advanced-workflow-guide/understanding-buffer-memory/configuring-buffer-memory.html

### Talend

- S43 https://help.qlik.com/talend/en-US/components/8.0/tmap/tmap-lookup-models
- S44 https://help.qlik.com/talend/en-US/studio-user-guide/8.0-R2024-08/handling-lookups
- S45 https://help.qlik.com/talend/en-US/components/8.0/tmap/tmap-standard-properties
- S46 https://help.qlik.com/talend/en-US/components/8.0/tmap/tmap-tfileinputdelimited-tfileoutputdelimited-tfileoutputdelimited-mapping-data-using-inner-join-rejections-standard-component-this
- S47 https://help.qlik.com/talend/en-US/components/8.0/tmap/tmap-tfileinputdelimited-tfileoutputdelimited-tfileoutputdelimited-advanced-mapping-with-filters-and-different-rejections-standard-component-this
- S48 https://help.qlik.com/talend/en-US/components/8.0/tjoin/tjoin-standard-properties
- S49 https://help.qlik.com/talend/en-US/components/8.0/mysql/tmysqloutput-standard-properties (the same settings as tDBOutput per database)
- S50 https://help.qlik.com/talend/en-US/components/8.0/tfilterrow/tfilterrow-standard-properties
- S51 https://help.qlik.com/talend/en-US/components/8.0/taggregaterow/taggregaterow-standard-properties
- S52 https://help.qlik.com/talend/en-US/components/8.0/tsortrow/tsortrow-standard-properties
- S53 https://help.qlik.com/talend/en-US/components/8.0/tuniqrow/tuniqrow-standard-properties
- S54 https://help.qlik.com/talend/en-US/components/8.0/tnormalize/tnormalize-standard-properties
- S55 https://help.qlik.com/talend/en-US/components/8.0/tsamplerow/tsamplerow-standard-properties
- S56 https://help.qlik.com/talend/en-US/components/8.0/tschemacompliancecheck/tschemacompliancecheck-standard-properties

### KNIME

- S57 https://www.knime.com/sites/default/files/inline-images/_manipulation_column_column_split_combine_Joiner.html
- S58 https://hub.knime.com/knime/extensions/org.knime.features.base/latest/org.knime.base.node.preproc.joiner3.Joiner3NodeFactory
- S59 https://hub.knime.com/knime/extensions/org.knime.features.base/latest/org.knime.base.node.preproc.groupby.GroupByNodeFactory
- S60 https://www.knime.com/sites/default/files/inline-images/org.knime.base.node.preproc.pivot.Pivot2NodeFactory.html
- S61 https://www.knime.com/sites/default/files/inline-images/_manipulation_row_row_filter_Row_Filter.html
- S62 https://hub.knime.com/knime/extensions/org.knime.features.base/latest/org.knime.base.node.preproc.sorter.SorterNodeFactory
- S63 https://hub.knime.com/knime/extensions/org.knime.features.base/latest/org.knime.base.node.preproc.duplicates.DuplicateRowFilterNodeFactory
- S64 https://docs.knime.com/ap/latest/databases/insert-update-merge
- S65 https://www.knime.com/faq

### Alteryx (from search extracts; the pages render empty to automated reading)

- S66 https://help.alteryx.com/current/en/designer/tools/join/join-tool.html
- S67 https://help.alteryx.com/current/en/designer/tools/preparation/filter-tool.html
- S68 https://help.alteryx.com/current/en/designer/tools/preparation/unique-tool.html
- S69 https://help.alteryx.com/20231/en/designer/tools/preparation/sort-tool.html
- S70 https://help.alteryx.com/current/Output_Options.htm
- S71 https://help.alteryx.com/current/en/designer/tools/in-out-tools/output-data-tool.html
- S72 https://help.alteryx.com/current/en/designer/tools/preparation/select-tool.html
- S73 https://help.alteryx.com/20241/en/designer/tools/preparation/sample-tool.html
- S74 https://help.alteryx.com/current/en/designer/tools/preparation/random---sample-tool.html
- S75 https://help.alteryx.com/current/en/designer/tools/preparation/data-cleansing-tool.html
- S76 https://help.alteryx.com/current/en/designer/tools/preparation/multi-row-formula-tool.html
- S77 https://help.alteryx.com/current/en/designer/tools/parse/text-to-columns-tool.html
- S78 https://help.alteryx.com/current/en/designer/workflows/workflow-configuration.html
- S79 https://help.alteryx.com/current/en/designer/workflows/workflow-configuration/conversion-errors.html
- S80 https://help.alteryx.com/current/en/designer/get-started/user-settings/memory-use.html
- S81 https://help.alteryx.com/current/en/designer/workflows/results-window.html

### Apache NiFi

- S82 https://nifi.apache.org/components/org.apache.nifi.processors.standard.QueryDatabaseTableRecord/
- S83 https://nifi.apache.org/components/org.apache.nifi.processors.standard.GenerateTableFetch/
- S84 https://nifi.apache.org/components/org.apache.nifi.csv.CSVReader/
- S85 https://nifi.apache.org/components/org.apache.nifi.processors.standard.PutDatabaseRecord/
- S86 https://nifi.apache.org/components/org.apache.nifi.processors.standard.QueryRecord/
- S87 https://nifi.apache.org/components/org.apache.nifi.processors.standard.UpdateRecord/
- S88 https://nifi.apache.org/components/org.apache.nifi.processors.standard.PartitionRecord/
- S89 https://nifi.apache.org/components/org.apache.nifi.processors.standard.ValidateRecord/
- S90 https://nifi.apache.org/components/org.apache.nifi.processors.standard.ConvertRecord/
- S91 https://nifi.apache.org/components/org.apache.nifi.processors.standard.LookupRecord/
- S92 https://nifi.apache.org/components/org.apache.nifi.processors.standard.JoinEnrichment/
- S93 https://nifi.apache.org/components/org.apache.nifi.processors.standard.DeduplicateRecord/
- S94 https://nifi.apache.org/components/org.apache.nifi.processors.standard.SampleRecord/
- S95 https://nifi.apache.org/docs/nifi-docs/html/user-guide.html

### Matillion

- S96 https://docs.maia.ai/docs/components/table-input
- S97 https://docs.maia.ai/docs/components/database-query
- S98 https://docs.maia.ai/docs/components/sharepoint-load
- S99 https://docs.matillion.com/metl/docs/2955330/
- S100 https://docs.maia.ai/docs/components/table-output
- S101 https://docs.maia.ai/docs/components/table-update
- S102 https://docs.maia.ai/docs/components/rewrite-table
- S103 https://docs.maia.ai/docs/components/filter
- S104 https://docs.maia.ai/docs/components/calculator
- S105 https://docs.maia.ai/docs/components/generate-sequence
- S106 https://docs.maia.ai/docs/components/join
- S107 https://docs.maia.ai/docs/components/aggregate
- S108 https://docs.maia.ai/docs/components/unite
- S109 https://docs.maia.ai/docs/components/distinct
- S110 https://docs.maia.ai/docs/components/detect-changes
- S111 https://docs.maia.ai/docs/components/rank
- S112 https://docs.maia.ai/docs/components/window-calculation
- S113 https://docs.maia.ai/docs/components/lead-lag
- S114 https://docs.maia.ai/docs/components/pivot
- S115 https://docs.maia.ai/docs/components/unpivot
- S116 https://docs.maia.ai/docs/components/split-field
- S117 https://docs.maia.ai/docs/components/extract-nested-data
- S118 https://docs.maia.ai/docs/components/convert-type

### dbt

- S119 https://docs.getdbt.com/docs/build/materializations
- S120 https://docs.getdbt.com/docs/build/incremental-strategy
- S121 https://docs.getdbt.com/docs/build/incremental-models
- S122 https://docs.getdbt.com/docs/build/incremental-microbatch
- S123 https://docs.getdbt.com/docs/build/snapshots
- S124 https://docs.getdbt.com/reference/resource-configs/contract
- S125 https://docs.getdbt.com/reference/resource-properties/constraints
- S126 https://docs.getdbt.com/docs/build/data-tests
- S127 https://docs.getdbt.com/reference/resource-configs/severity
- S128 https://docs.getdbt.com/reference/resource-configs/pre-hook-post-hook
- S129 https://docs.getdbt.com/reference/resource-configs/grants
- S130 https://raw.githubusercontent.com/dbt-labs/docs.getdbt.com/current/website/docs/docs/build/incremental-strategy.md (the docs' own source, for the adapter support table)

### Spark and Databricks

- S131 https://spark.apache.org/docs/latest/sql-data-sources-jdbc.html
- S132 https://spark.apache.org/docs/latest/sql-data-sources-csv.html
- S133 https://docs.databricks.com/aws/en/ingestion/bad-records
- S134 https://docs.databricks.com/aws/en/ingestion/cloud-object-storage/auto-loader/schema
- S135 https://spark.apache.org/docs/latest/sql-data-sources-load-save-functions.html
- S136 https://docs.databricks.com/aws/en/delta/merge
- S137 https://docs.databricks.com/aws/en/delta/update-schema
- S138 https://docs.databricks.com/aws/en/delta/selective-overwrite
- S139 https://docs.databricks.com/aws/en/tables/constraints
- S140 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-join.html
- S141 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-hints.html
- S142 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-orderby.html
- S143 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-window.html
- S144 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-pivot.html
- S145 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-unpivot.html
- S146 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-lateral-view.html
- S147 https://spark.apache.org/docs/latest/streaming/apis-on-dataframes-and-datasets.html
- S148 https://spark.apache.org/docs/latest/api/python/reference/pyspark.sql/api/pyspark.sql.DataFrame.sample.html
- S149 https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-sampling.html
- S150 https://docs.databricks.com/aws/en/ldp/expectations
- S151 https://spark.apache.org/docs/latest/sql-performance-tuning.html
- S152 https://spark.apache.org/docs/latest/tuning.html

### AWS Glue

- S153 https://docs.aws.amazon.com/glue/latest/dg/edit-jobs-transforms.html
- S154 https://docs.aws.amazon.com/glue/latest/dg/monitor-continuations.html
- S155 https://docs.aws.amazon.com/glue/latest/dg/programming-etl-connect-bookmarks.html
- S156 https://docs.aws.amazon.com/glue/latest/dg/run-jdbc-parallel-read-job.html
- S157 https://docs.aws.amazon.com/glue/latest/dg/update-from-job.html
- S158 https://docs.aws.amazon.com/glue/latest/dg/transforms-configure-applymapping.html
- S159 https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-crawler-pyspark-transforms-ResolveChoice.html
- S160 https://docs.aws.amazon.com/glue/latest/dg/transforms-filter.html
- S161 https://docs.aws.amazon.com/glue/latest/dg/transforms-conditional-router.html
- S162 https://docs.aws.amazon.com/glue/latest/dg/transforms-derived-column.html
- S163 https://docs.aws.amazon.com/glue/latest/dg/transforms-identifier.html
- S164 https://docs.aws.amazon.com/glue/latest/dg/transforms-configure-join.html
- S165 https://docs.aws.amazon.com/glue/latest/dg/transforms-configure-union.html
- S166 https://docs.aws.amazon.com/glue/latest/dg/transforms-aggregate-fields.html
- S167 https://docs.aws.amazon.com/glue/latest/dg/transforms-drop-duplicates.html
- S168 https://docs.aws.amazon.com/glue/latest/dg/transforms-record-matching.html
- S169 https://docs.aws.amazon.com/glue/latest/dg/transforms-pivot-rows-to-columns.html
- S170 https://docs.aws.amazon.com/glue/latest/dg/transforms-unpivot-columns-to-rows.html
- S171 https://docs.aws.amazon.com/glue/latest/dg/transforms-explode-array.html
- S172 https://docs.aws.amazon.com/glue/latest/dg/transforms-flatten.html
- S173 https://docs.aws.amazon.com/glue/latest/dg/transforms-configure-fmv.html
- S174 https://docs.aws.amazon.com/glue/latest/dg/detect-PII.html
- S175 https://docs.aws.amazon.com/glue/latest/dg/tutorial-data-quality.html
- S176 https://docs.aws.amazon.com/glue/latest/dg/transforms-autobalance-processing.html
- S177 https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-crawler-pyspark-extensions-dynamic-frame.html

### Kilnmark's own documents

- `docs/guide/reference-pipeline-nodes.md`
- `docs/guide/pipeline-nodes.md`
- `docs/guide/writing-data.md`
- `docs/guide/reference-connectors.md`
- `docs/guide/reference-flow-nodes.md`
- `docs/adr/0049-more-nodes-and-how-coarse-they-are.md`
- `docs/adr/0050-ai-in-pipelines.md`

---
