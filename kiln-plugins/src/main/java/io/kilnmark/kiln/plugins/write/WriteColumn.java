package io.kilnmark.kiln.plugins.write;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.arrow.vector.BaseLargeVariableWidthVector;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeSecVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * One column arriving at a writer: its name, the engine's kind for it and, for decimals, precision
 * and scale. Writers decide what to create and how to encode from the kind, so every Arrow type a
 * step may produce is written, the way readers read: integers of any width, floats, decimals,
 * text, bytes, dates, times and timestamps keep their type; anything else (lists, structs, maps,
 * intervals) is written as text, never dropped.
 *
 * <p>{@link #value} gives each value as one Java type per kind: {@link Boolean}, {@link Long} (every
 * integer kind), {@link Float}, {@link Double}, {@link BigDecimal}, {@link String}, {@code byte[]},
 * {@link LocalDate}, {@link LocalTime} (microseconds), {@link LocalDateTime} (timestamps without a
 * zone, microseconds) and {@link Instant} (timestamps in UTC, microseconds).
 *
 * @param arrowType the type as it arrived
 */
public record WriteColumn(String name, Kind kind, int precision, int scale, ArrowType arrowType) {

    /** Bytes as text: {@code 0xCAFE}, as the database readers show bytes they cannot type. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** The columns of a schema, in order. */
    public static List<WriteColumn> of(Schema schema) {
        List<WriteColumn> out = new ArrayList<>(schema.getFields().size());
        for (Field f : schema.getFields()) {
            out.add(of(f));
        }
        return out;
    }

    /** The column an Arrow field becomes. */
    public static WriteColumn of(Field field) {
        ArrowType t = field.getType();
        String name = field.getName();
        return switch (t) {
            case ArrowType.Bool b -> new WriteColumn(name, Kind.BOOLEAN, 0, 0, t);
            case ArrowType.Int i -> integer(name, i);
            case ArrowType.FloatingPoint f -> new WriteColumn(name,
                f.getPrecision() == FloatingPointPrecision.DOUBLE ? Kind.FLOAT64 : Kind.FLOAT32, 0, 0, t);
            case ArrowType.Decimal d when d.getPrecision() <= 38 -> new WriteColumn(name, Kind.DECIMAL,
                d.getPrecision(), d.getScale(), t);
            case ArrowType.Utf8 u -> new WriteColumn(name, Kind.TEXT, 0, 0, t);
            case ArrowType.LargeUtf8 u -> new WriteColumn(name, Kind.TEXT, 0, 0, t);
            case ArrowType.Binary b -> new WriteColumn(name, Kind.BINARY, 0, 0, t);
            case ArrowType.LargeBinary b -> new WriteColumn(name, Kind.BINARY, 0, 0, t);
            case ArrowType.FixedSizeBinary b -> new WriteColumn(name, Kind.BINARY, 0, 0, t);
            case ArrowType.BinaryView b -> new WriteColumn(name, Kind.BINARY, 0, 0, t);
            case ArrowType.Date d -> new WriteColumn(name, Kind.DATE, 0, 0, t);
            case ArrowType.Time tm -> new WriteColumn(name, Kind.TIME, 0, 0, t);
            case ArrowType.Timestamp ts -> new WriteColumn(name,
                ts.getTimezone() == null || ts.getTimezone().isEmpty() ? Kind.TIMESTAMP : Kind.TIMESTAMP_TZ, 0, 0, t);
            default -> new WriteColumn(name, Kind.TEXT, 0, 0, t); // decimals over 38 digits, nested and the rest
        };
    }

    private static WriteColumn integer(String name, ArrowType.Int i) {
        int bits = i.getBitWidth();
        if (!i.getIsSigned()) {
            bits *= 2; // the next signed width holds every unsigned value
        }
        if (bits <= 16) {
            return new WriteColumn(name, Kind.INT16, 0, 0, i);
        }
        if (bits <= 32) {
            return new WriteColumn(name, Kind.INT32, 0, 0, i);
        }
        if (bits <= 64) {
            return new WriteColumn(name, Kind.INT64, 0, 0, i);
        }
        return new WriteColumn(name, Kind.DECIMAL, 20, 0, i); // unsigned 64-bit
    }

