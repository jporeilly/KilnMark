# 0019 Relationships: declared and inferred, in PostgreSQL tables, proposed for people to decide

Status: Accepted (24 October 2026)

## Decision

Kilnmark records relationships between assets at field level in one table, `relationship`
(migration V8): from an asset and its ordered fields to another asset and its ordered fields,
with a kind, a status, a confidence, its evidence, and who decided and when.

- **Declared** relationships are the foreign keys a source declares. The JDBC catalogue reads them
  with `DatabaseMetaData.getImportedKeys`: one call per schema on PostgreSQL (its driver accepts no
  table name), one per table elsewhere. A multi-column key stays together in key order
  (`KEY_SEQ`); a nameless one is grouped by `KEY_SEQ` starting again at 1. If the keys cannot be
  read, the scan records the catalogue without them and says so in the run's detail. Keys between schemas of one source are kept when both schemas are
  scanned. They are always `accepted`; a rescan replaces them with what the source declares now.
- **Inferred** relationships are proposals: a column whose name says it refers to another
  table's key, whose type matches, whose target is unique, and whose values lie inside the
  target's as far as their sketches tell. They start `proposed`. A steward accepts or rejects
  them; nothing is applied without a person (ADR 0012). A rescan updates their confidence and
  evidence and keeps the decision; a proposal not found again is withdrawn; a rejected one is
  never proposed again while its fields exist.
- Either asset going (a source deleted) takes the relationship with it (`ON DELETE CASCADE`).

## How the connector interface carries them

`AssetInfo` is unchanged: other connectors construct it, and a record's canonical constructor
cannot gain a component without breaking them. Instead the SPI gains, as default methods only:

- `RelationshipInfo(fromPath, fromFields, toPath, toFields, name)`, by asset path;
- `CatalogInfo(assets, relationships)`;
- `Connector.catalogWithRelationships(source)`, by default `new CatalogInfo(catalog(source), List.of())`.

`JdbcConnector` overrides it to read tables and keys on one connection, and its `catalog` now
returns `catalogWithRelationships(source).assets()`; a database whose driver reports keys oddly
overrides `readForeignKeys`. The scan calls `catalogWithRelationships`. A connector that never
heard of relationships keeps working unchanged. Relationships are a property of the catalogue as
a whole, not of one asset, so they sit beside the assets rather than inside them.

## How inference works, and what bounds it

1. **Sketches kept with profiles.** Profiling already builds a HyperLogLog sketch (lgK 14) of
   every column for its distinct count. For key-like integer, number and text columns (an
   identifier-like name, or the whole single-column primary key: the columns inference can use)
   it now also emits the sketch, serialised compact with 4-bit buckets (at most about 8 KiB), and
   the scan stores it in `profile.hll`. Only a field's latest profile keeps one. Containment of A
   in B is then `(|A| + |B| - |A ∪ B|) / |A|`, from a union of the two sketches, with no second
   read of either source. All three counts are composite estimates: mixing them with the HIP
   estimate a sketch keeps while it is built biased `|A ∪ B| - |B|` by several hundred on a large
   B and hid real keys under about 13% of B. With one estimator a true subset leaves B's buckets
   unchanged, so it scores exactly 1 at any size; on a 100,000-value key, subsets of 2%, 5% and
   10% score 1, half-overlaps 0.47 to 0.50, and disjoint sets under 0.07. The margin is about 10%
   of the values A adds to B plus two standard errors of A's own count (both measured), relative
   to A. Values hash alike only within a type family (whole numbers, other numbers, text), so only
   those pairs are compared.
