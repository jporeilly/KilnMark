package io.kilnmark.kiln;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Rows that fail at a node (ADR 0054): the {@code errors} port they go to, the four columns added
 * to them, and the node's error tolerance.
 *
 * <p>An error row is the row as it arrived, with {@code error_node}, {@code error_column},
 * {@code error_code} and {@code error_message} at the end. The message comes from a fixed list of
 * wordings and never holds a value: the value stays in its own column, under its mark (ADR 0027).
 */
public final class RowErrors {

    /** The port failing rows go to. */
    public static final String PORT = "errors";

    public static final String NODE = "error_node";
    public static final String COLUMN = "error_column";
    public static final String CODE = "error_code";
    public static final String MESSAGE = "error_message";
    /** The columns added to an error row, in order. */
    public static final List<String> COLUMNS = List.of(NODE, COLUMN, CODE, MESSAGE);

    /** The settings of the tolerance. */
    public static final String MAX_ERRORS = "max_errors";
    public static final String MAX_SHARE = "max_error_share";

    /** The message of a failure whose cause is not one of the known kinds. */
    public static final String GENERIC = "the value could not be worked out for this row";

    private RowErrors() {
    }

    /** The two tolerance settings, for a node's settings list. */
    public static List<SettingSpec> settings() {
        return List.of(
            SettingSpec.optional(MAX_ERRORS, "Most failing rows", SettingSpec.Kind.NUMBER, null,
                "With the errors port connected: stop the run when more than this many rows fail. Leave empty for "
                    + "no limit"),
            SettingSpec.optional(MAX_SHARE, "Most failing share", SettingSpec.Kind.NUMBER, null,
                "With the errors port connected: stop the run when more than this share of the rows fail, from 0 to "
                    + "1, judged when the input ends. Leave empty for no limit"));
    }

    /** The keys of {@link #settings()}. */
    public static final Set<String> KEYS = Set.of(MAX_ERRORS, MAX_SHARE);

    /** Problems with the tolerance settings, each starting with its key. */
    public static List<String> check(Map<String, Object> config) {
        List<String> problems = new ArrayList<>();
        Object count = config == null ? null : config.get(MAX_ERRORS);
        if (count != null && count(count) == null) {
            problems.add(MAX_ERRORS + ": a whole number of rows, 0 or more, such as 100");
        }
        Object share = config == null ? null : config.get(MAX_SHARE);
        if (share != null && share(share) == null) {
            problems.add(MAX_SHARE + ": a share from 0 to 1, such as 0.01 for 1%");
        }
        return problems;
    }

