package io.kilnmark.server.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * The flows a worker watches share one connection for their locks (docs/design/flows.md, section 4;
 * efficiency review, 6 November 2026): however many flows run, their jobs' locks and their sql
 * nodes' places are on one PostgreSQL session, which is given back when the last flow ends. A flow
 * job's lock is still refused when another worker holds it, and a place one flow holds is not free
 * to another on the same session. The api role alone, so no worker takes anything meanwhile.
 */
@QuarkusTest
@TestProfile(JobQueueTest.ApiOnly.class)
class FlowLeasesTest {

    @Inject
    JobQueue queue;

    @Inject
    AgroalDataSource db;

    private final List<JobQueue.ScanLease> leases = new ArrayList<>();

    @AfterEach
    void closeLeases() {
        leases.forEach(JobQueue.ScanLease::close);
        leases.clear();
    }

    /** A job id no real job has. */
    private static long jobId() {
        return -ThreadLocalRandom.current().nextLong(1, 1L << 30);
    }

    /** The backends holding a flow or pipeline job's lock on any of {@code jobs}. */
    private List<Integer> holders(List<Long> jobs) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT DISTINCT pid "
            + "FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 2 AND granted AND classid = ?::bigint::oid "
            + "AND objid::bigint = ANY (?)")) {
            ps.setLong(1, JobQueue.PIPELINE_JOB_LOCK_CLASS);
            // the lock's key is the job id as a 32-bit number, which pg_locks shows as an unsigned oid
            ps.setArray(2, c.createArrayOf("bigint", jobs.stream().map(j -> Integer.toUnsignedLong((int) (long) j))
                .toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    @Test
    void theFlowsOfAWorkerHoldTheirLocksOnOneConnection() throws Exception {
        JobQueue.FlowLeases flows = queue.flowLeases();
        List<Long> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long job = jobId();
            jobs.add(job);
            leases.add(flows.lock(job).orElseThrow());
        }
        List<Integer> holders = holders(jobs);
        assertEquals(1, holders.size(), "six flows, one connection: " + holders);
        assertEquals(holders.get(0), leases.get(0).backendPid());

        // the same job twice is refused, as a second connection's lock would be: the session's locks nest
        assertTrue(flows.lock(jobs.get(0)).isEmpty());
        // another worker's flows cannot take a job this worker holds
        assertTrue(queue.flowLeases().lock(jobs.get(1)).isEmpty());

        // a flow that ends gives back its own lock only; the last gives the connection back
        leases.remove(0).close();
        assertEquals(holders, holders(jobs.subList(1, 6)), "the others are still held, on the same connection");
        assertTrue(holders(List.of(jobs.get(0))).isEmpty(), "the ended flow's lock is free");
        leases.forEach(JobQueue.ScanLease::close);
        leases.clear();
        assertTrue(holders(jobs).isEmpty(), "every flow ended: nothing held");

        // a flow after that opens a connection again
        long again = jobId();
        leases.add(flows.lock(again).orElseThrow());
        assertEquals(1, holders(List.of(again)).size());
    }

    @Test
    void aPlaceOneFlowHoldsIsNotFreeToAnotherOnTheSameConnection() {
        JobQueue.FlowLeases flows = queue.flowLeases();
        JobQueue.ScanLease a = flows.lock(jobId()).orElseThrow();
        leases.add(a);
        JobQueue.ScanLease b = flows.lock(jobId()).orElseThrow();
        leases.add(b);
        long source = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50);
        JobQueue.SourceLimit one = new JobQueue.SourceLimit(source, "src-" + source, 1);
        JobQueue.Places taken = a.takePlaces(List.of(one));
        assertTrue(taken.taken());
        assertFalse(b.takePlaces(List.of(one)).taken(), "the source's one place is a's, though b is on a's session");
        a.givePlaces(taken.keys());
        assertTrue(b.takePlaces(List.of(one)).taken(), "given back, it is free");
        // a flow that ends without giving its places back frees them, for every session, while other
        // flows still hold the shared connection (on a's session the lock would be granted again anyway)
        b.close();
        leases.remove(b);
        JobQueue.ScanLease elsewhere = queue.lockPipelineJob(jobId()).orElseThrow();
        leases.add(elsewhere);
        assertTrue(elsewhere.takePlaces(List.of(one)).taken(), "b's place is free once b has ended");
    }
}
