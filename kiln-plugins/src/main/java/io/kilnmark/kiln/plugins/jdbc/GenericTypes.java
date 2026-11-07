package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
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
 * Column types of a database Kilnmark knows only through its JDBC driver, as the engine's kinds:
 * from the standard {@link java.sql.Types} code the driver reports, with the type's name only to
 * recognise time zones and unsigned integers. The same mapping for the catalogue
 * ({@link java.sql.DatabaseMetaData#getColumns}) and for a result's metadata, as for the dedicated
 * connectors ({@link JdbcColumn}). Nothing is rounded:
 *
 * <ul>
 *   <li>{@code BIT} (one bit) and {@code BOOLEAN} as boolean; {@code TINYINT} and {@code SMALLINT}
 *       as int16, {@code INTEGER} as int32, {@code BIGINT} as int64. Unsigned types (by name) widen:
 *       {@code SMALLINT UNSIGNED} as int32, {@code INTEGER UNSIGNED} as int64, {@code BIGINT
 *       UNSIGNED} as decimal(20,0).</li>
 *   <li>{@code REAL} as float32; {@code FLOAT} and {@code DOUBLE} as float64.</li>
 *   <li>{@code DECIMAL} and {@code NUMERIC} with a precision of 1 to 38 and a scale from 0 to the
 *       precision as decimal(p,s), read as {@link BigDecimal}, never as a double. Any other
 *       (declared without a precision, wider than 38 digits, a negative scale) as text holding the
 *       exact digits, with a note.</li>
 *   <li>{@code DATE} as date, {@code TIME} as time, {@code TIMESTAMP} as timestamp (wall clock, as
 *       stored); {@code TIMESTAMP WITH TIME ZONE} ({@link Types#TIMESTAMP_WITH_TIMEZONE}, or a name
 *       saying {@code WITH TIME ZONE}, {@code TIMESTAMPTZ}, {@code TIMESTAMP_TZ}) and {@code WITH
 *       LOCAL TIME ZONE} ({@code TIMESTAMP_LTZ}) as timestamp_tz, in UTC. A time of day with a time
 *       zone has no engine type: text, with a note.</li>
 *   <li>Character types and {@code CLOB}s as text; {@code BINARY}, {@code VARBINARY},
 *       {@code LONGVARBINARY} and {@code BLOB} as binary.</li>
 *   <li>Anything else ({@code OTHER}, {@code ARRAY}, {@code STRUCT}, {@code JAVA_OBJECT}, a
 *       vendor's own code) as text, as the driver writes it, with a note naming the type, so
 *       nothing is dropped.</li>
 * </ul>
 *
 * <p>Values are read as {@code java.time} types; a driver that predates them is read through the
 * {@code java.sql} types instead, once it has said it cannot.
 */
final class GenericTypes {

    private GenericTypes() {
    }

    /** How a value is read. */
    enum Read { BOOLEAN, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL, NUMBER_TEXT, TEXT, BINARY, DATE, TIME,
        TIMESTAMP, TIMESTAMP_TZ }

    /**
     * What a column type becomes.
     *
     * @param note why it is read as it is, for people, when that is not plain from its type; else null
     */
    record Mapped(Kind kind, int precision, int scale, Read read, String note) {

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

    private static Mapped of(Kind kind, Read read) {
        return new Mapped(kind, 0, 0, read, null);
    }

    /** The JDBC type as people read it: {@code OTHER}, or {@code vendor type -155}. */
    static String jdbcName(int jdbcType) {
        try {
            return JDBCType.valueOf(jdbcType).getName();
        } catch (IllegalArgumentException e) {
            return "vendor type " + jdbcType;
        }
    }

    /**
     * Maps a column.
     *
     * @param jdbcType  the {@link Types} code the driver reports
     * @param typeName  the database's own name for the type
     * @param precision the precision or length the driver reports (0 when it reports none)
     * @param scale     the scale or digits of a second (0 when it reports none)
     */
    static Mapped map(int jdbcType, String typeName, int precision, int scale) {
        String name = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        String shown = typeName == null || typeName.isBlank() ? jdbcName(jdbcType) : typeName;
        boolean timestamp = name.startsWith("TIMESTAMP") || name.startsWith("DATETIME");
        if (name.contains("WITH LOCAL TIME ZONE") || name.equals("TIMESTAMP_LTZ") || name.equals("TIMESTAMPLTZ")) {
            return micros(of(Kind.TIMESTAMP_TZ, Read.TIMESTAMP_TZ), shown, scale);
        }
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || (timestamp && name.contains("WITH TIME ZONE"))
            || name.equals("TIMESTAMPTZ") || name.equals("TIMESTAMP_TZ") || name.equals("DATETIMEOFFSET")) {
            return micros(of(Kind.TIMESTAMP_TZ, Read.TIMESTAMP_TZ), shown, scale);
        }
        if (jdbcType == Types.TIME_WITH_TIMEZONE || (!timestamp && name.startsWith("TIME") && name.contains("WITH TIME ZONE"))
            || name.equals("TIMETZ")) {
            return new Mapped(Kind.TEXT, 0, 0, Read.TEXT, "Read as text: a time of day with a time zone (" + shown
                + ") has no type of its own in Kilnmark");
        }
        if (name.contains("UNSIGNED")) {
            switch (jdbcType) {
                case Types.TINYINT -> {
                    return of(Kind.INT16, Read.INT16);
                }
                case Types.SMALLINT -> {
                    return of(Kind.INT32, Read.INT32);
                }
                case Types.INTEGER -> {
                    return of(Kind.INT64, Read.INT64);
                }
                case Types.BIGINT -> {
                    return new Mapped(Kind.DECIMAL, 20, 0, Read.DECIMAL, null);
                }
                default -> {
                    // an unsigned decimal or float reads as its signed kind
                }
            }
        }
        return switch (jdbcType) {
            case Types.BIT -> precision > 1
                ? new Mapped(Kind.TEXT, 0, 0, Read.TEXT, "Read as text: " + shown + "(" + precision + ") holds "
                    + "several bits, not one true or false")
                : of(Kind.BOOLEAN, Read.BOOLEAN);
            case Types.BOOLEAN -> of(Kind.BOOLEAN, Read.BOOLEAN);
            case Types.TINYINT, Types.SMALLINT -> of(Kind.INT16, Read.INT16);
            case Types.INTEGER -> of(Kind.INT32, Read.INT32);
            case Types.BIGINT -> of(Kind.INT64, Read.INT64);
            case Types.REAL -> of(Kind.FLOAT32, Read.FLOAT32);
            case Types.FLOAT, Types.DOUBLE -> of(Kind.FLOAT64, Read.FLOAT64);
            case Types.NUMERIC, Types.DECIMAL -> {
                if (precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision) {
                    yield new Mapped(Kind.DECIMAL, precision, scale, Read.DECIMAL, null);
                }
                yield new Mapped(Kind.TEXT, 0, 0, Read.NUMBER_TEXT, "Read as text holding the exact digits: "
                    + (precision <= 0 ? shown + " is declared without a precision"
                    : shown + "(" + precision + "," + scale + ")" + (precision > 38 ? " has more than 38 digits"
                    : " has a scale outside 0 to its precision"))
                    + ", which no fixed decimal holds without rounding");
            }
            case Types.DATE -> of(Kind.DATE, Read.DATE);
            case Types.TIME -> micros(of(Kind.TIME, Read.TIME), shown, scale);
            case Types.TIMESTAMP -> micros(of(Kind.TIMESTAMP, Read.TIMESTAMP), shown, scale);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML, Types.ROWID -> of(Kind.TEXT, Read.TEXT);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> of(Kind.BINARY, Read.BINARY);
            default -> new Mapped(Kind.TEXT, 0, 0, Read.TEXT, "Read as text: Kilnmark has no type for " + shown
                + " (JDBC " + jdbcName(jdbcType) + "), so values are kept as the driver writes them");
        };
    }

    /**
     * A time or timestamp keeping more than six digits of a second (Db2's {@code TIMESTAMP(12)},
     * nanoseconds): the engine keeps microseconds, so the column says that the rest is cut when read.
     */
    private static Mapped micros(Mapped m, String shown, int scale) {
        return scale <= 6 ? m : new Mapped(m.kind(), m.precision(), m.scale(), m.read(), shown + " keeps " + scale
            + " digits of a second; Kilnmark keeps six (microseconds), so the rest are cut when read");
    }

    /** Column {@code i} (1-based) of a result, mapped and ready to copy. */
    static Column reader(ResultSetMetaData md, int i) throws SQLException {
        int precision;
        int scale;
        try {
            precision = md.getPrecision(i);
            scale = md.getScale(i);
        } catch (SQLException | RuntimeException e) {
            precision = 0; // a driver that cannot say: decimals read as exact text
            scale = 0;
        }
        return new Column(md.getColumnLabel(i), map(md.getColumnType(i), md.getColumnTypeName(i), precision, scale));
    }

    /** How one column of a result becomes one Arrow column. */
    static final class Column implements JdbcTableInput.ColumnReader {

        private static final HexFormat HEX = HexFormat.of().withUpperCase();

        private final String name;
        private final Mapped mapped;
        /** Set once the driver has said it cannot give {@code java.time} values: java.sql types from then on. */
        private boolean legacy;

        Column(String name, Mapped mapped) {
            this.name = name;
            this.mapped = mapped;
        }

        Mapped mapped() {
            return mapped;
        }

        @Override
        public Field field() {
            return new Field(name, FieldType.nullable(mapped.arrowType()), null);
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
                case FLOAT32 -> {
                    float v = rs.getFloat(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float4Vector) vector).setSafe(row, v);
                }
                case FLOAT64 -> {
                    double v = rs.getDouble(col);
                    if (rs.wasNull()) vector.setNull(row); else ((Float8Vector) vector).setSafe(row, v);
                }
                case DECIMAL -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    if (v == null) {
                        vector.setNull(row);
                    } else {
                        ((DecimalVector) vector).setSafe(row, exact(v));
                    }
                }
                case NUMBER_TEXT -> {
                    BigDecimal v = rs.getBigDecimal(col);
                    if (v == null) vector.setNull(row);
                    else ((VarCharVector) vector).setSafe(row, v.toPlainString().getBytes(StandardCharsets.UTF_8));
                }
                case TEXT -> {
                    Object o = rs.getObject(col);
                    String v = switch (o) {
                        case null -> null;
                        case String text -> text;
                        case byte[] bytes -> textOf(rs, col, bytes);
                        default -> rs.getString(col);
                    };
                    if (v == null) vector.setNull(row);
                    else ((VarCharVector) vector).setSafe(row, v.getBytes(StandardCharsets.UTF_8));
                }
                case BINARY -> {
                    byte[] v = rs.getBytes(col);
                    if (v == null) vector.setNull(row); else ((VarBinaryVector) vector).setSafe(row, v);
                }
                case DATE -> {
                    LocalDate v = legacy ? legacyDate(rs, col) : modern(rs, col, LocalDate.class);
                    if (v == null && !legacy && needsLegacy) {
                        v = legacyDate(rs, col);
                    }
                    if (v == null) vector.setNull(row); else ((DateDayVector) vector).setSafe(row, (int) v.toEpochDay());
                }
                case TIME -> {
                    LocalTime v = legacy ? legacyTime(rs, col) : modern(rs, col, LocalTime.class);
                    if (v == null && !legacy && needsLegacy) {
                        v = legacyTime(rs, col);
                    }
                    if (v == null) vector.setNull(row);
                    else ((TimeMicroVector) vector).setSafe(row, v.toNanoOfDay() / 1_000);
                }
                case TIMESTAMP -> {
                    LocalDateTime v = legacy ? legacyTimestamp(rs, col) : modern(rs, col, LocalDateTime.class);
                    if (v == null && !legacy && needsLegacy) {
                        v = legacyTimestamp(rs, col);
                    }
                    if (v == null) vector.setNull(row);
                    else ((TimeStampMicroVector) vector).setSafe(row, micros(v.toInstant(ZoneOffset.UTC)));
                }
                case TIMESTAMP_TZ -> {
                    java.time.Instant v;
                    if (legacy) {
                        v = legacyInstant(rs, col);
                    } else {
                        OffsetDateTime t = modern(rs, col, OffsetDateTime.class);
                        v = t != null ? t.toInstant() : needsLegacy ? legacyInstant(rs, col) : null;
                    }
                    if (v == null) vector.setNull(row);
                    else ((TimeStampMicroTZVector) vector).setSafe(row, micros(v));
                }
            }
            needsLegacy = false;
        }

        /**
         * A value the driver gives as bytes (a JSON or vendor type): its own text form when it has
         * one, otherwise the bytes in hex ({@code 0xCAFE}), never a Java object reference.
         */
        private static String textOf(ResultSet rs, int col, byte[] bytes) {
            try {
                String s = rs.getString(col);
                if (s != null && !s.startsWith("[B@")) {
                    return s;
                }
            } catch (SQLException | RuntimeException e) {
                // no text form: hex below
            }
            return "0x" + HEX.formatHex(bytes);
        }

        /** Set by {@link #modern} when the driver refused a java.time value for this row. */
        private boolean needsLegacy;

        /**
         * {@code rs.getObject(col, type)}; null with {@link #needsLegacy} set when the driver cannot
         * give that type (JDBC 4.1 drivers and older), after which this column uses java.sql types.
         */
        private <T> T modern(ResultSet rs, int col, Class<T> type) throws SQLException {
            try {
                return rs.getObject(col, type);
            } catch (SQLFeatureNotSupportedException | AbstractMethodError | UnsupportedOperationException e) {
                legacy = true;
                needsLegacy = true;
                return null;
            } catch (SQLException e) {
                if ("0A000".equals(e.getSQLState()) || (e.getSQLState() == null && looksUnsupported(e))) {
                    legacy = true;
                    needsLegacy = true;
                    return null;
                }
                throw e;
            }
        }

        private static boolean looksUnsupported(SQLException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            return m.contains("not supported") || m.contains("unsupported") || m.contains("conversion");
        }

        /** A decimal as its column's type holds it; one with more digits than the driver declared stops the read. */
        private BigDecimal exact(BigDecimal v) throws SQLException {
            try {
                BigDecimal e = v.setScale(mapped.scale(), RoundingMode.UNNECESSARY);
                if (e.precision() - e.scale() > mapped.precision() - mapped.scale() && e.signum() != 0) {
                    throw new ArithmeticException("too many digits");
                }
                return e;
            } catch (ArithmeticException x) {
                // Never the value itself: a message may reach a run's log or page (CLAUDE.md, "Writing").
                throw new SQLException("A value in column " + name + " does not fit "
                    + "decimal(" + mapped.precision() + "," + mapped.scale() + "), the type the driver reports for it, "
                    + "and is never rounded", "22003");
            }
        }

        private static LocalDate legacyDate(ResultSet rs, int col) throws SQLException {
            java.sql.Date d = rs.getDate(col);
            return d == null ? null : d.toLocalDate();
        }

        private static LocalTime legacyTime(ResultSet rs, int col) throws SQLException {
            java.sql.Timestamp t = rs.getTimestamp(col);
            return t == null ? null : t.toLocalDateTime().toLocalTime();
        }

        private static LocalDateTime legacyTimestamp(ResultSet rs, int col) throws SQLException {
            java.sql.Timestamp t = rs.getTimestamp(col);
            return t == null ? null : t.toLocalDateTime();
        }

        private static java.time.Instant legacyInstant(ResultSet rs, int col) throws SQLException {
            // in UTC, never the JVM's own time zone
            java.sql.Timestamp t = rs.getTimestamp(col, java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone(ZoneOffset.UTC)));
            return t == null ? null : t.toInstant();
        }
    }

    private static long micros(java.time.Instant t) {
        return ChronoUnit.MICROS.between(java.time.Instant.EPOCH, t);
    }
}
