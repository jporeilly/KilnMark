package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** What the pipelines API takes and returns (docs/design/pipelines.md section 6). */
public final class PipelineViews {

    private PipelineViews() {
    }

    // ---- requests ----

    /** {@code POST /api/v1/pipelines}: a new pipeline and its first version. */
    public record NewPipeline(String name, String displayName, String description, String yaml, String message) {
    }

    /** {@code PUT /api/v1/pipelines/{name}}: a new version; display name and description optional. */
    public record PipelineChange(String yaml, String message, String displayName, String description) {
    }

    /** {@code POST .../validate}: YAML to check. {@code name} is the pipeline's, for a pipeline not yet saved. */
    public record ValidateRequest(String yaml, String name) {
    }

    /** {@code POST .../runs}: the version to run; the latest when left out. */
    public record RunRequest(Integer version) {
    }

    // ---- responses ----

    /**
     * What validating found: problems with their lines, notes (which do not stop a run), each step
     * with its ports and the columns leaving each port (null when they cannot be known before a
     * run), and the edges.
     */
    public record Validation(boolean valid, String name, String description,
                             List<PipelineCompilerPort.Problem> problems, List<PipelineCompilerPort.Problem> notes,
                             List<PipelineCompilerPort.StepOutline> steps, List<PipelineCompilerPort.EdgeOutline> edges) {
    }

    /**
     * A run, without its steps. {@code rowsWritten} is the rows its writes received, once it has
     * finished. {@code qualityScore} is the lowest measured score (0 to 1) of what it wrote, or null
     * when nothing it wrote was measured; {@code qualityThreshold} the pipeline's {@code quality.min_score}
     * for the version that ran, or null.
     */
    public record RunSummary(long id, String pipeline, int version, String status, String requestedBy,
                             Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested,
                             String error, Long rowsWritten, Double qualityScore, Double qualityThreshold) {
    }

    /** A pipeline in the list. {@code valid} is whether its latest version can run. */
    public record PipelineSummary(String name, String displayName, String description, int latestVersion,
                                  boolean valid, String updatedBy, Instant updatedAt, RunSummary lastRun) {
    }

    public record VersionSummary(int n, String message, String author, Instant createdAt, boolean valid,
                                 int problems) {
    }

    public record VersionDetail(int n, String yaml, String message, String author, Instant createdAt, boolean valid,
                                List<PipelineCompilerPort.Problem> problems) {
    }

    /** A pipeline with its latest version. Its ETag is the latest version's number. */
    public record PipelineDetail(String name, String displayName, String description, int latestVersion,
                                 String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                                 VersionDetail latest, RunSummary lastRun) {
    }

    /**
     * One step's part in a run. {@code ports} is {"in": {port: rows}, "out": {port: rows}}, a null
     * count meaning the engine did not report that port on its own. {@code outcome} is how the step
     * ended: finished, ended_early (a limit had its rows, or nothing read on), stopped (by the
     * engine, after another step failed or the run was stopped) or failed; null if not recorded.
     * The times: when it started, had its first row (in; out for a source), handed on its last row
     * (out; in for a sink) and ended; null for a step that never started, and for older runs.
     */
    public record StepRunView(String step, String type, int position, Long rowsIn, Long rowsOut, JsonNode ports,
                              Long peakMemory, Long durationMs, String error, String outcome, Instant startedAt,
                              Instant firstRowAt, Instant lastRowAt, Instant finishedAt) {
    }

    /** What a write step wrote, and whether it is now in the catalogue. */
    public record WrittenAsset(String step, String urn, String source, String target, String mode, Long rows,
                               boolean registered) {
    }

    /** The written asset {@code output} came from the read asset {@code input}, through {@code step}. */
    public record LineageLink(String step, String input, String output) {
    }

