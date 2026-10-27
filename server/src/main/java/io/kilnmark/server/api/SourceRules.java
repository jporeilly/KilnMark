package io.kilnmark.server.api;

import java.util.List;
import java.util.regex.Pattern;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.sources.CommonSettings;

/** What makes a source acceptable, shared by single adds and bulk import. */
final class SourceRules {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    static final int MAX_CONTAINERS = 1000;
    static final int MAX_SETTING = 4000;

    private SourceRules() {
    }

    static final int MAX_DISPLAY_NAME = 200;

    /** The problem with a display name, or null; null itself is acceptable (it means the name). */
    static String displayNameProblem(String displayName) {
        if (displayName == null) {
            return null;
        }
        if (displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
            return "displayName: 1 to " + MAX_DISPLAY_NAME + " characters, or leave it out to show the name";
        }
        if (displayName.chars().anyMatch(Character::isISOControl)) {
            return "displayName: no line breaks, tabs or other control characters";
        }
        return null;
    }

    /** Whether a name is acceptable for a source. */
    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * The first problem with a source, or null if it is acceptable: its name, its kind, its
     * connector's settings and the common settings. Give the secret the source will have (new or
     * kept), since some kinds check it goes with other settings. Does not connect to anything.
     */
    static String check(Connectors connectors, SourceConfig source) {
        if (!validName(source.name())) {
            return "name: 1 to 63 lower-case letters, digits and hyphens, starting with a letter or digit";
        }
        for (var e : source.settings().entrySet()) {
            if (e.getValue().length() > MAX_SETTING && !e.getKey().equals(CommonSettings.DESCRIPTION)) {
                return e.getKey() + ": at most " + MAX_SETTING + " characters";
            }
        }
        List<String> common = CommonSettings.problems(source.settings());
        if (!common.isEmpty()) {
            return common.get(0);
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
        List<String> wrongKind = Connector.Setting.problems(c.settings(), source);
        if (!wrongKind.isEmpty()) {
            return wrongKind.get(0);
        }
        List<String> chosen = source.list(c.containerKind().setting());
        if (chosen.size() > MAX_CONTAINERS) {
            return c.containerKind().setting() + ": at most " + MAX_CONTAINERS + " " + c.containerKind().label()
                .toLowerCase(java.util.Locale.ROOT);
        }
        List<String> problems = c.validate(source);
        return problems.isEmpty() ? null : problems.get(0);
    }
}
