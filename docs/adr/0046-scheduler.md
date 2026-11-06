# 0046 One built-in scheduler for pipelines and flows

Status: Accepted (6 November 2026)

## Context

Nightly loads and month-end closes must run without a person pressing Run now. ADR 0026 decided
that the scheduler is built in, with no external one, and left open who a run acts as when nobody
starts it. docs/design/flows.md section 5 said such a run acts as an identity someone records when
scheduling it, capped at a role. The owner decided that the scheduler serves pipelines as well as
flows.

Three things make a schedule different from what a pipeline or flow says:

- **Who a run acts as is a person's decision**, not the YAML author's: a designer who saves a flow
  should not, by saving it, decide that it runs every night with a role.
- **Changing a time is not a new version.** Versions record what a pipeline does, and a run names
  the version it ran.
- **A data load must not run twice** because two scheduler processes looked at once, or because the
  server came back after a night down and caught up every fire it had missed.

## Decision

1. **One scheduler, for pipelines and flows.** A schedule is a row of its own (`schedule`, V36),
   not part of the YAML. It names one pipeline or one flow; each may have several schedules.
   Deleting a pipeline deletes its schedules, in the same transaction, and records each one.
   Deleting a flow does the same ([ADR 0048](0048-deleting-a-flow.md), which came after).
2. **Designers and admins set schedules**: add, change, pause, resume and delete them (owner,
   6 November 2026), checked by the API's roles and again inside `ScheduleService`. Stewards read
   them with the values their runs start with; viewers read them without those values, as they
   never see the values a run starts with (flows.md section 5).
3. **A scheduled run acts as the schedule.** It is requested by `schedule <id> (set by <name>)`,
   where the name is whoever last set the schedule, with the role chosen for it: steward (the
   default) or designer. Never admin, and never above the role of the person saving it. The run
   is of the **latest version**, with the schedule's parameter values, through the same
   `queueRun` as Run now, so every check Run now makes applies. Today a designer-role and a
   steward-role scheduled run behave the same: a run's role is only checked as "steward or
   above". The role is recorded with each run for when that changes. The values are also checked when
   the schedule is saved, so a mistake is refused then, not at the first fire.
4. **Five-field Unix cron**, written in Kilnmark (no new dependency): minute, hour, day of the
   month, month, day of the week; `*`, lists, ranges, steps, month and day names, 0 and 7 for
   Sunday, the Vixie rule that when both day fields are restricted a day matching either fires,
   and `@hourly`, `@daily`, `@weekly`, `@monthly` and `@yearly`. No seconds field and no
   `@reboot`. Times are read in the schedule's zone, an IANA name (UTC by default). A local time
   that does not exist (the clocks go forward) fires at that time moved on by the gap; one that
   happens twice (the clocks go back) fires once, at the first. An expression that does not fire
   in the next ten years (`0 0 30 2 *`) is refused when saved.
5. **At most once.** On processes with the scheduler role, every
   `kilnmark.schedules.poll-interval` (30 seconds), one short transaction selects the enabled
   schedules that are due `FOR UPDATE SKIP LOCKED`, moves each one's next fire past now, marks it
   `starting` and commits. Only then is each run queued, and its outcome recorded. To hold:
   - **One clock.** Every next fire is worked out from the database's clock, never a process's own,
     and never from earlier than the schedule's last fire. Processes whose clocks differ cannot
     put back a fire already claimed.
   - **Saving locks the row first.** A change that keeps the times, zone and pausing keeps the
     next fire as it is, due or not, so editing a description never moves or drops a fire. A change
     to them works the next fire out again; if a fire was due and not yet claimed, it is recorded
     as `missed: changed while it was due`, never dropped silently.
   - **An outcome is written only onto the fire it belongs to**: not when the schedule was changed
     meanwhile to run something else, or changed while the fire was starting.

   Two schedulers never fire one schedule twice. A crash between the commit and the queue loses
   that fire: the outcome stays `starting`, and once it has been so for three poll intervals (at
   least five minutes) it reads `failed: the server stopped before the run was queued`. For loads
   of data, a fire lost and seen is safer than one run twice.
6. **Missed fires are not run late.** A fire due longer ago than `kilnmark.schedules.catch-up`
   (1 hour) when it is found, because the server was not running, is recorded as missed, and the
   next fire is worked out from now. A fire within the catch-up runs once, however many it
   covers.
7. **A busy pipeline or flow skips the fire.** If a run is already queued or running (one run at a
   time), the fire is skipped and the outcome says so; it is not queued for later. Outcomes are
   words a person reads: `starting` (for a moment), `started`, `skipped: a run was still going`,
   `missed: the server was not running`, `missed: changed while it was due` and
   `failed: <the server's message>`, which never holds secrets. Viewers see a failed fire only as
   `failed`, since a version's problems can be worked out from the run's values, and the log
   records only an outcome's first word at INFO, the reason at DEBUG.
