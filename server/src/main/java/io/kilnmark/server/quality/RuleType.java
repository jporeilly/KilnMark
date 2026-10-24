package io.kilnmark.server.quality;

import java.util.Locale;

/** The library of checks. Each has the dimension it is tagged with unless a rule says otherwise. */
public enum RuleType {
    /** No nulls; with {@code blankIsMissing}, no blank text either. */
    NOT_NULL(Dimension.COMPLETENESS, true),
    /** No value appears twice; {@code fields} for a key of several columns. */
    UNIQUE(Dimension.UNIQUENESS, true),
    /** Every value is one of {@code values}. */
    ALLOWED_VALUES(Dimension.VALIDITY, true),
    /** Every value matches {@code regex}. */
    MATCHES_PATTERN(Dimension.VALIDITY, true),
    /** Every value lies between {@code min} and {@code max} (either may be left out), inclusive. */
    IN_RANGE(Dimension.ACCURACY, true),
    /** The latest value of a date or timestamp column is at most {@code maxAgeHours} old. */
    FRESHNESS(Dimension.TIMELINESS, true),
    /** The asset has between {@code min} and {@code max} rows. */
    ROW_COUNT(Dimension.VOLUME, false),
    /** The row count changed by at most {@code maxChange} (a share, 0.2 for 20%) since the last scan. */
    ROW_COUNT_CHANGE(Dimension.VOLUME, false),
    /** Every value appears in {@code field} of the asset {@code asset} (same source): a foreign key holds. */
    REFERENCES(Dimension.INTEGRITY, true),
    /** {@code sql} lists the failing rows; written by a steward, run read-only with a time limit. */
    CUSTOM_SQL(Dimension.VALIDITY, false);

    private final Dimension dimension;
    private final boolean needsField;

    RuleType(Dimension dimension, boolean needsField) {
        this.dimension = dimension;
        this.needsField = needsField;
    }

    /** The name used in the API and the database. */
    @com.fasterxml.jackson.annotation.JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Dimension dimension() {
        return dimension;
    }

    public boolean needsField() {
        return needsField;
    }

    /** True for checks with one result for the whole asset (pass or fail), not a share of rows. */
    public boolean wholeAsset() {
        return this == FRESHNESS || this == ROW_COUNT || this == ROW_COUNT_CHANGE;
    }

    public static RuleType parse(String text) {
        if (text != null) {
            for (RuleType t : values()) {
                if (t.id().equalsIgnoreCase(text.trim())) {
                    return t;
                }
            }
        }
        throw new IllegalArgumentException("type: one of not_null, unique, allowed_values, matches_pattern, in_range, "
            + "freshness, row_count, row_count_change, references or custom_sql");
    }
}
