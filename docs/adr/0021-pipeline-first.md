# 0021 Pipeline-first: a data integration engine, not a catalogue

Status: Accepted, 24 October 2026

## Decision

Kilnmark is a modern data integration engine in the streaming-ETL tradition that knows the quality of
the data it moves. It is not a data catalogue.

- **Kilnmark keeps the metadata pipelines need:** sources, containers, assets, fields,
  profiles, patterns, samples, relationships, quality rules and results, drift, contracts,
  documents and their text, and the lineage of its own runs.
- **It does not build catalogue features:** glossary curation, ratings, collections and data
  products, social trust scores, governance dashboards, a GraphQL API, or import adapters for
  other catalogues.
- **It works alongside a customer's catalogue through open standards:** it sends lineage and
  quality results out as OpenLineage, and accepts ODCS 3.1 data contracts in for its pipelines to
  enforce.

## Why

Catalogues are a crowded market, with mature free options (OpenMetadata, DataHub) and
established enterprise products. Matching them is years of work that would not set Kilnmark
apart.

What no catalogue does is check quality inside the engine that moves the data:

- profile each source once and reuse that metadata;
- predict a pipeline's output quality before it runs;
- gate or quarantine in the single pass;
- catch drift from profiles;
- let AI draft and repair pipelines from that metadata.

That loop is Kilnmark's reason to exist. See docs/design/data-quality.md.

## Cost

- Customers who want a catalogue need another product. We make Kilnmark easy to connect to one.
- The roadmap moves from discovery and classification (old v0.2) to trusted pipelines. Earlier
  documents that describe Kilnmark as a catalogue are superseded where they conflict with this
  record.
- The ETL market is also crowded. The proof must be the quality loop, measured with design
  partners: fewer bad loads, less re-reading of data, and shorter build and repair times.
