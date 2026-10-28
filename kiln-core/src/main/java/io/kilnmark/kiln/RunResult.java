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

    /** How one step ended. */
    public enum Outcome {
        /** It read all its input and wrote all its output. */
        FINISHED,
        /** It stopped before its input ended (a limit), or every step reading it had finished: not a failure. */
        ENDED_EARLY,
        /** The engine stopped it: another step failed, the run stalled or was stopped. */
        STOPPED,
        /** The run failed here: its own error, or it did not free its memory. */
        FAILED
    }

    /**
     * One step's part in a run. {@code error} is null unless this step is where the run failed.
     *
     * @param rowsOut       rows the step handed on, over all its connected output ports (rows a step
     *                      drops on an unconnected port are not counted)
     * @param rowsIn        rows that arrived on all its input ports
     * @param rowsInByPort  rows that arrived on each connected input port
     * @param rowsOutByPort rows handed on through each connected output port, counted once however
     *                      many steps read the port
     * @param outcome       how the step ended, so a step the engine stopped is not shown as a clean finish
     * @param startedAt     when its thread started, or null if it never did (or the runner does not time steps)
     * @param firstRowAt    when its first row arrived; for a step with no input (a source), when it
     *                      handed on its first row. Null when no row came
     * @param lastRowAt     when it handed on its last row; for a step with no output (a sink), when its
     *                      last row arrived. Null when no row went
     * @param finishedAt    when it ended, its resources closed; null if it never started
     */
    public record StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                             long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort,
                             Outcome outcome, Instant startedAt, Instant firstRowAt, Instant lastRowAt,
                             Instant finishedAt) {

        public StepResult {
            rowsInByPort = copy(rowsInByPort);
            rowsOutByPort = copy(rowsOutByPort);
            outcome = error != null ? Outcome.FAILED : outcome != null ? outcome : Outcome.FINISHED;
        }

        /** A step not timed. */
        public StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                          long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort,
                          Outcome outcome) {
            this(name, type, rowsOut, peakMemoryBytes, error, rowsIn, rowsInByPort, rowsOutByPort, outcome, null, null,
                null, null);
        }

        /** How long the step ran, from its start to its end; null when it was not timed. */
        public Duration duration() {
            return startedAt == null || finishedAt == null ? null : Duration.between(startedAt, finishedAt);
        }

        /** A step with rows per port; its outcome follows from {@code error}. */
        public StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error,
                          long rowsIn, Map<String, Long> rowsInByPort, Map<String, Long> rowsOutByPort) {
            this(name, type, rowsOut, peakMemoryBytes, error, rowsIn, rowsInByPort, rowsOutByPort, null);
        }

        /** A step in a line: rows out only. */
        public StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error) {
            this(name, type, rowsOut, peakMemoryBytes, error, 0, Map.of(), Map.of(), null);
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
