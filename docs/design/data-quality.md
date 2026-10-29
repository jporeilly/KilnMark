# Data quality and discovery

Status: build step 1 built (round 2, stream E; ADR 0020). Build order at the end.

Kilnmark qualifies data before anyone relies on it. A scan profiles every column. Quality
measures follow from the profile with no set-up. People then add rules, and AI proposes more.
A pipeline can then refuse data that fails its contract. Everything is recorded in one metadata
model and exchanged in open formats.

## 1. What profiling keeps

Profiling already computes, per column, rows, nulls, distinct (HyperLogLog), quantiles (KLL),
frequent values, lengths and min/max. It adds:

- **The HLL sketch itself**, stored in compact form, so relationship and containment checks
  never read the data again.
- **Value patterns over every row**, not a sample. Each value is reduced to its shape: letters to
  `A`/`a`, digits to `9`, punctuation kept, runs optionally collapsed. For example, `SW1A 1AA`
  becomes `AA9A 9AA` and `2024-03-01` becomes `9999-99-99`. A frequent-items sketch keeps about
  the top 20 shapes with counts and the share of values outside them. The cost is one pass and a
  few kilobytes per column.
- **Detectors** for common formats: email, phone, UK postcode, NI number, IBAN, card number (Luhn),
  ISO dates, numbers and booleans stored as text, JSON in text, and leading or trailing spaces.
- **Samples** (section 2).

## 2. Sampling

Patterns and sketches cover all rows. Samples exist for what needs real values: example values
for each pattern, regex suggestions, rule previews, AI suggestions, the data preview on the
asset page, and comparing scans.

- **Rows, not values.** A sample is a set of whole rows, so values line up across columns. This
  lets a rule that spans columns be previewed.
- **Coordinated and repeatable.** A row is chosen when the hash of its key (primary key, or
  the whole row when there is none) falls below a threshold, a bottom-k sample. The same rows are
  chosen on every scan, so changes between scans are real, not sampling noise. Related tables
  sampled on the same key values pick matching rows, so samples can be joined to preview
  relationship checks.
- **Unkeyed and streamed data** (files, later Kafka) uses reservoir sampling (Algorithm L),
  seeded per asset so it is repeatable for the same input.
- **Large tables** can be sampled at the source (`TABLESAMPLE` on PostgreSQL and SQL Server) when
  a full read is too costly. The profile then says it is an estimate and from how many rows.
- **Per pattern**, a few example values and a few values that match no accepted pattern, so a
  steward sees what `AA9A 9AA` is and what the failures look like.
- **Sizes**: default 1,000 rows per asset and 5 examples per pattern, configurable per source.

Samples hold real data, so they are controlled:

- A source setting `keep_samples` (default on). When it is off, only shapes and statistics are
  kept, never values.
- Samples are replaced on every scan and deleted with the asset.
- Viewing samples needs a role that may see data. Columns classified as sensitive are shown masked
  unless the viewer is allowed to unmask them.
- Samples go to a hosted AI model only when the source's `ai_egress` is `allowed`. A local model
  may use them under `local_only`. Under `none`, no model sees them.

## 3. Automatic pre-analysis

Straight after profiling, with no rules and no model, each column gets measures by dimension:

| Dimension | Measure |
| --- | --- |
| Completeness | Nulls and blanks as a share of rows |
| Uniqueness | Duplicates in declared keys; columns that look like keys but are not unique |
| Validity / conformity | Share of values matching the column's accepted patterns, or its detected format |
| Consistency | Several competing shapes for the same kind of value (two date formats); impossible values (future date of birth, negative quantity) |
| Accuracy (estimated) | Outliers beyond the quartile fences; values outside a detected domain |
| Timeliness | Latest timestamp against the scan time and the expected cadence |
| Volume | Row count change since the previous scan |
| Referential integrity | Orphans on declared and accepted relationships, from the stored sketches |

Each gets an estimated score with its evidence: the patterns, how many matched and examples.
A heat map shows each pattern's share. Kilnmark suggests a regex for the dominant patterns.
A steward accepts patterns with one click. Accepted patterns become validity rules.

## 4. Rules

- A library of parameterised checks: not null, unique, allowed values, matches pattern, in range,
  freshness, row count and its change, references (foreign key holds), and custom SQL. Each is
  tagged with a dimension.
- Thresholds as a share or a count for pass, warn and fail. Actions: set status, apply tags, call
  a webhook, open a steward task.
