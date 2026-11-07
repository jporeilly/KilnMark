package io.kilnmark.server.runs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
     * Adds the job of run {@code runId}, tried once, and wakes the workers once the transaction
     * commits. {@code priority} is the job's (ADR 0041), or null for the column's own.
     */
    public static void addJob(Connection c, RunKind k, long runId, Integer priority) throws SQLException {
        addJob(c, k, runId, priority, 1);
    }

    /**
     * As {@link #addJob(Connection, RunKind, long, Integer)}, tried at most {@code tries} times: as
     * many as the run may have tries (ADR 0053, decision 6), so stale recovery queues it again after a
     * lost worker while tries are left. A run's tries are never more than its own retries allow: a
     * pipeline's writes, or the pipelines a flow started, may have begun, and only a run whose writes
     * are safe to repeat has retries.
     */
    public static void addJob(Connection c, RunKind k, long runId, Integer priority, int tries) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts"
            + (priority == null ? "" : ", priority") + ") VALUES ('" + k.jobKind() + "', jsonb_build_object('"
            + k.payloadKey() + "', ?::bigint), ?" + (priority == null ? "" : ", ?") + ")")) {
            ps.setLong(1, runId);
            ps.setInt(2, Math.max(1, tries));
            if (priority != null) {
                ps.setShort(3, priority.shortValue());
            }
            ps.executeUpdate();
        }
        notifyWorkers(c);
    }

    private static void notifyWorkers(Connection c) throws SQLException {
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
            // What a queued run said while it waited (a source's place, its next try) is cleared.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET status = 'running', "
                + "started_at = now(), retry_at = NULL, error = NULL"
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
        finishRun(db, k, id, status, error, result, null);
    }

    /**
     * As {@link #finishRun(Db, RunKind, long, String, String, String)}, recording the try that ended
     * with it (ADR 0053) when it had started: how it ended, and {@code next}, why it is not tried again
     * (or null).
     */
    public static void finishRun(Db db, RunKind k, long id, String status, String error, String result, String next) {
        finishRun(db, k, id, status, error, result, next, null);
    }

    /**
     * As {@link #finishRun(Db, RunKind, long, String, String, String, String)}, only while the run is
     * still on try {@code onTry} (null: whatever its try). A worker ending its try never ends another's:
     * one that lost its job, whose run another worker has taken over, changes nothing (ADR 0053).
     *
     * @return whether the run was ended
     */
    public static boolean finishRun(Db db, RunKind k, long id, String status, String error, String result, String next,
                                    Integer onTry) {
        return db.tx(c -> {
            if (onTry != null && !onTry(c, k, id, onTry)) {
                return false;
            }
            recordTry(c, k, id, status, error, next);
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
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Whether run {@code id} is still on try {@code n}, its row locked till the transaction ends. */
    private static boolean onTry(Connection c, RunKind k, long id, int n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE id = ? "
            + "AND run_try = ? FOR UPDATE")) {
            ps.setLong(1, id);
            ps.setInt(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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

    // ---- tries (ADR 0053) ----

    /**
     * Records the try a running run is on as ended, in {@code c}'s transaction: its number, how it
     * ended ({@code succeeded}, {@code failed}, {@code cancelled} or {@code lost}), its start, why and
     * what comes next. Nothing for a run that is not running (it never started this try).
     */
    static void recordTry(Connection c, RunKind k, long id, String status, String error, String next)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + k.tryTable() + " (run_id, n, status, "
            + "started_at, error, next) SELECT id, run_try, ?, started_at, ?, ? FROM " + k.runTable()
            + " WHERE id = ? AND status = 'running' ON CONFLICT (run_id, n) DO NOTHING")) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setString(3, next == null ? null : clip(next, 500));
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * Ends the try a running run is on and queues it for its next try at {@code at}, in one
     * transaction with its job (ADR 0053, decision 3): the try is recorded, the run is queued again
     * with {@code waiting} as what it says meanwhile, and the job, still held by {@code workerId}, is
     * due again then, counting the try. A try is a new try of the same run, never a new run, so a
     * schedule's fire is never repeated and one run at a time holds while it waits. {@code workerId} is
     * the worker that claimed the job for this try, and {@code onTry} the try: a worker that has lost the
     * job to another, which took the run over, changes nothing.
     *
     * @return false, and nothing changed, when the run was asked to stop meanwhile, is no longer
     *         running, or its job is no longer this worker's: the caller then ends the run for good
     */
    public static boolean retry(Db db, RunKind k, long id, long jobId, String workerId, int onTry, Instant at,
                                String status, String error, String waiting) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE id = ? "
                + "AND status = 'running' AND run_try = ? AND NOT cancel_requested FOR UPDATE")) {
                ps.setLong(1, id);
                ps.setInt(2, onTry);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            int moved;
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'queued', run_after = ?, "
                + "locked_by = NULL, locked_at = NULL, heartbeat_at = NULL, last_error = ? WHERE id = ? AND kind = '"
                + k.jobKind() + "' AND locked_by IS NOT DISTINCT FROM ? AND status = 'running'")) {
                ps.setTimestamp(1, Db.timestamp(at));
                ps.setString(2, "Waiting for the run's next try");
                ps.setLong(3, jobId);
                ps.setString(4, workerId);
                moved = ps.executeUpdate();
            }
            if (moved != 1) {
                return false; // nothing written yet: the run is still running, for the caller to end
            }
            recordTry(c, k, id, status, error, "Tried again");
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET status = 'queued', "
                + "run_try = run_try + 1, retry_at = ?, error = ? WHERE id = ?")) {
                ps.setTimestamp(1, Db.timestamp(at));
                ps.setString(2, waiting);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            notifyWorkers(c);
            return true;
        });
    }

    /**
     * Takes over a run whose worker was lost (ADR 0053, decision 6): its job was queued again by stale
     * recovery and claimed here, and the run still reads running. The lost try is recorded, and the run
     * goes on as its next try: with {@code requeue} queued again, to start as any try does, else
     * still running. False when it no longer reads running (it ended meanwhile).
     */
    public static boolean takeOver(Db db, RunKind k, long id, boolean requeue) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE id = ? "
                + "AND status = 'running' AND run_try < " + MAX_TRY + " FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            recordTry(c, k, id, "lost", LOST, "Taken over by another worker");
            // A pipeline starts again from its beginning, so it is queued as its next try and started as any is;
            // a flow carries on where it is, still running.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET run_try = run_try + 1, "
                + (requeue ? "status = 'queued', started_at = NULL" : "started_at = now()") + ", retry_at = NULL "
                + "WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** The worker holding job {@code jobId} now, or null: whose job a try's end may queue again. */
    public static String jobHolder(Db db, long jobId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT locked_by FROM kilnmark.job WHERE id = ? "
                + "AND status = 'running'")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** The highest try number a run may reach (V39 checks it). */
    public static final int MAX_TRY = 50;

    /** What a try lost with its worker says. */
    public static final String LOST = "The worker running it stopped, or lost its connection to Kilnmark's database";

    /** One ended try of a run, as recorded: its number from 0. */
    public record Try(int number, String status, Instant startedAt, Instant finishedAt, String error, String next,
                      String askedBy) {
    }

    /** Every ended try of a run, the first first. */
    public static List<Try> tries(Db db, RunKind k, long id) {
        return db.tx(c -> {
            List<Try> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT n, status, started_at, finished_at, error, next, "
                + "asked_by FROM " + k.tryTable() + " WHERE run_id = ? ORDER BY n")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Try(rs.getInt(1), rs.getString(2), Db.instant(rs.getTimestamp(3)),
                            Db.instant(rs.getTimestamp(4)), rs.getString(5), rs.getString(6), rs.getString(7)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * When the schedule whose last fire started run {@code id} fires next, or null when no schedule
     * started it (or it is paused): no try starts after it (ADR 0053, decision 4).
     */
    public static Instant nextFire(Db db, RunKind k, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT min(next_at) FROM kilnmark.schedule WHERE "
                + k.scheduleRunColumn() + " = ? AND enabled")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Db.instant(rs.getTimestamp(1)) : null;
                }
            }
        });
    }

    /** Marks that run {@code id}'s owners were told it is taking long; false when they already were (once a run). */
    public static boolean markWarned(Db db, RunKind k, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET warned_at = now() "
                + "WHERE id = ? AND warned_at IS NULL AND status = 'running'")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Makes a failed run run again from its failure, by hand (ADR 0053, decision 9), in {@code c}'s
     * transaction, the run's row locked: queued as its next try, which may not be tried again by itself
     * ({@code tries} is the try's own number + 1), and its job queued again, tried once. The try it
     * failed on records who asked. A run already going (one run at a time) is refused by the store's
     * own busy refusal before this is called.
     *
     * @return false when the run is no longer failed, or has had as many tries as a run may (50)
     */
    public static boolean runAgain(Connection c, RunKind k, long id, String by) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + k.runTable() + " WHERE id = ? "
            + "AND status = 'failed' AND run_try < " + MAX_TRY + " FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.tryTable() + " SET next = ?, asked_by = ? "
            + "WHERE run_id = ? AND n = (SELECT run_try FROM " + k.runTable() + " WHERE id = ?)")) {
            ps.setString(1, "Run again from the failure");
            ps.setString(2, by);
            ps.setLong(3, id);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.runTable() + " SET status = 'queued', "
            + "run_try = run_try + 1, tries = least(6, run_try + 2), retry_at = NULL, started_at = NULL, "
            + "finished_at = NULL, error = NULL, cancel_requested = false WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        // One job per run (a flow's is unique, V20): the run's own job is queued again, tried once.
        int queued;
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'queued', attempts = 0, "
            + "max_attempts = 1, run_after = now(), locked_by = NULL, locked_at = NULL, heartbeat_at = NULL, "
            + "finished_at = NULL, last_error = NULL WHERE kind = '" + k.jobKind() + "' AND (payload ->> '"
            + k.payloadKey() + "')::bigint = ?")) {
            ps.setLong(1, id);
            queued = ps.executeUpdate();
        }
        if (queued == 0) {
            addJob(c, k, id, null, 1);
        } else {
            notifyWorkers(c);
        }
        return true;
    }

    // ---- owners (ADR 0053, decision 10) ----

    /** The owners of the pipeline or flow {@code name}, in the order set; empty when it has none or is not there. */
    public static List<String> owners(Db db, RunKind k, String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT owners FROM " + k.table() + " WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? strings(rs.getArray(1)) : List.<String>of();
                }
            }
        });
    }

    /** The owners of each of these pipelines or flows by name, in one query; those with none are left out. */
    public static java.util.Map<String, List<String>> ownersOf(Db db, RunKind k, Collection<String> names) {
        if (names.isEmpty()) {
            return java.util.Map.of();
        }
        return db.tx(c -> {
            java.util.Map<String, List<String>> out = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, owners FROM " + k.table()
                + " WHERE name = ANY (?) AND cardinality(owners) > 0")) {
                ps.setArray(1, c.createArrayOf("text", names.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), strings(rs.getArray(2)));
                    }
                }
            }
            return out;
        });
    }

    private static List<String> strings(java.sql.Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    /**
     * Sets the owners of {@code name}, returning those it had before; empty when there is no such
     * pipeline or flow. The row is locked, so two changes at once are recorded in the order they land.
     */
    public static Optional<List<String>> setOwners(Db db, RunKind k, String name, List<String> owners) {
        return db.tx(c -> {
            List<String> before;
            try (PreparedStatement ps = c.prepareStatement("SELECT owners FROM " + k.table() + " WHERE name = ? "
                + "FOR UPDATE")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<List<String>>empty();
                    }
                    before = strings(rs.getArray(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + k.table() + " SET owners = ? WHERE name = ?")) {
                ps.setArray(1, c.createArrayOf("text", owners.toArray()));
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return Optional.of(before);
        });
    }
}
