# Changelog

## 0.0.2 (24 October 2026)

The first working slice: point Kilnmark at PostgreSQL databases, scan them, and search what it
found.

- **Sources:** register PostgreSQL databases one at a time, or many at once from a CSV or JSON
  file (checked row by row, all or nothing, with a dry run). Passwords are encrypted with
  AES-256-GCM before they reach the database; the key lives outside it. Bulk files can refer to
  passwords as `env:VARIABLE` or `secret:NAME` instead of holding them.
- **Scans:** every table and view, with columns, types, comments and primary keys; each one read
  through the Kiln engine and profiled as it streams (rows, nulls, distinct values, ranges,
  lengths, most common values). Two tables at a time; one failing does not stop the scan. Assets
  that disappear from a source are marked removed.
- **Search:** tables, views and columns by name or description, tolerant of partial and misspelt
  words.
- **Web UI:** search from anywhere (Ctrl+K), sources with scan status, asset pages with every
  column's profile. WCAG 2.2 AA automated checks pass in light and dark themes.
- **Kiln engine:** steps on virtual threads joined by bounded Arrow row sets, per-step memory
  accounting, back-pressure, clean failure and leak reporting.
- **Platform:** Java 25 (ADR 0013), Business Source License 1.1 (ADR 0008), sign-in plan
  (ADR 0014). No sign-in yet: lab use only.

## 0.0.1 (24 October 2026)

Scaffolding: build, CI, containers, reference lab and decision records.
