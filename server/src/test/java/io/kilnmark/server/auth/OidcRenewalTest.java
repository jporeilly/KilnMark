package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.schedules.ScheduleService;
import io.kilnmark.server.schedules.ScheduleStore;
import io.kilnmark.server.schedules.ScheduleViews;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;

/**
 * A provider sign-in renews itself when its ID token expires (ADR 0036): the OIDC client gets a fresh
 * ID token with the refresh token, and every check a sign-in passes is made again on it. A stand-in
 * provider (WireMock) issues ID tokens that expire in two seconds and answers the refresh grant as
 * each test needs; the longest session here is three hours, so the tests can reach it.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(OidcRenewalTest.Provider.class)
class OidcRenewalTest {

    public static class Provider implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>();
            m.put("quarkus.oidc.tenant-enabled", "true");
            m.put("quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus");
            m.put("quarkus.oidc.client-id", "kilnmark");
            m.put("quarkus.oidc.credentials.secret", "test-only-client-secret");
            m.put("quarkus.oidc.token.issuer", ISSUER);
            // the stand-in provider advertises no revocation endpoint; a real one (Keycloak) does
            m.put("quarkus.oidc.revoke-path", "${keycloak.url}" + REVOKE);
            m.put("kilnmark.auth.oidc.steward-groups", "kr-stewards");
            m.put("kilnmark.auth.oidc.designer-groups", "kr-designers");
            m.put("kilnmark.auth.oidc.admin-groups", "kr-admins");
            m.put("kilnmark.auth.oidc.max-session", "3h");
            return m;
        }
    }

    static final String ISSUER = "https://server.example.com";
    static final String REVOKE = "/realms/quarkus/protocol/openid-connect/revoke";
    static final Duration MAX_SESSION = Duration.ofHours(3);
    /** How long the short ID tokens last: long enough for a slow first sign-in to finish before it expires. */
    static final Duration SHORT = Duration.ofSeconds(10);

    static final String FLOW = """
        kilnmark: flow/v1
        name: renewal-flow
        nodes:
          - { id: pause, type: wait, for: 1s }
        """;

    @OidcWireMock
    WireMockServer provider;

    @Inject
    AuditLog audit;

    @Inject
    ScheduleService schedules;

    @Inject
    ScheduleStore store;

    /** An ID token as the provider would sign it; no {@code auth_time} when {@code authTime} is null. */
    private static String idToken(String user, List<String> groups, List<String> amr, Instant authTime,
                                  Instant expires) {
        var claims = Jwt.issuer(ISSUER).audience("kilnmark").subject(user).preferredUserName(user)
            .issuedAt(Instant.now()).expiresAt(expires).claim("groups", groups).claim("amr", amr);
        if (authTime != null) {
            claims.claim("auth_time", authTime.getEpochSecond());
        }
        return claims.jws().keyId("1").sign("privateKey.jwk");
    }

    private String tokenPath() {
        String endpoint = given().get(provider.baseUrl() + "/auth/realms/quarkus/.well-known/openid-configuration")
            .then().statusCode(200).extract().path("token_endpoint");
        return URI.create(endpoint).getPath();
    }

    private static String tokens(String idToken, String refreshToken) {
        return "{\"id_token\":\"" + idToken + "\",\"access_token\":\"opaque-access\",\"token_type\":\"Bearer\","
            + "\"expires_in\":2,\"refresh_token\":\"" + refreshToken + "\"}";
    }

    /** The provider answers the refresh grant for {@code refreshToken} with these tokens. */
    private void renewsWith(String refreshToken, String idToken, String nextRefreshToken) {
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo(tokenPath())).atPriority(1)
            .withFormParam("refresh_token", WireMock.equalTo(refreshToken))
            .willReturn(WireMock.okJson(tokens(idToken, nextRefreshToken))));
    }

    /** The provider refuses the refresh grant, as when its own session has ended or the person is disabled. */
    private void refusesRenewal(String refreshToken) {
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo(tokenPath())).atPriority(1)
            .withFormParam("refresh_token", WireMock.equalTo(refreshToken))
            .willReturn(WireMock.aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}")));
    }

    private int renewals(String refreshToken) {
        return provider.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(tokenPath()))
            .withFormParam("refresh_token", WireMock.equalTo(refreshToken))).size();
    }

    /** Signs in as the browser would (OidcSignInTest), the code exchanged for this ID token and refresh token. */
    private Response callback(String idToken, String refreshToken) {
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo(tokenPath())).atPriority(2)
            .withRequestBody(WireMock.containing("grant_type=authorization_code"))
            .willReturn(WireMock.okJson(tokens(idToken, refreshToken))));
        Response toProvider = given().redirects().follow(false).get("/api/v1/auth/login");
        assertEquals(302, toProvider.statusCode(), toProvider.asString());
        Map<String, String> query = query(URI.create(toProvider.header("Location")));
        Response back = given().redirects().follow(false).cookies(new HashMap<>(toProvider.cookies()))
            .queryParam("state", query.get("state")).queryParam("code", "test-code")
            .get(URI.create(query.get("redirect_uri")).getPath());
        assertTrue(back.statusCode() == 302 || back.statusCode() == 303, back.statusCode() + " " + back.asString());
        return back;
    }

    private Map<String, String> signIn(String idToken, String refreshToken) {
        Map<String, String> session = sessionCookies(callback(idToken, refreshToken));
        assertFalse(session.isEmpty(), "a session cookie is set");
        return session;
    }

    private static Map<String, String> sessionCookies(Response r) {
        Map<String, String> session = new HashMap<>();
        r.cookies().forEach((k, v) -> {
            if (k.startsWith("q_session") && !v.isEmpty()) {
                session.put(k, v);
            }
        });
        return session;
    }

    private static boolean sessionCleared(Response r) {
        return r.getDetailedCookies().asList().stream().anyMatch(c -> c.getName().startsWith("q_session")
            && (c.getMaxAge() == 0 || c.getValue().isEmpty()));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            int eq = part.indexOf('=');
            out.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** A request from Kilnmark's own pages, as the web UI sends it. */
    private static Response fromPage(Map<String, String> session, String path) {
        return given().redirects().follow(false).cookies(session)
            .header(SessionRequests.HEADER, SessionRequests.VALUE).get(path);
    }

    private static void waitUntil(Instant when) throws InterruptedException {
        long ms = Duration.between(Instant.now(), when).toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
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

    private long count(String action, String actor) {
        audit.awaitStored(5000);
        return audit.list(action, null, 500).stream().filter(e -> actor.equals(e.actor())).count();
    }

    /** The session ended as the web UI sees it: 499 (or 401), and the OIDC client clears its cookie. */
    private static void ended(Response r) {
        assertTrue(r.statusCode() == 499 || r.statusCode() == 401, r.statusCode() + " " + r.asString());
        assertTrue(sessionCleared(r), "the session cookie is cleared");
    }

    @Test
    void anExpiredSignInRenewsAndCarriesOn() throws Exception {
        Instant signedIn = Instant.now();
        Instant expires = Instant.now().plus(SHORT);
        renewsWith("rt-rena-1", idToken("rena", List.of("kr-admins"), List.of("pwd", "otp"), signedIn,
            Instant.now().plus(Duration.ofMinutes(5))), "rt-rena-2");
        Response back = callback(idToken("rena", List.of("kr-admins"), List.of("pwd", "otp"), signedIn, expires),
            "rt-rena-1");
        // the session cookie outlives the ID token by the longest session, so it is still there to renew
        long maxAge = back.getDetailedCookies().asList().stream().filter(c -> c.getName().startsWith("q_session"))
            .mapToLong(io.restassured.http.Cookie::getMaxAge).max().orElse(-1);
        assertTrue(maxAge >= MAX_SESSION.toSeconds() && maxAge <= MAX_SESSION.toSeconds() + 60,
            "session cookie Max-Age " + maxAge);
        Map<String, String> s = sessionCookies(back);
        fromPage(s, "/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));
        assertEquals(1, count(AuditLog.SIGN_IN, "sso:rena"));

        waitUntil(expires.plusSeconds(1));
        Response renewed = fromPage(s, "/api/v1/me");
        assertEquals(200, renewed.statusCode(), renewed.asString());
        assertEquals("admin", renewed.jsonPath().getString("role"));
        assertEquals(1, renewals("rt-rena-1"), "the expired ID token was renewed with the refresh token");
        Map<String, String> fresh = sessionCookies(renewed);
        assertFalse(fresh.isEmpty(), "the renewed tokens go back in the session cookie");
        fromPage(fresh, "/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));
        assertEquals(1, renewals("rt-rena-1"), "a fresh ID token needs no renewal");
        // a renewal is no sign-in: still the one event
        assertEquals(1, count(AuditLog.SIGN_IN, "sso:rena"));
        assertEquals(0, count(AuditLog.SIGN_IN_REFUSED, "sso:rena"));
    }

    /** The renewed ID token says when the person signed in; past the longest session, renewing ends it. */
    @Test
    void theLongestSessionEndsARenewedSignInToo() throws Exception {
        Instant signedIn = Instant.now().minus(MAX_SESSION).plus(SHORT).plusSeconds(2);
        Instant expires = Instant.now().plus(SHORT);
        // the provider would renew it: its refresh token is still good
        renewsWith("rt-olga", idToken("olga", List.of("kr-admins"), List.of("mfa"), signedIn,
            Instant.now().plus(Duration.ofMinutes(5))), "rt-olga-2");
        Map<String, String> s = signIn(idToken("olga", List.of("kr-admins"), List.of("mfa"), signedIn, expires),
            "rt-olga");
        fromPage(s, "/api/v1/me").then().statusCode(200).body("role", equalTo("admin"));
        waitUntil(signedIn.plus(MAX_SESSION).plusSeconds(1));
        Response r = fromPage(s, "/api/v1/me");
        ended(r);
        assertEquals(1, renewals("rt-olga"), "the provider renewed it; Kilnmark refused");
        assertTrue(sessionCleared(r), "the session cookie is cleared");
    }

    /** A renewed ID token that does not say when the person signed in cannot restart the clock. */
    @Test
    void aRenewalWithoutAuthTimeEndsTheSession() throws Exception {
        Instant expires = Instant.now().plus(SHORT);
        renewsWith("rt-noah", idToken("noah", List.of("kr-admins"), List.of("mfa"), null,
            Instant.now().plus(Duration.ofMinutes(5))), "rt-noah-2");
        Map<String, String> s = signIn(idToken("noah", List.of("kr-admins"), List.of("mfa"), Instant.now(), expires),
            "rt-noah");
        waitUntil(expires.plusSeconds(1));
        ended(fromPage(s, "/api/v1/me"));
        assertEquals(1, renewals("rt-noah"));
    }

    @Test
    void aRenewalWithoutASecondFactorEndsTheSession() throws Exception {
        Instant signedIn = Instant.now();
        Instant expires = Instant.now().plus(SHORT);
        renewsWith("rt-pia", idToken("pia", List.of("kr-admins"), List.of("pwd"), signedIn,
            Instant.now().plus(Duration.ofMinutes(5))), "rt-pia-2");
        Map<String, String> s = signIn(idToken("pia", List.of("kr-admins"), List.of("pwd", "otp"), signedIn, expires),
            "rt-pia");
        fromPage(s, "/api/v1/me").then().statusCode(200);
        waitUntil(expires.plusSeconds(1));
        Response r = fromPage(s, "/api/v1/me");
        ended(r);
        assertEquals(1, renewals("rt-pia"));
        assertTrue(sessionCleared(r), "the session cookie is cleared");
    }

    /** The provider's session has ended, or the person was disabled there: the refresh fails, sign in again. */
    @Test
    void aRenewalTheProviderRefusesEndsTheSession() throws Exception {
        Instant expires = Instant.now().plus(SHORT);
        refusesRenewal("rt-ivo");
        Map<String, String> s = signIn(idToken("ivo", List.of("kr-admins"), List.of("mfa"), Instant.now(), expires),
            "rt-ivo");
        waitUntil(expires.plusSeconds(1));
        Response r = fromPage(s, "/api/v1/me");
        ended(r);
        assertEquals(1, renewals("rt-ivo"));
        assertTrue(sessionCleared(r), "the session cookie is cleared");
    }

    /**
     * A renewal with the role unchanged pauses nothing and records no sign-in; one where the provider
     * has since lowered the role pauses what the person set above it, as a sign-in would; one where
     * none of their groups has a role ends the session and pauses the rest.
     */
    @Test
    void aRenewalPausesSchedulesOnlyWhenTheRoleFalls() throws Exception {
        int made = admin().contentType(ContentType.JSON).body(Map.of("yaml", FLOW, "message", "first"))
            .post("/api/v1/flows").statusCode();
        assertTrue(made == 201 || made == 409, "the flow is there: " + made);
        Instant signedIn = Instant.now();
        Instant expires = Instant.now().plus(SHORT);
        Instant expires2 = expires.plus(SHORT);
        Instant expires3 = expires2.plus(SHORT);
        // first renewal: still a designer; second: only a steward; third: no group with a role
        renewsWith("rt-dora-1", idToken("dora", List.of("kr-designers"), List.of("mfa"), signedIn, expires2),
            "rt-dora-2");
        renewsWith("rt-dora-2", idToken("dora", List.of("kr-stewards"), List.of("mfa"), signedIn, expires3),
            "rt-dora-3");
        renewsWith("rt-dora-3", idToken("dora", List.of("people"), List.of("mfa"), signedIn,
            expires3.plus(Duration.ofMinutes(5))), "rt-dora-4");
        Map<String, String> s = signIn(idToken("dora", List.of("kr-designers"), List.of("mfa"), signedIn, expires),
            "rt-dora-1");
        fromPage(s, "/api/v1/me").then().statusCode(200).body("role", equalTo("designer"));
        ScheduleStore.Setter setter = new ScheduleStore.Setter("sso:dora", null,
            OidcIdentityAugmentor.personRef(person("dora")));
        ScheduleStore.Schedule asDesigner = schedule(setter, "designer");
        ScheduleStore.Schedule asSteward = schedule(setter, "steward");
        try {
            waitUntil(expires.plusSeconds(1));
            Response r1 = fromPage(s, "/api/v1/me");
            assertEquals(200, r1.statusCode(), r1.asString());
            assertEquals("designer", r1.jsonPath().getString("role"));
            Map<String, String> s1 = sessionCookies(r1);

            waitUntil(expires2.plusSeconds(1));
            assertTrue(store.find(asDesigner.id()).orElseThrow().enabled(), "a renewal as designer paused nothing");
            Response r2 = fromPage(s1, "/api/v1/me");
            assertEquals(200, r2.statusCode(), r2.asString());
            assertEquals("steward", r2.jsonPath().getString("role"));
            await("the designer schedule to pause", () -> !store.find(asDesigner.id()).orElseThrow().enabled());
            assertTrue(store.find(asSteward.id()).orElseThrow().enabled());
            Map<String, String> s2 = sessionCookies(r2);

            waitUntil(expires3.plusSeconds(1));
            ended(fromPage(s2, "/api/v1/me"));
            await("the steward schedule to pause", () -> !store.find(asSteward.id()).orElseThrow().enabled());

            audit.awaitStored(5000);
            long paused = audit.list(AuditLog.SCHEDULE_PAUSED, null, 500).stream()
                .filter(e -> ("schedule " + asDesigner.id()).equals(e.target())
                    || ("schedule " + asSteward.id()).equals(e.target())).count();
            assertEquals(2, paused, "one pause each, from the renewals that lowered the role");
            assertEquals(1, count(AuditLog.SIGN_IN, "sso:dora"), "renewals record no sign-in");
            assertEquals(0, count(AuditLog.SIGN_IN_REFUSED, "sso:dora"), "nor a refused one");
        } finally {
            schedules.delete(asDesigner.id(), "test", AccessRole.ADMIN);
            schedules.delete(asSteward.id(), "test", AccessRole.ADMIN);
        }
    }

    /** Signing out asks the provider to revoke the refresh token, so a copied cookie cannot renew. */
    @Test
    void signingOutRevokesTheRefreshToken() {
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo("/auth" + REVOKE)).willReturn(WireMock.ok()));
        Map<String, String> s = signIn(idToken("otto", List.of("kr-admins"), List.of("mfa"), Instant.now(),
            Instant.now().plus(Duration.ofMinutes(5))), "rt-otto");
        Response out = given().cookies(s).header(SessionRequests.HEADER, SessionRequests.VALUE)
            .post("/api/v1/auth/sign-out");
        assertEquals(200, out.statusCode(), out.asString());
        assertTrue(sessionCleared(out), "the session cookie is cleared");
        assertEquals(1, provider.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/auth" + REVOKE))
            .withFormParam("token", WireMock.equalTo("rt-otto"))).size(), "the refresh token is revoked");
    }

    /** A provider that fails to revoke never stops the person signing out. */
    @Test
    void signingOutWorksWhenTheProviderCannotRevoke() {
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo("/auth" + REVOKE)).atPriority(1)
            .withFormParam("token", WireMock.equalTo("rt-ulla"))
            .willReturn(WireMock.aResponse().withStatus(500)));
        Map<String, String> s = signIn(idToken("ulla", List.of("kr-admins"), List.of("mfa"), Instant.now(),
            Instant.now().plus(Duration.ofMinutes(5))), "rt-ulla");
        Response out = given().cookies(s).header(SessionRequests.HEADER, SessionRequests.VALUE)
            .post("/api/v1/auth/sign-out");
        assertEquals(200, out.statusCode(), out.asString());
        assertTrue(sessionCleared(out), "the session cookie is cleared");
        assertEquals(1, count(AuditLog.SIGN_OUT, "sso:ulla"));
    }

    private ScheduleStore.Schedule schedule(ScheduleStore.Setter by, String runAs) {
        return schedules.create(new ScheduleViews.ScheduleChange(null, "renewal-flow", "@daily", null, Map.of(), runAs,
            true, null), by, AccessRole.DESIGNER);
    }

    /** The identity the stand-in provider's sign-in for {@code sub} has, as far as the person key goes. */
    private static io.quarkus.security.identity.SecurityIdentity person(String sub) {
        JsonWebToken token = new JsonWebToken() {
            @Override public String getName() { return sub; }
            @Override public Set<String> getClaimNames() { return Set.of("iss", "sub"); }
            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) ("iss".equals(name) ? ISSUER : "sub".equals(name) ? sub : null);
            }
        };
        return QuarkusSecurityIdentity.builder().setPrincipal(token).addCredential(new IdTokenCredential("id-token"))
            .build();
    }
}
