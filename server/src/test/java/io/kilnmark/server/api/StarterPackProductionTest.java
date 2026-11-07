package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * On an install marked production (KILNMARK_PRODUCTION=true) the Starter Pack is refused, and
 * nothing of it is made, the refusal recorded; only an admin calling the API with force=true can add
 * it there, and the audit event says it was forced. The example accounts are refused sign-in, and
 * what they left from a trial stops working: the API tokens they made or approved the unmask grant
 * of, the tokens those made, and the schedules they set (ADR 0051).
 */
@QuarkusTest
@TestProfile(StarterPackProductionTest.Production.class)
class StarterPackProductionTest {

    public static class Production implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.production", "true", "kilnmark.samples-db.password", StarterPackApiTest.PASSWORD);
        }
    }

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @Test
    void refusedOnProductionUnlessForcedThroughTheApi() throws Exception {
        StarterPackApiTest.makeSamplesDatabase(db);
        admin().get("/api/v1/starter-pack/details").then().statusCode(200).body("production", equalTo(true));
        int refused = StarterPackApiTest.events(AuditLog.STARTER_PACK_ADDED);
        admin().post("/api/v1/starter-pack").then().statusCode(409)
            .body("error", containsString("marked as production"))
            .body("error", containsString("Nothing was changed"));
        StarterPackApiTest.eventually(AuditLog.STARTER_PACK_ADDED, refused + 1);
        assertEquals("production", admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_ADDED + "&limit=1").then()
            .statusCode(200).extract().jsonPath().getString("events[0].detail.refused"), "the refusal is recorded");
        admin().get("/api/v1/starter-pack").then().body("on", equalTo(false));
        admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(404);
        admin().get("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(404);

        int added = StarterPackApiTest.events(AuditLog.STARTER_PACK_ADDED);
        JsonPath r = admin().post("/api/v1/starter-pack?force=true").then().statusCode(200).extract().jsonPath();
        assertEquals("done", r.getString("steps.find { it.step == 'database source' }.outcome"), r.prettify());
        StarterPackApiTest.eventually(AuditLog.STARTER_PACK_ADDED, added + 1);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_ADDED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(ev.getBoolean("events[0].detail.forced"), ev.prettify());

        StarterPackApiTest.waitForRun(r.getLong("tablesRun.id"));
        // Turning it off is never refused: taking example data out of production is what should happen.
        JsonPath off = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE"))
            .delete("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), off.getList("steps.findAll { it.outcome == 'failed' }"), off.prettify());
    }

    @jakarta.inject.Inject
    io.kilnmark.server.auth.ApiTokens tokens;

    @jakarta.inject.Inject
    io.kilnmark.server.starter.ProductionCheck check;

    @jakarta.inject.Inject
    io.kilnmark.server.schedules.ScheduleService schedules;

    @jakarta.inject.Inject
    io.kilnmark.server.schedules.ScheduleStore store;

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<io.quarkus.oidc.SecurityEvent> signIns;

    @jakarta.inject.Inject
    AuditLog audit;

    /** A provider sign-in as {@code user} (its name and email), with two factors, signed in now. */
    private static io.quarkus.security.identity.SecurityIdentity person(String sub, String user) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", "https:" + "/" + "/idp.example.test/realms/kilnmark");
        claims.put("sub", sub);
        claims.put("email", user);
        claims.put("amr", List.of("pwd", "otp"));
        claims.put("auth_time", java.time.Instant.now().getEpochSecond());
        claims.put("groups", List.of("kilnmark-designers"));
        org.eclipse.microprofile.jwt.JsonWebToken token = new org.eclipse.microprofile.jwt.JsonWebToken() {
            @Override public String getName() { return user; }
            @Override public java.util.Set<String> getClaimNames() { return claims.keySet(); }
            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) claims.get(name);
            }
        };
        return io.quarkus.security.runtime.QuarkusSecurityIdentity.builder().setPrincipal(token)
            .addCredential(new io.quarkus.oidc.IdTokenCredential("id-token")).addRole("designer").build();
    }

    @Test
    void whatTheExampleAccountsLeftStopsWorking() throws Exception {
        String alex = "sso:alex.morgan@kilnmark.com (sub-alex)";
        String rita = "sso:Rita Real (sub-rita)";
        AccessRole admin = AccessRole.ADMIN;
        long ex = tokens.create("pc-ex", admin, null, alex).token().id();
        long child = tokens.create("pc-child", admin, null, "token " + ex + " (pc-ex)").token().id();
        long grandchild = tokens.create("pc-grandchild", AccessRole.VIEWER, null, "token " + child + " (pc-child)").token().id();
        long approved = tokens.create("pc-approved", AccessRole.DESIGNER, null, rita).token().id();
        try (var c = db.getConnection(); var ps = c.prepareStatement(
            "UPDATE kilnmark.api_token SET unmask = true, unmask_approved_by = ?, unmask_approved_at = now() WHERE id = ?")) {
            ps.setString(1, alex);   // a trial's example admin approved Rita's grant
            ps.setLong(2, approved);
            assertEquals(1, ps.executeUpdate());
        }
        long own = tokens.create("pc-own", admin, null, rita).token().id();
        long ownChild = tokens.create("pc-own-child", AccessRole.VIEWER, null, "token " + own + " (pc-own)").token().id();

        // An example person signs in: refused on production, the refusal recorded with their key.
        var priya = person("sub-priya", "priya.shah@kilnmark.com");
        signIns.fire(new io.quarkus.oidc.SecurityEvent(io.quarkus.oidc.SecurityEvent.Type.OIDC_LOGIN, priya));
        audit.awaitStored(5000);
        JsonPath in = admin().get("/api/v1/audit?action=" + AuditLog.SIGN_IN_REFUSED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(io.kilnmark.server.auth.OidcAccess.EXAMPLE_ON_PRODUCTION, in.getString("events[0].detail.reason"),
            in.prettify());
        // a schedule Priya set in the trial
        admin().contentType(ContentType.JSON).body(Map.of("yaml", """
            kilnmark: flow/v1
            name: pc-flow
            nodes:
              - { id: pause, type: wait, for: 1s }
            """, "message", "first")).post("/api/v1/flows").then().statusCode(201);
        var schedule = schedules.create(new io.kilnmark.server.schedules.ScheduleViews.ScheduleChange(null, "pc-flow",
            "@daily", null, Map.of(), "designer", true, null), io.kilnmark.server.schedules.ScheduleService.setter(priya,
            "sso:priya.shah@kilnmark.com (sub-priya)"), AccessRole.DESIGNER);

        int revokedEvents = StarterPackApiTest.events(AuditLog.TOKEN_REVOKED);
        io.kilnmark.server.starter.ProductionCheck.Swept swept = check.sweep();
        assertEquals(java.util.Set.of(ex, child, grandchild, approved), java.util.Set.copyOf(swept.tokens()), swept.toString());
        for (long id : List.of(ex, child, grandchild, approved)) {
            assertTrue(tokens.find(id).orElseThrow().revokedAt() != null, "revoked: " + id);
        }
        for (long id : List.of(own, ownChild)) {
            assertEquals(null, tokens.find(id).orElseThrow().revokedAt(), "a real person's tokens stay: " + id);
        }
        assertFalse(store.find(schedule.id()).orElseThrow().enabled(), "the example person's schedule is paused");
        StarterPackApiTest.eventually(AuditLog.TOKEN_REVOKED, revokedEvents + 4);
        JsonPath rev = admin().get("/api/v1/audit?action=" + AuditLog.TOKEN_REVOKED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(io.kilnmark.server.starter.ProductionCheck.TOKEN_REASON, rev.getString("events[0].detail.reason"));
        // again: nothing more to do
        assertEquals(List.of(), check.sweep().tokens());
        schedules.delete(schedule.id(), "test", AccessRole.ADMIN);
    }

    @AfterAll
    static void dropTheDatabase() {
        try (var c = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class).get().getConnection();
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS kilnmark_samples WITH (FORCE)");
        } catch (Exception e) {
            // gone with the test's own database
        }
    }
}
