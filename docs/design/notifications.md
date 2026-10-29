# Notifications

How Kilnmark tells people that something needs attention, in the app and by email. The user guide
is [docs/guide/notifications.md](../guide/notifications.md).

## Goals

- One way for any code path to say "this happened", cheap and safe to call anywhere: a request,
  a scan worker, a quality job. The caller never waits for it and never fails because of it.
- In-app notifications for everyone by default; email opt-in, except admins' high-severity alerts.
- Never show a recipient a value their role could not see in the UI.
- Never send the mail password anywhere but where it was entered for, or unencrypted without an
  admin's leave.
- Little noise: one message per failing streak, a rate limit per address, daily digests.
- Never send on a request thread; survive restarts and mail-server outages without losing email.

## The event

`NotificationEvent(kind, severity, subjectUrn, title, detail, link, source, field,
restrictedDetail, dedupeKey, occurredAt)`, in `server/.../notify`. `NotificationPublisher` builds
events from the code that knows what happened and fires them as CDI events; every one of its
methods catches what goes wrong, so it never throws.

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
(`NotificationCleared`); a successful connection test clears only `connection:<source>`. So the
next failure is told.

**Pipelines hook.** `PipelineService.finished` calls `notifications.pipelineRunFinished(pipeline,
id, status, score, threshold, output, link)` wherever a run gets its final status on the worker.
`score` is the lowest measured score among the run's outputs (each write's, from the profile its
writer made), `threshold` the pipeline's `quality.min_score` for the version that ran, and `output`
the write node with that score; any of them is null when there is none, and then only a failure
can be told.

## Recording: off the caller's thread

Firing an event only puts it in a bounded in-memory queue (`Notifier`, 10,000). One thread per
process takes events in the order fired, so a rule's failure is always decided before its recovery,
and records each in one transaction on one connection (`NotificationStore.record`):

1. an advisory lock on the dedupe key, and `Dedupe.decide` against `notify_state`;
2. when told: the event, then what it touches that is marked sensitive (its asset's columns, and
   whether its source has any), looked up once;
3. the in-app notifications, in one `INSERT ... SELECT` over the people who want it, with the detail
   chosen per role by a `CASE`;
4. the queued emails, for subscribers who want it and may get it.

Events still queued at shutdown get five seconds; a full queue drops an event with a warning. The
earlier synchronous design inserted per person and opened a second connection per person for the
marks, which could exhaust a small pool and slow scans.

## Dedupe

`Dedupe.decide(kind, severity, state)` is pure. Per dedupe key, `notify_state` keeps whether a
problem is open and the severity told:

- *opens*: told when nothing is open, or when the severity rises (a warning becomes a failure);
- *closes*: told only when something is open, at the open severity, so the same people hear of
  the recovery; then closed;
- *once*: told the first time the key is seen (keys are unique per occurrence).

States whose rule or source was deleted are purged with the rest.

## Recipients and defaults

People are API tokens (until OpenID Connect sign-in, ADR 0014); team addresses are rows without a
token. **In the app**, a person is a token that has asked for its notifications lately
(`notify_person`, refreshed at most hourly; the app asks for the unread count when it opens), is
not revoked or expired, and is not marked as a local AI assistant's: scripts that never open the
app get no rows. `Subscription.defaults(role)`: every kind in the app for everyone; by email,
admins get every kind but `issue_opened` at high severity at once, as soon as they give and
confirm an address, and everyone else nothing until they opt in. In-app choices are per kind (any
severity); email choices are kinds, least severity, immediate or daily digest at an hour in the
server's time zone; both are limited by an optional list of sources (left out of an update, it
keeps its value; `allSources` clears it).

**Addresses.** Read strictly by Jakarta Mail as exactly one address, no display name, lower-cased
(so the rate limit counts one address once). A person's address gets nothing until confirmed: a
24-byte code, its SHA-256 kept for 7 days, sent as a link (with the base URL) and as text. Admins
can turn confirmation off. Allowed email domains, when listed, apply to people's and team
addresses: outside ones are refused when saved, and existing ones are paused (`paused = 'domain'`)
when the list changes, not deleted. A revoked or expired token gets nothing.

## Masking

