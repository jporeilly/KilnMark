package io.kilnmark.server.quality;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rules evaluated row by row on cell text: while streaming a file through Kiln, or against the
 * stored sample for a preview. Cells are the text {@link io.kilnmark.kiln.plugins.profile.CellText}
 * gives (ISO dates and times, plain decimals), so the same rule means the same thing here and in
 * SQL.
 */
public final class RowChecks {

    /** Distinct keys a uniqueness check holds in memory before it gives up on being exact. */
    static final int UNIQUE_LIMIT = 1_000_000;

    /**
     * What one check found. {@code error} set means it could not run: {@code error} is for every
     * role, {@code errorDetail} (the database's own message, which may quote data) only for roles
     * that may see data. {@code estimate} when the data was read through a sample of the table.
     */
    public record Evaluation(long total, long failing, String method, String failingQuery, FailingRows failingRows,
                             String message, String error, String errorDetail, boolean estimate) {

        public Evaluation(long total, long failing, String method, String failingQuery, FailingRows failingRows,
                          String message, String error) {
            this(total, failing, method, failingQuery, failingRows, message, error, null, false);
        }

        static Evaluation error(String method, String error) {
            return new Evaluation(0, 0, method, null, null, null, error, null, false);
        }

        static Evaluation error(String method, String error, String detail) {
            return new Evaluation(0, 0, method, null, null, null, error, detail, false);
        }

        Evaluation asEstimate() {
            return new Evaluation(total, failing, method, failingQuery, failingRows,
                message == null ? "Estimated from a sample of the table" : message + " (estimated from a sample of the table)",
                error, errorDetail, true);
        }
    }

    /** Longest cell kept in failing rows, in characters. */
    static final int CELL = 200;

    /** A few failing rows: column names and cell texts. */
    public record FailingRows(List<String> columns, List<List<String>> rows) {
    }

    /** One row: cells by column index, read on demand. */
    @FunctionalInterface
    public interface Row {
        String cell(int column);
    }

    /** A check in progress. */
    public interface Check {
        void row(Row row);

        Evaluation finish();
    }

    private RowChecks() {
    }

    /**
     * A check for {@code rule} over rows with {@code columns}.
     *
     * @param kinds         field name to kind
     * @param previousRows  the row count at the previous scan, for {@code row_count_change}
     * @param keepFailing   failing rows to keep (0 when the source keeps no samples)
     */
    public static Check of(Rule rule, List<String> columns, Map<String, String> kinds, Long previousRows, Instant now,
                           int keepFailing, String method) {
        int col = rule.field() == null ? -1 : columns.indexOf(rule.field());
        if (rule.type().needsField() && rule.field() != null && col < 0) {
            return fixed(Evaluation.error(method, "The data has no column " + rule.field()));
        }
        JsonNode p = rule.params();
        String kind = rule.field() == null ? null : kinds.get(rule.field());
        Failing failing = new Failing(columns, keepFailing);
        return switch (rule.type()) {
            case NOT_NULL -> {
                boolean blank = p.path("blankIsMissing").asBoolean(false);
                yield new Counting(method, failing, r -> {
                    String v = r.cell(col);
                    return v == null || (blank && v.isBlank());
                });
            }
            case ALLOWED_VALUES -> {
                Set<String> allowed = new HashSet<>();
                p.path("values").forEach(v -> allowed.add(v.asText()));
                yield new Counting(method, failing, r -> {
                    String v = r.cell(col);
                    return v != null && !allowed.contains(v);
                });
            }
            case MATCHES_PATTERN -> {
                Pattern regex = Pattern.compile(p.path("regex").asText());
                long[] unchecked = new long[1];
                Counting c = new Counting(method, failing, r -> {
                    String v = r.cell(col);
                    if (v == null) {
                        return false;
                    }
                    Boolean found = Regexes.find(regex, v);
                    if (found == null) {
                        unchecked[0]++;
                        return true; // too long or too slow to match: counted as failing, and said so
                    }
                    return !found;
                });
                c.note = () -> unchecked[0] == 0 ? null : Measures.count(unchecked[0]) + " values were too long or too "
                    + "slow to match and count as failing";
                yield c;
            }
            case IN_RANGE -> {
                Bound min = Bound.of(p.path("min"), kind);
                Bound max = Bound.of(p.path("max"), kind);
                yield new Counting(method, failing, r -> {
                    String v = r.cell(col);
                    if (v == null) {
                        return false;
                    }
                    try {
                        return (min != null && min.compareTo(v) > 0) || (max != null && max.compareTo(v) < 0);
                    } catch (RuntimeException e) {
                        return true; // a value that cannot be compared is not in range
                    }
                });
            }
            case UNIQUE -> new Unique(method, failing, keyColumns(rule, columns));
            case FRESHNESS -> new Freshness(method, col, p.path("maxAgeHours").asDouble(), now);
            case ROW_COUNT -> new RowCount(method, p, null);
            case ROW_COUNT_CHANGE -> new RowCount(method, p, previousRows);
            case REFERENCES -> fixed(Evaluation.error(method, "References are checked in the database; this source "
                + "is not one"));
            case CUSTOM_SQL -> fixed(Evaluation.error(method, "Custom SQL runs in the database; this source is not one"));
        };
    }

