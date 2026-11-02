# 0029 An append-only audit log in the server's own database

Status: Accepted (1 November 2026)

## Context

Kilnmark is meant to run inside banks and other regulated organisations, in the EU and in US
federal and state settings. Their rules ask for an audit trail they can show to an examiner:

- **NIST SP 800-53 (and FedRAMP)**: AU-2 (decide which events are logged), AU-3 (what a record
  holds: what, when, where, who, outcome), AU-9 (protect the records from change and deletion),
  AU-6 (review them, usually in a SIEM).
- **NYDFS 23 NYCRR Part 500**: audit trails designed to detect and respond to cybersecurity events,
  and access privileges that are limited and reviewed.
- **DORA** (EU 2022/2554): logging of ICT-related events as part of an ICT risk-management
  framework, and evidence for the financial entity's own audits and its supervisors.

ADR 0027 also relies on a record of who granted the unmask grant and who cleared a mark. Until now
those went only to the server's log, which an operator can rotate away and nobody can query.

## Decision

1. **One table, `kilnmark.audit_event`, in the server's own database (V28).** Each row: when, who
   (the principal's name and the token's number), the action, what it was done to, and a small
   JSON detail (for requests: method, status and the caller's address). Rows are only ever added: a
   trigger refuses `UPDATE`, `DELETE` and `TRUNCATE`. The server's own login owns the table and
   could switch the trigger off, so the SIEM's copy is the one nothing in Kilnmark can change.
2. **Two kinds of event.** Every request that may change something and succeeded (`api.request`),
   and every request refused for want of a role, or for want of sign-in on a change
   (`api.refused`), from a response filter, so nothing new escapes the log by being forgotten. And
   events of their own, with what changed, for what decides who sees sensitive values and what
   reaches a source: tokens created (with the grant) and revoked, marks set and cleared, sources
   deleted, Allow writing and Allow SQL turned on or off (from the form or an import).
3. **Never a body or a query string**: only the method, path and status, so no password or token
   secret is ever kept.
4. **Stored in the background, never in the request.** The JSON line is written at once; the row is
   written by one thread of the audit log's own, in order, from a queue of at most 10,000. A
   refusal can be answered on the event loop, where a database call must not run, and no request
   should wait for its own audit row. Refusals are kept at most 30 a minute for each caller (each
   token, and everyone not signed in together), with a count of the rest, and request events never
   take the last 1,000 places in the queue, so a flood cannot keep a token, mark or source event
   out: when even those places are taken, such an event is stored on the caller's thread. The
   queue drains as the server stops, before its database closes. Rows are in the order recorded;
   read them by `id`.
5. **The change stands if its event cannot be stored**, and the server logs an error. Refusing the
   change would let a database hiccup stop work; the JSON line keeps the event for the SIEM.
6. **Every event is also a JSON line on the `kilnmark.audit` log category**; the `siem` profile
   sends that category to a syslog collector (`KILNMARK_AUDIT_SYSLOG_ENDPOINT`).
7. **Admins read it** (`GET /api/v1/audit`, Settings › Audit log). Nobody else does: it names
   people, sources and assets.

## Consequences

- An examiner can be shown who granted the unmask grant, who cleared a mark, and who turned on
  Allow SQL, from the product itself, and the bank's SIEM has the same events.
- The table grows without limit. A retention rule (delete events older than a set age, itself
  recorded) needs the trigger to allow one exception; it comes when a customer's retention period
  is known, as regulators set different ones.
- Reading data is not yet an event: previews and run detail viewed by designers with the grant are
  not recorded. That is the next step, with sign-in events when sign-in through an identity
  provider (OIDC) arrives.
- The server's login, or a superuser of the database, can still disable the trigger. Protection
  against that is the SIEM's copy, which the server cannot change. A separate owner role for the
  table, with the server granted only `INSERT` and `SELECT`, would close it for the server's login;
  it needs the customer's database administrator, so it is documented, not done.
- Events waiting to be stored when the process dies are lost from the table (not from the SIEM).
- The MCP endpoint's refusals are made before the REST layer and are not recorded yet.
