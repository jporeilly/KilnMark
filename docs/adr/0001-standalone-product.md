# 0001 Standalone product, built clean-room

Status: Accepted (22 October 2026)

## Decision

Kilnmark is a standalone product, independent of any Pentaho product. It uses no Pentaho code,
APIs, file formats or lab data, except Apache-licensed Kettle 10.2 code under ADR 0006.
Integration with other catalogs comes later and only through open standards (OpenLineage,
JSON-LD, CSV).

## Why

The founder's employment contract has been checked and is clear. Keeping the product clean-room
protects ownership and lets it be sold anywhere.

## Consequences

- Work only on personal hardware and accounts.
- Record the public source of any concept taken from another product.
- Code adapted from Kettle 10.2 is logged in [provenance.md](../legal/provenance.md).
