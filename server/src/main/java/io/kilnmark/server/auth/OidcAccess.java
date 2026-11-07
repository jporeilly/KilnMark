package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.kilnmark.server.KilnmarkConfig;

/**
 * What a person signed in with the identity provider may do (ADR 0036), from the claims in their
 * ID token: the highest role any of their groups is given, the unmask grant for designers in an
 * unmask group, or no role at all with the reason, when the provider used no second factor or none
 * of their groups has a role. Groups compare exactly, as the provider writes them.
 */
public final class OidcAccess {

    /** The outcome: a role and the unmask grant, or a refusal saying why there is no role. */
    public record Decision(AccessRole role, boolean unmask, String refusal) {

        static Decision refused(String why) {
            return new Decision(null, false, why);
        }

        /** Whether the person is given a role. */
        public boolean allowed() {
            return role != null;
        }
    }

    /** Why a sign-in without a second factor is refused. */
    public static final String NO_MFA = "Your organisation's sign-in did not use a second factor (MFA), which "
        + "Kilnmark requires. Sign in again with MFA, or ask your administrator to require it for Kilnmark.";

    /** Why a sign-in with none of the configured groups is refused. */
    public static final String NO_GROUP = "You signed in, but none of your groups is given a role in Kilnmark. Ask a "
        + "Kilnmark administrator to add one of your groups to the role you need.";

    /** Why a sign-in that does not say when the person signed in is refused. */
    public static final String NO_AUTH_TIME = "Your organisation's sign-in did not say when you signed in (auth_time), "
        + "so Kilnmark cannot limit how long it lasts. Ask your administrator to have the provider send it.";

    /** Why a sign-in older than the longest session is refused. */
    public static final String TOO_OLD = "Your sign-in is older than Kilnmark allows. Sign in again.";

    /** Why one of the Starter Pack's example accounts is refused on an install marked production (ADR 0051). */
    public static final String EXAMPLE_ON_PRODUCTION = "This is one of the Starter Pack's example accounts, which "
        + "an install marked production does not let in. Sign in with your own account.";

    private OidcAccess() {
    }

    /**
     * Decides from the provider's ID token, as {@link #decide(KilnmarkConfig.Oidc, Function, Instant)}
     * does, and refuses the Starter Pack's example accounts (by the name Kilnmark gives the sign-in, or
     * its email) on an install marked production (ADR 0051): every sign-in, renewal and request with a
     * session is decided here, so a session an example person had from a trial ends too.
     */
    public static Decision decide(KilnmarkConfig config, org.eclipse.microprofile.jwt.JsonWebToken token, Instant now) {
        if (config.production() && (io.kilnmark.server.starter.ExampleAccounts.isExample(
            OidcIdentityAugmentor.actorName(token)) || strings(token.getClaim("email")).stream()
                .anyMatch(io.kilnmark.server.starter.ExampleAccounts::isExample))) {
            return Decision.refused(EXAMPLE_ON_PRODUCTION);
        }
        return decide(config.auth().oidc(), token::getClaim, now);
    }

    /**
     * Decides from the ID token's claims.
     *
     * @param claim reads a claim by name: a string, a number, or a collection or JSON array of strings
     */
    public static Decision decide(KilnmarkConfig.Oidc config, Function<String, Object> claim, Instant now) {
        if (config.requireMfa() && !usedSecondFactor(config, claim)) {
            return Decision.refused(NO_MFA);
        }
        // Never the token's issue time instead: each token the provider re-issues has a new one, which
        // would restart the longest session for ever. Kilnmark sends max_age, so auth_time is required.
        Instant signedInAt = instant(claim.apply("auth_time"));
        if (signedInAt == null) {
            return Decision.refused(NO_AUTH_TIME);
        }
        if (signedInAt.plus(config.maxSession()).isBefore(now)) {
            return Decision.refused(TOO_OLD);
        }
        Set<String> groups = Set.copyOf(strings(claim.apply(config.groupsClaim())));
        AccessRole role = roleOf(config, groups);
        if (role == null) {
            return Decision.refused(NO_GROUP);
        }
        boolean unmask = role.mayHoldUnmask() && anyOf(groups, config.unmaskGroups().orElse(List.of()));
        return new Decision(role, unmask, null);
    }

