package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * A second admin approves the unmask grant (ADR 0038): with kilnmark.auth.unmask-approval on, a
 * designer token asked to see unmasked values is made without the grant until another admin
 * approves it; the admin who asked cannot.
 */
@QuarkusTest
@TestProfile(UnmaskApprovalTest.Required.class)
class UnmaskApprovalTest {

    public static class Required implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.auth.unmask-approval", "true");
        }
    }

    @Inject
    AuditLog audit;

    /** A new token made by the test's admin: its id and secret. */
    private static JsonPath create(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath();
    }

    @Test
    void theGrantWaitsForASecondAdmin() {
        admin().get("/api/v1/tokens/policy").then().statusCode(200).body("unmaskApproval", equalTo(true));
        JsonPath designer = create("analyst-" + System.nanoTime(), "designer");
        long id = designer.getLong("id");
        String secret = designer.getString("token");
        // made without the grant: it sees sensitive values masked until approved
        as(secret).get("/api/v1/me").then().statusCode(200).body("unmask", equalTo(false));
        admin().get("/api/v1/tokens").then().statusCode(200)
            .body("find { it.id == " + id + " }.unmaskRequestedBy", notNullValue())
            .body("find { it.id == " + id + " }.unmask", equalTo(false));

        // the admin who asked cannot approve it
        admin().post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(403);
        as(secret).get("/api/v1/me").then().body("unmask", equalTo(false));

        // a second admin can
        String second = create("second-admin-" + System.nanoTime(), "admin").getString("token");
        as(second).post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(200)
            .body("unmask", equalTo(true)).body("unmaskRequestedBy", nullValue());
        as(secret).get("/api/v1/me").then().body("unmask", equalTo(true));
        // once decided, there is nothing more to approve
        as(second).post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(409);

        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.UNMASK_REQUESTED, null, 50).stream()
            .anyMatch(e -> ("token " + id).equals(e.target())), "the request is in the audit log");
        assertTrue(audit.list(AuditLog.UNMASK_APPROVED, null, 50).stream()
            .anyMatch(e -> ("token " + id).equals(e.target())), "the approval is in the audit log");
    }

    /** A refusal leaves the token without the grant; the one who asked may withdraw it. */
    @Test
    void aRefusedGrantLeavesTheTokenWithoutIt() {
        JsonPath designer = create("refused-" + System.nanoTime(), "designer");
        long id = designer.getLong("id");
        admin().post("/api/v1/tokens/" + id + "/unmask/refuse").then().statusCode(200)
            .body("unmask", equalTo(false)).body("unmaskRequestedBy", nullValue());
        as(designer.getString("token")).get("/api/v1/me").then().body("unmask", equalTo(false));
        admin().post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(409);
        admin().post("/api/v1/tokens/999999999/unmask/approve").then().statusCode(404);
    }

    /** Only the grant waits: other tokens, and a designer token made without it, are as before. */
    @Test
    void tokensWithoutTheGrantAreUnchanged() {
        JsonPath steward = create("steward-" + System.nanoTime(), "steward");
        admin().get("/api/v1/tokens").then()
            .body("find { it.id == " + steward.getLong("id") + " }.unmaskRequestedBy", nullValue());
        JsonPath plain = admin().contentType(ContentType.JSON)
            .body(Map.of("name", "plain-" + System.nanoTime(), "role", "designer", "unmask", false))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath();
        admin().get("/api/v1/tokens").then()
            .body("find { it.id == " + plain.getLong("id") + " }.unmaskRequestedBy", nullValue());
    }
}
