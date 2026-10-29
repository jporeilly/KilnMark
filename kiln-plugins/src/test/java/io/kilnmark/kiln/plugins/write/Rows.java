package io.kilnmark.kiln.plugins.write;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeSpec;

/** Test rows: a node that sends them as Arrow batches, a node that collects what arrives, and runs. */
public final class Rows {

    private Rows() {
    }

    public static Field field(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }

    public static final ArrowType BOOL = ArrowType.Bool.INSTANCE;
    public static final ArrowType INT8 = new ArrowType.Int(8, true);
    public static final ArrowType INT16 = new ArrowType.Int(16, true);
    public static final ArrowType INT32 = new ArrowType.Int(32, true);
    public static final ArrowType INT64 = new ArrowType.Int(64, true);
    public static final ArrowType FLOAT32 = new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
    public static final ArrowType FLOAT64 = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    public static final ArrowType TEXT = ArrowType.Utf8.INSTANCE;
    public static final ArrowType BINARY = ArrowType.Binary.INSTANCE;
    public static final ArrowType DATE = new ArrowType.Date(DateUnit.DAY);
    public static final ArrowType TIME = new ArrowType.Time(TimeUnit.MICROSECOND, 64);
    public static final ArrowType TIMESTAMP = new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
    public static final ArrowType TIMESTAMP_TZ = new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");

    public static ArrowType decimal(int precision, int scale) {
        return new ArrowType.Decimal(precision, scale, 128);
    }

    /** Every kind, with nulls and awkward values: see {@link #allKinds}. */
    public static final Schema ALL_KINDS = new Schema(List.of(
        field("id", INT64), field("small", INT16), field("tiny", INT8), field("count", INT32),
        field("ratio", FLOAT32), field("score", FLOAT64), field("price", decimal(9, 2)),
        field("balance", decimal(18, 4)), field("huge", decimal(38, 10)), field("ok", BOOL),
        field("name", TEXT), field("raw", BINARY), field("born", DATE), field("at", TIME),
        field("local", TIMESTAMP), field("utc", TIMESTAMP_TZ)));

