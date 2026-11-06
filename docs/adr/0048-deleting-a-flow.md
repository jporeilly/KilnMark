# 0048 Deleting a flow

Status: Accepted (6 November 2026)

## Context

A pipeline can be deleted: by a designer or an admin, never while a run of it is queued or running,
with its versions, runs and schedules, while what its runs wrote and the lineage they recorded stay
(`PipelineResource.delete`, ADR 0046 for the schedules). A flow could not be deleted. Flows F3
(docs/design/flows.md) left it open because a flow's runs start pipeline runs, and those stay
linked to the flow run that started them (`pipeline_run.flow_run_id`, V20). What deleting a flow
does to them had to be decided first. The scheduler (ADR 0046) added a second question: a flow's
schedules, and the order the locks are taken in.

## Decision

1. **Deleting a flow works as deleting a pipeline does.** `DELETE /api/v1/flows/{name}`, for
   designers and admins: 204 when deleted, 404 when there is no such flow, 409 while a run of it is
   queued or running (cancel it, or wait for it, then delete). The check and the delete are one
   transaction, with the flow's row locked, so a run queued at the same moment either comes first
   (and the delete is refused) or fails because the flow is gone.
2. **What goes:** the flow, its versions, its runs and each run's node runs. The foreign keys of
   V20 already cascade (`flow_version` from `flow`, `flow_run` from `flow_version`, `flow_node_run`
   from `flow_run`), so no migration is needed.
3. **What stays: the pipeline runs the flow's runs started.** They wrote data and recorded lineage
   and quality results, which belong to the pipelines and the catalogue, not to the flow. They stay
   in their pipelines' histories. Their link to the flow run is set to null by V20's
   `ON DELETE SET NULL`, so nothing links to a run that is gone; the node, item and try numbers
   they were started for stay as written, read by nothing once the flow run is gone. Who asked for
   each (a person, or a schedule) stays too. A schedule's last flow run is set to null the same way
   (V36), though the schedule itself goes (decision 4).
4. **Its schedules go with it, and each is recorded**, exactly as a pipeline's are: deleted in the
   flow delete's own transaction with `DELETE … RETURNING`, and each recorded as
   `schedule.deleted` with `via: flow deleted`, so a schedule added while the flow was being deleted
   is never removed unrecorded. Pipelines and flows share the code (`ScheduleStore.lockFor`,
   `ScheduleStore.deleteFor`, `ScheduleService.recordDeletedWith`).
5. **The flow's schedules are locked before the flow's own row**, the order a scheduled fire takes
   them: the fire holds its schedule's row while it queues the run, which locks the flow. The fire
   holds the one on one connection and asks for the other on another, so PostgreSQL could not see a
   deadlock between them and break it. A test holds a fire between the two, starts a delete, waits
   until PostgreSQL shows the delete waiting, then lets the fire go: the fire queues its run and the
   delete is refused with 409. With the order reversed, the test fails (it times out).
6. **No audit event for the delete itself.** Deleting a pipeline records none either: only the
   schedules deleted with it are recorded. Kilnmark is consistent, and this is a gap for both: who
   deleted a pipeline or a flow, and when, is in the server's log only, not in the audit log. Adding
   `pipeline.deleted` and `flow.deleted` together is a follow-up.
7. **No flow names another flow today**, so none is refused for being used. A flow's nodes are
   `run-pipeline`, `for-each`, `stop`, `notify`, `wait`, `sql`, `check-source` and `wait-for`
   (`FlowDefinition.TYPES`); none runs a flow. When a `run-flow` node arrives (Flows F2b), it must
   decide this: refuse deleting a flow another one names, or fail the calling node at run time as a
   flow does today when a pipeline it runs has been deleted.
8. **A Library sample is deleted like any other flow** (ADR 0043). Its mark is a column of the
   flow's row and goes with it. The Library then shows the sample as not added, and Add samples
   adds it again. On the page, deleting a sample returns to the Library; any other flow returns to
   the Flows list.
9. **The page asks first, on the page.** Delete is on a flow's page for designers and admins. It
   shows a warning on the page, as the pipeline page does before running unsaved work, that names
   the flow, says what is deleted and what stays, and starts with the focus on Cancel. A refusal
   (a run is going) is shown as the page's error.

## Consequences

- A flow's run history goes with it. The pipelines it ran keep their runs, but those no longer say
  which flow run started them.
- A link to one of its runs (in a notification, say) now finds no run.
- Deleting a pipeline and a flow that ran it at the same moment can touch the same pipeline runs.
  Both waits are inside PostgreSQL, so if they ever wait for each other it breaks the deadlock and
  one of the two fails; it can be tried again.
- Follow-up: audit events for deleting a pipeline and a flow (decision 6).
