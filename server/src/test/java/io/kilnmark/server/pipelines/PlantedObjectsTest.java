package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlDialect;
import io.kilnmark.server.Tokens;

import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleDraft;
import io.kilnmark.server.quality.SqlRules;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * The fifth review: another database user who may create objects in a schema on the source login's
 * search path ({@code public}, by default on PostgreSQL 14 and older) plants functions and operators
 * that match a name in the SQL Kilnmark writes better than PostgreSQL's own: an {@code =} for
 * {@code oid} and {@code regclass}, an {@code =} for {@code name} and {@code varchar}, an
 * {@code rtrim(varchar)}. Every connection Kilnmark opens to a PostgreSQL source resolves names in
 * {@code pg_catalog} alone, so a write still refuses to replace a table with row-level security, an
 * extension's table is still refused, and a library rule counts with the built-ins. Everything
 * happens in a database of its own, dropped afterwards.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlantedObjectsTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static final String DB = "kilnmark_planted";
    private static String cleanUrl;
    private static String cleanUser;
    private static String cleanPassword;

    private String urlOf(String database) {
        int slash = url.indexOf('/', "jdbc:postgresql://".length());
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + database + (query < 0 ? "" : url.substring(query));
    }

    private void sql(String database, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(database == null ? url : urlOf(database), user, password);
             Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private SourceConfig source() {
        return new SourceConfig("planted-db", "postgresql", Map.of("url", urlOf(DB), "username", user,
            "schemas", "plant"), password);
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        cleanUrl = url;
        cleanUser = user;
        cleanPassword = password;
        sql(null, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)", "CREATE DATABASE " + DB);
        sql(DB, "CREATE SCHEMA plant",
            "CREATE TABLE plant.t (v varchar(20))", "INSERT INTO plant.t VALUES (NULL), ('   '), ('a')",
            "CREATE TABLE plant.secured (id integer, name text)", "INSERT INTO plant.secured VALUES (1, 'kept')",
            "ALTER TABLE plant.secured ENABLE ROW LEVEL SECURITY",
            "CREATE EXTENSION IF NOT EXISTS pg_buffercache SCHEMA plant",
            // What another database user plants in public: each an exact match where the built-in needs a cast.
            "CREATE FUNCTION public.planted_eq(oid, regclass) RETURNS boolean LANGUAGE sql AS 'SELECT false'",
            "CREATE OPERATOR public.= (LEFTARG = oid, RIGHTARG = regclass, FUNCTION = public.planted_eq)",
            "CREATE FUNCTION public.rtrim(varchar) RETURNS text LANGUAGE sql AS 'SELECT ''x''::text'",
            "CREATE FUNCTION public.quote_ident(name) RETURNS text LANGUAGE sql AS 'SELECT NULL::text'");
        // The plants bite on a plain connection: the reason the source's connections resolve in pg_catalog alone
        try (Connection c = DriverManager.getConnection(urlOf(DB), user, password); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT c.relrowsecurity FROM pg_class c WHERE c.oid = "
                 + "pg_catalog.to_regclass('plant.secured')")) {
            assertTrue(!rs.next(), "the planted = answers the plain connection's lookup");
        }
    }

    /** A library rule counts blank values as missing with PostgreSQL's own rtrim, not the planted one. */
    @Test
    @Order(5)
    void aLibraryRuleCountsWithTheBuiltIns() throws Exception {
        Rule rule = new RuleDraft("urn:kilnmark:planted-db:plant.t", "v", null, "not_null", null,
            new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"blankIsMissing\": true}"), null, null, null, null,
            null)
            .check(new RuleDraftContext(), "tester");
        SqlRules.Plan plan = SqlRules.plan(rule, SqlDialect.POSTGRESQL, new SqlRules.Table("plant", "t"),
            Map.of("v", "text"), u -> null);
        try (Connection c = new PostgresConnector().connect(source()); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(plan.countSql())) {
            rs.next();
            assertEquals(3, rs.getLong(1));
            assertEquals(2, rs.getLong(2), "the empty value and the blank one are missing");
        }
    }

    /** An extension's table is refused though the planted = would hide it from the lookup. */
    @Test
    @Order(6)
    void anExtensionsTableIsStillRefused() throws Exception {
        sql(DB, "CREATE FUNCTION public.planted_name_eq(name, varchar) RETURNS boolean LANGUAGE sql AS 'SELECT false'",
            "CREATE OPERATOR public.= (LEFTARG = name, RIGHTARG = varchar, FUNCTION = public.planted_name_eq)");
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> new PostgresConnector().explore(source(), "plant.pg_buffercache", Duration.ofSeconds(30)));
        assertTrue(e.getMessage().contains("belongs to the database itself"), e.getMessage());
    }

    /** Replace still refuses a table with row-level security, and the table and its row are kept. */
    @Test
    @Order(2)
    void replaceStillRefusesATableWithRowSecurity() throws Exception {
        String designer = admin().contentType(ContentType.JSON).body(Map.of("name", "planted-designer", "role",
            "designer")).post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        admin().contentType(ContentType.JSON).body(Map.of("name", "planted-mem", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "3"), "password", "planted-mem-secret")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "planted-db", "kind", "postgresql", "password",
            password, "settings", Map.of("url", urlOf(DB), "username", user, "schemas", "plant",
                "allow_writing", "true"))).post("/api/v1/sources").then().statusCode(201);
        long scan = Tokens.as(designer).contentType(ContentType.JSON).post("/api/v1/sources/planted-mem/scans").then()
            .statusCode(202).extract().jsonPath().getLong("id");
        String status = "queued";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && !List.of("succeeded", "failed").contains(status)) {
            Thread.sleep(250);
            status = Tokens.as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", status);
        String yaml = """
            kilnmark: pipeline/v1
            name: planted-replace
            nodes:
              - id: people
                type: read
                source: planted-mem
                asset: lab.people
              - id: out
                type: write
                input: people
                source: planted-db
                table: plant.secured
                mode: replace
            """;
        Tokens.as(designer).contentType(ContentType.JSON).body(Map.of("name", "planted-replace", "yaml", yaml))
            .post("/api/v1/pipelines").then().statusCode(201);
        long id = Tokens.as(designer).contentType(ContentType.JSON).post("/api/v1/pipelines/planted-replace/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id");
        JsonPath run = null;
        deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            run = Tokens.as(designer).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (!List.of("queued", "running").contains(run.getString("run.status"))) {
                break;
            }
            Thread.sleep(250);
        }
        assertEquals("failed", run.getString("run.status"), run.prettify());
        assertTrue(run.prettify().contains("it has row-level security"), run.prettify());
        try (Connection c = DriverManager.getConnection(urlOf(DB), user, password); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM plant.secured")) {
            assertTrue(rs.next() && rs.getString(1).equals("kept"), "the table was not replaced");
        }
        admin().delete("/api/v1/pipelines/planted-replace");
        admin().delete("/api/v1/sources/planted-db");
        admin().delete("/api/v1/sources/planted-mem");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (cleanUrl == null) {
            return;
        }
        try (Connection c = DriverManager.getConnection(cleanUrl, cleanUser, cleanPassword);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** A source's context for checking a draft rule: PostgreSQL, SQL allowed. */
    private static final class RuleDraftContext implements RuleDraft.Context {
        @Override
        public Map<String, String> fields() {
            return Map.of("v", "text");
        }

        @Override
        public String sourceKind() {
            return "postgresql";
        }

        @Override
        public boolean sql() {
            return true;
        }

        @Override
        public java.util.Optional<Map<String, String>> fieldsOf(String urn) {
            return java.util.Optional.empty();
        }

        @Override
        public List<String> webhookAllowList() {
            return List.of();
        }

        @Override
        public boolean customSqlEnabled() {
            return true;
        }
    }
}
