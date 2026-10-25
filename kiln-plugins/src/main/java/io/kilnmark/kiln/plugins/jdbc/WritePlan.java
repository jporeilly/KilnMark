package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.TextForm;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * How the columns arriving at a database writer meet the target table's columns: which input
 * column goes to which table column, and how a value is converted on the way. Checked before any
 * row is sent, so an append or upsert that cannot work fails at once with every mismatched column
 * named.
 *
 * <p>An input column goes to the table column of the same name (exactly, or ignoring case when
 * only one matches). It fits when the table column can hold every value of its kind: the same
 * kind; a wider integer, a decimal or a float; float32 to float64; a date into a timestamp; a
 * timestamp with or without a zone into either (times without a zone are taken as UTC); and
 * anything except bytes into text. Integers and decimals go into any decimal column: where its
 * precision or scale is narrower, each value is checked as it is written ({@link #fitDecimal}) and
 * one that would need rounding or more digits stops the write, naming the row. Table columns the
 * input lacks must allow nulls or have a default.
 */
final class WritePlan {

    private WritePlan() {
    }

    /**
     * One column of the target table, as the database describes it.
     *
     * @param precision the declared precision (decimals) or length (text); 0 when not declared
     * @param maxLength the most characters a text column holds; 0 when unlimited or not text
     */
    record TargetColumn(String name, String typeName, Kind kind, int precision, int scale, int maxLength) {

        /** As people read it: {@code numeric(10,2)}, {@code nvarchar(50)}, {@code integer}. */
        String described() {
            if (kind == Kind.DECIMAL && precision > 0) {
                return typeName + "(" + precision + "," + scale + ")";
            }
            if (maxLength > 0) {
                return typeName + "(" + maxLength + ")";
            }
            return typeName;
        }

        /**
         * A decimal column declared without precision (PostgreSQL's plain {@code numeric}): it holds
         * any value. Any declared precision, beyond 38 digits too, is checked value by value.
         */
        boolean unconstrainedDecimal() {
            return kind == Kind.DECIMAL && precision <= 0;
        }
    }

    /**
     * An input column going to a table column.
     *
     * @param index     the input column's position in each batch
     * @param target    the table column's name
     * @param kind      the kind the value is converted to and bound as
     * @param maxLength the most characters the table column holds; 0 when unlimited
     * @param key       whether it is an upsert key column
     * @param precision for a decimal column with declared digits, its precision; 0 otherwise
     * @param scale     for such a column, its scale
     */
    record Binding(int index, WriteColumn input, String target, Kind kind, int maxLength, boolean key, int precision,
                   int scale) {

        Binding(int index, WriteColumn input, String target, Kind kind, int maxLength, boolean key) {
            this(index, input, target, kind, maxLength, key, 0, 0);
        }
    }

    /** The bindings, or the problems that stop the write. */
    record Plan(List<Binding> bindings, List<String> problems) {
    }

    /** Every input column to a column of its own name and kind, for a table created from the input. */
    static List<Binding> identity(List<WriteColumn> input, int textLength) {
        List<Binding> out = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            WriteColumn c = input.get(i);
            out.add(new Binding(i, c, c.name(), c.kind(), c.kind() == Kind.TEXT ? Math.max(0, textLength) : 0, false));
        }
        return out;
    }

    /**
     * Matches the input to an existing table.
     *
     * @param required table columns that need a value (not null, no default, not generated)
     * @param key      upsert key columns, by the input's names; empty otherwise
     */
    static Plan plan(List<WriteColumn> input, List<TargetColumn> table, Set<String> required, List<String> key) {
        List<Binding> bindings = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        List<String> matchedBy = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            WriteColumn c = input.get(i);
            TargetColumn t = find(table, c.name());
            if (t == null) {
                problems.add("the table has no column " + c.name());
                continue;
            }
            int earlier = matched.indexOf(t.name());
            if (earlier >= 0) {
                problems.add("the input's columns " + matchedBy.get(earlier) + " and " + c.name()
                    + " would both go to the table's column " + t.name());
                continue;
            }
            matched.add(t.name());
            matchedBy.add(c.name());
            String why = misfit(c, t);
            if (why != null) {
                problems.add(why);
                continue;
            }
            boolean declared = t.kind() == Kind.DECIMAL && !t.unconstrainedDecimal();
            bindings.add(new Binding(i, c, t.name(), t.kind(), t.maxLength(), containsName(key, c.name()),
                declared ? t.precision() : 0, declared ? t.scale() : 0));
        }
        for (TargetColumn t : table) {
            if (required.contains(t.name()) && !matched.contains(t.name())) {
                problems.add("the table's column " + t.name() + " needs a value (it is NOT NULL with no default), "
                    + "but the input has no such column");
            }
        }
        for (String k : key) {
            if (findInput(input, k) == null) {
                problems.add("the key column " + k + " is not in the input");
            }
        }
        return new Plan(bindings, problems);
    }

    private static TargetColumn find(List<TargetColumn> table, String name) {
        for (TargetColumn t : table) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        TargetColumn only = null;
        for (TargetColumn t : table) {
            if (t.name().equalsIgnoreCase(name)) {
                if (only != null) {
                    return null; // two columns differ only in case: the name must match exactly
                }
                only = t;
            }
        }
        return only;
    }

    private static WriteColumn findInput(List<WriteColumn> input, String name) {
        for (WriteColumn c : input) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static boolean containsName(List<String> names, String name) {
        return names.contains(name);
    }

    /** Why {@code c} cannot go into {@code t}, or null when it fits. */
    static String misfit(WriteColumn c, TargetColumn t) {
        String in = c.kind() == Kind.DECIMAL ? "decimal(" + c.precision() + "," + c.scale() + ")" : c.kindName();
        String problem = "the input's column " + c.name() + " is " + in + " but the table's column " + t.name()
            + " is " + t.described();
        Kind from = c.kind();
        Kind to = t.kind();
        if (to == Kind.TEXT) {
            return from == Kind.BINARY ? problem + ", and bytes are not written as text" : null;
        }
        boolean fits = switch (from) {
            case BOOLEAN -> to == Kind.BOOLEAN;
            case INT16, INT32, INT64 -> {
                int digits = from == Kind.INT16 ? 5 : from == Kind.INT32 ? 10 : 19;
                yield switch (to) {
                    case INT16 -> from == Kind.INT16;
                    case INT32 -> from != Kind.INT64;
                    case INT64 -> true;
                    case FLOAT32 -> from == Kind.INT16;
                    case FLOAT64 -> from != Kind.INT64;
                    case DECIMAL -> true; // values too large are refused one by one (decimalProblem)
                    default -> false;
                };
            }
            case FLOAT32 -> to == Kind.FLOAT32 || to == Kind.FLOAT64;
            case FLOAT64 -> to == Kind.FLOAT64;
            // a narrower decimal is checked value by value, never rounded (decimalProblem)
            case DECIMAL -> to == Kind.DECIMAL;
            case TEXT -> false;
            case BINARY -> to == Kind.BINARY;
            case DATE -> to == Kind.DATE || to == Kind.TIMESTAMP || to == Kind.TIMESTAMP_TZ;
            case TIME -> to == Kind.TIME;
            case TIMESTAMP, TIMESTAMP_TZ -> to == Kind.TIMESTAMP || to == Kind.TIMESTAMP_TZ;
        };
        if (fits) {
            return null;
        }
        return problem;
    }

    /**
     * {@code value} as a decimal column of {@code precision} and {@code scale} stores it, or null when
     * it does not fit: more decimal places than the scale (other than zeros), or more digits before
     * the point than {@code precision - scale}. Nothing is rounded.
     */
    static java.math.BigDecimal fitDecimal(java.math.BigDecimal value, int precision, int scale) {
        java.math.BigDecimal exact;
        try {
            exact = value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            return null;
        }
        return exact.precision() - exact.scale() > precision - scale && exact.signum() != 0 ? null : exact;
    }

    /** Why {@code value} does not fit a decimal column, for the "Row N" message. */
    static String decimalProblem(java.math.BigDecimal value, int precision, int scale) {
        try {
            value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            return scale < 0 ? "is not a whole multiple of " + java.math.BigDecimal.ONE.scaleByPowerOfTen(-scale)
                .toPlainString() + ", as the column's scale of " + scale + " needs"
                : "has more decimal places than the column's " + scale;
        }
        return "has more digits before the point than the column's " + (precision - scale);
    }

    /** {@code value}, of kind {@code from}, as kind {@code to} (a binding the plan accepted). */
    static Object convert(Object value, Kind from, Kind to) {
        if (value == null || from == to) {
            return value;
        }
        return switch (to) {
            case TEXT -> TextForm.of(value);
            case INT16, INT32, INT64 -> value; // integers are Long whatever their width
            case FLOAT32 -> ((Number) value).floatValue();
            case FLOAT64 -> value instanceof Float f ? Double.parseDouble(Float.toString(f)) : ((Number) value).doubleValue();
            case DECIMAL -> value instanceof BigDecimal d ? d : BigDecimal.valueOf(((Number) value).longValue());
            case TIMESTAMP -> switch (value) {
                case LocalDate d -> d.atStartOfDay();
                case Instant i -> LocalDateTime.ofInstant(i, ZoneOffset.UTC);
                default -> value;
            };
            case TIMESTAMP_TZ -> switch (value) {
                case LocalDate d -> d.atStartOfDay().toInstant(ZoneOffset.UTC);
                case LocalDateTime t -> t.toInstant(ZoneOffset.UTC);
                default -> value;
            };
            default -> value;
        };
    }

    /** The problems as one message, for an append or upsert to {@code table}. */
    static String message(String mode, TableName table, List<String> problems) {
        return "Cannot " + mode.toLowerCase(Locale.ROOT) + " to " + table + ": its columns do not match the input: "
            + String.join("; ", problems) + ". Rename or cast columns with a select step first, or write to a new "
            + "table with mode create";
    }
}
