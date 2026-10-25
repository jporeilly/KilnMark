package io.kilnmark.server.quality;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What each role may see of values from the data. Roles that may see data (steward and admin)
 * get values, with columns marked sensitive masked unless an admin unmasks them; viewers get
 * words, counts and shares only.
 *
 * <p>Every view of an asset's values passes through here with a {@link Sight}: samples, example
 * values, statistics in profiles and evidence, drift detail, rule parameters drawn from the data,
 * failing rows and queries, database error text, recommendations and exports. Shapes
 * ({@code AA9A 9AA}), counts and shares need no masking; values from a sensitive column do.
 */
public final class Masking {

    public static final String MASK = "••••";
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    /** Profile statistics that are values from the column. */
    static final Set<String> STATISTICS = Set.of("min", "p25", "median", "p75", "max", "mean", "sum", "fence_low",
        "fence_high");

    private Masking() {
    }

    /**
     * Who is looking at one asset's values: whether they may see data at all ({@code data}), the
     * asset's columns marked sensitive, and whether an admin asked to unmask them.
     */
    public record Sight(boolean data, Set<String> sensitive, boolean unmasked) {
        public Sight {
            sensitive = sensitive == null ? Set.of() : Set.copyOf(sensitive);
        }

        /** Whether values from {@code field} may be shown; null or empty for the asset as a whole. */
        public boolean values(String field) {
            return data && (unmasked || field == null || field.isEmpty() || !sensitive.contains(field));
        }

        /**
         * Whether any column's values are hidden. Rows from custom SQL, and text a database wrote
         * about one, can hold any column under any name.
         */
        public boolean anyHidden() {
            return !data || !unmasked && !sensitive.isEmpty();
        }
    }

    /**
     * A column profile for display: for a sensitive column not unmasked, its most common values
     * are masked (their counts stay), and so are its range, its histogram's bounds and other
     * statistics that are values. For {@code GET /api/v1/assets/{urn}} and the MCP server.
     */
    public static JsonNode profile(JsonNode profile, boolean sensitive, boolean unmasked) {
        if (profile == null || !sensitive || unmasked || !(profile instanceof ObjectNode o)) {
            return profile;
        }
        ObjectNode copy = o.deepCopy();
        if (copy.path("top").isArray()) {
            copy.withArrayProperty("top").forEach(t -> {
                if (t instanceof ObjectNode v) {
                    v.put("value", MASK);
                }
            });
        }
        if (copy.path("histogram").isArray()) {
            copy.withArrayProperty("histogram").forEach(b -> {
                if (b instanceof ObjectNode bin) {
                    bin.put("from", MASK);
                    bin.put("to", MASK);
                }
            });
        }
        for (String k : STATISTICS) {
            if (copy.has(k)) {
                copy.put(k, MASK);
            }
        }
        copy.remove("quantiles");
        return copy;
    }

    /** A column profile as {@code s} may see it (see {@link Sights#catalogue} and {@link Sights#model}). */
    public static JsonNode profile(JsonNode profile, String field, Sight s) {
        return profile(profile, !s.values(field), false);
    }

    /** Measure or drift evidence without the values in it, for roles that may not see data. */
    public static JsonNode evidence(JsonNode evidence, boolean data) {
        if (data || !(evidence instanceof ObjectNode o)) {
            return evidence;
        }
        ObjectNode copy = o.deepCopy();
        Measures.VALUE_KEYS.forEach(copy::remove);
        return copy;
    }

    /**
     * Measure or drift evidence about {@code field} as {@code s} may see it. Evidence about the
     * asset as a whole may name the column its values come from ({@code field}, for timeliness and
     * freshness), which counts too.
     */
    public static JsonNode evidence(JsonNode evidence, String field, Sight s) {
        return evidence(evidence, s.values(field) && s.values(about(evidence)));
    }

    /** A drift event as {@code s} may see it: without its detail or values for a column it may not see. */
    public static QualityStore.DriftRow drift(QualityStore.DriftRow d, Sight s) {
        if (s.values(d.field()) && s.values(about(d.evidence()))) {
            return d;
        }
        return new QualityStore.DriftRow(d.id(), d.assetUrn(), d.field(), d.kind(), d.change(), d.severity(),
            d.message(), evidence(d.evidence(), false), d.versionId(), d.comparedWith(), d.against(), d.detectedAt(),
            null);
    }

