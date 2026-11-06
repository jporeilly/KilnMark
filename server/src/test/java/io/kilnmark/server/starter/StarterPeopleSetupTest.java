package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The Starter Pack's example people (ADR 0051), made by keycloak-setup (containers/keycloak/setup.sh)
 * with Keycloak's own admin, against the Keycloak image compose pins: only with
 * KILNMARK_STARTER=allowed, never on an install marked production; each carries the pack's mark,
 * which a person cannot set on themselves; a run never resets an existing person's password unless
 * asked; and once the switch goes, exactly the marked people go, never someone else with one of the
 * addresses.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StarterPeopleSetupTest {

    static final String ADMIN_PASSWORD = "kc-admin-0123456789";
    static final String FIRST = "first-people-password-0123";
    static final String SECOND = "second-people-password-4567";
    static final List<String> PEOPLE = List.of("vera.lane@kilnmark.com", "sam.okafor@kilnmark.com",
        "dana.reyes@kilnmark.com", "priya.shah@kilnmark.com", "alex.morgan@kilnmark.com");
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

    /** keycloak-setup as compose runs it, with these settings, and as the control script asks for people. */
    private static ExecResult setup(String starter, String production, String action, String password) throws Exception {
        String env = "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='"
            + ADMIN_PASSWORD + "' KILNMARK_OIDC_CLIENT_SECRET=oidc-0123456789 KILNMARK_URL=http:" + "/" + "/localhost:8780"
            + " KILNMARK_STARTER='" + starter + "' KILNMARK_PRODUCTION='" + production + "'"
            + " KILNMARK_STARTER_PEOPLE_ACTION='" + action + "' KILNMARK_STARTER_PEOPLE_PASSWORD='" + password + "'";
        return KC.execInContainer("bash", "-c", env + "; bash /opt/kilnmark/setup.sh");
    }

    private static void ok(ExecResult r) {
        assertEquals(0, r.getExitCode(), r.getStdout() + r.getStderr());
    }

    private static String token(String realm, String user, String password) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/realms/" + realm
            + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username="
                + URLEncoder.encode(user, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? JSON.readTree(r.body()).path("access_token").asText() : null;
    }

    private static String admin() throws Exception {
        return token("master", "admin", ADMIN_PASSWORD);
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

    private static JsonNode user(String username) throws Exception {
        JsonNode list = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users?exact=true&briefRepresentation=false"
            + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8), null).body());
        return list.size() == 0 ? null : list.get(0);
    }

    private static boolean marked(JsonNode user) {
        JsonNode v = user.path("attributes").path("kilnmark_starter_pack");
        return v.isArray() && v.size() == 1 && "true".equals(v.get(0).asText());
    }

    /** Whether the person signs in with the password (the realm's direct grant, with admin-cli). */
    private static boolean signsIn(String user, String password) throws Exception {
        return token("kilnmark", user, password) != null;
    }

    @Test
    @Order(1)
    void noPeopleWithoutTheSwitchOrOnProduction() throws Exception {
        ok(setup("", "false", "", ""));
        // A real person, made by an admin, with one of the addresses: never adopted, never deleted.
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username",
            "vera.lane@kilnmark.com", "email", "vera.lane@kilnmark.com", "enabled", true)).statusCode());
        ExecResult r = setup("", "false", "add", FIRST);
        assertNotEquals(0, r.getExitCode(), "refused without KILNMARK_STARTER=allowed");
        r = setup("allowed", "true", "add", FIRST);
        assertNotEquals(0, r.getExitCode(), "refused on production");
        assertTrue(r.getStderr().contains("not marked production"), r.getStderr());
        for (String p : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(p), p + " was not made");
        }
    }

    @Test
    @Order(2)
    void allowedTheyAreMadeMarkedInTheirGroupsLeavingSomeoneElseAlone() throws Exception {
        // An admin had let people set their own unmanaged attributes: the run takes that back.
        String profile = call(admin(), "GET", "/admin/realms/kilnmark/users/profile", null).body();
        JsonNode p = JSON.readTree(profile);
        ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("unmanagedAttributePolicy", "ENABLED");
        assertEquals(200, call(admin(), "PUT", "/admin/realms/kilnmark/users/profile", p).statusCode());

        ok(setup("allowed", "false", "add", FIRST));
        assertEquals("ADMIN_EDIT", JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/profile", null).body())
            .path("unmanagedAttributePolicy").asText(), "only admins see and set the mark");
        assertFalse(marked(user("vera.lane@kilnmark.com")), "someone else's account is not taken over");
        assertFalse(signsIn("vera.lane@kilnmark.com", FIRST));
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            JsonNode u = user(person);
            assertNotNull(u, person);
            assertTrue(marked(u), person + " carries the mark");
            assertTrue(signsIn(person, FIRST), person + " signs in with the password given");
        }
        JsonNode priya = user("priya.shah@kilnmark.com");
        JsonNode groups = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + priya.path("id").asText()
            + "/groups", null).body());
        assertEquals(2, groups.size(), "designers and sensitive data: " + groups);
        assertTrue(JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark", null).body()).path("adminEventsEnabled")
            .asBoolean(), "Keycloak records the changes as admin events");
    }

    @Test
    @Order(3)
    void aRunNeverResetsAPasswordUnlessAskedTo() throws Exception {
        ok(setup("allowed", "false", "", ""));          // an ordinary up
        ok(setup("allowed", "false", "add", SECOND));   // add again, with another password
        assertTrue(signsIn("sam.okafor@kilnmark.com", FIRST), "the password they had is kept");
        assertFalse(signsIn("sam.okafor@kilnmark.com", SECOND));
        ok(setup("allowed", "false", "new-password", SECOND));
        assertTrue(signsIn("sam.okafor@kilnmark.com", SECOND), "--new-password gives them all the new one");
        assertFalse(signsIn("sam.okafor@kilnmark.com", FIRST));
    }

    @Test
    @Order(4)
    void noOneCanPutTheMarkOnThemselves() throws Exception {
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "eve@example.com",
            "email", "eve@example.com", "firstName", "Eve", "lastName", "Example", "enabled", true, "credentials",
            List.of(Map.of("type", "password", "value", "eve-password-0123", "temporary", false)))).statusCode());
        String eve = token("kilnmark", "eve@example.com", "eve-password-0123");
        assertNotNull(eve);
        HttpResponse<String> self = call(eve, "POST", "/realms/kilnmark/account", Map.of("username", "eve@example.com",
            "email", "eve@example.com", "firstName", "Eve", "lastName", "Example", "attributes",
            Map.of("kilnmark_starter_pack", List.of("true"))));
        assertTrue(self.statusCode() < 500, self.statusCode() + " " + self.body());
        assertFalse(marked(user("eve@example.com")), "the account console does not set the mark: " + self.statusCode());
    }

    @Test
    @Order(5)
    void withoutTheSwitchExactlyTheMarkedPeopleGo() throws Exception {
        // the people never made by the pack are kept: vera.lane (an admin's) and eve
        ok(setup("allowed", "true", "", ""));   // marked production: they go at once
        for (String p : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(p), p + " removed once the install is marked production");
        }
        assertNotNull(user("vera.lane@kilnmark.com"), "the unmarked person stays");
        assertNotNull(user("eve@example.com"));

        ok(setup("allowed", "false", "add", FIRST));
        assertNotNull(user("alex.morgan@kilnmark.com"));
        ok(setup("", "false", "", ""));         // KILNMARK_STARTER removed: the next up removes them
        for (String p : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(p), p + " removed once the switch went");
        }
        assertNotNull(user("vera.lane@kilnmark.com"), "the unmarked person stays");

        ok(setup("allowed", "false", "add", FIRST));
        ok(setup("allowed", "false", "remove", ""));   // starter people --remove
        assertNull(user("dana.reyes@kilnmark.com"));
        assertNotNull(user("vera.lane@kilnmark.com"));
    }
}
