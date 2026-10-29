package io.kilnmark.server.flows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.kilnmark.server.Db;
import io.kilnmark.server.jobs.JobQueue;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Flows, their versions and runs in the server's database (V20). JSON columns are passed as text;
 * {@link FlowService} reads and writes them.
 */
@ApplicationScoped
public class FlowStore {

    /** The job kind workers run flows under. */
    public static final String JOB_KIND = "flow";

    public record Flow(String name, String displayName, String description, int latestVersion, String createdBy,
                       Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    /** {@code problems} is a JSON array of {line, column, node, message}. */
    public record Version(String flow, int n, String yaml, String message, String author, Instant createdAt,
                          boolean valid, String problems) {
    }

    /** {@code parameters} is a JSON object of the values the run started with. */
    public record Run(long id, String flow, int version, String status, String requestedBy, String requestedRole,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested, String error,
                      String parameters) {

        public boolean finished() {
            return !status.equals("queued") && !status.equals("running");
        }
    }

    /** {@code reported} is a JSON object of what the node reported; {@code pipelineRunId} is null for other nodes. */
    public record NodeRun(String nodeId, String type, int position, String status, Instant startedAt,
                          Instant finishedAt, Long pipelineRunId, String message, String reported) {
    }

    /** A flow with that name exists already. */
    public static final class Exists extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Exists() {
            super("exists", null, false, false);
        }
    }

