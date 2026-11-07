package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * The window node on the server: in the catalogue, checked when a pipeline is validated, previewed,
 * and run end to end on a worker, putting rows in order on disk past its memory limit and saying so
 * in the run's log. Sources are the in-memory test connector.
 */
@QuarkusTest
class WindowPipelineTest {

    static final String YAML = """
        kilnmark: pipeline/v1
        name: people-numbered
        description: Synthetic people in ten groups, numbered with running totals.
        nodes:
          - id: people
            type: read
            source: window-src
            asset: lab.people

          - id: grouped
            type: compute
            input: people
            columns:
              grp: "id % 10"

          - id: numbered
            type: window
            input: grouped
            partition_by: [grp]
            order_by: [id desc]
            columns:
              n: "row_number()"
              total: "sum(id)"
              before: "lag(name, 1, 'none')"
            memory_limit_mb: 1

          - id: out
            type: write
            input: numbered
            source: window-out
            table: lab.people_numbered
            mode: replace
        """;

    static String designer;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    /** Once, from the first test: the application is up only once a test runs. */
    static synchronized void sources() {
        if (designer != null) {
            return;
        }
        designer = admin().contentType(ContentType.JSON).body(Map.of("name", "window-designer", "role", "designer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        admin().contentType(ContentType.JSON).body(Map.of("name", "window-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "60000"), "password", "window-in-secret")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "window-out", "kind", MemoryConnector.KIND,
            "settings", Map.of("allow_writing", "true"), "password", "window-out-secret")).post("/api/v1/sources")
            .then().statusCode(201);
        // Scan the source read from, so validation knows its columns.
        long scan = as(designer).post("/api/v1/sources/window-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !"succeeded".equals(status)) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            status = as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", status);
    }

    @Test
    void theCatalogueListsItAndValidationChecksItsColumns() {
        sources();
        JsonPath nodes = as(designer).get("/api/v1/node-types").then().statusCode(200).extract().jsonPath();
        assertEquals("Transform", nodes.getString("find { it.type == 'window' }.category"));
        assertEquals(List.of("partition_by", "order_by", "columns", "memory_limit_mb"),
            nodes.getList("find { it.type == 'window' }.settings.key"));
        assertEquals("order", nodes.getString("find { it.type == 'window' }.settings.find { it.key == 'order_by' }.kind"));

        as(designer).body(Map.of("yaml", YAML)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'numbered' }.columns.out.name",
                equalTo(List.of("id", "name", "grp", "n", "total", "before")));
        as(designer).body(Map.of("yaml", YAML.replace("[id desc]", "[ib desc]")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node numbered: order_by: the input has no column 'ib'; did you mean id?"));
        as(designer).body(Map.of("yaml", YAML.replace("total: \"sum(id)\"", "name: \"sum(id)\"")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node numbered: column name: the input already has a column called "
                + "'name'; give the new column another name, or drop that column with a select node first"));
        as(designer).body(Map.of("yaml", YAML.replace("\"sum(id)\"", "\"sum(name)\"")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node numbered: column total: sum needs numbers, not utf8 (text) at 1"));
    }

    @Test
    void itIsPreviewed() {
        sources();
        JsonPath p = as(designer).body(Map.of("yaml", YAML, "node", "numbered", "rows", 3))
            .post("/api/v1/pipelines/preview").then().statusCode(200).extract().jsonPath();
        // group 0 first: ids 60000, 59990, 59980, each numbered and totalled
        assertEquals(List.of("60000", "59990", "59980"), p.getList("rows.collect { it[0] }"));
        assertEquals(List.of("1", "2", "3"), p.getList("rows.collect { it[3] }"));
        assertEquals(List.of("60000", "119990", "179970"), p.getList("rows.collect { it[4] }"));
    }

    @Test
    void aRunOrdersRowsOnDiskPastItsMemoryLimitAndSaysSo() throws Exception {
        sources();
        as(designer).body(Map.of("name", "people-numbered", "yaml", YAML)).post("/api/v1/pipelines").then()
            .statusCode(201);
        long id = as(designer).post("/api/v1/pipelines/people-numbered/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath run = waitForRun(id);
        assertEquals("succeeded", run.getString("run.status"), run.prettify());
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'numbered' }.rowsIn"));
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'numbered' }.rowsOut"));
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'out' }.rowsIn"));
        JsonPath log = as(designer).get("/api/v1/pipeline-runs/" + id + "/events").then().statusCode(200).extract()
            .jsonPath();
        List<String> texts = log.getList("events.text");
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("Node numbered: Put 60,000 rows in order on disk for the "
            + "window functions: they took more than 1 MB")), texts.toString());
    }

    private static JsonPath waitForRun(long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = as(designer).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            String status = run.getString("run.status");
            if (!"queued".equals(status) && !"running".equals(status)) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + id + " did not end: " + (run == null ? null : run.prettify()));
    }
}
