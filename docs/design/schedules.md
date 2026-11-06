# Schedules

Pipelines and flows that run at set times, without a person pressing Run now
([ADR 0046](../adr/0046-scheduler.md); ADR 0026 decided the scheduler is built in). This note says
how a schedule is stored, how it fires, and who its runs act as. The guide is
[Running pipelines and flows on a schedule](../guide/schedules.md).

## 1. The table (V36)

One row of `schedule` per schedule; a pipeline or flow may have several.

| Column | What it holds |
| --- | --- |
| `id` | A number of its own, shown in who a run was requested by |
| `pipeline`, `flow` | What it runs: exactly one is set. Each is a foreign key that cascades, so deleting a pipeline or flow deletes its schedules |
| `cron`, `zone` | When: five-field cron, read in an IANA zone (`UTC` by default) |
| `parameters` | The values each run starts with, as given; defaults are filled in at each run, from the version then latest |
| `run_as` | The role the runs have: `steward` (the default) or `designer`, never `admin` |
| `enabled` | Off while paused |
| `description` | A short line, at most 200 characters |
| `created_by`, `created_at`, `updated_by`, `updated_at` | Who added it and who last set it, and when |
| `next_at` | When it fires next; null while paused, or when it never fires again |
| `last_at`, `last_outcome` | When it was last due, and what happened, in words |
| `last_pipeline_run`, `last_flow_run` | The run the last fire started. Foreign keys set to null when the run is deleted, so the link never leads to a run that is gone |

`schedule_due_idx` on `next_at WHERE enabled` is what the scheduler reads. A schedule is not part
of the YAML: who a run acts as is the decision of the person who sets the schedule, not of the
YAML's author, and changing a time does not make a new version.

## 2. Who may do what

- **Designers and admins** add, change, pause, resume and delete schedules
  (`ScheduleResource`, `@RolesAllowed(designer)`).
- **Stewards** read them with their parameter values.
- **Viewers** read them without the values (`parameters` is null), as they never see the values a
  run starts with ([flows.md](flows.md) section 5), and see a failed fire's outcome only as
  `failed`. Values go through `Sights.data()`.
- Anyone signed in can try an expression (`POST /api/v1/schedules/preview`): the next five fire
  times, or the problem.

## 3. Who a run acts as

A scheduled run is requested by `schedule <id> (set by <name>)`, where the name is whoever last
set the schedule, with the schedule's role: steward or designer, never admin, and never above the
role of the person saving it. It runs the **latest version** with the schedule's values, through
`PipelineService.queueRun` or `FlowService.queueRun` exactly as Run now does, so every check Run
now makes applies: a version with problems, a missing value, a source that no longer allows
writing. The pipeline runs a scheduled flow starts carry the same name and role.

When a schedule is saved, its values are checked against the latest version
(`PipelineService.checkParameters`, `FlowService.checkParameters`), so a typo is refused at once
rather than at the first fire. The latest version may still change before the fire; the fire is
checked again then.

## 4. Cron

`Cron` is a small pure class: `Cron.parse(text)` and `next(after, zone)`, the first fire strictly
after `after`.

- Five fields: minute (0-59), hour (0-23), day of the month (1-31), month (1-12 or `JAN`-`DEC`),
  day of the week (0-7 or `SUN`-`SAT`; 0 and 7 are Sunday). Each takes `*`, a number, `a-b`,
  `*/n`, `a-b/n`, `a/n` (from a to the end) and lists joined by commas. Names in any case.
- `@hourly`, `@daily` (`@midnight`), `@weekly`, `@monthly`, `@yearly` (`@annually`). No seconds
  field and no `@reboot`: both are refused, saying so.
- **The two day fields** follow Vixie cron: when both are restricted (neither starts with `*`), a
  day matching either fires (`0 0 1 * MON`: the 1st, and every Monday). Otherwise a day must match
  both, so `*/2` in the day of the month with `MON` means odd days that are Mondays.
- **Daylight saving.** Times are local to the zone. A local time that does not exist (the clocks go
  forward) fires at that time moved on by the gap, as `ZonedDateTime.ofLocal` does: in London
  `30 1 * * *` fires at 02:30 BST on the last Sunday in March. A local time that happens twice (the
  clocks go back) fires once, at the first: the earlier offset, and the second is never strictly
  after it.
