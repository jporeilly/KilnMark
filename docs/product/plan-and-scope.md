# Kilnmark — Plan & Scope

Oct 22, 2026 · @James O'Reilly

A standalone product, independent of any other vendor's product. First milestone: **v0.1.0 Foundation** — ingest, profile and search metadata from databases, files and object storage on a streaming Java engine, in about 12 weeks.

> **Direction, 24 October:** pipeline-first, not a catalogue ([ADR 0021](../adr/0021-pipeline-first.md)). Phases after v0.1 are re-planned around trusted pipelines; see docs/design/data-quality.md.

## Scope

v0.1 proves the core loop: point the engine at a source, and it ingests metadata, profiles the data in-stream, and makes everything searchable through an API. Discovery, rules and AI build on that loop in later phases.

**Deployment target: on-prem first.** v0.1 ships for a single Linux VM (x86\_64, Ubuntu or RHEL-family) running Docker Compose with two containers, installable without internet access. Splitting roles across VMs follows in v0.2+; Kubernetes and Helm wait until v0.5.

**Product boundary**

- Standalone: no code, APIs, file formats or lab data from other vendors' products; integrations with any catalog come later through open standards only (OpenLineage, JSON-LD, CSV)
- Built clean-room from public concepts; streaming ETL engines and data optimizers are design inspiration, not dependencies

**v0.1 in scope**

- Metadata graph in PostgreSQL: DataSource, Asset, Field, Profile, Run, with URNs and the assertion envelope
- REST API with OpenAPI spec and token auth
- Java 21 engine: node plugin interface, Arrow row sets, YAML pipelines, local runner
- Connectors: PostgreSQL, SQL Server, local files (CSV, Parquet, JSON), S3-compatible object storage
- Incremental ingest using change markers
- In-stream profiling with mergeable sketches
- Keyword search over assets and fields
- A modern web UI (React): search with a command palette, sources and scans, asset pages with profile charts
- One-command install with Docker Compose, online or from an offline bundle

**v0.1 out of scope**

- Classification, PII and trust scores (v0.2)
- Rules, tiering and actions (v0.3)
- AI features and MCP (v0.4)
- Lineage, remote workers, Kubernetes (v0.5)
- Multi-tenant, SSO, fine-grained RBAC

**Success criteria (proposed)**

- [ ] Fresh install to first ingest in under 15 minutes on one VM, including from the offline bundle with no internet
- [ ] Ingests and profiles the reference lab end to end with no manual steps
- [ ] Re-run of an unchanged source skips all assets (incremental works)
- [ ] Profiling a 10 million-row table completes without exhausting a 4 GB heap
- [ ] Full regression suite green before the v0.1.0 package

* [ ] Every UI page passes automated WCAG 2.2 AA checks and a manual keyboard-only pass

## Timeline

&#91;embedded content: v0.1 sprint plan · 6 sprints, 12 weeks\]

Each sprint builds on the last: graph before engine, engine before connectors, connectors before profiling. The Arrow spike in sprint 0 de-risks the engine sprint. Re-baseline after sprint 1 once real pace is known.

## Work breakdown

24 tasks across six sprints; each sprint ends with something runnable.

| # | Task | Sprint | Workstream | Status |
| --- | --- | --- | --- | --- |
| 1 | Mono-repo, licence file, contribution rules, NOTICE and provenance log for borrowed Apache-licensed code | S0 | Platform | Not started |
| 2 | CI: build, tests, one multi-arch image with role flags (api, scheduler, worker) | S0 | Platform | Not started |
| 3 | Docker Compose with two services: PostgreSQL (AGE, pgvector) and the Kilnmark server | S0 | Platform | Not started |
| 4 | Reference lab: sample database, S3-compatible bucket (VersityGW), file share, synthetic data | S0 | Platform | Not started |
| 5 | Decision records: name, licence, framework | S0 | Platform | Not started |
| 6 | Graph schema v0 on PostgreSQL + Apache AGE behind a GraphStore interface; migrations (Flyway) | S1 | Graph and API | Not started |
| 7 | URN scheme and assertion envelope | S1 | Graph and API | Not started |
| 8 | REST CRUD for sources, assets, fields | S1 | Graph and API | Not started |
| 9 | OpenAPI spec and token auth | S1 | Graph and API | Not started |
| 10 | Node plugin interface and plugin loader | S2 | Engine | Not started |
| 11 | Arrow row sets with back-pressure | S2 | Engine | Not started |
| 12 | YAML pipeline format and validator | S2 | Engine | Not started |
| 13 | Local runner, flow connections, run records, PostgreSQL job queue (SKIP LOCKED) | S2 | Engine | Not started |
| 14 | JDBC connector: PostgreSQL, SQL Server (database dialects adapted from Apache-licensed code) | S3 | Connectors | Not started |
| 15 | File connector: CSV, Parquet, JSON | S3 | Connectors | Not started |
| 16 | S3-compatible object storage connector | S3 | Connectors | Not started |
| 17 | Change markers for incremental ingest | S3 | Connectors | Not started |
| 18 | Profiling node with Apache DataSketches: stats, top-N, inferred formats | S4 | Profiling | Not started |
| 19 | HyperLogLog and quantile sketches, profile merge | S4 | Profiling | Not started |
| 20 | Profile versions and basic drift diff | S4 | Profiling | Not started |
| 21 | PostgreSQL full-text and trigram search API behind a SearchIndex interface | S5 | Search and UI | Not started |
| 22 | Modern web UI v1: command palette search, sources and scans, asset page with profile charts, light and dark themes | S5 | Search and UI | Not started |
| 23 | Regression suite on the reference lab, including automated WCAG 2.2 AA checks (axe-core) on every page | S5 | Platform | Not started |
| 24 | Offline install bundle, backup and restore scripts, package and release v0.1.0 | S5 | Platform | Not started |

