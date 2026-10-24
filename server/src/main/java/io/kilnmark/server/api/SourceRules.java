package io.kilnmark.server.api;

import java.util.List;
import java.util.regex.Pattern;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

/** What makes a source acceptable, shared by single adds and bulk import. */
final class SourceRules {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private SourceRules() {
    }

    /** The first problem with a source, or null if it is acceptable. Does not connect to anything. */
    static String check(Connectors connectors, SourceConfig source) {
        if (!NAME.matcher(source.name()).matches()) {
            return "name: 1 to 63 lower-case letters, digits and hyphens, starting with a letter or digit";
        }
        Connector c = connectors.get(source.kind()).orElse(null);
        if (c == null) {
            return "kind: unknown kind '" + source.kind() + "'; this server supports "
                + String.join(", ", connectors.all().stream().map(Connector::kind).toList());
        }
        for (Connector.Setting s : c.settings()) {
            if (s.required() && source.setting(s.key()) == null) {
                return s.key() + ": required for " + c.displayName();
            }
        }
        List<String> problems = c.validate(source);
        return problems.isEmpty() ? null : problems.get(0);
    }
}
