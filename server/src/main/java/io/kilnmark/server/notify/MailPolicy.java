package io.kilnmark.server.notify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The rules that keep the mail password where it was entered for, and email where it may go. Pure
 * functions, so each rule is tested on its own (MailPolicyTest).
 *
 * <ul>
 * <li>A stored password is only sent to the server, port, security and user name it was entered
 * with: changing any of them clears it, and asks for it again.</li>
 * <li>The configured password ({@code KILNMARK_MAIL_PASSWORD}) is only sent while the server and
 * user name in force are the configured ones.</li>
 * <li>A password goes without encryption (security none) only when an admin allowed it, and only a
 * password entered under Settings › Email, never the configured one.</li>
 * <li>A setting the operator locked ({@code kilnmark.mail.locked}) cannot be changed through the API.</li>
 * <li>Email goes only to the allowed domains, when any are listed.</li>
 * </ul>
 */
public final class MailPolicy {

    /** The settings an operator may lock, by their configuration names. */
    public static final Set<String> LOCKABLE = Set.of("host", "port", "security", "username", "from", "from-name",
        "base-url", "allow-plain-auth", "allowed-domains", "require-confirmation", "max-attempts",
        "rate-limit-per-hour", "undelivered-after", "undelivered-action");

    private MailPolicy() {
    }

    /** Checks {@code kilnmark.mail.locked}: unknown names stop the server rather than lock nothing. */
    public static Set<String> locked(List<String> names) {
        Set<String> out = new java.util.TreeSet<>();
        for (String n : names) {
            String id = n.strip().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            if (!LOCKABLE.contains(id)) {
                throw new IllegalStateException("kilnmark.mail.locked: '" + id + "' is not a mail setting. Use any of "
                    + String.join(", ", new java.util.TreeSet<>(LOCKABLE)));
            }
            out.add(id);
        }
        return out;
    }

    /** Refuses a change to a locked setting, naming it. {@code current} holds the locked values. */
    public static void checkLocked(MailSettings current, MailSettings.Valid next) {
        List<String> changed = new ArrayList<>();
        for (String id : current.locked()) {
            boolean same = switch (id) {
                case "host" -> Objects.equals(current.host(), next.host());
                case "port" -> Objects.equals(current.port(), next.port());
                case "security" -> current.security() == next.security();
                case "username" -> Objects.equals(current.username(), next.username());
                case "from" -> Objects.equals(current.fromAddress(), next.fromAddress());
                case "from-name" -> Objects.equals(current.fromName(), next.fromName());
                case "base-url" -> Objects.equals(current.baseUrl(), next.baseUrl());
                case "allow-plain-auth" -> current.allowPlainAuth() == next.allowPlainAuth();
                case "allowed-domains" -> current.allowedDomains().equals(next.allowedDomains());
                case "require-confirmation" -> current.requireConfirmation() == next.requireConfirmation();
                case "max-attempts" -> current.maxAttempts() == next.maxAttempts();
                case "rate-limit-per-hour" -> current.rateLimitPerHour() == next.rateLimitPerHour();
                case "undelivered-after" -> current.undeliveredHours() == next.undeliveredHours();
                case "undelivered-action" -> current.undeliveredAction().equals(next.undeliveredAction());
                default -> true;
            };
            if (!same) {
                changed.add(id);
            }
        }
        if (!changed.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", changed) + (changed.size() == 1 ? " is" : " are")
                + " set by the server's configuration (kilnmark.mail.locked) and cannot be changed here");
        }
    }

    /** Whether the password would now go somewhere else: another server, port, security or user name. */
    public static boolean destinationChanged(MailSettings current, MailSettings.Valid next) {
        return !Objects.equals(lower(current.host()), lower(next.host())) || current.effectivePort() != next.effectivePort()
            || current.security() != next.security() || !Objects.equals(current.username(), next.username());
    }

    /** What to do with the stored password when saving. */
    public enum PasswordAction {
        KEEP, REPLACE, REMOVE
    }

    /**
     * What saving does to the stored password. A new one replaces it, "" removes it. Kept only while it
     * would still go where it was entered for; when the destination changed it is removed if no one
     * signs in any more, and otherwise has to be entered again.
     */
    public static PasswordAction password(boolean stored, boolean destinationChanged, MailSettings.Valid next) {
        if (next.password() != null) {
            return next.password().isEmpty() ? PasswordAction.REMOVE : PasswordAction.REPLACE;
        }
        if (!stored || !destinationChanged) {
            return PasswordAction.KEEP;
        }
        if (next.username() == null) {
            return PasswordAction.REMOVE;
        }
        throw new IllegalArgumentException("password: the server, port, security or user name changed, so the stored "
            + "password was not kept. Enter it again: a password is only sent where it was entered for.");
    }

    /**
     * Refuses signing in without encryption unless allowed. {@code passwordEntered} says whether the
     * password is one entered under Settings › Email (only that may go unencrypted).
     */
    public static void checkPlainAuth(MailSecurity security, String username, boolean allowPlainAuth,
                                      boolean passwordEntered) {
        if (security == MailSecurity.NONE && username != null && !(allowPlainAuth && passwordEntered)) {
            throw new IllegalArgumentException("security: with None, the user name and password would be sent "
                + "unencrypted. Choose STARTTLS or TLS" + (passwordEntered ? ", or turn on Allow sign-in without "
                + "encryption" : "; a password from the server's configuration is never sent unencrypted") + ".");
        }
    }

    /** Whether the configured password may be sent: only to the configured server, as the configured user. */
    public static boolean configuredPasswordApplies(String configuredHost, String configuredUser, String host,
                                                    String username) {
        return configuredHost != null && Objects.equals(lower(configuredHost.strip()), lower(host))
            && Objects.equals(blankToNull(configuredUser), username);
    }

    /** Whether email may go to {@code address}: any domain when none are listed, else a listed one or a subdomain. */
    public static boolean domainAllowed(String address, List<String> domains) {
        if (domains.isEmpty()) {
            return true;
        }
        int at = address == null ? -1 : address.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        return domains.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    /** Refuses an address outside the allowed domains, for {@code field}. */
    public static void checkDomain(String field, String address, List<String> domains) {
        if (address != null && !domainAllowed(address, domains)) {
            throw new IllegalArgumentException(field + ": this server sends email only to " + String.join(", ", domains)
                + " (Allowed email domains, under Settings, Email)");
        }
    }

    /**
     * Text from a mail server with the credentials taken out: the password and user name as typed and
     * in base64, and the AUTH PLAIN token ({@code \0user\0password}), in case a server echoes what it
     * was sent.
     */
    public static String scrub(String text, String username, String password) {
        if (text == null) {
            return null;
        }
        String out = text;
        List<String> secrets = new ArrayList<>();
        if (password != null && !password.isEmpty()) {
            secrets.add(password);
            secrets.add(b64(password));
            secrets.add(b64("\0" + (username == null ? "" : username) + "\0" + password));
            if (username != null) {
                secrets.add(b64(username + "\0" + username + "\0" + password));
            }
        }
        if (username != null && !username.isEmpty()) {
            secrets.add(b64(username));
            if (username.length() >= 4) {
                secrets.add(username); // shorter ones would blank ordinary words
            }
        }
        secrets.sort(java.util.Comparator.comparingInt(String::length).reversed());
        for (String s : secrets) {
            out = out.replace(s, "[hidden]");
            String unpadded = s.replaceAll("=+$", "");
            if (unpadded.length() >= 4 && !unpadded.equals(s)) {
                out = out.replace(unpadded, "[hidden]");
            }
        }
        return out;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
