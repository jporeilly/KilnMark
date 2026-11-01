package io.kilnmark.server;

import static io.restassured.RestAssured.given;

import io.restassured.specification.RequestSpecification;

/** Signing in from tests: the admin token configured for the test profile, or any other. */
public final class Tokens {

    /** Matches {@code %test.kilnmark.admin-token} in application.properties. */
    public static final String ADMIN = "km_test-admin-0123456789abcdefghijklmnopqrstuvwxyzABCDEF";

    private Tokens() {
    }

    /** A request signed in as the test admin. */
    public static RequestSpecification admin() {
        return as(ADMIN);
    }

    /** A request signed in with {@code token}. */
    public static RequestSpecification as(String token) {
        return given().header("Authorization", "Bearer " + token);
    }

    /**
     * A request signed in as a designer whose token sees unmasked values (ADR 0027): the only kind
     * of caller that does, admins included. A new token is created for each request, by the admin.
     */
    public static RequestSpecification designer() {
        return as(newToken("designer", true));
    }

    /** A new token with {@code role}, created by the test admin; {@code unmask} as asked. */
    public static String newToken(String role, boolean unmask) {
        return admin().contentType("application/json")
            .body(java.util.Map.of("name", "test " + role, "role", role, "unmask", unmask))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }
}
