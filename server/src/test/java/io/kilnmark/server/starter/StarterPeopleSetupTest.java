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
 * KILNMARK_STARTER=allowed, never on an install marked production (KILNMARK_PRODUCTION anything but
 * false, in any case); their password only on its standard input; each in the group
 * kilnmark-starter-pack, which only setup puts people in as it makes them, so someone else with one
 * of the addresses is never adopted, whatever they set on themselves; a run never resets an existing
 * person's password unless asked; the realm's own settings are left as they are; and once the
 * switch goes, exactly the people in the group go, whatever the realm lets admins see.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StarterPeopleSetupTest {

    static final String ADMIN_PASSWORD = "kc-admin-0123456789";
    static final String FIRST = "first-people-password-0123";
    static final String SECOND = "second-people-password-4567";
    static final String VERAS_OWN = "vera-own-password-0123";
    static final String GROUP = "/kilnmark-starter-pack";
    /** A team's own group, with a subgroup of the pack's group's name: never taken for it. */
    static final String DECOY = "A real team";
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

    /**
     * keycloak-setup as compose runs it, with these settings, and as the control script asks for
     * people: the password on its standard input, one line ending CR LF as Windows PowerShell sends it.
     */
    private static ExecResult setup(String starter, String production, String action, String password) throws Exception {
        return KC.execInContainer("bash", "-c", env(starter, production, action) + "; printf '%s\\r\\n' '" + password
            + "' | bash /opt/kilnmark/setup.sh");
    }

    private static String env(String starter, String production, String action) {
        return "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='"
            + ADMIN_PASSWORD + "' KILNMARK_OIDC_CLIENT_SECRET=oidc-0123456789 KILNMARK_URL=http:" + "/" + "/localhost:8780"
            + " KILNMARK_STARTER='" + starter + "' KILNMARK_PRODUCTION='" + production + "'"
            + " KILNMARK_STARTER_PEOPLE_ACTION='" + action + "'";
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

    /** The paths of the groups the person is in. */
    private static List<String> groups(JsonNode user) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode g : JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + user.path("id").asText()
            + "/groups", null).body())) {
            out.add(g.path("path").asText());
        }
        return out;
    }

    /** The status of the group at {@code path} ("/a/b"): 200 when it is there. */
    private static int group(String path) throws Exception {
        return call(admin(), "GET", "/admin/realms/kilnmark/group-by-path" + path.replace(" ", "%20"), null).statusCode();
    }

    /** Makes a group (under {@code parent}'s id, or at the top); its id. */
    private static String makeGroup(String parent, String name) throws Exception {
        HttpResponse<String> r = call(admin(), "POST", "/admin/realms/kilnmark/groups"
            + (parent == null ? "" : "/" + parent + "/children"), Map.of("name", name));
        assertEquals(201, r.statusCode(), r.body());
        String at = r.headers().firstValue("Location").orElseThrow();
        return at.substring(at.lastIndexOf('/') + 1);
    }

    private static JsonNode realm() throws Exception {
        return JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark", null).body());
    }

    private static JsonNode profile() throws Exception {
        return JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/profile", null).body());
    }

    /** Whether the person signs in with the password (the realm's direct grant, with admin-cli). */
    private static boolean signsIn(String user, String password) throws Exception {
        return token("kilnmark", user, password) != null;
    }

    @Test
    @Order(1)
    void noPeopleWithoutTheSwitchOrOnProduction() throws Exception {
        ok(setup("", "false", "", ""));
        // The site lets people set attributes of their own, and keeps admin events' details: setup
        // changes neither. Someone with one of the addresses put the old mark on themselves.
        com.fasterxml.jackson.databind.node.ObjectNode p = (com.fasterxml.jackson.databind.node.ObjectNode) profile();
        p.put("unmanagedAttributePolicy", "ENABLED");
        assertEquals(200, call(admin(), "PUT", "/admin/realms/kilnmark/users/profile", p).statusCode());
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark", Map.of("adminEventsDetailsEnabled", true))
            .statusCode());
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username",
            "vera.lane@kilnmark.com", "email", "vera.lane@kilnmark.com", "firstName", "Vera", "lastName", "Lane",
            "enabled", true, "attributes", Map.of("kilnmark_starter_pack", List.of("true")), "credentials",
            List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", false)))).statusCode());
        // A team's own group holds a subgroup named as the pack's group, and Vera: Keycloak's group
        // search answers the pack's name with this parent, which setup must never take for its own.
        String team = makeGroup(null, DECOY);
        makeGroup(team, GROUP.substring(1));
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + user("vera.lane@kilnmark.com")
            .path("id").asText() + "/groups/" + team, null).statusCode());

        ExecResult r = setup("", "false", "add", FIRST);
        assertNotEquals(0, r.getExitCode(), "refused without KILNMARK_STARTER=allowed");
        // production as the server reads it (true in any case), and anything else but false
        for (String production : List.of("true", "TRUE", "True", "1", "yes", "on")) {
            r = setup("allowed", production, "add", FIRST);
            assertNotEquals(0, r.getExitCode(), "refused on production: " + production);
            assertTrue(r.getStderr().contains("not marked production"), r.getStderr());
        }
        // the password comes on standard input only, never from the environment
        r = KC.execInContainer("bash", "-c", env("allowed", "false", "add") + " KILNMARK_STARTER_PEOPLE_PASSWORD='" + FIRST
            + "'; bash /opt/kilnmark/setup.sh < /dev/null");
        assertNotEquals(0, r.getExitCode(), "no password on its input: refused");
        assertTrue(r.getStderr().contains("on standard input"), r.getStderr());
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(person), person + " was not made");
        }
    }

    @Test
    @Order(2)
    void allowedTheyAreMadeInTheGroupLeavingSomeoneElseAndTheRealmAlone() throws Exception {
        ok(setup("allowed", "FALSE", "add", FIRST));   // false in any case: not production
        JsonNode vera = user("vera.lane@kilnmark.com");
        assertEquals(List.of("/" + DECOY), groups(vera), "someone else with the address is not adopted, whatever they set");
        assertFalse(signsIn("vera.lane@kilnmark.com", FIRST));
        assertTrue(signsIn("vera.lane@kilnmark.com", VERAS_OWN), "their own password is kept");
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            JsonNode u = user(person);
            assertNotNull(u, person);
            assertTrue(groups(u).contains(GROUP), person + " is in " + GROUP);
            assertTrue(signsIn(person, FIRST), person + " signs in with the password given, CR and all dropped");
        }
        assertEquals(java.util.Set.of(GROUP, "/kilnmark-designers", "/kilnmark-sensitive-data"),
            java.util.Set.copyOf(groups(user("priya.shah@kilnmark.com"))));
        assertEquals(java.util.Set.of(GROUP, "/kilnmark-admins"), java.util.Set.copyOf(groups(user("alex.morgan@kilnmark.com"))));
        JsonNode realm = realm();
        assertTrue(realm.path("adminEventsEnabled").asBoolean(), "Keycloak records the changes as admin events");
        assertTrue(realm.path("adminEventsDetailsEnabled").asBoolean(), "the site's own event setting is kept");
        assertEquals("ENABLED", profile().path("unmanagedAttributePolicy").asText(), "the site's own policy is kept");
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
        assertTrue(signsIn("vera.lane@kilnmark.com", VERAS_OWN), "never someone else's");
    }

    @Test
    @Order(4)
    void withoutTheSwitchExactlyThePeopleInTheGroupGo() throws Exception {
        // the realm now hides unmanaged attributes from everyone (no policy): the group still marks them
        com.fasterxml.jackson.databind.node.ObjectNode p = (com.fasterxml.jackson.databind.node.ObjectNode) profile();
        p.remove("unmanagedAttributePolicy");
        assertEquals(200, call(admin(), "PUT", "/admin/realms/kilnmark/users/profile", p).statusCode());

        // where the realm lets user names change, an example admin renamed out of kilnmark.com still goes
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark", Map.of("editUsernameAllowed", true)).statusCode());
        com.fasterxml.jackson.databind.node.ObjectNode alex = (com.fasterxml.jackson.databind.node.ObjectNode)
            user("alex.morgan@kilnmark.com");
        String alexId = alex.path("id").asText();
        alex.put("username", "a.morgan@example.test");
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + alexId, alex).statusCode());
        assertNotNull(user("a.morgan@example.test"), "renamed");

        ok(setup("allowed", "1", "", ""));   // marked production (1 is not false): they go at once
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(person), person + " removed once the install is marked production");
        }
        assertEquals(404, call(admin(), "GET", "/admin/realms/kilnmark/users/" + alexId, null).statusCode(),
            "the renamed example admin is removed too");
        assertNotNull(user("vera.lane@kilnmark.com"), "someone else stays");

        ok(setup("allowed", "false", "add", FIRST));
        assertNotNull(user("alex.morgan@kilnmark.com"));
        ok(setup("", "false", "", ""));         // KILNMARK_STARTER removed: the next up removes them
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(person), person + " removed once the switch went");
        }
        assertNotNull(user("vera.lane@kilnmark.com"), "someone else stays");
        assertEquals(404, group(GROUP), "the empty group goes too");
        assertEquals(200, group("/" + DECOY), "a team's own group stays");
        assertEquals(200, group("/" + DECOY + GROUP), "and its subgroup of that name");
        assertEquals(List.of("/" + DECOY), groups(user("vera.lane@kilnmark.com")));

        ok(setup("allowed", "false", "add", FIRST));
        ok(setup("allowed", "false", "remove", ""));   // starter people --remove
        assertNull(user("dana.reyes@kilnmark.com"));
        assertNotNull(user("vera.lane@kilnmark.com"));
        assertTrue(signsIn("vera.lane@kilnmark.com", VERAS_OWN));
    }
}
