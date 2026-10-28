package io.kilnmark.kiln.graph;

import java.time.Instant;
import java.util.List;

/**
 * Where a graph run's rows are at one moment (see {@link RunListener#progress}).
 *
 * @param at      when the snapshot was taken
 * @param steps   each step, in the graph's order
 * @param edges   each edge, in the graph's order
 * @param slowest the step holding the run back right now, or null: a running step whose input queues
 *                are full while none of its output queues is (it cannot keep up with what it is given,
 *                and what comes after it can). When several are, the one with the most full inputs,
 *                then the first in the graph's order
 */
public record RunProgress(Instant at, List<StepProgress> steps, List<EdgeProgress> edges, String slowest) {

    public RunProgress {
        steps = List.copyOf(steps);
        edges = List.copyOf(edges);
    }

    /** Where a step is in its life. */
    public enum State {
        /** Its thread has not started. */
        NOT_STARTED,
        /** Started, and no row has arrived (or, for a source, left) yet. */
        WAITING,
        /** Rows are moving. */
        RUNNING,
        /** It has ended. */
        ENDED
    }

    /**
     * One step.
     *
     * @param rowsPerSecond rows it handled per second since the snapshot before (rows out, or rows
     *                      in for a step with no output); 0 when nothing moved
     */
    public record StepProgress(String name, State state, long rowsIn, long rowsOut, double rowsPerSecond,
                               Instant startedAt, Instant firstRowAt, Instant lastRowAt, Instant finishedAt) {
    }

    /**
     * One edge, from an output port to an input port.
     *
     * @param rows     rows its reader has been given
     * @param queued   batches waiting on it now
     * @param capacity batches it holds before the writer waits
     * @param full     whether it is full now (or its writer is waiting for room)
     * @param empty    whether nothing waits on it now (or its reader is waiting for a batch)
     */
    public record EdgeProgress(String fromStep, String fromPort, String toStep, String toPort, long rows, int queued,
                               int capacity, boolean full, boolean empty) {
    }

    /** The step called {@code name}, or null. */
    public StepProgress step(String name) {
        for (StepProgress s : steps) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
