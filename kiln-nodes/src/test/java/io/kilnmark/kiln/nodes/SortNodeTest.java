package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.BOOL;
import static io.kilnmark.kiln.nodes.Harness.DATE;
import static io.kilnmark.kiln.nodes.Harness.FLOAT64;
import static io.kilnmark.kiln.nodes.Harness.INT32;
import static io.kilnmark.kiln.nodes.Harness.INT64;
import static io.kilnmark.kiln.nodes.Harness.TIMESTAMP;
import static io.kilnmark.kiln.nodes.Harness.UTF8;
import static io.kilnmark.kiln.nodes.Harness.dec;
import static io.kilnmark.kiln.nodes.Harness.decimal;
import static io.kilnmark.kiln.nodes.Harness.field;
import static io.kilnmark.kiln.nodes.Harness.row;
import static io.kilnmark.kiln.nodes.Harness.schema;
import static io.kilnmark.kiln.nodes.RowNodesTest.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowSet;

/** sort: ordering, stability, nulls, every type, and the path that spills to disk. */
class SortNodeTest {

    static final Schema ORDERS = schema(field("id", INT64), field("region", UTF8), field("amount", decimal(10, 2)));

    private static Harness orders(Harness h) {
        return h.input("in",
            h.batch(ORDERS, row(1L, "north", dec("10.00")), row(2L, "south", dec("5.50")), row(3L, null, dec("7.00"))),
            h.batch(ORDERS, row(4L, "north", dec("2.00")), row(5L, "east", null), row(6L, "south", dec("5.50"))));
    }

