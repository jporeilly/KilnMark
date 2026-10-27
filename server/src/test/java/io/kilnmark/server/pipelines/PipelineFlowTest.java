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
 * Pipelines end to end on PostgreSQL: allow_writing, the step catalogue, validation, saving
 * versions with If-Match, runs on a worker with kiln-core's graph runner, per-step records, written
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
        steps:
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

    static String steward;
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
            run = as(steward).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
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
        steward = token("pipeline-steward", "steward");
        viewer = token("pipeline-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "mem-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "2500"), "password", IN_SECRET)).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "mem-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);

        as(steward).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/mem-out")
            .then().statusCode(403).body("error", startsWith("allow_writing: only admins"));
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/mem-out")
            .then().statusCode(200).body("settings.allow_writing", equalTo("true"));
        // A steward's edit that leaves the setting out keeps it.
        as(steward).body(Map.of("settings", Map.of("description", "Copies land here")))
            .put("/api/v1/sources/mem-out").then().statusCode(200).body("settings.allow_writing", equalTo("true"));
        // But only an admin may change where a source that allows writing points.
        as(steward).body(Map.of("settings", Map.of("description", "Copies land here", "rows", "5")))
            .put("/api/v1/sources/mem-out").then().statusCode(403)
            .body("error", containsString("only an admin can change where it points (rows)"));

        // Scan the source read from, so validation knows its columns.
        long scan = as(steward).post("/api/v1/sources/mem-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !status.equals("succeeded")) {
            Thread.sleep(250);
            status = as(steward).get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
            if (status == null) {
                status = as(steward).get("/api/v1/sources/mem-src").then().extract().jsonPath()
                    .getString("lastRun.status");
            }
        }
        assertEquals("succeeded", status);
    }

    @Test
    @Order(2)
    void theStepCatalogueListsTheServersSteps() {
        JsonPath steps = as(viewer).get("/api/v1/steps").then().statusCode(200).extract().jsonPath();
        assertTrue(steps.getList("type").containsAll(List.of("read", "write")), steps.prettify());
        assertEquals("source", steps.getString("find { it.type == 'read' }.kind"));
        assertEquals(List.of("source", "asset", "row_limit"), steps.getList("find { it.type == 'read' }.settings.key"));
        assertEquals(List.of("create", "append", "replace", "upsert"),
            steps.getList("find { it.type == 'write' }.settings.find { it.key == 'mode' }.choices"));
    }

    @Test
    @Order(3)
    void validationGivesProblemsByLineAndEachStepsColumns() {
        as(viewer).body(Map.of("yaml", YAML)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("steps.find { it.id == 'people' }.columns.out.name", equalTo(List.of("id", "name")))
            .body("edges[0].fromStep", equalTo("people"));
        JsonPath bad = as(viewer).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    colour: red")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false)).extract().jsonPath();
        assertEquals(16, bad.getInt("problems[0].line"));
        assertEquals("out", bad.getString("problems[0].step"));
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
        as(steward).body(Map.of("name", "People Copy", "yaml", YAML)).post("/api/v1/pipelines").then().statusCode(400)
            .body("error", startsWith("name:"));
        as(steward).body(Map.of("name", "people-copy", "yaml", YAML, "message", "First go")).post("/api/v1/pipelines")
            .then().statusCode(201).header("ETag", containsString("1")).body("latestVersion", equalTo(1))
            .body("latest.valid", equalTo(true)).body("description", equalTo("Synthetic people, copied."));
        as(steward).body(Map.of("name", "people-copy", "yaml", YAML)).post("/api/v1/pipelines").then().statusCode(409);

        as(steward).header("If-Match", "\"1\"").body(Map.of("yaml", YAML.replace("Synthetic people, copied.",
            "Synthetic people, copied again."), "message", "Clearer description")).put("/api/v1/pipelines/people-copy")
            .then().statusCode(200).header("ETag", containsString("2")).body("latestVersion", equalTo(2));
        as(steward).header("If-Match", "\"1\"").body(Map.of("yaml", YAML, "message", "Stale"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(409)
            .body("error", startsWith("Someone else saved a new version"));

        // A draft with a problem is saved but cannot run.
        as(steward).header("If-Match", "\"2\"").body(Map.of("yaml", YAML.replace("type: write", "type: wirte"),
            "message", "Draft")).put("/api/v1/pipelines/people-copy").then().statusCode(200)
            .body("latest.valid", equalTo(false)).body("latest.problems[0].line", equalTo(11));
        as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(409)
            .body("error", containsString("cannot run until its problems are fixed"));
        as(steward).header("If-Match", "\"3\"").body(Map.of("yaml", YAML, "message", "Fixed"))
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
        firstRun = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202)
            .header("Location", containsString("/api/v1/pipeline-runs/")).body("status", equalTo("queued"))
            .body("version", equalTo(4)).extract().jsonPath().getLong("id");
        JsonPath run = waitForRun(firstRun, "succeeded");
        assertEquals(2500L, run.getLong("steps.find { it.step == 'people' }.rowsOut"));
        assertEquals(2500L, run.getLong("steps.find { it.step == 'out' }.rowsIn"));
        assertEquals(2500L, run.getLong("steps.find { it.step == 'out' }.ports.in.in"));
        assertEquals(List.of("finished", "finished"), run.getList("steps.outcome"));
        assertEquals(2, run.getList("steps").size(), "the pipeline's two steps, and only those");
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
    }

    @Test
    @Order(6)
    void runsCanBeCancelled() throws Exception {
        HoldingGraphRunner.hold = true;
        long id;
        try {
            id = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
                .getLong("id");
            waitForRun(id, "running");
            as(viewer).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(403);
            as(steward).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(200)
                .body("cancelRequested", equalTo(true));
            JsonPath done = waitForRun(id, "cancelled");
            assertTrue(done.getString("run.error").startsWith("Cancelled"), done.prettify());
        } finally {
            HoldingGraphRunner.hold = false;
        }
        as(steward).post("/api/v1/pipeline-runs/" + id + "/cancel").then().statusCode(409);
        as(steward).post("/api/v1/pipeline-runs/" + firstRun + "/cancel").then().statusCode(409);
        as(viewer).get("/api/v1/pipeline-runs/999999").then().statusCode(404);
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
        as(steward).body(Map.of("name", "people-copy-2", "yaml", YAML.replace("name: people-copy",
            "name: people-copy-2"))).post("/api/v1/pipelines").then().statusCode(201);
        HoldingGraphRunner.hold = true;
        long first;
        try {
            first = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(first, "running");
            as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(409)
                .body("error", containsString("already queued or running"));
            // Another pipeline writing the same table waits for it, then gives up plainly.
            long second = as(steward).post("/api/v1/pipelines/people-copy-2/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            JsonPath failed = waitForRun(second, "failed");
            assertTrue(failed.getString("run.error").startsWith("Another pipeline run is writing " + WRITE_URN),
                failed.prettify());
            as(steward).post("/api/v1/pipeline-runs/" + first + "/cancel").then().statusCode(200);
            waitForRun(first, "cancelled");
        } finally {
            HoldingGraphRunner.hold = false;
        }
        as(steward).delete("/api/v1/pipelines/people-copy-2").then().statusCode(204);
    }

    @Test
    @Order(8)
    void writtenAssetsKeepWhatTheCatalogueKnowsAndSurviveScans() throws Exception {
        long sourceId = graph.source("mem-out").orElseThrow().id();
        // A scan that began before the pipeline wrote must not mark the written table removed.
        long scan = sql("INSERT INTO kilnmark.run (source_id, kind, status, started_at) VALUES (" + sourceId
            + ", 'scan', 'running', now() - interval '1 hour') RETURNING id");
        sql("UPDATE kilnmark.asset SET description = 'Copied people' WHERE urn = '" + WRITE_URN + "'");
        long run = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
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
            long held = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            JsonPath r = waitForRun(held, "succeeded");
            assertFalse(r.getBoolean("written[0].registered"), r.prettify());
            assertTrue(r.getList("notes").toString().contains("Scan the source to catalogue"), r.prettify());
        }

        // Rows appended to a table the connector cannot describe keep the catalogued columns.
        String missing = "urn:kilnmark:mem-out:lab.missing_copy";
        String toMissing = YAML.replace("table: lab.people_copy", "table: lab.missing_copy");
        as(steward).body(Map.of("yaml", toMissing, "message", "Elsewhere")).put("/api/v1/pipelines/people-copy")
            .then().statusCode(200);
        waitForRun(as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id"), "succeeded");
        sql("UPDATE kilnmark.field SET description = 'Who' WHERE asset_urn = '" + missing + "' AND name = 'name'");
        as(steward).body(Map.of("yaml", toMissing.replace("mode: replace", "mode: append"), "message", "Append"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(200);
        JsonPath appended = waitForRun(as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202)
            .extract().jsonPath().getLong("id"), "succeeded");
        assertTrue(appended.getBoolean("written[0].registered"), appended.prettify());
        as(viewer).get("/api/v1/assets/" + missing).then().statusCode(200)
            .body("fields.find { it.name == 'name' }.description", equalTo("Who"));

        // A write's options cannot say where to write.
        as(viewer).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    host: elsewhere")))
            .post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("problems.message", hasItems(containsString("host")))
            .body("valid", equalTo(false));
        as(steward).body(Map.of("yaml", YAML, "message", "Back")).put("/api/v1/pipelines/people-copy").then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    void failuresNameTheStepWithoutTheSecret() throws Exception {
        String failing = YAML.replace("mode: replace", "mode: replace\n    fail: true");
        as(steward).body(Map.of("yaml", failing, "message", "Make the write fail"))
            .put("/api/v1/pipelines/people-copy").then().statusCode(200);
        long id = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        JsonPath run = waitForRun(id, "failed");
        String all = run.prettify();
        assertTrue(run.getString("run.error").contains("refused the rows"), all);
        assertTrue(run.getString("steps.find { it.step == 'out' }.error").contains("refused the rows"), all);
        assertEquals("failed", run.getString("steps.find { it.step == 'out' }.outcome"), all);
        assertFalse(all.contains(OUT_SECRET), all);
        assertFalse(all.contains(IN_SECRET), all);
        assertFalse(run.getBoolean("written[0].registered"), "a failed run catalogues nothing");
    }

    @Test
    @Order(10)
    void messagesFromTheDataGoThroughSights() throws Exception {
        String admin = Tokens.ADMIN;
        // The pipeline now fails at its write (the previous test); mark a column it reads sensitive.
        as(steward).body(Map.of("field", "name", "sensitive", true)).queryParam("urn", READ_URN)
            .put("/api/v1/quality/asset/sensitive").then().statusCode(204);
        try {
            long failed = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(failed, "failed");
            for (String path : List.of("/api/v1/pipeline-runs/" + failed)) {
                JsonPath v = as(viewer).get(path).then().statusCode(200).extract().jsonPath();
                assertEquals("The run failed. " + PipelineService.FOR_STEWARDS, v.getString("run.error"));
                assertEquals("This step failed. " + PipelineService.FOR_STEWARDS,
                    v.getString("steps.find { it.step == 'out' }.error"));
                JsonPath st = as(steward).get(path).then().statusCode(200).extract().jsonPath();
                assertEquals("The run failed. " + PipelineService.FOR_ADMINS, st.getString("run.error"));
                assertEquals("This step failed. " + PipelineService.FOR_ADMINS,
                    st.getString("steps.find { it.step == 'out' }.error"));
                JsonPath ad = as(admin).get(path).then().statusCode(200).extract().jsonPath();
                assertTrue(ad.getString("run.error").contains("refused the rows"), ad.prettify());
                assertFalse(ad.prettify().contains(OUT_SECRET), "the secret stays out, even for admins");
            }
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
            as(steward).body(Map.of("yaml", YAML.replace("mode: replace", "mode: replace\n    break_profile: true"),
                "message", "Writes again")).put("/api/v1/pipelines/people-copy").then().statusCode(200);
            long noted = as(steward).post("/api/v1/pipelines/people-copy/runs").then().statusCode(202).extract()
                .jsonPath().getLong("id");
            waitForRun(noted, "succeeded");
            String path = "/api/v1/pipeline-runs/" + noted;
            String note = "out: what it wrote could not be profiled; scan mem-out to profile lab.people_copy";
            as(viewer).get(path).then().statusCode(200)
                .body("notes", hasItems(note + ". " + PipelineService.FOR_STEWARDS)).body("run.error",
                    org.hamcrest.Matchers.nullValue());
            as(steward).get(path).then().statusCode(200).body("notes", hasItems(note + ". "
                + PipelineService.FOR_ADMINS));
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
        as(steward).delete("/api/v1/pipelines/people-copy").then().statusCode(204);
        as(viewer).get("/api/v1/pipelines/people-copy").then().statusCode(404);
        as(viewer).get("/api/v1/pipeline-runs/" + firstRun).then().statusCode(404);
        as(viewer).queryParam("urn", WRITE_URN).get("/api/v1/lineage").then().statusCode(200)
            .body("upstream[0].urn", equalTo(READ_URN));
    }
}
