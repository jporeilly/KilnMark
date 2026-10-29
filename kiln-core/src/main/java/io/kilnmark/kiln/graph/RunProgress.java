package io.kilnmark.kiln.graph;

import java.time.Instant;
import java.util.List;

/**
 * Where a graph run's rows are at one moment (see {@link RunListener#progress}).
 *
 * @param at      when the snapshot was taken
 * @param nodes   each node, in the graph's order
 * @param edges   each edge, in the graph's order
 * @param slowest the node holding the run back right now, or null: a running node whose input queues
 *                are full while none of its output queues is (it cannot keep up with what it is given,
 *                and what comes after it can). When several are, the one with the most full inputs,
 *                then the first in the graph's order
 */
public record RunProgress(Instant at, List<NodeProgress> nodes, List<EdgeProgress> edges, String slowest) {

    public RunProgress {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** Where a node is in its life. */
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
     * One node.
     *
     * @param rowsPerSecond rows it handled per second since the snapshot before (rows out, or rows
     *                      in for a node with no output); 0 when nothing moved
     * @param waitingForRowsNanos time so far it waited for rows on its inputs
     * @param heldUpNanos   time so far it waited for room on its outputs (held up by the next node)
     * @param outputFill    how full its fullest output queue is now, 0 to 1 (0 for a node with no output)
     */
    public record NodeProgress(String name, State state, long rowsIn, long rowsOut, double rowsPerSecond,
                               Instant startedAt, Instant firstRowAt, Instant lastRowAt, Instant finishedAt,
                               long waitingForRowsNanos, long heldUpNanos, double outputFill) {
    }

    /**
     * One edge, from an output port to an input port.
     *
     * @param rows     rows its reader has been given
     * @param queued   batches waiting on it now
     * @param capacity batches it holds before the writer waits
     * @param full     whether it is full now (or its writer is waiting for room)
     * @param empty    whether nothing waits on it now (or its reader is waiting for a batch)
     * @param fill     batches waiting over capacity, 0 to 1 (1 also while its writer waits for room)
     */
    public record EdgeProgress(String fromNode, String fromPort, String toNode, String toPort, long rows, int queued,
                               int capacity, boolean full, boolean empty, double fill) {
    }

    /** The node called {@code name}, or null. */
    public NodeProgress node(String name) {
        for (NodeProgress s : nodes) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
