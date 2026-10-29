package io.kilnmark.server.auth;

import java.util.Locale;
import java.util.Set;

/**
 * What a caller may do. Each role includes the ones below it: a steward can do everything a
 * viewer can, a designer everything a steward can, and an admin everything a designer can. Resources name the least role they need in
 * {@code @RolesAllowed}, using the constants here.
 */
public enum AccessRole {
    /** Reads the catalogue: search, assets, sources, runs and connectors. */
    VIEWER,
    /** Also runs pipelines, and starts scans. Stewards cannot edit pipelines. */
    STEWARD,
    /**
     * Also builds and edits pipelines, previews steps, adds sources, turns on writing, writes custom
     * SQL rules and sees values of columns marked sensitive.
     */
    DESIGNER,
    /** Also the Settings pages, API tokens and the admin-only settings of sources. */
    ADMIN;

    /** Security role names, as used in {@code @RolesAllowed}. */
    public static final String VIEWER_ROLE = "viewer";
    /** See {@link #VIEWER_ROLE}. */
    public static final String STEWARD_ROLE = "steward";
    /** See {@link #VIEWER_ROLE}. */
    public static final String DESIGNER_ROLE = "designer";
    /** See {@link #VIEWER_ROLE}. */
    public static final String ADMIN_ROLE = "admin";

    /** The name stored with a token and shown in the API: {@code viewer}, {@code steward}, {@code designer} or {@code admin}. */
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

    /** Reads {@code viewer}, {@code steward}, {@code designer} or {@code admin}, in any case. */
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
