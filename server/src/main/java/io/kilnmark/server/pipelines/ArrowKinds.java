package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.graph.store.Records;

/**
 * The engine's kinds of value ({@link io.kilnmark.kiln.connect.AssetInfo.FieldInfo#kind()}: boolean,
 * int16, int32, int64, float32, float64, decimal, text, binary, date, time, timestamp, timestamp_tz)
 * and the Arrow types steps exchange, both ways.
 */
final class ArrowKinds {

    private ArrowKinds() {
    }

    /** The columns of a catalogued asset as an Arrow schema, for a read step's output. */
    static Schema schema(List<Records.FieldDetail> fields) {
        List<Field> out = new ArrayList<>();
        fields.stream().sorted(java.util.Comparator.comparingInt(Records.FieldDetail::position))
            .forEach(f -> out.add(new Field(f.name(), new FieldType(f.nullable(), type(f.kind()), null), null)));
        return new Schema(out);
    }

    static ArrowType type(String kind) {
        String k = kind == null ? "text" : kind.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "boolean" -> ArrowType.Bool.INSTANCE;
            case "int16" -> new ArrowType.Int(16, true);
            case "int32" -> new ArrowType.Int(32, true);
            case "int64" -> new ArrowType.Int(64, true);
            case "float32" -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
            case "float64" -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case "decimal" -> new ArrowType.Decimal(38, 10, 128);
            case "binary" -> ArrowType.Binary.INSTANCE;
            case "date" -> new ArrowType.Date(DateUnit.DAY);
            case "time" -> new ArrowType.Time(TimeUnit.MICROSECOND, 64);
            case "timestamp" -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
            case "timestamp_tz" -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
            default -> ArrowType.Utf8.INSTANCE;
        };
    }

    /** The engine's kind of an Arrow field's values. */
    static String kind(Field f) {
        return kind(f.getType());
    }

    static String kind(ArrowType t) {
        return switch (t) {
            case ArrowType.Bool b -> "boolean";
            case ArrowType.Int i -> i.getBitWidth() <= 16 ? "int16" : i.getBitWidth() <= 32 ? "int32" : "int64";
            case ArrowType.FloatingPoint f -> f.getPrecision() == FloatingPointPrecision.DOUBLE ? "float64" : "float32";
            case ArrowType.Decimal d -> "decimal";
            case ArrowType.Binary b -> "binary";
            case ArrowType.LargeBinary b -> "binary";
            case ArrowType.Date d -> "date";
            case ArrowType.Time tm -> "time";
            case ArrowType.Timestamp ts -> ts.getTimezone() == null ? "timestamp" : "timestamp_tz";
            default -> "text";
        };
    }
}
