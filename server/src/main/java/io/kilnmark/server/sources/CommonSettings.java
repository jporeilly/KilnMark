package io.kilnmark.server.sources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.kilnmark.kiln.connect.AssetFilter;
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
 * </ul>
 */
public final class CommonSettings {

    public static final String DESCRIPTION = "description";
    public static final String AI_EGRESS = "ai_egress";

    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_PATTERNS = 1000;

    /** One common setting, as forms show it. {@code choices} is empty for free text. */
    public record Definition(String key, String label, String hint, boolean multiple, List<String> choices,
                             String defaultValue) {
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
        new Definition(AssetFilter.INCLUDE, "Include", "Only tables or files matching these patterns, ignoring case: "
            + "cust* matches names, crm.* or raw/** match paths. Leave empty for all", true, List.of(), null),
        new Definition(AssetFilter.EXCLUDE, "Exclude", "Leave out tables or files matching these patterns, for "
            + "example tmp_*, *_backup or archive/**", true, List.of(), null));

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

    /** {@code settings} with common values written the one way they are stored ({@code ai_egress} in lower case). */
    public static Map<String, String> normalise(Map<String, String> settings) {
        Map<String, String> out = new HashMap<>(settings);
        AiEgress e = AiEgress.parse(out.get(AI_EGRESS));
        if (e != null) {
            out.put(AI_EGRESS, e.id());
        }
        return out;
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
        for (String key : List.of(AssetFilter.INCLUDE, AssetFilter.EXCLUDE)) {
            if (AssetFilter.split(settings.get(key)).size() > MAX_PATTERNS) {
                problems.add(key + ": at most " + MAX_PATTERNS + " patterns");
            }
        }
        problems.addAll(AssetFilter.problems(new SourceConfig("check", "check", settings, null)));
        return problems;
    }
}
