# The assistant

Status: step 1 (the documentation domain) built, 25 October 2026; steps 2 and 3 are design.
Replaces help buttons: people ask in a chat window, and the answer links to the documentation.

A chat window, open from anywhere in the app, answers questions about Kilnmark from what it
knows: its **knowledge domains**. Each answer is short, says where it came from, and links to
the exact section it used. The first domain is the documentation. Later domains add the
catalogue and quality results.

## Knowledge domains

A knowledge domain is a set of texts the assistant may answer from, with its own rules for who
may see it and which models may read it.

| Domain | What it holds | Who may ask | Models that may read it | When |
| --- | --- | --- | --- | --- |
| `docs` | The user guides and the changelog that ship with this build | Every signed-in user | Any model allowed by the server-wide rule | First |
| `catalogue` | Sources, assets, fields, profiles, quality scores, rule results and drift, through the existing search and quality APIs | As the API allows for the user's role | Per source, by its `ai_egress` setting, as everywhere else | Second |
| `pipelines` | Pipelines, their runs and versions | As the API allows | Per source of each node | With round 3 pipelines |

The documentation holds no customer data, so it only needs the server-wide rule
(`kilnmark.ai.hosted-models` and the admin setting). The question itself is the user's own text
and may contain data, so it follows the same server-wide rule. Catalogue answers filter every
source through `EgressPolicy`, exactly as search and ask do now.

## The documentation domain

- **One source of truth.** `docs/guide/*.md` and `CHANGELOG.md` are copied into the server jar
  and the UI bundle at build time. The in-app viewer (`#/docs/<page>/<section>`) and the
  assistant read the same files, so an answer never cites a page that is not in this build.
- **Sections, not pages.** At start-up the server splits each page at its `##` and `###`
  headings into sections with a stable id (`quality-rules/thresholds`), a title path ("Setting
  data quality rules › Thresholds"), the text, and a hash. Sections are stored in a
  `knowledge_section` table with a full-text vector and, when an embedding model is routed, an
  embedding. A section is re-indexed only when its hash changes, so upgrades re-index only what
  changed.
- **Retrieval.** Hybrid, as asset search already is: full-text match plus nearest embeddings,
  merged, top 6 sections, within a token budget. The page the user is on biases the ranking (on
  the Quality rules page, the rules guide comes first).
- **Answering.** A chat model gets the question, the sections with their ids, and an instruction
  to answer only from them in a few sentences, to cite each claim as `[n]`, and to say plainly
  when the documentation does not cover it. The server checks every citation against the
  sections it sent, drops any it did not send, and turns each into a link.
- **Without a chat model** the window still works: it shows the best matching sections as
  links with a snippet, like a search. Without an embedding model, retrieval is full-text only.

## The chat window

- A button in the header ("Ask", keyboard shortcut) opens a side panel on any page. The
  conversation stays while the user moves around the app, and is cleared on sign-out.
- Answers show their sources under the text as links. A link opens the viewer at that section
  in the main area, so the chat stays open beside it.
- Suggested questions for the current page, e.g. on a rule form: "What does Fail above mean?".
- A thumbs-up or down on each answer is recorded with the question and the section ids, so the
  documentation's gaps become visible.
- Accessible as the rest of the UI: a labelled dialog region, answers announced politely, full
  keyboard use.

## Running it

- A new AI feature `assistant` (purpose chat), routed by an admin under **AI models** like the
  others. The embedding route is the existing `embed` feature.
- Every call is recorded in AI usage under `assistant`, with the domains used.
- `POST /api/v1/assistant/ask` with `{question, page?, history?}` returns `{answer, citations[],
  domains[], model?}`. `GET /api/v1/assistant/sections?q=` is the search fallback. Both need a
  signed-in user.
- The MCP server gains a `search_docs` tool, so outside assistants can answer from the same
  documentation.
- An evaluation set of questions with the sections that answer them runs in
  `./gradlew :server:aiEval`, scoring retrieval (recall at 6) and citation validity.

## Build order

1. Documentation domain: bundling, sections, retrieval, the ask endpoint with checked
   citations, the chat window, the search fallback, usage, feedback. After the local model
   discovery work merges.
2. Catalogue domain, reusing search, ask and the quality API under each source's egress rule.
3. Pipelines domain, with round 3.

The assistant answers once and stops. Agents, which work in steps and end with a proposal a
person approves, are decided in [ADR 0052](../adr/0052-agents.md); the failed-run agent grows
from run diagnosis and its built-in playbook (ADR 0012).
