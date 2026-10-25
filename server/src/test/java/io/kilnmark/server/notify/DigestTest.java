package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;

/** When the daily digest is due, and how its events are grouped. */
class DigestTest {

    private static NotificationEvent event(NotificationKind kind, Severity severity, String title, String at) {
        return new NotificationEvent(kind, severity, null, title, null, null, "lab", null, null, title,
            Instant.parse(at));
    }

    @Test
    void isDueAtTheNextChosenHourInTheServersTimeZone() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), Digest.nextAt(Instant.parse("2026-10-25T07:59:00Z"), 8, utc));
        assertEquals(Instant.parse("2026-10-26T08:00:00Z"), Digest.nextAt(Instant.parse("2026-10-25T08:00:00Z"), 8, utc),
            "on the hour itself: tomorrow's");
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), Digest.nextAt(Instant.parse("2026-10-25T23:30:00Z"), 0, utc));
        ZoneId london = ZoneId.of("Europe/London");
        // Clocks go back on 25 October 2026: 08:00 is GMT that day, BST the day before.
        assertEquals(Instant.parse("2026-10-24T07:00:00Z"), Digest.nextAt(Instant.parse("2026-10-24T06:00:00Z"), 8, london));
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), Digest.nextAt(Instant.parse("2026-10-24T07:00:00Z"), 8, london));
    }

    @Test
    void groupsByKindMostSevereThenNewestFirst() {
        List<NotificationEvent> events = List.of(
            event(NotificationKind.DRIFT, Severity.LOW, "drift low", "2026-10-25T01:00:00Z"),
            event(NotificationKind.RULE_FAILED, Severity.MEDIUM, "rule medium", "2026-10-25T05:00:00Z"),
            event(NotificationKind.RULE_FAILED, Severity.HIGH, "rule high old", "2026-10-25T02:00:00Z"),
            event(NotificationKind.RULE_FAILED, Severity.HIGH, "rule high new", "2026-10-25T03:00:00Z"),
            event(NotificationKind.RULE_RECOVERED, Severity.HIGH, "recovered", "2026-10-25T06:00:00Z"),
            event(NotificationKind.SCAN_FAILED, Severity.HIGH, "scan", "2026-10-25T04:00:00Z"));
        List<Digest.Group> groups = Digest.group(events);
        assertEquals(List.of(NotificationKind.RULE_FAILED, NotificationKind.RULE_RECOVERED, NotificationKind.SCAN_FAILED,
            NotificationKind.DRIFT), groups.stream().map(Digest.Group::kind).toList(), "in the order of the kinds");
        assertEquals(List.of("rule high new", "rule high old", "rule medium"),
            groups.get(0).events().stream().map(NotificationEvent::title).toList());
        assertEquals(Severity.HIGH, Digest.highest(events));
        assertNull(Digest.highest(List.of()));
    }

    @Test
    void theDigestEmailListsEveryGroupWithCounts() {
        MailSettings settings = new MailSettings(true, "smtp.example.com", null, MailSecurity.STARTTLS, null, false,
            "kilnmark@example.com", "Kilnmark", "https://kilnmark.example.com", true, null, null);
        MailMessages messages = new MailMessages(settings, ZoneOffset.UTC, urn -> Set.of());
        List<NotificationEvent> events = List.of(
            event(NotificationKind.RULE_FAILED, Severity.HIGH, "email not null fails on crm.customers",
                "2026-10-25T03:00:00Z"),
            event(NotificationKind.DRIFT, Severity.MEDIUM, "2 changes since the last scan of crm.customers",
                "2026-10-25T04:00:00Z"));
        MailMessages.Mail m = messages.digest(events, new MailMessages.Recipient("a@example.com", AccessRole.STEWARD, false),
            Instant.parse("2026-10-25T08:00:00Z"));
        assertEquals("[Kilnmark] High: Daily digest for 25 October 2026: 2 events, 1 high", m.subject());
        assertTrue(m.text().contains("Quality rule failing (1)"), m.text());
        assertTrue(m.text().contains("Drift detected (1)"), m.text());
        assertTrue(m.html().contains("<h2 style=\"font-size: 1.1em;\">Quality rule failing (1)</h2>"), m.html());
    }
}
