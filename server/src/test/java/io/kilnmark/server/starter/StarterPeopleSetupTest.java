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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * that has any of the five. The people commands: a one-time code's label (a CRLF kept, as Keycloak's
 * own form keeps it) never forges a temporary password's time, and credentials that do not read
 * cleanly disable their person (fail closed); groups are compared whole and names hold no
 * formatting character; the pack's marked group is found at any depth; and a refused run changes
 * nothing, Keycloak's master realm included.
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
        return ContainerExec.exec(KC, "bash", "-c", env(starter, mode, action) + "; printf '%s\\r\\n' '" + password
            + "' | bash /opt/kilnmark/setup.sh");
    }

    private static String env(String starter, String mode, String action) {
        return "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='"
            + ADMIN_PASSWORD + "' KILNMARK_OIDC_CLIENT_SECRET=oidc-0123456789 KILNMARK_URL=http:" + "/" + "/localhost:8780"
            + " KILNMARK_STARTER='" + starter + "' KILNMARK_MODE='" + mode + "'"
            + " KILNMARK_STARTER_PEOPLE_ACTION='" + action + "'";
    }

    static final String STARTER = "starter-pack";
    static final String NL = "\n";
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

        // a production realm holding one of the example user names is not a production install's: refused
        ExecResult kept = setup("", PRODUCTION, "", "");
        assertNotEquals(0, kept.getExitCode());
        assertTrue(kept.getStderr().contains("holds the Starter Pack's example people"), kept.getStderr());
        assertNotNull(user("vera.lane@kilnmark.com"), "and left as she is");

        ExecResult r = setup("", STARTER, "add", FIRST);
        assertNotEquals(0, r.getExitCode(), "refused without KILNMARK_STARTER=allowed");
        // only starter-pack, exactly, is a Starter Pack install
        for (String mode : List.of(PRODUCTION, "", "Starter-Pack", "starter_pack", "STARTER-PACK", "true")) {
            r = setup("allowed", mode, "add", FIRST);
            assertNotEquals(0, r.getExitCode(), "refused: " + mode);
            assertTrue(r.getStderr().contains("Starter Pack install"), r.getStderr());
        }
        // the password comes on standard input only, never from the environment
        r = ContainerExec.exec(KC, "bash", "-c", env("allowed", STARTER, "add") + " KILNMARK_STARTER_PEOPLE_PASSWORD='" + FIRST
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

        // an example person given another email (by an admin: people may not change their own) is still one
        com.fasterxml.jackson.databind.node.ObjectNode dana = (com.fasterxml.jackson.databind.node.ObjectNode)
            user("dana.reyes@kilnmark.com");
        dana.put("email", "dana@bank.example");
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + dana.path("id").asText(), dana).statusCode());

        ExecResult gone = setup("", STARTER, "", "");   // KILNMARK_STARTER removed: the next up removes them
        ok(gone);
        for (String person : PEOPLE.subList(1, PEOPLE.size() - 1)) {
            assertNull(user(person), person + " removed once the switch went, by user name, whatever their email");
        }
        assertNotNull(user("vera.lane@kilnmark.com"), "never someone the pack did not make");
        // one an admin renamed is no longer known for an example person: only taken out of the group, and said
        assertEquals(200, call(admin(), "GET", "/admin/realms/kilnmark/users/" + alexId, null).statusCode());
        assertTrue(gone.getStdout().contains("took a.morgan@example.test out of the group kilnmark-starter-pack"), gone.getStdout());
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/users/" + alexId, null).statusCode());
        // and from now on nobody's user name changes, nor may people change their own email or names
        assertFalse(realm().path("editUsernameAllowed").asBoolean(), "user names are fixed");
        for (JsonNode a : profile().path("attributes")) {
            if (List.of("email", "firstName", "lastName").contains(a.path("name").asText())) {
                assertEquals("[\"admin\"]", a.path("permissions").path("edit").toString(), a.toString());
            }
        }
        assertEquals(404, group(GROUP), "the empty group goes too");
        assertEquals(200, group("/" + DECOY), "a team's own group stays");
        assertEquals(200, group("/" + DECOY + GROUP), "and its subgroup of that name");

        ok(setup("allowed", STARTER, "add", FIRST));
        ok(setup("allowed", STARTER, "remove", ""));   // starter people --remove
        assertNull(user("dana.reyes@kilnmark.com"));
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/users/" + user("vera.lane@kilnmark.com")
            .path("id").asText(), null).statusCode());
        ok(setup("", PRODUCTION, "", ""));
        // a production realm with the pack's marked group (a Keycloak kept from a Starter Pack install),
        // its people gone: refused by the group alone
        ok(setup("allowed", STARTER, "add", FIRST));
        for (String person : PEOPLE) {
            assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/users/" + id(person), null).statusCode());
        }
        ExecResult kept = setup("", PRODUCTION, "", "");
        assertNotEquals(0, kept.getExitCode());
        assertTrue(kept.getStderr().contains("holds the Starter Pack's group"), kept.getStderr());
        ok(setup("allowed", STARTER, "remove", ""));
        ok(setup("", PRODUCTION, "", ""));
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
        return enabled(mode, addPersonOnly(mode, email, first, last, groups, password));
    }

    /**
     * As the control script does once Kilnmark's audit log has recorded them: enables those given a
     * temporary password just now, made or activated ({@code r}, setup's run). {@code r} itself.
     */
    private static ExecResult enabled(String mode, ExecResult r) throws Exception {
        Matcher m = Pattern.compile("keycloak-setup: (?:made (\\S+) \\([^)]*\\): they choose their own password"
            + "|activated (\\S+): they choose)").matcher(r.getStdout());
        List<String> todo = new java.util.ArrayList<>();
        while (m.find()) {
            todo.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        if (!todo.isEmpty()) {
            ExecResult on = run(mode, "KILNMARK_PEOPLE_ACTION=enable", String.join(NL, todo));
            ok(on);
            for (String person : todo) {
                assertTrue(on.getStdout().contains("enabled " + person), on.getStdout());
            }
        }
        return r;
    }

    /** people add as keycloak-setup does it alone: the person made, and left disabled. */
    private static ExecResult addPersonOnly(String mode, String email, String first, String last, String groups,
        String password) throws Exception {
        return ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " KILNMARK_PEOPLE_ACTION=add KILNMARK_PERSON_EMAIL='"
            + email + "' KILNMARK_PERSON_FIRST_NAME='" + first + "' KILNMARK_PERSON_LAST_NAME='" + last
            + "' KILNMARK_PERSON_GROUPS='" + groups + "'; printf '%s\\r\\n' '" + password + "' | bash /opt/kilnmark/setup.sh");
    }

    /**
     * people import: on a Starter Pack install the class's password, then the file, on setup's input,
     * as the control script sends them; on production the file alone (password null).
     */
    private static ExecResult importPeople(String mode, String password, String csv) throws Exception {
        return importPeople(mode, password, csv, "");
    }

    /** As {@link #importPeople(String, String, String)}, with these settings beside the usual ones. */
    private static ExecResult importPeople(String mode, String password, String csv, String extra) throws Exception {
        KC.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(
            (password == null ? "" : password + "\r\n") + csv), "/tmp/people-in");
        return enabled(mode, ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " " + extra
            + " KILNMARK_PEOPLE_ACTION=import; bash /opt/kilnmark/setup.sh < /tmp/people-in; rc=$?; rm -f /tmp/people-in; exit $rc"));
    }

    /** people activate: the email in setup's environment, the password on its input. */
    private static ExecResult activate(String mode, String email, String password) throws Exception {
        return enabled(mode, ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "")
            + " KILNMARK_PEOPLE_ACTION=activate KILNMARK_PERSON_EMAIL='" + email + "'; printf '%s\\n' '" + password
            + "' | bash /opt/kilnmark/setup.sh"));
    }

    /** An ordinary run, as if at {@code hoursLater} hours from now (KILNMARK_SETUP_NOW_MS, for tests only). */
    private static ExecResult later(String mode, int hoursLater, String activationHours) throws Exception {
        long at = System.currentTimeMillis() + hoursLater * 3_600_000L;
        return ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "")
            + " KILNMARK_SETUP_NOW_MS=" + at + (activationHours == null ? "" : " KILNMARK_PEOPLE_ACTIVATION_HOURS=" + activationHours)
            + "; bash /opt/kilnmark/setup.sh < /dev/null");
    }

    private static ExecResult listPeople(String mode) throws Exception {
        return ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " KILNMARK_PEOPLE_ACTION=list; bash /opt/kilnmark/setup.sh"
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
        // made disabled: enabled only once the control script has recorded them, and asks (people enable)
        ok(addPersonOnly(PRODUCTION, "nia@bank.example", "Nia", "Obi", "kilnmark-viewers", RITAS_FIRST));
        assertFalse(user("nia@bank.example").path("enabled").asBoolean(), "disabled until Kilnmark has recorded them");
        assertFalse(grant("nia@bank.example", RITAS_FIRST).contains("not fully set up"), "no sign-in yet");
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=enable", "nia@bank.example");
        ok(r);
        assertTrue(r.getStdout().contains("enabled nia@bank.example"), r.getStdout());
        assertTrue(grant("nia@bank.example", RITAS_FIRST).contains("not fully set up"));
        // never someone enabled already, nor anyone not waiting for their first sign-in
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=enable", "nia@bank.example" + NL + "lee@bank.example");
        ok(r);
        assertTrue(r.getStdout().contains("left nia@bank.example as they are")
            && r.getStdout().contains("left lee@bank.example as they are"), r.getStdout());
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/users/" + id("nia@bank.example"), null).statusCode());
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
        r = ContainerExec.exec(KC, "bash", "-c", env("allowed", STARTER, "add") + " KILNMARK_PEOPLE_ACTION=add"
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
        assertTrue(r.getStderr().contains("has signed in (a password of their own, or a one-time code)"), r.getStderr());
        assertFalse(user("cy@bank.example").path("enabled").asBoolean());
        // enable leaves them as they are, and anyone imported still waiting
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=enable", "cy@bank.example" + NL + "ben@bank.example");
        ok(r);
        assertTrue(r.getStdout().contains("left cy@bank.example as they are")
            && r.getStdout().contains("left ben@bank.example as they are"), r.getStdout());
        assertFalse(user("cy@bank.example").path("enabled").asBoolean());
        assertFalse(user("ben@bank.example").path("enabled").asBoolean());
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
        // a temporary password an admin set in Keycloak's console is not the people commands': it never expires
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "una@bank.example",
            "email", "una@bank.example", "emailVerified", true, "firstName", "Una", "lastName", "Byrne", "enabled", true,
            "credentials", List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", true)))).statusCode());
        assertTrue(user("una@bank.example").path("requiredActions").toString().contains("UPDATE_PASSWORD"));
        // past it: everyone still holding a temporary password the people commands set, the first
        // admin too, is disabled, and said
        r = later(PRODUCTION, 73, null);
        ok(r);
        assertTrue(user("una@bank.example").path("enabled").asBoolean(), "an admin's own temporary password: " + r.getStdout());
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

    /** One run of setup with these settings beside the usual ones, and one line on its input. */
    private static ExecResult run(String mode, String extra, String input) throws Exception {
        return ContainerExec.exec(KC, "bash", "-c", env(STARTER.equals(mode) ? "allowed" : "", mode, "") + " " + extra
            + "; printf '%s\\n' '" + input + "' | bash /opt/kilnmark/setup.sh");
    }

    private static String id(String username) throws Exception {
        return user(username).path("id").asText();
    }

    private static void partialImport(List<Map<String, Object>> users) throws Exception {
        HttpResponse<String> r = call(admin(), "POST", "/admin/realms/kilnmark/partialImport",
            Map.of("ifResourceExists", "SKIP", "users", users));
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    @Order(12)
    void peopleExpireDisablesOnlyThePeopleCommandsTemporaryPasswordsAndActivateOnlyThoseWaitingForOne() throws Exception {
        // people expire: the expiry alone, as the hourly timer runs it
        ok(activate(PRODUCTION, "ada@bank.example", RITAS_FIRST));
        long at = System.currentTimeMillis() + 73 * 3_600_000L;
        ExecResult r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=expire KILNMARK_SETUP_NOW_MS=" + at, "");
        ok(r);
        assertTrue(r.getStdout().contains("disabled ada@bank.example: their temporary password was not used")
            && r.getStdout().contains("people expire: done"), r.getStdout());
        assertFalse(r.getStdout().contains("the client kilnmark signs in"), "nothing else is set up again");
        assertFalse(user("ada@bank.example").path("enabled").asBoolean());
        // the one in use is marked the people commands' own, and the mark goes once they choose their own
        JsonNode ada = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + id("ada@bank.example")
            + "/credentials", null).body());
        assertEquals("kilnmark-temporary", ada.path(0).path("userLabel").asText(), ada.toString());

        // activate never takes anyone from another identity provider, or with a one-time code
        HttpResponse<String> idp = call(admin(), "POST", "/admin/realms/kilnmark/identity-provider/instances", Map.of(
            "alias", "corp", "providerId", "oidc", "enabled", true, "config", Map.of("authorizationUrl",
                "https:" + "/" + "/idp.bank.example/auth", "tokenUrl", "https:" + "/" + "/idp.bank.example/token",
                "clientId", "kilnmark", "clientSecret", "not-a-secret-0123", "clientAuthMethod", "client_secret_post")));
        assertEquals(201, idp.statusCode(), idp.body());
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "fed@bank.example",
            "email", "fed@bank.example", "emailVerified", true, "firstName", "Fed", "lastName", "Eral", "enabled", false))
            .statusCode());
        assertEquals(204, call(admin(), "POST", "/admin/realms/kilnmark/users/" + id("fed@bank.example")
            + "/federated-identity/corp", Map.of("identityProvider", "corp", "userId", "fed-1", "userName", "fed"))
            .statusCode());
        r = activate(PRODUCTION, "fed@bank.example", SECOND);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("comes from a directory or another identity provider"), r.getStderr());
        assertFalse(user("fed@bank.example").path("enabled").asBoolean());
        partialImport(List.of(Map.of("username", "otp@bank.example", "email", "otp@bank.example", "emailVerified", true,
            "firstName", "Ot", "lastName", "Pee", "enabled", false, "credentials", List.of(Map.of("type", "otp",
                "secretData", "{\"value\":\"JBSWY3DPEHPK3PXP\"}", "credentialData",
                "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}")))));
        r = activate(PRODUCTION, "otp@bank.example", SECOND);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("has signed in (a password of their own, or a one-time code)"), r.getStderr());
        // the marked temporary password beside a one-time code is not one waiting either
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + id("otp@bank.example") + "/reset-password",
            Map.of("type", "password", "value", VERAS_OWN, "temporary", true)).statusCode());
        assertNotEquals(0, activate(PRODUCTION, "otp@bank.example", SECOND).getExitCode());
        assertFalse(user("otp@bank.example").path("enabled").asBoolean());
        // someone waiting with no password at all, or only the marked temporary one, is activated
        ok(activate(PRODUCTION, "ada@bank.example", SECOND));
        ok(activate(PRODUCTION, "ben@bank.example", SECOND));

        // a temporary password someone intercepted, with a one-time code set up on it (Keycloak asks for
        // the code before the new password) and then left: it still expires, and the code goes with it
        partialImport(List.of(Map.of("username", "tom@bank.example", "email", "tom@bank.example", "emailVerified", true,
            "firstName", "Tom", "lastName", "Ng", "enabled", true, "requiredActions", List.of("UPDATE_PASSWORD"),
            "credentials", List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", true), Map.of("type", "otp", "secretData", "{\"value\":\"JBSWY3DPEHPK3PXP\"}",
                "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}")))));
        String tom = id("tom@bank.example");
        // marked as the people commands mark theirs
        for (JsonNode c : JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + tom + "/credentials", null).body())) {
            if ("password".equals(c.path("type").asText())) {
                HttpResponse<String> l = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/admin/realms/kilnmark/users/" + tom
                    + "/credentials/" + c.path("id").asText() + "/userLabel")).header("Authorization", "Bearer " + admin())
                    .header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofString("kilnmark-temporary")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(204, l.statusCode(), l.body());
            }
        }
        assertEquals(2, JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + tom + "/credentials", null)
            .body()).size(), "the marked password and a one-time code");
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=expire KILNMARK_SETUP_NOW_MS=" + at, "");
        ok(r);
        assertTrue(r.getStdout().contains("disabled tom@bank.example: their temporary password was not used"), r.getStdout());
        JsonNode left = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + tom + "/credentials", null).body());
        assertEquals(1, left.size(), left.toString());
        assertEquals("password", left.path(0).path("type").asText(), "the code set up on it is gone");
        ok(activate(PRODUCTION, "tom@bank.example", SECOND));   // and the real person can be given a new one
    }

    @Test
    @Order(13)
    void nobodyMadeLandsInThePacksGroupAndItsRemovalDeletesOnlyItsExamplePeople() throws Exception {
        ok(setup("allowed", STARTER, "add", FIRST));
        String pack = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/group-by-path" + GROUP, null).body())
            .path("id").asText();
        // the pack's group a default group of the realm: someone made would land in it, so is not made
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/default-groups/" + pack, null).statusCode());
        ExecResult r = addPerson(STARTER, "dee@bank.example", "Dee", "Ross", "kilnmark-viewers", RITAS_FIRST);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("was put in the group kilnmark-starter-pack"), r.getStderr());
        assertNull(user("dee@bank.example"), "removed again");
        r = importPeople(STARTER, RITAS_FIRST, "email,first_name,last_name,groups" + NL + "eve@bank.example,Eve,Ross,kilnmark-viewers" + NL);
        assertNotEquals(0, r.getExitCode());
        assertNull(user("eve@bank.example"), "removed again");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/default-groups/" + pack, null).statusCode());
        // nor in a Kilnmark group the row did not name: admins as a default group would make an import admins
        String admins = JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/group-by-path/kilnmark-admins", null).body())
            .path("id").asText();
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/default-groups/" + admins, null).statusCode());
        r = importPeople(STARTER, RITAS_FIRST, "email,first_name,last_name,groups" + NL + "fay@bank.example,Fay,Ross,kilnmark-viewers" + NL);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("was put in the group kilnmark-admins"), r.getStderr());
        assertNull(user("fay@bank.example"), "removed again");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/default-groups/" + admins, null).statusCode());
        // nor in a group of the site's own that config/.env maps to a role
        String team = makeGroup(null, "team-admins");
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/default-groups/" + team, null).statusCode());
        r = ContainerExec.exec(KC, "bash", "-c", env("allowed", STARTER, "") + " KILNMARK_OIDC_ADMIN_GROUPS='kilnmark-admins, team-admins'"
            + " KILNMARK_PEOPLE_ACTION=add KILNMARK_PERSON_EMAIL=hal@bank.example KILNMARK_PERSON_FIRST_NAME=Hal"
            + " KILNMARK_PERSON_LAST_NAME=Ross KILNMARK_PERSON_GROUPS=kilnmark-viewers; printf '%s\\n' '" + RITAS_FIRST
            + "' | bash /opt/kilnmark/setup.sh");
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("was put in the group team-admins"), r.getStderr());
        assertNull(user("hal@bank.example"), "removed again");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/default-groups/" + team, null).statusCode());
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/groups/" + team, null).statusCode());
        // nor a group of the same name elsewhere: a subgroup is another group, compared by its full path
        String branch = makeGroup(null, "branch");
        String sub = makeGroup(branch, "kilnmark-viewers");
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/default-groups/" + sub, null).statusCode());
        r = addPerson(STARTER, "kim@bank.example", "Kim", "Ross", "kilnmark-viewers", RITAS_FIRST);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("was put in the group branch/kilnmark-viewers"), r.getStderr());
        assertNull(user("kim@bank.example"), "removed again");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/default-groups/" + sub, null).statusCode());
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/groups/" + branch, null).statusCode());
        // a group config/.env maps to admin, or to seeing sensitive values, counts as those in a file
        for (String[] mapped : new String[][] {{"KILNMARK_OIDC_ADMIN_GROUPS", "admins are added one at a time"},
            {"KILNMARK_OIDC_UNMASK_GROUPS", "people who see sensitive values are added one at a time"}}) {
            r = importPeople(PRODUCTION, null, "email,first_name,last_name,groups" + NL + "ivy@bank.example,Ivy,Ross,kilnmark-stewards" + NL,
                mapped[0] + "='kilnmark-stewards, team-x'");
            assertNotEquals(0, r.getExitCode(), mapped[0]);
            assertTrue(r.getStderr().contains(mapped[1]), mapped[0] + ": " + r.getStderr());
            assertNull(user("ivy@bank.example"), "nobody made");
        }

        // someone put in the pack's group by hand, not at kilnmark.com: only taken out of it
        assertEquals(201, call(admin(), "POST", "/admin/realms/kilnmark/users", Map.of("username", "pat@bank.example",
            "email", "pat@bank.example", "emailVerified", true, "firstName", "Pat", "lastName", "Kay", "enabled", true))
            .statusCode());
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/users/" + id("pat@bank.example") + "/groups/" + pack,
            null).statusCode());
        r = setup("allowed", STARTER, "remove", "");
        ok(r);
        assertTrue(r.getStdout().contains("took pat@bank.example out of the group kilnmark-starter-pack and left them"),
            r.getStdout());
        assertNotNull(user("pat@bank.example"), "never deleted");
        assertNull(user("vera.lane@kilnmark.com"), "the example people are");
        assertEquals(404, group(GROUP));
    }

    @Test
    @Order(14)
    void expiryAndListReachEveryoneAPageAtATimeAndSetupLeavesNoTokenBehind() throws Exception {
        // last by user name, so on the last page
        ok(addPerson(PRODUCTION, "zz-late@bank.example", "Zed", "Late", "kilnmark-viewers", RITAS_FIRST));
        int all = Integer.parseInt(call(admin(), "GET", "/admin/realms/kilnmark/users/count", null).body().strip());
        assertTrue(all > 12, "several pages of four: " + all);
        // pages of four (KILNMARK_SETUP_PAGE, for the tests only): everyone is listed, the last page too
        ExecResult r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=list KILNMARK_SETUP_PAGE=4", "");
        ok(r);
        assertTrue(r.getStdout().contains("people: " + all), r.getStdout());
        assertTrue(r.getStdout().contains("person zz-late@bank.example "), "the last page too");
        long at = System.currentTimeMillis() + 73 * 3_600_000L;
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=expire KILNMARK_SETUP_PAGE=4 KILNMARK_SETUP_NOW_MS=" + at, "");
        ok(r);
        assertTrue(r.getStdout().contains("disabled zz-late@bank.example"), "expiry reaches the last page too: " + r.getStdout());
        // kcadm's token for the master realm is never left behind
        ExecResult left = ContainerExec.exec(KC, "bash", "-c", "ls -a \"$HOME/.keycloak\" 2> /dev/null; ls /tmp");
        assertFalse(left.getStdout().contains("kcadm.config"), left.getStdout());
        assertFalse(left.getStdout().contains("tmp."), "its private folder goes too: " + left.getStdout());
    }

    @Test
    @Order(15)
    void theSecretsFileIsReadAsComposeReadsItAndTheRealmKnowsItsInstall() throws Exception {
        // quoted, with an export in front, spaces around =, and comments, as config/.env may hold them
        KC.copyFileToContainer(org.testcontainers.images.builder.Transferable.of("export KILNMARK_KEYCLOAK_ADMIN_PASSWORD = \""
            + ADMIN_PASSWORD + "\" # quoted" + NL + "KILNMARK_OIDC_CLIENT_SECRET='oidc-0123456789' # single quotes" + NL, Integer.parseInt("644", 8)),
            "/run/kilnmark/env");
        String id = "6f1e2d3c-4b5a-4968-8776-655443322110";
        ExecResult r = ContainerExec.exec(KC, "bash", "-c", "export KEYCLOAK_URL=http:" + "/" + "/localhost:8080 KC_ADMIN_USER=admin"
            + " KILNMARK_URL=http:" + "/" + "/localhost:8780 KILNMARK_MODE=production KILNMARK_INSTALL_ID=" + id
            + "; bash /opt/kilnmark/setup.sh < /dev/null");
        ok(r);
        JsonNode attributes = realm().path("attributes");
        assertEquals(id, attributes.path("kilnmark_install_id").asText(), attributes.toString());
        assertEquals(PRODUCTION, attributes.path("kilnmark_mode").asText());
        assertTrue(r.getStdout().contains("stamped the realm with this install's id and mode"), r.getStdout());
        // stamped once: the same again changes nothing, and says nothing
        ExecResult same = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID=" + id, "");
        ok(same);
        assertFalse(same.getStdout().contains("stamped the realm"), same.getStdout());
        // another install's id, or the other mode, is refused, and the stamp is left as it was
        String other = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        ExecResult another = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID=" + other, "");
        assertNotEquals(0, another.getExitCode());
        assertTrue(another.getStderr().contains("belongs to another Kilnmark install"), another.getStderr());
        ExecResult mode = run(STARTER, "KILNMARK_PEOPLE_ACTION=list", "");
        assertNotEquals(0, mode.getExitCode());
        assertTrue(mode.getStderr().contains("stamped for a production install, and this one is starter-pack"), mode.getStderr());
        assertEquals(id, realm().path("attributes").path("kilnmark_install_id").asText());
        // a stamp Kilnmark did not write is refused, never taken for none: a mode not one of its own,
        // an id not of its shape (each matched in part by a pattern that skips what it cannot read)
        JsonNode stamped = realm();
        for (String[] odd : new String[][] {{"kilnmark_mode", "PRODUCTION"}, {"kilnmark_install_id", id + "x"}}) {
            com.fasterxml.jackson.databind.node.ObjectNode changed = stamped.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("attributes")).put(odd[0], odd[1]);
            assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark", changed).statusCode());
            ExecResult refused = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID=" + id, "");
            assertNotEquals(0, refused.getExitCode(), odd[0]);
            assertTrue(refused.getStderr().contains("holds a stamp Kilnmark did not write"), odd[0] + ": " + refused.getStderr());
        }
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark", stamped).statusCode());
        ok(run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=stamp KILNMARK_INSTALL_ID=" + id, ""));
    }

    /** Invisible formatting characters, built from their code points so none is in this file. */
    static final String RLO = Character.toString(0x202E);
    static final String ZWSP = Character.toString(0x200B);
    static final String LRI = Character.toString(0x2066);
    static final String PDI = Character.toString(0x2069);
    static final String SHY = Character.toString(0xAD);

    static final Map<String, Object> OTP = Map.of("type", "otp", "secretData", "{\"value\":\"JBSWY3DPEHPK3PXP\"}",
        "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}");

    /**
     * Someone holding an unused temporary password the people commands set (its label the mark), who
     * then set up a one-time code labelled {@code label}, made at {@code otpMade}: as someone who
     * intercepted the temporary password could, since Keycloak asks for the code first. Their id.
     */
    private static String waitingWithCode(String username, String label, long otpMade) throws Exception {
        Map<String, Object> otp = new java.util.HashMap<>(OTP);
        otp.put("userLabel", label);
        otp.put("createdDate", otpMade);
        partialImport(List.of(Map.of("username", username, "email", username, "emailVerified", true, "firstName", "Fo",
            "lastName", "Rge", "enabled", true, "requiredActions", List.of("UPDATE_PASSWORD"), "credentials",
            List.of(Map.of("type", "password", "value", VERAS_OWN, "temporary", true), otp))));
        String who = id(username);
        for (JsonNode c : JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + who + "/credentials", null).body())) {
            if ("password".equals(c.path("type").asText())) {
                HttpResponse<String> l = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/admin/realms/kilnmark/users/" + who
                    + "/credentials/" + c.path("id").asText() + "/userLabel")).header("Authorization", "Bearer " + admin())
                    .header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofString("kilnmark-temporary")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(204, l.statusCode(), l.body());
            }
        }
        return who;
    }

    /** The label of person {@code id}'s one-time code, as Keycloak keeps it. */
    private static String codeLabel(String id) throws Exception {
        for (JsonNode c : JSON.readTree(call(admin(), "GET", "/admin/realms/kilnmark/users/" + id + "/credentials", null).body())) {
            if ("otp".equals(c.path("type").asText())) {
                return c.path("userLabel").asText();
            }
        }
        return null;
    }

    @Test
    @Order(16)
    void aOneTimeCodesLabelNeverForgesTheTemporaryPasswordsTimeAndOddCredentialsFailClosed() throws Exception {
        ok(setup("", PRODUCTION, "", ""));   // the realm is there (this test runs on its own too)
        long now = System.currentTimeMillis();
        long tenYears = now + 10L * 365 * 24 * 3_600_000L;
        // Keycloak 26.8's own form for setting up the code keeps a line break in its label, a CRLF;
        // read as CSV, each of these is a second line, a marked password with a time of the label's
        // choosing (a future one, or one that does not read as a time)
        String crlf = waitingWithCode("forge-crlf@bank.example", "phone\r\npassword,kilnmark-temporary,9999999999999", now);
        String lf = waitingWithCode("forge-lf@bank.example", "phone\npassword,kilnmark-temporary,1", now);
        waitingWithCode("forge-later@bank.example", "phone\npassword,kilnmark-temporary", tenYears);
        assertTrue(codeLabel(crlf).contains("\r\n"), "the label keeps its CRLF, as the form's does");
        assertTrue(codeLabel(lf).contains("\n"));
        // a formatting character in a label, and an ordinary label with a comma and quotes
        waitingWithCode("forge-bidi@bank.example", "ph" + RLO + "one", now);
        waitingWithCode("plain-label@bank.example", "my phone, \"work\"", now);
        // a code labelled as the mark is not a password, and its long-ago time is not the password's
        waitingWithCode("typed-mark@bank.example", "kilnmark-temporary", 1_000L);

        // within the window: whoever holds a label that does not read cleanly is disabled at once,
        // and named (fail closed); the others wait as anyone does
        ExecResult r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=expire", "");
        ok(r);
        for (String p : List.of("forge-crlf@bank.example", "forge-lf@bank.example", "forge-later@bank.example",
            "forge-bidi@bank.example")) {
            assertTrue(r.getStdout().contains("disabled " + p + ": their temporary password was not used yet, and their "
                + "credentials could not be read cleanly"), p + ": " + r.getStdout());
            assertFalse(user(p).path("enabled").asBoolean(), p);
        }
        for (String p : List.of("plain-label@bank.example", "typed-mark@bank.example")) {
            assertTrue(user(p).path("enabled").asBoolean(), p + " is within the window: " + r.getStdout());
        }
        assertFalse(r.getStdout().contains("9999999999999") || r.getStdout().contains(RLO), "no label is printed");
        // past the window, the marked password's own time decides, whatever the code's label says
        r = run(PRODUCTION, "KILNMARK_PEOPLE_ACTION=expire KILNMARK_SETUP_NOW_MS=" + (now + 73 * 3_600_000L), "");
        ok(r);
        for (String p : List.of("plain-label@bank.example", "typed-mark@bank.example")) {
            assertTrue(r.getStdout().contains("disabled " + p + ": their temporary password was not used within 72 hours"),
                p + ": " + r.getStdout());
            assertFalse(user(p).path("enabled").asBoolean(), p);
        }
    }

    @Test
    @Order(17)
    void groupsAreComparedWholeNamesHoldNoFormattingTheMarkedGroupIsFoundAtAnyDepthAndARefusalChangesNothing()
        throws Exception {
        ok(setup("", PRODUCTION, "", ""));   // the realm is there (this test runs on its own too)
        // a space inside a group, not a ;: never read as Kilnmark's two groups, nor taken half way
        ExecResult r = addPerson(PRODUCTION, "gus@bank.example", "Gus", "Ng", "kilnmark-designers kilnmark-admins", RITAS_FIRST);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("a group with a space in it"), r.getStderr());
        assertNull(user("gus@bank.example"));
        r = importPeople(PRODUCTION, null, "email,first_name,last_name,groups" + NL + "hal2@bank.example,Hal,Ng,kilnmark-viewers"
            + NL + "gus@bank.example,Gus,Ng,kilnmark-designers kilnmark-admins" + NL);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("line 3: a group with a space in it"), r.getStderr());
        assertTrue(r.getStderr().contains("made 0, already there 0, refused 1"), r.getStderr());
        assertNull(user("hal2@bank.example"), "the whole file is checked first: nobody is made");

        // a name holding an invisible formatting character (a right-to-left override, a zero-width
        // space, an isolate, a soft hyphen), which [[:cntrl:]] does not see
        for (String name : List.of("A" + RLO + "my", "Am" + ZWSP + "y", LRI + "Amy" + PDI, "Amy" + SHY)) {
            r = addPerson(PRODUCTION, "amy@bank.example", name, "Ng", "kilnmark-viewers", RITAS_FIRST);
            assertNotEquals(0, r.getExitCode(), name);
            assertTrue(r.getStderr().contains("invisible formatting"), r.getStderr());
            assertNull(user("amy@bank.example"));
        }
        r = importPeople(PRODUCTION, null, "email,first_name,last_name,groups" + NL + "amy@bank.example,Amy,N" + RLO + "g,kilnmark-viewers" + NL);
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("line 2: a name holds a control character or an invisible formatting one"), r.getStderr());
        assertNull(user("amy@bank.example"));
        ok(addPerson(PRODUCTION, "zoe@bank.example", "Zo" + Character.toString(0xEB), "D" + Character.toString(0xED) + "az", "kilnmark-viewers", RITAS_FIRST));   // letters are fine

        // the pack's marked group as a subgroup, two levels down, on production: found, and refused;
        // and a refused run changes nothing, Keycloak's own master realm included
        String branch = makeGroup(null, "branch-office");
        String floor = makeGroup(branch, "floor-2");
        String sub = makeGroup(floor, GROUP.substring(1));
        assertEquals(204, call(admin(), "PUT", "/admin/realms/kilnmark/groups/" + sub, Map.of("name", GROUP.substring(1),
            "attributes", Map.of("kilnmark_starter_pack", List.of("true")))).statusCode());
        assertEquals(204, call(admin(), "PUT", "/admin/realms/master", Map.of("bruteForceProtected", false)).statusCode());
        r = setup("", PRODUCTION, "", "");
        assertNotEquals(0, r.getExitCode());
        assertTrue(r.getStderr().contains("holds the Starter Pack's group"), r.getStderr());
        assertFalse(JSON.readTree(call(admin(), "GET", "/admin/realms/master", null).body()).path("bruteForceProtected")
            .asBoolean(), "the master realm is as it was");
        assertEquals(204, call(admin(), "DELETE", "/admin/realms/kilnmark/groups/" + branch, null).statusCode());
        ok(setup("", PRODUCTION, "", ""));
        assertTrue(JSON.readTree(call(admin(), "GET", "/admin/realms/master", null).body()).path("bruteForceProtected")
            .asBoolean(), "an accepted run sets it");
    }
}