    /** The value at {@code row} of {@code v} (this column's vector), as described above; null for a null. */
    public Object value(FieldVector v, int row) {
        if (v.isNull(row)) {
            return null;
        }
        return switch (kind) {
            case BOOLEAN -> ((BitVector) v).get(row) == 1;
            case INT16, INT32, INT64 -> integer(v, row);
            case FLOAT32 -> v instanceof Float2Vector h ? h.getValueAsFloat(row) : ((Float4Vector) v).get(row);
            case FLOAT64 -> ((Float8Vector) v).get(row);
            case DECIMAL -> switch (v) {
                case DecimalVector d -> d.getObject(row);
                case Decimal256Vector d -> d.getObject(row);
                case UInt8Vector u -> new BigDecimal(Long.toUnsignedString(u.get(row)));
                default -> new BigDecimal(String.valueOf(v.getObject(row)));
            };
            case TEXT -> text(v, row);
            case BINARY -> switch (v) {
                case BaseVariableWidthVector b -> b.get(row);
                case BaseLargeVariableWidthVector b -> b.get(row);
                case FixedSizeBinaryVector b -> b.get(row);
                default -> (byte[]) v.getObject(row);
            };
            case DATE -> switch (v) {
                case DateDayVector d -> LocalDate.ofEpochDay(d.get(row));
                case DateMilliVector d -> LocalDate.ofEpochDay(Math.floorDiv(d.get(row), 86_400_000L));
                default -> throw new IllegalStateException("Unexpected date vector " + v.getClass().getSimpleName());
            };
            case TIME -> LocalTime.ofNanoOfDay(timeMicros(v, row) * 1_000);
            case TIMESTAMP -> {
                long micros = timestampMicros(v, row);
                yield LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    (int) Math.floorMod(micros, 1_000_000L) * 1_000, ZoneOffset.UTC);
            }
            case TIMESTAMP_TZ -> {
                long micros = timestampMicros(v, row);
                yield Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000);
            }
        };
    }

    private static Long integer(FieldVector v, int row) {
        return switch (v) {
            case TinyIntVector x -> (long) x.get(row);
            case SmallIntVector x -> (long) x.get(row);
            case IntVector x -> (long) x.get(row);
            case BigIntVector x -> x.get(row);
            case UInt1Vector x -> (long) Byte.toUnsignedInt(x.get(row));
            case UInt2Vector x -> (long) x.get(row);
            case UInt4Vector x -> Integer.toUnsignedLong(x.get(row));
            default -> ((Number) v.getObject(row)).longValue();
        };
    }

    private static String text(FieldVector v, int row) {
        if (v instanceof BaseVariableWidthVector b && !(b.getField().getType() instanceof ArrowType.Binary)) {
            return new String(b.get(row), StandardCharsets.UTF_8);
        }
        if (v instanceof BaseLargeVariableWidthVector b && !(b.getField().getType() instanceof ArrowType.LargeBinary)) {
            return new String(b.get(row), StandardCharsets.UTF_8);
        }
        Object o = v.getObject(row);
        return switch (o) {
            case null -> null;
            case byte[] bytes -> "0x" + HEX.formatHex(bytes);
            default -> o.toString(); // lists and structs print as JSON, intervals in ISO 8601
        };
    }

    /** Microseconds since midnight. */
    private static long timeMicros(FieldVector v, int row) {
        return switch (v) {
            case TimeSecVector t -> t.get(row) * 1_000_000L;
            case TimeMilliVector t -> t.get(row) * 1_000L;
            case TimeMicroVector t -> t.get(row);
            case TimeNanoVector t -> t.get(row) / 1_000;
            default -> throw new IllegalStateException("Unexpected time vector " + v.getClass().getSimpleName());
        };
    }

    /** Microseconds since 1970 (nanoseconds are cut to microseconds, as the engine keeps them). */
    private long timestampMicros(FieldVector v, int row) {
        long raw = ((TimeStampVector) v).get(row);
        return switch (((ArrowType.Timestamp) arrowType).getUnit()) {
            case SECOND -> raw * 1_000_000L;
            case MILLISECOND -> raw * 1_000L;
            case MICROSECOND -> raw;
            case NANOSECOND -> Math.floorDiv(raw, 1_000L);
        };
    }

    /** The kind's name as catalogues record it: {@code int64}, {@code timestamp_tz}. */
    public String kindName() {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
