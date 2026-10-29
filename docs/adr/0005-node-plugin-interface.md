# 0005 Node plugin interface stays provisional until sprint 3

Status: Accepted (24 October 2026)

## Decision

`io.kilnmark.kiln.Node` (init, process, close) and `NodeContext` are provisional. They are frozen
only after three different connectors (JDBC, file, object store) have been built on them in
sprint 3.

## Why

Every later node depends on this interface. Freezing it before real connectors exist risks
rewriting all of them.

## Consequences

- Breaking changes to the interface are allowed until the end of sprint 3, then need a new ADR.
- Node settings are described by a schema, so the UI can generate each node's settings panel.
