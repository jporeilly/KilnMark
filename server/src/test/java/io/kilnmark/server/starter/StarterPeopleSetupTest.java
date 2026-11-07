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
 * with Keycloak's own admin, against the Keycloak image compose pins: only on a Starter Pack install
 * (KILNMARK_MODE=starter-pack exactly) with KILNMARK_STARTER=allowed; their password only on its
 * standard input; each in the group kilnmark-starter-pack, which carries a mark only setup sets, so
 * a same-named group setup did not make (from a directory, say) is never taken for it; the five
 * exact addresses an earlier version made are brought into the group; a run never resets an existing
 * person's password unless asked; the realm's own settings are left as they are; once the switch
 * goes, exactly the group's members go, renamed or not; and a production install refuses a realm
 * that has any of the five.
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
    private static ExecResult setup(String starter, String mode, String action, String password) throws Exception {
        return KC.execInContainer("bash", "-c", env(starter, mode, action) + "; printf '%s\\r\\n' '" + password
            + "' | bash /opt/kilnmark/setup.sh");
    }

    private static String env(String starter, String mode, String action) {
        return "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='"
            + ADMIN_PASSWORD + "' KILNMARK_OIDC_CLIENT_SECRET=oidc-0123456789 KILNMARK_URL=http:" + "/" + "/localhost:8780"
            + " KILNMARK_STARTER='" + starter + "' KILNMARK_MODE='" + mode + "'"
            + " KILNMARK_STARTER_PEOPLE_ACTION='" + action + "'";
    }

    static final String STARTER = "starter-pack";
    static final String PRODUCTION = "production";

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
        ok(setup("", PRODUCTION, "", ""));   // a production install, with no example people: fine
        // The site lets people set attributes of their own, and keeps admin events' details: setup
        // changes neither. Someone not made by the pack has one of the five addresses, and sets
        // the attribute an earlier design marked the pack's people with.
        com.fasterxml.jackson.databind.node.ObjectNode p = (com.fasterxml.jackson.databind.node.ObjectNode) profile();
        p.put("unmanagedAttributePolicy", "ENABLED");
        assertEquals(200, call(admin(), "PUT", "/admin/realms/kilnmark/users/profile", p).statusCode());
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark", Map.of("adminEventsDetailsEnabled", true))
            .statusCode());
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username",
            "vera.lane@kilnmark.com", "email", "vera.lane@kilnmark.com", "firstName", "Vera", "lastName", "Lane",
            "enabled", true, "attributes", Map.of("kilnmark_starter_pack", List.of("true")), "credentials",
            List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", false)))).statusCode());
        // A team's own group holds a subgroup named as the pack's group: Keycloak's group search
        // answers the pack's name with this parent, which setup must never take for its own.
        String team = makeGroup(null, DECOY);
        makeGroup(team, GROUP.substring(1));
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + user("vera.lane@kilnmark.com")
            .path("id").asText() + "/groups/" + team, null).statusCode());

        ok(setup("", PRODUCTION, "", ""));   // a production run leaves her as she is
        assertNotNull(user("vera.lane@kilnmark.com"));

        ExecResult r = setup("", STARTER, "add", FIRST);
        assertNotEquals(0, r.getExitCode(), "refused without KILNMARK_STARTER=allowed");
        // only starter-pack, exactly, is a Starter Pack install
        for (String mode : List.of(PRODUCTION, "", "Starter-Pack", "starter_pack", "STARTER-PACK", "true")) {
            r = setup("allowed", mode, "add", FIRST);
            assertNotEquals(0, r.getExitCode(), "refused: " + mode);
            assertTrue(r.getStderr().contains("Starter Pack install"), r.getStderr());
        }
        // the password comes on standard input only, never from the environment
        r = KC.execInContainer("bash", "-c", env("allowed", STARTER, "add") + " KILNMARK_STARTER_PEOPLE_PASSWORD='" + FIRST
            + "'; bash /opt/kilnmark/setup.sh < /dev/null");
        assertNotEquals(0, r.getExitCode(), "no password on its input: refused");
        assertTrue(r.getStderr().contains("on standard input"), r.getStderr());
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(person), person + " was not made");
        }
    }

    @Test
    @Order(2)
    void allowedTheyAreMadeInTheMarkedGroupAndSomeoneElseWithAnAddressIsLeftAlone() throws Exception {
        ExecResult r = setup("allowed", STARTER, "add", FIRST);
        ok(r);
        // someone the pack did not make, with one of the addresses: never taken into it
        assertTrue(r.getStdout().contains("left vera.lane@kilnmark.com as they are"), r.getStdout());
        JsonNode vera = user("vera.lane@kilnmark.com");
        assertEquals(List.of("/" + DECOY), groups(vera), "never given the pack's group or its people's groups");
        assertFalse(signsIn("vera.lane@kilnmark.com", FIRST));
        assertTrue(signsIn("vera.lane@kilnmark.com", VERAS_OWN), "her own password is kept");
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            JsonNode u = user(person);
            assertNotNull(u, person);
            assertTrue(groups(u).contains(GROUP), person + " is in " + GROUP);
            assertTrue(signsIn(person, FIRST), person + " signs in with the password given, CR and all dropped");
        }
        assertEquals(java.util.Set.of(GROUP, "/kilnmark-designers", "/kilnmark-sensitive-data"),
            java.util.Set.copyOf(groups(user("priya.shah@kilnmark.com"))));
        assertEquals(java.util.Set.of(GROUP, "/kilnmark-admins"), java.util.Set.copyOf(groups(user("alex.morgan@kilnmark.com"))));
        // the group carries the mark only setup sets
        JsonNode g = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/group-by-path" + GROUP, null).body());
        assertEquals("true", g.path("attributes").path("kilnmark_starter_pack").path(0).asText(), g.toString());
        JsonNode realm = realm();
        assertTrue(realm.path("adminEventsEnabled").asBoolean(), "Keycloak records the changes as admin events, in both modes");
        assertTrue(realm.path("adminEventsDetailsEnabled").asBoolean(), "the site's own event setting is kept");
        assertEquals("ENABLED", profile().path("unmanagedAttributePolicy").asText(), "the site's own policy is kept");
    }

    @Test
    @Order(3)
    void aRunNeverResetsAPasswordUnlessAskedTo() throws Exception {
        ok(setup("allowed", STARTER, "", ""));          // an ordinary up
        ok(setup("allowed", STARTER, "add", SECOND));   // add again, with another password
        assertTrue(signsIn("sam.okafor@kilnmark.com", FIRST), "the password they had is kept");
        assertFalse(signsIn("sam.okafor@kilnmark.com", SECOND));
        ok(setup("allowed", STARTER, "new-password", SECOND));
        assertTrue(signsIn("sam.okafor@kilnmark.com", SECOND), "--new-password gives them all the new one");
        assertFalse(signsIn("sam.okafor@kilnmark.com", FIRST));
        assertTrue(signsIn("vera.lane@kilnmark.com", VERAS_OWN), "someone the pack did not make keeps her own");
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

        ok(setup("", STARTER, "", ""));         // KILNMARK_STARTER removed: the next up removes them
        for (String person : PEOPLE.subList(1, PEOPLE.size())) {
            assertNull(user(person), person + " removed once the switch went");
        }
        assertNotNull(user("vera.lane@kilnmark.com"), "never someone the pack did not make");
        assertEquals(404, call(admin(), "GET", "/admin/realms/kilnmark/users/" + alexId, null).statusCode(),
            "the renamed example admin is removed too");
        assertEquals(404, group(GROUP), "the empty group goes too");
        assertEquals(200, group("/" + DECOY), "a team's own group stays");
        assertEquals(200, group("/" + DECOY + GROUP), "and its subgroup of that name");

        ok(setup("allowed", STARTER, "add", FIRST));
        ok(setup("allowed", STARTER, "remove", ""));   // starter people --remove
        assertNull(user("dana.reyes@kilnmark.com"));
        ok(setup("", PRODUCTION, "", ""));
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/users/" + user("vera.lane@kilnmark.com")
            .path("id").asText(), null).statusCode());
    }

    @Test
    @Order(5)
    void aSameNamedGroupSetupDidNotMakeIsNeverTakenForThePacks() throws Exception {
        // as a directory federation would bring it: a top-level kilnmark-starter-pack, without the mark,
        // holding a real person
        String theirs = makeGroup(null, GROUP.substring(1));
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "rita@bank.example",
            "email", "rita@bank.example", "enabled", true)).statusCode());
        String rita = user("rita@bank.example").path("id").asText();
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + rita + "/groups/" + theirs, null)
            .statusCode());
        for (String action : List.of("add", "")) {
            ExecResult r = action.isEmpty() ? setup("", STARTER, "", "") : setup("allowed", STARTER, action, FIRST);
            assertNotEquals(0, r.getExitCode(), "refused: " + action);
            assertTrue(r.getStderr().contains("did not make"), r.getStderr());
            assertNotNull(user("rita@bank.example"), "its members are never touched");
        }
        assertNull(user("sam.okafor@kilnmark.com"), "nothing was made in it either");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/groups/" + theirs, null).statusCode());
    }

    static final String RITAS_FIRST = "rita-first-password-0123";

    /** people add: these in setup's environment, the password on its input. */
    private static ExecResult addPerson(String mode, String email, String first, String last, String groups,
        String password) throws Exception {
        return KC.execInContainer("bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " KILNMARK_PEOPLE_ACTION=add KILNMARK_PERSON_EMAIL='"
            + email + "' KILNMARK_PERSON_FIRST_NAME='" + first + "' KILNMARK_PERSON_LAST_NAME='" + last
            + "' KILNMARK_PERSON_GROUPS='" + groups + "'; printf '%s\\r\\n' '" + password + "' | bash /opt/kilnmark/setup.sh");
    }

    /**
     * people import: on a Starter Pack install the class's password, then the file, on setup's input,
     * as the control script sends them; on production the file alone (password null).
     */
    private static ExecResult importPeople(String mode, String password, String csv) throws Exception {
        KC.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(
            (password == null ? "" : password + "\r\n") + csv), "/tmp/people-in");
        return KC.execInContainer("bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " KILNMARK_PEOPLE_ACTION=import;"
            + " bash /opt/kilnmark/setup.sh < /tmp/people-in; rc=$?; rm -f /tmp/people-in; exit $rc");
    }

    /** people activate: the email in setup's environment, the password on its input. */
    private static ExecResult activate(String mode, String email, String password) throws Exception {
        return KC.execInContainer("bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "")
            + " KILNMARK_PEOPLE_ACTION=activate KILNMARK_PERSON_EMAIL='" + email + "'; printf '%s\\n' '" + password
            + "' | bash /opt/kilnmark/setup.sh");
    }

    /** An ordinary run, as if at {@code hoursLater} hours from now (KILNMARK_SETUP_NOW_MS, for tests only). */
    private static ExecResult later(String mode, int hoursLater, String activationHours) throws Exception {
        long at = System.currentTimeMillis() + hoursLater * 3_600_000L;
        return KC.execInContainer("bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "")
            + " KILNMARK_SETUP_NOW_MS=" + at + (activationHours == null ? "" : " KILNMARK_PEOPLE_ACTIVATION_HOURS=" + activationHours)
            + "; bash /opt/kilnmark/setup.sh < /dev/null");
    }

    private static ExecResult listPeople(String mode) throws Exception {
        return KC.execInContainer("bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " KILNMARK_PEOPLE_ACTION=list; bash /opt/kilnmark/setup.sh"
            + " < /dev/null");
    }

    /** What the realm's direct grant says to this password: Keycloak's error, or empty when it signs in. */
    private static String grant(String user, String password) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base()
            + "/realms/kilnmark/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username="
                + URLEncoder.encode(user, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? "" : JSON.readTree(r.body()).path("error_description").asText();
    }

    private static String created(JsonNode user) throws Exception {
        return JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + user.path("id").asText()
            + "/credentials", null).body()).path(0).path("createdDate").asText();
    }

    @Test
    @Order(6)
    void peopleAddMakesAPersonOnceInTheirGroupsWithATemporaryPassword() throws Exception {
        // install's first admin is this, with kilnmark-admins
        ExecResult r = addPerson(PRODUCTION, "Rita.Okoro@Bank.example", "Rita", "Okoro", "kilnmark-admins", RITAS_FIRST);
        ok(r);
        assertTrue(r.getStdout().contains("made rita.okoro@bank.example (kilnmark-admins): they choose their own password"),
            r.getStdout());
        assertFalse((r.getStdout() + r.getStderr()).contains(RITAS_FIRST), "the password never appears");
        JsonNode rita = user("rita.okoro@bank.example");
        assertNotNull(rita, "made, in lower case");
        assertEquals("rita.okoro@bank.example", rita.path("email").asText());
        assertEquals("Rita", rita.path("firstName").asText());
        assertEquals("Okoro", rita.path("lastName").asText());
        assertEquals(List.of("/kilnmark-admins"), groups(rita), "an admin, never in the Starter Pack's group");
        assertTrue(rita.path("requiredActions").toString().contains("UPDATE_PASSWORD"), rita.toString());
        assertFalse(rita.path("attributes").has("kilnmark_starter_pack"), "no mark");
        // the password given is theirs for now, and Keycloak makes them change it first
        assertTrue(grant("rita.okoro@bank.example", RITAS_FIRST).contains("not fully set up"),
            grant("rita.okoro@bank.example", RITAS_FIRST));
        assertTrue(grant("rita.okoro@bank.example", SECOND).contains("Invalid user credentials"));
        String when = created(rita);

        // made once: a second run, with other names, groups and password, changes nothing
        r = addPerson(PRODUCTION, "rita.okoro@bank.example", "Someone", "Else", "kilnmark-viewers", SECOND);
        ok(r);
        assertTrue(r.getStdout().contains("left rita.okoro@bank.example as they are"), r.getStdout());
        assertFalse((r.getStdout() + r.getStderr()).contains(SECOND), "the password never appears");
        JsonNode again = user("rita.okoro@bank.example");
        assertEquals("Rita", again.path("firstName").asText());
        assertEquals(List.of("/kilnmark-admins"), groups(again), "their groups are unchanged");
        assertEquals(when, created(again), "their password is the one they had");
        assertTrue(grant("rita.okoro@bank.example", RITAS_FIRST).contains("not fully set up"));
        // an ordinary up afterwards changes nothing either
        ok(setup("", PRODUCTION, "", ""));
        assertEquals(when, created(user("rita.okoro@bank.example")));
        // someone with that email under another user name is left as they are too
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "lee",
            "email", "lee@bank.example", "enabled", true)).statusCode());
        r = addPerson(PRODUCTION, "lee@bank.example", "Lee", "Park", "kilnmark-admins", RITAS_FIRST);
        ok(r);
        assertTrue(r.getStdout().contains("left lee@bank.example as they are"), r.getStdout());
        assertNull(user("lee@bank.example"), "no second person with that email");
        assertEquals(List.of(), groups(user("lee")), "and nobody was made an admin");
        // more than one group, written either way
        ok(addPerson(PRODUCTION, "omar@bank.example", "Omar", "Haddad", "kilnmark-designers, kilnmark-sensitive-data",
            RITAS_FIRST));
        assertEquals(java.util.Set.of("/kilnmark-designers", "/kilnmark-sensitive-data"),
            java.util.Set.copyOf(groups(user("omar@bank.example"))));
    }

    @Test
    @Order(7)
    void peopleAddRefusesExampleAddressesKilnmarkDotComAndStrangeGroups() throws Exception {
        for (String email : List.of("vera.lane@kilnmark.com", "ALEX.MORGAN@KILNMARK.COM", "someone@kilnmark.com",
            "someone@eu.kilnmark.com")) {
            ExecResult r = addPerson(PRODUCTION, email, "Vera", "Lane", "kilnmark-viewers", RITAS_FIRST);
            assertNotEquals(0, r.getExitCode(), email);
            assertTrue(r.getStderr().contains("kilnmark.com address"), r.getStderr());
            assertFalse((r.getStdout() + r.getStderr()).contains(RITAS_FIRST), "the password never appears");
            assertNull(user(email.toLowerCase(java.util.Locale.ROOT)), email + " was not made");
        }
        for (String[] bad : new String[][] {{"not-an-address", "Kim", "Lee", "kilnmark-viewers", "not an email address"},
            {"kim@bank.example", "", "Lee", "kilnmark-viewers", "a first and a last name"},
            {"kim@bank.example", "Kim", "Lee", "", "no group"},
            {"kim@bank.example", "Kim", "Lee", "kilnmark-starter-pack", "not one of Kilnmark's"},
            {"kim@bank.example", "Kim", "Lee", "kilnmark-viewers;kilnmark-viewers", "named twice"}}) {
            ExecResult r = addPerson(PRODUCTION, bad[0], bad[1], bad[2], bad[3], RITAS_FIRST);
            assertNotEquals(0, r.getExitCode(), bad[4]);
            assertTrue(r.getStderr().contains(bad[4]), r.getStderr());
        }
        ExecResult r = addPerson(PRODUCTION, "kim@bank.example", "Kim", "Lee", "kilnmark-viewers", "short");
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("at least 12 characters"), r.getStderr());
        r = addPerson(PRODUCTION, "kim@bank.example", "Kim", "Lee", "kilnmark-viewers", "a-tab\there-0123456789");
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("control character"), r.getStderr());
        assertNull(user("kim@bank.example"));
        // never in the same run as the example people
        r = KC.execInContainer("bash", "-c", env("allowed", STARTER, "add") + " KILNMARK_PEOPLE_ACTION=add"
            + " KILNMARK_PERSON_EMAIL=kim@bank.example KILNMARK_PERSON_FIRST_NAME=Kim KILNMARK_PERSON_LAST_NAME=Lee"
            + " KILNMARK_PERSON_GROUPS=kilnmark-viewers; printf '%s\\n' '" + RITAS_FIRST + "' | bash /opt/kilnmark/setup.sh");
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("separate runs"), r.getStderr());
        assertNull(user("kim@bank.example"));
        assertNull(user("sam.okafor@kilnmark.com"));
    }

    /** The example file, exactly as the release ships it, comment line and all. */
    static String exampleFile() throws java.io.IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of("../containers/keycloak/people.example.csv"));
    }

    @Test
    @Order(8)
    void importChecksTheWholeFileFirstAndABadLineMakesNobody() throws Exception {
        String good = exampleFile();
        // each bad line is named by its number, never its values, and nobody is made
        String bad = good + "alex.morgan@example.com,Alex,Morgan,kilnmark-admins\n"   // line 7: an admin
            + "VERA.LANE@example.com,Vera,Again,kilnmark-viewers\n"                     // line 8: a duplicate
            + "someone@kilnmark.com,Some,One,kilnmark-viewers\n"                        // line 9: kilnmark.com
            + "nobody,No,Body,kilnmark-viewers\n"                                       // line 10: not an address
            + "x@example.com,X,Y,kilnmark-everything\n"                                 // line 11: no such group
            + "y@example.com,Y,Z\n"                                                     // line 12: three fields
            + "z@example.com,,Z,kilnmark-viewers\n"                                     // line 13: no first name
            + "o@example.com,O(Brien,Smith,kilnmark-viewers\n";                         // line 14: Keycloak refuses (
        ExecResult r = importPeople(STARTER, RITAS_FIRST, bad);
        assertNotEquals(0, r.getExitCode());
        String err = r.getStderr();
        for (String line : List.of("line 7: admins are added one at a time", "line 8: the same address",
            "line 9: a kilnmark.com address", "line 10: not an email address", "line 11: a group that is not",
            "line 12: not four fields", "line 13: needs a first and a last name", "line 14: a name holds a character")) {
            assertTrue(err.contains(line), line + ": " + err);
        }
        assertTrue(err.contains("people import: made 0, already there 0, refused 8"), err);
        for (String value : List.of("alex.morgan", "Morgan", "someone@kilnmark", "nobody", "kilnmark-everything",
            "y@example.com", "z@example.com", "O(Brien", RITAS_FIRST)) {
            assertFalse((r.getStdout() + err).contains(value), "a value was echoed: " + value);
        }
        for (String person : List.of("vera.lane@example.com", "sam.okafor@example.com", "dana.reyes@example.com",
            "priya.shah@example.com")) {
            assertNull(user(person), person + ": nobody was made");
        }
        // on production, someone who sees sensitive values never comes from a file: the example's
        // line 6 is refused there, and nobody is made
        r = importPeople(PRODUCTION, null, good);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("line 6: people who see sensitive values are added one at a time"), r.getStderr());
        assertNull(user("vera.lane@example.com"));
        // a file of only an admin is refused too
        r = importPeople(STARTER, RITAS_FIRST, "email,first_name,last_name,groups\nalex@bank.example,Alex,M,kilnmark-viewers;kilnmark-admins\n");
        assertNotEquals(0, r.getExitCode());
        assertNull(user("alex@bank.example"));
        // the header first, and a password for a class
        assertNotEquals(0, importPeople(STARTER, RITAS_FIRST, "mail,first,last,groups\na@bank.example,A,B,kilnmark-viewers\n")
            .getExitCode());
        assertTrue(importPeople(STARTER, "short", good).getStderr().contains("at least 12 characters"));
        assertNull(user("a@bank.example"));
    }

    @Test
    @Order(9)
    void importMakesTheExampleFilesPeopleInTheirGroupsAndLeavesThoseThereAlone() throws Exception {
        // one of them is here already, made by an admin: left exactly as they are
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "sam.okafor@example.com",
            "email", "sam.okafor@example.com", "emailVerified", true, "firstName", "Samuel", "lastName", "Okafor",
            "enabled", true, "credentials",
            List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", false)))).statusCode());
        // on a Starter Pack install, with the example people there too
        ok(setup("allowed", STARTER, "add", FIRST));
        ExecResult r = importPeople(STARTER, RITAS_FIRST, exampleFile().replace("\n", "\r\n"));   // as Windows writes it
        ok(r);
        assertTrue(r.getStdout().contains("people import: made 3, already there 1, refused 0"), r.getStdout());
        assertTrue(r.getStdout().contains("left sam.okafor@example.com as they are"), r.getStdout());
        assertFalse((r.getStdout() + r.getStderr()).contains(RITAS_FIRST), "the password never appears");
        Map<String, java.util.Set<String>> want = Map.of("vera.lane@example.com", java.util.Set.of("/kilnmark-viewers"),
            "dana.reyes@example.com", java.util.Set.of("/kilnmark-designers"),
            "priya.shah@example.com", java.util.Set.of("/kilnmark-designers", "/kilnmark-sensitive-data"));
        for (var e : want.entrySet()) {
            JsonNode u = user(e.getKey());
            assertNotNull(u, e.getKey());
            assertEquals(e.getValue(), java.util.Set.copyOf(groups(u)), e.getKey() + ": never the pack's group");
            assertTrue(u.path("requiredActions").toString().contains("UPDATE_PASSWORD"), e.getKey());
            assertTrue(grant(e.getKey(), RITAS_FIRST).contains("not fully set up"), e.getKey());
        }
        JsonNode sam = user("sam.okafor@example.com");
        assertEquals("Samuel", sam.path("firstName").asText(), "untouched");
        assertEquals(List.of(), groups(sam), "no group given");
        assertTrue(signsIn("sam.okafor@example.com", VERAS_OWN), "their password is the one they had");

        // list: everyone, their Kilnmark groups, whether active, and who must still choose a password
        r = listPeople(STARTER);
        ok(r);
        assertTrue(r.getStdout().contains("person priya.shah@example.com groups=kilnmark-designers;kilnmark-sensitive-data "
            + "status=active must-choose-password=yes") || r.getStdout().contains("person priya.shah@example.com "
            + "groups=kilnmark-sensitive-data;kilnmark-designers status=active must-choose-password=yes"), r.getStdout());
        assertTrue(r.getStdout().contains("person sam.okafor@example.com groups=none status=active must-choose-password=no"),
            r.getStdout());
        assertTrue(r.getStdout().contains("person vera.lane@kilnmark.com groups="), "the example people too");
        assertFalse(r.getStdout().contains(RITAS_FIRST) || r.getStdout().contains(FIRST), "no password");

        // the pack's removal takes its example people only, never people imported beside them
        ok(setup("allowed", STARTER, "remove", ""));
        assertNull(user("vera.lane@kilnmark.com"));
        for (String person : want.keySet()) {
            assertNotNull(user(person), person + " stays");
        }
        ok(setup("", STARTER, "", ""));   // the switch removed: the same
        for (String person : want.keySet()) {
            assertNotNull(user(person), person + " stays");
        }
        // and, on a Starter Pack install too, never someone at kilnmark.com
        assertNotEquals(0, importPeople(STARTER, RITAS_FIRST, "email,first_name,last_name,groups\n"
            + "vera.lane@kilnmark.com,Vera,Lane,kilnmark-viewers\n").getExitCode());
        assertNull(user("vera.lane@kilnmark.com"));
    }

    static final String TEAM = "email,first_name,last_name,groups\nada@bank.example,Ada,Byron,kilnmark-viewers\n"
        + "ben@bank.example,Ben,Ng,kilnmark-designers;kilnmark-stewards\n";

    @Test
    @Order(10)
    void productionImportMakesPeopleWaitingDisabledWithNoPasswordUntilEachIsActivated() throws Exception {
        ExecResult r = importPeople(PRODUCTION, null, TEAM);
        ok(r);
        assertTrue(r.getStdout().contains("made ada@bank.example (kilnmark-viewers): waiting to be activated"), r.getStdout());
        assertTrue(r.getStdout().contains("people import: made 2, already there 0, refused 0"), r.getStdout());
        for (String person : List.of("ada@bank.example", "ben@bank.example")) {
            JsonNode u = user(person);
            assertNotNull(u, person);
            assertFalse(u.path("enabled").asBoolean(), person + " is disabled");
            assertEquals("[]", JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + u.path("id").asText()
                + "/credentials", null).body()).toString(), person + " has no password");
        }
        assertEquals(java.util.Set.of("/kilnmark-designers", "/kilnmark-stewards"), java.util.Set.copyOf(groups(user("ben@bank.example"))));
        r = listPeople(PRODUCTION);
        assertTrue(r.getStdout().contains("person ada@bank.example groups=kilnmark-viewers status=waiting-to-be-activated"),
            r.getStdout());

        // activate: one person, a temporary password of their own; never shown
        r = activate(PRODUCTION, "Ada@Bank.example", RITAS_FIRST);
        ok(r);
        assertTrue(r.getStdout().contains("activated ada@bank.example: they choose their own password"), r.getStdout());
        assertFalse((r.getStdout() + r.getStderr()).contains(RITAS_FIRST), "the password never appears");
        JsonNode ada = user("ada@bank.example");
        assertTrue(ada.path("enabled").asBoolean());
        assertTrue(ada.path("requiredActions").toString().contains("UPDATE_PASSWORD"), ada.toString());
        assertTrue(grant("ada@bank.example", RITAS_FIRST).contains("not fully set up"));
        assertFalse(user("ben@bank.example").path("enabled").asBoolean(), "the others still wait");
        // never someone already active, nor anyone unknown, nor at kilnmark.com
        r = activate(PRODUCTION, "ada@bank.example", SECOND);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("is active already"), r.getStderr());
        assertTrue(grant("ada@bank.example", RITAS_FIRST).contains("not fully set up"), "her password is unchanged");
        assertTrue(activate(PRODUCTION, "nobody@bank.example", SECOND).getStderr().contains("nobody here has that address"));
        assertTrue(activate(PRODUCTION, "x@kilnmark.com", SECOND).getStderr().contains("kilnmark.com address"));
        assertTrue(activate(PRODUCTION, "ben@bank.example", "short").getStderr().contains("at least 12 characters"));
        // nor someone who has chosen their own password and was disabled on purpose
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "cy@bank.example",
            "email", "cy@bank.example", "emailVerified", true, "firstName", "Cy", "lastName", "Ng", "enabled", false,
            "credentials", List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", false)))).statusCode());
        r = activate(PRODUCTION, "cy@bank.example", SECOND);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("has signed in and chosen their own password"), r.getStderr());
        assertFalse(user("cy@bank.example").path("enabled").asBoolean());
    }

    @Test
    @Order(11)
    void anUnusedTemporaryPasswordExpiresAfterTheWindowAndActivateGivesANewOne() throws Exception {
        // the window: 1 to 720 hours
        for (String hours : List.of("0", "721", "72h")) {
            ExecResult r = later(PRODUCTION, 0, hours);
            assertNotEquals(0, r.getExitCode(), hours);
            assertTrue(r.getStderr().contains("KILNMARK_PEOPLE_ACTIVATION_HOURS"), r.getStderr());
        }
        // within it, nobody goes
        ExecResult r = later(PRODUCTION, 71, null);
        ok(r);
        assertTrue(user("ada@bank.example").path("enabled").asBoolean(), "within 72 hours");
        // past it: everyone still holding a temporary password, the first admin too, is disabled, and said
        r = later(PRODUCTION, 73, null);
        ok(r);
        for (String person : List.of("ada@bank.example", "rita.okoro@bank.example", "omar@bank.example")) {
            assertTrue(r.getStdout().contains("disabled " + person + ": their temporary password was not used within 72 hours"),
                person + ": " + r.getStdout());
            assertFalse(user(person).path("enabled").asBoolean(), person);
        }
        // someone with a password of their own is never touched
        assertTrue(user("sam.okafor@example.com").path("enabled").asBoolean());
        // a shorter window, as set
        ok(activate(PRODUCTION, "ada@bank.example", SECOND));
        r = later(PRODUCTION, 2, "1");
        assertTrue(r.getStdout().contains("disabled ada@bank.example"), r.getStdout());
        // activate gives a new temporary password, never shown, and the person may sign in again
        r = activate(PRODUCTION, "rita.okoro@bank.example", SECOND);
        ok(r);
        assertFalse((r.getStdout() + r.getStderr()).contains(SECOND), "the password never appears");
        assertTrue(user("rita.okoro@bank.example").path("enabled").asBoolean());
        assertTrue(grant("rita.okoro@bank.example", SECOND).contains("not fully set up"));
        assertTrue(listPeople(PRODUCTION).getStdout().contains("person ada@bank.example groups=kilnmark-viewers "
            + "status=waiting-to-be-activated must-choose-password=yes"));
    }
}
