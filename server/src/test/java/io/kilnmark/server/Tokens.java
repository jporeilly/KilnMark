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
}
