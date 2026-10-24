package io.kilnmark.server.quality;

import java.util.Locale;

/**
 * The dimensions Kilnmark measures quality in. Each maps onto an Open Data Contract Standard
 * dimension ({@link #odcs()}); the three ODCS lacks are exported with the nearest one and the
 * original kept in a custom property, so an export read back loses nothing.
 */
public enum Dimension {
    COMPLETENESS("completeness"),
    UNIQUENESS("uniqueness"),
    VALIDITY("conformity"),
    CONSISTENCY("consistency"),
    ACCURACY("accuracy"),
    TIMELINESS("timeliness"),
    VOLUME("coverage"),
    INTEGRITY("consistency");

    private final String odcs;

    Dimension(String odcs) {
        this.odcs = odcs;
    }

    /** The name used in the API and the database. */
    @com.fasterxml.jackson.annotation.JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The ODCS 3.1 dimension this one is exported as. */
    public String odcs() {
        return odcs;
    }

    /** True when the ODCS dimension is a different word, so the export keeps ours beside it. */
    public boolean odcsDiffers() {
        return !odcs.equals(id());
    }

    public static Dimension parse(String text) {
        if (text != null) {
            for (Dimension d : values()) {
                if (d.id().equalsIgnoreCase(text.trim())) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("dimension: one of completeness, uniqueness, validity, consistency, "
            + "accuracy, timeliness, volume or integrity");
    }

    /** People's words for it, for UI text and reasons. */
    public String label() {
        return switch (this) {
            case COMPLETENESS -> "Completeness";
            case UNIQUENESS -> "Uniqueness";
            case VALIDITY -> "Validity";
            case CONSISTENCY -> "Consistency";
            case ACCURACY -> "Accuracy (estimated)";
            case TIMELINESS -> "Timeliness";
            case VOLUME -> "Volume";
            case INTEGRITY -> "Referential integrity";
        };
    }
}
