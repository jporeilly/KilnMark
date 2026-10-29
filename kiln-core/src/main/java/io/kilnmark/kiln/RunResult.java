package io.kilnmark.kiln;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a pipeline run ended, node by node, with the rows that crossed each edge.
 *
 * @param edges one entry per edge of a pipeline graph, in the graph's order; for a pipeline in a
 *              line, one per pair of neighbouring nodes
 */
public record RunResult(String pipeline, Status status, Instant startedAt, Instant finishedAt,
                        List<NodeResult> nodes, List<EdgeResult> edges, String error) {

    public enum Status { SUCCEEDED, FAILED }

    /** How one node ended. */
    public enum Outcome {
        /** It read all its input and wrote all its output. */
        FINISHED,
        /** It stopped before its input ended (a limit), or every node reading it had finished: not a failure. */
        ENDED_EARLY,
        /** The engine stopped it: another node failed, the run stalled or was stopped. */
        STOPPED,
        /** The run failed here: its own error, or it did not free its memory. */
        FAILED
    }

    /**
     * One node's part in a run. {@code error} is null unless this node is where the run failed.
     *
     * @param rowsOut       rows the node handed on, over all its connected output ports (rows a node
     *                      drops on an unconnected port are not counted)
     * @param rowsIn        rows that arrived on all its input ports
     * @param rowsInByPort  rows that arrived on each connected input port
     * @param rowsOutByPort rows handed on through each connected output port, counted once however
     *                      many nodes read the port
     * @param outcome       how the node ended, so a node the engine stopped is not shown as a clean finish
     * @param startedAt     when its thread started, or null if it never did (or the runner does not time nodes)
     * @param firstRowAt    when its first row arrived; for a node with no input (a source), when it
     *                      handed on its first row. Null when no row came
     * @param lastRowAt     when it handed on its last row; for a node with no output (a sink), when its
     *                      last row arrived. Null when no row went
     * @param finishedAt    when it ended, its resources closed; null if it never started
     * @param waitingForRowsNanos time it spent waiting for a batch on its inputs
     * @param heldUpNanos   time it spent waiting for room on its outputs: held up by the nodes after it
     *                      (back-pressure; a copy for several readers counts against the node writing)
     */
    public record NodeResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                             long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort,
                             Outcome outcome, Instant startedAt, Instant firstRowAt, Instant lastRowAt,
                             Instant finishedAt, long waitingForRowsNanos, long heldUpNanos) {

        public NodeResult {
            rowsInByPort = copy(rowsInByPort);
            rowsOutByPort = copy(rowsOutByPort);
            outcome = error != null ? Outcome.FAILED : outcome != null ? outcome : Outcome.FINISHED;
        }

        /** A node not timed. */
        public NodeResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                          long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort,
                          Outcome outcome) {
            this(name, type, rowsOut, peakMemoryBytes, error, rowsIn, rowsInByPort, rowsOutByPort, outcome, null, null,
                null, null, 0, 0);
        }

        /**
         * Time it spent working: from its start to its end, less the time it waited for rows and was
         * held up by the nodes after it; null when it was not timed.
         */
        public Duration working() {
            Duration d = duration();
            return d == null ? null
                : Duration.ofNanos(Math.max(0, d.toNanos() - waitingForRowsNanos - heldUpNanos));
        }

        /** How long the node ran, from its start to its end; null when it was not timed. */
        public Duration duration() {
            return startedAt == null || finishedAt == null ? null : Duration.between(startedAt, finishedAt);
        }

        /** A node with rows per port; its outcome follows from {@code error}. */
        public NodeResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                          long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort) {
            this(name, type, rowsOut, peakMemoryBytes, error, rowsIn, rowsInByPort, rowsOutByPort, null);
        }

        /** A node in a line: rows out only. */
        public NodeResult(String name, String type, long rowsOut, long peakMemoryBytes, String error) {
            this(name, type, rowsOut, peakMemoryBytes, error, 0, Map.of(), Map.of(), null);
        }

        private static Map<String, Long> copy(Map<String, Long> m) {
            return m == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
        }
    }

    /**
     * The rows that crossed one edge, from an output port of one node to an input port of another.
     * When one output feeds several nodes, each edge counts the rows its reader was given.
     */
    public record EdgeResult(String fromNode, String fromPort, String toNode, String toPort, long rows) {

        public EdgeResult {
            Objects.requireNonNull(fromNode, "fromNode");
            Objects.requireNonNull(fromPort, "fromPort");
            Objects.requireNonNull(toNode, "toNode");
            Objects.requireNonNull(toPort, "toPort");
        }

        @Override
        public String toString() {
            return fromNode + "." + fromPort + " -> " + toNode + "." + toPort + ": " + rows;
        }
    }

    public RunResult {
        nodes = List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A run without edge counts. */
    public RunResult(String pipeline, Status status, Instant startedAt, Instant finishedAt, List<NodeResult> nodes,
                     String error) {
        this(pipeline, status, startedAt, finishedAt, nodes, List.of(), error);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /** The node called {@code name}. */
    public NodeResult node(String name) {
        for (NodeResult s : nodes) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No node called " + name + " in this run");
    }
}
