package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * The flows API (docs/design/flows.md, F3): viewers read, designers save, stewards run and cancel;
 * a flow with a sql node is saved only by a designer whose token sees unmasked values (ADR 0028);
 * If-Match refuses a save over someone else's; a run is shown with each of its nodes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowApiTest {

    /** Made-up credentials for the in-memory test connector. */
    static final String IN_SECRET = "fa-in-token-63";
    static final String OUT_SECRET = "fa-out-token-28";

    static final String COPY = """
        kilnmark: pipeline/v1
        name: fa-copy
        nodes:
          - id: people
            type: read
            source: fa-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: fa-out
            table: lab.fa_copy
            mode: replace
        """;

    static final String FLOW = """
        kilnmark: flow/v1
        name: fa-flow
        description: Copy, then wait a moment.
        parameters:
          label: { type: text }
          share: { type: number, default: 0.02 }
        nodes:
          - { id: copy, type: run-pipeline, pipeline: fa-copy }
          - { id: pause, type: wait, for: 1s }
          - { id: never, type: stop, message: The copy failed }
        connections:
          - { from: copy, to: pause }
          - { from: copy, to: never, when: failure }
        """;

    static final String SQL_FLOW = """
        kilnmark: flow/v1
        name: fa-sql
        nodes:
          - id: empty
            type: sql
            source: fa-nowhere
            statements:
              - TRUNCATE staging.orders
        """;

    static String viewer;
    static String steward;
    static String designer;
    static String unmasking;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
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

    private static JsonPath run(long id) {
        return as(viewer).get("/api/v1/flow-runs/" + id).then().statusCode(200).extract().jsonPath();
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        viewer = newToken("viewer", false);
        steward = newToken("steward", false);
        designer = newToken("designer", false);
        unmasking = newToken("designer", true);
        admin().contentType(ContentType.JSON).body(Map.of("name", "fa-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "100"), "password", IN_SECRET)).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "fa-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/fa-out")
            .then().statusCode(200);
        long scan = as(designer).post("/api/v1/sources/fa-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan " + scan, () -> as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        as(designer).body(Map.of("name", "fa-copy", "yaml", COPY)).post("/api/v1/pipelines").then().statusCode(201);
    }

    @Test
    @Order(2)
    void viewersReadDesignersSaveOthersAreRefused() {
        Map<String, Object> body = Map.of("yaml", FLOW, "message", "first");
        as(viewer).body(body).post("/api/v1/flows").then().statusCode(403);
        as(steward).body(body).post("/api/v1/flows").then().statusCode(403);
        Tokens.as(steward).get("/api/v1/flows/fa-flow").then().statusCode(404);
        as(designer).body(body).post("/api/v1/flows").then().statusCode(201)
            .header("ETag", containsString("1"))
            .header("Location", containsString("/api/v1/flows/fa-flow"))
            .body("latest.valid", equalTo(true))
            .body("latest.parameters.name", equalTo(List.of("label", "share")))
            .body("latest.parameters[0].required", equalTo(true));
        as(designer).body(body).post("/api/v1/flows").then().statusCode(409);

        Tokens.as(viewer).get("/api/v1/flows").then().statusCode(200)
            .body("find { it.name == 'fa-flow' }.valid", equalTo(true))
            .body("find { it.name == 'fa-flow' }.latestVersion", equalTo(1));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow").then().statusCode(200).header("ETag", containsString("1"))
            .body("description", equalTo("Copy, then wait a moment."));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow/versions").then().statusCode(200).body("n", equalTo(List.of(1)));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow/versions/1").then().statusCode(200)
            .body("yaml", equalTo(FLOW));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow/versions/9").then().statusCode(404);
        Tokens.as(viewer).get("/api/v1/flows").then().statusCode(200);
        Tokens.as("km_not-a-token").get("/api/v1/flows").then().statusCode(401);

        // Saving a version takes a designer too.
        as(viewer).body(body).put("/api/v1/flows/fa-flow").then().statusCode(403);
        as(steward).body(body).put("/api/v1/flows/fa-flow").then().statusCode(403);
        as(designer).body(Map.of("yaml", FLOW, "message", "nothing new")).put("/api/v1/flows/nowhere")
            .then().statusCode(404);
        as(designer).body(Map.of("yaml", FLOW.replace("name: fa-flow", "name: fa-renamed")))
            .put("/api/v1/flows/fa-flow").then().statusCode(400).body("error", containsString("cannot change"));
    }

    @Test
    @Order(3)
    void validateChecksNewAndSavedFlowsForAnyone() {
        as(viewer).body(Map.of("yaml", FLOW)).post("/api/v1/flows/validate").then().statusCode(200)
            .body("valid", equalTo(true)).body("name", equalTo("fa-flow"));
        as(viewer).body(Map.of("yaml", FLOW.replace("pipeline: fa-copy", "pipeline: fa-missing")))
            .post("/api/v1/flows/validate").then().statusCode(200)
            .body("valid", equalTo(false))
            .body("problems.node", hasItem("copy"))
            .body("problems.message", hasItem(containsString("does not exist")));
        as(viewer).body(Map.of("yaml", FLOW.replace("name: fa-flow", "name: fa-other")))
            .post("/api/v1/flows/fa-flow/validate").then().statusCode(200)
            .body("valid", equalTo(false)).body("problems[0].message", containsString("cannot change"));
        as(viewer).body(Map.of("yaml", FLOW)).post("/api/v1/flows/nowhere/validate").then().statusCode(404);
        as(viewer).body(Map.of()).post("/api/v1/flows/validate").then().statusCode(400);
    }

    @Test
    @Order(4)
    void aSqlNodeIsSavedOnlyByADesignerWhoSeesUnmaskedValues() {
        Map<String, Object> body = Map.of("yaml", SQL_FLOW);
        as(designer).body(body).post("/api/v1/flows").then().statusCode(403)
            .body("error", containsString("unmasked values"));
        admin().contentType(ContentType.JSON).body(body).post("/api/v1/flows").then().statusCode(403);
        Tokens.as(viewer).get("/api/v1/flows/fa-sql").then().statusCode(404);
        // The source does not exist, so it is saved as a draft; what matters is that it is saved.
        as(unmasking).body(body).post("/api/v1/flows").then().statusCode(201).body("latest.valid", equalTo(false));
        // A designer without the grant cannot add a sql node to a flow that has none, either.
        String withSql = FLOW.replace("  - { id: never, type: stop, message: The copy failed }",
            "  - { id: never, type: stop, message: The copy failed }\n"
                + "  - { id: tidy, type: sql, source: fa-nowhere, statements: [TRUNCATE staging.orders] }");
        as(designer).body(Map.of("yaml", withSql)).put("/api/v1/flows/fa-flow").then().statusCode(403);
        assertEquals(1, Tokens.as(viewer).get("/api/v1/flows/fa-flow").then().extract().jsonPath()
            .getInt("latestVersion"));
    }

    @Test
    @Order(5)
    void ifMatchRefusesASaveOverSomeoneElses() {
        String v2 = FLOW.replace("Copy, then wait a moment.", "Copy, then wait.");
        as(designer).header("If-Match", "\"1\"").body(Map.of("yaml", v2, "message", "shorter"))
            .put("/api/v1/flows/fa-flow").then().statusCode(200).header("ETag", containsString("2"))
            .body("latestVersion", equalTo(2)).body("latest.message", equalTo("shorter"));
        // Someone who read version 1 saves over it: refused, and nothing is saved.
        as(designer).header("If-Match", "\"1\"").body(Map.of("yaml", FLOW, "message", "stale"))
            .put("/api/v1/flows/fa-flow").then().statusCode(409).body("error", containsString("Reload it"));
        as(designer).header("If-Match", "two").body(Map.of("yaml", FLOW)).put("/api/v1/flows/fa-flow")
            .then().statusCode(400);
        Tokens.as(viewer).get("/api/v1/flows/fa-flow/versions").then().statusCode(200)
            .body("n", equalTo(List.of(2, 1)));
        // Without If-Match it saves as the next version.
        as(designer).body(Map.of("yaml", FLOW, "message", "back")).put("/api/v1/flows/fa-flow").then()
            .statusCode(200).body("latestVersion", equalTo(3));
    }

    @Test
    @Order(6)
    void aRunShowsEachNodeAndThePipelineRunItStarted() throws Exception {
        Map<String, Object> body = Map.of("parameters", Map.of("label", "nightly"));
        as(viewer).body(body).post("/api/v1/flows/fa-flow/runs").then().statusCode(403);
        // A parameter with no default must be given.
        as(steward).body(Map.of()).post("/api/v1/flows/fa-flow/runs").then().statusCode(400);
        as(steward).body(body).post("/api/v1/flows/nowhere/runs").then().statusCode(404);
        // A draft cannot run.
        as(steward).body(Map.of()).post("/api/v1/flows/fa-sql/runs").then().statusCode(409);

        JsonPath queued = as(steward).body(body).post("/api/v1/flows/fa-flow/runs").then().statusCode(202)
            .header("Location", containsString("/api/v1/flow-runs/")).extract().jsonPath();
        long id = queued.getLong("id");
        assertEquals("queued", queued.getString("status"));
        assertEquals(3, queued.getInt("version"));
        assertEquals("nightly", queued.getString("parameters.label"));
        assertEquals(0.02, queued.getDouble("parameters.share"), 1e-9);

        JsonPath done = await("flow run " + id, () -> run(id), j -> "succeeded".equals(j.getString("run.status")));
        assertEquals(List.of("copy", "pause", "never"), done.getList("nodes.node"));
        assertEquals(List.of("succeeded", "succeeded", "skipped"), done.getList("nodes.status"));
        assertEquals("fa-copy", done.getString("nodes[0].pipeline"));
        assertNotNull(done.get("nodes[0].startedAt"));
        assertNotNull(done.get("nodes[0].finishedAt"));
        long pipelineRun = done.getLong("nodes[0].pipelineRunId");
        Tokens.as(viewer).get("/api/v1/pipeline-runs/" + pipelineRun).then().statusCode(200)
            .body("run.pipeline", equalTo("fa-copy"));
        assertEquals(100.0, done.getDouble("nodes[0].reported.rows_written"), 1e-9);
        assertNull(done.get("nodes[1].pipelineRunId"));

        Tokens.as(viewer).get("/api/v1/flows/fa-flow/runs").then().statusCode(200).body("[0].id", equalTo((int) id));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow").then().statusCode(200).body("lastRun.id", equalTo((int) id));
        Tokens.as(viewer).get("/api/v1/flows").then().statusCode(200)
            .body("find { it.name == 'fa-flow' }.lastRun.status", equalTo("succeeded"));
        Tokens.as(viewer).get("/api/v1/flow-runs/999999999").then().statusCode(404);
        // A finished run cannot be cancelled.
        as(steward).post("/api/v1/flow-runs/" + id + "/cancel").then().statusCode(409);
    }

    @Test
    @Order(7)
    void stewardsCancelARunViewersCannot() throws Exception {
        String waits = """
            kilnmark: flow/v1
            name: fa-waits
            nodes:
              - { id: hold, type: wait, for: 10m }
              - { id: after, type: wait, for: 1s }
            connections:
              - { from: hold, to: after }
            """;
        as(designer).body(Map.of("yaml", waits)).post("/api/v1/flows").then().statusCode(201);
        long id = as(steward).body(Map.of()).post("/api/v1/flows/fa-waits/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath running = await("the wait to start", () -> run(id),
            j -> "running".equals(j.getString("nodes[0].status")));
        assertEquals("pending", running.getString("nodes[1].status"), "a node not yet started is shown pending");

        as(viewer).post("/api/v1/flow-runs/" + id + "/cancel").then().statusCode(403);
        as(steward).post("/api/v1/flow-runs/" + id + "/cancel").then().statusCode(200)
            .body("cancelRequested", equalTo(true));
        JsonPath done = await("the run to be cancelled", () -> run(id),
            j -> "cancelled".equals(j.getString("run.status")));
        assertEquals("cancelled", done.getString("nodes[0].status"));
        assertTrue(List.of("skipped", "cancelled").contains(done.getString("nodes[1].status")),
            done.getString("nodes[1].status"));
    }
}