2. **Candidates from names only.** Targets are key-like columns of current assets: a single-column
   primary key, or an identifier-like name (`id`, `..._id`, `key`, `code`, `ref`, `no`, `number`,
   `uuid`) with no nulls and distinct values equal to rows (98% above 1,000 rows, for the sketch's
   error). A referring column must have an identifier-like name other than a bare `id`. Rules,
   strongest first: `customer_id` to `customers.id` (table name and id), `customer_id` to
   `customers.customer_id` (same name as the table's key), `billing_customer_id` to `customers.id`
   (a prefix, on word boundaries: names are split at underscores and camelCase humps, and the
   words after the prefix must be the table's name, so `account_id` never matches `counts` nor
   `address_id` `dresses`), and the same column name elsewhere. Names compare without case or punctuation, and
   table names in the singular (`customers`, `categories`, `addresses`, file extensions dropped).
3. **Bounded.** At most 200,000 key-like columns are read, 5,000 candidate pairs scored (strongest
   names first; the rest are counted and logged), and three proposals kept for one column.
   Sketches are loaded only for the columns of the pairs scored. After a scan of source S, only
   pairs with at least one end in S are scored, across every source.
4. **Score.** With a conclusive containment estimate (margin up to 0.25):
   `0.3 × name + 0.1 × uniqueness + 0.6 × containment`, the containment counting less below ten
   distinct values; under half contained says no, and under 0.9 caps the confidence at 0.7, so a
   join that leaves a tenth of its values unmatched is never a strong proposal. Without a
   conclusive estimate, names alone give at most 0.5.
   Proposals of 0.75 or more are kept, capped at 0.99. Of two copies of one key referring to each
   other, only the stronger direction is kept. Evidence (rule, containment, its margin, distinct
   counts, types) is stored as JSON with each proposal.

On the reference lab this proposes `billing.transactions.customer_id` to `crm.customers` (and
`account_id` to `crm.accounts`) at 0.99, and the CSV export, the JSON lines file and the archive
copy to `crm.customers`; `crm.accounts` to `crm.customers` is declared.

## Why not the AGE graph (yet)

ADR 0003 chose Apache AGE for the metadata graph, and migration V2 expects relationships to go
to the graph `kilnmark_graph` once discovery finds them. Today `JdbcGraphStore` writes no nodes to it: assets, fields and profiles live in relational
tables only. Mirroring relationships as edges would first need every asset (and field) mirrored
as a node and kept in step on every scan, removal and source deletion; that is its own decision
with its own failure modes (two stores to keep consistent, AGE's cost on large estates), and
nothing reads the graph yet. The relational table answers what the product needs now (one
asset's relationships, a container's diagram, two-step neighbourhoods) with indexed joins. When
lineage or path queries arrive, a projection into `kilnmark_graph` can be built from
`relationship` and `asset` in one pass, since the table is the source of truth.

## The diagram

The browse page draws schema diagrams with **@xyflow/react** 12.12.0 (MIT; with @xyflow/system,
zustand and classcat, MIT, and d3-zoom, d3-drag, d3-selection and friends, ISC, plus d3-ease,
BSD-3-Clause) and lays them out with **@dagrejs/dagre** 3.1.2 (MIT; with @dagrejs/graphlib,
MIT). Together they add 228 kB minified (74 kB gzipped) to a chunk loaded only when a diagram is
shown. We considered drawing SVG ourselves (pan, zoom and edge routing are most of the work) and
ELK (EPL, refused). At most 60 tables are drawn (150 on request); beyond that the page says so
and offers a filter or a table's neighbourhood. The same relationships are always listed in a
table beside the diagram, so keyboard and screen-reader users lose nothing (ADR 0009).

## Costs

- About 8 KiB per profiled key-like column in `profile.hll`.
- Inference after each scan reads every key-like column's latest counts (the scanned source's
  first, so the 200,000 cap never cuts it), then sketches for at most 5,000 pairs.
- Refreshes run one at a time across every server process, under a PostgreSQL advisory lock held
  for the whole step (declared keys, inference, inferred rows), and write rows in one sorted
  order. Two scans finishing together therefore never deadlock, and the second sees the first's
  result. A large estate's refresh holds up others' for its length.
- Responses are bounded: relationships come a page at a time (100 for an asset), and a diagram
  reads at most 1,000 relationships, one query per neighbourhood level.

## Limits of the evidence

- **Small columns.** Below ten distinct values the containment counts for less, and below about
  a hundred a few stray values move it a lot; such columns rarely make strong proposals.
- **Whole-number surrogate keys are weak evidence.** Sequences 1..N overlap: any column of small
  integers lies inside any larger key's range. The name rules carry most of the weight there, and
  containment only rules pairs out; a steward still decides.
- **Estimates.** Distinct counts and containment are HyperLogLog estimates (about 1% error); a
  key with a few orphan values may score just under the 0.9 bar.
- Name rules miss renamed keys (`billing.invoices.cust_ref`, text, holds customer ids): finding
  those needs comparing sketches across all pairs of compatible columns, which is left for later.
- Inferred relationships are single-column; composite keys are only found when declared.
