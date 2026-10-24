# APIs and version control

Status: design, trimmed on 24 October to what a pipeline engine needs.

- **Now:** Settings, interactive API docs, and in-app version history.
- **v0.2:** one Git and files feature.
- **Parked until a design partner asks:** data as APIs, and the Python service.

## Settings

Administration moves under one **Settings** section in the navigation, so the main menu stays
about data and pipelines:

- API tokens
- AI models and AI usage
- Drivers (round 3)
- Quality settings: webhook allow-list, retention
- Version control (below)
- Server: roles, sign-in, allowed hosts, read-only status of operator settings

Settings pages are admin-only, apart from a person's own API tokens.

## Interactive API docs

Kilnmark's REST API is described by an OpenAPI 3.1 document. The server publishes it at
`/q/openapi` and offers an interactive explorer at `/api/docs`, similar to FastAPI's `/docs`:
every endpoint is listed with its parameters, schemas and examples and can be tried in the
browser with an API token.

- Generated from the code (SmallRye OpenAPI), with operation summaries, error responses and
  role requirements written on each endpoint.
- The explorer is served from the server itself, so it works offline.
- A setting can turn the explorer off in production. The OpenAPI document stays available to
  signed-in users.
- The OpenAPI document is versioned with the API (`/api/v1`). A breaking change needs `/api/v2`.

## Data as APIs (parked)

Parked until a design partner asks. It is a product category of its own (data services), with
security, caching and rate-limit work that would pull focus from pipelines. The idea, for later:


Any table, view, file or pipeline output can be published as a read-only REST endpoint, the way
earlier tools offered data services.

- **Publishing:** a steward chooses the asset or pipeline output, the columns, and filters fixed
  by the publisher. They also set which parameters callers may pass, page size limits and the
  roles or tokens allowed.
- **Endpoint shape:** `GET /data/v1/{name}`, with `?filter=`, `?select=`, `?order=` and cursor
  paging. It returns JSON or CSV (Arrow IPC and Parquet on request). Each published API has its
  own OpenAPI document, so it appears in the explorer too.
- **Pipeline outputs:** a published pipeline runs on request with the caller's parameters, or
  serves its latest output from a cache with a stated freshness.
- **Trust:** every response carries the asset's quality score and contract status in headers. A
  publisher can refuse to serve data whose gate failed.
- **Safety:** sensitive columns are masked unless the token may unmask. Responses are rate-limited
  per token. Queries are pushed down to the source with the same read-only rules as quality
  checks. Every call is logged without its data.

## Python service (parked)

Parked until a design partner asks. It is a second runtime to package, secure and support. The
AI steps and the documented REST API cover most needs. Anyone can generate a Python client from
the OpenAPI document in minutes. The idea, for later:


A Python sidecar built on FastAPI serves Python users and AI tooling:

- **Python SDK** (`pip install kilnmark`): a typed client generated from the OpenAPI document, plus
  helpers that read published data or pipeline outputs straight into pandas or Polars through
  Arrow.
- **Python steps in pipelines:** a pipeline step can call a Python function hosted by the
  sidecar. Batches go through Arrow Flight or Arrow IPC, so there is no row-by-row conversion.
  This covers data science libraries, custom parsing and model code that does not exist in Java.
- **The sidecar's own API** is FastAPI, documented at its own `/docs`, reachable only from the
  Kilnmark server, and authenticated with a service token.
- It runs as an optional compose profile, `python`, with a pinned image and a locked dependency
  set. Python steps are off unless an admin turns them on, because they run arbitrary code.

## Version control

Three layers. Each works on its own, and Git is optional.

### In-app history (always on)

- Every change to a pipeline, quality rule, contract, source setting or published API is stored
  as a version: who, when, a message and the full content.
- **Diff** between any two versions: YAML diff for pipelines, a field-level diff for settings.
- **Restore** a previous version as a new version. History is never rewritten.
- Pipeline runs record the version they ran, so a result can always be traced to the exact
  pipeline that produced it.

### Git and files (v0.2, one feature)

Pipelines are YAML already, so one feature covers Git and configuration as code.

- **Export and import as files:** the project, meaning pipelines plus sources (without secrets),
  quality rules, contracts and AI routing, exports to a folder of YAML files in a readable
  layout, and imports back with a summary of what will change before it is applied.
- **Secrets never go into files.** A file refers to a secret by name (`secret:NAME` or
  `env:NAME`), and each environment supplies its own values.
- **Optional Git:** an admin connects a repository (HTTPS or SSH, any Git host) and that folder
  is kept in sync.
  - Saving in Kilnmark commits, with author and message.
  - Commits made elsewhere are checked and loaded.
  - Branches, reviews and promotion between dev, test and prod use the team's own Git host and
    merges. Kilnmark does not add its own promotion workflow.

## Build order

1. **With the round 2 merge:** the Settings section and the interactive API docs.
2. **Round 3, with pipelines:** in-app history for pipelines, rules, sources and contracts. Every
   run records the pipeline version it ran.
3. **v0.2:** Git and files.
4. **Parked:** data as APIs and the Python service, until a design partner asks.