    /**
     * {@code progress}: where the run's rows are, as the worker last wrote it (every two seconds while
     * it runs, and once at its end), or null: {at, slowest, steps: [{step, state (not_started, waiting,
     * running, ended), rowsIn, rowsOut, rowsPerSecond, startedAt, firstRowAt, lastRowAt, finishedAt}],
     * edges: [{from, fromPort, to, toPort, rows, queued, capacity, full, empty}]}. {@code slowest} is
     * the step holding the run back: its input queues are full while its output queues are not.
     * Counts, states and times only.
     */
    public record RunDetail(RunSummary run, List<StepRunView> steps, List<WrittenAsset> written, List<String> read,
                            List<LineageLink> lineage, List<String> notes, List<StepQualityView> quality,
                            JsonNode progress) {
    }

    /**
     * One event of a run's log. {@code text} is in Kilnmark's words; {@code detail} is an engine's
     * message, which may quote values from the data, and is null with {@code withheld} saying why
     * when the caller may not read it. {@code level} is info, warn or error; {@code kind} one of
     * run_started, step_started, first_row_in, first_row_out, last_row_out, step_ended, engine_note,
     * run_note, run_ended, log_truncated.
     */
    public record RunEvent(int seq, Instant at, String step, String level, String kind, String text, String detail,
                           String withheld) {
    }

    /** A page of a run's log; {@code next} is the {@code after} for the next page, or null at the end. */
    public record RunEvents(List<RunEvent> events, Integer next) {
    }

    /** A score from 0 to 1, overall and per dimension, with its main reasons (words and counts, never values). */
    public record ScoreView(Double overall, java.util.Map<String, Double> dimensions, List<String> reasons) {
    }

    /**
     * One step's quality in a run: each write ({@code role} output) and each gate. {@code measured} is
     * what the run measured, null when it could not (and {@code note} says why); {@code predicted} is
     * the estimate made when the run started from the catalogue's metadata about what it reads, null
     * when none could be made. {@code prediction}: what the estimate says beside its numbers, {unknown,
     * assumptions, rows, gate: {onFail, rowsIn, rowsPassed, rowsQuarantined, decision, rules: [{name,
     * type, field, dimension, origin, failingShare, status, basis}]}}, or null. {@code gate}, for a
     * gate: {onFail, decision (passed, warned, quarantined or stopped), rowsIn, rowsPassed, rowsFailing,
     * rowsQuarantined, minScore, maxFailingShare, stopReason, rules: [{name, type, field, dimension,
     * origin (asset or inline), status, failing, total, message}]}.
     */
    public record StepQualityView(String step, String role, String asset, ScoreView measured, ScoreView predicted,
                                  String note, JsonNode gate, JsonNode prediction) {

        public StepQualityView(String step, String role, String asset, ScoreView measured, ScoreView predicted,
                               String note, JsonNode gate) {
            this(step, role, asset, measured, predicted, note, gate, null);
        }
    }

    /** One setting of a step type, for the canvas's settings panel. */
    public record SettingView(String key, String label, String kind, boolean required, Object defaultValue,
                              String help, List<String> choices) {
    }

    /**
     * A step type in the catalogue. Input ports in {@code manyInputs} take any number of edges;
     * {@code outputs} are the fixed output ports (route adds its own). {@code writerSettings}, for
     * {@code write} only (else null): for each kind of source that can be written to, the keys of the
     * writer settings (among {@code settings}) a step writing to such a source takes. {@code category}
     * is the palette folder it is listed in (Read, Write, Filter and route, Transform, Combine,
     * Aggregate, Quality, AI or Other).
     */
    public record StepType(String type, String kind, String label, String help, List<String> inputs,
                           List<String> manyInputs, List<String> outputs, List<SettingView> settings,
                           java.util.Map<String, List<String>> writerSettings, String category) {

        public StepType(String type, String kind, String label, String help, List<String> inputs,
                        List<String> manyInputs, List<String> outputs, List<SettingView> settings) {
            this(type, kind, label, help, inputs, manyInputs, outputs, settings, null, null);
        }
    }

    /** Lineage recorded by pipeline writes for one asset. */
    public record AssetLineage(String urn, List<LineageEntry> upstream, List<LineageEntry> downstream) {
    }

    public record LineageEntry(String urn, String pipeline, Long runId, String step, Instant recordedAt) {
    }
}
