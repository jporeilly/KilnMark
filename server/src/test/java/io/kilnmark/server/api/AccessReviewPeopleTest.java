package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.OidcAccess;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * The access review lists everyone (ADR 0037): the people the control script made in the bundled
 * Keycloak appear before their first sign-in, with their groups, the role and grant those groups
 * give, and whether they wait for their first sign-in or were disabled for an unused temporary
 * password; once they sign in, their sign-in takes their place. Plus what each role may do, the
 * settings that limit people, and the CSV's new columns. Never a token's secret.
 */
@QuarkusTest
@TestProfile(AccessReviewPeopleTest.Groups.class)
class AccessReviewPeopleTest {

    /** The bundled Keycloak's groups, mapped as release.env.example maps them, and a longest token lifetime. */
    public static class Groups implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.auth.oidc.viewer-groups", "kilnmark-viewers",
                "kilnmark.auth.oidc.steward-groups", "kilnmark-stewards",
                "kilnmark.auth.oidc.designer-groups", "kilnmark-designers",
                "kilnmark.auth.oidc.admin-groups", "kilnmark-admins",
                "kilnmark.auth.oidc.unmask-groups", "kilnmark-sensitive-data",
                "kilnmark.auth.token-max-lifetime", "90d");
        }
    }

    @Inject
    AuditLog audit;

    private static Map<String, String> made(String person, String groups) {
        return Map.of("person", person, "groups", groups);
    }

    /** A token within the 90-day longest lifetime this profile sets. */
    private static String token(String role, boolean unmask) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", "review " + role, "role", role, "unmask", unmask,
            "expiresAt", java.time.Instant.now().plus(java.time.Duration.ofDays(10)).toString())).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static void people(Map<String, Object> body) {
        admin().contentType(ContentType.JSON).body(body).post("/api/v1/auth/people").then().statusCode(204);
    }

    /** The review's entry for {@code name}, or null. */
    private static Map<String, Object> entry(JsonPath review, String name) {
        List<Map<String, Object>> found = review.getList("entries.findAll { it.name == '" + name + "' }");
        assertTrue(found.size() <= 1, name + " listed once: " + found);
        return found.isEmpty() ? null : found.getFirst();
    }

    private JsonPath review() {
        audit.awaitStored(5000);
        return admin().get("/api/v1/access-review").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void peopleMadeBeforeTheirFirstSignInAreListedWithTheirStateAndGroups() {
        String n = Long.toString(System.nanoTime(), 36);
        String dana = "dana." + n + "@bank.example";
        String vic = "vic." + n + "@bank.example";
        String old = "old." + n + "@bank.example";
        String back = "back." + n + "@bank.example";
        String sam = "sam." + n + "@bank.example";
        String rita = "rita." + n + "@bank.example";
        people(Map.of("via", "add", "made", List.of(made(dana, "kilnmark-designers;kilnmark-sensitive-data"))));
        // on a Starter Pack install an import gives the class a password: they wait for their first sign-in
        people(Map.of("via", "import", "made", List.of(made(vic, "kilnmark-viewers"),
            made(sam, "kilnmark-stewards;kilnmark-sensitive-data"))));
        people(Map.of("via", "add", "made", List.of(made(old, "kilnmark-designers;kilnmark-sensitive-data"))));
        people(Map.of("via", "expire", "disabled", List.of(old), "hours", 72));
        people(Map.of("via", "add", "made", List.of(made(back, "kilnmark-viewers"))));
        people(Map.of("via", "expire", "disabled", List.of(back)));
        people(Map.of("via", "activate", "activated", List.of(back)));
        people(Map.of("via", "add", "made", List.of(made(rita, "kilnmark-admins"))));
        // rita signs in: her sign-in, by the user name the bundled Keycloak gives her, takes her place
        audit.record(new AuditLog.Actor("sso:" + rita + " (0f1e2d3c)", null), AuditLog.SIGN_IN, "session",
            Map.of("role", "admin", "unmask", false, "groups", List.of("kilnmark-admins")));

        JsonPath r = review();
        Map<String, Object> d = entry(r, dana);
        assertEquals("waiting", d.get("state"));
        assertEquals("waiting for first sign-in", d.get("status"));
        assertEquals("designer", d.get("role"), "the role their groups give once they sign in");
        assertEquals(true, d.get("unmask"), "a designer in the sensitive-data group");
        assertEquals(List.of("kilnmark-designers", "kilnmark-sensitive-data"), d.get("groups"));
        assertEquals(List.of("kilnmark-sensitive-data"), d.get("sensitiveGroups"));
        assertEquals("provider", d.get("kind"));
        assertNull(d.get("lastUsedAt"));
        assertTrue(String.valueOf(d.get("createdBy")).contains("token"), "who recorded them: " + d.get("createdBy"));

        Map<String, Object> s = entry(r, sam);
        assertEquals("steward", s.get("role"));
        assertEquals(false, s.get("unmask"), "in the group, but not a designer: sees values masked");
        assertEquals(List.of("kilnmark-sensitive-data"), s.get("sensitiveGroups"));
        assertEquals("waiting", entry(r, vic).get("state"));
        assertEquals("viewer", entry(r, vic).get("role"));

        Map<String, Object> o = entry(r, old);
        assertEquals("disabled", o.get("state"));
        assertEquals("disabled: temporary password unused for 72 hours", o.get("status"));
        assertEquals("waiting", entry(r, back).get("state"), "activated again after being disabled");

        assertNull(entry(r, rita), "listed by her sign-in instead");
        Map<String, Object> signedIn = entry(r, "sso:" + rita + " (0f1e2d3c)");
        assertEquals("signed-in", signedIn.get("state"));
        assertEquals("admin", signedIn.get("role"));
        assertEquals(List.of("kilnmark-admins"), signedIn.get("groups"));
    }

    @Test
    void aSignInGivenNoRoleIsShownAndOneBeforeBeingMadeAgainIsKeptApart() {
        String n = Long.toString(System.nanoTime(), 36);
        String nora = "nora." + n + "@bank.example";
        String ezra = "ezra." + n + "@bank.example";
        people(Map.of("via", "add", "made", List.of(made(nora, "kilnmark-viewers"))));
        audit.record(new AuditLog.Actor("sso:" + nora + " (aa11)", null), AuditLog.SIGN_IN_REFUSED, "session",
            Map.of("reason", OidcAccess.NO_MFA, "groups", List.of("kilnmark-viewers")));
        // ezra signed in, then was made again (removed and added in Keycloak): his old sign-in is not the new one
        audit.record(new AuditLog.Actor("sso:" + ezra + " (bb22)", null), AuditLog.SIGN_IN, "session",
            Map.of("role", "viewer", "unmask", false, "groups", List.of("kilnmark-viewers")));
        audit.awaitStored(5000);
        people(Map.of("via", "add", "made", List.of(made(ezra, "kilnmark-stewards"))));

        JsonPath r = review();
        assertNull(entry(r, nora), "she has signed in since she was made");
        Map<String, Object> refused = entry(r, "sso:" + nora + " (aa11)");
        assertEquals("no-role", refused.get("state"));
        assertTrue(String.valueOf(refused.get("status")).startsWith("signed in, but given no role: "), refused.toString());
        assertNull(refused.get("role"));
        assertEquals("waiting", entry(r, ezra).get("state"));
        assertEquals("steward", entry(r, ezra).get("role"));
        assertEquals("signed-in", entry(r, "sso:" + ezra + " (bb22)").get("state"));
    }

    @Test
    void theReviewSaysWhatEachRoleMayDoAndTheRulesInForce() {
        JsonPath r = review();
        assertEquals(Map.of("viewer", "no", "steward", "no", "designer", "grant", "admin", "no"),
            r.getMap("roles.find { it.id == 'unmask' }.byRole"));
        assertEquals(List.of("kilnmark-sensitive-data"), r.getList("groups.unmask"));
        assertEquals(List.of("kilnmark-admins"), r.getList("groups.admin"));
        assertEquals("90 days", r.getString("rules.find { it.id == 'token-lifetime' }.value"));
        assertTrue(r.getString("rules.find { it.id == 'unmask-approval' }.value").startsWith("Off"));
        assertTrue(r.getString("rules.find { it.id == 'schedules' }.value").contains("30 days"));
        assertTrue(r.getString("rules.find { it.id == 'hosted-models' }.value") != null);
        // sign-in with a provider is off here, so its session settings are not shown
        assertTrue(r.getString("rules.find { it.id == 'provider' }.value").startsWith("Off"));
        assertNull(r.get("rules.find { it.id == 'max-session' }"));
    }

    @Test
    void theCsvHasTheNewColumnsAndNoTokenSecret() {
        String n = Long.toString(System.nanoTime(), 36);
        String lena = "lena." + n + "@bank.example";
        people(Map.of("via", "add", "made", List.of(made(lena, "kilnmark-viewers"))));
        String secret = token("steward", false);
        audit.awaitStored(5000);
        String csv = admin().accept("text/csv").get("/api/v1/access-review?format=csv").then().statusCode(200)
            .extract().asString();
        assertTrue(csv.startsWith("kind,name,role,unmask,status,created_by,created_at,expires_at,last_used_at,sign_ins,"
            + "unmask_approved_by,example,state,groups,sensitive_data_groups\r\n"), csv.lines().findFirst().orElse(""));
        assertTrue(csv.contains("\"provider\",\"" + lena + "\",\"viewer\",\"false\",\"waiting for first sign-in\""), csv);
        assertTrue(csv.contains(",\"waiting\",\"kilnmark-viewers\",\"\"\r\n"), csv);
        String json = admin().get("/api/v1/access-review").then().statusCode(200).extract().asString();
        assertFalse(csv.contains(secret) || json.contains(secret), "never a token's secret");
        assertFalse(json.contains("\"hash\"") || json.contains("\"secret\"") || json.contains("\"token\":"),
            "never a token's hash or secret, by any name");
        // admins only, as before
        Tokens.as(secret).get("/api/v1/access-review").then().statusCode(403);
        Tokens.as(token("designer", true)).get("/api/v1/access-review?format=csv").then().statusCode(403);
    }

    @Test
    void hoursAreRecordedOnlyForWhoWasDisabledAndWithinTheSettingsRange() {
        String n = Long.toString(System.nanoTime(), 36);
        String p = "h." + n + "@bank.example";
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
            Map.of("via", "expire", "disabled", List.of(p), "hours", 0),
            Map.of("via", "expire", "disabled", List.of(p), "hours", 721),
            Map.of("via", "expire", "hours", 72),
            Map.of("via", "add", "made", List.of(made(p, "kilnmark-viewers")), "hours", 72))) {
            admin().contentType(ContentType.JSON).body(bad).post("/api/v1/auth/people").then().statusCode(400);
        }
        people(Map.of("via", "expire", "disabled", List.of(p), "hours", 720));
        audit.awaitStored(5000);
        AuditLog.PersonRecord rec = audit.peopleRecorded().stream().filter(x -> x.person().equals(p)).findFirst()
            .orElseThrow();
        assertEquals(720, rec.hours());
        assertEquals("disabled", rec.latest());
        assertNull(rec.groups(), "Kilnmark never recorded them being made");
    }

    @Test
    void aSignInsNameMatchesOnlyThatUser() {
        assertTrue(AccessReviewResource.sameUser("sso:rita@bank.example (0f1e)", "rita@bank.example"));
        assertFalse(AccessReviewResource.sameUser("sso:rita@bank.example.evil (0f1e)", "rita@bank.example"));
        assertFalse(AccessReviewResource.sameUser("sso:xrita@bank.example (0f1e)", "rita@bank.example"));
        assertFalse(AccessReviewResource.sameUser("rita@bank.example (0f1e)", "rita@bank.example"));
        assertFalse(AccessReviewResource.sameUser(null, "rita@bank.example"));
    }
}
