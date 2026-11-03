package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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

    /** Why a sign-in older than the longest session is refused. */
    public static final String TOO_OLD = "Your sign-in is older than Kilnmark allows. Sign in again.";

    private OidcAccess() {
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
        Instant signedInAt = instant(claim.apply("auth_time"));
        if (signedInAt == null) {
            signedInAt = instant(claim.apply("iat"));
        }
        if (signedInAt == null || signedInAt.plus(config.maxSession()).isBefore(now)) {
            return Decision.refused(TOO_OLD);
        }
        Set<String> groups = Set.copyOf(strings(claim.apply(config.groupsClaim())));
        AccessRole role = null;
        for (AccessRole r : AccessRole.values()) {
            if (anyOf(groups, groupsFor(config, r))) {
                role = r; // values() runs from the lowest role to the highest
            }
        }
        if (role == null) {
            return Decision.refused(NO_GROUP);
        }
        boolean unmask = role.mayHoldUnmask() && anyOf(groups, config.unmaskGroups().orElse(List.of()));
        return new Decision(role, unmask, null);
    }

    /** Whether the provider says it used more than a password: an {@code amr} or {@code acr} the settings accept. */
    static boolean usedSecondFactor(KilnmarkConfig.Oidc config, Function<String, Object> claim) {
        Set<String> accepted = lower(config.mfaMethods());
        for (String method : strings(claim.apply("amr"))) {
            if (accepted.contains(method.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        List<String> acr = strings(claim.apply("acr"));
        return !acr.isEmpty() && config.mfaAcr().map(a -> a.contains(acr.getFirst())).orElse(false);
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

    private static Set<String> lower(List<String> values) {
        Set<String> out = new java.util.HashSet<>();
        for (String v : values) {
            out.add(v.strip().toLowerCase(Locale.ROOT));
        }
        return out;
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
