package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * Pipelines end to end on PostgreSQL: allow_writing, the node catalogue, validation, saving
 * versions with If-Match, runs on a worker with kiln-core's graph runner, per-node records, written
 * assets in the catalogue with lineage, cancelling, scrubbed errors and roles. Sources are the
 * in-memory test connector, so nothing outside the test is written.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelineFlowTest {

    static final String IN_SECRET = "mem-in-secret-41";
    static final String OUT_SECRET = "mem-out-secret-97";
    static final String READ_URN = "urn:kilnmark:mem-src:lab.people";
    static final String WRITE_URN = "urn:kilnmark:mem-out:lab.people_copy";

    static final String YAML = """
        kilnmark: pipeline/v1
        name: people-copy
        description: Synthetic people, copied.
        nodes:
          - id: people
            type: read
            source: mem-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: mem-out
            table: lab.people_copy
            mode: replace
        """;

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @jakarta.inject.Inject
    io.kilnmark.server.jobs.JobQueue jobs;

    @jakarta.inject.Inject
    io.kilnmark.graph.store.GraphStore graph;

    static String designer;
    static String viewer;
    static long firstRun;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    @AfterAll
    static void release() {
        HoldingGraphRunner.hold = false;
    }

    private static JsonPath waitForRun(long id, String status) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = as(designer).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (status.equals(run.getString("run.status"))) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + id + " did not become " + status + ": " + (run == null ? null : run.prettify()));
    }

    @Test
    @Order(1)
    void sourcesAreReadOnlyUntilAnAdminAllowsWriting() throws Exception {
        designer = token("pipeline-designer", "designer");
        viewer = token("pipeline-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "mem-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "2500"), "password", IN_SECRET)).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "mem-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);

        // Stewards may not change sources at all; designers turn on writing.
        String steward = token("pipeline-steward", "steward");
        as(steward).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/mem-out")
            .then().statusCode(403);
        as(designer).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/mem-out")
            .then().statusCode(200).body("settings.allow_writing", equalTo("true"));
        // A designer sends every setting, as an admin does: one left out is removed, so writing stays on
        // only while it is sent. And a designer may change where a source that allows writing points.
        // (Moving a source needs its password again: Kilnmark never sends a stored one to a new place.)
        as(designer).body(Map.of("settings", Map.of("description", "Copies land here", "allow_writing", "true",
            "rows", "5"), "password", OUT_SECRET)).put("/api/v1/sources/mem-out").then().statusCode(200)
            .body("settings.allow_writing", equalTo("true"));
        as(designer).body(Map.of("settings", Map.of("description", "Copies land here", "allow_writing", "true"),
            "password", OUT_SECRET)).put("/api/v1/sources/mem-out").then().statusCode(200)
            .body("settings.allow_writing", equalTo("true"));

        // Scan the source read from, so validation knows its columns.
        long scan = as(designer).post("/api/v1/sources/mem-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !status.equals("succeeded")) {
            Thread.sleep(250);
            status = as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
            if (status == null) {
                status = as(designer).get("/api/v1/sources/mem-src").then().extract().jsonPath()
                    .getString("lastRun.status");
            }
        }
        assertEquals("succeeded", status);
    }

    @Test
    @Order(2)
    void theNodeCatalogueListsTheServersNodes() {
        JsonPath nodes = as(viewer).get("/api/v1/node-types").then().statusCode(200).extract().jsonPath();
        assertTrue(nodes.getList("type").containsAll(List.of("read", "write")), nodes.prettify());
        assertEquals("source", nodes.getString("find { it.type == 'read' }.kind"));
        assertEquals(List.of("source", "asset", "columns", "where", "row_limit"), nodes.getList("find { it.type == 'read' }.settings.key"));
        assertEquals(List.of("create", "append", "replace", "upsert"),
            nodes.getList("find { it.type == 'write' }.settings.find { it.key == 'mode' }.choices"));
        // The writer settings each kind of source takes, for the canvas; other nodes have none.
        assertEquals(List.of("fail", "break_profile", "delimiter"),
            nodes.getList("find { it.type == 'write' }.writerSettings.'" + MemoryConnector.KIND + "'"));
        // Each type's palette folder.
        assertEquals("Read", nodes.getString("find { it.type == 'read' }.category"));
        assertEquals("Write", nodes.getString("find { it.type == 'write' }.category"));
        assertEquals("Filter and route", nodes.getString("find { it.type == 'filter' }.category"));
        assertEquals("Combine", nodes.getString("find { it.type == 'union' }.category"));
        assertEquals("Quality", nodes.getString("find { it.type == 'gate' }.category"));
        assertEquals("gate", nodes.getString("find { it.type == 'gate' }.kind"));
        assertEquals(List.of("out", "quarantine"), nodes.getList("find { it.type == 'gate' }.outputs"));
        assertEquals("rules", nodes.getString("find { it.type == 'gate' }.settings.find { it.key == 'rules' }.kind"));
        Object readWriterSettings = nodes.get("find { it.type == 'read' }.writerSettings");
        assertEquals(null, readWriterSettings);
    }

    @Test
    @Order(3)
    void validationGivesProblemsByLineAndEachNodesColumns() {
        as(viewer).body(Map.of("yaml", YAML)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'people' }.columns.out.name", equalTo(List.of("id", "name")))
            .body("edges[0].fromNode", equalTo("people"));
        JsonPath bad = as(viewer).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    colour: red")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false)).extract().jsonPath();
        assertEquals(16, bad.getInt("problems[0].line"));
        assertEquals("out", bad.getString("problems[0].node"));
        as(viewer).body(Map.of("yaml", YAML.replace("source: mem-out", "source: mem-src")))
            .post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("problems.message", hasItems(containsString("mem-src is read-only")));
        as(viewer).body(Map.of("yaml", "x".repeat(300 * 1024))).post("/api/v1/pipelines/validate").then()
            .statusCode(400).body("error", startsWith("yaml: the pipeline is"));
    }

    @Test
    @Order(4)
    void stewardsSaveVersionsWithIfMatch() {
        as(viewer).body(Map.of("name", "people-copy", "yaml", YAML)).post("/api/v1/pipelines").then().statusCode(403);
        as(designer).body(Map.of("name", "People Copy", "yaml", YAML)).post("/api/v1/pipelines").then().statusCode(400)
            .body("error", startsWith("name:"));
        as(designer).body(Map.of("name", "people-copy", "yaml", YAML, "message", "First go")).post("/api/v1/pipelines")
            .then().statusCode(201).header("ETag", containsString("1")).body("latestVersion", equalTo(1))
            .body("latest.valid", equalTo(true)).body("description", equalTo("Synthetic people, copied."));
        as(designer).body(Map.of("name", "people-copy", "yaml", YAML)).post("/api/v1/pipelines").then().statusCode(409);

        as(designer).header("If-Match", "\"1\"").body(Map.of("yaml", YAML.replace("Synthetic people, copied.",
            "Synthetic people, copied again."), "message", "Clearer description")).put("/api/v1/pipelines/people-copy")
            .then().statusCode(200).header("ETag", containsString("2")).body("latestVersion", equalTo(2));
        as(designer).header("If-Match", "\"1\"").body(Map.of("yaml", YAML, "message", "Stale"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(409)
            .body("error", startsWith("Someone else saved a new version"));

        // A draft with a problem is saved but cannot run.
        as(designer).header("If-Match", "\"2\"").body(Map.of("yaml", YAML.replace("type: write", "type: wirte"),
            "message", "Draft")).put("/api/v1/pipelines/people-copy").then().statusCode(200)
            .body("latest.valid", equalTo(false)).body("latest.problems[0].line", equalTo(11));
        as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(409)
            .body("error", containsString("cannot run until its problems are fixed"));
        as(designer).header("If-Match", "\"3\"").body(Map.of("yaml", YAML, "message", "Fixed"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(200).body("latestVersion", equalTo(4));

        JsonPath versions = as(viewer).get("/api/v1/pipelines/people-copy/versions").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(List.of(4, 3, 2, 1), versions.getList("n"));
        assertEquals(List.of(true, false, true, true), versions.getList("valid"));
        as(viewer).get("/api/v1/pipelines/people-copy/versions/1").then().statusCode(200)
            .body("message", equalTo("First go")).body("yaml", containsString("Synthetic people, copied."));
        as(viewer).get("/api/v1/pipelines/people-copy/versions/9").then().statusCode(404);
        as(viewer).body(Map.of("yaml", YAML.replace("name: people-copy", "name: other")))
            .post("/api/v1/pipelines/people-copy/validate").then().statusCode(200)
            .body("problems[0].message", containsString("a pipeline's name cannot change"));
    }

    @Test
    @Order(5)
    void aRunReadsWritesAndCataloguesWhatItWrote() throws Exception {
        as(viewer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(403);
        firstRun = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202)
            .header("Location", containsString("/api/v1/pipeline-runs/")).body("status", equalTo("queued"))
            .body("version", equalTo(4)).extract().jsonPath().getLong("id");
        JsonPath run = waitForRun(firstRun, "succeeded");
        assertEquals(2500L, run.getLong("nodes.find { it.node == 'people' }.rowsOut"));
        assertEquals(2500L, run.getLong("nodes.find { it.node == 'out' }.rowsIn"));
        assertEquals(2500L, run.getLong("nodes.find { it.node == 'out' }.ports.in.in"));
        assertEquals(List.of("finished", "finished"), run.getList("nodes.outcome"));
        assertEquals(2, run.getList("nodes").size(), "the pipeline's two nodes, and only those");
        assertEquals(WRITE_URN, run.getString("written[0].urn"));
        assertTrue(run.getBoolean("written[0].registered"), run.prettify());
        assertEquals(List.of(READ_URN), run.getList("read"));
        assertEquals(READ_URN, run.getString("lineage[0].input"));
        assertEquals(2500L, run.getLong("run.rowsWritten"));
        assertEquals(2500L, MemoryConnector.WRITTEN.get(WRITE_URN).get());

        JsonPath asset = as(viewer).get("/api/v1/assets/" + WRITE_URN).then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("id", "name"), asset.getList("fields.name"));
        assertEquals(2500L, asset.getLong("rowCount"));
        as(viewer).queryParam("urn", WRITE_URN).get("/api/v1/lineage").then().statusCode(200)
            .body("upstream[0].urn", equalTo(READ_URN)).body("upstream[0].pipeline", equalTo("people-copy"));
        as(viewer).queryParam("urn", READ_URN).get("/api/v1/lineage").then().statusCode(200)
            .body("downstream[0].urn", equalTo(WRITE_URN));
        // The write is recorded under a finished run of kind 'pipeline', never shown as the source's scan.
        as(viewer).get("/api/v1/sources/mem-out/runs").then().statusCode(200).body("size()", equalTo(0));

        as(viewer).get("/api/v1/pipelines").then().statusCode(200)
            .body("find { it.name == 'people-copy' }.lastRun.status", equalTo("succeeded"))
            .body("find { it.name == 'people-copy' }.valid", equalTo(true));
        as(viewer).get("/api/v1/pipelines/people-copy/runs").then().statusCode(200).body("[0].id",
            equalTo((int) firstRun));

        // Each node is timed, and the run kept its log and its last progress (V19).
        for (String node : List.of("people", "out")) {
            String at = "nodes.find { it.node == '" + node + "' }.";
            for (String time : List.of("startedAt", "firstRowAt", "lastRowAt", "finishedAt")) {
                assertTrue(run.getString(at + time) != null, node + " " + time + ": " + run.prettify());
            }
            assertTrue(run.getLong(at + "durationMs") >= 0, run.prettify());
            assertTrue(run.getLong(at + "waitingForRowsMs") >= 0 && run.getLong(at + "heldUpMs") >= 0, run.prettify());
        }
        assertTrue(run.getString("progress.workerStartedAt") != null && run.getString("progress.workerFinishedAt") != null,
            run.prettify());
        assertFalse(run.prettify().contains("progressSeries"), "the run view stays small: rows a second are read on their own");
        JsonPath series = as(viewer).get("/api/v1/pipeline-runs/" + firstRun + "/series").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(List.of("people", "out"), series.getList("nodes"), series.prettify());
        assertEquals(1, series.getInt("stride"));
        List<Integer> times = series.getList("points.t", Integer.class);
        assertTrue(!times.isEmpty(), series.prettify());
        as(viewer).queryParam("after", times.getLast()).get("/api/v1/pipeline-runs/" + firstRun + "/series").then()
            .statusCode(200).body("points.size()", equalTo(0));
        as(viewer).queryParam("after", "x").get("/api/v1/pipeline-runs/" + firstRun + "/series").then().statusCode(400);
        as(viewer).get("/api/v1/pipeline-runs/999999/series").then().statusCode(404);
        assertEquals(2500L, run.getLong("progress.nodes.find { it.node == 'out' }.rowsIn"), run.prettify());
        assertEquals("ended", run.getString("progress.nodes.find { it.node == 'out' }.state"));
        assertEquals(2500L, run.getLong("progress.edges[0].rows"));
        JsonPath log = as(viewer).get("/api/v1/pipeline-runs/" + firstRun + "/events").then().statusCode(200).extract()
            .jsonPath();
        List<String> kinds = log.getList("events.kind");
        assertEquals("run_started", kinds.getFirst(), log.prettify());
        assertEquals("run_ended", kinds.getLast(), log.prettify());
        assertTrue(kinds.containsAll(List.of("node_started", "first_row_out", "first_row_in", "last_row_out",
            "node_ended")), log.prettify());
        as(viewer).queryParam("node", "out").get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(200).body("events.node", org.hamcrest.Matchers.everyItem(equalTo("out")));
        as(viewer).queryParam("level", "warn").get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(200).body("events.size()", equalTo(0));
        as(viewer).queryParam("level", "loud").get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(400);
        as(viewer).queryParam("after", "soon").get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(400);
        as(viewer).queryParam("limit", "-3").get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(400);
        JsonPath first = as(viewer).queryParam("limit", 2).get("/api/v1/pipeline-runs/" + firstRun + "/events").then()
            .statusCode(200).body("events.size()", equalTo(2)).extract().jsonPath();
        as(viewer).queryParam("after", first.getInt("next")).get("/api/v1/pipeline-runs/" + firstRun + "/events")
            .then().statusCode(200).body("events[0].seq", equalTo(3));
        String text = Tokens.as(viewer).accept("text/plain").get("/api/v1/pipeline-runs/" + firstRun
            + "/events/download").then().statusCode(200).header("Content-Disposition", containsString("attachment"))
            .extract().asString();
        assertTrue(text.contains("The run started") && text.contains("The run succeeded"), text);
        String nd = Tokens.as(viewer).accept("application/x-ndjson").queryParam("format", "ndjson")
            .get("/api/v1/pipeline-runs/" + firstRun + "/events/download").then().statusCode(200).extract().asString();
        assertEquals(kinds.size(), nd.lines().count(), nd);
        as(viewer).get("/api/v1/pipeline-runs/999999/events").then().statusCode(404);
    }

    @Test
    @Order(6)
    void runsCanBeCancelled() throws Exception {
        HoldingGraphRunner.hold = true;
        long id;
        try {
            id = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
                .getLong("id");
            waitForRun(id, "running");
            as(viewer).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(403);
            as(designer).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(200)
                .body("cancelRequested", equalTo(true));
            JsonPath done = waitForRun(id, "cancelled");
            assertTrue(done.getString("run.error").startsWith("Cancelled"), done.prettify());
        } finally {
            HoldingGraphRunner.hold = false;
        }
        as(designer).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(409);
        as(designer).post("/api/v1/pipeline-runs/" + firstRun + "/cancel").then().statusCode(409);
        as(viewer).get("/api/v1/pipeline-runs/999999").then().statusCode(404);
    }

    /** A count that the notifier records in the background: waits until it reaches {@code want}. */
    private long eventually(String query, long want) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long n = sql(query);
        while (n < want && System.nanoTime() < deadline) {
            Thread.sleep(200);
            n = sql(query);
        }
        Thread.sleep(500); // and no second one follows
        return sql(query);
    }

    private long sql(String query) throws Exception {
        try (java.sql.Connection c = db.getConnection(); java.sql.Statement st = c.createStatement()) {
            if (st.execute(query)) {
                try (java.sql.ResultSet rs = st.getResultSet()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
            return st.getUpdateCount();
        }
    }

    @Test
    @Order(7)
    void oneRunOfAPipelineAndOneWriterOfATargetAtATime() throws Exception {
        as(designer).body(Map.of("name", "people-copy-2", "yaml", YAML.replace("name: people-copy",
            "name: people-copy-2"))).post("/api/v1/pipelines").then().statusCode(201);
        HoldingGraphRunner.hold = true;
        long first;
        try {
            first = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(first, "running");
            as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(409)
                .body("error", containsString("already queued or running"));
            // Another pipeline writing the same table waits for it, then gives up plainly.
            long second = as(designer).post("/api/v1/pipelines/people-copy-2/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            JsonPath failed = waitForRun(second, "failed");
            assertTrue(failed.getString("run.error").startsWith("Another pipeline run is writing " + WRITE_URN),
                failed.prettify());
            as(designer).post("/api/v1/pipeline-runs/" + first + "/cancel").then().statusCode(200);
            waitForRun(first, "cancelled");
        } finally {
            HoldingGraphRunner.hold = false;
        }
        as(designer).delete("/api/v1/pipelines/people-copy-2").then().statusCode(204);
    }

    @Test
    @Order(8)
    void writtenAssetsKeepWhatTheCatalogueKnowsAndSurviveScans() throws Exception {
        long sourceId = graph.source("mem-out").orElseThrow().id();
        // A scan that began before the pipeline wrote must not mark the written table removed.
        long scan = sql("INSERT INTO kilnmark.run (source_id, kind, status, started_at) VALUES (" + sourceId
            + ", 'scan', 'running', now() - interval '1 hour') RETURNING id");
        sql("UPDATE kilnmark.asset SET description = 'Copied people' WHERE urn = '" + WRITE_URN + "'");
        long run = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        waitForRun(run, "succeeded");
        graph.markUnseenRemoved(sourceId, scan);
        sql("UPDATE kilnmark.run SET status = 'failed', finished_at = now() WHERE id = " + scan);
        as(viewer).get("/api/v1/assets/" + WRITE_URN).then().statusCode(200)
            .body("removedAt", org.hamcrest.Matchers.nullValue())
            .body("description", equalTo("Copied people"));

        // While a scan holds the source, the write is not catalogued, and the run says to scan.
        try (io.kilnmark.server.jobs.JobQueue.ScanLease lease = jobs.lockSource(sourceId).orElseThrow()) {
            assertTrue(lease != null);
            long held = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            JsonPath r = waitForRun(held, "succeeded");
            assertFalse(r.getBoolean("written[0].registered"), r.prettify());
            assertTrue(r.getList("notes").toString().contains("Scan the source to catalogue"), r.prettify());
        }

        // Rows appended to a table the connector cannot describe keep the catalogued columns.
        String missing = "urn:kilnmark:mem-out:lab.missing_copy";
        String toMissing = YAML.replace("table: lab.people_copy", "table: lab.missing_copy");
        as(designer).body(Map.of("yaml", toMissing, "message", "Elsewhere")).put("/api/v1/pipelines/people-copy")
            .then().statusCode(200);
        waitForRun(as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id"), "succeeded");
        sql("UPDATE kilnmark.field SET description = 'Who' WHERE asset_urn = '" + missing + "' AND name = 'name'");
        as(designer).body(Map.of("yaml", toMissing.replace("mode: replace", "mode: append"), "message", "Append"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(200);
        JsonPath appended = waitForRun(as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202)
            .extract().jsonPath().getLong("id"), "succeeded");
        assertTrue(appended.getBoolean("written[0].registered"), appended.prettify());
        as(viewer).get("/api/v1/assets/" + missing).then().statusCode(200)
            .body("fields.find { it.name == 'name' }.description", equalTo("Who"));

        // A write's options cannot say where to write.
        as(viewer).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    host: elsewhere")))
            .post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("problems.message", hasItems(containsString("host")))
            .body("valid", equalTo(false));
        as(designer).body(Map.of("yaml", YAML, "message", "Back")).put("/api/v1/pipelines/people-copy").then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    void failuresNameTheNodeWithoutTheSecret() throws Exception {
        String failing = YAML.replace("mode: replace", "mode: replace\n    fail: true");
        as(designer).body(Map.of("yaml", failing, "message", "Make the write fail"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(200);
        long id = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        JsonPath run = waitForRun(id, "failed");
        String all = run.prettify();
        assertTrue(run.getString("run.error").contains("refused the rows"), all);
        assertTrue(run.getString("nodes.find { it.node == 'out' }.error").contains("refused the rows"), all);
        assertEquals("failed", run.getString("nodes.find { it.node == 'out' }.outcome"), all);
        assertFalse(all.contains(OUT_SECRET), all);
        assertFalse(all.contains(IN_SECRET), all);
        assertFalse(run.getBoolean("written[0].registered"), "a failed run catalogues nothing");
    }

    @Test
    @Order(10)
    void messagesFromTheDataGoThroughSights() throws Exception {
        String admin = Tokens.ADMIN;
        // The pipeline now fails at its write (the previous test); mark a column it reads sensitive.
        as(designer).body(Map.of("field", "name", "sensitive", true)).queryParam("urn", READ_URN)
            .put("/api/v1/quality/asset/sensitive").then().statusCode(204);
        try {
            long failed = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(failed, "failed");
            for (String path : List.of("/api/v1/pipeline-runs/" + failed)) {
                JsonPath v = as(viewer).get(path).then().statusCode(200).extract().jsonPath();
                assertEquals("The run failed. " + PipelineService.FOR_STEWARDS, v.getString("run.error"));
                assertEquals("This node failed. " + PipelineService.FOR_STEWARDS,
                    v.getString("nodes.find { it.node == 'out' }.error"));
                // Stewards see that it was held back; designers, who see sensitive values, see why.
                JsonPath ds = as(designer).get(path).then().statusCode(200).extract().jsonPath();
                assertTrue(ds.getString("run.error").contains("refused the rows"), ds.prettify());
                JsonPath st = as(token("flow-msg-steward", "steward")).get(path).then().statusCode(200).extract()
                    .jsonPath();
                assertEquals("The run failed. " + PipelineService.FOR_ADMINS, st.getString("run.error"));
                assertEquals("This node failed. " + PipelineService.FOR_ADMINS,
                    st.getString("nodes.find { it.node == 'out' }.error"));
                JsonPath ad = as(admin).get(path).then().statusCode(200).extract().jsonPath();
                assertTrue(ad.getString("run.error").contains("refused the rows"), ad.prettify());
                assertFalse(ad.prettify().contains(OUT_SECRET), "the secret stays out, even for admins");
            }
            // The log's details are held back alike; its own words are not.
            String events = "/api/v1/pipeline-runs/" + failed + "/events";
            JsonPath vl = as(viewer).queryParam("level", "error").get(events).then().statusCode(200).extract().jsonPath();
            assertTrue(vl.getList("events.text").contains("Node out failed"), vl.prettify());
            assertFalse(vl.prettify().contains("refused the rows"), vl.prettify());
            assertTrue(vl.getList("events.withheld").contains(PipelineService.FOR_STEWARDS), vl.prettify());
            String steward = token("flow-log-steward", "steward");
            String stl = as(steward).get(events).then().statusCode(200).extract().asString();
            assertFalse(stl.contains("refused the rows"), stl);
            String dsl = as(designer).get(events).then().statusCode(200).extract().asString();
            assertTrue(dsl.contains("refused the rows"), dsl);
            String vText = Tokens.as(viewer).accept("text/plain").get(events + "/download").then().statusCode(200)
                .extract().asString();
            assertFalse(vText.contains("refused the rows"), vText);
            String al = as(admin).get(events).then().statusCode(200).extract().asString();
            assertTrue(al.contains("refused the rows"), al);
            assertFalse(al.contains(OUT_SECRET), al);
            as(viewer).get("/api/v1/pipelines/people-copy/runs").then().statusCode(200)
                .body("[0].error", equalTo("The run failed. " + PipelineService.FOR_STEWARDS));
            as(steward).get("/api/v1/pipelines/people-copy/runs").then().statusCode(200)
                .body("[0].error", equalTo("The run failed. " + PipelineService.FOR_ADMINS));
            as(admin).get("/api/v1/pipelines/people-copy/runs").then().statusCode(200)
                .body("[0].error", containsString("refused the rows"));
            as(viewer).get("/api/v1/pipelines").then().statusCode(200)
                .body("find { it.name == 'people-copy' }.lastRun.error",
                    equalTo("The run failed. " + PipelineService.FOR_STEWARDS));
            as(steward).get("/api/v1/pipelines").then().statusCode(200)
                .body("find { it.name == 'people-copy' }.lastRun.error",
                    equalTo("The run failed. " + PipelineService.FOR_ADMINS));

            // A run that succeeds with a note quoting the engine: the note's detail is held back alike.
            as(designer).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    break_profile: true"),
                "message", "Writes again")).put("/api/v1/pipelines/people-copy").then().statusCode(200);
            long noted = as(designer).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(noted, "succeeded");
            String path = "/api/v1/pipeline-runs/" + noted;
            String note = "out: what it wrote could not be profiled; scan mem-out to profile lab.people_copy";
            as(viewer).get(path).then().statusCode(200)
                .body("notes", hasItems(note + ". " + PipelineService.FOR_STEWARDS)).body("run.error",
                    org.hamcrest.Matchers.nullValue());
            as(steward).get(path).then().statusCode(200).body("notes", hasItems(note + ". "
                + PipelineService.FOR_ADMINS));
            as(designer).get(path).then().statusCode(200).body("notes", hasItems(note
                + " (cannot profile a column of maps (test))"));
            as(admin).get(path).then().statusCode(200).body("notes", hasItems(note
                + " (cannot profile a column of maps (test))"));
        } finally {
            as(admin).body(Map.of("field", "name", "sensitive", false)).queryParam("urn", READ_URN)
                .put("/api/v1/quality/asset/sensitive").then().statusCode(204);
        }
    }

    @Test
    @Order(11)
    void deletingAPipelineKeepsItsLineage() {
        as(viewer).delete("/api/v1/pipelines/people-copy").then().statusCode(403);
        as(designer).delete("/api/v1/pipelines/people-copy").then().statusCode(204);
        as(viewer).get("/api/v1/pipelines/people-copy").then().statusCode(404);
        as(viewer).get("/api/v1/pipeline-runs/" + firstRun).then().statusCode(404);
        as(viewer).queryParam("urn", WRITE_URN).get("/api/v1/lineage").then().statusCode(200)
            .body("upstream[0].urn", equalTo(READ_URN));
    }

    static final String GATED = """
        kilnmark: pipeline/v1
        name: people-gate
        description: Synthetic people, checked on the way.
        quality:
          min_score: 0.99
        nodes:
          - id: people
            type: read
            source: mem-src
            asset: lab.people

          - id: tidy
            type: compute
            input: people
            columns:
              name: "case when id % 10 = 0 then null else name end"

          - id: check
            type: gate
            input: tidy
            on_fail: quarantine
            rules:
              - {name: early ids, type: in_range, field: id, max: 2000}

          - id: good
            type: write
            input: check
            source: mem-out
            table: lab.people_checked
            mode: replace

          - id: aside
            type: write
            input: check.quarantine
            source: mem-out
            table: lab.people_aside
            mode: replace
        """;

    @Test
    @Order(12)
    void aGateQuarantinesRowsAndEveryOutputIsScored() throws Exception {
        // The asset read has a rule of its own, which the gate checks beside the one written on it.
        as(designer).body(Map.of("assetUrn", READ_URN, "field", "id", "type", "not_null", "name", "id present"))
            .post("/api/v1/quality/rules").then().statusCode(201);
        as(viewer).body(Map.of("yaml", GATED)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'check' }.outputs", equalTo(List.of("out", "quarantine")))
            .body("nodes.find { it.id == 'check' }.columns.quarantine.name", equalTo(List.of("id", "name")));
        // Settings problems point at the setting.
        JsonPath bad = as(viewer).body(Map.of("yaml", GATED.replace("max: 2000}", "max: 2000, colour: red}")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false)).extract()
            .jsonPath();
        assertTrue(bad.getString("problems[0].message").contains("rules: rule 1 (early ids): a rule of type in_range "
            + "has no setting 'colour'"), bad.prettify());
        assertEquals(22, bad.getInt("problems[0].line"), bad.prettify());
        as(viewer).body(Map.of("yaml", GATED.replace("on_fail: quarantine", "on_fail: quarantine\n    min_score: 2")))
            .post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("problems.message", hasItems(containsString("min_score: a share from 0 to 1")));
        as(viewer).body(Map.of("yaml", GATED.replace("min_score: 0.99", "min_score: 99")))
            .post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("problems.message", hasItems(containsString("quality.min_score must be a share from 0 to 1")));

        // A stop gate: a limit after it would end its rows early; quarantine wired to a gate that sends it none.
        String limited = GATED.replace("on_fail: quarantine", "on_fail: stop")
            .replace("  - id: good\n", "  - id: first\n    type: limit\n    input: check\n    rows: 10\n\n  - id: good\n")
            .replace("    input: check\n    source: mem-out\n    table: lab.people_checked",
                "    input: first\n    source: mem-out\n    table: lab.people_checked");
        JsonPath stopped = as(viewer).body(Map.of("yaml", limited)).post("/api/v1/pipelines/validate").then()
            .statusCode(200).body("valid", equalTo(false)).extract().jsonPath();
        assertEquals("first", stopped.getString("problems[0].node"), stopped.prettify());
        assertEquals(25, stopped.getInt("problems[0].line"), stopped.prettify());
        assertTrue(stopped.getString("problems[0].message").contains("a limit after the stop gate check ends the rows "
            + "early"), stopped.prettify());
        assertTrue(stopped.getList("notes.message").toString().contains("on_fail: stop sends no rows to the quarantine "
            + "port, so aside gets none"), stopped.prettify());
        assertEquals(21, stopped.getInt("notes.find { it.message.contains('quarantine port') }.line"),
            stopped.prettify());

        // Predicted before it runs, from the catalogue alone: the scan's profile of lab.people and the asset's rule.
        // (By a designer, who may see the values the estimate uses; a viewer's is below.)
        JsonPath predicted = as(designer).body(Map.of("yaml", GATED)).post("/api/v1/pipelines/predict").then()
            .statusCode(200).body("estimate", equalTo(true)).body("valid", equalTo(true)).extract().jsonPath();
        String said = predicted.prettify();
        assertEquals(0.9, predicted.getDouble("outputs.find { it.node == 'check' }.overall"), 0.01, said);
        assertEquals("quarantined", predicted.getString("outputs.find { it.node == 'check' }.gate.decision"), said);
        assertEquals(2000, predicted.getInt("outputs.find { it.node == 'check' }.gate.rowsPassed"), 30, said);
        assertEquals(500, predicted.getInt("outputs.find { it.node == 'check' }.gate.rowsQuarantined"), 30, said);
        assertEquals(List.of("profile", "quantiles"),
            predicted.getList("outputs.find { it.node == 'check' }.gate.rules.basis"), said);
        assertEquals(2500, predicted.getInt("nodes.find { it.node == 'people' }.rows.out"), said);
        // tidy's case expression cannot be predicted: said, not guessed.
        assertTrue(predicted.getList("outputs.find { it.node == 'good' }.unknown").toString()
            .contains("name as tidy computes it"), said);
        assertEquals(1.0, predicted.getDouble("outputs.find { it.node == 'good' }.dimensions.completeness"), 1e-6, said);
        as(viewer).body(Map.of()).post("/api/v1/pipelines/predict").then().statusCode(400);
        // A viewer may not see values: a bound or a value cannot be used to probe the spread through rows.
        String probe = GATED.replace("  - id: check\n", "  - id: low\n    type: filter\n    input: tidy\n"
            + "    condition: \"id <= 1000\"\n\n  - id: check\n").replace("    input: tidy\n    on_fail", "    input: low\n"
            + "    on_fail");
        JsonPath byViewer = as(viewer).body(Map.of("yaml", probe)).post("/api/v1/pipelines/predict").then()
            .statusCode(200).body("valid", equalTo(true)).extract().jsonPath();
        Object hiddenRows = byViewer.get("nodes.find { it.node == 'low' }.rows.out");
        assertEquals(null, hiddenRows, byViewer.prettify());
        JsonPath bySteward = as(designer).body(Map.of("yaml", probe)).post("/api/v1/pipelines/predict").then()
            .statusCode(200).extract().jsonPath();
        assertEquals(1000, bySteward.getInt("nodes.find { it.node == 'low' }.rows.out"), 20, bySteward.prettify());

        as(designer).body(Map.of("name", "people-gate", "yaml", GATED)).post("/api/v1/pipelines").then()
            .statusCode(201);
        as(viewer).get("/api/v1/pipelines/people-gate/versions/1/prediction").then().statusCode(200)
            .body("outputs.node", hasItems("check", "good", "aside"));
        as(viewer).get("/api/v1/pipelines/people-gate/versions/9/prediction").then().statusCode(404);
        long id = as(designer).post("/api/v1/pipelines/people-gate/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        JsonPath run = waitForRun(id, "succeeded");
        String all = run.prettify();
        assertEquals(2000L, MemoryConnector.WRITTEN.get("urn:kilnmark:mem-out:lab.people_checked").get(), all);
        assertEquals(500L, MemoryConnector.WRITTEN.get("urn:kilnmark:mem-out:lab.people_aside").get(), all);
        assertEquals(2000L, run.getLong("nodes.find { it.node == 'check' }.ports.out.out"), all);
        assertEquals(500L, run.getLong("nodes.find { it.node == 'check' }.ports.out.quarantine"), all);

        // The gate: what it decided, its rules (the asset's and its own), its score.
        assertEquals("gate", run.getString("quality.find { it.node == 'check' }.role"), all);
        assertEquals(READ_URN, run.getString("quality.find { it.node == 'check' }.asset"), all);
        assertEquals("quarantined", run.getString("quality.find { it.node == 'check' }.gate.decision"), all);
        assertEquals(2500, run.getInt("quality.find { it.node == 'check' }.gate.rowsIn"), all);
        assertEquals(500, run.getInt("quality.find { it.node == 'check' }.gate.rowsQuarantined"), all);
        assertEquals(List.of("id present", "early ids"),
            run.getList("quality.find { it.node == 'check' }.gate.rules.name"), all);
        assertEquals(List.of("asset", "inline"), run.getList("quality.find { it.node == 'check' }.gate.rules.origin"),
            all);
        assertEquals(List.of("pass", "fail"), run.getList("quality.find { it.node == 'check' }.gate.rules.status"),
            all);
        // The verdict is in the run's log; the failing rules, with their counts, are its detail.
        JsonPath verdict = as(Tokens.ADMIN).queryParam("node", "check").get("/api/v1/pipeline-runs/" + id + "/events")
            .then().statusCode(200).extract().jsonPath();
        assertTrue(verdict.getString("events.find { it.kind == 'gate_verdict' }.text")
            .startsWith("Gate check quarantined 500 rows (early ids: "), verdict.prettify());
        assertEquals(0.9, run.getDouble("quality.find { it.node == 'check' }.measured.overall"), 1e-6, all);
        assertEquals(0.8, run.getDouble("quality.find { it.node == 'check' }.measured.dimensions.accuracy"), 1e-6,
            all);
        // The prediction made as the run started, beside what it measured.
        assertEquals(0.9, run.getDouble("quality.find { it.node == 'check' }.predicted.overall"), 0.01, all);
        assertEquals(0.8, run.getDouble("quality.find { it.node == 'check' }.predicted.dimensions.accuracy"), 0.01,
            all);
        assertEquals(2000, run.getInt("quality.find { it.node == 'check' }.prediction.gate.rowsPassed"), 30, all);
        assertEquals(1.0, run.getDouble("quality.find { it.node == 'good' }.predicted.dimensions.completeness"), 1e-6,
            all);
        assertTrue(run.getList("quality.find { it.node == 'good' }.prediction.unknown").toString()
            .contains("name as tidy computes it"), all);
        assertEquals(2000, run.getInt("quality.find { it.node == 'good' }.prediction.rows"), 30, all);
        assertEquals(1L, sql("SELECT count(*) FROM kilnmark.pipeline_node_quality WHERE run_id = " + id
            + " AND node_id = 'good' AND predicted IS NOT NULL AND measured IS NOT NULL AND predicted_detail IS NOT NULL"));

        // Each output, measured from the profile its writer made: 10% of the good rows have no name.
        assertEquals("output", run.getString("quality.find { it.node == 'good' }.role"), all);
        assertEquals(0.95, run.getDouble("quality.find { it.node == 'good' }.measured.dimensions.completeness"), 1e-6,
            all);
        double good = run.getDouble("quality.find { it.node == 'good' }.measured.overall");
        assertTrue(good < 0.99 && good > 0.9, all);
        assertTrue(run.getDouble("quality.find { it.node == 'aside' }.measured.overall") > 0, all);
        assertEquals(good, run.getDouble("run.qualityScore"), 1e-9, "the run's score is its lowest output's");
        assertEquals(0.99, run.getDouble("run.qualityThreshold"), 1e-9);

        // Below the pipeline's threshold: told once, naming the output.
        long told = eventually("SELECT count(*) FROM kilnmark.notify_event WHERE kind = 'pipeline_low_quality' "
            + "AND dedupe_key = 'pipeline-quality:" + id + "'", 1);
        assertEquals(1, told);
        long named = sql("SELECT count(*) FROM kilnmark.notify_event WHERE kind = 'pipeline_low_quality' "
            + "AND detail LIKE '%for what node good wrote%'");
        assertEquals(1, named);
        as(viewer).get("/api/v1/pipelines/people-gate/runs").then().statusCode(200)
            .body("[0].qualityThreshold", equalTo(0.99f));

        // Stop: the run fails at the gate, naming the rule and counts, and writes nothing.
        as(designer).body(Map.of("yaml", GATED.replace("on_fail: quarantine", "on_fail: stop"), "message", "Strict"))
            .put("/api/v1/pipelines/people-gate").then().statusCode(200);
        long strict = as(designer).post("/api/v1/pipelines/people-gate/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath failed = waitForRun(strict, "failed");
        JsonPath asAdmin = as(Tokens.ADMIN).get("/api/v1/pipeline-runs/" + strict).then().statusCode(200).extract()
            .jsonPath();
        assertTrue(asAdmin.getString("run.error").contains("The quality gate stopped the run: rule 'early ids' failed "
            + "on "), asAdmin.prettify());
        assertTrue(asAdmin.getString("run.error").contains("more than its limit of 0%"),
            asAdmin.prettify());
        assertEquals("failed", failed.getString("nodes.find { it.node == 'check' }.outcome"), failed.prettify());
        assertEquals("stopped", failed.getString("quality.find { it.node == 'check' }.gate.decision"),
            failed.prettify());
        assertEquals(3, failed.getList("quality").size(), "a failed run measures no output, but keeps its predictions");
        Object unmeasured = failed.get("quality.find { it.node == 'good' }.measured");
        assertEquals(null, unmeasured, failed.prettify());
        assertTrue(failed.getString("quality.find { it.node == 'good' }.note").contains("did not succeed"),
            failed.prettify());
        assertEquals(1.0, failed.getDouble("quality.find { it.node == 'good' }.predicted.dimensions.completeness"),
            1e-6, failed.prettify());
        assertEquals("stopped", failed.getString("quality.find { it.node == 'check' }.prediction.gate.decision"),
            failed.prettify());

        // The engine itself fails: the predictions stay, none still saying the run has just started.
        HoldingGraphRunner.failWith = "engine broke (test)";
        long broken = as(designer).post("/api/v1/pipelines/people-gate/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath engine = waitForRun(broken, "failed");
        assertEquals(3, engine.getList("quality").size(), engine.prettify());
        assertFalse(engine.getList("quality.note").toString().contains("just started"), engine.prettify());
        assertTrue(engine.getString("quality.find { it.node == 'good' }.note").contains("did not succeed"),
            engine.prettify());
        assertEquals(1.0, engine.getDouble("quality.find { it.node == 'good' }.predicted.dimensions.completeness"),
            1e-6, engine.prettify());
        assertEquals(0, sql("SELECT count(*) FROM kilnmark.notify_event WHERE kind = 'pipeline_low_quality' "
            + "AND dedupe_key = 'pipeline-quality:" + strict + "'"));
        as(designer).delete("/api/v1/pipelines/people-gate").then().statusCode(204);
    }

    @Test
    @Order(13)
    void suggestionsHonourEachSourcesAiRuleEndToEnd() throws Exception {
        String yaml = GATED.replace("name: people-gate", "name: people-suggest");
        as(viewer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/suggest").then().statusCode(403);
        // No model routed: Kilnmark's own suggestions, and ai says so.
        JsonPath own = as(designer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/suggest").then().statusCode(200)
            .body("estimate", equalTo(true)).body("ai.state", equalTo("off")).extract().jsonPath();
        for (Object s : own.getList("suggestions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) s;
            assertEquals("rules", m.get("source"), own.prettify());
        }
        io.kilnmark.server.ai.FakeModelServer fake = io.kilnmark.server.ai.FakeModelServer.get();
        fake.clear();
        fake.chatReplies(user -> "{\"ranking\": []}");
        Map<String, Object> profile = new java.util.HashMap<>();
        profile.put("name", "pl-chat-hosted");
        profile.put("provider", "openai-compatible");
        profile.put("baseUrl", fake.baseUrl());
        profile.put("model", "fake-chat");
        profile.put("purpose", "chat");
        profile.put("local", false);
        profile.put("timeoutSeconds", 5);
        Tokens.admin().contentType(ContentType.JSON).body(profile).post("/api/v1/ai/profiles").then().statusCode(201);
        Map<String, Object> route = new java.util.HashMap<>();
        route.put("profile", "pl-chat-hosted");
        Tokens.admin().contentType(ContentType.JSON).body(route).put("/api/v1/ai/features/pipeline_suggestions").then()
            .statusCode(200);
        try {
            // mem-src keeps the default rule, local_only: a hosted model sees nothing and is not called.
            as(designer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/suggest").then().statusCode(200)
                .body("ai.state", equalTo("withheld")).body("ai.model", equalTo("pl-chat-hosted"));
            assertTrue(fake.seen().stream().noneMatch(x -> x.path().contains("chat")), "no call was made");
            // Allowed: the model is asked, with metadata only.
            sql("UPDATE kilnmark.data_source SET settings = settings || '{\"ai_egress\": \"allowed\"}'::jsonb "
                + "WHERE name = 'mem-src'");
            as(designer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/suggest").then().statusCode(200)
                .body("ai.state", equalTo("used"));
            List<io.kilnmark.server.ai.FakeModelServer.Seen> calls = fake.seen().stream()
                .filter(x -> x.path().contains("chat")).toList();
            assertEquals(1, calls.size());
            String body = calls.get(0).body();
            // tidy rewrites name with an expression it cannot predict, so only id's metadata goes: shares, no values.
            assertTrue(body.contains("lab.people") && body.contains("emptyShare") && body.contains("distinctShare"), body);
            assertFalse(body.contains("Person 1"), "no value from the data is sent: " + body);
            // A model that fails leaves Kilnmark's own suggestions.
            fake.failNext(500, 10);
            as(designer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/suggest").then().statusCode(200)
                .body("ai.state", equalTo("unavailable"));
        } finally {
            sql("UPDATE kilnmark.data_source SET settings = settings - 'ai_egress' WHERE name = 'mem-src'");
            route.put("profile", null);
            Tokens.admin().contentType(ContentType.JSON).body(route).put("/api/v1/ai/features/pipeline_suggestions");
            fake.clear();
        }
    }

    /**
     * The read node's columns and where (docs/design/pipelines.md, "The read node's columns and
     * where"): the memory source reads every row, so Kilnmark applies where, and the row limit
     * counts the rows kept.
     */
    @Test
    @Order(14)
    void aReadKeepsTheColumnsAndRowsAsked() throws Exception {
        String yaml = """
            kilnmark: pipeline/v1
            name: people-some
            parameters:
              max_id: { type: number, default: 200 }
            nodes:
              - id: people
                type: read
                source: mem-src
                asset: lab.people
                columns: [name, id]
                where: "id <= ${max_id} and name like 'Person 1%'"
                row_limit: 50

              - id: out
                type: write
                input: people
                source: mem-out
                table: lab.people_some
                mode: replace
            """;
        as(viewer).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'people' }.columns.out.name", equalTo(List.of("name", "id")))
            .body("notes.message", hasItems(containsString("where is applied by Kilnmark as the rows arrive")));
        as(viewer).body(Map.of("yaml", yaml.replace("[name, id]", "[name, nope]"))).post("/api/v1/pipelines/validate")
            .then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems(containsString("'nope' is not a column")));
        as(viewer).body(Map.of("yaml", yaml.replace("\"id <= ${max_id} and name like 'Person 1%'\"", "id + 1")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false));

        as(designer).body(Map.of("name", "people-some", "yaml", yaml)).post("/api/v1/pipelines").then().statusCode(201);
        String urn = "urn:kilnmark:mem-out:lab.people_some";
        // ids 1, 10-19 and 100-199 start "Person 1": 111 rows, of which the first 50
        JsonPath run = waitForRun(as(designer).post("/api/v1/pipelines/people-some/runs").then().statusCode(202)
            .extract().jsonPath().getLong("id"), "succeeded");
        assertEquals(50L, run.getLong("nodes.find { it.node == 'people' }.rowsOut"), run.prettify());
        assertEquals(50L, MemoryConnector.WRITTEN.get(urn).get());

        // A smaller value, with no row limit reached: ids 1 and 10-19.
        as(designer).body(Map.of("parameters", Map.of("max_id", 19))).post("/api/v1/pipelines/people-some/runs")
            .then().statusCode(202);
        JsonPath fewer = waitForRun(as(designer).get("/api/v1/pipelines/people-some/runs").then().statusCode(200)
            .extract().jsonPath().getLong("[0].id"), "succeeded");
        assertEquals(11L, fewer.getLong("nodes.find { it.node == 'people' }.rowsOut"), fewer.prettify());
    }

    /**
     * Marks follow the data: a column written from a column marked sensitive is marked where it is
     * written, passed on or worked out from it, before the written asset is catalogued; a column made
     * from nothing marked is not; and an admin clears a carried mark as any other.
     */
    @Test
    @Order(15)
    void marksFollowTheDataToWhatAPipelineWrites() throws Exception {
        as(designer).body(Map.of("field", "name", "sensitive", true))
            .put("/api/v1/quality/asset/sensitive?urn=" + READ_URN).then().statusCode(204);
        String yaml = """
            kilnmark: pipeline/v1
            name: people-marked
            nodes:
              - id: people
                type: read
                source: mem-src
                asset: lab.people
              - id: worked
                type: compute
                input: people
                columns:
                  greeting: "'hello ' || name"
                  twice: "id * 2"
              - id: out
                type: write
                input: worked
                source: mem-out
                table: lab.people_marked
                mode: replace
            """;
        as(designer).body(Map.of("name", "people-marked", "yaml", yaml)).post("/api/v1/pipelines").then()
            .statusCode(201).body("latest.valid", equalTo(true));
        long id = as(designer).body(Map.of()).post("/api/v1/pipelines/people-marked/runs").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        waitForRun(id, "succeeded");
        String urn = "urn:kilnmark:mem-out:lab.people_marked";
        java.util.Set<String> carried = new java.util.TreeSet<>();
        try (java.sql.Connection c = db.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
            "SELECT name FROM kilnmark.sensitive_column WHERE asset_urn = ?")) {
            ps.setString(1, urn);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    carried.add(rs.getString(1));
                }
            }
        }
        assertEquals(java.util.Set.of("greeting", "name"), carried, "name passed on, greeting worked out from it");
        // Only an admin clears it, as any mark; the mark on what was read stays.
        as(designer).body(Map.of("field", "greeting", "sensitive", false))
            .put("/api/v1/quality/asset/sensitive?urn=" + urn).then().statusCode(403);
        as(Tokens.ADMIN).body(Map.of("field", "greeting", "sensitive", false))
            .put("/api/v1/quality/asset/sensitive?urn=" + urn).then().statusCode(204);
        as(Tokens.ADMIN).body(Map.of("field", "name", "sensitive", false))
            .put("/api/v1/quality/asset/sensitive?urn=" + READ_URN).then().statusCode(204);
    }
}
