# Published JSON schemas, vendored for tests

The data quality export tests (`QualityFlowTest`) validate Kilnmark's exports against these
schemas, offline. They are copied unchanged from their publishers on 24 October 2026.

| File | Source | Licence |
| --- | --- | --- |
| `openlineage/2-0-2/OpenLineage.json` | https://openlineage.io/spec/2-0-2/OpenLineage.json | Apache License 2.0 (OpenLineage project) |
| `openlineage/facets/1-1-1/SchemaDatasetFacet.json` | https://openlineage.io/spec/facets/1-1-1/SchemaDatasetFacet.json | Apache License 2.0 (OpenLineage project) |
| `openlineage/facets/1-0-2/DataQualityMetricsInputDatasetFacet.json` | https://openlineage.io/spec/facets/1-0-2/DataQualityMetricsInputDatasetFacet.json | Apache License 2.0 (OpenLineage project) |
| `openlineage/facets/1-0-1/DataQualityAssertionsDatasetFacet.json` | https://openlineage.io/spec/facets/1-0-1/DataQualityAssertionsDatasetFacet.json | Apache License 2.0 (OpenLineage project) |
| `odcs/odcs-json-schema-v3.1.0.json` | https://github.com/bitol-io/open-data-contract-standard/blob/main/schema/odcs-json-schema-v3.1.0.json | Apache License 2.0 (Bitol, LF AI & Data) |

They are test resources only and are not shipped.
