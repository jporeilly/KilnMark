# Run options in other tools: retries, timeouts, alerts and owners

Research for Kilnmark's run-level retries and owners, 7 November 2026. The summary and what was
decided are in [README.md](README.md).

Every claim about a comparison tool carries a source tag such as [A1]; the tags are listed under
**Sources** at the end. Tool P and Tool H (an earlier data integration tool and its open-source
successor) are described in plain words only; their sources name those tools, so they are kept
outside the repository.

**How sure this is.** Most facts come from the vendor's own page, read for this note. Where a page
could not be opened (one Control-M page answered 403; AutoSys's reference guide was not reached),
the fact comes from a search engine's summary of the vendor's page or from the vendor's knowledge
base, and is marked *(summary)*. Nothing was skipped entirely: all ten tools had reachable docs.

---

## 1. Kilnmark today (from its own docs)

- **Node controls** (docs/design/flow-controls.md, docs/guide/reference-flow-nodes.md):
  `timeout` on run-pipeline, for-each (each item), sql and check-source (`90s` to `7d`, no default);
  `retries` 0 to 5 with `retry_delay` 10s to 1h (1m by default), doubled before each try, with no
  cap on the doubled wait. Retried only when a second try cannot write twice: every write in the
  pipeline's version replaces or upserts (checked when saved and again at run time), or a sql node's
  transaction rolled back. A failed commit (in doubt) is never retried. A cancel, by a person or the
  flow, is never retried; only the watcher's own timeout cancel is. While waiting, the node holds no
  worker; the run page says "Try 1 of 4 failed; trying again in 2m". `priority` high, normal, low.
- **Connections**: `success` (default), `failure`, `always`, or a condition on what earlier nodes
  reported (`"load.score >= good"`). A node with several incoming connections waits until all are
  settled and starts if any was taken (an automatic join). Loops are refused.
- **Schedules** (docs/design/schedules.md, ADR 0046): five-field cron in a zone; at most once (a
  crash between claim and queue loses the fire); one run at a time (a fire while a run is going is
  `skipped`); a fire found more than `kilnmark.schedules.catch-up` (1h) late is `missed`, never run
  late; runs act as `steward` or `designer`, never admin, as the person who last set the schedule;
  schedules pause when that person loses the role or has not signed in for 30 days.
- **Notifications** (docs/guide/notifications.md): pipeline run failed and flow run failed (high),
  told once per problem, until a run of that pipeline or flow succeeds; in the app and by email;
  team addresses; at most 20 emails an address an hour, then the daily digest.
- **External schedulers** (docs/guide/external-schedulers.md): `kilnmark-run` waits for the run and
  exits 0 to 5; a request key means a scheduler's retry never starts the work twice.
- **Gaps the owner named**: a run whose worker stops or loses its database connection fails and is
  never picked up again; there is no owner.
- **One inconsistency to check.** flow-controls.md C4 says a worker that takes a flow over "carries
  on from" the latest try, while flows.md says "today a flow whose worker stopped is failed, not
  picked up again". One of the two is out of date.

---

## 2. Retries

### 2.1 Comparison

| Tool | Level | Count | Delay, backoff, cap, jitter | What is retried | Resume or restart | Worker or infrastructure loss |
| --- | --- | --- | --- | --- | --- | --- |
| Airflow | Task | `retries`; server default `default_task_retries` 0 [A4][A5] | `retry_delay` (300 s by default) [A4][A5]; `retry_exponential_backoff` is a multiplier, 0 = constant [A5]; `max_retry_delay` per task, server cap 86,400 s [A4][A5]; no jitter mentioned [A5] | Every failure by default; a `retry_policy` maps exceptions to retry, fail or reschedule, and cannot exceed `retries` [A1]; `AirflowFailException` fails at once [A1]; a sensor's own timeout is not retried, an execution timeout is [A1] | Each task retries on its own; clearing failed tasks re-runs only those [A9] | "Zombie" tasks (worker killed, OOM, node scaled down) are found by heartbeat and failed or retried if retries remain [A1]; `task_instance_heartbeat_timeout` 300 s [A4] |
| Dagster | Op/asset, and run | `RetryPolicy.max_retries` defaults to 1 [D2]; run retries `max_retries` in config or `dagster/max_retries` tag [D3] | `delay`; `Backoff.LINEAR` or `EXPONENTIAL`; `Jitter.FULL` or `PLUS_MINUS` [D2] | Op: every exception, unless raised as `Failure(allow_retries=False)` [D1]. Run: "fails for any reason", including a crashed worker; `retry_on_asset_or_op_failure: false` limits run retries to crashes [D3] | Run retries default to `FROM_FAILURE` (successful ops skipped); `ALL_STEPS` re-runs all [D3] | Run monitoring detects dead run workers; can resume up to `max_resume_run_attempts` (3) on some launchers, else fails [D4] |
| Prefect | Task and flow | `retries` [P1][P2]; server defaults `PREFECT_TASKS_DEFAULT_RETRIES` [P3] | `retry_delay_seconds`: a number, a list, or `exponential_backoff(...)`; `retry_jitter_factor` [P3] | Any exception or Failed state; `retry_condition_fn` decides (the example skips HTTP 401 and 404) [P3] | Flow retry re-runs the flow; a child flow's result is reused only when persisted [P3]; task caching can restore results [P1] | Crashed (infrastructure) is its own state, with no retry path shown [P4]; heartbeats plus an automation mark silent runs Crashed after about 9 minutes [P5] |
| Azure Data Factory | Activity | `retry` 0 by default [F1] | `retryIntervalInSeconds` 30 by default; no backoff or jitter listed [F1] | Not stated on the page | Not stated | Not stated |
| Step Functions | State (Task, Parallel, Map) | `MaxAttempts` 3 by default once a retrier is written; 0 = never [S1] | `IntervalSeconds` 1, `BackoffRate` 2.0, `MaxDelaySeconds` unlimited unless set, `JitterStrategy` `FULL` or `NONE` (default `NONE`) [S1] | Per error name in `ErrorEquals`; `States.ALL`; `States.TaskFailed` matches all but timeouts; `States.Runtime` is never retriable [S1] | Redrive continues from the failed step with the same input, within 14 days; successful steps are not re-run [S2] | `HeartbeatSeconds` fails a silent task with `States.Timeout` [S3] |
| Databricks | Task and job | `max_retries`: -1 indefinitely, 0 never, default never [B6] | `min_retry_interval_millis`, otherwise at once [B6]; continuous jobs back off exponentially [B2] | Failed runs and internal errors; timeouts only with `retry_on_timeout` [B6] | Repair re-runs failed or cancelled tasks and their dependants; successful ones are not re-run [B5] | Not described on the pages read |
| Argo Workflows | Step (template) | `limit`; total attempts are `limit + 1` [R2] | `backoff.duration`, `factor`, `cap`, `maxDuration` [R2]; `affinity` keeps a retry off the same host [R2] | `retryPolicy`: `Always`, `OnFailure` (default), `OnError` (controller or sidecar errors), `OnTransientError`; plus an `expression` on exit code, status, duration, message [R1] | `argo retry` re-runs failed steps, keeping successful ones [R3] | `OnError` covers controller-side failures [R1] |
| Control-M | Job | Maximum reruns, 0 by default (0 to 99 generally) [C1] | Rerun every: interval from the start or end of the last run *(summary)* [C2] | Automatic rerun on Not OK through a rerun action; manual reruns do not count [C1] | Rerun runs the job again | Not covered in pages reached |
| AutoSys | Job | `n_retrys` *(KB)* [X1] | No delay between tries (community answer) [X1 thread] | "Only when the job's command fails" *(KB)* [X1] | Runs the job again | Server setting `MaxRestartTrys` silently caps `n_retrys` [X3] |
| dbt Cloud | Job | Manual only on the page read [T1] | — | — | Rerun from failure: the failed step and skipped steps; or rerun from start [T1] | — |
| Temporal | Activity (and optionally workflow) | `MaximumAttempts` unlimited by default for activities; workflows have no retry policy by default [M1] | `InitialInterval` 1 s, `BackoffCoefficient` 2.0, `MaximumInterval` 100x initial [M1]; an error may carry its own next delay [M1] | All but `NonRetryableErrorTypes` or errors raised as non-retryable [M1] | Activity retries only; the workflow carries on from its history [M1] | Heartbeat timeout detects a dead worker; heartbeat details let a retry resume progress [M2] |

### 2.2 What is common

- **Off by default.** Airflow 0 [A4], ADF 0 [F1], Databricks never [B6], Control-M 0 [C1],
  Dagster run retries need enabling outside its cloud [D3]. Temporal's unlimited activity retries
  [M1] and Step Functions' 3 once a retrier exists [S1] are the exceptions, and both assume the work
  is written to be repeated.
- **The count is "more tries", not "tries".** Argo: limit + 1 attempts [R2]; Airflow: retries
  before failing [A5]. Kilnmark already counts this way.
- **Exponential doubling is the norm.** Step Functions 2.0 [S1], Temporal 2.0 [M1], Airflow's
  example 2.0 [A5], Dagster `EXPONENTIAL` [D2].
- **A cap on one wait.** Airflow 86,400 s [A4], Temporal 100x initial [M1], Argo `cap` [R2]; Step
  Functions has none unless set [S1].
- **Jitter is available but off.** Step Functions default `NONE` [S1]; Dagster and Prefect offer it
  [D2][P3]. Prefect says why: so many tasks do not retry at one cadence and load one system [P3].
- **Some failures must never be retried.** Temporal non-retryable errors [M1], Dagster
  `allow_retries=False` [D1], Airflow fail exception [A1], Step Functions `States.Runtime` [S1],
  Prefect `retry_condition_fn` [P3], Argo's policies [R1]. The usual split is infrastructure or
  transient (retry) versus logic or data (do not).
- **Two levels exist, and they multiply.** Dagster warns that op retries reset in each run retry, so
  an op can run more often than meant, and offers `retry_on_asset_or_op_failure: false` [D3].
- **Re-running a failed run resumes from the failure** in the newer tools: Step Functions redrive
  [S2], dbt [T1], Argo [R3], Databricks repair [B5], Dagster `FROM_FAILURE` [D3], Airflow clearing
  failed tasks [A9].
- **Crash detection is by heartbeat.** Airflow 300 s [A4], Prefect about 9 minutes [P5], Dagster
  daemon health checks [D4], Step Functions and Temporal heartbeat timeouts [S3][M2].

---

## 3. Timeouts, SLAs and duration warnings

| Tool | Execution timeout | Deadline or SLA | Duration warning |
| --- | --- | --- | --- |
| Airflow | `execution_timeout` per task; no server default [A4][A5] | SLA removed in 3.0; Deadline Alerts (3.1, experimental) on the DAG run, measured from queued time, logical date, a fixed time or average runtime [A1][A3] | Through a deadline callback [A3] |
| Dagster | `max_runtime_seconds` or `dagster/max_runtime` per run; start and cancel timeouts 180 s [D4] | Freshness policies with warn and fail windows (preview) *(summary)* [D8] | Run alert "exceeds its time limit"; new organisations get one at 24 hours [D5] |
| Prefect | `timeout_seconds` on task and flow; a flow may run on until its next task [P1][P2] | Automations on metrics such as lateness, or the absence of an expected event [P6] | Same automations [P6] |
| ADF | Activity `timeout`, default 12 hours, minimum 10 minutes [F1] | Azure Monitor alerts on metrics or logs [F2] | Through Azure Monitor [F2] |
| Step Functions | `TimeoutSeconds` on a task and on the whole execution; activities and callbacks have no default [S1][S3] | — | — |
| Databricks | Job and task `Timeout`, applied to each retry [B1][B2]; none by default [B6] | — | A duration **Warning** per job and task raises an event [B1][B2] |
| Argo | `activeDeadlineSeconds` per workflow or template [R4]; `maxDuration` bounds retries [R2] | — | — |
| Control-M | Exectime against a limit or the average run time *(summary)* [C3] | SLA Management service with deadline, predicted lateness and slack time *(summary)* [C4] | SHOUT on late submission, late end, run time [C3] |
| AutoSys | `term_run_time` ends the job; `max_run_alarm` only alarms *(KB)* [X4][X5] | — | `max_run_alarm` [X4] |
| Temporal | Start-to-close strongly recommended; schedule-to-close covers retries [M2] | Advises against workflow timeouts; use a timer [M3] | — |

**Common:** no default timeout (Airflow, Databricks, Temporal); a separate *warning* that alerts but
does not stop (Databricks, AutoSys, Control-M, Dagster); deadlines measured against a time of day
for scheduled runs (Airflow, Control-M). ADF's 12-hour default is the outlier, and a hidden one.

---

## 4. Notifications and alerting

| Tool | Failure | Retry | Success | Deadline or long run | Recovery | Channels | Noise control |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Airflow | `on_failure_callback` [A2] | `on_retry_callback`; `email_on_retry` **on by default** [A2][A4] | `on_success_callback` [A2] | Deadline callback [A3] | — | Notifier objects, such as Slack webhooks [A2][A3] | Callbacks do not fire for state changed in the UI or CLI [A2] |
| Dagster+ | Run and asset policies [D5] | — | Run success [D5] | Run over time limit [D5] | Health: once per change of state [D5] | Email, email to owners, Slack, Teams, PagerDuty [D6] | Health and schedule alerts only on a change of state [D5] |
| Prefect | Automations on state [P6] | Possible on state events [P6] | Yes [P6] | Absence of events [P6] | — | Slack, Teams, email, webhook [P6] | Not covered on the page |
| ADF | Metric "failed pipeline runs > 0" [F2] | — | — | Metric or log alerts [F2] | — | Azure Monitor alerts [F2] | Not covered |
| Step Functions | Catch to a fallback state [S1] | — | — | `TIMED_OUT` events through EventBridge [S1] | — | Your own states | — |
| Databricks | Job and task [B3] | Task: "mute until the last retry"; **job-level notifications are not sent on retries** [B3] | Yes [B3] | Duration and streaming backlog [B3] | — | Email, Slack, Teams, PagerDuty, webhook; 3 system destinations per event [B3] | Mute skipped or cancelled runs [B3][B6] |
| Argo | Exit handler on Failed or Error [R5] | — | Exit handler [R5] | — | — | Whatever the handler runs [R5] | — |
| Control-M | SHOUT on Not OK [C3] | SHOUT on rerun [C3] | SHOUT on OK [C3] | Late, late submission, exectime; SLA "service is late" [C3][C4] | — | Shout destinations, urgency R/U/V [C6] | Late alerts only for the first run, not reruns [C3] |
| AutoSys | `alarm_if_fail`, email on JOBFAILURE [X4] | **A JOBFAILURE alarm on every failed try**, then MAX_RETRYS [X2] | — | MAXRUNALARM [X4] | — | Email, alarms [X4] | `alarm_if_fail: 0` silences the per-try alarms [X2] |
| dbt Cloud | Fails [T2] | — | Succeeds [T2] | — | — | Email, Slack, Teams [T2] | Warns kept apart [T2] |
| Temporal | Not covered in pages read | — | — | — | — | — | — |

**Escalation** (alert again, or alert someone else, if nobody acts) is not built into any of these
schedulers; the docs leave it to PagerDuty-style destinations [B3][D6]. **Deduplication** is done by
alerting on a change of state rather than on each event (Dagster [D5]); Kilnmark already does this
("told once per problem").

---

## 5. Owners and run-as

| Tool | How declared | Used for | Run-as identity | No owner |
| --- | --- | --- | --- | --- |
| Airflow | `owner` text on each task; `owner_links` to a page or mailto [A5][A6] | Shown and linked in the DAG list [A6] | The worker's | Server default `default_owner` is `airflow` [A4] |
| Dagster | `owners` on an asset: an email or `team:name`; marked experimental *(summary)* [D7] | Alerts to owners on Dagster+ Pro (`email_owners`) [D6] | — | `default_email_addresses` "fallback if asset has no owner" [D6] |
| Databricks | Exactly one owner, a user or service principal, never a group; the creator by default; only admins change it [B4] | Permissions (`IS OWNER`) and the default run-as [B4] | `Run as`: user, service principal or group; service principals recommended [B4] | Warns a job may fail if the run-as user leaves [B4] |
| Control-M | `Run as`, formerly named **Owner** [C5] | Security: what the job may do [C5] | Same field [C5] | Mandatory [C5] |
| AutoSys | `owner`: the account the job runs as on its machine (community answer) [X6] | Execution account | Same field [X6] | — |
| Prefect, ADF, Step Functions, Argo, dbt, Temporal | No owner concept found on the pages read | — | Step Functions: a role per task [S3] | — |

**Common:** modern tools keep **owner** (who is accountable, who is told) apart from **run-as** (whose
rights the run has): Databricks separates them [B4], Dagster's owners are only for alerts [D6]. The
older enterprise schedulers merged the two, and Control-M renamed its field away from "Owner" [C5].
Teams as owners: Dagster yes [D7], Databricks no (groups only as run-as) [B4]. A fallback for the
unowned is documented (Dagster [D6], Airflow default [A4]).

---

## 6. Other run options worth noting

| Option | Examples |
| --- | --- |
| Idempotency | Temporal: activities should be idempotent [M1]; Databricks: repair "may duplicate data" as tasks are not idempotent [B5] |
| Concurrency | Airflow pools (128 default slots) and `max_active_runs_per_dag` 16 [A4][A7]; Databricks max concurrent runs 1 by default, queueing up to 48 h [B1][B6]; ADF pipeline `concurrency`, no maximum by default [F1]; Argo mutexes and semaphores [R2] |
| Catch-up and backfill | Airflow catch-up off by default in 3, backfill on request [A4][A9] |
| Pause | Prefect automations pause schedules [P6]; Databricks CAN MANAGE pauses schedules [B4] |
| Priority | Airflow `priority_weight` 1, weighted by downstream by default [A8]; Control-M priority *(not verified)* |
| Depends on past | Airflow `depends_on_past`, `wait_for_downstream` [A5][A10] |
| Trigger rules | Airflow 13 rules (all_success default, all_done, one_failed...) [A10]; ADF Succeeded, Failed, Skipped, Completed [F1] |

---

## 7. Pitfalls the tools document

1. **Alert on every retry.** Airflow's `email_on_retry` defaults to on [A4]; AutoSys raises a
   failure alarm on each try until silenced [X2]. Databricks went the other way: job-level
   notifications are not sent on retries [B3].
2. **Retries that write twice.** Databricks: a repaired task may duplicate data [B5]; Temporal asks
   for idempotent activities [M1].
3. **Retries multiplying across levels.** Dagster op retries times run retries [D3]; Step Functions
   resets retry counts on redrive [S2]. The same arises when an outside scheduler retries a job that
   retries itself (Control-M reruns [C1], AutoSys `n_retrys` [X1]).
4. **Retry storms.** Prefect's jitter exists to stop many tasks retrying together against one
   system [P3]; Step Functions' `FULL` jitter spreads attempts [S1]. Both are off by default.
5. **Unbounded waits.** Step Functions has no cap on the doubled wait unless set [S1]; Temporal
   retries activities without limit by default [M1]; Databricks `-1` retries forever [B6].
6. **Retrying what cannot pass.** Temporal: permanent failures need a change of input or logic, so
   surface them [M1]; Dagster suggests no retries for validation or permission errors [D1].
7. **Hidden or missing timeouts.** ADF's 12-hour default [F1]; Step Functions activities without
   a timeout run until the execution's maximum, so set a heartbeat [S3]; Temporal: set start-to-close
   because the server cannot see a worker crash by itself [M2].
8. **Timeouts that do not stop the work.** Prefect: a sync task in a thread pool is not interrupted;
   a flow may run on until its next task [P1][P2].
9. **Silent caps.** AutoSys `MaxRestartTrys` lower than `n_retrys` [X3].
10. **Owner as run-as.** Databricks: a job may fail when its run-as user leaves [B4].
11. **Success that hides failure.** ADF's try-catch pattern makes the pipeline succeed when the
    failure branch succeeds [F3].
12. **Late alerts lost on reruns.** Control-M's late alerts fire only for the first run [C3].

---

## 8. Recommended option set for Kilnmark

Fits the rules: retries only when safe; cancels never retried; schedules fire at most once (ADR
0046); no alert on each retry.

### 8.1 Run options, on a pipeline and on a flow

A `run:` block in the pipeline's and the flow's YAML (versioned, as node controls are):

```yaml
run:
  retries: 2         # 0 to 5; default 0
  retry_delay: 5m    # 10s to 1h; default 1m; doubled before each later try, each wait at most 1h
  retry_on: any      # any (default) | lost
  timeout: 2h        # each try; 90s to 7d; no default (no limit)
  warn_after: 1h     # 1m to 7d, less than timeout; no default
```

| Setting | Range | Default | What it does | Grounds |
| --- | --- | --- | --- | --- |
| `retries` | 0 to 5 | 0 | More tries after a failed one, before the run fails and owners are told | Off by default almost everywhere [A4][F1][B6][C1]; same range as node retries |
| `retry_delay` | 10s to 1h | 1m | Wait before the first try again, doubled after, **each wait capped at 1h**, with up to 10% random spread added (not a setting) | Doubling [S1][M1]; cap [A4][M1]; jitter against storms [P3][S1]. Apply the same cap to node retries, whose doubled wait has none today (5 tries from 1h reach 16h) |
| `retry_on` | `any`, `lost` | `any` | `lost` retries only a run lost with its worker or database connection, never a failure the run itself reported | Dagster's crash-only switch [D3]; Argo's `OnError` [R1] |
| `timeout` | 90s to 7d | none | Past it the try is cancelled by Kilnmark and fails; it may be retried, as node timeouts are | Per-try timeout [B2]; no default [A4][B6] |
| `warn_after` | 1m to 7d | none | A run still going past this tells owners once (medium); it does not stop the run | Databricks duration warning [B1]; AutoSys `max_run_alarm` [X4] |

**What is never retried, whatever the settings** (each run says why, "Not tried again: ..."):

- a cancel by a person, a flow or a `stop` node (rule);
- a pipeline whose version has a write that appends, or whose mode a parameter sets (same check
  as node retries, made when saved and again before each try, against the version the run used);
- a sql commit in doubt;
- failures the run reports as its own and that a second try cannot change: a version with
  problems, a missing value, a refusal (rights, allowed hosts, TLS), a quality gate that stopped
  the run, a pipeline below its quality threshold. Retried: a lost worker, a lost database
  connection, a source that could not be reached, a timeout, an engine error (Temporal's and
  Step Functions' split [M1][S1]);
- a try that would start after the schedule's next fire, so a daily run never overlaps the next
  day's (the run fails: "Not tried again: the next scheduled run is due").

**How a retry is a retry, not a new run.**

- Each try belongs to the same run: `run.try` 0, 1, 2 (as `pipeline_run.flow_try` does for nodes).
  It is never a new schedule fire, so at most once still holds; one run at a time still holds, so a
  fire that comes while a try waits is `skipped: a run was still going`.
- A try acts as the first did (the schedule's role and setter, the token, the request key), checked
  again before it starts; a setter who lost the role, or a revoked token, ends the retries.
- `kilnmark-run` keeps waiting across tries and exits with the last try's code. The guide should
  tell scheduler owners to set their own reruns to 0 (Control-M maximum reruns, AutoSys `n_retrys`)
  or to give Kilnmark none, never both.
- **A flow run retried resumes from the failure** (Step Functions, dbt, Argo, Databricks, Dagster
  [S2][T1][R3][B5][D3]): succeeded nodes are not run again and their reported values are kept;
  failed, lost and never-started nodes run. A node lost while running starts again only if it
  could be retried (safe writes). Node `retries` inside the flow keep their own count for each flow
  try.
- **No double counting.** A pipeline run started by a flow node uses the node's `retries`, never the
  pipeline's `run.retries`, so tries do not multiply (Dagster's pitfall [D3]).
- A retry wait holds no worker and no source place, as node retries do.

**Lost runs (server settings).**

| Setting | Range | Default | What it does |
| --- | --- | --- | --- |
| `kilnmark.runs.lost-after` | 30s to 30m | 2m | A running job whose worker has not renewed its lease (or whose lease connection ended) this long counts as lost: failed "lost with its worker", then retried if its `run` options allow. Airflow uses 300 s [A4], Prefect about 9 minutes [P5] |
| `kilnmark.runs.max-retries` | 0 to 5 | 5 | A cap over every pipeline and flow; 0 turns run retries off on this server (Dagster's server-wide maximum [D3]) |

**Notifications.**

- No notice on a try that fails and will be tried again: the run page says "Try 2 of 3 failed;
  trying again in 10m" (Databricks' default for jobs [B3]).
- After the last try: one **Pipeline run failed** or **Flow run failed** (high), saying how many
  tries were made and why no more were, told once per problem as today.
- A run that succeeds after earlier tries tells nobody, but its row and the run list mark it
  **Succeeded on try 3**.
- New: **Pipeline run recovered** and **Flow run recovered** (shown as information, by email at the
  failure's severity), when a run succeeds after a failure that was told, as for quality rules today.
- New: **Run taking longer than expected** (medium), once per run, from `warn_after`.

### 8.2 Owners

Not part of the YAML: who answers for a pipeline is a decision about people, as a schedule's run-as
is, and changing it should not make a new version.

| Setting | Range | Default | What it does |
| --- | --- | --- | --- |
| `owners` on a pipeline or a flow | 0 to 10 entries, each a person (identity) or a team address | The person who first saved it; none when a script's token saved it | Who is told when a run fails after its last try, recovers, or runs long; shown on its page, in lists and in the catalogue; a filter **Mine** |

- **Who may set them**: designers and admins; each change is an audit event (ADR 0029).
- **Not permissions and not run-as.** Owning gives no rights; runs act as their schedule or starter.
  Keeping the two apart is what modern tools do [B4][D6].
- **Told how**: owners get the run's failure, recovery and long-run notices in the app and by
  email (a team address by its own choices), even if their own choices leave out that kind; the
  hourly limit and digest still apply. People who follow pipeline failures today still get them.
- **No owner, or no owner who can be told**: notices go to admins, as high-severity events do today
  (Dagster's fallback [D6]); the pipeline's page says **No owner**; a list of scheduled pipelines
  and flows with no owner sits on the admin's page. A person owner who loses the role, or has not
  signed in for 30 days (as for schedules), counts as unable to be told.

### 8.3 Later, not now

- **Run again from the failure** on a failed flow run, by hand (dbt, Databricks repair, Step
  Functions redrive [T1][B5][S2]).
- **Due by** on a schedule: a time of day, in its zone, by which the run should have succeeded,
  telling owners once if not (Airflow deadlines, Control-M SLA [A3][C4]).

---

## 9. Tool P and Tool H: their run options, in plain words

Both tools run a pipeline from a flow-like unit, with units joined by edges.

| Question | Tool P | Tool H |
| --- | --- | --- |
| Retries and loops on failure | No retry setting on the unit that runs a pipeline. Edges may loop back, so people build retries as a loop with a wait and a counter; the docs warn loops can run forever | Same edges; a **repeat** unit runs a pipeline or flow again and again until a variable is set or an end unit runs in the child, with a delay between runs. A failed run ends the loop rather than retrying |
| Timeouts and waits | A **wait** unit (a delay); a **wait for file** unit. No run time limit on running a pipeline was found | **Wait for** (delay) and **wait for file** units; no run time limit found |
| Notifications | A **mail** unit placed on a failure edge, able to attach the run's log and result files, zipped. The docs warn no mail is sent when the job itself crashes | A **mail** unit with attachments by file kind and priority; not tied to failure edges |
| Run-as and owners | Schedules on its server belong to whoever made them, can be handed to someone with the right role, and run with that person's rights. No owner of a job or pipeline | No owner; a run has the rights of the process (desktop, command line or server) running it |
| Continue on failure or success | Edges: always, on success (true), on failure (false). Units run one after another by default | Edges: always, on success, on failure; an edge may be marked to run the next units at once, and a **join** unit waits for parallel branches |
| Checkpoints and restart | **Checkpoints** on units: reaching one saves its parameters, result rows and files; restarting after a failure starts from the last checkpoint; a checkpoint log table records runs | No checkpoint unit found in its list of units |
| Logging tables | Job, unit and pipeline log tables in a database the designer chooses, with a write interval and days kept; a log file per unit if wanted | Logging is sent to a **pipeline** of the designer's (at start, end or every 30 s), and to an execution-information store chosen in the run settings |
| Lost server, remote runs | A run setting picks local, a remote server or a cluster; the flow can wait for the remote run and pass a stop on to it. What happens when the server is lost is not documented | A remote engine sends the flow and its files to a server and polls it every second or two; finished runs leave the server's status after a day by default. Loss of the server is not documented |
| Start and timing | The start unit can repeat on an interval or at set times while the process runs; the docs point to the server's scheduler for real use | The start unit can repeat; the docs point to cron or an outside scheduler for production |
| Transactions | A flow option opens one connection per database so a failed flow can be rolled back whole | Not checked |

---

## 10. Migration map: Tool P and Tool H jobs/workflows to Kilnmark flows

### 10.1 Units

| Unit (plain meaning) | Kilnmark | Fit |
| --- | --- | --- |
| Start | Nothing: nodes with no connection into them start first | Yes |
| Start that repeats on a timer | A schedule (cron, by the minute, in a zone) | Partly: no repeat more often than a minute; no repeat while one process stays up |
| Run a pipeline | `run-pipeline` (once the pipeline itself is converted) | Yes |
| Run a pipeline once per incoming row | `for-each` over a list or a list parameter (at most 500 items, 200 from a parameter) | Partly: items cannot come from rows a previous node produced |
| Run a sub-job (flow in a flow) | None today; the `run-flow` node is planned (F2b). Flatten the child into the parent, or chain flows from an outside scheduler | No equivalent |
| SQL | `sql` (one transaction, rolled back on failure) | Partly: PostgreSQL only, Allow SQL and writing on, no `${...}` in statements, no queries, at most 20 statements |
| Check a table exists | `check-source` with `tables` | Yes |
| Check a file exists | `check-source` with `files` | Yes |
| Check database connections | `check-source` with no names | Yes |
| Wait for a file | `wait-for` with `file` (every, timeout up to 24h) | Yes |
| Wait for a condition in a table (polling) | `wait-for` with `query` (PostgreSQL, read-only, first column true) | Partly: PostgreSQL only |
| Wait (a delay) | `wait` (up to 24h) | Yes |
| Copy, move, delete files | None today; file actions in flows are planned (ADR 0049, item 11) | No equivalent |
| Zip and unzip | None today; planned with file actions | No equivalent |
| Mail | `notify` (in the app and by email to whoever follows flow notices, with severity, title and message) | Partly: no addresses in the flow, no attachments, no log attached |
| Write to the log | `notify` at `info`, or nothing (the run page records each node) | Partly |
| Evaluate a condition or variable | A connection's condition on what earlier nodes reported and on flow parameters | Partly: no free variables set during the run |
| Evaluate the rows in a table | `wait-for` query with a short timeout, then success and failure connections | Partly: PostgreSQL only; a one-look check node would fit better |
| Set variables | Flow parameters (given at the start) and `${node.value}` from earlier nodes | Partly: nothing can be set mid-run |
| Abort | `stop` with `status: failed` | Yes |
| Success | `stop` with `status: succeeded` | Partly: check whether the source meant "end here" or "clear the error and go on" |
| Loop back on failure (retry pattern) | `retries` and `retry_delay` on the node | Yes when the loop is recognised and every write replaces or upserts; otherwise no equivalent |
| Loop that polls until ready | `wait-for` | Yes when recognised |
| Repeat until a variable is set | None (loops are refused) | No equivalent |
| Parallel branches | Nodes with no connection between them run at once; `max_parallel` on the flow | Yes |
| Join of parallel branches | Automatic: a node waits until every incoming connection has settled | Yes |
| Shell or script | None, by design | No equivalent |
| Edges: always / on success / on failure | `always` / `success` / `failure` | Yes |
| (none in them) | Quality condition on a connection (`load.score >= good`) | Kilnmark only |

### 10.2 Run options

| Their option | Kilnmark |
| --- | --- |
| Retries (loops with a counter and a wait) | Node `retries` and `retry_delay`; the planned run-level `run.retries`. Refused for pipelines that append: flag these for a person |
| Run time limits (none found) | Node `timeout`; planned `run.timeout` and `run.warn_after` |
| Named parameters with defaults | Flow `parameters`, and `with:` on a node |
| Variables set during a run, passed down | Partly: parameters and `${node.value}`; no setting mid-run |
| Log tables, log files | Kilnmark's own run records, run pages and API; the audit log. People who report on their log tables need the run API or an export |
| Checkpoints and restart | None today; the planned flow run retry resumes from the failure; a manual "run again from the failure" later |
| Run-as (schedule maker's rights; process account) | Schedule `run_as` (steward or designer) as the person who set it; tokens for outside schedulers |
| Owner of a schedule | The schedule's setter today; planned `owners` on the pipeline or flow |
| Remote server, cluster, wait for remote | Workers take jobs from the queue; no choice of server. Lost worker: failed today; planned `retry_on: lost` |
| Mail on failure | Built-in **Flow run failed** and **Pipeline run failed**, told by the server, so a crash is still told (the earlier tool's mail is not); owners planned |
| Timer on the start unit | Schedules |
| One transaction across the whole flow | None: each `sql` node is its own transaction |

### 10.3 Gaps that block conversion most often, ranked

Ranked by judgement of how often each appears in such jobs and how hard it is to work round; not
measured.

1. **Sub-jobs.** Nesting flows is the usual structure; there is no `run-flow` node yet.
2. **File operations** (copy, move, delete, zip, transfer). Nearly every file load ends by moving
   or archiving the file; planned, not built.
3. **SQL on other databases, and SQL with values in it.** The `sql` node is PostgreSQL only and
   refuses `${...}`; their SQL units run on any connection, usually with variables.
4. **Variables set during a run** and results passed from one unit to the next (including rows
   driving a per-row loop).
5. **Shell and script units**, which Kilnmark will not run; each needs a person to decide.
6. **Loops.** Retry and polling loops convert when recognised; general loops do not.
7. **Retries on pipelines that append.** A converted retry loop around an appending pipeline cannot
   keep its retry; the importer must drop it and say so.
8. **Mail with addresses and attachments.** `notify` follows people's choices and attaches nothing.
9. **Restart from a checkpoint.**
10. **Log tables that other reports read.**

---

## Sources

Airflow
- [A1] https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/tasks.html
- [A2] https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/logging-monitoring/callbacks.html
- [A3] https://airflow.apache.org/docs/apache-airflow/stable/howto/deadline-alerts.html
- [A4] https://airflow.apache.org/docs/apache-airflow/stable/configurations-ref.html
- [A5] https://airflow.apache.org/docs/task-sdk/stable/api.html
- [A6] https://airflow.apache.org/docs/apache-airflow/stable/howto/add-owner-links.html
- [A7] https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/pools.html
- [A8] https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/priority-weight.html
- [A9] https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html
- [A10] https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dags.html

Dagster
- [D1] https://dagster.io/docs/guides/build/ops/op-retries
- [D2] https://dagster.io/docs/api/dagster/ops
- [D3] https://dagster.io/docs/deployment/execution/run-retries
- [D4] https://dagster.io/docs/deployment/execution/run-monitoring
- [D5] https://dagster.io/docs/guides/observe/alerts/alert-policy-types
- [D6] https://dagster.io/docs/guides/observe/alerts/yaml-reference
- [D7] https://dagster.io/docs/guides/build/assets/metadata-and-tags *(summary)*
- [D8] https://docs.dagster.io/guides/labs/freshness *(summary)*

Prefect
- [P1] https://www.prefect.io/docs/v3/develop/write-tasks
- [P2] https://www.prefect.io/docs/v3/develop/write-flows
- [P3] https://www.prefect.io/docs/v3/how-to-guides/workflows/retries
- [P4] https://www.prefect.io/docs/v3/concepts/states
- [P5] https://www.prefect.io/docs/v3/advanced/detect-zombie-flows
- [P6] https://www.prefect.io/docs/v3/concepts/automations

Azure Data Factory
- [F1] https://learn.microsoft.com/en-us/azure/data-factory/concepts-pipelines-activities
- [F2] https://learn.microsoft.com/en-us/azure/data-factory/monitor-data-factory
- [F3] https://learn.microsoft.com/en-us/azure/data-factory/tutorial-pipeline-failure-error-handling

AWS Step Functions
- [S1] https://docs.aws.amazon.com/step-functions/latest/dg/concepts-error-handling.html
- [S2] https://docs.aws.amazon.com/step-functions/latest/dg/redrive-executions.html
- [S3] https://docs.aws.amazon.com/step-functions/latest/dg/state-task.html

Databricks
- [B1] https://docs.databricks.com/aws/en/jobs/settings
- [B2] https://docs.databricks.com/aws/en/jobs/configure-task
- [B3] https://docs.databricks.com/aws/en/jobs/notifications
- [B4] https://docs.databricks.com/aws/en/jobs/privileges
- [B5] https://docs.databricks.com/aws/en/jobs/repair-job-failures
- [B6] https://docs.databricks.com/gcp/en/reference/jobs-2.0-api (API 2.0; the 2.2 reference was
  not read). Note: [B3] says tasks retry three times by default where [B2] and [B6] say they are not
  retried; the difference is likely serverless jobs [B2].

Argo Workflows
- [R1] https://argo-workflows.readthedocs.io/en/latest/retries/
- [R2] https://argo-workflows.readthedocs.io/en/latest/fields/
- [R3] https://argo-workflows.readthedocs.io/en/latest/cli/argo_retry/
- [R4] https://argo-workflows.readthedocs.io/en/latest/walk-through/timeouts/
- [R5] https://argo-workflows.readthedocs.io/en/latest/walk-through/exit-handlers/

Control-M (one Automation API page answered 403)
- [C1] https://documents.bmc.com/supportu/9.0.20/help/Main_help/en-US/JefMaxRerun.htm
- [C2] https://documents.bmc.com/supportu/9.0.20/help/Main_help/en-US/1341.htm *(summary)*
- [C3] https://documents.bmc.com/supportu/INC/9.0.20/en-US/71635.htm *(summary)*
- [C4] https://documents.bmc.com/supportu/9.0.22/en-US/Documentation/SLA_Management_Job_parameters.htm *(summary)*
- [C5] https://documents.bmc.com/supportu/9.0.20/help/Main_help/en-US/JefRunAs.htm
- [C6] https://documents.bmc.com/supportu/9.0.22/en-US/Documentation/Utilities/ctmshout.htm *(summary)*

AutoSys (the reference guide was not reached; vendor knowledge base and community)
- [X1] https://knowledge.broadcom.com/external/article/50353 *(summary)*; thread on delays:
  https://community.broadcom.com/communities/community-home/digestviewer/viewthread?MID=739669
- [X2] https://knowledge.broadcom.com/external/article/249432/unexpected-failure-alarm-for-every-retry.html
- [X3] https://knowledge.broadcom.com/external/article/17424/parameter-nretrys-does-not-work.html
- [X4] https://knowledge.broadcom.com/external/article/385175/autosys-email-notification-on-failure-an.html
- [X5] https://knowledge.broadcom.com/external/article/433958/need-a-filewatcher-job-to-fail-and-an-al.html *(summary)*
- [X6] https://community.broadcom.com/question/job-owneruser-name-relationship *(summary)*

dbt Cloud
- [T1] https://docs.getdbt.com/docs/deploy/retry-jobs
- [T2] https://docs.getdbt.com/docs/deploy/job-notifications

Temporal
- [M1] https://docs.temporal.io/encyclopedia/retry-policies
- [M2] https://docs.temporal.io/encyclopedia/detecting-activity-failures
- [M3] https://docs.temporal.io/encyclopedia/detecting-workflow-failures

---
