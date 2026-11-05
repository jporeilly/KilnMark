# 0043 A Library of samples and templates, apart from a team's own work

Status: Accepted (5 November 2026)

## Context

Kilnmark ships samples: small pipelines and flows, one idea each, that run on its synthetic sample
data (ADR 0024 keeps that data apart from the lab). Until now **Add samples** sat on the Pipelines
page and saved them there, among a team's own pipelines. On a server that also holds development
and production work, two dozen `sample-...` pipelines then share one list with the real ones, and
anyone reading the list has to tell them apart by name.

Kilnmark had one template, the starter a new pipeline opens with, and none for flows. Teams start
most work from the same few shapes: copy a table, load a file, check rows on the way, load what is
new, run pipelines in order.

The owner asked on 5 November 2026 for "a library of samples and templates", with the samples "on a
separate page so they don't get mixed up with dev / production pipelines and flows".

## Decision

1. **A Library page** in the navigation, after Flows, with two tabs: **Samples** and
   **Templates**. Add samples and the starter pack move there from the Pipelines page.

2. **Samples are marked, not moved.** An added sample is an ordinary pipeline or flow: it is
   saved, versioned, checked, run, scheduled and audited exactly as any other, so nothing in the
   engine, the API or the run history changes for it. A new column, `library`, on `pipeline` and
   `flow` (migration V33) marks one added from the samples (`sample`); it is NULL for a team's own.
   The Pipelines and Flows pages list only those without a mark and say how many samples are in
   the Library; the Library lists the samples with their last runs. The mark is given by the
   server when it adds a sample, never by the YAML, so no one can hide their own pipeline in the
   Library by writing a line, and saving a new version keeps it.

3. **Existing samples are marked by their fixed names.** Servers that added samples before this
   have them on the Pipelines page. The migration marks the pipelines named exactly as the samples
   bundled with 0.0.9 and `sample-tables`, never a pattern such as `sample-%`, so a team's own
   pipeline named `sample-something` is never moved.

4. **Sample flows are added too**, after the pipelines they run. A flow whose pipelines were not
   all added (a placeholder left out) is skipped, saying which pipeline it needs.

5. **Templates are bundled files, never added as they are.** They live in `samples/templates`,
   ship with the samples, and are listed by `GET /api/v1/templates`, even where an admin has
   turned the samples off: they hold no data and name no source. Each has `<...>` where a source,
   table or pipeline of one's own goes. **Use this template** opens it in the editor as a new
   pipeline or flow under a name the person gives; once saved it is theirs, unmarked, on the
   Pipelines or Flows page. Add samples never adds a template.

## Consequences

- A team's own work and Kilnmark's samples no longer share a list. The API is unchanged for
  callers that ignore `library`; the lists still return everything, marked.
- Home, notifications and the run pages still show sample runs as runs, as they are: a run is a
  run, and hiding some would make the run history disagree with the audit log.
- Moving a pipeline into or out of the Library by hand is not offered: there is no case for it yet.
  `PipelineStore.setLibrary` is the one place that sets the mark.
- Templates of a team's own ("save as template") are a later step, if asked for.
