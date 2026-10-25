# Notifications

How Kilnmark tells people that something needs attention, in the app and by email. The user guide
is [docs/guide/notifications.md](../guide/notifications.md).

## Goals

- One way for any code path to say "this happened", cheap and safe to call anywhere: a request,
  a scan worker, a quality job.
- In-app notifications for everyone by default; email opt-in, except admins' high-severity alerts.
- Never show a recipient a value their role could not see in the UI.
- Little noise: one message per failing streak, a rate limit per address, daily digests.
- Never send on a request thread; survive restarts and mail-server outages without losing email.

## The event

`NotificationEvent(kind, severity, subjectUrn, title, detail, link, source, field,
restrictedDetail, dedupeKey, occurredAt)`, in `server/.../notify`. `NotificationPublisher` builds
events from the code that knows what happened and fires them as CDI events; it never throws.

| Kind (`id`) | Fired from | Dedupe key | Repeats |
| --- | --- | --- | --- |
| `rule_failed` | `QualityService` after each rule result that is not a pass | `rule:<id>` | opens |
| `rule_recovered` | the same, a pass after a non-pass | `rule:<id>` | closes |
| `issue_opened` | `QualityStore.openRuleIssue` / `openDriftIssue` when the upsert inserted (`xmax = 0`) | `issue:<id>` | once |
| `drift` | `QualityService.analyse`, comparing with the previous version | `drift:<versionId>` | once |
| `scan_failed` | `ScanFinished` (status failed), and `JobWorker` when a scan's last attempt throws | `scan:<source>` | opens |
| `connection_failed` | `SourceResource.test` and import batches (saved sources only) | `connection:<source>` | opens |
| `pipeline_failed` | `NotificationPublisher.pipelineRunFinished` (see below) | `pipeline:<name>` | opens |
| `pipeline_low_quality` | the same, a success scored below threshold | `pipeline-quality:<runId>` | once |

A successful scan clears `scan:<source>` and `connection:<source>` quietly
(`NotificationCleared`), and so does a successful connection test for the latter, so the next
failure is told.

**Pipelines hook.** The pipelines change (branch r3d-service) wires its runs with one line where
`PipelineService` writes a run's final status:

```java
notifications.pipelineRunFinished(run.pipeline(), id, o.status(), score, threshold, link);
```

with `NotificationPublisher notifications` injected. Nothing in this change touches pipeline code.

## Dedupe

`Dedupe.decide(kind, severity, state)` is pure. Per dedupe key, `notify_state` keeps whether a
problem is open and the severity told:

- *opens*: told when nothing is open, or when the severity rises (a warning becomes a failure);
- *closes*: told only when something is open, at the open severity, so the same people hear of
  the recovery; then closed;
- *once*: told the first time the key is seen (keys are unique per occurrence).

`NotificationStore.record` takes an advisory lock on the key, decides, writes the state and, when
told, the event, every person's in-app notification and the queued emails, in one transaction.

## Recipients and defaults

People are API tokens (until OpenID Connect sign-in, ADR 0014); team addresses are rows without a
token. `Subscription.defaults(role)`: every kind in the app for everyone; by email, admins get
every kind but `issue_opened` at high severity at once, as soon as they give an address, and
everyone else nothing until they opt in. In-app choices are per kind (any severity); email choices
are kinds, least severity, immediate or daily digest at an hour in the server's time zone; both are
limited by an optional list of sources. A revoked or expired token gets nothing.

## Masking

Event titles and details hold names, counts and shares only. `restrictedDetail` (a connection
error, a rule's could-not-run reason, which may quote the data) is shown only where
`Sights.recipient(role, marks).values(field)` is true: the quality views' rule for that role,
without unmasking, so never for a column marked sensitive, even to admins. In-app notifications
are written per person at event time (`InAppFeed`); emails per recipient at send time
(`MailMessages`). Team addresses are written for a viewer. Email addresses never appear in the
feed; team addresses appear by name, to admins only.

## Sending

`MailWorker` runs on the worker role, on a thread of its own. Each pass: requeue emails a stopped
worker left in `sending` (after 10 minutes); claim due immediate deliveries
(`FOR UPDATE SKIP LOCKED`); check the recipient is still reachable and wants email; apply the rate
limit (`RateLimit`, 20 an hour per address by default; the excess moves to the next digest); send;
then claim and send each subscriber's due digest as one email. Failures retry after 1, 3, 9, 27
minutes, then hourly, up to `max-attempts` (5); a 5xx refusal is final. Every attempt goes to
`notify_log`. While email is not set up or is paused, deliveries wait.

Email uses Jakarta Mail (Eclipse Angus, version from the Quarkus platform), not the Quarkus mailer,
because the mail server is chosen at run time and kept in the database. `mail.debug` is always off
(it would log the sign-in). Settings: `mail_settings`, one row; the password sealed with
`SecretBox`, write-only in the API, never logged; `kilnmark.mail.*` are defaults until an admin
saves. The test email runs on a virtual thread and the endpoint returns a `CompletionStage`.

## The in-app feed contract

Built against by the home page's Notifications panel; additions only from here on.

- `GET /api/v1/notifications?limit=N&unread=true|false` returns
  `{"items": [{"id", "kind", "severity", "title", "detail", "link", "createdAt", "read",
  "channels": [{"type": "in_app"}, {"type": "email", "status", "at"}], "teamEmails": [{"name",
  "status"}]}], "unread": n}`. `id` is text. `kind` is one of the ids above; `severity` is `high`,
  `medium`, `low` or `info` (recoveries show as `info`). The email channel appears when an email
  about the event was meant for the caller: `sent`, `queued` (including while being sent),
  `failed` or `skipped` (they turned email off, or their token stopped working, before it went),
  with `at` the time it finished or is due. `teamEmails` is present for admins only.
- `POST /api/v1/notifications/read` with `{"ids": [...]}` or `{"all": true}` returns 204; ids that
  are not the caller's are ignored.
- `GET /api/v1/notifications/unread-count` returns `{"unread": n}`, an index-only count on
  `notify_inbox (token_id, read, created_at)`.

With sign-in off there is no person: the feed is empty and choices cannot be saved.

## Retention

`NotificationRetention` (scheduler role, every six hours): in-app notifications after
`in-app-retention` (90 days); log entries, closed dedupe states, and events with nothing queued and
no in-app notification left after `log-retention` (30 days).

## Schema and migration order

Migration V13 (`mail_settings`, `notify_subscriber`, `notify_event`, `notify_state`,
`notify_inbox`, `notify_delivery`, `notify_log`) refers only to V1 and V4. V12 is taken by the
pipelines change, which may be released after this one. Flyway applies V13 to a database at V11
without complaint, but a database at V13 then refuses V12 ("Detected resolved migration not
applied to database: 12") unless out-of-order migrations are allowed, so
`quarkus.flyway.out-of-order=true` is set. Both migrations apply in either order.

## Not done yet

- An unsubscribe link (`List-Unsubscribe`) that works without signing in.
- A stale-job recovery that fails a scan for good (`JobQueue.recoverStale`) is not told; a scan
  that fails or throws on its last attempt is.
- Per-source owners: people choose sources themselves; there is no "stewards of this source" yet.
