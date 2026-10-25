package io.kilnmark.kiln.plugins.write;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.kilnmark.kiln.SettingSpec;

import io.kilnmark.kiln.connect.ConnectorException;

/**
 * Reads a write step's further settings ({@code WriteOptions.options()}, as written in the pipeline
 * YAML: strings, numbers and booleans). Each connector declares the keys it takes in
 * {@code Connector.writeOptions()}; the pipeline service passes only those, and keys a writer does
 * not read are ignored here. A setting of the wrong type is refused with a message naming it.
 */
public final class WriteSettings {

    /** Whether to profile what is written (default true). */
    public static final String PROFILE = "profile_output";

    /** Whether the profile may keep example values and sample rows (default true, as scans). */
    public static final String KEEP_VALUES = "keep_values";

    /** The profiling settings every writer takes, as {@code Connector.writeOptions()} lists them. */
    public static final List<SettingSpec> PROFILING = List.of(
        SettingSpec.optional(PROFILE, "Profile what is written", SettingSpec.Kind.BOOLEAN, true,
            "Profile the rows in the same pass and record the profile with the written table or file"),
        SettingSpec.optional(KEEP_VALUES, "Keep example values", SettingSpec.Kind.BOOLEAN, true,
            "false keeps statistics without example values or sample rows; the source's own rule still applies"));

    /** A whole-number setting. */
    public static SettingSpec number(String key, String label, long fallback, String help) {
        return SettingSpec.optional(key, label, SettingSpec.Kind.NUMBER, fallback, help);
    }

    /** A true/false setting. */
    public static SettingSpec flag(String key, String label, boolean fallback, String help) {
        return SettingSpec.optional(key, label, SettingSpec.Kind.BOOLEAN, fallback, help);
    }

    /** {@code specs} followed by {@link #PROFILING}. */
    public static List<SettingSpec> withProfiling(SettingSpec... specs) {
        List<SettingSpec> out = new java.util.ArrayList<>(List.of(specs));
        out.addAll(PROFILING);
        return List.copyOf(out);
    }

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
