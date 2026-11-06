package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.schedules.ScheduleService;
import io.kilnmark.server.schedules.ScheduleStore;
import io.kilnmark.server.schedules.ScheduleViews;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.oidc.SecurityEvent;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * A schedule set by a person signed in through the identity provider, through the real code: the
 * key computed when the schedule is saved ({@link ScheduleService#setter}) is the one the sign-in
 * event records and pauses by ({@link SignInEvents}) (ADR 0046). The test profile maps no group to
 * a role, so any provider sign-in here is refused for want of a group.
 */
@QuarkusTest
class ScheduleSignInTest {

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
    SignInEvents signIns;

    @Inject
    AuditLog audit;

    /** A provider sign-in for {@code sub}: an ID token with two factors, signed in now. */
    private static SecurityIdentity person(String sub) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https:" + "/" + "/idp.example.test/realms/kilnmark");
        claims.put("sub", sub);
        claims.put("name", "Sam " + sub);
        claims.put("amr", List.of("pwd", "otp"));
        claims.put("auth_time", Instant.now().getEpochSecond());
        claims.put("groups", List.of("people"));
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

    @Test
    void theKeySavedWithAScheduleIsTheOneASignInPausesBy() {
        admin().contentType(ContentType.JSON).body(Map.of("yaml", FLOW, "message", "first")).post("/api/v1/flows")
            .then().statusCode(201);
        SecurityIdentity sam = person("sam-0005");
        ScheduleStore.Setter setter = ScheduleService.setter(sam, "sso:Sam sam-0005 (sam-0005)");
        assertNotNull(setter.person());
        ScheduleStore.Schedule s = schedules.create(new ScheduleViews.ScheduleChange(null, "ssi-flow", "@daily", null,
            Map.of(), "steward", true, null), setter, AccessRole.DESIGNER);
        // someone else's refused sign-in leaves it alone
        events.fire(new SecurityEvent(SecurityEvent.Type.OIDC_LOGIN, person("sam-0006")));
        assertTrue(store.find(s.id()).orElseThrow().enabled());

        events.fire(new SecurityEvent(SecurityEvent.Type.OIDC_LOGIN, sam));
        ScheduleStore.Schedule after = store.find(s.id()).orElseThrow();
        assertFalse(after.enabled());
        assertEquals(ScheduleStore.PAUSED, after.lastOutcome());

        audit.awaitStored(5000);
        JsonPath refused = admin().queryParam("action", AuditLog.SIGN_IN_REFUSED).queryParam("limit", 50)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        assertTrue(refused.getList("events.detail.person").contains(setter.person()), refused.prettify());
        assertTrue(refused.getList("events.detail.groups").contains(List.of("people")), refused.prettify());
        schedules.delete(s.id(), "test", AccessRole.ADMIN);
    }

    @Test
    @SuppressWarnings("UnnecessaryAssignment") // the bean's injected field, swapped for this test and put back
    void aSignInNeverFailsForWantOfPausingSchedules() {
        SignInEvents bean = io.quarkus.arc.ClientProxy.unwrap(signIns);
        ScheduleService was = bean.schedules;
        bean.schedules = new ScheduleService(null, null, null, null) {
            @Override
            public void personSignedIn(String person, AccessRole role, AuditLog.Actor actor) {
                throw new IllegalStateException("the database is not reachable");
            }
        };
        try {
            assertDoesNotThrow(() -> events.fire(new SecurityEvent(SecurityEvent.Type.OIDC_LOGIN, person("sam-0007"))));
        } finally {
            bean.schedules = was;
        }
    }
}
