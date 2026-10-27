package io.kilnmark.server.sources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The settings every source has, whatever its kind, kept in {@code source.settings} beside the
 * connector's own. The one place they are defined: the API, bulk import and the web UI's form all
 * take them from here.
 *
 * <ul>
 *   <li>{@value #DESCRIPTION}: free text for people.</li>
 *   <li>{@value #AI_EGRESS}: which AI models may see the source's metadata and data
 *       ({@link AiEgress}); {@code local_only} when absent.</li>
 *   <li>{@value AssetFilter#INCLUDE} and {@value AssetFilter#EXCLUDE}: glob patterns choosing the
 *       tables or files to scan, applied by {@link AssetFilter} for every kind of source.</li>
 *   <li>{@value #ALLOW_WRITING}: whether pipelines may write to the source ({@code true}); read-only
 *       ({@code false}) when absent. Only admins may change it (docs/design/pipelines.md).</li>
 *   <li>{@value #KEEP_SAMPLES}, {@value #SAMPLE_ROWS} and {@value #TABLE_SAMPLE_ABOVE_ROWS}: how
 *       profiling samples a source (docs/design/data-quality.md); the last applies to databases.</li>
 * </ul>
 *
 * <p>Each has a kind, as connector settings do ({@code Connector.Setting}), which a save checks.
 */
public final class CommonSettings {

    public static final String DESCRIPTION = "description";
    public static final String AI_EGRESS = "ai_egress";
    public static final String ALLOW_WRITING = "allow_writing";
    public static final String KEEP_SAMPLES = "keep_samples";
    public static final String SAMPLE_ROWS = "sample_rows";
    public static final String TABLE_SAMPLE_ABOVE_ROWS = "table_sample_above_rows";

    /** The most rows a source's {@value #SAMPLE_ROWS} may ask for. */
    public static final int MAX_SAMPLE_ROWS = 10_000;

    static final int MAX_DESCRIPTION = 2000;

    /**
     * One common setting, as forms show it. {@code choices} is empty for free text. {@code kind} is
     * {@code TEXT}, {@code NUMBER}, {@code BOOLEAN} or {@code CHOICE}; without one, {@code CHOICE}
     * when there are choices and {@code TEXT} otherwise.
     */
    public record Definition(String key, String label, String hint, boolean multiple, List<String> choices,
                             String defaultValue, SettingSpec.Kind kind) {

        public Definition {
            choices = choices == null ? List.of() : List.copyOf(choices);
            kind = kind != null ? kind : choices.isEmpty() ? SettingSpec.Kind.TEXT : SettingSpec.Kind.CHOICE;
        }

        public Definition(String key, String label, String hint, boolean multiple, List<String> choices,
                          String defaultValue) {
            this(key, label, hint, multiple, choices, defaultValue, null);
        }

        /** The setting as a connector would declare it, so a value is checked by kind the same way. */
        Connector.Setting asSetting() {
            return new Connector.Setting(key, label, false, hint, multiple, kind, kind == SettingSpec.Kind.CHOICE
                ? choices : List.of(), defaultValue);
        }
    }

    /**
     * Which AI models may see a source's metadata and data. Enforced where models are called;
     * defined here so every source has it.
     */
    public enum AiEgress {
        /** Only models on servers the administrator marked local. The default. */
        LOCAL_ONLY,
        /** Any configured model. */
        ALLOWED,
        /** No model at all. */
        NONE;

        /** The stored value: {@code local_only}, {@code allowed} or {@code none}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Reads a stored value, ignoring case; null for anything else. */
        public static AiEgress parse(String value) {
            if (value != null) {
                for (AiEgress e : values()) {
                    if (e.id().equalsIgnoreCase(value.trim())) {
                        return e;
                    }
                }
            }
            return null;
        }

        /** A source's setting: {@link #LOCAL_ONLY} when it has none (or, defensively, an unknown one). */
        public static AiEgress of(Map<String, String> settings) {
            AiEgress e = settings == null ? null : parse(settings.get(AI_EGRESS));
            return e == null ? LOCAL_ONLY : e;
        }
    }

    private static final List<Definition> DEFINITIONS = List.of(
        new Definition(DESCRIPTION, "Description", "What this source holds and who looks after it", false, List.of(),
            null),
        new Definition(AI_EGRESS, "AI models", "local_only: only models on servers marked local may see this source's "
            + "metadata and data. allowed: any configured model. none: no model at all", false,
            List.of(AiEgress.LOCAL_ONLY.id(), AiEgress.ALLOWED.id(), AiEgress.NONE.id()), AiEgress.LOCAL_ONLY.id()),
        new Definition(AssetFilter.INCLUDE, "Include", "Only tables or files matching these patterns, ignoring case. "
            + "cust* or *.csv match names; crm.* (a schema's tables) or raw/** (a folder's files) match paths. "
            + "Leave empty for all", true, List.of(), null),
        new Definition(AssetFilter.EXCLUDE, "Exclude", "Leave out tables or files matching these patterns, for "
            + "example tmp_*, *_backup or archive/**", true, List.of(), null),
        new Definition(ALLOW_WRITING, "Allow writing", "true lets pipelines write tables or files to this source, "
            + "with an account that has write rights; false keeps it read-only. Only admins can change it", false,
            List.of("false", "true"), "false", SettingSpec.Kind.BOOLEAN),
        new Definition(KEEP_SAMPLES, "Keep samples", "true (the default) keeps sample rows, example values and "
            + "failing rows for stewards to see; false keeps only shapes and statistics", false, List.of(), "true",
            SettingSpec.Kind.BOOLEAN),
        new Definition(SAMPLE_ROWS, "Rows sampled", "Rows sampled from each table or file for profiling, up to "
            + MAX_SAMPLE_ROWS + ". Leave empty for the server's setting", false, List.of(), null,
            SettingSpec.Kind.NUMBER),
        new Definition(TABLE_SAMPLE_ABOVE_ROWS, "Sample tables above (rows)", "Databases only: tables with more rows "
            + "than this, at least 1000, are profiled from a sample of about that many rows. Leave empty to read "
            + "every row", false, List.of(), null, SettingSpec.Kind.NUMBER));

    private static final Set<String> KEYS = keys(DEFINITIONS);

    private CommonSettings() {
    }

    private static Set<String> keys(List<Definition> definitions) {
        Set<String> keys = new LinkedHashSet<>();
        definitions.forEach(d -> keys.add(d.key()));
        return Set.copyOf(keys);
    }

    /** Every common setting, in the order a form shows them. */
    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    /** The keys of the common settings. */
    public static Set<String> keys() {
        return KEYS;
    }

    /** {@code settings} with common values written the one way they are stored: in lower case. */
    public static Map<String, String> normalise(Map<String, String> settings) {
        Map<String, String> out = new HashMap<>(settings);
        AiEgress e = AiEgress.parse(out.get(AI_EGRESS));
        if (e != null) {
            out.put(AI_EGRESS, e.id());
        }
        for (Definition d : DEFINITIONS) {
            String w = out.get(d.key());
            if (d.kind() == SettingSpec.Kind.BOOLEAN && w != null
                && (w.trim().equalsIgnoreCase("true") || w.trim().equalsIgnoreCase("false"))) {
                out.put(d.key(), w.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean wholeUpTo(String v, int max) {
        return v.matches("\\d{1,9}") && Integer.parseInt(v) <= max;
    }

    /** Whether pipelines may write to a source with these settings: {@code allow_writing=true}. */
    public static boolean allowsWriting(Map<String, String> settings) {
        String w = settings == null ? null : settings.get(ALLOW_WRITING);
        return w != null && w.trim().equalsIgnoreCase("true");
    }

    /** Problems with the common settings, each starting with its key; empty when all are acceptable. */
    public static List<String> problems(Map<String, String> settings) {
        List<String> problems = new ArrayList<>();
        String description = settings.get(DESCRIPTION);
        if (description != null && description.length() > MAX_DESCRIPTION) {
            problems.add(DESCRIPTION + ": at most " + MAX_DESCRIPTION + " characters; this one has "
                + description.length());
        }
        String egress = settings.get(AI_EGRESS);
        if (egress != null && !egress.isBlank() && AiEgress.parse(egress) == null) {
            problems.add(AI_EGRESS + ": one of local_only, allowed or none, not '" + egress + "'");
        }
        for (Definition d : DEFINITIONS) {
            if (!d.key().equals(AI_EGRESS)) {
                String p = d.asSetting().problem(settings.get(d.key()));
                if (p != null) {
                    problems.add(p);
                }
            }
        }
        String rows = settings.get(SAMPLE_ROWS);
        if (rows != null && !rows.isBlank() && !wholeUpTo(rows.strip(), MAX_SAMPLE_ROWS)) {
            problems.removeIf(p -> p.startsWith(SAMPLE_ROWS + ":"));
            problems.add(SAMPLE_ROWS + ": a whole number from 0 to " + MAX_SAMPLE_ROWS + ", not '" + rows + "'");
        }
        problems.addAll(AssetFilter.problems(new SourceConfig("check", "check", settings, null)));
        return problems;
    }
}
