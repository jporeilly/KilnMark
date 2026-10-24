package io.kilnmark.server.quality;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What each role may see of values from the data. Roles that may see data (steward and admin)
 * get values, with columns marked sensitive masked unless an admin unmasks them; viewers get
 * words, counts and shares only.
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
     * A column profile for display: for a sensitive column not unmasked, its most common values
     * are masked (their counts stay), and so are its range and other statistics that are values.
     * For {@code GET /api/v1/assets/{urn}}.
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
        for (String k : STATISTICS) {
            if (copy.has(k)) {
                copy.put(k, MASK);
            }
        }
        copy.remove("quantiles");
        return copy;
    }

    /** Masks the profile's most common values of every sensitive field; for {@code CatalogResource}. */
    public static JsonNode profile(String field, JsonNode profile, Set<String> sensitive, boolean unmasked) {
        return profile(profile, sensitive.contains(field), unmasked);
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
     * A rule's parameters for display: viewers do not see lists of allowed values, range bounds
     * or custom SQL (which may quote values).
     */
    public static JsonNode params(Rule r, boolean data) {
        if (data) {
            return r.params();
        }
        return switch (r.type()) {
            case ALLOWED_VALUES, IN_RANGE, CUSTOM_SQL -> J.objectNode().put("hidden", true);
            default -> r.params();
        };
    }

    /** The rule as a role may see it. */
    public static Rule rule(Rule r, boolean data) {
        if (data) {
            return r;
        }
        JsonNode p = params(r, false);
        return new Rule(r.id(), r.assetUrn(), r.field(), r.name(), r.type(), r.dimension(),
            p instanceof ObjectNode o ? o : J.objectNode(), r.thresholdUnit(), r.warnAbove(), r.failAbove(),
            new Rule.Actions(r.actions().webhook() == null ? null : "(set)", r.actions().task()), r.enabled(), r.origin(),
            r.originRef(), r.createdBy(), r.createdAt(), r.updatedAt());
    }
}
