# 0016 AI runtime: LangChain4j modules, model profiles, one egress gate, embeddings in one table

Status: Accepted (24 October 2026). Records the concrete choices behind
[0012](0012-ai-runtime.md), which stays as it is.

## Decision

### Libraries

| Need | Library | Licence | Jar size |
| --- | --- | --- | --- |
| Model client core | `dev.langchain4j:langchain4j-core` 1.22.0, with its JDK HTTP client (`langchain4j-http-client`, `-http-client-jdk`, `-reactive-streaming`) | Apache-2.0 | 0.85 MB |
| OpenAI API: llama-server, vLLM, Ollama, LM Studio, OpenAI, Azure OpenAI | `langchain4j-open-ai` 1.22.0 (jtokkit, its token counter, excluded) | Apache-2.0 | 0.39 MB |
| Anthropic | `langchain4j-anthropic` 1.22.0 | Apache-2.0 | 0.17 MB |
| Google Gemini | `langchain4j-google-ai-gemini` 1.22.0 | Apache-2.0 | 0.26 MB |
| MCP server | `io.quarkiverse.mcp:quarkus-mcp-server-http` 2.0.1, from the Quarkus platform BOM 3.40.1, with `mcp-server-api`, `jsonschema-generator`, `classmate`, `mutiny-zero` | Apache-2.0 | 1.07 MB |

In all, 2.7 MB of jars. All pass `:server:checkLicense`.

- **Plain LangChain4j, not the Quarkus LangChain4j extension.** The extension defines models in
  configuration at build or start time; administrators add models while the server runs. The
  provider modules are libraries ([0007](0007-dependency-policy.md)) and are built from stored
  profiles at run time.
