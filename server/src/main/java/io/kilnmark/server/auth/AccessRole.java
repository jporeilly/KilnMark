package io.kilnmark.server.auth;

import java.util.Locale;
import java.util.Set;

/**
 * What a caller may do. Each role includes the ones below it: a steward can do everything a
 * viewer can, and an admin everything a steward can. Resources name the least role they need in
 * {@code @RolesAllowed}, using the constants here.
 */
public enum AccessRole {
    /** Reads the catalogue: search, assets, sources, runs and connectors. */
    VIEWER,
    /** Also edits, tests and deletes sources, and starts scans. */
    STEWARD,
    /** Also adds and imports sources and manages API tokens. */
    ADMIN;

    /** Security role names, as used in {@code @RolesAllowed}. */
    public static final String VIEWER_ROLE = "viewer";
    /** See {@link #VIEWER_ROLE}. */
    public static final String STEWARD_ROLE = "steward";
    /** See {@link #VIEWER_ROLE}. */
    public static final String ADMIN_ROLE = "admin";

    /** The name stored with a token and shown in the API: {@code viewer}, {@code steward} or {@code admin}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** This role and every role it includes, as security role names. */
    public Set<String> grants() {
        return switch (this) {
            case VIEWER -> Set.of(VIEWER_ROLE);
            case STEWARD -> Set.of(VIEWER_ROLE, STEWARD_ROLE);
            case ADMIN -> Set.of(VIEWER_ROLE, STEWARD_ROLE, ADMIN_ROLE);
        };
    }

    /** Reads {@code viewer}, {@code steward} or {@code admin}, in any case. */
    public static AccessRole parse(String text) {
        if (text != null) {
            for (AccessRole r : values()) {
                if (r.id().equalsIgnoreCase(text.trim())) {
                    return r;
                }
            }
        }
        throw new IllegalArgumentException("role: use viewer, steward or admin");
    }
}
