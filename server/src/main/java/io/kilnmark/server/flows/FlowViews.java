package io.kilnmark.server.flows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** What the flows API takes and returns (docs/design/flows.md, F3). */
public final class FlowViews {

    private FlowViews() {
    }

    // ---- requests ----

    /** {@code POST /api/v1/flows} and {@code PUT /api/v1/flows/{name}}: the YAML and what changed. */
    public record FlowChange(String yaml, String message) {
    }

    /** {@code POST .../validate}: YAML to check. */
    public record ValidateRequest(String yaml) {
    }

    /** {@code POST .../runs}: the version to run (the latest when left out) and parameter values. */
    public record RunRequest(Integer version, Map<String, Object> parameters) {
    }

    // ---- responses ----

    /** One problem: its line and column (from 1), the node it is in (or null) and what is wrong. */
    public record Problem(int line, int column, String node, String message) {
    }

    /** What checking found; {@code name} is null when the YAML gave none. */
    public record Validation(boolean valid, String name, List<Problem> problems, List<Parameter> parameters) {
    }

    /**
     * A parameter the flow declares: its name, its type as written ({@code text}, {@code number},
     * {@code boolean}, {@code text list}, {@code number list}), its default, or the server variable its
     * default is ({@code ${vars.name}}; its value is not shown here), and its description. Required
     * when it has neither: a run must give it.
     */
    public record Parameter(String name, String type, Object defaultValue, String defaultVariable, boolean required,
                            String description) {
    }

    /**
     * A flow run, without its nodes. {@code parameters} are the values it started with, for stewards
     * and above; null for viewers, since they can hold server variables. {@code error} is in
     * Kilnmark's words and the flow's own, never a pipeline's error. {@code pipelineRuns} is how many
     * pipeline runs it has asked for (queued, running or ended), {@code rowsWritten} the rows they
     * wrote in all (null until one says) and {@code qualityScore} the lowest measured score (0 to 1)
     * among them, each the lowest of what that run wrote (null when nothing was measured).
     */
    public record RunSummary(long id, String flow, int version, String status, String requestedBy,
                             String requestedRole, Instant requestedAt, Instant startedAt, Instant finishedAt,
                             boolean cancelRequested, String error, JsonNode parameters, int pipelineRuns,
                             Long rowsWritten, Double qualityScore) {
    }

    /**
     * A flow in the list. {@code valid} is whether its latest version can run; {@code library} is
     * {@code sample} for one added from the samples, shown in the Library (ADR 0043), else null.
     */
    public record FlowSummary(String name, String displayName, String description, int latestVersion, boolean valid,
                              String updatedBy, Instant updatedAt, RunSummary lastRun, String library) {
    }

    public record VersionSummary(int n, String message, String author, Instant createdAt, boolean valid,
                                 int problems) {
    }

    public record VersionDetail(int n, String yaml, String message, String author, Instant createdAt, boolean valid,
                                List<Problem> problems, List<Parameter> parameters) {
    }

    /** A flow with its latest version. Its ETag is the latest version's number. */
    public record FlowDetail(String name, String displayName, String description, int latestVersion,
                             String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                             VersionDetail latest, RunSummary lastRun, String library) {
    }

    /**
     * One node's part in a run: {@code status} is pending (not started yet), running, succeeded,
     * failed, cancelled or skipped; when it started and ended; the pipeline run it started, for a
     * run-pipeline node; its message, in Kilnmark's words; and what it reported (status, score,
     * quarantined_share, rows_written, duration_s; rows_affected for a sql node), or null.
     * {@code pipelineRunTimes} are the times of the pipeline run it started, for a run-pipeline node
     * that has started one: with the node's own times they say how long it waited before asking for
     * the run (another run of the pipeline was going), how long the run was queued for a worker, and
     * how long it ran.
     */
    public record NodeRunView(String node, String type, int position, String status, Instant startedAt,
                              Instant finishedAt, Long pipelineRunId, String pipeline, String message,
                              JsonNode reported, List<ItemRunView> items, PipelineRunTimes pipelineRunTimes) {

        /** A node that runs no items and started no pipeline run. */
        public NodeRunView(String node, String type, int position, String status, Instant startedAt,
                           Instant finishedAt, Long pipelineRunId, String pipeline, String message,
                           JsonNode reported) {
            this(node, type, position, status, startedAt, finishedAt, pipelineRunId, pipeline, message, reported, null,
                null);
        }
    }

    /** When a pipeline run was asked for, started (taken by a worker) and finished; null until each happens. */
    public record PipelineRunTimes(Instant requestedAt, Instant startedAt, Instant finishedAt) {
    }

    /**
     * One item of a for-each node in a run: its place in the list (from 0), its value (null for
     * viewers when the items came from a parameter of the run, which can hold server variables),
     * and its pipeline run with that run's status ({@code pending} before it starts, {@code queued},
     * running, succeeded, failed or cancelled; {@code skipped} if the node ended without starting it)
     * and times (when it was asked for, taken by a worker, and finished). An item that failed with no
     * pipeline run is one that could not be queued (the pipeline refused its values, or could not be
     * run); the node's message says why.
     */
    public record ItemRunView(int item, Object value, String status, Long pipelineRunId, Instant requestedAt,
                              Instant startedAt, Instant finishedAt) {
    }

    /** A flow run with every node of its version, in the order the YAML gives them. */
    public record RunDetail(RunSummary run, List<NodeRunView> nodes) {
    }
}
