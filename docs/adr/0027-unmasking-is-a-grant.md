# 0027 Seeing unmasked values is a grant of its own, held only by designers

Status: Accepted (31 October 2026)

## Context

Kilnmark masks the values of columns marked sensitive (`••••`) in profiles, samples, examples,
failing rows, previews, run errors and document text. Until now the right to see them unmasked came
with the role ladder: a role "at least" some level could unmask, so admins, who include every role
below them, always could.

That is the wrong shape. An admin's job is the server (Settings, tokens, connectors), not the data.
Data platforms that mask columns keep the two apart:

- **Snowflake**: a masking policy names who sees raw values, usually one custom role. It can stop
  ACCOUNTADMIN and SECURITYADMIN "from unnecessarily viewing data", and an object's owner cannot
  see masked columns. Policy administrators are kept apart from object owners (separation of duties).
- **BigQuery**: raw values need Fine-Grained Reader on the column's policy tag; Masked Reader sees
  the masked value. Granted sparingly and audited.
- **Databricks Unity Catalog**: a column mask is a function, usually gated on membership of a named
  group; the documentation names no exemption for admins.
- **SQL Server** is the exception: sysadmin and db_owner always see unmasked values, and Microsoft
  warns that masking alone does not secure sensitive data.

Data catalogues show the same pattern. Their roles are a ladder of what you may do with metadata,
and seeing values is a separate permission: OpenMetadata's `ViewSampleData` (left out of its
example analyst and read-only roles, given to a temporary break-glass role), Atlan's Purposes,
Purview's profile readers (statistics, not values). Kilnmark's roles map onto them so:

| Kilnmark | Alation | Microsoft Purview | DataHub | OpenMetadata |
| --- | --- | --- | --- | --- |
| viewer | Viewer, Explorer | Catalog Reader, Data Quality Reader | Reader | Data Consumer |
| steward | Steward | Data Steward, Data Quality Steward | Editor | Data Steward |
| designer | Composer | Data Product Owner, Data Profile Steward | (none: metadata only) | Data Engineer |
| admin | Server, Catalog or Source Admin | Data Governance Administrator | Admin | Admin |

Two differences matter to people coming from a catalogue. A catalogue's steward edits metadata
(descriptions, glossary, quality rules); Kilnmark's steward runs pipelines and looks after quality
but does not build. And catalogues have no exact designer, because they do not build pipelines.

## Decision

- **The four roles stay a ladder of actions:** viewer < steward < designer < admin. Each includes
  the ones below it, as before.
- **Seeing unmasked values is a grant, `unmask`, that no role includes.** It is a security role
  of its own (`AccessRole.UNMASK_GRANT`), and `Sights` (the one place that decides what a caller
  sees) asks for it rather than for a level on the ladder.
- **Only a designer token can hold it.** A designer token holds it unless created without it
  (`"unmask": false`); asking for it on a viewer, steward or admin token is refused (400), and the
  database refuses it too (a check constraint). To take it from a designer, revoke the token and
  create one without it.
- **Admins see masked values**, everywhere, including in Previews, in run errors and in document
  text. With sign-in off every caller is an admin, so every caller sees masked values.
- **A model never sees unmasked values** (the MCP server), whoever's token it uses, as before.
- **The Tokens page shows which tokens hold the grant** and who created each, and the server logs
  each token created with the grant and each mark cleared, with who did it.

## Consequences

- Designers build what transforms the data, and cannot do it blind, so they see it. Admins can run
  the server without seeing salaries.
- Admins still create tokens, so an admin can create a designer token and use it. The grant makes
  that a deliberate, visible act (the Tokens page, the log), not a side effect of being an admin.
  The audit log (planned under hardening) will record it durably.
- Clearing a sensitive mark stays an admin act: it is a governance decision, like changing a
  masking policy in Snowflake, and it is logged. An admin who clears a mark sees that column's values
  afterwards; the log line is the control until the audit log.
- Messages that said "ask an admin" about sensitive values now say "ask a designer".
- Tests that read unmasked values sign in with a designer token, not the admin token.
