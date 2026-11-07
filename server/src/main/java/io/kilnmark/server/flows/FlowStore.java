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
import io.kilnmark.server.runs.RunKind;
import io.kilnmark.server.runs.RunRow;
import io.kilnmark.server.runs.RunTables;
import io.kilnmark.server.schedules.ScheduleStore;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Flows, their versions and runs in the server's database (V20). JSON columns are passed as text;
 * {@link FlowService} reads and writes them.
 */
@ApplicationScoped
public class FlowStore implements io.kilnmark.server.runs.RunStore<FlowStore.Run> {

    /** The job kind workers run flows under. */
    public static final String JOB_KIND = "flow";

    /**
     * Flows in the run lifecycle they share with pipelines (docs/design/pipelines.md, "One run
     * lifecycle"): a queued run says nothing while it waits, a run records no result, and a delete
     * waits for a run's job to end too (ADR 0048, decision 1).
     */
    public static final RunKind KIND = new RunKind(JOB_KIND, ScheduleStore.Target.FLOW, Busy::new, Missing::new,
        false, false, true);

    /** {@code library}: {@code sample} for one added from the samples (ADR 0043), else null. */
    public record Flow(String name, String displayName, String description, int latestVersion, String createdBy,
                       Instant createdAt, String updatedBy, Instant updatedAt, String library) {
    }

    /** {@code problems} is a JSON array of {line, column, node, message}. */
    public record Version(String flow, int n, String yaml, String message, String author, Instant createdAt,
                          boolean valid, String problems) {
    }

