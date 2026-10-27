package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;

/**
 * Emails and in-app notifications never show a recipient what their role could not see in the
 * UI: restricted detail only for roles that may see the column's values, never a sensitive
 * column's (emails never unmask, even for admins); about a whole source or asset with marks, only
 * admins; and team addresses as viewers.
 */
class NotificationMaskingTest {

    static final String URN = "urn:kilnmark:lab:crm.customers";
    static final String SECRET = "value-from-the-data-4471";
    static final Exposure MARKED = new Exposure(Set.of("ni_number"), true);
    static final MailSettings SETTINGS = Fixtures.settings("https://kilnmark.example.com");

    static NotificationEvent event(String field) {
        return new NotificationEvent(NotificationKind.RULE_FAILED, Severity.LOW, URN, field + " check could not run on "
            + "crm.customers", "The rule could not run. Open the asset to see why.", NotificationEvent.assetLink(URN),
            "lab", field, "The rule could not run: invalid input syntax: \"" + SECRET + "\"", "rule:9",
            Instant.parse("2026-10-25T06:00:00Z"));
    }

    /** A failed scan as the publisher writes it: the run's own words only as restricted detail. */
    static NotificationEvent scanFailed() {
        return new NotificationEvent(NotificationKind.SCAN_FAILED, Severity.HIGH, null, "Scan of lab failed",
            "The scan failed. Open the source to see why.", NotificationEvent.sourceLink("lab"), "lab", null,
            "It said: crm.customers: ERROR: invalid input syntax for type integer: \"" + SECRET + "\"", "scan:lab",
            Instant.parse("2026-10-25T06:00:00Z"));
    }

    static MailMessages messages(Exposure x) {
        return new MailMessages(SETTINGS, ZoneId.of("UTC"), e -> x);
    }

    static MailMessages messages() {
        return messages(MARKED);
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
    void aFailedScansOwnWordsReachStewardsOnlyAndOnlyAdminsWhenTheSourceHasMarks() {
        NotificationEvent e = scanFailed();
        Exposure clean = Exposure.NONE;
        assertFalse(shows(messages(clean).single(e, person(AccessRole.VIEWER))), "a viewer never sees the run's words");
        assertFalse(shows(messages(clean).single(e, TEAM)), "nor does a team address");
        assertTrue(shows(messages(clean).single(e, person(AccessRole.STEWARD))), "a steward does, on an unmarked source");
        assertTrue(messages(clean).single(e, person(AccessRole.VIEWER)).text()
            .contains("The scan failed. Open the source to see why."), "everyone gets the plain words");
        Exposure marked = new Exposure(Set.of(), true);
        assertFalse(shows(messages(marked).single(e, person(AccessRole.STEWARD))), "a source with marks: not stewards");
        assertTrue(shows(messages(marked).single(e, person(AccessRole.ADMIN))), "but admins");
        assertFalse(InAppFeed.write(e, AccessRole.STEWARD, marked).detail().contains(SECRET), "in the app alike");
        assertTrue(InAppFeed.write(e, AccessRole.ADMIN, marked).detail().contains(SECRET));
        assertFalse(InAppFeed.write(e, AccessRole.VIEWER, clean).detail().contains(SECRET));
    }

    @Test
    void theScanPublisherKeepsTheRunsWordsOutOfTheDetail() {
        java.util.List<NotificationEvent> fired = new java.util.ArrayList<>();
        NotificationPublisher p = new NotificationPublisher() {
            @Override
            public void publish(NotificationEvent e) {
                fired.add(e);
            }

            @Override
            public void clear(String key) {
                // nothing to clear in this test
            }
        };
        p.scanFinished("lab", 7, "failed", "crm.customers: \"" + SECRET + "\"");
        assertEquals(1, fired.size());
        assertFalse(fired.get(0).detail().contains(SECRET), fired.get(0).detail());
        assertTrue(fired.get(0).restrictedDetail().contains(SECRET));
        assertEquals(null, fired.get(0).field(), "about the whole source");
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
        assertFalse(InAppFeed.write(event("email"), AccessRole.VIEWER, MARKED).detail().contains(SECRET));
        assertTrue(InAppFeed.write(event("email"), AccessRole.STEWARD, MARKED).detail().contains(SECRET));
        for (AccessRole r : AccessRole.values()) {
            NotificationStore.InAppWriter.Item item = InAppFeed.write(event("ni_number"), r, MARKED);
            assertFalse(item.detail().contains(SECRET), r.id());
            assertEquals("The rule could not run. Open the asset to see why.", item.detail());
            assertEquals("#/asset/urn%3Akilnmark%3Alab%3Acrm.customers", item.link());
        }
        NotificationEvent recovered = new NotificationEvent(NotificationKind.RULE_RECOVERED, Severity.HIGH, URN,
            "email not null passes again on crm.customers", null, null, "lab", null, null, "rule:9", null);
        assertEquals(Severity.INFO, InAppFeed.write(recovered, AccessRole.VIEWER, MARKED).severity(), "good news");
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
        MailMessages.Mail plain = new MailMessages(Fixtures.settings(null), ZoneId.of("UTC"), e -> Exposure.NONE)
            .single(event("email"), person(AccessRole.VIEWER));
        assertFalse(plain.html().contains("<a "), "no base URL, no links");
        assertTrue(plain.text().contains("under Settings, Notifications"));
    }

    @Test
    void theConfirmationEmailCarriesALinkAndTheCode() {
        MailMessages.Mail m = messages().confirmation("abc123");
        assertEquals("[Kilnmark] Confirm your email address", m.subject());
        assertTrue(m.text().contains("https://kilnmark.example.com/#/settings/notifications?confirm=abc123"), m.text());
        assertTrue(m.html().contains("enter this code: abc123"), m.html());
        MailMessages.Mail noLink = new MailMessages(Fixtures.settings(null), ZoneId.of("UTC"), e -> Exposure.NONE)
            .confirmation("abc123");
        assertFalse(noLink.text().contains("http"), "without a base URL, only the code");
        assertTrue(noLink.text().contains("abc123"));
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
