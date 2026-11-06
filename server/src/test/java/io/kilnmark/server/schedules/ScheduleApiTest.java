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
import java.util.Optional;
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
 * pipeline or flow (a delete waiting for a fire, never deadlocking), and audit events without
 * parameter values.
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

    /** Makes schedule {@code id} due {@code ago}, by the database's clock. */
    private void dueAgo(long id, Duration ago) throws SQLException {
        set(id, "next_at", ago.negated());
    }

    /** Sets a time column of schedule {@code id} to the database's clock plus {@code from}. */
    private void set(long id, String column, Duration from) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET " + column
                 + " = clock_timestamp() + make_interval(secs => ?) WHERE id = ?")) {
            ps.setLong(1, from.toSeconds());
            ps.setLong(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** Waits until a backend runs a statement containing {@code text} and waits for a lock. */
    private void awaitLockWait(String text) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = "
                     + "current_database() AND wait_event_type = 'Lock' AND position(? in query) > 0")) {
                ps.setString(1, text);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nothing waited for a lock running: " + text);
    }

    /** Ends every backend waiting for a lock, so a test that failed in a deadlock leaves none behind. */
    private void endLockWaits() throws SQLException {
        try (Connection c = db.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'");
        }
    }

    /** The database's clock. */
    private Instant dbNow() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT clock_timestamp()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
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
        for (String name : List.of("sc-copy", "sc-twice", "sc-gone", "sc-race")) {
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
        Instant before = dbNow();
        assertEquals(1, scheduler.tick());
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
        assertEquals(0, scheduler.tick());
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
        Instant now = dbNow();
        List<ScheduleStore.Due> due = store.claimDue(null, Scheduler.BATCH,
            (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1)));
        assertEquals(1, due.stream().filter(d -> d.schedule().id() == id).count());
        JsonPath s = schedule(id);
        // while it is fresh it is only starting, as every healthy fire is for a moment
        assertEquals(ScheduleStore.STARTING, s.getString("lastOutcome"));
        assertNull(s.get("lastPipelineRun"));
        assertTrue(Instant.parse(s.getString("nextAt")).isAfter(now));
        // once it has been starting for longer than three polls (72 hours in tests), it was lost
        set(id, "last_at", Duration.ofDays(-4));
        assertEquals(ScheduleStore.UNQUEUED, schedule(id).getString("lastOutcome"));
        // viewers see that it failed, not why
        as(viewer).get("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", equalTo("failed"));
        assertEquals(0, runsBy("schedule " + id + " (set by " + s.getString("updatedBy") + ")"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(7)
    void aChangeNeverDropsOrRepeatsAFire() throws Exception {
        Map<String, Object> b = body("pipeline", "sc-twice", "cron", "*/10 * * * *", "description", "first");
        long id = create(designer, b);

        // due and not yet claimed: changing only the description keeps it due, so the next tick fires it
        dueAgo(id, Duration.ofSeconds(5));
        Instant due = Instant.parse(schedule(id).getString("nextAt"));
        Map<String, Object> described = new HashMap<>(b);
        described.put("description", "second");
        as(designer).body(described).put("/api/v1/schedules/" + id).then().statusCode(200)
            .body("nextAt", equalTo(schedule(id).getString("nextAt")));
        assertEquals(due, Instant.parse(schedule(id).getString("nextAt")));

        // due and not yet claimed: changing its times records the fire as missed, never silently
        Map<String, Object> retimed = new HashMap<>(described);
        retimed.put("cron", "*/15 * * * *");
        as(designer).body(retimed).put("/api/v1/schedules/" + id).then().statusCode(200)
            .body("lastOutcome", equalTo(ScheduleStore.CHANGED_WHILE_DUE));
        assertTrue(Instant.parse(schedule(id).getString("nextAt")).isAfter(dbNow()));

        // a fire claimed later than this clock says (another process's): the next is never before it
        set(id, "last_at", Duration.ofHours(2));
        Instant claimed = Instant.parse(schedule(id).getString("lastAt"));
        Map<String, Object> again = new HashMap<>(retimed);
        again.put("cron", "*/20 * * * *");
        as(designer).body(again).put("/api/v1/schedules/" + id).then().statusCode(200);
        assertTrue(Instant.parse(schedule(id).getString("nextAt")).isAfter(claimed), schedule(id).prettify());
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(8)
    void anOutcomeIsNotWrittenOntoAScheduleThatNowRunsSomethingElse() throws Exception {
        long id = create(designer, body("pipeline", "sc-twice", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        List<ScheduleStore.Due> due = store.claimDue(null, Scheduler.BATCH,
            (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1)));
        ScheduleStore.Schedule claimed = due.stream().filter(d -> d.schedule().id() == id).findFirst().orElseThrow()
            .schedule();
        // while the run is being queued, it is changed to run a flow
        as(designer).body(body("flow", "sc-flow", "cron", "@hourly", "parameters", Map.of("label", "x")))
            .put("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", nullValue());
        assertTrue(store.fire(claimed, s -> new ScheduleStore.Fired(Scheduler.STARTED, null, null, null)).isEmpty());
        assertNull(schedule(id).get("lastOutcome"));
        // with the same target, the outcome is written
        long same = create(designer, body("pipeline", "sc-twice", "cron", "@hourly"));
        dueAgo(same, Duration.ofSeconds(5));
        ScheduleStore.Schedule mine = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(d -> d.schedule().id() == same).findFirst().orElseThrow().schedule();
        assertTrue(store.fire(mine, s -> new ScheduleStore.Fired(Scheduler.SKIPPED, null, null, null)).isPresent());
        assertEquals(Scheduler.SKIPPED, schedule(same).getString("lastOutcome"));

        // paused by a person while it was starting: nothing runs, and the outcome says why
        long paused = create(designer, body("pipeline", "sc-twice", "cron", "@hourly"));
        dueAgo(paused, Duration.ofSeconds(5));
        ScheduleStore.Schedule starting = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(d -> d.schedule().id() == paused).findFirst().orElseThrow().schedule();
        as(designer).body(body("pipeline", "sc-twice", "cron", "@hourly", "enabled", false))
            .put("/api/v1/schedules/" + paused).then().statusCode(200);
        assertTrue(store.fire(starting, s -> new ScheduleStore.Fired(Scheduler.STARTED, null, null, null)).isEmpty());
        assertEquals(ScheduleStore.CHANGED_WHILE_STARTING, schedule(paused).getString("lastOutcome"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
        as(designer).delete("/api/v1/schedules/" + same).then().statusCode(204);
        as(designer).delete("/api/v1/schedules/" + paused).then().statusCode(204);
    }

    @Test
    @Order(9)
    void aFlowRunsAsDesignerAndABusyFireIsSkipped() throws Exception {
        long id = create(Tokens.ADMIN, body("flow", "sc-flow", "cron", "@daily", "runAs", "designer",
            "parameters", Map.of("label", SECRET_LABEL)));
        dueAgo(id, Duration.ofSeconds(30));
        assertEquals(1, scheduler.tick());
        JsonPath s = schedule(id);
        assertEquals(Scheduler.STARTED, s.getString("lastOutcome"));
        long run = s.getLong("lastFlowRun");
        String by = "schedule " + id + " (set by " + s.getString("updatedBy") + ")";
        as(steward).get("/api/v1/flow-runs/" + run).then().statusCode(200)
            .body("run.requestedBy", equalTo(by)).body("run.requestedRole", equalTo("designer"))
            .body("run.parameters.label", equalTo(SECRET_LABEL));

        // the flow waits a minute: firing again finds it still going
        dueAgo(id, Duration.ofSeconds(10));
        assertEquals(1, scheduler.tick());
        s = schedule(id);
        assertEquals(Scheduler.SKIPPED, s.getString("lastOutcome"));
        assertNull(s.get("lastFlowRun"));

        as(steward).post("/api/v1/flow-runs/" + run + "/cancel").then().statusCode(200);
        await("flow run " + run + " to stop", () -> as(viewer).get("/api/v1/flow-runs/" + run).then().extract()
            .jsonPath().getString("run.status"), "cancelled"::equals);
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(10)
    void aFireMissedWhileTheServerWasDownDoesNotRun() throws Exception {
        long id = create(designer, body("flow", "sc-flow", "cron", "@hourly", "parameters", Map.of("label", "x")));
        dueAgo(id, Duration.ofDays(3)); // the test profile's catch-up is 48 hours
        Instant now = dbNow();
        assertEquals(1, scheduler.tick());
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
    @Order(11)
    void twoSchedulersAtOnceFireOnce() throws Exception {
        long id = create(designer, body("pipeline", "sc-twice", "cron", "* * * * *"));
        dueAgo(id, Duration.ofSeconds(20));
        int ticks = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(ticks);
        try {
            List<Future<Integer>> claimed = new ArrayList<>();
            for (int i = 0; i < ticks; i++) {
                claimed.add(pool.submit(() -> {
                    go.await();
                    return scheduler.tick();
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
    @Order(12)
    void deletingAPipelineDeletesItsSchedules() throws SQLException {
        long id = create(designer, body("pipeline", "sc-gone", "cron", "@daily"));
        // an open failure of the pipeline is cleared with it, so one saved later under the name is told of
        sql("INSERT INTO kilnmark.notify_state (dedupe_key, open, severity) VALUES (?, true, 'high')",
            "pipeline:sc-gone");
        as(designer).delete("/api/v1/pipelines/sc-gone").then().statusCode(204);
        as(steward).get("/api/v1/schedules/" + id).then().statusCode(404);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM kilnmark.notify_state WHERE dedupe_key = 'pipeline:sc-gone'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
        audit.awaitStored(5000);
        JsonPath deleted = admin().queryParam("action", AuditLog.SCHEDULE_DELETED).queryParam("limit", 200)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        assertEquals("pipeline deleted", deleted.getString("events.find { it.target == 'schedule " + id
            + "' }.detail.via"), deleted.prettify());
        assertEquals("sc-gone", deleted.getString("events.find { it.target == 'schedule " + id
            + "' }.detail.pipeline"));
    }

    @Test
    @Order(13)
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

    // ---- the setter must keep the role (ADR 0046) ----

    /** A new token: its id and secret. */
    private record Made(long id, String token) {
    }

    private static Made token(String name, String role) {
        JsonPath t = admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath();
        return new Made(t.getLong("id"), t.getString("token"));
    }

    private void sql(String statement, Object... values) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }

    /** A JSON object of name, value pairs. */
    private static String json(Object... pairs) throws Exception {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    }

    private JsonPath pausedEvents() {
        audit.awaitStored(5000);
        return admin().queryParam("action", AuditLog.SCHEDULE_PAUSED).queryParam("limit", 200).get("/api/v1/audit")
            .then().statusCode(200).extract().jsonPath();
    }

    static long revokedSchedule;
    static String revokedName;

    @Test
    @Order(20)
    void revokingATokenPausesWhatItSetAtOnce() throws Exception {
        Made d = token("sc leaver", "designer");
        revokedName = "sc leaver";
        long id = create(d.token(), body("pipeline", "sc-copy", "cron", "@hourly", "runAs", "designer"));
        long other = create(designer, body("pipeline", "sc-copy", "cron", "@daily"));
        admin().delete("/api/v1/tokens/" + d.id()).then().statusCode(204);

        JsonPath s = schedule(id);
        assertFalse(s.getBoolean("enabled"));
        assertNull(s.get("nextAt"));
        assertEquals(ScheduleStore.PAUSED, s.getString("lastOutcome"));
        as(viewer).get("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", equalTo("paused"));
        // another designer's schedule goes on
        assertTrue(schedule(other).getBoolean("enabled"));

        JsonPath events = pausedEvents();
        Map<String, Object> e = events.getMap("events.find { it.target == 'schedule " + id + "' }");
        assertNotNull(e, events.prettify());
        assertEquals("the token that set it was revoked", events.getString("events.find { it.target == 'schedule "
            + id + "' }.detail.reason"));
        assertEquals("sc-copy", events.getString("events.find { it.target == 'schedule " + id + "' }.detail.pipeline"));
        assertNull(events.getMap("events.find { it.target == 'schedule " + other + "' }"));
        as(designer).delete("/api/v1/schedules/" + other).then().statusCode(204);
        revokedSchedule = id;
    }

    @Test
    @Order(21)
    void anExpiredTokensScheduleIsPausedAtItsNextFireAndNothingRuns() throws Exception {
        Made d = token("sc expiring", "designer");
        long id = create(d.token(), body("pipeline", "sc-copy", "cron", "@hourly"));
        sql("UPDATE kilnmark.api_token SET expires_at = now() - interval '1 minute' WHERE id = ?", d.id());
        dueAgo(id, Duration.ofSeconds(5));
        assertEquals(1, scheduler.tick());

        JsonPath s = schedule(id);
        assertFalse(s.getBoolean("enabled"));
        assertEquals(ScheduleStore.PAUSED, s.getString("lastOutcome"));
        assertNull(s.get("lastPipelineRun"));
        assertEquals(0, runsBy("schedule " + id + " (set by sc expiring)"));
        JsonPath events = pausedEvents();
        assertEquals("the token that set it is revoked or expired", events.getString(
            "events.find { it.target == 'schedule " + id + "' }.detail.reason"), events.prettify());
        assertTrue(events.getString("events.find { it.target == 'schedule " + id + "' }.actor").startsWith("server"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Inject
    Setters setters;

    @Inject
    ScheduleService service;

    @Test
    @Order(22)
    void aPersonWhoLosesTheRoleHasTheirSchedulesPaused() throws Exception {
        String person = "p:test-person-0001";
        String name = "sso:Pat Test (pat-0001)";
        long asDesigner = create(designer, body("pipeline", "sc-copy", "cron", "@hourly", "runAs", "designer"));
        long asSteward = create(designer, body("pipeline", "sc-copy", "cron", "@daily"));
        for (long id : new long[] {asDesigner, asSteward}) {
            sql("UPDATE kilnmark.schedule SET set_by_token = NULL, set_by_person = ?, updated_by = ? WHERE id = ?",
                person, name, id);
        }
        String signIn = "INSERT INTO kilnmark.audit_event (actor, action, target, detail) VALUES (?, ?, 'session', "
            + "?::jsonb)";
        sql(signIn, name, AuditLog.SIGN_IN, json("role", "designer", "unmask", false, "person", person));
        assertNull(setters.problem(store.find(asDesigner).orElseThrow()));
        // another person who shares the name signs in as a viewer: it is not this person's sign-in
        sql(signIn, name, AuditLog.SIGN_IN, json("role", "viewer", "unmask", false, "person", "p:someone-else-0002"));
        assertNull(setters.problem(store.find(asDesigner).orElseThrow()));
        // a sign-in refused for want of a second factor takes no role away, as the access review reads it
        sql(signIn, name, AuditLog.SIGN_IN_REFUSED, json("reason", "No second factor.", "person", person));
        assertNull(setters.problem(store.find(asDesigner).orElseThrow()));
        // signed in again as a steward, under a new name (the provider's name changed): the key is
        // what counts, so at the next fire the designer schedule may not run
        sql(signIn, "sso:Pat Renamed (pat-0001)", AuditLog.SIGN_IN, json("role", "steward", "unmask", false,
            "person", person));
        assertEquals("the person who set it signed in with a lower role",
            setters.problem(store.find(asDesigner).orElseThrow()).reason());
        assertNull(setters.problem(store.find(asSteward).orElseThrow()));

        // and at once, when the sign-in happens: only the schedule above their new role
        service.personSignedIn(person, io.kilnmark.server.auth.AccessRole.STEWARD, new AuditLog.Actor(name, null));
        assertFalse(schedule(asDesigner).getBoolean("enabled"));
        assertEquals(ScheduleStore.PAUSED, schedule(asDesigner).getString("lastOutcome"));
        assertTrue(schedule(asSteward).getBoolean("enabled"));

        // a sign-in given no role at all, because none of their groups has one: everything stops
        sql(signIn, name, AuditLog.SIGN_IN_REFUSED, json("reason", io.kilnmark.server.auth.OidcAccess.NO_GROUP,
            "person", person));
        assertEquals("the person who set it has no role at their latest sign-in",
            setters.problem(store.find(asSteward).orElseThrow()).reason());
        service.personSignedIn(person, null, new AuditLog.Actor(name, null));
        assertFalse(schedule(asSteward).getBoolean("enabled"));
        JsonPath events = pausedEvents();
        assertEquals("the person who set it signed in and was given no role", events.getString(
            "events.find { it.target == 'schedule " + asSteward + "' }.detail.reason"), events.prettify());
        as(designer).delete("/api/v1/schedules/" + asDesigner).then().statusCode(204);
        as(designer).delete("/api/v1/schedules/" + asSteward).then().statusCode(204);
    }

    @Test
    @Order(23)
    void resumingByAnotherDesignerRunsAsThem() throws Exception {
        Made d = token("sc successor", "designer");
        long id = revokedSchedule;
        as(d.token()).body(body("pipeline", "sc-copy", "cron", "@hourly", "runAs", "designer"))
            .put("/api/v1/schedules/" + id).then().statusCode(200).body("enabled", equalTo(true))
            .body("updatedBy", equalTo("sc successor"));
        dueAgo(id, Duration.ofSeconds(5));
        assertEquals(1, scheduler.tick());
        JsonPath s = schedule(id);
        assertEquals(Scheduler.STARTED, s.getString("lastOutcome"), s.prettify());
        long run = s.getLong("lastPipelineRun");
        assertEquals(List.of("schedule " + id + " (set by sc successor)", "designer"), requested("pipeline_run", run));
        awaitPipelineRunDone(run);
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(24)
    void aPersonWhoHasNotSignedInForThirtyDaysHasTheirScheduleStopped() throws Exception {
        String person = "p:test-person-0003";
        String name = "sso:Lee Gone (lee-0003)";
        long id = create(designer, body("pipeline", "sc-copy", "cron", "@hourly"));
        sql("UPDATE kilnmark.schedule SET set_by_token = NULL, set_by_person = ?, updated_by = ? WHERE id = ?",
            person, name, id);
        sql("INSERT INTO kilnmark.audit_event (at, actor, action, target, detail) VALUES (now() - interval '31 days', "
            + "?, ?, 'session', ?::jsonb)", name, AuditLog.SIGN_IN, json("role", "designer", "person", person));
        dueAgo(id, Duration.ofSeconds(5));
        assertEquals(1, scheduler.tick());
        JsonPath s = schedule(id);
        assertFalse(s.getBoolean("enabled"));
        assertEquals("paused: the person who set it has not signed in for 30 days", s.getString("lastOutcome"));
        as(viewer).get("/api/v1/schedules/" + id).then().statusCode(200).body("lastOutcome", equalTo("paused"));
        assertEquals(0, runsBy("schedule " + id + " (set by " + name + ")"));
        JsonPath events = pausedEvents();
        assertEquals("the person who set it has not signed in for 30 days", events.getString(
            "events.find { it.target == 'schedule " + id + "' }.detail.reason"), events.prettify());
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(25)
    void aRoleThisServerDoesNotKnowCountsAsNone() throws Exception {
        String person = "p:test-person-0004";
        long id = create(designer, body("pipeline", "sc-copy", "cron", "@hourly"));
        sql("UPDATE kilnmark.schedule SET set_by_token = NULL, set_by_person = ?, updated_by = ? WHERE id = ?",
            person, "sso:Kim (kim-0004)", id);
        sql("INSERT INTO kilnmark.audit_event (actor, action, target, detail) VALUES (?, ?, 'session', ?::jsonb)",
            "sso:Kim (kim-0004)", AuditLog.SIGN_IN, json("role", "superuser", "person", person));
        assertEquals(ScheduleStore.PAUSED, setters.problem(store.find(id).orElseThrow()).outcome());
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(26)
    void aRevokeWhileARunIsBeingQueuedWaitsForIt() throws Exception {
        Made d = token("sc racing", "designer");
        long id = create(d.token(), body("pipeline", "sc-copy", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        ScheduleStore.Schedule claimed = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(x -> x.schedule().id() == id).findFirst().orElseThrow().schedule();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // the fire has checked the setter and is queueing its run, holding the schedule's row
            Future<Optional<ScheduleStore.Fired>> fire = pool.submit(() -> store.fire(claimed, s -> {
                checked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ScheduleStore.Fired(Scheduler.STARTED, null, null, null);
            }));
            assertTrue(checked.await(30, java.util.concurrent.TimeUnit.SECONDS));
            // an admin revokes the token meanwhile: its pause waits for that row, and comes after the run
            Future<Integer> revoke = pool.submit(() -> admin().delete("/api/v1/tokens/" + d.id()).statusCode());
            awaitLockWait("set_by_token = $1");
            assertFalse(revoke.isDone(), "the revoke's pause waits for the fire holding the schedule");
            release.countDown();
            assertTrue(fire.get().isPresent());
            assertEquals(204, revoke.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        JsonPath s = schedule(id);
        assertFalse(s.getBoolean("enabled"));
        assertEquals(ScheduleStore.PAUSED, s.getString("lastOutcome"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Test
    @Order(27)
    void theSignInHistoryIsReadByItsIndexes() throws Exception {
        Object[][] queries = {
            {AuditLog.LATEST_SIGN_IN_BY_KEY, "audit_event_sign_in_person_idx", new Object[] {"p:x"}},
            {AuditLog.LATEST_SIGN_IN_BY_NAME, "audit_event_sign_in_actor_idx", new Object[] {"sso:x"}},
            {AuditLog.NO_GROUP_AFTER_BY_KEY, "audit_event_sign_in_person_idx", new Object[] {"p:x", 1L, "r"}},
            {AuditLog.NO_GROUP_AFTER_BY_NAME, "audit_event_sign_in_actor_idx", new Object[] {"sso:x", 1L, "r"}}};
        for (Object[] q : queries) {
            StringBuilder plan = new StringBuilder();
            try (Connection c = db.getConnection(); java.sql.Statement st = c.createStatement()) {
                c.setAutoCommit(false);
                st.execute("SET LOCAL enable_seqscan = off");
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + q[0])) {
                    Object[] values = (Object[]) q[2];
                    for (int k = 0; k < values.length; k++) {
                        ps.setObject(k + 1, values[k]);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
                c.rollback();
            }
            assertTrue(plan.toString().contains((String) q[1]), q[0] + "\n" + plan);
        }
    }

    @Test
    @Order(29)
    void onlyASignInWithARoleOrARefusalForWantOfAGroupCounts() throws Exception {
        String person = "p:test-person-0008";
        String name = "sso:Ray (ray-0008)";
        long id = create(designer, body("pipeline", "sc-copy", "cron", "@hourly", "runAs", "designer"));
        sql("UPDATE kilnmark.schedule SET set_by_token = NULL, set_by_person = ?, updated_by = ? WHERE id = ?",
            person, name, id);
        String signIn = "INSERT INTO kilnmark.audit_event (at, actor, action, target, detail) VALUES (now() - "
            + "make_interval(secs => ?), ?, ?, 'session', ?::jsonb)";
        // signed in as a designer before sign-ins carried the key
        sql(signIn, 60.0, name, AuditLog.SIGN_IN, json("role", "designer"));
        assertNull(setters.problem(store.find(id).orElseThrow()));
        // since then, 501 refusals for want of a second factor, carrying the key: none takes the role away
        sql("INSERT INTO kilnmark.audit_event (actor, action, target, detail) SELECT ?, ?, 'session', ?::jsonb "
            + "FROM generate_series(1, 501)", name, AuditLog.SIGN_IN_REFUSED,
            json("reason", io.kilnmark.server.auth.OidcAccess.NO_MFA, "person", person));
        assertNull(setters.problem(store.find(id).orElseThrow()));
        // a sign-in as a designer, then one as a steward stored after it, though another server's clock
        // put it an hour earlier: the one stored last counts
        sql(signIn, 0.0, name, AuditLog.SIGN_IN, json("role", "designer", "person", person));
        sql(signIn, 3600.0, name, AuditLog.SIGN_IN, json("role", "steward", "person", person));
        assertEquals("the person who set it signed in with a lower role",
            setters.problem(store.find(id).orElseThrow()).reason());
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
    }

    @Inject
    io.kilnmark.server.pipelines.PipelineService pipelineService;

    @Inject
    io.kilnmark.server.pipelines.PipelineStore pipelineStore;

    /** A delete's status and error, as "409 A run of ..."; the error is empty for a 204. */
    private static String refusal(io.restassured.response.Response r) {
        String error = r.statusCode() == 204 ? "" : r.jsonPath().getString("error");
        return r.statusCode() + " " + error;
    }

    @Test
    @Order(31)
    void aPipelineDeleteThatCannotHaveItsLocksSoonIsRefusedAndAPipelineGoneIsNotFound() throws Exception {
        as(designer).body(Map.of("name", "sc-held", "yaml", pipeline("sc-held"))).post("/api/v1/pipelines").then()
            .statusCode(201);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = db.getConnection()) {
            // something else holds the pipeline's row, for longer than a delete waits for a lock
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement(
                "SELECT 1 FROM kilnmark.pipeline WHERE name = ? FOR UPDATE")) {
                ps.setString(1, "sc-held");
                ps.executeQuery().close();
            }
            try {
                Future<String> delete = pool.submit(() -> refusal(as(designer).delete("/api/v1/pipelines/sc-held")));
                // without the lock timeout this would wait for as long as the row is held
                String refused = delete.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(refused.startsWith("409 ") && refused.contains("Try again"), refused);
            } finally {
                held.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
        as(designer).delete("/api/v1/pipelines/sc-held").then().statusCode(204);
        // a run queued just as the pipeline is deleted finds it gone: not a server error
        org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.server.pipelines.PipelineStore.Missing.class,
            () -> pipelineStore.queueRun("sc-held", 1, "a steward", "steward"));
        // and through the service, as a start that was checked before the delete queues it: 404
        jakarta.ws.rs.WebApplicationException gone = org.junit.jupiter.api.Assertions.assertThrows(
            jakarta.ws.rs.WebApplicationException.class, () -> pipelineService.queueChecked(
                new io.kilnmark.server.pipelines.PipelineService.CheckedRun("sc-held", 1, "{}", "{}"), "a steward",
                io.kilnmark.server.auth.AccessRole.STEWARD));
        assertEquals(404, gone.getResponse().getStatus());
    }

    @Test
    @Order(28)
    void deletingAPipelineWhileItsScheduleFiresWaitsInsteadOfDeadlocking() throws Exception {
        long id = create(designer, body("pipeline", "sc-race", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        ScheduleStore.Schedule claimed = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(x -> x.schedule().id() == id).findFirst().orElseThrow().schedule();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long run;
        try {
            // the fire holds the schedule's row, then queues a run, which locks the pipeline
            Future<Optional<ScheduleStore.Fired>> fire = pool.submit(() -> store.fire(claimed, s -> {
                holding.countDown();
                try {
                    release.await();
                    long r = pipelineService.queueRun("sc-race", null, Map.of(), "schedule " + id,
                        io.kilnmark.server.auth.AccessRole.STEWARD).id();
                    return new ScheduleStore.Fired(Scheduler.STARTED, r, null, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ScheduleStore.Fired(Scheduler.FAILED + "interrupted", null, null, null);
                } catch (RuntimeException e) {
                    return new ScheduleStore.Fired(Scheduler.FAILED + e.getClass().getSimpleName(), null, null, null);
                }
            }));
            assertTrue(holding.await(30, java.util.concurrent.TimeUnit.SECONDS));
            Future<String> delete = pool.submit(() -> refusal(as(designer).delete("/api/v1/pipelines/sc-race")));
            // the delete is waiting for the schedule's row, which the fire holds
            awaitLockWait("kilnmark.schedule WHERE pipeline = $1");
            release.countDown();
            // with the locks taken in the other order these would wait for each other for ever:
            // PostgreSQL cannot see that the fire's transaction waits on its own run's connection
            ScheduleStore.Fired fired = fire.get(60, java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
            assertEquals(Scheduler.STARTED, fired.outcome());
            // the delete waited for the fire, then found its run queued: not a lock wait given up on, as
            // it would be with the locks taken in the other order
            String refused = delete.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(refused.startsWith("409 ") && refused.contains("queued or running"), refused);
            run = fired.pipelineRun();
        } finally {
            release.countDown();
            endLockWaits();
            pool.shutdownNow();
        }
        awaitPipelineRunDone(run);
        as(designer).delete("/api/v1/pipelines/sc-race").then().statusCode(204);
        as(steward).get("/api/v1/schedules/" + id).then().statusCode(404);
    }

    @Inject
    io.kilnmark.server.flows.FlowService flowService;

    @Test
    @Order(29)
    void deletingAFlowWhileItsScheduleFiresWaitsInsteadOfDeadlocking() throws Exception {
        as(designer).body(Map.of("yaml", """
            kilnmark: flow/v1
            name: sc-flow-race
            nodes:
              - { id: pause, type: wait, for: 1s }
            """)).post("/api/v1/flows").then().statusCode(201);
        long id = create(designer, body("flow", "sc-flow-race", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        ScheduleStore.Schedule claimed = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(x -> x.schedule().id() == id).findFirst().orElseThrow().schedule();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long run;
        try {
            // the fire holds the schedule's row, then queues a run, which locks the flow
            Future<Optional<ScheduleStore.Fired>> fire = pool.submit(() -> store.fire(claimed, s -> {
                holding.countDown();
                try {
                    release.await();
                    long r = flowService.queueRun("sc-flow-race", null, Map.of(), "schedule " + id,
                        io.kilnmark.server.auth.AccessRole.STEWARD).id();
                    return new ScheduleStore.Fired(Scheduler.STARTED, null, r, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ScheduleStore.Fired(Scheduler.FAILED + "interrupted", null, null, null);
                } catch (RuntimeException e) {
                    return new ScheduleStore.Fired(Scheduler.FAILED + e.getClass().getSimpleName(), null, null, null);
                }
            }));
            assertTrue(holding.await(60, java.util.concurrent.TimeUnit.SECONDS));
            Future<String> delete = pool.submit(() -> refusal(as(designer).delete("/api/v1/flows/sc-flow-race")));
            // the delete is waiting for the schedule's row, which the fire holds
            awaitLockWait("kilnmark.schedule WHERE flow = $1");
            release.countDown();
            // with the locks taken in the other order these would wait for each other for ever:
            // PostgreSQL cannot see that the fire's transaction waits on its own run's connection
            ScheduleStore.Fired fired = fire.get(60, java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
            assertEquals(Scheduler.STARTED, fired.outcome());
            // the delete waited for the fire, then found its run queued: not a lock wait given up on, as
            // it would be with the locks taken in the other order
            String refused = delete.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(refused.startsWith("409 ") && refused.contains("queued or running"), refused);
            run = fired.flowRun();
        } finally {
            release.countDown();
            endLockWaits();
            pool.shutdownNow();
        }
        await("flow run " + run, () -> as(viewer).get("/api/v1/flow-runs/" + run).then().extract().jsonPath()
            .getString("run.status"), "succeeded"::equals);
        // its job ends just after the run, and a delete waits for that
        await("flow run " + run + "'s job", () -> refusal(as(designer).delete("/api/v1/flows/sc-flow-race")),
            r -> !r.contains("queued or running"));
        as(viewer).get("/api/v1/flows/sc-flow-race").then().statusCode(404);
        as(steward).get("/api/v1/schedules/" + id).then().statusCode(404);
    }

    @Test
    @Order(30)
    void aPauseRecordsTheScheduleAsItIsNowAndAnOutcomeIsWrittenOnce() throws Exception {
        Made d = token("sc changing", "designer");
        long id = create(d.token(), body("pipeline", "sc-copy", "cron", "@hourly"));
        dueAgo(id, Duration.ofSeconds(5));
        ScheduleStore.Schedule claimed = store.claimDue(null, Scheduler.BATCH,
                (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1))).stream()
            .filter(x -> x.schedule().id() == id).findFirst().orElseThrow().schedule();
        // changed while starting, by the same token, which then expires
        as(d.token()).body(body("pipeline", "sc-copy", "cron", "@daily")).put("/api/v1/schedules/" + id).then()
            .statusCode(200);
        sql("UPDATE kilnmark.api_token SET expires_at = now() - interval '1 minute' WHERE id = ?", d.id());
        scheduler.run(claimed);
        assertEquals(ScheduleStore.PAUSED, schedule(id).getString("lastOutcome"));
        JsonPath events = pausedEvents();
        assertEquals("@daily", events.getString("events.find { it.target == 'schedule " + id + "' }.detail.cron"),
            events.prettify());

        // an outcome written again after a failure only replaces "starting"
        long other = create(designer, body("pipeline", "sc-copy", "cron", "@hourly"));
        dueAgo(other, Duration.ofSeconds(5));
        store.claimDue(null, Scheduler.BATCH, (s, at) -> Scheduler.plan(s, at, Duration.ofHours(1)));
        assertTrue(store.recordAfterFailure(other, new ScheduleStore.Fired(Scheduler.STARTED, null, null, null)));
        assertEquals(Scheduler.STARTED, schedule(other).getString("lastOutcome"));
        assertFalse(store.recordAfterFailure(other, new ScheduleStore.Fired(Scheduler.SKIPPED, null, null, null)));
        assertEquals(Scheduler.STARTED, schedule(other).getString("lastOutcome"));
        as(designer).delete("/api/v1/schedules/" + id).then().statusCode(204);
        as(designer).delete("/api/v1/schedules/" + other).then().statusCode(204);
    }
}
