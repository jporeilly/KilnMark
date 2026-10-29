# Kilnmark — Architecture & Build Spec

Oct 22, 2026 · @James O'Reilly

## Vision and scope

Build one engine that both **knows** your data and **acts** on it: a streaming, plugin-based execution model fused with rules-driven, metadata-triggered actions, with AI woven through discovery, enrichment and stewardship.

**Name:** the product is **Kilnmark** and its execution engine is **Kiln**. A kiln is the heat that does the work; a hallmark is the assay office's stamp certifying metal is genuine, which is what the trust score does for data.

**Purpose: Trusted Data Operations.** The engine exists to answer three questions about any asset, continuously: *what is it* (ingest and catalog), *what is in it* (profile and discover), and *can I trust and use it* (trust score, policy, lineage). Catalog features and data discovery are one product, not two: every ingest automatically profiles, discovers and scores, and every score can trigger an operation.

Today these live in separate products. ETL tools move and transform data but keep little memory of what they touched. Catalogs and data optimizers hold rich metadata and rules, but execute a fixed set of actions (tag, move, purge, rehydrate). The engine closes that gap: every scan, profile and pipeline run writes to one metadata graph, and every rule can fire any pipeline as its action.

**In scope for v1**

- Harvest and profile relational databases, files and object storage
- A single metadata graph with lineage, glossary, classifications and temperature
- A rules engine whose actions are pipelines, not just built-in verbs
- AI enrichment (descriptions, classification, glossary suggestions) with human approval
- REST API and an MCP server so agents can read and act on the graph

**Out of scope for v1**

- A visual pipeline designer (author pipelines as YAML/JSON first; a canvas later)
- BI, reporting and semantic-layer features
- FUSE/HDFS block-level tiering (file and object tiering only)

## Market landscape

The 2026 market has converged on one idea: the catalog is now a context layer that feeds both people and AI agents at runtime. No product combines that with a general-purpose execution engine, which is the gap this engine targets.

