package io.kilnmark.server.notify;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.mail.internet.MimeMessage;

/**
 * Notifications end to end on PostgreSQL (dev services), with GreenMail as the mail server in the
 * test's own process: admin-only settings with a write-only password, the test email, people's
 * choices, a failed connection test told in the app (written for each role) and by email (to a
 * person and a team address), told once until it clears, each person's feed kept to themselves,
 * and the send log.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationFlowTest {

    static final String PASSWORD = "smtp-pw-7731";
    static final String SOURCE = "notify-bad";
    static GreenMail smtp;
    static String steward;
    static String viewer;
    static String quiet;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @BeforeAll
    static void startMailServer() {
        smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.withConfiguration(GreenMailConfiguration.aConfig().withUser("kilnmark", PASSWORD));
        smtp.start();
    }

    @AfterAll
    static void stopMailServer() {
        // Leave email paused, so later tests queue nothing for a server that has gone.
        admin().contentType(ContentType.JSON).body(Map.of("enabled", false)).put("/api/v1/mail/settings");
        smtp.stop();
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role)).post("/api/v1/tokens")
            .then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static RequestSpecification as(String token) {
        return Tokens.as(token);
    }

    private static Map<String, Object> settings(String password) {
        Map<String, Object> s = new HashMap<>(Map.of("enabled", true, "host", "127.0.0.1", "port",
            smtp.getSmtp().getPort(), "security", "none", "username", "kilnmark", "fromAddress", "kilnmark@example.com",
            "fromName", "Kilnmark", "baseUrl", "http://kilnmark.example.com"));
        if (password != null) {
            s.put("password", password);
        }
        return s;
    }

    private static void await(String what, BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(250);
        }
    }

    private static List<MimeMessage> to(String address) {
        return Arrays.stream(smtp.getReceivedMessages()).filter(m -> {
            try {
                return Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().equals(address));
            } catch (jakarta.mail.MessagingException e) {
                return false;
            }
        }).toList();
    }

    /** The caller's feed items about the test source. */
    private static List<Map<String, Object>> mine(String token) {
        JsonPath j = as(token).get("/api/v1/notifications?limit=100").then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> items = j.getList("items");
        return items.stream().filter(i -> String.valueOf(i.get("title")).contains(SOURCE)).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> channels(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("channels");
    }

    @Test
    @Order(1)
    void mailSettingsAreForAdminsAndThePasswordIsWriteOnly() {
        steward = token("notify-steward", "steward");
        viewer = token("notify-viewer", "viewer");
        quiet = token("notify-quiet", "steward");
        as(viewer).get("/api/v1/mail/settings").then().statusCode(403);
        as(steward).contentType(ContentType.JSON).body(settings(PASSWORD)).put("/api/v1/mail/settings").then()
            .statusCode(403);
        as(steward).get("/api/v1/mail/log").then().statusCode(403);
        as(steward).get("/api/v1/mail/teams").then().statusCode(403);

        String saved = admin().contentType(ContentType.JSON).body(settings(PASSWORD)).put("/api/v1/mail/settings").then()
            .statusCode(200).body("hasPassword", equalTo(true)).body("ready", equalTo(true))
            .body("saved", equalTo(true)).extract().asString();
        assertFalse(saved.contains(PASSWORD), saved);
        // Leaving the password out keeps it.
        String again = admin().contentType(ContentType.JSON).body(settings(null)).put("/api/v1/mail/settings").then()
            .statusCode(200).body("hasPassword", equalTo(true)).extract().asString();
        assertFalse(again.contains(PASSWORD));
        assertFalse(admin().get("/api/v1/mail/settings").then().statusCode(200).extract().asString().contains(PASSWORD));
        admin().contentType(ContentType.JSON).body(Map.of("host", "smtp://x", "fromAddress", "k@example.com"))
            .put("/api/v1/mail/settings").then().statusCode(400).body("error", containsString("host:"));
    }

    @Test
    @Order(2)
    void theTestEmailSaysWhetherItWent() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("to", "admin@example.com")).post("/api/v1/mail/test").then()
            .statusCode(200).body("ok", equalTo(true)).body("message", containsString("Sent to admin@example.com"));
        await("the test email", () -> !to("admin@example.com").isEmpty());
        assertEquals("[Kilnmark] Test email", to("admin@example.com").get(0).getSubject());
        admin().contentType(ContentType.JSON).body(Map.of("to", "not an address")).post("/api/v1/mail/test").then()
            .statusCode(400);

        // A wrong password comes back in plain words, never repeated.
        admin().contentType(ContentType.JSON).body(settings("wrong-" + PASSWORD)).put("/api/v1/mail/settings").then()
            .statusCode(200);
        String failed = admin().contentType(ContentType.JSON).body(Map.of("to", "admin@example.com"))
            .post("/api/v1/mail/test").then().statusCode(200).body("ok", equalTo(false))
            .body("message", containsString("refused the user name or password")).extract().asString();
        assertFalse(failed.contains(PASSWORD));
        admin().contentType(ContentType.JSON).body(settings(PASSWORD)).put("/api/v1/mail/settings").then().statusCode(200);
    }

    @Test
    @Order(3)
    void choicesStartFromTheRolesDefaults() {
        as(steward).get("/api/v1/me/notifications").then().statusCode(200).body("saved", equalTo(false))
            .body("enabled", equalTo(false)).body("available", equalTo(true)).body("mailReady", equalTo(true))
            .body("inAppKinds.size()", equalTo(8)).body("kindChoices.size()", equalTo(8))
            .body("timeZone", not(equalTo(null)));
        admin().get("/api/v1/me/notifications").then().statusCode(200).body("enabled", equalTo(true))
            .body("minSeverity", equalTo("high"));
        as(steward).contentType(ContentType.JSON).body(Map.of("email", "steward@example.com", "enabled", true, "kinds",
            List.of("connection_failed", "scan_failed"), "minSeverity", "low", "mode", "immediate", "digestHour", 8))
            .put("/api/v1/me/notifications").then().statusCode(200).body("saved", equalTo(true))
            .body("email", equalTo("steward@example.com")).body("inAppKinds.size()", equalTo(8));
        as(quiet).contentType(ContentType.JSON).body(Map.of("enabled", false, "inAppKinds", List.of()))
            .put("/api/v1/me/notifications").then().statusCode(200).body("inAppKinds.size()", equalTo(0));
        as(viewer).contentType(ContentType.JSON).body(Map.of("enabled", true, "kinds", List.of("scan_failed")))
            .put("/api/v1/me/notifications").then().statusCode(400).body("error", containsString("email:"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "Data team", "email", "team@example.com", "kinds",
            List.of("connection_failed"), "minSeverity", "low", "mode", "immediate")).post("/api/v1/mail/teams").then()
            .statusCode(201).body("name", equalTo("Data team"));
    }

    @Test
    @Order(4)
    void aFailedConnectionTestIsToldOnceInTheAppAndByEmailWrittenForEachRole() throws Exception {
        Map<String, Object> source = Map.of("name", SOURCE, "kind", "postgresql", "settings",
            Map.of("url", url, "username", user), "password", "wrong-password-0042");
        admin().contentType(ContentType.JSON).body(source).post("/api/v1/sources").then().statusCode(201);
        as(steward).post("/api/v1/sources/" + SOURCE + "/test").then().statusCode(200).body("ok", equalTo(false));

        List<Map<String, Object>> stewards = mine(steward);
        assertEquals(1, stewards.size(), stewards.toString());
        Map<String, Object> item = stewards.get(0);
        assertEquals("connection_failed", item.get("kind"));
        assertEquals("medium", item.get("severity"));
        assertEquals("#/source/" + SOURCE, item.get("link"));
        assertEquals(false, item.get("read"));
        assertTrue(String.valueOf(item.get("detail")).contains("It said:"), "a steward may see the reason: " + item);
        assertFalse(String.valueOf(item.get("detail")).contains("wrong-password-0042"));
        List<Map<String, Object>> viewers = mine(viewer);
        assertEquals(1, viewers.size());
        assertFalse(String.valueOf(viewers.get(0).get("detail")).contains("It said:"), "not for a viewer");
        assertEquals(List.of(), mine(quiet), "in-app turned off");

        await("the steward's and the team's emails", () -> !to("steward@example.com").isEmpty()
            && !to("team@example.com").isEmpty());
        MimeMessage personal = to("steward@example.com").get(0);
        assertEquals("[Kilnmark] Medium: Connection test of " + SOURCE + " failed", personal.getSubject());
        String body = GreenMailUtil.getBody(personal);
        assertTrue(body.contains("It said:"), body);
        assertTrue(body.contains("http://kilnmark.example.com/#/settings/notifications"), body);
        assertFalse(body.contains("<img"), body);
        String team = GreenMailUtil.getBody(to("team@example.com").get(0));
        assertFalse(team.contains("It said:"), "a team address is written for a viewer");
        assertTrue(team.contains("#/settings/email"), team);

        // Once per failing streak: testing again tells no one.
        as(steward).post("/api/v1/sources/" + SOURCE + "/test").then().statusCode(200).body("ok", equalTo(false));
        Thread.sleep(3000);
        assertEquals(1, mine(steward).size());
        assertEquals(1, to("steward@example.com").size());

        await("the feed to show the email as sent", () -> {
            List<Map<String, Object>> channels = channels(mine(steward).get(0));
            return channels.size() == 2 && "sent".equals(channels.get(1).get("status"));
        });
        Map<String, Object> adminItem = mine(Tokens.ADMIN).get(0);
        assertEquals(List.of(Map.of("name", "Data team", "status", "sent")), adminItem.get("teamEmails"));
        assertFalse(adminItem.toString().contains("@"), "never an address in the feed: " + adminItem);
        assertFalse(mine(steward).get(0).containsKey("teamEmails"), "team emails for admins only");
    }

    @Test
    @Order(5)
    void everyoneReadsAndMarksOnlyTheirOwn() {
        String stewardItem = String.valueOf(mine(steward).get(0).get("id"));
        String viewerItem = String.valueOf(mine(viewer).get(0).get("id"));
        assertFalse(stewardItem.equals(viewerItem));
        List<String> viewerIds = as(viewer).get("/api/v1/notifications?limit=100").then().statusCode(200).extract()
            .jsonPath().getList("items.id");
        assertFalse(viewerIds.contains(stewardItem), "a viewer never sees the steward's notifications");

        // Marking someone else's notification changes nothing.
        as(viewer).contentType(ContentType.JSON).body(Map.of("ids", List.of(stewardItem)))
            .post("/api/v1/notifications/read").then().statusCode(204);
        assertEquals(false, mine(steward).get(0).get("read"));

        long before = as(steward).get("/api/v1/notifications/unread-count").then().statusCode(200).extract().jsonPath()
            .getLong("unread");
        assertTrue(before >= 1);
        as(steward).get("/api/v1/notifications?unread=true&limit=100").then().statusCode(200)
            .body("items.id", org.hamcrest.Matchers.hasItem(stewardItem));
        as(steward).contentType(ContentType.JSON).body(Map.of("ids", List.of(stewardItem)))
            .post("/api/v1/notifications/read").then().statusCode(204);
        assertEquals(true, mine(steward).get(0).get("read"));
        as(steward).get("/api/v1/notifications/unread-count").then().body("unread", equalTo((int) before - 1));
        as(steward).get("/api/v1/notifications?unread=true&limit=100").then().statusCode(200)
            .body("items.id", not(org.hamcrest.Matchers.hasItem(stewardItem)));
        assertEquals(false, mine(viewer).get(0).get("read"), "the viewer's own copy is still unread");

        as(viewer).contentType(ContentType.JSON).body(Map.of("all", true)).post("/api/v1/notifications/read").then()
            .statusCode(204);
        as(viewer).get("/api/v1/notifications/unread-count").then().body("unread", equalTo(0));
        assertEquals(false, mine(Tokens.ADMIN).get(0).get("read"), "marking all is per person");
        as(viewer).contentType(ContentType.JSON).body(Map.of()).post("/api/v1/notifications/read").then().statusCode(400);
        as(viewer).contentType(ContentType.JSON).body(Map.of("ids", List.of("abc"))).post("/api/v1/notifications/read")
            .then().statusCode(400);
        Tokens.as("km_not-a-real-token-000000000000000000000000000000000000000").get("/api/v1/notifications").then()
            .statusCode(401);
    }

    @Test
    @Order(6)
    void theSendLogShowsEveryAttemptToAdmins() {
        JsonPath log = admin().get("/api/v1/mail/log?status=sent&limit=500").then().statusCode(200).extract().jsonPath();
        List<String> recipients = log.getList("recipient");
        assertTrue(recipients.contains("steward@example.com"), recipients.toString());
        assertTrue(recipients.contains("team@example.com"), recipients.toString());
        assertTrue(log.getList("kind").contains("connection_failed"));
        admin().get("/api/v1/mail/log?status=nope").then().statusCode(400);
        as(viewer).get("/api/v1/mail/log").then().statusCode(403);
    }
}
