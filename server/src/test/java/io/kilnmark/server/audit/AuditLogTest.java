package io.kilnmark.server.audit;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static io.kilnmark.server.Tokens.newToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * The audit log (V27): what is recorded, who may read it, and that it cannot be rewritten.
 */
@QuarkusTest
class AuditLogTest {

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    private static JsonPath events(String action) {
        return admin().queryParam("action", action).queryParam("limit", 50).get("/api/v1/audit").then()
            .statusCode(200).extract().jsonPath();
    }

    @Test
    void tokensAreRecordedWithTheUnmaskGrant() {
        String token = admin().contentType(ContentType.JSON)
            .body(Map.of("name", "audited designer", "role", "designer", "unmask", true))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("id");
        JsonPath created = events(AuditLog.TOKEN_CREATED);
        Map<String, Object> e = created.getMap("events.find { it.target == 'token " + token + "' }");
        assertNotNull(e, created.prettify());
        assertEquals(Boolean.TRUE, created.get("events.find { it.target == 'token " + token + "' }.detail.unmask"));
        assertEquals("designer", created.get("events.find { it.target == 'token " + token + "' }.detail.role"));
        assertFalse(created.prettify().contains("km_"), "the token's secret is never recorded");

        admin().delete("/api/v1/tokens/" + token).then().statusCode(204);
        assertNotNull(events(AuditLog.TOKEN_REVOKED).getMap("events.find { it.target == 'token " + token + "' }"));
        // The request itself is recorded too, by method and path only.
        assertNotNull(events(AuditLog.API_REQUEST).getMap("events.find { it.target == '/api/v1/tokens/" + token
            + "' && it.detail.method == 'DELETE' }"));
    }

    @Test
    void allowWritingTurnedOnAndTheSourceDeletedAreRecorded() throws Exception {
        Path roots = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files"));
        Path dir = Files.createTempDirectory(roots, "audit");
        admin().contentType(ContentType.JSON).body(Map.of("name", "audited-files", "kind", "files", "settings",
            Map.of("path", dir.toString(), "allow_writing", "true"))).post("/api/v1/sources").then().statusCode(201);
        JsonPath writing = events(AuditLog.WRITING_CHANGED);
        assertEquals(Boolean.TRUE, writing.get("events.find { it.target == 'audited-files' }.detail.on"),
            writing.prettify());
        assertEquals("form", writing.get("events.find { it.target == 'audited-files' }.detail.via"));

        admin().delete("/api/v1/sources/audited-files").then().statusCode(200);
        JsonPath deleted = events(AuditLog.SOURCE_DELETED);
        assertEquals(Boolean.FALSE, deleted.get("events.find { it.target == 'audited-files' }.detail.marked"),
            deleted.prettify());
    }

    @Test
    void onlyAdminsReadItAndRefusalsAreRecorded() {
        String steward = newToken("steward", false);
        as(steward).get("/api/v1/audit").then().statusCode(403);
        as(newToken("designer", true)).get("/api/v1/audit").then().statusCode(403);
        JsonPath refused = events(AuditLog.API_REFUSED);
        assertNotNull(refused.getMap("events.find { it.target == '/api/v1/audit' && it.detail.status == 403 }"),
            refused.prettify());
        admin().queryParam("action", "nope").get("/api/v1/audit").then().statusCode(400);
        admin().queryParam("limit", 0).get("/api/v1/audit").then().statusCode(400);
    }

    @Test
    void pagesGoBackInTime() {
        JsonPath first = admin().queryParam("limit", 1).get("/api/v1/audit").then().statusCode(200).extract()
            .jsonPath();
        List<Map<String, Object>> one = first.getList("events");
        assertEquals(1, one.size());
        Long next = first.getLong("next");
        assertNotNull(next);
        List<Integer> ids = admin().queryParam("limit", 5).queryParam("before", next).get("/api/v1/audit").then()
            .statusCode(200).extract().jsonPath().getList("events.id");
        assertTrue(ids.stream().allMatch(id -> id < next), ids.toString());
    }

    @Test
    void eventsCannotBeChangedOrDeleted() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("name", "audit probe", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201);
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            SQLException update = assertThrows(SQLException.class,
                () -> st.executeUpdate("UPDATE kilnmark.audit_event SET actor = 'someone else'"));
            assertTrue(update.getMessage().contains("append-only"), update.getMessage());
            assertThrows(SQLException.class, () -> st.executeUpdate("DELETE FROM kilnmark.audit_event"));
            assertThrows(SQLException.class, () -> st.executeUpdate("TRUNCATE kilnmark.audit_event"));
        }
    }

    @Test
    void unknownActionsAreRefusedAndValuesAreMadeSafeToLog() {
        AuditLog log = new AuditLog(null);
        assertThrows(IllegalArgumentException.class, () -> log.record(AuditLog.Actor.server("test"), "made.up", "x",
            Map.of()));
        assertEquals("token 7 (jo?bad)", new AuditLog.Actor("jo\nbad", 7L).toString());
        assertEquals("server (start)", AuditLog.Actor.server("start").toString());
    }
}
