package io.kilnmark.server.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * A source's places (ADR 0040), on leases as workers hold them: a limit lets that many in at once,
 * a run takes all its places or none, a lease never counts a place it holds twice, places above a
 * lowered limit are kept, and a dead worker's places are free again; and a pipeline run that never
 * started is queued again, not failed, when its worker stops or errs before it starts. The api role
 * alone, so no worker takes anything meanwhile.
 */
@QuarkusTest
@TestProfile(JobQueueTest.ApiOnly.class)
class SourcePlacesTest {

    @Inject
    JobQueue queue;

    @Inject
    AgroalDataSource db;

    @Inject
    io.kilnmark.server.pipelines.PipelineStore pipelines;

    private final List<JobQueue.ScanLease> leases = new ArrayList<>();

    @AfterEach
    void closeLeases() {
        leases.forEach(JobQueue.ScanLease::close);
        leases.clear();
    }

    /** A lease as a worker running a pipeline job holds one: on a job id no real job has. */
    private JobQueue.ScanLease lease() {
        JobQueue.ScanLease l = queue.lockPipelineJob(-ThreadLocalRandom.current().nextLong(1, 1L << 40)).orElseThrow();
        leases.add(l);
        return l;
    }

    /** A source id no other test uses: places are keyed by id alone, and need no source row. */
    private static long sourceId() {
        return ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50);
    }

    private static JobQueue.SourceLimit limit(long id, int max) {
        return new JobQueue.SourceLimit(id, "src-" + id, max);
    }

    /** The places (single-key advisory locks) a lease's session holds. */
    private int placesHeld(JobQueue.ScanLease l) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_locks "
            + "WHERE locktype = 'advisory' AND objsubid = 1 AND granted AND pid = ?")) {
            ps.setInt(1, l.backendPid());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void aLimitLetsThatManyRunsInAtOnce() {
        long s = sourceId();
        JobQueue.ScanLease a = lease();
        JobQueue.ScanLease b = lease();
        JobQueue.ScanLease c = lease();
        JobQueue.Places first = a.takePlaces(List.of(limit(s, 2)));
        assertTrue(first.taken());
        assertEquals(1, first.keys().size());
        assertTrue(b.takePlaces(List.of(limit(s, 2))).taken());
        JobQueue.Places third = c.takePlaces(List.of(limit(s, 2)));
        assertFalse(third.taken(), "two places, both taken");
        assertEquals(s, third.full().sourceId());
        assertTrue(third.keys().isEmpty());

        a.givePlaces(first.keys());
        assertTrue(c.takePlaces(List.of(limit(s, 2))).taken(), "a place given back is free");
    }

    @Test
    void oneLeaseNeverCountsAPlaceItHoldsTwice() {
        // Advisory locks nest within a session: two sql nodes of one flow share the flow's lease.
        long s = sourceId();
        JobQueue.ScanLease flow = lease();
        JobQueue.Places one = flow.takePlaces(List.of(limit(s, 1)));
        assertTrue(one.taken());
        assertFalse(flow.takePlaces(List.of(limit(s, 1))).taken(), "the source's only place is already held");
        JobQueue.Places two = flow.takePlaces(List.of(limit(s, 2)));
        assertTrue(two.taken(), "a second place, under a higher limit");
        assertFalse(one.keys().equals(two.keys()));
        flow.givePlaces(one.keys());
        assertTrue(lease().takePlaces(List.of(limit(s, 1))).taken(), "given back once, it is free");
    }

    @Test
    void aRunOnAFullSourceHoldsNothingWhileItWaits() throws Exception {
        long s1 = sourceId();
        long s2 = s1 + 1;
        JobQueue.ScanLease other = lease();
        assertTrue(other.takePlaces(List.of(limit(s2, 1))).taken());

        JobQueue.ScanLease waiting = lease();
        // In id order: s1 is taken first, then s2 is full, so s1 is given back.
        JobQueue.Places p = waiting.takePlaces(List.of(limit(s2, 1), limit(s1, 1)));
        assertFalse(p.taken());
        assertEquals(s2, p.full().sourceId());
        assertEquals(0, placesHeld(waiting), "it holds neither source while it waits");
        assertTrue(lease().takePlaces(List.of(limit(s1, 1))).taken(), "so another run may use s1");
    }

    @Test
    void placesAboveALoweredLimitAreKept() {
        long s = sourceId();
        JobQueue.ScanLease a = lease();
        JobQueue.ScanLease b = lease();
        JobQueue.Places pa = a.takePlaces(List.of(limit(s, 2)));
        assertTrue(pa.taken());
        assertTrue(b.takePlaces(List.of(limit(s, 2))).taken());
        // The limit drops to 1: both keep their places; a new run waits until place 0 is free.
        JobQueue.ScanLease c = lease();
        assertFalse(c.takePlaces(List.of(limit(s, 1))).taken());
        a.givePlaces(pa.keys());
        assertTrue(c.takePlaces(List.of(limit(s, 1))).taken());
    }

    @Test
    void aDeadWorkersPlacesAreFreeAgain() throws Exception {
        long s = sourceId();
        JobQueue.ScanLease dying = lease();
        assertTrue(dying.takePlaces(List.of(limit(s, 1))).taken());
        JobQueue.ScanLease next = lease();
        assertFalse(next.takePlaces(List.of(limit(s, 1))).taken());

        // The worker dies: PostgreSQL ends its session, and with it the places.
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT pg_terminate_backend(" + dying.backendPid() + ")");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JobQueue.Places p = next.takePlaces(List.of(limit(s, 1)));
        while (!p.taken() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            p = next.takePlaces(List.of(limit(s, 1)));
        }
        assertTrue(p.taken(), "the dead worker's place is free");
        assertNull(p.full());
    }

    @Test
    void closingALeaseFreesItsPlaces() {
        long s = sourceId();
        JobQueue.ScanLease a = queue.lockPipelineJob(-ThreadLocalRandom.current().nextLong(1, 1L << 40)).orElseThrow();
        assertTrue(a.takePlaces(List.of(limit(s, 1))).taken());
        a.close();
        assertTrue(lease().takePlaces(List.of(limit(s, 1))).taken());
    }

    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    private record JobRow(long id, String status, int attempts, String lastError, boolean due) {
    }

    private JobRow jobOf(long run) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, status, attempts, "
            + "last_error, run_after <= now() FROM kilnmark.job WHERE kind = 'pipeline' "
            + "AND (payload ->> 'pipelineRunId')::bigint = ?")) {
            ps.setLong(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new JobRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBoolean(5));
            }
        }
    }

    /** A worker took the run's job and is gone: running, its heartbeat old, no lock held. */
    private void takenByADeadWorker(long jobId) throws Exception {
        sql("UPDATE kilnmark.job SET status = 'running', attempts = 1, locked_by = 'dead-worker', locked_at = now(), "
            + "heartbeat_at = now() - interval '10 minutes' WHERE id = " + jobId);
    }

    @Test
    void aRunThatNeverStartedIsQueuedAgainNotFailed() throws Exception {
        String name = "places-never-" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30, 1L << 40), 36);
        pipelines.create(name, name, null, "tester", "kilnmark: pipeline/v1\nname: " + name + "\nnodes: []\n", "test",
            true, "[]");
        long run = pipelines.queueRun(name, 1, "tester", "steward").id();
        try {
            // The worker died after it took the job, while the run waited for its places.
            JobRow job = jobOf(run);
            takenByADeadWorker(job.id());
            queue.recoverStale(Duration.ofMinutes(2));
            JobRow back = jobOf(run);
            assertEquals("queued", back.status(), "nothing ran, so it waits again");
            assertEquals(0, back.attempts(), "and its one attempt is not used");
            assertTrue(back.lastError().contains("before the run started"), back.lastError());
            assertEquals(0, pipelines.failOrphanedRuns());
            assertEquals("queued", pipelines.run(run).orElseThrow().status());

            // An error before it started (reading the queue, taking its places) puts it back too.
            takenByADeadWorker(job.id());
            assertTrue(queue.retryOrFail(new JobQueue.Job(job.id(), "pipeline", -1, -1, 1, 1, "dead-worker"),
                "the database went away"));
            back = jobOf(run);
            assertEquals("queued", back.status());
            assertEquals(0, back.attempts());
            assertFalse(back.due(), "back in a few seconds");
            assertEquals("queued", pipelines.run(run).orElseThrow().status());

            // Once the run has started, a stopped worker still fails it, as before.
            takenByADeadWorker(job.id());
            assertTrue(pipelines.startRun(run));
            queue.recoverStale(Duration.ofMinutes(2));
            assertEquals("failed", jobOf(run).status());
        } finally {
            pipelines.requestCancel(run, "tester");
            pipelines.finishRun(run, "cancelled", "test", null);
            pipelines.delete(name);
        }
    }
}
