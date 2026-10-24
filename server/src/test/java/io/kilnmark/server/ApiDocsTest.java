package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The interactive API docs and the OpenAPI document they read. */
@QuarkusTest
class ApiDocsTest {

    @Test
    void theOpenApiDocumentDescribesTheApiAndItsTokenSignIn() {
        given().accept("application/json").when().get("/q/openapi")
            .then().statusCode(200)
            .body("info.title", equalTo("Kilnmark API"))
            .body("components.securitySchemes.apiToken.scheme", equalTo("bearer"))
            .body("paths.keySet()", org.hamcrest.Matchers.hasItem("/api/v1/sources"));
    }

    @Test
    void theInteractiveDocsAreServedFromThisServer() {
        given().redirects().follow(true).when().get("/api/docs/")
            .then().statusCode(200)
            .body(containsString("swagger-ui"));
    }

    @Test
    void callsFromTheDocsStillNeedAToken() {
        given().when().get("/api/v1/sources").then().statusCode(401);
    }
}
