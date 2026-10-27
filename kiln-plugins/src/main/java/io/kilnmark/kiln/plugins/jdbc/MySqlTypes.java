package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
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
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * MySQL and MariaDB column types as the engine's kinds, the same from the catalogue
 * ({@code information_schema.COLUMNS}) and from a result's metadata, and how each value is read.
 *
 * <ul>
 *   <li>Integers: TINYINT, SMALLINT as int16; TINYINT UNSIGNED as int16; SMALLINT UNSIGNED,
 *       MEDIUMINT (either) and INT as int32; INT UNSIGNED and BIGINT as int64; BIGINT UNSIGNED as
 *       decimal(20,0), since its values go past int64. TINYINT(1), which is what MySQL makes of
 *       BOOLEAN, is boolean when the source's {@code tinyint1_as_boolean} setting says so (the
 *       default); any value but 0 is then true.</li>
 *   <li>DECIMAL(p,s) as decimal(p,s) up to 38 digits; beyond that (MySQL allows 65) as text, so no
 *       digit is lost. FLOAT as float32, DOUBLE as float64.</li>
 *   <li>BIT(1) as boolean; BIT(2) to BIT(63) as int64; BIT(64) as decimal(20,0): the bits read as
 *       an unsigned number.</li>
 *   <li>YEAR as int16.</li>
 *   <li>DATE as date, DATETIME as timestamp (wall-clock, as stored: no time zone is applied),
 *       TIMESTAMP as timestamp_tz (MySQL stores it in UTC; Kilnmark's sessions use time zone
 *       {@code +00:00}, so the instant is exact whatever the server's or Kilnmark's own zone).
 *       Temporal values are read as the server's text and parsed, never through the driver's
 *       {@code java.sql} types, which would apply Kilnmark's time zone.</li>
 *   <li>Zero dates ({@code 0000-00-00}, or a zero month or day, which MySQL allows outside strict
 *       modes) and impossible ones ({@code 2024-02-31} under {@code ALLOW_INVALID_DATES}) have no
 *       calendar value: they are read as null and counted, and the read's {@code ROW_COUNT} says
 *       {@code zero_dates}. YEAR {@code 0000}, MySQL's zero year, too.</li>
 *   <li>TIME as text in MySQL's own form ({@code -12:30:00}, {@code 838:59:59.000000}): it is a
 *       duration from -838:59:59 to 838:59:59, which a time of day cannot hold, and the driver's
 *       {@code LocalTime} wraps such values round silently.</li>
 *   <li>JSON, ENUM, SET (its members comma-separated, as MySQL returns them), CHAR, VARCHAR and
 *       the TEXT types as text; BINARY, VARBINARY and the BLOB types as binary.</li>
 *   <li>GEOMETRY and its subtypes as text: the reader selects them through {@code ST_AsText}, so
 *       they arrive as well-known text, longitude first on MySQL ({@code axis-order=long-lat}),
 *       without the SRID.</li>
 *   <li>Anything else (MariaDB's UUID, INET4, INET6, ...) as text.</li>
 * </ul>
 */
final class MySqlTypes {

    private MySqlTypes() {
    }

    /** The spatial types, read through {@code ST_AsText}. */
    static final Set<String> SPATIAL = Set.of("geometry", "point", "linestring", "polygon", "multipoint",
        "multilinestring", "multipolygon", "geometrycollection", "geomcollection");

    private static final Set<String> TEXT_TYPES = Set.of("char", "varchar", "tinytext", "text", "mediumtext",
        "longtext", "json", "enum", "set");

    private static final Set<String> BINARY_TYPES = Set.of("binary", "varbinary", "tinyblob", "blob", "mediumblob",
        "longblob");

    /** {@code bit(5)}, {@code tinyint(1) unsigned}: the length in brackets. */
    private static final Pattern LENGTH = Pattern.compile("^[a-z]+\\((\\d+)\\)");

    /**
     * How a value is read: which {@link ResultSet} getter, and what is done with it.
     */
    enum Read { BOOLEAN_NUMBER, BIT_BOOLEAN, BIT_NUMBER, BIT_DECIMAL, INT16, INT32, INT64, UNSIGNED_DECIMAL, DECIMAL,
        DECIMAL_TEXT, FLOAT32, FLOAT64, YEAR, DATE, DATETIME, TIMESTAMP, TEXT, BINARY }

    /**
     * A column type as MySQL describes it.
     *
     * @param name     the base type, lower case: {@code tinyint}, {@code varchar}, {@code point}
     * @param unsigned whether it is UNSIGNED
     * @param length   the display width or length in brackets ({@code tinyint(1)}, {@code bit(5)}),
     *                 or the decimal precision; 0 when none
     * @param scale    the decimal scale
     */
    record Type(String name, boolean unsigned, int length, int scale) {
    }

    /**
     * What a type becomes.
     *
     * @param kind      the engine's kind
     * @param precision decimal precision, else 0
     * @param scale     decimal scale, else 0
     * @param read      how values are read
     */
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
                case DATE -> new ArrowType.Date(DateUnit.DAY);
                case TIME -> new ArrowType.Time(TimeUnit.MICROSECOND, 64);
                case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
                case TIMESTAMP_TZ -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
            };
        }
    }

    /**
     * A type from {@code information_schema.COLUMNS}.
     *
     * @param dataType   {@code DATA_TYPE}, for example {@code bigint}
     * @param columnType {@code COLUMN_TYPE}, for example {@code bigint(20) unsigned} or {@code tinyint(1)}
     * @param precision  {@code NUMERIC_PRECISION}, or null
     * @param scale      {@code NUMERIC_SCALE}, or null
     */
    static Type fromCatalog(String dataType, String columnType, Long precision, Long scale) {
        String name = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT).strip();
        String full = columnType == null ? name : columnType.toLowerCase(Locale.ROOT).strip();
        boolean unsigned = full.contains(" unsigned");
        int length;
        if (name.equals("decimal") || name.equals("numeric")) {
            length = precision == null ? 0 : precision.intValue();
        } else {
            Matcher m = LENGTH.matcher(full);
            length = m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
        return new Type(name.equals("numeric") ? "decimal" : name, unsigned, length,
            scale == null ? 0 : scale.intValue());
    }

    /**
     * Column {@code i} (1-based) of a result, as MariaDB Connector/J describes it with
     * {@code tinyInt1isBit=false} and {@code yearIsDateType=false}: type names such as
     * {@code TINYINT UNSIGNED} or {@code INTEGER}, the display width or length as precision.
     */
    static Type fromResult(ResultSetMetaData md, int i) throws SQLException {
        String name = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i).toLowerCase(Locale.ROOT).strip();
        boolean unsigned = name.contains(" unsigned");
        name = name.replace(" unsigned", "").replace(" zerofill", "").strip();
        if (name.equals("integer")) {
            name = "int";
        }
        if (name.equals("numeric")) {
            name = "decimal";
        }
        return new Type(name, unsigned, Math.max(0, md.getPrecision(i)), Math.max(0, md.getScale(i)));
    }

    /** What {@code t} becomes; TINYINT(1) is boolean when {@code tinyint1AsBoolean}. */
    static Mapped map(Type t, boolean tinyint1AsBoolean) {
        return switch (t.name()) {
            case "tinyint" -> tinyint1AsBoolean && t.length() == 1 ? new Mapped(Kind.BOOLEAN, 0, 0, Read.BOOLEAN_NUMBER)
                : new Mapped(Kind.INT16, 0, 0, Read.INT16);
            case "smallint" -> t.unsigned() ? new Mapped(Kind.INT32, 0, 0, Read.INT32)
                : new Mapped(Kind.INT16, 0, 0, Read.INT16);
            case "mediumint" -> new Mapped(Kind.INT32, 0, 0, Read.INT32);
            case "int" -> t.unsigned() ? new Mapped(Kind.INT64, 0, 0, Read.INT64)
                : new Mapped(Kind.INT32, 0, 0, Read.INT32);
            case "bigint" -> t.unsigned() ? new Mapped(Kind.DECIMAL, 20, 0, Read.UNSIGNED_DECIMAL)
                : new Mapped(Kind.INT64, 0, 0, Read.INT64);
            case "decimal" -> {
                int p = t.length();
                int s = Math.max(0, t.scale());
                yield p >= 1 && p <= 38 && s <= p ? new Mapped(Kind.DECIMAL, p, s, Read.DECIMAL)
                    : new Mapped(Kind.TEXT, 0, 0, Read.DECIMAL_TEXT);
            }
            case "float" -> new Mapped(Kind.FLOAT32, 0, 0, Read.FLOAT32);
            case "double", "real", "double precision" -> new Mapped(Kind.FLOAT64, 0, 0, Read.FLOAT64);
            case "bit" -> t.length() <= 1 ? new Mapped(Kind.BOOLEAN, 0, 0, Read.BIT_BOOLEAN)
                : t.length() < 64 ? new Mapped(Kind.INT64, 0, 0, Read.BIT_NUMBER)
                : new Mapped(Kind.DECIMAL, 20, 0, Read.BIT_DECIMAL);
            case "year" -> new Mapped(Kind.INT16, 0, 0, Read.YEAR);
            case "date" -> new Mapped(Kind.DATE, 0, 0, Read.DATE);
            case "datetime" -> new Mapped(Kind.TIMESTAMP, 0, 0, Read.DATETIME);
            case "timestamp" -> new Mapped(Kind.TIMESTAMP_TZ, 0, 0, Read.TIMESTAMP);
            default -> BINARY_TYPES.contains(t.name()) ? new Mapped(Kind.BINARY, 0, 0, Read.BINARY)
                : new Mapped(Kind.TEXT, 0, 0, Read.TEXT); // text types, time, spatial (as WKT) and the rest
        };
    }

    /** Whether a type name is one of the text types (not TIME or spatial ones, which also read as text). */
    static boolean isTextType(String name) {
        return TEXT_TYPES.contains(name);
    }

    /** How one column of a MySQL or MariaDB result is read. */
    static JdbcTableInput.ColumnReader reader(String name, Mapped m) {
        return new Column(name, m);
    }

    /** One column of a result; it counts the zero dates it reads as null. */
    private static final class Column implements JdbcTableInput.ColumnReader {

        private final Field field;
        private final Mapped mapped;
        private long zeroDates;

        Column(String name, Mapped mapped) {
            this.field = new Field(name, FieldType.nullable(mapped.arrowType()), null);
            this.mapped = mapped;
        }

        @Override
        public Field field() {
            return field;
        }

        @Override
        public long zeroDates() {
            return zeroDates;
        }

        @Override
        public void copy(ResultSet rs, int col, FieldVector vector, int row) throws SQLException {
            switch (mapped.read()) {
                case BOOLEAN_NUMBER -> {
                    long v = rs.getLong(col);
                    if (rs.wasNull()) vector.setNull(row); else ((BitVector) vector).setSafe(row, v != 0 ? 1 : 0);
                }
                case BIT_BOOLEAN -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((BitVector) vector).setSafe(row, bits(v).signum() != 0 ? 1 : 0);
                }
                case BIT_NUMBER -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((BigIntVector) vector).setSafe(row, bits(v).longValue());
                }
                case BIT_DECIMAL -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((DecimalVector) vector).setSafe(row, new BigDecimal(bits(v)));
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
                case UNSIGNED_DECIMAL, DECIMAL -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    if (v == null) vector.setNull(row);
                    else ((DecimalVector) vector).setSafe(row, v.setScale(mapped.scale(), RoundingMode.HALF_EVEN));
                }
                case DECIMAL_TEXT -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    text(vector, row, v == null ? null : v.toPlainString());
                }
                case FLOAT32 -> {
                    float v = rs.getFloat(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float4Vector) vector).setSafe(row, v);
                }
                case FLOAT64 -> {
                    double v = rs.getDouble(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float8Vector) vector).setSafe(row, v);
                }
                case YEAR -> {
                    short v = rs.getShort(col);
                    if (rs.wasNull()) {
                        vector.setNull(row);
                    } else if (v == 0) {
                        zeroDates++;
                        vector.setNull(row);
                    } else {
                        ((SmallIntVector) vector).setSafe(row, v);
                    }
                }
                case DATE -> {
                    String s = rs.getString(col);
                    LocalDate d = s == null ? null : date(s);
                    if (s != null && d == null) zeroDates++;
                    if (d == null) vector.setNull(row); else ((DateDayVector) vector).setSafe(row, (int) d.toEpochDay());
                }
                case DATETIME, TIMESTAMP -> {
                    String s = rs.getString(col);
                    LocalDateTime t = s == null ? null : dateTime(s);
                    if (s != null && t == null) zeroDates++;
                    if (t == null) {
                        vector.setNull(row);
                    } else {
                        long micros = ChronoUnit.MICROS.between(java.time.Instant.EPOCH, t.toInstant(ZoneOffset.UTC));
                        if (mapped.read() == Read.TIMESTAMP) {
                            ((TimeStampMicroTZVector) vector).setSafe(row, micros);
                        } else {
                            ((TimeStampMicroVector) vector).setSafe(row, micros);
                        }
                    }
                }
                case TEXT -> text(vector, row, rs.getString(col));
                case BINARY -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((VarBinaryVector) vector).setSafe(row, v);
                }
            }
        }

        private static void text(FieldVector vector, int row, String v) {
            if (v == null) vector.setNull(row);
            else ((VarCharVector) vector).setSafe(row, v.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** BIT bytes, most significant first, as an unsigned number. */
    static BigInteger bits(byte[] v) {
        return new BigInteger(1, v);
    }

    /**
     * {@code 2024-02-29} as a date; null for a zero date ({@code 0000-00-00}, a zero month or day)
     * or one the calendar does not have.
     */
    static LocalDate date(String s) {
        String v = s.strip();
        if (v.length() < 10 || v.charAt(4) != '-' || v.charAt(7) != '-') {
            return null;
        }
        try {
            int year = Integer.parseInt(v.substring(0, 4));
            int month = Integer.parseInt(v.substring(5, 7));
            int day = Integer.parseInt(v.substring(8, 10));
            if (month == 0 || day == 0) {
                return null;
            }
            return LocalDate.of(year, month, day);
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /** {@code 2024-02-29 13:45:00.123456} as a timestamp; null where {@link #date} is null. */
    static LocalDateTime dateTime(String s) {
        String v = s.strip();
        LocalDate d = date(v);
        if (d == null) {
            return null;
        }
        if (v.length() <= 10) {
            return d.atStartOfDay();
        }
        try {
            String t = v.substring(11);
            int hour = Integer.parseInt(t.substring(0, 2));
            int minute = Integer.parseInt(t.substring(3, 5));
            int second = Integer.parseInt(t.substring(6, 8));
            int nanos = 0;
            if (t.length() > 9 && t.charAt(8) == '.') {
                String f = (t.substring(9) + "000000000").substring(0, 9);
                nanos = Integer.parseInt(f);
            }
            return LocalDateTime.of(d, LocalTime.of(hour, minute, second, nanos));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
