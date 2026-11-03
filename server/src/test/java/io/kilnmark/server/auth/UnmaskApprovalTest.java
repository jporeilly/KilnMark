package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;

/**
 * Two people for the unmask grant (ADR 0038): with kilnmark.auth.unmask-approval on, a designer
 * token asked to see unmasked values waits until a second person approves it. Both sign in with the
 * identity provider (a stand-in here, WireMock) and are told apart by its fixed id for each, so a
 * token, the asker, or the asker under another name cannot approve.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(UnmaskApprovalTest.TwoPeople.class)
class UnmaskApprovalTest {

    public static class TwoPeople implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>();
            m.put("quarkus.oidc.tenant-enabled", "true");
            m.put("quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus");
            m.put("quarkus.oidc.client-id", "kilnmark");
            m.put("quarkus.oidc.credentials.secret", "test-only-client-secret");
            m.put("quarkus.oidc.token.issuer", ISSUER);
            m.put("kilnmark.auth.oidc.admin-groups", "km-admins");
            m.put("kilnmark.auth.unmask-approval", "true");
            return m;
        }
    }

    static final String ISSUER = "https://server.example.com";

    @OidcWireMock
    WireMockServer provider;

    @Inject
    AuditLog audit;

    /** A provider session for the person {@code sub}, going by {@code name}: an admin with MFA. */
    private Map<String, String> signIn(String sub, String name) {
        String idToken = Jwt.issuer(ISSUER).audience("kilnmark").subject(sub).preferredUserName(name)
            .issuedAt(Instant.now()).expiresIn(Duration.ofMinutes(5)).claim("groups", List.of("km-admins"))
            .claim("amr", List.of("pwd", "mfa")).claim("auth_time", Instant.now().getEpochSecond())
            .jws().keyId("1").sign("privateKey.jwk");
        String tokenEndpoint = given().get(provider.baseUrl() + "/auth/realms/quarkus/.well-known/openid-configuration")
            .then().statusCode(200).extract().path("token_endpoint");
        provider.stubFor(WireMock.post(WireMock.urlPathEqualTo(URI.create(tokenEndpoint).getPath())).atPriority(1)
            .willReturn(WireMock.okJson("{\"id_token\":\"" + idToken + "\",\"access_token\":\"opaque\","
                + "\"token_type\":\"Bearer\",\"expires_in\":300}")));
        Response toProvider = given().redirects().follow(false).get("/api/v1/auth/login");
        Map<String, String> q = new HashMap<>();
        for (String part : URI.create(toProvider.header("Location")).getRawQuery().split("&")) {
            int eq = part.indexOf('=');
            q.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        Response back = given().redirects().follow(false).cookies(toProvider.cookies())
            .queryParam("state", q.get("state")).queryParam("code", "test-code")
            .get(URI.create(q.get("redirect_uri")).getPath());
        Map<String, String> session = new HashMap<>();
        back.cookies().forEach((k, v) -> {
            if (k.startsWith("q_session") && !v.isEmpty()) {
                session.put(k, v);
            }
        });
        assertTrue(!session.isEmpty(), "signed in");
        return session;
    }

    private static io.restassured.specification.RequestSpecification person(Map<String, String> session) {
        return given().cookies(session).header(SessionRequests.HEADER, SessionRequests.VALUE);
    }

    private static JsonPath designer(Map<String, String> asker) {
        return person(asker).contentType(ContentType.JSON).body(Map.of("name", "analyst-" + System.nanoTime(),
            "role", "designer", "expiresAt", Instant.now().plus(Duration.ofDays(10)).toString()))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath();
    }

    @Test
    void aSecondPersonApproves() {
        Map<String, String> alice = signIn("alice-sub", "alice");
        JsonPath t = designer(alice);
        long id = t.getLong("id");
        assertEquals(false, t.getBoolean("unmask"));
        as(t.getString("token")).get("/api/v1/me").then().body("unmask", equalTo(false));
        admin().get("/api/v1/tokens").then().body("find { it.id == " + id + " }.unmaskRequestedBy", startsWith("sso:alice"));

        // not the asker, not under another name, and not a token
        person(alice).post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(403);
        person(signIn("alice-sub", "Alice Renamed")).post("/api/v1/tokens/" + id + "/unmask/approve").then()
            .statusCode(403);
        admin().post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(403);
        as(t.getString("token")).get("/api/v1/me").then().body("unmask", equalTo(false));

        Map<String, String> bob = signIn("bob-sub", "bob");
        person(bob).post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(200)
            .body("unmask", equalTo(true)).body("unmaskApprovedBy", startsWith("sso:bob"))
            .body("unmaskRequestedBy", nullValue());
        as(t.getString("token")).get("/api/v1/me").then().body("unmask", equalTo(true));
        person(bob).post("/api/v1/tokens/" + id + "/unmask/approve").then().statusCode(409);

        audit.awaitStored(5000);
        assertTrue(audit.list(AuditLog.UNMASK_APPROVED, null, 50).stream().anyMatch(e -> ("token " + id)
            .equals(e.target()) && String.valueOf(e.detail().get("askedBy")).startsWith("sso:alice")),
            "the approval names who asked");
        admin().get("/api/v1/access-review").then().statusCode(200)
            .body("entries.find { it.unmaskApprovedBy != null }.unmaskApprovedBy", notNullValue());
    }

    /** A token names no person, and an admin can always make another: it cannot ask for the grant. */
    @Test
    void aTokenCannotAskForTheGrant() {
        admin().contentType(ContentType.JSON).body(Map.of("name", "by-token-" + System.nanoTime(), "role", "designer"))
            .post("/api/v1/tokens").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("name", "masked-" + System.nanoTime(), "role", "designer",
            "unmask", false)).post("/api/v1/tokens").then().statusCode(201).body("unmask", equalTo(false));
    }

    /** The asker, or anyone, may withdraw it: the token stays, without the grant. */
    @Test
    void aRefusedGrantLeavesTheTokenWithoutIt() {
        Map<String, String> alice = signIn("alice-sub", "alice");
        JsonPath t = designer(alice);
        person(alice).post("/api/v1/tokens/" + t.getLong("id") + "/unmask/refuse").then().statusCode(200)
            .body("unmask", equalTo(false)).body("unmaskRequestedBy", nullValue());
        person(signIn("bob-sub", "bob")).post("/api/v1/tokens/" + t.getLong("id") + "/unmask/approve").then()
            .statusCode(409);
    }

    /** Without sign-in through the provider, no one could be told apart: the server does not start. */
    @Test
    void approvalNeedsTheProvider() {
        assertThrows(IllegalStateException.class, () -> ApiTokens.checkApproval(true, false));
        ApiTokens.checkApproval(true, true);
        ApiTokens.checkApproval(false, false);
    }
}
