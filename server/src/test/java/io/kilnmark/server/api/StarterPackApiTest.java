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
 * the banner's state says so for every role, and it is in the audit log; turned on again, nothing
 * is added twice; turned off (only once DELETE is typed), everything it added is gone and the
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
        makeSamplesDatabase(db);
        String viewer = Tokens.newToken("viewer", false);
        Tokens.as(viewer).get("/api/v1/starter-pack").then().statusCode(200).body("on", equalTo(false));
        Tokens.as(viewer).get("/api/v1/starter-pack/details").then().statusCode(403);
        Tokens.as(Tokens.newToken("designer", true)).post("/api/v1/starter-pack").then().statusCode(403);
        admin().contentType(ContentType.JSON).body(Map.of("confirm", "delete")).delete("/api/v1/starter-pack").then()
            .statusCode(400).body("error", containsString("type DELETE"));
        JsonPath d = admin().get("/api/v1/starter-pack/details").then().statusCode(200).extract().jsonPath();
        assertTrue(d.getBoolean("ready"), d.prettify());
        assertFalse(d.getBoolean("production"));
        assertFalse(d.getBoolean("people.allowed"), "no KILNMARK_STARTER=allowed here");
    }

    @Test
    @Order(2)
    void turnedOnEverySampleIsThereAndRuns() throws Exception {
        int added = events(AuditLog.STARTER_PACK_ADDED);
        JsonPath r = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertTrue(r.getBoolean("on"), r.prettify());
        for (String step : List.of("database", "database source", "files source", "sample tables", "samples")) {
            assertEquals("done", r.getString("steps.find { it.step == '" + step + "' }.outcome"), step + ": " + r.prettify());
        }
        assertEquals("skipped", r.getString("steps.find { it.step == 'example people' }.outcome"));
        assertEquals(null, r.getString("peoplePassword"), "no people, no password");
        Tokens.as(Tokens.newToken("viewer", false)).get("/api/v1/starter-pack").then().statusCode(200)
            .body("on", equalTo(true));
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

    @Test
    @Order(3)
    void turnedOnAgainNothingIsAddedTwice() {
        JsonPath r = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertTrue(r.getString("steps.find { it.step == 'sample tables' }.detail").startsWith("already there"), r.prettify());
        assertTrue(r.getString("steps.find { it.step == 'samples' }.detail").contains("already there"), r.prettify());
        assertFalse(r.getString("steps.find { it.step == 'samples' }.detail").matches("(?s).*[0-9]+ added.*"), r.prettify());
    }

    @Test
    @Order(4)
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
    @Order(5)
    void turnedOffEverythingItAddedIsGoneAndTheDatabaseStaysEmpty() throws Exception {
        assertTrue(tables() >= Samples.TABLES.size());
        int removed = events(AuditLog.STARTER_PACK_REMOVED);
        JsonPath r = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE")).delete("/api/v1/starter-pack")
            .then().statusCode(200).extract().jsonPath();
        assertFalse(r.getBoolean("on"), r.prettify());
        assertEquals(List.of(), r.getList("steps.findAll { it.outcome == 'failed' }"), r.prettify());
        eventually(AuditLog.STARTER_PACK_REMOVED, removed + 1);
        admin().get("/api/v1/starter-pack").then().body("on", equalTo(false));
        for (String s : List.of(StarterPack.DATABASE_SOURCE, StarterPack.FILES_SOURCE, Samples.SAMPLE_DATA)) {
            admin().get("/api/v1/sources/" + s).then().statusCode(404);
        }
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
