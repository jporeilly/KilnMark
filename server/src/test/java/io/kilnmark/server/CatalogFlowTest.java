package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * The v0.0.2 loop end to end: register a PostgreSQL source, scan it (queued, then run by this
 * process's worker), search, open an asset.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogFlowTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static boolean loaded;

    @BeforeEach
    void loadShop() throws Exception {
        if (loaded) {
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA shop");
            s.execute("""
                CREATE TABLE shop.customers (
                    customer_id integer PRIMARY KEY,
                    email text,
                    segment text NOT NULL)""");
            s.execute("COMMENT ON TABLE shop.customers IS 'People who buy from the shop'");
            s.execute("""
                INSERT INTO shop.customers
                SELECT n, 'buyer' || n || '@example.com', (ARRAY['Retail','Trade'])[1 + n % 2]
                FROM generate_series(1, 5000) n""");
            s.execute("""
                CREATE TABLE shop.order_lines (
                    order_id bigint, line integer, customer_id integer, amount numeric(10,2),
                    ordered_at timestamptz, PRIMARY KEY (order_id, line))""");
            s.execute("""
                INSERT INTO shop.order_lines
                SELECT n / 3, n % 3, 1 + n % 5000, (n % 500) + 0.99, timestamptz '2025-01-01' + n * interval '1 hour'
                FROM generate_series(1, 30000) n""");
            s.execute("CREATE VIEW shop.trade_customers AS SELECT * FROM shop.customers WHERE segment = 'Trade'");
        }
        loaded = true;
    }

    private Map<String, Object> source(String name, String pw) {
        return Map.of("name", name, "kind", "postgresql", "password", pw,
            "settings", Map.of("url", url, "username", user, "schemas", "shop"));
    }

    private JsonPath waitForRun(long id) throws InterruptedException {
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
    @Order(1)
    void registersAPostgresSourceWithItsPasswordEncrypted() throws Exception {
        admin().contentType(ContentType.JSON).body(source("shop-db", password))
            .when().post("/api/v1/sources")
            .then().statusCode(201)
            .body("name", equalTo("shop-db"))
            .body("settings.schemas", equalTo("shop"))
            .body("hasPassword", equalTo(true))
            .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("password")));
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT secret FROM kilnmark.data_source WHERE name = 'shop-db'")) {
            assertTrue(rs.next());
            String stored = new String(rs.getBytes(1), StandardCharsets.ISO_8859_1);
            assertFalse(stored.contains(password), "the password is not stored as written");
        }
    }

    @Test
    @Order(2)
    void scansProfilesAndRecordsEveryTableAndView() throws Exception {
        long runId = admin().post("/api/v1/sources/shop-db/scans")
            .then().statusCode(202).body("status", notNullValue())
            .extract().jsonPath().getLong("id");
        JsonPath run = waitForRun(runId);
        assertEquals("succeeded", run.getString("status"), run.getString("detail"));
        assertEquals(3, run.getInt("assetsSeen"));
        assertEquals(5000 + 30000 + 2500, run.getLong("rowsRead"));

        admin().get("/api/v1/sources/shop-db").then().statusCode(200)
            .body("lastRun.id", equalTo((int) runId));
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT status, attempts, locked_by FROM kilnmark.job "
                 + "WHERE kind = 'scan' AND (payload ->> 'runId')::bigint = " + runId)) {
            assertTrue(rs.next(), "the scan went through the job queue");
            assertEquals("done", rs.getString(1));
            assertEquals(1, rs.getInt(2));
            assertTrue(rs.getString(3) != null && !rs.getString(3).isBlank(), "the worker that ran it is recorded");
        }
    }

    @Test
    @Order(3)
    void findsAssetsByNameAndByFieldName() {
        admin().queryParam("q", "order lines").get("/api/v1/search")
            .then().statusCode(200)
            .body("hits[0].urn", equalTo("urn:kilnmark:shop-db:shop.order_lines"))
            .body("hits[0].rowCount", equalTo(30000));
        admin().queryParam("q", "email").get("/api/v1/search")
            .then().statusCode(200)
            .body("hits.urn", hasItem("urn:kilnmark:shop-db:shop.customers"))
            .body("hits[0].matchedFields", hasItem("email"));
        admin().queryParam("q", "custmers").get("/api/v1/search") // misspelt
            .then().statusCode(200)
            .body("hits.urn", hasItem("urn:kilnmark:shop-db:shop.customers"));
    }

    @Test
    @Order(4)
    void anAssetPageHasFieldsAndTheirProfiles() {
        JsonPath a = admin().get("/api/v1/assets/urn:kilnmark:shop-db:shop.customers")
            .then().statusCode(200).extract().jsonPath();
        assertEquals("People who buy from the shop", a.getString("description"));
        assertEquals(5000, a.getLong("rowCount"));
        assertTrue(a.getBoolean("rowCountComplete"));
        assertEquals(java.util.List.of("customer_id"), a.getList("primaryKey"));
        assertEquals("email", a.getString("fields[1].name"));
        assertEquals(0, a.getInt("fields[1].profile.nulls"));
        assertTrue(Math.abs(a.getInt("fields[1].profile.distinct") - 5000) < 150);
        assertEquals(java.util.Set.of("Retail", "Trade"), java.util.Set.copyOf(a.getList("fields[2].profile.top.value")));
        assertEquals(2500, a.getInt("fields[2].profile.top[0].count"));

        JsonPath view = admin().get("/api/v1/assets/urn:kilnmark:shop-db:shop.trade_customers")
            .then().statusCode(200).extract().jsonPath();
        assertEquals("view", view.getString("kind"));
        assertEquals(2500, view.getLong("rowCount"));
    }

    @Test
    @Order(5)
    void aWrongPasswordEndsTheRunWithAReason() throws Exception {
        admin().contentType(ContentType.JSON).body(source("shop-bad", "not-the-password"))
            .post("/api/v1/sources").then().statusCode(201);
        long runId = admin().post("/api/v1/sources/shop-bad/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        JsonPath run = waitForRun(runId);
        assertEquals("failed", run.getString("status"));
        assertTrue(run.getString("detail").contains("Cannot read the catalogue of shop-bad"), run.getString("detail"));
    }

    @Test
    @Order(6)
    void rejectsBadRequestsClearly() {
        admin().contentType(ContentType.JSON).body(Map.of("name", "Bad Name", "settings", Map.of("url", url)))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("name"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "odd", "kind", "teradata"))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("unknown kind"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "no-url"))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("url: required"));
        admin().get("/api/v1/connectors").then().statusCode(200)
            .body("kind", hasItem("postgresql"))
            .body("find { it.kind == 'postgresql' }.settings.key", hasItem("url"));
        admin().contentType(ContentType.JSON).body(source("shop-db", password))
            .post("/api/v1/sources").then().statusCode(409).body("error", containsString("already exists"));
        admin().get("/api/v1/assets/urn:kilnmark:shop-db:shop.nothing").then().statusCode(404);
        admin().get("/api/v1/search").then().statusCode(400);
        admin().post("/api/v1/sources/nope/scans").then().statusCode(404);
    }
}
