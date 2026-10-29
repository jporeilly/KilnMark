package io.kilnmark.kiln.nodes.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecTZVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;

/** Expressions over real Arrow batches: every column type they read, and results written back. */
class ArrowIOTest {

    @Test
    void readsEveryKindOfColumnAndWritesResults() throws Exception {
        try (BufferAllocator a = new RootAllocator(Long.MAX_VALUE);
             IntVector qty = new IntVector("qty", a);
             Float4Vector rate = new Float4Vector("rate", a);
             DecimalVector price = new DecimalVector("price", a, 10, 2);
             VarCharVector name = new VarCharVector("name", a);
             LargeVarCharVector note = new LargeVarCharVector("note", a);
             BitVector ok = new BitVector("ok", a);
             DateMilliVector day = new DateMilliVector("day", a);
             TimeStampSecTZVector at = new TimeStampSecTZVector("at", a, "Europe/London");
             TimeStampNanoVector precise = new TimeStampNanoVector("precise", a);
             VarBinaryVector blob = new VarBinaryVector("blob", a)) {
            qty.setSafe(0, 3);
            qty.setNull(1);
            rate.setSafe(0, 0.5f);
            rate.setSafe(1, 2f);
            price.setSafe(0, new BigDecimal("9.99"));
            price.setSafe(1, new BigDecimal("1.00"));
            name.setSafe(0, "Ada".getBytes(StandardCharsets.UTF_8));
            name.setSafe(1, "Grace".getBytes(StandardCharsets.UTF_8));
            note.setSafe(0, "x".getBytes(StandardCharsets.UTF_8));
            note.setNull(1);
            ok.setSafe(0, 1);
            ok.setSafe(1, 0);
            day.setSafe(0, 19_753L * 86_400_000L); // 2024-01-31
            day.setSafe(1, 0);
            at.setSafe(0, 1_706_693_400L); // 2024-01-31T09:30:00Z
            at.setSafe(1, 0);
            precise.setSafe(0, 1_706_693_400_123_456_789L);
            precise.setSafe(1, -1L);
            blob.setSafe(0, new byte[] {1});
            blob.setSafe(1, new byte[] {2});
            List<FieldVector> vectors = List.of(qty, rate, price, name, note, ok, day, at, precise, blob);
            vectors.forEach(v -> v.setValueCount(2));
            VectorSchemaRoot root = new VectorSchemaRoot(vectors);
            Batch b = Batch.of(root, 0);

            Expression.Compiled total = Expression.parse("qty * price").compile(root.getSchema());
            Col c = total.eval(b);
            assertEquals(new BigDecimal("29.97"), c.get(0));
            assertTrue(c.isNull(1));
            assertEquals(1.0, Expression.parse("rate * 2").compile(root.getSchema()).eval(b).get(0));
            assertEquals("Adax!", Expression.parse("name || coalesce(note, '') || '!'").compile(root.getSchema())
                .eval(b).get(0));
            assertEquals(true, Expression.parse("ok and day = date '2024-01-31'").compile(root.getSchema())
                .eval(b).get(0));
            assertEquals(true, Expression.parse("at = timestamp '2024-01-31 09:30:00'").compile(root.getSchema())
                .eval(b).get(0));
            assertEquals(123L, Expression.parse("cast(format_date(precise, 'SSS') as int64)")
                .compile(root.getSchema()).eval(b).get(0));
            assertEquals(0L, Expression.parse("date_part('second', precise) - 59").compile(root.getSchema())
                .eval(b).get(1));
            ExprException blobError = assertThrows(ExprException.class,
                () -> Expression.parse("blob = 1").compile(root.getSchema()));
            assertTrue(blobError.getMessage().startsWith("the column 'blob' holds Binary values, which expressions "
                + "cannot use at 1"), blobError.getMessage());

            // results written back as Arrow: decimal(38,2), and an int32 field from whole numbers
            try (FieldVector out = ArrowIO.write(c, total.field("total"), a);
                 FieldVector narrow = ArrowIO.write(ArrowIO.read(qty),
                     new Field("q", FieldType.nullable(new ArrowType.Int(32, true)), null), a)) {
                assertEquals(new ArrowType.Decimal(38, 2, 128), out.getField().getType());
                assertEquals(new BigDecimal("29.97"), out.getObject(0));
                assertTrue(out.isNull(1));
                assertEquals(3, narrow.getObject(0));
            }
            Col huge = Col.nulls(Type.decimal(38, 0), 1);
            huge.set(0, new BigDecimal("1".repeat(38)));
            ExprException tooBig = assertThrows(ExprException.class,
                () -> ArrowIO.write(huge, new Field("x", FieldType.nullable(new ArrowType.Decimal(5, 0, 128)), null), a));
            assertTrue(tooBig.getMessage().startsWith("a decimal value has more digits than decimal(5,0) holds"));
        }
    }
}
