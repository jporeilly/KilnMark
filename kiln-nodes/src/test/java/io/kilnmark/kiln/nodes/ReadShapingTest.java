package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.INT64;
import static io.kilnmark.kiln.nodes.Harness.UTF8;
import static io.kilnmark.kiln.nodes.Harness.field;
import static io.kilnmark.kiln.nodes.Harness.row;
import static io.kilnmark.kiln.nodes.Harness.rowsOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.nodes.expr.ExprException;

class ReadShapingTest {

    private final BufferAllocator allocator = new RootAllocator();
    private static final Schema ORDERS = Harness.schema(field("order_id", INT64), field("status", UTF8),
        field("note", UTF8));

    @AfterEach
    void noLeaks() {
        assertEquals(0, allocator.getAllocatedMemory(), "every batch is closed");
        allocator.close();
    }

    private VectorSchemaRoot orders() {
        return Harness.batch(allocator, ORDERS, row(1, "delivered", "a"), row(2, "shipped", "b"),
            row(3, "delivered", "c"), row(4, "Delivered", "d"));
    }

    @Test
    void keepsTheRowsMeetingTheConditionWithTheChosenColumns() throws Exception {
        ReadShaping s = new ReadShaping("status = 'delivered'", List.of("status", "order_id"), 0);
        try (VectorSchemaRoot out = s.apply(orders(), allocator, true)) {
            assertEquals(List.of(Map.of("status", "delivered", "order_id", 1L),
                Map.of("status", "delivered", "order_id", 3L)), rowsOf(out));
            assertEquals(List.of("status", "order_id"), out.getSchema().getFields().stream().map(f -> f.getName()).toList());
        }
        assertFalse(s.full());
    }

    /** A database comparing without regard to case sends "Delivered" too: Kilnmark drops it, as it would. */
    @Test
    void theRowsAreKilnmarksWhateverTheSourceSent() throws Exception {
        ReadShaping s = new ReadShaping("status = 'delivered'", List.of(), 0);
        try (VectorSchemaRoot out = s.apply(orders(), allocator, true)) {
            assertEquals(2, out.getRowCount());
        }
    }

    @Test
    void theRowLimitCountsRowsAfterTheCondition() throws Exception {
        ReadShaping s = new ReadShaping("status = 'delivered'", List.of(), 1);
        try (VectorSchemaRoot out = s.apply(orders(), allocator, true)) {
            assertEquals(1, out.getRowCount());
            assertEquals(1L, rowsOf(out).get(0).get("order_id"));
        }
        assertTrue(s.full());
    }

    @Test
    void anEmptyBatchKeepsTheColumnsOnceThenNothing() throws Exception {
        ReadShaping s = new ReadShaping("order_id > 100", List.of("order_id"), 0);
        try (VectorSchemaRoot first = s.apply(orders(), allocator, true)) {
            assertEquals(0, first.getRowCount());
            assertEquals(1, first.getSchema().getFields().size());
        }
        assertNull(s.apply(orders(), allocator, false));
    }

    @Test
    void neededColumnsIncludeWhatTheConditionReads() throws ExprException {
        assertEquals(List.of("order_id", "status"), ReadShaping.needed(ORDERS, List.of("order_id"), "status = 'x'"));
        assertEquals(List.of(), ReadShaping.needed(ORDERS, List.of(), "status = 'x'"), "every column");
    }

    @Test
    void theOutputIsTheChosenColumnsAndTheConditionIsChecked() throws ExprException {
        assertEquals(List.of("note", "order_id"), ReadShaping.outputSchema(ORDERS, List.of("note", "order_id"), null)
            .getFields().stream().map(f -> f.getName()).toList());
        assertTrue(assertThrows(ExprException.class, () -> ReadShaping.outputSchema(ORDERS, List.of("nope"), null))
            .getMessage().contains("'nope' is not a column"));
        assertThrows(ExprException.class, () -> ReadShaping.outputSchema(ORDERS, List.of(), "order_id + 1"));
        assertThrows(ExprException.class, () -> ReadShaping.outputSchema(ORDERS, List.of(), "nope = 1"));
    }
}
