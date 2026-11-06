# Running from an external scheduler

Most banks run their batch work from an enterprise scheduler: Control-M, AutoSys, IBM Workload
Automation, cron or Windows Task Scheduler. This guide shows how to start Kilnmark's pipelines and
flows from one, inside its dependency chains, so that the job ends when the run ends and says how
it went. Kilnmark's own scheduler is being built too; use whichever fits, or both. Why it works
this way is in [ADR 0047](../adr/0047-external-schedulers.md).

![A scheduler job that waits for the run to end: the scheduler starts kilnmark-run, which starts the run with a request key and waits up to 15 minutes; Kilnmark answers 202 while the run is still going, and kilnmark-run waits again until Kilnmark answers 200 because the run has ended; kilnmark-run then exits with a code, and the scheduler acts on it. The API token is read from a file, never from the command line, the environment or the job's log](images/external-scheduler-flow.svg)

Three things make this safe:

- **The job waits for the run.** Starting a run answers at once, so a scheduler that only starts it
  would mark the job done before the work ends. `kilnmark-run` waits until the run has ended.
- **A retry never runs the work twice.** Every start carries a request key. If the scheduler, the
  network or the script tries again with the same key, Kilnmark gives back the run the first try
  started.
- **The exit code says what happened**, from 0 to 5, so the scheduler can go on, alert or retry.

## Before you start

1. **A token for the scheduler.** An admin opens **API tokens** and makes one with the **Steward**
   role (it can run pipelines and flows, and change nothing else), named after the scheduler, for
   example `scheduler-controlm`, with the expiry your policy sets. See
   [Signing in, roles and tokens](signing-in.md#api-tokens). One token for each scheduler makes the
   audit log say which one started a run.
2. **A file holding the token**, on each machine that runs the jobs (the scheduler's agent),
   readable only by the account the jobs run as. The token is never put on the command line or in
   an environment value: process lists and job logs show those.

   On Linux, as the job's account:

   ```
   mkdir -p ~/.kilnmark && chmod 700 ~/.kilnmark
   (umask 077; cat > ~/.kilnmark/scheduler.token)     paste the token, then Enter and Ctrl+D
   ```

   On Windows, save it as `C:\ProgramData\Kilnmark\scheduler.token`, then let only the job's account
   (here `svc-kmsched`) and administrators read it:

   ```
   icacls C:\ProgramData\Kilnmark\scheduler.token /inheritance:r /grant:r "svc-kmsched:R" "Administrators:F"
   ```

3. **The run script.** It is in the release folder, beside `kilnmark.sh`: `kilnmark-run.sh` for
   Linux and Unix (it needs only `sh` and `curl`), and `kilnmark-run.ps1` with `kilnmark-run.cmd`
   for Windows (Windows PowerShell 5.1 or later). Copy them to the agent machines.
4. **Kilnmark's address**, over HTTPS, and, if a private certificate authority signed Kilnmark's
   certificate, that CA's certificate as a file.

The script reads three settings from the job's environment:

| Setting | What it holds |
| --- | --- |
| `KILNMARK_URL` | Kilnmark's address, for example `https://kilnmark.example.com:8443`. Plain `http://` is refused, except to the same machine |
| `KILNMARK_TOKEN_FILE` | The path of the file holding the token |
| `KILNMARK_CA_FILE` | Optional: the private CA's certificate. Certificate checks are never turned off |

## The run script

```
kilnmark-run.sh pipeline|flow NAME [--param NAME=VALUE]... [--version N] [--timeout 2h]
                [--key REQUEST_KEY] [--cancel-on-timeout]
```

On Windows, run `kilnmark-run.cmd` with the same arguments.

- `--param NAME=VALUE` gives a value for one of the pipeline's or flow's parameters; the others
  take their defaults. A number, `true` or `false` is sent as such, `[...]` as a JSON list, and
  anything else as text. To send a number as text, put it in double quotes: `--param 'code="007"'`.
- `--version N` runs that version; the latest by default.
- `--timeout` stops waiting after that long: `90` (seconds), `30s`, `10m`, `2h` or `1d`. The run
  goes on unless you add `--cancel-on-timeout`. With no timeout, the script waits until the run
  ends.
- `--key` gives the request key: 1 to 200 printable characters. Left out, the script makes one up.
  Either way it prints the key first, so you can run the job again with the same key.

What it prints:

```
Request key: daily-load-20261106-4411
Run: 42
Page: https://kilnmark.example.com:8443/#/pipeline-runs/42
Status: failed
Message: Node out failed: the table lab.orders is locked by another run
```

The script never prints the token. If Kilnmark cannot be reached for a moment, the script tries
again, with the same key, up to five times, ten seconds apart.

## Exit codes

| Code | Meaning | What to do |
| --- | --- | --- |
| 0 | The run succeeded | Nothing: the jobs that depend on it can go on |
| 1 | The run failed | Open its page (the `Page:` line) to see why. Fix the cause, then run the job again with a new key, or with none |
| 2 | The run could not start: no such pipeline or flow, its version has problems, a run of it is already queued or running, a parameter is wrong, the token is not allowed, or the key was used for another pipeline or flow | The message says which. Fix it and run the job again. Nothing ran |
| 3 | Still going when `--timeout` was reached | The run is left going, unless `--cancel-on-timeout` was given. To wait for that same run again, run the job with the key it printed |
| 4 | The run was cancelled | Someone cancelled it on its page or through the API: the page says who. Decide whether to run it again |
| 5 | Kilnmark could not be reached, or the command was wrong | Check the address, the CA file, the token file and the network. The run may have started: run the job again with the key it printed, so that you find that run rather than start another |

## Running again with the same key

A request key stands for one run you mean to start. The same key, from the same token, for the same
pipeline or flow, within 24 hours, always gives back the run it started first, finished or not. So:

- **To find a run again** after a timeout (3) or a lost connection (5), run the job with the key
  it printed: `--key daily-load-20261106-4411`. It waits for that run and exits with how it ends.
- **To run the work again** after it failed (1), use a new key, or none. The old key would give back
  the failed run.
- The same key for another pipeline or flow is refused (2): make keys from the job's name.
- A start that was refused (2) keeps no key, so the same key can be used once the cause is fixed.

![A retry with the same request key gets the same run: a first try with a key starts run 42 and the answer is lost; a retry with the same key finds the key, starts no second run and answers 200 with run 42 and Idempotent-Replayed: true; the same key for another pipeline or flow is refused with 422. A key lasts 24 hours for one token or person, and a request that could not start a run keeps no key](images/request-key-retry.svg)

Good keys come from the scheduler: the job's name, the business date and the scheduler's own run
number, so that a rerun the scheduler counts as new gets a new key. Even with a new key, the work
cannot run twice at once: Kilnmark runs one run of a pipeline or flow at a time and refuses a second
while the first is going (exit code 2).

## Control-M

**As an OS job** (the simplest). On a Linux agent, the job's command:

```
/opt/kilnmark/kilnmark-run.sh pipeline daily-load --timeout 3h --key daily-load-%%ODATE-%%ORDERID-%%RUNCOUNT
```

Set `KILNMARK_URL` and `KILNMARK_TOKEN_FILE` as the job's variables, or in the run-as account's
profile. Control-M takes exit code 0 as Ended OK and any other as Ended Not OK; add **On Do**
actions on the codes you want handled apart, for example a notice to the data team on 1 and a
rerun after ten minutes on 5. On a Windows agent the command is
`C:\Kilnmark\kilnmark-run.cmd pipeline daily-load ...`.

**As a Web Services REST job**, without the script. Keep the token in the job's connection profile,
as Control-M keeps other secrets, and send it as `Authorization: Bearer <token>`. Then:

1. A request that starts the run and waits:
   `POST /api/v1/pipelines/daily-load/runs?wait=900` with the header
   `Idempotency-Key: daily-load-%%ODATE-%%ORDERID-%%RUNCOUNT`, the header `Accept: text/plain`, and
   the body `{}` (or `{"parameters": {...}}`).
2. While the answer says `ended: false`, a request that waits again:
   `GET /api/v1/pipeline-runs/<id>?wait=900` with `Accept: text/plain`, taking the id from the
   first answer's `id:` line.
3. Output handling that ends the job OK only on the line `status: succeeded`.

The `Accept: text/plain` answer is one `name: value` a line, which output rules match easily:

```
id: 42
kind: pipeline
name: daily-load
status: running
ended: false
page: /#/pipeline-runs/42
message:
```

## AutoSys

A command job, on an agent where the script and the token file are:

```
insert_job: kilnmark_daily_load   job_type: CMD
machine: etl-agent-01
owner: kmsched
command: /opt/kilnmark/kilnmark-run.sh pipeline daily-load --timeout 3h
envvars: KILNMARK_URL=https://kilnmark.example.com:8443, KILNMARK_TOKEN_FILE=/home/kmsched/.kilnmark/scheduler.token
max_exit_success: 0
std_out_file: /var/log/kilnmark/kilnmark_daily_load.out
std_err_file: /var/log/kilnmark/kilnmark_daily_load.err
```

AutoSys marks the job FAILURE on any code but 0. Jobs that depend on it start with
`condition: success(kilnmark_daily_load)`. The key the script made is in the job's output file, for
a rerun that should find the same run.

## cron and systemd timers

A line in `/etc/cron.d/kilnmark`, run as the job's account at 02:00:

```
KILNMARK_URL=https://kilnmark.example.com:8443
KILNMARK_TOKEN_FILE=/home/kmsched/.kilnmark/scheduler.token
0 2 * * * kmsched /opt/kilnmark/kilnmark-run.sh pipeline daily-load --timeout 3h >> /var/log/kilnmark/daily-load.log 2>&1
```

cron does nothing with the exit code itself: a systemd timer records it, and `systemctl status`
shows a failed run. `/etc/systemd/system/kilnmark-daily-load.service`:

```
[Unit]
Description=Kilnmark: run the daily-load pipeline

[Service]
Type=oneshot
User=kmsched
Environment=KILNMARK_URL=https://kilnmark.example.com:8443
Environment=KILNMARK_TOKEN_FILE=/home/kmsched/.kilnmark/scheduler.token
ExecStart=/opt/kilnmark/kilnmark-run.sh pipeline daily-load --timeout 3h
```

and `/etc/systemd/system/kilnmark-daily-load.timer`:

```
[Unit]
Description=Kilnmark: daily-load at 02:00

[Timer]
OnCalendar=*-*-* 02:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

Then `sudo systemctl enable --now kilnmark-daily-load.timer`. The run's output is in
`journalctl -u kilnmark-daily-load`.

## Windows Task Scheduler

A small file for the job, `C:\ProgramData\Kilnmark\daily-load.cmd`:

```
@echo off
set KILNMARK_URL=https://kilnmark.example.com:8443
set KILNMARK_TOKEN_FILE=C:\ProgramData\Kilnmark\scheduler.token
call C:\Kilnmark\kilnmark-run.cmd pipeline daily-load --timeout 3h >> C:\ProgramData\Kilnmark\logs\daily-load.log 2>&1
exit /b %errorlevel%
```

Then, as an administrator, a task that runs it at 02:00 as the job's account (it asks for the
account's password):

```
schtasks /Create /TN "Kilnmark daily-load" /SC DAILY /ST 02:00 /RU svc-kmsched /RP * /TR "C:\ProgramData\Kilnmark\daily-load.cmd"
```

The task's **Last Run Result** is the exit code: `0x0` succeeded, `0x1` failed, and so on up to
`0x5`. If a private CA signed Kilnmark's certificate, either import it into the machine's
**Trusted Root Certification Authorities** store, or set `KILNMARK_CA_FILE`; the second works with
Windows PowerShell 5.1, which `kilnmark-run.cmd` uses.

## Calling the API yourself

The script uses four endpoints, which any scheduler or program can call with a steward token:

- `POST /api/v1/pipelines/{name}/runs` and `POST /api/v1/flows/{name}/runs` start a run.
- `GET /api/v1/pipeline-runs/{id}` and `GET /api/v1/flow-runs/{id}` read one.

On all four, `wait` is a whole number of seconds: the answer comes when the run has ended, or when
the wait is up, whichever is first. A start answers 200 when the run has ended and 202 when it is
still going; a read answers 200 either way, and the run's `status` says which (`queued` and
`running` are still going; `succeeded`, `failed` and `cancelled` have ended). A longer wait than
the server allows is cut to it (15 minutes unless the operator changed it), so wait in a loop.

On the two starts, the `Idempotency-Key` header carries the request key. An answer that gives back
the run an earlier request started is 200 and has the header `Idempotent-Replayed: true`. A key for
another pipeline or flow is refused with 422.

With `Accept: text/plain`, all four answer with the run as `name: value` lines (shown under
Control-M above), for tools that cannot read JSON. A refusal is JSON either way:
`{"error": "..."}`.

**Settings › API documentation** (`/api/docs`) describes each endpoint in full, and lets you try it.

## Limits

Waiting requests cost the server almost nothing: they sleep on light threads and look at the run
about once a second. Two settings, which the operator sets, keep many waiting schedulers from
crowding out everyone else:

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.api.max-wait` | `15m` | The longest one request waits |
| `kilnmark.api.max-waiting` | `100` | Requests that may wait at the same time, on each server with the api role |

One more waiting request than allowed is refused with 503 and `Retry-After: 5`, and starts nothing;
the script waits as told and asks again. Reading a run without `wait` is never refused this way.

## What is not here yet

- Business calendars (holidays, working days, the last working day of the month) in Kilnmark
  itself. Your scheduler's calendars decide when jobs run.
- Starting a run with a key a person chooses in the app: **Run now** never needs one.
