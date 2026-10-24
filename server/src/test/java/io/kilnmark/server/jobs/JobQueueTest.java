package io.kilnmark.server.jobs;

import static io.kilnmark.server.Tokens.admin;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * The job queue, on a server with only the api role, so no worker of its own takes the jobs:
 * claiming, the run lock, stale-job recovery, and a worker running a job it was handed.
 */
@QuarkusTest
@TestProfile(JobQueueTest.ApiOnly.class)
class JobQueueTest {

    /** The api role alone. */
    public static class ApiOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.roles", "api");
        }
    }

    private static final String SOURCE = "queue-test";

    @Inject
    JobQueue queue;

    @Inject
    JobWorker worker;

    @Inject
    GraphStore store;

    @Inject
    AgroalDataSource db;

    private long sourceId;

    @BeforeEach
    void addSource() {
        // Nothing listens on port 1, so a scan of this source fails at once with a reason.
        sourceId = store.addSource(new Records.NewSource(SOURCE, "postgresql",
            Map.of("url", "jdbc:postgresql://127.0.0.1:1/none"), null)).id();
    }

    @AfterEach
    void removeSource() throws Exception {
        sql("DELETE FROM kilnmark.job WHERE (payload ->> 'runId')::bigint IN "
            + "(SELECT r.id FROM kilnmark.run r JOIN kilnmark.data_source s ON s.id = r.source_id WHERE s.name = '"
            + SOURCE + "')");
        sql("DELETE FROM kilnmark.data_source WHERE name = '" + SOURCE + "'");
    }

    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private record JobRow(String status, int attempts, String lockedBy, String lastError) {
    }

    private JobRow job(long runId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT status, attempts, locked_by, last_error FROM kilnmark.job WHERE (payload ->> 'runId')::bigint = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "run " + runId + " has a job");
                return new JobRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    private Records.Run run(long runId) {
        return store.run(runId).orElseThrow();
    }

    private void ageHeartbeat(long runId) throws Exception {
        sql("UPDATE kilnmark.job SET heartbeat_at = now() - interval '10 minutes' WHERE (payload ->> 'runId')::bigint = "
            + runId);
    }

    @Test
    void aServerWithoutTheWorkerRoleLeavesScansQueued() throws Exception {
        long runId = admin().post("/api/v1/sources/" + SOURCE + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        Thread.sleep(2500); // more than two poll intervals in the test profile
        assertEquals("queued", run(runId).status());
        JobRow j = job(runId);
        assertEquals("queued", j.status());
        assertEquals(0, j.attempts());
        assertNull(j.lockedBy());
        assertNull(worker.id(), "the worker never started");
    }

    @Test
    void twoPollersNeverClaimTheSameJob() throws Exception {
        int jobs = 30;
        for (int i = 0; i < jobs; i++) {
            queue.queueScan(sourceId);
        }
        ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> pollers = new ArrayList<>();
        for (String name : List.of("poller-a", "poller-b")) {
            for (int t = 0; t < 3; t++) {
                pollers.add(Thread.ofVirtual().start(() -> {
                    try {
                        go.await();
                        for (Optional<JobQueue.Job> j = queue.claim(name); j.isPresent(); j = queue.claim(name)) {
                            claimed.add(j.get().id());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        }
        go.countDown();
        for (Thread t : pollers) {
            t.join();
        }
        Set<Long> unique = new HashSet<>(claimed);
        assertEquals(claimed.size(), unique.size(), "no job was claimed twice");
        assertTrue(unique.size() >= jobs, "every job was claimed");
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM kilnmark.job j JOIN kilnmark.run r "
                 + "ON r.id = (j.payload ->> 'runId')::bigint WHERE r.source_id = " + sourceId
                 + " AND (j.status <> 'running' OR j.attempts <> 1 OR j.locked_by NOT IN ('poller-a', 'poller-b'))")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "each job is running, once, for the poller that claimed it");
        }
    }

    @Test
    void aRunCanBeLockedByOneWorkerAtATime() {
        long runId = queue.queueScan(sourceId);
        try (JobQueue.RunLease first = queue.lockRun(runId).orElseThrow()) {
            assertTrue(queue.lockRun(runId).isEmpty(), "a second worker cannot take the run");
            assertTrue(first != null);
        }
        Optional<JobQueue.RunLease> again = queue.lockRun(runId);
        assertTrue(again.isPresent(), "the lock is free once released");
        again.get().close();
    }

    @Test
    void aStaleJobIsQueuedAgainWhileAttemptsRemain() throws Exception {
        long runId = queue.queueScan(sourceId);
        JobQueue.Job j = claimRun(runId, "dead-worker");
        sql("UPDATE kilnmark.run SET status = 'running', started_at = now() WHERE id = " + runId);

        assertEquals("running", job(runId).status());
        queue.recoverStale(Duration.ofMinutes(2));
        assertEquals("running", job(runId).status(), "a fresh heartbeat is left alone");

        ageHeartbeat(runId);
        try (JobQueue.RunLease held = queue.lockRun(runId).orElseThrow()) {
            queue.recoverStale(Duration.ofMinutes(2));
            assertEquals("running", job(runId).status(), "a job whose run lock is held is not stale");
            assertTrue(held != null);
        }

        JobQueue.Recovery r = queue.recoverStale(Duration.ofMinutes(2));
        assertTrue(r.requeued() >= 1, r.toString());
        JobRow row = job(runId);
        assertEquals("queued", row.status());
        assertNull(row.lockedBy());
        assertEquals(j.attempts(), row.attempts());
        assertTrue(row.lastError().contains("dead-worker"), row.lastError());
        assertEquals("queued", run(runId).status());
    }

    @Test
    void aStaleJobWithNoAttemptsLeftFailsWithItsRun() throws Exception {
        long runId = queue.queueScan(sourceId);
        claimRun(runId, "dead-worker");
        sql("UPDATE kilnmark.job SET attempts = max_attempts WHERE (payload ->> 'runId')::bigint = " + runId);
        sql("UPDATE kilnmark.run SET status = 'running', started_at = now() WHERE id = " + runId);
        ageHeartbeat(runId);

        JobQueue.Recovery r = queue.recoverStale(Duration.ofMinutes(2));
        assertTrue(r.failed() >= 1, r.toString());
        assertEquals("failed", job(runId).status());
        Records.Run failed = run(runId);
        assertEquals("failed", failed.status());
        assertTrue(failed.detail().contains("start the scan again"), failed.detail());
    }

    @Test
    void aWorkerRunsAClaimedScanAndFinishesItsJob() throws Exception {
        long runId = queue.queueScan(sourceId);
        JobQueue.Job j = claimRun(runId, "test-worker");
        worker.execute(j);
        Records.Run done = run(runId);
        assertEquals("failed", done.status(), "nothing listens on port 1");
        assertTrue(done.detail().contains(SOURCE), done.detail());
        JobRow row = job(runId);
        assertEquals("done", row.status(), "the scan ran; its failure is the run's, not the job's");
        assertEquals("test-worker", row.lockedBy());

        assertTrue(queue.lockRun(runId).map(l -> {
            l.close();
            return true;
        }).orElse(false), "the worker let go of the run");
        assertFalse(queue.claim("test-worker").map(x -> x.runId() == runId).orElse(false));
    }

    /** Claims jobs until the one for {@code runId} comes up; others go back to the queue untouched. */
    private JobQueue.Job claimRun(long runId, String workerId) throws Exception {
        List<JobQueue.Job> others = new ArrayList<>();
        try {
            for (Optional<JobQueue.Job> j = queue.claim(workerId); j.isPresent(); j = queue.claim(workerId)) {
                if (j.get().runId() == runId) {
                    return j.get();
                }
                others.add(j.get());
            }
            throw new AssertionError("the job for run " + runId + " was not in the queue");
        } finally {
            for (JobQueue.Job o : others) {
                queue.release(o, Duration.ZERO, null);
            }
        }
    }
}
