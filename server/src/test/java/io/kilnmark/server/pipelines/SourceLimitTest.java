package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * A source's Most runs at once (ADR 0040), end to end: set and checked through the API, recorded in
 * the audit log, and kept by pipeline runs, which wait their turn in the queue without failing or
 * using their one attempt, saying which source they wait for, and are not overtaken by runs queued
 * after them. A designer who sees values masked may still change the limit of a source with columns
 * marked sensitive. Sources are the in-memory test connector.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SourceLimitTest {

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    /** A suffix of this run's own, so the test runs again on a database that kept the last run's. */
    static final String ID = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30, 1L << 40),
        36);
    static final String SRC = "lim-src-" + ID;
    static final String OUT = "lim-out-" + ID;
    static final String A = "lim-a-" + ID;
    static final String B = "lim-b-" + ID;
    static final String C = "lim-c-" + ID;

    static String yaml(String name, String table) {
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
                table: %s
                mode: replace
            """.formatted(name, SRC, OUT, table);
    }

    private static RequestSpecification json() {
        return admin().contentType(ContentType.JSON);
    }

    @AfterAll
    static void release() {
        HoldingGraphRunner.hold = false;
    }

    static JsonPath awaitRun(long id, Predicate<JsonPath> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = admin().get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (done.test(run)) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Run " + id + " did not get there: " + (run == null ? null : run.prettify()));
    }

    private Object[] job(long runId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT status, attempts "
            + "FROM kilnmark.job WHERE kind = 'pipeline' AND (payload ->> 'pipelineRunId')::bigint = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Object[] {rs.getString(1), rs.getInt(2)};
            }
        }
    }

    @Test
    @Order(1)
    void mostRunsAtOnceIsASourceSettingCheckedAndAudited() throws Exception {
        json().body(Map.of("name", SRC, "kind", MemoryConnector.KIND, "settings", Map.of("rows", "50",
            "max_runs", "0"), "password", "lim-src-secret-1")).post("/api/v1/sources").then().statusCode(400)
            .body("error", startsWith("max_runs: a whole number from 1 to 100"));
        json().body(Map.of("name", SRC, "kind", MemoryConnector.KIND, "settings", Map.of("rows", "50",
            "max_runs", "1"), "password", "lim-src-secret-1")).post("/api/v1/sources").then().statusCode(201)
            .body("settings.max_runs", equalTo("1"));
        json().body(Map.of("name", OUT, "kind", MemoryConnector.KIND, "settings", Map.of("allow_writing", "true"),
            "password", "lim-out-secret-2")).post("/api/v1/sources").then().statusCode(201);
        // Raised, then back to 1: each change is in the audit log with what it was and is.
        json().body(Map.of("settings", Map.of("rows", "50", "max_runs", "3"))).put("/api/v1/sources/" + SRC).then()
            .statusCode(200).body("settings.max_runs", equalTo("3"));
        json().body(Map.of("settings", Map.of("rows", "50", "max_runs", "101"))).put("/api/v1/sources/" + SRC).then()
            .statusCode(400);
        json().body(Map.of("settings", Map.of("rows", "50", "max_runs", "1"))).put("/api/v1/sources/" + SRC).then()
            .statusCode(200);
        io.kilnmark.server.audit.AuditLog audit = io.quarkus.arc.Arc.container()
            .instance(io.kilnmark.server.audit.AuditLog.class).get();
        audit.awaitStored(5_000);
        JsonPath events = admin().get("/api/v1/audit?action=source.max_runs&limit=10").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(3, events.getList("events.findAll { it.target == '" + SRC + "' }").size(), events.prettify());
        assertEquals(Map.of("before", 3, "after", 1, "via", "form"),
            events.getMap("events.findAll { it.target == '" + SRC + "' }[0].detail"), events.prettify());

        long scan = admin().post("/api/v1/sources/" + SRC + "/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !"succeeded".equals(status)) {
            Thread.sleep(250);
            status = admin().get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", status);
        for (String name : new String[] {A, B, C}) {
            json().body(Map.of("name", name, "yaml", yaml(name, "lab." + name.replace('-', '_')))).post("/api/v1/pipelines")
                .then().statusCode(201).body("latest.valid", equalTo(true));
        }
    }

    @Test
    @Order(2)
    void twoRunsOnASourceWithALimitOfOneTakeTurnsAndNeitherFails() throws Exception {
        HoldingGraphRunner.hold = true;
        try {
            long a = admin().contentType(ContentType.JSON).post("/api/v1/pipelines/" + A + "/runs").then().statusCode(202).extract().jsonPath()
                .getLong("id");
            awaitRun(a, r -> "running".equals(r.getString("run.status")));
            long b = admin().contentType(ContentType.JSON).post("/api/v1/pipelines/" + B + "/runs").then().statusCode(202).extract().jsonPath()
                .getLong("id");
            JsonPath waiting = awaitRun(b, r -> r.getString("run.error") != null);
            assertEquals("queued", waiting.getString("run.status"));
            assertEquals(PipelineService.waitingFor(SRC), waiting.getString("run.error"));
            // It goes back to the queue every few seconds, and stays waiting while a holds the place.
            Thread.sleep(6_000);
            assertEquals("queued", awaitRun(b, r -> true).getString("run.status"));
            assertEquals("running", awaitRun(a, r -> true).getString("run.status"));
        } finally {
            HoldingGraphRunner.hold = false;
        }
        JsonPath runA = awaitRun(lastRun(A), r -> !"running".equals(r.getString("run.status")));
        assertEquals("succeeded", runA.getString("run.status"), runA.prettify());
        long b = lastRun(B);
        JsonPath runB = awaitRun(b, r -> "succeeded".equals(r.getString("run.status"))
            || "failed".equals(r.getString("run.status")));
        assertEquals("succeeded", runB.getString("run.status"), runB.prettify());
        assertNull(runB.getString("run.error"), "the note it waited with is gone once it started");
        Object[] job = job(b);
        assertEquals("done", job[0]);
        assertTrue((Integer) job[1] <= 1, "waiting used no attempt: " + job[1]);
    }

    private static long lastRun(String pipeline) {
        return admin().get("/api/v1/pipelines/" + pipeline + "/runs?limit=1").then().statusCode(200).extract().jsonPath()
            .getLong("[0].id");
    }

    private static long run(String pipeline) {
        return json().post("/api/v1/pipelines/" + pipeline + "/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id");
    }

    @Test
    @Order(3)
    void aWaitingRunIsNotOvertakenByALaterOne() throws Exception {
        HoldingGraphRunner.hold = true;
        long b;
        long c;
        try {
            long a = run(A);
            awaitRun(a, r -> "running".equals(r.getString("run.status")));
            b = run(B);
            awaitRun(b, r -> r.getString("run.error") != null);
            // a ends; b, back in the queue for a few seconds, is woken as the place frees. A run queued
            // just after must not take the place first.
            admin().contentType(ContentType.JSON).post("/api/v1/pipeline-runs/" + a + "/cancel").then().statusCode(200);
            awaitRun(a, r -> "cancelled".equals(r.getString("run.status")));
            Thread.sleep(500);
            c = run(C);
            long later = c;
            JsonPath runB = awaitRun(b, r -> "running".equals(r.getString("run.status"))
                || "running".equals(admin().get("/api/v1/pipeline-runs/" + later).then().extract().jsonPath()
                    .getString("run.status")));
            assertEquals("running", runB.getString("run.status"), "the run that waited goes first: " + runB.prettify());
            JsonPath runC = awaitRun(c, r -> r.getString("run.error") != null);
            assertEquals("queued", runC.getString("run.status"), "the later run waits: " + runC.prettify());
        } finally {
            HoldingGraphRunner.hold = false;
        }
        assertEquals("succeeded", awaitRun(b, r -> !"running".equals(r.getString("run.status"))).getString("run.status"));
        assertEquals("succeeded", awaitRun(c, r -> "succeeded".equals(r.getString("run.status"))
            || "failed".equals(r.getString("run.status"))).getString("run.status"));
    }

    @Test
    @Order(4)
    void aDesignerWhoSeesValuesMaskedMayChangeTheLimitOfAMarkedSource() {
        admin().contentType(ContentType.JSON).body(Map.of("field", "name", "sensitive", true))
            .queryParam("urn", "urn:kilnmark:" + SRC + ":lab.people").put("/api/v1/quality/asset/sensitive").then()
            .statusCode(204);
        String designer = io.kilnmark.server.Tokens.newToken("designer", false);
        io.kilnmark.server.Tokens.as(designer).contentType(ContentType.JSON)
            .body(Map.of("settings", Map.of("rows", "50", "max_runs", "2"))).put("/api/v1/sources/" + SRC).then()
            .statusCode(200).body("settings.max_runs", equalTo("2"));
        // How it is read is still theirs to leave alone.
        io.kilnmark.server.Tokens.as(designer).contentType(ContentType.JSON)
            .body(Map.of("settings", Map.of("rows", "50", "max_runs", "2", "include", "lab.*")))
            .put("/api/v1/sources/" + SRC).then().statusCode(403);
    }
}
