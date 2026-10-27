package io.kilnmark.server.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Something that happened which people may want an email about, fired as a CDI event through
 * {@link NotificationPublisher} from the code where it happens.
 *
 * <p>What may be written where (masking): {@code title} and {@code detail} hold names, words,
 * counts and shares only, what a viewer may see in the UI. Never put values from the data in
 * them. {@code restrictedDetail} is shown only to a recipient whose role may see values of
 * {@code field} on {@code subjectUrn} ({@code field} null: values of the asset at all), decided per
 * recipient by {@code Sights.recipient}; it is for text such as a database's own error message,
 * which may quote the data.
 *
 * @param kind             what happened
 * @param severity         how much it matters
 * @param subjectUrn       the asset concerned, or null (a source, a pipeline)
 * @param title            one line, used in the subject: {@code email not null fails on crm.customers}
 * @param detail           what happened, for every recipient; may be null
 * @param link             where to look in Kilnmark, as an in-app address ({@code #/asset/...}); may be null
 * @param source           the source concerned, by name, for subscriptions limited to some sources; may be null
 * @param field            the column {@code restrictedDetail} is about, or null
 * @param restrictedDetail more detail for roles that may see the data; may be null
 * @param dedupeKey        what makes two events "the same problem", such as {@code rule:42}
 * @param occurredAt       when it happened
 */
public record NotificationEvent(NotificationKind kind, Severity severity, String subjectUrn, String title, String detail,
                                String link, String source, String field, String restrictedDetail, String dedupeKey,
                                Instant occurredAt) {

    public NotificationEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        title = clip(Objects.requireNonNull(title, "title"), 300);
        detail = detail == null ? null : clip(detail, 4000);
        restrictedDetail = restrictedDetail == null ? null : clip(restrictedDetail, 4000);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    /** A short event: no source filter, field or restricted detail. */
    public static NotificationEvent of(NotificationKind kind, Severity severity, String subjectUrn, String title,
                                       String detail, String link, String dedupeKey) {
        return new NotificationEvent(kind, severity, subjectUrn, title, detail, link, null, null, null, dedupeKey, null);
    }

    /** The in-app address of an asset's page. */
    public static String assetLink(String urn) {
        return "#/asset/" + encode(urn);
    }

    /** The in-app address of a source's page. */
    public static String sourceLink(String name) {
        return "#/source/" + encode(name);
    }

    /** The in-app address of the issue list. */
    public static String issuesLink() {
        return "#/quality/issues";
    }

    /** The in-app address of a person's own notification settings. */
    public static final String SETTINGS_LINK = "#/settings/notifications";
    /** The in-app address of the admins' email settings, where team addresses are edited. */
    public static final String EMAIL_SETTINGS_LINK = "#/settings/email";

    /** As the web UI encodes a path part (encodeURIComponent). */
    static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!")
            .replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }

    /** Text cut to {@code max} characters, with an ellipsis, and without control characters but new lines. */
    static String clip(String text, int max) {
        StringBuilder b = new StringBuilder(Math.min(text.length(), max));
        text.codePoints().forEach(cp -> b.appendCodePoint(cp == '\n' || !Character.isISOControl(cp) ? cp : ' '));
        String s = b.toString().strip();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
