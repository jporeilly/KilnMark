package io.kilnmark.server.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.kilnmark.kiln.plugins.jdbc.SqlDialect;

/**
 * Rules as SQL run in the source database: one query giving the rows checked and the rows
 * failing, and one listing the failing rows. Every identifier goes through the dialect's quoting
 * and every value through its literals.
 */
public final class SqlRules {

    /** What a plan's {@link Plan#countSql()} returns. */
    public enum Shape {
        /** One row: rows checked, rows failing. */
        TOTAL_AND_FAILING,
        /** One row: the latest value (freshness). */
        LATEST,
        /** One row: the row count. */
        ROWS
    }

    /** The SQL for one rule. {@code failingSql} is null for checks on the asset as a whole. */
    public record Plan(String countSql, String failingSql, Shape shape) {
    }

    /** A table elsewhere in the source: schema and name. */
    public record Table(String schema, String name) {
    }

    private static final String TEXT_KINDS = "text";

    private SqlRules() {
    }

    /**
     * The plan for {@code rule} on {@code table}, or null when this database cannot run it (a
     * regular expression on a database without them); Kilnmark then checks it while streaming.
     *
     * @param others finds another asset's table by URN, for {@code references}
     */
    public static Plan plan(Rule rule, SqlDialect d, Table table, Map<String, String> kinds,
                            Function<String, Table> others) {
        String from = d.table(table.schema(), table.name()) + " t";
        String c = rule.field() == null ? null : "t." + d.quote(rule.field());
        JsonNode p = rule.params();
        String kind = rule.field() == null ? null : kinds.get(rule.field());
        return switch (rule.type()) {
            case NOT_NULL -> {
                String cond = c + " IS NULL";
                if (p.path("blankIsMissing").asBoolean(false) && TEXT_KINDS.equals(kind)) {
                    cond = "(" + cond + " OR LTRIM(RTRIM(" + c + ")) = '')";
                }
                yield counting(from, cond);
            }
            case ALLOWED_VALUES -> {
                List<String> values = new ArrayList<>();
                p.path("values").forEach(v -> values.add(d.literal(v.asText())));
                yield counting(from, c + " IS NOT NULL AND " + d.text(c) + " NOT IN (" + String.join(", ", values) + ")");
            }
            case MATCHES_PATTERN -> {
                String match = d.regex(c, Regexes.toPostgres(p.path("regex").asText()));
                yield match == null ? null : counting(from, c + " IS NOT NULL AND NOT (" + match + ")");
            }
            case IN_RANGE -> {
                List<String> outside = new ArrayList<>();
                if (p.hasNonNull("min")) {
                    outside.add(c + " < " + bound(d, p.get("min").asText(), kind));
                }
                if (p.hasNonNull("max")) {
                    outside.add(c + " > " + bound(d, p.get("max").asText(), kind));
                }
                yield counting(from, c + " IS NOT NULL AND (" + String.join(" OR ", outside) + ")");
            }
            case UNIQUE -> unique(rule, d, table);
            case FRESHNESS -> new Plan("SELECT MAX(" + c + ") FROM " + from, null, Shape.LATEST);
            case ROW_COUNT, ROW_COUNT_CHANGE -> new Plan("SELECT COUNT(*) FROM " + from, null, Shape.ROWS);
            case REFERENCES -> {
                Table other = others.apply(p.path("asset").asText());
                if (other == null) {
                    throw new IllegalStateException("The asset " + p.path("asset").asText() + " is no longer in the "
                        + "catalogue");
                }
                String k = d.quote("k");
                String join = from + " LEFT JOIN (SELECT DISTINCT o." + d.quote(p.path("field").asText()) + " AS " + k
                    + " FROM " + d.table(other.schema(), other.name()) + " o) r ON r." + k + " = " + c;
                String cond = c + " IS NOT NULL AND r." + k + " IS NULL";
                yield new Plan("SELECT COUNT(*), COALESCE(SUM(CASE WHEN " + cond + " THEN 1 ELSE 0 END), 0) FROM " + join,
                    "SELECT t.* FROM " + join + " WHERE " + cond, Shape.TOTAL_AND_FAILING);
            }
            case CUSTOM_SQL -> {
                String sql = placeholders(p.path("sql").asText(), d.table(table.schema(), table.name()),
                    rule.field() == null ? null : d.quote(rule.field()));
                yield new Plan("SELECT (SELECT COUNT(*) FROM " + from + "), (SELECT COUNT(*) FROM (" + sql + ") f)", sql,
                    Shape.TOTAL_AND_FAILING);
            }
        };
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\$\\{(table|column)}");

    /**
     * Custom SQL with {@code ${table}} and {@code ${column}} filled in, in one pass, so a name that
     * itself holds {@code ${column}} is not filled in again.
     */
    static String placeholders(String sql, String table, String column) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(sql);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String with = m.group(1).equals("table") ? table : column == null ? m.group() : column;
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(with));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static Plan counting(String from, String failingCondition) {
        return new Plan("SELECT COUNT(*), COALESCE(SUM(CASE WHEN " + failingCondition + " THEN 1 ELSE 0 END), 0) FROM "
            + from, "SELECT t.* FROM " + from + " WHERE " + failingCondition, Shape.TOTAL_AND_FAILING);
    }

    private static Plan unique(Rule rule, SqlDialect d, Table table) {
        List<String> keys = new ArrayList<>();
        if (rule.params().has("fields")) {
            rule.params().get("fields").forEach(n -> keys.add(n.asText()));
        } else {
            keys.add(rule.field());
        }
        String tbl = d.table(table.schema(), table.name());
        List<String> u = keys.stream().map(k -> "u." + d.quote(k)).toList();
        String notNull = String.join(" AND ", u.stream().map(x -> x + " IS NOT NULL").toList());
        String groupBy = String.join(", ", u);
        String counts = "SELECT COUNT(*) AS " + d.quote("n") + " FROM " + tbl + " u WHERE " + notNull + " GROUP BY "
            + groupBy;
        String n = "g." + d.quote("n");
        String countSql = "SELECT COALESCE(SUM(" + n + "), 0), COALESCE(SUM(CASE WHEN " + n + " > 1 THEN " + n
            + " ELSE 0 END), 0) FROM (" + counts + ") g";
        String dups = "SELECT " + String.join(", ", u) + " FROM " + tbl + " u WHERE " + notNull + " GROUP BY " + groupBy
            + " HAVING COUNT(*) > 1";
        String on = String.join(" AND ", keys.stream().map(k -> "t." + d.quote(k) + " = x." + d.quote(k)).toList());
        String failing = "SELECT t.* FROM " + tbl + " t JOIN (" + dups + ") x ON " + on;
        return new Plan(countSql, failing, Shape.TOTAL_AND_FAILING);
    }

    private static String bound(SqlDialect d, String value, String kind) {
        if (kind != null && List.of("int16", "int32", "int64", "float32", "float64", "decimal").contains(kind)) {
            return d.number(value);
        }
        return d.literal(value);
    }
}
