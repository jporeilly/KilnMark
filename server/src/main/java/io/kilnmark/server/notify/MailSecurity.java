package io.kilnmark.server.notify;

import java.util.Locale;

/** How the connection to the mail server is protected. */
public enum MailSecurity {
    /** Plain SMTP, usually port 25: only for a relay on a trusted network. */
    NONE(25),
    /** Plain at first, then upgraded with STARTTLS before signing in; refused if the server cannot. Usually port 587. */
    STARTTLS(587),
    /** TLS from the first byte ("implicit TLS", SMTPS), usually port 465. */
    TLS(465);

    private final int defaultPort;

    MailSecurity(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MailSecurity parse(String text) {
        if (text != null) {
            for (MailSecurity s : values()) {
                if (s.id().equalsIgnoreCase(text.trim())) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("security: use none, starttls or tls");
    }
}
