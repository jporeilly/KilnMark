package io.kilnmark.server.pipelines;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.kilnmark.server.Db;
import io.kilnmark.server.jobs.JobQueue;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Pipelines, their versions and runs in the server's database (V12). JSON columns are passed as
 * text; {@link PipelineService} reads and writes them.
 */
@ApplicationScoped
public class PipelineStore {

    /** The job kind workers run pipelines under. */
    public static final String JOB_KIND = "pipeline";

    public record Pipeline(String name, String displayName, String description, int latestVersion, String createdBy,
                           Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    /** {@code problems} is a JSON array of {line, column, node, message}. */
    public record Version(String pipeline, int n, String yaml, String message, String author, Instant createdAt,
                          boolean valid, String problems) {
    }

    /** {@code result} is JSON or null; {@code assets} a JSON array of the URNs the run reads and writes. */
    public record Run(long id, String pipeline, int version, String status, String requestedBy, String requestedRole,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested, String error,
                      String result, String assets, String parameters, String variables) {

        public boolean finished() {
            return !status.equals("queued") && !status.equals("running");
        }
    }

    /**
     * {@code portRows} is JSON: {"in": {port: rows}, "out": {port: rows}}. {@code outcome} is how the
     * node ended (finished, ended_early, stopped, failed), or null when the engine did not say. The
     * times (V19) are null for a node that never started and for runs before V19.
     */
    public record NodeRun(String nodeId, String type, int position, Long rowsIn, Long rowsOut, String portRows,
                          Long peakMemory, Long durationMs, String error, String outcome, Instant startedAt,
                          Instant firstRowAt, Instant lastRowAt, Instant finishedAt, Long waitingForRowsMs,
                          Long heldUpMs) {

        /** A node without times. */
        public NodeRun(String nodeId, String type, int position, Long rowsIn, Long rowsOut, String portRows,
                       Long peakMemory, Long durationMs, String error, String outcome) {
            this(nodeId, type, position, rowsIn, rowsOut, portRows, peakMemory, durationMs, error, outcome, null, null,
                null, null, null, null);
        }
    }

    /**
     * One event of a run's log (V19). {@code text} is the server's own words; {@code detail} an
     * engine's message, which may quote the data. {@code level} is info, warn or error.
     */
    public record Event(int seq, Instant at, String nodeId, String level, String kind, String text, String detail) {
    }

    public record Lineage(String pipeline, Long runId, String nodeId, String inputUrn, String outputUrn,
                          Instant recordedAt) {
    }

    /**
     * A node's measured quality in a run (migration V15). The JSON columns are text here:
     * {@code measuredDimensions} and {@code predictedDimensions} {"dimension": score}, {@code measuredReasons}
     * a list of words, {@code gate} a gate's outcome. {@code predicted} is null until design-time
     * scores exist.
     */
    public record QualityRow(String nodeId, String role, String assetUrn, Double measured, String measuredDimensions,
                             String measuredReasons, Double predicted, String predictedDimensions, String note,
                             String gate, String predictedDetail) {

        public QualityRow(String nodeId, String role, String assetUrn, Double measured, String measuredDimensions,
                          String measuredReasons, Double predicted, String predictedDimensions, String note,
                          String gate) {
            this(nodeId, role, assetUrn, measured, measuredDimensions, measuredReasons, predicted, predictedDimensions,
                note, gate, null);
        }
    }

    /** A pipeline in the list: whether its latest version is valid, and its latest run. */
    public record Summary(Pipeline pipeline, boolean valid, Run lastRun) {
    }

    /** The pipeline was changed by someone else since the version the caller read. */
    public static final class Changed extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Changed(int latest) {
            super("Version " + latest + " is the latest", null, false, false);
        }
    }

