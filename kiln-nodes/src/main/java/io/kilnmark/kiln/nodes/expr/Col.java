package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;

/**
 * A column of values while an expression is worked out over one batch: plain Java arrays, one per
 * kind of value, and a null flag per row. Booleans in {@code bools}; whole numbers, dates (days
 * since 1970-01-01) and timestamps (microseconds since 1970-01-01T00:00Z) in {@code longs};
 * floating point in {@code doubles}; text (String) and decimals (BigDecimal) in {@code objects}.
 * A column of type {@code null} has every row null.
 */
public final class Col {

    final Type type;
    final int rows;
    final boolean[] nulls;
    final boolean[] bools;
    final long[] longs;
    final double[] doubles;
    final Object[] objects;

    private Col(Type type, int rows) {
        this.type = type;
        this.rows = rows;
        this.nulls = new boolean[rows];
        this.bools = type.kind() == Type.Kind.BOOLEAN ? new boolean[rows] : null;
        this.longs = switch (type.kind()) {
            case INT64, DATE, TIMESTAMP -> new long[rows];
            default -> null;
        };
        this.doubles = type.kind() == Type.Kind.FLOAT64 ? new double[rows] : null;
        this.objects = type.kind() == Type.Kind.UTF8 || type.kind() == Type.Kind.DECIMAL ? new Object[rows] : null;
    }

    /** A column of {@code rows} values, all null until set. */
    public static Col nulls(Type type, int rows) {
        Col c = new Col(type, rows);
        java.util.Arrays.fill(c.nulls, true);
        return c;
    }

    /** A column of {@code rows} values, all set (not null) to the type's zero until set. */
    static Col blank(Type type, int rows) {
        Col c = new Col(type, rows);
        if (type.isNull()) {
            java.util.Arrays.fill(c.nulls, true);
        }
        return c;
    }

    public Type type() {
        return type;
    }

    public int rows() {
        return rows;
    }

    public boolean isNull(int i) {
        return nulls[i];
    }

    /**
     * The value at row {@code i}, boxed: Boolean, Long (also dates and timestamps), Double,
     * BigDecimal or String; null for a null.
     */
    public Object get(int i) {
        if (nulls[i]) {
            return null;
        }
        return switch (type.kind()) {
            case NULL -> null;
            case BOOLEAN -> bools[i];
            case INT64, DATE, TIMESTAMP -> longs[i];
            case FLOAT64 -> doubles[i];
            case DECIMAL, UTF8 -> objects[i];
        };
    }

    /** Sets row {@code i} from a boxed value as {@link #get} returns it; null makes the row null. */
    public void set(int i, Object v) {
        if (v == null || type.isNull()) {
            nulls[i] = true;
            return;
        }
        nulls[i] = false;
        switch (type.kind()) {
            case BOOLEAN -> bools[i] = (Boolean) v;
            case INT64, DATE, TIMESTAMP -> longs[i] = ((Number) v).longValue();
            case FLOAT64 -> doubles[i] = ((Number) v).doubleValue();
            case DECIMAL -> objects[i] = (BigDecimal) v;
            case UTF8 -> objects[i] = v.toString();
            default -> nulls[i] = true;
        }
    }

    /**
     * This column converted to {@code to}, as {@code cast(x as to)} converts it; this column itself
     * when it already has that type.
     *
     * @throws ExprException if a value cannot be converted
     * @throws IllegalArgumentException if no value of this type can be ({@link Type#canCast})
     */
    public Col castTo(Type to) throws ExprException {
        if (!Casts.canCast(type, to)) {
            throw new IllegalArgumentException("Cannot cast " + type + " to " + to);
        }
        return Casts.convert(this, to, 0);
    }

    /**
     * Row {@code i} as a key for matching and grouping, with numbers, dates and timestamps made
     * comparable across types once both sides are seen as {@code as} (from {@link Type#common}):
     * {@code 1}, {@code 1.0} and {@code 1.00} are one key as decimals. Null for a null.
     */
    public Object key(int i, Type as) {
        if (nulls[i]) {
            return null;
        }
        return switch (as.kind()) {
            case DECIMAL -> {
                BigDecimal d = type.kind() == Type.Kind.INT64 ? BigDecimal.valueOf(longs[i]) : (BigDecimal) objects[i];
                yield d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
            }
            case FLOAT64 -> {
                double d = switch (type.kind()) {
                    case INT64 -> longs[i];
                    case DECIMAL -> ((BigDecimal) objects[i]).doubleValue();
                    default -> doubles[i];
                };
                yield d == 0.0 ? 0.0 : d;
            }
            case TIMESTAMP -> {
                if (type.kind() != Type.Kind.DATE) {
                    yield longs[i];
                }
                try {
                    yield Math.multiplyExact(longs[i], Temporal.MICROS_PER_DAY);
                } catch (ArithmeticException e) {
                    // too far away for a timestamp, so no timestamp can match it; keep it distinct and exact
                    yield java.math.BigInteger.valueOf(longs[i]).multiply(java.math.BigInteger.valueOf(Temporal.MICROS_PER_DAY));
                }
            }
            default -> get(i);
        };
    }

    /** Whether row {@code i} is a boolean true (not false, not null). */
    public boolean isTrue(int i) {
        return !nulls[i] && bools[i];
    }

    /** Copies row {@code from} of {@code src}, which has the same type, into row {@code to}. */
    void copy(Col src, int from, int to) {
        nulls[to] = src.nulls[from];
        if (nulls[to]) {
            return;
        }
        switch (type.kind()) {
            case BOOLEAN -> bools[to] = src.bools[from];
            case INT64, DATE, TIMESTAMP -> longs[to] = src.longs[from];
            case FLOAT64 -> doubles[to] = src.doubles[from];
            case DECIMAL, UTF8 -> objects[to] = src.objects[from];
            default -> {
                // null type: nothing to copy
            }
        }
    }
}
