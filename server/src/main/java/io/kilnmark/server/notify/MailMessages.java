package io.kilnmark.server.notify;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Sights;

/**
 * Writes the emails: one event on its own, or a day's events as a digest, each as plain text and as
 * simple HTML (no images, no tracking, no styles beyond a readable font and width).
 *
 * <p>Masking: each recipient's email is written for their role, through {@link Exposure#restricted}
 * and {@link Sights#recipient}, the same rule the UI applies. Events carry no values from the data in their title or detail;
 * their restricted detail (a database's error text, say) is shown only where the recipient's role
 * may see values of that column of that asset, and never unmasked, whoever the recipient is. A
 * team address gets what a viewer gets.
 */
public final class MailMessages {

    /** The product's name in every email, as text. */
    public static final String PRODUCT = "Kilnmark";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm zzz", Locale.UK);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    /** An email ready to send. */
    public record Mail(String subject, String text, String html) {
    }

    /**
     * Who an email is for. {@code role} decides what it may show; a team address is written for a
     * viewer, whatever role is passed.
     */
    public record Recipient(String email, AccessRole role, boolean team) {
        public AccessRole sightRole() {
            return team ? AccessRole.VIEWER : role;
        }
    }

    private final MailSettings settings;
    private final ZoneId zone;
    /** What each event touches that is marked sensitive. */
    private final Function<NotificationEvent, Exposure> exposure;

    public MailMessages(MailSettings settings, ZoneId zone, Function<NotificationEvent, Exposure> exposure) {
        this.settings = settings;
        this.zone = zone;
        this.exposure = exposure;
    }

    /** An event as this recipient may see it: its restricted detail only where their role may see it. */
    record Shown(NotificationEvent event, String restricted) {
    }

    Shown shown(NotificationEvent e, Recipient to) {
        if (e.restrictedDetail() == null) {
            return new Shown(e, null);
        }
        return new Shown(e, exposure.apply(e).restricted(e, to.sightRole()) ? e.restrictedDetail() : null);
    }

    /** The email for one event. */
    public Mail single(NotificationEvent event, Recipient to) {
        Shown s = shown(event, to);
        NotificationEvent e = s.event();
        String heading = e.severity().label() + ": " + e.title();
        String subject = subject(heading);
        String link = settings.absolute(e.link());
        String when = WHEN.format(e.occurredAt().atZone(zone));

        StringBuilder t = new StringBuilder();
        t.append(PRODUCT).append("\n\n").append(heading).append("\n\n");
        if (e.detail() != null) {
            t.append(e.detail()).append("\n\n");
        }
        if (s.restricted() != null) {
            t.append(s.restricted()).append("\n\n");
        }
        t.append("When: ").append(when).append('\n');
        t.append("What: ").append(e.kind().label()).append('\n');
        if (e.source() != null) {
            t.append("Source: ").append(e.source()).append('\n');
        }
        if (e.subjectUrn() != null) {
            t.append("Asset: ").append(e.subjectUrn()).append('\n');
        }
        t.append('\n');
        if (link != null) {
            t.append("Open in ").append(PRODUCT).append(": ").append(link).append("\n\n");
        }
        footerText(t, to);

        StringBuilder h = new StringBuilder();
        open(h, subject);
        h.append("<h1 style=\"font-size: 1.25em;\">").append(esc(heading)).append("</h1>\n");
        paragraph(h, e.detail());
        paragraph(h, s.restricted());
        h.append("<dl>\n");
        item(h, "When", when);
        item(h, "What", e.kind().label());
        if (e.source() != null) {
            item(h, "Source", e.source());
        }
        if (e.subjectUrn() != null) {
            item(h, "Asset", e.subjectUrn());
        }
        h.append("</dl>\n");
        if (link != null) {
            h.append("<p><a href=\"").append(esc(link)).append("\">Open in ").append(PRODUCT).append("</a></p>\n");
        }
        footerHtml(h, to);
        return new Mail(subject, t.toString(), h.toString());
    }