## Beyond v0.1

Later phases follow the architecture spec's roadmap; each gets its own detailed plan once the previous phase ships.

| Phase | Delivers | Exit gate |
| --- | --- | --- |
| v0.2 Discover and classify | Classification tiers 1 to 3, PII, keys, duplicates, drift, relationship discovery, GraphQL API, trust score, glossary, steward workflow, OIDC sign-in | Discovery results reproducible on the reference lab |
| v0.3 Act | Rules engine, dry run and what-if, temperature terms, tier/purge/rehydrate, pipelines as actions, an importer for existing jobs, read-only pipeline canvas | Dry run matches the real run |
| v0.4 AI | Auto-descriptions, LLM tier, natural-language search, MCP server, steward agent | No AI output lands without approval |
| v0.5 Lineage and scale | OpenLineage in and out, SQL parsing, propagation, Remote workers, optional Kafka connector, Helm | Full regression green, then v1.0 |

Integration with other catalogs arrives only through open standards in v0.5 or later.

## Engineering setup

One mono-repo, one build, one Compose file: the whole product runs from a clean clone with a single command.

**Repository layout**

```
kilnmark/
  kiln-core/          node SPI, Arrow row sets, runner, flow connections
  kiln-plugins/       connectors and profiling nodes
  graph/              schema, migrations, URNs, assertion envelope
  api/                REST service (Quarkus)
  ui/                 React + TypeScript
  sidecar-python/     reserved for AI work from v0.4
  lab/                reference lab: Compose, seed data, synthetic PII
  pipelines/          built-in YAML pipelines (ingest, profile)
  tests/regression/   end-to-end suite against the lab
  docs/adr/           decision records
  docker-compose.yml
```

**Practices**

- Gradle multi-module build; Java 21; Testcontainers for integration tests
- CI on every push: build, unit tests, integration tests, container images
- Versioning: 0.0.1 per release; a phase bump (0.1 to 0.2) only at an exit gate
- Full regression on the reference lab before every package, no exceptions
- Decision records for anything hard to reverse (schema, plugin interface, licence)

**Reference lab** — public and synthetic data only: a sample relational database, an S3-compatible bucket (VersityGW) of CSV, Parquet and JSON files, a file share, and a generated dataset seeded with fake PII for later discovery tests.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Ownership challenged by employer IP clause | Product cannot be commercialised | Contract checked and clear; no code until after the retirement date; personal hardware and accounts; clean-room log |
| Scope creep into catalog features before the loop works | v0.1 slips indefinitely | Hold the out-of-scope list; new ideas go to a v0.2+ backlog |
| Plugin interface designed wrong | Every connector rewritten later | Build three different connectors in S3 before freezing the interface |
| Arrow learning curve | Engine sprint overruns | Spike Arrow row sets in S0; fall back to row objects behind the same interface |
| Part-time capacity | Estimates slip | Re-baseline after S1 using actual velocity |
| Profiling memory at scale | Crashes on large tables | Sketch-based stats only; heap-bounded test in the regression suite |

## Decisions needed before sprint 0

- [ ] Decided: contract checked and clear; sprint 0 starts after the retirement date next month
- [ ] Product name: Kilnmark, engine Kiln (decided); confirm trademark and domains
- [ ] Licence model: proprietary, open core, or source-available
- [ ] API framework: Quarkus (decided), one image with api, scheduler and worker roles
- [ ] Where the repo lives: private GitHub org under your own account
- [ ] Weekly capacity, so sprint estimates can be calibrated

* [ ] Decided: clean Kiln engine borrowing Apache-licensed code where useful, with a provenance log ([ADR 0006](../adr/0006-clean-engine-borrowed-code.md)); an importer for existing jobs in v0.3

- [ ] Do on-prem customers need a non-Docker install (Java plus their own PostgreSQL, run as a systemd service)? If so, will their DBAs allow the AGE and pgvector extensions? Ask prospects; decide before v0.2
