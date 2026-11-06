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
| `pipeline`, `flow` | What it runs: exactly one is set. Each is a foreign key that cascades. Deleting a pipeline deletes its schedules in its own transaction (`PipelineStore.deleteWithSchedules`, `DELETE … RETURNING`) and records each. Flows cannot be deleted yet; when they can, the cascade takes their schedules |
| `cron`, `zone` | When: five-field cron, read in an IANA zone (`UTC` by default) |
| `parameters` | The values each run starts with, as given; defaults are filled in at each run, from the version then latest |
| `run_as` | The role the runs have: `steward` (the default) or `designer`, never `admin` |
| `enabled` | Off while paused |
| `description` | A short line, at most 200 characters |
| `created_by`, `created_at`, `updated_by`, `updated_at` | Who added it and who last set it, and when |
| `next_at` | When it fires next; null while paused, or when it never fires again |
| `last_at`, `last_outcome` | When it was last due, and what happened, in words (`starting` while its run is being queued) |
| `last_pipeline_run`, `last_flow_run` | The run the last fire started. Foreign keys set to null when the run is deleted, so the link never leads to a run that is gone |

`schedule_due_idx` on `next_at WHERE enabled` is what the scheduler reads. A schedule is not part
of the YAML: who a run acts as is the decision of the person who sets the schedule, not of the
YAML's author, and changing a time does not make a new version.

## 2. Who may do what

- **Designers and admins** add, change, pause, resume and delete schedules
  (`ScheduleResource`, `@RolesAllowed(designer)`, and again in `ScheduleService.requireSetter`).
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

Today a designer-role and a steward-role scheduled run behave the same: a run's role is only
checked as "steward or above". The role is recorded with each run for when that changes.

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
`kilnmark.schedules.poll-interval` (30 seconds). Each `tick()`:

1. **Claims**, in one short transaction (`ScheduleStore.claimDue`), with now read from the
   database's clock: `SELECT … WHERE enabled AND next_at <= now ORDER BY next_at LIMIT 100 FOR
   UPDATE SKIP LOCKED`; for each row, works out the next fire after now, or after its last fire if
   that is later, and writes it with `last_at = now`, the last run cleared, and `starting` as the
   outcome; then commits.
2. **Queues** each claimed run, outside that transaction.
3. **Records** the outcome and the run it started (`ScheduleStore.recordOutcome`), only while the
   row still runs the same pipeline or flow and still says `starting`.

A tick that claims a full batch of 100 is followed at once by another. Tests call
`tick(Instant)` to fix the clock.

**At most once, and why it holds.**

- **Between schedulers:** one holds the row's lock and the other skips it; once the first has
  committed, `next_at` is past now.
- **Between a scheduler and a save:** saving (`ScheduleStore.update`) locks the row first. If
  the times, zone and pausing are unchanged, `next_at` stays as it is, due or not. If they
  changed, `next_at` is worked out from the database's clock read after the lock, or from
  `last_at` if that is later, so it is never a fire already claimed. A fire that was due and not
  yet claimed is recorded as `missed: changed while it was due`.
- **Across clocks:** no process's own clock is used for `next_at`: the claim, a save and a new
  schedule all read the database's (`clock_timestamp()`). The preview alone uses the API's, since
  it only shows times.
- **A crash** between the commit and the queue loses that fire, which is the safer failure for
  loads of data. Its outcome stays `starting`; once `last_at` is older than three poll intervals
  (at least five minutes; `ScheduleStore.staleAfter`), every read shows it as
  `failed: the server stopped before the run was queued`. Nothing is written twice.

**Keeping going.** `fire()` catches anything a tick throws, an `Error` too, logs it at ERROR by
its kind (the detail at DEBUG) and looks again at the next poll; it records when it last looked.
The readiness check `scheduler` (`ReadinessChecks`) is down when a process with the scheduler
role has not looked within three poll intervals and a minute. The log says what each fire did by
its first word at INFO (`started`, `skipped`, `missed`, `failed`); the reason, which can be worked
out from the run's values, only at DEBUG.

## 6. Outcomes

![What happens when a schedule is due: a fire due more than an hour ago is missed; a pipeline or flow with a run still going is skipped; a latest version that cannot run fails with the server's reason; otherwise the run starts. Either way the next fire is worked out from now](../guide/images/schedule-outcomes.svg)

| Outcome | When |
| --- | --- |
| `starting` | Claimed; its run is being queued. Seconds at most |
| `started` | The run was queued; `last_pipeline_run` or `last_flow_run` names it |
| `skipped: a run was still going` | One run at a time: the pipeline or flow already had a run queued or running (`AlreadyRunning`, a 409 for Run now). The fire is not queued for later |
| `missed: the server was not running` | The fire was due longer ago than `kilnmark.schedules.catch-up` (1 hour) when it was claimed. It does not run late |
| `failed: <message>` | The run could not be queued: the server's message, as Run now would show it (a version with problems, a missing value, a pipeline deleted since). Messages never hold secrets; a version's problems can be worked out from the run's values, so viewers see only `failed` |
| `missed: changed while it was due` | Its times, zone or pausing were changed while a fire was due and not yet claimed. That fire does not run |
| `failed: the server stopped before the run was queued` | How `starting` reads once it is older than three poll intervals (at least five minutes): the process stopped before it queued the run. That fire is lost |

Within the catch-up a schedule runs once, however many fires it covers: the next fire is always
worked out from now. A schedule whose expression or zone cannot be read any more stops (its next
fire is cleared) and its outcome says why.

## 7. The API

`/api/v1/schedules`: `GET` (filters `pipeline=` and `flow=`), `GET {id}`, `POST`, `PUT {id}`
(replaces everything; `enabled` false pauses, true resumes and works the next fire out from then;
a change that keeps the times, zone and pausing keeps the next fire),
`DELETE {id}`, and `POST preview`. Each schedule shows `nextAt`, `lastAt`, `lastOutcome` and the
last run. Mistakes are 400 with `{"error": "..."}` naming the field: `cron:`, `zone:`, `runAs:`,
`parameters:`, `pipeline:` or `flow:`.

## 8. The audit log

`schedule.set` when a schedule is added or changed, and `schedule.deleted`, each on
`schedule <id>` with the pipeline or flow, cron, zone, `runAs` and `enabled`: never the parameter
values (ADR 0029). Deleting a pipeline records `schedule.deleted`, with `via: pipeline deleted`,
for exactly the schedules its own transaction deleted, so one added while it was being deleted is
never removed unrecorded. The runs themselves are recorded as runs, requested by the
schedule.

## 9. Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.schedules.poll-interval` | `30s` | How often a process with the scheduler role looks for schedules that are due |
| `kilnmark.schedules.catch-up` | `1h` | A fire due longer ago than this when it is found is missed, not run late |

