# 0053 Run retries, carrying on from a failure, and owners

Status: Accepted (7 November 2026)

## Context

A pipeline or flow run that failed stayed failed. A worker that stopped, or lost its connection to
Kilnmark's database, failed its runs for good: stale-job recovery (`JobQueue.recoverStale`) queues a
job again only while attempts remain, and a pipeline or flow job had one attempt, so no flow was ever
taken over (docs/design/flows.md said so; flow-controls.md C4 read as if one were). Flow nodes could
be tried again (ADR 0041), runs could not. Nobody answered for a pipeline or a flow: its failure went
to whoever followed that kind of notification, or to nobody.

The owner asked for run retries, owners, and Run again from the failure (backlog item 1, 7 November
2026), after research into how other schedulers and orchestrators do it
(docs/design/research/run-options.md, sections 7 and 8). The pitfalls they document shaped what
follows: an alert on every retry, retries that write twice, retries that multiply across levels,
retry storms, unbounded waits, retrying what cannot pass, and an owner who is also the identity a
run acts as.

## Decision

1. **A `run:` block in a pipeline's and a flow's YAML**, versioned with it and checked when saved:

   | Key | Range | Default | What it does |
   | --- | --- | --- | --- |
   | `retries` | 0 to 5 | 0 | More tries after a failed one, before the run fails and its owners are told |
   | `retry_delay` | 10s to 1h | 1m | The wait before the second try; doubled before each later one |
   | `retry_on` | `any`, `lost` | `any` | `lost` tries again only a try lost with its worker or its connection |
   | `timeout` | 90s to 7d | none | How long each try may take; past it the try is stopped and fails |
   | `warn_after` | 1m to 7d, less than `timeout` | none | A run still going past this tells its owners once |

   `retry_delay` and `retry_on` without `retries` are refused, as a node's `retry_delay` is.

2. **Every wait is bounded.** The wait before try `n + 1` is `retry_delay × 2^(n − 1)`, each wait at
   most one hour, plus up to a tenth more, chosen at random, so runs failing together against one
   source do not all come back at the same moment. Five tries again from one hour wait five hours,
   never thirty-one. Node retries (ADR 0041) get the same one-hour cap on each wait, which they did
   not have, without the random part, so a node still says exactly when it tries again.

3. **A retry is a new try of the same run, never a new run.** While it waits, the run is queued
   again (`run_try` counts its tries from 0, `retry_at` says when the next starts), holding no
   worker and no place on a source. So:
   - a schedule's fire is never repeated (ADR 0046's at most once), and one run at a time still
     holds: a fire that comes while a run waits for its next try is `skipped: a run was still going`;
   - `kilnmark-run` and `?wait=` (ADR 0047) keep waiting across tries and see the last try's end; a
     request key gives back the same run;
   - every try uses the run's version, parameters and variables, and acts as the run did (its
     requester and role);
   - each try that ends is recorded (`pipeline_run_try`, `flow_run_try`: its number, how it ended,
     when, why, and whether it was tried again), and the run's page lists them.

4. **What is never tried again, whatever the settings**, saying why ("Not tried again: ..."):
   - a cancel, by a person, by the flow, or by a `stop` node;
   - a refusal a second try cannot change: a version that cannot run, values that cannot be read,
     a refusal as the run is set up (rights, a source that no longer allows writing), a quality
     gate that stopped the run, a flow that a `stop` node failed;
   - a sql node whose commit is in doubt (ADR 0041);
   - a pipeline whose writes are not safe to repeat: every write must replace or upsert, as node
     retries require. A pipeline with `retries` above 0 is refused when saved otherwise; so is a
     flow with `retries` above 0 and `retry_on: any` whose pipelines append. The check is made
     again, against the version the run used, before each try;
   - a try that would start after the schedule's next fire, so a daily run never runs into the
     next day's ("Not tried again: the next scheduled run is due first");
   - a try past the server's own cap, `kilnmark.runs.max-retries` (0 to 5, 5 by default; 0 turns
     run retries off on the server).
   Tried again: an error, a lost worker or connection, a timeout, a source that could not be
   reached, a target another run was writing.

5. **Node retries and run retries never multiply.** A node's own retries take the place of the
   run's for that node:
   - a pipeline run that a flow node starts never uses the pipeline's own `run: retries`: the node's
     `retries` decide, as before;
   - when a flow is tried again by itself, a node with `retries` of its own is never run again: the
     tries it made stand. If such a node failed and nothing handled it, the flow is not tried again,
     since it would fail the same way;
   - a node without `retries` of its own may be run again by the flow's next try, once a try.
   So a node is tried at most `1 + node retries` times, or at most `1 + run retries` times, never
   their product. Run again from the failure (decision 9) is a person's choice and runs such a
   node again with its own tries.

6. **Lost workers are found by the stale recovery there is.** A pipeline's or flow's job is now
   tried as many times as its run may be (`max_attempts` = 1 + its retries, after the server's
   cap; 1 for a run a flow node starts and for a try started by hand). A worker that stops, or loses
   its connection, stops renewing its job's lease; after `kilnmark.runs.lost-after` (2 minutes, 30
   seconds to 30 minutes) recovery queues the job again, and the next worker finds the run still
   running and takes it over: a pipeline starts again from its beginning (when its writes are safe
   to repeat), a flow carries on (decision 7). That wait is the lost try's retry wait. A worker
   that is shutting down, or finds its lease gone, ends the try as lost, and the try is tried again
   as any other is.

