package io.kilnmark.kiln;

import java.util.List;
import java.util.Objects;

/**
 * One setting of a node type, as the canvas shows it in the node's settings panel and validation
 * checks it (docs/design/pipelines.md).
 *
 * @param key          the YAML key
 * @param label        what people read
 * @param kind         the kind of value, which decides the control the canvas uses
 * @param required     whether the node cannot run without it
 * @param defaultValue the value used when it is left out, or null
 * @param help         one sentence on what it does
 * @param choices      the allowed values for {@link Kind#CHOICE}, else empty
 */
public record SettingSpec(String key, String label, Kind kind, boolean required, Object defaultValue, String help,
                          List<String> choices) {

    /** Kinds of setting value. */
    public enum Kind {
        /** Free text. */
        TEXT,
        /** A whole or decimal number. */
        NUMBER,
        /** True or false. */
        BOOLEAN,
        /** One of {@code choices}. */
        CHOICE,
        /** An address, such as a JDBC URL or an endpoint; for connector settings ({@code Connector.Setting}). */
        URL,
        /** One expression (docs/design/pipelines.md, section 3), for example a filter condition. */
        EXPRESSION,
        /** Output column name → expression, for compute and aggregate. */
        EXPRESSIONS,
        /** One column of an input. */
        COLUMN,
        /** A list of columns of an input. */
        COLUMNS,
        /** Column name → column name, for renames and lookup keys. */
        COLUMN_MAP,
        /** Port name → condition, for route. */
        CONDITIONS,
        /** A saved source, by name. */
        SOURCE,
        /** An asset of the chosen source: a table, view or file path. */
        ASSET,
        /**
         * Quality rules written in the pipeline: a list of mappings, each with a {@code type} and
         * the rule's own settings (for the gate node). The node's factory checks each rule.
         */
        RULES
    }

    public SettingSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        help = help == null ? "" : help;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static SettingSpec required(String key, String label, Kind kind, String help) {
        return new SettingSpec(key, label, kind, true, null, help, List.of());
    }

    public static SettingSpec optional(String key, String label, Kind kind, Object defaultValue, String help) {
        return new SettingSpec(key, label, kind, false, defaultValue, help, List.of());
    }

    public static SettingSpec choice(String key, String label, boolean required, String defaultValue, String help,
                                     List<String> choices) {
        return new SettingSpec(key, label, Kind.CHOICE, required, defaultValue, help, choices);
    }
}
