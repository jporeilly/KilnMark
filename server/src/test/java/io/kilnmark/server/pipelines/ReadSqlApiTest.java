package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.as;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * {@code POST /api/v1/pipelines/read-sql}: any signed-in role may see what a read node sends to its
 * database (the canvas shows it read-only); only a read node has SQL of its own. The statements
 * themselves are worked out in {@link ReadSqlTest}.
 */
@QuarkusTest
class ReadSqlApiTest {

    private static final String YAML = """
        kilnmark: pipeline/v1
        name: read-sql
        nodes:
          - id: people
            type: read
            source: nowhere
            asset: crm.customers
          - id: active
            type: filter
            input: people
            condition: status = 'active'
        """;

    @Test
    void aViewerSeesWhyAReadHasNoSqlYet() {
        String viewer = newToken("viewer", false);
        as(viewer).contentType(ContentType.JSON).body(Map.of("yaml", YAML, "node", "people"))
            .post("/api/v1/pipelines/read-sql").then().statusCode(200)
            .body("node", equalTo("people"))
            .body("sql", nullValue())
            .body("values", equalTo(0))
            .body("note", equalTo("There is no saved source by that name."));
    }

    @Test
    void onlyAReadNodeThatIsThereHasSql() {
        String viewer = newToken("viewer", false);
        as(viewer).contentType(ContentType.JSON).body(Map.of("yaml", YAML, "node", "active"))
            .post("/api/v1/pipelines/read-sql").then().statusCode(400)
            .body("error", startsWith("node: only a read node"));
        as(viewer).contentType(ContentType.JSON).body(Map.of("yaml", YAML, "node", "missing"))
            .post("/api/v1/pipelines/read-sql").then().statusCode(400)
            .body("error", startsWith("node: the pipeline has no node"));
        as(viewer).contentType(ContentType.JSON).body(Map.of("yaml", YAML))
            .post("/api/v1/pipelines/read-sql").then().statusCode(400);
    }
}
