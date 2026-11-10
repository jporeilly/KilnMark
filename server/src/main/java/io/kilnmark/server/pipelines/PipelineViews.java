package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    public record RunRequest(Integer version, Map<String, Object> parameters) {
    }

    /**
     * {@code POST /api/v1/pipelines/preview}: the pipeline as it stands (saved or not), the node to
     * preview, which of its outputs ({@code out}, or its first, when left out) and how many rows
     * (100 when left out, at most 1,000). {@code yaml} is not read for a saved version's preview.
     */
    public record PreviewRequest(String yaml, String node, String port, Integer rows) {
    }

    /** {@code POST /api/v1/pipelines/read-sql}: the pipeline as it stands and the read node to explain. */
    public record ReadSqlRequest(String yaml, String node) {
    }

    /**
     * The SQL a read node sends to its database: {@code sql} with {@code ?} for each of {@code values}
     * bound from its where, or null with {@code note} saying why; a note beside a statement says what
     * else may run.
     */
    public record ReadSql(String node, String sql, int values, String note) {
    }

    // ---- previews ----

    /** A column of a preview: its name and the engine's kind of value; {@code sourceType} is always null here. */
    public record PreviewColumn(String name, String kind, String sourceType) {
    }

    /**
     * The first rows leaving one output of one node. Shaped as a source's preview ({@code asset},
     * {@code columns}, {@code rows}, {@code rowCount}, {@code truncated}, {@code details}) plus:
     * {@code complete}, true when the node gave fewer rows than asked for (these are all of them);
     * {@code millis}, how long the preview took; {@code masked}, the columns whose values are
     * hidden from the caller; {@code waits}, nodes that read all their rows (or one side) before
     * passing any on, in words.
     */
    public record NodePreviewView(String node, String port, String asset, List<PreviewColumn> columns,
                                  List<List<String>> rows, int rowCount, boolean truncated,
                                  java.util.Map<String, String> details, boolean complete, long millis,
                                  List<String> masked, List<String> waits) {
    }

    // ---- responses ----

    /**
     * What validating found: problems with their lines, notes (which do not stop a run), each node
     * with its ports and the columns leaving each port (null when they cannot be known before a
     * run), and the edges.
     */
    public record Validation(boolean valid, String name, String description,
                             List<PipelineCompilerPort.Problem> problems, List<PipelineCompilerPort.Problem> notes,
                             List<PipelineCompilerPort.NodeOutline> nodes, List<PipelineCompilerPort.EdgeOutline> edges) {
    }

    /**
     * A run, without its nodes. {@code rowsWritten} is the rows its writes received, once it has
     * finished. {@code qualityScore} is the lowest measured score (0 to 1) of what it wrote, or null
     * when nothing it wrote was measured; {@code qualityThreshold} the pipeline's {@code quality.min_score}
     * for the version that ran, or null.
     */
    public record RunSummary(long id, String pipeline, int version, String status, String requestedBy,
                             Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested,
                             String error, Long rowsWritten, Double qualityScore, Double qualityThreshold,
                             int tryNumber, int tries, Instant retryAt) {
    }

    /**
     * A pipeline in the list. {@code valid} is whether its latest version can run; {@code library} is
     * {@code sample} for one added from the samples, shown in the Library (ADR 0043), else null.
     */
    public record PipelineSummary(String name, String displayName, String description, int latestVersion,
                                  boolean valid, String updatedBy, Instant updatedAt, RunSummary lastRun,
                                  String library) {
    }

    public record VersionSummary(int n, String message, String author, Instant createdAt, boolean valid,
                                 int problems) {
    }

    /**
     * One saved version. {@code parameters} are those it declares (each with its type, its default, or
     * the server variable its default is, and its description), for the values asked for before a run;
     * none when its YAML cannot be read.
     */
    public record VersionDetail(int n, String yaml, String message, String author, Instant createdAt, boolean valid,
                                List<PipelineCompilerPort.Problem> problems,
                                List<io.kilnmark.server.flows.FlowViews.Parameter> parameters) {
    }

    /**
     * A pipeline with its latest version. Its ETag is the latest version's number. {@code sample} is
     * what the sample it was added from explains about itself, for one in the Library added from the
     * samples ({@code library} {@code sample}); else null.
     */
    public record PipelineDetail(String name, String displayName, String description, int latestVersion,
                                 String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                                 VersionDetail latest, RunSummary lastRun, String library, Samples.About sample) {
    }

    /**
     * One node's part in a run. {@code ports} is {"in": {port: rows}, "out": {port: rows}}, a null
     * count meaning the engine did not report that port on its own. {@code outcome} is how the node
     * ended: finished, ended_early (a limit had its rows, or nothing read on), stopped (by the
     * engine, after another node failed or the run was stopped) or failed; null if not recorded.
     * The times: when it started, had its first row (in; out for a source), handed on its last row
     * (out; in for a sink) and ended, on the worker's clock; null for a node that never started, and
     * for older runs. {@code waitingForRowsMs} and {@code heldUpMs}: the time it waited for rows on
     * its inputs, and for room on its outputs (held up by the nodes after it); the rest is working.
     */
    public record NodeRunView(String node, String type, int position, Long rowsIn, Long rowsOut, JsonNode ports,
                              Long peakMemory, Long durationMs, String error, String outcome, Instant startedAt,
                              Instant firstRowAt, Instant lastRowAt, Instant finishedAt, Long waitingForRowsMs,
                              Long heldUpMs) {
    }

    /** What a write node wrote, and whether it is now in the catalogue. */
    public record WrittenAsset(String node, String urn, String source, String target, String mode, Long rows,
                               boolean registered) {
    }

    /** The written asset {@code output} came from the read asset {@code input}, through {@code node}. */
    public record LineageLink(String node, String input, String output) {
    }

    /**
     * {@code progress}: where the run's rows are, as the worker last wrote it (every two seconds while
     * it runs, and once at its end), or null: {at, slowest, nodes: [{node, state (not_started, waiting,
     * running, ended), rowsIn, rowsOut, rowsPerSecond, startedAt, firstRowAt, lastRowAt, finishedAt}],
     * edges: [{from, fromPort, to, toPort, rows, queued, capacity, full, empty}]}. {@code slowest} is
     * the node holding the run back: its input queues are full while its output queues are not.
     * Counts, states and times only, on the worker's clock ({@code workerStartedAt}, and
     * {@code workerFinishedAt} once it has finished). Rows a second over the run are read on their
     * own ({@code GET .../series}), so this stays small however long the run.
     */
    public record RunDetail(RunSummary run, List<NodeRunView> nodes, List<WrittenAsset> written, List<String> read,
                            List<LineageLink> lineage, List<String> notes, List<NodeQualityView> quality,
                            JsonNode progress, List<io.kilnmark.server.runs.RunTryView> tries) {
    }

    /** {@code PUT .../owners}: the owners, sign-in names and team addresses (ADR 0053). */
    public record OwnersChange(List<String> owners) {
    }

    /**
     * A run's rows a second over time ({@code GET /api/v1/pipeline-runs/{id}/series?after=}): its nodes,
     * how many snapshots a point stands for, and the points after {@code after}: {@code t} ms from the
     * worker's start, {@code r} each node's rows a second and {@code q} its fullest output queue's fill
     * (0 to 1), in {@code nodes}' order.
     */
    public record RunSeries(JsonNode nodes, int stride, List<SeriesPoint> points) {
    }

    public record SeriesPoint(long t, JsonNode r, JsonNode q) {
    }

    /**
     * One event of a run's log. {@code text} is in Kilnmark's words; {@code detail} is an engine's
     * message, which may quote values from the data, and is null with {@code withheld} saying why
     * when the caller may not read it. {@code level} is info, warn or error; {@code kind} one of
     * run_started, node_started, first_row_in, first_row_out, last_row_out, node_ended, engine_note,
     * gate_quarantining, gate_verdict, run_note, run_ended, log_truncated.
     */
    public record RunEvent(int seq, Instant at, String node, String level, String kind, String text, String detail,
                           String withheld) {
    }

    /** A page of a run's log; {@code next} is the {@code after} for the next page, or null at the end. */
    public record RunEvents(List<RunEvent> events, Integer next) {
    }

    /** A score from 0 to 1, overall and per dimension, with its main reasons (words and counts, never values). */
    public record ScoreView(Double overall, java.util.Map<String, Double> dimensions, List<String> reasons) {
    }

    /**
     * One node's quality in a run: each write ({@code role} output) and each gate. {@code measured} is
     * what the run measured, null when it could not (and {@code note} says why); {@code predicted} is
     * the estimate made when the run started from the catalogue's metadata about what it reads, null
     * when none could be made. {@code prediction}: what the estimate says beside its numbers, {unknown,
     * assumptions, rows, gate: {onFail, rowsIn, rowsPassed, rowsQuarantined, decision, rules: [{name,
     * type, field, dimension, origin, failingShare, status, basis}]}}, or null. {@code gate}, for a
     * gate: {onFail, decision (passed, warned, quarantined or stopped), rowsIn, rowsPassed, rowsFailing,
     * rowsQuarantined, minScore, maxFailingShare, stopReason, rules: [{name, type, field, dimension,
     * origin (asset or inline), status, failing, total, message}]}.
     */
    public record NodeQualityView(String node, String role, String asset, ScoreView measured, ScoreView predicted,
                                  String note, JsonNode gate, JsonNode prediction) {

        public NodeQualityView(String node, String role, String asset, ScoreView measured, ScoreView predicted,
                               String note, JsonNode gate) {
            this(node, role, asset, measured, predicted, note, gate, null);
        }
    }

    /** One setting of a node type, for the canvas's settings panel. */
    public record SettingView(String key, String label, String kind, boolean required, Object defaultValue,
                              String help, List<String> choices) {
    }

    /**
     * A node type in the catalogue. Input ports in {@code manyInputs} take any number of edges;
     * {@code outputs} are the fixed output ports (route adds its own). {@code writerSettings}, for
     * {@code write} only (else null): for each kind of source that can be written to, the keys of the
     * writer settings (among {@code settings}) a node writing to such a source takes. {@code category}
     * is the palette folder it is listed in (Read, Write, Filter and route, Transform, Combine,
     * Aggregate, Quality, AI or Other).
     */
    public record NodeType(String type, String kind, String label, String help, List<String> inputs,
                           List<String> manyInputs, List<String> outputs, List<SettingView> settings,
                           java.util.Map<String, List<String>> writerSettings, String category) {

        public NodeType(String type, String kind, String label, String help, List<String> inputs,
                        List<String> manyInputs, List<String> outputs, List<SettingView> settings) {
            this(type, kind, label, help, inputs, manyInputs, outputs, settings, null, null);
        }
    }

    /** Lineage recorded by pipeline writes for one asset. */
    public record AssetLineage(String urn, List<LineageEntry> upstream, List<LineageEntry> downstream) {
    }

    public record LineageEntry(String urn, String pipeline, Long runId, String node, Instant recordedAt) {
    }
}
