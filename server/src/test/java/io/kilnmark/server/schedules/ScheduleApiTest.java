package io.kilnmark.server.schedules;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/**
 * Schedules (ADR 0046): who may read and set them, what is refused when saved, and firing them
 * through {@link Scheduler#tick}: a pipeline run and a flow run as the schedule, a busy fire
 * skipped, a missed fire not run, two schedulers firing once, schedules deleted with their
 * pipeline, and audit events without parameter values.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScheduleApiTest {

    /** Made-up credentials for the in-memory test connector. */
    static final String IN_SECRET = "sc-in-token-41";
    static final String OUT_SECRET = "sc-out-token-17";
    /** A parameter value the audit log must never hold. */
    static final String SECRET_LABEL = "zebra-quartz-77";

    static String pipeline(String name) {
        return """
            kilnmark: pipeline/v1
            name: %s
            parameters:
              max_id: { type: number, default: 200 }
            nodes:
              - id: people
                type: read
                source: sc-src
                asset: lab.people
                where: "id <= ${max_id}"

              - id: out
                type: write
                input: people
                source: sc-out
                table: lab.%s
                mode: replace
            """.formatted(name, name.replace('-', '_'));
    }

    static final String FLOW = """
        kilnmark: flow/v1
        name: sc-flow
        parameters:
          label: { type: text }
        nodes:
          - { id: pause, type: wait, for: 60s }
        """;

    static String viewer;
    static String steward;
    static String designer;

    @Inject
    Scheduler scheduler;

    @Inject
    AgroalDataSource db;

    @Inject
    AuditLog audit;

    @Inject
    ScheduleStore store;

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

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static long create(String token, Map<String, Object> body) {
        return as(token).body(body).post("/api/v1/schedules").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private static JsonPath schedule(long id) {
        return as(steward).get("/api/v1/schedules/" + id).then().statusCode(200).extract().jsonPath();
    }

    private static String refused(Map<String, Object> body) {
        return as(designer).body(body).post("/api/v1/schedules").then().statusCode(400).extract().jsonPath()
            .getString("error");
    }

    /** Makes schedule {@code id} due {@code ago}. */
    private void dueAgo(long id, Duration ago) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET next_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minus(ago)));
            ps.setLong(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** {requested_by, requested_role} of a pipeline run. */
    private List<String> requested(String table, long run) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT requested_by, requested_role FROM kilnmark." + table
                 + " WHERE id = ?")) {
            ps.setLong(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return List.of(rs.getString(1), rs.getString(2));
            }
        }
    }

    private long runsBy(String by) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kilnmark.pipeline_run "
                 + "WHERE requested_by = ?")) {
            ps.setString(1, by);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void awaitPipelineRunDone(long run) throws InterruptedException {
        await("pipeline run " + run, () -> as(viewer).get("/api/v1/pipeline-runs/" + run).then().extract().jsonPath()
            .getString("run.status"), s -> s != null && !s.equals("queued") && !s.equals("running"));
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        viewer = newToken("viewer", false);
        steward = newToken("steward", false);
        designer = newToken("designer", false);
        admin().contentType(ContentType.JSON).body(Map.of("name", "sc-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "100"), "password", IN_SECRET)).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "sc-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", OUT_SECRET)).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/sc-out")
            .then().statusCode(200);
        long scan = as(designer).post("/api/v1/sources/sc-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        await("the scan " + scan, () -> as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"), "succeeded"::equals);
        for (String name : List.of("sc-copy", "sc-twice", "sc-gone")) {
            as(designer).body(Map.of("name", name, "yaml", pipeline(name))).post("/api/v1/pipelines").then()
                .statusCode(201).body("latestVersion", equalTo(1));
        }
        as(designer).body(Map.of("yaml", FLOW, "message", "first")).post("/api/v1/flows").then().statusCode(201);
    }

    @Test
    @Order(2)
    void designersAndAdminsSetStewardsAndViewersRead() {
        Map<String, Object> b = body("pipeline", "sc-copy", "cron", "0 2 * * *", "zone", "Europe/London",
            "parameters", Map.of("max_id", 50), "description", "Nightly copy");
        as(viewer).body(b).post("/api/v1/schedules").then().statusCode(403);
        as(steward).body(b).post("/api/v1/schedules").then().statusCode(403);
        long id = create(designer, b);
        long byAdmin = create(Tokens.ADMIN, body("pipeline", "sc-copy", "cron", "@hourly", "runAs", "designer"));

        // stewards see the values a run starts with; viewers do not
        JsonPath s = schedule(id);
        assertEquals(50, s.getInt("parameters.max_id"));
        assertEquals("steward", s.getString("runAs"));
        assertEquals("Europe/London", s.getString("zone"));
        assertTrue(s.getBoolean("enabled"));
        assertNotNull(s.getString("nextAt"));
        assertNull(s.getString("lastOutcome"));
        as(viewer).get("/api/v1/schedules/" + id).then().statusCode(200).body("parameters", nullValue())
            .body("cron", equalTo("0 2 * * *"));
        as(viewer).queryParam("pipeline", "sc-copy").get("/api/v1/schedules").then().statusCode(200)
            .body("findAll { it.pipeline == 'sc-copy' }", hasSize(2)).body("[0].parameters", nullValue());
        as(steward).queryParam("flow", "sc-flow").get("/api/v1/schedules").then().statusCode(200)
            .body("findAll { it.pipeline == 'sc-copy' }", hasSize(0));

        // only designers and admins change, pause, resume and delete
        Map<String, Object> paused = new HashMap<>(b);
        paused.put("enabled", false);
        as(steward).body(paused).put("/api/v1/schedules/" + id).then().statusCode(403);
        as(steward).delete("/api/v1/schedules/" + byAdmin).then().statusCode(403);
        as(designer).body(paused).put("/api/v1/schedules/" + id).then().statusCode(200)
            .body("enabled", equalTo(false)).body("nextAt", nullValue());
        as(Tokens.ADMIN).body(b).put("/api/v1/schedules/" + id).then().statusCode(200)
            .body("enabled", equalTo(true)).body("nextAt", notNullValue())
            .body("updatedBy", not(equalTo(s.getString("createdBy"))))
            .body("createdBy", equalTo(s.getString("createdBy")));
        as(designer).delete("/api/v1/schedules/" + byAdmin).then().statusCode(204);
        as(designer).delete("/api/v1/schedules/" + byAdmin).then().statusCode(404);
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(3)
    void mistakesAreRefusedWhenSaved() {
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "0 25 * * *")).contains("hour field takes 0 to 23"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "0 0 0 * * *")).contains("no seconds"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "0 0 30 2 *")).contains("never fires"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "zone", "Mars/Olympus"))
            .startsWith("zone:"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "zone", "+01:00")).startsWith("zone:"));
        assertTrue(refused(body("pipeline", "sc-nowhere", "cron", "@daily")).contains("no pipeline called sc-nowhere"));
        assertTrue(refused(body("flow", "sc-nowhere", "cron", "@daily")).contains("no flow called sc-nowhere"));
        assertTrue(refused(body("pipeline", "sc-copy", "flow", "sc-flow", "cron", "@daily")).contains("not both"));
        assertTrue(refused(body("cron", "@daily")).contains("not both"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "parameters", Map.of("max_idd", 3)))
            .contains("has no parameter 'max_idd'"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "parameters", Map.of("max_id", "many")))
            .startsWith("parameters: max_id must be"));
        assertTrue(refused(body("flow", "sc-flow", "cron", "@daily")).contains("label needs a value"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "runAs", "admin"))
            .contains("never acts as an admin"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "runAs", "viewer")).startsWith("runAs:"));
        assertTrue(refused(body("pipeline", "sc-copy", "cron", "@daily", "description", "x".repeat(201)))
            .startsWith("description:"));
    }

    @Test
    @Order(4)
    void anyoneSignedInCanTryAnExpression() {
        as(viewer).body(Map.of("cron", "0 9 * * MON-FRI", "zone", "America/New_York"))
            .post("/api/v1/schedules/preview").then().statusCode(200).body("valid", equalTo(true))
            .body("next", hasSize(5)).body("zone", equalTo("America/New_York"));
        as(viewer).body(Map.of("cron", "0 0 30 2 *")).post("/api/v1/schedules/preview").then().statusCode(200)
            .body("valid", equalTo(false)).body("problem", containsString("never fires")).body("next", hasSize(0));
        as(viewer).body(Map.of("cron", "@reboot")).post("/api/v1/schedules/preview").then().statusCode(200)
            .body("valid", equalTo(false)).body("problem", containsString("@reboot"));
        Tokens.as("km_not-a-token").contentType(ContentType.JSON).body(Map.of("cron", "@daily"))
            .post("/api/v1/schedules/preview").then().statusCode(401);
    }

    @Test
    @Order(5)
    void aDueScheduleStartsAPipelineRunAsTheSchedule() throws Exception {
        long id = create(designer, body("pipeline", "sc-copy", "cron", "*/5 * * * *",
            "parameters", Map.of("max_id", 10)));
        dueAgo(id, Duration.ofMinutes(1));
        Instant before = Instant.now();
        assertEquals(1, scheduler.tick(Instant.now()));
        JsonPath s = schedule(id);
        assertEquals(Scheduler.STARTED, s.getString("lastOutcome"));
        long run = s.getLong("lastPipelineRun");
        assertNull(s.get("lastFlowRun"));
        assertTrue(Instant.parse(s.getString("nextAt")).isAfter(before));
        assertNotNull(s.getString("lastAt"));
        String by = "schedule " + id + " (set by " + s.getString("updatedBy") + ")";
        assertEquals(List.of(by, "steward"), requested("pipeline_run", run));
        awaitPipelineRunDone(run);
        as(viewer).get("/api/v1/pipeline-runs/" + run).then().statusCode(200).body("run.status", equalTo("succeeded"))
            .body("run.requestedBy", equalTo(by));
        // nothing more is due
        assertEquals(0, scheduler.tick(Instant.now()));
        // the same pipeline keeps its last run; another target drops it
        as(designer).body(body("pipeline", "sc-copy", "cron", "0 3 * * *", "parameters", Map.of("max_id", 10)))
            .put("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", equalTo(Scheduler.STARTED))
            .body("lastPipelineRun", equalTo((int) run));
        as(designer).body(body("flow", "sc-flow", "cron", "0 3 * * *", "parameters", Map.of("label", "x")))
            .put("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", nullValue())
            .body("lastPipelineRun", nullValue());
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(6)
    void aFireClaimedButNeverQueuedIsSeen() throws Exception {
        long id = create(designer, body("pipeline", "sc-twice", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        // the server stops between the claim and the queue: the claim alone
        Instant now = Instant.now();
        List<ScheduleStore.Due> due = store.claimDue(now, Scheduler.BATCH,
            s -> Scheduler.plan(s, now, Duration.ofHours(1)));
        assertEquals(1, due.stream().filter(d -> d.schedule().id() == id).count());
        JsonPath s = schedule(id);
        assertEquals(ScheduleStore.UNQUEUED, s.getString("lastOutcome"));
        assertNull(s.get("lastPipelineRun"));
        assertTrue(Instant.parse(s.getString("nextAt")).isAfter(now));
        // viewers see that it failed, not why
        as(viewer).get("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", equalTo("failed"));
        assertEquals(0, runsBy("schedule " + id + " (set by " + s.getString("updatedBy") + ")"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(7)
    void aFlowRunsAsDesignerAndABusyFireIsSkipped() throws Exception {
        long id = create(Tokens.ADMIN, body("flow", "sc-flow", "cron", "@daily", "runAs", "designer",
            "parameters", Map.of("label", SECRET_LABEL)));
        dueAgo(id, Duration.ofSeconds(30));
        assertEquals(1, scheduler.tick(Instant.now()));
        JsonPath s = schedule(id);
        assertEquals(Scheduler.STARTED, s.getString("lastOutcome"));
        long run = s.getLong("lastFlowRun");
        String by = "schedule " + id + " (set by " + s.getString("updatedBy") + ")";
        as(steward).get("/api/v1/flow-runs/" + run).then().statusCode(200)
            .body("run.requestedBy", equalTo(by)).body("run.requestedRole", equalTo("designer"))
            .body("run.parameters.label", equalTo(SECRET_LABEL));

        // the flow waits a minute: firing again finds it still going
        dueAgo(id, Duration.ofSeconds(10));
        assertEquals(1, scheduler.tick(Instant.now()));
        s = schedule(id);
        assertEquals(Scheduler.SKIPPED, s.getString("lastOutcome"));
        assertNull(s.get("lastFlowRun"));

        as(steward).post("/api/v1/flow-runs/" + run + "/cancel").then().statusCode(200);
        await("flow run " + run + " to stop", () -> as(viewer).get("/api/v1/flow-runs/" + run).then().extract()
            .jsonPath().getString("run.status"), "cancelled"::equals);
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(8)
    void aFireMissedWhileTheServerWasDownDoesNotRun() throws Exception {
        long id = create(designer, body("flow", "sc-flow", "cron", "@hourly", "parameters", Map.of("label", "x")));
        dueAgo(id, Duration.ofDays(3)); // the test profile's catch-up is 48 hours
        Instant now = Instant.now();
        assertEquals(1, scheduler.tick(now));
        JsonPath s = schedule(id);
        assertEquals(Scheduler.MISSED, s.getString("lastOutcome"));
        assertNull(s.get("lastFlowRun"));
        Instant next = Instant.parse(s.getString("nextAt"));
        assertTrue(next.isAfter(now) && next.isBefore(now.plus(Duration.ofHours(1)).plusSeconds(1)), next.toString());
        as(viewer).get("/api/v1/flows/sc-flow/runs").then().statusCode(200)
            .body("findAll { it.requestedBy.startsWith('schedule " + id + " ') }", hasSize(0));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(9)
    void twoSchedulersAtOnceFireOnce() throws Exception {
        long id = create(designer, body("pipeline", "sc-twice", "cron", "* * * * *"));
        dueAgo(id, Duration.ofSeconds(20));
        int ticks = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(ticks);
        try {
            List<Future<Integer>> claimed = new ArrayList<>();
            Instant now = Instant.now();
            for (int i = 0; i < ticks; i++) {
                claimed.add(pool.submit(() -> {
                    go.await();
                    return scheduler.tick(now);
                }));
            }
            go.countDown();
            int total = 0;
            for (Future<Integer> f : claimed) {
                total += f.get();
            }
            assertEquals(1, total);
        } finally {
            pool.shutdownNow();
        }
        JsonPath s = schedule(id);
        assertEquals(Scheduler.STARTED, s.getString("lastOutcome"));
        assertEquals(1, runsBy("schedule " + id + " (set by " + s.getString("updatedBy") + ")"));
        awaitPipelineRunDone(s.getLong("lastPipelineRun"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(10)
    void deletingAPipelineDeletesItsSchedules() {
        long id = create(designer, body("pipeline", "sc-gone", "cron", "@daily"));
        as(designer).delete("/api/v1/pipelines/sc-gone").then().statusCode(204);
        as(steward).get("/api/v1/schedules/" + id).then().statusCode(404);
        audit.awaitStored(5000);
        JsonPath deleted = admin().queryParam("action", AuditLog.SCHEDULE_DELETED).queryParam("limit", 200)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        assertEquals("pipeline deleted", deleted.getString("events.find { it.target == 'schedule " + id
            + "' }.detail.via"), deleted.prettify());
        assertEquals("sc-gone", deleted.getString("events.find { it.target == 'schedule " + id
            + "' }.detail.pipeline"));
    }

    @Test
    @Order(11)
    void theAuditLogRecordsSchedulesWithoutTheirValues() {
        long id = create(designer, body("flow", "sc-flow", "cron", "0 6 * * *", "zone", "Europe/London",
            "parameters", Map.of("label", SECRET_LABEL)));
        Map<String, Object> changed = body("flow", "sc-flow", "cron", "0 7 * * *", "zone", "Europe/London",
            "parameters", Map.of("label", SECRET_LABEL), "enabled", false);
        as(designer).body(changed).put("/api/v1/schedules/" + id).then().statusCode(200);
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
        audit.awaitStored(5000);

        JsonPath set = admin().queryParam("action", AuditLog.SCHEDULE_SET).queryParam("limit", 200)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> mine = set.getList("events.findAll { it.target == 'schedule " + id + "' }");
        assertEquals(2, mine.size(), set.prettify());
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) mine.get(0).get("detail");
        assertEquals("sc-flow", latest.get("flow"));
        assertEquals("0 7 * * *", latest.get("cron"));
        assertEquals("Europe/London", latest.get("zone"));
        assertEquals("steward", latest.get("runAs"));
        assertEquals(false, latest.get("enabled"));
        assertFalse(latest.containsKey("parameters"));

        JsonPath deleted = admin().queryParam("action", AuditLog.SCHEDULE_DELETED).queryParam("limit", 200)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        assertNotNull(deleted.getMap("events.find { it.target == 'schedule " + id + "' }"), deleted.prettify());
        assertFalse(set.prettify().contains(SECRET_LABEL), "parameter values are never recorded");
        assertFalse(deleted.prettify().contains(SECRET_LABEL), "parameter values are never recorded");
    }
}