    /**
     * {@code parameters} is a JSON object of the values the run started with. {@code runTry} is its try
     * from 0, {@code tries} how many it may have by itself, and {@code retryAt} when its next try starts
     * while it waits (ADR 0053).
     */
    public record Run(long id, String flow, int version, String status, String requestedBy, String requestedRole,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, boolean cancelRequested, String error,
                      String parameters, String variables, int runTry, int tries, Instant retryAt) implements RunRow {

        @Override
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

    /** The flow named is not there (it was deleted a moment ago). */
    public static final class Missing extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Missing() {
            super("missing", null, false, false);
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

    /**
     * Whether version {@code n} of {@code name} was saved after the check that a sql node is written
     * by a designer who may see unmasked values (V29); false for an older or missing version.
     */
    public boolean sqlChecked(String name, int n) {
        return checked(name, n, "sql_checked");
    }

    /**
     * Whether version {@code n} of {@code name} was saved by someone who may write SQL a person writes,
     * as recorded since V34 (a wait-for node's query); false for an older or missing version.
     */
    public boolean queryChecked(String name, int n) {
        return checked(name, n, "query_checked");
    }

    private boolean checked(String name, int n, String column) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM kilnmark.flow_version "
                + "WHERE flow = ? AND n = ?")) {
                ps.setString(1, name);
                ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /**
     * Deletes the flow with its versions, its runs and their node runs (each table cascades from the
     * one before, V20) and, in the same transaction, its schedules, returning exactly those, so each
     * can be recorded (ADR 0048); empty when there is no such flow. The pipeline runs its runs
     * started stay, as what they wrote and the lineage they recorded stay: their link to the flow run
     * is set to null by its foreign key. Its open problem for notifications is cleared, so a flow
     * saved later under the name is told of afresh.
     *
     * @throws Busy when a run is queued or running, or the job of one is still finishing
     * @throws ScheduleStore.LockWaitTooLong when a lock was not granted in time; nothing was deleted
     */
    @Override
    public Optional<List<ScheduleStore.Gone>> deleteWithSchedules(String name) {
        return RunTables.delete(db, KIND, name);
    }

    // ---- flows and versions ----

    private static final String FLOW_COLUMNS = "f.name, f.display_name, f.description, f.latest_version, f.created_by, "
        + "f.created_at, f.updated_by, f.updated_at, f.library";

    private static Flow flow(ResultSet rs) throws SQLException {
        return new Flow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
            rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getString(9));
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

    /** A flow in the list: whether its latest version can run, and its latest run (or null). */
    public record Summary(Flow flow, boolean valid, Run lastRun) {
    }

    /** Every flow by name, each with its latest version's validity and its latest run, in one query. */
    public List<Summary> summaries() {
        return db.tx(c -> {
            List<Summary> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + FLOW_COLUMNS + ", v.valid, " + RUN_COLUMNS
                + " FROM kilnmark.flow f JOIN kilnmark.flow_version v ON v.flow = f.name AND v.n = f.latest_version"
                + " LEFT JOIN LATERAL (SELECT * FROM kilnmark.flow_run x WHERE x.flow = f.name ORDER BY x.id DESC"
                + " LIMIT 1) r ON true ORDER BY f.name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Flow f = flow(rs);
                    boolean valid = rs.getBoolean(10);
                    rs.getLong(11);
                    out.add(new Summary(f, valid, rs.wasNull() ? null : run(rs, 10)));
                }
            }
            return out;
        });
    }

    /** A flow's versions, newest first, without their YAML. */
    public List<FlowViews.VersionSummary> versions(String flow) {
        return db.tx(c -> {
            List<FlowViews.VersionSummary> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT n, message, author, created_at, valid, "
                + "coalesce(jsonb_array_length(problems), 0) FROM kilnmark.flow_version WHERE flow = ? "
                + "ORDER BY n DESC")) {
                ps.setString(1, flow);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new FlowViews.VersionSummary(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getTimestamp(4).toInstant(), rs.getBoolean(5), rs.getInt(6)));
                    }
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
     * @throws Missing when saving a version of a flow that is not there
     */
    public Flow save(String name, Integer expected, boolean create, String displayName, String description, String yaml,
                     String message, String by, boolean valid, String problems) {
        return save(name, expected, create, displayName, description, yaml, message, by, valid, problems, null);
    }

    /**
     * As {@link #save(String, Integer, boolean, String, String, String, String, String, boolean, String)};
     * a flow created here goes in the library {@code library} ({@code sample}, ADR 0043) or none
     * (null), in the same transaction. A later version leaves the mark as it is.
     */
    public Flow save(String name, Integer expected, boolean create, String displayName, String description, String yaml,
                     String message, String by, boolean valid, String problems, String library) {
        return save(name, expected, create, displayName, description, yaml, message, by, valid, problems, library, false);
    }

    /**
     * As {@link #save(String, Integer, boolean, String, String, String, String, String, boolean, String, String)},
     * recording {@code sqlChecked}: whether the version's author may write SQL a person writes (ADR 0028).
     * A version saved without it never runs a node with SQL.
     */
    public Flow save(String name, Integer expected, boolean create, String displayName, String description, String yaml,
                     String message, String by, boolean valid, String problems, String library, boolean sqlChecked) {
        return db.tx(c -> {
            int next;
            if (create) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow (name, display_name, "
                    + "description, latest_version, created_by, updated_by, library) VALUES (?, ?, ?, 1, ?, ?, ?) "
                    + "ON CONFLICT (name) DO NOTHING")) {
                    ps.setString(1, name);
                    ps.setString(2, displayName);
                    ps.setString(3, description);
                    ps.setString(4, by);
                    ps.setString(5, by);
                    ps.setString(6, library);
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
                            throw new Missing();
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
            // sql_checked (V29) and query_checked (V34): whether the author may write SQL; FlowService.save also
            // refuses SQL without it.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_version (flow, n, yaml, "
                + "message, author, valid, problems, sql_checked, query_checked) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")) {
                ps.setString(1, name);
                ps.setInt(2, next);
                ps.setString(3, yaml);
                ps.setString(4, message);
                ps.setString(5, by);
                ps.setBoolean(6, valid);
                ps.setString(7, problems);
                ps.setBoolean(8, sqlChecked);
                ps.setBoolean(9, sqlChecked);
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
        + "r.requested_at, r.started_at, r.finished_at, r.cancel_requested, r.error, r.parameters::text, "
        + "r.variables::text, r.run_try, r.tries, r.retry_at";

    private static Instant at(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    private static Run run(ResultSet rs) throws SQLException {
        return run(rs, 0);
    }

    /** The run in RUN_COLUMNS from column {@code from + 1}. */
    private static Run run(ResultSet rs, int from) throws SQLException {
        return new Run(rs.getLong(from + 1), rs.getString(from + 2), rs.getInt(from + 3), rs.getString(from + 4),
            rs.getString(from + 5), rs.getString(from + 6), at(rs, from + 7), at(rs, from + 8), at(rs, from + 9),
            rs.getBoolean(from + 10), rs.getString(from + 11), rs.getString(from + 12), rs.getString(from + 13),
            rs.getInt(from + 14), rs.getInt(from + 15), at(rs, from + 16));
    }

    /**
     * Queues a run of one version as a job of kind flow, in one transaction.
     *
     * @throws Busy when the flow has a run queued or running
     * @throws Missing when the flow is not there (it was deleted a moment ago)
     */
    public Run queueRun(String flow, int version, String by, String role, String parameters, String variables) {
        return queueRun(flow, version, by, role, parameters, variables, 1);
    }

    /**
     * As {@link #queueRun(String, int, String, String, String, String)}, with the {@code tries} the run may
     * have by itself (ADR 0053): its job is tried as many times, so a run lost with its worker is taken over
     * while tries are left.
     */
    public Run queueRun(String flow, int version, String by, String role, String parameters, String variables,
                        int tries) {
        return db.tx(c -> {
            // the flow can have been deleted since the caller found it
            RunTables.lockOwner(c, KIND, flow);
            RunTables.refuseWhileBusy(c, KIND, flow, null, null);
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_run (flow, version, "
                + "requested_by, requested_role, parameters, variables, tries) VALUES (?, ?, ?, ?, ?::jsonb, "
                + "?::jsonb, ?) RETURNING id")) {
                ps.setString(1, flow);
                ps.setInt(2, version);
                ps.setString(3, by);
                ps.setString(4, role);
                ps.setString(5, parameters);
                ps.setString(6, variables);
                ps.setInt(7, tries);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            // A flow only decides what runs next. One whose worker stopped is taken over while it has tries left
            // (ADR 0053), carrying on from where it was; the pipeline runs it started are never started again.
            RunTables.addJob(c, KIND, id, null, tries);
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

    @Override
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
    @Override
    public Optional<Long> runForJob(long jobId) {
        return RunTables.runForJob(db, KIND, jobId);
    }

    /** Marks a queued run running; false if it was cancelled meanwhile. */
    @Override
    public boolean startRun(long id) {
        return RunTables.startRun(db, KIND, id);
    }

    @Override
    public void finishRun(long id, String status, String error) {
        RunTables.finishRun(db, KIND, id, status, error, null);
    }

    @Override
    public boolean cancelRequested(long id) {
        return RunTables.cancelRequested(db, KIND, id);
    }

    /** Asks a run to stop: a queued run is cancelled at once and its job taken off the queue. */
    @Override
    public Optional<Run> requestCancel(long id, String by) {
        return db.tx(c -> {
            RunTables.requestCancel(c, KIND, id, by);
            return findRun(c, id);
        });
    }

    /** Whether a worker still has the job of this run (queued or running). */
    @Override
    public boolean jobActive(long runId) {
        return RunTables.jobActive(db, KIND, runId);
    }

    @Override
    public RunKind kind() {
        return KIND;
    }

    /**
     * Marks failed the runs whose job failed without the run finishing (its worker stopped), so
     * they do not stay running for ever. Returns how many.
     */
    public List<Run> failOrphanedRuns() {
        return db.tx(c -> {
            // The try each was on is recorded as lost (ADR 0053).
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.flow_run_try (run_id, n, status, started_at, error, next)
                SELECT r.id, r.run_try, 'lost', r.started_at, ?, 'Not tried again: it has no tries left'
                FROM kilnmark.flow_run r JOIN kilnmark.job j ON (j.payload ->> 'flowRunId')::bigint = r.id
                WHERE j.kind = 'flow' AND j.status IN ('failed', 'done') AND r.status = 'running'
                ON CONFLICT (run_id, n) DO NOTHING""")) {
                ps.setString(1, RunTables.LOST);
                ps.executeUpdate();
            }
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
                  AND (j.payload ->> 'flowRunId')::bigint = r.id AND r.status IN ('queued', 'running')
                RETURNING r.id""")) {
                List<Long> ids = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
                List<Run> out = new ArrayList<>();
                for (long id : ids) {
                    findRun(c, id).ifPresent(out::add);
                }
                return out;
            }
        });
    }

    // ---- tries (ADR 0053) ----

    /** Queues a run for its next try at {@code at} with its job, held by {@code holder} (RunTables#retry). */
    public boolean retry(long id, long jobId, String holder, Instant at, String error, String waiting) {
        return RunTables.retry(db, KIND, id, jobId, holder, at, "failed", error, waiting);
    }

    /** Takes over a run whose worker was lost: it carries on, still running, as its next try. */
    public boolean takeOver(long id) {
        return RunTables.takeOver(db, KIND, id, false);
    }

    /** When the schedule that started run {@code id} fires next, or null. */
    public Instant nextFire(long id) {
        return RunTables.nextFire(db, KIND, id);
    }

    /** Marks that the run's owners were told it is taking long; false when they already were. */
    public boolean markWarned(long id) {
        return RunTables.markWarned(db, KIND, id);
    }

    /** Every ended try of a run. */
    public List<RunTables.Try> tries(long id) {
        return RunTables.tries(db, KIND, id);
    }

    /** The worker holding job {@code jobId} now, or null. */
    public String jobHolder(long jobId) {
        return RunTables.jobHolder(db, jobId);
    }

    /** Gives an unfinished run its final status, recording why it is not tried again ({@code next}, or null). */
    public void finishRun(long id, String status, String error, String next) {
        RunTables.finishRun(db, KIND, id, status, error, null, next);
    }

    /**
     * Runs a failed run again from its failure, by hand (ADR 0053, decision 9): queued as its next try,
     * in one transaction with its job, the flow's row locked as a queue locks it.
     *
     * @return false when the run is no longer failed
     * @throws Busy while another run of the flow is queued or running
     */
    public boolean runAgain(long id, String flow, String by) {
        return db.tx(c -> {
            RunTables.lockOwner(c, KIND, flow);
            RunTables.refuseWhileBusy(c, KIND, flow, null, null);
            return RunTables.runAgain(c, KIND, id, by);
        });
    }

    /**
     * Before a run's next try: removes the rows of the nodes it does not keep, so each runs afresh and
     * one not reached this time reads as skipped (ADR 0053, decision 7). The kept nodes' rows stay.
     */
    public void clearNodes(long runId, java.util.Collection<String> keep) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.flow_node_run WHERE run_id = ? "
                + "AND NOT (node_id = ANY (?))")) {
                ps.setLong(1, runId);
                ps.setArray(2, c.createArrayOf("text", keep.toArray()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The flow's owners (ADR 0053). */
    public List<String> owners(String name) {
        return RunTables.owners(db, KIND, name);
    }

    /** The owners of these flows, by name. */
    public java.util.Map<String, List<String>> ownersOf(java.util.Collection<String> names) {
        return RunTables.ownersOf(db, KIND, names);
    }

    /** Sets the flow's owners; those before, or empty when there is no such flow. */
    public Optional<List<String>> setOwners(String name, List<String> owners) {
        return RunTables.setOwners(db, KIND, name, owners);
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
