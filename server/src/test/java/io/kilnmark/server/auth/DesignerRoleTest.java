package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * The designer role: designers build pipelines and sources and turn on writing; stewards run what
 * is built and no longer build; admin-only kinds of source stay with admins. Unmasking is a grant of
 * its own, held only by designer tokens (UnmaskGrantTest, ADR 0027).
 */
@QuarkusTest
class DesignerRoleTest {

    static final String YAML = """
        kilnmark: pipeline/v1
        name: %s
        nodes:
          - id: rows
            type: read
            source: roles-src
            asset: lab.people
        """;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).body("role", equalTo(role)).extract().jsonPath().getString("token");
    }

    @Test
    void designersBuildStewardsRun() {
        String steward = token("roles-steward", "steward");
        String designer = token("roles-designer", "designer");
        try {
            // Sources: designers add and change them, and turn on writing; stewards may not.
            as(steward).body(Map.of("name", "roles-src", "kind", MemoryConnector.KIND, "settings", Map.of(),
                "password", "roles-secret-1")).post("/api/v1/sources").then().statusCode(403);
            as(designer).body(Map.of("name", "roles-src", "kind", MemoryConnector.KIND, "settings",
                Map.of("rows", "10"), "password", "roles-secret-1")).post("/api/v1/sources").then().statusCode(201);
            as(steward).body(Map.of("settings", Map.of("rows", "10", "allow_writing", "true")))
                .put("/api/v1/sources/roles-src").then().statusCode(403);
            as(designer).body(Map.of("settings", Map.of("rows", "10", "allow_writing", "true")))
                .put("/api/v1/sources/roles-src").then().statusCode(200).body("settings.allow_writing", equalTo("true"));
            // Admin-only kinds stay with admins, however the kind is written, in the form and in an import.
            for (String kind : List.of("jdbc", "jdbc ", " jdbc")) {
                as(designer).body(Map.of("name", "roles-jdbc", "kind", kind, "settings",
                    Map.of("url", "jdbc:h2:mem:x"))).post("/api/v1/sources").then().statusCode(403)
                    .body("error", containsString("Only admins"));
                as(designer).body(Map.of("name", "roles-jdbc", "kind", kind, "settings",
                    Map.of("url", "jdbc:h2:mem:x"))).post("/api/v1/drafts/test").then().statusCode(403);
            }
            Tokens.as(designer).contentType("text/csv").body("name,kind,url\nroles-jdbc,jdbc ,jdbc:h2:mem:x\n")
                .post("/api/v1/sources/import").then().statusCode(422)
                .body("rows[0].error", containsString("Only admins"));
            admin().get("/api/v1/sources/roles-jdbc").then().statusCode(404);

            // Pipelines: designers add them; stewards run them but cannot add, change or delete them.
            as(steward).body(Map.of("name", "roles-p", "yaml", YAML.formatted("roles-p"))).post("/api/v1/pipelines")
                .then().statusCode(403);
            as(designer).body(Map.of("name", "roles-p", "yaml", YAML.formatted("roles-p"))).post("/api/v1/pipelines")
                .then().statusCode(201);
            as(steward).body(Map.of("yaml", YAML.formatted("roles-p"), "message", "x")).put("/api/v1/pipelines/roles-p")
                .then().statusCode(403);
            as(steward).body(Map.of()).post("/api/v1/pipelines/roles-p/runs").then().statusCode(202);
            as(steward).delete("/api/v1/pipelines/roles-p").then().statusCode(403);

            // Server variables: designers set them; stewards read them.
            as(steward).body(Map.of("type", "text", "value", "EMEA")).put("/api/v1/variables/roles_region").then()
                .statusCode(403);
            as(designer).body(Map.of("type", "text", "value", "EMEA")).put("/api/v1/variables/roles_region").then()
                .statusCode(200);
            as(steward).get("/api/v1/variables/roles_region").then().statusCode(200);

            // Settings and tokens stay with admins.
            as(designer).body(Map.of("offered", false)).put("/api/v1/samples/offered").then().statusCode(403);
            as(designer).get("/api/v1/tokens").then().statusCode(403);
        } finally {
            for (String path : List.of("/api/v1/variables/roles_region", "/api/v1/pipelines/roles-p",
                "/api/v1/sources/roles-src")) {
                admin().delete(path);
            }
        }
    }
}