- **A search limit.** `next` looks ten years ahead (3,660 days) and returns null past that. Saving
  an expression that never fires (`0 0 30 2 *`) is refused. The 29th of February is found: from
  2096 the next is 2104.

## 5. Firing

![How a schedule fires: on the scheduler role, one short transaction finds the due schedules, locks each one, moves its next fire past now and commits; then the run is queued as Run now would queue it, acting as the schedule, and the outcome is recorded](../guide/images/schedule-fire.svg)

`Scheduler` runs on processes with the scheduler role, on a virtual thread, every
`kilnmark.schedules.poll-interval` (30 seconds). Each `tick(now)`:

1. **Claims**, in one short transaction (`ScheduleStore.claimDue`): `SELECT … WHERE enabled AND
   next_at <= now ORDER BY next_at LIMIT 100 FOR UPDATE SKIP LOCKED`; for each row, works out the
   next fire after now, and writes it with `last_at = now`, the last run cleared, and
   `failed: the server stopped before the run was queued` as the outcome until step 3 replaces it;
   then commits.
2. **Queues** each claimed run, outside that transaction.
3. **Records** the outcome and the run it started (`ScheduleStore.recordOutcome`).

A tick that claims a full batch of 100 is followed at once by another.

**At most once.** Two schedulers never fire one schedule twice: one holds the row's lock, and the
other skips it, or, once the first has committed, finds `next_at` past now. Saving a schedule
locks its row before working out `next_at`, so a change saved while a fire is claimed never puts
that fire back. A crash between the commit and the queue loses that fire, which is the safer
failure for loads of data: the outcome left by the claim says so, and nothing is written twice.

## 6. Outcomes

![What happens when a schedule is due: a fire due more than an hour ago is missed; a pipeline or flow with a run still going is skipped; a latest version that cannot run fails with the server's reason; otherwise the run starts. Either way the next fire is worked out from now](../guide/images/schedule-outcomes.svg)

| Outcome | When |
| --- | --- |
| `started` | The run was queued; `last_pipeline_run` or `last_flow_run` names it |
| `skipped: a run was still going` | One run at a time: the pipeline or flow already had a run queued or running (`AlreadyRunning`, a 409 for Run now). The fire is not queued for later |
| `missed: the server was not running` | The fire was due longer ago than `kilnmark.schedules.catch-up` (1 hour) when it was claimed. It does not run late |
| `failed: <message>` | The run could not be queued: the server's message, as Run now would show it (a version with problems, a missing value, a pipeline deleted since). Messages never hold secrets; a version's problems can be worked out from the run's values, so viewers see only `failed` |
| `failed: the server stopped before the run was queued` | Left by the claim: the process stopped before it queued the run. That fire is lost |

Within the catch-up a schedule runs once, however many fires it covers: the next fire is always
worked out from now. A schedule whose expression or zone cannot be read any more stops (its next
fire is cleared) and its outcome says why.

## 7. The API

`/api/v1/schedules`: `GET` (filters `pipeline=` and `flow=`), `GET {id}`, `POST`, `PUT {id}`
(replaces everything; `enabled` false pauses, true resumes and works the next fire out from now),
`DELETE {id}`, and `POST preview`. Each schedule shows `nextAt`, `lastAt`, `lastOutcome` and the
last run. Mistakes are 400 with `{"error": "..."}` naming the field: `cron:`, `zone:`, `runAs:`,
`parameters:`, `pipeline:` or `flow:`.

## 8. The audit log

`schedule.set` when a schedule is added or changed, and `schedule.deleted`, each on
`schedule <id>` with the pipeline or flow, cron, zone, `runAs` and `enabled`: never the parameter
values (ADR 0029). Deleting a pipeline records `schedule.deleted` for each schedule it took with
it, with `via: pipeline deleted`. The runs themselves are recorded as runs, requested by the
schedule.

## 9. Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.schedules.poll-interval` | `30s` | How often a process with the scheduler role looks for schedules that are due |
| `kilnmark.schedules.catch-up` | `1h` | A fire due longer ago than this when it is found is missed, not run late |

## 10. Not yet

- The pages to set and read schedules (the next change); until then, the API.
- Event triggers: a run when a file lands, or when another flow ends.
- **The role is checked when the schedule is saved, not at each fire.** A person whose token is
  revoked, or who loses a role, leaves their schedules running as `set by` them until a designer
  or admin changes or deletes them. The access review does not list schedules yet. Changing a
  schedule makes the person changing it its new `set by`.
