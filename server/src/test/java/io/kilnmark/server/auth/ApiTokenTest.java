package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/** API tokens: sign-in, roles, one-time secrets, revocation, expiry and the bootstrap admin token. */
@QuarkusTest
class ApiTokenTest {

    private static final String TOKEN_FORMAT = "km_[A-Za-z0-9_-]{43}";

    @Inject
    AgroalDataSource db;

    @Inject
    ApiTokens tokens;

    private static JsonPath create(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens")
            .then().statusCode(201).header("Cache-Control", containsString("no-store"))
            .extract().jsonPath();
    }

    @Test
    void publicPagesNeedNoToken() {
        given().get("/api/v1/info").then().statusCode(200);
        given().get("/q/health").then().statusCode(200);
        // Branding: the sign-in page shows the organisation's name and logo, and <img> sends no token.
        // A stale or bogus token kept in the browser must not break it either.
        for (String authorization : new String[] {null, "Bearer km_" + "x".repeat(43)}) {
            var request = authorization == null ? given() : given().header("Authorization", authorization);
            request.get("/api/v1/branding").then().statusCode(200).contentType(ContentType.JSON)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .body("$", hasKey("connectors"));
            var again = authorization == null ? given() : given().header("Authorization", authorization);
            again.get("/api/v1/branding/files/missing.svg").then().statusCode(404).contentType(ContentType.JSON)
                .body("error", containsString("No branding image"));
        }
    }

    @Test
    void anApiCallWithoutATokenIsRefusedWithAReason() {
        given().get("/api/v1/sources")
            .then().statusCode(401)
            .header("WWW-Authenticate", containsString("Bearer"))
            .body("error", containsString("Sign in"));
        given().header("Authorization", "Basic YWRtaW46YWRtaW4=").get("/api/v1/search?q=x").then().statusCode(401);
    }

    @Test
    void anUnknownTokenIsRefused() {
        Tokens.as("km_" + "x".repeat(43)).get("/api/v1/sources")
            .then().statusCode(401).body("error", containsString("not accepted"));
        Tokens.as("not-a-kilnmark-token").get("/api/v1/sources").then().statusCode(401);
    }

