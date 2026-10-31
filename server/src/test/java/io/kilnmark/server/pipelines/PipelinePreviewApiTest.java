package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.quality.Masking;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * {@code POST /api/v1/pipelines/preview} and {@code .../versions/{n}/preview} on PostgreSQL: roles,
 * the error format, rows from unsaved YAML, a saved version, refused writes, masking of a column
 * marked sensitive for stewards (not admins) and nothing kept by caches. The source is the
 * in-memory test connector (synthetic people).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelinePreviewApiTest {

    static final String URN = "urn:kilnmark:preview-src:lab.people";
    static final String YAML = """
        kilnmark: pipeline/v1
        name: preview-people
        nodes:
          - id: people
            type: read
            source: preview-src
            asset: lab.people
          - id: tidy
            type: compute
            input: people
            columns:
              shout: "upper(name)"
          - id: named
            type: filter
            input: people
            condition: "name like 'Person 1%'"
          - id: out
            type: write
            input: tidy
            source: preview-src
            table: lab.people_copy
        """;

    static String designer;
    static String viewer;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    @Test
    @Order(1)
    void stewardsPreviewUnsavedYaml() throws Exception {
        designer = token("preview-designer", "designer");
        viewer = token("preview-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "preview-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "100000"), "password", "preview-secret-55")).post("/api/v1/sources").then()
            .statusCode(201);

        JsonPath p = as(designer).body(Map.of("yaml", YAML, "node", "tidy", "rows", 7)).post("/api/v1/pipelines/preview")
            .then().statusCode(200).header("Cache-Control", containsString("no-store")).extract().jsonPath();
        assertEquals("tidy", p.getString("node"));
        assertEquals("out", p.getString("port"));
        assertEquals(7, p.getInt("rowCount"));
        assertEquals(List.of("id", "name", "shout"), p.getList("columns.name"));
        assertEquals(List.of("int64", "text", "text"), p.getList("columns.kind"));
        assertEquals(List.of("1", "Person 1", "PERSON 1"), p.getList("rows[0]"));
        assertEquals(false, p.getBoolean("complete"));
        assertEquals(List.of(), p.getList("masked"));
        assertTrue(p.getLong("millis") >= 0);
    }

    @Test
    @Order(2)
    void viewersAndStrangersGetTheUsualErrors() {
        as(viewer).body(Map.of("yaml", YAML, "node", "tidy")).post("/api/v1/pipelines/preview").then().statusCode(403);
        given().contentType(ContentType.JSON).body(Map.of("yaml", YAML, "node", "tidy"))
            .post("/api/v1/pipelines/preview").then().statusCode(401);
    }

    @Test
    @Order(3)
    void writesAndBadRequestsAreRefusedPlainly() {
        as(designer).body(Map.of("yaml", YAML, "node", "out")).post("/api/v1/pipelines/preview").then().statusCode(400)
            .body("error", containsString("writes rows"));
        as(designer).body(Map.of("yaml", YAML, "node", "nowhere")).post("/api/v1/pipelines/preview").then()
            .statusCode(400).body("error", startsWith("node: there is no node called 'nowhere'"));
        as(designer).body(Map.of("yaml", YAML, "node", "tidy", "rows", 5000)).post("/api/v1/pipelines/preview").then()
            .statusCode(400).body("error", equalTo("rows: from 1 to 1000"));
        as(designer).body(Map.of("yaml", YAML, "node", "named", "port", "nope")).post("/api/v1/pipelines/preview")
            .then().statusCode(400).body("error", containsString("its outputs are out, rejected"));
    }

    @Test
    @Order(4)
    void aSavedVersionCanBePreviewed() {
        as(designer).body(Map.of("name", "preview-people", "yaml", YAML)).post("/api/v1/pipelines").then()
            .statusCode(201);
        as(designer).body(Map.of("node", "named", "port", "rejected", "rows", 3))
            .post("/api/v1/pipelines/preview-people/versions/1/preview").then().statusCode(200)
            .body("port", equalTo("rejected")).body("rows[0][1]", equalTo("Person 2"));
        as(designer).body(Map.of("node", "named")).post("/api/v1/pipelines/preview-people/versions/9/preview").then()
            .statusCode(404);
        Tokens.as(designer).contentType(ContentType.TEXT).body("node=named")
            .post("/api/v1/pipelines/preview-people/versions/1/preview").then().statusCode(415);
        as(viewer).body(Map.of("node", "named")).post("/api/v1/pipelines/preview-people/versions/1/preview").then()
            .statusCode(403);
    }

    @Test
    @Order(5)
    void aSensitiveColumnIsMaskedForStewardsAndItsRowsCannotBeChosen() throws Exception {
        // Catalogue the asset, then mark its name sensitive.
        long scan = as(designer).post("/api/v1/sources/preview-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !"succeeded".equals(status)) {
            Thread.sleep(250);
            status = as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", status);
        as(designer).body(Map.of("field", "name", "sensitive", true)).queryParam("urn", URN)
            .put("/api/v1/quality/asset/sensitive").then().statusCode(204);

        JsonPath masked = as(designer).body(Map.of("yaml", YAML, "node", "people", "rows", 3))
            .post("/api/v1/pipelines/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("name"), masked.getList("masked"));
        assertEquals(List.of("1", Masking.MASK), masked.getList("rows[0]"));
        // Nothing upstream may look inside it: a calculation or a condition on it is refused.
        as(designer).body(Map.of("yaml", YAML, "node", "tidy")).post("/api/v1/pipelines/preview").then()
            .statusCode(403).body("error", startsWith("Only an admin can preview this: node tidy (compute) works out "
                + "a value from name"));
        as(designer).body(Map.of("yaml", YAML, "node", "named")).post("/api/v1/pipelines/preview").then()
            .statusCode(403).body("error", startsWith("Only an admin can preview this: node named (filter) chooses "
                + "rows by name"));

        JsonPath open = as(Tokens.ADMIN).body(Map.of("yaml", YAML, "node", "tidy", "rows", 3))
            .post("/api/v1/pipelines/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), open.getList("masked"));
        assertEquals("PERSON 1", open.getString("rows[0][2]"));
        as(Tokens.ADMIN).body(Map.of("yaml", YAML, "node", "named", "rows", 2)).post("/api/v1/pipelines/preview")
            .then().statusCode(200).body("rowCount", equalTo(2));
    }
}
