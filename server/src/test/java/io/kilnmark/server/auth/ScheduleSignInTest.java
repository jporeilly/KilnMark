package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.schedules.ScheduleService;
import io.kilnmark.server.schedules.ScheduleStore;
import io.kilnmark.server.schedules.ScheduleViews;
import io.kilnmark.server.schedules.Setters;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.oidc.SecurityEvent;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * A schedule set by a person signed in through the identity provider, through the real code (ADR
 * 0046): the key computed when the schedule is saved ({@link ScheduleService#setter}) is the one
 * the sign-in event records and pauses by ({@link SignInEvents}); a sign-in records only the groups
 * the settings map to roles; a sign-in with a lower role pauses what is now above it.
 */
@QuarkusTest
@TestProfile(ScheduleSignInTest.Mapped.class)
class ScheduleSignInTest {

    /** Two groups mapped to roles; no provider is needed, as the sign-in events are fired here. */
    public static class Mapped implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.auth.oidc.designer-groups", "ssi-designers",
                "kilnmark.auth.oidc.steward-groups", "ssi-stewards");
        }
    }

    static final String FLOW = """
        kilnmark: flow/v1
        name: ssi-flow
        nodes:
          - { id: pause, type: wait, for: 1s }
        """;

    @Inject
    Event<SecurityEvent> events;

    @Inject
    ScheduleService schedules;

    @Inject
    ScheduleStore store;

    @Inject
    Setters setters;

    @Inject
    AuditLog audit;

    /** A provider sign-in for {@code sub} in {@code groups}: an ID token with two factors, signed in now. */
    private static SecurityIdentity person(String sub, String... groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https:" + "/" + "/idp.example.test/realms/kilnmark");
        claims.put("sub", sub);
        claims.put("name", "Sam " + sub);
        claims.put("amr", List.of("pwd", "otp"));
        claims.put("auth_time", Instant.now().getEpochSecond());
        claims.put("groups", List.of(groups));
        JsonWebToken token = new JsonWebToken() {
            @Override public String getName() { return (String) claims.get("name"); }
            @Override public Set<String> getClaimNames() { return claims.keySet(); }
            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) claims.get(name);
            }
        };
        return QuarkusSecurityIdentity.builder().setPrincipal(token).addCredential(new IdTokenCredential("id-token"))
            .addRole("designer").build();
    }

    private static void await(String what, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private void signIn(SecurityIdentity who) {
        events.fire(new SecurityEvent(SecurityEvent.Type.OIDC_LOGIN, who));
        audit.awaitStored(5000);
    }

    private JsonPath events(String action) {
        return admin().queryParam("action", action).queryParam("limit", 100).get("/api/v1/audit").then()
            .statusCode(200).extract().jsonPath();
    }

    private ScheduleStore.Schedule schedule(ScheduleStore.Setter by, String runAs) {
        return schedules.create(new ScheduleViews.ScheduleChange(null, "ssi-flow", "@daily", null, Map.of(), runAs,
            true, null), by, AccessRole.DESIGNER);
    }

    @Test
    void aSignInWithALowerRolePausesWhatIsNowAboveIt() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("yaml", FLOW, "message", "first")).post("/api/v1/flows")
            .then().statusCode(201);
        SecurityIdentity sam = person("sam-0005", "ssi-designers", "deal-team-wall-crossed");
        signIn(sam);
        // only the group the settings map is recorded; the other never is
        JsonPath in = events(AuditLog.SIGN_IN);
        assertEquals(List.of("ssi-designers"), in.getList("events.find { it.detail.person != null && "
            + "it.actor.contains('sam-0005') }.detail.groups"), in.prettify());
        assertFalse(in.prettify().contains("deal-team-wall-crossed"));

        ScheduleStore.Setter setter = ScheduleService.setter(sam, "sso:Sam sam-0005 (sam-0005)");
        assertNotNull(setter.person());
        ScheduleStore.Schedule asDesigner = schedule(setter, "designer");
        ScheduleStore.Schedule asSteward = schedule(setter, "steward");
        assertNull(setters.problem(store.find(asDesigner.id()).orElseThrow()));

        // someone else's sign-in leaves them alone
        signIn(person("sam-0006", "ssi-stewards"));
        // Sam signs in again, now only a steward: the designer schedule pauses at once, the other runs on
        signIn(person("sam-0005", "ssi-stewards"));
        await("the designer schedule to pause", () -> !store.find(asDesigner.id()).orElseThrow().enabled());
        assertEquals(ScheduleStore.PAUSED, store.find(asDesigner.id()).orElseThrow().lastOutcome());
        assertTrue(store.find(asSteward.id()).orElseThrow().enabled());
        assertNull(setters.problem(store.find(asSteward.id()).orElseThrow()));

        // and when none of Sam's groups has a role any more, the rest pauses too
        signIn(person("sam-0005", "people"));
        await("the steward schedule to pause", () -> !store.find(asSteward.id()).orElseThrow().enabled());
        JsonPath refused = events(AuditLog.SIGN_IN_REFUSED);
        assertTrue(refused.getList("events.detail.person").contains(setter.person()), refused.prettify());
        schedules.delete(asDesigner.id(), "test", AccessRole.ADMIN);
        schedules.delete(asSteward.id(), "test", AccessRole.ADMIN);
    }

    @Test
    void pausingAtSignInNeverThrows() {
        ScheduleService failing = new ScheduleService(null, null, null, null) {
            @Override
            public void personSignedIn(String person, AccessRole role, AuditLog.Actor actor) {
                throw new IllegalStateException("the database is not reachable");
            }
        };
        assertDoesNotThrow(() -> SignInEvents.pause(failing, "p:x", null, new AuditLog.Actor("sso:x", null)));
    }
}