    @Test
    void eachRoleIncludesTheOnesBelowIt() {
        String viewer = create("test-viewer", "viewer").getString("token");
        String steward = create("test-steward", "STEWARD").getString("token");

        Tokens.as(viewer).get("/api/v1/me").then().statusCode(200)
            .body("name", equalTo("test-viewer")).body("role", equalTo("viewer"))
            .body("signInRequired", equalTo(true));
        Tokens.as(viewer).get("/api/v1/sources").then().statusCode(200);
        Tokens.as(viewer).get("/api/v1/connectors").then().statusCode(200);
        Tokens.as(viewer).queryParam("q", "anything").get("/api/v1/search").then().statusCode(200);
        Tokens.as(viewer).get("/api/v1/runs/999999").then().statusCode(404);
        Tokens.as(viewer).post("/api/v1/sources/nope/scans")
            .then().statusCode(403).body("error", containsString("designer to build pipelines"));
        Tokens.as(viewer).contentType(ContentType.JSON).body(Map.of("name", "x")).post("/api/v1/sources")
            .then().statusCode(403);
        Tokens.as(viewer).get("/api/v1/tokens").then().statusCode(403);

        Tokens.as(steward).post("/api/v1/sources/nope/scans").then().statusCode(404); // allowed, no such source
        Tokens.as(steward).contentType("text/csv").body("name\nx\n").post("/api/v1/sources/import")
            .then().statusCode(403);
        Tokens.as(steward).get("/api/v1/tokens").then().statusCode(403);

        admin().get("/api/v1/tokens").then().statusCode(200);
        admin().get("/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));
    }

    @Test
    void aTokenIsShownOnceAndStoredOnlyAsItsHash() throws Exception {
        JsonPath created = create("test-once", "viewer");
        String token = created.getString("token");
        long id = created.getLong("id");
        assertTrue(token.matches(TOKEN_FORMAT), token.substring(0, 3));

        String list = admin().get("/api/v1/tokens").then().statusCode(200)
            .body("find { it.id == " + id + " }.status", equalTo("active"))
            .body("find { it.id == " + id + " }", not(hasKey("token")))
            .body("find { it.id == " + id + " }", not(hasKey("hash")))
            .extract().asString();
        assertFalse(list.contains(token.substring(3)), "the list never holds a secret");

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT hash, t::text FROM kilnmark.api_token t WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertArrayEquals(ApiTokens.hash(token), rs.getBytes(1));
                assertFalse(rs.getString(2).contains(token.substring(3)), "the token itself is not stored");
            }
        }
    }

    @Test
    void revokedAndExpiredTokensStopWorking() throws Exception {
        JsonPath created = create("test-revoke", "viewer");
        String token = created.getString("token");
        Tokens.as(token).get("/api/v1/sources").then().statusCode(200);
        admin().delete("/api/v1/tokens/" + created.getLong("id")).then().statusCode(204);
        Tokens.as(token).get("/api/v1/sources").then().statusCode(401);
        admin().delete("/api/v1/tokens/" + created.getLong("id")).then().statusCode(204);
        admin().get("/api/v1/tokens").then()
            .body("find { it.id == " + created.getLong("id") + " }.status", equalTo("revoked"));
        admin().delete("/api/v1/tokens/99999999").then().statusCode(404);

        JsonPath expiring = create("test-expiry", "viewer");
        Tokens.as(expiring.getString("token")).get("/api/v1/sources").then().statusCode(200);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE kilnmark.api_token SET expires_at = now() - interval '1 second' WHERE id = ?")) {
            ps.setLong(1, expiring.getLong("id"));
            ps.executeUpdate();
        }
        Tokens.as(expiring.getString("token")).get("/api/v1/sources").then().statusCode(401);
    }

    @Test
    void anExpiryMustBeInTheFutureAndTheRoleKnown() {
        String soon = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        admin().contentType(ContentType.JSON).body(Map.of("name", "test-soon", "role", "viewer", "expiresAt", soon))
            .post("/api/v1/tokens").then().statusCode(201).body("expiresAt", notNullValue());
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "test-past", "role", "viewer", "expiresAt", "2020-01-01T00:00:00Z"))
            .post("/api/v1/tokens").then().statusCode(400).body("error", containsString("future"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "test-owner", "role", "owner"))
            .post("/api/v1/tokens").then().statusCode(400).body("error", containsString("viewer, steward, designer or admin"));
        admin().contentType(ContentType.JSON).body(Map.of("name", " ", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(400).body("error", containsString("name"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "ci\nINFO forged log line", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(400).body("error", containsString("control characters"));
    }

    @Test
    void lastUseIsWrittenAtMostOnceAMinute() throws Exception {
        JsonPath created = create("test-last-use", "viewer");
        long id = created.getLong("id");
        assertEquals(null, lastUsed(id));
        Tokens.as(created.getString("token")).get("/api/v1/me").then().statusCode(200);
        Timestamp first = lastUsed(id);
        assertNotNull(first);
        Thread.sleep(20);
        Tokens.as(created.getString("token")).get("/api/v1/sources").then().statusCode(200);
        assertEquals(first, lastUsed(id), "a second use within the minute writes nothing");
    }

    private Timestamp lastUsed(long id) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_used_at FROM kilnmark.api_token WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    /** Revokes every usable admin token, runs {@code test}, then puts them back. */
    private void withoutAdmins(ThrowingRunnable test) throws Exception {
        List<Long> revoked = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE kilnmark.api_token SET revoked_at = now(), revoked_reason = 'test' WHERE role = 'admin' "
                + "AND revoked_at IS NULL RETURNING id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                revoked.add(rs.getLong(1));
            }
        }
        try {
            test.run();
        } finally {
            try (Connection c = db.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET revoked_at = now() "
                    + "WHERE created_by IN ('first-start', 'environment') AND revoked_at IS NULL")) {
                    ps.executeUpdate();
                }
                Array ids = c.createArrayOf("bigint", revoked.toArray());
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE kilnmark.api_token SET revoked_at = NULL, revoked_reason = NULL WHERE id = ANY(?)")) {
                    ps.setArray(1, ids);
                    ps.executeUpdate();
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void withNoUsableAdminTokenTheServerWritesOneToAPrivateFile(@TempDir Path dir) throws Exception {
        withoutAdmins(() -> {
            Optional<Path> file = tokens.bootstrap(Optional.empty(), dir);
            assertEquals(Optional.of(dir.resolve("admin-token")), file);
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.get())));
            String token = Files.readString(file.get()).strip();
            assertTrue(token.matches(TOKEN_FORMAT));
            Tokens.as(token).get("/api/v1/tokens").then().statusCode(200);
            Tokens.as(token).get("/api/v1/me").then().body("name", equalTo("first admin"));
            Tokens.as(Tokens.ADMIN).get("/api/v1/sources").then().statusCode(401);
            try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT expires_at > now() + interval '23 hours' AND expires_at < now() + interval '25 hours' "
                    + "FROM kilnmark.api_token WHERE created_by = 'first-start' AND revoked_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1), "the first-start token works for 24 hours");
            }

            Files.delete(file.get());
            assertEquals(Optional.empty(), tokens.bootstrap(Optional.empty(), dir), "an admin exists now");
            assertFalse(Files.exists(dir.resolve("admin-token")));
        });
        admin().get("/api/v1/tokens").then().statusCode(200);
    }

    @Test
    void changingTheEnvironmentTokenSetsTheOldOneAsideUntilItReturns(@TempDir Path dir) throws Exception {
        String next = "km_" + "n".repeat(43);
        withoutAdmins(() -> {
            assertEquals(Optional.empty(), tokens.bootstrap(Optional.of(next), dir));
            Tokens.as(next).get("/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));
            assertFalse(Files.exists(dir.resolve("admin-token")), "no file when the environment gives the token");

            // Back to a value an admin had revoked: it stays revoked, and the server is never left without an admin.
            Optional<Path> file = tokens.bootstrap(Optional.of(Tokens.ADMIN), dir);
            Tokens.as(next).get("/api/v1/me").then().statusCode(401);
            Tokens.as(Tokens.ADMIN).get("/api/v1/me").then().statusCode(401);
            assertTrue(file.isPresent(), "no usable admin was left, so a first-start token was written");
            String firstStart = Files.readString(file.get()).strip();
            Files.delete(file.get());

            // The earlier value again: only set aside by the start-up check, so it works again.
            assertEquals(Optional.empty(), tokens.bootstrap(Optional.of(next), dir));
            Tokens.as(next).get("/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));

            // Revoked by an admin: setting the same value again does not bring it back.
            long id = Tokens.as(next).get("/api/v1/me").then().extract().jsonPath().getLong("tokenId");
            Tokens.as(firstStart).delete("/api/v1/tokens/" + id).then().statusCode(204);
            tokens.bootstrap(Optional.of(next), dir);
            Tokens.as(next).get("/api/v1/me").then().statusCode(401);
            assertFalse(Files.exists(dir.resolve("admin-token")), "the first-start token is still usable");
        });
        admin().get("/api/v1/me").then().statusCode(200);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> tokens.bootstrap(Optional.of("short"), dir));
        assertTrue(e.getMessage().contains("KILNMARK_ADMIN_TOKEN"), e.getMessage());
    }

    @Test
    void anAdminCanRevokeASetAsideEnvironmentTokenForGood(@TempDir Path dir) throws Exception {
        String leaked = "km_" + "l".repeat(43);
        String replacement = "km_" + "r".repeat(43);
        withoutAdmins(() -> {
            tokens.bootstrap(Optional.of(leaked), dir);
            long id = Tokens.as(leaked).get("/api/v1/me").then().extract().jsonPath().getLong("tokenId");
            tokens.bootstrap(Optional.of(replacement), dir); // the leaked value is replaced: set aside
            Tokens.as(replacement).get("/api/v1/tokens").then().statusCode(200)
                .body("find { it.id == " + id + " }.status", equalTo("set-aside"));
            Tokens.as(replacement).delete("/api/v1/tokens/" + id).then().statusCode(204);
            tokens.bootstrap(Optional.of(leaked), dir); // a configuration rollback to the leaked value
            Tokens.as(leaked).get("/api/v1/me").then().statusCode(401);
        });
    }

    @Test
    void rolesAreParsedStrictly() {
        assertEquals(AccessRole.ADMIN, AccessRole.parse(" Admin "));
        assertThrows(IllegalArgumentException.class, () -> AccessRole.parse("root"));
        assertThrows(IllegalArgumentException.class, () -> AccessRole.parse(null));
        assertEquals(java.util.Set.of("viewer", "steward"), AccessRole.STEWARD.grants());
    }
}
