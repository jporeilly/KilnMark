# APIs and version control

Status: design. Settings and the interactive API docs come with the round 2 merge. The rest is
planned for round 3 onwards.

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

## Data as APIs

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

## Python service

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

### Git (optional)

- An admin connects a Git repository (HTTPS or SSH; GitHub, GitLab, Azure DevOps, Gitea or a
  bare server). Kilnmark keeps a working copy.
- **Pipelines live in the repository as YAML files**, one per pipeline, in a readable layout.
  Saving in Kilnmark commits, with the author and message. Commits made outside Kilnmark are
  picked up, checked and loaded.
- **Branches and reviews:** a person can work on a branch and open a pull request in the Git
  host. Kilnmark shows which branch each environment follows.
- **Promotion:** dev, test and prod are Kilnmark servers following different branches or tags.
  Promotion is a merge or a tag, and the target server loads it after validation and, if
  configured, a dry run.

### Config as code

- Sources (without secrets), quality rules, contracts, published APIs, AI routing and settings
  export as YAML files in the same repository layout, and import back.
- Secrets are never written to Git. A file refers to a secret by name (`secret:NAME` or
  `env:NAME`), and each environment supplies its own values.
- **Import is a plan, then an apply:** Kilnmark shows what would be created, changed or removed,
  as infrastructure-as-code tools do, and applies it only when confirmed. It can be run from the
  command line in CI.
- An environment can be rebuilt from the repository plus its secrets.

## Build order

1. **With the round 2 merge:** the Settings section, and the interactive API docs.
2. **Round 3, with pipelines:** in-app history for pipelines, rules, sources and contracts.
3. **Round 3 or 4:**
   - Git for pipelines;
   - config as code with plan and apply;
   - data as APIs for assets.
4. **Later:**
   - data as APIs for pipeline outputs;
   - the Python sidecar, SDK and Python steps;
   - promotion between environments.
