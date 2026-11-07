# 0050 AI in pipelines

Status: Accepted (6 November 2026); amended 7 November 2026: one node per task (ADR 0049, "Revised: about 55 nodes")

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

They also want inference: when a valve reports an error, say what is most likely wrong, from
that valve's history and what engineers wrote when they fixed it before. The row alone cannot
answer that; the model needs the right past records beside it, and a person must be able to see
which ones it used.

Other tools have settled on much the same set (research of 6 November 2026). The large data
platforms offer row-level AI functions in SQL: classify, extract to a schema, a yes-or-no filter,
a score for ranking, a summary across many rows, a general prompt and embeddings, with cheap
filters run before the model to cut calls. Visual pipeline tools add prompt and context-lookup
components, with lineage into the vector store. Two patterns from practice shaped this decision:
extraction that points back to where in the text each value was found, guided by a few worked
examples; and a pipeline that fetches an asset's recent history itself and then makes one model
call per row, rather than handing the work to an agent.

## Decision

**Amended 7 November 2026.** The tasks below become a node each (classify, extract, AI filter,
score, summarise, translate, ask, diagnose, detect personal data), beside `embed`, rather than one
`ai` node with a `task` setting. People look for them by name in the palette, each has settings of
its own, and `diagnose` and `ask` need context and limits that `translate` does not. Everything
else in this record holds for each of them as written for the `ai` node: local models by default,
never unmasked values, proposals with a confidence, flagged when unreadable, scored before it
ships, context from other records, findings kept, inferring never acting.

1. **Two nodes, coarse as ADR 0049 asks:**
   - **`ai`**, with a `task`:
     - `classify`: a label for each row from the categories given, with a confidence;
     - `extract`: named fields from free text into typed columns, by a schema the node declares
       and a few worked examples it carries. Each value comes with where it was found (its start
       and end in the text), so a person can see it in place and a value the text does not hold
       shows as invented. Long text is read in overlapping pieces, and in more than one pass when
       the node asks, since recall falls as text grows;
     - `filter`: yes or no to a question about each row ("does this complaint mention a
       refund?"), with a confidence, for a `route` or `filter` after it;
     - `score`: a number on a scale the node declares, for ranking ("how urgent, 0 to 100");
     - `summarise` and `translate`: text into shorter text, or another language;
     - `ask`: a question of the designer's own, answered to a schema the node declares, with worked
       examples, for what the named tasks do not cover;
     - `diagnose`: the most likely cause of a fault, alarm or incident, with a confidence and the
       evidence it used, by a schema the node declares (a cause, and optionally the next checks
       to make); it is the task that most needs context (decision 8);
     - `detect-personal-data`: which personal data a text holds (names, account numbers,
       addresses), with the patterns and checksum classifiers as the fallback and first pass.
   - **`embed`**: a text column into a vector column, for search by meaning on the customer's own
     data (written to PostgreSQL with pgvector, as Kilnmark's own embeddings are).

   And one across rows: the `aggregate` node gains `ai-summary`, one text for each group from its
   rows ("what customers in this region complained about this week"), read in pieces when a
   group is large, under the same rules.
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
   (synthetic tickets, notes and documents, and for `diagnose` synthetic faults with the
   engineers' notes and the confirmed cause) and a release that scores worse does not ship.
8. **Context from other records.** Any `ai` task can take a `context`: a table on a source the
   pipeline may read, and how to choose from it. Kilnmark chooses by keys and time (the 10 most
   recent records for the same asset), by meaning (the records closest to a column of the row,
   from a table an `embed` node wrote), or by keys first and then meaning (the closest records
   for the same valve type). It takes at most 5 by default and never more than 20. The chosen
   records go into the prompt marked as data, never
   instructions ([ADR 0052](0052-agents.md), decision 6). The answer must name the records it
   relied on, by key; those keys are written beside it, so a person can check the evidence and
   lineage shows which records informed the answer. The context table is read through `Sights`
   like any other read, so decision 3 holds for it too. The lookups count against the run's
   limits (decision 5), and the cache key includes the chosen records, so a changed history
   gives a fresh answer.
9. **Inferring is not acting.** A model's answer is data. What happens next is the pipeline's
   own, fixed when it is saved: a `route` or `gate` on the confidence, and a person for low
   confidence or for anything the customer marks critical. A model's answer alone never starts
   an action on equipment or money.
10. **Findings come back.** The confirmed cause a person records can be written beside the
   model's answer. That table feeds the context for later rows, and the run page shows how often
   the model's answer matched the confirmed one, so the customer sees its accuracy on their own
   data as well as the lab's score.
11. **An answer that cannot be read is never guessed.** An answer that does not fit the schema
   is asked for once more. If it still does not, the row gets empty answer columns, confidence 0
   and an `unreadable` flag, and goes where low confidence goes. Kilnmark never fills in a
   default: a "medium" priority made up after a failure looks like a real answer and hides that
   the model failed.
12. **Cheap work first.** The canvas suggests moving a `filter`, `dedupe` or `limit` that does
   not need the model's answer to before the `ai` node, so fewer rows reach the model. Before a
   run, the preview shows how many rows will reach each `ai` node and an estimate of the calls,
   tokens and time.
13. **A customer's own model or agent service** is called with the `http` node (ADR 0049),
   behind the allowed hosts an administrator sets, and is not built into Kilnmark. When the
   designer marks the host as a model service, the node keeps the rules of an `ai` node: its
   answer is held to a schema the node declares, is marked as a model's (decision 4), and a
   column marked sensitive is refused unless masked first (decision 3). Whatever the service
   does inside, to the pipeline it is one call per row with an answer, so the pipeline stays the
   same shape every run.

## An example: a valve's fault

![Inferring a valve's fault: engineer notes and past incidents are embedded once; for each error the closest records for that valve are chosen, the ai node's diagnose task gives a cause, a confidence and the records it used; high confidence drafts a work order, low confidence goes to an engineer, whose finding joins the history](../guide/images/ai-diagnose.svg)

1. An `embed` node turns the engineers' notes and past incidents into a table with vectors, once
   and then as new findings arrive.
2. A valve error arrives as a row: the code, the reading, the asset and the time.
3. The `ai` node with `task: diagnose` and a `context` on that table picks the 5 closest past
   records for the same valve type, and answers: "actuator seal wear, confidence 0.82, from
   incidents 4411 and 3902 and the note of 12 March".
4. A `route` sends answers of 0.8 or more to draft a work order, and the rest, and every critical
   asset, to an engineer with the evidence beside it.
5. The engineer's finding is written back: it joins the history the next error is compared with,
   and the confirmed causes become the measure of how often the model was right.

## Beyond nodes

The same work makes room for AI around pipelines, each its own change on the backlog. The
agents among them, and the rules every agent keeps, are decided in [ADR 0052](0052-agents.md).

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
  So does a `context`, which reads a table of vectors.
- A prompt with context is longer, so slower and dearer per row; the benchmarks measure
  `diagnose` with and without it.
- Hosted providers make data leave the network: the administrator's choice per feature, recorded
  in the audit log, and refused for sensitive columns whatever the choice.
