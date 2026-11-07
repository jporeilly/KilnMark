package io.kilnmark.server.runs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.kilnmark.server.Db;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.schedules.ScheduleStore;

/**
 * The SQL of the run lifecycle, the same for pipelines and flows (docs/design/pipelines.md, "One
 * run lifecycle"): queueing a run with its job, starting, finishing and cancelling it, and deleting
 * what runs with its schedules. Each statement names the kind's tables ({@link RunKind}); the
 * stores call these inside their own transactions, so what each commits together is unchanged.
 */
public final class RunTables {

    private static final String UNFINISHED = "('queued', 'running')";

    private RunTables() {
    }

    /**
     * Locks the row of the pipeline or flow a run is being queued for, in {@code c}'s transaction:
     * one run at a time is checked under it. It can have been deleted since the caller found it.
     *
     * @throws RuntimeException the kind's missing refusal when it is not there
     */
    public static void lockOwner(Connection c, RunKind k, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.table() + " WHERE name = ? FOR UPDATE")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw k.missing().get();
                }
            }
        }
    }

    /**
     * Refuses while the pipeline or flow has a run queued or running. With {@code flowNode} given, a
     * run of the same for-each node of the same flow run ({@code flowRunId}) does not count: the
     * items of one node may run a pipeline at once (ADR 0040). An item run whose flow run is gone
     * (flow_run_id set to null) still counts: null is never "same".
     *
     * @throws RuntimeException the kind's busy refusal
     */
    public static void refuseWhileBusy(Connection c, RunKind k, String name, Long flowRunId, String flowNode)
        throws SQLException {
        boolean item = flowNode != null;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE " + k.noun()
            + " = ? AND status IN " + UNFINISHED
            + (item ? " AND NOT coalesce(flow_item IS NOT NULL AND flow_run_id = ? AND flow_node = ?, false)" : "")
            + " LIMIT 1")) {
            ps.setString(1, name);
            if (item) {
                ps.setLong(2, flowRunId);
                ps.setString(3, flowNode);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw k.busy().get();
                }
            }
        }
    }

    /**
     * Adds the job of run {@code runId}, tried once (a run is never repeated on its own: a pipeline's
     * writes, or the pipelines a flow started, may have begun), and wakes the workers once the
     * transaction commits. {@code priority} is the job's (ADR 0041), or null for the column's own.
     */
    public static void addJob(Connection c, RunKind k, long runId, Integer priority) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts"
            + (priority == null ? "" : ", priority") + ") VALUES ('" + k.jobKind() + "', jsonb_build_object('"
            + k.payloadKey() + "', ?::bigint), 1" + (priority == null ? "" : ", ?") + ")")) {
            ps.setLong(1, runId);
            if (priority != null) {
                ps.setShort(2, priority.shortValue());
            }
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("NOTIFY " + JobQueue.CHANNEL)) {
            ps.execute();
        }
    }

    /** The run a job of the kind is for. */
    public static Optional<Long> runForJob(Db db, RunKind k, long jobId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT (payload ->> '" + k.payloadKey() + "')::bigint "
                + "FROM kilnmark.job WHERE id = ? AND kind = '" + k.jobKind() + "'")) {
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

    /**
     * Marks a queued run running, clearing what it said while it waited when the kind says so;
     * false when it is no longer queued (cancelled, or already taken).
     */
    public static boolean startRun(Db db, RunKind k, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET status = 'running', "
                + "started_at = now()" + (k.notesWhileQueued() ? ", error = NULL" : "")
                + " WHERE id = ? AND status = 'queued' AND NOT cancel_requested")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Gives an unfinished run its final status, and its {@code result} (JSON) when the kind records
     * one. A cancel asked for too late to stop the run is cleared, so the run does not read as stopping.
     */
    public static void finishRun(Db db, RunKind k, long id, String status, String error, String result) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET status = ?, "
                + "finished_at = now(), error = ?, " + (k.results() ? "result = ?::jsonb, " : "")
                + "cancel_requested = (? = 'cancelled') WHERE id = ? AND status IN " + UNFINISHED)) {
                int i = 1;
                ps.setString(i++, status);
                ps.setString(i++, error);
                if (k.results()) {
                    ps.setString(i++, result);
                }
                ps.setString(i++, status);
                ps.setLong(i, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Whether someone has asked the run to stop. */
    public static boolean cancelRequested(Db db, RunKind k, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT cancel_requested FROM " + k.runTable()
                + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /**
     * Which of {@code ids} someone has asked to stop, in one query, whatever their number: the
     * database is where a cancel asked of another process is seen ({@link RunWatch}).
     */
    public static Set<Long> cancelledAmong(Db db, RunKind k, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return db.tx(c -> {
            Set<Long> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + k.runTable()
                + " WHERE id = ANY (?) AND cancel_requested")) {
                ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getLong(1));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Asks a run to stop, in {@code c}'s transaction, its row locked: a queued run is cancelled at
     * once and its job taken off the queue; a running one is marked, and its worker stops it. Nothing
     * changes for a run that has ended or is not there. The caller reads the run back afterwards.
     */
    public static void requestCancel(Connection c, RunKind k, long id, String by) throws SQLException {
        String status;
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM " + k.runTable() + " WHERE id = ? "
            + "FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                status = rs.getString(1);
            }
        }
        boolean queued = status.equals("queued");
        if (!queued && !status.equals("running")) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(queued
            ? "UPDATE " + k.runTable() + " SET status = 'cancelled', cancel_requested = true, finished_at = now(), "
                + "error = ? WHERE id = ?"
            : "UPDATE " + k.runTable() + " SET cancel_requested = true, error = ? WHERE id = ?")) {
            ps.setString(1, "Cancelled by " + by);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
        if (queued) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'done', "
                + "finished_at = now(), last_error = 'Cancelled before it started' WHERE kind = '" + k.jobKind()
                + "' AND status = 'queued' AND (payload ->> '" + k.payloadKey() + "')::bigint = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Whether the run's job is still being run by a worker (queued or running); false when the job
     * is done, failed or gone, so a run still marked running has been left behind.
     */
    public static boolean jobActive(Db db, RunKind k, long runId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.job WHERE kind = '" + k.jobKind()
                + "' AND status IN " + UNFINISHED + " AND (payload ->> '" + k.payloadKey() + "')::bigint = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Deletes the pipeline or flow {@code name} and, in the same transaction, its schedules,
     * returning exactly those, so each can be recorded (ADR 0046, ADR 0048); empty when there is no
     * such pipeline or flow. Its open problem for notifications is cleared, so one saved later under
     * the name is told of afresh. What else goes, and what stays, follows from the tables' foreign
     * keys.
     *
     * <p>The locks are taken in the order a scheduled fire takes them: the schedules' rows, then the
     * row of the pipeline or flow (a fire holds its schedule's row while it queues a run, which locks
     * the pipeline or flow), each wait at most {@link ScheduleStore#DELETE_LOCK_TIMEOUT}.
     *
     * @throws RuntimeException the kind's busy refusal while a run is queued or running, or (when the
     *                          kind waits for jobs) its job is still finishing
     * @throws ScheduleStore.LockWaitTooLong when a lock was not granted in time; nothing was deleted
     */
    public static Optional<List<ScheduleStore.Gone>> delete(Db db, RunKind k, String name) {
        return ScheduleStore.deleting(db, c -> {
            ScheduleStore.lockFor(c, k.target(), name);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.table() + " WHERE name = ? FOR UPDATE")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<List<ScheduleStore.Gone>>empty();
                    }
                }
            }
            // A flow run's job ends after the run is recorded as ended, and tells of a failure in between:
            // while it is still going, a failure told after the delete would be about a flow that is gone.
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE " + k.noun()
                + " = ? AND status IN " + UNFINISHED + (k.deleteWaitsForJob() ? " UNION ALL SELECT 1 FROM kilnmark.job j "
                + "JOIN " + k.runTable() + " r ON (j.payload ->> '" + k.payloadKey() + "')::bigint = r.id WHERE r."
                + k.noun() + " = ? AND j.kind = '" + k.jobKind() + "' AND j.status IN " + UNFINISHED : "")
                + " LIMIT 1")) {
                ps.setString(1, name);
                if (k.deleteWaitsForJob()) {
                    ps.setString(2, name);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw k.busy().get();
                    }
                }
            }
            List<ScheduleStore.Gone> gone = ScheduleStore.deleteFor(c, k.target(), name);
            // a name used again starts with no open problem: its next failure is told (notify_state, V13)
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.notify_state WHERE dedupe_key = ?")) {
                ps.setString(1, k.noun() + ":" + name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + k.table() + " WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return Optional.of(gone);
        });
    }
}
