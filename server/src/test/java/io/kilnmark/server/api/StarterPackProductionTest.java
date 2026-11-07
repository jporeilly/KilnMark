package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.OidcAccess;
import io.kilnmark.server.starter.ModeCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * A production install (ADR 0051): the Starter Pack is never turned on, through the API either,
 * and the refusal is recorded; the example people, by the pack's group or, from an earlier version,
 * by their five addresses, are refused sign-in, each refusal recorded; the templates are there as on
 * any install; and an install whose records name one of the five addresses is never first recorded
 * as production. On a database of its own: the mode is recorded at the first start.
 */
@QuarkusTest
@TestProfile(StarterPackProductionTest.Production.class)
class StarterPackProductionTest {

    public static class Production implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.mode", "production",
                // its own database: the other tests' records the mode as starter-pack
                "quarkus.datasource.devservices.db-name", "kilnmark_production",
                "kilnmark.auth.oidc.viewer-groups", "kilnmark-viewers",
                "kilnmark.auth.oidc.admin-groups", "kilnmark-admins");
        }
    }

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<io.quarkus.oidc.SecurityEvent> signIns;

    @jakarta.inject.Inject
    AuditLog audit;

    @jakarta.inject.Inject
    ModeCheck modes;

    @jakarta.inject.Inject
    io.kilnmark.server.KilnmarkConfig config;

    @Test
    void thePackIsNeverTurnedOnAndTheRefusalIsRecorded() throws Exception {
        admin().get("/api/v1/starter-pack").then().statusCode(200).body("mode", equalTo("production"))
            .body("on", equalTo(false));
        admin().get("/api/v1/starter-pack/details").then().statusCode(200).body("mode", equalTo("production"));
        int before = StarterPackApiTest.events(AuditLog.STARTER_PACK_ADDED);
        admin().post("/api/v1/starter-pack").then().statusCode(409)
            .body("error", containsString("production install"))
            .body("error", containsString("Nothing was changed"));
        // no way round it: the old force flag does nothing
        admin().post("/api/v1/starter-pack?force=true").then().statusCode(409);
        StarterPackApiTest.eventually(AuditLog.STARTER_PACK_ADDED, before + 2);
        assertEquals("production", admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_ADDED + "&limit=1").then()
            .statusCode(200).extract().jsonPath().getString("events[0].detail.refused"), "the refusal is recorded");
        admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(404);
        // and no example people to record
        admin().contentType(ContentType.JSON).body(Map.of("action", "add", "people", List.of("vera.lane@kilnmark.com")))
            .post("/api/v1/starter-pack/people").then().statusCode(409);
    }

    private static Map<String, Object> made(String person, String groups) {
        return Map.of("person", person, "groups", groups);
    }

    @Test
    void whatThePeopleCommandDidIsRecordedNeverWithAPasswordAndNeverAnExampleAddress() throws Exception {
        int added = StarterPackApiTest.events(AuditLog.PERSON_ADDED);
        int imports = StarterPackApiTest.events(AuditLog.PEOPLE_IMPORTED);
        // people add (install's first admin): one event; anything else in the body, a password say, is never kept
        admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("rita.okoro@bank.example",
            "kilnmark-admins")), "kept", List.of(), "refused", 0, "password", "never-kept-0123456789"))
            .post("/api/v1/auth/people").then().statusCode(204);
        // people import: one event for each person made, and one for the import
        admin().contentType(ContentType.JSON).body(Map.of("via", "import", "made", List.of(
            made("vera.lane@example.com", "kilnmark-viewers"),
            made("priya.shah@example.com", "kilnmark-designers;kilnmark-sensitive-data")),
            "kept", List.of("sam.okafor@example.com"), "refused", 0)).post("/api/v1/auth/people").then().statusCode(204);
        StarterPackApiTest.eventually(AuditLog.PERSON_ADDED, added + 3);
        StarterPackApiTest.eventually(AuditLog.PEOPLE_IMPORTED, imports + 1);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.PERSON_ADDED + "&limit=3").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(Map.of("person", "rita.okoro@bank.example", "groups", List.of("kilnmark-admins"), "via", "add"),
            ev.getMap("events[2].detail"));
        assertEquals(List.of("kilnmark-designers", "kilnmark-sensitive-data"), ev.getList("events[0].detail.groups"));
        assertTrue(!ev.prettify().contains("never-kept"), "no password");
        JsonPath im = admin().get("/api/v1/audit?action=" + AuditLog.PEOPLE_IMPORTED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(Map.of("made", 2, "alreadyThere", 1, "refused", 0), im.getMap("events[0].detail"));
        // only addresses of the organisation's own, only Kilnmark's groups, no admin from a file, only admins
        for (String person : List.of("vera.lane@kilnmark.com", "someone@kilnmark.com", "someone@eu.kilnmark.com",
            "Rita@Bank.example", "not-an-address")) {
            admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made(person, "kilnmark-viewers"))))
                .post("/api/v1/auth/people").then().statusCode(400);
            admin().contentType(ContentType.JSON).body(Map.of("via", "import", "kept", List.of(person)))
                .post("/api/v1/auth/people").then().statusCode(400);
        }
        for (String groups : List.of("kilnmark-starter-pack", "", "kilnmark-viewers;kilnmark-viewers", "admins")) {
            admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("rita@bank.example", groups))))
                .post("/api/v1/auth/people").then().statusCode(400);
        }
        admin().contentType(ContentType.JSON).body(Map.of("via", "import", "made", List.of(made("rita@bank.example",
            "kilnmark-viewers;kilnmark-admins")))).post("/api/v1/auth/people").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("via", "reset", "made", List.of(made("rita@bank.example",
            "kilnmark-viewers")))).post("/api/v1/auth/people").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("a@bank.example",
            "kilnmark-viewers"), made("b@bank.example", "kilnmark-viewers")))).post("/api/v1/auth/people").then()
            .statusCode(400);
        io.kilnmark.server.Tokens.as(io.kilnmark.server.Tokens.newToken("designer", false)).contentType(ContentType.JSON)
            .body(Map.of("via", "add", "made", List.of(made("rita@bank.example", "kilnmark-viewers"))))
            .post("/api/v1/auth/people").then().statusCode(403);
        assertEquals(added + 3, StarterPackApiTest.events(AuditLog.PERSON_ADDED), "nothing refused was recorded");
        // and a production install lets them sign in: in kilnmark-admins, not the pack's group
        assertNull(OidcAccess.decide(config, token("f1", "rita.okoro@bank.example", "kilnmark-admins"), Instant.now())
            .refusal());
    }

    @Test
    void theTemplatesAreThereAsOnAnyInstall() {
        List<String> names = admin().get("/api/v1/templates").then().statusCode(200).extract().jsonPath().getList("name");
        assertTrue(names.containsAll(List.of("template-copy-table", "template-run-in-order")), names.toString());
    }

    /** A provider sign-in as {@code user}, in {@code groups}, with two factors, signed in now. */
    private static JsonWebToken token(String sub, String user, String... groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https:" + "/" + "/idp.example.test/realms/kilnmark");
        claims.put("sub", sub);
        claims.put("preferred_username", user);
        claims.put("email", user);
        claims.put("amr", List.of("pwd", "otp"));
        claims.put("auth_time", Instant.now().getEpochSecond());
        claims.put("groups", List.of(groups));
        return new JsonWebToken() {
            @Override public String getName() { return user; }
            @Override public Set<String> getClaimNames() { return claims.keySet(); }
            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) claims.get(name);
            }
        };
    }

    @Test
    void theExamplePeopleAreRefusedSignInByTheirGroupOrTheirAddresses() throws Exception {
        Instant now = Instant.now();
        // a member of the pack's group, whatever their name
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config,
            token("s1", "someone@bank.example", "kilnmark-admins", OidcAccess.STARTER_GROUP), now).refusal());
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config,
            token("s2", "someone@bank.example", "kilnmark-viewers", "/" + OidcAccess.STARTER_GROUP), now).refusal());
        // one of the five an earlier version made, never put in the group
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config,
            token("s3", "Alex.Morgan@kilnmark.com", "kilnmark-admins"), now).refusal());
        // a real person
        assertNull(OidcAccess.decide(config, token("s4", "rita@bank.example", "kilnmark-admins"), now).refusal());

        // each refusal is recorded, as the sign-in events record every refused sign-in
        var identity = io.quarkus.security.runtime.QuarkusSecurityIdentity.builder()
            .setPrincipal(token("s5", "priya.shah@kilnmark.com", "kilnmark-viewers"))
            .addCredential(new io.quarkus.oidc.IdTokenCredential("id-token")).build();
        signIns.fire(new io.quarkus.oidc.SecurityEvent(io.quarkus.oidc.SecurityEvent.Type.OIDC_LOGIN, identity));
        audit.awaitStored(5000);
        JsonPath in = admin().get("/api/v1/audit?action=" + AuditLog.SIGN_IN_REFUSED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, in.getString("events[0].detail.reason"), in.prettify());
    }

    @Test
    void anInstallThatHasHadTheExamplePeopleIsNeverFirstRecordedAsProduction() throws Exception {
        // this database was recorded as production at its first start (no signs then)
        assertNull(modes.check(ModeCheck.PRODUCTION));
        // an install with no mode recorded yet, whose records name one of the five: only starter-pack
        audit.record(new AuditLog.Actor("sso:dana.reyes@kilnmark.com (d1)", null), AuditLog.SIGN_IN, "session", Map.of());
        audit.awaitStored(5000);
        String key = "deployment_mode_test_" + System.nanoTime();
        String refused = modes.checkFor(ModeCheck.PRODUCTION, key);
        assertTrue(refused != null && refused.contains("has had the Starter Pack's example people"), refused);
        assertNull(modes.checkFor(ModeCheck.STARTER_PACK, key), "it may be a Starter Pack install");
        // and once recorded, the other mode is refused
        assertTrue(modes.checkFor(ModeCheck.PRODUCTION, key).contains("never becomes a production one"));
    }
}
