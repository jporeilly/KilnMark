package io.kilnmark.kiln.connect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Server-wide settings for connectors, set by whoever runs Kilnmark (the server's configuration),
 * never by the people who add sources: for example the folders the {@code files} connector may
 * read. Keys are the server's configuration names, such as {@code kilnmark.files.roots}.
 */
public record ConnectorContext(Map<String, String> settings) {

    /** No server-wide settings: connectors that need them stay at their safest defaults. */
    public static final ConnectorContext EMPTY = new ConnectorContext(Map.of());

    public ConnectorContext {
        settings = Map.copyOf(settings == null ? Map.of() : settings);
    }

    /** A setting, or null when absent or blank. */
    public String setting(String key) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** A setting holding a list, written comma-separated. */
    public List<String> list(String key) {
        String v = setting(key);
        if (v == null) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** A true/false setting. */
    public boolean flag(String key, boolean fallback) {
        String v = setting(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    /** A whole-number setting. */
    public int integer(String key, int fallback) {
        String v = setting(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, not '" + v + "'", e);
        }
    }
}
