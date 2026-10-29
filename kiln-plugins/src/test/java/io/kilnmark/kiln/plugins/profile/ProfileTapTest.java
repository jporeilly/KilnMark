package io.kilnmark.kiln.plugins.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.MetadataEvent;

/** The profiling tap writers use, on columns readers never produced: they profile instead of failing. */
class ProfileTapTest {

    static Field f(String name, ArrowType t) {
        return new Field(name, FieldType.nullable(t), null);
    }

    @Test
    void unusualColumnsAreProfiledAndEmptyTextAndNaNDoNotFail() throws Exception {
        Schema schema = new Schema(List.of(f("nan", new ArrowType.FloatingPoint(
                org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE)),
            f("u32", new ArrowType.Int(32, false)), f("d256", new ArrowType.Decimal(40, 2, 256)),
            f("large", ArrowType.LargeUtf8.INSTANCE), f("tiny", new ArrowType.Int(8, true)),
            f("empty", ArrowType.Utf8.INSTANCE), f("ms", new ArrowType.Timestamp(TimeUnit.MILLISECOND, null))));
        ProfileNode tap = ProfileNode.tap("t", Map.of("asset-urn", "urn:kilnmark:x:t"));
        try (RootAllocator a = new RootAllocator(); VectorSchemaRoot b = VectorSchemaRoot.create(schema, a)) {
            b.allocateNew();
            for (int i = 0; i < 4; i++) {
                ((Float8Vector) b.getVector(0)).setSafe(i, Double.NaN);
                ((UInt4Vector) b.getVector(1)).setSafe(i, i == 3 ? -1 : i); // -1 is 4294967295 unsigned
                ((Decimal256Vector) b.getVector(2)).setSafe(i, new BigDecimal(i).setScale(2));
                ((LargeVarCharVector) b.getVector(3)).setSafe(i, ("v" + i).getBytes(StandardCharsets.UTF_8));
                ((TinyIntVector) b.getVector(4)).setSafe(i, (byte) (i - 2));
                ((VarCharVector) b.getVector(5)).setSafe(i, "".getBytes(StandardCharsets.UTF_8));
                ((TimeStampMilliVector) b.getVector(6)).setSafe(i, 1_700_000_000_000L + i);
            }
            b.setRowCount(4);
            tap.accept(b);
        }
        List<MetadataEvent> events = new java.util.ArrayList<>();
        tap.report(events::add);
        Map<String, Map<String, String>> p = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> p.put(e.attributes().get("field"), e.attributes()));
        assertEquals(7, p.size());
        assertFalse(p.get("nan").containsKey("median"), "no quantiles when every value is NaN");
        assertEquals("4294967295", p.get("u32").get("max"));
        assertEquals("-2", p.get("tiny").get("min"));
        assertEquals("1", p.get("tiny").get("max"));
        assertEquals("3", p.get("d256").get("max"));
        assertEquals("2", p.get("large").get("min_length"));
        assertEquals("0", p.get("empty").get("max_length"));
        assertTrue(p.get("ms").get("min").startsWith("2023-11-14"), "milliseconds read as such: " + p.get("ms"));
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.SAMPLE));
    }
}
