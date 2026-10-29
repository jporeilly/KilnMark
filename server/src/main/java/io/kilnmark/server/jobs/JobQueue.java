package io.kilnmark.server.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The job queue, in PostgreSQL. The api role queues a scan as a run plus a job in one transaction
 * and notifies {@value #CHANNEL}; workers claim jobs with {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * in a short transaction and run them outside it.
 *
 * <p>A source has at most one waiting or running scan (a partial unique index on {@code run}):
 * asking again returns the scan already in progress. While a worker runs a scan it holds a
 * PostgreSQL advisory lock on the source ({@link #lockSource}) on a connection of its own, which
 * PostgreSQL releases if the worker dies, so two scans of one source never overlap. Stale-job
 * recovery only touches jobs whose heartbeat is old <em>and</em> whose source lock nobody holds.
 */
@ApplicationScoped
public class JobQueue {

    private static final Logger LOG = Logger.getLogger(JobQueue.class);

    /** PostgreSQL notification channel: a job was queued. */
    public static final String CHANNEL = "kilnmark_jobs";
    /** Advisory lock class for scans of a source (objid is the source id); deleting a source takes it too. */
    static final int SOURCE_LOCK_CLASS = 0x4B53;
    /** Advisory lock class for a running pipeline job (objid is the job id): held by the worker running it. */
    static final int PIPELINE_JOB_LOCK_CLASS = 0x4B50;

    private static final long OID_RANGE = 1L << 32;

    /** A claimed job. {@code attempts} counts this one. */
    public record Job(long id, String kind, long runId, long sourceId, int attempts, int maxAttempts,
                      String workerId) {
    }

    /** What a recovery pass did. */
    public record Recovery(int requeued, int failed) {
    }

    private final Db db;
    private final KilnmarkConfig config;

    public JobQueue(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    /**
     * Queues a scan of a source: a queued run and its job, in one transaction. If the source already
     * has a scan waiting or running, returns that run instead of queueing another.
     *
     * @return the run id
     */
    public long queueScan(long sourceId) {
        return db.tx(c -> {
            for (int tries = 0; tries < 5; tries++) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.run (source_id, kind, status) "
                    + "VALUES (?, 'scan', 'queued') "
                    + "ON CONFLICT (source_id) WHERE status IN ('queued', 'running') DO NOTHING RETURNING id")) {
                    ps.setLong(1, sourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long runId = rs.getLong(1);
                            addJob(c, runId, sourceId);
                            notify(c);
                            return runId;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM kilnmark.run "
                    + "WHERE source_id = ? AND status IN ('queued', 'running')")) {
                    ps.setLong(1, sourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
                // The scan in progress finished between the two statements: try again.
            }
            throw new IllegalStateException("Could not queue a scan of source " + sourceId + ": scans of it keep "
                + "starting and finishing. Try again.");
        });
    }

    private void addJob(Connection c, long runId, long sourceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts) "
            + "VALUES ('scan', jsonb_build_object('runId', ?::bigint, 'sourceId', ?::bigint), ?)")) {
            ps.setLong(1, runId);
            ps.setLong(2, sourceId);
            ps.setInt(3, Math.max(1, config.worker().maxAttempts()));
            ps.executeUpdate();
        }
    }

    /** Wakes listening workers once the transaction commits. */
    private static void notify(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("NOTIFY " + CHANNEL);
        }
    }

    /** Claims the next job that is due, if any, and marks it running for {@code workerId}. */
    public Optional<Job> claim(String workerId) {
        return db.tx(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM kilnmark.job "
                + "WHERE status = 'queued' AND run_after <= now() ORDER BY run_after, id "
                + "FOR UPDATE SKIP LOCKED LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                id = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'running', "
                + "attempts = attempts + 1, locked_by = ?, locked_at = now(), heartbeat_at = now() WHERE id = ? "
                + "RETURNING kind, coalesce((payload ->> 'runId')::bigint, -1), "
                + "coalesce((payload ->> 'sourceId')::bigint, -1), attempts, max_attempts")) {
                ps.setString(1, workerId);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next(); // -1: missing from the payload, so the worker fails the job
                    return Optional.of(new Job(id, rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                        rs.getInt(5), workerId));
                }
            }
        });
    }

    /**
     * Takes the source's scan lock (an advisory lock) on a connection of its own, held until the
     * lease is closed. Empty if another worker holds it.
     */
    public Optional<ScanLease> lockSource(long sourceId) {
        return lock(SOURCE_LOCK_CLASS, sourceId, "Cannot lock source " + sourceId + " for a scan: ");
    }

    /**
     * Takes the lock of a pipeline job (an advisory lock keyed by the job id) on a connection of
     * its own, as {@link #lockSource} does for scans: heartbeats go over it, and recovery leaves
     * the job alone while it is held. Pipelines need no source lock: runs of one pipeline may
     * overlap, as scans of the sources they read may.
     */
    public Optional<ScanLease> lockPipelineJob(long jobId) {
        return lock(PIPELINE_JOB_LOCK_CLASS, jobId, "Cannot lock pipeline job " + jobId + ": ");
    }

    /**
     * Tries {@code take} until it is true, {@code wait} has passed or {@code stop} turns true,
     * polling a few times a second.
     *
     * @return whether {@code take} succeeded
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean waitFor(java.util.function.BooleanSupplier take, Duration wait,
                                  java.util.function.BooleanSupplier stop) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            if (take.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline || stop.getAsBoolean()) {
                return false;
            }
            Thread.sleep(Math.min(250, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
        }
    }

    /** A 64-bit key for a written asset's advisory lock: the first eight bytes of its URN's SHA-256. */
    public static long targetKey(String urn) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(urn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(h).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    private Optional<ScanLease> lock(int lockClass, long key, String failure) {
        Connection c = null;
        try {
            c = db.connection();
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?, ?), pg_backend_pid()")) {
                ps.setInt(1, lockClass);
                ps.setInt(2, (int) key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        ScanLease lease = new ScanLease(c, lockClass, key, rs.getInt(2));
                        c = null;
                        return Optional.of(lease);
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new Db.DbException(failure + e.getMessage(), e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException e) {
                    LOG.debugf(e, "Closing a connection after a failed source lock");
                }
            }
        }
    }

    /**
     * The right to scan one source: an advisory lock on a connection held for the scan's whole
     * length. Heartbeats go over the same connection, so a lost connection (and with it the lock)
     * is noticed.
     */
    public static final class ScanLease implements AutoCloseable {
        private final Connection c;
        private final int lockClass;
        private final long sourceId;
        private final int backendPid;
        private boolean closed;

        /** {@code sourceId} is the lock's key: a source id, or a pipeline job's id. */
        ScanLease(Connection c, int lockClass, long sourceId, int backendPid) {
            this.c = c;
            this.lockClass = lockClass;
            this.sourceId = sourceId;
            this.backendPid = backendPid;
        }

        /** The PostgreSQL backend holding the lock (tests end it to play a dead worker). */
        int backendPid() {
            return backendPid;
        }

        /**
         * Takes the write lock of one asset (single-key advisory lock, {@link #targetKey}) on this
         * lease's own connection, so a run holds all its locks on the one connection its job
         * already has. False if another session holds it.
         */
        public synchronized boolean tryLockTarget(String urn) {
            return tryLock("SELECT pg_try_advisory_lock(?)", ps -> ps.setLong(1, targetKey(urn)));
        }

        /** Gives back an asset's write lock taken with {@link #tryLockTarget}. */
        public synchronized void unlockTarget(String urn) {
            tryLock("SELECT pg_advisory_unlock(?)", ps -> ps.setLong(1, targetKey(urn)));
        }

        /**
         * Takes a source's scan lock on this lease's connection, so no scan of the source runs
         * meanwhile (scans take the same lock). False if a scan holds it.
         */
        public synchronized boolean tryLockSource(long source) {
            return tryLock("SELECT pg_try_advisory_lock(?, ?)", ps -> {
                ps.setInt(1, SOURCE_LOCK_CLASS);
                ps.setInt(2, (int) source);
            });
        }

        /** Gives back a source's scan lock taken with {@link #tryLockSource}. */
        public synchronized void unlockSource(long source) {
            tryLock("SELECT pg_advisory_unlock(?, ?)", ps -> {
                ps.setInt(1, SOURCE_LOCK_CLASS);
                ps.setInt(2, (int) source);
            });
        }

        private interface Binder {
            void bind(PreparedStatement ps) throws SQLException;
        }

        private boolean tryLock(String sql, Binder binder) {
            if (closed) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new Db.DbException("Cannot take or give back a lock: " + e.getMessage(), e);
            }
        }

        /** Refreshes the job's heartbeat. False if the job is no longer this worker's, or the lock was lost. */
        public synchronized boolean beat(long jobId, String workerId) {
            if (closed) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET heartbeat_at = now() "
                + "WHERE id = ? AND locked_by = ? AND status = 'running'")) {
                ps.setLong(1, jobId);
                ps.setString(2, workerId);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                LOG.warnf("Lost the database connection holding source %d's scan lock: %s", sourceId, e.getMessage());
                return false;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Every advisory lock this session took (a pipeline's target and source locks too), so the
            // connection goes back to the pool holding none.
            try (c; PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock_all()")) {
                ps.execute();
            } catch (SQLException e) {
                LOG.debugf(e, "Releasing source %d's scan lock; a closed connection releases it anyway", sourceId);
            }
        }
    }

    /** Marks a job done. {@code note} explains a job that had nothing to do; null otherwise. */
    public void complete(Job job, String note) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'done', "
                + "finished_at = now(), heartbeat_at = NULL, last_error = ? "
                + "WHERE id = ? AND locked_by = ? AND status = 'running'")) {
                ps.setString(1, note);
                ps.setLong(2, job.id());
                ps.setString(3, job.workerId());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * After an error: queued again with a back-off if attempts remain, otherwise failed with its run.
     *
     * @return true if requeued
     */
    public boolean retryOrFail(Job job, String error) {
        return db.tx(c -> {
            if (job.attempts() < job.maxAttempts()) {
                requeue(c, job.id(), job.workerId(), Duration.ofSeconds(30L * job.attempts()), false,
                    "Attempt " + job.attempts() + " failed: " + error);
                requeueRun(c, job.runId(), "Queued again after an error: " + error);
                notify(c);
                return true;
            }
            if (fail(c, job.id(), job.workerId(), error)) {
                failRun(c, job.runId(), "The scan failed " + job.attempts() + " times. Last error: " + error
                    + ". Fix the cause, then start the scan again.");
            }
            return false;
        });
    }

    /**
     * Puts a job back without counting the attempt: the run was locked by another worker, or this
     * worker is shutting down. {@code runDetail}, if not null, is shown on the run while it waits.
     */
    public void release(Job job, Duration delay, String runDetail) {
        db.tx(c -> {
            if (requeue(c, job.id(), job.workerId(), delay, true, runDetail) && runDetail != null) {
                requeueRun(c, job.runId(), runDetail);
            }
            notify(c);
            return null;
        });
    }

    private static boolean requeue(Connection c, long jobId, String workerId, Duration delay, boolean uncount,
                                   String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'queued', "
            + "attempts = attempts - ?, run_after = now() + make_interval(secs => ?), locked_by = NULL, "
            + "locked_at = NULL, heartbeat_at = NULL, last_error = coalesce(?, last_error) "
            + "WHERE id = ? AND locked_by IS NOT DISTINCT FROM ? AND status = 'running'")) {
            ps.setInt(1, uncount ? 1 : 0);
            ps.setDouble(2, delay.toMillis() / 1000.0);
            ps.setString(3, error);
            ps.setLong(4, jobId);
            ps.setString(5, workerId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Marks the job failed if {@code workerId} still holds it; false if it does not. */
    private static boolean fail(Connection c, long jobId, String workerId, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.job SET status = 'failed', "
            + "finished_at = now(), heartbeat_at = NULL, last_error = ? "
            + "WHERE id = ? AND locked_by IS NOT DISTINCT FROM ? AND status = 'running'")) {
            ps.setString(1, error);
            ps.setLong(2, jobId);
            ps.setString(3, workerId);
            return ps.executeUpdate() == 1;
        }
    }

    private static void requeueRun(Connection c, long runId, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run SET status = 'queued', "
            + "started_at = NULL, detail = ? WHERE id = ? AND status IN ('queued', 'running')")) {
            ps.setString(1, detail);
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    private static void failRun(Connection c, long runId, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run SET status = 'failed', "
            + "finished_at = now(), detail = ? WHERE id = ? AND status IN ('queued', 'running')")) {
            ps.setString(1, detail);
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    /**
     * Recovers jobs left running by a worker that stopped: heartbeat older than {@code staleAfter}
     * and nobody holding its source's scan lock. Requeued while attempts remain; otherwise the job and its
     * run are marked failed with a reason.
     */
    public Recovery recoverStale(Duration staleAfter) {
        return db.tx(c -> {
            record Stale(long id, int attempts, int maxAttempts, String worker, long runId) {
            }
            List<Stale> stale = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT j.id, j.attempts, j.max_attempts, j.locked_by, (j.payload ->> 'runId')::bigint
                FROM kilnmark.job j
                WHERE j.status = 'running'
                  AND (j.heartbeat_at IS NULL OR j.heartbeat_at < now() - make_interval(secs => ?))
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_locks l
                      WHERE l.locktype = 'advisory' AND l.objsubid = 2 AND l.granted
                        AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())
                        AND l.classid = ?::bigint::oid
                        AND l.objid = ((j.payload ->> 'sourceId')::bigint % ?)::oid)
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_locks l
                      WHERE j.kind IN ('pipeline', 'flow') AND l.locktype = 'advisory' AND l.objsubid = 2 AND l.granted
                        AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())
                        AND l.classid = ?::bigint::oid
                        AND l.objid = (j.id % ?)::oid)
                FOR UPDATE OF j SKIP LOCKED""")) {
                ps.setDouble(1, staleAfter.toMillis() / 1000.0);
                ps.setLong(2, SOURCE_LOCK_CLASS);
                ps.setLong(3, OID_RANGE);
                ps.setLong(4, PIPELINE_JOB_LOCK_CLASS);
                ps.setLong(5, OID_RANGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stale.add(new Stale(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getLong(5)));
                    }
                }
            }
            int requeued = 0;
            int failed = 0;
            for (Stale s : stale) {
                String why = "Worker " + s.worker() + " stopped responding during attempt " + s.attempts() + " of "
                    + s.maxAttempts();
                if (s.attempts() < s.maxAttempts()) {
                    requeue(c, s.id(), s.worker(), Duration.ZERO, false, why);
                    requeueRun(c, s.runId(), "Queued again: the worker running this scan stopped responding");
                    requeued++;
                } else if (fail(c, s.id(), s.worker(), why)) {
                    failRun(c, s.runId(), "The worker running this scan stopped responding, and the scan has now "
                        + "been tried " + s.attempts() + " times. Check that the workers are running and have enough "
                        + "memory (see their logs), then start the scan again.");
                    failed++;
                }
            }
            if (requeued > 0) {
                notify(c);
            }
            return new Recovery(requeued, failed);
        });
    }
}
