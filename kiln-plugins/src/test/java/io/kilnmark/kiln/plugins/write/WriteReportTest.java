package io.kilnmark.kiln.plugins.write;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.MetadataEvent;

/** What writers report: the whole asset for create, replace and files; only a row count for append and upsert. */
class WriteReportTest {

    static final Schema SCHEMA = new Schema(List.of(field("id", Rows.INT64), field("note", Rows.TEXT)));

    static List<MetadataEvent> report(boolean whole, String mode) throws Exception {
        WriteReport r = new WriteReport("w", "urn:kilnmark:db:billing.t", new WriteSettings(Map.of()), whole);
        try (RootAllocator a = new RootAllocator(); VectorSchemaRoot b = VectorSchemaRoot.create(SCHEMA, a)) {
            b.allocateNew();
            for (int i = 0; i < 3; i++) {
                Rows.set(b.getVector(0), i, (long) i);
                Rows.set(b.getVector(1), i, i == 1 ? "" : "n" + i);
            }
            b.setRowCount(3);
            r.accept(b);
        }
        List<MetadataEvent> events = Rows.events();
        r.emit(events::add, WriteColumn.of(SCHEMA), List.of("bigint", "text"), Map.of("target", "billing.t", "mode", mode));
        return events;
    }

    @Test
    void aWholeAssetIsReportedWithSchemaCountAndProfile() throws Exception {
        List<MetadataEvent> events = report(true, "replace");
        assertEquals(List.of(MetadataEvent.Kind.SCHEMA, MetadataEvent.Kind.ROW_COUNT, MetadataEvent.Kind.PROFILE,
            MetadataEvent.Kind.PROFILE, MetadataEvent.Kind.SAMPLE), events.stream().map(MetadataEvent::kind).toList());
        assertEquals("true", events.get(1).attributes().get("complete"));
        assertEquals("3", events.get(1).attributes().get("rows"));
    }

    @Test
    void anAppendIsReportedAsRowsAddedNotAsTheTable() throws Exception {
        List<MetadataEvent> events = report(false, "append");
        assertEquals(1, events.size());
        assertEquals(MetadataEvent.Kind.ROW_COUNT, events.get(0).kind());
        assertEquals(Map.of("rows", "3", "complete", "false", "written", "true", "appended", "true", "mode", "append"),
            events.get(0).attributes());
    }

    @Test
    void decimalsTooWideForADecimalColumnAreWrittenInFull() {
        WriteColumn c = WriteColumn.of(field("x", new org.apache.arrow.vector.types.pojo.ArrowType.Decimal(60, 2, 256)));
        assertEquals(io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind.TEXT, c.kind());
        try (RootAllocator a = new RootAllocator();
             VectorSchemaRoot b = VectorSchemaRoot.create(new Schema(List.of(field("x", c.arrowType()))), a)) {
            b.allocateNew();
            ((org.apache.arrow.vector.Decimal256Vector) b.getVector(0)).setSafe(0, new BigDecimal("1E+50").setScale(2));
            b.setRowCount(1);
            String text = (String) c.value(b.getVector(0), 0);
            assertTrue(text.startsWith("10000000000") && !text.contains("E"), text);
        }
    }
}
