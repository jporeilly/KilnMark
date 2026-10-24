# 0020 Data quality in open formats: OpenLineage facets and ODCS 3.1

Status: Accepted (24 October 2026)

## Decision

Kilnmark's data quality model (profiles, value patterns, rules, results, drift, scores) maps
one-to-one onto two published formats, and Kilnmark exports every asset in both:

| Kilnmark | OpenLineage (spec 2-0-2) | ODCS 3.1.0 |
| --- | --- | --- |
| Asset and its fields | input dataset with the `schema` facet (SchemaDatasetFacet 1-1-1) | `schema[0]` object with `properties` |
| Row count; per column nulls, distinct, values, sum, min, max, quantiles every 5% | `dataQualityMetrics` input facet (1-0-2): `rowCount`, `columnMetrics` | `customProperties` (`rowCount`) |
| Rule results | `dataQualityAssertions` input facet (1-0-1): assertion, success, column | `customProperties.lastResult` on each quality item |
| Rules | (assertions only) | `quality` items: library metrics where ODCS has one (`nullValues`, `missingValues`, `duplicateValues`, `invalidValues` with `validValues` or `pattern`, `rowCount`), `sql` for custom SQL counted in rows, and `type: custom, engine: kilnmark` for the rest |
| Thresholds (share or count; warn and fail) | | `unit` (`percent` or `rows`) and `mustBeLessOrEqualTo`; warn level and unit in `customProperties` |
| Dimensions | | `dimension`: validity is exported as `conformity`, volume as `coverage`, referential integrity as `consistency`; ours is kept in `customProperties.kilnmarkDimension` |
| Columns marked sensitive | | `classification: sensitive` |
| Provenance: origin catalogue, observed at, method | `kilnmark_provenance` input facet | `customProperties`: `originCatalogue`, `observedAt`, `profileMethod` |

Endpoints: `GET /api/v1/quality/export/openlineage?urn=` returns a `COMPLETE` run event with the
asset as its input; `GET /api/v1/quality/export/odcs?urn=` returns a data contract as JSON (ODCS
documents are usually YAML; JSON is the same model and is what the published schema validates).

Profile versions, rules and results carry provenance in the database (`origin`, `origin_ref`,
`observed_at`, `method`; profile versions from another catalogue keep their column statistics in
`profile_version.columns`), so importing baselines from other catalogues, the next build step,
needs no change to the tables.

## Why

- **The product owner's condition:** a customer's existing catalogue can be plugged in "as long
  as it uses a standard format". OpenLineage is what lineage and quality tools already emit
  (Marquez, dbt, Great Expectations, Soda, Airflow); ODCS is the Linux Foundation's data contract
  standard and has a quality section. Between them they cover profiles, checks and contracts.
- **One-to-one, losslessly.** Where a format has no field for something (a dimension ODCS lacks,
  a warning threshold, a result), it goes in that format's own extension point (custom facets,
  `customProperties`), so an export read back loses nothing.
- **Validated against the published schemas** in the tests (`QualityFlowTest`), with the schemas
  vendored under `server/src/test/resources/schemas` (Apache-2.0, see the README there).

## What it costs

- One test-only library, `com.networknt:json-schema-validator` 1.5.9 (Apache-2.0, about 0.6 MB
  with its `com.ethlo.time:itu` dependency), for draft 2019-09 and 2020-12 schemas. Nothing is
  added to what the server ships.
- Our custom facet's `_schemaURL` (`urn:kilnmark:facet:provenance:1`) names a schema we have not
  published yet.
- OpenLineage dataset names follow its naming conventions for PostgreSQL
  (`postgres://host:port`, `database.schema.table`) and S3; other sources use
  `kilnmark://<source>` until their conventions are added.
- ODCS's dimension list is smaller than ours, so three of our dimensions export under a
  neighbouring ODCS dimension (with ours alongside).
