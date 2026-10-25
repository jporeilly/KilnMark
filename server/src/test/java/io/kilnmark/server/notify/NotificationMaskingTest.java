package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;

/**
 * Emails and in-app notifications never show a recipient what their role could not see in the
 * UI: restricted detail only for roles that may see the column's values, never a sensitive
 * column's (emails never unmask, even for admins), and team addresses as viewers.
 */
class NotificationMaskingTest {

    static final String URN = "urn:kilnmark:lab:crm.customers";
    static final String SECRET = "value-from-the-data-4471";
    static final Map<String, Set<String>> MARKS = Map.of(URN, Set.of("ni_number"));
    static final MailSettings SETTINGS = new MailSettings(true, "smtp.example.com", null, MailSecurity.STARTTLS, null,
        false, "kilnmark@example.com", "Kilnmark", "https://kilnmark.example.com", true, null, null);

    static NotificationEvent event(String field) {
        return new NotificationEvent(NotificationKind.RULE_FAILED, Severity.LOW, URN, field + " check could not run on "
            + "crm.customers", "The rule could not run. Open the asset to see why.", NotificationEvent.assetLink(URN),
            "lab", field, "The rule could not run: invalid input syntax: \"" + SECRET + "\"", "rule:9",
            Instant.parse("2026-10-25T06:00:00Z"));
    }

    static MailMessages messages() {
        return new MailMessages(SETTINGS, java.time.ZoneId.of("UTC"), urn -> MARKS.getOrDefault(urn, Set.of()));
    }

    static MailMessages.Recipient person(AccessRole role) {
        return new MailMessages.Recipient(role.id() + "@example.com", role, false);
    }

    static final MailMessages.Recipient TEAM = new MailMessages.Recipient("team@example.com", AccessRole.ADMIN, true);

    private static boolean shows(MailMessages.Mail m) {
        return m.text().contains(SECRET) || m.html().contains(SECRET) || m.subject().contains(SECRET);
    }

    @Test
    void restrictedDetailOnAnOrdinaryColumnReachesOnlyRolesThatMaySeeData() {
        NotificationEvent e = event("email");
        assertFalse(shows(messages().single(e, person(AccessRole.VIEWER))), "viewer");
        assertTrue(shows(messages().single(e, person(AccessRole.STEWARD))), "steward");
        assertTrue(shows(messages().single(e, person(AccessRole.ADMIN))), "admin");
        assertFalse(shows(messages().single(e, TEAM)), "a team address is written for a viewer, whatever role is passed");
    }

    @Test
    void aSensitiveColumnsDetailReachesNoOneByEmail() {
        NotificationEvent e = event("ni_number");
        for (AccessRole r : AccessRole.values()) {
            assertFalse(shows(messages().single(e, person(r))), r.id());
            assertFalse(shows(messages().digest(List.of(e), person(r), Instant.parse("2026-10-25T08:00:00Z"))), r.id());
        }
        assertFalse(shows(messages().single(e, TEAM)));
    }

    @Test
    void digestsMaskEachEventTheSameWay() {
        Instant now = Instant.parse("2026-10-25T08:00:00Z");
        List<NotificationEvent> events = List.of(event("email"), event("ni_number"));
        assertFalse(shows(messages().digest(events, person(AccessRole.VIEWER), now)));
        assertFalse(shows(messages().digest(events, TEAM, now)));
        MailMessages.Mail steward = messages().digest(events, person(AccessRole.STEWARD), now);
        assertEquals(1, count(steward.text(), SECRET), "only the ordinary column's detail: " + steward.text());
    }

    @Test
    void inAppNotificationsAreWrittenForTheRoleByTheSameRule() {
        java.util.function.Function<String, Set<String>> marks = urn -> MARKS.getOrDefault(urn, Set.of());
        assertFalse(InAppFeed.write(event("email"), AccessRole.VIEWER, marks).detail().contains(SECRET));
        assertTrue(InAppFeed.write(event("email"), AccessRole.STEWARD, marks).detail().contains(SECRET));
        for (AccessRole r : AccessRole.values()) {
            NotificationStore.InAppWriter.Item item = InAppFeed.write(event("ni_number"), r, marks);
            assertFalse(item.detail().contains(SECRET), r.id());
            assertEquals("The rule could not run. Open the asset to see why.", item.detail());
            assertEquals("#/asset/urn%3Akilnmark%3Alab%3Acrm.customers", item.link());
        }
        NotificationEvent recovered = new NotificationEvent(NotificationKind.RULE_RECOVERED, Severity.HIGH, URN,
            "email not null passes again on crm.customers", null, null, "lab", null, null, "rule:9", null);
        assertEquals(Severity.INFO, InAppFeed.write(recovered, AccessRole.VIEWER, marks).severity(), "good news");
    }

    @Test
    void emailsAreSimpleAccessibleAndLinkBack() {
        MailMessages.Mail m = messages().single(event("email"), person(AccessRole.STEWARD));
        assertEquals("[Kilnmark] Low: email check could not run on crm.customers", m.subject());
        assertTrue(m.text().startsWith("Kilnmark\n"), "the product's name, as text");
        assertTrue(m.text().contains("When: Sunday 25 October 2026, 06:00 UTC"), m.text());
        assertTrue(m.text().contains("Open in Kilnmark: https://kilnmark.example.com/#/asset/urn%3Akilnmark%3Alab%3Acrm"
            + ".customers"), m.text());
        assertTrue(m.text().contains("https://kilnmark.example.com/#/settings/notifications"), "footer: their settings");
        assertTrue(m.html().contains("<html lang=\"en\">"));
        assertTrue(m.html().contains("<title>[Kilnmark] Low: email check could not run on crm.customers</title>"));
        assertTrue(m.html().contains("&quot;" + SECRET + "&quot;"), "text is escaped in HTML");
        for (String banned : List.of("<img", "<script", "url(", "background", "src=", "color:")) {
            assertFalse(m.html().toLowerCase(java.util.Locale.ROOT).contains(banned), banned);
        }
        MailMessages.Mail team = messages().single(event("email"), TEAM);
        assertTrue(team.text().contains("https://kilnmark.example.com/#/settings/email"), "teams: ask an admin");
        MailSettings noBase = new MailSettings(true, "smtp.example.com", null, MailSecurity.NONE, null, false,
            "kilnmark@example.com", null, null, true, null, null);
        MailMessages.Mail plain = new MailMessages(noBase, ZoneOffset.UTC, u -> Set.of()).single(event("email"),
            person(AccessRole.VIEWER));
        assertFalse(plain.html().contains("<a "), "no base URL, no links");
        assertTrue(plain.text().contains("under Settings, Notifications"));
    }

    @Test
    void subjectsStayOnOneLine() {
        assertEquals("[Kilnmark] a b", MailMessages.subject("a\r\nb"));
        assertTrue(MailMessages.subject("x".repeat(500)).length() <= 200);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
