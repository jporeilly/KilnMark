package io.kilnmark.kiln.nodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Names;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * Which of several rows with the same key is kept: the rule {@code dedupe}, {@code lookup} (for
 * its lookup side) and an upsert {@code write} state with {@code duplicates}, and, for
 * {@code latest} and {@code earliest}, the column {@code duplicates_by} that decides.
 *
 * <ul>
 *   <li>{@code first}: the first row to arrive.</li>
 *   <li>{@code last}: the last row to arrive.</li>
 *   <li>{@code latest}: the row with the largest value of {@code duplicates_by}.</li>
 *   <li>{@code earliest}: the row with the smallest value of {@code duplicates_by}.</li>
 *   <li>{@code error}: a repeated key stops the run, naming the key's columns, never its value.</li>
 * </ul>
 *
 * <p>Ties under {@code latest} and {@code earliest} go to the first of the tied rows to arrive, and
 * an empty value never beats a value. Values order as {@code sort} orders them: numbers by value
 * across number types, dates and timestamps in time, text by Unicode code point, {@code false}
 * before {@code true}.
 */
public final class WhichRow {

    /** The setting that names the rule. */
    public static final String KEY = "duplicates";
    /** The setting that names the column {@code latest} and {@code earliest} decide by. */
    public static final String BY = "duplicates_by";

    /** The rules, as pipelines write them. */
    public enum Rule {
        FIRST, LAST, LATEST, EARLIEST, ERROR;

        /** The rule as a pipeline writes it. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether the rule decides by a column's value. */
        public boolean byColumn() {
            return this == LATEST || this == EARLIEST;
        }

        static Rule of(String word) {
            return Rule.valueOf(word.toUpperCase(Locale.ROOT));
        }
    }

    /** The rules' words, in the order the settings panel offers them. */
    public static final List<String> CHOICES = List.of("first", "last", "latest", "earliest", "error");

    /**
     * The rule and its column, as checked.
     *
     * @param rule the rule
     * @param by   the column {@code latest} and {@code earliest} decide by; null for the others
     */
    public record Settings(Rule rule, String by) {

        /** The rule in words, for a node's summary and its notes: "latest by updated_at". */
        public String words() {
            return rule.byColumn() ? rule.word() + " by " + by : rule.word();
        }
    }

    private WhichRow() {
    }

    /** The {@code duplicates} setting, with the node's own default and help. */
    public static SettingSpec setting(String fallback, String help) {
        return SettingSpec.choice(KEY, "Which row wins", false, fallback, help, CHOICES);
    }

    /** The {@code duplicates_by} setting; {@code side} says which input's column, for lookup. */
    public static SettingSpec bySetting(String side) {
        return SettingSpec.optional(BY, "Decide by column", SettingSpec.Kind.COLUMN, null,
            "For latest or earliest: the " + (side == null ? "" : side + " ") + "column whose largest (latest) "
                + "or smallest (earliest) value wins. On a tie the first of the tied rows to arrive wins, and an "
                + "empty value never beats a value");
    }

    /**
     * Reads {@code duplicates} and {@code duplicates_by}, adding problems to {@code problems} (each
     * starting with its setting's key); {@code fallback} is the node's default rule.
     */
    public static Settings read(Map<String, Object> config, String fallback, List<String> problems) {
        Object raw = config == null ? null : config.get(KEY);
        Rule rule = Rule.of(fallback);
        if (raw != null && !(raw instanceof String s && s.isBlank())) {
            String word = raw instanceof String s ? s.strip().toLowerCase(Locale.ROOT) : null;
            if (word == null || !CHOICES.contains(word)) {
                problems.add(KEY + ": one of " + String.join(", ", CHOICES));
                return new Settings(rule, null);
            } else {
                rule = Rule.of(word);
            }
        }
        Object byRaw = config == null ? null : config.get(BY);
        String by = null;
        if (byRaw != null && !(byRaw instanceof String s && s.isBlank())) {
            if (!(byRaw instanceof String s)) {
                problems.add(BY + ": one column name");
            } else {
                by = s.strip();
            }
        }
        if (rule.byColumn() && by == null && (byRaw == null || byRaw instanceof String)) {
            problems.add(BY + ": " + rule.word() + " needs the column that decides, such as updated_at");
        } else if (!rule.byColumn() && by != null) {
            problems.add(BY + ": only latest and earliest decide by a column; leave it out for " + rule.word());
        }
        return new Settings(rule, rule.byColumn() ? by : null);
    }

