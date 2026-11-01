package io.kilnmark.server.auth;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a caller may do. Each role includes the ones below it: a steward can do everything a
 * viewer can, a designer everything a steward can, and an admin everything a designer can.
 * Resources name the least role they need in {@code @RolesAllowed}, using the constants here.
 *
 * <p>Seeing values of columns marked sensitive is not on this ladder: it is {@link #UNMASK_GRANT},
 * which no role includes and only a designer token may hold (ADR 0027).
 */
public enum AccessRole {
    /** Reads the catalogue: search, assets, sources, pipelines, flows and runs. */
    VIEWER,
    /**
     * Also runs pipelines and flows and cancels their runs; tests, browses and scans sources; and
     * looks after quality: rules (not custom SQL), issues, baselines and what is marked sensitive.
     */
    STEWARD,
    /**
     * Also builds: adds, edits, previews and deletes pipelines and flows; adds, imports, edits and
     * deletes sources and turns on writing for them; writes custom SQL rules; sets server variables.
     * The only role whose tokens may hold {@link #UNMASK_GRANT}.
     */
    DESIGNER,
    /** Also the Settings pages, API tokens and admin-only connectors. Sees values masked. */
    ADMIN;

    /** Security role names, as used in {@code @RolesAllowed}. */
    public static final String VIEWER_ROLE = "viewer";
    /** See {@link #VIEWER_ROLE}. */
    public static final String STEWARD_ROLE = "steward";
    /** See {@link #VIEWER_ROLE}. */
    public static final String DESIGNER_ROLE = "designer";
    /** See {@link #VIEWER_ROLE}. */
    public static final String ADMIN_ROLE = "admin";
    /**
     * Security role for seeing values of columns marked sensitive unmasked. In no role's
     * {@link #grants()}: a designer token adds it when it holds the grant, and nothing else does.
     */
    public static final String UNMASK_GRANT = "unmask";

    /** Whether a token with this role may hold {@link #UNMASK_GRANT}: designers only. */
    public boolean mayHoldUnmask() {
        return this == DESIGNER;
    }

    /** The name stored with a token and shown in the API: viewer, steward, designer or admin. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** This role and every role it includes, as security role names. */
    public Set<String> grants() {
        return switch (this) {
            case VIEWER -> Set.of(VIEWER_ROLE);
            case STEWARD -> Set.of(VIEWER_ROLE, STEWARD_ROLE);
            case DESIGNER -> Set.of(VIEWER_ROLE, STEWARD_ROLE, DESIGNER_ROLE);
            case ADMIN -> Set.of(VIEWER_ROLE, STEWARD_ROLE, DESIGNER_ROLE, ADMIN_ROLE);
        };
    }

    /** Whether this role includes {@code other}. */
    public boolean atLeast(AccessRole other) {
        return compareTo(other) >= 0;
    }

    /** The highest role a caller has, from its security roles (such as {@code identity::hasRole}). */
    public static AccessRole highest(Predicate<String> has) {
        return has.test(ADMIN_ROLE) ? ADMIN : has.test(DESIGNER_ROLE) ? DESIGNER
            : has.test(STEWARD_ROLE) ? STEWARD : VIEWER;
    }

    /** Reads viewer, steward, designer or admin, in any case. */
    public static AccessRole parse(String text) {
        if (text != null) {
            for (AccessRole r : values()) {
                if (r.id().equalsIgnoreCase(text.trim())) {
                    return r;
                }
            }
        }
        throw new IllegalArgumentException("role: use viewer, steward, designer or admin");
    }
}
