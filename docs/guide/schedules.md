# Running pipelines and flows on a schedule

A schedule runs a pipeline or a flow at set times, such as 02:00 every weekday, without anyone
pressing **Run now**. Each run is of the latest saved version, with values you choose for its
parameters, and is checked exactly as Run now checks it.

You set schedules on a pipeline's or flow's page, in the **Schedules** tab under its canvas or
diagram, and see every schedule on the server on the **Schedules** page in the sidebar. Scripts
can do the same through the API (see [With the API](#with-the-api)).

## Before you start

- **Designers and admins** add, change, pause, resume and delete schedules.
- **Stewards** read schedules, with the values their runs start with.
- **Viewers** read schedules too, but not the values their runs start with, nor why a run failed
  to start.
- The pipeline or flow must exist and have a saved version. A pipeline or flow can have several
  schedules: a nightly run and a month-end run with other values, say.

## Who a scheduled run acts as

Nobody presses Run now, so the schedule itself asks for the run. Its runs are recorded as
requested by `schedule <id> (set by <name>)`, where the name is whoever last added or changed the
schedule, with the role the schedule gives them:

- **steward**, the default: enough to run pipelines and flows;
- **designer**, if the work needs it.

A scheduled run never acts as an admin, and you cannot choose a role above your own. Whoever
changes a schedule becomes the name its later runs carry, so they are answerable for them.

A scheduled flow's pipeline runs carry the same name and role. Like any run, it can only write to
sources that allow writing.

Today a run as designer can do no more than a run as steward: Kilnmark checks only that a run's
role is steward or above. The role is recorded with each run, for when that changes.

The **Schedules** page lists every schedule with who set it (see
[Finding schedules](#finding-schedules)).

## When the person who set it loses the role

A schedule runs only while whoever last set it could still run it themselves. Before every run,
Kilnmark checks:

- **for an API token:** it has not been revoked or expired, and still has the role;
- **for someone who signs in with your organisation's login:**
  - their latest sign-in gave them the role. A sign-in refused because none of their groups has a
    role any more takes it away; one refused for another reason, such as no second factor, does
    not, as in the access review;
  - their groups still give that role as Kilnmark's settings map groups to roles today. If an admin
    changes the mapping, the lower of the two counts. For this, each sign-in records only the groups
    your settings map to a role, never the person's other groups;
  - they have signed in within the last 30 days (`kilnmark.schedules.signed-in-within`). Someone who
    has left, and is disabled at your provider, never signs in again, so their schedules stop
    after 30 days at most.

If not, nothing runs. The schedule is **paused**: it is off, has no next time, and says why:
`paused: the person who set it no longer has the role`, or
`paused: the person who set it has not signed in for 30 days`. The pages show it as the last
outcome, in amber, apart from the grey **Paused** state: **Paused: setter lost the role** or
**Paused: setter not signed in for 30 days** (viewers see just **Paused by Kilnmark**). The
audit log records **Schedule paused**, with why. Kilnmark also pauses at once, without waiting for
the next time, when an admin revokes the token, or when the person signs in with less of a role (or
none), or their sign-in renews with less of one ([Signing in](signing-in.md#signing-in-with-your-organisation)). A run already queued or running is not cancelled by a pause: cancel it on its run page if
you need to.

**What you do** (designers and admins):

1. Open the **Schedules** page and choose **Paused by Kilnmark**. It lists only the schedules
   Kilnmark paused this way; their last outcome says why.
2. For each, decide whether the work should go on.
3. To keep it, choose **Resume** (or **Edit**, then **Save changes**, with **Paused** cleared). You
   become its setter, so its runs carry your name and are checked against your role from then on.
4. Otherwise choose **Delete**.

Nothing resumes by itself, even if the person's role comes back. Scripts find them with
`GET /api/v1/schedules`: `"enabled": false` and a `lastOutcome` starting `paused`, and resume one
by saving it with `"enabled": true`.

Two things to know:

- **The server's own admin token** (`KILNMARK_ADMIN_TOKEN`): if its value changes, the old token is
  set aside and the schedules it set are paused at their next time. Set schedules with your own
  token or login, not that one.
- **Someone removed from their groups at your provider** is seen by Kilnmark only when they next
  sign in, or after 30 days without one. Their API tokens are separate: revoking a token stops only
  the schedules set with that token. To stop a person's schedules at once, pause or reassign them
  yourself.
- **Work that must outlive any one person**, such as a nightly load, is best set with a team's
  designer API token, kept by whoever looks after that work. It stops only if the token is
  revoked or expires, not when someone moves on; it is still listed in the access review.

## A pipeline's or flow's schedules

Open the pipeline or flow and choose the **Schedules** tab in the panel under it. Each schedule
shows:

- **Schedule**: when it runs, in words where that is easy ("Weekdays (Monday to Friday) at
  06:30"), with its cron expression, its time zone, its number and its description. Stewards and
  above also see the **Values** its runs start with; viewers do not;
- **Set by**: who last added or changed it, the role its runs have, and when. Its runs carry
  this name. A token shows by its name; a person who signs in through your identity provider by
  their `sso:` name, with the provider's ID for them when you hover over it. Long names wrap, so
  the table keeps every column in view;
- **State and next run**: **On** or **Paused**, and the next run in your own time, with your zone
  named; when the schedule's zone is another, the same time in that zone too;
- **Last time it was due**: what happened, in a word with a colour (**Started**, **Starting**,
  **Skipped**, **Missed**, **Failed**, **Paused: setter lost the role** or **Paused: setter not
  signed in for 30 days**), when, why, and a link to the run it started.

The line under the pipeline's or flow's name says when it next runs on a schedule; choosing it
shows the tab.

![The Schedules tab under the canvas of sample-orders-by-status, with Add a schedule: two schedules, both on. The first runs on weekdays at 07:15 in Europe/London with the defaults, set by Dana Reyes, who signs in through the identity provider, to run as steward; its next run is Monday at 07:15 and it has not run yet. The second runs every 15 minutes in UTC with its values, set by an admin token; its next run is shown in the viewer's time and in UTC, and last time it Started, with a link to its pipeline run. The table scrolls sideways to each row's Edit, Pause and Delete](images/schedules-section.png)

## Adding a schedule

1. Open the pipeline or flow, choose the **Schedules** tab under it, then **Add a schedule**.
2. Under **When**, choose how often in **Repeats**: every day at a time, weekdays at a time,
   every hour, every few minutes, or a day of each month. Then give the time, the minutes past the
   hour, how many minutes, or the day. Each fills in the **Cron expression**.
3. For anything else, choose **Something else** and write the cron expression yourself (see
   [Writing the times](#writing-the-times)). Typing an expression that is one of the presets
   chooses that preset.
4. Choose the **Time zone**. It starts as your own. The times are in this zone all year.
5. Check **Next five runs**, which follows what you choose: each time is in your own zone, and in
   the schedule's zone when that is another. An expression that cannot be used says why here.
6. Under **Values**, change the values of the parameters the latest version declares, as in the
   Run now dialog. A field left as its default is not saved, so each run takes the default of the
   version then latest.
7. Choose what it **Runs as**: steward, or designer.
8. Add a short **Description** of what it is for, if you like, and tick **Paused** to add it
   without it running yet.
9. Choose **Add schedule**.

![The form to add a schedule to a pipeline: Repeats set to weekdays at a time, at 07:15, which fills in the cron expression 15 7 * * MON-FRI; the time zone Europe/London, marked as yours; the next five runs, Monday to Friday at 07:15. Under them the pipeline's three parameters with their defaults and descriptions, Runs as steward, a description, Paused, and Add schedule](images/schedule-form.png)

Kilnmark checks everything before saving: the expression must fire, the zone must be known, and the
values must suit the latest version, as Run now would check them. A mistake is refused at once,
naming the field, and the form shows the message, rather than the first run failing.

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

The schedule form shows the next five times an expression runs as you type it, or what is wrong
with it. Scripts can ask the same of the API (see [With the API](#with-the-api)).

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

### When a scheduled run is tried again

A pipeline or flow with a `run: retries` block tries a failed run again
([Trying a run again](pipelines.md#trying-a-run-again)). That is a new try of the same run, never a
new fire: the schedule still fires at most once for each time, and its outcome stays **Started**
with the same run. While the run waits for its next try, it is still going, so a time that comes
meanwhile is **Skipped**.

No try starts after the schedule's next time: a daily run never runs into the next day's. A try
that would is not made, and the run fails, saying "Not tried again: the next scheduled run is due
first". A try acts as the first did, as the schedule, with the schedule's role.

## What happened last time

Each schedule shows, under **Last time it was due**, when that was and what happened, as a word
with a colour, the reason, and a link to the run it started (in the API, `lastAt` and
`lastOutcome`):

![What happens when a schedule is due: a time due more than an hour ago is missed and not run late; if whoever set the schedule no longer has its role, the schedule is paused and the audit log says so; if a run of the pipeline or flow is still going, the time is skipped; if the latest version cannot run, it fails with the server's reason; otherwise the run starts. Either way the next time is worked out from now](images/schedule-outcomes.svg)

| Outcome | What it means | What to do |
| --- | --- | --- |
| **Starting** (`starting`) | Kilnmark is starting the run, which takes a moment | Look again shortly |
| **Started** (`started`) | The run was queued; the link opens it (`lastPipelineRun` or `lastFlowRun` in the API) | Open the run to follow it |
| **Skipped** (`skipped: a run was still going`) | The last run had not finished, and only one runs at a time | If it happens often, the runs take longer than the gap between them: space the times out |
| **Missed** (`missed: the server was not running`) | Kilnmark was stopped when it was due, for longer than an hour. It does not run late | Run it now by hand if the work is still needed |
| **Failed** (`failed: …`) | The run could not start, for the reason given: the latest version has problems, a value no longer suits it, a source no longer allows writing. Viewers see only `failed` | Fix what it says; the next time runs as usual |
| **Paused: setter not signed in for 30 days** (`paused: the person who set it has not signed in for 30 days`) | It was set by someone signing in with your organisation's login who has not signed in for 30 days. The schedule is off. Viewers see **Paused by Kilnmark** (`paused`) | Resume it (you become its setter), or delete it |
| **Skipped** (`skipped: the schedule was changed while it was starting`) | It was paused, changed or deleted at the moment its run was being started, so nothing ran | Nothing, unless the change was a mistake |
| **Paused: setter lost the role** (`paused: the person who set it no longer has the role`) | Whoever last set it no longer has the role: their token was revoked or expired, or they signed in (or their sign-in renewed) with less of a role. The schedule is off. Viewers see **Paused by Kilnmark** (`paused`) | Resume it (you become its setter), or delete it |
| **Missed** (`missed: changed while it was due`) | Someone changed its times, zone or pausing just as it was due, so that time did not run | Run it now by hand if the work is still needed |
| **Failed** (`failed: the server stopped before the run was queued`) | Kilnmark stopped at the moment it was starting the run, so that time was lost. It shows once it has said `starting` for a few minutes | Run it now by hand if the work is still needed |

A missed or skipped time is never made up later, and a schedule that was due several times while
Kilnmark was stopped for less than an hour runs once, not once for each. How late a time may still
run is `kilnmark.schedules.catch-up` (1 hour), set by whoever runs Kilnmark.

## Changing, pausing and deleting

In the **Schedules** tab, or on the **Schedules** page, each schedule has three buttons for
designers and admins:

- **Edit** opens the form with what it is set to now. Change what you need and choose **Save
  changes**.
- **Pause** stops it running until you choose **Resume**. Kilnmark reads the schedule again first,
  so a change someone else made since the page was read is kept.
- **Delete** asks first, then deletes it.

Saving, pausing and resuming each make you the schedule's **Set by**: its later runs carry your
name.

A change that keeps the times, the zone and whether it is paused keeps its next time, so editing
the description or the values never moves or drops a run. A change to the times, the zone or
pausing works the next time out again; if a time was due at that moment and had not started, it
is recorded as `missed: changed while it was due` rather than lost without a word. A paused
schedule has no next time. Resuming works the next time out from then, and the times it was paused
are not made up.

A deleted schedule's runs stay, in the pipeline's or flow's history. Deleting a pipeline or a flow
deletes its schedules too, and each is recorded in the audit log as deleted with it
([Deleting a flow](flows.md#deleting-a-flow)).

Every change to a schedule is in the [audit log](audit-log.md), as **Schedule set** or **Schedule
deleted**, with what it runs, when, in which zone, as which role, and whether it is on. The values
its runs start with are never recorded there.

## Finding schedules

The **Schedules** page, in the sidebar after **Flows**, lists every schedule on the server: when it
runs, what it runs (linked to the pipeline or flow), who set it and as which role, whether it is
on, its next run and what happened last time. Choose **On**, **Paused**, **Paused by Kilnmark** or
**Failed last time** to see only those; each says how many there are. **Paused** holds every
schedule that is off, whoever paused it; **Paused by Kilnmark** only those Kilnmark paused because
whoever set them lost the role or has not signed in for 30 days.

![The Schedules page, as an admin sees it: filters for All 4, On 3, Paused 1, Paused by Kilnmark 0 and Failed last time 0; a note for access reviews saying the 4 schedules run in the name of 2 people, that Kilnmark pauses the schedules of someone whose token is revoked or who loses their role, or who has not signed in for 30 days, and that a team's designer token suits work that must outlive any one person; and a table of every schedule with when it runs, the pipeline or flow it runs, linked, who set it and its role, and its state and next run, one of them paused. The table scrolls sideways to the last time each was due and to Edit, Pause or Resume, and Delete](images/schedules-page.png)

**For access reviews**, admins see a note saying how many people the schedules run in the name of.
Read the **Set by** column. When someone's token is revoked or expires, or they lose their role,
Kilnmark pauses the schedules they set; a schedule set by someone signing in with your
organisation's login also pauses once they have not signed in for 30 days. An admin resumes each
one, becoming its setter, or deletes it (see
[When the person who set it loses the role](#when-the-person-who-set-it-loses-the-role)). A token
that still works is not caught: revoke the tokens of someone who leaves. For work that must
outlive any one person, use a team's designer API token.

A run started by a schedule shows `schedule <id> (set by <name>)` as who asked for it, on its run
page and in the History tab.

## With the API

Scripts set and read schedules at `/api/v1/schedules`. The requests below use an API token in
`$TOKEN` (see [Signing in, roles and tokens](signing-in.md)) and Kilnmark's address in
`$KILNMARK`.

To add one, send what to run, when, and in which time zone:

```bash
curl -s -X POST "$KILNMARK/api/v1/schedules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"pipeline": "customer-billing", "cron": "0 2 * * MON-FRI", "zone": "Europe/London",
       "parameters": {"region": "EMEA"}, "description": "Weekday billing load"}'
```

| Field | What it is |
| --- | --- |
| `pipeline` or `flow` | What to run: give one, not both |
| `cron` | When, in five fields (see [Writing the times](#writing-the-times)) |
| `zone` | The time zone the times are in, such as `Europe/London` or `America/New_York`. `UTC` if left out |
| `parameters` | Values for the latest version's parameters; the others take their defaults. A flow's parameter with no default must be given |
| `runAs` | `steward` (the default) or `designer` |
| `enabled` | `false` adds it paused; on by default |
| `description` | One short line, at most 200 characters, saying what it is for |

The answer is the schedule, with `nextAt`, when it fires next.

- `PUT /api/v1/schedules/<id>` replaces everything a schedule is set to, so send all of it: send
  `"enabled": false` to pause it and `true` to resume it. If it now runs a different pipeline or
  flow, its last outcome is cleared.
- `DELETE /api/v1/schedules/<id>` deletes it.
- `GET /api/v1/schedules` lists them all; add `?pipeline=customer-billing` or `?flow=month-end` for
  one pipeline's or flow's. `GET /api/v1/schedules/<id>` gives one. Each shows `updatedBy`, the name
  its runs carry, `lastAt`, `lastOutcome`, and `lastPipelineRun` or `lastFlowRun`.
- `POST /api/v1/schedules/preview` with `{"cron": "0 2 * * MON-FRI", "zone": "Europe/London"}` gives
  the next five times, or what is wrong (`valid` false, with `problem`). Anyone signed in may.
