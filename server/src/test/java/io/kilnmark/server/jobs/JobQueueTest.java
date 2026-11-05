package io.kilnmark.server.jobs;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.scan.ScanService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * The job queue, on a server with only the api role, so no worker of its own takes the jobs
 * (except where a test starts one): claiming, one scan per source, the source lock, stale-job
 * recovery, cancellation, and the worker loop end to end.
 */
@QuarkusTest
@TestProfile(JobQueueTest.ApiOnly.class)
class JobQueueTest {

    /** The api role alone; a poll interval so long that only a notification can wake a worker. */
    public static class ApiOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.roles", "api", "kilnmark.worker.poll-interval", "10m");
        }
    }

    /** Every source this class creates starts with this, and is removed after each test. */
    private static final String PREFIX = "queue-test";

    @Inject
    JobQueue queue;

    @Inject
    JobWorker worker;

    @Inject
    ScanService scans;

    @Inject
    GraphStore store;

    @Inject
    SecretBox secrets;

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    /** A source nothing listens for (port 1), so its scan fails at once with a reason. */
    private long unreachable(String suffix) {
        return store.addSource(new Records.NewSource(PREFIX + "-" + suffix, "postgresql",
            Map.of("url", "jdbc:postgresql://127.0.0.1:1/none"), null)).id();
    }

    /** A source that can be read: this test's own database. */
    private long reachable(String suffix) {
        return store.addSource(new Records.NewSource(PREFIX + "-" + suffix, "postgresql",
            Map.of("url", url, "username", user, "schemas", "kilnmark"), secrets.seal(password))).id();
    }

    @AfterEach
    void removeSources() throws Exception {
        sql("DELETE FROM kilnmark.job WHERE (payload ->> 'sourceId')::bigint IN "
            + "(SELECT id FROM kilnmark.data_source WHERE name LIKE '" + PREFIX + "-%')");
        sql("DELETE FROM kilnmark.data_source WHERE name LIKE '" + PREFIX + "-%'");
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

    /** Claims jobs until the one for {@code runId} comes up; any others go back to the queue untouched. */
    private JobQueue.Job claimRun(long runId, String workerId) {
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

    @Test
    void aServerWithoutTheWorkerRoleLeavesScansQueued() throws Exception {
        unreachable("idle");
        long runId = admin().post("/api/v1/sources/" + PREFIX + "-idle/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        Thread.sleep(2500);
        assertEquals("queued", run(runId).status());
        JobRow j = job(runId);
        assertEquals("queued", j.status());
        assertEquals(0, j.attempts());
        assertNull(j.lockedBy());
    }

    @Test
    void askingAgainReturnsTheScanAlreadyInProgress() throws Exception {
        long source = unreachable("twice");
        long first = queue.queueScan(source);
        assertEquals(first, queue.queueScan(source), "the same run, not a second one");
        long viaApi = admin().post("/api/v1/sources/" + PREFIX + "-twice/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        assertEquals(first, viaApi);
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM kilnmark.job WHERE (payload ->> 'sourceId')::bigint = "
                 + source)) {
            rs.next();
            assertEquals(1, rs.getInt(1), "one job for the source");
        }
        sql("UPDATE kilnmark.run SET status = 'succeeded', finished_at = now() WHERE id = " + first);
        assertTrue(queue.queueScan(source) != first, "once it has finished, a new scan is queued");
    }

    @Test
    void twoPollersNeverClaimTheSameJob() throws Exception {
        Set<Long> mine = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            mine.add(queue.queueScan(unreachable("poll-" + i)));
        }
        ConcurrentLinkedQueue<JobQueue.Job> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> pollers = new ArrayList<>();
        for (String name : List.of("poller-a", "poller-b")) {
            for (int t = 0; t < 3; t++) {
                pollers.add(Thread.ofVirtual().start(() -> {
                    try {
                        go.await();
                        for (Optional<JobQueue.Job> j = queue.claim(name); j.isPresent(); j = queue.claim(name)) {
                            claimed.add(j.get());
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
        List<Long> ids = claimed.stream().map(JobQueue.Job::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "no job was claimed twice");
        Set<Long> runs = new HashSet<>();
        for (JobQueue.Job j : claimed) {
            if (mine.contains(j.runId())) {
                runs.add(j.runId());
                assertEquals(1, j.attempts());
            } else {
                queue.release(j, Duration.ZERO, null); // not this test's: put it back as it was
            }
        }
        assertEquals(mine, runs, "every job this test queued was claimed");
    }

    @Test
    void aJobOfHigherPriorityIsClaimedFirstThenTheOneQueuedFirst() throws Exception {
        // Queued low, normal, normal, high (ADR 0041): claimed high, the two normal in the order queued, low.
        long low = queue.queueScan(unreachable("prio-low"));
        long normalFirst = queue.queueScan(unreachable("prio-normal-1"));
        long normalSecond = queue.queueScan(unreachable("prio-normal-2"));
        long high = queue.queueScan(unreachable("prio-high"));
        sql("UPDATE kilnmark.job SET priority = -1 WHERE (payload ->> 'runId')::bigint = " + low);
        sql("UPDATE kilnmark.job SET priority = 1 WHERE (payload ->> 'runId')::bigint = " + high);
        Set<Long> ours = Set.of(low, normalFirst, normalSecond, high);
        List<Long> order = new ArrayList<>();
        List<JobQueue.Job> claimed = new ArrayList<>();
        try {
            for (Optional<JobQueue.Job> j = queue.claim("prio-worker"); j.isPresent(); j = queue.claim("prio-worker")) {
                claimed.add(j.get());
                if (ours.contains(j.get().runId())) {
                    order.add(j.get().runId());
                }
            }
        } finally {
            for (JobQueue.Job j : claimed) {
                queue.release(j, Duration.ZERO, null);
            }
        }
        assertEquals(List.of(high, normalFirst, normalSecond, low), order);
        // A priority is one of three.
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE kilnmark.job SET priority = 2 "
            + "WHERE (payload ->> 'runId')::bigint = " + high));
    }

    @Test
    void aSourceCanBeScannedByOneWorkerAtATime() {
        long source = unreachable("lock");
        try (JobQueue.ScanLease first = queue.lockSource(source).orElseThrow()) {
            assertTrue(queue.lockSource(source).isEmpty(), "a second worker cannot scan the source");
            assertTrue(first.backendPid() > 0);
        }
        Optional<JobQueue.ScanLease> again = queue.lockSource(source);
        assertTrue(again.isPresent(), "the lock is free once released");
        again.get().close();
    }

    @Test
    void aStaleJobIsQueuedAgainWhileAttemptsRemain() throws Exception {
        long source = unreachable("stale");
        long runId = queue.queueScan(source);
        JobQueue.Job j = claimRun(runId, "dead-worker");
        sql("UPDATE kilnmark.run SET status = 'running', started_at = now() WHERE id = " + runId);

        queue.recoverStale(Duration.ofMinutes(2));
        assertEquals("running", job(runId).status(), "a fresh heartbeat is left alone");

        ageHeartbeat(runId);
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
    void aDeadWorkersLockIsLetGoAndItsJobRecovered() throws Exception {
        long source = unreachable("dead");
        long runId = queue.queueScan(source);
        claimRun(runId, "dying-worker");
        JobQueue.ScanLease lease = queue.lockSource(source).orElseThrow();
        ageHeartbeat(runId);

        queue.recoverStale(Duration.ofMinutes(2));
        assertEquals("running", job(runId).status(), "the worker still holds the source: it is slow, not dead");

        // The worker dies: PostgreSQL ends its session, and with it the lock.
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(" + lease.backendPid() + ")");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && queue.lockSource(source).map(l -> {
            l.close();
            return false;
        }).orElse(true)) {
            Thread.sleep(100);
        }
        JobQueue.Recovery r = queue.recoverStale(Duration.ofMinutes(2));
        assertTrue(r.requeued() >= 1, r.toString());
        assertEquals("queued", job(runId).status());
        assertFalse(lease.beat(1, "dying-worker"), "the dead worker's heartbeat fails");
        lease.close();
    }

    @Test
    void aStaleJobWithNoAttemptsLeftFailsWithItsRun() throws Exception {
        long source = unreachable("spent");
        long runId = queue.queueScan(source);
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
    void failingAJobNeedsTheWorkerThatHoldsIt() throws Exception {
        long source = unreachable("guard");
        long runId = queue.queueScan(source);
        JobQueue.Job j = claimRun(runId, "first-worker");
        JobQueue.Job other = new JobQueue.Job(j.id(), j.kind(), j.runId(), j.sourceId(), j.maxAttempts(),
            j.maxAttempts(), "another-worker");
        queue.retryOrFail(other, "boom");
        assertEquals("running", job(runId).status(), "a worker that does not hold the job cannot fail it");
        assertEquals("queued", run(runId).status());
    }

    @Test
    void aCancelledScanGoesBackWithoutUsingAnAttempt() throws Exception {
        long source = reachable("cancel");
        long runId = queue.queueScan(source);
        JobQueue.Job j = claimRun(runId, "stopping-worker");
        assertEquals(1, j.attempts());
        ScanService.Result result = scans.execute(runId, () -> true);
        assertEquals(ScanService.Outcome.CANCELLED, result.outcome());
        assertEquals("running", run(runId).status(), "nothing final was written");
        queue.release(j, Duration.ZERO, "Queued again: the worker running this scan was stopped.");
        JobRow row = job(runId);
        assertEquals("queued", row.status());
        assertEquals(0, row.attempts(), "a shutdown does not count as an attempt");
        assertEquals("queued", run(runId).status());
    }

    @Test
    void aWorkerWakesOnANotificationAndRunsTheScan() throws Exception {
        long source = unreachable("loop");
        worker.start();
        try {
            Thread.sleep(1000); // let it start listening; its poll interval is 10 minutes
            long runId = queue.queueScan(source);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && !"done".equals(job(runId).status())) {
                Thread.sleep(100);
            }
            JobRow row = job(runId);
            assertEquals("done", row.status(), "the worker ran the job after the notification");
            assertEquals(worker.id(), row.lockedBy());
            Records.Run done = run(runId);
            assertEquals("failed", done.status(), "nothing listens on port 1");
            assertTrue(done.detail().contains(PREFIX + "-loop"), done.detail());
            assertTrue(queue.lockSource(source).map(l -> {
                l.close();
                return true;
            }).orElse(false), "the worker let go of the source");
        } finally {
            worker.stop();
        }
    }
}
