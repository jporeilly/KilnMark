package io.kilnmark.server.flows;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.HoldingGraphRunner;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.kilnmark.server.pipelines.PipelineStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * Flows end to end on PostgreSQL (docs/design/flows.md): a flow's nodes run saved pipelines on the
 * job queue, as the person who started the flow, and the connections decide what runs next: success,
 * failure, and a condition on what the pipeline measured. Sources are the in-memory test
 * connector, so nothing outside the test is written.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowRunTest {

    /** Made-up credentials for the in-memory test connector, as PipelineFlowTest has. */
    static final String IN_SECRET = "fl-in-token-41";
    static final String OUT_SECRET = "fl-out-token-97";

    static final String COPY = """
        kilnmark: pipeline/v1
        name: fl-copy
        description: Synthetic people, copied.
        nodes:
          - id: people
            type: read
            source: fl-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: fl-out
            table: lab.fl_copy
            mode: replace
        """;

    /** 2,500 people; the 500 with an id above 2,000 are set aside: a fifth. */
    static final String GATED = """
        kilnmark: pipeline/v1
        name: fl-gated
        description: Synthetic people, checked on the way.
        nodes:
          - id: people
            type: read
            source: fl-src
            asset: lab.people

          - id: check
            type: gate
            input: people
            on_fail: quarantine
            rules:
              - {name: early ids, type: in_range, field: id, max: 2000}

          - id: good
            type: write
            input: check
            source: fl-out
            table: lab.fl_checked
            mode: replace

          - id: aside
            type: write
            input: check.quarantine
            source: fl-out
            table: lab.fl_aside
            mode: replace
        """;

    static final String BROKEN = COPY.replace("name: fl-copy", "name: fl-broken")
        .replace("table: lab.fl_copy", "table: lab.fl_broken").replace("mode: replace", "mode: replace\n    fail: true");

    @Inject
    FlowService flows;

    @Inject
    PipelineStore pipelineStore;

    static String designer;
    static String viewer;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    @AfterEach
    void release() {
        HoldingGraphRunner.hold = false;
    }

    private static <T> T await(String what, java.util.function.Supplier<T> look, Predicate<T> done)
        throws InterruptedException {
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

    private FlowStore.Run waitForFlow(long id, String status) throws InterruptedException {
        return await("flow run " + id + " to be " + status, () -> flows.run(id).orElseThrow(),
            r -> status.equals(r.status()));
    }

    private FlowStore.NodeRun node(long runId, String id) {
        return flows.nodeRuns(runId).stream().filter(s -> s.nodeId().equals(id)).findFirst().orElse(null);
    }

    private FlowStore.Flow save(String yaml) {
        return flows.save(yaml, "test", "tester", AccessRole.DESIGNER, null);
    }

    private FlowStore.Run start(String flow, Map<String, Object> parameters) {
        return flows.queueRun(flow, null, parameters, "tester", AccessRole.STEWARD);
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        designer = token("flow-designer", "designer");
        viewer = token("flow-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "2500"), "password", IN_SECRET)).post("/api/v1/sources")
            .then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/fl-out")
            .then().statusCode(200);
        long scan = as(designer).post("/api/v1/sources/fl-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan " + scan, () -> as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        for (String yaml : List.of(COPY, GATED, BROKEN)) {
            String name = yaml.lines().filter(l -> l.startsWith("name: ")).findFirst().orElseThrow().substring(6);
            as(designer).body(Map.of("name", name, "yaml", yaml)).post("/api/v1/pipelines").then().statusCode(201);
        }
    }

    @Test
    @Order(2)
    void nodesRunInOrderAndEachPipelineRunSaysWhichFlowStartedIt() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-sequence
            nodes:
              - { id: first, type: run-pipeline, pipeline: fl-copy }
              - { id: second, type: run-pipeline, pipeline: fl-gated }
            connections:
              - { from: first, to: second }
            """);
        FlowStore.Run run = start("fl-sequence", Map.of());
        assertEquals("queued", run.status());
        FlowStore.Run done = waitForFlow(run.id(), "succeeded");
        assertNull(done.error());
        assertEquals("succeeded", node(run.id(), "first").status());
        assertEquals("succeeded", node(run.id(), "second").status());
        PipelineStore.Run a = pipelineStore.flowNodeRun(run.id(), "first").orElseThrow();
        PipelineStore.Run b = pipelineStore.flowNodeRun(run.id(), "second").orElseThrow();
        assertEquals("fl-copy", a.pipeline());
        assertEquals("fl-gated", b.pipeline());
        assertEquals("tester", a.requestedBy(), "the pipeline runs as the person who started the flow");
        assertEquals("steward", a.requestedRole());
        assertFalse(b.startedAt().isBefore(a.finishedAt()), "second started only after first had finished");
        assertEquals(a.id(), node(run.id(), "first").pipelineRunId());
        assertNotNull(node(run.id(), "second").finishedAt());

        // The run page's summary: what its pipeline runs came to, from what they recorded.
        FlowViews.RunDetail detail = flows.runDetail(run.id(), false);
        long rowsA = as(designer).get("/api/v1/pipeline-runs/" + a.id()).then().extract().jsonPath()
            .getLong("run.rowsWritten");
        long rowsB = as(designer).get("/api/v1/pipeline-runs/" + b.id()).then().extract().jsonPath()
            .getLong("run.rowsWritten");
        Float scoreB = as(designer).get("/api/v1/pipeline-runs/" + b.id()).then().extract().jsonPath()
            .get("run.qualityScore");
        assertEquals(2, detail.run().pipelineRuns());
        assertEquals(rowsA + rowsB, detail.run().rowsWritten(), "the rows its pipeline runs wrote, in all");
        assertNotNull(scoreB, "the gated pipeline measures what it wrote");
        assertTrue(detail.run().qualityScore() <= scoreB + 1e-6, "the lowest score among its pipeline runs");
        FlowViews.RunSummary listed = flows.runViews("fl-sequence", 5, false).get(0);
        assertEquals(detail.run().rowsWritten(), listed.rowsWritten(), "the runs list says the same");
        assertEquals(2, listed.pipelineRuns());
        // And each node's pipeline run times, for where its time went.
        FlowViews.PipelineRunTimes times = detail.nodes().get(0).pipelineRunTimes();
        assertEquals(a.requestedAt(), times.requestedAt());
        assertEquals(a.startedAt(), times.startedAt());
        assertEquals(a.finishedAt(), times.finishedAt());
        assertFalse(times.requestedAt().isBefore(detail.nodes().get(0).startedAt().minusSeconds(1)),
            "the node asked for its run as it started");
    }

    @Test
    @Order(3)
    void aConditionOnWhatThePipelineMeasuredChoosesTheConnection() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-quality
            parameters:
              limit_share: { type: number, default: 0.02 }
            nodes:
              - { id: clean, type: run-pipeline, pipeline: fl-gated }
              - { id: publish, type: run-pipeline, pipeline: fl-copy }
              - id: halt
                type: stop
                status: failed
                message: Too many customers were set aside
            connections:
              - { from: clean, to: publish, when: "clean.quarantined_share < limit_share" }
              - { from: clean, to: halt, when: "clean.quarantined_share >= limit_share" }
            """);
        // A fifth is set aside, more than 2%: publish is skipped and the flow stops.
        FlowStore.Run run = start("fl-quality", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        assertEquals("Too many customers were set aside", done.error());
        assertEquals("succeeded", node(run.id(), "clean").status());
        assertEquals("skipped", node(run.id(), "publish").status());
        assertEquals("succeeded", node(run.id(), "halt").status());
        assertTrue(node(run.id(), "clean").reported().contains("\"quarantined_share\": 0.2"),
            node(run.id(), "clean").reported());
        assertTrue(node(run.id(), "clean").reported().contains("\"rows_written\""), node(run.id(), "clean").reported());

        // With the limit raised by a parameter, the same data passes and publish runs.
        FlowStore.Run second = start("fl-quality", Map.of("limit_share", 0.5));
        FlowStore.Run ok = waitForFlow(second.id(), "succeeded");
        assertNull(ok.error());
        assertEquals("succeeded", node(second.id(), "publish").status());
        assertEquals("skipped", node(second.id(), "halt").status());
        assertTrue(ok.parameters().contains("0.5"), ok.parameters());
    }

    @Test
    @Order(4)
    void aFailureConnectionHandlesAFailureAndWithoutOneTheFlowFails() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-handled
            nodes:
              - { id: broken, type: run-pipeline, pipeline: fl-broken }
              - { id: after, type: run-pipeline, pipeline: fl-copy }
              - { id: tell, type: stop, status: succeeded, message: Handled }
            connections:
              - { from: broken, to: after }
              - { from: broken, to: tell, when: failure }
            """);
        FlowStore.Run handled = start("fl-handled", Map.of());
        FlowStore.Run done = waitForFlow(handled.id(), "succeeded");
        assertEquals("Handled", done.error(), "a stop node's message is kept even when the flow succeeds");
        assertEquals("failed", node(handled.id(), "broken").status());
        assertEquals("skipped", node(handled.id(), "after").status());
        assertEquals("succeeded", node(handled.id(), "tell").status());
        assertFalse(node(handled.id(), "broken").message().contains("secret"),
            "the node says that the pipeline failed, not why");

        save("""
            kilnmark: flow/v1
            name: fl-unhandled
            nodes:
              - { id: broken, type: run-pipeline, pipeline: fl-broken }
              - { id: after, type: run-pipeline, pipeline: fl-copy }
            connections:
              - { from: broken, to: after }
            """);
        FlowStore.Run unhandled = start("fl-unhandled", Map.of());
        FlowStore.Run failed = waitForFlow(unhandled.id(), "failed");
        assertTrue(failed.error().startsWith("Node broken failed"), failed.error());
        assertEquals("skipped", node(unhandled.id(), "after").status());
    }

    @Test
    @Order(5)
    void aNodeWaitsForAPipelineThatIsAlreadyRunningThenGivesUp() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-waits
            nodes:
              - { id: copy, type: run-pipeline, pipeline: fl-copy }
            """);
        save("""
            kilnmark: flow/v1
            name: fl-gives-up
            queue_timeout: 1s
            nodes:
              - { id: copy, type: run-pipeline, pipeline: fl-copy }
            """);
        HoldingGraphRunner.hold = true;
        long direct = as(designer).post("/api/v1/pipelines/fl-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the direct run to start", () -> pipelineStore.run(direct).orElseThrow().status(), "running"::equals);

        FlowStore.Run waits = start("fl-waits", Map.of());
        FlowStore.NodeRun waiting = await("the node to say it waits", () -> node(waits.id(), "copy"),
            s -> s != null && s.message() != null);
        assertEquals("running", waiting.status());
        assertEquals("Waiting for fl-copy to finish its current run", waiting.message());
        assertNull(waiting.pipelineRunId());

        // The direct run is held; cancelling it frees the pipeline, and later runs are not held.
        as(designer).post("/api/v1/pipeline-runs/" + direct + "/cancel").then().statusCode(200);
        await("the direct run to be cancelled", () -> pipelineStore.run(direct).orElseThrow().status(), "cancelled"::equals);
        HoldingGraphRunner.hold = false;
        FlowStore.Run done = waitForFlow(waits.id(), "succeeded");
        assertNull(done.error());
        assertNotNull(node(waits.id(), "copy").pipelineRunId());

        HoldingGraphRunner.hold = true;
        long again = as(designer).post("/api/v1/pipelines/fl-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the second direct run to start", () -> pipelineStore.run(again).orElseThrow().status(), "running"::equals);
        FlowStore.Run gives = start("fl-gives-up", Map.of());
        FlowStore.Run failed = waitForFlow(gives.id(), "failed");
        assertEquals("Node copy failed (the pipeline fl-copy)", failed.error());
        assertTrue(node(gives.id(), "copy").message().startsWith("Waited 1 seconds for fl-copy"),
            node(gives.id(), "copy").message());
        // What the run page reads its wait from: the node's own times, with no pipeline run of its own.
        FlowViews.NodeRunView gaveUp = flows.runDetail(gives.id(), false).nodes().get(0);
        assertNull(gaveUp.pipelineRunTimes());
        assertTrue(Duration.between(gaveUp.startedAt(), gaveUp.finishedAt()).toMillis() >= 1000,
            "the node's times span its wait");
        assertEquals(0, flows.runDetail(gives.id(), false).run().pipelineRuns());
        as(designer).post("/api/v1/pipeline-runs/" + again + "/cancel").then().statusCode(200);
        await("the direct run to be cancelled", () -> pipelineStore.run(again).orElseThrow().status(), "cancelled"::equals);
        HoldingGraphRunner.hold = false;
    }

    @Test
    @Order(6)
    void cancellingAFlowCancelsItsRunningPipelineAndSkipsTheRest() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-cancel
            nodes:
              - { id: copy, type: run-pipeline, pipeline: fl-copy }
              - { id: gated, type: run-pipeline, pipeline: fl-gated }
            connections:
              - { from: copy, to: gated }
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-cancel", Map.of());
        FlowStore.NodeRun running = await("the first pipeline to run", () -> node(run.id(), "copy"),
            s -> s != null && s.pipelineRunId() != null);
        await("it to be running", () -> pipelineStore.run(running.pipelineRunId()).orElseThrow().status(),
            "running"::equals);
        flows.cancel(run.id(), "tester", AccessRole.STEWARD);
        FlowStore.Run done = waitForFlow(run.id(), "cancelled");
        assertEquals("cancelled", pipelineStore.run(running.pipelineRunId()).orElseThrow().status());
        assertEquals("cancelled", node(run.id(), "copy").status());
        assertEquals("skipped", node(run.id(), "gated").status());
        assertThrows(WebApplicationException.class, () -> flows.cancel(run.id(), "tester", AccessRole.STEWARD), "already finished");
        assertEquals("Cancelled", done.error());
    }

    @Test
    @Order(7)
    void aStopNodeEndsWhatIsStillRunning() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-stop-early
            nodes:
              - { id: slow, type: run-pipeline, pipeline: fl-copy }
              - { id: now, type: stop, status: failed, message: Stopped early }
              - { id: never, type: run-pipeline, pipeline: fl-gated }
            connections:
              - { from: now, to: never }
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-stop-early", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        assertEquals("Stopped early", done.error());
        assertEquals("succeeded", node(run.id(), "now").status());
        assertEquals("skipped", node(run.id(), "never").status());
        assertEquals("cancelled", node(run.id(), "slow").status());
        assertEquals("cancelled", pipelineStore.flowNodeRun(run.id(), "slow").orElseThrow().status());
    }

    @Test
    @Order(8)
    void savingChecksPipelinesParametersRolesAndVersions() {
        FlowService.Validation unknown = flows.validate("""
            kilnmark: flow/v1
            name: fl-bad
            nodes:
              - { id: a, type: run-pipeline, pipeline: no-such-pipeline }
              - { id: b, type: run-pipeline, pipeline: fl-copy, version: 9 }
            """);
        assertFalse(unknown.valid());
        assertEquals(2, unknown.problems().size(), unknown.problems().toString());
        assertTrue(unknown.problems().get(0).message().contains("'no-such-pipeline', which does not exist"));
        assertEquals(4, unknown.problems().get(0).line());
        assertTrue(unknown.problems().get(1).message().contains("version 9 of fl-copy, which has only"));

        // A draft with problems is saved, but cannot run.
        FlowStore.Flow draft = save("""
            kilnmark: flow/v1
            name: fl-draft
            nodes:
              - { id: a, type: run-pipeline, pipeline: no-such-pipeline }
            """);
        assertEquals(1, draft.latestVersion());
        assertFalse(flows.version("fl-draft", 1).orElseThrow().valid());
        WebApplicationException cannot = assertThrows(WebApplicationException.class,
            () -> start("fl-draft", Map.of()));
        assertEquals(409, cannot.getResponse().getStatus());

        // Another version; a stale If-Match is refused.
        String yaml = """
            kilnmark: flow/v1
            name: fl-draft
            nodes:
              - { id: a, type: run-pipeline, pipeline: fl-copy }
            """;
        assertEquals(2, flows.save(yaml, "fixed", "tester", AccessRole.DESIGNER, 1).latestVersion());
        assertEquals(409, assertThrows(WebApplicationException.class, () -> flows.save(yaml, "stale", "tester", AccessRole.DESIGNER, 1))
            .getResponse().getStatus());

        assertEquals(403, assertThrows(WebApplicationException.class,
            () -> flows.queueRun("fl-draft", null, Map.of(), "someone", AccessRole.VIEWER)).getResponse().getStatus());
        assertEquals(403, assertThrows(WebApplicationException.class,
            () -> flows.save(yaml, "no", "someone", AccessRole.VIEWER, null)).getResponse().getStatus());
        assertEquals(403, assertThrows(WebApplicationException.class,
            () -> flows.cancel(1, "someone", AccessRole.VIEWER)).getResponse().getStatus());
        assertEquals(404, assertThrows(WebApplicationException.class,
            () -> flows.queueRun("no-such-flow", null, Map.of(), "tester", AccessRole.STEWARD)).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class,
            () -> flows.queueRun("fl-draft", 1, Map.of(), "tester", AccessRole.STEWARD)).getResponse().getStatus(),
            "version 1 is the draft");

        // A pipeline deleted since the flow was saved stops it running.
        Optional<FlowStore.Flow> f = flows.find("fl-draft");
        assertTrue(f.isPresent());
        assertThrows(IllegalArgumentException.class, () -> flows.save("kilnmark: flow/v1\nnodes: []", null, "t", AccessRole.DESIGNER, null));
    }

    @Test
    @Order(9)
    void parametersAreCheckedAgainstTheirTypesAndDefaults() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-params
            parameters:
              region: { type: text, default: EMEA }
              limit: { type: number }
              dry: { type: boolean, default: false }
            nodes:
              - { id: a, type: run-pipeline, pipeline: fl-copy }
            """);
        assertEquals("parameters: limit needs a value (it has no default)", assertThrows(IllegalArgumentException.class,
            () -> start("fl-params", Map.of())).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> start("fl-params", Map.of("limit", "lots")))
            .getMessage().contains("limit must be a number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> start("fl-params", Map.of("limit", 1, "colour", "red")))
            .getMessage().contains("has no parameter 'colour'; it takes region, limit, dry"));
        // Nothing queued by the refusals, so a good start is not "busy".
        FlowStore.Run run = start("fl-params", Map.of("limit", 3));
        assertTrue(run.parameters().contains("\"region\": \"EMEA\"") || run.parameters().contains("\"region\":\"EMEA\""),
            run.parameters());
        assertTrue(run.parameters().contains("\"dry\": false") || run.parameters().contains("\"dry\":false"),
            run.parameters());
        waitForFlow(run.id(), "succeeded");
    }

    // ---- F2: parameters, server variables, with:, notify and wait ----

    @Inject
    io.kilnmark.server.Db db;

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

    /** Writes to lab.fl_<suffix>_<area>: a parameter and a server variable name the table. */
    static final String PARAM = COPY.replace("name: fl-copy", "name: fl-param")
        .replace("description: Synthetic people, copied.", """
            description: Synthetic people, copied to a table a parameter names.
            parameters:
              suffix: { type: text, default: base }""")
        .replace("table: lab.fl_copy", "table: lab.fl_${suffix}_${vars.area}");

    @Test
    @Order(10)
    void stewardsKeepServerVariablesAndEveryChangeIsKept() {
        as(designer).body(Map.of("type", "text", "value", "north", "description", "Where the lab is"))
            .put("/api/v1/variables/area").then().statusCode(200).body("value", org.hamcrest.Matchers.equalTo("north"));
        as(designer).body(Map.of("type", "number", "value", 3)).put("/api/v1/variables/batch").then().statusCode(200);
        as(designer).body(Map.of("type", "number", "value", 4)).put("/api/v1/variables/batch").then().statusCode(200);
        assertEquals(List.of("area", "batch"), as(designer).get("/api/v1/variables").then().statusCode(200).extract()
            .jsonPath().getList("name"));
        List<Object> after = as(designer).get("/api/v1/variables/batch/changes").then().statusCode(200).extract()
            .jsonPath().getList("after");
        assertEquals(2, after.size());
        assertEquals(4, ((Number) after.get(0)).intValue(), "newest first");
        as(designer).delete("/api/v1/variables/batch").then().statusCode(204);
        assertEquals(3, as(designer).get("/api/v1/variables/batch/changes").then().statusCode(200).extract().jsonPath()
            .getList("after").size(), "the changes outlive the variable");
        // Never a secret, never the wrong kind, and viewers neither read nor write them.
        assertTrue(as(designer).body(Map.of("type", "text", "value", "x")).put("/api/v1/variables/db_password").then()
            .statusCode(400).extract().jsonPath().getString("error").contains("looks like a credential"));
        as(designer).body(Map.of("type", "number", "value", "lots")).put("/api/v1/variables/n").then().statusCode(400);
        as(viewer).get("/api/v1/variables").then().statusCode(403);
        as(viewer).body(Map.of("type", "text", "value", "x")).put("/api/v1/variables/v").then().statusCode(403);
        // An admin can do whatever a designer can.
        as(Tokens.ADMIN).body(Map.of("type", "boolean", "value", true)).put("/api/v1/variables/by_admin").then()
            .statusCode(200);
        as(Tokens.ADMIN).get("/api/v1/variables/by_admin/changes").then().statusCode(200);
        as(Tokens.ADMIN).delete("/api/v1/variables/by_admin").then().statusCode(204);
        FlowStore.Run byAdmin = flows.queueRun("fl-sequence", null, Map.of(), "admin", AccessRole.ADMIN);
        try {
            waitForFlow(byAdmin.id(), "succeeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    @Order(11)
    void aPipelineRunTakesParametersAndKeepsTheValuesItStartedWith() throws Exception {
        as(designer).body(Map.of("name", "fl-param", "yaml", PARAM)).post("/api/v1/pipelines").then().statusCode(201);
        long id = as(designer).body(Map.of("parameters", Map.of("suffix", "emea"))).post("/api/v1/pipelines/fl-param/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id");
        PipelineStore.Run run = await("pipeline run " + id, () -> pipelineStore.run(id).orElseThrow(),
            PipelineStore.Run::finished);
        assertEquals("succeeded", run.status(), run.error());
        assertTrue(run.result().contains("lab.fl_emea_north"), run.result());
        assertTrue(run.parameters().replace(" ", "").contains("\"suffix\":\"emea\""), run.parameters());
        assertTrue(run.variables().replace(" ", "").contains("\"area\":\"north\""), run.variables());
        // A value that would leave its part of the name, an unknown parameter, a wrong kind.
        as(designer).body(Map.of("parameters", Map.of("suffix", "../x"))).post("/api/v1/pipelines/fl-param/runs")
            .then().statusCode(409);
        assertTrue(as(designer).body(Map.of("parameters", Map.of("colour", "red"))).post("/api/v1/pipelines/fl-param/runs")
            .then().statusCode(400).extract().jsonPath().getString("error").contains("has no parameter 'colour'"));
        // A run never chooses the table a write replaces: a parameter is only part of a name the pipeline writes.
        String whole = PARAM.replace("name: fl-param", "name: fl-whole").replace("table: lab.fl_${suffix}_${vars.area}",
            "table: \"${suffix}\"");
        as(designer).body(Map.of("name", "fl-whole", "yaml", whole)).post("/api/v1/pipelines").then().statusCode(201);
        assertTrue(as(designer).body(Map.of("parameters", Map.of("suffix", "payroll")))
            .post("/api/v1/pipelines/fl-whole/runs").then().statusCode(409).extract().jsonPath().getString("error")
            .contains("is only parameters"));
    }

    @Test
    @Order(12)
    void aFlowPassesItsParametersNodeValuesAndNotices() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-passing
            parameters:
              suffix: { type: text, default: flow }
            nodes:
              - { id: copy, type: run-pipeline, pipeline: fl-copy }
              - { id: named, type: run-pipeline, pipeline: fl-param }
              - { id: given, type: run-pipeline, pipeline: fl-param, with: { suffix: "n${copy.rows_written}" } }
              - { id: pause, type: wait, for: 1s }
              - id: tell
                type: notify
                severity: low
                title: "Copied ${copy.rows_written} rows into lab.fl_${suffix}_${vars.area}"
            connections:
              - { from: copy, to: named }
              - { from: named, to: given }
              - { from: given, to: pause }
              - { from: pause, to: tell }
            """);
        FlowStore.Run run = start("fl-passing", Map.of());
        waitForFlow(run.id(), "succeeded");
        PipelineStore.Run named = pipelineStore.flowNodeRun(run.id(), "named").orElseThrow();
        assertTrue(named.result().contains("lab.fl_flow_north"), "inherited from the flow: " + named.result());
        PipelineStore.Run given = pipelineStore.flowNodeRun(run.id(), "given").orElseThrow();
        assertTrue(given.result().contains("lab.fl_n2500_north"), "with: and a node value: " + given.result());
        assertEquals("Waited 1 seconds", node(run.id(), "pause").message());
        assertEquals("Sent: Copied 2500 rows into lab.fl_flow_north", node(run.id(), "tell").message());
        await("the notice", () -> count("SELECT count(*) FROM kilnmark.notify_event WHERE kind = 'flow_notice' "
            + "AND title = ? AND severity = 'low'", "Copied 2500 rows into lab.fl_flow_north"), n -> n == 1L);
    }

    @Test
    @Order(13)
    void savingChecksWithAgainstThePipelinesParameters() {
        String flow = """
            kilnmark: flow/v1
            name: fl-wrong-with
            parameters:
              suffix: { type: number, default: 1 }
            nodes:
              - { id: a, type: run-pipeline, pipeline: fl-param, with: { colour: red } }
            """;
        List<String> problems = flows.validate(flow).problems().stream().map(io.kilnmark.kiln.yaml.Problem::message)
            .toList();
        assertTrue(problems.stream().anyMatch(m -> m.contains("the parameter 'colour', which it does not take: it "
            + "takes suffix")), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("passes the flow's suffix (a number) to fl-param, which "
            + "takes a text")), problems.toString());
        List<String> vars = flows.validate(flow.replace("with: { colour: red }", "with: { suffix: \"${vars.nope}\" }"))
            .problems().stream().map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(vars.stream().anyMatch(m -> m.contains("server variable 'nope', which is not set")), vars.toString());
    }

    @Test
    @Order(14)
    void cancellingAFlowStopsItsWaitAndAFailedFlowIsTold() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-waiting
            nodes:
              - { id: pause, type: wait, for: 10m }
            """);
        FlowStore.Run run = start("fl-waiting", Map.of());
        await("the wait", () -> node(run.id(), "pause"), n -> n != null && "running".equals(n.status()));
        flows.cancel(run.id(), "tester", AccessRole.STEWARD);
        waitForFlow(run.id(), "cancelled");
        assertEquals("cancelled", node(run.id(), "pause").status());
        assertEquals("Stopped waiting: the flow was stopped", node(run.id(), "pause").message());

        save("""
            kilnmark: flow/v1
            name: fl-told
            nodes:
              - { id: broken, type: run-pipeline, pipeline: fl-broken }
            """);
        FlowStore.Run failed = start("fl-told", Map.of());
        waitForFlow(failed.id(), "failed");
        await("the failure notice", () -> count("SELECT count(*) FROM kilnmark.notify_event WHERE kind = 'flow_failed' "
            + "AND dedupe_key = 'flow:fl-told'"), n -> n == 1L);
    }

    // ---- max_parallel and for-each (ADR 0040) ----

    private PipelineStore.Run item(long runId, String node, int i) {
        return pipelineStore.flowItemRun(runId, node, i).orElse(null);
    }

    private long itemRuns(long runId, String node) {
        return count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ? AND flow_node = ?", runId, node);
    }

    @Test
    @Order(15)
    void aFlowsMaxParallelHoldsReadyNodesBackInTheOrderWritten() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-one-at-a-time
            max_parallel: 1
            nodes:
              - { id: first, type: run-pipeline, pipeline: fl-copy }
              - { id: second, type: run-pipeline, pipeline: fl-gated }
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-one-at-a-time", Map.of());
        PipelineStore.Run first = await("the first node's run", () -> pipelineStore.flowNodeRun(run.id(), "first")
            .orElse(null), r -> r != null && "running".equals(r.status()));
        Thread.sleep(1500); // several looks of the flow
        assertTrue(pipelineStore.flowNodeRun(run.id(), "second").isEmpty(), "second waits while first runs");
        assertEquals("pending", flows.runDetail(run.id(), true).nodes().get(1).status());
        HoldingGraphRunner.hold = false;
        waitForFlow(run.id(), "succeeded");
        PipelineStore.Run a = pipelineStore.run(first.id()).orElseThrow();
        PipelineStore.Run b = pipelineStore.flowNodeRun(run.id(), "second").orElseThrow();
        assertFalse(b.requestedAt().isBefore(a.finishedAt()), "second was queued only after first had finished");
    }

    @Test
    @Order(16)
    void aForEachNodeRunsItsItemsAtOnceUpToItsLimit() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-each
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: [ea, eb, ec]
                as: suffix
                max_parallel: 2
              - id: tell
                type: notify
                title: "${each.succeeded} of ${each.items} written"
            connections:
              - { from: each, to: tell, when: "each.failed = 0" }
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-each", Map.of());
        // The same pipeline, twice at once: the items of one node are the exception to one run at a time.
        await("two items running at once", () -> List.of(Optional.ofNullable(item(run.id(), "each", 0)),
                Optional.ofNullable(item(run.id(), "each", 1))),
            l -> l.stream().allMatch(r -> r.isPresent() && "running".equals(pipelineStore.run(r.get().id())
                .orElseThrow().status())));
        Thread.sleep(1500);
        assertNull(item(run.id(), "each", 2), "a third item waits: the node runs at most two at once");
        FlowViews.NodeRunView going = flows.runDetail(run.id(), true).nodes().get(0);
        assertEquals("running", going.status());
        assertEquals("0 of 3 items done", going.message());
        assertEquals(List.of("running", "running", "pending"), going.items().stream().map(FlowViews.ItemRunView::status)
            .toList());
        // Any other run of the pipeline still waits its turn.
        as(designer).post("/api/v1/pipelines/fl-param/runs").then().statusCode(409);
        HoldingGraphRunner.hold = false;

        FlowStore.Run done = waitForFlow(run.id(), "succeeded");
        assertNull(done.error());
        PipelineStore.Run r0 = item(run.id(), "each", 0);
        PipelineStore.Run r1 = item(run.id(), "each", 1);
        PipelineStore.Run r2 = item(run.id(), "each", 2);
        assertTrue(r0.startedAt().isBefore(r1.finishedAt()) && r1.startedAt().isBefore(r0.finishedAt()),
            "the first two items overlapped");
        assertTrue(r0.result().contains("lab.fl_ea_north") && r2.result().contains("lab.fl_ec_north"), r2.result());
        assertTrue(r1.parameters().replace(" ", "").contains("\"suffix\":\"eb\""), r1.parameters());
        assertEquals("tester", r2.requestedBy());
        assertEquals(3, itemRuns(run.id(), "each"), "each item ran once");
        FlowStore.NodeRun each = node(run.id(), "each");
        assertEquals("succeeded", each.status());
        assertEquals("3 of 3 items succeeded", each.message());
        assertTrue(each.reported().replace(" ", "").contains("\"failed\":0.0"), each.reported());
        assertEquals("Sent: 3 of 3 written", node(run.id(), "tell").message());
        assertNull(each.pipelineRunId(), "a for-each node's runs are its items'");
        FlowViews.NodeRunView view = flows.runDetail(run.id(), false).nodes().get(0);
        assertEquals(List.of("ea", "eb", "ec"), view.items().stream().map(FlowViews.ItemRunView::value).toList(),
            "items written in the flow are shown to viewers, who can read the flow");
        assertEquals(r2.id(), view.items().get(2).pipelineRunId());
        assertEquals(r2.requestedAt(), view.items().get(2).requestedAt(), "when the item's run was asked for");
        assertNull(view.pipelineRunTimes(), "a for-each node's runs are its items'");
        assertEquals(3, flows.runDetail(run.id(), false).run().pipelineRuns());
        // An item never starts twice: the database refuses a second run for it.
        assertThrows(RuntimeException.class, () -> pipelineStore.queueRun("fl-param", 1, "tester", "steward", run.id(),
            "each", 0, "{}", "{}"));
        assertEquals(3, itemRuns(run.id(), "each"));
    }

    @Test
    @Order(17)
    void aFailingItemFailsTheNodeOnceTheOthersHaveEnded() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-each-fails
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: [fa, "../x", fc]
                as: suffix
                max_parallel: 1
              - { id: after, type: run-pipeline, pipeline: fl-copy }
              - { id: tell, type: stop, status: failed, message: "Some items failed" }
            connections:
              - { from: each, to: after }
              - { from: each, to: tell, when: failure }
            """);
        HoldingGraphRunner.failWith = "the engine failed on purpose";
        FlowStore.Run run = start("fl-each-fails", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        assertEquals("Some items failed", done.error());
        assertEquals("failed", item(run.id(), "each", 0).status(), "the first item's run failed");
        assertNull(item(run.id(), "each", 1), "the second could not start: its value leaves its part of the name");
        assertEquals("succeeded", item(run.id(), "each", 2).status(), "the others still ran");
        FlowStore.NodeRun each = node(run.id(), "each");
        assertEquals("failed", each.status());
        assertTrue(each.message().startsWith("2 of 3 items failed; their pipeline runs say why. Item 2:"),
            each.message());
        assertFalse(each.message().contains("the engine failed"), "never why a run failed");
        assertFalse(each.finishedAt().isBefore(item(run.id(), "each", 2).finishedAt()), "it ended after the last item");
        assertTrue(each.reported().replace(" ", "").contains("\"succeeded\":1.0"), each.reported());
        assertEquals("skipped", node(run.id(), "after").status());
        assertTrue(flows.runDetail(run.id(), false).nodes().get(0).message().contains("shown to stewards"),
            "viewers do not see the values an item could not run with");
        // The item that could not start is failed on the run page too, not "not started", so the two agree.
        List<FlowViews.ItemRunView> items = flows.runDetail(run.id(), true).nodes().get(0).items();
        assertEquals(List.of("failed", "failed", "succeeded"), items.stream().map(FlowViews.ItemRunView::status).toList());
        assertNull(items.get(1).pipelineRunId());
        assertTrue(each.reported().replace(" ", "").contains("\"not_started\":[1]"), each.reported());
    }

    @Test
    @Order(18)
    void cancellingAFlowCancelsItsItemsAndStartsNoMore() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-each-cancel
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: [ca, cb, cc]
                as: suffix
                max_parallel: 2
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-each-cancel", Map.of());
        await("two items running", () -> List.of(Optional.ofNullable(item(run.id(), "each", 0)),
                Optional.ofNullable(item(run.id(), "each", 1))),
            l -> l.stream().allMatch(r -> r.isPresent() && "running".equals(pipelineStore.run(r.get().id())
                .orElseThrow().status())));
        flows.cancel(run.id(), "tester", AccessRole.STEWARD);
        waitForFlow(run.id(), "cancelled");
        assertEquals("cancelled", item(run.id(), "each", 0).status());
        assertEquals("cancelled", item(run.id(), "each", 1).status());
        assertNull(item(run.id(), "each", 2), "nothing more starts");
        assertEquals("cancelled", node(run.id(), "each").status());
        assertEquals("skipped", flows.runDetail(run.id(), true).nodes().get(0).items().get(2).status());

        // Someone cancelling one item's run by hand stops the node, and the flow, the same way.
        FlowStore.Run again = start("fl-each-cancel", Map.of());
        PipelineStore.Run first = await("an item running", () -> item(again.id(), "each", 0),
            r -> r != null && "running".equals(pipelineStore.run(r.id()).orElseThrow().status()));
        as(designer).post("/api/v1/pipeline-runs/" + first.id() + "/cancel").then().statusCode(200);
        waitForFlow(again.id(), "cancelled");
        assertTrue(node(again.id(), "each").message().startsWith("A run of one of its items was cancelled"),
            node(again.id(), "each").message());
        HoldingGraphRunner.hold = false;
    }

    @Test
    @Order(19)
    void aForEachNodeTakesItsItemsFromAListParameterAndIsChecked() throws Exception {
        String yaml = """
            kilnmark: flow/v1
            name: fl-each-param
            parameters:
              suffixes: { type: text list, default: [pa] }
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: "${suffixes}"
                as: suffix
            """;
        save(yaml);
        FlowStore.Run run = start("fl-each-param", Map.of("suffixes", List.of("pb", "pc")));
        waitForFlow(run.id(), "succeeded");
        assertTrue(item(run.id(), "each", 1).result().contains("lab.fl_pc_north"), item(run.id(), "each", 1).result());
        assertEquals(List.of("pb", "pc"), flows.runDetail(run.id(), true).nodes().get(0).items().stream()
            .map(FlowViews.ItemRunView::value).toList());
        assertEquals(java.util.Arrays.asList(null, null), flows.runDetail(run.id(), false).nodes().get(0).items()
            .stream().map(FlowViews.ItemRunView::value).toList(), "a run's values are for stewards");

        // Checked when saved, against the pipeline it runs.
        List<String> notTaken = flows.validate(yaml.replace("as: suffix", "as: colour")).problems().stream()
            .map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(notTaken.stream().anyMatch(m -> m.contains("fills colour with each item, but fl-param takes no such "
            + "parameter: it takes suffix")), notTaken.toString());
        List<String> kind = flows.validate(yaml.replace("type: text list, default: [pa]", "type: number list, default: [1]"))
            .problems().stream().map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(kind.stream().anyMatch(m -> m.contains("each item, a number, but fl-param takes a text for it")),
            kind.toString());
        List<String> missing = flows.validate(yaml.replace("pipeline: fl-param", "pipeline: no-such-pipeline")).problems()
            .stream().map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(missing.stream().anyMatch(m -> m.contains("'no-such-pipeline', which does not exist")), missing.toString());
        // Items at once that would all write one table would fail waiting for each other: refused when saved.
        as(designer).body(Map.of("name", "fl-shared", "yaml", PARAM.replace("name: fl-param", "name: fl-shared")
            .replace("table: lab.fl_${suffix}_${vars.area}", "table: lab.fl_shared"))).post("/api/v1/pipelines")
            .then().statusCode(201);
        String shared = yaml.replace("pipeline: fl-param", "pipeline: fl-shared");
        List<String> together = flows.validate(shared).problems().stream().map(io.kilnmark.kiln.yaml.Problem::message)
            .toList();
        assertTrue(together.contains("Every item would write lab.fl_shared, so they would fail waiting for each other: "
            + "put ${suffix} in the write's target, or set max_parallel: 1"), together.toString());
        assertTrue(flows.validate(shared.replace("as: suffix", "as: suffix\n    max_parallel: 1")).valid(),
            "one at a time, they take turns");
        // The flow's own suffix is not passed by name to the parameter each item fills.
        assertTrue(flows.validate(yaml.replace("suffixes: { type: text list, default: [pa] }",
            "suffixes: { type: text list, default: [pa] }\n  suffix: { type: number, default: 1 }")).valid());
    }

    // ---- timeout (ADR 0041) ----

    @Test
    @Order(20)
    void aRunPipelineNodePastItsTimeoutIsCancelledAndFails() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-timeout
            nodes:
              - { id: slow, type: run-pipeline, pipeline: fl-copy, timeout: 3s }
              - { id: after, type: run-pipeline, pipeline: fl-gated }
            connections:
              - { from: slow, to: after }
            """);
        HoldingGraphRunner.hold = true; // released after each test (release())
        FlowStore.Run run = start("fl-timeout", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        assertEquals("Node slow took longer than 3s and was stopped", done.error());
        FlowStore.NodeRun slow = node(run.id(), "slow");
        assertEquals("failed", slow.status(), "a timeout fails the node; it does not cancel the flow");
        assertEquals("Took longer than 3s and was stopped; its pipeline run was cancelled", slow.message());
        assertTrue(slow.reported().replace(" ", "").contains("\"timed_out\":true"), slow.reported());
        PipelineStore.Run pr = pipelineStore.run(slow.pipelineRunId()).orElseThrow();
        assertEquals("cancelled", pr.status());
        assertTrue(Duration.between(pr.startedAt(), pr.finishedAt()).toMillis() >= 3000, "it ran its three seconds");
        assertEquals("skipped", node(run.id(), "after").status());
        assertTrue(flows.runDetail(run.id(), false).nodes().get(0).reported().get("timed_out").asBoolean(),
            "the run page reads it");
        HoldingGraphRunner.hold = false;

        // A run that ends within its timeout is not touched.
        FlowStore.Run quick = start("fl-timeout", Map.of());
        waitForFlow(quick.id(), "succeeded");
        assertFalse(node(quick.id(), "slow").reported().contains("timed_out"), node(quick.id(), "slow").reported());
    }

    @Test
    @Order(21)
    void aForEachItemPastTheTimeoutFailsAndTheOthersRun() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-each-timeout
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: [ta, tb]
                as: suffix
                max_parallel: 1
                timeout: 3s
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-each-timeout", Map.of());
        // The first item is held past its timeout; once it is stopped, the second runs and is let through.
        await("the first item to be cancelled", () -> item(run.id(), "each", 0),
            r -> r != null && "cancelled".equals(r.status()));
        HoldingGraphRunner.hold = false;
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        assertEquals("Node each failed (the pipeline fl-param)", done.error());
        assertEquals("succeeded", item(run.id(), "each", 1).status(), "the other item still ran");
        FlowStore.NodeRun each = node(run.id(), "each");
        assertEquals("failed", each.status(), "not cancelled: the flow was not stopped by hand");
        assertEquals("1 of 2 items failed; their pipeline runs say why. Item 1 took longer than 3s and was stopped",
            each.message());
        assertTrue(each.reported().replace(" ", "").contains("\"timed_out_items\":[0]"), each.reported());
        assertTrue(each.reported().replace(" ", "").contains("\"failed\":1.0"), each.reported());
        // The run page shows the item as failed, as the node counts it, though its pipeline run was cancelled.
        assertEquals(List.of("failed", "succeeded"), flows.runDetail(run.id(), true).nodes().get(0).items().stream()
            .map(FlowViews.ItemRunView::status).toList());
        assertEquals("cancelled", item(run.id(), "each", 0).status());
    }

    // ---- priority (ADR 0041) ----

    /** The priority of the job that runs a pipeline run: 1 high, 0 normal, -1 low. */
    private long jobPriority(long pipelineRunId) {
        return count("SELECT priority FROM kilnmark.job WHERE kind = 'pipeline' AND (payload ->> 'pipelineRunId')::bigint = ?",
            pipelineRunId);
    }

    @Test
    @Order(22)
    void aNodesPriorityIsItsPipelineRunsJobs() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-priority
            nodes:
              - { id: urgent, type: run-pipeline, pipeline: fl-copy, priority: high }
              - { id: plain, type: run-pipeline, pipeline: fl-gated }
              - id: later
                type: for-each
                pipeline: fl-param
                items: [pa, pb]
                as: suffix
                priority: Low
            connections:
              - { from: urgent, to: plain }
              - { from: plain, to: later }
            """);
        FlowStore.Run run = start("fl-priority", Map.of());
        waitForFlow(run.id(), "succeeded");
        assertEquals(1, jobPriority(node(run.id(), "urgent").pipelineRunId()), "high");
        assertEquals(0, jobPriority(node(run.id(), "plain").pipelineRunId()), "normal when not written");
        assertEquals(-1, jobPriority(item(run.id(), "later", 0).id()), "each item's run, low");
        assertEquals(-1, jobPriority(item(run.id(), "later", 1).id()));
        // A person's Run now is normal.
        long direct = as(designer).post("/api/v1/pipelines/fl-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        assertEquals(0, jobPriority(direct));
        await("the direct run to end", () -> pipelineStore.run(direct).orElseThrow().finished(), Boolean::booleanValue);
    }

    // ---- check-source (ADR 0041) ----

    @Test
    @Order(23)
    void aCheckSourceNodeSaysWhatIsMissingInTheFlowsOwnWords() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-check
            nodes:
              - id: ready
                type: check-source
                source: fl-src
                tables: [lab.people, lab.missing_one, lab.missing_two]
              - { id: tell, type: notify, title: "Missing: ${ready.missing}" }
              - { id: load, type: run-pipeline, pipeline: fl-copy }
            connections:
              - { from: ready, to: tell, when: failure }
              - { from: ready, to: load }
            """);
        FlowStore.Run run = start("fl-check", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "succeeded");
        assertNull(done.error(), "the failure was handled");
        FlowStore.NodeRun ready = node(run.id(), "ready");
        assertEquals("failed", ready.status());
        assertEquals("2 of 3 tables are missing on fl-src: lab.missing_one, lab.missing_two", ready.message());
        assertFalse(ready.message().contains(IN_SECRET), "never what the source said");
        assertTrue(ready.reported().replace(" ", "").contains("\"missing\":2.0"), ready.reported());
        assertTrue(node(run.id(), "tell").message().contains("Missing: 2"), node(run.id(), "tell").message());
        assertEquals("skipped", node(run.id(), "load").status(), "nothing after it ran");
        assertNull(node(run.id(), "ready").pipelineRunId(), "it starts no pipeline run");

        // Everything there: it succeeds and what follows runs.
        save("""
            kilnmark: flow/v1
            name: fl-check-ok
            nodes:
              - { id: ready, type: check-source, source: fl-src, tables: [lab.people] }
              - { id: load, type: run-pipeline, pipeline: fl-copy }
            connections:
              - { from: ready, to: load }
            """);
        FlowStore.Run ok = start("fl-check-ok", Map.of());
        waitForFlow(ok.id(), "succeeded");
        assertEquals("fl-src can be reached; the 1 table it names is there", node(ok.id(), "ready").message());
        assertTrue(node(ok.id(), "ready").reported().replace(" ", "").contains("\"missing\":0.0"));
        assertEquals("succeeded", node(ok.id(), "load").status());
    }

    @Test
    @Order(24)
    void aCheckSourceNodeThatCannotReachItsSourceFailsWithoutLookingFurther() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-down", "kind", "postgresql",
            "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:1/none", "username", "nobody"),
            "password", "fl-down-secret-7")).post("/api/v1/sources").then().statusCode(201);
        save("""
            kilnmark: flow/v1
            name: fl-check-down
            nodes:
              - { id: ready, type: check-source, source: fl-down, tables: [public.orders] }
            """);
        FlowStore.Run run = start("fl-check-down", Map.of());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        FlowStore.NodeRun ready = node(run.id(), "ready");
        assertEquals("Could not reach fl-down", ready.message());
        assertFalse(ready.reported().contains("missing"), "nothing was looked for: " + ready.reported());
        assertEquals("Node ready failed", done.error());
    }

    @Test
    @Order(25)
    void aCheckSourceNodeIsCheckedAgainstItsSourceWhenSaved() {
        String yaml = """
            kilnmark: flow/v1
            name: fl-check-saved
            nodes:
              - { id: ready, type: check-source, source: fl-src, tables: [lab.people] }
            """;
        assertTrue(flows.validate(yaml).valid(), flows.validate(yaml).problems().toString());
        List<String> none = flows.validate(yaml.replace("source: fl-src", "source: no-such-source")).problems().stream()
            .map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(none.contains("Node ready checks the source 'no-such-source', which does not exist"), none.toString());
        List<String> files = flows.validate(yaml.replace("tables: [lab.people]", "files: [incoming/today.csv]"))
            .problems().stream().map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(files.stream().anyMatch(m -> m.startsWith("Node ready looks for files, but fl-src is not a file")),
            files.toString());
    }

    // ---- wait-for a file (ADR 0041, C2) ----

    @Test
    @Order(26)
    void aWaitForFileNodeLooksAgainUntilEnoughFilesMatch() throws Exception {
        java.nio.file.Path roots = java.nio.file.Files.createDirectories(java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"), "kilnmark-test-files"));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory(roots, "fl-landing");
        java.nio.file.Files.createDirectories(dir.resolve("incoming/old"));
        java.nio.file.Files.writeString(dir.resolve("incoming/orders-1.csv"), "id\n1\n");
        java.nio.file.Files.writeString(dir.resolve("incoming/old/orders-0.csv"), "id\n0\n");
        java.nio.file.Files.writeString(dir.resolve("incoming/returns-1.csv"), "id\n1\n");
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-landing", "kind", "files",
            "settings", Map.of("path", dir.toString()))).post("/api/v1/sources").then().statusCode(201);
        save("""
            kilnmark: flow/v1
            name: fl-wait-file
            nodes:
              - id: arrived
                type: wait-for
                source: fl-landing
                file: "incoming/orders-*.csv"
                at_least: 2
                every: 30s
                timeout: 5m
            """);
        FlowStore.Run run = start("fl-wait-file", Map.of());
        // The first look finds one: the other file is in a folder below, and returns-1.csv does not match.
        FlowStore.NodeRun waiting = await("the first look", () -> node(run.id(), "arrived"),
            n -> n != null && n.message() != null && n.message().contains("so far"));
        assertEquals("running", waiting.status());
        assertTrue(waiting.message().endsWith("1 of 2 so far"), waiting.message());
        java.nio.file.Files.writeString(dir.resolve("incoming/orders-2.csv"), "id\n2\n");
        FlowStore.Run done = waitForFlow(run.id(), "succeeded");
        FlowStore.NodeRun arrived = node(run.id(), "arrived");
        assertTrue(arrived.message().startsWith("2 files match incoming/orders-*.csv after waiting"), arrived.message());
        assertTrue(arrived.reported().replace(" ", "").contains("\"found\":2.0"), arrived.reported());
        assertTrue(Duration.between(arrived.startedAt(), arrived.finishedAt()).toSeconds() >= 25,
            "it looked again after its every, not at once");
        assertNull(done.error());

        // Checked when saved: a file needs a file source, and * only in the file's name.
        String yaml = """
            kilnmark: flow/v1
            name: fl-wait-saved
            nodes:
              - { id: arrived, type: wait-for, source: fl-src, file: "incoming/a.csv" }
            """;
        List<String> wrongSource = flows.validate(yaml).problems().stream().map(io.kilnmark.kiln.yaml.Problem::message)
            .toList();
        assertTrue(wrongSource.contains("Node arrived waits for a file, but fl-src is not a file or object-store source"),
            wrongSource.toString());
        List<String> query = flows.validate(yaml.replace("file: \"incoming/a.csv\"", "query: \"select true\""))
            .problems().stream().map(io.kilnmark.kiln.yaml.Problem::message).toList();
        assertTrue(query.stream().anyMatch(m -> m.contains("a query runs only on PostgreSQL sources")), query.toString());
    }

    // ---- retries (ADR 0041) ----

    @Test
    @Order(30)
    void aFailedNodeIsTriedAgainEachTryARunOfItsOwn() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-retry
            nodes:
              - { id: load, type: run-pipeline, pipeline: fl-broken, retries: 1, retry_delay: 10s }
            """);
        FlowStore.Run run = start("fl-retry", Map.of());
        FlowStore.NodeRun waiting = await("the first try to fail", () -> node(run.id(), "load"),
            n -> n != null && n.message() != null && n.message().startsWith("Try 1 of 2 failed"));
        assertEquals("running", waiting.status(), "it waits to try again");
        assertEquals("Try 1 of 2 failed; trying again in 10s", waiting.message());
        FlowStore.Run done = waitForFlow(run.id(), "failed");
        FlowStore.NodeRun load = node(run.id(), "load");
        assertEquals("The pipeline run failed; its run page says why, after 2 tries", load.message());
        assertTrue(load.reported().replace(" ", "").contains("\"tries\":2.0"), load.reported());
        List<PipelineStore.Try> tries = pipelineStore.flowTries(run.id(), "load");
        assertEquals(List.of(0, 1), tries.stream().map(PipelineStore.Try::number).toList());
        assertTrue(tries.stream().allMatch(t -> "failed".equals(t.run().status())));
        assertTrue(Duration.between(tries.get(0).run().finishedAt(), tries.get(1).run().requestedAt()).toSeconds() >= 9,
            "it waited its retry_delay");
        assertEquals(tries.get(1).run().id(), load.pipelineRunId(), "the node shows its last try");
        assertEquals(tries.get(1).run().id(), pipelineStore.flowNodeRun(run.id(), "load").orElseThrow().id());
        List<FlowViews.TryView> shown = flows.runDetail(run.id(), false).nodes().get(0).tries();
        assertEquals(List.of(1, 2), shown.stream().map(FlowViews.TryView::number).toList(), "the run page lists both");
        assertNotNull(done.error());
    }

    @Test
    @Order(31)
    void aNodePastItsTimeoutIsTriedAgainAndCanSucceed() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-retry-timeout
            nodes:
              - { id: slow, type: run-pipeline, pipeline: fl-copy, timeout: 3s, retries: 2, retry_delay: 10s }
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-retry-timeout", Map.of());
        FlowStore.NodeRun waiting = await("the first try to time out", () -> node(run.id(), "slow"),
            n -> n != null && n.message() != null && n.message().contains("Try 1 of 3 failed"));
        assertEquals("Took longer than 3s and was stopped. Try 1 of 3 failed; trying again in 10s", waiting.message());
        HoldingGraphRunner.hold = false;
        waitForFlow(run.id(), "succeeded");
        FlowStore.NodeRun slow = node(run.id(), "slow");
        assertEquals("Succeeded at try 2 of 3", slow.message());
        assertFalse(slow.reported().contains("timed_out"), "the last try did not time out: " + slow.reported());
        List<PipelineStore.Try> tries = pipelineStore.flowTries(run.id(), "slow");
        assertEquals(List.of("cancelled", "succeeded"), tries.stream().map(t -> t.run().status()).toList());
    }

    @Test
    @Order(32)
    void aFailedItemIsTriedAgainAndTheOthersRun() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fl-each-retry
            nodes:
              - id: each
                type: for-each
                pipeline: fl-param
                items: [ra, rb]
                as: suffix
                max_parallel: 1
                timeout: 3s
                retries: 1
                retry_delay: 10s
            """);
        HoldingGraphRunner.hold = true;
        FlowStore.Run run = start("fl-each-retry", Map.of());
        await("the first item to be stopped", () -> node(run.id(), "each"),
            n -> n != null && n.message() != null && n.message().contains("to try again"));
        HoldingGraphRunner.hold = false;
        waitForFlow(run.id(), "succeeded");
        FlowStore.NodeRun each = node(run.id(), "each");
        assertEquals("2 of 2 items succeeded", each.message());
        assertFalse(each.reported().contains("timed_out_items"), each.reported());
        List<PipelineStore.Try> tries = pipelineStore.flowTries(run.id(), "each");
        assertEquals(List.of("0:0:cancelled", "0:1:succeeded", "1:0:succeeded"), tries.stream()
            .map(t -> t.item() + ":" + t.number() + ":" + t.run().status()).toList());
        FlowViews.NodeRunView view = flows.runDetail(run.id(), true).nodes().get(0);
        assertEquals(List.of("succeeded", "succeeded"), view.items().stream().map(FlowViews.ItemRunView::status).toList(),
            "an item is as its last try ended");
        assertEquals(3, view.tries().size());
    }

    @Test
    @Order(33)
    void retriesNeedWritesThatReplaceOrUpsert() throws Exception {
        as(designer).body(Map.of("name", "fl-append", "yaml", COPY.replace("name: fl-copy", "name: fl-append")
            .replace("table: lab.fl_copy", "table: lab.fl_append").replace("mode: replace", "mode: append")))
            .post("/api/v1/pipelines").then().statusCode(201);
        String yaml = """
            kilnmark: flow/v1
            name: fl-retry-append
            nodes:
              - { id: load, type: run-pipeline, pipeline: fl-append, retries: 2 }
            """;
        List<String> problems = flows.validate(yaml).problems().stream().map(io.kilnmark.kiln.yaml.Problem::message)
            .toList();
        assertEquals(List.of("Node load is tried again after a failure, so every write in fl-append must replace or "
            + "upsert: out appends"), problems);
        assertTrue(flows.validate(yaml.replace(", retries: 2", "")).valid(), "without retries it may append");
        assertTrue(flows.validate(yaml.replace("fl-append", "fl-copy")).valid(), "a replace may be tried again");
    }
}
