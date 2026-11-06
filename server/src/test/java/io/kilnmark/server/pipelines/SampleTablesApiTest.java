package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * The samples end to end on Kilnmark's own sample data: the sample tables are created in a
 * PostgreSQL schema by the sample-tables pipeline, the samples are added reading them, and samples
 * run on them: one writing what it read, and the gate that is meant to stop.
 */
@QuarkusTest
class SampleTablesApiTest {

    static final String SCHEMA = "samples_e2e";

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private long count(String table) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static JsonPath waitForRun(String token, long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = as(token).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (List.of("succeeded", "failed", "cancelled").contains(run.getString("run.status"))) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + id + " did not finish: " + (run == null ? null : run.prettify()));
    }

    private void source(String name, String kind, Map<String, String> settings, String password) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "kind", kind, "settings", settings));
        if (password != null) {
            body.put("password", password);
        }
        admin().contentType(ContentType.JSON).body(body).post("/api/v1/sources").then().statusCode(201);
    }

    @Test
    void theSamplesRunOnTheSampleTables() throws Exception {
        sql("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files", "samples-e2e");
        Files.createDirectories(out);
        try {
            run(out);
        } finally {
            for (Samples.Sample s : Samples.bundled()) {
                admin().delete("/api/v1/pipelines/" + s.name());
            }
            admin().delete("/api/v1/pipelines/" + Samples.SETUP);
            admin().delete("/api/v1/variables/" + Samples.REGION_VARIABLE);
            for (String s : List.of("e2e-db", "e2e-db-readonly", "e2e-files", Samples.SAMPLE_DATA)) {
                admin().delete("/api/v1/sources/" + s);
            }
            sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private void run(Path out) throws Exception {
        String designer = token("sample-tables-designer", "designer");
        source("e2e-db", "postgresql", Map.of("url", url, "username", "kilnmark", "schemas", SCHEMA,
            "allow_writing", "true"), "kilnmark");
        source("e2e-db-readonly", "postgresql", Map.of("url", url, "username", "kilnmark", "schemas", SCHEMA),
            "kilnmark");
        source("e2e-files", "files", Map.of("path", out.toString(), "allow_writing", "true"), null);

        // What cannot have the tables: no such source, not a database, not writable, an unusable schema.
        as(designer).body(Map.of("source", "nope", "schema", SCHEMA)).post("/api/v1/samples/tables").then()
            .statusCode(400).body("error", containsString("no saved source called 'nope'"));
        as(designer).body(Map.of("source", "e2e-files", "schema", SCHEMA)).post("/api/v1/samples/tables").then()
            .statusCode(400).body("error", containsString("is not a database"));
        as(designer).body(Map.of("source", "e2e-db-readonly", "schema", SCHEMA)).post("/api/v1/samples/tables")
            .then().statusCode(400).body("error", containsString("does not allow writing"));
        as(designer).body(Map.of("source", "e2e-db", "schema", "x; drop")).post("/api/v1/samples/tables").then()
            .statusCode(400).body("error", containsString("schema:"));

        // Created: the sample data's source appears, the pipeline runs, and every table is filled.
        JsonPath made = as(designer).body(Map.of("source", "e2e-db", "schema", SCHEMA))
            .post("/api/v1/samples/tables").then().statusCode(200).extract().jsonPath();
        assertEquals(Samples.SETUP, made.getString("pipeline"));

        // The samples, added at once, as the starter pack adds them: before the tables are there.
        JsonPath added = as(designer).body(Map.of("sources", Map.of("crm-db", "e2e-db", "shop-db", "e2e-db",
            "files", "e2e-files"), "schema", SCHEMA)).post("/api/v1/samples").then().statusCode(200)
            .extract().jsonPath();
        // 20 pipelines, then the sample flows that run them (ADR 0043). The one with sql nodes needs a
        // source with Allow SQL on, which e2e-db does not have: skipped, saying so, never a draft.
        assertEquals(23, added.getInt("added"), added.prettify());
        assertEquals(List.of("sample-for-each-region", "sample-passing-values", "sample-quality-decides"),
            added.getList("samples.findAll { it.outcome == 'added' && it.file.startsWith('08-flows/') }.name"),
            added.prettify());
        assertEquals("it runs SQL: choose a PostgreSQL source with Allow SQL on for shop-db",
            added.getString("samples.find { it.name == 'sample-sql-before-and-after' }.reason"), added.prettify());
        JsonPath flows = as(designer).get("/api/v1/flows").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("sample"), flows.getList("findAll { it.name.startsWith('sample-') }.library").stream()
            .distinct().toList(), flows.prettify());
        // All of them can run: the starter pack also set the server variable one of them reads.
        assertEquals(List.of(true), added.getList("samples.findAll { it.outcome == 'added' }.valid").stream()
            .distinct().toList(), added.prettify());
        String yaml = as(designer).get("/api/v1/pipelines/sample-limit").then().statusCode(200).extract()
            .jsonPath().getString("latest.yaml");
        assertTrue(yaml.contains("asset: " + SCHEMA + ".billing_transactions"), yaml);

        JsonPath setup = waitForRun(designer, made.getLong("run.id"));
        assertEquals("succeeded", setup.getString("run.status"), setup.prettify());
        assertEquals(240, count(SCHEMA + ".crm_customers"));
        assertEquals(700, count(SCHEMA + ".shop_orders"));
        for (String t : Samples.TABLES) {
            assertTrue(count(Samples.tableIn(SCHEMA, t)) > 0, t);
        }
        as(designer).get("/api/v1/sources/" + Samples.SAMPLE_DATA).then().statusCode(200);
        JsonPath listed = as(designer).get("/api/v1/samples").then().statusCode(200).extract().jsonPath();
        assertEquals("e2e-db", listed.getString("tables.source"));
        assertEquals(SCHEMA, listed.getString("tables.schema"));
        assertEquals("succeeded", listed.getString("tables.lastRun.status"));

        // Again: the tables are replaced, not added to.
        JsonPath again = waitForRun(designer, as(designer).body(Map.of("source", "e2e-db", "schema", SCHEMA))
            .post("/api/v1/samples/tables").then().statusCode(200).extract().jsonPath().getLong("run.id"));
        assertEquals("succeeded", again.getString("run.status"));
        assertEquals(240, count(SCHEMA + ".crm_customers"));

        JsonPath limit = waitForRun(designer, as(designer).body(Map.of()).post("/api/v1/pipelines/sample-limit/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", limit.getString("run.status"), limit.prettify());
        assertEquals(100, limit.getLong("run.rowsWritten"));
        assertTrue(Files.exists(out.resolve("samples/filter-and-route/first_transactions.csv")));

        // The volume sample: four million rows from the sample data itself, totalled and the large lines set
        // apart, in one run.
        JsonPath volume = waitForRun(designer, as(designer).body(Map.of()).post("/api/v1/pipelines/sample-volume/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", volume.getString("run.status"), volume.prettify());
        assertTrue(Files.exists(out.resolve("samples/volume/takings_by_category_and_channel.csv")));
        assertTrue(volume.getLong("run.rowsWritten") > 24, volume.prettify());

        // The gate that is meant to stop does: two shipping fees are out of range.
        JsonPath stop = waitForRun(designer, as(designer).body(Map.of()).post("/api/v1/pipelines/sample-gate-stop/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("failed", stop.getString("run.status"), stop.prettify());

        // A sample-tables pipeline someone has changed is not written over.
        int version = as(designer).get("/api/v1/pipelines/" + Samples.SETUP).then().statusCode(200).extract()
            .jsonPath().getInt("latestVersion");
        String changed = Samples.setupYaml("e2e-db", SCHEMA) + "# mine now\n";
        as(designer).header("If-Match", "\"" + version + "\"").body(Map.of("yaml", changed, "message", "Mine"))
            .put("/api/v1/pipelines/" + Samples.SETUP).then().statusCode(200);
        as(designer).body(Map.of("source", "e2e-db", "schema", SCHEMA)).post("/api/v1/samples/tables").then()
            .statusCode(409).body("error", containsString("has been changed since"));
    }
}
