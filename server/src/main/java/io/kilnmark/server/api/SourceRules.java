package io.kilnmark.server.api;

import java.util.regex.Pattern;

/** What makes a source acceptable, shared by single adds and bulk import. */
final class SourceRules {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private SourceRules() {
    }

    /** The first problem with a source, or null if it is acceptable. */
    static String check(String name, String kind, String url) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "name: 1 to 63 lower-case letters, digits and hyphens, starting with a letter or digit";
        }
        if (!"postgresql".equals(kind)) {
            return "kind: only postgresql is supported in this version";
        }
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            return "url: a PostgreSQL JDBC URL, for example jdbc:postgresql://host:5432/db";
        }
        return null;
    }
}
