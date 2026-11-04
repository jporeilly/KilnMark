package io.kilnmark.server.jobs;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.pipelines.HoldingGraphRunner;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.kilnmark.server.scan.ScanService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Two workers in one process share the one queue, as two Kilnmark processes against one database
 * do (ADR 0040): with more pipeline runs queued than either has slots, every run runs once, the
 * slots of both are used, and a source's Most runs at once holds across both. The second worker is
 * built here beside the server's own.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwoWorkersTest {

    private static final int RUNS = 6;
    /** A suffix of this run's own, so the test runs again on a database that kept the last run's. */
    static final String ID = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30, 1L << 40),
        36);
    static final String SRC = "tw-src-" + ID;
    static final String OUT = "tw-out-" + ID;

    @Inject
    JobQueue queue;

    @Inject
    ScanService scans;

    @Inject
    Db db;

    @Inject
    KilnmarkConfig config;

    @Inject
    JobWorker first;

    @Inject
    io.kilnmark.server.pipelines.PipelineService pipelines;

    @Inject
    io.kilnmark.server.flows.FlowService flows;

    @Inject
    io.kilnmark.server.quality.QualityService quality;

    @Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;

    @Inject
    io.kilnmark.graph.store.GraphStore graph;

    @Inject
    AgroalDataSource data;

    static JobWorker second;

    @AfterAll
    static void stopSecond() {
        HoldingGraphRunner.hold = false;
        if (second != null) {
            second.stop();
        }
    }

    static String pipeline(int n) {
        return "tw-" + ID + "-" + n;
    }

    static String yaml(int n) {
        return """
            kilnmark: pipeline/v1
            name: %s
            nodes:
              - id: people
                type: read
                source: %s
                asset: lab.people
              - id: out
                type: write
                input: people
                source: %s
                table: lab.tw_%d
                mode: replace
            """.formatted(pipeline(n), SRC, OUT, n);
    }

    /** A run's job: its status, attempts and the worker that holds it (null while queued). */
    private record JobRow(long run, String status, int attempts, String worker) {
    }

    private List<JobRow> jobs(List<Long> runs) throws Exception {
        List<JobRow> out = new ArrayList<>();
        try (Connection c = data.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT "
            + "(payload ->> 'pipelineRunId')::bigint, status, attempts, locked_by FROM kilnmark.job "
            + "WHERE kind = 'pipeline' AND (payload ->> 'pipelineRunId')::bigint = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("bigint", runs.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new JobRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    /** How many times each run started: its log's run_started events. */
    private Map<Long, Integer> starts(List<Long> runs) throws Exception {
        Map<Long, Integer> out = new HashMap<>();
        try (Connection c = data.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT run_id, count(*) "
            + "FROM kilnmark.pipeline_run_event WHERE kind = 'run_started' AND run_id = ANY (?) GROUP BY run_id")) {
            ps.setArray(1, c.createArrayOf("bigint", runs.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getInt(2));
                }
            }
        }
        return out;
    }

    private static String status(long run) {
        return admin().get("/api/v1/pipeline-runs/" + run).then().statusCode(200).extract().jsonPath()
            .getString("run.status");
    }

    private List<JobRow> await(List<Long> runs, java.util.function.Predicate<List<JobRow>> done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        List<JobRow> last = List.of();
        while (System.nanoTime() < deadline) {
            last = jobs(runs);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out; jobs: " + last);
    }

    private static long running(List<JobRow> jobs) {
        return jobs.stream().filter(j -> j.status().equals("running")).count();
    }

    private List<Long> queueAll() {
        List<Long> runs = new ArrayList<>();
        for (int n = 1; n <= RUNS; n++) {
            runs.add(admin().contentType(ContentType.JSON).post("/api/v1/pipelines/" + pipeline(n) + "/runs").then().statusCode(202).extract().jsonPath()
                .getLong("id"));
        }
        return runs;
    }

    /** Lets the held runs go and checks every one succeeded, having started once and used one attempt at most. */
    private void finish(List<Long> runs) throws Exception {
        HoldingGraphRunner.hold = false;
        List<JobRow> done = await(runs, js -> js.size() == RUNS && js.stream().allMatch(j -> j.status().equals("done")));
        for (JobRow j : done) {
            assertTrue(j.attempts() <= 1, "no run used more than its one attempt: " + done);
            assertEquals("succeeded", status(j.run()), "run " + j.run());
        }
        Map<Long, Integer> starts = starts(runs);
        for (long r : runs) {
            assertEquals(1, starts.getOrDefault(r, 0), "run " + r + " started once");
        }
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        assertTrue(first.running(), "the server's own worker");
        assertEquals(2, config.worker().concurrency());
        admin().contentType(ContentType.JSON).body(Map.of("name", SRC, "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "20"), "password", "tw-src-secret-3")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", OUT, "kind", MemoryConnector.KIND,
            "settings", Map.of("allow_writing", "true"), "password", "tw-out-secret-4")).post("/api/v1/sources").then()
            .statusCode(201);
        long scan = admin().post("/api/v1/sources/" + SRC + "/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String s = "queued";
        while (System.nanoTime() < deadline && !"succeeded".equals(s)) {
            Thread.sleep(250);
            s = admin().get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", s);
        for (int n = 1; n <= RUNS; n++) {
            admin().contentType(ContentType.JSON).body(Map.of("name", pipeline(n), "yaml", yaml(n)))
                .post("/api/v1/pipelines").then().statusCode(201).body("latest.valid", equalTo(true));
        }

        second = new JobWorker(queue, scans, db, config);
        second.pipelines = pipelines;
        second.flows = flows;
        second.quality = quality;
        second.notifications = notifications;
        second.graph = graph;
        second.start();
        assertTrue(second.running());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    @Order(2)
    void everyRunRunsOnceAndBothWorkersSlotsAreUsed() throws Exception {
        HoldingGraphRunner.hold = true;
        List<Long> runs;
        try {
            runs = queueAll();
            // Two slots each: four run at once, two on each worker, and the rest wait in the queue.
            List<JobRow> busy = await(runs, js -> running(js) == 4);
            Map<String, Long> byWorker = busy.stream().filter(j -> j.status().equals("running"))
                .collect(Collectors.groupingBy(JobRow::worker, Collectors.counting()));
            assertEquals(Map.of(first.id(), 2L, second.id(), 2L), byWorker, busy.toString());
            Thread.sleep(2_000);
            assertEquals(4, running(jobs(runs)), "no worker runs more than its slots");
        } finally {
            HoldingGraphRunner.hold = false;
        }
        finish(runs);
    }

    @Test
    @Order(3)
    void aSourcesLimitHoldsAcrossBothWorkers() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("settings", Map.of("rows", "20", "max_runs", "2")))
            .put("/api/v1/sources/" + SRC).then().statusCode(200);
        HoldingGraphRunner.hold = true;
        List<Long> runs;
        try {
            runs = queueAll();
            // Four slots between them, but only two places on the source they all read.
            await(runs, js -> running(js) == 2);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline) {
                long now = runs.stream().filter(r -> "running".equals(status(r))).count();
                assertTrue(now <= 2, "at most two runs on the source at once, whichever worker: " + now);
                Thread.sleep(250);
            }
            long waiting = runs.stream().filter(r -> admin().get("/api/v1/pipeline-runs/" + r).then().extract().jsonPath()
                .getString("run.error") != null).count();
            assertEquals(RUNS - 2, waiting, "the others say what they wait for");
        } finally {
            HoldingGraphRunner.hold = false;
        }
        finish(runs);
    }
}
