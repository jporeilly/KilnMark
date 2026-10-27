package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * Oracle column types as the engine's kinds, the same from the catalogue
 * ({@code ALL_TAB_COLUMNS}) and from a result's metadata, and how each value is read. Nothing is
 * rounded on the way:
 *
 * <ul>
 *   <li>{@code NUMBER(p)} and {@code NUMBER(p,0)}: int16 up to 4 digits, int32 up to 9, int64 up to
 *       18, decimal(p,0) beyond. {@code NUMBER(p,s)}: decimal(p,s); a negative scale (whole
 *       hundreds) as decimal(p−s,0); a scale above the precision as decimal(s,s).
 *       {@code NUMBER(*,0)}: decimal(38,0).</li>
 *   <li>{@code NUMBER} without precision holds up to 38 significant digits at any scale, which no
 *       fixed decimal type can: read as text, the exact digits ({@code 1234.5}, {@code 0.000017}).
 *       Declare the precision to read it as a number.</li>
 *   <li>{@code FLOAT(b)} and {@code BINARY_DOUBLE} as float64, {@code BINARY_FLOAT} as float32.</li>
 *   <li>{@code DATE} (which keeps a time of day) and {@code TIMESTAMP(n)} as timestamp, wall-clock
 *       as stored; {@code TIMESTAMP WITH TIME ZONE} and {@code WITH LOCAL TIME ZONE} as
 *       timestamp_tz (sessions use time zone {@code +00:00}). Read as {@code java.time} values,
 *       never through {@code java.sql} types, which would apply Kilnmark's own time zone.</li>
 *   <li>{@code BOOLEAN} (23ai) as boolean.</li>
 *   <li>{@code VARCHAR2}, {@code NVARCHAR2}, {@code CHAR}, {@code NCHAR}, {@code CLOB},
 *       {@code NCLOB}, {@code LONG}, {@code ROWID}, intervals, {@code JSON}, {@code XMLTYPE}
 *       (selected through {@code XMLSERIALIZE}) and object types as text; {@code RAW},
 *       {@code LONG RAW} and {@code BLOB} as binary. {@code BFILE} points at a file outside the
 *       database: it is read as null.</li>
 * </ul>
 */
final class OracleTypes {

    private OracleTypes() {
    }

    /**
     * A column type, normalised.
     *
     * @param name      the base name, upper case, without lengths: {@code NUMBER}, {@code TIMESTAMP},
     *                  {@code TIMESTAMP WITH TIME ZONE}, {@code VARCHAR2}
     * @param precision a NUMBER's precision, FLOAT's binary precision; null when not declared
     * @param scale     a NUMBER's scale or a timestamp's digits of a second; null when not declared
     */
    record Type(String name, Integer precision, Integer scale) {
    }

    /** How a value is read. */
    enum Read { BOOLEAN, INT16, INT32, INT64, DECIMAL, NUMBER_TEXT, FLOAT32, FLOAT64, TIMESTAMP, TIMESTAMP_TZ,
        TIMESTAMP_LTZ, TEXT, BINARY, NULL }

    /** What a type becomes. */
    record Mapped(Kind kind, int precision, int scale, Read read) {

