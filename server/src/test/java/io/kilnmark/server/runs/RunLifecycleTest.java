package io.kilnmark.server.runs;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.pipelines.HoldingGraphRunner;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * The run lifecycle on a worker, end to end (efficiency review, 6 November 2026): a cancel asked of
 * another process, seen only in the database, still stops a running pipeline and a running flow
 * within a moment, through the run watcher; and the flows a worker watches hold their locks on one
 * connection, however many there are.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunLifecycleTest {

    /** Made-up credentials for the in-memory test connector. */
    static final String IN_SECRET = "rl-in-token-23";
    static final String OUT_SECRET = "rl-out-token-58";

    static final String COPY = """
        kilnmark: pipeline/v1
        name: rl-copy
        nodes:
          - id: people
            type: read
            source: rl-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: rl-out
            table: lab.rl_copy
            mode: replace
        """;

    /** The advisory lock class of a pipeline or flow job's lock ({@code JobQueue.PIPELINE_JOB_LOCK_CLASS}). */
    static final int JOB_LOCK_CLASS = 0x4B50;

    @Inject
    PipelineService pipelines;

    @Inject
    PipelineStore pipelineStore;

    @Inject
    FlowService flows;

    @Inject
    AgroalDataSource db;

    @AfterEach
    void release() {
        HoldingGraphRunner.hold = false;
    }

    private static <T> T await(String what, Supplier<T> look, Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = look.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last seen: " + last);
    }

    /** What another process's cancel writes: the run's row only, so nothing on this one is told. */
    private void cancelElsewhere(String table, long id) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE kilnmark." + table
            + " SET cancel_requested = true, error = 'Cancelled by another process' WHERE id = ?")) {
            ps.setLong(1, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("name", "rl-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "50"), "password", IN_SECRET)).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "rl-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);
        Tokens.as(Tokens.ADMIN).contentType(ContentType.JSON).body(Map.of("settings", Map.of("allow_writing", "true")))
            .put("/api/v1/sources/rl-out").then().statusCode(200);
        long scan = admin().post("/api/v1/sources/rl-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan " + scan, () -> admin().get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        admin().contentType(ContentType.JSON).body(Map.of("name", "rl-copy", "yaml", COPY)).post("/api/v1/pipelines")
            .then().statusCode(201);
    }

    @Test
    @Order(2)
    void aCancelAskedOfAnotherProcessStopsARunningPipeline() throws Exception {
        HoldingGraphRunner.hold = true;
        long id = pipelines.queueRun("rl-copy", null, Map.of(), "tester", AccessRole.STEWARD).id();
        await("the run to be running", () -> pipelineStore.run(id).orElseThrow().status(), "running"::equals);
        long asked = System.nanoTime();
        cancelElsewhere("pipeline_run", id);
        PipelineStore.Run done = await("the run to stop", () -> pipelineStore.run(id).orElseThrow(),
            PipelineStore.Run::finished);
        Duration took = Duration.ofNanos(System.nanoTime() - asked);
        assertEquals("cancelled", done.status());
        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "seen at the watcher's next look: " + took);
    }

    @Test
    @Order(3)
    void aCancelAskedOfAnotherProcessStopsARunningFlow() throws Exception {
        flows.save("""
            kilnmark: flow/v1
            name: rl-wait
            nodes:
              - { id: pause, type: wait, for: 60s }
            """, "test", "tester", AccessRole.DESIGNER, null);
        long id = flows.queueRun("rl-wait", null, Map.of(), "tester", AccessRole.STEWARD).id();
        await("the flow's wait to start", () -> flows.nodeRuns(id), n -> !n.isEmpty());
        long asked = System.nanoTime();
        cancelElsewhere("flow_run", id);
        FlowStore.Run done = await("the flow to stop", () -> flows.run(id).orElseThrow(), FlowStore.Run::finished);
        Duration took = Duration.ofNanos(System.nanoTime() - asked);
        assertEquals("cancelled", done.status());
        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "seen at the watcher's next look: " + took);
    }

    /** The backends holding the locks of the jobs of these flow runs. */
    private List<Integer> lockHolders(List<Long> flowRuns) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT DISTINCT l.pid "
            + "FROM pg_locks l JOIN kilnmark.job j ON l.objid::bigint = j.id % 4294967296 "
            + "WHERE l.locktype = 'advisory' AND l.objsubid = 2 AND l.granted AND l.classid = ?::bigint::oid "
            + "AND j.kind = 'flow' AND (j.payload ->> 'flowRunId')::bigint = ANY (?)")) {
            ps.setLong(1, JOB_LOCK_CLASS);
            ps.setArray(2, c.createArrayOf("bigint", flowRuns.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    @Test
    @Order(4)
    void theFlowsAWorkerWatchesHoldTheirLocksOnOneConnection() throws Exception {
        List<Long> runs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            flows.save("""
                kilnmark: flow/v1
                name: rl-watched-%d
                nodes:
                  - { id: pause, type: wait, for: 60s }
                """.formatted(i), "test", "tester", AccessRole.DESIGNER, null);
            runs.add(flows.queueRun("rl-watched-" + i, null, Map.of(), "tester", AccessRole.STEWARD).id());
        }
        try {
            for (long id : runs) {
                await("flow run " + id + " to be watched", () -> flows.nodeRuns(id), n -> !n.isEmpty());
            }
            List<Integer> holders = lockHolders(runs);
            assertEquals(1, holders.size(), "three flows watched, their locks on one connection: " + holders);
        } finally {
            for (long id : runs) {
                flows.cancel(id, "tester", AccessRole.STEWARD);
            }
        }
        for (long id : runs) {
            await("flow run " + id + " to stop", () -> flows.run(id).orElseThrow(), FlowStore.Run::finished);
        }
        await("their locks to be given back", () -> {
            try {
                return lockHolders(runs);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, List::isEmpty);
    }
}