    /** The daily digest of {@code events} (at least one), sent at {@code now}. */
    public Mail digest(List<NotificationEvent> events, Recipient to, Instant now) {
        List<Digest.Group> groups = Digest.group(events);
        Severity top = Digest.highest(events);
        long highs = events.stream().filter(e -> e.severity() == Severity.HIGH).count();
        String count = events.size() + (events.size() == 1 ? " event" : " events");
        String heading = "Daily digest for " + DAY.format(now.atZone(zone)) + ": " + count
            + (highs > 0 ? ", " + highs + " high" : "");
        String subject = subject((top == null ? "" : top.label() + ": ") + heading);

        StringBuilder t = new StringBuilder();
        t.append(PRODUCT).append("\n\n").append(heading).append("\n\n");
        StringBuilder h = new StringBuilder();
        open(h, subject);
        h.append("<h1 style=\"font-size: 1.25em;\">").append(esc(heading)).append("</h1>\n");
        for (Digest.Group g : groups) {
            String title = g.kind().label() + " (" + g.events().size() + ")";
            t.append(title).append('\n').append("-".repeat(title.length())).append('\n');
            h.append("<h2 style=\"font-size: 1.1em;\">").append(esc(title)).append("</h2>\n<ul>\n");
            for (NotificationEvent raw : g.events()) {
                Shown s = shown(raw, to);
                NotificationEvent e = s.event();
                String when = WHEN.format(e.occurredAt().atZone(zone));
                String link = settings.absolute(e.link());
                t.append("* ").append(e.severity().label()).append(": ").append(e.title()).append(" (").append(when)
                    .append(")\n");
                for (String part : new String[] {e.detail(), s.restricted()}) {
                    if (part != null) {
                        t.append("  ").append(part.replace("\n", "\n  ")).append('\n');
                    }
                }
                if (link != null) {
                    t.append("  ").append(link).append('\n');
                }
                // The title is the link, so a list of links read aloud says where each one goes.
                h.append("<li><p><strong>").append(esc(e.severity().label())).append(":</strong> ");
                if (link != null) {
                    h.append("<a href=\"").append(esc(link)).append("\">").append(esc(e.title())).append("</a>");
                } else {
                    h.append(esc(e.title()));
                }
                h.append(" (").append(esc(when)).append(")</p>\n");
                paragraph(h, e.detail());
                paragraph(h, s.restricted());
                h.append("</li>\n");
            }
            t.append('\n');
            h.append("</ul>\n");
        }
        footerText(t, to);
        footerHtml(h, to);
        return new Mail(subject, t.toString(), h.toString());
    }

    /** A test email, sent by an admin from Settings › Email. */
    public Mail test(String by) {
        String heading = "Test email";
        String subject = subject(heading);
        String text = PRODUCT + "\n\n" + heading + "\n\nThis is a test from " + PRODUCT + ", sent by " + by
            + ". If you can read it, " + PRODUCT + " can send email through this mail server.\n";
        StringBuilder h = new StringBuilder();
        open(h, subject);
        h.append("<h1 style=\"font-size: 1.25em;\">").append(heading).append("</h1>\n");
        paragraph(h, "This is a test from " + PRODUCT + ", sent by " + by + ". If you can read it, " + PRODUCT
            + " can send email through this mail server.");
        h.append("</body>\n</html>\n");
        return new Mail(subject, text, h.toString());
    }