7. **A flow carries on from where it failed.** Each try starts from the nodes' rows of the try
   before:
   - a node that succeeded, and every node before it was kept, keeps its result (its values are
     read by later nodes as before) and is not run again;
   - a node that failed and cannot be run again (decision 4, its own retries) is kept as it failed
     when a connection handled the failure; when nothing did, the flow is not tried again;
   - every other node runs again, as its connections fall this time: parallel branches carry on
     each from its own state;
   - a node that ran before and is not kept must be safe to run again (writes that replace or
     upsert; a sql node only after a rollback); if one is not, the flow is not tried again, naming
     it;
   - a for-each node tries only its items that did not succeed;
   - a takeover after a lost worker restores every node exactly as it ended and watches the
     pipeline runs still going, never starting one again; a sql node that was running is failed, its
     commit in doubt; waits keep their start.
   Each try of a node's pipeline run is a run of its own (`flow_try`, V35), so a try of the flow
   starts each node's pipeline run once.

8. **Pipelines start again in full.** A pipeline has no Run again from the failure: its nodes
   stream into one another, so there is no point between them to carry on from. Its earlier tries'
   node details give way to the latest's; its log keeps every try. A long pipeline that should
   carry on becomes stages in a flow, or reads only what is new (backlog item 9).

9. **Run again from the failure**, by hand, on a failed flow run (`POST
   /api/v1/flow-runs/{id}/run-again`, and a button on the run page): stewards and above, as for
   running a flow, and never by someone whose role is below the run's. It is one more try of the
   same run, with the same version, parameters and variables, acting as the run did; it keeps the
   succeeded nodes and runs the rest (decision 7). It is refused while another run of the flow is
   queued or running, and when a node that would run again is not safe to, naming the node and
   why. It is recorded against the run (its try says who asked) and in the audit log
   (`flow.run_again`). A try started by hand is not tried again, nor taken over, by itself.

10. **Owners**, on a pipeline and on a flow: 0 to 10 entries, each a person's sign-in name (as
    Kilnmark records who did something) or a team address an admin has added under Notifications.
    - **Outside the YAML**: who answers for a pipeline is a decision about people, as a schedule's
      run-as is, and changing it is not a new version. Stored on the pipeline's and the flow's row.
    - **By default, whoever first saved it** (existing pipelines and flows get their first saver).
    - **Set** on the pipeline and flow pages by designers and admins; each change is an audit event
      (`pipeline.owners_set`, `flow.owners_set`, ADR 0029), with the list before and after.
    - **No rights, and never the identity a run acts as.** Owning gives nothing: what a person may
      see and do follows their role; runs act as their starter or their schedule.
    - **With no owners**, the page says so, and admins are told in their place.

11. **Notifications.**
    - None on a try that fails and will be tried again: the run page says "Retrying: try 2 of 4,
      next at 14:05".
    - One **Pipeline run failed** or **Flow run failed** (high) after the last try, saying how many
      tries were made and why there were no more, told once per problem as before.
    - New **Pipeline run recovered** and **Flow run recovered**, when a run succeeds after a failure
      that was told; shown as information in the app, by email at the failure's severity, as a
      quality rule's recovery is.
    - New **Run taking longer than expected** (medium), once a run, at `warn_after`.
    - **Owners are always told** of these, in the app, and by email when they have email on with a
      confirmed address, whatever kinds, severity and sources they chose; the hourly limit and the
      digest still apply. Each owner gets what their role may see, as anyone does; a team address
      gets what a viewer gets. Everyone else is told as their own choices say, as now.
    - When no owner can be told (none set, or none with a working token who has used the app
      lately, and no team address with email on), admins are told in their place, the same way.

12. **`timeout` is per try**, measured by the process's run watcher from the try's start. Past it
    the try is stopped as a cancel would stop it and fails ("Took longer than 2h and was stopped");
    it may be tried again. `warn_after` counts from the same start, and is told once a run.

## Consequences

- Migration V39 adds `run_try`, `tries`, `retry_at` and `warned_at` to `pipeline_run` and
  `flow_run`, the two try tables, a pipeline run's `failed_as` (what kind of failure ended it, for
  a flow deciding whether to run its node again), and `owners` on `pipeline` and `flow`.
- External schedulers that retry should not retry what Kilnmark retries: set the scheduler's own
  reruns to 0, or give the run no retries, never both (docs/guide/external-schedulers.md).
- A person owner is told through the token they sign in with; people who sign in through the
  identity provider get no notifications yet (docs/design/notifications.md), so such an owner
  counts as one who cannot be told, and admins are told in their place.
- Not covered by a test: a takeover by a second worker process. The tests drive the takeover on one
  worker by marking its run lost, and test the carry-on plan directly.
- Later: Run again from the failure for pipeline stages, a "due by" time on a schedule, a list of
  scheduled pipelines and flows without owners on the admin's page, and owners in the catalogue.
