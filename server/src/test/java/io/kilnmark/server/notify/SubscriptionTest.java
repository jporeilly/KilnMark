package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;

/** Defaults by role, who wants what, and checking what people send. */
class SubscriptionTest {

    @Test
    void adminsGetHighSeverityEmailAtOnceAndEveryoneElseNoneUntilTheyOptIn() {
        Subscription admin = Subscription.defaults(AccessRole.ADMIN);
        assertTrue(admin.enabled());
        assertEquals(Severity.HIGH, admin.minSeverity());
        assertEquals(Subscription.Mode.IMMEDIATE, admin.mode());
        assertNull(admin.sources(), "every source");
        assertFalse(admin.kinds().contains(NotificationKind.ISSUE_OPENED), "issues repeat failing rules and drift");
        assertTrue(admin.kinds().containsAll(EnumSet.of(NotificationKind.RULE_FAILED, NotificationKind.SCAN_FAILED,
            NotificationKind.CONNECTION_FAILED, NotificationKind.PIPELINE_FAILED)));
        for (AccessRole r : List.of(AccessRole.STEWARD, AccessRole.VIEWER)) {
            Subscription s = Subscription.defaults(r);
            assertFalse(s.enabled(), r + " gets no email until they turn it on");
            assertEquals(admin.kinds(), s.kinds(), "with the same choices ready");
        }
    }

    @Test
    void inAppIsOnForEveryKindAndEveryRoleByDefault() {
        for (AccessRole r : AccessRole.values()) {
            Subscription s = Subscription.defaults(r);
            assertEquals(EnumSet.allOf(NotificationKind.class), s.inAppKinds(), r.id());
            assertTrue(s.wantsInApp(NotificationKind.RULE_RECOVERED, "lab"), "at any severity, without an address");
        }
        assertTrue(Subscription.teamDefaults("team@example.com").inAppKinds().isEmpty(), "a team has no one to sign in");
    }

    @Test
    void anAdminWithoutAnAddressGetsNoEmailYet() {
        Subscription admin = Subscription.defaults(AccessRole.ADMIN);
        assertFalse(admin.wants(NotificationKind.SCAN_FAILED, Severity.HIGH, "lab"));
        Subscription withAddress = Subscription.from(new Subscription.Input("me@example.com", null, null, null, null,
            null, null, null, null), admin);
        assertTrue(withAddress.wants(NotificationKind.SCAN_FAILED, Severity.HIGH, "lab"));
        assertFalse(withAddress.wants(NotificationKind.RULE_FAILED, Severity.MEDIUM, "lab"), "below the least severity");
        assertFalse(withAddress.wants(NotificationKind.ISSUE_OPENED, Severity.HIGH, "lab"), "a kind not chosen");
    }

    @Test
    void sourcesLimitBothChannels() {
        Subscription s = Subscription.from(new Subscription.Input("me@example.com", true, List.of("scan_failed"), "low",
            "digest", 7, List.of("lab", "crm"), List.of("scan_failed"), null), Subscription.defaults(AccessRole.STEWARD));
        assertTrue(s.wants(NotificationKind.SCAN_FAILED, Severity.LOW, "crm"));
        assertFalse(s.wants(NotificationKind.SCAN_FAILED, Severity.HIGH, "billing"));
        assertFalse(s.wantsInApp(NotificationKind.SCAN_FAILED, "billing"));
        assertTrue(s.wantsInApp(NotificationKind.SCAN_FAILED, "lab"));
        assertFalse(s.wantsInApp(NotificationKind.DRIFT, "lab"), "in-app kinds are chosen apart from email");
        assertEquals(7, s.digestHour());
        assertEquals(Subscription.Mode.DIGEST, s.mode());
    }

    @Test
    void leftOutSourcesKeepTheirValueAndAllSourcesClearsThem() {
        Subscription limited = Subscription.from(new Subscription.Input("me@example.com", true, null, null, null, null,
            List.of("lab"), null, null), Subscription.defaults(AccessRole.STEWARD));
        assertEquals(List.of("lab"), limited.sources());
        Subscription kept = Subscription.from(new Subscription.Input(null, null, null, "low", null, null, null, null,
            null), limited);
        assertEquals(List.of("lab"), kept.sources(), "sources left out stay as they were");
        Subscription every = Subscription.from(new Subscription.Input(null, null, null, null, null, null, List.of("x"),
            null, true), limited);
        assertNull(every.sources(), "allSources wins over a list");
    }