- Run after each scan, on a schedule through the job queue, or on demand. On databases, checks
  run as SQL in the database. On files, Kiln evaluates them while streaming. Results are kept
  per run, with a query that finds the failing rows.
- Rules preview against the sample before saving ("would fail 3.1% of sampled rows").
- Without AI, Kilnmark recommends rules from the profile (patterns, ranges, sets of values,
  uniqueness), as some cloud tools do.

## 5. AI

AI runs through the model runtime (ADR 0012, ADR 0016) and the per-source `ai_egress` rule.
A person approves every proposal before it takes effect.

- Suggests rules and explains them, using names, profiles, patterns and, where allowed, samples.
- Classifies what a column holds beyond dictionaries and patterns, with steward feedback
  improving later suggestions.
- Explains why a check failed and what changed since the last good run.
- Drafts a data contract for an asset.

## 6. Contracts and the unified metadata model

One model holds sources, containers, assets, fields, documents, profiles, patterns, samples,
relationships, rules, results, classifications, glossary terms, lineage and contracts. One API
and the MCP server expose it. Open formats at the edges:

- **Data contracts** in the Open Data Contract Standard (ODCS) 3.1: schema, quality rules,
  freshness and other service levels, and owners. Kilnmark rules map to the contract's quality
  section, for import and export.
- **Lineage** as OpenLineage events: Kiln emits them for its runs and accepts them from other
  tools.

## 7. Quality gate for pipelines

A pipeline's read node can require the asset's contract to hold. It either uses the latest
results when they are recent enough or runs the checks inline. On failure the run stops, or the
failing rows are diverted to a quarantine output, as the pipeline chooses.