| Product | Type | Best feature to borrow | Source |
| --- | --- | --- | --- |
| DataHub | Open source (Java) | MCP server for agents, natural-language metadata queries, GenAI classification with propagation | [modern-datatools](https://www.modern-datatools.com/tools/DataHub) |
| OpenMetadata / Collate | Open source | Simple 4-component deployment; auto column lineage from SQL, dbt, Airflow, Spark; semantic context graph for agents | [modern-datatools](https://www.modern-datatools.com/tools/openmetadata), [dupple](https://dupple.com/learn/best-ai-data-catalog-tools) |
| Atlan | Commercial SaaS | Active metadata: bi-directional sync pushes context back into tools; Context Agents writing descriptions at scale | [checkthat.ai](https://checkthat.ai/answers/what-are-the-best-data-catalog-tools), [atlan](https://atlan.com/collibra-alternatives) |
| Alation | Commercial | Behavioural signals (who queries what) plus agents that automate stewardship and documentation | [Gartner Peer Insights](https://www.gartner.com/reviews/product/alation-agentic-data-intelligence-platform) |
| Collibra | Commercial | Configurable knowledge graph and structured stewardship workflows for regulated industries | [checkthat.ai](https://checkthat.ai/answers/what-are-the-best-data-catalog-tools) |
| Informatica CDGC (CLAIRE) | Commercial | AI classification and descriptions from metadata alone; AI-inferred lineage without parsing ETL code | [Informatica](https://success.informatica.com/content/dam/informatica-cxp/techtuesdays-slides-pdf/AI%20for%20the%20Catalog,%20Catalog%20for%20the%20AI.pdf) |
| Unity Catalog | Open source (Apache 2.0) | One namespace for tables, files and ML models, with governance where compute runs | [Atlan](https://atlan.com/know/purview-vs-databricks-unity-catalog/), [Microsoft Learn](https://learn.microsoft.com/en-us/training/modules/explore-azure-databricks/05-data-governance-using-unity-catalog-and-microsoft-purview) |
| Microsoft Purview | Commercial | Estate-wide Data Map that scans other catalogs (e.g. Unity) rather than duplicating them | [Microsoft Learn](https://learn.microsoft.com/en-us/training/modules/explore-azure-databricks/05-data-governance-using-unity-catalog-and-microsoft-purview) |
| Komprise | Commercial | Global metadatabase across silos; Python plugins for custom metadata extraction; tiering with no rehydration penalty; what-if cost projection | [Komprise](https://www.komprise.com/resource/know-first/), [IBM](https://www.ibm.com/products/komprise) |
| Commercial data optimizers | Commercial | Rule definitions compiled to queries over scanned metadata; glossary terms as data temperature driving tier, purge and rehydrate | Vendor documentation |

**What the engine takes from the field:** an MCP server and natural-language querying (DataHub), a lean deployment footprint (OpenMetadata), active bi-directional metadata (Atlan), usage signals as first-class metadata (Alation), AI that works from metadata before touching data (CLAIRE), pluggable extractors in Python (Komprise), what-if cost simulation before a rule runs (Komprise), and term-driven rules (data optimizers).

## Core capabilities

Seven capabilities, each tagged with where it comes from: streaming ETL engines (E), data optimizers and catalogs (DO), or the wider market (M).

| Capability | What it does | Heritage |
| --- | --- | --- |
| Harvest | Connectors scan schemas, files and object stores into the graph; incremental by change markers | K (nodes as plugins), M |
| Profile | Column stats, patterns, cardinality, nulls, sample values, computed in-stream during harvest | K (row streaming) |
| Classify | Dictionaries, regex patterns, rules and LLM classifiers with a confidence score per hit | DO, M (CLAIRE, DataHub) |
| Govern | Glossary, ownership, stewardship tasks, certification, policies | DO, M (Collibra) |
| Act | Rules match assets and fire actions: tag, tier, purge, rehydrate, or run any pipeline | DO + K |
| Trace | Lineage from engine runs, OpenLineage events and parsed SQL, down to column level | K, M (OpenMetadata) |
| Converse | Natural-language search, AI descriptions, agent access via MCP | M (DataHub, Atlan) |

The differentiator is **Act**: in other catalogs, actions are a short fixed list. Here an action is a pipeline, so anything an ETL pipeline can do (move, mask, copy, load, call an API, rebuild a mart) becomes a governed, metadata-triggered operation that writes its own lineage back.

## Architecture

&#91;embedded content: engine architecture · 5 layers\]

Consumers reach everything through one API; the four services submit work to a single streaming engine, which reads and moves data at the sources and writes every result back to the metadata graph. Services also read the graph directly. Everything ships as Docker Compose on one VM for v1, with remote workers deployed next to the data when needed.

## Deployment: one image, many roles

Kilnmark is a modular monolith built into **one container image** that starts in one or more roles (`--role=api,scheduler,worker`). Small on-prem installs run every role in one container; Kubernetes runs each role as its own scaled deployment. Same code, same tests, same image, so there is never a distributed system to debug that only exists in production.

**Order of delivery:** on-prem first. v0.1 targets the Small topology on a single Linux VM, including offline install; Medium (roles split across VMs) follows; Large (Kubernetes) arrives with v0.5. The role split is built in from day one, so moving up a size is configuration, not a rewrite.

&#91;embedded content: deployment topologies · small, medium, large\]

**Why this is the efficient choice**

- **No broker, no service mesh.** Workers pull jobs from a PostgreSQL queue (`SELECT … FOR UPDATE SKIP LOCKED`); events use the outbox. The only network calls are client to API, worker to PostgreSQL, and API to the optional AI sidecar over gRPC
- **Stateless roles.** All state lives in PostgreSQL, so any pod can die and be replaced; workers scale on queue depth
- **Remote workers dial out.** A worker deployed next to on-prem data connects outbound to the API, so no inbound firewall rules are needed
- **Air-gapped ready.** One offline bundle (images plus Helm chart plus Compose file), no calls home, licence file checked locally

**Runtime choices**

| Concern | Choice | Why |
| --- | --- | --- |
| Framework | Quarkus | Lower memory and faster start than Spring Boot; Kubernetes-native config and health checks; optional native image |
| API role | JVM now; GraalVM native image as a later option | Near-instant start and small footprint for scale-to-zero |
| Worker role | JVM with class-data sharing | JIT gives the best long-running throughput for Arrow and profiling |
| Images | Minimal base with a jlink-trimmed JRE; amd64 and arm64 | Small images, small attack surface |
| PostgreSQL | Single instance on-prem; CloudNativePG operator for HA on Kubernetes, with its built-in connection pooler | Many workers without exhausting connections |
| Observability | OpenTelemetry metrics, traces and logs, exported to whatever the customer runs | Nothing bundled |
| Config | One config schema; Compose and Helm generated from it | Same settings everywhere |

A new role or service needs a decision record, under the dependency policy below.

## Metadata model

Store metadata as a typed property graph with an append-only change log: every fact carries who or what asserted it, when, and with what confidence.

**Core entities**

| Entity | Key attributes | Notes |
| --- | --- | --- |
| DataSource | type, connection ref, agent affinity, cost per TB, capacity, writable | Cost and capacity fields enable tiering economics (DO pattern) |
| Asset | URN, kind (table, view, file, object, folder, topic), path, size, owner, created, modified, last accessed | URN scheme: `urn:kilnmark:<source>:<path>` |
| Field | name, type, nullable, position, profile stats | Child of a tabular Asset |
| Term | domain, name, definition, synonyms, status | Temperature is just a domain (Boiling…Frozen) |
| Classification | method, label, confidence, evidence | Method = dictionary, pattern, rule or model |
| Pipeline / Run | definition, version, start, end, rows in/out, status | Every run is a lineage event |
| Rule / RuleRun | criteria, actions, scope, schedule, dry-run result | See Rules engine |
| Usage | asset, actor, query, timestamp | Feeds popularity, temperature and AI ranking (Alation pattern) |

**Edges:** `contains`, `hasField`, `taggedWith`, `classifiedAs`, `ownedBy`, `readBy` / `writtenBy` (Run → Asset), `derivedFrom` (Field → Field for column lineage), `governedBy` (Asset → Rule).

**Assertion envelope** on every attribute and edge: `source` (scanner, user, rule, ai), `confidence` (0 to 1), `status` (proposed, approved, rejected), `assertedAt`. AI output always lands as `proposed` until a steward or an approval rule accepts it.

**Storage: a real graph from day one, in one database.** PostgreSQL with the [Apache AGE](https://pigsty.io/ext/e/age/) extension stores assets, fields, terms and lineage as vertices and edges queried with openCypher, Neo4j's query language, alongside plain SQL and JSONB in the same transaction. pgvector holds embeddings (HNSW indexes) and PostgreSQL full-text search with trigram matching handles keyword search, so one SQL query can combine keyword, vector, filter and graph conditions. One database keeps v1 deployable on a single VM.

**Graph store behind an interface.** All graph access goes through a `GraphStore` interface, so a Neo4j adapter can be added later for very large estates or customers who already run it. Neo4j Community is GPLv3 and Enterprise is AGPLv3 or commercial ([Neo4j repo](https://github.com/F-22/neo4j)), so it stays an optional adapter rather than something Kilnmark bundles.

## Execution engine and connector framework

Keep the proven model of streaming ETL engines, rebuilt clean: a pipeline is a graph of nodes, each node runs on its own worker, and rows stream between nodes through bounded buffers. Harvesting, profiling and actions all run on this one engine.

**What to keep from streaming ETL engines**

- **Nodes as plugins.** One interface (`init`, `processRow`, `dispose`) for every source, transform and sink, discovered at start-up from a plugins folder
- **Row sets with back-pressure.** Bounded queues between nodes; a slow sink throttles the source instead of exhausting memory
- **Node copies.** Run N copies of a CPU- or latency-bound node in parallel, for lookups and scripts
- **Flows over pipelines.** A flow orchestrates pipelines with success/failure connections; the rules engine submits flows
- **Metadata injection.** Build pipelines at runtime from graph metadata, so one template can harvest or move a thousand assets
- **Remote workers.** A remote worker agent that runs near the data and reports back, giving an agent-affinity model

**What to change**

- **Pipelines as code.** YAML/JSON definitions in Git, versioned and diffable, instead of XML files
- **Metadata side-channel.** Every node emits schema, row counts and field-level lineage as it runs, so the engine writes the graph without extra scanning
- **Polyglot plugins.** Java/Kotlin for core nodes; a Python SDK for extractors and AI nodes (Komprise KAPPA pattern)
- **Incremental by default.** Connectors track change markers (modified time, ETag, CDC offset, schema hash) and only re-harvest what moved

**Scan lifecycle:** discover → harvest schema → sample and profile → classify → emit lineage → evaluate rules. Each stage is a node, so a scan is itself just a pipeline you can inspect and extend.

## Profiling and classification

Classification runs as a cascade from cheapest to most expensive, and stops as soon as confidence clears the threshold. Most columns never reach the LLM.

1. **Metadata only** — column and table names, types, comments matched against dictionaries and embeddings (CLAIRE pattern: no data read)
2. **Pattern and dictionary** — regex and value lists over a sample, as catalogs' data identification methods do
3. **Statistical** — cardinality, length distribution, format entropy, checksum validity (Luhn, IBAN, NHS number)
4. **Small model** — a local embedding or classifier model on Ollama for ambiguous columns
5. **LLM** — only for what is still unresolved, given profile stats and a masked sample, never raw rows

**Profile stats per field:** row count, null %, distinct count, min/max, mean and stddev, top-N values, length histogram, inferred format, and a HyperLogLog sketch so profiles merge across incremental runs.

**Confidence and evidence:** every hit stores which tier fired, the score, and the evidence (matched pattern, sample hit rate, model rationale). Stewards approve or reject; rejections feed back as negative examples.

**Reconcile modes:** rule criteria flag columns as UNKNOWN (no classification), UNLINKED (classified but no term), DRIFT (profile no longer matches its classification) and MISSING (expected asset gone). Method definitions for tiers 1 and 2 use an open JSON format of the engine's own.

## Data discovery and trust

Discovery goes beyond labelling columns: it finds what is sensitive, how assets relate, what is redundant, and whether an asset is fit to use. The output of discovery is a trust score per asset that operations can rely on.

**Discovery capabilities**

| Discovery | What it finds | How |
| --- | --- | --- |
| Sensitive data | PII, PCI, PHI, secrets across tables, files and documents | Classification cascade; document text extraction for PDFs and Office files |
| Keys and relationships | Primary keys, foreign keys and join paths, even undeclared | Uniqueness from profiles, value overlap via DataSketches theta-sketch intersections, name similarity |
| Duplicates and ROT | Redundant, obsolete and trivial copies; near-duplicate files and tables | Content hashes, schema fingerprints, last-access and owner checks |
| Similar assets | "Other tables like this one" across sources | Embeddings of schema plus profile; feeds glossary suggestions |
| Drift | Schema change, distribution shift, volume anomalies between scans | Compare profile versions and sketches |
| Usage | Who reads what, how often, which queries | Query logs and access logs, as catalogs' usage observation does for MSSQL, Snowflake and Oracle |

**Trust score** (0 to 100, recomputed on every scan and event), extending the common catalog model of quality, ratings, verified lineage and glossary coverage:

| Factor | Signal |
| --- | --- |
| Quality | Completeness, validity, uniqueness, consistency from profiles and rules |
| Governance | Owner assigned, glossary terms linked, sensitivity set, policies attached |
| Lineage | Lineage present and verified by a steward |
| Freshness | Updated within its expected cadence |
| Usage and endorsement | Active users, ratings, certifications |

Weights are configurable per domain, and every score shows its breakdown so a low score explains itself.

**Relationship discovery on the graph.** Discovered keys, joins, duplicates and lineage are stored as edges, so relationship questions become graph queries rather than application code:

```cypher
// How can customers be joined to transactions, within 3 hops?
MATCH p = shortestPath((a:Asset {name:'customers'})-[:JOINS_ON*..3]-(b:Asset {name:'transactions'}))
RETURN p

// Impact analysis: everything downstream of a PII column
MATCH (f:Field {urn:$urn})-[:DERIVED_FROM*1..]->(d:Field)<-[:HAS_FIELD]-(a:Asset)
RETURN DISTINCT a.name, d.name
```

The same questions reach users and agents through GraphQL:

```graphql
query {
  asset(urn: "urn:kilnmark:pg:crm.customers") {
    trustScore
    fields { name classifications { label confidence } }
    joins(maxHops: 3) { target { name } via { field confidence } }
    downstream(depth: 5) { name owner }
  }
}
```

**From trust to operations:** trust and sensitivity are rule criteria like any other attribute. Examples: block publishing a data product below a threshold (a marketplace-ready check), quarantine a file when PII appears in an unclassified share, or open a steward task when trust drops after a drift event.

## Rules and actions engine

A rule is criteria plus actions, compiled to a query over the graph and executed as a job. This keeps the data optimizers' two-stage pattern (define once, apply to any source) and widens the action list to any pipeline.

**Rule definition** (source-agnostic, versioned in Git):

```yaml
rule: tier-cold-files
criteria:
  kind: file
  all:
    - lastAccessed < now - 365d
    - not taggedWith: Legal Hold
actions:
  - applyTerm: Data Temperature/Cold
  - removeTerms: [Data Temperature/Hot, Data Temperature/Warm]
  - run: pipelines/move-to-archive.yaml
    params: { target: s3-glacier, leaveStub: true }
mode: dry-run
```

**Rule binding** attaches a definition to sources with a schedule or an event trigger (new asset, profile change, classification approved).

**Built-in actions:** apply/remove term or tag, set owner, open steward task, notify, move, copy, purge, rehydrate, quarantine. **Pipeline actions:** any engine pipeline, with the matched assets injected as rows (metadata injection).

**Safeguards borrowed from the market**

- **Dry run and what-if** — show matched assets, total size and projected cost saving per tier before anything moves (Komprise)
- **Two-person approval** for destructive actions (purge, quarantine)
- **Stub or link on move** — leave a stub or a resolvable link so access survives tiering; rehydrate on demand
- **Full audit** — every RuleRun records matches, actions, outcomes and the lineage it produced

**Temperature as a computed term:** a scheduled rule scores each asset from last access, access frequency (Usage), size and cost per TB, then applies Boiling to Frozen. Tiering rules key off the term, as data optimizers' tiering workflows do.

## AI layer

AI runs in two directions, as Informatica frames it: AI builds the catalog, and the catalog feeds AI. Every AI output is a proposed assertion with evidence, never a silent write.

**AI builds the catalog**

| Feature | How it works | Market precedent |
| --- | --- | --- |
| Auto-descriptions | LLM drafts table and column descriptions from names, profiles, lineage and glossary; steward approves | Atlan Context Agents, CLAIRE |
| Tiered classification | Cascade in Profiling and classification; LLM only for leftovers | CLAIRE, DataHub |
| Glossary suggestions | Clusters similar fields across sources, proposes terms and synonyms, links to existing terms | Your Glossary Generator, CLAIRE |
| Propagation | Approved tags and terms flow along column lineage to downstream fields | DataHub |
| Lineage inference | Infers links where no run or SQL exists, from name, type and value-overlap similarity; flagged as inferred | CLAIRE |
| Rule authoring | Plain English to rule YAML ("archive untouched PDFs older than a year"), shown as dry run first | Atlan, Alation agents |
| Anomaly detection | Flags profile drift, schema change and volume spikes between runs | DataHub observability |

**The catalog feeds AI**

- **Natural-language search** — hybrid keyword (PostgreSQL full-text) and vector (pgvector) search, re-ranked by usage and certification
- **Ask the catalog** — chat that answers "where is customer churn data and can I use it?" with cited assets, owners and policies
- **MCP server** — tools such as `search_assets`, `get_lineage`, `propose_term`, `dry_run_rule`, `run_pipeline`, so Claude or any agent can read the graph and act within policy
- **Context packs** — export a governed slice of the graph (definitions, joins, certified metrics) for agents to ground on, like Atlan Context Studio

**Agentic stewardship:** a steward agent works the queue overnight: drafts descriptions, proposes classifications, reconciles drift, and leaves a morning digest. Humans approve in batches.

**Model strategy:** pluggable provider interface. Local Ollama for classification and embeddings (data never leaves the network), with Claude or another hosted model optional for descriptions and chat. Prompts versioned in Git; outputs cached by input hash to control cost.

## Lineage

Lineage comes from three sources, ranked by trust: engine runs (exact), OpenLineage events from other tools (reported), and parsed SQL or AI inference (derived).

- **Engine runs** — each node knows its input and output fields, so column-level lineage is emitted as a by-product of execution, not reverse-engineered
- **OpenLineage in and out** — accept events from Airflow, Spark, dbt and other ETL tools' OpenLineage plugins; emit the engine's own runs as OpenLineage so Marquez or any consumer can display them
- **SQL parsing** — parse view definitions and query logs (Apache Calcite) for column lineage across warehouses
- **Inferred** — AI-proposed links, shown dashed and never used for propagation until approved

The UI shows table and column graphs with impact analysis ("what breaks if I drop this column?") and rule history on each node ("tiered to Cold on 3 March by rule tier-cold-files").

## APIs and integration

API-first: the UI, CLI and agents all use the same public API, so nothing is UI-only.

| Interface | Purpose |
| --- | --- |
| REST (OpenAPI 3) | CRUD on assets, terms, rules, pipelines; run and dry-run endpoints |
| GraphQL | Relationship queries in one request: asset to fields, classifications, terms, owners, joins and lineage, to any depth; resolvers run Cypher on the graph |
| Event stream | Change events (asset created, profile changed, rule fired) on a message bus, so other tools can react: active metadata, Atlan-style |
| Webhooks | Push events to Slack, Teams, ticketing |
| MCP server | Agent access to search, lineage, proposals and governed actions |
| OpenLineage | Lineage in and out |
| Import/export | JSON-LD graph export; open CSV/JSON formats for glossaries, terms and classification methods, so content moves in and out of any catalog |
| CLI | `kiln scan`, `kiln rule dry-run`, `kiln run pipeline.yaml` for CI and scripting |

## Security and governance

Governance is enforced in the engine, not just recorded: a rule or agent can only act within the caller's permissions.

- **Identity** — built-in accounts, or OIDC sign-in with any provider (Keycloak, Entra ID); service accounts for agents and workers
- **RBAC plus ABAC** — roles (Admin, Steward, Analyst, Agent) combined with attribute policies such as "PII-classified assets: masked sample only"
- **Secrets** — connection credentials encrypted at rest; optional Vault or cloud secret manager lookup
- **Data minimisation** — profiles store statistics, not rows; samples are masked before reaching any LLM
- **Stewardship workflow** — proposed → reviewed → approved, with batch approval and delegation by domain
- **Audit** — append-only log of every assertion, rule run, action and agent tool call; exportable for compliance

## User interface

A modern, fast web app, not a desktop designer: everything runs in the browser, served by the Kilnmark server itself, so on-prem installs need no Node.js and no client install.

**Principles**

- **Search first.** A command palette (Ctrl+K) reaches any asset, term, rule or action; natural-language questions go to the same box
- **Every page answers one question:** what is it, can I trust it, what touches it, what should I do
- **Explain everything.** Trust scores, classifications and AI proposals always show their evidence
- **Review in batches.** Stewards approve or reject many proposals at once, with keyboard shortcuts
- **Light and dark themes, WCAG 2.2 AA, usable at laptop and tablet widths**

**Design practices the UI follows**

| Practice | How it shows up in Kilnmark |
| --- | --- |
| AI is labelled and accountable | Every AI output carries an "AI draft" or "AI suggestion" badge, its evidence and confidence, and which model ran (local or hosted); nothing applies without approval |
| Undo over confirm | Reversible actions apply at once with an Undo toast; confirmation dialogs only for destructive or irreversible actions (purge, quarantine) |
| Keyboard first | Command palette everywhere; list shortcuts (J/K move, X select, A approve, R reject) shown on screen |
| Progressive disclosure | Summary first (trust score, top joins), detail one click away (score breakdown, evidence, Cypher behind a view) |
| Data in context | Live previews on pipeline connections; profiles drawn inline in field tables; impact shown before any change |
| Honest states | Skeleton loading, useful empty states that say what to do next, errors that name the cause and a fix |
| Design tokens | Colour, type, spacing and radius as tokens, driving light and dark themes and an optional compact density |
| Accessible by construction | Real buttons, links and inputs; visible focus; colour never the only signal (line style and text labels too); 44 px touch targets |
| Fast | Interactions respond within 100 ms; long lists and graphs virtualised; no page blocks on a scan |
| Ready for other languages | All copy in message files from day one; dates and numbers formatted per locale |

**Mockups:** see the Kilnmark UI Mockups canvas (asset page, steward inbox, relationship explorer, pipeline designer).

### Accessibility compliance

**Target: WCAG 2.2 Level AA** across the whole product, including the canvases. It is the strictest of the rules buyers will cite and includes everything in WCAG 2.1 and 2.0 AA, so one target satisfies all markets below.

| Market | Rule | What it asks | Why it matters to Kilnmark |
| --- | --- | --- | --- |
| UK public sector | Public Sector Bodies Accessibility Regulations 2018 | The latest WCAG, now 2.2 AA ([DfE](https://accessibility.education.gov.uk/standards/accessibility-regulations)) | Public-sector buyers will require it of tools they deploy |
| UK, everyone | Equality Act 2010 | Reasonable adjustments; no discrimination against disabled staff | Applies to employers using Kilnmark internally |
| EU | European Accessibility Act (from 28 June 2025) and EN 301 549 | Consumer-facing products and services; business-only software is largely outside scope, but several countries (France, Italy, Spain) cover public-sector or large-company internal systems, and public buyers apply EN 301 549 ([Karl Groves](https://karlgroves.com/the-european-accessibility-act-and-b2b-software-what-internal-platforms-must-comply-with/)) | EU public-sector and large-enterprise deals |
| US federal | Section 508 | WCAG 2.0 AA via the revised 508 standards; vendors provide an accessibility conformance report | Any US government sale |
| US state and local | ADA Title II rule | WCAG 2.1 AA; deadlines moved by interim final rule to 26 April 2027 (large entities) and 26 April 2028 (smaller) ([WebAbility](https://www.webability.io/blog/ada-title-ii-deadline-what-we-know)) | Services vendors deliver for these bodies are expected to meet it |

**WCAG 2.2 criteria that bite for this UI**

- **Dragging movements (2.5.7):** every drag on the pipeline canvas and graph has a non-drag alternative (Add node, keyboard move, context menu)
- **Target size (2.5.8):** at least 24 by 24 px; Kilnmark uses 40 to 44 px for main controls
- **Focus not obscured (2.4.11):** sticky headers, toasts and side panels never cover the focused element
- **Accessible authentication (3.3.8):** sign-in allows password managers and paste; no puzzles
- **Consistent help (3.2.6) and redundant entry (3.3.7):** help in the same place on every page; never ask for the same information twice in a flow
- **Complex visuals:** graphs, lineage and pipelines always have a text or table view; charts have text summaries

**How it is assured**

- Design: contrast checked against tokens (text 4.5:1, UI and graphics 3:1), focus styles, semantics and text alternatives in every mockup
- Build: automated axe-core checks in CI on every page; a failed check blocks the release, like any regression
- Release: manual keyboard and screen-reader passes (NVDA, JAWS, VoiceOver) on each major release, plus testing with disabled users before v1.0
- Sales: a public accessibility statement and an Accessibility Conformance Report (VPAT 2.5, international edition covering WCAG, EN 301 549 and Section 508), kept current each release

This is a summary for planning, not legal advice.

**Key screens**

| Screen | Purpose | Release |
| --- | --- | --- |
| Sources and scans | Connect sources, run and schedule scans, see progress live | v0.1 |
| Search and browse | Faceted search across assets, fields and terms | v0.1 |
| Asset page | Schema, profile charts, classifications, owner, trust breakdown, usage | v0.1 (profile), v0.2 (trust) |
| Discovery dashboard | Sensitive data map, trust distribution, drift and duplicates | v0.2 |
| Steward inbox | Queue of proposed classifications, terms and descriptions to approve | v0.2 |
| Relationship and lineage explorer | Interactive graph of joins, keys and lineage with impact analysis | v0.2 to v0.5 |
| Rules | Build a rule, run a dry run, see matched assets, cost impact and approvals | v0.3 |
| Ask | Chat over the catalog with cited assets | v0.4 |
| Pipeline canvas | Read-only view of built-in and imported pipelines with live data and runs, then full editing round-tripping to YAML | v0.3 (view and run), after v0.5 (edit) |

**Stack:** React and TypeScript built with Vite; Radix-based accessible components styled with Tailwind; TanStack Query and Table for data and large grids; React Flow for graphs and the later pipeline canvas; ECharts for profile and dashboard charts. Built assets are bundled into the server image and use the GraphQL and REST APIs only, so the UI has no special access.

### Pipeline designer

Not a box-and-wire flow editor with a pop-up dialog per node. The designer should feel like a modern data tool:

- **Canvas and code are one artefact.** Each pipeline is a YAML file in Git; the canvas is a view of it, edits round-trip both ways, and changes show as readable diffs
- **Live data while building.** Select any connection to see sample rows and a quick profile; schemas propagate so type mismatches and missing columns show before a run
- **Catalog-aware.** Drag an asset from search onto the canvas; columns autocomplete from the graph; connections carrying sensitive fields are flagged
- **No dialogs.** Node settings open in a side panel generated from each node's schema
- **Keyboard first.** Add nodes from the command palette; auto-layout, minimap, and grouping into sub-pipelines
- **Run and debug in place.** Run to a selected node, watch row counts and timings live on each connection, set breakpoints
- **AI drafts, people approve.** Describe a pipeline in plain English for a draft; explain a node; suggest a fix for an error
- **Review like code.** Comments, version history, and an approval step before a pipeline goes live

Delivery: a read-only canvas in v0.3 that shows and runs built-in pipelines and imported jobs with live data; full editing after v0.5.

## Tech stack

One opinionated stack that runs in Docker Compose on a single VM, matching a typical single-VM lab deployment.

| Layer | Choice | Why |
| --- | --- | --- |
| Execution engine | Java 21 or Kotlin, virtual threads | JDBC ecosystem, cheap per-node workers |
| Plugin SDKs | Java (core), Python via gRPC (extractors, AI) | Fast core, easy community plugins |
| API and rules service | Java on Quarkus, same image as the engine | Rules compile straight into engine jobs; Python only in the AI sidecar |
| System of record | PostgreSQL 18 + Apache AGE + JSONB + pgvector | Cypher graph, SQL, attributes and embeddings in one DB; Neo4j adapter optional |
| Search | PostgreSQL full-text + pg\_trgm | One store, always consistent; Lucene later behind a SearchIndex interface |
| Event bus | PostgreSQL outbox + LISTEN/NOTIFY | Active-metadata events without a broker |
| Scheduler | Built-in, cron and event triggers | Avoid an Airflow dependency in v1 |
| SQL parsing | Apache Calcite | Column lineage across dialects |
| AI | Ollama local; Claude API optional | Private by default |
| Auth | Built-in accounts + any OIDC provider | No identity server to ship |
| UI | React + TypeScript | Lineage graph via React Flow |
| Packaging | Docker Compose; Helm later | One-line install like Glossary Generator |

**Why Java for the engine core:** every database vendor ships a JDBC driver, so connector breadth comes almost free; the big-data formats (Parquet, Iceberg, ORC, Avro, HDFS) are Java-first; Java 21 virtual threads give each node its own cheap worker, the streaming-engine model without thread-count limits; and DataHub's backend and OpenMetadata's server are Java too, so the patterns are proven at catalog scale.

**Modernise the row format:** use Apache Arrow columnar batches between nodes instead of per-row objects. Profiling and sketches run much faster on columns, and Arrow passes zero-copy to the Python sidecar.

| Option | Verdict |
| --- | --- |
| Java 21 / Kotlin + Arrow | Recommended core: drivers, formats, streaming-engine model |
| Python | Use as a sidecar (gRPC + Arrow) for AI, LLM calls and community extractors; too slow for in-stream profiling at volume |
| Rust (DataFusion) | Fastest raw execution, but a thin driver and connector ecosystem; consider later for hot profiling kernels |
| Go | Easy single-binary deploys, weak database and format libraries |

**One language for services:** keep the API and rules service on the JVM too (Quarkus, in the same image) so rules compile straight into engine jobs; reserve Python strictly for the AI sidecar.

## Dependency policy and Apache components

Kilnmark ships as two services: **PostgreSQL** (with AGE and pgvector as in-database extensions) and **the Kilnmark server** (Kiln engine, API and UI in one JVM). Apache projects come in as libraries inside that process, never as extra services to install, configure and keep in sync.

**The rule:** a dependency is welcome if it is a JAR or a database extension; it needs a decision record if it runs as its own process; it is out if it is a platform with its own storage, scheduler or UI.

**Adopt: embedded libraries**

| Project | Used for | From |
| --- | --- | --- |
| Apache Arrow | Columnar row batches between Kiln nodes; zero-copy to the Python sidecar | v0.1 |
| Apache Parquet, ORC, Avro | Reading file formats in connectors and profiling | v0.1 (Parquet), later the rest |
| Apache DataSketches | HyperLogLog distinct counts, KLL quantiles, frequent items; theta-sketch intersections to find columns that share values, the basis of join and key discovery | v0.1 |
| Apache Lucene | Optional later search backend behind the SearchIndex interface, if ranking or facets outgrow PostgreSQL full-text | Later, if needed |
| Apache AGE | Cypher graph inside PostgreSQL | v0.1 |
| Apache Commons (CSV, Compress, Text) | File handling and string similarity for name matching | v0.1 |
| Apache Tika | File-type detection and text extraction from PDF and Office files for document PII discovery | v0.2 |
| Apache Calcite | SQL parsing across dialects for column lineage, JVM-native | v0.5 |

**Optional connector plugins** (separate JARs, off by default, no services): Apache Iceberg for table-format metadata, Hadoop client for HDFS, Kafka client for topic metadata.

### Heritage: an independent engine, selective borrowing

Kilnmark is fully independent: no runtime dependency on another ETL engine or vendor product.

**Options considered**

| Option | Gains | Costs |
| --- | --- | --- |
| 1. Clean-room Kiln | Lean, Arrow-native, fully owned, nothing to untangle | Starts with a handful of nodes |
| 2. Full fork of an older open-source ETL engine | Hundreds of nodes; runs existing jobs | Inherits an old plugin container, a desktop designer, older Java and years of debt; such forks have taken a team years to modernise |
| 3. Clean Kiln plus selective borrowing of Apache-licensed code (chosen) | Lean core; reuse proven pieces where they save time, such as database dialects and value types; an importer converts supported existing jobs to Kiln pipelines | Each borrowed class needs adapting to Kiln's interfaces; the importer covers a subset |

**Why option 3:** Kilnmark's own nodes are metadata-focused (harvest, profile, discover, act), so most of a general ETL tool's nodes aren't needed. Borrowing a few proven parts saves time without importing a platform, and an importer still gives teams a path for their existing jobs.

**Decided (24 October 2026):** a clean Kiln engine, borrowing Apache-licensed code only where it saves time, with every borrowed file keeping its licence header and recorded in the provenance log ([ADR 0006](../adr/0006-clean-engine-borrowed-code.md) and [provenance.md](../legal/provenance.md) hold the licence facts and rules). The importer lands in v0.3 alongside the read-only canvas.

**Avoid: platforms that bring their own estate**

| Project | Why not |
| --- | --- |
| Apache Atlas | Needs Solr, HBase and Kafka just to run ([Cloudera docs](https://docs.cloudera.com/HDPDocuments/HDP3/HDP-3.0.1/installing-atlas/content/apache_atlas_prerequisites.html)): the exact sprawl to avoid |
| Apache Kafka (broker) | Events use a PostgreSQL outbox and LISTEN/NOTIFY instead |
| Apache Airflow, NiFi | Second schedulers and engines that overlap Kiln |
| Apache Solr / OpenSearch | PostgreSQL full-text covers search; Lucene is the fallback |
| Apache Spark, Flink | Compute clusters; at most, later push-down connectors |
| Apache Ranger, Superset | Security and BI platforms outside scope |

**Search and vectors stay in PostgreSQL.** Metadata search and embeddings live in the system of record: no second index to keep in sync, rebuild after a restore, or share between server instances. Dedicated vector databases (Milvus, Qdrant, Weaviate) are not adopted: at metadata scale pgvector's HNSW indexes are enough, and each would be another service to run. Both sit behind interfaces (`SearchIndex`, `VectorIndex`), so a customer needing far larger scale gets a different backend, not a different design.

## Roadmap

&#91;embedded content: roadmap · 5 phases, 5 gates\]

Ingest, profile and discovery come first because every later phase depends on a trustworthy graph; the rules phase is where the engine becomes different from a catalog. No dates are set yet.

## IP and licensing considerations

Owning this outright depends less on the code than on where the ideas and code come from. Settle these before writing any engine code. This is not legal advice; an employment or IP lawyer should confirm.

- **Employment IP clause** — an employment contract may assign inventions related to the employer's business to the employer, possibly including work done in your own time. Check it, and get a written carve-out or agreement before starting if the product overlaps the employer's.
- **Commercial products are proprietary** — reuse only concepts described in public documentation (rule definitions, temperature terms, tiering workflow); never internal code, designs or non-public material. Keep a clean-room log of sources.
- **Borrowed code's licence** — confirm the licence of the exact version any code is borrowed from, since open-source projects change licences over time ([ADR 0006](../adr/0006-clean-engine-borrowed-code.md)). Reimplementing from scratch avoids the question entirely.
- **Market features** — features and patterns (MCP servers, active metadata, propagation) aren't protectable as ideas; copying UI, docs or code is. DataHub, OpenMetadata and Unity Catalog code is Apache 2.0 and can be studied or reused with attribution.
- **Your licence choice** — decide early: proprietary, open core (Apache core plus commercial AI and enterprise features), or BSL-style. This shapes contributor agreements from the first commit.

**Open questions**

- [ ] Decided: a standalone product, separate from any other vendor's product. Plan and scope live in the companion plan doc.
- [ ] Decided: Kilnmark (product) and Kiln (engine). Trademark (UK IPO, USPTO, classes 9 and 42) and domain checks still to do.
- [ ] Decided: Quarkus, one image with api, scheduler and worker roles.
- [x] Decided: the first sources are PostgreSQL, S3-compatible object storage and local files, then SQL Server. The reference lab uses VersityGW for S3 (ADR 0015); MinIO is supported as a customer's store but not used in the lab.
