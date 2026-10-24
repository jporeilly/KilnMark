# 0012 AI is core, local by default, and people approve

Status: Accepted (24 October 2026); amended the same day for GPUs, hosted providers and
llama.cpp. Supersedes the Python sidecar timing in [0004](0004-java-quarkus-arrow.md).

## Decision

AI is part of every release from v0.1, not a layer added in v0.4. Five rules hold for every AI
feature:

1. **Local by default, hosted by choice.** Out of the box Kilnmark uses a model running inside
   the customer's network. An administrator can add hosted providers with an API key and choose,
   feature by feature, which model does what.
2. **Runs on what the customer has.** The default models answer in seconds on a CPU-only server.
   When a GPU is present, Kilnmark uses it and can run larger local models. A GPU is never
   required.
3. **AI proposes, people approve.** Anything AI writes to the catalogue (a description, a
   classification, a glossary term, a rule) lands as a proposal with the model, the prompt
   version and the facts it was given. A person accepts, edits or rejects it, and that decision
   is kept with who and why.
4. **Every feature works with AI off.** Each AI feature has a deterministic fallback: keyword
   search for natural-language search, pattern and checksum classifiers for the LLM tier, a
   built-in playbook for run diagnosis. AI makes the product better; it never makes it work.
5. **Scored before it ships.** The reference lab's answer key ([lab/README.md](../../lab/README.md))
   is the evaluation set. A release that scores worse than the last one does not ship.

## Where models run

| Kind | Runtimes and providers | Notes |
| --- | --- | --- |
| Bundled | llama.cpp `llama-server`, in the Compose `ai` profile: a CPU image, and a CUDA image when a GPU is present | Loads GGUF model files from disk, so offline installs carry the model files in the bundle. Enforces a JSON schema on answers. MIT licence |
| Customer's own, local | Ollama, vLLM, LM Studio, or any OpenAI-compatible endpoint | vLLM for a shared GPU server with many users; Ollama where it is already in use |
| Hosted, with an API key | OpenAI, Anthropic, Google Gemini, Azure OpenAI, AWS Bedrock, Mistral | Chosen per feature by an administrator |

## How

- **Model client.** Kilnmark's own interface (`ModelClient`: chat with a JSON schema, embed),
  with adapters built at runtime from the administrator's settings. Adapters use LangChain4j's
  provider modules (a library, under [ADR 0007](0007-dependency-policy.md)), not the Quarkus
  extension, because providers are chosen at runtime, not at build time.
- **Per-feature routing.** Each AI feature (embeddings, search, descriptions, classification,
  diagnosis, agents) names its model. Embeddings default to a local model even when chat goes to
  a hosted one: they are cheap, private and run at ingest.
- **What may leave the network.** Each source carries a rule: metadata only, metadata and sample
  values, or nothing to hosted models. Columns classified as personal never send values to a
  hosted model. Kilnmark checks the rule before every call to a hosted model and refuses the
  call rather than trim it silently.
- **API keys.** Stored encrypted in the database with a key the server reads from its
  environment or a mounted secret, or referenced from the environment directly. Never logged,
  never returned by the API, shown only as the last four characters.
- **Usage.** Every call records the provider, model, tokens, time and the feature that asked,
  so an administrator can see cost and speed per provider.
- **Structured answers.** Every chat call asks for JSON against a schema, at low temperature, and
  is retried once or falls back when the answer does not parse.
- **Facts only.** A prompt carries only what a user could see for themselves (profile, schema,
  samples the user may view, run records), and the answer lists the facts it relied on, so a
  person can check it.
- **Embeddings.** Written at ingest beside the profile, in pgvector, with the model's name and
  dimension stored with each vector, so changing models means re-embedding, never mixing.
- **Answers cached** by a hash of the facts, the model and the prompt version, so asking again is
  instant and repeatable.
- **Model selection.** Without a setting, the server picks the first available model from a
  preferred list, never an embedding model for chat. It records tokens per second, so a slow
  setup shows on the status page.
- **MCP server.** Read-only in v0.1 (search, asset, profile); proposals only from v0.4.
- **Python sidecar.** Only if a model we need has no HTTP-served route. Not planned for v0.1.
- **Evaluation in CI.** Deterministic checks run on every build. Model-scored checks run in a
  scheduled workflow with the default local models, and before every release; the same suite
  can score any configured provider, so a customer can compare before switching.

## Why

Customers buying a trusted-data product on-prem will not send their metadata to a cloud model by
default, and many servers have no GPU; others have GPUs or a preferred provider and expect to
use them. Per-feature routing and the egress rule let each customer choose without giving up
control. llama.cpp is the runtime to ship because it is small, runs on CPU and GPU, and works
offline from files. Approval keeps people accountable for what the catalogue says. Fallbacks
mean the product still installs and runs in a locked-down network. Scoring against a fixed
answer key turns "the AI seems better" into a number.

## Cost

About two more weeks in v0.1 (sprint 6). Each provider adapter must be tested against the
evaluation suite. A CPU-sized model is weaker than a large hosted one, so prompts must be narrow
and the facts well chosen. Every AI feature carries the cost of a fallback and an evaluation.
