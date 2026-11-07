package io.kilnmark.server.runs;

import java.util.List;
import java.util.Optional;

import io.kilnmark.server.schedules.ScheduleStore;

/**
 * What the run lifecycle needs of the store of one kind of run ({@code PipelineStore},
 * {@code FlowStore}). Each implements it with the shared SQL of {@link RunTables}.
 */
public interface RunStore<R extends RunRow> {

    RunKind kind();

    Optional<R> run(long id);

    /** The run a job is for. */
    Optional<Long> runForJob(long jobId);

    /** Marks a queued run running; false when it is no longer queued (cancelled, or already taken). */
    boolean startRun(long id);

    /** Gives an unfinished run its final status. */
    void finishRun(long id, String status, String error);

    boolean cancelRequested(long id);

    /** Asks a run to stop: a queued run is cancelled at once; the run as it now is. */
    Optional<R> requestCancel(long id, String by);

    /** Whether a worker still has the run's job (queued or running). */
    boolean jobActive(long runId);

    /**
     * Deletes the pipeline or flow with its schedules, in one transaction; empty when there is none.
     *
     * @throws RuntimeException the kind's busy refusal while a run is queued or running
     * @throws ScheduleStore.LockWaitTooLong when a lock was not granted in time; nothing was deleted
     */
    Optional<List<ScheduleStore.Gone>> deleteWithSchedules(String name);
}
