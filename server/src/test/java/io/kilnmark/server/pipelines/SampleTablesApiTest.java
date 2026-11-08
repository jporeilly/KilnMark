package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    private static JsonPath waitForFlowRun(String token, long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = as(token).get("/api/v1/flow-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (List.of("succeeded", "failed", "cancelled").contains(run.getString("run.status"))) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Flow run " + id + " did not finish: " + (run == null ? null : run.prettify()));
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
            // The flows first, then the pipelines they run.
            for (Samples.Sample s : Samples.bundled()) {
                if ("flow".equals(s.type())) {
                    admin().delete("/api/v1/flows/" + s.name());
                }
            }
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
            .statusCode(400).body("error", containsString("no saved source of that name")).body("error", not(containsString("nope")));
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
        // 25 pipelines (all but the one reading with a query), then the sample flows that run them (ADR
        // 0043). The one with sql nodes needs a source with Allow SQL on, which e2e-db does not have:
        // skipped, saying so, never a draft. The one that checks a source and waits for a file needs none.
        assertEquals(30, added.getInt("added"), added.prettify());
        assertEquals(List.of("sample-checks-and-waits", "sample-for-each-region", "sample-passing-values",
                "sample-quality-decides", "sample-retries"),
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
        // A flow's check-source node looks for the sample tables where they were created.
        String flowYaml = as(designer).get("/api/v1/flows/sample-checks-and-waits").then().statusCode(200).extract()
            .jsonPath().getString("latest.yaml");
        assertTrue(flowYaml.contains("tables: [" + SCHEMA + ".crm_customers, " + SCHEMA + ".crm_accounts]"), flowYaml);

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

        // The window sample: every transaction with its account's running balance, and one latest row per account.
        JsonPath window = waitForRun(designer, as(designer).body(Map.of()).post("/api/v1/pipelines/sample-window/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", window.getString("run.status"), window.prettify());
        long transactions = count(Samples.tableIn(SCHEMA, "billing.transactions"));
        List<String> balances = Files.readAllLines(out.resolve("samples/transform/running_balances.csv"));
        List<String> latest = Files.readAllLines(out.resolve("samples/transform/latest_per_account.csv"));
        assertEquals(transactions + 1, balances.size(), "every transaction, and the header");
        assertTrue(latest.size() > 1 && latest.size() < balances.size(), "one row per account: " + latest.size());
        assertEquals(latest.size() - 1, latest.stream().skip(1).map(l -> l.split(",")[1]).distinct().count(),
            "each account once");

        // Rows that fail (ADR 0054): the badly shaped SKU is set aside with why, and the rest are written.
        JsonPath failing = waitForRun(designer, as(designer).body(Map.of())
            .post("/api/v1/pipelines/sample-rows-that-fail/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id"));
        assertEquals("succeeded", failing.getString("run.status"), failing.prettify());
        List<String> aside = Files.readAllLines(out.resolve("samples/quality/supplier_feed_errors.csv"));
        List<String> numbered = Files.readAllLines(out.resolve("samples/quality/supplier_feed_numbered.csv"));
        assertEquals(2, aside.size(), aside.toString());
        assertTrue(aside.get(0).endsWith("error_node,error_column,error_code,error_message"), aside.toString());
        assertTrue(aside.get(1).contains("numbered,sku_number,cast_failed,"), aside.toString());
        assertEquals(count(Samples.tableIn(SCHEMA, "stock.supplier_feed")), numbered.size() - 1 + aside.size() - 1,
            "every row read is written or set aside");

        // The volume sample: four million rows from the sample data itself, totalled and the large lines set
        // apart, in one run.
        JsonPath volume = waitForRun(designer, as(designer).body(Map.of()).post("/api/v1/pipelines/sample-volume/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", volume.getString("run.status"), volume.prettify());
        assertTrue(Files.exists(out.resolve("samples/volume/takings_by_category_and_channel.csv")));
        assertTrue(volume.getLong("run.rowsWritten") > 24, volume.prettify());

        // The functions sample: padded numbers, a split reference, dates, bands and a hashed email, never the email.
        JsonPath functions = waitForRun(designer, as(designer).body(Map.of())
            .post("/api/v1/pipelines/sample-functions/runs").then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", functions.getString("run.status"), functions.prettify());
        List<String> invoices = Files.readAllLines(out.resolve("samples/transform/invoices_with_functions.csv"));
        assertEquals(count(Samples.tableIn(SCHEMA, "billing.invoices")) + 1, invoices.size());
        List<String> header = List.of(invoices.get(0).split(","));
        assertTrue(header.containsAll(List.of("invoice_no", "customer_number", "due_on", "band", "days_overdue",
            "short_no", "email_hash", "in_bristol")) && !header.contains("email"), header.toString());
        java.util.Map<String, String> first = new java.util.HashMap<>();
        String[] row = invoices.get(1).split(",");
        for (int i = 0; i < header.size(); i++) {
            first.put(header.get(i), row[i]);
        }
        assertEquals("0000007001", first.get("invoice_no"), first.toString());
        assertEquals("7001", first.get("short_no"), first.toString());
        assertTrue(first.get("email_hash").matches("[0-9a-f]{64}"), first.toString());

        // Which row wins: one customer per email, the latest created; each product's latest delivery price.
        JsonPath which = waitForRun(designer, as(designer).body(Map.of())
            .post("/api/v1/pipelines/sample-which-row/runs").then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", which.getString("run.status"), which.prettify());
        long customers = count(Samples.tableIn(SCHEMA, "crm.customers"));
        long latestCustomers = Files.readAllLines(out.resolve("samples/transform/customers_latest_per_email.csv"))
            .size() - 1L;
        long older = Files.readAllLines(out.resolve("samples/transform/customers_older_repeats.csv")).size() - 1L;
        assertTrue(older > 0 && latestCustomers + older == customers, latestCustomers + " + " + older);
        List<String> prices = Files.readAllLines(out.resolve("samples/transform/products_latest_cost.csv"));
        assertEquals(count(Samples.tableIn(SCHEMA, "stock.products")) + 1, prices.size());
        // The feed lists KIT-1005 twice: 16.68, then a later delivery at 18.35, which wins.
        assertTrue(prices.stream().anyMatch(l -> l.contains(",KIT-1005,") && l.contains(",18.35,")),
            String.join("\n", prices));

        // The flows that check a source and wait for a file, and that try again: both run through.
        JsonPath checks = waitForFlowRun(designer, as(designer).body(Map.of())
            .post("/api/v1/flows/sample-checks-and-waits/runs").then().statusCode(202).extract().jsonPath()
            .getLong("id"));
        assertEquals("succeeded", checks.getString("run.status"), checks.prettify());
        assertEquals(List.of("succeeded"), checks.getList("nodes.findAll { it.node in ['ready', 'accounts', "
            + "'landed', 'customers'] }.status").stream().distinct().toList(), checks.prettify());
        JsonPath retried = waitForFlowRun(designer, as(designer).body(Map.of())
            .post("/api/v1/flows/sample-retries/runs").then().statusCode(202).extract().jsonPath().getLong("id"));
        assertEquals("succeeded", retried.getString("run.status"), retried.prettify());

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
