package io.kilnmark.server.notify;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The outgoing mail server, as an admin sets it under Settings › Email (or, until then, as the
 * server's {@code kilnmark.mail.*} configuration gives it). The password is not here: it is kept
 * sealed and only {@link MailService} opens it, to send.
 *
 * @param enabled     whether emails go at all (an admin can pause them)
 * @param host        the SMTP server
 * @param port        its port; null for the usual one for {@code security}
 * @param security    none, STARTTLS or TLS
 * @param username    who to sign in as; null to send without signing in
 * @param hasPassword whether a password is stored (or configured)
 * @param fromAddress the sender's address
 * @param fromName    the sender's name, such as {@code Kilnmark}; null for none
 * @param baseUrl     how people reach Kilnmark, for links in emails, such as {@code https://kilnmark.example.com}; null for no links
 * @param saved       whether an admin has saved these (false: from the server's configuration)
 */
public record MailSettings(boolean enabled, String host, Integer port, MailSecurity security, String username,
                           boolean hasPassword, String fromAddress, String fromName, String baseUrl, boolean saved,
                           String updatedBy, Instant updatedAt) {

    private static final Pattern HOST = Pattern.compile(
        "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*|\\[[0-9a-f:.]{2,45}]");

    /** Whether emails can go: on, with a server and a sender. */
    public boolean ready() {
        return enabled && host != null && fromAddress != null;
    }

    /** What is missing before emails can go, in plain words; null when ready. */
    public String notReady() {
        if (host == null || fromAddress == null) {
            return "Email is not set up yet: an admin enters the mail server and the sender's address under Settings, Email.";
        }
        return enabled ? null : "Email is paused: an admin turned off sending under Settings, Email.";
    }

    public int effectivePort() {
        return port != null ? port : security.defaultPort();
    }

    /** {@code link} (an in-app address, {@code #/...}) as a full URL, or null without a base URL. */
    public String absolute(String link) {
        if (baseUrl == null || link == null) {
            return null;
        }
        return baseUrl + "/" + (link.startsWith("/") ? link.substring(1) : link);
    }

    /** Settings as the API takes them. Leave {@code password} out (null) to keep the stored one; send "" to remove it. */
    public record Input(Boolean enabled, String host, Integer port, String security, String username, String password,
                        String fromAddress, String fromName, String baseUrl) {
    }

    /** Checked settings with the password as given (null: keep, "": remove). */
    public record Valid(boolean enabled, String host, Integer port, MailSecurity security, String username,
                        String password, String fromAddress, String fromName, String baseUrl) {
    }

    /** Checks settings from the API, saying in plain words what is wrong and where. */
    public static Valid validate(Input in) {
        if (in == null) {
            throw new IllegalArgumentException("Send the mail settings as JSON: host, port, security, username, password, "
                + "fromAddress, fromName and baseUrl");
        }
        String host = host(in.host());
        Integer port = in.port();
        if (port != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("port: choose a port from 1 to 65535, usually 587 (STARTTLS), 465 (TLS) or 25");
        }
        MailSecurity security = in.security() == null || in.security().isBlank() ? MailSecurity.STARTTLS
            : MailSecurity.parse(in.security());
        String username = blank(in.username());
        if (username != null && (username.length() > 320 || username.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("username: at most 320 characters, without line breaks");
        }
        String password = in.password();
        if (password != null && (password.length() > 1000 || password.chars().anyMatch(c -> c == '\r' || c == '\n'))) {
            throw new IllegalArgumentException("password: at most 1,000 characters, without line breaks");
        }
        String from;
        try {
            from = Subscription.email(in.fromAddress());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage().replaceFirst("^email:", "fromAddress:"), e);
        }
        String name = blank(in.fromName());
        if (name != null && (name.length() > 100 || name.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("fromName: at most 100 characters, without line breaks");
        }
        boolean enabled = in.enabled() == null || in.enabled();
        if (enabled && host == null) {
            throw new IllegalArgumentException("host: enter the mail server's name, such as smtp.example.com, or turn "
                + "sending off");
        }
        if (enabled && from == null) {
            throw new IllegalArgumentException("fromAddress: enter the address emails come from, such as "
                + "kilnmark@example.com");
        }
        return new Valid(enabled, host, port, security, username, password, from, name, baseUrl(in.baseUrl()));
    }

    private static String host(String text) {
        String h = blank(text);
        if (h == null) {
            return null;
        }
        if (h.length() > 253 || !HOST.matcher(h).matches()) {
            throw new IllegalArgumentException("host: enter only the server's name or address, such as smtp.example.com, "
                + "without smtp:// or a port");
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /**
     * The address people open Kilnmark at: http or https, a host, and optionally a port and path; no
     * user name, query or fragment. A trailing slash is dropped.
     */
    static String baseUrl(String text) {
        String b = blank(text);
        if (b == null) {
            return null;
        }
        String problem = "baseUrl: enter the address people open Kilnmark at, such as https://kilnmark.example.com";
        try {
            URI u = new URI(b);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null
                || u.getRawQuery() != null || u.getRawFragment() != null || b.length() > 500) {
                throw new IllegalArgumentException(problem);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(problem, e);
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
