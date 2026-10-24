package io.kilnmark.kiln.connect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A data source as a connector sees it: its name, kind, settings, and its secret (a password or
 * access key), already decrypted. Never log or serialise one: {@link #toString} hides the secret.
 */
public record SourceConfig(String name, String kind, Map<String, String> settings, String secret) {

    public SourceConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        settings = Map.copyOf(settings == null ? Map.of() : settings);
    }

    /** A setting, or null when absent or blank. */
    public String setting(String key) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** A setting holding a list, written comma- or semicolon-separated. */
    public List<String> list(String key) {
        String v = setting(key);
        if (v == null) {
            return List.of();
        }
        return Arrays.stream(v.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public String toString() {
        return "SourceConfig[name=" + name + ", kind=" + kind + ", settings=" + settings.keySet()
            + ", secret=" + (secret == null ? "none" : "hidden") + "]";
    }
}
