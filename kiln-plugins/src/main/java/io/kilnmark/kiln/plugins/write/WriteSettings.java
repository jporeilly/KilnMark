package io.kilnmark.kiln.plugins.write;

import java.util.Locale;
import java.util.Map;

import io.kilnmark.kiln.connect.ConnectorException;

/**
 * Reads a write step's further settings ({@code WriteOptions.options()}, as written in the pipeline
 * YAML: strings, numbers and booleans). Settings a writer does not know are ignored here; the
 * pipeline's own check refuses keys a step does not declare. A setting of the wrong type is refused
 * with a message naming it.
 */
public final class WriteSettings {

    /** Whether to profile what is written (default true). */
    public static final String PROFILE = "profile";

    /** Whether the profile may keep example values and sample rows (default true, as scans). */
    public static final String KEEP_VALUES = "keep_values";

    private final Map<String, Object> options;

    public WriteSettings(Map<String, Object> options) {
        this.options = options == null ? Map.of() : options;
    }

    /** A true/false setting: a boolean, or the text {@code true} or {@code false}. */
    public boolean bool(String key, boolean fallback) throws ConnectorException {
        Object v = options.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ConnectorException(key + ": true or false, not '" + v + "'");
        };
    }

    /** A whole number from {@code min} to {@code max}, or {@code fallback} when absent. */
    public long number(String key, long fallback, long min, long max) throws ConnectorException {
        Object v = options.get(key);
        if (v == null) {
            return fallback;
        }
        long n;
        if (v instanceof Number num && (num instanceof Integer || num instanceof Long || num instanceof Short)) {
            n = num.longValue();
        } else {
            try {
                n = Long.parseLong(v.toString().strip().replace("_", ""));
            } catch (NumberFormatException e) {
                throw new ConnectorException(key + ": a whole number, not '" + v + "'");
            }
        }
        if (n < min || n > max) {
            throw new ConnectorException(key + ": from " + min + " to " + max + ", not " + n);
        }
        return n;
    }

    /** A text setting, or {@code fallback} when absent or blank. */
    public String text(String key, String fallback) {
        Object v = options.get(key);
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    /** Whether the setting was given at all. */
    public boolean has(String key) {
        return options.containsKey(key);
    }
}
