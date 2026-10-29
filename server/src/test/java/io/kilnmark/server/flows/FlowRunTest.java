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

    static String steward;
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
        return flows.save(yaml, "test", "tester", AccessRole.STEWARD, null);
    }

    private FlowStore.Run start(String flow, Map<String, Object> parameters) {
        return flows.queueRun(flow, null, parameters, "tester", AccessRole.STEWARD);
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        steward = token("flow-steward", "steward");
        viewer = token("flow-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "2500"), "password", IN_SECRET)).post("/api/v1/sources")
            .then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "fl-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/fl-out")
            .then().statusCode(200);
        long scan = as(steward).post("/api/v1/sources/fl-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan " + scan, () -> as(steward).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        for (String yaml : List.of(COPY, GATED, BROKEN)) {
            String name = yaml.lines().filter(l -> l.startsWith("name: ")).findFirst().orElseThrow().substring(6);
            as(steward).body(Map.of("name", name, "yaml", yaml)).post("/api/v1/pipelines").then().statusCode(201);
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
        long direct = as(steward).post("/api/v1/pipelines/fl-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the direct run to start", () -> pipelineStore.run(direct).orElseThrow().status(), "running"::equals);

        FlowStore.Run waits = start("fl-waits", Map.of());
        FlowStore.NodeRun waiting = await("the node to say it waits", () -> node(waits.id(), "copy"),
            s -> s != null && s.message() != null);
        assertEquals("running", waiting.status());
        assertEquals("Waiting for fl-copy to finish its current run", waiting.message());
        assertNull(waiting.pipelineRunId());

        // The direct run is held; cancelling it frees the pipeline, and later runs are not held.
        as(steward).post("/api/v1/pipeline-runs/" + direct + "/cancel").then().statusCode(200);
        await("the direct run to be cancelled", () -> pipelineStore.run(direct).orElseThrow().status(), "cancelled"::equals);
        HoldingGraphRunner.hold = false;
        FlowStore.Run done = waitForFlow(waits.id(), "succeeded");
        assertNull(done.error());
        assertNotNull(node(waits.id(), "copy").pipelineRunId());

        HoldingGraphRunner.hold = true;
        long again = as(steward).post("/api/v1/pipelines/fl-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the second direct run to start", () -> pipelineStore.run(again).orElseThrow().status(), "running"::equals);
        FlowStore.Run gives = start("fl-gives-up", Map.of());
        FlowStore.Run failed = waitForFlow(gives.id(), "failed");
        assertEquals("Node copy failed (the pipeline fl-copy)", failed.error());
        assertTrue(node(gives.id(), "copy").message().startsWith("Waited 1 seconds for fl-copy"),
            node(gives.id(), "copy").message());
        as(steward).post("/api/v1/pipeline-runs/" + again + "/cancel").then().statusCode(200);
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
        assertEquals(2, flows.save(yaml, "fixed", "tester", AccessRole.STEWARD, 1).latestVersion());
        assertEquals(409, assertThrows(WebApplicationException.class, () -> flows.save(yaml, "stale", "tester", AccessRole.STEWARD, 1))
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
        assertThrows(IllegalArgumentException.class, () -> flows.save("kilnmark: flow/v1\nnodes: []", null, "t", AccessRole.STEWARD, null));
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
}