8. **The scheduler keeps going.** Anything a tick throws, an `Error` too, is logged at ERROR and
   the next poll looks again. The readiness check `scheduler` is down when a process with the
   scheduler role has not looked for due schedules within three poll intervals and a minute.
9. **A schedule stops when whoever set it no longer holds its role** (owner, 6 November 2026).
   Who set a schedule is kept as an identity, not only a name: the API token they used, or, for a
   person signed in through the identity provider, an opaque key for them (a hash of the
   provider's issuer and their subject, so it names no address). Before every fire the setter must
   still hold at least the schedule's role:
   - a token must still work (not revoked, not expired, within the longest lifetime) and have the
     role;
   - a person must have it as the access review reads their sign-ins: their latest sign-in's role,
     unless a later sign-in was refused because none of their groups has a role any more. Other
     refusals (no second factor, say) take nothing away, as in the access review, so one sign-in
     on the wrong device does not stop a team's nightly work, however many there are. Sign-ins are
     taken in the order the database stored them, not by the times different servers' clocks gave
     them. That role is lowered to what the person's groups give under the group-to-role settings
     now, so changing the mapping takes effect at the next fire. For that, a sign-in records **only
     the groups the settings map to a role**, never the person's other groups: in a bank a group's
     name can say more than it should (a deal team behind an information barrier, an HR or health
     matter), and the audit log, the SIEM and the audit API would keep it for ever. Since the check
     only lowers a role, the mapped groups are all it needs. And their latest sign-in must be
     within `kilnmark.schedules.signed-in-within` (30 days): someone who has left and is disabled
     at the provider never signs in again, so their schedules stop after 30 days at most, saying
     `paused: the person who set it has not signed in for 30 days`.

   If not, nothing runs: the schedule is paused (off, no next fire), its outcome is
   `paused: the person who set it no longer has the role` (viewers see `paused`), and the audit
   log records `schedule.paused` with the reason. It is also paused at once, without waiting for a
   fire, when the token is revoked, or when a sign-in gives the person less of a role than a
   schedule of theirs (or none). That pause runs off the sign-in's thread and waits at most two
   seconds for a schedule a fire holds, so a sign-in never waits on it; the check before each fire
   is the guarantee. If the database is out when someone signs in with less of a role, that
   sign-in's event and its pause can both be lost, and their schedules then stand on the previous
   sign-in until the next one, or 30 days. The check and the queueing happen
   under the schedule's row lock, so a revoke or pause either comes before (nothing runs) or after
   the run is queued and recorded. A pause never cancels a run already queued or running. A designer or admin resumes it by saving it, which makes them its
   setter, checked against their own role. The server's environment admin token
   (`KILNMARK_ADMIN_TOKEN`) is a token like any other: when its value changes, Kilnmark sets the old
   token aside, and the schedules it set are paused at their next fire. A schedule saved while
   sign-in is off (a lab) runs only while sign-in is still off.
10. **Audited** (ADR 0029): `schedule.set` when one is added or changed, `schedule.deleted` when
   one is deleted, with what it runs, its cron, zone, role and whether it is on, and never its
   parameter values. A pipeline or flow deleted with schedules records `schedule.deleted` for exactly the
   schedules its own transaction deleted. A schedule paused because its setter lost the role
   records `schedule.paused`, with the reason. The runs themselves are recorded as runs requested by
   the schedule.

## Choices made without the owner, to change if wished

- **The name in `set by` is whoever last set the schedule**, not whoever added it, so a change
  makes the person changing it answerable for the runs that follow.
- **The last run is a foreign key that is set to null** when that run is deleted
  (`last_pipeline_run`, `last_flow_run`), rather than a plain number, so the schedule never links
  to a run that is gone. The table is small, so the keys need no index.
- **Pausing is a change** (`PUT` with `enabled` false), not an endpoint of its own. A paused
  schedule has no next fire; resuming works it out from then.
- **A schedule stops when its expression cannot be read** any more (a rule tightened later, a zone
  Java no longer knows): its next fire is cleared and the outcome says why.

## Consequences

- Nightly work runs without an external scheduler. The pages to set schedules follow in the next
  change; until then they are set through `/api/v1/schedules`.
- A scheduled run can do no more than a steward or designer could, whoever set it, and is answerable
  to a named person in the run's history and the audit log.
- A fire can be lost (a crash at the wrong moment, a server down for longer than the catch-up). The
  outcome, the run history and the server's log say so; nothing runs twice to make up for it.
- Event triggers (a run when a file lands, when another flow ends) are not part of this decision.
- A person who leaves the organisation's groups is known to Kilnmark only when they next sign in,
  or once their latest sign-in is 30 days old (Kilnmark is not told otherwise). API tokens are not
  linked to people: revoking a person's tokens stops the schedules set with those tokens, not those
  they set when signed in. Work that must outlive any one person is best set with a team's designer
  token, which stops only when it is revoked or expires.
- Follow-up: the access review does not list the schedules each token or person set. It groups
  people by name, while schedules key them by the person's key, so it must be keyed by person
  first.