Built so far (docs/guide/pipelines.md, "Quality gates"): a `gate` node that runs the asset's
enabled rules, and rules written on the node, inline on the Arrow batches passing through
(`RowChecks`, one pass, no second read), with `on_fail: stop`, `quarantine` (a `quarantine`
output port) or `warn`, and run-level thresholds (`min_score`, `max_failing_share`). Each gate and
each write gets a measured score per dimension and overall, stored with the run
(`pipeline_node_quality`, beside an empty `predicted` for section 8's design-time scores), and a
pipeline's `quality.min_score` tells its followers of a run whose outputs score below it. Still to
come: reusing recent results when the change check says the source is unchanged.

## 8. Profile once, reuse everywhere

Data is read for quality once, as far upstream as possible, and what is learnt travels with it.
Pipelines do not re-read sources to find out what they hold.

- **At design time, no data is read.** A pipeline's inputs already have profiles, patterns,
  samples, relationships and rule results in the catalogue. Kilnmark propagates them through the
  nodes to predict the quality of each output. A not-null filter drops a known share of rows. A
  join's orphan rate comes from the stored sketches. A date parse fails on the share of values in
  other shapes. The pipeline gets a predicted quality score per output and per dimension before
  it ever runs.
- **AI suggests improvements from that metadata**, for example: trim these columns, standardise
  the two date formats, deduplicate on this key, repair these orphans with a lookup, quarantine
  rows outside the accepted patterns. Each suggestion shows its predicted effect on the score. A
  person adds the ones they want as nodes.
- **At run time, nothing is read twice.** Before reading, the gate checks whether the source
  changed since its last profile (row count and watermark, file size and modified time or ETag,
  database change markers). If it did not change, the stored results stand. While data flows,
  checks and profiling run on the same Arrow batches through the metadata side channel, in the
  one pass the pipeline makes anyway.
- **Outputs are profiled as they are written** and registered in the catalogue with lineage, so
  the next pipeline downstream starts from metadata too.
- **Incremental.** HLL, KLL and frequent-items sketches merge, so new rows or new files are
  profiled alone and merged into the asset's profile. Only a structural change forces a full
  re-profile.
- **Limits.** Predicted scores are estimates and are labelled as such. A gate that must be exact,
  such as a financial reconciliation, runs its checks exactly in the stream, which still costs
  no extra read. Reused results are only trusted while the change check says the data is the
  same.

The run then reports the predicted and the measured scores side by side. The gap tells stewards
where the metadata, or the pipeline, needs attention.

Built (docs/guide/pipelines.md, "Predicted quality"):

- **Prediction** (`QualityPredictor`): each read's latest profile, accepted shapes, relationships
  and rule results, carried through the nodes with a rule per node type, to an estimated profile
  at each write, scored with the same `Measures` and `Scores` a run uses for what it wrote (so the
  dimensions match), and to each gate's rules' failing shares, scored as the gate scores itself.
  Unknown stays unknown: a dimension without evidence gets no score, and each prediction lists
  what it could not tell and what it assumed. Orphan rates come from a relationship's stored
  containment, else from the two columns' HyperLogLog sketches (`Containment`), as a share of
  distinct values used as a share of rows. `POST /api/v1/pipelines/predict` (YAML in, for the
  canvas, debounced) and `GET /api/v1/pipelines/{name}/versions/{n}/prediction`; no source is read.
- **At run start** each write's and gate's prediction is stored in `pipeline_node_quality`
  (`predicted`, `predicted_dimensions`, and V17's `predicted_detail`: reasons, unknowns,
  assumptions, rows, a gate's expected decision), beside what the run then measures; a run that
  fails keeps its predictions.
- **Suggestions** (`PipelineAdvisor`, `POST /api/v1/pipelines/suggest`, stewards): candidates by
  rule with no model (trim, standardise dates, dedupe on a repeating key, drop orphans with a
  lookup, quarantine rows outside accepted patterns or without a key), each tried out by predicting
  the pipeline with it and kept only when it raises the write's score (or, for orphans, leaves rows
  out). The AI feature `pipeline_suggestions` ranks, explains and adds; it is sent metadata only of
  columns whose source's `ai_egress` allows that model (`EgressPolicy`), never values, never a
  computed column, and the gateway checks the call again against the strictest source shown. Its
  additions are checked like the rest (node types the server has, the settings they take, columns
  it was shown that reach that point, the node's own check and output schema) and dropped
  otherwise.

## 9. Drift

Every scan and every pipeline run produces a profile version. Kilnmark compares it with the
previous one, or with a baseline, and records drift events:

- **Schema**: columns added, removed, renamed (same position and profile) or retyped.
- **Volume**: row count or file count outside its usual range.
- **Distribution**: quantile shift (from KLL sketches), change in null rate, distinct count,
  top values, and the share of each value pattern.
- **Freshness**: no new data within the expected cadence.

Drift needs no extra read: the new profile comes from the scan or the pipeline's single pass and
is compared with stored sketches. Drift lowers the quality and trust scores, opens a steward
task, and can stop a pipeline at its gate.

## 10. Bring your own catalogue

A customer who already has a catalogue does not need Kilnmark to profile everything again.
Kilnmark imports what that catalogue knows and uses it as the baseline, as long as it comes in
a standard format.

- **Formats Kilnmark reads and writes:**
  - OpenLineage dataset facets: schema, data quality metrics (row count, and per column null
    count, distinct count, min, max, sum, quantiles), data quality assertions, column lineage.
  - ODCS 3.1 data contracts: schema, quality rules, service levels, owners.
  - Adapters for the APIs of common open catalogues (OpenMetadata, DataHub) that translate into
    the same model.
- **Kilnmark's own model maps one-to-one onto these formats**, so imported and native metadata
  sit side by side and compare directly. Everything Kilnmark learns can be exported the same way.
- **Imported metadata keeps its provenance:** which catalogue, when it was observed, how it was
  computed. It is a baseline, not a fact. Kilnmark checks it cheaply against the source (schema
  and row count, file size and timestamps) before relying on it, and marks it as stale when they
  disagree.
- **From then on, drift is found incrementally.** The next scan or pipeline run profiles in its
  one pass and compares with the imported baseline. Full profiling happens only where no
  baseline exists or the baseline is shown to be wrong.

## 11. Scores and trust

Scores roll up from column to table, source and data product, with the reasons shown. The trust
score later combines quality with ratings, verified lineage, glossary coverage and
classification. Quality appears in the browse tree and on asset pages, and stewards get an
issues queue.

## Build order

1. Patterns, detectors, coordinated sampling with its controls, pre-analysis measures and
   scores, rule library, thresholds and actions, results, profile versions and drift, the model
   mapped onto OpenLineage and ODCS with export, UI. Round 2, stream E.
2. Import from other catalogues (OpenLineage, ODCS, OpenMetadata and DataHub adapters) as
   baselines, after source management merges.
3. Rule recommendations without AI, then AI suggestions, classification and explanations, after
   the AI runtime merges.
4. Predicted pipeline quality, AI improvement suggestions and the quality gate, with round 3
   pipelines (built).