    private static int[] keyColumns(Rule rule, List<String> columns) {
        List<String> names = new ArrayList<>();
        if (rule.params().has("fields")) {
            rule.params().get("fields").forEach(n -> names.add(n.asText()));
        } else {
            names.add(rule.field());
        }
        return names.stream().mapToInt(columns::indexOf).toArray();
    }

    private static Check fixed(Evaluation e) {
        return new Check() {
            @Override
            public void row(Row row) {
            }

            @Override
            public Evaluation finish() {
                return e;
            }
        };
    }

    /** Keeps the first few failing rows. */
    private static final class Failing {
        final List<String> columns;
        final int keep;
        final List<List<String>> rows = new ArrayList<>();

        Failing(List<String> columns, int keep) {
            this.columns = columns;
            this.keep = keep;
        }

        void add(Row r) {
            if (rows.size() < keep) {
                List<String> cells = new ArrayList<>(columns.size());
                for (int c = 0; c < columns.size(); c++) {
                    String v = r.cell(c);
                    cells.add(v == null || v.length() <= CELL ? v : v.substring(0, CELL) + "…");
                }
                rows.add(cells);
            }
        }

        FailingRows result() {
            return keep == 0 ? null : new FailingRows(columns, List.copyOf(rows));
        }
    }

    private static final class Counting implements Check {
        private final String method;
        private final Failing failing;
        private final java.util.function.Predicate<Row> fails;
        private long total;
        private long count;
        java.util.function.Supplier<String> note = () -> null;

        Counting(String method, Failing failing, java.util.function.Predicate<Row> fails) {
            this.method = method;
            this.failing = failing;
            this.fails = fails;
        }

        @Override
        public void row(Row row) {
            total++;
            if (fails.test(row)) {
                count++;
                failing.add(row);
            }
        }

        @Override
        public Evaluation finish() {
            return new Evaluation(total, count, method, null, failing.result(), note.get(), null);
        }
    }

    private static final class Unique implements Check {
        private final String method;
        private final Failing failing;
        private final int[] key;
        private final Map<String, Integer> seen = new HashMap<>();
        private long total;
        private boolean overflow;

        Unique(String method, Failing failing, int[] key) {
            this.method = method;
            this.failing = failing;
            this.key = key;
        }

        @Override
        public void row(Row row) {
            if (overflow) {
                return;
            }
            StringBuilder b = new StringBuilder();
            for (int k : key) {
                if (k < 0) {
                    return;
                }
                String v = row.cell(k);
                if (v == null) {
                    return; // keys with a null part are not compared
                }
                b.append(v).append('\u001f');
            }
            total++;
            Integer n = seen.merge(b.toString(), 1, Integer::sum);
            if (n > 1) {
                failing.add(row);
            }
            if (seen.size() > UNIQUE_LIMIT) {
                overflow = true;
                seen.clear();
            }
        }

        @Override
        public Evaluation finish() {
            if (overflow) {
                return Evaluation.error(method, "More than " + Measures.count(UNIQUE_LIMIT) + " distinct values: too "
                    + "many to check exactly while streaming. The profile's uniqueness measure estimates it instead.");
            }
            long dup = 0;
            for (int n : seen.values()) {
                if (n > 1) {
                    dup += n;
                }
            }
            return new Evaluation(total, dup, method, null, failing.result(), null, null);
        }
    }

