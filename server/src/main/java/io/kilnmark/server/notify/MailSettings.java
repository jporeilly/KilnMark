package io.kilnmark.server.notify;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The outgoing mail server and the rules for email, as an admin sets them under Settings › Email
 * (or, until then, as the server's {@code kilnmark.mail.*} configuration gives them; a setting in
 * {@code locked} always comes from there). The password is not here: it is kept sealed and only
 * {@link MailService} opens it, to send.
 *
 * @param enabled             whether emails go at all (an admin can pause them)
 * @param host                the SMTP server
 * @param port                its port; null for the usual one for {@code security}
 * @param security            none, STARTTLS or TLS
 * @param username            who to sign in as; null to send without signing in
 * @param hasPassword         whether a password will be sent: one saved here, or the configured one
 *                            while the server and user name are the configured ones
 * @param fromAddress         the sender's address
 * @param fromName            the sender's name, such as {@code Kilnmark}; null for none
 * @param baseUrl             how people reach Kilnmark, for links in emails; null for no links
 * @param allowPlainAuth      whether a password saved here may go without encryption (security none)
 * @param allowedDomains      the domains email may go to (subdomains included); empty for any
 * @param requireConfirmation whether people must confirm their address before it gets email
 * @param maxAttempts         tries before an email is given up
 * @param rateLimitPerHour    emails of their own one address may get in an hour
 * @param undeliveredHours    how long an unsent email waits before it goes to the digest or is dropped
 * @param undeliveredAction   {@code digest} or {@code drop}
 * @param locked              settings that come from the server's configuration and cannot be changed here
 * @param saved               whether an admin has saved these (false: from the server's configuration)
 */
public record MailSettings(boolean enabled, String host, Integer port, MailSecurity security, String username,
                           boolean hasPassword, String fromAddress, String fromName, String baseUrl,
                           boolean allowPlainAuth, List<String> allowedDomains, boolean requireConfirmation,
                           int maxAttempts, int rateLimitPerHour, int undeliveredHours, String undeliveredAction,
                           Set<String> locked, boolean saved, String updatedBy, Instant updatedAt) {

    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ATTEMPTS = 10;
    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 1000;
    public static final int MIN_UNDELIVERED_HOURS = 1;
    public static final int MAX_UNDELIVERED_HOURS = 168;
    static final int MAX_DOMAINS = 100;

    private static final Pattern HOST = Pattern.compile(
        "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*|\\[[0-9a-f:.]{2,45}]");
    private static final Pattern DOMAIN = Pattern.compile(
        "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");

    public MailSettings {
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        locked = locked == null ? Set.of() : Set.copyOf(locked);
    }

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

    /** Whether email may go to {@code address}: any domain when none are listed, else a listed one or a subdomain of one. */
    public boolean domainAllowed(String address) {
        return MailPolicy.domainAllowed(address, allowedDomains);
    }

    /**
     * Settings as the API takes them. A field left out (null) keeps its value; an empty text clears
     * it. Leave {@code password} out to keep the stored one; send "" to remove it.
     */
    public record Input(Boolean enabled, String host, Integer port, String security, String username, String password,
                        String fromAddress, String fromName, String baseUrl, Boolean allowPlainAuth,
                        List<String> allowedDomains, Boolean requireConfirmation, Integer maxAttempts,
                        Integer rateLimitPerHour, Integer undeliveredHours, String undeliveredAction) {
    }

    /** Checked settings with the password as given (null: keep, "": remove). */
    public record Valid(boolean enabled, String host, Integer port, MailSecurity security, String username,
                        String password, String fromAddress, String fromName, String baseUrl, boolean allowPlainAuth,
                        List<String> allowedDomains, boolean requireConfirmation, int maxAttempts, int rateLimitPerHour,
                        int undeliveredHours, String undeliveredAction) {
        public int effectivePort() {
            return port != null ? port : security.defaultPort();
        }
    }

    /** Checks settings from the API against {@code current}, saying in plain words what is wrong and where. */
    public static Valid validate(Input in, MailSettings current) {
        if (in == null) {
            throw new IllegalArgumentException("Send the mail settings as JSON: host, port, security, username, password, "
                + "fromAddress, fromName, baseUrl and the rules for email");
        }
        String host = in.host() == null ? current.host() : host(in.host());
        Integer port = in.port() == null ? current.port() : in.port();
        if (port != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("port: choose a port from 1 to 65535, usually 587 (STARTTLS), 465 (TLS) or 25");
        }
        MailSecurity security = in.security() == null || in.security().isBlank() ? current.security()
            : MailSecurity.parse(in.security());
        String username = in.username() == null ? current.username() : blank(in.username());
        if (username != null && (username.length() > 320 || username.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("username: at most 320 characters, without line breaks");
        }
        String password = in.password();
        if (password != null && (password.length() > 1000 || password.chars().anyMatch(c -> c == '\r' || c == '\n'))) {
            throw new IllegalArgumentException("password: at most 1,000 characters, without line breaks");
        }
        String from;
        try {
            from = in.fromAddress() == null ? current.fromAddress() : Subscription.email(in.fromAddress());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage().replaceFirst("^email:", "fromAddress:"), e);
        }
        String name = in.fromName() == null ? current.fromName() : blank(in.fromName());
        if (name != null && (name.length() > 100 || name.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("fromName: at most 100 characters, without line breaks");
        }
        boolean enabled = in.enabled() == null ? current.enabled() : in.enabled();
        if (enabled && host == null) {
            throw new IllegalArgumentException("host: enter the mail server's name, such as smtp.example.com, or turn "
                + "sending off");
        }
        if (enabled && from == null) {
            throw new IllegalArgumentException("fromAddress: enter the address emails come from, such as "
                + "kilnmark@example.com");
        }
        String base = in.baseUrl() == null ? current.baseUrl() : baseUrl(in.baseUrl());
        List<String> domains = in.allowedDomains() == null ? current.allowedDomains() : domains(in.allowedDomains());
        int attempts = between("maxAttempts", in.maxAttempts(), current.maxAttempts(), MIN_ATTEMPTS, MAX_ATTEMPTS, "tries");
        int rate = between("rateLimitPerHour", in.rateLimitPerHour(), current.rateLimitPerHour(), MIN_RATE, MAX_RATE,
            "emails an hour");
        int hours = between("undeliveredHours", in.undeliveredHours(), current.undeliveredHours(), MIN_UNDELIVERED_HOURS,
            MAX_UNDELIVERED_HOURS, "hours");
        String action = in.undeliveredAction() == null ? current.undeliveredAction() : action(in.undeliveredAction());
        return new Valid(enabled, host, port, security, username, password, from, name, base,
            in.allowPlainAuth() == null ? current.allowPlainAuth() : in.allowPlainAuth(), domains,
            in.requireConfirmation() == null ? current.requireConfirmation() : in.requireConfirmation(), attempts, rate,
            hours, action);
    }

    private static int between(String field, Integer value, int current, int min, int max, String unit) {
        int v = value == null ? current : value;
        if (v < min || v > max) {
            throw new IllegalArgumentException(field + ": choose from " + min + " to " + max + " " + unit);
        }
        return v;
    }

    /** {@code digest} or {@code drop}. */
    static String action(String text) {
        String a = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (!a.equals("digest") && !a.equals("drop")) {
            throw new IllegalArgumentException("undeliveredAction: use digest or drop");
        }
        return a;
    }

    /** Domains as typed: lower-case, without a leading {@code @}, each a real domain name, at most 100. */
    static List<String> domains(List<String> typed) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : typed) {
            String d = t == null ? "" : t.strip().toLowerCase(Locale.ROOT);
            if (d.startsWith("@")) {
                d = d.substring(1);
            }
            if (d.isEmpty()) {
                continue;
            }
            if (d.length() > 253 || !DOMAIN.matcher(d).matches()) {
                throw new IllegalArgumentException("allowedDomains: '" + d.replaceAll("\\p{Cntrl}", "?")
                    + "' is not a domain such as example.com");
            }
            out.add(d);
        }
        if (out.size() > MAX_DOMAINS) {
            throw new IllegalArgumentException("allowedDomains: at most " + MAX_DOMAINS + " domains");
        }
        return new ArrayList<>(out);
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
