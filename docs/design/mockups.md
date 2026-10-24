# UI mockups

The mockups live on a Claude design canvas, "Kilnmark UI Mockups", in two rows:

1. **Understand**: asset page; relationship and lineage explorer
2. **Act**: steward inbox; pipeline designer

## Design rules carried into the build

- Search first: a command palette (Ctrl+K) reaches everything.
- AI output is always labelled, shows its evidence and confidence, and waits for approval.
- Undo instead of confirmation dialogs, except for destructive actions.
- Light and dark themes from design tokens; Inter for UI text, JetBrains Mono for identifiers.
- Accessibility to WCAG 2.2 AA (see [ADR 0009](../adr/0009-accessibility.md)): text contrast 4.5:1,
  UI contrast 3:1 (form borders `#767D89`), visible focus, skip links, text alternatives for the
  graph and pipeline canvases, and non-drag ways to edit.

## Palette used in the mockups

| Token | Value | Use |
| --- | --- | --- |
| ink | `#15171C` | Text, sidebar |
| muted | `#5A6170` | Secondary text |
| border-control | `#767D89` | Inputs and controls (3:1) |
| ground | `#F5F6F8` | Page background |
| ember | `#C2410C` | Accent, primary actions |
| link | `#1D4ED8` | Links, focus ring |
| good / warn / bad | `#0F766E` / `#B45309` / `#B91C1C` | Trust and status |
