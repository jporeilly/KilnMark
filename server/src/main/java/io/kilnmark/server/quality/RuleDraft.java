package io.kilnmark.server.quality;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.Urn;

/**
 * A rule as sent to the API, before it is checked. {@link #check} returns it cleaned up, or
 * throws {@link IllegalArgumentException} naming the first problem ({@code "params.regex: ..."}).
 */
public record RuleDraft(String assetUrn, String field, String name, String type, String dimension, JsonNode params,
                        String thresholdUnit, Double warnAbove, Double failAbove, ActionsDraft actions,
                        Boolean enabled) {

    /** Actions as sent: a webhook URL and whether to open a steward task. */
    public record ActionsDraft(String webhook, Boolean task) {
    }

    /** What checking needs to know about the asset and the server. */
    public interface Context {
        /** The asset's fields, name to kind (text, int64, date, ...). */
        Map<String, String> fields();

        /** The source's kind: {@code postgresql}, {@code files}, ... */
        String sourceKind();

        /** Whether rules run in the source's database (custom SQL needs it). */
        boolean sql();

        /** Fields of another asset, for {@code references}; empty if there is no such asset. */
        Optional<Map<String, String>> fieldsOf(String urn);

        /** URL prefixes webhooks may call; set by the administrator. */
        List<String> webhookAllowList();

        /** Whether the administrator turned custom SQL rules on ({@code kilnmark.quality.custom-sql}). */
        default boolean customSqlEnabled() {
            return false;
        }
    }

    static final int MAX_VALUES = 1000;
    static final int MAX_SQL = 10_000;
    private static final Set<String> TEMPORAL = Set.of("date", "timestamp", "timestamp_tz");
    private static final Set<String> NUMERIC = Set.of("int16", "int32", "int64", "float32", "float64", "decimal");
    /** The rule this draft describes, checked; {@code id}, dates and author are left for the store. */
    public Rule check(Context context, String author) {
        if (assetUrn == null || assetUrn.isBlank()) {
            throw new IllegalArgumentException("assetUrn: required");
        }
        RuleType t = RuleType.parse(type);
        Dimension d = dimension == null || dimension.isBlank() ? t.dimension() : Dimension.parse(dimension);
        String f = field == null || field.isBlank() ? null : field.trim();
        if (t.needsField() && f == null && !(t == RuleType.UNIQUE && params != null && params.has("fields"))) {
            throw new IllegalArgumentException("field: required for a " + t.id() + " rule");
        }
        if (f != null && !context.fields().containsKey(f)) {
            throw new IllegalArgumentException("field: the asset has no field called " + f);
        }
        String n = name == null || name.isBlank() ? (f == null ? "" : f + " ") + t.id().replace('_', ' ') : name.trim();
        if (n.length() > 200) {
            throw new IllegalArgumentException("name: at most 200 characters");
        }
        ObjectNode p = params(t, f, context);
        String unit = thresholdUnit == null || thresholdUnit.isBlank() ? "share" : thresholdUnit.trim();
        if (!unit.equals("share") && !unit.equals("count")) {
            throw new IllegalArgumentException("thresholdUnit: share or count");
        }
        double fail = failAbove == null ? 0 : failAbove;
        Double warn = warnAbove;
        if (t.wholeAsset()) {
            unit = "count";
            fail = 0;
            warn = null;
        }
        check(unit, "failAbove", fail);
        if (warn != null) {
            check(unit, "warnAbove", warn);
            if (warn > fail) {
                throw new IllegalArgumentException("warnAbove: must not be above failAbove");
            }
        }
        Rule.Actions a = actions(context);
        return new Rule(0, assetUrn, f, n, t, d, p, unit, warn, fail, a, enabled == null || enabled, "kilnmark", null,
            author, null, null);
    }

    private static void check(String unit, String name, double v) {
        if (Double.isNaN(v) || v < 0 || (unit.equals("share") && v > 1)) {
            throw new IllegalArgumentException(name + ": " + (unit.equals("share")
                ? "a share between 0 and 1, for example 0.05 for 5%" : "a count of rows, 0 or more"));
        }
    }

    private Rule.Actions actions(Context context) {
        if (actions == null) {
            return Rule.Actions.NONE;
        }
        String hook = actions.webhook() == null || actions.webhook().isBlank() ? null : actions.webhook().trim();
        if (hook != null && !allowed(hook, context.webhookAllowList())) {
            throw new IllegalArgumentException("actions.webhook: " + hook + " is not on this server's webhook "
                + "allow-list. Ask an administrator to add it to kilnmark.quality.webhook-allow-list.");
        }
        return new Rule.Actions(hook, Boolean.TRUE.equals(actions.task()));
    }

    /**
     * True when {@code url} is an http(s) URL starting with one of the allowed prefixes. The
     * comparison is on the parsed URL, so {@code https://hooks.example.com@evil.example/} does
     * not pass for {@code https://hooks.example.com/}, and paths with dot segments or encoded dots
     * and slashes are refused ({@link #plainPath}).
     */
    public static boolean allowed(String url, List<String> allowList) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (u.getScheme() == null || u.getHost() == null || u.getRawUserInfo() != null || u.getRawFragment() != null
            || !(u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https"))) {
            return false;
        }
        if (!plainPath(u.getRawPath())) {
            return false;
        }
        String normal = u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
            + (u.getPort() >= 0 ? ":" + u.getPort() : "") + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/"
            : u.getRawPath());
        for (String entry : allowList) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            URI a;
            try {
                a = URI.create(e);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (a.getScheme() == null || a.getHost() == null) {
                continue;
            }
            String prefix = a.getScheme().toLowerCase(Locale.ROOT) + "://" + a.getHost().toLowerCase(Locale.ROOT)
                + (a.getPort() >= 0 ? ":" + a.getPort() : "")
                + (a.getRawPath() == null || a.getRawPath().isEmpty() ? "/" : a.getRawPath());
            if (normal.startsWith(prefix) && (prefix.endsWith("/") || normal.length() == prefix.length()
                || normal.charAt(prefix.length()) == '/')) {
                return true;
            }
        }
        return false;
    }

    /**
     * A path with no dot segments ({@code .} or {@code ..}) and no encoded dots, slashes or
     * backslashes, so it cannot climb out of an allowed prefix once the receiving server
     * normalises it.
     */
    static boolean plainPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return true;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\")) {
            return false;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode params(RuleType t, String f, Context context) {
        ObjectNode in = params != null && params.isObject() ? (ObjectNode) params : JsonNodeFactory.instance.objectNode();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        String kind = f == null ? null : context.fields().get(f);
        switch (t) {
            case NOT_NULL -> out.put("blankIsMissing", in.path("blankIsMissing").asBoolean(false));
            case UNIQUE -> {
                if (in.has("fields")) {
                    List<String> names = strings(in.get("fields"), "params.fields");
                    if (names.isEmpty()) {
                        throw new IllegalArgumentException("params.fields: one or more field names");
                    }
                    for (String name : names) {
                        if (!context.fields().containsKey(name)) {
                            throw new IllegalArgumentException("params.fields: the asset has no field called " + name);
                        }
                    }
                    out.putArray("fields").addAll(names.stream().map(JsonNodeFactory.instance::textNode).toList());
                }
            }
            case ALLOWED_VALUES -> {
                List<String> values = strings(in.get("values"), "params.values");
                if (values.isEmpty() || values.size() > MAX_VALUES) {
                    throw new IllegalArgumentException("params.values: 1 to " + MAX_VALUES + " allowed values");
                }
                out.putArray("values").addAll(new ArrayList<>(new java.util.LinkedHashSet<>(values)).stream()
                    .map(JsonNodeFactory.instance::textNode).toList());
            }
            case MATCHES_PATTERN -> {
                String regex = in.path("regex").asText("");
                if (regex.isBlank() || regex.length() > 1000) {
                    throw new IllegalArgumentException("params.regex: a regular expression of 1 to 1000 characters");
                }
                String problem = Regexes.problem(regex);
                if (problem != null) {
                    throw new IllegalArgumentException("params.regex: " + problem);
                }
                out.put("regex", regex);
            }
            case IN_RANGE -> {
                if (!in.hasNonNull("min") && !in.hasNonNull("max")) {
                    throw new IllegalArgumentException("params: give min, max or both");
                }
                for (String key : List.of("min", "max")) {
                    if (in.hasNonNull(key)) {
                        out.put(key, bound(in.get(key), kind, "params." + key));
                    }
                }
            }
            case FRESHNESS -> {
                if (kind == null || !TEMPORAL.contains(kind)) {
                    throw new IllegalArgumentException("field: freshness needs a date or timestamp field");
                }
                double hours = in.path("maxAgeHours").asDouble(-1);
                if (!(hours > 0)) {
                    throw new IllegalArgumentException("params.maxAgeHours: how old the latest value may be, in hours");
                }
                out.put("maxAgeHours", hours);
            }
            case ROW_COUNT -> {
                if (!in.hasNonNull("min") && !in.hasNonNull("max")) {
                    throw new IllegalArgumentException("params: give min, max or both");
                }
                for (String key : List.of("min", "max")) {
                    if (in.hasNonNull(key)) {
                        if (!in.get(key).canConvertToLong() || in.get(key).asLong() < 0) {
                            throw new IllegalArgumentException("params." + key + ": a whole number of rows");
                        }
                        out.put(key, in.get(key).asLong());
                    }
                }
            }
            case ROW_COUNT_CHANGE -> {
                double max = in.path("maxChange").asDouble(-1);
                if (!(max > 0)) {
                    throw new IllegalArgumentException("params.maxChange: the largest change allowed, as a share "
                        + "(0.2 for 20%)");
                }
                out.put("maxChange", max);
            }
            case REFERENCES -> {
                String target = in.path("asset").asText("");
                String targetField = in.path("field").asText("");
                Urn mine = Urn.parse(assetUrn);
                Urn theirs;
                try {
                    theirs = Urn.parse(target);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("params.asset: the URN of the asset referred to");
                }
                if (!theirs.source().equals(mine.source())) {
                    throw new IllegalArgumentException("params.asset: must be in the same source as the rule's asset");
                }
                Map<String, String> fields = context.fieldsOf(target).orElseThrow(
                    () -> new IllegalArgumentException("params.asset: no asset " + target));
                if (!fields.containsKey(targetField)) {
                    throw new IllegalArgumentException("params.field: " + target + " has no field called " + targetField);
                }
                if (!context.sql()) {
                    throw new IllegalArgumentException("type: references rules run in the database, so they need a "
                        + "database source");
                }
                out.put("asset", target);
                out.put("field", targetField);
            }
            case CUSTOM_SQL -> {
                if (!context.customSqlEnabled()) {
                    throw new IllegalArgumentException("type: custom SQL rules are turned off on this server. An "
                        + "administrator can turn them on with kilnmark.quality.custom-sql=true.");
                }
                if (!"postgresql".equals(context.sourceKind())) {
                    throw new IllegalArgumentException("type: custom SQL rules run only on PostgreSQL sources, where "
                        + "Kilnmark can hold them to a read-only transaction. " + (context.sql()
                        ? "This database has no read-only transactions, so custom SQL could change data there. Use the "
                            + "library's checks instead." : "This source is not a database."));
                }
                out.put("sql", customSql(in.path("sql").asText("")));
            }
        }
        return out;
    }

    /** Checks custom SQL with {@link SqlTokens#checkCustomSql}; see there for what is refused. */
    static String customSql(String sql) {
        return SqlTokens.checkCustomSql(sql, MAX_SQL);
    }

    private static String bound(JsonNode v, String kind, String name) {
        String text = v.asText();
        if (kind != null && NUMERIC.contains(kind)) {
            if (!v.isNumber() && !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                throw new IllegalArgumentException(name + ": a number");
            }
            return v.isNumber() ? v.decimalValue().toPlainString() : text;
        }
        if (kind != null && TEMPORAL.contains(kind)) {
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[T ][0-9:.]+(?:Z|[+-][0-9:]+)?)?")) {
                throw new IllegalArgumentException(name + ": a date or timestamp, for example 2024-01-31");
            }
            return text;
        }
        if (text.isEmpty() || text.length() > 200) {
            throw new IllegalArgumentException(name + ": 1 to 200 characters");
        }
        return text;
    }

    private static List<String> strings(JsonNode node, String name) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(name + ": a list of text values");
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode v : node) {
            if (v.isNull() || v.isContainerNode()) {
                throw new IllegalArgumentException(name + ": text values only");
            }
            String s = v.asText();
            if (s.length() > 500) {
                throw new IllegalArgumentException(name + ": each value at most 500 characters");
            }
            if (seen.add(s)) {
                out.add(s);
            }
        }
        return out;
    }

}