    /** Someone saved another version since the caller read the flow. */
    public static final class Changed extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Changed() {
            super("changed", null, false, false);
        }
    }

    /** The flow has a run queued or running. */
    public static final class Busy extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Busy() {
            super("busy", null, false, false);
        }
    }

    private final Db db;

    public FlowStore(Db db) {
        this.db = db;
    }

    // ---- flows and versions ----

    private static final String FLOW_COLUMNS = "f.name, f.display_name, f.description, f.latest_version, f.created_by, "
        + "f.created_at, f.updated_by, f.updated_at";

    private static Flow flow(ResultSet rs) throws SQLException {
        return new Flow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
            rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getTimestamp(8).toInstant());
    }

    public Optional<Flow> find(String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + FLOW_COLUMNS
                + " FROM kilnmark.flow f WHERE f.name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(flow(rs)) : Optional.<Flow>empty();
                }
            }
        });
    }

    public List<Flow> list() {
        return db.tx(c -> {
            List<Flow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + FLOW_COLUMNS
                + " FROM kilnmark.flow f ORDER BY f.name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(flow(rs));
                }
            }
            return out;
        });
    }

    /**
     * Saves a flow's next version, creating the flow when there is none ({@code expected} null).
     *
     * @param expected the latest version the caller read, or null to create or to take the latest
     * @throws Exists  when creating and the name is taken
     * @throws Changed when {@code expected} is no longer the latest version
     */
    public Flow save(String name, Integer expected, boolean create, String displayName, String description, String yaml,
                     String message, String by, boolean valid, String problems) {
        return db.tx(c -> {
            int next;
            if (create) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow (name, display_name, "
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
                next = 1;
            } else {
                int latest;
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT latest_version FROM kilnmark.flow WHERE name = ? FOR UPDATE")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("No flow " + name);
                        }
                        latest = rs.getInt(1);
                    }
                }
                if (expected != null && expected != latest) {
                    throw new Changed();
                }
                next = latest + 1;
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.flow SET latest_version = ?, "
                    + "display_name = coalesce(?, display_name), description = ?, updated_by = ?, updated_at = now() "
                    + "WHERE name = ?")) {
                    ps.setInt(1, next);
                    ps.setString(2, displayName);
                    ps.setString(3, description);
                    ps.setString(4, by);
                    ps.setString(5, name);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_version (flow, n, yaml, "
                + "message, author, valid, problems) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)")) {
                ps.setString(1, name);
                ps.setInt(2, next);
                ps.setString(3, yaml);
                ps.setString(4, message);
                ps.setString(5, by);
                ps.setBoolean(6, valid);
                ps.setString(7, problems);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + FLOW_COLUMNS
                + " FROM kilnmark.flow f WHERE f.name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return flow(rs);
                }
            }
        });
    }

    public Optional<Version> version(String flow, int n) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT flow, n, yaml, message, author, created_at, valid, "
                + "problems::text FROM kilnmark.flow_version WHERE flow = ? AND n = ?")) {
                ps.setString(1, flow);
                ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Version(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getBoolean(7),
                        rs.getString(8))) : Optional.<Version>empty();
                }
            }
        });
    }

    // ---- runs ----

    private static final String RUN_COLUMNS = "r.id, r.flow, r.version, r.status, r.requested_by, r.requested_role, "
        + "r.requested_at, r.started_at, r.finished_at, r.cancel_requested, r.error, r.parameters::text";

    private static Instant at(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    private static Run run(ResultSet rs) throws SQLException {
        return new Run(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
            at(rs, 7), at(rs, 8), at(rs, 9), rs.getBoolean(10), rs.getString(11), rs.getString(12));
    }

    /**
     * Queues a run of one version as a job of kind flow, in one transaction.
     *
     * @throws Busy when the flow has a run queued or running
     */
    public Run queueRun(String flow, int version, String by, String role, String parameters) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.flow WHERE name = ? FOR UPDATE")) {
                ps.setString(1, flow);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.flow_run WHERE flow = ? "
                + "AND status IN ('queued', 'running') LIMIT 1")) {
                ps.setString(1, flow);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new Busy();
                    }
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_run (flow, version, "
                + "requested_by, requested_role, parameters) VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id")) {
                ps.setString(1, flow);
                ps.setInt(2, version);
                ps.setString(3, by);
                ps.setString(4, role);
                ps.setString(5, parameters);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            // A flow only decides what runs next; one that was running when its worker stopped is
            // failed, not repeated, as pipelines are: the pipelines it started may have written.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts) "
                + "VALUES ('" + JOB_KIND + "', jsonb_build_object('flowRunId', ?::bigint), 1)")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("NOTIFY " + JobQueue.CHANNEL)) {
                ps.execute();
            }
            return findRun(c, id).orElseThrow();
        });
    }

    private static Optional<Run> findRun(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
            + " FROM kilnmark.flow_run r WHERE r.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(run(rs)) : Optional.<Run>empty();
            }
        }
    }

    public Optional<Run> run(long id) {
        return db.tx(c -> findRun(c, id));
    }

    public List<Run> runs(String flow, int limit) {
        return db.tx(c -> {
            List<Run> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
                + " FROM kilnmark.flow_run r WHERE r.flow = ? ORDER BY r.id DESC LIMIT ?")) {
                ps.setString(1, flow);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(run(rs));
                    }
                }
            }
            return out;
        });
    }

    /** The run a flow job is for. */
    public Optional<Long> runForJob(long jobId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT (payload ->> 'flowRunId')::bigint "
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

    /** Marks a queued run running; false if it was cancelled meanwhile. */
    public boolean startRun(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.flow_run SET status = 'running', "
                + "started_at = now() WHERE id = ? AND status = 'queued' AND NOT cancel_requested")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public void finishRun(long id, String status, String error) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.flow_run SET status = ?, "
                + "finished_at = now(), error = ?, cancel_requested = (? = 'cancelled') "
                + "WHERE id = ? AND status IN ('queued', 'running')")) {
                ps.setString(1, status);
                ps.setString(2, error);
                ps.setString(3, status);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean cancelRequested(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT cancel_requested FROM kilnmark.flow_run WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /** Asks a run to stop: a queued run is cancelled at once and its job taken off the queue. */
    public Optional<Run> requestCancel(long id, String by) {
        return db.tx(c -> {
            Optional<Run> run;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS
                + " FROM kilnmark.flow_run r WHERE r.id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    run = rs.next() ? Optional.of(run(rs)) : Optional.empty();
                }
            }
            if (run.isEmpty() || run.get().finished()) {
                return run;
            }
            boolean queued = run.get().status().equals("queued");
            try (PreparedStatement ps = c.prepareStatement(queued
                ? "UPDATE kilnmark.flow_run SET status = 'cancelled', cancel_requested = true, finished_at = now(), "
                    + "error = ? WHERE id = ?"
                : "UPDATE kilnmark.flow_run SET cancel_requested = true, error = ? WHERE id = ?")) {
                ps.setString(1, "Cancelled by " + by);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            if (queued) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'done', "
                    + "finished_at = now(), last_error = 'Cancelled before it started' WHERE kind = '" + JOB_KIND
                    + "' AND status = 'queued' AND (payload ->> 'flowRunId')::bigint = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            return findRun(c, id);
        });
    }

    /** Whether a worker still has the job of this run (queued or running). */
    public boolean jobActive(long runId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.job WHERE kind = '" + JOB_KIND
                + "' AND status IN ('queued', 'running') AND (payload ->> 'flowRunId')::bigint = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
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
            // Nodes of those runs still marked running will never end: say so.
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kilnmark.flow_node_run s SET status = 'failed', finished_at = now(),
                    message = 'The worker running this flow stopped'
                FROM kilnmark.flow_run r, kilnmark.job j
                WHERE s.run_id = r.id AND s.status = 'running' AND r.status IN ('queued', 'running')
                  AND j.kind = 'flow' AND j.status IN ('failed', 'done')
                  AND (j.payload ->> 'flowRunId')::bigint = r.id""")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kilnmark.flow_run r SET status = 'failed', finished_at = now(),
                    error = 'The worker running this flow stopped before it finished. Check the workers, then run '
                        || 'the flow again; check what the pipelines it had started had written first.'
                FROM kilnmark.job j
                WHERE j.kind = 'flow' AND j.status IN ('failed', 'done')
                  AND (j.payload ->> 'flowRunId')::bigint = r.id AND r.status IN ('queued', 'running')""")) {
                return ps.executeUpdate();
            }
        });
    }

    // ---- nodes ----

    /** Records a node as running, or its ending: one row per node and run, replaced as it changes. */
    public void saveNode(long runId, NodeRun s) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_node_run (run_id, node_id, type, "
                + "position, status, started_at, finished_at, pipeline_run_id, message, reported) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (run_id, node_id) DO UPDATE SET "
                + "status = EXCLUDED.status, started_at = coalesce(EXCLUDED.started_at, flow_node_run.started_at), "
                + "finished_at = EXCLUDED.finished_at, "
                + "pipeline_run_id = coalesce(EXCLUDED.pipeline_run_id, flow_node_run.pipeline_run_id), "
                + "message = EXCLUDED.message, reported = EXCLUDED.reported")) {
                ps.setLong(1, runId);
                ps.setString(2, s.nodeId());
                ps.setString(3, s.type());
                ps.setInt(4, s.position());
                ps.setString(5, s.status());
                ps.setTimestamp(6, s.startedAt() == null ? null : Timestamp.from(s.startedAt()));
                ps.setTimestamp(7, s.finishedAt() == null ? null : Timestamp.from(s.finishedAt()));
                if (s.pipelineRunId() == null) {
                    ps.setNull(8, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(8, s.pipelineRunId());
                }
                ps.setString(9, s.message());
                ps.setString(10, s.reported() == null ? "{}" : s.reported());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<NodeRun> nodeRuns(long runId) {
        return db.tx(c -> {
            List<NodeRun> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT node_id, type, position, status, started_at, "
                + "finished_at, pipeline_run_id, message, reported::text FROM kilnmark.flow_node_run "
                + "WHERE run_id = ? ORDER BY position, node_id")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant started = at(rs, 5);
                        Instant finished = at(rs, 6);
                        long p = rs.getLong(7);
                        Long pipelineRun = rs.wasNull() ? null : p;
                        out.add(new NodeRun(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            started, finished, pipelineRun, rs.getString(8), rs.getString(9)));
                    }
                }
            }
            return out;
        });
    }
}
