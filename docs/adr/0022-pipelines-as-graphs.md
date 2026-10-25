# 0022 Pipelines run as graphs of steps with named ports

Status: Accepted (25 October 2026)

## Context

The Kiln runner takes steps in a line: each step's output is the next step's input. That was
enough for scanning, which reads and profiles. Pipelines need lookups and joins (two inputs),
unions (many inputs), and filters and routes that send rows to more than one place (several
outputs). ADR 0005 left the step interface open until real steps were built on it.

## Decision

- A pipeline is a directed acyclic graph. Steps have named input and output ports; edges join an
  output port to an input port. One output may feed several inputs, and the engine copies each
  batch to every reader so each step owns what it takes.
- Each edge is a bounded row set, as today: back-pressure and per-step memory accounting are
  unchanged. Steps that must hold all their input (aggregate, a lookup's lookup side) have their
  own memory limit and fail plainly when it is exceeded.
- `Step` keeps its `init` / `process` / `close` lifecycle. `StepContext` gains named ports and
  structured settings (`config()`), with the old `input()` / `output()` meaning the `in` and
  `out` ports, so existing steps keep working unchanged.
- Step types are described by `StepFactory`: ports, a settings schema, a check of the settings,
  and optionally the output columns for given input columns. The canvas builds its palette and
  settings panels from these, and validation catches unknown columns before a run.
- Pipelines are saved as YAML (`kilnmark: pipeline/v1`), naming saved sources rather than holding
  credentials.

## Consequences

- Scanning keeps the linear `Pipeline` and `Runner`, run as a graph internally.
- The contract types live in `io.kilnmark.kiln.graph` and `io.kilnmark.kiln` (`StepFactory`,
  `StepFactories`, `SettingSpec`, `StepKind`). Breaking changes to them after round 3 need a new
  ADR.
- A step with several outputs must cope with an unconnected port (its rows are dropped) and with
  a slow reader on one port holding up the others; the row set capacity bounds how far ahead any
  output can run.
- Expressions are one small language shared by every step (docs/design/pipelines.md, section 3),
  so it can later be pushed down to SQL where the source is a database.
