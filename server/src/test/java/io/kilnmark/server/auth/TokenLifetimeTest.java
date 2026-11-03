package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Db;
import io.kilnmark.server.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * A longest token lifetime (ADR 0037), and the access review: who can do what, for admins, as JSON
 * or CSV, each download recorded.
 */
@QuarkusTest
@TestProfile(TokenLifetimeTest.ThirtyDays.class)
class TokenLifetimeTest {

    public static class ThirtyDays implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.auth.token-max-lifetime", "30d");
        }
    }

    @Inject
    ApiTokens tokens;

    @Inject
    Db db;

    @Inject
    AuditLog audit;

    /** A token for this test, within the longest lifetime (Tokens.newToken makes ones that never expire). */
    private String token(String role) {
        return tokens.create("lifetime-test", AccessRole.parse(role), Instant.now().plus(Duration.ofDays(10)),
            "test").secret();
    }

    private static String body(String name, Instant expiresAt) {
        return "{\"name\":\"" + name + "\",\"role\":\"viewer\"" + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt
            + "\"") + "}";
    }

    @Test
    void newTokensMustExpireWithinTheLongestLifetime() {
        admin().get("/api/v1/tokens/policy").then().statusCode(200).body("maxLifetimeDays", equalTo(30));
        admin().contentType(ContentType.JSON).body(body("forever", null)).post("/api/v1/tokens").then()
            .statusCode(400).body("error", containsString("30 days"));
        admin().contentType(ContentType.JSON).body(body("too-long", Instant.now().plus(Duration.ofDays(40))))
            .post("/api/v1/tokens").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(body("within", Instant.now().plus(Duration.ofDays(20))))
            .post("/api/v1/tokens").then().statusCode(201);
    }

    /** A token made before the limit, or with a longer expiry, stops working once older than the limit. */
    @Test
    void anOlderTokenStopsWorkingWhateverItsOwnExpiry() throws Exception {
        String secret = token("viewer");
        as(secret).get("/api/v1/me").then().statusCode(200);
        db.tx(c -> {
            // made 31 days ago, and set (as before the limit) to expire in a year
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET created_at = now() - "
                + "interval '31 days', expires_at = now() + interval '1 year' WHERE hash = ?")) {
                ps.setBytes(1, ApiTokens.hash(secret));
                return ps.executeUpdate();
            }
        });
        as(secret).get("/api/v1/me").then().statusCode(401);
        Instant created = Instant.now().minus(Duration.ofDays(31));
        assertEquals(created.plus(Duration.ofDays(30)), tokens.expiry("token 1 (admin)", created, null));
        assertNull(tokens.expiry("environment", created, null), "the environment's admin token is not limited");
        Instant soon = Instant.now().plus(Duration.ofDays(2));
        assertEquals(soon, tokens.expiry("x", Instant.now(), soon), "its own expiry, when sooner");
    }

    @Test
    void theAccessReviewListsEveryTokenForAdminsAndIsRecorded() {
        admin().get("/api/v1/access-review").then().statusCode(200)
            .body("tokenMaxLifetimeDays", equalTo(30))
            .body("entries.kind", hasItem("token"));
        String csv = admin().accept("text/csv").get("/api/v1/access-review?format=csv").then().statusCode(200)
            .header("Content-Disposition", containsString("kilnmark-access-review.csv")).extract().asString();
        assertTrue(csv.startsWith("kind,name,role,unmask,status,created_by,created_at,expires_at,last_used_at,sign_ins"),
            csv.lines().findFirst().orElse(""));
        as(token("steward")).get("/api/v1/access-review").then().statusCode(403);
        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.ACCESS_REVIEW, null, 10).size() >= 2, "each download is in the audit log");
    }

    /** A name a person chose cannot run as a formula in the spreadsheet the auditors open. */
    @Test
    void csvCellsCannotRunAsFormulas() {
        var r = new io.kilnmark.server.api.AccessReviewResource.Review(Instant.now(), null, java.util.List.of(
            new io.kilnmark.server.api.AccessReviewResource.Entry("token", "=HYPERLINK(\"x\")", "viewer", false,
                "active", "@admin", null, null, null, null)));
        String csv = io.kilnmark.server.api.AccessReviewResource.csvForTests(r);
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""), csv);
        assertTrue(csv.contains("\"'@admin\""), csv);
    }
}
