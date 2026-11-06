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
 * The sort node on the server: in the catalogue, checked when a pipeline is validated, previewed in
 * order, and run end to end on a worker, sorting on disk past its memory limit and saying so in the
 * run's log. Sources are the in-memory test connector.
 */
@QuarkusTest
class SortPipelineTest {

    static final String YAML = """
        kilnmark: pipeline/v1
        name: people-sorted
        description: Synthetic people, last first.
        nodes:
          - id: people
            type: read
            source: sort-src
            asset: lab.people

          - id: ordered
            type: sort
            input: people
            by: [name desc, id]
            memory_limit_mb: 1

          - id: out
            type: write
            input: ordered
            source: sort-out
            table: lab.people_sorted
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
        designer = admin().contentType(ContentType.JSON).body(Map.of("name", "sort-designer", "role", "designer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        admin().contentType(ContentType.JSON).body(Map.of("name", "sort-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "60000"), "password", "sort-in-secret")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "sort-out", "kind", MemoryConnector.KIND,
            "settings", Map.of("allow_writing", "true"), "password", "sort-out-secret")).post("/api/v1/sources")
            .then().statusCode(201);
        // Scan the source read from, so validation knows its columns.
        long scan = as(designer).post("/api/v1/sources/sort-src/scans").then().statusCode(202).extract().jsonPath()
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
        assertEquals("Transform", nodes.getString("find { it.type == 'sort' }.category"));
        assertEquals(List.of("by", "memory_limit_mb"), nodes.getList("find { it.type == 'sort' }.settings.key"));
        assertEquals("order", nodes.getString("find { it.type == 'sort' }.settings.find { it.key == 'by' }.kind"));

        as(designer).body(Map.of("yaml", YAML)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'ordered' }.columns.out.name", equalTo(List.of("id", "name")));
        as(designer).body(Map.of("yaml", YAML.replace("[name desc, id]", "[nmae desc]")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node ordered: by: the input has no column 'nmae'; did you mean name?"));
        as(designer).body(Map.of("yaml", YAML.replace("[name desc, id]", "name desc")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems[0].message", org.hamcrest.Matchers.containsString(
                "must be a list of columns, each perhaps followed by desc"));
    }

    @Test
    void itIsPreviewedInOrder() {
        sources();
        JsonPath p = as(designer).body(Map.of("yaml", YAML, "node", "ordered", "rows", 3))
            .post("/api/v1/pipelines/preview").then().statusCode(200).extract().jsonPath();
        // "Person 9999" > "Person 9998" > ... in code point order; with 60,000 rows, "Person 9999" sorts first
        assertEquals(List.of("Person 9999", "Person 9998", "Person 9997"), p.getList("rows.collect { it[1] }"));
    }

    @Test
    void aRunSortsOnDiskPastItsMemoryLimitAndSaysSo() throws Exception {
        sources();
        as(designer).body(Map.of("name", "people-sorted", "yaml", YAML)).post("/api/v1/pipelines").then()
            .statusCode(201);
        long id = as(designer).post("/api/v1/pipelines/people-sorted/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath run = waitForRun(id);
        assertEquals("succeeded", run.getString("run.status"), run.prettify());
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'ordered' }.rowsIn"));
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'ordered' }.rowsOut"));
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'out' }.rowsIn"));
        JsonPath log = as(designer).get("/api/v1/pipeline-runs/" + id + "/events").then().statusCode(200).extract()
            .jsonPath();
        List<String> texts = log.getList("events.text");
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("Node ordered: Sorted 60,000 rows on disk: they took more than 1 MB")),
            texts.toString());
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
