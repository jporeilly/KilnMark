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

    /** {@code problems} is a JSON array of {line, column, step, message}. */
    public record Version(String pipeline, int n, String yaml, String message, String author, Instant createdAt,
                          boolean valid, String problems) {
    }

    /** {@code result} is JSON or null; {@code assets} a JSON array of the URNs the run reads and writes. */
    public record Run(long id, String pipeline, int version, String status, String requestedBy, String requestedRole,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested, String error,
                      String result, String assets) {

        public boolean finished() {
            return !status.equals("queued") && !status.equals("running");
        }
    }

    /**
     * {@code portRows} is JSON: {"in": {port: rows}, "out": {port: rows}}. {@code outcome} is how the
     * step ended (finished, ended_early, stopped, failed), or null when the engine did not say.
     */
    public record StepRun(String stepId, String type, int position, Long rowsIn, Long rowsOut, String portRows,
                          Long peakMemory, Long durationMs, String error, String outcome) {
    }

    public record Lineage(String pipeline, Long runId, String stepId, String inputUrn, String outputUrn,
                          Instant recordedAt) {
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_version (pipeline, n, yaml, "
            + "message, author, valid, problems) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)")) {
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
        + "r.assets";

    private static Run run(ResultSet rs, int from) throws SQLException {
        int i = from;
        return new Run(rs.getLong(i), rs.getString(i + 1), rs.getInt(i + 2), rs.getString(i + 3), rs.getString(i + 4),
            rs.getString(i + 5), Db.instant(rs.getTimestamp(i + 6)), Db.instant(rs.getTimestamp(i + 7)),
            Db.instant(rs.getTimestamp(i + 8)), rs.getBoolean(i + 9), rs.getString(i + 10), rs.getString(i + 11),
            rs.getString(i + 12));
    }

    /**
     * Queues a run of one version: the run and a job of kind {@value #JOB_KIND} for a worker, in one
     * transaction. A pipeline job is tried once: its writes are not repeated on their own.
     *
     * @throws Busy while a run of the pipeline is queued or running
     */
    public Run queueRun(String name, int version, String by, String role) {
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
                + "requested_by, requested_role) VALUES (?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, name);
                ps.setInt(2, version);
                ps.setString(3, by);
                ps.setString(4, role);
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

    /** Records the assets a run reads and writes, as soon as its steps are known (for who may read its messages). */
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

    // ---- steps, written assets and lineage ----

    public void saveStepRuns(long runId, List<StepRun> steps) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_step_run (run_id, step_id, "
                + "type, position, rows_in, rows_out, port_rows, peak_memory, duration_ms, error, outcome) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) ON CONFLICT (run_id, step_id) DO UPDATE SET "
                + "type = EXCLUDED.type, position = EXCLUDED.position, rows_in = EXCLUDED.rows_in, "
                + "rows_out = EXCLUDED.rows_out, port_rows = EXCLUDED.port_rows, peak_memory = EXCLUDED.peak_memory, "
                + "duration_ms = EXCLUDED.duration_ms, error = EXCLUDED.error, outcome = EXCLUDED.outcome")) {
                for (StepRun s : steps) {
                    ps.setLong(1, runId);
                    ps.setString(2, s.stepId());
                    ps.setString(3, s.type());
                    ps.setInt(4, s.position());
                    setLong(ps, 5, s.rowsIn());
                    setLong(ps, 6, s.rowsOut());
                    ps.setString(7, s.portRows() == null ? "{}" : s.portRows());
                    setLong(ps, 8, s.peakMemory());
                    setLong(ps, 9, s.durationMs());
                    ps.setString(10, s.error());
                    ps.setString(11, s.outcome());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public List<StepRun> stepRuns(long runId) {
        return db.tx(c -> {
            List<StepRun> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT step_id, type, position, rows_in, rows_out, "
                + "port_rows, peak_memory, duration_ms, error, outcome FROM kilnmark.pipeline_step_run WHERE run_id = ? "
                + "ORDER BY position, step_id")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new StepRun(rs.getString(1), rs.getString(2), rs.getInt(3), getLong(rs, 4),
                            getLong(rs, 5), rs.getString(6), getLong(rs, 7), getLong(rs, 8), rs.getString(9),
                            rs.getString(10)));
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

    public void saveLineage(String pipeline, long runId, String stepId, List<String> inputs, String output) {
        if (inputs.isEmpty()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_lineage (pipeline, run_id, "
                + "step_id, input_urn, output_urn) VALUES (?, ?, ?, ?, ?)")) {
                for (String in : inputs) {
                    ps.setString(1, pipeline);
                    ps.setLong(2, runId);
                    ps.setString(3, stepId);
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
            try (PreparedStatement ps = c.prepareStatement("SELECT pipeline, run_id, step_id, input_urn, output_urn, "
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

    private static Long getLong(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }
}
