package io.kilnmark.server.auth;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/** With sign-in off, /mcp answers only requests addressed to localhost (no DNS rebinding). */
@QuarkusTest
@TestProfile(AuthOffTest.Off.class)
class McpSignInOffTest {

    private static final String INIT = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": "
        + "{\"protocolVersion\": \"2025-06-18\", \"capabilities\": {}, \"clientInfo\": {\"name\": \"t\", \"version\": \"1\"}}}";

    @Test
    void onlyLocalHostNamesReachMcp() {
        given().header("Host", "evil.example").contentType(ContentType.JSON)
            .accept("application/json, text/event-stream").body(INIT).post("/mcp").then().statusCode(403);
        given().header("Host", "localhost").contentType(ContentType.JSON)
            .accept("application/json, text/event-stream").body(INIT).post("/mcp").then().statusCode(200);
        // The rest of the API is not affected.
        given().header("Host", "evil.example").get("/api/v1/info").then().statusCode(200);
    }
}