## 10. Not yet

- Event triggers: a run when a file lands, or when another flow ends.
- **The role is checked when the schedule is saved, not at each fire.** A person whose token is
  revoked, or who loses a role, leaves their schedules running as `set by` them until a designer
  or admin changes or deletes them. Changing a schedule makes the person changing it its new
  `set by`. The Schedules page shows every schedule's `set by`, with a note for admins' access
  reviews; the access review page and its download do not list schedules yet.

## 11. Pages

In `ui/src/schedules`; the guide walks through them.

- **A pipeline's or flow's Schedules tab** (`SchedulesSection`), in the panel under its canvas or
  diagram, reads `GET /schedules?pipeline=` or `?flow=` when the page opens (the line under its
  name gives the next scheduled run) and again every 30 seconds while the tab shows.
- **The Schedules page** (`#/schedules`, `SchedulesPage`) reads `GET /schedules`, with filters for
  on, paused and failed last time; display names come from the pipeline and flow lists.
- **One table** (`SchedulesTable`) for both: the cron in words for the shapes the presets make and
  the `@` shorthands (`cronWords`), else the expression; `updatedBy`, the name runs carry, in bold;
  On or Paused with the next run in the viewer's zone, and in the schedule's when that differs;
  the last outcome as a word, a tone and its reason, linked to the run. The values are a line
  under the schedule for stewards and above; viewers get null from the API and no line. Six
  columns at most, so the page fits 1200 pixels.
- **The form** (`ScheduleForm`, designers and admins) has presets that fill the cron (`presetCron`;
  `readPreset` reads one back, so an edit opens on its preset) and a free cron field. Every few
  minutes offers only steps that divide the hour, so the gaps stay even. A preset part that is not
  right empties the cron rather than keep an earlier one. The zone list is
  `Intl.supportedValuesOf("timeZone")` with UTC and the browser's zone. `POST /schedules/preview`
  is asked 400 ms after the last change, earlier answers aborted, and only the answer for the
  current expression and zone is shown. Parameter fields are the Run now dialog's
  (`ParameterFields`); a pipeline's parameters are read from its latest YAML (`pipelineParameters`),
  a flow's from its version. The form does not save until the parameters are read, since a PUT
  replaces every value.
- **Pause and Resume** read the schedule again (`GET /schedules/{id}`) and PUT it with only
  `enabled` changed, so a change since the list was read is kept; **Edit** opens on a fresh read
  too. Each makes the person the schedule's `set by`, as the notice says. **Delete** asks first.
