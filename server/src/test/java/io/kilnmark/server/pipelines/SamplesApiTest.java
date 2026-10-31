package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * The bundled samples through the API: listed with their placeholders, and added with a saved
 * source for each placeholder; those whose placeholders are not all given are skipped, and one
 * already there is left as it is.
 */
@QuarkusTest
class SamplesApiTest {

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    /** Leaves no samples or sources for other tests' lists. */
    private static void tidy() {
        for (String name : List.of("sample-read-write-csv", "sample-write-parquet", "sample-read-columns-where",
            "sample-limit", "sample-compute", "sample-dedupe", "sample-union", "sample-gate-quarantine",
            "sample-volume")) {
            admin().delete("/api/v1/pipelines/" + name);
        }
        admin().delete("/api/v1/sources/" + Samples.SAMPLE_DATA);
        admin().delete("/api/v1/sources/samples-crm");
        admin().delete("/api/v1/sources/samples-files");
    }

    @Test
    void samplesAreListedAndAddedWithTheChosenSources() {
        try {
            listAndAdd();
        } finally {
            tidy();
        }
    }

    private static void listAndAdd() {
        String steward = token("samples-steward", "steward");
        String viewer = token("samples-viewer", "viewer");

        JsonPath list = as(viewer).get("/api/v1/samples").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("crm-db", "shop-db", "files"), list.getList("placeholders.name"));
        assertEquals(List.of("databases", "databases", "files"), list.getList("placeholders.category"));
        List<String> names = list.getList("samples.name");
        assertTrue(names.containsAll(List.of("sample-read-write-csv", "sample-filter", "sample-quality-decides")),
            names.toString());
        assertEquals("flow", list.getString("samples.find { it.name == 'sample-quality-decides' }.type"));
        assertEquals(List.of("crm-db", "files"), list.getList("samples.find { it.name == 'sample-read-write-csv' }"
            + ".sources"));
        assertFalse(list.getBoolean("samples.find { it.name == 'sample-read-write-csv' }.exists"));

        Map<String, Object> body = Map.of("sources", Map.of("crm-db", "samples-crm", "files", "samples-files"));
        as(viewer).body(body).post("/api/v1/samples").then().statusCode(403);
        as(steward).body(Map.of("sources", Map.of())).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("choose a saved source"));
        as(steward).body(Map.of("sources", Map.of("lab-db", "x"))).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("not a placeholder"));
        as(steward).body(body).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("no saved source called 'samples-"));

        admin().contentType(ContentType.JSON).body(Map.of("name", "samples-crm", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "10"), "password", "samples-secret-1")).post("/api/v1/sources")
            .then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "samples-files", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", "samples-secret-2")).post("/api/v1/sources").then().statusCode(201);

        JsonPath added = as(steward).body(body).post("/api/v1/samples").then().statusCode(200).extract().jsonPath();
        assertEquals("added", added.getString("samples.find { it.name == 'sample-read-write-csv' }.outcome"));
        assertEquals("skipped", added.getString("samples.find { it.name == 'sample-filter' }.outcome"));
        assertEquals("no source chosen for shop-db", added.getString("samples.find { it.name == 'sample-filter' }"
            + ".reason"));
        assertEquals("skipped", added.getString("samples.find { it.name == 'sample-quality-decides' }.outcome"));
        assertTrue(added.getInt("added") > 0);

        // Saved with the chosen sources in place of the placeholders.
        String yaml = as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200)
            .extract().jsonPath().getString("latest.yaml");
        assertTrue(yaml.contains("source: samples-crm") && yaml.contains("source: samples-files"), yaml);
        assertFalse(yaml.contains("crm-db") || yaml.contains("source: files"), yaml);

        // Again: what is there is left as it is.
        as(steward).body(body).post("/api/v1/samples").then().statusCode(200)
            .body("added", equalTo(0))
            .body("samples.findAll { it.name == 'sample-read-write-csv' }.outcome", hasItems("exists"));
        as(viewer).get("/api/v1/samples").then().statusCode(200)
            .body("samples.find { it.name == 'sample-read-write-csv' }.exists", equalTo(true));
    }

    @Test
    void anAdminCanStopOfferingTheSamples() {
        String steward = token("samples-off-steward", "steward");
        String viewer = token("samples-off-viewer", "viewer");
        as(viewer).get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(true));
        as(steward).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(403);
        try {
            as(Tokens.ADMIN).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(200)
                .body("offered", equalTo(false));
            as(viewer).get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(false));
            as(viewer).get("/api/v1/samples").then().statusCode(403).body("error", containsString("not offered"));
            as(steward).body(Map.of("sources", Map.of("files", "x"))).post("/api/v1/samples").then().statusCode(403);
            as(steward).body(Map.of("source", "x", "schema", "public")).post("/api/v1/samples/tables").then()
                .statusCode(403);
            as(Tokens.ADMIN).body(Map.of()).put("/api/v1/samples/offered").then().statusCode(400);
        } finally {
            as(Tokens.ADMIN).body(Map.of("offered", true)).put("/api/v1/samples/offered").then().statusCode(200);
        }
        as(viewer).get("/api/v1/samples").then().statusCode(200);
    }

    @Test
    void placeholdersAreReplacedOnlyAsSourceSettings() {
        String yaml = """
            nodes:
              - id: a
                source: crm-db
                asset: crm.files
              - source: "files"   # quoted
                path: files/crm-db.csv
            """;
        String out = Samples.withSources(yaml, Map.of("crm-db", "pg", "files", "bucket"));
        assertTrue(out.contains("    source: pg\n"), out);
        assertTrue(out.contains("  - source: \"bucket\"   # quoted\n"), out);
        assertTrue(out.contains("asset: crm.files") && out.contains("path: files/crm-db.csv"), out);
        assertEquals(List.of("crm-db", "files"), Samples.sourcesOf(yaml));
        // A source name YAML would read as something other than text is quoted.
        out = Samples.withSources(yaml, Map.of("crm-db", "null", "files", "123"));
        assertTrue(out.contains("    source: \"null\"\n") && out.contains("  - source: \"123\"   # quoted\n"), out);
        assertTrue(Samples.withSources(yaml, Map.of("crm-db", "On")).contains("    source: \"On\"\n"));
    }
}
