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
- **A model never sees unmasked values** (the MCP server, AI pipeline suggestions), whoever's token
  it uses: while a request answers a model, `Sights` never unmasks.
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
- Marks belong to the source that found the columns, so a second source over the same data would
  catalogue it unmarked. For folders and buckets, which the server reaches without a login of the
  person's own, a second source over the same files (one folder or prefix inside the other, links
  followed, case ignored, every name of AWS's own S3 service one endpoint) is refused for everyone,
  whether or not the first has marks yet, since marks can be set or carried there later. A second
  database source is not refused: adding it takes a login, and whoever holds that login can read
  the data directly, outside Kilnmark. Its scan would still show unmarked values to stewards, so
  the guide asks for one source per database, and the audit log will record who added each source.
  Two sources added at the same moment are not locked against each other.
- Deleting a source with marks clears them with its assets, so it is an admin's act, logged, as
  clearing a mark is.
- Custom SQL quality rules can read any column the source's login can, and even their counts of
  failing rows could tell a value bit by bit, so only designers with the grant write, change or run
  them; admins do not. Their rows and error text are withheld from everyone else, in views and in
  notifications, while any table of the source has a mark.
- Masking hides values, not every fact about them. Accepted, as in other masking systems: counts
  from a library rule on a marked column (how many rows lie in a range), and a pipeline that
  filters or routes on a marked column and writes only other columns (which rows matched). Both
  are built by people with roles that already act on the data, and each run is recorded with who
  asked; a preview of them stays refused without the grant.
- Messages that said "ask an admin" about sensitive values now say "ask a designer".
- Tests that read unmasked values sign in with a designer token, not the admin token.
