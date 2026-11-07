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

    /**
     * A source a run uses that limits how many runs use it at once: its id, its name (for the run's
     * page) and its Most runs at once (ADR 0040).
     */
    public record SourceLimit(long sourceId, String source, int max) {
    }

    /**
     * What taking places found: the places taken (their lock keys, to give back with
     * {@link ScanLease#givePlaces}), or {@code full}, the first source with no free place, in which
     * case nothing is held.
     */
    public record Places(List<Long> keys, SourceLimit full) {

        public Places {
            keys = List.copyOf(keys);
        }

        /** Whether every place asked for was taken. */
        public boolean taken() {
            return full == null;
        }
    }

    /**
     * The lock key of place {@code k} of source {@code sourceId}: a 64-bit advisory lock key, made as
     * {@link #targetKey} makes a written asset's.
     */
    static long placeKey(long sourceId, int k) {
        return targetKey("kilnmark:source-place:" + sourceId + ":" + k);
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

    /**
     * Claims, among the jobs that are due, the one with the highest priority (ADR 0041: 1 high, 0
     * normal, -1 low), and of those the one queued first, if any, and marks it running for
     * {@code workerId}. First queued, not first due: a run waiting for a place on a source, put back
     * for a few seconds, is then not overtaken by runs of its priority queued after it (ADR 0040).
     */
    public Optional<Job> claim(String workerId) {
        return db.tx(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM kilnmark.job "
                + "WHERE status = 'queued' AND run_after <= now() ORDER BY priority DESC, id "
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
     * The flow jobs' locks of one worker, all on one connection (docs/design/flows.md, section 4): a
     * flow job's lock is taken as a pipeline job's is ({@link #lockPipelineJob}), and its sql nodes'
     * places on sources too, but every flow the worker watches shares the one connection, opened for
     * the first and given back when the last ends. So the connections a worker holds do not grow with
     * the flows it watches. A worker that dies frees them all, as it frees a pipeline job's.
     */
    public FlowLeases flowLeases() {
        return new FlowLeases();
    }

    /** The flow jobs' leases of one worker, on one connection of their own ({@link #flowLeases}). */
    public final class FlowLeases {
        private Session session;

        private FlowLeases() {
        }

        /**
         * Takes the lock of flow job {@code jobId} on the shared connection, opening it when no flow
         * holds it; empty if another worker holds the lock, or this one already does.
         */
        public synchronized Optional<ScanLease> lock(long jobId) {
            while (true) {
                Session s = session;
                String failure = "Cannot lock pipeline job " + jobId + ": "; // as a job's own connection says
                if (s == null || s.broken || s.closed) {
                    s = open(failure);
                    session = s;
                }
                synchronized (s) {
                    if (s.closed) {
                        continue; // its last flow ended just now, and gave the connection back
                    }
                    // A session's advisory locks nest: one already held here would be granted again.
                    if (s.jobs.contains(jobId)) {
                        return Optional.empty();
                    }
                    boolean locked;
                    try (PreparedStatement ps = s.c.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                        ps.setInt(1, PIPELINE_JOB_LOCK_CLASS);
                        ps.setInt(2, (int) jobId);
                        try (ResultSet rs = ps.executeQuery()) {
                            locked = rs.next() && rs.getBoolean(1);
                        }
                    } catch (SQLException e) {
                        s.broken = true;
                        s.closeIfUnused();
                        throw new Db.DbException(failure + e.getMessage(), e);
                    }
                    if (!locked) {
                        s.closeIfUnused();
                        return Optional.empty();
                    }
                    s.jobs.add(jobId);
                    s.leases++;
                    return Optional.of(new ScanLease(s, jobId));
                }
            }
        }
    }

    /** The connection a worker's flows share, opened with nothing locked yet. */
    private Session open(String failure) {
        try {
            Connection c = db.connection();
            try {
                c.setAutoCommit(true);
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Session(c, rs.getInt(1), true, PIPELINE_JOB_LOCK_CLASS);
                }
            } catch (SQLException | RuntimeException e) {
                c.close();
                throw e;
            }
        } catch (SQLException e) {
            throw new Db.DbException(failure + e.getMessage(), e);
        }
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
                        ScanLease lease = new ScanLease(new Session(c, rs.getInt(2), false, lockClass), key);
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
     * A connection that holds advisory locks: a lease's own ({@code shared} false), or the one a
     * worker's flows share ({@link FlowLeases}). Every use is synchronized on it.
     */
    private static final class Session {
        private final Connection c;
        private final int backendPid;
        private final boolean shared;
        private final int lockClass;
        /**
         * Source places held on this connection, by every lease on it: advisory locks nest within a
         * session, so a place one lease holds would be granted to another as if it were free.
         */
        private final java.util.Set<Long> places = new java.util.HashSet<>();
        /** The jobs whose locks a shared connection holds. */
        private final java.util.Set<Long> jobs = new java.util.HashSet<>();
        private int leases;
        /** A statement failed: new flows take a new connection; this one closes once its flows end. */
        private boolean broken;
        private boolean closed;

        Session(Connection c, int backendPid, boolean shared, int lockClass) {
            this.c = c;
            this.backendPid = backendPid;
            this.shared = shared;
            this.lockClass = lockClass;
        }

        private interface Binder {
            void bind(PreparedStatement ps) throws SQLException;
        }

        boolean tryLock(String sql, Binder binder) {
            if (closed) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                broken = true;
                throw new Db.DbException("Cannot take or give back a lock: " + e.getMessage(), e);
            }
        }

        /** Gives the connection back, holding no lock, when no lease is on it any more. */
        void closeIfUnused() {
            if (leases == 0 && !closed) {
                closed = true;
                try (c; PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock_all()")) {
                    ps.execute();
                } catch (SQLException e) {
                    LOG.debugf(e, "Giving back the flows' shared connection; closing it releases its locks anyway");
                }
            }
        }
    }

    /**
     * The right to scan one source, or to run one pipeline or flow job: an advisory lock on a
     * connection held for the scan's or run's whole length. Heartbeats go over the same connection,
     * so a lost connection (and with it the lock) is noticed. A flow job's lease is on the connection
     * its worker's flows share ({@link FlowLeases}); closing it then gives back only its own locks.
     */
    public static final class ScanLease implements AutoCloseable {
        private final Session s;
        private final long sourceId;
        /** Source places this lease holds (the session counts every lease's, {@link Session#places}). */
        private final java.util.Set<Long> places = new java.util.HashSet<>();
        /** Whether this lease has held a place: closing it then wakes the runs waiting for one. */
        private boolean heldPlaces;
        private boolean closed;

        /** {@code sourceId} is the lock's key: a source id, or a pipeline or flow job's id. */
        private ScanLease(Session s, long sourceId) {
            this.s = s;
            this.sourceId = sourceId;
        }

        /** The PostgreSQL backend holding the lock (tests end it to play a dead worker). */
        int backendPid() {
            return s.backendPid;
        }

        /**
         * Takes the write lock of one asset (single-key advisory lock, {@link #targetKey}) on this
         * lease's own connection, so a run holds all its locks on the one connection its job
         * already has. False if another session holds it.
         */
        public boolean tryLockTarget(String urn) {
            synchronized (s) {
                return tryLock("SELECT pg_try_advisory_lock(?)", ps -> ps.setLong(1, targetKey(urn)));
            }
        }

        /** Gives back an asset's write lock taken with {@link #tryLockTarget}. */
        public void unlockTarget(String urn) {
            synchronized (s) {
                tryLock("SELECT pg_advisory_unlock(?)", ps -> ps.setLong(1, targetKey(urn)));
            }
        }

        /**
         * Takes a source's scan lock on this lease's connection, so no scan of the source runs
         * meanwhile (scans take the same lock). False if a scan holds it.
         */
        public boolean tryLockSource(long source) {
            synchronized (s) {
                return tryLock("SELECT pg_try_advisory_lock(?, ?)", ps -> {
                    ps.setInt(1, SOURCE_LOCK_CLASS);
                    ps.setInt(2, (int) source);
                });
            }
        }

        /** Gives back a source's scan lock taken with {@link #tryLockSource}. */
        public void unlockSource(long source) {
            synchronized (s) {
                tryLock("SELECT pg_advisory_unlock(?, ?)", ps -> {
                    ps.setInt(1, SOURCE_LOCK_CLASS);
                    ps.setInt(2, (int) source);
                });
            }
        }

        /**
         * Takes one place on each source, on this lease's connection: sources in id order, the first
         * free place of each (place {@code k} for {@code k} from 0 to the limit less one). All or
         * nothing: if a source has no free place, the places taken so far are given back, so a run
         * never holds a place while it waits for another. Places are freed by {@link #givePlaces},
         * or when the lease closes (by PostgreSQL, when a worker dies).
         */
        public Places takePlaces(java.util.Collection<SourceLimit> sources) {
            synchronized (s) {
                java.util.Map<Long, SourceLimit> byId = new java.util.TreeMap<>();
                sources.forEach(x -> byId.putIfAbsent(x.sourceId(), x));
                List<Long> taken = new ArrayList<>();
                try {
                    for (SourceLimit x : byId.values()) {
                        Long got = null;
                        for (int k = 0; k < x.max() && got == null; k++) {
                            long key = placeKey(x.sourceId(), k);
                            // A place this session already holds would be granted again: it is not free.
                            if (!s.places.contains(key)
                                && tryLock("SELECT pg_try_advisory_lock(?)", ps -> ps.setLong(1, key))) {
                                got = key;
                            }
                        }
                        if (got == null) {
                            unlockPlaces(taken); // no wake: nothing was free for long, and waking would loop
                            return new Places(List.of(), x);
                        }
                        s.places.add(got);
                        places.add(got);
                        taken.add(got);
                    }
                } catch (RuntimeException e) {
                    unlockPlaces(taken);
                    throw e;
                }
                heldPlaces |= !taken.isEmpty(); // only places kept: a run that gave them back wakes no one
                return new Places(taken, null);
            }
        }

        /**
         * Gives back places taken with {@link #takePlaces}, and wakes the runs waiting for one; never
         * throws (closing the lease frees them anyway).
         */
        public void givePlaces(List<Long> keys) {
            synchronized (s) {
                if (unlockPlaces(keys)) {
                    wakeWaiting(s.c);
                }
            }
        }

        /** Unlocks places this lease holds; whether any was. Never throws. */
        private boolean unlockPlaces(java.util.Collection<Long> keys) {
            boolean freed = false;
            for (long key : List.copyOf(keys)) {
                if (places.remove(key)) {
                    s.places.remove(key);
                    try {
                        freed |= tryLock("SELECT pg_advisory_unlock(?)", ps -> ps.setLong(1, key));
                    } catch (RuntimeException e) {
                        LOG.debugf("Could not give back a source's place; closing the lease does");
                    }
                }
            }
            return freed;
        }

        /**
         * Makes the pipeline jobs put back to wait (for a place, or for a job's lock) due now, and
         * wakes the workers, so a run that has waited takes a freed place before runs queued after it
         * ({@link #claim} takes the first queued). Pipeline jobs are tried once, so none is waiting
         * for a back-off. Never throws.
         */
        private static void wakeWaiting(Connection c) {
            try (Statement st = c.createStatement()) {
                // Not a run waiting for its next try (ADR 0053): its wait is its retry_delay, never cut short.
                st.executeUpdate("UPDATE kilnmark.job j SET run_after = now() WHERE j.kind = '"
                    + io.kilnmark.server.pipelines.PipelineStore.JOB_KIND + "' AND j.status = 'queued' AND j.run_after > now() "
                    + "AND NOT EXISTS (SELECT 1 FROM kilnmark.pipeline_run r WHERE r.id = (j.payload ->> 'pipelineRunId')::bigint "
                    + "AND r.retry_at > now())");
                st.execute("NOTIFY " + CHANNEL);
            } catch (SQLException e) {
                LOG.debugf("Could not wake the runs waiting for a place; they look again within seconds");
            }
        }

        private boolean tryLock(String sql, Session.Binder binder) {
            if (closed) {
                return false;
            }
            return s.tryLock(sql, binder);
        }

        /** Refreshes the job's heartbeat. False if the job is no longer this worker's, or the lock was lost. */
        public boolean beat(long jobId, String workerId) {
            synchronized (s) {
                if (closed || s.closed) {
                    return false;
                }
                try (PreparedStatement ps = s.c.prepareStatement("UPDATE kilnmark.job SET heartbeat_at = now() "
                    + "WHERE id = ? AND locked_by = ? AND status = 'running'")) {
                    ps.setLong(1, jobId);
                    ps.setString(2, workerId);
                    return ps.executeUpdate() == 1;
                } catch (SQLException e) {
                    s.broken = true;
                    LOG.warnf("Lost the database connection holding source %d's scan lock: %s", sourceId,
                        e.getMessage());
                    return false;
                }
            }
        }

        @Override
        public void close() {
            synchronized (s) {
                if (closed) {
                    return;
                }
                closed = true;
                if (s.shared) {
                    closeShared();
                    return;
                }
                places.clear();
                s.places.clear();
                s.closed = true;
                // Every advisory lock this session took (a pipeline's target and source locks too), so the
                // connection goes back to the pool holding none.
                try (Connection c = s.c; PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock_all()")) {
                    ps.execute();
                    if (heldPlaces) {
                        wakeWaiting(c); // after the places are free, or a woken run would find them still held
                    }
                } catch (SQLException e) {
                    LOG.debugf(e, "Releasing source %d's scan lock; a closed connection releases it anyway", sourceId);
                }
            }
        }

        /** On the flows' shared connection: gives back this flow's places and job lock, and the connection with its last. */
        private void closeShared() {
            // through the session itself: this lease already reads as closed, and would unlock nothing
            for (long key : List.copyOf(places)) {
                places.remove(key);
                s.places.remove(key);
                try {
                    s.tryLock("SELECT pg_advisory_unlock(?)", ps -> ps.setLong(1, key));
                } catch (RuntimeException e) {
                    LOG.debugf("Could not give back a source's place; closing the shared connection does");
                }
            }
            try {
                s.tryLock("SELECT pg_advisory_unlock(?, ?)", ps -> {
                    ps.setInt(1, s.lockClass);
                    ps.setInt(2, (int) sourceId);
                });
            } catch (RuntimeException e) {
                LOG.debugf("Could not give back flow job %d's lock; closing the shared connection does", sourceId);
            }
            if (heldPlaces && !s.closed) {
                wakeWaiting(s.c); // after the places are free, or a woken run would find them still held
            }
            s.jobs.remove(sourceId);
            s.leases--;
            s.closeIfUnused();
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
     * A pipeline job whose run has not started is queued again for a few seconds without using an
     * attempt, whatever its attempts.
     *
     * @return true if requeued
     */
    public boolean retryOrFail(Job job, String error) {
        return db.tx(c -> {
            if (neverStarted(c, job.id())) {
                // A pipeline run tried once, that failed before it started (reading the queue, taking its
                // places): nothing ran, so it waits again rather than failing (ADR 0040).
                requeue(c, job.id(), job.workerId(), NOT_STARTED_RETRY, true, "Not started: " + error);
                notify(c);
                return true;
            }
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

    /** How long a pipeline job that could not start goes back in the queue for. */
    static final Duration NOT_STARTED_RETRY = Duration.ofSeconds(5);

    /** Whether the job is a pipeline run's whose run has not started: still queued. */
    private static boolean neverStarted(Connection c, long jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.job j JOIN kilnmark.pipeline_run r "
            + "ON r.id = (j.payload ->> 'pipelineRunId')::bigint WHERE j.id = ? AND j.kind = '"
            + io.kilnmark.server.pipelines.PipelineStore.JOB_KIND + "' AND r.status = 'queued'")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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
     * run are marked failed with a reason. A pipeline job whose run never started (still queued) is
     * requeued without using an attempt: nothing of it ran.
     */
    public Recovery recoverStale(Duration staleAfter) {
        return recoverStale(staleAfter, staleAfter);
    }

    /**
     * As {@link #recoverStale(Duration)}, with pipeline and flow jobs lost after {@code lostAfter}
     * ({@code kilnmark.runs.lost-after}, ADR 0053) rather than {@code staleAfter}. Such a job is tried
     * as many times as its run may be: queued again while tries are left, the next worker takes its
     * run over (it still reads running); otherwise failed, and its run with it.
     */
    public Recovery recoverStale(Duration staleAfter, Duration lostAfter) {
        return db.tx(c -> {
            record Stale(long id, int attempts, int maxAttempts, String worker, long runId, boolean neverStarted) {
            }
            List<Stale> stale = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT j.id, j.attempts, j.max_attempts, j.locked_by, (j.payload ->> 'runId')::bigint,
                       j.kind = 'pipeline' AND EXISTS (SELECT 1 FROM kilnmark.pipeline_run r
                           WHERE r.id = (j.payload ->> 'pipelineRunId')::bigint AND r.status = 'queued')
                FROM kilnmark.job j
                WHERE j.status = 'running'
                  AND (j.heartbeat_at IS NULL OR j.heartbeat_at < now() - make_interval(secs =>
                      CASE WHEN j.kind IN ('pipeline', 'flow') THEN ? ELSE ? END))
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
                ps.setDouble(1, lostAfter.toMillis() / 1000.0);
                ps.setDouble(2, staleAfter.toMillis() / 1000.0);
                ps.setLong(3, SOURCE_LOCK_CLASS);
                ps.setLong(4, OID_RANGE);
                ps.setLong(5, PIPELINE_JOB_LOCK_CLASS);
                ps.setLong(6, OID_RANGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stale.add(new Stale(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getLong(5),
                            rs.getBoolean(6)));
                    }
                }
            }
            int requeued = 0;
            int failed = 0;
            for (Stale s : stale) {
                String why = "Worker " + s.worker() + " stopped responding during attempt " + s.attempts() + " of "
                    + s.maxAttempts();
                if (s.neverStarted()) {
                    // A pipeline run that had not started (it was taking, or waiting for, its places): nothing
                    // ran, so it goes back in the queue without using its one attempt (ADR 0040).
                    requeue(c, s.id(), s.worker(), Duration.ZERO, true, why + ", before the run started");
                    requeued++;
                } else if (s.attempts() < s.maxAttempts()) {
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
