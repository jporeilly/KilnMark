package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InfoResourceTest {

    @Test
    void reportsAllRolesByDefault() {
        given().when().get("/api/v1/info")
                .then().statusCode(200)
                .body("product", equalTo("Kilnmark"))
                .body("roles", containsInAnyOrder("API", "SCHEDULER", "WORKER"));
    }
}
