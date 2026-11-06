# Running pipelines and flows on a schedule

A schedule runs a pipeline or a flow at set times, such as 02:00 every weekday, without anyone
pressing **Run now**. Each run is of the latest saved version, with values you choose for its
parameters, and is checked exactly as Run now checks it.

For now, schedules are set through the API, `/api/v1/schedules`. Pages to set and read them follow
in the next release.

## Before you start

- **Designers and admins** add, change, pause, resume and delete schedules.
- **Stewards** read schedules, with the values their runs start with.
- **Viewers** read schedules too, but not the values their runs start with, nor why a run failed
  to start.
- The pipeline or flow must exist and have a saved version. A pipeline or flow can have several
  schedules: a nightly run and a month-end run with other values, say.

Requests below use an API token in `$TOKEN` (see [Signing in, roles and tokens](signing-in.md))
and Kilnmark's address in `$KILNMARK`.

## Who a scheduled run acts as

Nobody presses Run now, so the schedule itself asks for the run. Its runs are recorded as
requested by `schedule <id> (set by <name>)`, where the name is whoever last added or changed the
schedule, with the role the schedule gives them:

- **steward**, the default: enough to run pipelines and flows;
- **designer**, if the work needs it.

A scheduled run never acts as an admin, and you cannot choose a role above your own. Whoever
changes a schedule becomes the name its later runs carry, so they are answerable for them.

The role is checked when the schedule is saved. If someone leaves, or their token is revoked,
their schedules keep running in their name: change each one, so it carries a current person's
name, or delete it. List them with `GET /api/v1/schedules` and look at `updatedBy`.

A scheduled flow's pipeline runs carry the same name and role. Like any run, it can only write to
sources that allow writing.

## Adding a schedule

Send what to run, when, and in which time zone:

```bash
curl -s -X POST "$KILNMARK/api/v1/schedules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"pipeline": "customer-billing", "cron": "0 2 * * MON-FRI", "zone": "Europe/London",
       "parameters": {"region": "EMEA"}, "description": "Weekday billing load"}'
```

| Field | What it is |
| --- | --- |
| `pipeline` or `flow` | What to run: give one, not both |
| `cron` | When, in five fields (below) |
| `zone` | The time zone the times are in, such as `Europe/London` or `America/New_York`. `UTC` if left out |
| `parameters` | Values for the latest version's parameters; the others take their defaults. A flow's parameter with no default must be given |
| `runAs` | `steward` (the default) or `designer` |
| `enabled` | `false` adds it paused; on by default |
| `description` | One short line, at most 200 characters, saying what it is for |

Kilnmark checks everything before saving: the expression must fire, the zone must be known, and the
values must suit the latest version, as Run now would check them. A mistake is refused at once,
naming the field, rather than at the first run. The answer is the schedule, with `nextAt`, when it
fires next.

## Writing the times

A cron expression is five fields, separated by spaces, in this order:

1. the minute, 0 to 59;
2. the hour, 0 to 23;
3. the day of the month, 1 to 31;
4. the month, 1 to 12 or `JAN` to `DEC`;
5. the day of the week, 0 to 7 or `SUN` to `SAT` (0 and 7 are both Sunday).

So `0 2 * * MON-FRI` is minute 0 of hour 2, on any day of any month that is a weekday.

Each field takes `*` (any), a number, a range (`9-17`), a list (`0,30`) and a step (`*/15`, every
15; `9-17/2`, every 2 from 9 to 17). Some examples:

| Expression | Runs |
| --- | --- |
| `0 2 * * *` | At 02:00 every day |
| `30 6 * * MON-FRI` | At 06:30 on weekdays |
| `*/15 * * * *` | Every 15 minutes |
| `0 9-17 * * 1-5` | On the hour, 09:00 to 17:00, on weekdays |
| `0 1 1 * *` | At 01:00 on the 1st of each month |
| `@daily` | At midnight; also `@hourly`, `@weekly` (Sunday), `@monthly` and `@yearly` |

- **Both day fields.** When the day of the month and the day of the week are both given (neither is
  `*`), a day matching **either** runs: `0 0 1 * MON` runs on the 1st and on every Monday. This is
  how cron has always read it.
