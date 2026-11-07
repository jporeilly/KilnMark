package io.kilnmark.server.runs;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Db;
import io.kilnmark.server.Tokens;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.notify.NotificationPublisher;
import io.kilnmark.server.pipelines.HoldingGraphRunner;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.kilnmark.server.pipelines.PipelineStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/**
 * Run retries, carrying on from a failure, Run again from the failure and owners, end to end on
 * PostgreSQL (ADR 0053). A retry's wait is at least ten seconds, so a test that has seen a run queued
 * for its next try brings its job forward rather than waiting; the run's own rows decide the rest.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunRetriesApiTest {

    static final String COPY = """
        kilnmark: pipeline/v1
        name: NAME
        RUN
        nodes:
          - id: people
            type: read
            source: rr-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: rr-out
            table: lab.TABLE
            mode: MODE
        """;

    static String pipeline(String name, String run, String mode) {
        return COPY.replace("NAME", name).replace("TABLE", name.replace('-', '_')).replace("RUN\n", run)
            .replace("MODE", mode);
    }

    static final String RETRIES = "run:\n  retries: 2\n  retry_delay: 10s\n";

    @Inject
    FlowService flows;

    @Inject
    FlowStore flowStore;

    @Inject
    PipelineStore pipelineStore;

    @Inject
    JobQueue queue;

    @Inject
    NotificationPublisher notifications;

    @Inject
    io.kilnmark.server.pipelines.PipelineService pipelineService;

    @Inject
    Db db;

    static String designer;
    static String steward;
    static String owner;
    static String quiet;

    @AfterEach
    void release() {
        HoldingGraphRunner.hold = false;
        HoldingGraphRunner.failWith = null;
        HoldingGraphRunner.failNext = null;
    }

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static <T> T await(String what, Supplier<T> look, Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = look.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last seen: " + last);
    }

    private long count(String query, Object... args) {
        return db.tx(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private void update(String sql, Object... args) {
        db.tx(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("NOTIFY " + JobQueue.CHANNEL);
            }
            return null;
        });
    }

    /** Brings a run waiting for its next try forward: its job is due now, rather than in ten seconds or more. */
    private void now(String kind, long runId) {
        update("UPDATE kilnmark.job SET run_after = now() WHERE kind = ? AND (payload ->> ?)::bigint = ? "
            + "AND status = 'queued'", kind, kind + "RunId", runId);
    }

    private PipelineStore.Run pipelineRun(long id, Predicate<PipelineStore.Run> done, String what)
        throws InterruptedException {
        return await(what, () -> pipelineStore.run(id).orElseThrow(), done);
    }

    private long startPipeline(String name, String token) {
        return as(token).body(Map.of()).post("/api/v1/pipelines/" + name + "/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
    }

    private long events(String kind, String key) {
        return count("SELECT count(*) FROM kilnmark.notify_event WHERE kind = ? AND dedupe_key = ?", kind, key);
    }

    /** The kinds in a person's feed about {@code name}. */
    private static List<String> feed(String token, String name) {
        JsonPath p = as(token).get("/api/v1/notifications?limit=100").then().statusCode(200).extract().jsonPath();
        List<String> kinds = p.getList("items.kind");
        List<String> titles = p.getList("items.title");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            if (titles.get(i).contains(" " + name + " ") || titles.get(i).endsWith(" " + name)) {
                out.add(kinds.get(i));
            }
        }
        return out;
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        designer = token("rr-designer", "designer");
        steward = token("rr-steward", "steward");
        owner = token("rr-owner", "viewer");
        quiet = token("rr-quiet", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "rr-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "300"), "password", "rr-in-77")).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "rr-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", "rr-out-88")).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/rr-out")
            .then().statusCode(200);
        long scan = as(designer).post("/api/v1/sources/rr-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan", () -> as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        Map<String, String> pipelines = new java.util.LinkedHashMap<>();
        pipelines.put("rr-copy", pipeline("rr-copy", RETRIES, "replace"));
        pipelines.put("rr-broken", pipeline("rr-broken", "run:\n  retries: 1\n  retry_delay: 10s\n", "replace\n    fail: true"));
        pipelines.put("rr-flaky", pipeline("rr-flaky", "", "replace"));
        pipelines.put("rr-plain", pipeline("rr-plain", "", "replace"));
        pipelines.put("rr-plain2", pipeline("rr-plain2", "", "replace"));
        pipelines.put("rr-sched", pipeline("rr-sched", "run:\n  retries: 1\n  retry_delay: 10s\n", "replace\n    fail: true"));
        pipelines.put("rr-appends", pipeline("rr-appends", "", "append"));
        pipelines.put("rr-region", pipeline("rr-region", "parameters:\n  region: { type: text, default: x }\n", "replace")
            .replace("table: lab.rr_region", "table: lab.rr_${region}"));
        pipelines.forEach((name, yaml) -> as(designer).body(Map.of("name", name, "yaml", yaml)).post("/api/v1/pipelines")
            .then().statusCode(201));
        // The owner and a bystander have both unticked every kind in the app: only owning tells the owner.
        for (String t : List.of(owner, quiet)) {
            as(t).get("/api/v1/notifications/unread-count").then().statusCode(200);
            as(t).body(Map.of("enabled", false, "inAppKinds", List.of())).put("/api/v1/me/notifications").then()
                .statusCode(200);
        }
    }

    // ---- pipelines ----

    @Test
    @Order(2)
    void aFailedTryIsANewTryOfTheSameRunAndTellsNobody() throws Exception {
        HoldingGraphRunner.failWith = "the engine failed on purpose (rr)";
        long id = startPipeline("rr-copy", steward);
        PipelineStore.Run waiting = pipelineRun(id, r -> r.status().equals("queued") && r.runTry() == 1,
            "try 1 to fail and the run to wait for try 2");
        assertEquals(3, waiting.tries(), "1 + retries 2");
        assertNotNull(waiting.retryAt());
        assertTrue(waiting.retryAt().isAfter(java.time.Instant.now().plusSeconds(5)), "the wait is at least 10s");
        assertTrue(waiting.error().startsWith("Try 1 of 3 failed; trying again at "), waiting.error());
        JsonPath view = as(steward).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals("queued", view.getString("run.status"), "a waiting run is still going: kilnmark-run keeps waiting");
        assertEquals(2, view.getInt("run.tryNumber"));
        assertEquals(1, view.getList("tries").size());
        assertEquals("failed", view.getString("tries[0].status"));
        assertEquals("Tried again", view.getString("tries[0].next"));
        // One run at a time holds while it waits: Run now is refused, as a schedule's fire would be skipped.
        as(steward).body(Map.of()).post("/api/v1/pipelines/rr-copy/runs").then().statusCode(409);
        Thread.sleep(1500);
        assertEquals(0, events("pipeline_failed", "pipeline:rr-copy"), "no notice on a try that is tried again");

        now("pipeline", id);
        PipelineStore.Run done = pipelineRun(id, r -> r.finished(), "try 2");
        assertEquals("succeeded", done.status());
        assertEquals(1, done.runTry());
        assertEquals(1, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE pipeline = 'rr-copy'"),
            "one run, two tries: never a second run");
        List<RunTables.Try> tries = pipelineStore.tries(id);
        assertEquals(List.of("failed", "succeeded"), tries.stream().map(RunTables.Try::status).toList());
        assertTrue(count("SELECT count(*) FROM kilnmark.pipeline_run_event WHERE run_id = ? AND text LIKE 'Try 2 of 3 "
            + "started%'", id) == 1, "the log keeps every try");
        assertEquals(0, events("pipeline_failed", "pipeline:rr-copy"));
    }

    @Test
    @Order(3)
    void afterTheLastTryOneNoticeGoesToTheOwnersWhateverTheyChose() throws Exception {
        as(designer).body(Map.of("owners", List.of("rr-owner"))).put("/api/v1/pipelines/rr-broken/owners").then()
            .statusCode(200);
        long id = startPipeline("rr-broken", steward);
        pipelineRun(id, r -> r.status().equals("queued") && r.runTry() == 1, "try 1 to fail");
        Thread.sleep(1500);
        assertEquals(0, events("pipeline_failed", "pipeline:rr-broken"), "nothing told while tries are left");
        assertTrue(feed(owner, "rr-broken").isEmpty());
        now("pipeline", id);
        PipelineStore.Run done = pipelineRun(id, PipelineStore.Run::finished, "the last try");
        assertEquals("failed", done.status());
        assertTrue(done.error().contains(", after 2 tries. Not tried again: all 2 tries were made"), done.error());
        await("one failure notice", () -> events("pipeline_failed", "pipeline:rr-broken"), n -> n == 1);
        await("the owner told, though they unticked every kind", () -> feed(owner, "rr-broken"),
            k -> k.contains("pipeline_failed"));
        assertFalse(feed(quiet, "rr-broken").contains("pipeline_failed"), "others are told as their choices say");
        assertEquals(List.of("failed", "failed"), pipelineStore.tries(id).stream().map(RunTables.Try::status).toList());
    }

    @Test
    @Order(4)
    void aRunThatSucceedsAfterAToldFailureTellsTheOwnersItRecovered() throws Exception {
        as(designer).body(Map.of("owners", List.of("rr-owner"))).put("/api/v1/pipelines/rr-flaky/owners").then()
            .statusCode(200);
        HoldingGraphRunner.failWith = "failed on purpose (rr-flaky)";
        long first = startPipeline("rr-flaky", steward);
        assertEquals("failed", pipelineRun(first, PipelineStore.Run::finished, "the failure").status());
        await("the failure told", () -> feed(owner, "rr-flaky"), k -> k.contains("pipeline_failed"));
        long second = startPipeline("rr-flaky", steward);
        assertEquals("succeeded", pipelineRun(second, PipelineStore.Run::finished, "the next run").status());
        await("recovered, told to the owner", () -> feed(owner, "rr-flaky"), k -> k.contains("pipeline_recovered"));
        long third = startPipeline("rr-flaky", steward);
        pipelineRun(third, PipelineStore.Run::finished, "another success");
        Thread.sleep(1500);
        assertEquals(1, events("pipeline_recovered", "pipeline:rr-flaky"), "told once per problem");
    }

    @Test
    @Order(5)
    void anAppendingPipelineIsRefusedRetriesWhenSavedAndRun() {
        String yaml = pipeline("rr-appends", RETRIES, "append");
        JsonPath v = as(designer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .extract().jsonPath();
        assertFalse(v.getBoolean("valid"));
        assertTrue(v.getList("problems.message").toString().contains("every write must replace or upsert: out appends"),
            v.getList("problems.message").toString());
        assertTrue(as(designer).body(Map.of("yaml", pipeline("rr-appends", "run:\n  retries: 2\n  retry_on: lost\n",
            "append"))).post("/api/v1/pipelines/validate").then().statusCode(200).extract().jsonPath()
            .getList("problems.message").toString().contains("must replace or upsert"),
            "a lost run may have written too: refused whatever retry_on says");
        assertTrue(as(designer).body(Map.of("yaml", pipeline("rr-appends", "", "append")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).extract().jsonPath().getBoolean("valid"),
            "without retries it may append");
    }

    @Test
    @Order(6)
    void aCancelIsNeverTriedAgain() throws Exception {
        HoldingGraphRunner.failWith = "failed on purpose (rr-cancel)";
        long id = startPipeline("rr-copy", steward);
        pipelineRun(id, r -> r.status().equals("queued") && r.runTry() == 1, "the run to wait for its next try");
        as(steward).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(200);
        PipelineStore.Run r = pipelineRun(id, PipelineStore.Run::finished, "the cancel");
        assertEquals("cancelled", r.status());
        now("pipeline", id);
        Thread.sleep(2500);
        assertEquals("cancelled", pipelineStore.run(id).orElseThrow().status(), "its job was taken off the queue");
        assertEquals(1, pipelineStore.tries(id).size(), "no second try");

        HoldingGraphRunner.hold = true;
        long running = startPipeline("rr-copy", steward);
        pipelineRun(running, r2 -> r2.status().equals("running"), "a running try");
        as(steward).post("/api/v1/pipeline-runs/" + running + "/cancel").then().statusCode(200);
        HoldingGraphRunner.hold = false;
        PipelineStore.Run cancelled = pipelineRun(running, PipelineStore.Run::finished, "the cancel of a running try");
        assertEquals("cancelled", cancelled.status());
        assertEquals(0, cancelled.runTry(), "a cancelled try is never tried again");
    }

    @Test
    @Order(7)
    void aScheduledRunIsNeverTriedAgainPastTheNextFire() throws Exception {
        HoldingGraphRunner.hold = true;
        long id = startPipeline("rr-sched", steward);
        pipelineRun(id, r -> r.status().equals("running"), "the run to start");
        // As if a schedule had started it and fires again in five seconds: a try ten seconds or more away
        // would run into that fire, so it is not made.
        update("INSERT INTO kilnmark.schedule (pipeline, cron, created_by, updated_by, next_at, last_pipeline_run) "
            + "VALUES ('rr-sched', '0 0 * * *', 'tester', 'tester', now() + interval '5 seconds', ?)", id);
        HoldingGraphRunner.hold = false;
        PipelineStore.Run done = pipelineRun(id, PipelineStore.Run::finished, "the failed try");
        assertEquals("failed", done.status());
        assertTrue(done.error().contains("Not tried again: the next scheduled run is due first"), done.error());
        assertEquals(0, done.runTry(), "the fire happens once, and its run is tried once");
    }

    // ---- flows ----

    private FlowStore.Run waitForFlow(long id, Predicate<FlowStore.Run> done, String what) throws InterruptedException {
        return await(what, () -> flows.run(id).orElseThrow(), done);
    }

    private FlowStore.NodeRun node(long runId, String id) {
        return flows.nodeRuns(runId).stream().filter(s -> s.nodeId().equals(id)).findFirst().orElse(null);
    }

    private long pipelineRuns(long flowRunId, String node) {
        return count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ? AND flow_node = ?", flowRunId,
            node);
    }

    static final String TWO = """
        kilnmark: flow/v1
        name: NAME
        RUN
        nodes:
          - { id: first, type: run-pipeline, pipeline: rr-plain }
          - { id: second, type: run-pipeline, pipeline: rr-plain2 }
        connections:
          - { from: first, to: second }
        """;

    /** Starts a flow whose first node succeeds and whose second fails, once. */
    private long startFailingSecond(String flow) {
        HoldingGraphRunner.failNext = "rr-plain2";
        return flows.queueRun(flow, null, Map.of(), "tester", AccessRole.STEWARD).id();
    }

    @Test
    @Order(10)
    void aFlowTriedAgainCarriesOnFromWhereItFailed() throws Exception {
        flows.save(TWO.replace("NAME", "rr-carry").replace("RUN\n", "run:\n  retries: 1\n  retry_delay: 10s\n"), "test",
            "tester", AccessRole.DESIGNER, null);
        long id = startFailingSecond("rr-carry");
        FlowStore.Run waiting = waitForFlow(id, r -> r.status().equals("queued") && r.runTry() == 1,
            "the flow to wait for its next try");
        assertEquals(2, waiting.tries());
        long firstRun = node(id, "first").pipelineRunId();
        now("flow", id);
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "try 2");
        assertEquals("succeeded", done.status(), String.valueOf(done.error()));
        assertEquals(firstRun, node(id, "first").pipelineRunId(), "first succeeded: kept, never run again");
        assertEquals(1, pipelineRuns(id, "first"));
        assertEquals(2, pipelineRuns(id, "second"), "second ran again, as a try of its own");
        assertEquals(1, pipelineStore.flowLatestTry(id, "second", null));
        assertEquals(List.of("failed", "succeeded"), flowStore.tries(id).stream().map(RunTables.Try::status).toList());
        assertEquals(0, events("flow_failed", "flow:rr-carry"), "no notice on a try that was tried again");
    }

    @Test
    @Order(11)
    void nodeRetriesTakeThePlaceOfTheFlowsSoTriesNeverMultiply() throws Exception {
        flows.save("""
            kilnmark: flow/v1
            name: rr-own
            run: { retries: 2, retry_delay: 10s }
            nodes:
              - { id: load, type: run-pipeline, pipeline: rr-broken, retries: 1, retry_delay: 10s }
            """, "test", "tester", AccessRole.DESIGNER, null);
        long id = flows.queueRun("rr-own", null, Map.of(), "tester", AccessRole.STEWARD).id();
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "the flow");
        assertEquals("failed", done.status());
        assertTrue(done.error().contains("has retries of its own"), done.error());
        assertEquals(2, pipelineRuns(id, "load"), "1 + the node's 1 retry; never (1 + 1) x (1 + 2)");
        assertEquals(0, done.runTry(), "the flow was not tried again");
        PipelineStore.Run started = pipelineStore.flowNodeRun(id, "load").orElseThrow();
        assertEquals(1, started.tries(), "a run a flow node starts never uses its pipeline's own run: retries");
        assertEquals(0, started.runTry());
    }

    @Test
    @Order(12)
    void aForEachNodeTriesOnlyItsFailedItems() throws Exception {
        flows.save("""
            kilnmark: flow/v1
            name: rr-each
            run: { retries: 1, retry_delay: 10s }
            nodes:
              - id: regions
                type: for-each
                pipeline: rr-region
                items: [ra, rb, rc]
                as: region
                max_parallel: 1
            """, "test", "tester", AccessRole.DESIGNER, null);
        HoldingGraphRunner.failWith = "the first item fails on purpose (rr)";
        long id = flows.queueRun("rr-each", null, Map.of(), "tester", AccessRole.STEWARD).id();
        waitForFlow(id, r -> r.status().equals("queued") && r.runTry() == 1, "the flow to wait for its next try");
        now("flow", id);
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "try 2");
        assertEquals("succeeded", done.status(), String.valueOf(done.error()));
        assertEquals(2, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ? AND flow_item = 0", id));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ? AND flow_item = 1", id),
            "an item that succeeded is not run again");
        assertEquals(1, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ? AND flow_item = 2", id));
    }

    @Test
    @Order(13)
    void runAgainFromTheFailureKeepsWhatSucceeded() throws Exception {
        flows.save(TWO.replace("NAME", "rr-again").replace("RUN\n", ""), "test", "tester", AccessRole.DESIGNER, null);
        long id = startFailingSecond("rr-again");
        FlowStore.Run failed = waitForFlow(id, FlowStore.Run::finished, "the failure");
        assertEquals("failed", failed.status());
        long firstRun = node(id, "first").pipelineRunId();
        Tokens.as(owner).post("/api/v1/flow-runs/" + id + "/run-again").then().statusCode(403);
        JsonPath again = as(steward).post("/api/v1/flow-runs/" + id + "/run-again").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(id, again.getLong("id"), "the same run, a new try");
        assertEquals(2, again.getInt("tryNumber"));
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "the try by hand");
        assertEquals("succeeded", done.status(), String.valueOf(done.error()));
        assertEquals(firstRun, node(id, "first").pipelineRunId(), "first kept");
        assertEquals(2, pipelineRuns(id, "second"));
        List<RunTables.Try> tries = flowStore.tries(id);
        assertEquals("Run again from the failure", tries.get(0).next());
        assertEquals("rr-steward", tries.get(0).askedBy(), "recorded against the run: who asked");
        assertEquals(1, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'flow.run_again' AND target = ?",
            String.valueOf(id)));
        as(steward).post("/api/v1/flow-runs/" + id + "/run-again").then().statusCode(409);
    }

    @Test
    @Order(14)
    void runAgainIsRefusedWhenANodeIsNotSafeToRunAgain() throws Exception {
        flows.save("""
            kilnmark: flow/v1
            name: rr-again-append
            nodes:
              - { id: load, type: run-pipeline, pipeline: rr-appends }
            """, "test", "tester", AccessRole.DESIGNER, null);
        HoldingGraphRunner.failWith = "load fails on purpose (rr)";
        long id = flows.queueRun("rr-again-append", null, Map.of(), "tester", AccessRole.STEWARD).id();
        waitForFlow(id, FlowStore.Run::finished, "the failure");
        String message = as(steward).post("/api/v1/flow-runs/" + id + "/run-again").then().statusCode(409).extract()
            .jsonPath().getString("error");
        assertTrue(message.contains("node load") && message.contains("out appends"), message);
        assertEquals("failed", flows.run(id).orElseThrow().status());
        // And a flow tried again by itself must not run such a pipeline again: refused when saved.
        assertFalse(flows.validate("""
            kilnmark: flow/v1
            name: rr-again-append
            run: { retries: 1 }
            nodes:
              - { id: load, type: run-pipeline, pipeline: rr-appends }
            """).valid());
        assertTrue(flows.validate("""
            kilnmark: flow/v1
            name: rr-again-append
            run: { retries: 1, retry_on: lost }
            nodes:
              - { id: load, type: run-pipeline, pipeline: rr-appends }
            """).valid(), "a takeover never starts a pipeline run again");
    }

    @Test
    @Order(15)
    void aRunLostWithItsWorkerIsTakenOverAndCarriesOn() throws Exception {
        flows.save(TWO.replace("NAME", "rr-lost").replace("RUN\n", "run: { retries: 1, retry_on: lost }\n"), "test",
            "tester", AccessRole.DESIGNER, null);
        // A run left by a worker that stopped: first had succeeded, second's pipeline run is still going (held).
        long id = db.tx(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_run (flow, version, "
                + "status, requested_by, requested_role, started_at, tries) VALUES ('rr-lost', 1, 'running', 'tester', "
                + "'steward', now(), 2) RETURNING id")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        PipelineStore.Run a = pipelineStore.queueRun("rr-plain", 1, "tester", "steward", id, "first", "{}", "{}");
        pipelineRun(a.id(), PipelineStore.Run::finished, "first's run");
        flowStore.saveNode(id, new FlowStore.NodeRun("first", "run-pipeline", 0, "succeeded", java.time.Instant.now(),
            java.time.Instant.now(), a.id(), null, "{\"rows_written\": 300.0}"));
        HoldingGraphRunner.hold = true;
        PipelineStore.Run b = pipelineStore.queueRun("rr-plain2", 1, "tester", "steward", id, "second", "{}", "{}");
        pipelineRun(b.id(), r -> r.status().equals("running"), "second's run");
        flowStore.saveNode(id, new FlowStore.NodeRun("second", "run-pipeline", 1, "running", java.time.Instant.now(),
            null, b.id(), null, "{}"));
        update("INSERT INTO kilnmark.job (kind, payload, status, attempts, max_attempts, locked_by, locked_at, "
            + "heartbeat_at) VALUES ('flow', jsonb_build_object('flowRunId', ?::bigint), 'running', 1, 2, 'gone-worker', "
            + "now() - interval '10 minutes', now() - interval '10 minutes')", id);
        JobQueue.Recovery r = queue.recoverStale(Duration.ofMinutes(2), Duration.ofSeconds(30));
        assertTrue(r.requeued() >= 1, "lost after kilnmark.runs.lost-after, and queued again: it has a try left");
        FlowStore.Run running = waitForFlow(id, x -> x.runTry() == 1, "the takeover");
        assertEquals("running", running.status(), "taken over, still running: never queued as a new run");
        HoldingGraphRunner.hold = false;
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "the flow to carry on and end");
        assertEquals("succeeded", done.status(), String.valueOf(done.error()));
        assertEquals(1, pipelineRuns(id, "first"), "first was not run again");
        assertEquals(1, pipelineRuns(id, "second"), "second's run was watched to its end, never started again");
        assertEquals("lost", flowStore.tries(id).get(0).status());
    }

    @Test
    @Order(16)
    void aTakeoverOnALaterTryRunsAgainWhatThatTryRunsAgain() throws Exception {
        flows.save(TWO.replace("NAME", "rr-lost2").replace("RUN\n", "run: { retries: 2, retry_delay: 10s }\n"), "test",
            "tester", AccessRole.DESIGNER, null);
        // Try 2 of a run whose second node failed in try 1: try 2 kept first and cleared second's row, then its
        // worker was lost before second started again.
        long id = db.tx(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.flow_run (flow, version, "
                + "status, requested_by, requested_role, started_at, tries, run_try) VALUES ('rr-lost2', 1, 'running', "
                + "'tester', 'steward', now(), 3, 1) RETURNING id")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        PipelineStore.Run a = pipelineStore.queueRun("rr-plain", 1, "tester", "steward", id, "first", "{}", "{}");
        pipelineRun(a.id(), PipelineStore.Run::finished, "first's run");
        flowStore.saveNode(id, new FlowStore.NodeRun("first", "run-pipeline", 0, "succeeded", java.time.Instant.now(),
            java.time.Instant.now(), a.id(), null, "{}"));
        HoldingGraphRunner.failNext = "rr-plain2";
        PipelineStore.Run b = pipelineStore.queueRun("rr-plain2", 1, "tester", "steward", id, "second", "{}", "{}");
        assertEquals("failed", pipelineRun(b.id(), PipelineStore.Run::finished, "try 1's run of second").status());
        update("INSERT INTO kilnmark.job (kind, payload, status, attempts, max_attempts, locked_by, locked_at, "
            + "heartbeat_at) VALUES ('flow', jsonb_build_object('flowRunId', ?::bigint), 'running', 2, 3, 'gone-worker', "
            + "now() - interval '10 minutes', now() - interval '10 minutes')", id);
        queue.recoverStale(Duration.ofMinutes(2), Duration.ofSeconds(30));
        FlowStore.Run done = waitForFlow(id, FlowStore.Run::finished, "the flow taken over");
        assertEquals("succeeded", done.status(), String.valueOf(done.error()));
        assertEquals(2, pipelineRuns(id, "second"), "second ran again, never counted as try 1's failed run");
        assertEquals(1, pipelineStore.flowLatestTry(id, "second", null));
        assertEquals(1, pipelineRuns(id, "first"));
    }

    @Test
    @Order(17)
    void aWorkerThatLostItsJobChangesNothingOfAnothersTry() throws Exception {
        HoldingGraphRunner.hold = true;
        long id = startPipeline("rr-region", steward);
        PipelineStore.Run running = pipelineRun(id, r -> r.status().equals("running"), "a running try");
        long job = count("SELECT id FROM kilnmark.job WHERE kind = 'pipeline' AND (payload ->> 'pipelineRunId')::bigint = ?",
            id);
        String holder = pipelineStore.jobHolder(job);
        assertFalse(pipelineStore.finishRun(id, "failed", "a stale worker's end", null, "error", null,
            running.runTry() + 1), "ending another try is refused");
        assertFalse(pipelineStore.retry(id, job, holder, running.runTry() + 1, java.time.Instant.now(), "x", "y"),
            "so is queueing it again");
        assertFalse(pipelineStore.retry(id, job, "another-worker", running.runTry(), java.time.Instant.now(), "x", "y"),
            "and by a worker that does not hold the job");
        assertEquals("running", pipelineStore.run(id).orElseThrow().status());
        HoldingGraphRunner.hold = false;
        assertEquals("succeeded", pipelineRun(id, PipelineStore.Run::finished, "the try").status());
    }

    @Test
    @Order(18)
    void aPipelineRunLostOnItsLastTryIsTold() throws Exception {
        as(designer).body(Map.of("owners", List.of("rr-owner"))).put("/api/v1/pipelines/rr-region/owners").then()
            .statusCode(200);
        // A run whose worker stopped, and whose job recovery failed: no tries left.
        long id = db.tx(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.pipeline_run (pipeline, "
                + "version, status, requested_by, requested_role, started_at) VALUES ('rr-region', 1, 'running', "
                + "'tester', 'steward', now()) RETURNING id")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        update("INSERT INTO kilnmark.job (kind, payload, status, attempts, max_attempts, locked_by) VALUES ('pipeline', "
            + "jsonb_build_object('pipelineRunId', ?::bigint), 'failed', 1, 1, 'gone-worker')", id);
        pipelineService.failOrphanedRuns();
        assertEquals("failed", pipelineStore.run(id).orElseThrow().status());
        await("the owner told", () -> feed(owner, "rr-region"), k -> k.contains("pipeline_failed"));
        assertEquals("lost", pipelineStore.tries(id).get(0).status());
    }

    // ---- owners and the long-run notice ----

    @Test
    @Order(20)
    void ownersAreSetByDesignersAreAuditedAndGiveNoRights() {
        JsonPath shown = as(owner).get("/api/v1/pipelines/rr-plain/owners").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("rr-designer"), shown.getList("owners.entry"), "by default whoever first saved it");
        as(steward).body(Map.of("owners", List.of("rr-steward"))).put("/api/v1/pipelines/rr-plain/owners").then()
            .statusCode(403);
        as(designer).body(Map.of("owners", List.of("nobody@elsewhere.example"))).put("/api/v1/pipelines/rr-plain/owners")
            .then().statusCode(400);
        long versions = count("SELECT latest_version FROM kilnmark.pipeline WHERE name = 'rr-plain'");
        as(designer).body(Map.of("owners", List.of("rr-owner", "rr-quiet"))).put("/api/v1/pipelines/rr-plain/owners")
            .then().statusCode(200);
        assertEquals(versions, count("SELECT latest_version FROM kilnmark.pipeline WHERE name = 'rr-plain'"),
            "owners are not part of the YAML: no new version");
        assertEquals(1, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'pipeline.owners_set' AND "
            + "target = 'rr-plain'"));
        // An owner who is a viewer still cannot run it, or change its owners.
        as(owner).body(Map.of()).post("/api/v1/pipelines/rr-plain/runs").then().statusCode(403);
        as(owner).body(Map.of("owners", List.of())).put("/api/v1/pipelines/rr-plain/owners").then().statusCode(403);
        as(designer).body(Map.of("owners", List.of())).put("/api/v1/flows/rr-carry/owners").then().statusCode(200);
        JsonPath none = as(owner).get("/api/v1/flows/rr-carry/owners").then().statusCode(200).extract().jsonPath();
        assertTrue(none.getBoolean("none"));
        assertTrue(none.getString("note").contains("admins are told"));
    }

    @Test
    @Order(21)
    void aRunTakingLongerThanExpectedTellsItsOwnersOnce() throws Exception {
        as(designer).body(Map.of("owners", List.of("rr-owner"))).put("/api/v1/pipelines/rr-plain2/owners").then()
            .statusCode(200);
        HoldingGraphRunner.hold = true;
        long id = startPipeline("rr-plain2", steward);
        pipelineRun(id, r -> r.status().equals("running"), "a running run");
        // What the run watcher does at warn_after (RunWatchTest proves it fires once, at the time).
        assertTrue(pipelineStore.markWarned(id));
        notifications.runTakingLong("pipeline", "rr-plain2", id, "1h");
        assertFalse(pipelineStore.markWarned(id), "once a run");
        await("the owner told it is taking long", () -> feed(owner, "rr-plain2"), k -> k.contains("run_taking_long"));
        assertFalse(feed(quiet, "rr-plain2").contains("run_taking_long"));
        HoldingGraphRunner.hold = false;
        assertNull(pipelineRun(id, PipelineStore.Run::finished, "the run").error());
    }
}
