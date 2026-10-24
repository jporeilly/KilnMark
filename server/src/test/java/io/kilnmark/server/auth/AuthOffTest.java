package io.kilnmark.server.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/** {@code kilnmark.auth.mode=off} on a loopback address: everyone is an admin, and no tokens can be made. */
@QuarkusTest
@TestProfile(AuthOffTest.Off.class)
class AuthOffTest {

    /** Sign-in off, listening on 127.0.0.1 only. */
    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.auth.mode", "off", "quarkus.http.host", "127.0.0.1", "kilnmark.roles", "api");
        }
    }

    @Test
    void everyCallerIsAnAdminButTokensCannotBeCreated() {
        given().get("/api/v1/me").then().statusCode(200)
            .body("role", equalTo("admin")).body("signInRequired", equalTo(false));
        given().get("/api/v1/sources").then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("name", "x", "role", "admin"))
            .post("/api/v1/tokens").then().statusCode(409).body("error", containsString("Sign-in is off"));
    }
}
