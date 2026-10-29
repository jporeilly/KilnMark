package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.FloatingPointVector;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/** Moves values between Arrow vectors and {@link Col}s. */
public final class ArrowIO {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private ArrowIO() {
    }

    /**
     * Reads a whole vector as a column of its expression type ({@link Type#of}).
     *
     * @throws IllegalArgumentException if expressions cannot use the vector's type
     */
    public static Col read(FieldVector v) {
        Type type = Type.of(v.getField().getType());
        if (type == null) {
            throw new IllegalArgumentException("Expressions cannot read a column of type " + v.getField().getType());
        }
        int n = v.getValueCount();
        Col c = Col.blank(type, n);
        if (type.isNull() || v instanceof NullVector) {
            java.util.Arrays.fill(c.nulls, true);
            return c;
        }
        for (int i = 0; i < n; i++) {
            c.nulls[i] = v.isNull(i);
        }
        switch (type.kind()) {
            case BOOLEAN -> {
                BitVector b = (BitVector) v;
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.bools[i] = b.get(i) != 0;
                    }
                }
            }
            case INT64 -> {
                BaseIntVector b = (BaseIntVector) v;
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.longs[i] = b.getValueAsLong(i);
                    }
                }
            }
            case FLOAT64 -> {
                FloatingPointVector f = (FloatingPointVector) v;
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.doubles[i] = f.getValueAsDouble(i);
                    }
                }
            }
            case DECIMAL -> {
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.objects[i] = v instanceof DecimalVector d ? d.getObject(i) : ((Decimal256Vector) v).getObject(i);
                    }
                }
            }
            case UTF8 -> {
                VariableWidthFieldVector s = (VariableWidthFieldVector) v;
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.objects[i] = new String(s.get(i), StandardCharsets.UTF_8);
                    }
                }
            }
            case DATE -> {
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        c.longs[i] = v instanceof DateDayVector d ? d.get(i)
                            : Math.floorDiv(((DateMilliVector) v).get(i), MILLIS_PER_DAY);
                    }
                }
            }
            case TIMESTAMP -> {
                TimeStampVector t = (TimeStampVector) v;
                TimeUnit unit = ((ArrowType.Timestamp) v.getField().getType()).getUnit();
                for (int i = 0; i < n; i++) {
                    if (!c.nulls[i]) {
                        long raw = t.get(i);
                        c.longs[i] = switch (unit) {
                            case SECOND -> Math.multiplyExact(raw, 1_000_000L);
                            case MILLISECOND -> Math.multiplyExact(raw, 1_000L);
                            case MICROSECOND -> raw;
                            case NANOSECOND -> Math.floorDiv(raw, 1_000L);
                        };
                    }
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
        return c;
    }

    /**
     * Writes a column into a new vector for {@code field}, allocated from {@code allocator}. The
     * field's type must be one expressions use; the column is widened or cast to it first (so a
     * whole-number column can be written into an int32 field, for example, as group keys are).
     *
     * @throws ExprException if a value does not fit the field, such as a decimal with too many digits
     */
    public static FieldVector write(Col col, Field field, BufferAllocator allocator) throws ExprException {
        Type target = Type.of(field.getType());
        if (target == null) {
            throw new IllegalArgumentException("Expressions cannot write a column of type " + field.getType());
        }
        Col c = col.type.equals(target) || target.kind() == Type.Kind.DECIMAL && col.type.kind() == Type.Kind.DECIMAL
            ? col : Casts.convert(col, target, 0);
        int n = c.rows;
        FieldVector v = field.createVector(allocator);
        try {
            v.setInitialCapacity(n);
            v.allocateNew();
            if (!(v instanceof NullVector)) {
                fill(c, v, field, n);
            }
            v.setValueCount(n);
            return v;
        } catch (ArithmeticException e) {
            v.close();
            throw new ExprException("a date or timestamp is too far from today for " + field.getType(), 0);
        } catch (ExprException | RuntimeException e) {
            v.close();
            throw e;
        }
    }

    private static void fill(Col c, FieldVector v, Field field, int n) throws ExprException {
        for (int i = 0; i < n; i++) {
            if (c.nulls[i]) {
                v.setNull(i);
                continue;
            }
            switch (v) {
                case BitVector b -> b.setSafe(i, c.bools[i] ? 1 : 0);
                case BaseIntVector b -> b.setWithPossibleTruncate(i, c.longs[i]);
                case FloatingPointVector f -> f.setSafeWithPossibleTruncate(i, c.doubles[i]);
                case DecimalVector d -> d.setSafe(i, fit((BigDecimal) c.objects[i], d.getPrecision(), d.getScale()));
                case Decimal256Vector d -> d.setSafe(i, fit((BigDecimal) c.objects[i], d.getPrecision(), d.getScale()));
                case VariableWidthFieldVector s -> s.setSafe(i, ((String) c.objects[i]).getBytes(StandardCharsets.UTF_8));
                case DateDayVector d -> d.setSafe(i, Math.toIntExact(c.longs[i]));
                case DateMilliVector d -> d.setSafe(i, Math.multiplyExact(c.longs[i], MILLIS_PER_DAY));
                case TimeStampVector t -> {
                    long micros = c.longs[i];
                    TimeUnit unit = ((ArrowType.Timestamp) field.getType()).getUnit();
                    t.setSafe(i, switch (unit) {
                        case SECOND -> Math.floorDiv(micros, 1_000_000L);
                        case MILLISECOND -> Math.floorDiv(micros, 1_000L);
                        case MICROSECOND -> micros;
                        case NANOSECOND -> Math.multiplyExact(micros, 1_000L);
                    });
                }
                default -> throw new IllegalArgumentException("Expressions cannot write a column of type "
                    + field.getType());
            }
        }
    }

    /** A decimal set to the vector's scale, checked against its precision. */
    static BigDecimal fit(BigDecimal value, int precision, int scale) throws ExprException {
        BigDecimal v = value.setScale(scale, RoundingMode.HALF_UP);
        if (v.precision() > precision) {
            throw new ExprException("a decimal value has more digits than decimal(" + precision + "," + scale
                + ") holds", 0);
        }
        return v;
    }
}
