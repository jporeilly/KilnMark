package io.kilnmark.kiln.plugins.files;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.SchemaElement;
import dev.hardwood.row.StructAccessor;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * How a Parquet file's top-level fields map to the engine's kinds and Arrow types, and how each
 * value is copied across.
 *
 * <p>Integers keep their width (8- and 16-bit as int16, unsigned ones widened); timestamps adjusted
 * to UTC (and legacy {@code INT96} and converted-type timestamps) are timestamp_tz, others
 * timestamp, both in microseconds; decimals up to 38 digits are decimal; strings, enums, JSON and
 * UUIDs are text; other byte arrays are binary. Nested groups (lists, maps, structs) are read as
 * JSON text, so nothing is dropped.
 */
final class ParquetFields {

    /** One top-level field: the column it becomes and what is needed to copy its values. */
    record ParquetField(FileColumn column, PhysicalType physical, boolean unsigned, boolean group, boolean uuid,
                        SchemaNode node) {

        ParquetField withNode(SchemaNode n) {
            return new ParquetField(column, physical, unsigned, group, uuid, n);
        }
    }

    private ParquetFields() {
    }

    /** The file's top-level fields, in order, with their schema nodes when {@code schema} is given. */
    static List<ParquetField> of(FileMetaData md, FileSchema schema) {
        List<SchemaElement> elements = md.schema();
        List<ParquetField> out = new ArrayList<>();
        if (elements.isEmpty()) {
            return out;
        }
        List<SchemaNode> nodes = schema == null ? List.of() : schema.getRootNode().children();
        int children = children(elements.get(0));
        int i = 1;
        for (int c = 0; c < children && i < elements.size(); c++) {
            ParquetField f = map(elements.get(i));
            out.add(c < nodes.size() ? f.withNode(nodes.get(c)) : f);
            i = skip(elements, i);
        }
        return out;
    }

    private static int children(SchemaElement e) {
        return e.numChildren() == null ? 0 : Math.max(0, e.numChildren());
    }

    /** The index just after the subtree starting at {@code i}; iterative, so deep nesting is safe. */
    private static int skip(List<SchemaElement> elements, int i) {
        int next = i + 1;
        long pending = children(elements.get(i));
        while (pending > 0 && next < elements.size()) {
            pending += children(elements.get(next)) - 1;
            next++;
        }
        return next;
    }

    static ParquetField map(SchemaElement e) {
        String name = e.name();
        LogicalType lt = e.logicalType();
        ConvertedType ct = e.convertedType();
        if (e.isGroup() || e.type() == null || e.repetitionType() == RepetitionType.REPEATED) {
            String what = ct == ConvertedType.LIST || lt instanceof LogicalType.ListType ? "list"
                : ct == ConvertedType.MAP || ct == ConvertedType.MAP_KEY_VALUE || lt instanceof LogicalType.MapType
                ? "map" : e.isGroup() ? "group" : "repeated " + e.type().name();
            return new ParquetField(new FileColumn(name, what, Kind.TEXT, 0, 0), e.type(), false, true, false, null);
        }
        PhysicalType pt = e.type();
        String source = pt.name() + (lt != null ? " " + describe(lt) : ct != null ? " " + ct.name() : "");
        // decimals, whatever they are stored as
        Integer precision = null;
        Integer scale = null;
        if (lt instanceof LogicalType.DecimalType d) {
            precision = d.precision();
            scale = d.scale();
        } else if (ct == ConvertedType.DECIMAL) {
            precision = e.precision();
            scale = e.scale() == null ? 0 : e.scale();
        }
        if (precision != null) {
            if (precision <= FileColumn.MAX_PRECISION) {
                return field(name, source, Kind.DECIMAL, precision, scale, pt);
            }
            return field(name, source, Kind.TEXT, 0, 0, pt);
        }
        return switch (pt) {
            case BOOLEAN -> field(name, source, Kind.BOOLEAN, 0, 0, pt);
            case INT32 -> {
                if (lt instanceof LogicalType.DateType || ct == ConvertedType.DATE) {
                    yield field(name, source, Kind.DATE, 0, 0, pt);
                }
                if (lt instanceof LogicalType.TimeType || ct == ConvertedType.TIME_MILLIS) {
                    yield field(name, source, Kind.TIME, 0, 0, pt);
                }
                int bits = 32;
                boolean signed = true;
                if (lt instanceof LogicalType.IntType it) {
                    bits = it.bitWidth();
                    signed = it.isSigned();
                } else if (ct != null) {
                    switch (ct) {
                        case INT_8 -> bits = 8;
                        case INT_16 -> bits = 16;
                        case UINT_8 -> { bits = 8; signed = false; }
                        case UINT_16 -> { bits = 16; signed = false; }
                        case UINT_32 -> signed = false;
                        default -> { }
                    }
                }
                if (bits == 8 || (bits == 16 && signed)) {
                    yield field(name, source, Kind.INT16, 0, 0, pt);
                }
                if (bits == 16 || signed) {
                    yield field(name, source, Kind.INT32, 0, 0, pt);
                }
                yield new ParquetField(new FileColumn(name, source, Kind.INT64, 0, 0), pt, true, false, false, null);
            }
            case INT64 -> {
                if (lt instanceof LogicalType.TimestampType ts) {
                    yield field(name, source, ts.isAdjustedToUTC() ? Kind.TIMESTAMP_TZ : Kind.TIMESTAMP, 0, 0, pt);
                }
                if (ct == ConvertedType.TIMESTAMP_MILLIS || ct == ConvertedType.TIMESTAMP_MICROS) {
                    yield field(name, source, Kind.TIMESTAMP_TZ, 0, 0, pt);
                }
                if (lt instanceof LogicalType.TimeType || ct == ConvertedType.TIME_MICROS) {
                    yield field(name, source, Kind.TIME, 0, 0, pt);
                }
                boolean unsigned = lt instanceof LogicalType.IntType it ? !it.isSigned() : ct == ConvertedType.UINT_64;
                if (unsigned) {
                    yield new ParquetField(new FileColumn(name, source, Kind.DECIMAL, 20, 0), pt, true, false, false, null);
                }
                yield field(name, source, Kind.INT64, 0, 0, pt);
            }
            case INT96 -> field(name, source, Kind.TIMESTAMP_TZ, 0, 0, pt);
            case FLOAT -> field(name, source, Kind.FLOAT32, 0, 0, pt);
            case DOUBLE -> field(name, source, Kind.FLOAT64, 0, 0, pt);
            case BYTE_ARRAY -> {
                boolean text = lt instanceof LogicalType.StringType || lt instanceof LogicalType.EnumType
                    || lt instanceof LogicalType.JsonType || ct == ConvertedType.UTF8 || ct == ConvertedType.ENUM
                    || ct == ConvertedType.JSON;
                yield field(name, source, text ? Kind.TEXT : Kind.BINARY, 0, 0, pt);
            }
            case FIXED_LEN_BYTE_ARRAY -> {
                if (lt instanceof LogicalType.UuidType) {
                    yield new ParquetField(new FileColumn(name, source, Kind.TEXT, 0, 0), pt, false, false, true, null);
                }
                if (lt instanceof LogicalType.Float16Type) {
                    yield field(name, source, Kind.FLOAT32, 0, 0, pt);
                }
                yield field(name, source, Kind.BINARY, 0, 0, pt);
            }
        };
    }

