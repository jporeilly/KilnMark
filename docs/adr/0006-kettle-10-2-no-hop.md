# 0006 Clean engine, selective Kettle 10.2 code, no Apache Hop

Status: Accepted (24 October 2026)

## Decision

Kiln is a new engine. It may adapt specific Apache-licensed code from Kettle (Pentaho Data
Integration) **10.2 or earlier**, such as database dialects, value types and step logic. A
.ktr/.kjb importer converts supported Kettle jobs into Kiln pipelines in v0.3.
Apache Hop is not used.

## Licence facts (checked 24 October 2026 in github.com/pentaho/pentaho-kettle)

| Release line | Licence | Usable |
| --- | --- | --- |
| 4.3 to 10.2 | Apache 2.0 | Yes, with attribution |
| 10.3 Developer Edition onward (including 11.1) | Business Source License 1.1, no additional use grant | No |

## Rules

- Start from a tagged 10.2 (or earlier) release; never take a later commit, file or fix.
- Keep original licence headers; add the file to [provenance.md](../legal/provenance.md).
- Never use the names Kettle, PDI or Pentaho for Kilnmark or its parts.
- Public source only.

## Why not a full fork

A full fork brings OSGi/Karaf, the SWT designer and years of debt, frozen at a 2024 codebase.
