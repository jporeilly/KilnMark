package io.kilnmark.kiln;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a pipeline run ended, step by step, with the rows that crossed each edge.
 *
 * @param edges one entry per edge of a pipeline graph, in the graph's order; for a pipeline in a
 *              line, one per pair of neighbouring steps
 */
public record RunResult(String pipeline, Status status, Instant startedAt, Instant finishedAt,
                        List<StepResult> steps, List<EdgeResult> edges, String error) {

    public enum Status { SUCCEEDED, FAILED }

    /**
     * One step's part in a run. {@code error} is null unless this step is where the run failed.
     *
     * @param rowsOut       rows the step handed on, over all its connected output ports (rows a step
     *                      drops on an unconnected port are not counted)
     * @param rowsIn        rows that arrived on all its input ports
     * @param rowsInByPort  rows that arrived on each connected input port
     * @param rowsOutByPort rows handed on through each connected output port, counted once however
     *                      many steps read the port
     */
    public record StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                             long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort) {

        public StepResult {
            rowsInByPort = copy(rowsInByPort);
            rowsOutByPort = copy(rowsOutByPort);
        }

        /** A step in a line: rows out only. */
        public StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error) {
            this(name, type, rowsOut, peakMemoryBytes, error, 0, Map.of(), Map.of());
        }

        private static Map<String, Long> copy(Map<String, Long> m) {
            return m == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
        }
    }

    /**
     * The rows that crossed one edge, from an output port of one step to an input port of another.
     * When one output feeds several steps, each edge counts the rows its reader was given.
     */
    public record EdgeResult(String fromStep, String fromPort, String toStep, String toPort, long rows) {

        public EdgeResult {
            Objects.requireNonNull(fromStep, "fromStep");
            Objects.requireNonNull(fromPort, "fromPort");
            Objects.requireNonNull(toStep, "toStep");
            Objects.requireNonNull(toPort, "toPort");
        }

        @Override
        public String toString() {
            return fromStep + "." + fromPort + " -> " + toStep + "." + toPort + ": " + rows;
        }
    }

    public RunResult {
        steps = List.copyOf(steps);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A run without edge counts. */
    public RunResult(String pipeline, Status status, Instant startedAt, Instant finishedAt, List<StepResult> steps,
                     String error) {
        this(pipeline, status, startedAt, finishedAt, steps, List.of(), error);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /** The step called {@code name}. */
    public StepResult step(String name) {
        for (StepResult s : steps) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No step called " + name + " in this run");
    }
}