    private static ParquetField field(String name, String source, Kind kind, int precision, int scale,
                                      PhysicalType pt) {
        return new ParquetField(new FileColumn(name, source, kind, precision, scale), pt, false, false, false, null);
    }

    private static String describe(LogicalType lt) {
        String s = lt.getClass().getSimpleName().replace("Type", "").toUpperCase(Locale.ROOT);
        return switch (lt) {
            case LogicalType.DecimalType d -> "DECIMAL(" + d.precision() + "," + d.scale() + ")";
            case LogicalType.TimestampType t -> "TIMESTAMP(" + t.unit() + (t.isAdjustedToUTC() ? ", UTC)" : ")");
            case LogicalType.TimeType t -> "TIME(" + t.unit() + ")";
            case LogicalType.IntType t -> "INT(" + t.bitWidth() + (t.isSigned() ? ")" : ", unsigned)");
            default -> s;
        };
    }

    /** Copies field {@code i} of the current row into {@code v} at {@code row}. */
    static void copy(ParquetField f, StructAccessor r, int i, FieldVector v, int row) {
        if (r.isNull(i)) {
            v.setNull(row);
            return;
        }
        FileColumn c = f.column();
        switch (c.kind()) {
            case BOOLEAN -> ((BitVector) v).setSafe(row, r.getBoolean(i) ? 1 : 0);
            case INT16 -> ((SmallIntVector) v).setSafe(row, (short) r.getInt(i));
            case INT32 -> ((IntVector) v).setSafe(row, r.getInt(i));
            case INT64 -> ((BigIntVector) v).setSafe(row,
                f.unsigned() ? Integer.toUnsignedLong(r.getInt(i)) : r.getLong(i));
            case FLOAT32 -> ((Float4Vector) v).setSafe(row, r.getFloat(i));
            case FLOAT64 -> ((Float8Vector) v).setSafe(row, r.getDouble(i));
            case DECIMAL -> {
                BigDecimal d = f.unsigned() ? new BigDecimal(Long.toUnsignedString(r.getLong(i))) : r.getDecimal(i);
                ((DecimalVector) v).setSafe(row, d.setScale(c.scale(), RoundingMode.HALF_EVEN));
            }
            case DATE -> ((DateDayVector) v).setSafe(row, (int) r.getDate(i).toEpochDay());
            case TIME -> ((TimeMicroVector) v).setSafe(row, r.getTime(i).toNanoOfDay() / 1_000);
            case TIMESTAMP -> ((TimeStampMicroVector) v).setSafe(row,
                TextValues.micros(r.getLocalTimestamp(i).toInstant(ZoneOffset.UTC)));
            case TIMESTAMP_TZ -> ((TimeStampMicroTZVector) v).setSafe(row, TextValues.micros(r.getTimestamp(i)));
            case TEXT -> {
                String s = f.group() ? ParquetJson.json(r.getValue(i), f.node())
                    : f.uuid() ? r.getUuid(i).toString()
                    : c.sourceType().contains("DECIMAL") ? r.getDecimal(i).toPlainString() // over 38 digits
                    : f.physical() == PhysicalType.BYTE_ARRAY ? r.getString(i)
                    : ParquetJson.json(r.getValue(i), f.node());
                ((VarCharVector) v).setSafe(row, s.getBytes(StandardCharsets.UTF_8));
            }
            case BINARY -> ((VarBinaryVector) v).setSafe(row, r.getBinary(i));
        }
    }
}