    private static final class Freshness implements Check {
        private final String method;
        private final int col;
        private final double maxAgeHours;
        private final Instant now;
        private Instant latest;
        private long unreadable;

        Freshness(String method, int col, double maxAgeHours, Instant now) {
            this.method = method;
            this.col = col;
            this.maxAgeHours = maxAgeHours;
            this.now = now;
        }

        @Override
        public void row(Row row) {
            String v = row.cell(col);
            if (v == null) {
                return;
            }
            Instant t = ProfileFacts.instant(v);
            if (t == null) {
                unreadable++; // counted and said, not skipped silently
            } else if (latest == null || t.isAfter(latest)) {
                latest = t;
            }
        }

        @Override
        public Evaluation finish() {
            Evaluation e = freshness(latest, maxAgeHours, now, method);
            if (unreadable == 0) {
                return e;
            }
            String note = Measures.count(unreadable) + (unreadable == 1 ? " value" : " values")
                + " could not be read as a date or time and " + (unreadable == 1 ? "was" : "were") + " left out";
            return new Evaluation(e.total(), e.failing(), e.method(), e.failingQuery(), e.failingRows(),
                e.message() == null ? note : e.message() + "; " + note, e.error());
        }
    }

    /** The freshness result for the latest value found. */
    static Evaluation freshness(Instant latest, double maxAgeHours, Instant now, String method) {
        if (latest == null) {
            return new Evaluation(1, 1, method, null, null, "No values at all", null);
        }
        Duration age = Duration.between(latest, now);
        boolean stale = age.toSeconds() > maxAgeHours * 3600;
        return new Evaluation(1, stale ? 1 : 0, method, null, null, "The latest value is " + Measures.hours(age)
            + " old", null);
    }

    private static final class RowCount implements Check {
        private final String method;
        private final JsonNode p;
        private final Long previous;
        private final boolean change;
        private long rows;

        RowCount(String method, JsonNode p, Long previous) {
            this.method = method;
            this.p = p;
            this.previous = previous;
            this.change = p.has("maxChange");
        }

        @Override
        public void row(Row row) {
            rows++;
        }

        @Override
        public Evaluation finish() {
            return change ? rowCountChange(rows, previous, p.path("maxChange").asDouble(), method)
                : rowCount(rows, p, method);
        }
    }

    static Evaluation rowCount(long rows, JsonNode p, String method) {
        boolean low = p.hasNonNull("min") && rows < p.get("min").asLong();
        boolean high = p.hasNonNull("max") && rows > p.get("max").asLong();
        return new Evaluation(1, low || high ? 1 : 0, method, null, null, Measures.count(rows) + " rows", null);
    }

    static Evaluation rowCountChange(long rows, Long previous, double maxChange, String method) {
        if (previous == null) {
            return new Evaluation(1, 0, method, null, null, "No earlier scan to compare with", null);
        }
        double change = previous == 0 ? (rows == 0 ? 0 : 1) : (double) (rows - previous) / previous;
        return new Evaluation(1, Math.abs(change) > maxChange ? 1 : 0, method, null, null, "Changed by "
            + Measures.percent(change) + " (" + Measures.count(previous) + " to " + Measures.count(rows) + ")", null);
    }

    /** A range bound compared with cell text according to the field's kind. */
    private record Bound(String text, String kind) {
        static Bound of(JsonNode n, String kind) {
            return n == null || n.isMissingNode() || n.isNull() ? null : new Bound(n.asText(), kind);
        }

        int compareTo(String v) {
            if (kind == null) {
                return text.compareTo(v);
            }
            return switch (kind) {
                case "int16", "int32", "int64", "float32", "float64", "decimal" ->
                    new BigDecimal(text).compareTo(new BigDecimal(v));
                case "date" -> LocalDate.parse(text.substring(0, 10)).compareTo(LocalDate.parse(v.substring(0, 10)));
                case "timestamp", "timestamp_tz" -> ProfileFacts.instant(text).compareTo(ProfileFacts.instant(v));
                default -> text.compareTo(v);
            };
        }
    }
}
