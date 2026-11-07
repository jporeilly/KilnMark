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
 * The join node on the server: in the catalogue, checked when a pipeline is validated, previewed, and
 * run end to end on a worker, sorting both inputs on disk past its memory limit and saying so in the
 * run's log. Sources are the in-memory test connector: two of them, of 60,000 and 40,000 people.
 */
@QuarkusTest
class JoinPipelineTest {

    static final String YAML = """
        kilnmark: pipeline/v1
        name: people-joined
        description: Synthetic people, joined to fewer synthetic people.
        nodes:
          - id: many
            type: read
            source: join-many
            asset: lab.people

          - id: few
            type: read
            source: join-few
            asset: lab.people

          - id: joined
            type: join
            inputs: { left: many, right: few }
            on: { id: id }
            how: left
            memory_limit_mb: 1

          - id: out
            type: write
            input: joined
            source: join-out
            table: lab.people_joined
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
        designer = admin().contentType(ContentType.JSON).body(Map.of("name", "join-designer", "role", "designer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        admin().contentType(ContentType.JSON).body(Map.of("name", "join-many", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "60000"), "password", "join-many-secret")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "join-few", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "40000"), "password", "join-few-secret")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "join-out", "kind", MemoryConnector.KIND,
            "settings", Map.of("allow_writing", "true"), "password", "join-out-secret")).post("/api/v1/sources")
            .then().statusCode(201);
        // Scan the sources read from, so validation knows their columns.
        for (String source : List.of("join-many", "join-few")) {
            long scan = as(designer).post("/api/v1/sources/" + source + "/scans").then().statusCode(202).extract()
                .jsonPath().getLong("id");
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
    }

    @Test
    void theCatalogueListsItAndValidationChecksItsKeysAndNames() {
        sources();
        JsonPath nodes = as(designer).get("/api/v1/node-types").then().statusCode(200).extract().jsonPath();
        assertEquals("Combine", nodes.getString("find { it.type == 'join' }.category"));
        assertEquals(List.of("left", "right"), nodes.getList("find { it.type == 'join' }.inputs"));
        assertEquals(List.of("on", "how", "right_prefix", "memory_limit_mb"),
            nodes.getList("find { it.type == 'join' }.settings.key"));

        as(designer).body(Map.of("yaml", YAML)).post("/api/v1/pipelines/validate").then().statusCode(200)
            .body("valid", equalTo(true))
            .body("nodes.find { it.id == 'joined' }.columns.out.name",
                equalTo(List.of("id", "name", "right_id", "right_name")));
        as(designer).body(Map.of("yaml", YAML.replace("on: { id: id }", "on: { id: nmae }")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node joined: on: the right input has no column 'nmae'; did you mean name?"));
        as(designer).body(Map.of("yaml", YAML.replace("on: { id: id }", "on: { id: name }")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false))
            .body("problems.message", hasItems("Node joined: on: the left column 'id' (int64) cannot be matched with the "
                + "right column 'name' (utf8); cast one of them first"));
        as(designer).body(Map.of("yaml", YAML.replace("how: left", "how: outer")))
            .post("/api/v1/pipelines/validate").then().statusCode(200).body("valid", equalTo(false));
    }

    @Test
    void itIsPreviewed() {
        sources();
        JsonPath p = as(designer).body(Map.of("yaml", YAML, "node", "joined", "rows", 2))
            .post("/api/v1/pipelines/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("1", "Person 1", "1", "Person 1"), p.getList("rows[0]"));
    }

    @Test
    void aRunJoinsOnDiskPastItsMemoryLimitAndSaysSo() throws Exception {
        sources();
        as(designer).body(Map.of("name", "people-joined", "yaml", YAML)).post("/api/v1/pipelines").then()
            .statusCode(201);
        long id = as(designer).post("/api/v1/pipelines/people-joined/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        JsonPath run = waitForRun(id);
        assertEquals("succeeded", run.getString("run.status"), run.prettify());
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'joined' }.ports.in.left"));
        assertEquals(40000L, run.getLong("nodes.find { it.node == 'joined' }.ports.in.right"));
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'joined' }.rowsOut"), "left: every left row once");
        assertEquals(60000L, run.getLong("nodes.find { it.node == 'out' }.rowsIn"));
        JsonPath log = as(designer).get("/api/v1/pipeline-runs/" + id + "/events").then().statusCode(200).extract()
            .jsonPath();
        List<String> texts = log.getList("events.text");
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("Node joined: Sorted the ") && t.contains(" on disk: the two took more than 1 MB")),
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
