package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import dev.hardwood.row.PqInterval;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;
import dev.hardwood.row.PqVariant;
import dev.hardwood.row.PqVariantArray;
import dev.hardwood.schema.SchemaNode;

/**
 * Nested Parquet values (structs, lists, maps, variants) as JSON text, so they can be read and
 * searched like JSON columns from other files. Bytes are written as {@code 0x} and upper-case hex,
 * as the JDBC connectors write them; dates and times as ISO 8601; intervals as
 * {@code {"months":..,"days":..,"milliseconds":..}}.
 */
final class ParquetJson {

    private static final JsonFactory FACTORY = JsonFactory.builder().build();
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private ParquetJson() {
    }

    /** {@code value}, described by schema node {@code node} (may be null), as JSON text. */
    static String json(Object value, SchemaNode node) {
        StringWriter w = new StringWriter();
        try (JsonGenerator g = FACTORY.createGenerator(w)) {
            write(g, value, node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return w.toString();
    }

    /** Bytes as text, the way the JDBC connectors write them: {@code 0xCAFE}. */
    static String hex(byte[] b) {
        return "0x" + HEX.formatHex(b);
    }

    private static void write(JsonGenerator g, Object v, SchemaNode node) throws IOException {
        switch (v) {
            case null -> g.writeNull();
            case PqStruct s -> {
                List<SchemaNode> children = node instanceof SchemaNode.GroupNode gn ? gn.children() : List.of();
                g.writeStartObject();
                for (int i = 0; i < children.size(); i++) {
                    g.writeFieldName(children.get(i).name());
                    write(g, s.isNull(i) ? null : s.getValue(i), children.get(i));
                }
                g.writeEndObject();
            }
            case PqList l -> {
                SchemaNode element = node instanceof SchemaNode.GroupNode gn && gn.isList() ? gn.getListElement() : null;
                g.writeStartArray();
                for (int i = 0; i < l.size(); i++) {
                    write(g, l.isNull(i) ? null : l.get(i), element);
                }
                g.writeEndArray();
            }
            case PqMap m -> {
                SchemaNode valueNode = node instanceof SchemaNode.GroupNode gn && gn.isMap() ? gn.getMapValue() : null;
                g.writeStartObject();
                for (PqMap.Entry e : m.getEntries()) {
                    Object k = e.getKey();
                    g.writeFieldName(k instanceof byte[] b ? hex(b) : String.valueOf(k));
                    write(g, e.isValueNull() ? null : e.getValue(), valueNode);
                }
                g.writeEndObject();
            }
            case PqVariant x -> variant(g, x);
            case PqInterval i -> {
                g.writeStartObject();
                g.writeNumberField("months", i.months());
                g.writeNumberField("days", i.days());
                g.writeNumberField("milliseconds", i.milliseconds());
                g.writeEndObject();
            }
            case Boolean b -> g.writeBoolean(b);
            case Integer i -> g.writeNumber(i);
            case Long l -> g.writeNumber(l);
            case Short s -> g.writeNumber(s);
            case Byte b -> g.writeNumber(b);
            case BigInteger b -> g.writeNumber(b);
            case BigDecimal d -> g.writeNumber(d);
            case Float f -> {
                if (f.isNaN() || f.isInfinite()) g.writeString(f.toString()); else g.writeNumber(f);
            }
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) g.writeString(d.toString()); else g.writeNumber(d);
            }
            case byte[] b -> g.writeString(hex(b));
            case String s -> g.writeString(s);
            default -> g.writeString(String.valueOf(v)); // dates, times, instants, UUIDs: ISO text
        }
    }

    private static void variant(JsonGenerator g, PqVariant v) throws IOException {
        if (v.isNull()) {
            g.writeNull();
            return;
        }
        switch (v.type()) {
            case NULL -> g.writeNull();
            case BOOLEAN_TRUE, BOOLEAN_FALSE -> g.writeBoolean(v.asBoolean());
            case INT8, INT16, INT32 -> g.writeNumber(v.asInt());
            case INT64 -> g.writeNumber(v.asLong());
            case FLOAT -> write(g, v.asFloat(), null);
            case DOUBLE -> write(g, v.asDouble(), null);
            case DECIMAL4, DECIMAL8, DECIMAL16 -> g.writeNumber(v.asDecimal());
            case DATE -> g.writeString(v.asDate().toString());
            case TIME_NTZ -> g.writeString(v.asTime().toString());
            case TIMESTAMP, TIMESTAMP_NANOS -> g.writeString(v.asTimestamp().toString());
            case TIMESTAMP_NTZ, TIMESTAMP_NTZ_NANOS -> g.writeString(v.asLocalTimestamp().toString());
            case BINARY -> g.writeString(hex(v.asBinary()));
            case STRING -> g.writeString(v.asString());
            case UUID -> g.writeString(v.asUuid().toString());
            case ARRAY -> {
                PqVariantArray a = v.asArray();
                g.writeStartArray();
                for (PqVariant e : a) {
                    variant(g, e);
                }
                g.writeEndArray();
            }
            // Hardwood offers no way to list a variant object's keys: keep its encoded bytes
            case OBJECT -> {
                g.writeStartObject();
                g.writeStringField("variant_metadata", hex(v.metadata()));
                g.writeStringField("variant_value", hex(v.value()));
                g.writeEndObject();
            }
        }
    }
}