    /**
     * Asks a person to confirm their address: a link to their settings carrying the code, and the code
     * itself to type there when there is no link (no base URL) or the link does not open.
     */
    public Mail confirmation(String code) {
        String heading = "Confirm your email address";
        String subject = subject(heading);
        String link = settings.absolute(NotificationEvent.SETTINGS_LINK + "?confirm=" + code);
        String why = "Someone signed in to " + PRODUCT + " asked for notifications to be sent to this address. "
            + "Nothing is sent to it until it is confirmed. If it was not you, ignore this email.";
        String how = link == null
            ? "To confirm, open " + PRODUCT + ", go to Settings, Notifications, and enter this code: " + code
            : "To confirm, open this link while signed in to " + PRODUCT + ": " + link
                + "\n\nOr go to Settings, Notifications, and enter this code: " + code;
        String text = PRODUCT + "\n\n" + heading + "\n\n" + why + "\n\n" + how + "\n\nThe code works for 7 days.\n";
        StringBuilder h = new StringBuilder();
        open(h, subject);
        h.append("<h1 style=\"font-size: 1.25em;\">").append(heading).append("</h1>\n");
        paragraph(h, why);
        if (link != null) {
            h.append("<p><a href=\"").append(esc(link)).append("\">Confirm this address in ").append(PRODUCT)
                .append("</a> (sign in first if asked).</p>\n");
        }
        paragraph(h, "Or go to Settings, Notifications, and enter this code: " + code);
        paragraph(h, "The code works for 7 days.");
        h.append("</body>\n</html>\n");
        return new Mail(subject, text, h.toString());
    }

    /** {@code [Kilnmark] text}, on one line and at most 200 characters. */
    static String subject(String text) {
        String s = "[" + PRODUCT + "] " + text.replaceAll("[\\r\\n\\t]+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }

    private void footerText(StringBuilder t, Recipient to) {
        t.append("-- \n");
        if (to.team()) {
            String link = settings.absolute(NotificationEvent.EMAIL_SETTINGS_LINK);
            t.append("This team address gets ").append(PRODUCT).append(" notifications because an admin added it. ")
                .append("Ask an admin to change what it gets")
                .append(link == null ? " (Settings, Email in " + PRODUCT + ").\n" : ": " + link + "\n");
        } else {
            String link = settings.absolute(NotificationEvent.SETTINGS_LINK);
            t.append("You get this email because of your notification settings in ").append(PRODUCT).append(". ")
                .append(link == null ? "Change them in " + PRODUCT + " under Settings, Notifications.\n"
                    : "Change them at " + link + "\n");
        }
    }

    private void footerHtml(StringBuilder h, Recipient to) {
        h.append("<hr>\n<p style=\"font-size: 0.875em;\">");
        if (to.team()) {
            String link = settings.absolute(NotificationEvent.EMAIL_SETTINGS_LINK);
            h.append("This team address gets ").append(PRODUCT).append(" notifications because an admin added it. ");
            if (link == null) {
                h.append("Ask an admin to change what it gets (Settings, Email in ").append(PRODUCT).append(").");
            } else {
                h.append("Ask an admin to change what it gets in <a href=\"").append(esc(link))
                    .append("\">the email settings</a>.");
            }
        } else {
            String link = settings.absolute(NotificationEvent.SETTINGS_LINK);
            h.append("You get this email because of your notification settings in ").append(PRODUCT).append(". ");
            if (link == null) {
                h.append("Change them in ").append(PRODUCT).append(" under Settings, Notifications.");
            } else {
                h.append("<a href=\"").append(esc(link)).append("\">Change your notification settings</a>.");
            }
        }
        h.append("</p>\n</body>\n</html>\n");
    }

    private static void open(StringBuilder h, String subject) {
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>")
            .append(esc(subject)).append("</title>\n</head>\n")
            .append("<body style=\"font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; ")
            .append("line-height: 1.5; max-width: 40em; margin: 0 auto; padding: 16px;\">\n")
            .append("<p><strong>").append(PRODUCT).append("</strong></p>\n");
    }

    private static void paragraph(StringBuilder h, String text) {
        if (text != null && !text.isBlank()) {
            h.append("<p>").append(esc(text).replace("\n", "<br>\n")).append("</p>\n");
        }
    }

    private static void item(StringBuilder h, String term, String value) {
        h.append("<dt><strong>").append(esc(term)).append("</strong></dt><dd>").append(esc(value)).append("</dd>\n");
    }

    /** Text made safe for HTML content and attribute values. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