    /**
     * Checks that {@code by} is a column of {@code in} whose values can be ordered.
     *
     * @throws NodeException naming the problem, prefixed with {@code prefix}
     */
    public static void checkBy(Schema in, Settings s, String prefix) throws NodeException {
        if (s.by() == null || in == null) {
            return;
        }
        Field f = BaseNode.field(in, s.by());
        if (f == null) {
            String near = Names.suggest(s.by(), BaseNode.names(in));
            throw new NodeException(prefix + BY + ": unknown column '" + s.by() + "'"
                + (near != null ? "; did you mean " + near + "?" : ""));
        }
        Type t = Type.of(f.getType());
        if (t == null || t.isNull()) {
            throw new NodeException(prefix + BY + ": the column '" + s.by() + "' holds " + f.getType()
                + " values, which cannot be put in order; use a number, text, date or timestamp column");
        }
    }

    /** The message for a repeated key under {@code error}: the key's columns, never its value. */
    static String repeated(String what, List<String> keyColumns) {
        return what + " more than one row with the same " + (keyColumns == null ? "values in every column"
            : "key (" + String.join(", ", keyColumns) + ")") + "; set " + KEY + " to first, last, latest or "
            + "earliest to choose which row is kept";
    }

    /**
     * Whether {@code candidate} beats {@code best} under {@code rule} ({@code latest} or
     * {@code earliest}): strictly larger or smaller, a value always beating an empty one. A tie
     * keeps {@code best}, the row that arrived first.
     */
    static boolean beats(Rule rule, Object candidate, Object best) {
        if (candidate == null) {
            return false;
        }
        if (best == null) {
            return true;
        }
        int c = compare(candidate, best);
        return rule == Rule.LATEST ? c > 0 : c < 0;
    }

