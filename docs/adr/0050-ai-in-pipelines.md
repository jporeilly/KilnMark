# 0050 AI in pipelines

Status: Accepted (6 November 2026)

## Context

Kilnmark's AI so far works on the catalogue and for people: it drafts descriptions and
classifications for people to approve, answers questions in plain English, searches by meaning,
suggests changes to a pipeline, and answers in the assistant
([ADR 0012](0012-ai-runtime.md), [ADR 0016](0016-ai-runtime-choices.md),
`server/src/main/java/io/kilnmark/server/ai`). Models run locally by default; an administrator
can add hosted providers and choose a model per feature; every call is logged; and a model never
sees values marked sensitive unmasked ([ADR 0027](0027-unmasking-is-a-grant.md)).

None of it works on the data a pipeline moves. Customers increasingly want that: sort support
tickets by topic, pull an amount and a date out of free text, summarise or translate notes, find
personal data hidden in comments, and turn text into vectors for search by meaning. The owner
asked on 6 November 2026 for "the whole AI piece" alongside more nodes
([ADR 0049](0049-more-nodes-and-how-coarse-they-are.md)).

## Decision

1. **Two nodes, coarse as ADR 0049 asks:**
   - **`ai`**, with a `task`:
     - `classify`: a label for each row from the categories given, with a confidence;
     - `extract`: named fields from free text into typed columns, by a schema the node declares;
     - `summarise` and `translate`: text into shorter text, or another language;
     - `detect-personal-data`: which personal data a text holds (names, account numbers,
       addresses), with the patterns and checksum classifiers as the fallback and first pass.
   - **`embed`**: a text column into a vector column, for search by meaning on the customer's own
     data (written to PostgreSQL with pgvector, as Kilnmark's own embeddings are).
2. **The same model rules as every AI feature.** A new feature, `pipeline_ai`, chosen per server by
   an administrator: local by default, a hosted provider only if they choose one. Answers are
   held to a JSON schema, at temperature 0, so the same text gives the same answer as far as a
   model allows.
3. **A model never sees a value marked sensitive unmasked.** A pipeline whose `ai` or `embed`
   node would read a column marked sensitive is refused when it is saved, unless the column is
   masked first (the `mask` node, ADR 0049) or the node runs `detect-personal-data` on a local
   model, whose answers are themselves marked sensitive. This keeps ADR 0027 without exception.
4. **What a model says is marked as a model's.** Each answer column comes with the model, the
   prompt version and a confidence, so a later `gate` or `route` can send low-confidence rows to
   a person, and lineage shows which columns a model wrote. What a pipeline writes is data, not a
   catalogue change, so it needs no approval; a proposal to the catalogue still does
   (ADR 0012, rule 3).
5. **Limits a run cannot pass:** a most rows per run, a most tokens per run, a time limit per
   call, calls in batches with a rate limit per provider, and answers cached by the hash of the
   text and the prompt version, so a rerun does not pay again. Every call is counted in the run's
   record and the usage log.
6. **With AI off, the node says so.** ADR 0012's rule 4 (every feature works with AI off) holds
   for `detect-personal-data`, which falls back to its patterns. The other tasks have no
   deterministic equivalent: a pipeline using them cannot be saved or run while `pipeline_ai` has
   no model, and says why.
7. **Scored before it ships** (ADR 0012, rule 5): each task gets an answer key in the lab
   (synthetic tickets, notes and documents) and a release that scores worse does not ship.

## Beyond nodes

The same work makes room for AI around pipelines, each its own change on the backlog. The agents among them,
and the rules every agent keeps, are decided in [ADR 0052](0052-agents.md).

- **Explain a failed run** in plain words, from its record, with the built-in playbook as the
  fallback (ADR 0012).
- **Draft a pipeline from a description**, as a proposal a designer edits and saves; it grows
  from the pipeline suggestions that exist.
- **Suggest quality rules** for a table from its profile, as proposals.
- **Run tools on the MCP server**, so an assistant a customer already uses can start and watch
  runs with the caller's own role (with Optimise on the backlog).

## Consequences

- AI nodes are the most expensive nodes per row, so they are the ones the benchmarks
  (docs/design/benchmarks.md) measure for rows per second per model, and the run page shows
  their calls and cost.
- Search by meaning on customer data needs pgvector on the target; the install guide says so.
- Hosted providers make data leave the network: the administrator's choice per feature, recorded
  in the audit log, and refused for sensitive columns whatever the choice.