    @Test
    void addressesAreOneStrictAddressInLowerCase() {
        assertEquals("ops@example.com", Subscription.email("Ops@Example.com"));
        for (String bad : List.of("Ops <ops@example.com>", "ops@example.com, b@example.com", "(c)ops@example.com",
            "ops@example.com;", "\"ops\"@example.com x")) {
            assertThrows(IllegalArgumentException.class, () -> Subscription.email(bad), bad);
        }
    }

    @Test
    void whatPeopleSendIsChecked() {
        Subscription base = Subscription.defaults(AccessRole.VIEWER);
        assertThrows(IllegalArgumentException.class, () -> Subscription.from(new Subscription.Input(null, true, null,
            null, null, null, null, null, null), base), "email on without an address");
        assertThrows(IllegalArgumentException.class, () -> Subscription.from(new Subscription.Input("me@example.com",
            true, List.of(), null, null, null, null, null, null), base), "email on with no kinds");
        assertThrows(IllegalArgumentException.class, () -> Subscription.from(new Subscription.Input("me@example.com",
            true, List.of("no_such_kind"), null, null, null, null, null, null), base));
        assertThrows(IllegalArgumentException.class, () -> Subscription.from(new Subscription.Input("me@example.com",
            true, null, null, null, 24, null, null, null), base));
        assertThrows(IllegalArgumentException.class, () -> Subscription.from(new Subscription.Input("me@example.com",
            true, null, null, null, null, List.of(), null, null), base), "no sources at all");
        for (String bad : List.of("not-an-address", "a@b", "two@example.com, three@example.com",
            "x@example.com\r\nBcc: y@example.com", "a..b@example.com")) {
            assertThrows(IllegalArgumentException.class, () -> Subscription.email(bad), bad);
        }
        assertEquals("data-team@example.com", Subscription.email("  Data-Team@example.com "));
        assertNull(Subscription.email(" "));
        Subscription off = Subscription.from(new Subscription.Input("", false, null, null, null, null, null, null, null), base);
        assertFalse(off.enabled());
        assertNull(off.email());
    }

    @Test
    void mailSettingsAreChecked() {
        MailSettings current = Fixtures.settings(null);
        MailSettings.Valid v = MailSettings.validate(new MailSettings.Input(true, "SMTP.Example.com", null, "tls",
            "kilnmark", "secret", "kilnmark@example.com", "Kilnmark", "https://kilnmark.example.com/", null, null, null,
            null, null, null, null), current);
        assertEquals("smtp.example.com", v.host());
        assertEquals(MailSecurity.TLS, v.security());
        assertEquals("https://kilnmark.example.com", v.baseUrl(), "without the trailing slash");
        for (String host : List.of("smtp://smtp.example.com", "smtp.example.com:587", "two words")) {
            assertThrows(IllegalArgumentException.class, () -> MailSettings.validate(new MailSettings.Input(true, host,
                null, null, null, null, "k@example.com", null, null, null, null, null, null, null, null, null), current),
                host);
        }
        for (String base : List.of("ftp://x.example.com", "https://user@x.example.com", "https://x.example.com/?q=1",
            "kilnmark.example.com")) {
            assertThrows(IllegalArgumentException.class, () -> MailSettings.baseUrl(base), base);
        }
        assertThrows(IllegalArgumentException.class, () -> MailSettings.validate(new MailSettings.Input(true,
            "smtp.example.com", 70000, null, null, null, "k@example.com", null, null, null, null, null, null, null, null,
            null), current));
        IllegalArgumentException from = assertThrows(IllegalArgumentException.class, () -> MailSettings.validate(
            new MailSettings.Input(true, "smtp.example.com", null, null, null, null, "nope", null, null, null, null, null,
                null, null, null, null), current));
        assertTrue(from.getMessage().startsWith("fromAddress:"), from.getMessage());
        MailSettings s = Fixtures.settings("https://example.com/kilnmark");
        assertEquals("https://example.com/kilnmark/#/settings/notifications", s.absolute(NotificationEvent.SETTINGS_LINK));
        assertEquals(587, s.effectivePort());
        assertNull(s.notReady());
    }
}