    @Test
    void ordersAscendingWithNullsLastByDefault() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new SortFactory(), config("by", List.of("amount")));
            assertEquals(List.of(4L, 2L, 6L, 3L, 1L, 5L), h.column("out", "id"));
            assertEquals(ORDERS, h.schemas.get("out"), "the columns and their types are kept");
        }
    }

    @Test
    void descendingKeepsNullsLastUnlessTold() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new SortFactory(), config("by", List.of("amount desc")));
            assertEquals(List.of(1L, 3L, 2L, 6L, 4L, 5L), h.column("out", "id"));
        }
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new SortFactory(), config("by", List.of("amount DESC NULLS FIRST")));
            assertEquals(List.of(5L, 1L, 3L, 2L, 6L, 4L), h.column("out", "id"));
        }
    }

    @Test
    void severalColumnsAndEqualRowsKeepTheirOrder() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new SortFactory(), config("by", List.of("region nulls first", "amount desc")));
            // null region first; then east, north (10.00 before 2.00), south (two 5.50s in arrival order)
            assertEquals(List.of(3L, 5L, 1L, 4L, 2L, 6L), h.column("out", "id"));
        }
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new SortFactory(), config("by", "region asc nulls last, id desc"));
            assertEquals(List.of(5L, 4L, 1L, 6L, 2L, 3L), h.column("out", "id"));
        }
    }

    @Test
    void valuesOrderAsExpressionsOrderThem() throws Exception {
        Schema s = schema(field("t", UTF8), field("f", FLOAT64), field("d", DATE), field("ts", TIMESTAMP),
            field("b", BOOL), field("i", INT32));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s,
                row("a", Double.NaN, LocalDate.of(2024, 1, 2), Instant.parse("2024-01-01T00:00:01Z"), true, -5),
                row("B", Double.POSITIVE_INFINITY, LocalDate.of(1969, 12, 31), Instant.parse("1969-12-31T23:59:59Z"), false, 3),
                row("é", -0.0, LocalDate.of(2024, 1, 1), Instant.parse("2024-01-01T00:00:00Z"), null, null),
                row("😀", 0.0, null, null, true, Integer.MIN_VALUE),
                row("�", Double.NEGATIVE_INFINITY, LocalDate.of(2024, 1, 1), null, false, Integer.MAX_VALUE),
                row("", -1.5, LocalDate.of(1, 1, 1), Instant.parse("0001-01-01T00:00:00Z"), true, 0)));
            h.connect("out");
            h.run(new SortFactory(), config("by", List.of("t")));
            // code point order: "" < "B" < "a" < "é" < U+FFFD < U+1F600 (UTF-16 order would put the emoji before U+FFFD)
            assertEquals(Arrays.asList("", "B", "a", "é", "�", "😀"), h.column("out", "t"));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(schema(field("f", FLOAT64), field("n", INT64)),
                row(Double.NaN, 1L), row(Double.POSITIVE_INFINITY, 2L), row(-0.0, 3L), row(0.0, 4L),
                row(Double.NEGATIVE_INFINITY, 5L), row(null, 6L), row(-1.5, 7L), row(Double.MIN_VALUE, 8L)));
            h.connect("out");
            h.run(new SortFactory(), config("by", List.of("f")));
            // -0.0 equals 0.0 (they keep their order); NaN after +infinity, as in expressions
            assertEquals(List.of(5L, 7L, 3L, 4L, 8L, 2L, 1L, 6L), h.column("out", "n"));
        }
        Schema typed = schema(field("d", DATE), field("ts", TIMESTAMP), field("b", BOOL), field("i", INT32),
            field("m", decimal(38, 4)));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(typed,
                row(LocalDate.of(2024, 1, 2), Instant.parse("2024-01-01T00:00:01Z"), true, -5, dec("-0.0001")),
                row(LocalDate.of(1969, 12, 31), Instant.parse("1969-12-31T23:59:59Z"), false, 3, dec("12345678901234567890.1234")),
                row(LocalDate.of(1, 1, 1), Instant.parse("0001-01-01T00:00:00Z"), true, Integer.MIN_VALUE, dec("-99999999999999999999.9999")),
                row(LocalDate.of(9999, 12, 31), Instant.parse("9999-12-31T23:59:59Z"), null, Integer.MAX_VALUE, dec("0"))));
            h.connect("out");
            h.run(new SortFactory(), config("by", List.of("d")));
            assertEquals(List.of(LocalDate.of(1, 1, 1), LocalDate.of(1969, 12, 31), LocalDate.of(2024, 1, 2),
                LocalDate.of(9999, 12, 31)), h.column("out", "d"));
        }
        Object[][] typedRows = {
            row(LocalDate.of(2024, 1, 2), Instant.parse("2024-01-01T00:00:01Z"), true, -5, dec("-0.0001")),
            row(LocalDate.of(1969, 12, 31), Instant.parse("1969-12-31T23:59:59Z"), false, 3, dec("12345678901234567890.1234")),
            row(LocalDate.of(1, 1, 1), Instant.parse("0001-01-01T00:00:00Z"), true, Integer.MIN_VALUE,
                dec("-99999999999999999999.9999")),
            row(LocalDate.of(9999, 12, 31), Instant.parse("9999-12-31T23:59:59Z"), null, Integer.MAX_VALUE, dec("0.0000"))};
        for (String by : List.of("ts", "b", "i", "m")) {
            for (boolean desc : List.of(false, true)) {
                try (Harness h = new Harness()) {
                    h.input("in", h.batch(typed, typedRows)).connect("out");
                    h.run(new SortFactory(), config("by", List.of(by + (desc ? " desc" : ""))));
                    List<Map<String, Object>> expected = new ArrayList<>();
                    for (Object[] r : typedRows) {
                        expected.add(Reference.row(typed, r));
                    }
                    expected.sort(Reference.comparator(List.of(new Config.OrderTerm(by, desc, false))));
                    assertEquals(expected, h.rows.get("out"), "ordering by " + by + (desc ? " desc" : ""));
                }
            }
        }
    }

    @Test
    void nanosecondsAndUnsignedNumbersOrderExactly() throws Exception {
        Schema ns = schema(field("ts", new org.apache.arrow.vector.types.pojo.ArrowType.Timestamp(
            org.apache.arrow.vector.types.TimeUnit.NANOSECOND, "UTC")), field("u", new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, false)),
            field("n", INT64));
        try (Harness h = new Harness()) {
            org.apache.arrow.vector.VectorSchemaRoot b = org.apache.arrow.vector.VectorSchemaRoot.create(ns, h.source);
            org.apache.arrow.vector.TimeStampNanoTZVector ts = (org.apache.arrow.vector.TimeStampNanoTZVector) b.getVector(0);
            org.apache.arrow.vector.UInt8Vector u = (org.apache.arrow.vector.UInt8Vector) b.getVector(1);
            org.apache.arrow.vector.BigIntVector n = (org.apache.arrow.vector.BigIntVector) b.getVector(2);
            b.allocateNew();
            long[] nanos = {1_000_000_999L, 1_000_000_001L, -1L, 1_000_000_500L, -2_000L};
            long[] unsigned = {Long.MIN_VALUE, 5L, -1L /* 2^64 - 1 */, 0L, Long.MAX_VALUE};
            for (int i = 0; i < nanos.length; i++) {
                ts.set(i, nanos[i]);
                u.set(i, unsigned[i]);
                n.set(i, i);
            }
            b.setRowCount(nanos.length);
            h.input("in", b).connect("out");
            h.run(new SortFactory(), config("by", List.of("ts")));
            // all five lie within two microseconds of 1970: ordered to the nanosecond, not by arrival
            assertEquals(List.of(4L, 2L, 1L, 3L, 0L), h.column("out", "n"));
        }
        try (Harness h = new Harness()) {
            org.apache.arrow.vector.VectorSchemaRoot b = org.apache.arrow.vector.VectorSchemaRoot.create(ns, h.source);
            org.apache.arrow.vector.UInt8Vector u = (org.apache.arrow.vector.UInt8Vector) b.getVector(1);
            org.apache.arrow.vector.BigIntVector n = (org.apache.arrow.vector.BigIntVector) b.getVector(2);
            b.allocateNew();
            long[] unsigned = {Long.MIN_VALUE, 5L, -1L, 0L, Long.MAX_VALUE};
            for (int i = 0; i < unsigned.length; i++) {
                b.getVector(0).setNull(i);
                u.set(i, unsigned[i]);
                n.set(i, i);
            }
            b.setRowCount(unsigned.length);
            h.input("in", b).connect("out");
            h.run(new SortFactory(), config("by", List.of("u")));
            // 0, 5, 2^63 - 1, 2^63, 2^64 - 1: the top half after, not before
            assertEquals(List.of(3L, 1L, 4L, 0L, 2L), h.column("out", "n"));
        }
    }

    @Test
    void anEmptyInputGivesNoRows() throws Exception {
        try (Harness h = new Harness().input("in").connect("out")) {
            h.run(new SortFactory(), config("by", List.of("id")));
            assertEquals(List.of(), h.rows.get("out"));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(ORDERS)).connect("out");
            h.run(new SortFactory(), config("by", List.of("id")));
            assertEquals(List.of(), h.rows.get("out"));
        }
    }

    @Test
    void settingsAreCheckedWithPlainWords() throws Exception {
        SortFactory f = new SortFactory();
        assertEquals(List.of("by: is required"), f.check(config()));
        assertEquals(List.of("by: names the column 'id' twice"), f.check(config("by", List.of("id", "id desc"))));
        assertEquals(List.of("memory_limit_mb: must be from 1 to 1048576"),
            f.check(config("by", List.of("id"), "memory_limit_mb", 0)));
        NodeException e = assertThrows(NodeException.class,
            () -> f.outputSchema("out", Map.of("in", ORDERS), config("by", List.of("regoin desc"))));
        assertEquals("by: the input has no column 'regoin'; did you mean region?", e.getMessage());
        Schema binary = schema(field("blob", org.apache.arrow.vector.types.pojo.ArrowType.Binary.INSTANCE));
        e = assertThrows(NodeException.class,
            () -> f.outputSchema("out", Map.of("in", binary), config("by", List.of("blob"))));
        assertTrue(e.getMessage().startsWith("by: the column 'blob' holds Binary values, which cannot be put in order"),
            e.getMessage());
        assertEquals(ORDERS, f.outputSchema("out", Map.of("in", ORDERS), config("by", List.of("region desc"))));
    }

    // ---- against a simple reference, on random data ----

    @Test
    void matchesASimpleStableSortOnRandomData() throws Exception {
        Random random = new Random(20261106);
        for (int round = 0; round < 40; round++) {
            randomRound(random, 1 + random.nextInt(3000), 256, round);
        }
    }

    @Test
    void matchesTheReferenceWhenItSpillsToDisk() throws Exception {
        Random random = new Random(42);
        for (int round = 0; round < 6; round++) {
            Harness h = randomRound(random, 40_000 + random.nextInt(20_000), 1, round);
            assertTrue(h.spilled.get() > 0, "round " + round + " should have written to disk");
            assertTrue(h.notes.stream().anyMatch(n -> n.contains("rows on disk")), h.notes.toString());
            Path folder = ((SortFactory.SortNode) h.node).spillFolder();
            assertNotNull(folder);
            assertFalse(Files.exists(folder), "its temporary folder is deleted once it ends");
        }
    }

    static final Schema RANDOM = schema(field("seq", INT64), field("a", INT32), field("t", UTF8),
        field("d", decimal(9, 2)), field("f", FLOAT64), field("day", DATE), field("b", BOOL));
    private static final String[] TEXTS = {"", "a", "A", "b", "ab", "a\u0000", "a\u0000b", "é", "e", "zz",
        "😀", "�", "north", "North"};
    private static final double[] FLOATS = {Double.NaN, -0.0, 0.0, 1.5, -1.5, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, 1e300, -1e-300};

    private static Harness randomRound(Random random, int rows, long limitMb, int round) throws Exception {
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(row((long) i,
                random.nextInt(10) == 0 ? null : random.nextInt(20) - 10,
                random.nextInt(10) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)],
                random.nextInt(10) == 0 ? null : BigDecimal.valueOf(random.nextInt(2000) - 1000, 2),
                random.nextInt(10) == 0 ? null : FLOATS[random.nextInt(FLOATS.length)],
                random.nextInt(10) == 0 ? null : LocalDate.ofEpochDay(random.nextInt(40) - 20),
                random.nextInt(10) == 0 ? null : random.nextBoolean()));
        }
        List<String> columns = new ArrayList<>(List.of("a", "t", "d", "f", "day", "b"));
        java.util.Collections.shuffle(columns, random);
        List<Config.OrderTerm> terms = new ArrayList<>();
        for (String c : columns.subList(0, 1 + random.nextInt(3))) {
            terms.add(new Config.OrderTerm(c, random.nextBoolean(), random.nextBoolean()));
        }
        Harness h = new Harness();
        try {
            List<VectorSchemaRoot> batches = new ArrayList<>();
            for (int from = 0; from < rows; from += 1000) {
                batches.add(h.batch(RANDOM, data.subList(from, Math.min(rows, from + 1000)).toArray(Object[][]::new)));
            }
            h.input("in", batches.toArray(VectorSchemaRoot[]::new)).connect("out");
            h.run(new SortFactory(), config("by", terms.stream().map(Config.OrderTerm::toString).toList(),
                "memory_limit_mb", limitMb));
            List<Map<String, Object>> expected = new ArrayList<>();
            for (Object[] r : data) {
                expected.add(Reference.row(RANDOM, r));
            }
            expected.sort(Reference.comparator(terms));
            assertEquals(expected.size(), h.rows.get("out").size(), "round " + round);
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(h.rows.get("out").get(i))) {
                    throw new AssertionError("round " + round + " by " + terms + ", row " + i + ": expected "
                        + expected.get(i) + " but was " + h.rows.get("out").get(i));
                }
            }
            return h;
        } finally {
            h.close();
        }
    }

    // ---- spilling: files deleted whatever happens ----

    @Test
    void itsFilesAreDeletedWhenItFails() throws Exception {
        Schema other = schema(field("id", INT64), field("region", UTF8));
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> batches = new ArrayList<>();
            for (int b = 0; b < 30; b++) {
                Object[][] rows = new Object[2000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row((long) (b * 2000 + i), "region-" + i % 97, dec("1.00"));
                }
                batches.add(h.batch(ORDERS, rows));
            }
            batches.add(h.batch(other, row(1L, "x")));
            h.input("in", batches.toArray(VectorSchemaRoot[]::new)).connect("out");
            NodeException e = assertThrows(NodeException.class,
                () -> h.run(new SortFactory(), config("by", List.of("region"), "memory_limit_mb", 1)));
            assertEquals("rows: the columns changed part-way through", e.getMessage());
            assertTrue(h.spilled.get() > 0, "it had written to disk before it failed");
            Path folder = ((SortFactory.SortNode) h.node).spillFolder();
            assertNotNull(folder);
            assertFalse(Files.exists(folder), "its temporary folder is deleted when it fails");
        }
    }

    @Test
    void itsFilesAreDeletedWhenItIsStopped() throws Exception {
        try (Harness h = new Harness()) {
            RowSet in = new RowSet("in", 1000);
            for (int b = 0; b < 30; b++) {
                Object[][] rows = new Object[2000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row((long) (b * 2000 + i), "region-" + i % 97, dec("1.00"));
                }
                in.put(h.batch(ORDERS, rows));
            }
            // the input never ends: the node waits for more rows until it is stopped
            h.input("in", in).connect("out");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = Thread.ofPlatform().start(() -> {
                try {
                    h.run(new SortFactory(), config("by", List.of("region"), "memory_limit_mb", 1));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (in.queued() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(200);
            Path folder = ((SortFactory.SortNode) h.node).spillFolder();
            assertNotNull(folder, "it should have written to disk by now");
            assertTrue(Files.exists(folder));
            t.interrupt();
            t.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(t.isAlive());
            assertTrue(failure.get() instanceof NodeException, String.valueOf(failure.get()));
            assertFalse(Files.exists(folder), "its temporary folder is deleted when it is stopped");
            in.finish();
        }
    }

    /** What the sort should do, written plainly: Java values compared as the expression language compares them. */
    static final class Reference {

        static Map<String, Object> row(Schema schema, Object[] values) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (int c = 0; c < values.length; c++) {
                Object v = values[c];
                m.put(schema.getFields().get(c).getName(), v instanceof Integer i ? (long) i : v);
            }
            return m;
        }

        static Comparator<Map<String, Object>> comparator(List<Config.OrderTerm> terms) {
            Comparator<Map<String, Object>> all = (_, _) -> 0;
            for (Config.OrderTerm t : terms) {
                all = all.thenComparing((x, y) -> {
                    Object a = x.get(t.column());
                    Object b = y.get(t.column());
                    if (a == null || b == null) {
                        if (a == b) {
                            return 0;
                        }
                        return (a == null) == t.nullsFirst() ? -1 : 1;
                    }
                    int c = value(a, b);
                    return t.descending() ? -c : c;
                });
            }
            return all;
        }

        @SuppressWarnings("unchecked")
        static int value(Object a, Object b) {
            if (a instanceof Double x && b instanceof Double y) {
                return x.doubleValue() == y.doubleValue() ? 0 : Double.compare(x, y);
            }
            if (a instanceof String x && b instanceof String y) {
                return Arrays.compare(x.codePoints().toArray(), y.codePoints().toArray());
            }
            return ((Comparable<Object>) a).compareTo(b);
        }
    }
}
