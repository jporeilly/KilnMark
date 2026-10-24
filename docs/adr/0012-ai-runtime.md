# 0012 AI is core, local by default, and people approve

Status: Accepted (24 October 2026). Supersedes the Python sidecar timing in [0004](0004-java-quarkus-arrow.md).

## Decision

AI is part of every release from v0.1, not a layer added in v0.4. Five rules hold for every AI
feature:

1. **Local by default.** The server talks to a model over HTTP: Ollama's API or any
   OpenAI-compatible endpoint (vLLM, LM Studio, an approved cloud model). Nothing leaves the
   customer's network unless an administrator points the endpoint elsewhere.
2. **Default models run on a CPU.** A small instruct model and an embedding model that answer in
   seconds on a server with no GPU. A GPU makes them faster; it is never required.
3. **AI proposes, people approve.** Anything AI writes to the catalogue (a description, a
   classification, a glossary term, a rule) lands as a proposal with the model, the prompt
   version and the facts it was given. A person accepts, edits or rejects it, and that decision
   is kept with who and why.
4. **Every feature works with AI off.** Each AI feature has a deterministic fallback: keyword
   search for natural-language search, pattern and checksum classifiers for the LLM tier, a
   built-in playbook for run diagnosis. AI makes the product better; it never makes it work.
5. **Scored before it ships.** The reference lab's answer key ([lab/README.md](../../lab/README.md))
   is the evaluation set. A release that scores worse than the last one does not ship.

## How

- **Model client.** A thin Java interface (`ModelClient`: chat with a JSON schema, embed) in the
  server, with Ollama and OpenAI-compatible adapters over `java.net.http`. No AI framework is
  needed for v0.1; `quarkus-langchain4j` stays a candidate if agents in v0.4 need it.
- **Structured answers.** Every chat call asks for JSON against a schema, at low temperature, and
  is rejected and retried, or falls back, when the answer does not parse.
- **Facts only.** A prompt carries only what a user could see for themselves (profile, schema,
  samples the user may view, run records), and the answer lists the facts it relied on, so a
  person can check it.
- **Embeddings.** Written at ingest beside the profile, in pgvector, with the embedding model's
  name and dimension stored with each vector, so changing models means re-embedding, never mixing.
- **Answers cached** by a hash of the facts and the prompt version, so asking again is instant and
  repeatable.
- **Model selection.** An administrator names the models; otherwise the server picks the first
  installed model from a preferred list, never an embedding model for chat. It records tokens per
  second, so a slow CPU-only setup shows on the status page.
- **Compose.** An optional `ai` profile adds an Ollama container with a volume for models; the
  offline bundle can carry the default model files.
- **MCP server.** Read-only in v0.1 (search, asset, profile); proposals only from v0.4.
- **Python sidecar.** Only if a model we need has no HTTP-served route. Not planned for v0.1.
- **Evaluation in CI.** Deterministic checks run on every build. Model-scored checks run in a
  scheduled workflow with the default models, and before every release.

## Why

Customers buying a trusted-data product on-prem will not send their metadata to a cloud model by
default, and many servers have no GPU. Approval keeps people accountable for what the catalogue
says. Fallbacks mean the product still installs and runs in a locked-down network. Scoring
against a fixed answer key turns "the AI seems better" into a number.

## Cost

About two more weeks in v0.1 (sprint 6). A CPU-sized model will be weaker than a large hosted one,
so prompts must be narrow and the facts well chosen. Every AI feature carries the cost of a
fallback and an evaluation.