    /**
     * The kind of factor each {@code amr} method (RFC 8176) is: something known, something held, or
     * something the person is. Methods that are no factor ({@code user}, {@code pop}, {@code geo},
     * {@code rba}, {@code mca}, {@code wia} and any not listed) count for nothing.
     */
    private static final Map<String, String> FACTOR = Map.ofEntries(
        Map.entry("pwd", "know"), Map.entry("pin", "know"), Map.entry("kba", "know"),
        Map.entry("otp", "have"), Map.entry("hwk", "have"), Map.entry("swk", "have"), Map.entry("sms", "have"),
        Map.entry("tel", "have"), Map.entry("sc", "have"),
        Map.entry("fpt", "are"), Map.entry("face", "are"), Map.entry("iris", "are"), Map.entry("retina", "are"),
        Map.entry("vbm", "are"));

    /**
     * Whether the provider says it used more than one factor: {@code amr} holds {@code mfa}, or
     * methods of two different kinds ({@code pwd} with {@code otp}, {@code hwk} with {@code fpt});
     * or the {@code acr} is one the settings accept. Two methods of one kind ({@code sms} and
     * {@code tel}, both the phone) are one factor, as is one method alone, however strong.
     */
    static boolean usedSecondFactor(KilnmarkConfig.Oidc config, Function<String, Object> claim) {
        Set<String> kinds = new java.util.HashSet<>();
        for (String method : strings(claim.apply("amr"))) {
            String m = method.strip().toLowerCase(Locale.ROOT);
            if (m.equals("mfa")) {
                return true;
            }
            String kind = FACTOR.get(m);
            if (kind != null) {
                kinds.add(kind);
            }
        }
        if (kinds.size() >= 2) {
            return true;
        }
        List<String> acr = strings(claim.apply("acr"));
        return !acr.isEmpty() && config.mfaAcr().map(a -> a.contains(acr.getFirst())).orElse(false);
    }

    /**
     * The highest role any of {@code groups} is given under the settings as they are now, or null
     * when none is: what a schedule a person set is checked by, from the groups their latest sign-in
     * recorded (ADR 0046).
     */
    public static AccessRole roleOf(KilnmarkConfig.Oidc config, java.util.Collection<String> groups) {
        Set<String> g = Set.copyOf(groups);
        AccessRole role = null;
        for (AccessRole r : AccessRole.values()) {
            if (anyOf(g, groupsFor(config, r))) {
                role = r; // values() runs from the lowest role to the highest
            }
        }
        return role;
    }

    /**
     * Of the groups a sign-in's claims name, only those the group-to-role settings name now, as the
     * settings write them, sorted: what a sign-in records for checking a person's schedules
     * (ADR 0046). Other groups are never recorded: in a bank a group's name can say more than it
     * should (a deal team, a health or HR matter). Bounded by the settings, so none is dropped.
     */
    public static List<String> mappedGroups(KilnmarkConfig.Oidc config, Function<String, Object> claim) {
        Set<String> groups = Set.copyOf(strings(claim.apply(config.groupsClaim())));
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (AccessRole r : AccessRole.values()) {
            for (String w : groupsFor(config, r)) {
                if (w != null && !w.isBlank() && groups.contains(w.strip())) {
                    out.add(w.strip());
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> groupsFor(KilnmarkConfig.Oidc config, AccessRole role) {
        return switch (role) {
            case VIEWER -> config.viewerGroups().orElse(List.of());
            case STEWARD -> config.stewardGroups().orElse(List.of());
            case DESIGNER -> config.designerGroups().orElse(List.of());
            case ADMIN -> config.adminGroups().orElse(List.of());
        };
    }

    private static boolean anyOf(Set<String> groups, List<String> wanted) {
        for (String w : wanted) {
            if (w != null && !w.isBlank() && groups.contains(w.strip())) {
                return true;
            }
        }
        return false;
    }

    /** A claim as strings: one string, or each string in a collection or JSON array. */
    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof jakarta.json.JsonString s) {
            out.add(s.getString());
        } else if (value instanceof jakarta.json.JsonArray a) {
            for (jakarta.json.JsonValue v : a) {
                if (v instanceof jakarta.json.JsonString s) {
                    out.add(s.getString());
                }
            }
        } else if (value instanceof Collection<?> c) {
            for (Object o : c) {
                out.addAll(strings(o));
            }
        }
        return out;
    }

    /** A claim holding seconds since 1970, as an instant; null when absent or not a number. */
    static Instant instant(Object value) {
        long seconds;
        if (value instanceof Number n) {
            seconds = n.longValue();
        } else if (value instanceof jakarta.json.JsonNumber n) {
            seconds = n.longValue();
        } else {
            return null;
        }
        return seconds <= 0 ? null : Instant.ofEpochSecond(seconds);
    }
}
