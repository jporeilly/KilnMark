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
     * What validating found: problems with their lines, each step with its ports and the columns
     * leaving each port (null when they cannot be known before a run), and the edges.
     */
    public record Validation(boolean valid, String name, String description,
                             List<PipelineCompilerPort.Problem> problems, List<PipelineCompilerPort.StepOutline> steps,
                             List<PipelineCompilerPort.EdgeOutline> edges) {
    }

    /** A run, without its steps. {@code rowsWritten} is the rows its writes received, once it has finished. */
    public record RunSummary(long id, String pipeline, int version, String status, String requestedBy,
                             Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested,
                             String error, Long rowsWritten) {
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
     * count meaning the engine did not report that port on its own.
     */
    public record StepRunView(String step, String type, int position, Long rowsIn, Long rowsOut, JsonNode ports,
                              Long peakMemory, Long durationMs, String error) {
    }

    /** What a write step wrote, and whether it is now in the catalogue. */
    public record WrittenAsset(String step, String urn, String source, String target, String mode, Long rows,
                               boolean registered) {
    }

    /** The written asset {@code output} came from the read asset {@code input}, through {@code step}. */
    public record LineageLink(String step, String input, String output) {
    }

    public record RunDetail(RunSummary run, List<StepRunView> steps, List<WrittenAsset> written, List<String> read,
                            List<LineageLink> lineage, List<String> notes) {
    }

    /** One setting of a step type, for the canvas's settings panel. */
    public record SettingView(String key, String label, String kind, boolean required, Object defaultValue,
                              String help, List<String> choices) {
    }

    /**
     * A step type in the catalogue. Input ports in {@code manyInputs} take any number of edges;
     * {@code outputs} are the fixed output ports (route adds its own).
     */
    public record StepType(String type, String kind, String label, String help, List<String> inputs,
                           List<String> manyInputs, List<String> outputs, List<SettingView> settings) {
    }

    /** Lineage recorded by pipeline writes for one asset. */
    public record AssetLineage(String urn, List<LineageEntry> upstream, List<LineageEntry> downstream) {
    }

    public record LineageEntry(String urn, String pipeline, Long runId, String step, Instant recordedAt) {
    }
}
