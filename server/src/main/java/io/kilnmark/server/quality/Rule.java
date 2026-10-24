package io.kilnmark.server.quality;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A data quality rule on one asset, and optionally one of its fields.
 *
 * @param thresholdUnit {@code share} (failing rows as a share of rows checked, 0 to 1) or
 *                      {@code count} (failing rows)
 * @param warnAbove     warn when failing rows are above this; null for no warning level
 * @param failAbove     fail when failing rows are above this; 0 fails on any failing row
 * @param origin        the catalogue the rule came from: {@code kilnmark} for rules made here
 */
public record Rule(long id, String assetUrn, String field, String name, RuleType type, Dimension dimension,
                   ObjectNode params, String thresholdUnit, Double warnAbove, double failAbove, Actions actions,
                   boolean enabled, String origin, String originRef, String createdBy, Instant createdAt,
                   Instant updatedAt) {

    /**
     * What happens when the rule warns or fails, besides setting its status.
     *
     * @param webhook a URL on the administrator's allow-list to POST the result to; null for none
     * @param task    whether to open (or keep open) a task in the stewards' issue list
     */
    public record Actions(String webhook, boolean task) {
        public static final Actions NONE = new Actions(null, false);
    }

    /** Pass, warn, fail, or error (the check could not run). */
    public enum Status {
        PASS, WARN, FAIL, ERROR;

        /** The name used in the API and the database. */
        @com.fasterxml.jackson.annotation.JsonValue
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Status parse(String s) {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** The status for {@code failing} of {@code total} rows checked. */
    public Status status(long failing, long total) {
        double measured = "count".equals(thresholdUnit) || type.wholeAsset() ? failing
            : total == 0 ? 0 : (double) failing / total;
        if (measured > failAbove) {
            return Status.FAIL;
        }
        if (warnAbove != null && measured > warnAbove) {
            return Status.WARN;
        }
        return Status.PASS;
    }

    /** The rule as people read it, for messages: "email not_null". */
    public String describe() {
        return name;
    }
}
