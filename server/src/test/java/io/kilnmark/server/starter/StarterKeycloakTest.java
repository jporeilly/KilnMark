package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Starter Pack's service account in the bundled Keycloak (ADR 0051), against the Keycloak image
 * compose.yaml pins and setup.sh as keycloak-setup runs it: the account exists only while
 * KILNMARK_STARTER=allowed and its secret is set; it manages people and groups in the kilnmark realm
 * and is refused anything else (clients, the sign-in flow, the realm, the master realm); Kilnmark's
 * people code adds the five with the pack's mark and removes only people carrying it.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StarterKeycloakTest {

    static final String ADMIN_PASSWORD = "kc-admin-0123456789";
    static final String STARTER_SECRET = "starter-secret-0123456789-0123456789-abc";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    static final GenericContainer<?> KC = new GenericContainer<>(DockerImageName.parse(
        "quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332"))
        .withCommand("start-dev").withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD).withExposedPorts(8080)
        .withCopyFileToContainer(MountableFile.forHostPath("../containers/keycloak/setup.sh"), "/opt/kilnmark/setup.sh")
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

    private static String base() {
        return "http:" + "/" + "/" + KC.getHost() + ":" + KC.getMappedPort(8080);
    }

    private static URI issuer() {
        return URI.create(base() + "/realms/kilnmark");
    }

    /** keycloak-setup, as compose runs it, with the Starter Pack allowed or not. */
    private static void setup(boolean allowed) throws Exception {
        ExecResult r = KC.execInContainer("bash", "-c", "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 "
            + "KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='" + ADMIN_PASSWORD + "' KILNMARK_OIDC_CLIENT_SECRET=oidc-0123456789 "
            + "KILNMARK_URL=http:" + "/" + "/localhost:8780 " + (allowed ? "KILNMARK_STARTER=allowed KILNMARK_KEYCLOAK_STARTER_SECRET='"
                + STARTER_SECRET + "'" : "KILNMARK_STARTER= KILNMARK_KEYCLOAK_STARTER_SECRET=")
            + "; bash /opt/kilnmark/setup.sh");
        assertEquals(0, r.getExitCode(), r.getStdout() + r.getStderr());
    }

    private static String token(String form, String realm) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/realms/" + realm
            + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? JSON.readTree(r.body()).path("access_token").asText() : null;
    }

    private static String starterToken() throws Exception {
        return token("grant_type=client_credentials&client_id=kilnmark-starter&client_secret="
            + URLEncoder.encode(STARTER_SECRET, StandardCharsets.UTF_8), "kilnmark");
    }

    private static String adminToken() throws Exception {
        return token("grant_type=password&client_id=admin-cli&username=admin&password="
            + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8), "master");
    }

    private static HttpResponse<String> call(String token, String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + token);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> groups() {
        return Map.of("viewer", "kilnmark-viewers", "steward", "kilnmark-stewards", "designer", "kilnmark-designers",
            "admin", "kilnmark-admins", "unmask", "kilnmark-sensitive-data");
    }

    private static JsonNode user(String admin, String username) throws Exception {
        HttpResponse<String> r = call(admin, "GET", "/admin/realms/kilnmark/users?exact=true&username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8), null);
        JsonNode list = JSON.readTree(r.body());
        return list.size() == 0 ? null : list.get(0);
    }

    @Test
    @Order(1)
    void withoutTheSettingThereIsNoServiceAccount() throws Exception {
        setup(false);
        assertTrue(starterToken() == null, "no client kilnmark-starter");
    }

    @Test
    @Order(2)
    void theServiceAccountManagesPeopleAndGroupsInTheKilnmarkRealmOnly() throws Exception {
        setup(true);
        setup(true); // again: what is there is kept
        String t = starterToken();
        assertTrue(t != null && !t.isEmpty(), "it signs in with its secret");
        // people and groups: yes
        assertEquals(200, call(t, "GET", "/admin/realms/kilnmark/users?max=1", null).statusCode());
        assertEquals(200, call(t, "GET", "/admin/realms/kilnmark/groups", null).statusCode());
        // everything else: no
        assertEquals(403, call(t, "POST", "/admin/realms/kilnmark/clients", Map.of("clientId", "sneaky")).statusCode(),
            "making a client");
        assertEquals(403, call(t, "GET", "/admin/realms/kilnmark/clients", null).statusCode(), "reading clients");
        assertEquals(403, call(t, "POST", "/admin/realms/kilnmark/authentication/flows", Map.of("alias", "weak",
            "providerId", "basic-flow", "topLevel", true, "builtIn", false)).statusCode(), "making a sign-in flow");
        assertEquals(403, call(t, "PUT", "/admin/realms/kilnmark", Map.of("browserFlow", "browser")).statusCode(),
            "changing the realm's sign-in flow");
        assertEquals(403, call(t, "PUT", "/admin/realms/kilnmark/users/profile", Map.of("attributes", List.of()))
            .statusCode(), "changing the user profile");
        int master = call(t, "GET", "/admin/realms/master/users", null).statusCode();
        assertTrue(master == 401 || master == 403, "the master realm: " + master);
        assertEquals(403, call(t, "POST", "/admin/realms/kilnmark/roles", Map.of("name", "sneaky")).statusCode(),
            "making a realm role");
    }

    @Test
    @Order(3)
    void thePackAddsItsPeopleAndRemovesOnlyThePeopleItMarked() throws Exception {
        String admin = adminToken();
        // A real person who happens to have one of the addresses, added by an admin, unmarked.
        assertEquals(201, call(admin, "POST", "/admin/realms/kilnmark/users", Map.of("username", "vera.lane@kilnmark.com",
            "email", "vera.lane@kilnmark.com", "enabled", true)).statusCode());
        KeycloakPeople people = new KeycloakPeople(issuer(), "kilnmark-starter", STARTER_SECRET, groups());
        List<KeycloakPeople.Outcome> added = people.add(KeycloakPeople.newPassword());
        assertEquals("left", added.get(0).outcome(), "vera.lane is someone else's: " + added);
        assertEquals(4, added.stream().filter(o -> "added".equals(o.outcome())).count(), added.toString());
        assertEquals(List.of("sam.okafor@kilnmark.com", "dana.reyes@kilnmark.com", "priya.shah@kilnmark.com",
            "alex.morgan@kilnmark.com"), people.present());
        JsonNode priya = user(admin, "priya.shah@kilnmark.com");
        assertTrue(KeycloakPeople.marked(priya), "carries the pack's mark");
        JsonNode groups = JSON.readTree(call(admin, "GET", "/admin/realms/kilnmark/users/" + priya.path("id").asText()
            + "/groups", null).body());
        assertEquals(2, groups.size(), "designers and sensitive data: " + groups);
        // again: the same people, a new password, nothing added twice
        assertEquals(4, people.add(KeycloakPeople.newPassword()).stream().filter(o -> "updated".equals(o.outcome())).count());

        List<KeycloakPeople.Outcome> removed = people.remove();
        assertEquals(4, removed.stream().filter(o -> "removed".equals(o.outcome())).count(), removed.toString());
        assertEquals("left", removed.get(0).outcome());
        assertTrue(user(admin, "vera.lane@kilnmark.com") != null, "the unmarked person is never deleted");
        assertEquals(null, user(admin, "alex.morgan@kilnmark.com"));
        assertEquals(List.of(), people.present());
    }

    @Test
    @Order(4)
    void noOneCanMarkThemselvesAndTurningTheSettingOffRemovesTheAccount() throws Exception {
        String admin = adminToken();
        JsonNode profile = JSON.readTree(call(admin, "GET", "/admin/realms/kilnmark/users/profile", null).body());
        assertEquals("ADMIN_EDIT", profile.path("unmanagedAttributePolicy").asText(), "only admins see and set the mark");
        setup(false);
        assertTrue(starterToken() == null, "the client is gone once the setting is off");
        KeycloakPeople people = new KeycloakPeople(issuer(), "kilnmark-starter", STARTER_SECRET, groups());
        KeycloakPeople.Failed e = assertThrows(KeycloakPeople.Failed.class, people::present);
        assertFalse(e.getMessage().contains(STARTER_SECRET) || e.getMessage().contains(base()), "no secret, no address");
    }
}
