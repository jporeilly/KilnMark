package io.kilnmark.kiln.plugins.files;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * One column of a file: its name, the engine's kind for it and the Arrow type it is read as. The
 * kinds and Arrow types are the ones {@link io.kilnmark.kiln.plugins.jdbc.JdbcColumn} uses, so the
 * profiler treats files and tables alike.
 *
 * @param sourceType what the file says (Parquet) or what inference chose (CSV, JSON), for example
 *                   {@code decimal(38,2)}
 * @param precision  decimal precision; 0 for other kinds
 * @param scale      decimal scale; 0 for other kinds
 */
public record FileColumn(String name, String sourceType, Kind kind, int precision, int scale) {

    /** Precision of decimals inferred from text, the most Arrow's 128-bit decimal holds. */
    public static final int MAX_PRECISION = 38;

    private static final Pattern DECIMAL = Pattern.compile("decimal\\((\\d+),(\\d+)\\)");

    /** A column inferred from text: its source type is the kind itself, for example {@code int64}. */
    public static FileColumn inferred(String name, Kind kind, int scale) {
        if (kind == Kind.DECIMAL) {
            return new FileColumn(name, "decimal(" + MAX_PRECISION + "," + scale + ")", kind, MAX_PRECISION, scale);
        }
        return new FileColumn(name, kind.name().toLowerCase(Locale.ROOT), kind, 0, 0);
    }

    /** The column a catalogued field describes, reading decimal precision and scale from its source type. */
    public static FileColumn of(AssetInfo.FieldInfo f) {
        Kind kind = Kind.valueOf(f.kind().toUpperCase(Locale.ROOT));
        if (kind == Kind.DECIMAL) {
            Matcher m = DECIMAL.matcher(f.sourceType() == null ? "" : f.sourceType().toLowerCase(Locale.ROOT));
            if (m.find()) {
                return new FileColumn(f.name(), f.sourceType(), kind, Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            }
            return new FileColumn(f.name(), f.sourceType(), kind, MAX_PRECISION, 10);
        }
        return new FileColumn(f.name(), f.sourceType(), kind, 0, 0);
    }

    /** As a catalogue field at {@code position} (1-based). */
    public AssetInfo.FieldInfo fieldInfo(int position) {
        return new AssetInfo.FieldInfo(name, position, sourceType, kind.name().toLowerCase(Locale.ROOT), true, null);
    }

    public ArrowType arrowType() {
        return arrowType(kind, precision, scale);
    }

    public Field field() {
        return new Field(name, FieldType.nullable(arrowType()), null);
    }

    /** The Arrow type for a kind, matching {@code JdbcColumn}. */
    static ArrowType arrowType(Kind kind, int precision, int scale) {
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
