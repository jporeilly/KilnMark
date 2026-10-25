package io.kilnmark.server.notify;

import java.util.List;
import java.util.Set;

/** Mail settings for tests: defaults everywhere but what a test names. */
final class Fixtures {

    private Fixtures() {
    }

    static MailSettings settings(String host, MailSecurity security, String username, String baseUrl) {
        return new MailSettings(true, host, null, security, username, false, "kilnmark@example.com", "Kilnmark", baseUrl,
            false, List.of(), true, 5, 20, 24, "digest", Set.of(), true, null, null);
    }

    static MailSettings settings(String baseUrl) {
        return settings("smtp.example.com", MailSecurity.STARTTLS, null, baseUrl);
    }

    /** {@code s} with some settings locked by the configuration. */
    static MailSettings locked(MailSettings s, String... ids) {
        return new MailSettings(s.enabled(), s.host(), s.port(), s.security(), s.username(), s.hasPassword(),
            s.fromAddress(), s.fromName(), s.baseUrl(), s.allowPlainAuth(), s.allowedDomains(), s.requireConfirmation(),
            s.maxAttempts(), s.rateLimitPerHour(), s.undeliveredHours(), s.undeliveredAction(), Set.of(ids), s.saved(),
            null, null);
    }

    /** An input that changes nothing but what the test sets afterwards. */
    static MailSettings.Input input() {
        return new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    }
}
