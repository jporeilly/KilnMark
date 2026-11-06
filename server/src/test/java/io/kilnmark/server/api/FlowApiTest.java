package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * The flows API (docs/design/flows.md, F3): viewers read, designers save, stewards run and cancel;
 * a flow with a sql node is saved only by a designer whose token sees unmasked values (ADR 0028);
 * If-Match refuses a save over someone else's; a run is shown with each of its nodes; designers
 * delete a flow with its runs and schedules, keeping the pipeline runs it started (ADR 0048).
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

    @Inject
    AgroalDataSource db;

    @Inject
    AuditLog audit;

    @Inject
    FlowService flowService;

    @Inject
    FlowStore flowStore;

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

    /** The one number {@code sql} gives for {@code value}. */
    private long count(String sql, Object value) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** Runs one statement with {@code values}; returns how many rows it changed. */
    private int sql(String statement, Object... values) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            return ps.executeUpdate();
        }
    }

    /** Waits until no job of a run of {@code flow} is queued or running: a job ends just after its run. */
    private void awaitFlowJobsEnded(String flow) throws InterruptedException {
        await("the jobs of " + flow + " to end", () -> {
            try {
                return count("SELECT count(*) FROM kilnmark.job j JOIN kilnmark.flow_run r "
                    + "ON (j.payload ->> 'flowRunId')::bigint = r.id WHERE r.flow = ? AND j.kind = 'flow' "
                    + "AND j.status IN ('queued', 'running')", flow);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, n -> n == 0);
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

        // The values it ran with are for stewards and above: they can hold server variables.
        assertNull(done.get("run.parameters"));
        Tokens.as(viewer).get("/api/v1/flows/fa-flow/runs").then().body("[0].parameters", equalTo(null));
        Tokens.as(steward).get("/api/v1/flow-runs/" + id).then().statusCode(200)
            .body("run.parameters.label", equalTo("nightly"));
    }

    @Test
    @Order(8)
    void viewersDoNotSeeValuesAServerVariableGave() throws Exception {
        as(designer).body(Map.of("type", "text", "value", "EMEA-secret-region")).put("/api/v1/variables/fa_region")
            .then().statusCode(200);
        String tells = """
            kilnmark: flow/v1
            name: fa-tells
            parameters:
              region: { type: text, default: "${vars.fa_region}" }
            nodes:
              - { id: tell, type: notify, title: "Done for ${region}" }
            """;
        as(designer).body(Map.of("yaml", tells)).post("/api/v1/flows").then().statusCode(201)
            .body("latest.parameters[0].defaultVariable", equalTo("fa_region"))
            .body("latest.parameters[0].defaultValue", equalTo(null))
            .body("latest.parameters[0].required", equalTo(false));
        long id = as(steward).body(Map.of()).post("/api/v1/flows/fa-tells/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        await("flow run " + id, () -> run(id), j -> "succeeded".equals(j.getString("run.status")));

        String seen = Tokens.as(viewer).get("/api/v1/flow-runs/" + id).then().statusCode(200).extract().asString();
        assertFalse(seen.contains("EMEA-secret-region"), seen);
        Tokens.as(viewer).get("/api/v1/flow-runs/" + id).then().body("nodes[0].message", containsString("stewards"));
        Tokens.as(steward).get("/api/v1/flow-runs/" + id).then()
            .body("nodes[0].message", equalTo("Sent: Done for EMEA-secret-region"))
            .body("run.parameters.region", equalTo("EMEA-secret-region"));
        // Other tests list the server's variables.
        as(designer).delete("/api/v1/variables/fa_region").then().statusCode(204);
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

    @Test
    @Order(9)
    void designersDeleteAFlowWithItsRunsAndSchedulesAndThePipelineRunsStay() throws Exception {
        String holds = """
            kilnmark: flow/v1
            name: fa-gone
            nodes:
              - { id: copy, type: run-pipeline, pipeline: fa-copy }
              - { id: hold, type: wait, for: 10m }
            connections:
              - { from: copy, to: hold }
            """;
        as(designer).body(Map.of("yaml", holds, "message", "first")).post("/api/v1/flows").then().statusCode(201);
        as(designer).body(Map.of("yaml", holds, "message", "second")).put("/api/v1/flows/fa-gone").then()
            .statusCode(200);
        long schedule = as(designer).body(Map.of("flow", "fa-gone", "cron", "0 6 * * *", "zone", "Europe/London"))
            .post("/api/v1/schedules").then().statusCode(201).extract().jsonPath().getLong("id");
        long id = as(steward).body(Map.of()).post("/api/v1/flows/fa-gone/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath holding = await("the copy to finish and the hold to start", () -> run(id),
            j -> "running".equals(j.getString("nodes[1].status")));
        long pipelineRun = holding.getLong("nodes[0].pipelineRunId");
        assertEquals(1, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE flow_run_id = ?", id));

        // only designers and admins delete, and not while a run is queued or running
        as(viewer).delete("/api/v1/flows/fa-gone").then().statusCode(403);
        as(steward).delete("/api/v1/flows/fa-gone").then().statusCode(403);
        as(designer).delete("/api/v1/flows/fa-gone").then().statusCode(409)
            .body("error", containsString("queued or running"));
        Tokens.as(viewer).get("/api/v1/flows/fa-gone").then().statusCode(200);
        as(steward).get("/api/v1/schedules/" + schedule).then().statusCode(200);

        as(steward).post("/api/v1/flow-runs/" + id + "/cancel").then().statusCode(200);
        await("the run to be cancelled", () -> run(id), j -> "cancelled".equals(j.getString("run.status")));
        awaitFlowJobsEnded("fa-gone");

        // while the job of a run is still finishing (it tells of a failure after the run is recorded),
        // the delete waits for it
        String jobWas;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT status FROM "
            + "kilnmark.job WHERE kind = 'flow' AND (payload ->> 'flowRunId')::bigint = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                jobWas = rs.getString(1);
            }
        }
        String job = "UPDATE kilnmark.job SET status = ? WHERE kind = 'flow' AND (payload ->> 'flowRunId')::bigint = ?";
        assertEquals(1, sql(job, "running", id));
        try {
            as(designer).delete("/api/v1/flows/fa-gone").then().statusCode(409)
                .body("error", containsString("queued or running"));
        } finally {
            sql(job, jobWas, id);
        }

        // the service checks the role too, as saving and running do
        WebApplicationException refused = assertThrows(WebApplicationException.class,
            () -> flowService.delete("fa-gone", "a steward", AccessRole.STEWARD));
        assertEquals(403, refused.getResponse().getStatus());

        // an open failure of the flow is cleared with it, so a flow saved later under the name is told of
        sql("INSERT INTO kilnmark.notify_state (dedupe_key, open, severity) VALUES (?, true, 'high')", "flow:fa-gone");
        admin().delete("/api/v1/flows/fa-gone").then().statusCode(204);
        assertEquals(0, count("SELECT count(*) FROM kilnmark.notify_state WHERE dedupe_key = ?", "flow:fa-gone"));

        // the flow, its versions, runs and node runs are gone
        Tokens.as(viewer).get("/api/v1/flows/fa-gone").then().statusCode(404);
        Tokens.as(viewer).get("/api/v1/flows/fa-gone/runs").then().statusCode(404);
        Tokens.as(viewer).get("/api/v1/flow-runs/" + id).then().statusCode(404);
        Tokens.as(viewer).get("/api/v1/flows").then().statusCode(200).body("name", not(hasItem("fa-gone")));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.flow_version WHERE flow = ?", "fa-gone"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.flow_run WHERE flow = ?", "fa-gone"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.flow_node_run WHERE run_id = ?", id));

        // the pipeline run it started stays, no longer linked to the flow run
        Tokens.as(viewer).get("/api/v1/pipeline-runs/" + pipelineRun).then().statusCode(200)
            .body("run.pipeline", equalTo("fa-copy")).body("run.status", equalTo("succeeded"));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.pipeline_run WHERE id = ? AND flow_run_id IS NULL",
            pipelineRun));

        // its schedule went with it, recorded as deleted with the flow
        as(steward).get("/api/v1/schedules/" + schedule).then().statusCode(404);
        audit.awaitStored(5000);
        JsonPath deleted = admin().queryParam("action", AuditLog.SCHEDULE_DELETED).queryParam("limit", 200)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        String mine = "events.find { it.target == 'schedule " + schedule + "' }";
        assertEquals("flow deleted", deleted.getString(mine + ".detail.via"), deleted.prettify());
        assertEquals("fa-gone", deleted.getString(mine + ".detail.flow"));
        assertEquals("0 6 * * *", deleted.getString(mine + ".detail.cron"));
        assertNull(deleted.get(mine + ".detail.pipeline"));

        admin().delete("/api/v1/flows/fa-gone").then().statusCode(404);
        // a designer deletes one with no runs; its name can be used again
        as(designer).delete("/api/v1/flows/fa-sql").then().statusCode(204);
        Tokens.as(viewer).get("/api/v1/flows/fa-sql").then().statusCode(404);
        as(designer).body(Map.of("yaml", holds)).post("/api/v1/flows").then().statusCode(201)
            .body("latestVersion", equalTo(1));
        as(designer).delete("/api/v1/flows/fa-gone").then().statusCode(204);
    }

    @Test
    @Order(10)
    void aDeleteThatCannotHaveItsLocksSoonIsRefusedAndAFlowGoneIsNotFound() throws Exception {
        as(designer).body(Map.of("yaml", """
            kilnmark: flow/v1
            name: fa-held
            nodes:
              - { id: pause, type: wait, for: 1s }
            """)).post("/api/v1/flows").then().statusCode(201);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = db.getConnection()) {
            // something else holds the flow's row, for longer than a delete waits for a lock
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("SELECT 1 FROM kilnmark.flow WHERE name = ? FOR UPDATE")) {
                ps.setString(1, "fa-held");
                ps.executeQuery().close();
            }
            try {
                Future<JsonPath> delete = pool.submit(() -> as(designer).delete("/api/v1/flows/fa-held").then()
                    .statusCode(409).extract().jsonPath());
                // without the lock timeout this would wait for as long as the row is held
                assertTrue(delete.get(60, TimeUnit.SECONDS).getString("error").contains("Try again"));
            } finally {
                held.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
        Tokens.as(viewer).get("/api/v1/flows/fa-held").then().statusCode(200);
        as(designer).delete("/api/v1/flows/fa-held").then().statusCode(204);

        // a run queued, or a version saved, just as the flow is deleted finds it gone: not a server error
        assertThrows(FlowStore.Missing.class, () -> flowStore.queueRun("fa-held", 1, "a steward", "steward", "{}",
            "{}"));
        assertThrows(FlowStore.Missing.class, () -> flowStore.save("fa-held", null, false, "fa-held", null,
            "kilnmark: flow/v1", null, "a designer", true, "[]"));
        // and through the service, as a start that was checked before the delete queues it: 404
        WebApplicationException gone = assertThrows(WebApplicationException.class, () -> flowService.queueChecked(
            new FlowService.CheckedRun("fa-held", 1, "{}", "{}"), "a steward", AccessRole.STEWARD));
        assertEquals(404, gone.getResponse().getStatus());
    }
}