- **Smallest set of modules.** One OpenAI module serves every local runtime (llama.cpp's
  `llama-server`, vLLM, Ollama's `/v1`, LM Studio), OpenAI itself, and Azure OpenAI through its v1
  API (`https://RESOURCE.openai.azure.com/openai/v1`, key sent as `api-key` and as a bearer token).
  The Azure SDK module (`langchain4j-azure-open-ai`) is not used: it brings the Azure core SDK,
  Netty and Reactor, tens of megabytes for an API the OpenAI module already speaks. Ollama is not a
  special case. Bedrock and Mistral (named in 0012) are left for later; Mistral speaks the OpenAI API.
- LangChain4j 1.22.0 wants mutiny-zero 1.3.1; the Quarkus platform pins 1.2.1. Only streaming
  uses it, and Kilnmark does not stream. The tests and a run against a real llama-server pass.

### Model profiles and routing

- A **model profile** (table `model_profile`, migration V6) holds provider, base URL, model name,
  purpose (`chat` or `embedding`), dimensions for embeddings, context size, timeout, concurrency
  limit, and a `local` flag: the administrator's assertion that the server is on their network.
  Only an OpenAI-compatible server can be marked local; hosted providers cannot.
- API keys are sealed with `SecretBox` (AES-256-GCM, the server's key) and only the model client
  opens them. The API returns `hasApiKey` and, for keys of 16 characters or more, the last four
  (0012); shorter keys show nothing. Failure messages are scrubbed of the key.
- A profile carries a `version` that goes up on every change. Each process caches one client per
  profile and rebuilds it when the stored version differs, so a change takes effect at once in
  every api and worker process, without a restart.
- **Routing** (`ai_route`): each feature (`describe`, `classify`, `search_nl`, `embed`) names one
  profile of the right purpose, or none (off, with its fallback). A profile in use cannot be
  deleted or change purpose. There is no automatic model selection yet (0012's "first available
  model"): a feature is off until an admin chooses.

### One gate for every call

`AiGateway` is the only way to call a model. For every call it: finds the profile, asks
`EgressPolicy`, waits for a slot (the profile's `max_concurrent`, a fair semaphore, waiting at
most the timeout; else "busy"), calls on a virtual thread with the profile's timeout, retries
timeouts, 408, 429, 5xx and refused connections up to `kilnmark.ai.max-retries` (default 2) with
exponential backoff and jitter, stops when the caller cancels, and writes a usage record whatever
the outcome. LangChain4j's own retries are off.

**Egress.** `EgressPolicy.refusal(profile, source)` is the one rule:

1. A model not marked local is refused when `kilnmark.ai.hosted-models=forbidden` (set by whoever
   runs the server; an admin cannot undo it) or the admin's `hosted_models` setting is
   `forbidden`.
2. A source's `ai_egress` (in `source.settings`, stream A's form): `local_only` (the default, and
   what any unknown value means) admits only local models, `none` admits none, `allowed` admits
   any model the server allows.

Calls about no source (a search query, a test) are checked against rule 1 only. A prompt that
names several sources (asking in plain English) lists only the sources the model may see. A
refused call never reaches the model, is recorded with outcome `refused`, and the message says
which rule refused it and what to change. There is no "metadata only versus sample values" split
yet (0012): every AI feature here sends metadata only, never values.

**Usage records** (`ai_usage`): feature, profile (name kept after deletion), provider, model,
local flag, source (name kept after the source is deleted), tokens in and out, latency, outcome
(`ok`, `refused`, `failed`, `timeout`, `busy`, `cancelled`), a plain-English detail and the
caller (`token 12 (name)`, `scan 34`, `mcp: token 12 (name)`). Prompt and answer text are kept
only with `kilnmark.ai.keep-usage-text=true`.

**Structured answers.** OpenAI-compatible servers, OpenAI and Azure get the JSON schema as
`response_format` (llama-server turns it into a grammar); Gemini is asked for JSON; Anthropic gets
the schema in the prompt. Every answer is parsed and checked; a plan that does not parse is asked
for once more, then the feature falls back. Temperature is 0, except for hosted OpenAI and Azure,
whose reasoning models refuse a temperature.

### Embeddings

- **When.** `ScanService` fires a CDI event, `ScanFinished(sourceId, sourceName, runId, status,
  cancelled)`, synchronously on the worker after the run's final status is written, while the
  worker still holds the source's scan lock. `Embeddings` observes it and embeds the source's
  assets that are new or changed, then adds a sentence to the run's detail ("Search by meaning: 12
  assets embedded with local-embed (40 unchanged).", or why nothing was embedded). Choosing or
  changing the embedding model re-embeds every source in the background, one process at a time
  (PostgreSQL advisory lock); admins can also start it (`POST /api/v1/ai/embeddings/refresh`).
- **What.** `AssetText`: kind, location and name (also split into words), source name and
  description, asset description, and every field with its type and description. Never values or
  profile statistics. Cut to about three characters per token of the profile's context size
  (1,500 characters when it has none).
- **Skipping.** Each row keeps a SHA-256 of the text plus provider, base URL, model and
  dimensions; an asset is embedded again only when that changes. A profile whose model, address
  or dimensions change loses its embeddings (and indexes) and, if it is the embedding model, all
  sources are embedded again.
- **Varying dimensions: one table, untyped column, partial index per profile.**
  `asset_embedding(profile_id, asset_urn, model, dimensions, text_hash, embedding vector)` with a
  check that `vector_dims(embedding) = dimensions`. The server creates, per profile,
  `asset_embedding_hnsw_p{id}_d{n} USING hnsw ((embedding::vector(n)) vector_cosine_ops) WHERE
  profile_id = {id}`; above 2,000 dimensions (pgvector's HNSW limit for `vector`) it indexes
  `embedding::halfvec(n)`, up to 4,000; above that, searches scan without an index. Searches use
  exactly the same cast, so the planner uses the index. We chose this over a table per dimension
  because there is one place to query, one foreign key to cascade, and swapping models never
  needs DDL beyond an index.
- Rows cascade away with their asset (`asset_urn REFERENCES asset ON DELETE CASCADE`), so
  deleting a source removes its embeddings. A source whose rule no longer admits the model loses
  its embeddings for that model at its next scan.

### Search

- **Hybrid.** `GET /api/v1/search` merges the existing keyword and trigram ranking (top 100) with
  the 100 nearest assets by cosine similarity (at least `kilnmark.ai.min-similarity`, default 0.3)
  by reciprocal rank fusion (k = 60). Each hit says whether it matched by `words`, `meaning` or
  both. Without an embedding model, or if the query cannot be embedded (refused, server down),
  it is the keyword search alone with a `note` saying why; `mode=keyword` asks for that.
- **Asking.** `POST /api/v1/search/ask` sends the question, the asset kinds in the catalogue and
  the names of sources the model may see to the `search_nl` model, which answers with a plan:
  words, kinds, sources, field names and tags. The plan is checked against the catalogue (unknown
  kinds and sources dropped, field names must look like names, at most five of each, tags dropped
  because the catalogue has none yet) and everything dropped is listed. The plan runs as a hybrid
  search with those filters and is returned with the results, so people see what was searched.
  Without a chat model, or if it fails, the question is searched as typed, with a note.

### MCP server

The Quarkiverse MCP server (Apache-2.0, 1.1 MB with its API, in the Quarkus platform) serves
Streamable HTTP at `/mcp` (and the older SSE transport at `/mcp/sse`). An HTTP permission puts
both behind the API token mechanism with the viewer role. Three read-only tools:
`search_assets`, `get_asset` (fields with a profile summary) and `list_sources` (with stream A's
display name when there is one). An assistant is a model of unknown location, so sources whose
rule is `none` are invisible, and minimum, maximum and most common values are given only for
sources whose rule is `allowed`. There is no lineage in the catalogue yet, so no lineage tool.

### Bundled models (Compose)

`compose.yaml` gains profiles `ai` (llama-server CPU image) and `ai-cuda` (CUDA image with an
NVIDIA GPU reservation), both `ghcr.io/ggml-org/llama.cpp` build b11429, plus `ai-embed` (CPU, in
both) and a one-off `ai-download` (curl 8.22.0) that fetches the default models into the `models`
volume: Qwen2.5-1.5B-Instruct Q4_K_M (Apache-2.0, about 1.1 GB) for chat and bge-small-en-v1.5
Q8_0 (MIT, 37 MB, 384 dimensions) for embeddings. They are not added to Kilnmark automatically:
the admin adds the profiles and marks them local.

### Evaluation

`tests/ai-eval/golden.json` (synthetic, over the reference lab) holds 15 search queries with the
assets that answer them and 6 questions with the plan they should give. `./gradlew :server:aiEval`
runs it against a running server with its configured models and writes `results.json` and
`results.md` to `dist/build/server/ai-eval`. It is not part of `build`. The first run, against
the lab with bge-small-en-v1.5 and Qwen2.5-0.5B-Instruct on two CPU cores: keyword MRR 0.333,
hybrid MRR 0.917, plan accuracy 0.514. With only five lab tables, recall at 5 is not yet a useful
number.

## Why

The owner's direction: AI in every release, local first, hosted by API key, people approve,
nothing leaves without the administrator's consent. One gate for every call makes the egress rule
and the usage record impossible to forget in a new feature. Profiles in the database, not
configuration, let an admin add a model in the UI and test it at once. One embedding table with
per-profile partial indexes keeps search one query whatever the model, and lets models change
without a migration.

## Cost

- LangChain4j moves fast (a minor release every few weeks). All use of it is in
  `LangChainModelClient` (about 120 lines) behind Kilnmark's own `ModelClient`.
- Indexes are created by the running server, as DDL, not by migrations; the database user must
  own the table (it does in every supported install).
- A full re-embed runs in whichever process handled the change, as a background thread, not as a
  queued job: the job table's kinds are shared with other work, and a second kind would collide
  with other branches' migrations. It survives nothing: if that process stops, the next scan of
  each source catches up, or an admin presses "Embed again".
- Every search with an embedding model costs one embedding call and one usage row.
- The `local` flag is the administrator's word. Kilnmark does not check where a server is.
- Hosted calls go straight out; there is no HTTP proxy setting yet.
