package io.kilnmark.server.notify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.kilnmark.server.auth.AccessRole;

/**
 * What one recipient hears about. By email: the kinds of event, the least severity, each at once
 * or in a daily digest at {@code digestHour} (server time zone). In the app (people only, not team
 * addresses): the kinds of event, at any severity. Both for every source or only some.
 *
 * @param email       where to send; null until a person gives one
 * @param enabled     whether they get any email at all
 * @param kinds       the kinds of event they get by email
 * @param minSeverity the least severity they get by email
 * @param mode        at once, or in a daily digest
 * @param digestHour  the hour (0 to 23, server time zone) the digest goes
 * @param sources     the sources they hear about, by name; null for every source
 * @param inAppKinds  the kinds of event they see in the app's notifications
 */
public record Subscription(String email, boolean enabled, Set<NotificationKind> kinds, Severity minSeverity, Mode mode,
                           int digestHour, List<String> sources, Set<NotificationKind> inAppKinds) {

    /** Each email at once, or one email a day. */
    public enum Mode {
        IMMEDIATE, DIGEST;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(String text) {
            if (text != null) {
                for (Mode m : values()) {
                    if (m.id().equalsIgnoreCase(text.trim())) {
                        return m;
                    }
                }
            }
            throw new IllegalArgumentException("mode: use immediate or digest");
        }
    }

    /** The hour digests go unless someone chooses another. */
    public static final int DEFAULT_DIGEST_HOUR = 8;
    static final int MAX_SOURCES = 500;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");

    public Subscription {
        kinds = kinds == null || kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
        inAppKinds = inAppKinds == null || inAppKinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(inAppKinds));
        sources = sources == null ? null : List.copyOf(sources);
        if (digestHour < 0 || digestHour > 23) {
            throw new IllegalArgumentException("digestHour: choose an hour from 0 to 23");
        }
    }

    /**
     * Before someone saves their own: everyone sees every kind of event in the app. By email, admins
     * get high-severity events at once (every kind but issues opened, which repeat failing rules and
     * drift), as soon as they give an address; everyone else gets no email until they turn it on,
     * with the same choices ready.
     */
    public static Subscription defaults(AccessRole role) {
        return new Subscription(null, role == AccessRole.ADMIN, NotificationKind.defaults(), Severity.HIGH,
            Mode.IMMEDIATE, DEFAULT_DIGEST_HOUR, null, EnumSet.allOf(NotificationKind.class));
    }

    /** A new team address: high-severity events, in a daily digest, for every source. */
    public static Subscription teamDefaults(String email) {
        return new Subscription(email, true, NotificationKind.defaults(), Severity.HIGH, Mode.DIGEST,
            DEFAULT_DIGEST_HOUR, null, Set.of());
    }

    /** Whether this recipient wants an email about an event of {@code kind} at {@code severity} from {@code source}. */
    public boolean wants(NotificationKind kind, Severity severity, String source) {
        return enabled && email != null && kinds.contains(kind) && severity.atLeast(minSeverity) && covers(source);
    }

    /** Whether this person wants to see an event of {@code kind} from {@code source} in the app, at any severity. */
    public boolean wantsInApp(NotificationKind kind, String source) {
        return inAppKinds.contains(kind) && covers(source);
    }

    private boolean covers(String source) {
        return sources == null || source == null || sources.contains(source);
    }

    /**
     * As people send it: subscriptions from the API, checked. A field left out keeps its value; for
     * every source send {@code allSources: true} (a list of sources limits to those).
     */
    public record Input(String email, Boolean enabled, List<String> kinds, String minSeverity, String mode,
                        Integer digestHour, List<String> sources, List<String> inAppKinds, Boolean allSources) {
    }

    /** Checks an input, filling what it leaves out from {@code base}. */
    public static Subscription from(Input in, Subscription base) {
        if (in == null) {
            throw new IllegalArgumentException("Send the subscription as JSON: email, enabled, kinds, minSeverity, mode, "
                + "digestHour, sources or allSources, and inAppKinds");
        }
        String email = in.email() == null ? base.email() : email(in.email());
        boolean enabled = in.enabled() == null ? base.enabled() : in.enabled();
        Set<NotificationKind> kinds = in.kinds() == null ? base.kinds() : kinds(in.kinds());
        Set<NotificationKind> inApp = in.inAppKinds() == null ? base.inAppKinds() : kinds(in.inAppKinds());
        Severity least = in.minSeverity() == null ? base.minSeverity() : Severity.parse(in.minSeverity());
        Mode mode = in.mode() == null ? base.mode() : Mode.parse(in.mode());
        int hour = in.digestHour() == null ? base.digestHour() : in.digestHour();
        List<String> sources = Boolean.TRUE.equals(in.allSources()) ? null
            : in.sources() == null ? base.sources() : sources(in.sources());
        if (enabled && email == null) {
            throw new IllegalArgumentException("email: give the address to send to, or turn email off");
        }
        if (enabled && kinds.isEmpty()) {
            throw new IllegalArgumentException("kinds: choose at least one kind of event, or turn email off");
        }
        return new Subscription(email, enabled, kinds, least, mode, hour, sources, inApp);
    }

    private static Set<NotificationKind> kinds(List<String> ids) {
        Set<NotificationKind> k = EnumSet.noneOf(NotificationKind.class);
        ids.forEach(x -> k.add(NotificationKind.parse(x)));
        return k;
    }

    /** The same subscription without in-app notifications (a team address has no one to sign in). */
    public Subscription withoutInApp() {
        return new Subscription(email, enabled, kinds, minSeverity, mode, digestHour, sources, Set.of());
    }

    private static List<String> sources(Collection<String> names) {
        if (names.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("sources: choose at most " + MAX_SOURCES + " sources, or every source");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (n == null || n.isBlank() || n.length() > 200) {
                throw new IllegalArgumentException("sources: each source is named as it is in its address, such as lab");
            }
            out.add(n.strip());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("sources: choose at least one source, or send null for every source");
        }
        return new ArrayList<>(out);
    }

    /**
     * An email address checked for sending: exactly one address, no display name, no line breaks
     * (which could add headers), at most 254 characters, read strictly as RFC 822 by Jakarta Mail,
     * and lower-cased so the same address is always counted as one.
     */
    public static String email(String text) {
        String e = text == null ? "" : text.strip();
        if (e.isEmpty()) {
            return null;
        }
        String problem = "email: '" + e.replaceAll("\\p{Cntrl}", "?") + "' is not one email address such as "
            + "data-team@example.com";
        if (e.length() > 254 || e.chars().anyMatch(c -> Character.isISOControl(c)) || !EMAIL.matcher(e).matches()
            || e.contains("..") || !e.substring(e.indexOf('@') + 1).contains(".")) {
            throw new IllegalArgumentException(problem);
        }
        try {
            jakarta.mail.internet.InternetAddress a = new jakarta.mail.internet.InternetAddress(e, true);
            if (a.isGroup() || a.getPersonal() != null || !e.equals(a.getAddress())) {
                throw new IllegalArgumentException(problem);
            }
        } catch (jakarta.mail.internet.AddressException x) {
            throw new IllegalArgumentException(problem, x);
        }
        return e.toLowerCase(java.util.Locale.ROOT);
    }
}
