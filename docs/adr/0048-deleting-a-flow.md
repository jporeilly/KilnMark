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
   designers and admins (checked by the API's roles and again in `FlowService`, as saving and
   running are): 204 when deleted, 404 when there is no such flow, 409 while a run of it is queued
   or running (cancel it, or wait for it, then delete). The check and the delete are one
   transaction, with the flow's row locked, so a run queued, or a version saved, at the same moment
   either comes first (and the delete is refused) or finds the flow gone and is answered 404. A
   run's job ends a moment after the run is recorded as ended, and tells of a failure in between;
   the delete waits for that too (409 for that moment), so a run's failure is not told of a flow
   already deleted. One failed because its worker stopped is told outside any job and can still be
   (docs/design/flows.md, section 4).
2. **What goes:** the flow, its versions, its runs and each run's node runs. The foreign keys of
   V20 already cascade (`flow_version` from `flow`, `flow_run` from `flow_version`, `flow_node_run`
   from `flow_run`), so no migration is needed. The request keys that started its runs (V37,
   ADR 0047) go with the runs, by their own cascade: a retry with such a key starts a new run, as a
   key whose run is gone does.
3. **What stays: the pipeline runs the flow's runs started.** They wrote data and recorded lineage
   and quality results, which belong to the pipelines and the catalogue, not to the flow. They stay
   in their pipelines' histories. Their link to the flow run is set to null by V20's
   `ON DELETE SET NULL`, so nothing links to a run that is gone; the node, item and try numbers
   they were started for stay as written, read by nothing once the flow run is gone. Who asked for
   each (a person, or a schedule) stays too. A schedule's last flow run is set to null the same way
   (V36), though the schedule itself goes (decision 4). The `job` rows of its runs (kind `flow`)
   stay, as a deleted pipeline's runs leave theirs: they name only a run id, and nothing reads a
   finished job's run again.
4. **Its schedules go with it, and each is recorded**, exactly as a pipeline's are: deleted in the
   flow delete's own transaction with `DELETE … RETURNING`, and each recorded as
   `schedule.deleted` with `via: flow deleted`, so a schedule added while the flow was being deleted
   is never removed unrecorded. The events are written once the delete has committed, by the audit
   log's own writer. Pipelines and flows share the code (`ScheduleStore.lockFor`,
   `ScheduleStore.deleteFor`, `ScheduleService.recordDeletedWith`).
5. **Its open problem for notifications is cleared.** A failed run is told once until a run of the
   flow succeeds (`notify_state`, key `flow:<name>`). The delete clears that key in its own
   transaction, so a flow saved later under the same name is told of at its first failure. Deleting
   a pipeline now clears `pipeline:<name>` the same way. A pipeline's delete does not wait for its
   run's job, so a failure told in the moment after its run ended can open the key again; it closes
   at that name's next success.
6. **The flow's schedules are locked before the flow's own row**, the order a scheduled fire takes
   them: the fire holds its schedule's row while it queues the run, which locks the flow. The fire
   holds the one on one connection and asks for the other on another, so PostgreSQL could not see a
   deadlock between them and break it. A test holds a fire between the two, starts a delete, waits
   until PostgreSQL shows the delete waiting, then lets the fire go: the fire queues its run and the
   delete is refused because the run is queued. With the order reversed, the delete gives up on its
   lock instead and the test fails.
7. **Every lock wait in the delete is limited to 5 seconds** (`lock_timeout`, for pipelines and
   flows alike). The order in decision 6 covers the schedules the flow has when the delete starts. A
   schedule changed to run the flow just after that, then fired, is not locked by the delete, so the
   two could still wait for each other across two connections. What is guaranteed is that a delete
   never hangs: if any lock is not granted in time, the delete is rolled back, nothing is deleted,
   and the answer is 409 saying to try again. The fire it waited on then goes ahead. A start with a
   request key (ADR 0047) takes the flow's row, then its key's row; the delete takes the flow's row
   before the keys its cascade deletes, the same order. A start checked before the delete and
   queued after it finds the flow gone and is answered 404, and its key is not kept.
8. **No audit event for the delete itself.** Deleting a pipeline records none either: only the
   schedules deleted with it are recorded. Kilnmark is consistent, and this is a gap for both: who
   deleted a pipeline or a flow, and when, is in the server's log only, not in the audit log. Adding
   `pipeline.deleted` and `flow.deleted` together is a follow-up.
9. **No flow names another flow today**, so none is refused for being used. A flow's nodes are
   `run-pipeline`, `for-each`, `stop`, `notify`, `wait`, `sql`, `check-source` and `wait-for`
   (`FlowDefinition.TYPES`); none runs a flow. When a `run-flow` node arrives (Flows F2b), it must
   decide this: refuse deleting a flow another one names, or fail the calling node at run time as a
   flow does today when a pipeline it runs has been deleted.
10. **A Library sample is deleted like any other flow** (ADR 0043). Its mark is a column of the
    flow's row and goes with it. The Library then shows the sample as not added, and Add samples
    adds it again. On the page, deleting a sample returns to the Library; any other flow returns to
    the Flows list.
11. **The page asks first, on the page.** Delete is on a flow's page for designers and admins. It
    shows a warning on the page, as the pipeline page does before running unsaved work, that names
    the flow, says what is deleted and what stays, says that changes not saved on the page are lost
    too, and starts with the focus on Cancel. It is a group named by its text, announced once as the
    focus moves, not an alert as well. A refusal is shown as the page's error.

## Consequences

- A flow's run history goes with it. The pipelines it ran keep their runs, but those no longer say
  which flow run started them.
- A link to one of its runs (in a notification, say) now finds no run.
- Deleting a pipeline and a flow that ran it at the same moment can touch the same pipeline runs.
  Both waits are inside PostgreSQL, so if they ever wait for each other it breaks the deadlock and
  one of the two fails; it can be tried again.
- A delete can be refused for a moment, while a scheduled run is starting or a run's job is
  finishing; trying again a little later works.
- Follow-up: audit events for deleting a pipeline and a flow (decision 8).
