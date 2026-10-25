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
- **Versions held back by the Quarkus platform.** LangChain4j 1.22.0 asks for Jackson 2.22.1 and
  mutiny-zero 1.3.1; the enforced Quarkus platform (3.40.1) keeps Jackson at 2.21.7 and
  mutiny-zero at 1.2.1. Only streaming uses mutiny-zero, and Kilnmark does not stream. The tests
  and a run against a real llama-server pass with these versions; a LangChain4j upgrade must be
  checked the same way.
- **A beta artifact.** `langchain4j-reactive-streaming` is published as `1.22.0-beta32`, pulled in
  by the OpenAI and Anthropic modules. It is unused here (no streaming), but it ships.
- **A dev-mode client that ships.** `quarkus-mcp-server-http` depends on
  `quarkus-mcp-server-sse-client` (8 KB, for its dev UI), so it is in the server too, unused.

### Model profiles and routing

- A **model profile** (table `model_profile`, migration V6) holds provider, base URL, model name,
  purpose (`chat` or `embedding`), dimensions for embeddings, context size, timeout, concurrency
  limit, and a `local` flag: the administrator's assertion that the server is on their network.
  Only an OpenAI-compatible server can be marked local; hosted providers cannot. The UI leaves it
  unticked, and clears it when the address's host changes.
- **The operator lock and the local flag.** With `kilnmark.ai.hosted-models=forbidden` (set by
  whoever runs the server, not an admin), the admin's tick is not enough: a profile counts as local
  only if its host is an IP address inside a network listed in `kilnmark.ai.local-hosts`, or a host
  name listed there explicitly. Names are never resolved for this, so DNS cannot make a name
  local; a listed name is trusted as the operator's word, and one that stops resolving is a failed
  call ("cannot reach"), never a refusal that would delete embeddings. The default list is loopback
  and private networks (127.0.0.0/8, ::1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7) and
  the name `localhost`; an empty value also means this default; the operator can narrow it, for
  example to `10.20.0.0/16,ai,ai-embed` for the Compose services. Every entry must parse, or the
  server does not start. It is checked when a profile is saved and before every call
  (`ModelHosts`, `EgressPolicy.local`). **What cannot be detected:** a server on a local address
  that forwards to a hosted service (a reverse proxy or gateway). The lock keeps Kilnmark from
  calling outside addresses; what a local server does with a request is the operator's to control.
- **No metadata services.** Base URLs on link-local addresses (169.254.0.0/16, fe80::/10), the
  Alibaba and AWS IPv6 metadata addresses, and cloud metadata names (`metadata.google.internal`
  and the like) are refused on save and at call time, unless the operator sets
  `kilnmark.ai.allow-metadata-addresses=true`. Admins can still point a profile at any other
  internal address: they are trusted with that, as with source URLs.
