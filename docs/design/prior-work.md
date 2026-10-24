# Lessons from earlier Trusted Data Operations work

The author built a working Trusted Data Operations example in October 2026, on another
data-integration tool. Kilnmark takes **ideas only** from it: no code, files, names or data are
copied, and no customer is named (see [ADR 0001](../adr/0001-standalone-product.md)). Each idea
below is restated in Kilnmark's own terms, with the release it belongs to.

| Idea | What it means for Kilnmark | Release |
| --- | --- | --- |
| Evidence on every run | Each run writes one record, success or failure: counts in and out, rejected rows, freshness, rule results, reconciliation and an outcome code. Kilnmark's `Run` node carries the same | v0.1 |
| Named outcome codes | `OK`, `STALE`, `EMPTY_RUN`, `SCHEMA_DRIFT`, `SOURCE_UNAVAILABLE`, `CONNECTION_LOST`, `FAILED`. An empty run is never reported as success | v0.1 |
| Claims proven by scripts | Each promise the product makes has a script that stages the failure and checks the result. The regression suite works this way on the reference lab | v0.1 |
| Facts-only AI with a fallback | The assistant is given only what a person could see, answers in a fixed JSON shape with its confidence and the facts it used, and a built-in playbook answers when no model is running. Answers are cached. See [ADR 0012](../adr/0012-ai-runtime.md) | v0.1 (pattern), v0.4 (diagnosis) |
| Model chosen for the machine | Pick an installed chat model from a preferred list, never an embedding model; warm it up at start; report tokens per second | v0.1 |
| Profiler proposes, steward confirms | Pattern and checksum checks propose a classification with evidence; the steward confirms, raises or keeps it with a reason | v0.2 |
| Contract sign-off by fingerprint | A contract is approved exactly as it stands; any change puts it back to pending, and restoring an approved version makes it approved again | v0.2 |
| Rejected-row review | Per run and rule, the steward decides: fix at source, the rejection is right, or change the rule | v0.2 |
| One decision log | Every steward decision, with who, why and when, in one list for auditors | v0.2 |
| Incidents from evidence | A feed's first failed or stale run opens an incident and its next good run closes it; people acknowledge and resolve; routing names who acts first | v0.3 |
| Declared versus observed lineage | Draw what a run actually read and wrote beside what its contract declares, column by column | v0.5 |
| Impact analysis | If this source column changes, which contracts, rules and targets are affected | v0.5 |
| Lost-row check | Rows sent to a target that never landed there are named, not just counted | v0.3 |

The same work showed traps worth designing around: an empty source that runs cleanly, a NULL in a
metadata query changing a value's inferred type, and steps that silently ignore configuration.
Kilnmark's step interface should fail loudly on each.