- **No seconds**, and no `@reboot`: a schedule runs at set times.
- **An expression that never runs**, such as `0 0 30 2 *` (the 30th of February), is refused.

To see when an expression would run before you save it, try it. Anyone signed in may:

```bash
curl -s -X POST "$KILNMARK/api/v1/schedules/preview" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"cron": "0 2 * * MON-FRI", "zone": "Europe/London"}'
```

The answer lists the next five times, or says what is wrong (`valid` false, with `problem`).

### When the clocks change

Times are in the schedule's zone, so 02:00 in `Europe/London` stays 02:00 through summer and winter.

- **When the clocks go forward**, a time that does not exist that night runs as late as the clocks
  jumped: in London, 01:30 on the last Sunday in March runs at 02:30.
- **When the clocks go back**, a time that happens twice runs once, the first time.

Choose a time outside 01:00 to 03:00, or the zone `UTC`, if this matters to the work.

## How a schedule fires

![How a schedule fires: every 30 seconds a Kilnmark process with the scheduler role finds the schedules that are due, locks each one so no other process takes it, moves its next run past now and commits; only then is the run queued, as Run now would queue it, acting as the schedule, and the outcome is recorded](images/schedule-fire.svg)

Kilnmark looks for schedules that are due every 30 seconds, so a run can start up to half a minute
after its time. It moves a schedule's next time on **before** it queues the run. However many
Kilnmark processes look at once, a schedule runs at most once for each time. If Kilnmark stops at
exactly the wrong moment, that one run is lost rather than run twice, and its outcome says so: for loads of data, a missing
run you can see is safer than a doubled one.

## What happened last time

Each schedule shows `lastAt`, when it was last due, and `lastOutcome`, what happened then:

![What happens when a schedule is due: a time due more than an hour ago is missed and not run late; if a run of the pipeline or flow is still going, the time is skipped; if the latest version cannot run, it fails with the server's reason; otherwise the run starts. Either way the next time is worked out from now](images/schedule-outcomes.svg)

| Outcome | What it means | What to do |
| --- | --- | --- |
| `started` | The run was queued; `lastPipelineRun` or `lastFlowRun` is its number | Open the run to follow it |
| `skipped: a run was still going` | The last run had not finished, and only one runs at a time | If it happens often, the runs take longer than the gap between them: space the times out |
| `missed: the server was not running` | Kilnmark was stopped when it was due, for longer than an hour. It does not run late | Run it now by hand if the work is still needed |
| `failed: …` | The run could not start, for the reason given: the latest version has problems, a value no longer suits it, a source no longer allows writing. Viewers see only `failed` | Fix what it says; the next time runs as usual |
| `failed: the server stopped before the run was queued` | Kilnmark stopped at the moment it was starting the run, so that time was lost | Run it now by hand if the work is still needed |

A missed or skipped time is never made up later, and a schedule that was due several times while
Kilnmark was stopped for less than an hour runs once, not once for each. How late a time may still
run is `kilnmark.schedules.catch-up` (1 hour), set by whoever runs Kilnmark.

## Changing, pausing and deleting

`PUT /api/v1/schedules/<id>` replaces everything a schedule is set to, so send all of it. If it
now runs a different pipeline or flow, its last outcome is cleared. To pause
one, send it with `"enabled": false`; it has no next time while paused. To resume, send `true`: the
next time is worked out from then, and the times it was paused are not made up.

`DELETE /api/v1/schedules/<id>` deletes a schedule. Its runs stay, in the pipeline's or flow's
history. Deleting a pipeline deletes its schedules too, and each is recorded in the audit log as
deleted with it.

Every change to a schedule is in the [audit log](audit-log.md), as **Schedule set** or **Schedule
deleted**, with what it runs, when, in which zone, as which role, and whether it is on. The values
its runs start with are never recorded there.

## Finding schedules

`GET /api/v1/schedules` lists them all; add `?pipeline=customer-billing` or `?flow=month-end` for
one pipeline's or flow's. `GET /api/v1/schedules/<id>` gives one. A run started by a schedule shows
`schedule <id> (set by <name>)` as who asked for it, on its run page and in the History tab.
