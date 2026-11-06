package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/**
 * Managing sources through the API: edit, delete, test connections, list containers, run history,
 * common settings, roles, and the readiness check.
 */
@QuarkusTest
class SourceManagementTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static String designer;
    private static String viewer;

    @BeforeEach
    void setUp() throws Exception {
        if (designer != null) {
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA mgmt_a");
            s.execute("CREATE TABLE mgmt_a.customers (id integer PRIMARY KEY, name text)");
            s.execute("CREATE TABLE mgmt_a.customers_backup (id integer, name text)");
            s.execute("CREATE SCHEMA mgmt_b");
            s.execute("CREATE TABLE mgmt_b.orders (id integer PRIMARY KEY, amount numeric(8,2))");
            s.execute("INSERT INTO mgmt_b.orders SELECT n, n * 1.5 FROM generate_series(1, 200) n");
            s.execute("CREATE VIEW mgmt_b.big_orders AS SELECT * FROM mgmt_b.orders WHERE amount > 100");
            s.execute("CREATE SCHEMA mgmt_empty");
        }
        designer = token("mgmt-designer", "designer");
        viewer = token("mgmt-viewer", "viewer");
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static RequestSpecification designer() {
        return Tokens.as(designer);
    }

    private static RequestSpecification viewer() {
        return Tokens.as(viewer);
    }

    private Map<String, Object> source(String name, String pw, String schemas) {
        Map<String, String> settings = new HashMap<>(Map.of("url", url, "username", user));
        if (schemas != null) {
            settings.put("schemas", schemas);
        }
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "postgresql", "settings", settings));
        if (pw != null) {
            body.put("password", pw);
        }
        return body;
    }

    private void add(String name, String pw, String schemas) {
        admin().contentType(ContentType.JSON).body(source(name, pw, schemas)).post("/api/v1/sources")
            .then().statusCode(201);
    }

    private byte[] storedSecret(String name) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT secret FROM kilnmark.data_source WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getBytes(1);
            }
        }
    }

    private static JsonPath waitForRun(long id) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            JsonPath run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            String status = run.getString("status");
            if (!status.equals("queued") && !status.equals("running")) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    @Test
    void viewersCannotChangeTestOrListSources() {
        add("mgmt-roles", password, "mgmt_a");
        Map<String, Object> edit = Map.of("settings", Map.of("url", url));
        viewer().contentType(ContentType.JSON).body(edit).put("/api/v1/sources/mgmt-roles").then().statusCode(403);
        viewer().delete("/api/v1/sources/mgmt-roles").then().statusCode(403);
        viewer().post("/api/v1/sources/mgmt-roles/test").then().statusCode(403);
        viewer().get("/api/v1/sources/mgmt-roles/containers").then().statusCode(403);
        viewer().contentType(ContentType.JSON).body(source("x", null, null)).post("/api/v1/drafts/test")
            .then().statusCode(403);
        viewer().contentType(ContentType.JSON).body(source("x", null, null)).post("/api/v1/drafts/containers")
            .then().statusCode(403);
        viewer().get("/api/v1/imports/1").then().statusCode(403);
        designer().get("/api/v1/imports/1").then().statusCode(org.hamcrest.Matchers.not(403)); // designers may read imports
        given().delete("/api/v1/sources/mgmt-roles").then().statusCode(401);
        // viewers read
        viewer().get("/api/v1/sources/mgmt-roles/history").then().statusCode(200);
        viewer().get("/api/v1/common-settings").then().statusCode(200)
            .body("key", hasItems("description", "ai_egress", "include", "exclude"))
            .body("find { it.key == 'ai_egress' }.choices", hasItems("local_only", "allowed", "none"))
            .body("find { it.key == 'ai_egress' }.defaultValue", equalTo("local_only"));
        admin().get("/api/v1/sources/mgmt-roles").then().statusCode(200);
    }

    @Test
    void aStewardEditsSettingsAndTheStoredPasswordIsKept() throws Exception {
        add("mgmt-edit", password, "mgmt_a");
        byte[] before = storedSecret("mgmt-edit");
        Map<String, Object> body = Map.of("displayName", "Edit test DB", "settings", Map.of("url", url, "username",
            user, "schemas", "mgmt_a,mgmt_b", "description", "Synthetic test data", "ai_egress", "ALLOWED",
            "exclude", "*_backup"));
        designer().contentType(ContentType.JSON).body(body).put("/api/v1/sources/mgmt-edit")
            .then().statusCode(200)
            .body("name", equalTo("mgmt-edit"))
            .body("displayName", equalTo("Edit test DB"))
            .body("hasPassword", equalTo(true))
            .body("settings.ai_egress", equalTo("allowed"))
            .body("settings.schemas", equalTo("mgmt_a,mgmt_b"))
            .body("settings.description", equalTo("Synthetic test data"))
            .body("$", not(hasKey("password")))
            .body("settings", not(hasKey("password")));
        assertArrayEquals(before, storedSecret("mgmt-edit"), "a blank password keeps the stored one");
        designer().post("/api/v1/sources/mgmt-edit/test").then().statusCode(200).body("ok", equalTo(true));

        // leaving a setting out removes it; display name back to the name
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", user)))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(200)
            .body("displayName", equalTo("mgmt-edit"))
            .body("settings", not(hasKey("description")));

        // a new place for the password needs the password again
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url",
                "jdbc:postgresql://attacker.example.com:5432/x", "username", user)))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400)
            .body("error", containsString("enter the password again"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", "someone")))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400).body("error", containsString("username"));
        assertArrayEquals(before, storedSecret("mgmt-edit"));

        // the name is a fixed key
        designer().contentType(ContentType.JSON).body(Map.of("name", "mgmt-renamed", "settings", Map.of("url", url)))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400)
            .body("error", containsString("cannot change"))
            .body("error", containsString("add a new source"));
        designer().contentType(ContentType.JSON).body(Map.of("kind", "files", "settings", Map.of("url", url)))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400).body("error", containsString("kind"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", user, "ai_egress", "cloud")))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400).body("error", containsString("ai_egress"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", user, "include", "*.{x")))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400).body("error", containsString("include"));
        designer().contentType(ContentType.JSON).body(Map.of("displayName", "two\nlines", "settings", Map.of("url", url)))
            .put("/api/v1/sources/mgmt-edit").then().statusCode(400).body("error", containsString("displayName"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url)))
            .put("/api/v1/sources/no-such-source").then().statusCode(404);

        // a new password replaces it; clearing removes it
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", user),
            "password", "changed-pw")).put("/api/v1/sources/mgmt-edit").then().statusCode(200);
        assertFalse(java.util.Arrays.equals(before, storedSecret("mgmt-edit")));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url, "username", user),
            "clearPassword", true)).put("/api/v1/sources/mgmt-edit").then().statusCode(200)
            .body("hasPassword", equalTo(false));
    }

    @Test
    void connectionTestsSayWhatIsWrongAndNeverEchoTheSecret() {
        add("mgmt-test-ok", password, null);
        add("mgmt-test-bad", "wrong-password-8813", null);
        designer().post("/api/v1/sources/mgmt-test-ok/test").then().statusCode(200)
            .body("ok", equalTo(true)).body("problem", equalTo(null));
        String problem = designer().post("/api/v1/sources/mgmt-test-bad/test").then().statusCode(200)
            .body("ok", equalTo(false)).extract().jsonPath().getString("problem");
        assertTrue(problem.contains("Cannot connect"), problem);
        assertFalse(problem.contains("wrong-password-8813"));

        // unsaved settings: a new source, then an edit with a blank password using the stored one
        designer().contentType(ContentType.JSON).body(source("", password, null)).post("/api/v1/drafts/test")
            .then().statusCode(200).body("ok", equalTo(true));
        designer().contentType(ContentType.JSON).body(source("", null, null)).post("/api/v1/drafts/test")
            .then().statusCode(200).body("ok", equalTo(false));
        Map<String, Object> edit = new HashMap<>(source("mgmt-test-ok", null, null));
        edit.put("source", "mgmt-test-ok");
        designer().contentType(ContentType.JSON).body(edit).post("/api/v1/drafts/test")
            .then().statusCode(200).body("ok", equalTo(true));
        Map<String, Object> moved = new HashMap<>(edit);
        moved.put("settings", Map.of("url", "jdbc:postgresql://elsewhere.example.com/x", "username", user));
        designer().contentType(ContentType.JSON).body(moved).post("/api/v1/drafts/test")
            .then().statusCode(400).body("error", containsString("enter the password again"));
        designer().contentType(ContentType.JSON).body(Map.of("kind", "postgresql", "settings", Map.of("url", "nope")))
            .post("/api/v1/drafts/test").then().statusCode(400).body("error", containsString("url"));
        designer().post("/api/v1/sources/no-such-source/test").then().statusCode(404);
    }

    @Test
    void listsSchemasWithCountsAndWarnsBeforeScanningNothing() {
        add("mgmt-list", password, "mgmt_a");
        JsonPath l = designer().get("/api/v1/sources/mgmt-list/containers").then().statusCode(200)
            .body("setting", equalTo("schemas"))
            .body("label", equalTo("Schemas"))
            .body("selected", equalTo(List.of("mgmt_a")))
            .body("nothingToScan", equalTo(false))
            .extract().jsonPath();
        assertEquals(2, l.getInt("containers.find { it.name == 'mgmt_a' }.count"));
        assertEquals(2, l.getInt("containers.find { it.name == 'mgmt_b' }.count"), "views count");
        assertEquals(0, l.getInt("containers.find { it.name == 'mgmt_empty' }.count"));
        assertTrue(l.getBoolean("containers.find { it.name == 'mgmt_a' }.selected"));
        assertFalse(l.getList("containers.name").contains("pg_catalog"), "system schemas are left out");
        assertFalse(l.getList("containers.name").contains("information_schema"));
        assertEquals(2, l.getInt("total"));

        add("mgmt-nothing", password, "mgmt_empty");
        designer().get("/api/v1/sources/mgmt-nothing/containers").then().statusCode(200)
            .body("total", equalTo(0))
            .body("nothingToScan", equalTo(true))
            .body("warnings[0]", containsString("nothing"));

        // unsaved settings, with patterns
        Map<String, Object> draft = source("", password, "mgmt_a");
        @SuppressWarnings("unchecked")
        Map<String, String> settings = new HashMap<>((Map<String, String>) draft.get("settings"));
        settings.put("exclude", "*_BACKUP");
        draft.put("settings", settings);
        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/containers")
            .then().statusCode(200).body("total", equalTo(1));

        designer().contentType(ContentType.JSON)
            .body(Map.of("name", "", "kind", "postgresql", "password", "x",
                "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:1/none", "username", "u")))
            .post("/api/v1/drafts/containers").then().statusCode(502).body("error", containsString("Cannot list"));
    }

    @Test
    void deleteIsRefusedWhileAScanIsActiveThenRemovesEverything() throws Exception {
        add("mgmt-del", password, "mgmt_b");
        long runId = admin().post("/api/v1/sources/mgmt-del/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        assertEquals("succeeded", waitForRun(runId).getString("status"));
        long sourceId;
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT id FROM kilnmark.data_source WHERE name = 'mgmt-del'")) {
                rs.next();
                sourceId = rs.getLong(1);
            }
            assertTrue(count(c, "SELECT count(*) FROM kilnmark.profile WHERE asset_urn LIKE 'urn:kilnmark:mgmt-del:%'")
                > 0, "the scan recorded profiles");
            cypher(c, "CREATE (:Asset {source: 'mgmt-del', urn: 'urn:kilnmark:mgmt-del:mgmt_b.orders'})");
            cypher(c, "CREATE (:Asset {source: 'mgmt-keep', urn: 'urn:kilnmark:mgmt-keep:x.y'})");
            // a scan in progress, without a job, so no worker picks it up
            s.execute("INSERT INTO kilnmark.run (source_id, kind, status) VALUES (" + sourceId + ", 'scan', 'running')");
        }
        admin().get("/api/v1/sources/mgmt-del").then().statusCode(200).body("assets", equalTo(2));
        designer().delete("/api/v1/sources/mgmt-del").then().statusCode(409)
            .body("error", containsString("queued or running"));
        admin().get("/api/v1/sources/mgmt-del").then().statusCode(200);

        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE kilnmark.run SET status = 'failed' WHERE source_id = " + sourceId
                + " AND status = 'running'");
        }
        designer().delete("/api/v1/sources/mgmt-del").then().statusCode(200)
            .body("name", equalTo("mgmt-del")).body("assets", equalTo(2)).body("runs", equalTo(2));
        admin().get("/api/v1/sources/mgmt-del").then().statusCode(404);
        admin().get("/api/v1/assets/urn:kilnmark:mgmt-del:mgmt_b.orders").then().statusCode(404);
        designer().delete("/api/v1/sources/mgmt-del").then().statusCode(404);

        try (Connection c = db.getConnection()) {
            assertEquals(List.of(), leftovers(c, "urn:kilnmark:mgmt-del:", sourceId),
                "every table that refers to the source or its assets is cleared (ON DELETE CASCADE)");
            assertEquals(0, count(c, "SELECT count(*) FROM kilnmark.job WHERE (payload ->> 'sourceId')::bigint = "
                + sourceId));
            assertEquals(0, count(c, "SELECT count(*) FROM kilnmark.run WHERE id = " + runId));
            assertEquals(0, graphCount(c, "mgmt-del"), "graph nodes go too");
            assertEquals(1, graphCount(c, "mgmt-keep"), "other sources' nodes stay");
        }
    }

    /**
     * Rows that still refer to a deleted source: in any kilnmark table, a text column whose name
     * ends in "urn" holding one of its URNs, or a column with a foreign key to data_source holding
     * its id. Written generally, so a table added later that forgets its cascade fails this test.
     */
    private static List<String> leftovers(Connection c, String urnPrefix, long sourceId) throws Exception {
        List<String> found = new ArrayList<>();
        List<String[]> urnColumns = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
            SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'kilnmark' AND column_name LIKE '%urn' AND data_type = 'text'""")) {
            while (rs.next()) {
                urnColumns.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }
        assertTrue(urnColumns.stream().anyMatch(t -> t[0].equals("profile")), "the check sees the known tables");
        for (String[] t : urnColumns) {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kilnmark.\"" + t[0] + "\" WHERE \""
                + t[1] + "\" LIKE ?")) {
                ps.setString(1, urnPrefix + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        found.add(t[0] + "." + t[1]);
                    }
                }
            }
        }
        List<String[]> sourceColumns = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
            SELECT cl.relname, a.attname
            FROM pg_constraint k
            JOIN pg_class cl ON cl.oid = k.conrelid
            JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
            WHERE k.contype = 'f' AND k.confrelid = 'kilnmark.data_source'::regclass""")) {
            while (rs.next()) {
                sourceColumns.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }
        for (String[] t : sourceColumns) {
            if (count(c, "SELECT count(*) FROM kilnmark.\"" + t[0] + "\" WHERE \"" + t[1] + "\" = " + sourceId) > 0) {
                found.add(t[0] + "." + t[1]);
            }
        }
        return found;
    }

    private static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void cypher(Connection c, String statement) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path = ag_catalog, kilnmark, public");
            s.execute("SELECT * FROM ag_catalog.cypher('kilnmark_graph', $$ " + statement
                + " $$) AS (v ag_catalog.agtype)");
            s.execute("SET search_path = kilnmark, public");
        }
    }

    private static long graphCount(Connection c, String source) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path = ag_catalog, kilnmark, public");
            try (ResultSet rs = s.executeQuery("SELECT * FROM ag_catalog.cypher('kilnmark_graph', $$ MATCH (n) "
                + "WHERE n.source = '" + source + "' RETURN count(n) $$) AS (n ag_catalog.agtype)")) {
                rs.next();
                return Long.parseLong(rs.getString(1));
            } finally {
                s.execute("SET search_path = kilnmark, public");
            }
        }
    }

    @Test
    void historyShowsRunsWithTheirJobs() throws Exception {
        add("mgmt-history", password, "mgmt_b");
        long runId = designer().post("/api/v1/sources/mgmt-history/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        waitForRun(runId);
        viewer().get("/api/v1/sources/mgmt-history/history").then().statusCode(200)
            .body("[0].run.id", equalTo((int) runId))
            .body("[0].run.status", equalTo("succeeded"))
            .body("[0].run.assetsSeen", equalTo(2))
            .body("[0].attempts", equalTo(1));
        viewer().get("/api/v1/sources/no-such-source/history").then().statusCode(404);
    }

    @Test
    void connectorsSayWhichSettingHoldsTheChosenContainers() {
        viewer().get("/api/v1/connectors").then().statusCode(200)
            .body("find { it.kind == 'postgresql' }.containerSetting", equalTo("schemas"))
            .body("find { it.kind == 'files' }.containerSetting", equalTo("containers"))
            .body("find { it.kind == 'files' }.containerLabel", equalTo("Folders"))
            .body("find { it.kind == 'files' }.settings.key", hasItem("containers"))
            .body("find { it.kind == 'files' }.settings.key", not(hasItem("include")));
    }

    @Inject
    io.kilnmark.graph.store.GraphStore store;

    @Test
    void aSilentServerCannotHoldAChecksPlaceOrTheCallerForLong() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50,
            java.net.InetAddress.getLoopbackAddress())) {
            Map<String, Object> draft = Map.of("kind", "postgresql", "password", "pw",
                "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/x",
                    "username", "u"));
            var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            List<java.util.concurrent.Future<Long>> stuck = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                stuck.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/test")
                        .then().statusCode(200).body("ok", equalTo(false));
                    return (System.nanoTime() - start) / 1_000_000;
                }));
            }
            Thread.sleep(800);
            designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/test")
                .then().statusCode(429).body("error", containsString("Try again"));
            designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/containers")
                .then().statusCode(429);
            for (var f : stuck) {
                long ms = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(ms < 6000, "a check ends within about the 3 s limit, took " + ms + " ms");
            }
            pool.shutdown();
            // the places come back once the checks have really ended
            int status = 429;
            for (int i = 0; i < 40 && status == 429; i++) {
                status = designer().contentType(ContentType.JSON).body(source("", password, null))
                    .post("/api/v1/drafts/test").then().extract().statusCode();
                if (status == 429) {
                    Thread.sleep(250);
                }
            }
            assertEquals(200, status);
        }
    }

    @Test
    void movedSettingsNeedThePasswordAgainForListingsAndS3Too() {
        add("mgmt-moved", password, null);
        Map<String, Object> moved = new HashMap<>(Map.of("source", "mgmt-moved", "settings",
            Map.of("url", "jdbc:postgresql://elsewhere.example.com/x", "username", user)));
        designer().contentType(ContentType.JSON).body(moved).post("/api/v1/drafts/containers")
            .then().statusCode(400).body("error", containsString("enter the password again"));

        admin().contentType(ContentType.JSON).body(Map.of("name", "mgmt-s3", "kind", "s3", "password", "s3-secret",
                "settings", Map.of("endpoint", "http://store.example.com:9000", "bucket", "raw", "access_key_id", "AK")))
            .post("/api/v1/sources").then().statusCode(201);
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("endpoint", "http://evil.example.com",
                "bucket", "raw", "access_key_id", "AK")))
            .put("/api/v1/sources/mgmt-s3").then().statusCode(400).body("error", containsString("endpoint"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("endpoint",
                "http://store.example.com:9000", "bucket", "raw", "access_key_id", "OTHER")))
            .put("/api/v1/sources/mgmt-s3").then().statusCode(400).body("error", containsString("access_key_id"));
        designer().contentType(ContentType.JSON).body(Map.of("settings", Map.of("endpoint",
                "http://store.example.com:9000", "bucket", "curated", "access_key_id", "AK")))
            .put("/api/v1/sources/mgmt-s3").then().statusCode(200).body("hasPassword", equalTo(true));
    }

    @Test
    void anUnreachableSourcesErrorNeverHoldsItsSecret() {
        String secret = "pw-secret-7731";
        String error = designer().contentType(ContentType.JSON)
            .body(Map.of("kind", "postgresql", "password", secret,
                "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:1/none", "username", "u")))
            .post("/api/v1/drafts/containers").then().statusCode(502).extract().jsonPath().getString("error");
        assertFalse(error.contains(secret), error);
    }

    @Test
    void urlPropertiesThatUseTheServersIdentityAreRefused() {
        admin().contentType(ContentType.JSON).body(Map.of("name", "mgmt-sslkey", "kind", "postgresql",
                "settings", Map.of("url", url + (url.contains("?") ? "&" : "?") + "sslfactory=org.example.Evil")))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("sslfactory"))
            .body("error", containsString("identity"));
    }

    @Test
    void aChangeThatRacedAChangeOfWhereThePasswordGoesIsRefused() {
        add("mgmt-race", password, null);
        var s = store.source("mgmt-race").orElseThrow();
        var stale = new io.kilnmark.graph.store.Records.SourceChange(null, Map.of("url", url, "username", user,
            "description", "x"), false, null, Map.of("url", "jdbc:postgresql://what-it-was-before/x"));
        org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.graph.store.GraphStore.SourceChangedException.class,
            () -> store.updateSource(s.id(), stale));
        var fresh = new io.kilnmark.graph.store.Records.SourceChange(null, Map.of("url", url, "username", user,
            "description", "x"), false, null, Map.of("url", url, "username", user));
        assertEquals("x", store.updateSource(s.id(), fresh).orElseThrow().settings().get("description"));
    }

    @Test
    void deleteIsRefusedWhileAWorkerHoldsTheSourcesScanLock() throws Exception {
        add("mgmt-locked", password, null);
        long id = store.source("mgmt-locked").orElseThrow().id();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT pg_advisory_lock(" + 0x4B53 + ", " + id + ")");
            designer().delete("/api/v1/sources/mgmt-locked").then().statusCode(409);
            s.execute("SELECT pg_advisory_unlock(" + 0x4B53 + ", " + id + ")");
        }
        designer().delete("/api/v1/sources/mgmt-locked").then().statusCode(200);
        designer().post("/api/v1/sources/mgmt-locked/scans").then().statusCode(404);
    }

    @Test
    void readinessIsPublicAndSaysNoMoreThanUpOrDown() {
        String body = given().get("/q/health/ready").then().statusCode(200)
            .body("status", equalTo("UP"))
            .body("checks.name", hasItems("database", "migrations", "worker", "scheduler"))
            .extract().asString();
        assertFalse(body.contains("\"data\""), body);
        assertFalse(body.contains("jdbc"), body);
        given().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }
}
