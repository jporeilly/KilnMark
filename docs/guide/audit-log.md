# The audit log

Kilnmark keeps an audit trail of who did what: every change made through the API, every request
refused for want of sign-in or a role, and, in more detail, the changes that decide who sees
sensitive values and what reaches a source. It is there for the controls regulated organisations
must show, such as an audit trail of changes and of access attempts, and it is the record ADR 0027
relies on for clearing marks and granting the unmask grant.

Admins read it under **Settings › Audit log**, or through `GET /api/v1/audit`. Nobody else can.

## What is recorded

Each event has when it happened, who did it (the token's name, and its number), what they did, what
it was done to, and a few details.

| Event | What it is | Details |
| --- | --- | --- |
| Token created (`token.created`) | An API token was made | Its name, role, whether it sees unmasked values, when it expires |
| Token revoked (`token.revoked`) | A token was revoked | Its name and role |
| Column marked sensitive (`mark.set`) | A column was marked | The column |
| Sensitive mark cleared (`mark.cleared`) | A mark was cleared: the column's values are no longer masked | The column |
| Source deleted (`source.deleted`) | A source and what was recorded about it were deleted | Its assets and runs, and whether it had marks (deleting it cleared them) |
| Allow writing changed (`source.allow_writing`) | Allow writing turned on or off | On or off, and how: in the form, or by an import |
| Allow SQL changed (`source.allow_sql`) | Allow SQL turned on or off | On or off, and how |
| Change made (`api.request`) | Any request that may change something (`POST`, `PUT`, `PATCH`, `DELETE`) and succeeded | The method and status; the event is on the request's path |
| Request refused (`api.refused`) | A request refused for want of a role (403), or for want of sign-in when it would have changed something (401) | The method and status |

A change is recorded as it is made, after it has succeeded. Only a request's method and path are
kept: never its query string or body, which can hold passwords, and never a token's secret.

## It cannot be changed

Events are only ever added. The database refuses to change or delete them, or to empty the table,
whoever asks, so a mistake in the server or an admin with database access cannot quietly rewrite
what happened. Back the table up with the rest of the database. If an event cannot be stored (the
database is unreachable, say), the change still stands and the server logs an error with the event,
so it is never lost silently.

The log grows with use: roughly one event for each change made through the API. A year of a busy
team is a few hundred thousand rows, which PostgreSQL holds easily.

## Sending it to your SIEM

Every event is also written, one JSON object a line, to the log category `kilnmark.audit`:

```json
{"at":"2026-11-01T19:30:00Z","actor":"jo","tokenId":12,"action":"source.allow_sql","target":"warehouse","detail":{"on":true,"via":"form"}}
```

To send them to your SIEM's syslog collector as well, set these on the server:

```sh
KILNMARK_AUDIT_SYSLOG_ENABLE=true
KILNMARK_AUDIT_SYSLOG_ENDPOINT=siem.example.com:514
KILNMARK_AUDIT_SYSLOG_PROTOCOL=tcp      # or udp, ssl-tcp
```

Or collect the container's own output, where the same lines appear, and filter on the category.

## Reading it

The page shows the newest events first, a hundred at a time; **Show older events** loads the next
hundred. **Show** narrows it to one kind of event. Through the API, `action` filters, `limit` sets
the page size (up to 500), and `before`, set to the `next` of the previous page, gives the page
after it.
