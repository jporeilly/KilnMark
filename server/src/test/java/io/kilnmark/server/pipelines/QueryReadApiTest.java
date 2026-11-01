package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.startsWith;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Writing a read that runs a query (ADR 0028): a query can read any column of its source, so only a
 * designer whose token sees unmasked values saves or previews one; admins and other designers are
 * refused. Pipelines without one are saved as before.
 */
@QuarkusTest
class QueryReadApiTest {

    private static final String YAML = """
        kilnmark: pipeline/v1
        name: %s
        nodes:
          - id: q
            type: read
            source: nowhere
            query: SELECT id FROM crm.customers
        """;

    @Test
    void onlyADesignerWhoSeesUnmaskedValuesWritesAQuery() {
        String plain = newToken("designer", false);
        String unmask = newToken("designer", true);
        as(plain).contentType(ContentType.JSON).body(Map.of("name", "query-plain", "yaml", YAML.formatted("query-plain")))
            .post("/api/v1/pipelines").then().statusCode(403).body("error", startsWith("Only a designer who may see"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "query-admin", "yaml", YAML.formatted("query-admin")))
            .post("/api/v1/pipelines").then().statusCode(403);
        as(plain).contentType(ContentType.JSON).body(Map.of("yaml", YAML.formatted("query-preview"), "node", "q"))
            .post("/api/v1/pipelines/preview").then().statusCode(403);

        // A designer who sees unmasked values saves it (a draft here: the source does not exist).
        as(unmask).contentType(ContentType.JSON).body(Map.of("name", "query-unmask", "yaml",
            YAML.formatted("query-unmask"))).post("/api/v1/pipelines").then().statusCode(201);
        // Nobody else saves a new version of it while it has the query.
        as(plain).contentType(ContentType.JSON).body(Map.of("yaml", YAML.formatted("query-unmask"), "message", "x"))
            .put("/api/v1/pipelines/query-unmask").then().statusCode(403);
        String table = YAML.formatted("query-unmask").replace("query: SELECT id FROM crm.customers",
            "asset: crm.customers");
        as(plain).contentType(ContentType.JSON).body(Map.of("yaml", table, "message", "A table instead"))
            .put("/api/v1/pipelines/query-unmask").then().statusCode(200);
    }
}