    /** A pipeline with that name exists already. */
    public static final class Exists extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Exists() {
            super("exists", null, false, false);
        }
    }

    /** The pipeline has a run queued or running. */
    public static final class Busy extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Busy() {
            super("busy", null, false, false);
        }
    }

    private final Db db;

    public PipelineStore(Db db) {
        this.db = db;
    }

    // ---- pipelines and versions ----

    private static final String PIPELINE_COLUMNS = "p.name, p.display_name, p.description, p.latest_version, "
        + "p.created_by, p.created_at, p.updated_by, p.updated_at";

    private static Pipeline pipeline(ResultSet rs) throws SQLException {
        return new Pipeline(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
            Db.instant(rs.getTimestamp(6)), rs.getString(7), Db.instant(rs.getTimestamp(8)));
    }

    /**
     * Adds a pipeline with its first version.
     *
     * @throws Exists when the name is taken
     */
    public Pipeline create(String name, String displayName, String description, String by, String yaml, String message,
                           boolean valid, String problems) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline (name, display_name, "
                + "description, latest_version, created_by, updated_by) VALUES (?, ?, ?, 1, ?, ?) "
                + "ON CONFLICT (name) DO NOTHING")) {
                ps.setString(1, name);
                ps.setString(2, displayName);
                ps.setString(3, description);
                ps.setString(4, by);
                ps.setString(5, by);
                if (ps.executeUpdate() == 0) {
                    throw new Exists();
                }
            }
            insertVersion(c, name, 1, yaml, message, by, valid, problems);
            return find(c, name).orElseThrow();
        });
    }

    private static void insertVersion(Connection c, String name, int n, String yaml, String message, String by,
                                      boolean valid, String problems) throws SQLException {
        // queries_checked (V27): every caller checks a query read's author before saving (allowQueries).
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_version (pipeline, n, yaml, "
            + "message, author, valid, problems, queries_checked) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, true)")) {
            ps.setString(1, name);
            ps.setInt(2, n);
            ps.setString(3, yaml);
            ps.setString(4, message);
            ps.setString(5, by);
            ps.setBoolean(6, valid);
            ps.setString(7, problems);
            ps.executeUpdate();
        }
    }

    /**
     * Saves a new version. {@code expected}, when not null, is the latest version the caller read.
     *
     * @return the pipeline as it now is; empty when there is no such pipeline
     * @throws Changed when {@code expected} is no longer the latest version
     */
    public Optional<Pipeline> saveVersion(String name, Integer expected, String yaml, String message, String by,
                                          boolean valid, String problems, String displayName, String description) {
        return db.tx(c -> {
            int latest;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT latest_version FROM kilnmark.pipeline WHERE name = ? FOR UPDATE")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    latest = rs.getInt(1);
                }
            }
            if (expected != null && expected != latest) {
                throw new Changed(latest);
            }
            insertVersion(c, name, latest + 1, yaml, message, by, valid, problems);
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline SET latest_version = ?, "
                + "updated_by = ?, updated_at = now(), display_name = coalesce(?, display_name), description = ? "
                + "WHERE name = ?")) {
                ps.setInt(1, latest + 1);
                ps.setString(2, by);
                ps.setString(3, displayName);
                ps.setString(4, description);
                ps.setString(5, name);
                ps.executeUpdate();
            }
            return find(c, name);
        });
    }

    private static Optional<Pipeline> find(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + PIPELINE_COLUMNS
            + " FROM kilnmark.pipeline p WHERE p.name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(pipeline(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Pipeline> pipeline(String name) {
        return db.tx(c -> find(c, name));
    }

    /** Every pipeline by name, with whether its latest version is valid and its latest run. */
    public List<Summary> pipelines() {
        return db.tx(c -> {
            List<Summary> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PIPELINE_COLUMNS + ", v.valid, " + RUN_COLUMNS
                + " FROM kilnmark.pipeline p "
                + "JOIN kilnmark.pipeline_version v ON v.pipeline = p.name AND v.n = p.latest_version "
                + "LEFT JOIN LATERAL (SELECT * FROM kilnmark.pipeline_run x WHERE x.pipeline = p.name "
                + "ORDER BY x.id DESC LIMIT 1) r ON true ORDER BY p.name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getLong(10);
                    Run last = rs.wasNull() ? null : run(rs, 10);
                    out.add(new Summary(pipeline(rs), rs.getBoolean(9), last));
                }
            }
            return out;
        });
    }

    private static final String VERSION_COLUMNS = "pipeline, n, yaml, message, author, created_at, valid, problems";

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
            Db.instant(rs.getTimestamp(6)), rs.getBoolean(7), rs.getString(8));
    }

    /** Every version, newest first. */
    public List<Version> versions(String name) {
        return db.tx(c -> {
            List<Version> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + VERSION_COLUMNS
                + " FROM kilnmark.pipeline_version WHERE pipeline = ? ORDER BY n DESC")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(version(rs));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Whether version {@code n} of {@code name} was saved after the check that a read's query is
     * written by a designer who may see unmasked values (V27); false for an older or missing version.
     */
    public boolean queriesChecked(String name, int n) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT queries_checked FROM kilnmark.pipeline_version "
                + "WHERE pipeline = ? AND n = ?")) {
                ps.setString(1, name);
                ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    public Optional<Version> version(String name, int n) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + VERSION_COLUMNS
                + " FROM kilnmark.pipeline_version WHERE pipeline = ? AND n = ?")) {
                ps.setString(1, name);
                ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(version(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Deletes a pipeline with its versions and runs; the lineage its writes recorded stays.
     *
     * @return false when there is no such pipeline
     * @throws Busy while one of its runs is queued or running
     */
    public boolean delete(String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM kilnmark.pipeline WHERE name = ? FOR UPDATE")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.pipeline_run WHERE pipeline = ? "
                + "AND status IN ('queued', 'running') LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new Busy();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.pipeline WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return true;
        });
    }

    // ---- runs ----

    private static final String RUN_COLUMNS = "r.id, r.pipeline, r.version, r.status, r.requested_by, "
        + "r.requested_role, r.requested_at, r.started_at, r.finished_at, r.cancel_requested, r.error, r.result, "
        + "r.assets, r.parameters::text, r.variables::text";

    private static Run run(ResultSet rs, int from) throws SQLException {
        int i = from;
        return new Run(rs.getLong(i), rs.getString(i + 1), rs.getInt(i + 2), rs.getString(i + 3), rs.getString(i + 4),
            rs.getString(i + 5), Db.instant(rs.getTimestamp(i + 6)), Db.instant(rs.getTimestamp(i + 7)),
            Db.instant(rs.getTimestamp(i + 8)), rs.getBoolean(i + 9), rs.getString(i + 10), rs.getString(i + 11),
            rs.getString(i + 12), rs.getString(i + 13), rs.getString(i + 14));
    }

    /**
     * Queues a run of one version: the run and a job of kind {@value #JOB_KIND} for a worker, in one
     * transaction. A pipeline job is tried once: its writes are not repeated on their own.
     *
     * @throws Busy while a run of the pipeline is queued or running
     */
    public Run queueRun(String name, int version, String by, String role) {
        return queueRun(name, version, by, role, null, null, "{}", "{}");
    }

    /**
     * As {@link #queueRun(String, int, String, String)}, with the values the run starts with: its
     * {@code parameters} and the server's {@code variables} now (JSON objects), which it reads however
     * they change; and, for a run a flow starts, which flow run and node started it ({@code flowRunId}
     * null for a run someone asked for).
     */
    public Run queueRun(String name, int version, String by, String role, Long flowRunId, String flowNode,
                        String parameters, String variables) {
        return db.tx(c -> {
            // One run of a pipeline at a time: the pipeline's row is locked while checking.
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM kilnmark.pipeline WHERE name = ? FOR UPDATE")) {
                ps.setString(1, name);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.pipeline_run WHERE pipeline = ? "
                + "AND status IN ('queued', 'running') LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new Busy();
                    }
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_run (pipeline, version, "
                + "requested_by, requested_role, flow_run_id, flow_node, parameters, variables) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) RETURNING id")) {
                ps.setString(1, name);
                ps.setInt(2, version);
                ps.setString(3, by);
                ps.setString(4, role);
                if (flowRunId == null) {
                    ps.setNull(5, Types.BIGINT);
                } else {
                    ps.setLong(5, flowRunId);
                }
                ps.setString(6, flowNode);
                ps.setString(7, parameters == null ? "{}" : parameters);
                ps.setString(8, variables == null ? "{}" : variables);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts) "
                + "VALUES ('" + JOB_KIND + "', jsonb_build_object('pipelineRunId', ?::bigint), 1)")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("NOTIFY " + JobQueue.CHANNEL)) {
                ps.execute();
            }
            return findRun(c, id).orElseThrow();
        });
    }

    /**
     * What a flow reads from a finished pipeline run: how it ended, and the values its conditions
     * can use. {@code score} is the lowest measured score of what the run wrote and
     * {@code quarantinedShare} the largest share of rows any of its gates set aside; either is null
     * when nothing was measured.
     */
    public record FlowReport(String status, Long rowsWritten, Double score, Double quarantinedShare,
                             Double durationSeconds) {
    }

    /** The report on one run, or empty if there is no such run. */
    public Optional<FlowReport> flowReport(long runId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.status, (r.result ->> 'rowsWritten')::bigint, (r.result -> 'quality' ->> 'score')::double precision,
                       EXTRACT(EPOCH FROM (r.finished_at - r.started_at))::double precision,
                       (SELECT max((q.gate ->> 'rowsQuarantined')::numeric / nullif((q.gate ->> 'rowsIn')::numeric, 0))
                          FROM kilnmark.pipeline_node_quality q
                         WHERE q.run_id = r.id AND q.role = 'gate' AND q.gate IS NOT NULL)::double precision
                FROM kilnmark.pipeline_run r WHERE r.id = ?""")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<FlowReport>empty();
                    }
                    String status = rs.getString(1);
                    long rows = rs.getLong(2);
                    Long rowsWritten = rs.wasNull() ? null : rows;
                    double score = rs.getDouble(3);
                    Double scoreV = rs.wasNull() ? null : score;
                    double secs = rs.getDouble(4);
                    Double secsV = rs.wasNull() ? null : secs;
                    double share = rs.getDouble(5);
                    Double shareV = rs.wasNull() ? null : share;
                    return Optional.of(new FlowReport(status, rowsWritten, scoreV, shareV, secsV));
                }
            }
        });
    }

    /** The run a flow node started, if it has started one (a flow node runs its pipeline once). */
    public Optional<Run> flowNodeRun(long flowRunId, String flowNode) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
                + " FROM kilnmark.pipeline_run r WHERE r.flow_run_id = ? AND r.flow_node = ?")) {
                ps.setLong(1, flowRunId);
                ps.setString(2, flowNode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(run(rs, 1)) : Optional.<Run>empty();
                }
            }
        });
    }

    /** The run a pipeline job is for. */
    public Optional<Long> runForJob(long jobId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT (payload ->> 'pipelineRunId')::bigint "
                + "FROM kilnmark.job WHERE id = ? AND kind = '" + JOB_KIND + "'")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        return rs.wasNull() ? Optional.<Long>empty() : Optional.of(id);
                    }
                    return Optional.<Long>empty();
                }
            }
        });
    }

    private static Optional<Run> findRun(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
            + " FROM kilnmark.pipeline_run r WHERE r.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(run(rs, 1)) : Optional.empty();
            }
        }
    }

    public Optional<Run> run(long id) {
        return db.tx(c -> findRun(c, id));
    }

    /** A pipeline's latest runs, newest first. */
    public List<Run> runs(String name, int limit) {
        return db.tx(c -> {
            List<Run> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
                + " FROM kilnmark.pipeline_run r WHERE r.pipeline = ? ORDER BY r.id DESC LIMIT ?")) {
                ps.setString(1, name);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(run(rs, 1));
                    }
                }
            }
            return out;
        });
    }

    /** Marks a queued run running; false when it is no longer queued (cancelled, or already taken). */
    public boolean startRun(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline_run SET status = 'running', "
                + "started_at = now() WHERE id = ? AND status = 'queued' AND NOT cancel_requested")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Gives an unfinished run its final status. */
    public void finishRun(long id, String status, String error, String result) {
        db.tx(c -> {
            // A cancel asked for too late to stop the run is cleared, so the run does not read as stopping.
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline_run SET status = ?, "
                + "finished_at = now(), error = ?, result = ?::jsonb, cancel_requested = (? = 'cancelled') "
                + "WHERE id = ? AND status IN ('queued', 'running')")) {
                ps.setString(1, status);
                ps.setString(2, error);
                ps.setString(3, result);
                ps.setString(4, status);
                ps.setLong(5, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Asks a run to stop. A queued run is cancelled at once and its job taken off the queue; a
     * running one is marked, and its worker stops it within a few seconds.
     *
     * @return the run as it now is; empty when there is no such run
     */
    public Optional<Run> requestCancel(long id, String by) {
        return db.tx(c -> {
            Optional<Run> run;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
                + " FROM kilnmark.pipeline_run r WHERE r.id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    run = rs.next() ? Optional.of(run(rs, 1)) : Optional.empty();
                }
            }
            if (run.isEmpty() || run.get().finished()) {
                return run;
            }
            boolean queued = run.get().status().equals("queued");
            try (PreparedStatement ps = c.prepareStatement(queued
                ? "UPDATE kilnmark.pipeline_run SET status = 'cancelled', cancel_requested = true, finished_at = now(), "
                    + "error = ? WHERE id = ?"
                : "UPDATE kilnmark.pipeline_run SET cancel_requested = true, error = ? WHERE id = ?")) {
                ps.setString(1, "Cancelled by " + by);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            if (queued) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'done', "
                    + "finished_at = now(), last_error = 'Cancelled before it started' WHERE kind = '" + JOB_KIND
                    + "' AND status = 'queued' AND (payload ->> 'pipelineRunId')::bigint = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            return findRun(c, id);
        });
    }

    /** Records the assets a run reads and writes, as soon as its nodes are known (for who may read its messages). */
    public void setAssets(long id, String assets) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline_run SET assets = ?::jsonb "
                + "WHERE id = ?")) {
                ps.setString(1, assets);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Whether the run's job is still being run by a worker (queued or running); false when the job
     * is done, failed or gone, so a run still marked running has been left behind.
     */
    public boolean jobActive(long runId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.job WHERE kind = '" + JOB_KIND
                + "' AND status IN ('queued', 'running') AND (payload ->> 'pipelineRunId')::bigint = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Whether someone has asked the run to stop. */
    public boolean cancelRequested(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT cancel_requested FROM kilnmark.pipeline_run WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /**
     * Marks failed the runs whose job failed without the run finishing (its worker stopped), so
     * they do not stay running for ever. Returns how many.
     */
    public int failOrphanedRuns() {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kilnmark.pipeline_run r SET status = 'failed', finished_at = now(),
                    error = 'The worker running this pipeline stopped before it finished. Check the workers, then run '
                        || 'the pipeline again; check what its writes had written first.'
                FROM kilnmark.job j
                WHERE j.kind = 'pipeline' AND j.status IN ('failed', 'done')
                  AND (j.payload ->> 'pipelineRunId')::bigint = r.id AND r.status IN ('queued', 'running')""")) {
                return ps.executeUpdate();
            }
        });
    }

    // ---- nodes, written assets and lineage ----

    public void saveNodeRuns(long runId, List<NodeRun> nodes) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_node_run (run_id, node_id, "
                + "type, position, rows_in, rows_out, port_rows, peak_memory, duration_ms, error, outcome, started_at, "
                + "first_row_at, last_row_at, finished_at, waiting_for_rows_ms, held_up_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (run_id, node_id) DO UPDATE SET "
                + "type = EXCLUDED.type, position = EXCLUDED.position, rows_in = EXCLUDED.rows_in, "
                + "rows_out = EXCLUDED.rows_out, port_rows = EXCLUDED.port_rows, peak_memory = EXCLUDED.peak_memory, "
                + "duration_ms = EXCLUDED.duration_ms, error = EXCLUDED.error, outcome = EXCLUDED.outcome, "
                + "started_at = EXCLUDED.started_at, first_row_at = EXCLUDED.first_row_at, "
                + "last_row_at = EXCLUDED.last_row_at, finished_at = EXCLUDED.finished_at, "
                + "waiting_for_rows_ms = EXCLUDED.waiting_for_rows_ms, held_up_ms = EXCLUDED.held_up_ms")) {
                for (NodeRun s : nodes) {
                    ps.setLong(1, runId);
                    ps.setString(2, s.nodeId());
                    ps.setString(3, s.type());
                    ps.setInt(4, s.position());
                    setLong(ps, 5, s.rowsIn());
                    setLong(ps, 6, s.rowsOut());
                    ps.setString(7, s.portRows() == null ? "{}" : s.portRows());
                    setLong(ps, 8, s.peakMemory());
                    setLong(ps, 9, s.durationMs());
                    ps.setString(10, s.error());
                    ps.setString(11, s.outcome());
                    setInstant(ps, 12, s.startedAt());
                    setInstant(ps, 13, s.firstRowAt());
                    setInstant(ps, 14, s.lastRowAt());
                    setInstant(ps, 15, s.finishedAt());
                    setLong(ps, 16, s.waitingForRowsMs());
                    setLong(ps, 17, s.heldUpMs());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void saveQuality(long runId, List<QualityRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_node_quality (run_id, "
                + "node_id, role, asset_urn, measured, measured_dimensions, measured_reasons, predicted, "
                + "predicted_dimensions, note, gate, predicted_detail) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, "
                + "?::jsonb, ?, ?::jsonb, ?::jsonb) "
                + "ON CONFLICT (run_id, node_id) DO UPDATE SET role = EXCLUDED.role, asset_urn = EXCLUDED.asset_urn, "
                + "measured = EXCLUDED.measured, measured_dimensions = EXCLUDED.measured_dimensions, "
                + "measured_reasons = EXCLUDED.measured_reasons, predicted = EXCLUDED.predicted, "
                + "predicted_dimensions = EXCLUDED.predicted_dimensions, note = EXCLUDED.note, gate = EXCLUDED.gate, "
                + "predicted_detail = EXCLUDED.predicted_detail")) {
                for (QualityRow q : rows) {
                    ps.setLong(1, runId);
                    ps.setString(2, q.nodeId());
                    ps.setString(3, q.role());
                    ps.setString(4, q.assetUrn());
                    setDouble(ps, 5, q.measured());
                    ps.setString(6, q.measuredDimensions() == null ? "{}" : q.measuredDimensions());
                    ps.setString(7, q.measuredReasons() == null ? "[]" : q.measuredReasons());
                    setDouble(ps, 8, q.predicted());
                    ps.setString(9, q.predictedDimensions());
                    ps.setString(10, q.note());
                    ps.setString(11, q.gate());
                    ps.setString(12, q.predictedDetail());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** A run's measured quality, node by node, in the order the nodes ran. */
    public List<QualityRow> quality(long runId) {
        return db.tx(c -> {
            List<QualityRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT q.node_id, q.role, q.asset_urn, q.measured, "
                + "q.measured_dimensions::text, q.measured_reasons::text, q.predicted, q.predicted_dimensions::text, "
                + "q.note, q.gate::text, q.predicted_detail::text FROM kilnmark.pipeline_node_quality q "
                + "LEFT JOIN kilnmark.pipeline_node_run s "
                + "ON s.run_id = q.run_id AND s.node_id = q.node_id WHERE q.run_id = ? "
                + "ORDER BY s.position NULLS LAST, q.node_id")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new QualityRow(rs.getString(1), rs.getString(2), rs.getString(3), getDouble(rs, 4),
                            rs.getString(5), rs.getString(6), getDouble(rs, 7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11)));
                    }
                }
            }
            return out;
        });
    }

    public List<NodeRun> nodeRuns(long runId) {
        return db.tx(c -> {
            List<NodeRun> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT node_id, type, position, rows_in, rows_out, "
                + "port_rows, peak_memory, duration_ms, error, outcome, started_at, first_row_at, last_row_at, "
                + "finished_at, waiting_for_rows_ms, held_up_ms FROM kilnmark.pipeline_node_run WHERE run_id = ? "
                + "ORDER BY position, node_id")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new NodeRun(rs.getString(1), rs.getString(2), rs.getInt(3), getLong(rs, 4),
                            getLong(rs, 5), rs.getString(6), getLong(rs, 7), getLong(rs, 8), rs.getString(9),
                            rs.getString(10), Db.instant(rs.getTimestamp(11)), Db.instant(rs.getTimestamp(12)),
                            Db.instant(rs.getTimestamp(13)), Db.instant(rs.getTimestamp(14)), getLong(rs, 15),
                            getLong(rs, 16)));
                    }
                }
            }
            return out;
        });
    }

    // ---- the run's log and progress (V19) ----

    /** How long a write of a run's log or progress may take before the database gives it up, in seconds. */
    static final int INSIGHT_TIMEOUT_SECONDS = 5;

    /**
     * Keeps where a run's rows are now (JSON, counts and times only), replacing the snapshot before.
     * Given up after {@value #INSIGHT_TIMEOUT_SECONDS} seconds: that bounds a slow statement or a lock
     * wait, not a connection that has died without closing.
     */
    public void saveProgress(long runId, String progress) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline_run SET progress = ?::jsonb, "
                + "progress_at = now() WHERE id = ?")) {
                ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
                ps.setString(1, progress);
                ps.setLong(2, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The run's latest progress snapshot as JSON, or null. */
    public String progress(long runId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT progress::text FROM kilnmark.pipeline_run WHERE id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** One point of a run's rows a second: when (ms from the worker's start), and JSON arrays of rates and fills. */
    public record Point(long t, String rates, String fills) {
    }

    /** A run's rows a second: its nodes (a JSON array), how many snapshots a point stands for, and the points. */
    public record Series(String nodes, int stride, List<Point> points) {
    }

    /** Appends one point to a run's rows a second (V19 {@code pipeline_run_point}), with the series' nodes and stride. */
    public void addPoint(long runId, String nodes, int stride, Point p) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_run_point (run_id, t_ms, "
                + "rates, fills) VALUES (?, ?, ?::jsonb, ?::jsonb) ON CONFLICT (run_id, t_ms) DO UPDATE SET "
                + "rates = EXCLUDED.rates, fills = EXCLUDED.fills")) {
                ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
                ps.setLong(1, runId);
                ps.setLong(2, p.t());
                ps.setString(3, p.rates());
                ps.setString(4, p.fills());
                ps.executeUpdate();
            }
            seriesMeta(c, runId, nodes, stride);
            return null;
        });
    }

    /** Replaces a run's points with these (its series merged in pairs), at a new stride, in one transaction. */
    public void replacePoints(long runId, String nodes, int stride, List<Point> points) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.pipeline_run_point WHERE run_id = ?")) {
                ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_run_point (run_id, t_ms, "
                + "rates, fills) VALUES (?, ?, ?::jsonb, ?::jsonb)")) {
                ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
                for (Point p : points) {
                    ps.setLong(1, runId);
                    ps.setLong(2, p.t());
                    ps.setString(3, p.rates());
                    ps.setString(4, p.fills());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            seriesMeta(c, runId, nodes, stride);
            return null;
        });
    }

    private static void seriesMeta(Connection c, long runId, String nodes, int stride) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.pipeline_run SET series_nodes = ?::jsonb, "
            + "series_stride = ? WHERE id = ?")) {
            ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
            ps.setString(1, nodes);
            ps.setInt(2, stride);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    /** A run's points after {@code afterT} (ms), in order, with its nodes and stride; empty nodes when none were kept. */
    public Series series(long runId, long afterT) {
        return db.tx(c -> {
            String nodes = null;
            int stride = 1;
            try (PreparedStatement ps = c.prepareStatement("SELECT series_nodes::text, series_stride FROM "
                + "kilnmark.pipeline_run WHERE id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        nodes = rs.getString(1);
                        int st = rs.getInt(2);
                        stride = rs.wasNull() ? 1 : st;
                    }
                }
            }
            List<Point> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT t_ms, rates::text, fills::text FROM "
                + "kilnmark.pipeline_run_point WHERE run_id = ? AND t_ms > ? ORDER BY t_ms")) {
                ps.setLong(1, runId);
                ps.setLong(2, afterT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Point(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            return new Series(nodes == null ? "[]" : nodes, stride, out);
        });
    }

    /** Adds events to a run's log; their sequence numbers are the caller's (see {@link RunLog}). */
    public void addEvents(long runId, List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_run_event (run_id, seq, at, "
                + "node_id, level, kind, text, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setQueryTimeout(INSIGHT_TIMEOUT_SECONDS);
                for (Event e : events) {
                    ps.setLong(1, runId);
                    ps.setInt(2, e.seq());
                    setInstant(ps, 3, e.at());
                    ps.setString(4, e.nodeId());
                    ps.setString(5, e.level());
                    ps.setString(6, e.kind());
                    ps.setString(7, e.text());
                    ps.setString(8, e.detail());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * A page of a run's log, in order: events after {@code afterSeq}, at least as serious as
     * {@code minLevel} (info, warn or error; null for all) and about {@code node} (null for all).
     */
    public List<Event> events(long runId, String node, String minLevel, int afterSeq, int limit) {
        List<String> levels = minLevel == null || minLevel.equals("info") ? List.of("info", "warn", "error")
            : minLevel.equals("warn") ? List.of("warn", "error") : List.of("error");
        return db.tx(c -> {
            List<Event> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT seq, at, node_id, level, kind, text, detail "
                + "FROM kilnmark.pipeline_run_event WHERE run_id = ? AND seq > ? AND level = ANY (?) "
                + "AND (?::text IS NULL OR node_id = ?) ORDER BY seq LIMIT ?")) {
                ps.setLong(1, runId);
                ps.setInt(2, afterSeq);
                ps.setArray(3, c.createArrayOf("text", levels.toArray()));
                ps.setString(4, node);
                ps.setString(5, node);
                ps.setInt(6, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Event(rs.getInt(1), Db.instant(rs.getTimestamp(2)), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * A finished run of kind 'pipeline' on the written source, which the written asset is recorded
     * under as a scan records what it finds (its fields, row count and profiles).
     */
    public long recordWrite(long sourceId, long rows, String detail) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.run (source_id, kind, status, "
                + "started_at, finished_at, assets_seen, rows_read, detail) VALUES (?, 'pipeline', 'succeeded', now(), "
                + "now(), 1, ?, ?) RETURNING id")) {
                ps.setLong(1, sourceId);
                ps.setLong(2, Math.max(0, rows));
                ps.setString(3, detail);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void saveLineage(String pipeline, long runId, String nodeId, List<String> inputs, String output) {
        if (inputs.isEmpty()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_lineage (pipeline, run_id, "
                + "node_id, input_urn, output_urn) VALUES (?, ?, ?, ?, ?)")) {
                for (String in : inputs) {
                    ps.setString(1, pipeline);
                    ps.setLong(2, runId);
                    ps.setString(3, nodeId);
                    ps.setString(4, in);
                    ps.setString(5, output);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public List<Lineage> lineageOfRun(long runId) {
        return lineage("run_id = ?", runId, null);
    }

    /** Where an asset came from and where it went, by pipeline writes, newest first. */
    public List<Lineage> lineageOfAsset(String urn) {
        return lineage("(input_urn = ? OR output_urn = ?)", null, urn);
    }

    private List<Lineage> lineage(String where, Long runId, String urn) {
        return db.tx(c -> {
            List<Lineage> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT pipeline, run_id, node_id, input_urn, output_urn, "
                + "recorded_at FROM kilnmark.pipeline_lineage WHERE " + where + " ORDER BY recorded_at DESC, id DESC LIMIT 500")) {
                if (runId != null) {
                    ps.setLong(1, runId);
                } else {
                    ps.setString(1, urn);
                    ps.setString(2, urn);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Lineage(rs.getString(1), getLong(rs, 2), rs.getString(3), rs.getString(4),
                            rs.getString(5), Db.instant(rs.getTimestamp(6))));
                    }
                }
            }
            return out;
        });
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setInstant(PreparedStatement ps, int i, Instant v) throws SQLException {
        ps.setTimestamp(i, Db.timestamp(v));
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static Double getDouble(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static Long getLong(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }
}
