package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** The web app's page is never kept from one release to the next (PageCaching). */
@QuarkusTest
class PageCachingTest {

    @Test
    void thePageIsCheckedOnEveryLoad() {
        given().get("/").then().header("Cache-Control", equalTo("no-cache"));
        given().get("/index.html").then().header("Cache-Control", equalTo("no-cache"));
    }
}
