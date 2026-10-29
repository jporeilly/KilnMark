package io.kilnmark.server.pipelines;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleDraft;
import io.kilnmark.server.quality.RuleType;

/**
 * The quality rules written on a gate node ({@code rules:}), read into {@link Rule}s with the same
 * checks a rule made in the quality pages gets ({@link RuleDraft#check}). In YAML each rule is a
 * mapping with {@code snake_case} keys:
 *
 * <pre>{@code
 * rules:
 *   - {type: not_null, field: email}
 *   - {name: known country, type: allowed_values, field: country, values: [GB, IE, FR]}
 *   - {type: in_range, field: amount, min: 0, max: 10000, fail_above: 0.01}
 * }</pre>
 *
 * Checks that run in a database ({@code references}, {@code custom_sql}) cannot run on rows
 * streaming through a pipeline, so they are refused here.
 */
final class GateRules {

    /** The URN inline rules are recorded under: they belong to the pipeline, not to an asset. */
    static final String INLINE = "urn:kilnmark:pipeline:inline";
    static final int MAX_RULES = 100;

    /** Keys a rule may have, besides its type's own. */
    static final Set<String> COMMON = Set.of("name", "type", "field", "dimension", "fail_above", "warn_above", "unit");

    /** Each type's own keys, as written in YAML, with the name the rule keeps them under. */
    static final Map<RuleType, Map<String, String>> PARAMS = Map.of(
        RuleType.NOT_NULL, Map.of("blank_is_missing", "blankIsMissing"),
        RuleType.UNIQUE, Map.of("fields", "fields"),
        RuleType.ALLOWED_VALUES, Map.of("values", "values"),
        RuleType.MATCHES_PATTERN, Map.of("regex", "regex"),
        RuleType.IN_RANGE, Map.of("min", "min", "max", "max"),
        RuleType.FRESHNESS, Map.of("max_age_hours", "maxAgeHours"),
        RuleType.ROW_COUNT, Map.of("min", "min", "max", "max"),
        RuleType.ROW_COUNT_CHANGE, Map.of("max_change", "maxChange"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private GateRules() {
    }

    /**
     * The rules written on a gate, checked.
     *
     * @param value the {@code rules} setting as written (a list of mappings), or null
     * @param kinds the columns arriving at the gate, name to kind, or null when they are not known
     *              before the run: then a rule's column is not checked yet
     * @throws IllegalArgumentException starting {@code rules:} and naming the rule and the problem
     */
    static List<Rule> read(Object value, Map<String, String> kinds) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("rules: a list of rules, each such as { type: not_null, field: email }");
        }
        if (list.size() > MAX_RULES) {
            throw new IllegalArgumentException("rules: at most " + MAX_RULES + " rules on one gate; this has "
                + list.size());
        }
        List<Rule> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Rule r = one(list.get(i), i + 1, kinds);
            if (!names.add(r.name())) {
                throw new IllegalArgumentException("rules: rule " + (i + 1) + ": another rule is called '" + r.name()
                    + "'; give each rule its own name");
            }
            out.add(r);
        }
        return out;
    }

    private static Rule one(Object item, int n, Map<String, String> kinds) {
        if (!(item instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("rules: rule " + n + " must be a mapping, such as "
                + "{ type: not_null, field: email }");
        }
        Object typeValue = m.get("type");
        String where = "rules: rule " + n + (m.get("name") instanceof String s && !s.isBlank() ? " (" + s.strip() + ")"
            : "");
        if (!(typeValue instanceof String typeText) || typeText.isBlank()) {
            throw new IllegalArgumentException(where + ": give its type, one of not_null, unique, allowed_values, "
                + "matches_pattern, in_range, freshness, row_count or row_count_change");
        }
        RuleType type;
        try {
            type = RuleType.parse(typeText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": type '" + typeText + "' is not a check a gate runs; use one "
                + "of not_null, unique, allowed_values, matches_pattern, in_range, freshness, row_count or "
                + "row_count_change");
        }
        if (type == RuleType.REFERENCES || type == RuleType.CUSTOM_SQL) {
            throw new IllegalArgumentException(where + ": " + type.id() + " rules run in the source's database, not on "
                + "rows passing through a pipeline. Make it a rule on the asset in Quality instead");
        }
        Map<String, String> own = PARAMS.getOrDefault(type, Map.of());
        ObjectNode params = JSON.createObjectNode();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (COMMON.contains(key)) {
                continue;
            }
            String to = own.get(key);
            if (to == null) {
                List<String> allowed = new ArrayList<>(COMMON);
                allowed.addAll(own.keySet());
                java.util.Collections.sort(allowed);
                throw new IllegalArgumentException(where + ": a rule of type " + type.id() + " has no setting '" + key
                    + "'; it takes " + String.join(", ", allowed));
            }
            params.set(to, JSON.valueToTree(plain(e.getValue())));
        }
        String field = text(m.get("field"), where, "field");
        String name = text(m.get("name"), where, "name");
        String dimension = text(m.get("dimension"), where, "dimension");
        String unit = text(m.get("unit"), where, "unit");
        Double fail = number(m.get("fail_above"), where, "fail_above");
        Double warn = number(m.get("warn_above"), where, "warn_above");
        RuleDraft draft = new RuleDraft(INLINE, field, name, type.id(), dimension, params, unit, warn, fail, null, true);
        Map<String, String> fields = kinds != null ? kinds : anyColumn(type);
        try {
            Rule r = draft.check(context(fields), "pipeline");
            return new Rule(0, INLINE, r.field(), r.name(), r.type(), r.dimension(), r.params(), r.thresholdUnit(),
                r.warnAbove(), r.failAbove(), Rule.Actions.NONE, true, "pipeline", null, "pipeline", null, null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + yamlWords(e.getMessage(), kinds != null));
        }
    }

    /** RuleDraft's words for API fields, in the YAML's words. */
    private static String yamlWords(String message, boolean columnsKnown) {
        String m = message
            .replace("params.blankIsMissing", "blank_is_missing").replace("params.maxAgeHours", "max_age_hours")
            .replace("params.maxChange", "max_change").replace("params.", "").replace("failAbove", "fail_above")
            .replace("warnAbove", "warn_above").replace("thresholdUnit", "unit")
            .replace("params: give min, max or both", "give min, max or both");
        if (columnsKnown) {
            m = m.replace("the asset has no field called", "no column arrives at the gate called");
        }
        return m;
    }

    /** Numbers as plain decimals, so a rule's bound reads back exactly as written. */
    private static Object plain(Object v) {
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().scale() <= 0 ? (Object) d.toBigIntegerExact() : d;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(GateRules::plain).toList();
        }
        return v;
    }

    private static String text(Object v, String where, String key) {
        if (v == null) {
            return null;
        }
        if (v instanceof String || v instanceof Number) {
            String s = v instanceof BigDecimal d ? d.toPlainString() : v.toString();
            return s.isBlank() ? null : s.strip();
        }
        throw new IllegalArgumentException(where + ": " + key + " must be a single value");
    }

    private static Double number(Object v, String where, String key) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.valueOf(s.strip());
            } catch (NumberFormatException e) {
                // explained below
            }
        }
        throw new IllegalArgumentException(where + ": " + key + " must be a number");
    }

    /** Before the columns are known, any column will do; freshness is taken to be on a date or time. */
    private static Map<String, String> anyColumn(RuleType type) {
        return new AbstractMap<>() {
            @Override
            public boolean containsKey(Object key) {
                return true;
            }

            @Override
            public String get(Object key) {
                return type == RuleType.FRESHNESS ? "timestamp" : null;
            }

            @Override
            public Set<Map.Entry<String, String>> entrySet() {
                return Set.of();
            }
        };
    }

    private static RuleDraft.Context context(Map<String, String> fields) {
        return new RuleDraft.Context() {
            @Override
            public Map<String, String> fields() {
                return fields;
            }

            @Override
            public String sourceKind() {
                return "pipeline";
            }

            @Override
            public boolean sql() {
                return false;
            }

            @Override
            public Optional<Map<String, String>> fieldsOf(String urn) {
                return Optional.empty();
            }

            @Override
            public List<String> webhookAllowList() {
                return List.of();
            }
        };
    }

    /** A rule's settings as people write them in YAML, for messages: the type's own keys back in snake_case. */
    static Map<String, Object> describe(Rule r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", r.type().id());
        if (r.field() != null) {
            out.put("field", r.field());
        }
        JsonNode p = r.params();
        PARAMS.getOrDefault(r.type(), Map.of()).forEach((yaml, key) -> {
            if (p.has(key)) {
                out.put(yaml, p.get(key).toString());
            }
        });
        return out;
    }
}
