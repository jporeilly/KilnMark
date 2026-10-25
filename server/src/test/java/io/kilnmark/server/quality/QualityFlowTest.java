package io.kilnmark.server.quality;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.sun.net.httpserver.HttpServer;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/**
 * Data quality end to end on PostgreSQL (dev services): patterns, samples, measures, rules run
 * in the database with awkward names, actions, previews, accepted patterns, masking, drift
 * between scans, exports validated against the published schemas, keep_samples off, and roles.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QualityFlowTest {

    static final String CUSTOMERS = "urn:kilnmark:quality-db:qa.Customers";
    static final String ORDERS = "urn:kilnmark:quality-db:qa.orders";
    static final String BIG = "urn:kilnmark:quality-db:qa.big";
    static final String ODD = "Odd \"Col\"";

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    static String viewer;
    static String steward;
    static boolean loaded;
    static HttpServer hooks;
    static final List<String> received = new CopyOnWriteArrayList<>();
    static final Map<String, Long> rules = new HashMap<>();
    static List<String> firstSampleIds;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startWebhookServer() throws IOException {
        hooks = HttpServer.create(new InetSocketAddress("127.0.0.1", 47391), 0);
        hooks.createContext("/hooks/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hooks.start();
    }

    @AfterAll
    static void stopWebhookServer() {
        hooks.stop(0);
    }

    private static RequestSpecification as(String token) {
        return Tokens.as(token);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private long count(String query) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @BeforeEach
    void load() throws Exception {
        if (loaded) {
            return;
        }
        sql("CREATE SCHEMA qa",
            """
            CREATE TABLE qa."Customers" (id integer PRIMARY KEY, "e-mail" text, postcode text, quantity integer,
                "Odd ""Col""\" text, updated_at timestamptz)""",
            """
            INSERT INTO qa."Customers"
            SELECT n,
                   CASE WHEN n % 100 = 0 THEN NULL WHEN n % 100 = 1 THEN '  ' WHEN n % 50 = 7 THEN 'broken'
                        ELSE 'buyer' || n || '@example.com' END,
                   CASE WHEN n % 200 = 5 THEN 'unknown' WHEN n % 3 = 0 THEN 'SW1A 1AA' WHEN n % 3 = 1 THEN 'M1 1AE'
                        ELSE 'EC1A 1BB' END,
                   CASE WHEN n % 250 = 0 THEN -1 ELSE n % 20 END,
                   'code-' || (n % 1990),
                   now() - make_interval(mins => n)
            FROM generate_series(1, 2000) n""",
            "CREATE TABLE qa.orders (order_id integer PRIMARY KEY, customer_id integer, amount numeric(10,2))",
            "INSERT INTO qa.orders SELECT n, 1 + n % 2010, (n % 300) + 0.5 FROM generate_series(1, 3000) n",
            "CREATE TABLE qa.big (id integer, v integer)",
            "INSERT INTO qa.big SELECT n, n % 7 FROM generate_series(1, 30000) n",
            "ANALYZE qa.big",
            "CREATE SEQUENCE qa.s",
            // Sources should use a read-only login without superuser rights: custom SQL refuses to run otherwise.
            "CREATE ROLE qa_reader LOGIN PASSWORD 'qa-reader-pw'",
            "GRANT USAGE ON SCHEMA qa TO qa_reader",
            "GRANT SELECT ON ALL TABLES IN SCHEMA qa TO qa_reader",
            "GRANT USAGE ON SEQUENCE qa.s TO qa_reader");
        viewer = token("quality-viewer", "viewer");
        steward = token("quality-steward", "steward");
        loaded = true;
    }

    /** A source on the test database: as the read-only qa_reader, or as the test's superuser. */
    private Map<String, Object> source(String name, Map<String, String> extra, boolean reader) {
        Map<String, String> settings = new HashMap<>(Map.of("url", url, "username", reader ? "qa_reader" : user,
            "schemas", "qa", "sample_rows", "200"));
        settings.putAll(extra);
        return Map.of("name", name, "kind", "postgresql", "password", reader ? "qa-reader-pw" : password,
            "settings", settings);
    }

    /** Scans a source and waits for the scan and the quality evaluation queued after it. */
    private long scan(String source) throws Exception {
        long runId = admin().post("/api/v1/sources/" + source + "/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        for (int i = 0; i < 240; i++) {
            String status = admin().get("/api/v1/runs/" + runId).then().statusCode(200).extract().jsonPath()
                .getString("status");
            if (!status.equals("queued") && !status.equals("running")) {
                assertEquals("succeeded", status, admin().get("/api/v1/runs/" + runId).asString());
                break;
            }
            Thread.sleep(250);
        }
        for (int i = 0; i < 400; i++) {
            try (Connection c = db.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT status, detail FROM kilnmark.quality_run WHERE scan_run_id = "
                     + runId)) {
                if (rs.next() && (rs.getString(1).equals("succeeded") || rs.getString(1).equals("failed"))) {
                    assertEquals("succeeded", rs.getString(1), rs.getString(2));
                    return runId;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("The quality evaluation after scan " + runId + " did not finish");
    }

    private JsonPath waitForQualityRun(long id) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            JsonPath run = as(steward).get("/api/v1/quality/runs/" + id).then().statusCode(200).extract().jsonPath();
            String status = run.getString("status");
            if (status.equals("succeeded") || status.equals("failed")) {
                assertEquals("succeeded", status, run.prettify());
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("quality run " + id + " did not finish");
    }

    private JsonPath quality(String token, String urn) {
        return as(token).queryParam("urn", urn).get("/api/v1/quality/asset").then().statusCode(200).extract().jsonPath();
    }

    @Test
    @Order(1)
    void aScanProfilesPatternsSamplesAndVersionsThenAnalysesThem() throws Exception {
        admin().contentType(ContentType.JSON).body(source("quality-db", Map.of("table_sample_above_rows", "10000"), true))
            .post("/api/v1/sources").then().statusCode(201);
        scan("quality-db");
        assertEquals(1, count("SELECT count(*) FROM kilnmark.profile_version WHERE asset_urn = '" + CUSTOMERS + "'"));
        assertTrue(count("SELECT count(*) FROM kilnmark.quality_measure m JOIN kilnmark.profile_version v "
            + "ON v.id = m.version_id WHERE v.asset_urn = '" + CUSTOMERS + "'") > 5);
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT estimate, method, sampled_rows, row_count, estimated_rows "
                 + "FROM kilnmark.profile_version WHERE asset_urn = '" + BIG + "'")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1), "30,000 rows above 10,000: profiled from TABLESAMPLE");
            assertEquals("table-sample", rs.getString(2));
            assertTrue(rs.getLong(3) < 30_000, "read fewer rows than the table holds: " + rs.getLong(3));
            assertEquals(30_000, rs.getLong(5), "the planner's estimate");
        }
        assertEquals(30_000, admin().get("/api/v1/assets/" + BIG).then().statusCode(200).extract().jsonPath()
            .getLong("rowCount"), "the catalogue keeps the estimate, not the size of the sample");
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT estimate FROM kilnmark.profile_version WHERE asset_urn = '"
                 + CUSTOMERS + "'")) {
            assertTrue(rs.next());
            assertFalse(rs.getBoolean(1), "small tables are read in full");
        }
    }

    @Test
    @Order(2)
    void theAssetShowsMeasuresScoresAndPatternsWithExamplesOnlyForDataRoles() {
        JsonPath v = quality(viewer, CUSTOMERS);
        assertFalse(v.getBoolean("canSeeData"));
        List<Map<String, Object>> patterns = v.getList("patterns");
        Map<String, Object> postcode = patterns.stream().filter(p -> p.get("field").equals("postcode")).findFirst()
            .orElseThrow();
        List<Map<String, Object>> shapes = JsonPath.from(JSON.valueToTree(postcode).toString()).getList("shapes");
        assertEquals(Set.of("AA9A 9AA", "A9 9AA", "aaaaaaa"), Set.copyOf(shapes.stream().map(s -> s.get("shape")).toList()));
        assertTrue(shapes.stream().noneMatch(s -> s.containsKey("examples")), "a viewer sees shapes, not values");
        assertTrue(JSON.valueToTree(postcode).get("formats").toString().contains("uk_postcode"));
        assertNotNull(JSON.valueToTree(postcode).get("suggestion"));

        List<Map<String, Object>> measures = v.getList("measures");
        Map<String, Object> completeness = measures.stream().filter(m -> m.get("field").equals("e-mail")
            && m.get("dimension").equals("completeness")).findFirst().orElseThrow();
        assertEquals(0.98, ((Number) completeness.get("score")).doubleValue(), 1e-6, "20 nulls and 20 blanks of 2,000");
        assertTrue(measures.stream().anyMatch(m -> m.get("field").equals("quantity")
            && m.get("dimension").equals("consistency") && ((Number) m.get("score")).doubleValue() < 1),
            "negative quantities are impossible values");
        assertTrue(measures.stream().anyMatch(m -> m.get("field").equals("id") && m.get("dimension").equals("uniqueness")));
        assertTrue(measures.stream().noneMatch(m -> m.get("dimension").equals("timeliness")),
            "no timeliness until the cadence is learned");
        for (Map<String, Object> m : measures) {
            Map<?, ?> evidence = (Map<?, ?>) m.get("evidence");
            for (String key : List.of("min", "max", "domain", "fenceLow", "fenceHigh", "latest", "detail")) {
                assertFalse(evidence.containsKey(key), "a viewer sees no values in evidence: " + m);
            }
        }
        assertTrue(v.getList("scores.asset.dimension").contains("overall"));
        assertTrue(v.getList("scores.fields.'e-mail'.dimension").contains("completeness"));

        JsonPath s = quality(steward, CUSTOMERS);
        assertTrue(s.getBoolean("canSeeData"));
        String examples = s.getString("patterns.find { it.field == 'postcode' }.shapes.find { it.shape == 'AA9A 9AA' }"
            + ".examples");
        assertTrue(examples.contains("SW1A 1AA") || examples.contains("EC1A 1BB"), examples);
    }

    @Test
    @Order(3)
    void samplesNeedARoleThatMaySeeData() {
        as(viewer).queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/samples").then().statusCode(403);
        given().queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/samples").then().statusCode(401);
        JsonPath s = as(steward).queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/samples").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(s.getBoolean("kept"));
        assertEquals("key", s.getString("method"));
        assertEquals(List.of("id"), s.getList("keyFields"));
        assertEquals(2000, s.getLong("seen"));
        List<List<String>> rows = s.getList("rows");
        assertEquals(200, rows.size());
        firstSampleIds = rows.stream().map(r -> r.get(0)).toList();
        assertTrue(s.getList("columns").contains(ODD));
    }

    private long rule(Map<String, Object> body) {
        long id = as(steward).contentType(ContentType.JSON).body(body).post("/api/v1/quality/rules").then()
            .statusCode(201).extract().jsonPath().getLong("id");
        return id;
    }

    @Test
    @Order(4)
    void rulesRunAsSqlInThePostgresSourceWithAwkwardNamesQuoted() throws Exception {
        rules.put("email", rule(Map.of("assetUrn", CUSTOMERS, "field", "e-mail", "type", "not_null",
            "params", Map.of("blankIsMissing", true), "thresholdUnit", "share", "failAbove", 0.01,
            "actions", Map.of("webhook", "http://127.0.0.1:47391/hooks/quality", "task", true))));
        rules.put("postcode", rule(Map.of("assetUrn", CUSTOMERS, "field", "postcode", "type", "matches_pattern",
            "params", Map.of("regex", "^[A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{2}$"), "thresholdUnit", "count",
            "warnAbove", 0, "failAbove", 100)));
        rules.put("odd", rule(Map.of("assetUrn", CUSTOMERS, "field", ODD, "type", "unique")));
        rules.put("quantity", rule(Map.of("assetUrn", CUSTOMERS, "field", "quantity", "type", "in_range",
            "params", Map.of("min", 0))));
        rules.put("orphans", rule(Map.of("assetUrn", ORDERS, "field", "customer_id", "type", "references",
            "params", Map.of("asset", CUSTOMERS, "field", "id"))));
        // Custom SQL: admins only, never functions that act outside the query.
        Map<String, Object> custom = Map.of("assetUrn", CUSTOMERS, "field", "quantity", "type", "custom_sql",
            "params", Map.of("sql", "SELECT * FROM ${table} WHERE ${column} < 0"));
        as(steward).contentType(ContentType.JSON).body(custom).post("/api/v1/quality/rules").then().statusCode(403);
        rules.put("custom", admin().contentType(ContentType.JSON).body(custom).post("/api/v1/quality/rules").then()
            .statusCode(201).extract().jsonPath().getLong("id"));
        admin().contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "type", "custom_sql",
            "params", Map.of("sql", "SELECT nextval('qa.s') FROM ${table}"))).post("/api/v1/quality/rules").then()
            .statusCode(400).body("error", containsString("nextval"));
        admin().contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "type", "custom_sql",
            "params", Map.of("sql", "SELECT 1 AS a) f) DENY SELECT ON qa.t TO public COMMIT SELECT (SELECT 1 AS b FROM "
                + "(SELECT 1 AS c"))).post("/api/v1/quality/rules").then().statusCode(400);
        rules.put("slow", admin().contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "type", "custom_sql",
            "params", Map.of("sql", "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT * FROM r")))
            .post("/api/v1/quality/rules").then().statusCode(201).extract().jsonPath().getLong("id"));
        as(steward).post("/api/v1/quality/rules/" + rules.get("slow") + "/run").then().statusCode(403);
        rules.put("rows", rule(Map.of("assetUrn", CUSTOMERS, "type", "row_count", "params", Map.of("min", 1000))));
        rules.put("fresh", rule(Map.of("assetUrn", CUSTOMERS, "field", "updated_at", "type", "freshness",
            "params", Map.of("maxAgeHours", 48))));

        // Checked at the API edge, and only stewards change rules.
        as(viewer).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "id", "type", "unique"))
            .post("/api/v1/quality/rules").then().statusCode(403);
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "nope", "type", "unique"))
            .post("/api/v1/quality/rules").then().statusCode(400).body("error", containsString("field"));
        admin().contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "type", "custom_sql",
            "params", Map.of("sql", "DELETE FROM qa.orders"))).post("/api/v1/quality/rules").then().statusCode(400)
            .body("error", containsString("params.sql"));
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "id", "type", "unique",
            "actions", Map.of("webhook", "http://127.0.0.1:47392/elsewhere"))).post("/api/v1/quality/rules")
            .then().statusCode(400).body("error", containsString("allow-list"));

        long customersRun = as(steward).queryParam("urn", CUSTOMERS).post("/api/v1/quality/asset/runs").then()
            .statusCode(202).extract().jsonPath().getLong("id");
        long ordersRun = as(steward).queryParam("urn", ORDERS).post("/api/v1/quality/asset/runs").then()
            .statusCode(202).extract().jsonPath().getLong("id");
        as(viewer).queryParam("urn", ORDERS).post("/api/v1/quality/asset/runs").then().statusCode(403);
        waitForQualityRun(customersRun);
        waitForQualityRun(ordersRun);

        JsonPath r = as(steward).queryParam("urn", CUSTOMERS).get("/api/v1/quality/rules").then().statusCode(200)
            .extract().jsonPath();
        assertEquals("fail", latest(r, "email", "status"));
        assertEquals(40, ((Number) latest(r, "email", "failing")).longValue(), "20 nulls and 20 blanks");
        assertEquals(2000, ((Number) latest(r, "email", "total")).longValue());
        assertEquals("sql", latest(r, "email", "method"));
        assertEquals("warn", latest(r, "postcode", "status"), "10 failing: above the warning count, below failure");
        assertEquals(10, ((Number) latest(r, "postcode", "failing")).longValue());
        assertEquals(20, ((Number) latest(r, "odd", "failing")).longValue(), "codes 1 to 10, twice each");
        String failingQuery = (String) latest(r, "odd", "failingQuery");
        assertTrue(failingQuery.contains("\"Odd \"\"Col\"\"\"") && failingQuery.contains("\"qa\".\"Customers\""),
            failingQuery);
        assertEquals(8, ((Number) latest(r, "quantity", "failing")).longValue());
        assertEquals(8, ((Number) latest(r, "custom", "failing")).longValue());
        assertEquals("error", latest(r, "slow", "status"));
        assertTrue(((String) latest(r, "slow", "message")).contains("longer than 5 seconds"));
        assertEquals("pass", latest(r, "rows", "status"));
        assertEquals("pass", latest(r, "fresh", "status"));

        // Viewers see the rules and results, but no values, queries or database errors.
        JsonPath v = as(viewer).queryParam("urn", CUSTOMERS).get("/api/v1/quality/rules").then().statusCode(200)
            .extract().jsonPath();
        String viewed = v.prettify();
        assertFalse(viewed.contains("failingQuery\": \"SELECT"), "no failing-rows queries for viewers");
        assertFalse(viewed.contains("${column} < 0"), "no custom SQL for viewers");
        assertTrue(viewed.contains("\"hidden\": true"), "range bounds hidden");
        assertFalse(viewed.contains("errorDetail\": \""), "no database error text for viewers");

        JsonPath o = as(viewer).queryParam("urn", ORDERS).get("/api/v1/quality/rules").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(10, ((Number) latest(o, "orphans", "failing")).longValue(), "customer ids 2001 to 2010");

        // Failing rows: kept for roles that may see data, not shown to viewers.
        long resultId = ((Number) latest(r, "odd", "id")).longValue();
        JsonPath steward = as(QualityFlowTest.steward).get("/api/v1/quality/results/" + resultId).then().statusCode(200)
            .extract().jsonPath();
        assertEquals(20, steward.getList("failingRows.rows").size());
        assertNull(as(viewer).get("/api/v1/quality/results/" + resultId).then().statusCode(200).extract().jsonPath()
            .get("failingRows"));
    }

    private static Object latest(JsonPath rules, String name, String key) {
        long id = QualityFlowTest.rules.get(name);
        List<Map<String, Object>> list = rules.getList("$");
        Map<String, Object> view = list.stream().filter(x -> ((Number) ((Map<?, ?>) x.get("rule")).get("id")).longValue()
            == id).findFirst().orElseThrow();
        Map<?, ?> latest = (Map<?, ?>) view.get("latest");
        assertNotNull(latest, "rule " + name + " has a result");
        return latest.get(key);
    }

    @Test
    @Order(5)
    void actionsCallTheAllowedWebhookAndOpenAStewardTask() throws Exception {
        for (int i = 0; i < 40 && received.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertFalse(received.isEmpty(), "the webhook was called");
        JsonNode body = JSON.readTree(received.get(0));
        assertEquals("fail", body.get("status").asText());
        assertEquals(CUSTOMERS, body.get("asset").asText());
        assertEquals(40, body.get("failing").asLong());
        JsonPath issues = as(viewer).get("/api/v1/quality/issues").then().statusCode(200).extract().jsonPath();
        assertTrue(issues.getList("ruleId").stream().anyMatch(id -> ((Number) id).longValue() == rules.get("email")),
            "an open task for the failing rule");
        assertEquals(0, count("SELECT count(*) FROM kilnmark.quality_issue WHERE rule_id = " + rules.get("postcode")),
            "a rule without the task action opens no task");
    }

    @Test
    @Order(6)
    void rulesPreviewAgainstTheSampleBeforeSaving() {
        JsonPath p = as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "e-mail",
            "type", "not_null", "params", Map.of("blankIsMissing", true))).post("/api/v1/quality/rules/preview")
            .then().statusCode(200).extract().jsonPath();
        assertEquals(200, p.getLong("checked"));
        assertTrue(p.getString("message").startsWith("Would fail "), p.getString("message"));
        assertTrue(p.getDouble("share") < 0.1);
        as(viewer).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "e-mail", "type", "not_null"))
            .post("/api/v1/quality/rules/preview").then().statusCode(403);
    }

    @Test
    @Order(7)
    void acceptingAPatternCreatesAValidityRule() {
        long id = as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "shape", "AA9A 9AA")).post("/api/v1/quality/asset/patterns").then()
            .statusCode(201).extract().jsonPath().getLong("ruleId");
        as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "shape", "AA9A 9AA")).post("/api/v1/quality/asset/patterns").then()
            .statusCode(409);
        as(viewer).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "shape", "A9 9AA")).post("/api/v1/quality/asset/patterns").then()
            .statusCode(403);
        long again = as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "shape", "A9 9AA")).post("/api/v1/quality/asset/patterns").then()
            .statusCode(201).extract().jsonPath().getLong("ruleId");
        assertEquals(id, again, "one validity rule per column covers its accepted patterns");
        JsonPath rule = as(viewer).get("/api/v1/quality/rules/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals("matches_pattern", rule.getString("rule.type"));
        assertEquals("validity", rule.getString("rule.dimension"));
        assertEquals("^(\\p{Lu}{2}[0-9]\\p{Lu} [0-9]\\p{Lu}{2}|\\p{Lu}[0-9] [0-9]\\p{Lu}{2})$",
            rule.getString("rule.params.regex"));
        JsonPath q = quality(viewer, CUSTOMERS);
        Map<String, Object> validity = q.<Map<String, Object>>getList("measures").stream()
            .filter(m -> m.get("field").equals("postcode") && m.get("dimension").equals("validity")).findFirst()
            .orElseThrow();
        assertEquals("accepted patterns", ((Map<?, ?>) validity.get("evidence")).get("basis"));
    }

    @Test
    @Order(8)
    void sensitiveColumnsAreMaskedUnlessAnAdminUnmasks() {
        as(viewer).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "e-mail", "sensitive", true)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(403);
        as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "e-mail", "sensitive", true)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(204);
        JsonPath masked = as(steward).queryParam("urn", CUSTOMERS).queryParam("unmask", true)
            .get("/api/v1/quality/asset/samples").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("e-mail"), masked.getList("masked"), "a steward cannot unmask");
        int col = masked.getList("columns").indexOf("e-mail");
        List<List<String>> rows = masked.getList("rows");
        assertTrue(rows.stream().map(r -> r.get(col)).allMatch(v -> v == null || v.equals("••••")));
        String examples = quality(steward, CUSTOMERS).getString("patterns.find { it.field == 'e-mail' }.shapes[0].examples");
        assertTrue(examples.contains("••••") && !examples.contains("@"), examples);

        // The catalogue's own profile masks the most common values of a sensitive column too.
        as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "sensitive", true)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(204);
        assertEquals(List.of("••••", "••••", "••••"), as(steward).get("/api/v1/assets/" + CUSTOMERS).then().statusCode(200)
            .extract().jsonPath().getList("fields.find { it.name == 'postcode' }.profile.top.value"));
        assertTrue(admin().get("/api/v1/assets/" + CUSTOMERS).then().statusCode(200).extract().jsonPath()
            .getList("fields.find { it.name == 'postcode' }.profile.top.value").contains("SW1A 1AA"));
        // Marking is for stewards; un-marking only for admins.
        as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "sensitive", false)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(403);
        admin().contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "postcode", "sensitive", false)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(204);

        JsonPath open = admin().queryParam("urn", CUSTOMERS).queryParam("unmask", true)
            .get("/api/v1/quality/asset/samples").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), open.getList("masked"));
        List<List<String>> plain = open.getList("rows");
        assertTrue(plain.stream().map(r -> r.get(col)).anyMatch(v -> v != null && v.contains("@example.com")));
    }

    @Test
    @Order(9)
    void aSecondScanOfChangedDataRecordsDriftAndSamplesTheSameRows() throws Exception {
        sql("UPDATE qa.\"Customers\" SET postcode = NULL WHERE id % 4 = 0",
            "UPDATE qa.\"Customers\" SET quantity = quantity * 50 WHERE id % 2 = 0",
            "ALTER TABLE qa.\"Customers\" ADD COLUMN note text",
            "DELETE FROM qa.orders WHERE order_id > 1500");
        scan("quality-db");
        List<Map<String, Object>> drift = as(viewer).queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/drift")
            .then().statusCode(200).extract().jsonPath().getList("$");
        assertTrue(drift.stream().anyMatch(d -> d.get("change").equals("null_rate") && "postcode".equals(d.get("field"))
            && d.get("severity").equals("high")), drift.toString());
        assertTrue(drift.stream().anyMatch(d -> d.get("change").equals("column_added") && "note".equals(d.get("field"))));
        assertTrue(drift.stream().anyMatch(d -> d.get("change").equals("quantile_shift") && "quantity".equals(d.get("field"))));
        List<Map<String, Object>> orders = as(viewer).queryParam("urn", ORDERS).get("/api/v1/quality/asset/drift")
            .then().statusCode(200).extract().jsonPath().getList("$");
        assertTrue(orders.stream().anyMatch(d -> d.get("change").equals("row_count") && d.get("severity").equals("high")));

        // Drift lowers the scores, with the reason given.
        JsonPath q = quality(viewer, CUSTOMERS);
        List<String> reasons = q.getList("scores.fields.postcode.find { it.dimension == 'completeness' }.reasons");
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("Drift: Nulls in postcode")), reasons.toString());
        assertTrue(count("SELECT count(*) FROM kilnmark.quality_issue WHERE drift_event_id IS NOT NULL AND asset_urn = '"
            + CUSTOMERS + "'") > 0, "significant drift opens a steward task");

        // The same keys are sampled again: changes between scans are real, not sampling noise.
        List<List<String>> rows = admin().queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/samples").then()
            .statusCode(200).extract().jsonPath().getList("rows");
        assertEquals(firstSampleIds, rows.stream().map(r -> r.get(0)).toList());
        assertEquals(2, as(viewer).queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/versions").then()
            .statusCode(200).extract().jsonPath().getList("$").size());
    }

    private static Set<ValidationMessage> validate(String schemaUri, JsonNode doc, SpecVersion.VersionFlag version) {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(version, b -> b.schemaMappers(m -> m
            .mapPrefix("https://openlineage.io/spec/", "classpath:schemas/openlineage/")));
        JsonSchema schema = factory.getSchema(SchemaLocation.of(schemaUri));
        return schema.validate(doc);
    }

    @Test
    @Order(10)
    void exportsAreValidOpenLineageAndOdcs() throws Exception {
        rule(Map.of("assetUrn", CUSTOMERS, "field", "postcode", "type", "allowed_values",
            "params", Map.of("values", List.of("SW1A 1AA", "M1 1AE", "EC1A 1BB"))));
        as(steward).contentType(ContentType.JSON).queryParam("urn", CUSTOMERS)
            .body(Map.of("field", "quantity", "sensitive", true)).put("/api/v1/quality/asset/sensitive").then()
            .statusCode(204);
        JsonNode event = JSON.readTree(admin().queryParam("urn", CUSTOMERS).queryParam("unmask", true)
            .get("/api/v1/quality/export/openlineage").then().statusCode(200).extract().asString());
        assertEquals(Set.of(), validate("https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent", event,
            SpecVersion.VersionFlag.V202012));
        JsonNode input = event.get("inputs").get(0);
        assertEquals(Set.of(), validate("https://openlineage.io/spec/facets/1-1-1/SchemaDatasetFacet.json",
            JSON.createObjectNode().set("schema", input.get("facets").get("schema")), SpecVersion.VersionFlag.V202012));
        assertEquals(Set.of(), validate("https://openlineage.io/spec/facets/1-0-2/DataQualityMetricsInputDatasetFacet.json",
            JSON.createObjectNode().set("dataQualityMetrics", input.get("inputFacets").get("dataQualityMetrics")),
            SpecVersion.VersionFlag.V202012));
        assertEquals(Set.of(), validate("https://openlineage.io/spec/facets/1-0-1/DataQualityAssertionsDatasetFacet.json",
            JSON.createObjectNode().set("dataQualityAssertions", input.get("inputFacets").get("dataQualityAssertions")),
            SpecVersion.VersionFlag.V202012));
        // The schema facet itself must not be vacuous: an obviously wrong document fails.
        assertFalse(validate("https://openlineage.io/spec/facets/1-0-2/DataQualityMetricsInputDatasetFacet.json",
            JSON.readTree("{\"dataQualityMetrics\":{\"_producer\":\"x\",\"_schemaURL\":\"y\"}}"),
            SpecVersion.VersionFlag.V202012).isEmpty(), "columnMetrics is required");

        assertTrue(input.get("namespace").asText().startsWith("postgres://"), input.toString());
        assertEquals("kilnmark.qa.Customers", input.get("name").asText());
        JsonNode metrics = input.get("inputFacets").get("dataQualityMetrics");
        assertEquals(2000, metrics.get("rowCount").asLong());
        assertEquals(20, metrics.get("columnMetrics").get("e-mail").get("nullCount").asLong());
        assertEquals(21, metrics.get("columnMetrics").get("quantity").get("quantiles").size());
        assertEquals("kilnmark", input.get("inputFacets").get("kilnmark_provenance").get("origin").asText());
        assertTrue(input.get("inputFacets").get("dataQualityAssertions").get("assertions").toString()
            .contains("\"assertion\":\"not_null\",\"success\":false"));

        JsonNode contract = JSON.readTree(as(steward).queryParam("urn", CUSTOMERS).get("/api/v1/quality/export/odcs")
            .then().statusCode(200).extract().asString());
        assertTrue(contract.toString().contains("\"validValues\""), "stewards see allowed values");
        assertFalse(contract.toString().contains("< 0"), "custom SQL may quote any column: hidden while the asset "
            + "has sensitive columns");
        assertTrue(admin().queryParam("urn", CUSTOMERS).queryParam("unmask", true).get("/api/v1/quality/export/odcs")
            .then().statusCode(200).extract().asString().contains("{property} < 0"), "custom SQL with ODCS placeholders");
        Set<ValidationMessage> odcsErrors = validate("classpath:schemas/odcs/odcs-json-schema-v3.1.0.json", contract,
            SpecVersion.VersionFlag.V201909);
        assertEquals(Set.of(), odcsErrors, odcsErrors + " in " + contract);
        assertEquals("v3.1.0", contract.get("apiVersion").asText());
        com.fasterxml.jackson.databind.node.ObjectNode broken = contract.deepCopy();
        broken.remove("status");
        assertFalse(validate("classpath:schemas/odcs/odcs-json-schema-v3.1.0.json", broken,
            SpecVersion.VersionFlag.V201909).isEmpty(), "the ODCS schema is really applied: status is required");
        JsonNode props = contract.get("schema").get(0).get("properties");
        JsonNode email = null;
        for (JsonNode p : props) {
            if (p.get("name").asText().equals("e-mail")) {
                email = p;
            }
        }
        assertNotNull(email);
        assertEquals("sensitive", email.get("classification").asText());
        assertEquals("missingValues", email.get("quality").get(0).get("metric").asText(), "blanks count as missing");
        assertTrue(contract.get("schema").get(0).get("quality").toString().contains("\"engine\":\"kilnmark\""),
            "row count and freshness checks travel as Kilnmark's own");

        // Viewers get the same formats without values: no allowed values, range bounds or custom SQL,
        // and no statistics of a sensitive column.
        JsonNode viewerContract = JSON.readTree(as(viewer).queryParam("urn", CUSTOMERS)
            .get("/api/v1/quality/export/odcs").then().statusCode(200).extract().asString());
        Set<ValidationMessage> viewerErrors = validate("classpath:schemas/odcs/odcs-json-schema-v3.1.0.json",
            viewerContract, SpecVersion.VersionFlag.V201909);
        assertEquals(Set.of(), viewerErrors, viewerErrors + " in " + viewerContract);
        String vc = viewerContract.toString();
        assertFalse(vc.contains("SW1A 1AA") || vc.contains("\"validValues\""), vc);
        assertFalse(vc.contains("< 0") || vc.contains("RECURSIVE"), "no custom SQL");
        JsonNode viewerEvent = JSON.readTree(as(viewer).queryParam("urn", CUSTOMERS)
            .get("/api/v1/quality/export/openlineage").then().statusCode(200).extract().asString());
        JsonNode quantity = viewerEvent.get("inputs").get(0).get("inputFacets").get("dataQualityMetrics")
            .get("columnMetrics").get("quantity");
        assertNull(quantity.get("min"));
        assertNull(quantity.get("quantiles"), "a sensitive column's statistics are left out");
        assertNotNull(quantity.get("nullCount"), "counts stay");
        assertEquals(Set.of(), validate("https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent", viewerEvent,
            SpecVersion.VersionFlag.V202012));
    }

    @Test
    @Order(11)
    void withKeepSamplesOffNoValuesAreStored() throws Exception {
        admin().contentType(ContentType.JSON).body(source("quality-plain", Map.of("keep_samples", "false"), false))
            .post("/api/v1/sources").then().statusCode(201);
        scan("quality-plain");
        String urn = "urn:kilnmark:quality-plain:qa.Customers";
        assertEquals(0, count("SELECT count(*) FROM kilnmark.sample WHERE asset_urn LIKE 'urn:kilnmark:quality-plain:%'"));
        JsonPath s = as(steward).queryParam("urn", urn).get("/api/v1/quality/asset/samples").then().statusCode(200)
            .extract().jsonPath();
        assertFalse(s.getBoolean("kept"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.profile p WHERE p.asset_urn LIKE "
            + "'urn:kilnmark:quality-plain:%' AND (p.stats -> 'top') IS NOT NULL"), "no most common values either");
        assertTrue(count("SELECT count(*) FROM kilnmark.profile p WHERE p.asset_urn = '" + urn + "' AND (p.stats -> 'patterns') IS NOT NULL")
            > 0, "shapes are still kept");
        String patterns = quality(steward, urn).getString("patterns");
        assertFalse(patterns.contains("examples"), patterns);

        long id = rule(Map.of("assetUrn", urn, "field", "e-mail", "type", "not_null"));
        waitForQualityRun(as(steward).post("/api/v1/quality/rules/" + id + "/run").then().statusCode(202).extract()
            .jsonPath().getLong("id"));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.quality_result WHERE rule_id = " + id
            + " AND status = 'fail' AND failing_rows IS NULL AND failing_query IS NOT NULL"),
            "the failing rows query is kept, the rows are not");
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", urn, "field", "e-mail", "type", "not_null"))
            .post("/api/v1/quality/rules/preview").then().statusCode(409);

        // This source logs in as the database's superuser: custom SQL refuses to run there.
        long custom = admin().contentType(ContentType.JSON).body(Map.of("assetUrn", urn, "field", "quantity",
            "type", "custom_sql", "params", Map.of("sql", "SELECT * FROM ${table} WHERE ${column} < 0")))
            .post("/api/v1/quality/rules").then().statusCode(201).extract().jsonPath().getLong("id");
        waitForQualityRun(admin().post("/api/v1/quality/rules/" + custom + "/run").then().statusCode(202).extract()
            .jsonPath().getLong("id"));
        JsonPath refused = admin().get("/api/v1/quality/rules/" + custom).then().statusCode(200).extract().jsonPath();
        assertEquals("error", refused.getString("latest.status"));
        assertTrue(refused.getString("latest.message").contains("superuser"), refused.getString("latest.message"));
    }

    @Test
    @Order(12)
    void stewardsWorkTheIssueList() {
        long issue = as(viewer).get("/api/v1/quality/issues").then().statusCode(200).extract().jsonPath().getLong("[0].id");
        as(viewer).contentType(ContentType.JSON).body(Map.of("status", "resolved")).put("/api/v1/quality/issues/" + issue)
            .then().statusCode(403);
        as(steward).contentType(ContentType.JSON).body(Map.of("status", "done")).put("/api/v1/quality/issues/" + issue)
            .then().statusCode(400);
        as(steward).contentType(ContentType.JSON).body(Map.of("status", "dismissed")).put("/api/v1/quality/issues/" + issue)
            .then().statusCode(200).extract().jsonPath();
        assertEquals("dismissed", as(viewer).queryParam("status", "all").get("/api/v1/quality/issues").then()
            .statusCode(200).extract().jsonPath().getString("find { it.id == " + issue + " }.status"));
    }

    @Test
    @Order(13)
    void recommendationsComeFromTheProfileAndHideValuesFromViewers() {
        List<Map<String, Object>> forSteward = as(steward).queryParam("urn", ORDERS)
            .get("/api/v1/quality/asset/recommendations").then().statusCode(200).extract().jsonPath().getList("$");
        assertTrue(forSteward.stream().anyMatch(r -> r.get("type").equals("unique") && "order_id".equals(r.get("field"))));
        Map<String, Object> range = forSteward.stream().filter(r -> r.get("type").equals("in_range")
            && "amount".equals(r.get("field"))).findFirst().orElseThrow();
        assertEquals("0.5", ((Map<?, ?>) range.get("params")).get("min"));
        List<Map<String, Object>> customers = as(steward).queryParam("urn", CUSTOMERS)
            .get("/api/v1/quality/asset/recommendations").then().statusCode(200).extract().jsonPath().getList("$");
        assertTrue(customers.stream().noneMatch(r -> r.get("type").equals("in_range") && "quantity".equals(r.get("field"))),
            "a rule that exists is not recommended again");
        assertTrue(customers.stream().anyMatch(r -> r.get("type").equals("not_null") && "id".equals(r.get("field"))));
        List<Map<String, Object>> forViewer = as(viewer).queryParam("urn", ORDERS)
            .get("/api/v1/quality/asset/recommendations").then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> hidden = forViewer.stream().filter(r -> r.get("type").equals("in_range")).findFirst()
            .orElseThrow();
        assertEquals(Map.of(), hidden.get("params"));
    }

    @Test
    @Order(14)
    void rolesAndSourceScores() {
        given().queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset").then().statusCode(401);
        as(viewer).get("/api/v1/quality/sources/quality-db").then().statusCode(200)
            .body("scores.overall", org.hamcrest.Matchers.notNullValue());
        as(viewer).delete("/api/v1/quality/rules/" + rules.get("rows")).then().statusCode(403);
        as(steward).delete("/api/v1/quality/rules/" + rules.get("rows")).then().statusCode(204);
        as(steward).get("/api/v1/quality/rules/" + rules.get("rows")).then().statusCode(404);
        as(viewer).queryParam("urn", "urn:kilnmark:quality-db:qa.nothing").get("/api/v1/quality/asset").then()
            .statusCode(404);
        as(viewer).get("/api/v1/quality/asset").then().statusCode(400).body("error", containsString("urn"));
        List<Object> all = new ArrayList<>(as(viewer).queryParam("source", "quality-db").get("/api/v1/quality/rules")
            .then().statusCode(200).extract().jsonPath().getList("$"));
        assertFalse(all.isEmpty());
    }

    @Test
    @Order(15)
    void stewardsSeeNoValueOfASensitiveColumnByAnyRoute() throws Exception {
        // quantity was marked sensitive in exportsAreValidOpenLineageAndOdcs; it drifted (quantile shift) on the
        // second scan and has an in_range rule with bounds.
        List<Map<String, Object>> drift = as(steward).queryParam("urn", CUSTOMERS).get("/api/v1/quality/asset/drift")
            .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> shift = drift.stream().filter(d -> "quantity".equals(d.get("field"))
            && d.get("change").equals("quantile_shift")).findFirst().orElseThrow();
        assertNull(shift.get("detail"), shift.toString());
        assertFalse(((Map<?, ?>) shift.get("evidence")).containsKey("medianFrom"), shift.toString());
        Map<String, Object> unmasked = admin().queryParam("urn", CUSTOMERS).queryParam("unmask", true)
            .get("/api/v1/quality/asset/drift").then().statusCode(200).extract().jsonPath().<Map<String, Object>>getList("$")
            .stream().filter(d -> d.get("id").equals(shift.get("id"))).findFirst().orElseThrow();
        assertNotNull(unmasked.get("detail"), "an admin who unmasks sees it");

        List<Map<String, Object>> measures = quality(steward, CUSTOMERS).getList("measures");
        for (Map<String, Object> m : measures) {
            if ("quantity".equals(m.get("field"))) {
                Map<?, ?> e = (Map<?, ?>) m.get("evidence");
                assertFalse(e.containsKey("min") || e.containsKey("max") || e.containsKey("fenceLow"), m.toString());
            }
        }

        JsonPath rule = as(steward).get("/api/v1/quality/rules/" + rules.get("quantity")).then().statusCode(200)
            .extract().jsonPath();
        assertEquals(true, rule.get("rule.params.hidden"), "the rule's bounds are values of the column");
        // Saving it again as shown keeps its bounds.
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "quantity",
            "type", "in_range", "params", Map.of("hidden", true), "failAbove", 0.5))
            .put("/api/v1/quality/rules/" + rules.get("quantity")).then().statusCode(200);
        JsonPath kept = admin().queryParam("unmask", true).get("/api/v1/quality/rules/" + rules.get("quantity")).then()
            .statusCode(200).extract().jsonPath();
        assertNull(kept.get("rule.params.hidden"));
        assertNotNull(kept.get("rule.params.min"), kept.prettify());

        // The catalogue profile and a live preview of the table mask it too.
        String profile = as(steward).get("/api/v1/assets/" + CUSTOMERS).then().statusCode(200).extract().jsonPath()
            .getString("fields.find { it.name == 'quantity' }.profile");
        assertFalse(profile.matches(".*(min|max|from|to)=[0-9-].*"), profile);
        JsonPath preview = as(steward).queryParam("asset", "qa.Customers").get("/api/v1/sources/quality-db/preview")
            .then().statusCode(200).extract().jsonPath();
        int col = preview.getList("columns.name").indexOf("quantity");
        List<List<String>> rows = preview.getList("rows");
        assertTrue(col >= 0 && rows.stream().allMatch(r -> r.get(col) == null || r.get(col).equals("••••")),
            rows.toString());
    }

    @Test
    @Order(16)
    void probingASensitiveColumnAndTheMarkersForMaskedValues() {
        // quantity is still marked sensitive; its in_range rule ran in step 4.
        Map<String, Object> range = Map.of("assetUrn", CUSTOMERS, "field", "quantity", "type", "in_range",
            "params", Map.of("min", 0, "max", 5));
        as(steward).contentType(ContentType.JSON).body(range).post("/api/v1/quality/rules/preview").then()
            .statusCode(403).body("error", containsString("sensitive"));
        admin().contentType(ContentType.JSON).body(range).queryParam("unmask", true)
            .post("/api/v1/quality/rules/preview").then().statusCode(200);
        long resultId = admin().queryParam("unmask", true).get("/api/v1/quality/rules/" + rules.get("quantity")).then()
            .statusCode(200).extract().jsonPath().getLong("latest.id");
        JsonPath result = as(steward).get("/api/v1/quality/results/" + resultId).then().statusCode(200).extract()
            .jsonPath();
        assertNull(result.get("failingRows"), "counts only: the rows would show which rows hold which values");
        assertNull(result.get("failingQuery"));
        assertNotNull(result.get("failing"));

        // Markers: refused where nothing is stored to keep, kept on a saved rule's own column and type.
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "quantity",
            "type", "in_range", "params", Map.of("hidden", true))).post("/api/v1/quality/rules").then().statusCode(400);
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "postcode",
            "type", "not_null", "actions", Map.of("webhook", "(set)"))).post("/api/v1/quality/rules").then()
            .statusCode(400);
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "quantity",
            "type", "not_null", "params", Map.of("hidden", true))).put("/api/v1/quality/rules/" + rules.get("quantity"))
            .then().statusCode(400).body("error", containsString("same column and type"));
        as(steward).contentType(ContentType.JSON).body(Map.of("assetUrn", CUSTOMERS, "field", "id",
            "type", "in_range", "params", Map.of("hidden", true))).put("/api/v1/quality/rules/" + rules.get("quantity"))
            .then().statusCode(400);

        // The rules page's on/off switch sends the rule back as shown: hidden parameters, "(set)" webhook.
        Map<String, Object> shown = new HashMap<>(as(steward).get("/api/v1/quality/rules/" + rules.get("quantity"))
            .then().statusCode(200).extract().jsonPath().getMap("rule"));
        shown.put("enabled", false);
        as(steward).contentType(ContentType.JSON).body(shown).put("/api/v1/quality/rules/" + rules.get("quantity"))
            .then().statusCode(200).body("enabled", equalTo(false)).body("params.hidden", equalTo(true));
        JsonPath kept = admin().queryParam("unmask", true).get("/api/v1/quality/rules/" + rules.get("quantity")).then()
            .statusCode(200).extract().jsonPath();
        assertNotNull(kept.get("rule.params.min"), kept.prettify());
        assertEquals(false, kept.get("rule.enabled"));
        Map<String, Object> email = new HashMap<>(as(steward).get("/api/v1/quality/rules/" + rules.get("email")).then()
            .statusCode(200).extract().jsonPath().getMap("rule"));
        email.put("actions", Map.of("webhook", "(set)", "task", true));
        as(steward).contentType(ContentType.JSON).body(email).put("/api/v1/quality/rules/" + rules.get("email")).then()
            .statusCode(200);
        assertEquals("http://127.0.0.1:47391/hooks/quality", admin().get("/api/v1/quality/rules/" + rules.get("email"))
            .then().statusCode(200).extract().jsonPath().getString("rule.actions.webhook"), "the stored webhook is kept");

        // Previews of settings not saved are for admins; the saved settings preview, masked.
        Map<String, String> saved = new HashMap<>(Map.of("url", url, "username", "qa_reader", "schemas", "qa",
            "sample_rows", "200", "table_sample_above_rows", "10000"));
        Map<String, String> changed = new HashMap<>(saved);
        changed.put("schemas", "qa,public");
        as(steward).contentType(ContentType.JSON).queryParam("asset", "qa.Customers")
            .body(Map.of("source", "quality-db", "name", "quality-db", "kind", "postgresql", "settings", changed))
            .post("/api/v1/drafts/preview").then().statusCode(403).body("error", containsString("admin"));
        JsonPath same = as(steward).contentType(ContentType.JSON).queryParam("asset", "qa.Customers")
            .body(Map.of("source", "quality-db", "name", "quality-db", "kind", "postgresql", "settings", saved))
            .post("/api/v1/drafts/preview").then().statusCode(200).extract().jsonPath();
        int col = same.getList("columns.name").indexOf("quantity");
        List<List<String>> rows = same.getList("rows");
        assertTrue(col >= 0 && rows.stream().allMatch(r -> r.get(col) == null || r.get(col).equals("••••")));
    }
}
