package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

/**
 * How one JDBC column becomes one Arrow column: the Arrow type, and how to copy a value across.
 *
 * <p>Kilnmark reads JDBC itself rather than through Arrow's JDBC adapter, which failed on
 * PostgreSQL's unconstrained {@code numeric} and turns {@code timestamptz} into milliseconds (see
 * docs/spikes/0001-arrow-row-sets.md). Types with no Arrow equivalent (json, uuid, arrays, ranges
 * and the like) are read as text, so nothing is dropped.
 */
public record JdbcColumn(String name, String sourceType, Kind kind, ArrowType arrowType, int scale) {

    /** The engine's view of a column, which decides how it is copied and profiled. */
    public enum Kind { BOOLEAN, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL, TEXT, BINARY, DATE, TIME,
        TIMESTAMP, TIMESTAMP_TZ }

    /** Precision and scale for a numeric column declared without them. */
    static final int UNCONSTRAINED_PRECISION = 38;
    static final int UNCONSTRAINED_SCALE = 10;

    public Field field() {
        return new Field(name, FieldType.nullable(arrowType), null);
    }

    /** Maps column {@code i} (1-based) of a result. */
    public static JdbcColumn of(ResultSetMetaData md, int i) throws SQLException {
        return map(md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i), md.getPrecision(i),
            md.getScale(i));
    }

    /** Maps a column from its JDBC type, database type name, precision and scale. */
    public static JdbcColumn map(String name, int jdbcType, String typeName, int precision, int scale) {
        String type = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        if (type.equals("timestamptz") || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
            return new JdbcColumn(name, typeName, Kind.TIMESTAMP_TZ, new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"), 0);
        }
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> new JdbcColumn(name, typeName, Kind.BOOLEAN, ArrowType.Bool.INSTANCE, 0);
            case Types.TINYINT, Types.SMALLINT -> new JdbcColumn(name, typeName, Kind.INT16, new ArrowType.Int(16, true), 0);
            case Types.INTEGER -> new JdbcColumn(name, typeName, Kind.INT32, new ArrowType.Int(32, true), 0);
            case Types.BIGINT -> new JdbcColumn(name, typeName, Kind.INT64, new ArrowType.Int(64, true), 0);
            case Types.REAL -> new JdbcColumn(name, typeName, Kind.FLOAT32,
                new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE), 0);
            case Types.FLOAT, Types.DOUBLE -> new JdbcColumn(name, typeName, Kind.FLOAT64,
                new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE), 0);
            case Types.NUMERIC, Types.DECIMAL -> {
                boolean declared = precision > 0 && precision <= 38 && scale >= 0 && scale <= precision;
                int p = declared ? precision : UNCONSTRAINED_PRECISION;
                int s = declared ? scale : UNCONSTRAINED_SCALE;
                yield new JdbcColumn(name, typeName, Kind.DECIMAL, new ArrowType.Decimal(p, s, 128), s);
            }
            case Types.DATE -> new JdbcColumn(name, typeName, Kind.DATE, new ArrowType.Date(DateUnit.DAY), 0);
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> new JdbcColumn(name, typeName, Kind.TIME,
                new ArrowType.Time(TimeUnit.MICROSECOND, 64), 0);
            case Types.TIMESTAMP -> new JdbcColumn(name, typeName, Kind.TIMESTAMP,
                new ArrowType.Timestamp(TimeUnit.MICROSECOND, null), 0);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> new JdbcColumn(name, typeName,
                Kind.BINARY, ArrowType.Binary.INSTANCE, 0);
            default -> new JdbcColumn(name, typeName, Kind.TEXT, ArrowType.Utf8.INSTANCE, 0);
        };
    }

    /** Copies column {@code col} (1-based) of the current row into {@code vector} at {@code row}. */
    public void copy(ResultSet rs, int col, FieldVector vector, int row) throws SQLException {
        switch (kind) {
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
                if (v == null) vector.setNull(row);
                else ((DecimalVector) vector).setSafe(row, v.setScale(scale, RoundingMode.HALF_EVEN));
            }
            case TEXT -> {
                String v = rs.getString(col);
                if (v == null) vector.setNull(row);
                else ((VarCharVector) vector).setSafe(row, v.getBytes(StandardCharsets.UTF_8));
            }
            case BINARY -> {
                byte[] v = rs.getBytes(col);
                if (v == null) vector.setNull(row); else ((VarBinaryVector) vector).setSafe(row, v);
            }
            case DATE -> {
                LocalDate v = rs.getObject(col, LocalDate.class);
                if (v == null) vector.setNull(row); else ((DateDayVector) vector).setSafe(row, (int) v.toEpochDay());
            }
            case TIME -> {
                LocalTime v = rs.getObject(col, LocalTime.class);
                if (v == null) vector.setNull(row);
                else ((TimeMicroVector) vector).setSafe(row, v.toNanoOfDay() / 1_000);
            }
            case TIMESTAMP -> {
                LocalDateTime v = rs.getObject(col, LocalDateTime.class);
                if (v == null) vector.setNull(row);
                else ((TimeStampMicroVector) vector).setSafe(row, micros(v.toInstant(ZoneOffset.UTC)));
            }
            case TIMESTAMP_TZ -> {
                OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
                if (v == null) vector.setNull(row);
                else ((TimeStampMicroTZVector) vector).setSafe(row, micros(v.toInstant()));
            }
        }
    }

    private static long micros(java.time.Instant t) {
        return ChronoUnit.MICROS.between(java.time.Instant.EPOCH, t);
    }
}
