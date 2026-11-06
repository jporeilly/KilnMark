# 0047 External schedulers start runs and wait for them, with request keys and a run script

Status: Accepted (6 November 2026). Amends [ADR 0026](0026-flows.md), which said no external
scheduler is needed or used.

## Context

Banks run their batch work from an enterprise scheduler: Control-M, AutoSys, IBM Workload
Automation, and cron or Windows Task Scheduler on smaller sites. The scheduler holds the
dependency chains across every system, the business calendars, the on-call alerts and the audit of
what ran when. Teams will not move those chains into Kilnmark; they want Kilnmark's pipelines and
flows to be steps in them. Kilnmark's own scheduler (ADR 0046, being built) serves teams without
one, and work that lives wholly inside Kilnmark. Both are needed.

The API could already start a run and read it. Three things made it unfit for a scheduler:

- **Starting answers at once (202).** A job that only starts the run ends before the work does,
  and the jobs after it start too early. This is the commonest bug in scheduler integrations.
- **A retry could start the work twice.** Schedulers, agents and networks retry. A start whose
  answer was lost, sent again, queued a second run once the first had ended.
- **No exit code.** A scheduler judges a command by its exit code, 0 to 255; a shell one-liner
  around curl gets that wrong in ways nobody sees until a failure is marked OK.

## Decision

1. **Waiting is on the existing endpoints**: a `wait` query parameter, in whole seconds, on
   `POST /api/v1/pipelines/{name}/runs`, `POST /api/v1/flows/{name}/runs`,
   `GET /api/v1/pipeline-runs/{id}` and `GET /api/v1/flow-runs/{id}`. The answer comes when the run
   has ended or the wait is up: 200 or 202 from a start, 200 from a read, with the run's status
   saying which. Seconds rather than an ISO-8601 duration: every scheduler and shell can write a
   number, and nothing needs parsing.
   - The longest wait is `kilnmark.api.max-wait` (15 minutes); a longer one is cut to it, so a
     caller waits in a loop and no request outlives a proxy's or load balancer's idle timeout.
   - A waiting request runs on a virtual thread and looks at the run's row about once a second. It
     holds no worker thread, and a database connection only for each look.
   - At most `kilnmark.api.max-waiting` (100) requests wait at once on one process; one more is
     refused with 503 and `Retry-After`, before anything starts, so waiting schedulers cannot crowd
     out the API. A read with no wait, or of a run that has ended, is never refused.
   - Polling the database is plain and good enough at this scale. A notification from the worker
     can replace it later without changing the API.
2. **Request keys**: an `Idempotency-Key` header on the two starts, 1 to 200 printable characters.
   - The same key from the same caller (a token, or a person signed in with the identity provider)
     for the same pipeline or flow within 24 hours gives back the run it started first: 200, with
     `Idempotent-Replayed: true`. The same key for another pipeline or flow is refused with 422.
   - A start that is refused keeps no key, so the same key works once the cause is fixed.
   - Keys are in `run_request_key` (migration V37), deleted with their run, and cleaned up after
     24 hours on processes with the scheduler role, as AI usage records are.
   - Race-safe: the key's row is inserted first and its transaction stays open while the run is
     queued, so a second request with the same key waits on the primary key and then finds the
     run. At most four keyed starts run at once on a process, so the two connections each holds
     can never use up the pool.
   - Keys are per caller, so one token cannot learn about, or collide with, another's.
3. **A plain-text answer** for scripts: with `Accept: text/plain` the four endpoints answer with the
   run as `name: value` lines (`id`, `kind`, `name`, `status`, `ended`, `page`, `message`). The
   shell script then needs only `sed`, never `jq` or Python, which bank servers often lack; and a
   Control-M REST job's output rules can match a line. Parsing JSON with `sed` was the other
   choice: it breaks when a message holds an escaped quote or a field moves.
4. **A run script in two editions**, `kilnmark-run.sh` (POSIX sh and curl) and `kilnmark-run.ps1`
   (Windows PowerShell 5.1, with `kilnmark-run.cmd` to run it), shipped at the release's root
   beside `kilnmark.sh` (ADR 0044):
   - It starts the run with a request key (made up and printed if not given), waits in a loop, and
     prints the run's id, page, status and message.
   - Exit codes: 0 succeeded, 1 failed, 2 could not start, 3 timed out waiting (the run is left
     going unless `--cancel-on-timeout`), 4 cancelled, 5 could not reach the server or bad usage.
     Few and fixed, so a scheduler's rules stay short.
   - Lost connections and 503s are retried with the same key, so a retry never starts a second run.
   - The PowerShell edition uses `Invoke-WebRequest` rather than `Invoke-RestMethod`: it needs the
     answer's status and headers, which `Invoke-RestMethod` hides in Windows PowerShell 5.1.
5. **The token is read from a file** (`KILNMARK_TOKEN_FILE`), never from the command line or an
   environment value: process lists, job definitions and job logs show those. The shell edition
   hands it to curl in a config file of its own, readable only by the job's account. Certificate
   checks are never turned off: a private CA is given as a file (`KILNMARK_CA_FILE`), and plain
   `http://` is refused except to the same machine.

## Consequences

- Schedulers can run pipelines and flows inside their chains, today, with their own calendars.
- A key does not re-run failed work: running again after a failure needs a new key. The guide says
  so, and how to build keys from the scheduler's own run number.
- The same work cannot run twice at once in any case: one run of a pipeline or flow at a time
  (ADR 0026, ADR 0040) refuses a second start while the first is going.
- V37 must merge after V36 (the built-in scheduler): Flyway refuses a migration older than the last
  applied one.
- Business calendars inside Kilnmark are a later backlog item; until then the scheduler's own
  calendars decide when jobs run.
- The guide is [Running from an external scheduler](../guide/external-schedulers.md); the design
  note is in [docs/design/pipelines.md](../design/pipelines.md), "Waiting for a run, and request
  keys".