    private static Long count(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            long n = ((Number) v).longValue();
            return n >= 0 ? n : null;
        }
        if (v instanceof String s) {
            try {
                long n = Long.parseLong(s.strip().replace("_", ""));
                return n >= 0 ? n : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double share(Object v) {
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else if (v instanceof String s) {
            try {
                d = Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return d >= 0 && d <= 1 && !Double.isNaN(d) ? d : null;
    }

    /**
     * The columns of an error row: the input's, less any error columns it already has (a row that
     * failed before and was routed on takes the latest failure), then the four error columns.
     */
    public static Schema schema(Schema in) {
        List<Field> fields = new ArrayList<>();
        for (Field f : in.getFields()) {
            // in any case: a database or an expression that ignores case would otherwise meet two
            if (!COLUMNS.contains(f.getName().toLowerCase(Locale.ROOT))) {
                fields.add(f);
            }
        }
        for (String name : COLUMNS) {
            fields.add(Field.nullable(name, ArrowType.Utf8.INSTANCE));
        }
        return new Schema(fields);
    }

    /**
     * Why one row failed.
     *
     * @param column  the column being worked out, cast or written, or the setting of a condition
     * @param code    a fixed word, such as {@code cast_failed}
     * @param message a wording that never holds a value
     */
    public record Failure(String column, String code, String message) {
    }

    /**
     * A batch of error rows: rows {@code rows[0..count)} of {@code in}, each with its failure
     * ({@code failures[i]} for {@code rows[i]}) in the four error columns. {@code in} is unchanged.
     */
    public static VectorSchemaRoot batch(VectorSchemaRoot in, int[] rows, Failure[] failures, int count, String node,
                                         BufferAllocator allocator) {
        Schema out = schema(in.getSchema());
        List<FieldVector> vectors = new ArrayList<>();
        try {
            for (Field f : out.getFields()) {
                int at = COLUMNS.indexOf(f.getName());
                FieldVector o = f.createVector(allocator);
                vectors.add(o);
                o.setInitialCapacity(count);
                o.allocateNew();
                if (at < 0) {
                    FieldVector from = in.getVector(f.getName());
                    for (int j = 0; j < count; j++) {
                        o.copyFromSafe(rows[j], j, from);
                    }
                } else {
                    VarCharVector text = (VarCharVector) o;
                    for (int j = 0; j < count; j++) {
                        Failure fl = failures[j];
                        String v = switch (at) {
                            case 0 -> node;
                            case 1 -> fl.column();
                            case 2 -> fl.code();
                            default -> fl.message();
                        };
                        if (v == null) {
                            text.setNull(j);
                        } else {
                            text.setSafe(j, v.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                o.setValueCount(count);
            }
            return new VectorSchemaRoot(out, vectors, count);
        } catch (RuntimeException e) {
            vectors.forEach(FieldVector::close);
            throw e;
        }
    }

    /** A new batch holding rows {@code rows[0..count)} of {@code in}; {@code in} is unchanged. */
    public static VectorSchemaRoot rows(VectorSchemaRoot in, int[] rows, int count, BufferAllocator allocator) {
        List<FieldVector> out = new ArrayList<>();
        try {
            for (FieldVector v : in.getFieldVectors()) {
                FieldVector o = v.getField().createVector(allocator);
                out.add(o);
                o.setInitialCapacity(count);
                o.allocateNew();
                for (int j = 0; j < count; j++) {
                    o.copyFromSafe(rows[j], j, v);
                }
                o.setValueCount(count);
            }
            return new VectorSchemaRoot(in.getSchema(), out, count);
        } catch (RuntimeException e) {
            out.forEach(FieldVector::close);
            throw e;
        }
    }

    /**
     * One node's failing rows over the whole run, against its tolerance: {@code max_errors} is
     * checked as rows fail, since the count only grows; {@code max_error_share} when the input ends.
     */
    public static final class Tally {
        private final Long maxErrors;
        private final Double maxShare;
        private long failed;
        private long rows;

        public Tally(Long maxErrors, Double maxShare) {
            this.maxErrors = maxErrors;
            this.maxShare = maxShare;
        }

        /** The tolerance in a node's settings (already checked); none when they are absent. */
        public static Tally of(Map<String, Object> config) {
            Object c = config == null ? null : config.get(MAX_ERRORS);
            Object s = config == null ? null : config.get(MAX_SHARE);
            return new Tally(c == null ? null : RowErrors.count(c), s == null ? null : RowErrors.share(s));
        }

        /** Rows that arrived at the node. */
        public void arrived(long n) {
            rows += n;
        }

        /**
         * Rows that failed and went to the errors port.
         *
         * @throws NodeException past {@code max_errors}
         */
        public void failed(long n) throws NodeException {
            failed += n;
            if (maxErrors != null && failed > maxErrors) {
                throw new NodeException(number(failed) + " of the " + number(rows) + " rows so far failed, more than "
                    + "its " + MAX_ERRORS + " of " + number(maxErrors) + "; the run stops. The failing rows so far went "
                    + "to its errors port");
            }
        }

        /**
         * The input has ended.
         *
         * @throws NodeException past {@code max_error_share}
         */
        public void finish() throws NodeException {
            if (maxShare != null && rows > 0 && (double) failed / rows > maxShare) {
                throw new NodeException(number(failed) + " of " + number(rows) + " rows (" + percentOf((double) failed / rows)
                    + ") failed, more than its " + MAX_SHARE + " of " + percentOf(maxShare) + "; the run stops. The "
                    + "failing rows went to its errors port");
            }
        }

        public long failedRows() {
            return failed;
        }

        public long rows() {
            return rows;
        }

        /**
         * The line for the run's log once the input has ended, or null when no row failed: how many
         * rows arrived and failed, against the limits.
         */
        public String note() {
            if (failed == 0) {
                return null;
            }
            StringBuilder b = new StringBuilder(number(failed)).append(failed == 1 ? " row" : " rows")
                .append(" of ").append(number(rows)).append(" failed and went to the errors port");
            List<String> limits = new ArrayList<>();
            if (maxErrors != null) {
                limits.add(MAX_ERRORS + " " + number(maxErrors));
            }
            if (maxShare != null) {
                limits.add(MAX_SHARE + " " + percentOf(maxShare));
            }
            if (!limits.isEmpty()) {
                b.append(" (limits: ").append(String.join(", ", limits)).append(')');
            }
            return b.toString();
        }

        private static String number(long n) {
            return String.format(Locale.UK, "%,d", n);
        }

        private static String percentOf(double share) {
            return String.format(Locale.UK, "%.2f%%", share * 100);
        }
    }
}