    /** {@code n} rows for {@link #ALL_KINDS}; every seventh row is all nulls (but its id). */
    public static List<Object[]> allKinds(int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean nulls = i % 7 == 3;
            rows.add(new Object[] {
                (long) i, nulls ? null : (short) (i % 30000 - 15000), nulls ? null : (byte) (i % 200 - 100),
                nulls ? null : i * 3 - 1000,
                nulls ? null : i / 8.0f, nulls ? null : i * 1.000001 - 50,
                nulls ? null : new BigDecimal(i % 1000 - 500).movePointLeft(2),
                nulls ? null : new BigDecimal((long) i * 104729 - 123456789).movePointLeft(4),
                nulls ? null : new BigDecimal("-1234567890123456789012345678.0123456789").add(BigDecimal.valueOf(i)),
                nulls ? null : i % 3 == 0,
                nulls ? null : i % 11 == 0 ? "" : "name " + i + " \"q\", é ✓\nline",
                nulls ? null : new byte[] {(byte) i, 0, (byte) 0xFF},
                nulls ? null : LocalDate.of(1950, 1, 1).plusDays(i),
                nulls ? null : LocalTime.of(i % 24, i % 60, i % 60, (i % 1000) * 1000),
                nulls ? null : LocalDateTime.of(2024, 1, 1, 0, 0).plusSeconds(i * 61L).plusNanos((i % 1000) * 1000L),
                nulls ? null : Instant.parse("2024-06-01T00:00:00Z").plusSeconds(i * 3601L).plusNanos(i * 1000L)});
        }
        return rows;
    }

    /** A small table: customer_id int64, total decimal(12,2), note text. */
    public static final Schema SPEND = new Schema(List.of(field("customer_id", INT64), field("total", decimal(12, 2)),
        field("note", TEXT)));

    /** Rows for {@link #SPEND}: ids {@code from} to {@code to - 1}, each with the same note. */
    public static List<Object[]> spend(int from, int to, String note) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(new Object[] {(long) i, new BigDecimal(i).movePointLeft(2), note});
        }
        return rows;
    }

    /** Compares a value given to a writer with what a reader gave back, by the engine's types. */
    public static void assertSameValue(Object want, Object got, String where) {
        if (want == null) {
            org.junit.jupiter.api.Assertions.assertNull(got, where);
            return;
        }
        switch (want) {
            case Short s -> org.junit.jupiter.api.Assertions.assertEquals((long) s, got, where);
            case Byte b -> org.junit.jupiter.api.Assertions.assertEquals((long) b, got, where);
            case Integer n -> org.junit.jupiter.api.Assertions.assertEquals((long) n, got, where);
            case BigDecimal d -> org.junit.jupiter.api.Assertions.assertEquals(0, d.compareTo((BigDecimal) got),
                where + ": " + d + " vs " + got);
            case byte[] b -> org.junit.jupiter.api.Assertions.assertArrayEquals(b, (byte[]) got, where);
            default -> org.junit.jupiter.api.Assertions.assertEquals(want, got, where);
        }
    }

    /** A source node sending {@code rows} in batches of {@code batchSize}; at least one batch, so the schema arrives. */
    public static Node source(Schema schema, List<Object[]> rows, int batchSize) {
        return source(schema, rows, batchSize, -1);
    }

    /** As {@link #source(Schema, List, int)}, failing instead of sending batch number {@code failAt} (0-based). */
    public static Node source(Schema schema, List<Object[]> rows, int batchSize, int failAt) {
        return new Node() {
            private int at;
            private int batch;

            @Override
            public String type() {
                return "test-rows";
            }

            @Override
            public void init(NodeContext context) {
                // nothing to open
            }

            @Override
            public boolean process(NodeContext context) throws NodeException {
                if (batch == failAt) {
                    throw new NodeException("the test source failed on purpose");
                }
                int n = Math.min(batchSize, rows.size() - at);
                VectorSchemaRoot root = VectorSchemaRoot.create(schema, context.allocator());
                root.allocateNew();
                for (int r = 0; r < n; r++) {
                    Object[] row = rows.get(at + r);
                    for (int c = 0; c < schema.getFields().size(); c++) {
                        set(root.getVector(c), r, row[c]);
                    }
                }
                root.setRowCount(n);
                at += n;
                batch++;
                try {
                    context.output().put(root);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NodeException("Interrupted", e);
                }
                return at < rows.size();
            }
        };
    }

    static void set(FieldVector v, int row, Object value) {
        if (value == null) {
            v.setNull(row);
            return;
        }
        switch (v) {
            case BitVector x -> x.setSafe(row, (Boolean) value ? 1 : 0);
            case TinyIntVector x -> x.setSafe(row, ((Number) value).byteValue());
            case SmallIntVector x -> x.setSafe(row, ((Number) value).shortValue());
            case IntVector x -> x.setSafe(row, ((Number) value).intValue());
            case BigIntVector x -> x.setSafe(row, ((Number) value).longValue());
            case Float4Vector x -> x.setSafe(row, ((Number) value).floatValue());
            case Float8Vector x -> x.setSafe(row, ((Number) value).doubleValue());
            // the vector's scale, as a reader would give it (1 becomes 1.00); never rounded
            case DecimalVector x -> x.setSafe(row, ((BigDecimal) value).setScale(x.getScale(),
                java.math.RoundingMode.UNNECESSARY));
            case VarCharVector x -> x.setSafe(row, ((String) value).getBytes(StandardCharsets.UTF_8));
            case VarBinaryVector x -> x.setSafe(row, (byte[]) value);
            case DateDayVector x -> x.setSafe(row, (int) ((LocalDate) value).toEpochDay());
            case TimeMicroVector x -> x.setSafe(row, ((LocalTime) value).toNanoOfDay() / 1000);
            case TimeStampMicroTZVector x -> x.setSafe(row, ChronoUnit.MICROS.between(Instant.EPOCH, (Instant) value));
            case TimeStampMicroVector x -> x.setSafe(row,
                ChronoUnit.MICROS.between(Instant.EPOCH, ((LocalDateTime) value).toInstant(ZoneOffset.UTC)));
            default -> throw new IllegalArgumentException("No test setter for " + v.getClass().getSimpleName());
        }
    }

    /** What a {@link #collector} saw: the columns and every row's values as {@link WriteColumn#value} gives them. */
    public static final class Collected {
        public volatile List<WriteColumn> columns = List.of();
        public final List<List<Object>> rows = Collections.synchronizedList(new ArrayList<>());
    }

    /** A last node keeping every row it receives. */
    public static Node collector(Collected into) {
        return new Node() {
            @Override
            public String type() {
                return "test-collect";
            }

            @Override
            public void init(NodeContext context) {
                // nothing to open
            }

            @Override
            public boolean process(NodeContext context) throws NodeException {
                VectorSchemaRoot b;
                try {
                    b = context.input().take(context.allocator());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NodeException("Interrupted", e);
                }
                if (b == null) {
                    return false;
                }
                try {
                    List<WriteColumn> cols = WriteColumn.of(b.getSchema());
                    into.columns = cols;
                    for (int r = 0; r < b.getRowCount(); r++) {
                        List<Object> row = new ArrayList<>();
                        for (int c = 0; c < cols.size(); c++) {
                            row.add(cols.get(c).value(b.getVector(c), r));
                        }
                        into.rows.add(row);
                    }
                } finally {
                    b.close();
                }
                return true;
            }
        };
    }

    /** Runs {@code first} then {@code second}, collecting the metadata events. */
    public static RunResult run(Node first, Node second, List<MetadataEvent> events) throws InterruptedException {
        return run(new NodeSpec("first", first, Map.of()), new NodeSpec("second", second, Map.of()), events);
    }

    public static RunResult run(NodeSpec first, NodeSpec second, List<MetadataEvent> events)
        throws InterruptedException {
        return new Runner(256L << 20, 2).run(new Pipeline("test", List.of(first, second)), events::add);
    }

    public static List<MetadataEvent> events() {
        return new CopyOnWriteArrayList<>();
    }

    public static List<Object[]> list(Object[]... rows) {
        return new ArrayList<>(List.of(rows));
    }
}
