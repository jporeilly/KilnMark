package io.kilnmark.server.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
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

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.kilnmark.server.audit.AuditLog;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;

/**
 * Sign-in with an identity provider, end to end (ADR 0036): a stand-in provider (WireMock) signs ID
 * tokens with the groups, second factor and sign-in time each test needs, and the browser's steps
 * (to the provider, back with a code, then the session cookie) are walked with plain requests.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(OidcSignInTest.Provider.class)
class OidcSignInTest {

    public static class Provider implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>();
            m.put("quarkus.oidc.tenant-enabled", "true");
            m.put("quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus");
            m.put("quarkus.oidc.client-id", "kilnmark");
            m.put("quarkus.oidc.credentials.secret", "test-only-client-secret");
            m.put("quarkus.oidc.token.issuer", ISSUER);
            m.put("kilnmark.auth.oidc.provider-name", "Example SSO");
            m.put("kilnmark.auth.oidc.viewer-groups", "km-viewers");
            m.put("kilnmark.auth.oidc.designer-groups", "km-designers");
            m.put("kilnmark.auth.oidc.admin-groups", "km-admins");
            m.put("kilnmark.auth.oidc.unmask-groups", "km-unmask");
            return m;
        }
    }

    static final String ISSUER = "https://server.example.com";

    @OidcWireMock
    WireMockServer provider;

    @Inject
    AuditLog audit;

    /** An ID token as the provider would sign it. */
    private static String idToken(String user, List<String> groups, List<String> amr, Instant authTime) {
        var claims = Jwt.issuer(ISSUER).audience("kilnmark").subject(user).preferredUserName(user)
            .issuedAt(Instant.now()).expiresIn(Duration.ofMinutes(5)).claim("groups", groups)
            .claim("auth_time", authTime.getEpochSecond());
        if (amr != null) {
            claims.claim("amr", amr);
        }
        return claims.jws().keyId("1").sign("privateKey.jwk");
    }

    /**
     * Signs in as the browser would: to /api/v1/auth/login, on to the provider (which here answers the
     * code at once), back to Kilnmark with the code, which it exchanges for this ID token. Returns the
     * session's cookies.
     */
    private Map<String, String> signIn(String idToken) {
        Response back = callback(idToken);
        assertTrue(back.statusCode() == 302 || back.statusCode() == 303, back.statusCode() + " " + back.asString());
        Map<String, String> session = sessionCookies(back);
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

    /** The browser's steps up to Kilnmark's answer to the provider's redirect back, with the code. */
    private Response callback(String idToken) {
        // The token endpoint the provider advertises, as the OIDC client finds it
        String tokenEndpoint = given().get(provider.baseUrl() + "/auth/realms/quarkus/.well-known/openid-configuration")
            .then().statusCode(200).extract().path("token_endpoint");
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo(URI.create(tokenEndpoint).getPath()))
            .atPriority(1)
            .willReturn(WireMock.okJson("{\"id_token\":\"" + idToken + "\",\"access_token\":\"opaque-access\","
                + "\"token_type\":\"Bearer\",\"expires_in\":300}")));
        Response toProvider = given().redirects().follow(false).get("/api/v1/auth/login");
        assertEquals(302, toProvider.statusCode(), toProvider.asString());
        URI location = URI.create(toProvider.header("Location"));
        Map<String, String> query = query(location);
        assertEquals(Long.toString(Duration.ofHours(8).toSeconds()), query.get("max_age"),
            "asks the provider to sign the person in again after the longest session");
        Map<String, String> cookies = new HashMap<>(toProvider.cookies());
        return given().redirects().follow(false).cookies(cookies)
            .queryParam("state", query.get("state")).queryParam("code", "test-code")
            .get(URI.create(query.get("redirect_uri")).getPath());
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            int eq = part.indexOf('=');
            out.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void anAdminSignsInWithMfaAndIsRecorded() {
        Map<String, String> s = signIn(idToken("ada", List.of("km-admins"), List.of("pwd", "mfa"), Instant.now()));
        given().cookies(s).get("/api/v1/me").then().statusCode(200)
            .body("role", equalTo("admin")).body("via", equalTo("provider")).body("unmask", equalTo(false))
            .body("tokenId", nullValue());
        given().cookies(s).get("/api/v1/auth/status").then().statusCode(200)
            .body("provider", equalTo(true)).body("providerName", equalTo("Example SSO"))
            .body("signedIn", equalTo(true)).body("refusal", nullValue());
        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.SIGN_IN, null, 50).stream().anyMatch(e -> "sso:ada".equals(e.actor())),
            "the sign-in is in the audit log, under its sso: name");
    }

    @Test
    void aDesignerInTheUnmaskGroupSeesUnmaskedValues() {
        Map<String, String> s = signIn(idToken("dee", List.of("km-designers", "km-unmask"), List.of("pwd", "hwk"),
            Instant.now()));
        given().cookies(s).get("/api/v1/me").then().statusCode(200)
            .body("role", equalTo("designer")).body("unmask", equalTo(true));
    }

    /** Without a second factor, or without a group Kilnmark knows, there is no role, and the page says why. */
    @Test
    void noSecondFactorOrNoGroupMeansNoRole() {
        Map<String, String> pwd = signIn(idToken("pat", List.of("km-admins"), List.of("pwd"), Instant.now()));
        given().cookies(pwd).get("/api/v1/me").then().statusCode(403);
        given().cookies(pwd).get("/api/v1/auth/status").then().statusCode(200)
            .body("signedIn", equalTo(true)).body("refusal", equalTo(OidcAccess.NO_MFA));
        Map<String, String> admin = signIn(idToken("amy", List.of("admin"), List.of("mfa"), Instant.now()));
        given().cookies(admin).get("/api/v1/me").then().statusCode(403);
        given().cookies(admin).get("/api/v1/auth/status").then().body("refusal", equalTo(OidcAccess.NO_GROUP));
        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.SIGN_IN_REFUSED, null, 50).stream()
            .anyMatch(e -> "sso:pat".equals(e.actor())), "the refused sign-in is in the audit log");
    }

    /**
     * A provider that ignores max_age and answers with a sign-in older than the longest session: no
     * role, the page says why, and the audit log records it.
     */
    @Test
    void aSignInOlderThanTheLongestSessionIsNotAccepted() {
        Map<String, String> old = signIn(idToken("oli", List.of("km-admins"), List.of("mfa"),
            Instant.now().minus(Duration.ofHours(9))));
        given().cookies(old).get("/api/v1/me").then().statusCode(403);
        given().cookies(old).get("/api/v1/auth/status").then().statusCode(200)
            .body("signedIn", equalTo(true)).body("refusal", equalTo(OidcAccess.TOO_OLD));
        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.SIGN_IN_REFUSED, null, 50).stream().anyMatch(e -> "sso:oli".equals(e.actor())
            && OidcAccess.TOO_OLD.equals(e.detail().get("reason"))), "the refusal is in the audit log");
    }

    /** A name the person may choose cannot pass for an anonymous caller, a token or the server. */
    @Test
    void aProviderSignInIsNamedSoNoOneElseCanBeMistakenForIt() {
        Map<String, String> s = signIn(idToken("anonymous", List.of("km-admins"), List.of("mfa"), Instant.now()));
        given().cookies(s).get("/api/v1/me").then().statusCode(200).body("name", equalTo("sso:anonymous"))
            .body("displayName", equalTo("anonymous"));
    }

    /**
     * The screen greets a person by their name at the provider, while Kilnmark records them by the
     * sso: name with the provider's ID, as before.
     */
    @Test
    void thePersonIsGreetedByTheirNameAtTheProvider() {
        var claims = Jwt.issuer(ISSUER).audience("kilnmark").subject("7fa04543-0000").preferredUserName(
            "alex.morgan@example.com").claim("name", "Alex Morgan").issuedAt(Instant.now())
            .expiresIn(Duration.ofMinutes(5)).claim("groups", List.of("km-admins"))
            .claim("auth_time", Instant.now().getEpochSecond()).claim("amr", List.of("pwd", "mfa"));
        Map<String, String> s = signIn(claims.jws().keyId("1").sign("privateKey.jwk"));
        given().cookies(s).get("/api/v1/me").then().statusCode(200)
            .body("displayName", equalTo("Alex Morgan"))
            .body("name", equalTo("sso:alex.morgan@example.com (7fa04543-0000)"));
    }

    /**
     * The provider's redirect back lands on /api/v1/auth/login: a refused session goes on to the page,
     * which says why, never round through the provider again (it would answer with the same sign-in).
     */
    @Test
    void aRefusedSignInGoesToThePageNotRoundAgain() {
        Map<String, String> refused = signIn(idToken("pat", List.of("km-admins"), List.of("pwd"), Instant.now()));
        Response landed = given().redirects().follow(false).cookies(refused).get("/api/v1/auth/login");
        assertEquals(303, landed.statusCode(), landed.asString());
        assertEquals("/", landed.header("Location").replaceFirst("^https?://[^/]+", ""));
    }

    /** Asked to sign in again, the refused session ends and the provider is asked to sign the person in afresh. */
    @Test
    void signingInAgainAsksTheProviderAfresh() {
        Map<String, String> refused = signIn(idToken("pat", List.of("km-admins"), List.of("pwd"), Instant.now()));
        Response again = given().redirects().follow(false).cookies(refused).get("/api/v1/auth/login?again=true");
        assertEquals(303, again.statusCode(), again.asString());
        URI next = URI.create(again.header("Location"));
        assertEquals("/api/v1/auth/login", next.getPath());
        assertTrue(again.getDetailedCookies().asList().stream().anyMatch(c -> c.getName().startsWith("q_session")
            && (c.getMaxAge() == 0 || c.getValue().isEmpty())), "the refused session is ended");
        Response toProvider = given().redirects().follow(false).get(next.getPath() + "?" + next.getRawQuery());
        assertEquals(302, toProvider.statusCode(), toProvider.asString());
        assertEquals("login", query(URI.create(toProvider.header("Location"))).get("prompt"));
        // A plain sign-in does not force a prompt
        Response plain = given().redirects().follow(false).get("/api/v1/auth/login");
        assertEquals(null, query(URI.create(plain.header("Location"))).get("prompt"));
    }

    /** A change made with the session cookie must come from Kilnmark's pages; scripts and tokens are unaffected. */
    @Test
    void changesWithTheSessionNeedKilnmarksOwnHeader() {
        Map<String, String> s = signIn(idToken("ada", List.of("km-admins"), List.of("mfa"), Instant.now()));
        String body = "{\"name\":\"from-a-session\",\"role\":\"viewer\"}";
        given().cookies(s).contentType("application/json").body(body).post("/api/v1/tokens").then().statusCode(403);
        given().cookies(s).header(SessionRequests.HEADER, SessionRequests.VALUE).contentType("application/json")
            .body(body).post("/api/v1/tokens").then().statusCode(201);
        // No token may take a name kept for provider sign-ins
        given().cookies(s).header(SessionRequests.HEADER, SessionRequests.VALUE).contentType("application/json")
            .body("{\"name\":\"SSO:ada\",\"role\":\"viewer\"}").post("/api/v1/tokens").then().statusCode(400);
    }

    /** Without credentials, the API answers 401 as scripts expect, never a redirect to the provider. */
    @Test
    void noCredentialsIsA401NotARedirect() {
        given().redirects().follow(false).get("/api/v1/me").then().statusCode(401);
        given().redirects().follow(false).get("/api/v1/auth/status").then().statusCode(200)
            .body("provider", equalTo(true)).body("signedIn", equalTo(false));
    }

    @Test
    void signingOutEndsTheSession() {
        Map<String, String> s = signIn(idToken("ada", List.of("km-admins"), List.of("mfa"), Instant.now()));
        Response out = given().cookies(s).header(SessionRequests.HEADER, SessionRequests.VALUE)
            .post("/api/v1/auth/sign-out");
        assertEquals(204, out.statusCode(), out.asString());
        assertTrue(out.getDetailedCookies().asList().stream().anyMatch(c -> c.getName().startsWith("q_session")
            && (c.getMaxAge() == 0 || c.getValue().isEmpty())), "the session cookie is cleared");
    }
}
