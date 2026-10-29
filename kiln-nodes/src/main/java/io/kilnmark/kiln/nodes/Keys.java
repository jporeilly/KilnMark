package io.kilnmark.kiln.nodes;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * Row keys for dedupe and lookup: the values of some columns, as one object with working
 * {@code equals} and {@code hashCode}. Numbers, dates and timestamps compare by value across types
 * when each key column is read as a common type ({@link Col#key}).
 */
final class Keys {

    private Keys() {
    }

    /** One key column read from a batch. */
    interface Reader {
        Object key(int row);
    }

    /**
     * A reader for one column of a batch, as {@code as}; columns expressions cannot read (binary,
     * lists) compare by their Java value when {@code as} is null.
     */
    static Reader reader(FieldVector v, Type as) {
        Type t = Type.of(v.getField().getType());
        if (t != null) {
            Col c = ArrowIO.read(v);
            Type target = as == null ? t : as;
            return row -> c.key(row, target);
        }
        return row -> {
            if (v.isNull(row)) {
                return null;
            }
            Object o = v.getObject(row);
            return o instanceof byte[] b ? ByteBuffer.wrap(b.clone()) : o;
        };
    }

    /**
     * A reader giving keys that match by value whatever the column's type within its family, so a
     * key can be stored before the other side's type is known: numbers become a Long when they are
     * whole (1, 1.0 and 1.00 are one key) and a stripped BigDecimal otherwise; dates and timestamps
     * become microseconds since 1970; text and booleans stay as they are. Callers check that the two
     * sides' types can be compared ({@link Type#common}) before matching.
     */
    static Reader universal(FieldVector v) {
        Type t = Type.of(v.getField().getType());
        if (t == null) {
            throw new IllegalArgumentException("A key column cannot hold " + v.getField().getType() + " values");
        }
        Col c = ArrowIO.read(v);
        return switch (t.kind()) {
            case INT64, BOOLEAN, UTF8, NULL -> c::get;
            case DATE -> row -> c.key(row, Type.TIMESTAMP);
            case TIMESTAMP -> c::get;
            case DECIMAL -> row -> whole((java.math.BigDecimal) c.get(row));
            case FLOAT64 -> row -> {
                Object o = c.get(row);
                if (o == null) {
                    return null;
                }
                double d = (Double) o;
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return d;
                }
                return whole(java.math.BigDecimal.valueOf(d));
            };
        };
    }

    private static Object whole(java.math.BigDecimal d) {
        if (d == null) {
            return null;
        }
        if (d.signum() == 0) {
            return 0L;
        }
        java.math.BigDecimal s = d.stripTrailingZeros();
        if (s.scale() <= 0 && s.precision() - s.scale() <= 19) {
            try {
                return s.longValueExact();
            } catch (ArithmeticException e) {
                return s; // too large for a Long; no whole-number column can hold it either
            }
        }
        return s;
    }

    /** Readers for several columns; {@code as} gives each one's common type, or is null. */
    static Reader[] readers(VectorSchemaRoot batch, List<String> columns, List<Type> as) {
        Reader[] r = new Reader[columns.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = reader(batch.getVector(columns.get(i)), as == null ? null : as.get(i));
        }
        return r;
    }

    /** The key of a row; {@code nullIfAnyNull} gives null when a part is null (lookups never match nulls). */
    static Object of(Reader[] readers, int row, boolean nullIfAnyNull) {
        if (readers.length == 1) {
            return readers[0].key(row);
        }
        Object[] parts = new Object[readers.length];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = readers[i].key(row);
            if (nullIfAnyNull && parts[i] == null) {
                return null;
            }
        }
        return Arrays.asList(parts);
    }

    /** Roughly the memory a key takes, in bytes. */
    static long size(Object key) {
        return switch (key) {
            case null -> 8;
            case String s -> 48 + 2L * s.length();
            case List<?> l -> {
                long n = 32 + 8L * l.size();
                for (Object o : l) {
                    n += size(o);
                }
                yield n;
            }
            case ByteBuffer b -> 64 + b.capacity();
            default -> 32;
        };
    }
}