Event titles and details hold names, counts and shares only. `restrictedDetail` (what a database
said when a scan or connection test failed, a rule's could-not-run reason; any of which can name
hosts and users or quote the data) is shown only where `Exposure.restricted` allows: the role may
see values of `field` by `Sights.recipient(role, marks)` (the quality views' rule, never unmasked,
so never a sensitive column's, even to admins); and when it concerns a whole asset or source that
has marks, only admins. In-app notifications are written per role at event time; emails per
recipient at send time (`MailMessages`), with the marks looked up once per event. Team addresses
are written for a viewer. Email addresses never appear in the feed; team addresses appear by name,
to admins only.

## The password

`MailPolicy` holds the rules, each tested on its own:

- A stored password goes only to the server, port, security and user name it was saved with.
  Saving a change to any of them without a new password is refused (or, with no user name left,
  removes the password).
- The configured password (`KILNMARK_MAIL_PASSWORD`) is used only while the server and user name in
  force equal `kilnmark.mail.host` and `.username`.
- Security none with a user name is refused unless an admin turned on "allow sign-in without
  encryption", and even then only for a password entered in the UI; `SmtpMailer` checks again
  before connecting. Turning it on is logged with the admin's name.
- Only the PLAIN and LOGIN mechanisms are offered (never NTLM or XOAUTH2); `mail.debug` is off.
- Every text from a mail server is scrubbed of the password and user name, as typed and in base64,
  and of the AUTH PLAIN token, before it is logged, stored or returned.

## Settings and locking

`mail_settings` holds the server, the password (sealed with `SecretBox`, write-only in the API),
and the rules: allow plain sign-in, allowed domains, confirmation, maximum attempts (1 to 10),
the hourly limit per address (1 to 1,000), and how long an unsent email waits (1 to 168 hours)
before it goes to the digest or is dropped. `kilnmark.mail.*` and `kilnmark.notifications.*` are
the defaults until an admin saves. Settings listed in `kilnmark.mail.locked` always come from the
configuration: the API refuses a different value, and the UI shows them read-only. An unknown name
there stops the server.

## Sending

`MailWorker` runs on the worker role, on a thread of its own. Each pass: requeue emails a stopped
worker left in `sending` (after 10 minutes; indexed); give up queued ones already tried the
maximum; move emails unsent past the undelivered time to their recipient's digest, or drop them
(this runs while email is paused too); then claim due immediate deliveries one at a time
(`FOR UPDATE SKIP LOCKED LIMIT 1`, so a slow server holds one row), check the recipient is still
reachable, wants email, is confirmed and inside the allowed domains, apply the rate limit
(the excess moves to the digest), and send; then each subscriber's due digest as one email. Every
change to a claim checks `locked_by` and `status = 'sending'`, so a worker never marks another's
claim. One delivery that throws is marked failed and the pass goes on. Failures retry after 1, 3,
9, 27 minutes, then hourly, up to the maximum; a 5xx refusal is final. Every attempt goes to
`notify_log`. Delivery is at least once: a worker stopped between the server's acceptance and the
`sent` mark sends again after the stale time.

Email uses Jakarta Mail (Eclipse Angus, version from the Quarkus platform), not the Quarkus mailer,
because the mail server is chosen at run time and kept in the database. Test and confirmation
emails go on virtual threads; the test endpoint returns a `CompletionStage`.

## The in-app feed contract

Built against by the home page's Notifications panel; additions only from here on.

- `GET /api/v1/notifications?limit=N&unread=true|false` returns
  `{"items": [{"id", "kind", "severity", "title", "detail", "link", "createdAt", "read",
  "channels": [{"type": "in_app"}, {"type": "email", "status", "at"}], "teamEmails": [{"name",
  "status"}]}], "unread": n, "more": true}`. `id` is text. `kind` is one of the ids above;
  `severity` is `high`, `medium`, `low` or `info` (recoveries show as `info`). The email channel
  appears when an email about the event was meant for the caller: `sent`, `queued` (including while
  being sent), `failed` or `skipped` (they turned email off, their address was not confirmed or
  was paused, their token stopped working, or it was dropped after the undelivered time), with
  `at` the time it finished or is due. `teamEmails` is present for admins only.
- `POST /api/v1/notifications/read` with `{"ids": [...]}` or `{"all": true}` returns 204; ids that
  are not the caller's are ignored.
- `GET /api/v1/notifications/unread-count` returns `{"unread": n}`. The count stops at 99: from
  100 unread it returns `{"unread": 99, "more": true}` (`more` is absent otherwise), counted as
  `count(*) FROM (... LIMIT 100)` on the partial index `notify_inbox (token_id, created_at DESC,
  id DESC) WHERE NOT read`. The full feed has its own index on the same columns.

With sign-in off there is no person: the feed is empty and choices cannot be saved.

## Retention

`NotificationRetention` (scheduler role, every six hours): in-app notifications after
`in-app-retention` (90 days); log entries, closed dedupe states, and events with nothing queued and
no in-app notification left after `log-retention` (30 days); states of deleted rules and sources.

## Schema and migration order

Migration V13 (`mail_settings`, `notify_subscriber`, `notify_event`, `notify_state`,
`notify_inbox`, `notify_person`, `notify_delivery`, `notify_log`) refers only to V1, V4 and V6
(`api_token.local_assistant`), never to V12 (pipelines), so a fresh database applies V1 to V13 in
order and a database at V12 takes V13 on top. Flyway runs with its default (no out-of-order
migrations): the order is the release order.

## Not done yet

- An unsubscribe link (`List-Unsubscribe`) that works without signing in.
- A stale-job recovery that fails a scan for good (`JobQueue.recoverStale`) is not told; a scan
  that fails or throws on its last attempt is.
- Per-source owners: people choose sources themselves; there is no "stewards of this source" yet.
- Events still in the in-memory queue when a process dies are lost (at most a few, as each is
  recorded in milliseconds).
