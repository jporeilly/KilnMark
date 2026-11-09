package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.pipelines.Samples;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * The Starter Pack end to end (ADR 0051), on a database kilnmark_samples with a login of its own on
 * the test's PostgreSQL server, as samples-db makes them: turned on, everything is there and runs,
 * the banner's state says so for every role, and it is in the audit log; its example schedule runs
 * one sample, set and recorded as any schedule is; turned on again, nothing is added twice (the
 * example schedule included); turned off (only once DELETE is typed), everything it added is gone and the
 * database stays, empty. The pack's guard refuses any database but its own.
 */
@QuarkusTest
@TestProfile(StarterPackApiTest.Pack.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StarterPackApiTest {

    static final String PASSWORD = "samples-test-0123456789";

    /** The samples' login's password, as compose passes it from config/.env. */
    public static class Pack implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.samples-db.password", PASSWORD);
        }
    }

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "kilnmark.files.roots")
    String roots;

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @jakarta.inject.Inject
    StarterPack pack;

    /** As samples-db makes them: an empty database owned by a login of its own, which is no superuser. */
    static void makeSamplesDatabase(io.agroal.api.AgroalDataSource db) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS kilnmark_samples WITH (FORCE)");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kilnmark_samples') THEN "
                + "CREATE ROLE kilnmark_samples LOGIN; END IF; END $$");
            s.execute("ALTER ROLE kilnmark_samples NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + PASSWORD + "'");
            s.execute("CREATE DATABASE kilnmark_samples OWNER kilnmark_samples");
        }
    }

    private Connection samples() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "kilnmark_samples");
        p.setProperty("password", PASSWORD);
        return DriverManager.getConnection(StarterPack.samplesUrl(url), p);
    }

    private int tables() throws Exception {
        try (Connection c = samples(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                 + "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast') AND c.relkind IN ('r', 'v')")) {
            r.next();
            return r.getInt(1);
        }
    }

    private Path folder() {
        return Path.of(roots.split(",")[0].strip()).resolve(StarterPack.FOLDER);
    }

    static JsonPath waitForRun(long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = admin().get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (List.of("succeeded", "failed", "cancelled").contains(run.getString("run.status"))) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + id + " did not finish: " + (run == null ? null : run.prettify()));
    }

    static int events(String action) {
        return admin().get("/api/v1/audit?action=" + action + "&limit=50").then().statusCode(200).extract().jsonPath()
            .getList("events").size();
    }

    /** The audit log stores events on a thread of its own: waits a little for the count to reach {@code n}. */
    static void eventually(String action, int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (events(action) < n && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(n, events(action), action);
    }

    @Test
    @Order(1)
    void onlyAdminsTurnItAndEveryoneSeesWhetherItIsOn() throws Exception {
        // the pack's folder an earlier run left in a files root other runs share: this run starts without it
        if (Files.exists(folder())) {
            try (var walk = Files.walk(folder())) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        makeSamplesDatabase(db);
        String viewer = Tokens.newToken("viewer", false);
        Tokens.as(viewer).get("/api/v1/starter-pack").then().statusCode(200).body("on", equalTo(false));
        Tokens.as(viewer).get("/api/v1/starter-pack/details").then().statusCode(403);
        Tokens.as(Tokens.newToken("designer", true)).post("/api/v1/starter-pack").then().statusCode(403);
        admin().contentType(ContentType.JSON).body(Map.of("confirm", "delete")).delete("/api/v1/starter-pack").then()
            .statusCode(400).body("error", containsString("type DELETE"));
        JsonPath d = admin().get("/api/v1/starter-pack/details").then().statusCode(200).extract().jsonPath();
        assertTrue(d.getBoolean("ready"), d.prettify());
        assertEquals("starter-pack", d.getString("mode"));
        Tokens.as(viewer).get("/api/v1/starter-pack").then().statusCode(200).body("mode", equalTo("starter-pack"));
        assertTrue(d.getString("people.add").startsWith("kilnmark.sh starter people"), "people are the control script's");
        assertEquals(List.of(), d.getList("people.signedIn"));
    }

    @Test
    @Order(2)
    void turnedOnEverySampleIsThereAndRuns() throws Exception {
        int added = events(AuditLog.STARTER_PACK_ADDED);
        JsonPath r = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertTrue(r.getBoolean("on"), r.prettify());
        for (String step : List.of("database", "database source", "files source", "sample tables", "samples",
            StarterPack.EXAMPLE_STEP)) {
            assertEquals("done", r.getString("steps.find { it.step == '" + step + "' }.outcome"), step + ": " + r.prettify());
        }
        assertEquals(null, r.getString("steps.find { it.step == 'example people' }"), "Kilnmark makes no people");
        assertFalse(r.prettify().contains("assword"), "and hands out no password");
        Tokens.as(Tokens.newToken("viewer", false)).get("/api/v1/starter-pack").then().statusCode(200)
            .body("on", equalTo(true)).body("by", org.hamcrest.Matchers.nullValue()); // who: details, admins only
        eventually(AuditLog.STARTER_PACK_ADDED, added + 1);

        JsonPath src = admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(200).extract().jsonPath();
        assertEquals("true", src.getString("settings.allow_writing"));
        assertTrue(src.getString("settings.url").contains("/kilnmark_samples?"), src.prettify());
        admin().get("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(200)
            .body("settings.allow_writing", equalTo("true"));

        JsonPath tables = waitForRun(r.getLong("tablesRun.id"));
        assertEquals("succeeded", tables.getString("run.status"), tables.prettify());
        try (Connection c = samples(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM public.crm_customers")) {
            rs.next();
            assertEquals(240, rs.getInt(1));
        }
        JsonPath limit = waitForRun(admin().contentType(ContentType.JSON).body(Map.of())
            .post("/api/v1/pipelines/sample-limit/runs").then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", limit.getString("run.status"), limit.prettify());
        assertTrue(Files.exists(folder().resolve("samples/filter-and-route/first_transactions.csv")));
    }

    /** The schedules on the example schedule's sample. */
    static List<Map<String, Object>> exampleSchedules() {
        return admin().get("/api/v1/schedules?pipeline=" + StarterPack.EXAMPLE_PIPELINE).then().statusCode(200)
            .extract().jsonPath().getList("$");
    }

    /** Makes schedule {@code id} due a minute ago, by the database's clock, and fires what is due. */
    private void fireNow(long id) throws Exception {
        try (Connection c = db.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
            "UPDATE kilnmark.schedule SET next_at = clock_timestamp() - interval '1 minute' WHERE id = ?")) {
            ps.setLong(1, id);
            assertEquals(1, ps.executeUpdate());
        }
        // the scheduler's loop waits a day in tests: fire what is due here, as ScheduleApiTest does
        java.lang.reflect.Method tick = io.kilnmark.server.schedules.Scheduler.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(io.quarkus.arc.ClientProxy.unwrap(scheduler));
    }

    @jakarta.inject.Inject
    io.kilnmark.server.schedules.Scheduler scheduler;

    @Test
    @Order(3)
    void theExampleScheduleRunsOneSampleEveryHourAndRunningItAgainIsHarmless() throws Exception {
        List<Map<String, Object>> there = exampleSchedules();
        assertEquals(1, there.size(), there.toString());
        long id = ((Number) there.get(0).get("id")).longValue();
        JsonPath s = admin().get("/api/v1/schedules/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals(StarterPack.EXAMPLE_CRON, s.getString("cron"));
        assertEquals("UTC", s.getString("zone"));
        assertEquals("steward", s.getString("runAs"), "never as an admin");
        assertTrue(s.getBoolean("enabled"));
        assertTrue(s.getString("description").startsWith("The Starter Pack's example schedule"), s.prettify());
        java.time.Instant next = java.time.Instant.parse(s.getString("nextAt"));
        assertTrue(next.isBefore(java.time.Instant.now().plus(Duration.ofMinutes(61))), "within the hour: " + next);
        // recorded as any schedule set is, saying the pack set it
        String event = "events.find { it.target == 'schedule " + id + "' }";
        JsonPath set = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            set = admin().get("/api/v1/audit?action=" + AuditLog.SCHEDULE_SET + "&limit=50").then().statusCode(200)
                .extract().jsonPath();
            if (set.get(event) != null) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("starter pack", set.getString(event + ".detail.via"), set.prettify());
        assertEquals(StarterPack.EXAMPLE_PIPELINE, set.getString(event + ".detail.pipeline"), set.prettify());
        assertEquals(StarterPack.EXAMPLE_CRON, set.getString(event + ".detail.cron"), set.prettify());

        // it fires as the schedule, and firing it again only replaces the one file it writes
        Path written = folder().resolve("samples/read-and-write");
        long filesBefore = -1;
        for (int i = 0; i < 2; i++) {
            fireNow(id);
            JsonPath fired = admin().get("/api/v1/schedules/" + id).then().statusCode(200).extract().jsonPath();
            assertEquals(io.kilnmark.server.schedules.Scheduler.STARTED, fired.getString("lastOutcome"), fired.prettify());
            JsonPath run = waitForRun(fired.getLong("lastPipelineRun"));
            assertEquals("succeeded", run.getString("run.status"), run.prettify());
            assertTrue(run.getString("run.requestedBy").startsWith("schedule " + id + " (set by "), run.prettify());
            assertTrue(Files.isRegularFile(written.resolve("accounts.csv")));
            try (var files = Files.list(written)) {
                long n = files.count();
                if (filesBefore >= 0) {
                    assertEquals(filesBefore, n, "the same file, replaced: nothing grows");
                }
                filesBefore = n;
            }
        }
    }

    @Test
    @Order(4)
    void turnedOnAgainNothingIsAddedTwice() {
        JsonPath r = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertTrue(r.getString("steps.find { it.step == 'sample tables' }.detail").startsWith("already there"), r.prettify());
        assertTrue(r.getString("steps.find { it.step == 'samples' }.detail").contains("already there"), r.prettify());
        assertFalse(r.getString("steps.find { it.step == 'samples' }.detail").matches("(?s).*[0-9]+ added.*"), r.prettify());
        assertEquals("done", r.getString("steps.find { it.step == '" + StarterPack.EXAMPLE_STEP + "' }.outcome"),
            r.prettify());
        assertTrue(r.getString("steps.find { it.step == '" + StarterPack.EXAMPLE_STEP + "' }.detail")
            .startsWith("already there"), r.prettify());
        assertEquals(1, exampleSchedules().size(), "no second schedule");
    }

    @Test
    @Order(5)
    void theGuardRefusesAnyDatabaseButThePacksOwn() throws Exception {
        // Kilnmark's own database, as Kilnmark's own login: refused, so it is never emptied by mistake.
        try (Connection c = db.getConnection()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> StarterPack.guard(c));
            assertTrue(e.getMessage().contains("does not name kilnmark_samples"), e.getMessage());
        }
        try (Connection c = samples()) {
            StarterPack.guard(c); // its own: let through
        }
        assertEquals("jdbc:postgresql:" + "/" + "/db:5432/kilnmark_samples?sslmode=require",
            StarterPack.samplesUrl("jdbc:postgresql:" + "/" + "/db:5432/kilnmark?sslmode=require"));
        assertEquals("jdbc:postgresql:" + "/" + "/db/kilnmark_samples?sslmode=require",
            StarterPack.samplesUrl("jdbc:postgresql:" + "/" + "/db/kilnmark"));
    }

    @Test
    @Order(6)
    void turnedOffEverythingItAddedIsGoneAndTheDatabaseStaysEmpty() throws Exception {
        assertTrue(tables() >= Samples.TABLES.size());
        // A flow of the team's own, outside the Library, still reads Kilnmark's sample data: kept.
        admin().contentType(ContentType.JSON).body(Map.of("yaml", """
            kilnmark: flow/v1
            name: own-checks-the-sample-data
            nodes:
              - { id: ready, type: check-source, source: sample-data, tables: [crm/customers.csv] }
              - { id: done, type: stop, message: Checked }
            connections:
              - { from: ready, to: done }
            """)).post("/api/v1/flows").then().statusCode(201);
        // a column of the pack's database marked sensitive: its delete is recorded as clearing marks
        String urn;
        String field;
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
            "SELECT f.asset_urn, f.name FROM kilnmark.field f JOIN kilnmark.asset a ON a.urn = f.asset_urn "
                + "JOIN kilnmark.data_source s ON s.id = a.source_id WHERE s.name = '" + StarterPack.DATABASE_SOURCE
                + "' ORDER BY f.asset_urn, f.name LIMIT 1")) {
            assertTrue(rs.next(), "the sample tables are catalogued");
            urn = rs.getString(1);
            field = rs.getString(2);
        }
        admin().contentType(ContentType.JSON).body(Map.of("field", field, "sensitive", true))
            .put("/api/v1/quality/asset/sensitive?urn=" + urn).then().statusCode(204);
        // a schedule on a sample: deleted with it, and recorded as a pipeline's own delete records it
        long schedule = admin().contentType(ContentType.JSON).body(Map.of("pipeline", "sample-limit", "cron", "0 6 * * *"))
            .post("/api/v1/schedules").then().statusCode(201).extract().jsonPath().getLong("id");
        // and the pack's example schedule, which goes with its sample in the same way
        long example = ((Number) exampleSchedules().get(0).get("id")).longValue();
        int removed = events(AuditLog.STARTER_PACK_REMOVED);
        int schedulesDeleted = events(AuditLog.SCHEDULE_DELETED);
        JsonPath r = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE")).delete("/api/v1/starter-pack")
            .then().statusCode(200).extract().jsonPath();
        assertFalse(r.getBoolean("on"), r.prettify());
        assertEquals(List.of(), r.getList("steps.findAll { it.outcome == 'failed' }"), r.prettify());
        eventually(AuditLog.STARTER_PACK_REMOVED, removed + 1);
        eventually(AuditLog.SCHEDULE_DELETED, schedulesDeleted + 2);
        JsonPath gone = admin().get("/api/v1/audit?action=" + AuditLog.SCHEDULE_DELETED + "&limit=2").then().statusCode(200)
            .extract().jsonPath();
        String limitGone = "events.find { it.target == 'schedule " + schedule + "' }";
        assertEquals("sample-limit", gone.getString(limitGone + ".detail.pipeline"), gone.prettify());
        String exampleGone = "events.find { it.target == 'schedule " + example + "' }";
        assertEquals(StarterPack.EXAMPLE_PIPELINE, gone.getString(exampleGone + ".detail.pipeline"), gone.prettify());
        assertEquals("pipeline deleted", gone.getString(exampleGone + ".detail.via"), gone.prettify());
        assertEquals(List.of(), exampleSchedules(), "the example schedule is gone");
        // starter.removed names what went
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_REMOVED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(ev.getList("events[0].detail.pipelines").contains("sample-limit"), ev.prettify());
        assertTrue(ev.getList("events[0].detail.pipelines").contains(Samples.SETUP), ev.prettify());
        assertFalse(ev.getList("events[0].detail.flows").isEmpty(), ev.prettify());
        assertTrue(ev.getList("events[0].detail.sources").contains(StarterPack.DATABASE_SOURCE), ev.prettify());
        JsonPath deleted = admin().get("/api/v1/audit?action=" + AuditLog.SOURCE_DELETED + "&limit=10").then()
            .statusCode(200).extract().jsonPath();
        assertTrue(deleted.getBoolean("events.find { it.target == '" + StarterPack.DATABASE_SOURCE
            + "' }.detail.marked"), deleted.prettify());
        admin().get("/api/v1/starter-pack").then().body("on", equalTo(false));
        for (String s : List.of(StarterPack.DATABASE_SOURCE, StarterPack.FILES_SOURCE)) {
            admin().get("/api/v1/sources/" + s).then().statusCode(404);
        }
        assertTrue(r.getString("steps.find { it.step == 'sources' }.detail").contains("sample-data"), r.prettify());
        admin().get("/api/v1/sources/" + Samples.SAMPLE_DATA).then().statusCode(200);
        admin().delete("/api/v1/flows/own-checks-the-sample-data").then().statusCode(204);
        admin().delete("/api/v1/sources/" + Samples.SAMPLE_DATA).then().statusCode(200);
        assertEquals(List.of(), admin().get("/api/v1/pipelines").then().statusCode(200).extract().jsonPath()
            .getList("findAll { it.library == 'sample' }.name"));
        assertEquals(List.of(), admin().get("/api/v1/flows").then().statusCode(200).extract().jsonPath()
            .getList("findAll { it.library == 'sample' }.name"));
        assertEquals(0, tables(), "kilnmark_samples is empty");
        assertFalse(Files.exists(folder()), "the pack's folder is gone");
        admin().get("/api/v1/variables/" + Samples.REGION_VARIABLE).then().statusCode(404);

        // again: nothing left to remove, nothing fails
        JsonPath again = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE"))
            .delete("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), again.getList("steps.findAll { it.outcome == 'failed' }"), again.prettify());
    }

    @Test
    @Order(7)
    void aSourceOfThePacksNameThatIsNotThePacksIsNeverChangedOrDeleted() throws Exception {
        Path mine = Files.createDirectories(Path.of(roots.split(",")[0].strip()).resolve("mine"));
        admin().contentType(ContentType.JSON).body(Map.of("name", StarterPack.FILES_SOURCE, "kind", "files", "settings",
            Map.of("path", mine.toString().replace('\\', '/')))).post("/api/v1/sources").then().statusCode(201);
        JsonPath on = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals("failed", on.getString("steps.find { it.step == 'files source' }.outcome"), on.prettify());
        assertTrue(on.getString("steps.find { it.step == 'files source' }.detail").contains("did not make"), on.prettify());
        JsonPath src = admin().get("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(200).extract().jsonPath();
        assertEquals(null, src.getString("settings.allow_writing"), "not repointed, not made writable");
        assertTrue(src.getString("settings.path").endsWith("/mine"), src.prettify());

        JsonPath off = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE")).delete("/api/v1/starter-pack")
            .then().statusCode(200).extract().jsonPath();
        assertTrue(off.getString("steps.find { it.step == 'sources' }.detail").contains(StarterPack.FILES_SOURCE), off.prettify());
        admin().get("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(200);
        admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(404);
        admin().delete("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(200);
    }

    @Test
    @Order(8)
    void aFolderOfThePacksNameThatIsNotThePacksIsNeverAdoptedOrDeleted() throws Exception {
        // KILNMARK_FILES_DIR can be a real share: a site's own folder starter-pack is left alone
        Path own = Files.createDirectories(folder());
        Files.writeString(own.resolve("keep.txt"), "the site's own");
        JsonPath on = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals("failed", on.getString("steps.find { it.step == 'files source' }.outcome"), on.prettify());
        assertTrue(on.getString("steps.find { it.step == 'files source' }.detail").contains("did not make"), on.prettify());
        JsonPath off = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE")).delete("/api/v1/starter-pack")
            .then().statusCode(200).extract().jsonPath();
        assertTrue(off.getString("steps.find { it.step == 'sample files' }.detail").contains("not the pack's"), off.prettify());
        assertEquals("the site's own", Files.readString(own.resolve("keep.txt")), "never deleted");
        Files.delete(own.resolve("keep.txt"));
        Files.delete(own);
    }

    @Test
    @Order(9)
    void whatTheControlScriptDidToTheExamplePeopleIsInTheAuditLog() throws Exception {
        int before = events(AuditLog.STARTER_PEOPLE);
        admin().contentType(ContentType.JSON).body(Map.of("action", "remove", "people",
            List.of("vera.lane@kilnmark.com", "alex.morgan@kilnmark.com"))).post("/api/v1/starter-pack/people").then()
            .statusCode(204);
        eventually(AuditLog.STARTER_PEOPLE, before + 1);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PEOPLE + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals("remove", ev.getString("events[0].detail.action"));
        assertEquals(List.of("vera.lane@kilnmark.com", "alex.morgan@kilnmark.com"), ev.getList("events[0].detail.people"));
        // only the five example addresses, only the four actions, only admins
        admin().contentType(ContentType.JSON).body(Map.of("action", "add", "people", List.of("rita@bank.example")))
            .post("/api/v1/starter-pack/people").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("action", "promote", "people", List.of("vera.lane@kilnmark.com")))
            .post("/api/v1/starter-pack/people").then().statusCode(400);
        Tokens.as(Tokens.newToken("designer", false)).contentType(ContentType.JSON).body(Map.of("action", "add",
            "people", List.of("vera.lane@kilnmark.com"))).post("/api/v1/starter-pack/people").then().statusCode(403);
    }

    @AfterAll
    static void dropTheDatabase() throws Exception {
        try (Connection c = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class).get()
            .getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS kilnmark_samples WITH (FORCE)");
        } catch (RuntimeException e) {
            // the container is gone with the test's own database
        }
    }
}