    /**
     * Orders two values of one column as {@link Col#get} gives them: numbers by value across their
     * types, text by Unicode code point, {@code false} before {@code true}; dates and timestamps
     * arrive as numbers.
     */
    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            if (x instanceof Long l && y instanceof Long m) {
                return Long.compare(l, m);
            }
            if (x instanceof Double || y instanceof Double) {
                double dx = x.doubleValue();
                double dy = y.doubleValue();
                if (Double.isNaN(dx) || Double.isNaN(dy) || Double.isInfinite(dx) || Double.isInfinite(dy)) {
                    return Double.compare(dx, dy);
                }
            }
            return decimal(x).compareTo(decimal(y));
        }
        if (a instanceof String x && b instanceof String y) {
            return codePoints(x, y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        // one column holds one kind of value; a column that changed kind part-way orders by kind
        return a.getClass().getName().compareTo(b.getClass().getName());
    }

    private static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            default -> new BigDecimal(n.toString());
        };
    }

    private static int codePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /** The values of {@code column} in a batch, as {@link #beats} compares them. */
    static Col values(VectorSchemaRoot batch, String column) {
        FieldVector v = batch.getVector(column);
        return ArrowIO.read(v);
    }

    /**
     * For an upsert: decides, batch by batch, which rows to send to the table so that it ends with
     * the winning row of each key, holding only each key and its deciding value in memory. Under
     * {@code first} a later row of a key already sent is left out; under {@code latest} and
     * {@code earliest} a row is sent only when it beats every earlier row of its key, and the
     * upsert's later write replaces the earlier one; under {@code error} a repeated key stops the
     * run. ({@code last} needs nothing: an upsert keeps the last row it is sent.) Rows with an empty
     * key column are passed on as they are, for the write to refuse.
     */
    public static final class Upserts {
        private final Settings settings;
        private final List<String> key;
        private final long limitBytes;
        private final long limitMb;
        private final Map<Object, Object> best = new HashMap<>();
        private long bytes;
        private long leftOut;
        private long replacing;

        /**
         * @param settings the rule
         * @param key      the upsert's key columns
         * @param limitMb  the most memory the keys seen may take, in MB
         */
        public Upserts(Settings settings, List<String> key, long limitMb) {
            if (settings.rule() == Rule.LAST) {
                throw new IllegalArgumentException("last needs no choosing: an upsert keeps the last row");
            }
            this.settings = settings;
            this.key = List.copyOf(key);
            this.limitMb = limitMb;
            this.limitBytes = BaseNode.megabytes(limitMb);
        }

        /** Rows left out because an earlier row of their key wins. */
        public long leftOut() {
            return leftOut;
        }

        /** Rows sent that replace an earlier row of their key in the table, because they beat it. */
        public long replacing() {
            return replacing;
        }

        /**
         * The rows of {@code batch} to write: the batch itself when every row goes, else a new
         * batch of those rows from {@code allocator}, and the original closed. Closes the batch on a
         * failure.
         *
         * @throws NodeException under {@code error} for a repeated key, or when the keys seen take
         *                       more than the memory limit
         */
        public VectorSchemaRoot choose(VectorSchemaRoot batch, BufferAllocator allocator) throws NodeException {
            int[] rows;
            int count = 0;
            int n = batch.getRowCount();
            try {
                Schema schema = batch.getSchema();
                for (String k : key) {
                    if (BaseNode.field(schema, k) == null) {
                        throw new NodeException("key: the input has no column '" + k + "'");
                    }
                }
                checkBy(schema, settings, "");
                Keys.Reader[] readers = Keys.readers(batch, key, null);
                Col by = settings.by() == null ? null : values(batch, settings.by());
                rows = new int[n];
                for (int i = 0; i < n; i++) {
                    Object k = Keys.of(readers, i, true);
                    if (k == null) {
                        rows[count++] = i;
                        continue;
                    }
                    Object value = by == null ? null : by.get(i);
                    if (!best.containsKey(k)) {
                        best.put(k, value);
                        bytes += 64 + Keys.size(k) + Keys.size(value);
                        rows[count++] = i;
                        continue;
                    }
                    switch (settings.rule()) {
                        case ERROR -> throw new NodeException(repeated("the input has", key));
                        case LATEST, EARLIEST -> {
                            Object before = best.get(k);
                            if (beats(settings.rule(), value, before)) {
                                best.put(k, value);
                                bytes += Keys.size(value) - Keys.size(before);
                                replacing++;
                                rows[count++] = i;
                            } else {
                                leftOut++;
                            }
                        }
                        default -> leftOut++;
                    }
                }
                if (bytes > limitBytes) {
                    throw new NodeException("the keys seen so far take more than " + limitMb + " MB ("
                        + BaseNode.count(best.size()) + " keys), to choose which row wins; set " + KEY + ": last, or "
                        + "choose the rows with a dedupe node before the write, which can be given more memory");
                }
            } catch (NodeException | RuntimeException e) {
                batch.close();
                throw e;
            }
            if (count == n) {
                return batch;
            }
            try {
                return BaseNode.copy(batch, rows, count, allocator);
            } finally {
                batch.close();
            }
        }
    }

    /**
     * For dedupe and lookup: the winning row of each key so far, as a reference the node chooses
     * ({@code long}, such as batch and row), with its deciding value.
     */
    static final class Winners {
        private final Settings settings;
        private final Map<Object, long[]> refs = new HashMap<>();
        private final Map<Object, Object> values = new HashMap<>();
        private long bytes;

        Winners(Settings settings) {
            this.settings = settings;
        }

        /**
         * Offers row {@code ref} with key {@code key} and deciding value {@code value} (null when
         * the rule decides by arrival). Returns the reference of the row it displaces, the row
         * itself when it loses, or -1 when it is the first row of its key.
         *
         * @throws NodeException under {@code error}, for a repeated key
         */
        long offer(Object key, long ref, Object value, String what, List<String> keyColumns) throws NodeException {
            long[] held = refs.get(key);
            if (held == null) {
                refs.put(key, new long[] {ref});
                if (settings.rule().byColumn()) {
                    values.put(key, value);
                }
                bytes += 72 + Keys.size(key) + (settings.rule().byColumn() ? Keys.size(value) : 0);
                return -1;
            }
            boolean wins = switch (settings.rule()) {
                case FIRST -> false;
                case LAST -> true;
                case LATEST, EARLIEST -> beats(settings.rule(), value, values.get(key));
                case ERROR -> throw new NodeException(repeated(what, keyColumns));
            };
            if (!wins) {
                return ref;
            }
            long before = held[0];
            held[0] = ref;
            if (settings.rule().byColumn()) {
                bytes += Keys.size(value) - Keys.size(values.put(key, value));
            }
            return before;
        }

        /** The winning row's reference for {@code key}, or null. */
        Long winner(Object key) {
            long[] held = refs.get(key);
            return held == null ? null : held[0];
        }

        int size() {
            return refs.size();
        }

        /** Roughly the memory the keys and values take, in bytes. */
        long bytes() {
            return bytes;
        }

        /** The winning references, for a node that sends the winners on at the end. */
        List<Long> all() {
            List<Long> out = new ArrayList<>(refs.size());
            refs.values().forEach(r -> out.add(r[0]));
            return out;
        }

        void clear() {
            refs.clear();
            values.clear();
        }
    }
}