    private static String about(JsonNode evidence) {
        return evidence == null || !evidence.path("field").isTextual() ? null : evidence.get("field").asText();
    }

    /**
     * A rule's parameters for display: viewers do not see lists of allowed values, range bounds
     * or custom SQL (which may quote values).
     */
    public static JsonNode params(Rule r, boolean data) {
        return params(r, new Sight(data, Set.of(), true));
    }

    /**
     * A rule's parameters as {@code s} may see them: allowed values and range bounds are values of
     * the rule's column (often taken from the data by a recommendation), hidden where the column's
     * values are; custom SQL (which may quote values of any column) where any column's are, the same
     * rule as for its failing query.
     */
    public static JsonNode params(Rule r, Sight s) {
        boolean hide = switch (r.type()) {
            case ALLOWED_VALUES, IN_RANGE -> !s.values(r.field());
            case CUSTOM_SQL -> s.anyHidden();
            default -> false;
        };
        return hide ? J.objectNode().put("hidden", true) : r.params();
    }

    /** The rule as a role may see it. */
    public static Rule rule(Rule r, boolean data) {
        return rule(r, new Sight(data, Set.of(), true));
    }

    /** The rule as {@code s} may see it; webhook addresses only for roles that may see data. */
    public static Rule rule(Rule r, Sight s) {
        JsonNode p = params(r, s);
        if (s.data() && p == r.params()) {
            return r;
        }
        return new Rule(r.id(), r.assetUrn(), r.field(), r.name(), r.type(), r.dimension(),
            p instanceof ObjectNode o ? o : J.objectNode(), r.thresholdUnit(), r.warnAbove(), r.failAbove(),
            s.data() ? r.actions() : new Rule.Actions(r.actions().webhook() == null ? null : "(set)", r.actions().task()),
            r.enabled(), r.origin(), r.originRef(), r.createdBy(), r.createdAt(), r.updatedAt());
    }

    /**
     * Whether the values a rule's checks touch may be shown: its column's (and a unique key's other
     * columns), or for custom SQL (which can read any column) every column's. {@code rule} null
     * (deleted) counts as custom. Where not, the rule is not previewed and its results give counts
     * only: failing rows would show which rows hold which values.
     */
    public static boolean ruleValues(Rule rule, Sight s) {
        if (rule == null || rule.type() == RuleType.CUSTOM_SQL) {
            return !s.anyHidden();
        }
        if (!s.values(rule.field())) {
            return false;
        }
        for (JsonNode f : rule.params().path("fields")) {
            if (!s.values(f.asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A result without its failing rows. The failing-rows query (which holds the rule's values) and
     * the database's error text (which may quote the data) only where the rule's values may be
     * shown.
     */
    public static QualityStore.Result result(QualityStore.Result r, Rule rule, Sight s) {
        if (r == null) {
            return null;
        }
        boolean show = ruleValues(rule, s);
        String message = r.message();
        if (rule != null && rule.type() == RuleType.FRESHNESS && s.sensitive().contains(rule.field()) && !s.unmasked()
            && message != null && r.failing() != null) {
            // the latest value's age is a value of the column
            message = r.failing() > 0 ? "The latest value is older than the limit" : "The latest value is within the limit";
        }
        return new QualityStore.Result(r.id(), r.ruleId(), r.qualityRunId(), r.assetUrn(), r.status(), r.total(),
            r.failing(), r.method(), show ? r.failingQuery() : null, null, message, r.observedAt(), r.origin(),
            show ? r.errorDetail() : null, r.estimate());
    }

    /**
     * A recommended rule as {@code s} may see it: allowed values and ranges are values of the
     * column, so without them the recommendation says only that a rule is worth adding.
     */
    public static Recommendations.Recommendation recommendation(Recommendations.Recommendation r, Sight s) {
        boolean values = r.type().equals("allowed_values") || r.type().equals("in_range");
        if (!values || s.values(r.field())) {
            return r;
        }
        return new Recommendations.Recommendation(r.field(), r.type(), r.dimension(), r.name(), J.objectNode(),
            s.data() ? "The column is sensitive: ask an admin to review it" : "Ask a steward to review it");
    }
}
