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
   Deleting the pipeline or flow deletes its schedules (a foreign key that cascades).
2. **Designers and admins set schedules**: add, change, pause, resume and delete them (owner,
   6 November 2026). Stewards read them with the values their runs start with; viewers read them
   without those values, as they never see the values a run starts with (flows.md section 5).
3. **A scheduled run acts as the schedule.** It is requested by `schedule <id> (set by <name>)`,
   where the name is whoever last set the schedule, with the role chosen for it: steward (the
   default) or designer. Never admin, and never above the role of the person saving it. The run
   is of the **latest version**, with the schedule's parameter values, through the same
   `queueRun` as Run now, so every check Run now makes applies. The values are also checked when
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
   schedules that are due `FOR UPDATE SKIP LOCKED`, moves each one's next fire past now and
   commits. Only then is each run queued, and its outcome recorded. Two schedulers never fire one
   schedule twice, and saving a schedule locks its row before working out its next fire, so a
   change never puts back a fire just claimed. A crash between the commit and the queue loses that
   fire, and the outcome the claim left says so: for loads of data, a fire lost and seen is safer
   than one run twice.
6. **Missed fires are not run late.** A fire due longer ago than `kilnmark.schedules.catch-up`
   (1 hour) when it is found, because the server was not running, is recorded as missed, and the
   next fire is worked out from now. A fire within the catch-up runs once, however many it
   covers.
7. **A busy pipeline or flow skips the fire.** If a run is already queued or running (one run at a
   time), the fire is skipped and the outcome says so; it is not queued for later. Outcomes are
   words a person reads: `started`, `skipped: a run was still going`,
   `missed: the server was not running` and `failed: <the server's message>`, which never holds
   secrets. Viewers see a failed fire only as `failed`, since a version's problems can be worked
   out from the run's values.
8. **Audited** (ADR 0029): `schedule.set` when one is added or changed, `schedule.deleted` when
   one is deleted, with what it runs, its cron, zone, role and whether it is on, and never its
   parameter values. A pipeline deleted with schedules records `schedule.deleted` for each. The
   runs themselves are recorded as runs requested by the schedule.

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
- **Known gap:** the role is checked when a schedule is saved, not at each fire. Someone whose
  token is revoked or whose role is lowered leaves their schedules running, still `set by` them,
  until a designer or admin changes or deletes them; the access review does not list schedules
  yet. Checking the person's current role at each fire needs a person's identity that outlives one
  token, which tokens do not have today.
