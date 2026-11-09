package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        String designer = token("samples-designer", "designer");
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
        as(designer).body(Map.of("sources", Map.of())).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("choose a saved source"));
        as(designer).body(Map.of("sources", Map.of("lab-db", "x"))).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("not a placeholder"));
        as(designer).body(body).post("/api/v1/samples").then().statusCode(400)
            .body("error", containsString("names a source that is not saved")).body("error", not(containsString("samples-")));

        admin().contentType(ContentType.JSON).body(Map.of("name", "samples-crm", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "10"), "password", "samples-secret-1")).post("/api/v1/sources")
            .then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "samples-files", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", "samples-secret-2")).post("/api/v1/sources").then().statusCode(201);

        JsonPath added = as(designer).body(body).post("/api/v1/samples").then().statusCode(200).extract().jsonPath();
        assertEquals("added", added.getString("samples.find { it.name == 'sample-read-write-csv' }.outcome"));
        assertEquals("skipped", added.getString("samples.find { it.name == 'sample-filter' }.outcome"));
        assertEquals("no source chosen for shop-db", added.getString("samples.find { it.name == 'sample-filter' }"
            + ".reason"));
        // A flow whose pipelines were not all added is skipped, and says which is missing.
        assertEquals("skipped", added.getString("samples.find { it.name == 'sample-quality-decides' }.outcome"));
        assertTrue(added.getString("samples.find { it.name == 'sample-quality-decides' }.reason")
            .contains("which is not here"), added.prettify());
        assertTrue(added.getInt("added") > 0);

        // Added samples are marked for the Library; a team's own pipelines are not (ADR 0043).
        JsonPath pipelines = as(viewer).get("/api/v1/pipelines").then().statusCode(200).extract().jsonPath();
        assertEquals("sample", pipelines.getString("find { it.name == 'sample-read-write-csv' }.library"));
        assertEquals("sample", as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200)
            .extract().jsonPath().getString("library"));
        // The templates are never listed or added as samples.
        assertFalse(list.getList("samples.file", String.class).stream().anyMatch(f -> f.startsWith("templates/")));
        assertFalse(added.getList("samples.file", String.class).stream().anyMatch(f -> f.startsWith("templates/")));

        // Saved with the chosen sources in place of the placeholders.
        String yaml = as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200)
            .extract().jsonPath().getString("latest.yaml");
        assertTrue(yaml.contains("source: samples-crm") && yaml.contains("source: samples-files"), yaml);
        // The explanation at the top still names the placeholders it needs; no setting does.
        String settings = String.join("\n", yaml.lines().filter(l -> !l.startsWith("#")).toList());
        assertFalse(settings.contains("crm-db") || settings.contains("source: files"), yaml);

        aboutTheSample(designer, viewer, yaml);

        // Again: what is there is left as it is.
        as(designer).body(body).post("/api/v1/samples").then().statusCode(200)
            .body("added", equalTo(0))
            .body("samples.findAll { it.name == 'sample-read-write-csv' }.outcome", hasItems("exists"));
        as(viewer).get("/api/v1/samples").then().statusCode(200)
            .body("samples.find { it.name == 'sample-read-write-csv' }.exists", equalTo(true));
    }

    /**
     * A pipeline added from the samples carries its sample's explanation, from the bundled file, and
     * whether it has been changed since: moving its nodes is not a change, a new setting is. Its
     * versions list the parameters they declare.
     */
    private static void aboutTheSample(String designer, String viewer, String added) {
        Samples.Explanation bundled = Samples.bundled().stream().filter(s -> s.name().equals("sample-read-write-csv"))
            .findFirst().orElseThrow().explanation();
        JsonPath d = as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200).extract().jsonPath();
        assertEquals("01-read-and-write/read-table-write-csv.yaml", d.getString("sample.file"));
        assertEquals(bundled.shows(), d.getString("sample.explanation.shows"));
        assertEquals(bundled.why(), d.getString("sample.explanation.why"));
        assertEquals(bundled.lookFor(), d.getString("sample.explanation.lookFor"));
        assertEquals(bundled.needs(), d.getString("sample.explanation.needs"));
        assertFalse(d.getBoolean("sample.changed"));
        assertEquals(List.of(), d.getList("latest.parameters"));

        // Moving its nodes on the canvas changes only the layout: still as added.
        String moved = added + "\n# Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.\n"
            + "layout:\n  accounts: {x: 0, y: 0}\n  out: {x: 240, y: 0}\n";
        as(designer).header("If-Match", "\"1\"").body(Map.of("yaml", moved, "message", "Moved nodes"))
            .put("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200);
        d = as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200).extract().jsonPath();
        assertEquals(2, d.getInt("latestVersion"));
        assertFalse(d.getBoolean("sample.changed"));

        // A parameter of its own: changed since, and the parameter listed with its kind and default.
        String changed = moved.replace("nodes:\n", "parameters:\n  label: { type: text, default: accounts, description: "
            + "Names the file }\n\nnodes:\n");
        as(designer).header("If-Match", "\"2\"").body(Map.of("yaml", changed, "message", "A label"))
            .put("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200);
        d = as(viewer).get("/api/v1/pipelines/sample-read-write-csv").then().statusCode(200).extract().jsonPath();
        assertTrue(d.getBoolean("sample.changed"), d.prettify());
        assertEquals(bundled.shows(), d.getString("sample.explanation.shows"));
        assertEquals("label", d.getString("latest.parameters[0].name"));
        assertEquals("text", d.getString("latest.parameters[0].type"));
        assertEquals("accounts", d.getString("latest.parameters[0].defaultValue"));
        assertEquals("Names the file", d.getString("latest.parameters[0].description"));
        assertFalse(d.getBoolean("latest.parameters[0].required"));

        // A pipeline of a team's own, even one named like a sample, has none.
        String own = changed.replace("name: sample-read-write-csv", "name: sample-own-copy");
        as(designer).body(Map.of("name", "sample-own-copy", "yaml", own, "message", "Mine")).post("/api/v1/pipelines")
            .then().statusCode(201);
        try {
            JsonPath o = as(viewer).get("/api/v1/pipelines/sample-own-copy").then().statusCode(200).extract().jsonPath();
            assertNull(o.get("sample"));
            assertNull(o.get("library"));
        } finally {
            admin().delete("/api/v1/pipelines/sample-own-copy");
        }
    }

    @Test
    void withoutLayoutLeavesOutOnlyWhereTheNodesAreDrawn() {
        String yaml = "name: x\nnodes:\n  - id: a\n";
        String drawn = yaml + "\n# Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.\n"
            + "layout:\n  a: {x: 1, y: 2}\n";
        assertEquals(Samples.withoutLayout(yaml), Samples.withoutLayout(drawn));
        assertEquals(Samples.withoutLayout(yaml), Samples.withoutLayout(drawn.replace("\n", "\r\n")));
        assertFalse(Samples.withoutLayout(yaml).equals(Samples.withoutLayout(yaml + "description: y\n")));
        // A key after the layout block is kept.
        assertTrue(Samples.withoutLayout(drawn + "quality: {min_score: 0.9}\n").contains("quality:"));
    }

    /** The templates: listed with their YAML for any signed-in person, even with the samples turned off. */
    @Test
    void theTemplatesAreListedWithTheirYaml() {
        String viewer = token("templates-viewer", "viewer");
        JsonPath t = as(viewer).get("/api/v1/templates").then().statusCode(200).extract().jsonPath();
        List<String> names = t.getList("name");
        assertTrue(names.containsAll(List.of("template-copy-table", "template-run-in-order")), names.toString());
        assertEquals("flow", t.getString("find { it.name == 'template-run-in-order' }.type"));
        assertTrue(t.getString("find { it.name == 'template-copy-table' }.yaml").contains("source: <your source"));
        given().get("/api/v1/templates").then().statusCode(401);
        try {
            as(Tokens.ADMIN).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(200);
            as(viewer).get("/api/v1/templates").then().statusCode(200);
        } finally {
            as(Tokens.ADMIN).body(Map.of("offered", true)).put("/api/v1/samples/offered").then().statusCode(200);
        }
    }

    @Test
    void anAdminCanStopOfferingTheSamples() {
        String designer = token("samples-off-designer", "designer");
        String viewer = token("samples-off-viewer", "viewer");
        as(viewer).get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(true));
        as(designer).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(403);
        try {
            as(Tokens.ADMIN).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(200)
                .body("offered", equalTo(false));
            as(viewer).get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(false));
            as(viewer).get("/api/v1/samples").then().statusCode(403).body("error", containsString("not offered"));
            as(designer).body(Map.of("sources", Map.of("files", "x"))).post("/api/v1/samples").then().statusCode(403);
            as(designer).body(Map.of("source", "x", "schema", "public")).post("/api/v1/samples/tables").then()
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