- API keys are sealed with `SecretBox` (AES-256-GCM, the server's key) and only the model client
  opens them. The API returns `hasApiKey` and, for keys of 16 characters or more, the last four
  (0012); shorter keys show nothing. Failure messages are scrubbed of the key, the input records
  hide it in `toString`, and a test captures the log while keys are used and refused. A stored key
  goes only where it was entered for: changing the address's scheme, host or port needs the key
  again.
- A profile carries a `version` that goes up on every change. Each process caches one client per
  profile and rebuilds it when the stored version differs, so a change takes effect at once in
  every api and worker process, without a restart.
- **Routing** (`ai_route`): each feature (`describe`, `classify`, `search_nl`, `embed`) names one
  profile of the right purpose, or none (off, with its fallback). A profile in use cannot be
  deleted or change purpose. There is no automatic model selection yet (0012's "first available
  model"): a feature is off until an admin chooses.

### One gate for every call

`AiGateway` is the only way to call a model. For every call it: checks the address
(`ModelHosts`), asks `EgressPolicy`, waits for a slot (the profile's `max_concurrent`, a fair
semaphore; the limit is per process, so separate api and worker processes can together exceed
it), calls on a virtual thread, retries 408, 429, 5xx, refused connections and (in the background)
timeouts with exponential backoff and jitter, stops when the caller cancels, and writes a usage
record whatever the outcome. LangChain4j's own retries are off.

**Two budgets.** Interactive calls (a search, a question, an MCP search) wait at most 2 seconds
for a slot, never retry a timeout, and give up after 10 seconds in all, name lookups included;
for 30 seconds after an interactive call timed out or could not reach the model, they skip it at
once (recorded as `failed`, "not tried again yet"), so an outage does not hold every search.
Background calls (embedding at scan, the profile test) use the profile's timeout and up to
`kilnmark.ai.max-retries` (default 2), and a slow background batch does not make searches skip the
model. The address and egress checks run again before every retry.

**What viewers see.** An `AiException`'s message can name the model's address and quote the
server: it goes to usage records and the profile test, which are admin-only. Search notes, ask
notes, scan run details and MCP answers use fixed words ("unavailable right now; an admin can see
why under AI usage"); only egress refusals, which name the source and the profile, are shown as
they are.

**Egress.** `EgressPolicy.refusal(profile, source)` is the one rule:

1. A model not marked local is refused when `kilnmark.ai.hosted-models=forbidden` (set by whoever
   runs the server; an admin cannot undo it) or the admin's `hosted_models` setting is
   `forbidden`.
2. A source's `ai_egress` (in `source.settings`, stream A's form): `local_only` (the default, and
   what any unknown value means) admits only local models, `none` admits none, `allowed` admits
   any model the server allows.

Calls about no source (a search query, a test) are checked against rule 1 only: with a hosted
embedding model, the text of every search goes to that provider, and with a hosted chat model,
every question does (the AI models page says so). A prompt that names several sources (asking in
plain English) lists only the sources the model may see, and only the kinds of asset found in
them. A refused call never reaches the model, is recorded with outcome `refused`, and the message says
which rule refused it and what to change. There is no "metadata only versus sample values" split
yet (0012): every AI feature here sends metadata only, never values.

**Usage records** (`ai_usage`): feature, profile (name kept after deletion), provider, model,
local flag, source (name kept after the source is deleted), tokens in and out, latency, outcome
(`ok`, `refused`, `failed`, `timeout`, `busy`, `cancelled`), a plain-English detail and the
caller (`token 12 (name)`, `scan 34`, `mcp: token 12 (name)`). An embedding answer with the wrong
number or size of vectors is recorded as `failed`. Prompt and answer text are kept only with
`kilnmark.ai.keep-usage-text=true`. Records older than `kilnmark.ai.usage-retention` (default 90
days) are deleted every six hours by processes with the scheduler role.

**Structured answers.** OpenAI-compatible servers, OpenAI and Azure get the JSON schema as
`response_format` (llama-server turns it into a grammar); Gemini is asked for JSON; Anthropic gets
the schema in the prompt. Every answer is parsed and checked; a plan that does not parse is asked
for once more, with what was wrong added to the question, then the feature falls back. Temperature is 0, except for hosted OpenAI and Azure,
whose reasoning models refuse a temperature.

### Embeddings

- **When.** `ScanService` fires a CDI event, `ScanFinished(sourceId, sourceName, runId, status,
  cancelled)`, synchronously on the worker after the run's final status is written, while the
  worker still holds the source's scan lock. `Embeddings` observes it (not after a failed scan) and
  embeds the source's assets that are new or changed, reading them 500 at a time, then adds a
  sentence to the run's detail ("Search by meaning: 12 assets embedded with local-embed (40
  unchanged).", or why nothing was embedded). Before each batch it reads the source and the
  embedding profile again, so a change to the source's AI rule or to the model applies at once.
- **Re-embedding.** Choosing or changing the embedding model re-embeds every source in the
  background; admins can also start it (`POST /api/v1/ai/embeddings/refresh`). A request made
  while one runs is not dropped: the run goes round once more. Across processes a PostgreSQL
  advisory lock makes the second process wait for the first, then run (finding most assets
  unchanged).
- **What.** `AssetText`: kind, location and name (also split into words), source name and
  description, asset description, and every field with its type and description. Never values or
  profile statistics. Cut to one and a half characters per token of the profile's context size
  (768 characters, for 512 tokens, when it has none). Asset text is mostly identifiers and types,
  which bge-small's tokenizer reads at about 1.8 characters per token, and llama-server answers an
  input longer than its slot's context with an error (HTTP 500, "input is too large"), not a cut;
  both were measured with build b11429.
- **Skipping.** Each row keeps a SHA-256 of the text plus provider, base URL, model and
  dimensions; an asset is embedded again only when that changes. A profile whose model, address
  or dimensions change loses its embeddings (and indexes) and, if it is the embedding model, all
  sources are embedded again.
- **Varying dimensions: one table, untyped column, partial index per profile.**
  `asset_embedding(profile_id, asset_urn, model, dimensions, text_hash, embedding vector)` with a
  check that `vector_dims(embedding) = dimensions`. The server creates, per profile,
  `asset_embedding_hnsw_p{id}_d{n} USING hnsw ((embedding::vector(n)) vector_cosine_ops) WHERE
  profile_id = {id} AND dimensions = {n}`, with `CREATE INDEX CONCURRENTLY` outside a transaction,
  after checking `pg_index` for a valid one. Building holds a per-profile advisory lock; a process
  that cannot take it skips, because the other's build shows as an invalid index until it ends.
  Only the lock holder drops an invalid index (a build that failed half-way) and builds it again. Above 2,000 dimensions (pgvector's HNSW limit for `vector`) it indexes
  `embedding::halfvec(n)`, up to 4,000; above that, searches scan without an index. Searches put
  the profile id and size into the SQL as numbers and use the same cast, so the partial index
  matches even under a generic plan, and rows of another size are never compared.
- **Changing a profile mid-scan.** Vectors are saved only while the profile still makes the same
  vectors (provider, address, model and size) as when they were made: the profile row is
  share-locked in the same transaction as the insert. Editing its timeout or concurrency does not
  stop a scan. Searches also filter on `dimensions`, and a database error in the vector half of a
  search falls back to words with a note. We chose this over a table per dimension
  because there is one place to query, one foreign key to cascade, and swapping models never
  needs DDL beyond an index.
- Rows cascade away with their asset (`asset_urn REFERENCES asset ON DELETE CASCADE`), so
  deleting a source removes its embeddings. A source whose rule no longer admits the model loses
  its embeddings for that model at its next scan.

### Search

- **Hybrid.** `GET /api/v1/search` (`q` at most 500 characters) merges the existing keyword and
  trigram ranking (top 100) with the 100 nearest assets by cosine similarity (at least
  `kilnmark.ai.min-similarity`, default 0.3) by reciprocal rank fusion (k = 60). The vector query
  runs with `SET LOCAL hnsw.ef_search` at least the number wanted; with filters (from a question)
  they go into the vector query itself, and with pgvector 0.8 or later
  `hnsw.iterative_scan = relaxed_order` keeps the index scan going until enough rows pass (the
  rows are sorted again after). Each hit says whether it matched by `words`, `meaning` or
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
Streamable HTTP at `/mcp`, behind the API token mechanism with the viewer role (an HTTP
permission). The extension's older SSE transport (`/mcp/sse`, `/mcp/messages`) has no switch, so
an HTTP permission denies it. With sign-in off (loopback only), `/mcp` also answers only requests
whose Host is `localhost`, `127.0.0.1` or `[::1]`, against DNS rebinding. Three read-only tools:
`search_assets` (query at most 500 characters), `get_asset` (fields with a profile summary) and
`list_sources` (with stream A's display name when there is one).

An assistant is a model of unknown location, so by default it sees only sources whose AI rule is
`allowed`. An admin can flag a token as used by a local assistant (`api_token.local_assistant`,
added in V6; `PUT /api/v1/ai/assistant-tokens/{id}`, and a tick on the Tokens page); MCP calls
with it also see `local_only` sources. Sources whose rule is `none` are never visible. There is no
lineage in the catalogue yet, so no lineage tool.

### Bundled models (Compose)

`compose.yaml` gains profiles `ai` (llama-server CPU image) and `ai-cuda` (CUDA image with an
NVIDIA GPU reservation), plus `ai-embed` (CPU, in both) and a one-off `ai-download` that fetches
the default models into the `models` volume. Images are pinned by tag and digest:

| Image | Tag | Digest |
| --- | --- | --- |
| `ghcr.io/ggml-org/llama.cpp` | `server-b11429` (5 October 2026) | `sha256:2f8ebc2dde83c4bd7e5c6d61efa57965e0d5a79d29802c246c4593740d9c04c1` |
| `ghcr.io/ggml-org/llama.cpp` | `server-cuda-b11429` | `sha256:883915ea20a4b350e98f7284098f4d400c21e9b366e248d397cd42d90602a84c` |
| `docker.io/curlimages/curl` | `8.22.0` | `sha256:58adaa4e8dca9c988bae2aba4ab3434a0bb2da16bbe3f92dec39ec7785166777` |

Each model file is downloaded from a fixed commit and kept only if its SHA-256 matches; a file
already in the volume is checked, not trusted:

| Model | Source (Hugging Face) | Revision | SHA-256 | Licence |
| --- | --- | --- | --- | --- |
| Qwen2.5-1.5B-Instruct, Q4_K_M, 1.12 GB | `Qwen/Qwen2.5-1.5B-Instruct-GGUF`, file `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `91cad51170dc346986eccefdc2dd33a9da36ead9` | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` | Apache-2.0 |
| bge-small-en-v1.5, Q8_0, 37 MB, 384 dimensions | `ggml-org/bge-small-en-v1.5-Q8_0-GGUF`, file `bge-small-en-v1.5-q8_0.gguf` | `f2068edd9b54f2a369549ccc71f70ed273a2a801` | `f046db1dc724cf4f6f0a0c5917e922823b73eb1d27b8f9a9c2797f7866974804` | MIT |

Qwen publishes its own GGUF files, so the chat model comes from the publisher. BAAI publishes
bge-small-en-v1.5 as PyTorch, safetensors and ONNX, not GGUF, so the embedding model is the
conversion published by ggml-org, the llama.cpp project itself, rather than a third party's.

Slots: chat runs `--parallel 2` over 8,192 tokens (4,096 per slot, matching the default of two
calls at a time per model); embeddings run `--parallel 2` over 1,024 tokens, so each slot has the
512 tokens bge-small accepts (Kilnmark sizes the text to fit, above). The models are not added to Kilnmark automatically: the admin adds
the profiles and ticks them local.

### Evaluation

`tests/ai-eval/golden.json` (synthetic, over the reference lab) holds 15 search queries with the
assets that answer them and 6 questions with the plan they should give. `./gradlew :server:aiEval`
runs it against a running server with its configured models and writes `results.json` and
`results.md` to `dist/build/server/ai-eval`. It is not part of `build`. It fails when a score falls
below `tests/ai-eval/baseline.json` by more than its tolerance (0.02), which makes 0012's rule 5
("a release that scores worse does not ship") a command to run;
`-Pkilnmark.eval.update-baseline=true` accepts new scores.

The baseline is the first run, against the lab with bge-small-en-v1.5 and **Qwen2.5-0.5B-Instruct**
(smaller than the shipped 1.5B chat model, to fit the build machine) on two CPU cores: keyword MRR
0.333, hybrid MRR 0.917, plan accuracy 0.514. A new baseline should be taken with the shipped
models before the first release. With only five lab tables, recall at 5 is not yet a useful
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
- Concurrency limits are per process; the model server should be sized for api and worker
  processes together.
- Every search with an embedding model costs one embedding call and one usage row.
- Without the operator lock, the `local` flag is the administrator's word. With it, Kilnmark checks
  the address against the operator's list, but cannot see past a local server that forwards
  requests elsewhere.
- Hosted calls go straight out; there is no HTTP proxy setting yet.

## Amendment (25 October 2026): container host names and Podman's host address

**Local hosts.** When Kilnmark runs in a container (`KILNMARK_IN_CONTAINER=true`, which the image
sets, or `/.dockerenv` or `/run/.containerenv`), the default `kilnmark.ai.local-hosts` also lists
`host.containers.internal` and `host.docker.internal`. These are the names Podman and Docker (and
Kilnmark's `compose.yaml`, through `extra_hosts`) give the machine a container runs on, which is
the usual way to reach Ollama or LM Studio running natively on the same PC, with its GPU. Without
them, the operator lock refused that set-up unless the operator listed the names by hand.

Unlike other listed names, these two are not taken on the operator's word alone, because nobody
chose them: DNS decides where a listed name goes. Inside a container the engine answers for them;
outside one, Docker Desktop writes `host.docker.internal` into the host's own hosts file, and a
search domain, a wildcard record or a hostile resolver could point either name at a remote
machine. So they are on the default list only in a container, and they count as local only while
every address they resolve to is loopback, private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,
fc00::/7) or 169.254.1.2, checked when a profile is saved and before every call (within the
call's time limit, and remembered for 5 seconds so a search over many sources looks the name up
once). A name that does
not resolve (or not within 2 seconds) is not refused, which would delete embeddings; the call
fails as unreachable instead. As for every address, the lookup at call time and the connection
are separate, so a resolver that answers differently between the two is not caught.

**Podman's host address.** Rootless Podman 5.3 and later (pasta, `--map-guest-addr`) maps
`host.containers.internal` to 169.254.1.2, a link-local address that the metadata rule refuses.
Exactly that address is now accepted, only while Kilnmark runs in a container and only when
reached through one of the two container host names; typed as an address, behind any other name,
and every other link-local or metadata address (169.254.169.254, 169.254.170.2, fd00:ec2::254 and
the rest) stays refused. IPv4 addresses written in unusual forms (leading zeros, hexadecimal, one
32-bit number, fewer than four parts) are refused rather than reinterpreted, because resolvers
disagree on what they mean (the JDK reads `0177.0.0.1` as 177.0.0.1, C libraries as 127.0.0.1);
IPv4-mapped IPv6 is read as the IPv4 address it carries. A host name ending in a dot is refused
too: the local-hosts list would match it without the dot, while the name service would look it
up as an absolute name, outside the local search domains.

**Discovery.** The same change added `POST /api/v1/ai/discover` (admins only), which asks the usual
places and one typed address for model servers and their models. It reports GPU use as the model
server sees it (Ollama's `/api/ps`), rather than Kilnmark looking for a GPU itself, which a
container usually cannot do. It calls only model-server paths, after the address rules above
(under the lock, unlisted hosts are not even looked up), within a time limit, and returns a
summary, never the servers' answers.