        ArrowType arrowType() {
            return switch (kind) {
                case BOOLEAN -> ArrowType.Bool.INSTANCE;
                case INT16 -> new ArrowType.Int(16, true);
                case INT32 -> new ArrowType.Int(32, true);
                case INT64 -> new ArrowType.Int(64, true);
                case FLOAT32 -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
                case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
                case DECIMAL -> new ArrowType.Decimal(precision, scale, 128);
                case TEXT -> ArrowType.Utf8.INSTANCE;
                case BINARY -> ArrowType.Binary.INSTANCE;
                case DATE -> new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY);
                case TIME -> new ArrowType.Time(TimeUnit.MICROSECOND, 64);
                case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
                case TIMESTAMP_TZ -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
            };
        }
    }

    /** The name without lengths: {@code TIMESTAMP(6) WITH TIME ZONE} is {@code TIMESTAMP WITH TIME ZONE}. */
    static String baseName(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT).replaceAll("\\(\\s*\\d+\\s*\\)", "")
            .replaceAll("\\s+", " ").strip();
    }

    /** A type from {@code ALL_TAB_COLUMNS}: {@code DATA_TYPE}, {@code DATA_PRECISION}, {@code DATA_SCALE}. */
    static Type fromCatalog(String dataType, Integer precision, Integer scale) {
        String name = baseName(dataType);
        if (name.equals("NUMBER") && precision == null && scale != null) {
            return new Type("NUMBER", 38, scale); // NUMBER(*,s)
        }
        return new Type(name, precision, scale);
    }

    /**
     * Column {@code i} (1-based) of a result, as Oracle's driver describes it: a NUMBER without
     * precision has precision 0 and scale -127; FLOAT(b) has precision b and scale -127.
     */
    static Type fromResult(ResultSetMetaData md, int i) throws SQLException {
        String name = baseName(md.getColumnTypeName(i));
        int p = md.getPrecision(i);
        int s = md.getScale(i);
        if (name.equals("NUMBER")) {
            if (s == -127) {
                return p == 0 ? new Type("NUMBER", null, null) : new Type("FLOAT", p, null);
            }
            return new Type("NUMBER", p == 0 ? 38 : p, s);
        }
        if (name.startsWith("TIMESTAMP") || name.equals("DATE")) {
            return new Type(name, null, s);
        }
        return new Type(name, null, null);
    }

    /** What {@code t} becomes. */
    static Mapped map(Type t) {
        return switch (t.name()) {
            case "NUMBER" -> number(t.precision(), t.scale());
            case "FLOAT", "BINARY_DOUBLE", "DOUBLE PRECISION", "REAL" -> new Mapped(Kind.FLOAT64, 0, 0, Read.FLOAT64);
            case "BINARY_FLOAT" -> new Mapped(Kind.FLOAT32, 0, 0, Read.FLOAT32);
            case "DATE", "TIMESTAMP" -> new Mapped(Kind.TIMESTAMP, 0, 0, Read.TIMESTAMP);
            case "TIMESTAMP WITH TIME ZONE" -> new Mapped(Kind.TIMESTAMP_TZ, 0, 0, Read.TIMESTAMP_TZ);
            // shown in the session's time zone, which is +00:00
            case "TIMESTAMP WITH LOCAL TIME ZONE" -> new Mapped(Kind.TIMESTAMP_TZ, 0, 0, Read.TIMESTAMP_LTZ);
            case "BOOLEAN" -> new Mapped(Kind.BOOLEAN, 0, 0, Read.BOOLEAN);
            case "RAW", "LONG RAW", "BLOB" -> new Mapped(Kind.BINARY, 0, 0, Read.BINARY);
            case "BFILE" -> new Mapped(Kind.TEXT, 0, 0, Read.NULL);
            default -> new Mapped(Kind.TEXT, 0, 0, Read.TEXT);
        };
    }

    private static Mapped number(Integer precision, Integer scale) {
        if (precision == null) {
            return new Mapped(Kind.TEXT, 0, 0, Read.NUMBER_TEXT);
        }
        int p = precision;
        int s = scale == null ? 0 : scale;
        if (s == 0) {
            return p <= 4 ? new Mapped(Kind.INT16, 0, 0, Read.INT16)
                : p <= 9 ? new Mapped(Kind.INT32, 0, 0, Read.INT32)
                : p <= 18 ? new Mapped(Kind.INT64, 0, 0, Read.INT64)
                : new Mapped(Kind.DECIMAL, p, 0, Read.DECIMAL);
        }
        if (s < 0) {
            int digits = p - s;
            return digits <= 38 ? new Mapped(Kind.DECIMAL, digits, 0, Read.DECIMAL)
                : new Mapped(Kind.TEXT, 0, 0, Read.NUMBER_TEXT);
        }
        int digits = Math.max(p, s);
        return digits <= 38 ? new Mapped(Kind.DECIMAL, digits, s, Read.DECIMAL)
            : new Mapped(Kind.TEXT, 0, 0, Read.NUMBER_TEXT);
    }

    /** How one column of an Oracle result is read. */
    static JdbcTableInput.ColumnReader reader(String name, Mapped m) {
        return new Column(name, m);
    }

    private static final class Column implements JdbcTableInput.ColumnReader {

        private final Field field;
        private final Mapped mapped;

        Column(String name, Mapped mapped) {
            this.field = new Field(name, FieldType.nullable(mapped.arrowType()), null);
            this.mapped = mapped;
        }

        @Override
        public Field field() {
            return field;
        }

        @Override
        public void copy(ResultSet rs, int col, FieldVector vector, int row) throws SQLException {
            switch (mapped.read()) {
                case BOOLEAN -> {
                    boolean v = rs.getBoolean(col);
                    if (rs.wasNull()) vector.setNull(row); else ((BitVector) vector).setSafe(row, v ? 1 : 0);
                }
                case INT16 -> {
                    short v = rs.getShort(col);
                    if (rs.wasNull()) vector.setNull(row); else ((SmallIntVector) vector).setSafe(row, v);
                }
                case INT32 -> {
                    int v = rs.getInt(col);
                    if (rs.wasNull()) vector.setNull(row); else ((IntVector) vector).setSafe(row, v);
                }
                case INT64 -> {
                    long v = rs.getLong(col);
                    if (rs.wasNull()) vector.setNull(row); else ((BigIntVector) vector).setSafe(row, v);
                }
                case DECIMAL -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    // the column's scale holds every value it can store: never rounded
                    if (v == null) vector.setNull(row); else ((DecimalVector) vector).setSafe(row, v.setScale(mapped.scale()));
                }
                case NUMBER_TEXT -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    text(vector, row, v == null ? null : v.stripTrailingZeros().toPlainString());
                }
                case FLOAT32 -> {
                    float v = rs.getFloat(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float4Vector) vector).setSafe(row, v);
                }
                case FLOAT64 -> {
                    double v = rs.getDouble(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float8Vector) vector).setSafe(row, v);
                }
                case TIMESTAMP -> {
                    LocalDateTime t = rs.getObject(col, LocalDateTime.class);
                    if (t == null) vector.setNull(row);
                    else ((TimeStampMicroVector) vector).setSafe(row, micros(t));
                }
                case TIMESTAMP_TZ -> {
                    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
                    if (t == null) vector.setNull(row);
                    else ((TimeStampMicroTZVector) vector).setSafe(row,
                        micros(t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()));
                }
                case TIMESTAMP_LTZ -> {
                    LocalDateTime t = rs.getObject(col, LocalDateTime.class);
                    if (t == null) vector.setNull(row); else ((TimeStampMicroTZVector) vector).setSafe(row, micros(t));
                }
                case TEXT -> {
                    String v;
                    try {
                        v = rs.getString(col);
                    } catch (SQLException e) {
                        v = OracleTypes.text(rs.getObject(col)); // object types and arrays, which have no plain text form
                    }
                    text(vector, row, v);
                }
                case BINARY -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((VarBinaryVector) vector).setSafe(row, v);
                }
                case NULL -> vector.setNull(row);
            }
        }

        private static long micros(LocalDateTime t) {
            return ChronoUnit.MICROS.between(java.time.Instant.EPOCH, t.toInstant(ZoneOffset.UTC));
        }

        private static void text(FieldVector vector, int row, String v) {
            if (v == null) vector.setNull(row);
            else ((VarCharVector) vector).setSafe(row, v.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A value of a text-like column as text: character data as it is, objects and arrays spelled out. */
    static String text(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Clob c) {
            long n = c.length();
            return n == 0 ? "" : c.getSubString(1, (int) Math.min(Integer.MAX_VALUE, n));
        }
        if (v instanceof SQLXML x) {
            return x.getString();
        }
        if (v instanceof Struct s) {
            Object[] attributes = s.getAttributes();
            StringBuilder b = new StringBuilder(s.getSQLTypeName()).append('(');
            for (int i = 0; i < attributes.length; i++) {
                b.append(i > 0 ? ", " : "").append(attributes[i] instanceof String str ? "'" + str + "'"
                    : text(attributes[i]));
            }
            return b.append(')').toString();
        }
        if (v instanceof Array a) {
            Object items = a.getArray();
            Object[] list = items instanceof Object[] o ? o : new Object[0];
            return Arrays.stream(list).map(x -> {
                try {
                    return String.valueOf(text(x));
                } catch (SQLException e) {
                    return "?";
                }
            }).collect(Collectors.joining(", ", "[", "]"));
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof byte[] b) {
            return java.util.HexFormat.of().formatHex(b);
        }
        return v.toString();
    }
}
