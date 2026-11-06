package io.kilnmark.kiln.nodes;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowSet;

/** join: every kind, keys of every family, duplicates, nulls, names, and the path that spills to disk. */
class JoinNodeTest {

    static final Schema ORDERS = schema(field("order_id", INT64), field("customer_id", INT64), field("amount", decimal(10, 2)));
    static final Schema CUSTOMERS = schema(field("customer_id", INT32), field("name", UTF8));

    private static Harness orders(Harness h) {
        return h.input("left",
                h.batch(ORDERS, row(1L, 7L, dec("10.00")), row(2L, 3L, dec("5.00")), row(3L, null, dec("1.00"))),
                h.batch(ORDERS, row(4L, 9L, dec("2.00")), row(5L, 3L, dec("8.00"))))
            .input("right",
                h.batch(CUSTOMERS, row(3, "Ada"), row(5, "Bea"), row(7, "Cy")),
                h.batch(CUSTOMERS, row(null, "Nobody"), row(3, "Ada again")));
    }

    private static Map<String, Object> on(String how) {
        return config("on", Map.of("customer_id", "customer_id"), "how", how);
    }

    private static List<String> pairs(Harness h) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : h.rows.get("out")) {
            out.add(r.get("order_id") + ":" + r.get("name"));
        }
        return out;
    }

    @Test
    void innerKeepsMatchingPairsInKeyOrder() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("inner"));
            // key 3: orders 2 and 5 (arrival order), each with Ada then Ada again; key 7: order 1 with Cy
            assertEquals(List.of("2:Ada", "2:Ada again", "5:Ada", "5:Ada again", "1:Cy"), pairs(h));
            assertEquals(List.of("order_id", "customer_id", "amount", "right_customer_id", "name"),
                BaseNode.names(h.schemas.get("out")));
            assertEquals(INT32, h.schemas.get("out").getFields().get(3).getType(), "types are kept");
            assertEquals(decimal(10, 2), h.schemas.get("out").getFields().get(2).getType());
        }
    }

    @Test
    void leftRightAndFullKeepRowsWithNoMatch() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("left"));
            assertEquals(List.of("2:Ada", "2:Ada again", "5:Ada", "5:Ada again", "1:Cy", "4:null", "3:null"), pairs(h));
        }
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("right"));
            assertEquals(List.of("2:Ada", "2:Ada again", "5:Ada", "5:Ada again", "null:Bea", "1:Cy", "null:Nobody"),
                pairs(h));
        }
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("full"));
            // by key (3, 5, 7, 9), then rows whose key is null: the left's, then the right's
            assertEquals(List.of("2:Ada", "2:Ada again", "5:Ada", "5:Ada again", "null:Bea", "1:Cy", "4:null", "3:null",
                "null:Nobody"), pairs(h));
            assertTrue(h.schemas.get("out").getFields().stream().allMatch(Field::isNullable));
        }
    }

    @Test
    void nullKeysNeverMatchEvenEachOther() throws Exception {
        Schema l = schema(field("k", UTF8), field("a", INT64));
        Schema r = schema(field("k", UTF8), field("b", INT64));
        try (Harness h = new Harness()) {
            h.input("left", h.batch(l, row(null, 1L), row("x", 2L)))
                .input("right", h.batch(r, row(null, 10L), row("x", 20L))).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", "full"));
            assertEquals(Arrays.asList(2L, 1L, null), h.column("out", "a"));
            assertEquals(Arrays.asList(20L, null, 10L), h.column("out", "b"));
        }
    }

    @Test
    void severalKeysAndKeysOfEveryFamily() throws Exception {
        Schema l = schema(field("d", DATE), field("n", INT64), field("t", UTF8), field("a", INT64));
        Schema r = schema(field("ts", TIMESTAMP), field("m", decimal(12, 3)), field("t", UTF8), field("b", INT64));
        try (Harness h = new Harness()) {
            h.input("left", h.batch(l,
                    row(LocalDate.of(2024, 1, 1), 1L, "é", 1L),
                    row(LocalDate.of(2024, 1, 1), 2L, "é", 2L),
                    row(LocalDate.of(2024, 1, 2), 1L, "é", 3L)))
                .input("right", h.batch(r,
                    row(Instant.parse("2024-01-01T00:00:00Z"), dec("1.000"), "é", 10L), // date at midnight, 1 = 1.000
                    row(Instant.parse("2024-01-01T00:00:01Z"), dec("1.000"), "é", 11L), // not midnight
                    row(Instant.parse("2024-01-01T00:00:00Z"), dec("2.000"), "e", 12L))) // text differs
                .connect("out");
            h.run(new JoinFactory(), config("on", new LinkedHashMap<>(Map.of("d", "ts", "n", "m", "t", "t")), "how",
                "inner"));
            assertEquals(List.of(1L), h.column("out", "a"));
            assertEquals(List.of(10L), h.column("out", "b"));
            assertEquals(List.of("d", "n", "t", "a", "ts", "m", "right_t", "b"), BaseNode.names(h.schemas.get("out")));
        }
        Schema fl = schema(field("x", FLOAT64), field("a", INT64));
        Schema il = schema(field("x", INT64), field("b", INT64));
        try (Harness h = new Harness()) {
            h.input("left", h.batch(fl, row(3.0, 1L), row(-0.0, 2L), row(Double.NaN, 3L), row(0.5, 4L)))
                .input("right", h.batch(il, row(3L, 10L), row(0L, 20L))).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("x", "x"), "how", "inner"));
            assertEquals(List.of(2L, 1L), h.column("out", "a"), "-0.0 matches 0, 3.0 matches 3; ordered by value");
        }
    }

    @Test
    void manyToManyGivesEveryPair() throws Exception {
        Schema l = schema(field("k", INT64), field("a", INT64));
        Schema r = schema(field("k", INT64), field("b", INT64));
        try (Harness h = new Harness()) {
            h.input("left", h.batch(l, row(1L, 1L), row(1L, 2L), row(1L, 3L)))
                .input("right", h.batch(r, row(1L, 10L), row(1L, 20L))).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("k", "k")));
            assertEquals(List.of(1L, 1L, 2L, 2L, 3L, 3L), h.column("out", "a"));
            assertEquals(List.of(10L, 20L, 10L, 20L, 10L, 20L), h.column("out", "b"));
        }
    }

    @Test
    void emptyInputsAndOneEmptySide() throws Exception {
        try (Harness h = new Harness()) {
            h.input("left").input("right").connect("out");
            h.declare("left", ORDERS).declare("right", CUSTOMERS);
            h.run(new JoinFactory(), on("full"));
            assertEquals(List.of(), h.rows.get("out"));
        }
        try (Harness h = new Harness()) {
            h.input("left", h.batch(ORDERS, row(1L, 7L, dec("1.00")))).input("right").connect("out");
            h.declare("left", ORDERS).declare("right", CUSTOMERS);
            h.run(new JoinFactory(), on("left"));
            assertEquals(List.of("1:null"), pairs(h));
            assertEquals(List.of("order_id", "customer_id", "amount", "right_customer_id", "name"),
                BaseNode.names(h.schemas.get("out")), "the empty side's columns, as the check worked them out");
        }
        try (Harness h = new Harness()) {
            h.input("left", h.batch(ORDERS, row(1L, 7L, dec("1.00")))).input("right").connect("out");
            h.declare("left", ORDERS).declare("right", CUSTOMERS);
            h.run(new JoinFactory(), on("inner"));
            assertEquals(List.of(), h.rows.get("out"));
        }
    }

    @Test
    void settingsAndColumnsAreCheckedWithPlainWords() throws Exception {
        JoinFactory f = new JoinFactory();
        assertEquals(List.of("on: is required"), f.check(config()));
        assertEquals(List.of("how: must be one of inner, left, right, full, not 'outer'"),
            f.check(config("on", Map.of("a", "b"), "how", "outer")));
        Map<String, Schema> in = Map.of("left", ORDERS, "right", CUSTOMERS);
        NodeException e = assertThrows(NodeException.class,
            () -> f.outputSchema("out", in, config("on", Map.of("custmer_id", "customer_id"))));
        assertEquals("on: the left input has no column 'custmer_id'; did you mean customer_id?", e.getMessage());
        e = assertThrows(NodeException.class, () -> f.outputSchema("out", in, config("on", Map.of("customer_id", "name"))));
        assertEquals("on: the left column 'customer_id' (int64) cannot be matched with the right column 'name' (utf8); "
            + "cast one of them first", e.getMessage());
        Schema clash = schema(field("id", INT64), field("right_ID", INT64));
        e = assertThrows(NodeException.class, () -> f.outputSchema("out", Map.of("left", clash,
            "right", schema(field("id", INT64))), config("on", Map.of("id", "id"))));
        assertEquals("right_prefix: the right column 'id' would be called 'right_id', which the output has already; "
            + "choose another right_prefix, or rename a column with a select node first", e.getMessage());
        Schema out = f.outputSchema("out", Map.of("left", clash, "right", schema(field("ID", INT64))),
            config("on", Map.of("id", "ID"), "right_prefix", "r_"));
        assertEquals(List.of("id", "right_ID", "r_ID"), BaseNode.names(out), "names clash ignoring case");
    }

    // ---- against a plain nested-loop join, on random data ----

    @Test
    void matchesANestedLoopJoinOnRandomData() throws Exception {
        Random random = new Random(1106);
        for (int round = 0; round < 40; round++) {
            randomRound(random, 1 + random.nextInt(400), 1 + random.nextInt(400), 12, 256, round);
        }
    }

    @Test
    void matchesTheReferenceWhenItSpillsToDisk() throws Exception {
        Random random = new Random(77);
        for (int round = 0; round < 4; round++) {
            Harness h = randomRound(random, 30_000, 25_000, 20_000, 1, round);
            assertTrue(h.spilled.get() > 0, "round " + round + " should have written to disk");
            assertTrue(h.notes.stream().anyMatch(n -> n.contains("on disk")), h.notes.toString());
            for (Path p : ((JoinFactory.JoinNode) h.node).spillFolders()) {
                assertFalse(Files.exists(p), "its temporary folders are deleted once it ends");
            }
        }
    }

    @Test
    void aKeyWithMoreRightRowsThanMemoryHoldsIsJoinedFromDisk() throws Exception {
        Schema l = schema(field("k", INT64), field("a", INT64));
        Schema r = schema(field("k", INT64), field("b", UTF8));
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> rights = new ArrayList<>();
            for (int b = 0; b < 10; b++) {
                Object[][] rows = new Object[2000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row(1L, "right-row-" + (b * 2000 + i) + "-" + "x".repeat(60));
                }
                rights.add(h.batch(r, rows));
            }
            h.input("left", h.batch(l, row(1L, 1L), row(1L, 2L), row(2L, 3L)))
                .input("right", rights.toArray(VectorSchemaRoot[]::new)).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("k", "k"), "memory_limit_mb", 2));
            assertEquals(40_000, h.rows.get("out").size());
            List<Object> b = h.column("out", "b");
            assertEquals("right-row-0-" + "x".repeat(60), b.get(0));
            assertEquals("right-row-19999-" + "x".repeat(60), b.get(19_999), "each left row with every right row, in order");
            assertEquals("right-row-0-" + "x".repeat(60), b.get(20_000));
            assertEquals(2L, h.column("out", "a").get(20_000));
            assertEquals(1, ((JoinFactory.JoinNode) h.node).groupsOnDisk(), "the right rows of key 1 were held on disk");
            assertTrue(h.notes.stream().anyMatch(n -> n.startsWith("1 key had more right rows")), h.notes.toString());
        }
    }

    static final Schema LEFT = schema(field("lid", INT64), field("k1", INT32), field("k2", UTF8), field("v", FLOAT64));
    static final Schema RIGHT = schema(field("rid", INT64), field("k1", decimal(9, 2)), field("k2", UTF8),
        field("w", INT64));
    private static final String[] TEXTS = {"a", "b", "B", "é", "", "a\u0000"};

    private static Harness randomRound(Random random, int leftRows, int rightRows, int keys, long limitMb, int round)
        throws Exception {
        List<Object[]> left = new ArrayList<>();
        for (int i = 0; i < leftRows; i++) {
            left.add(row((long) i, random.nextInt(8) == 0 ? null : random.nextInt(keys),
                random.nextInt(8) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)], random.nextDouble()));
        }
        List<Object[]> right = new ArrayList<>();
        for (int i = 0; i < rightRows; i++) {
            right.add(row((long) i, random.nextInt(8) == 0 ? null : java.math.BigDecimal.valueOf(random.nextInt(keys)),
                random.nextInt(8) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)], (long) random.nextInt(100)));
        }
        String how = JoinFactory.HOWS.get(random.nextInt(4));
        boolean twoKeys = random.nextBoolean();
        Map<String, Object> on = new LinkedHashMap<>();
        on.put("k1", "k1");
        if (twoKeys) {
            on.put("k2", "k2");
        }
        Harness h = new Harness();
        try {
            h.input("left", batches(h, LEFT, left)).input("right", batches(h, RIGHT, right)).connect("out");
            h.run(new JoinFactory(), config("on", on, "how", how, "memory_limit_mb", limitMb));
            Map<String, Integer> expected = reference(left, right, how, twoKeys);
            Map<String, Integer> got = new HashMap<>();
            for (Map<String, Object> r : h.rows.get("out")) {
                got.merge(r.get("lid") + "|" + r.get("rid"), 1, Integer::sum);
            }
            assertEquals(expected, got, "round " + round + ": " + how + (twoKeys ? " on k1, k2" : " on k1"));
            // in order of the key, rows whose key has a null last
            String last = null;
            boolean nulls = false;
            for (Map<String, Object> r : h.rows.get("out")) {
                Object k1 = r.get("k1") != null ? r.get("k1") : r.get("right_k1") == null ? null
                    : ((java.math.BigDecimal) r.get("right_k1")).intValueExact();
                boolean isNull = k1 == null || (twoKeys && r.get("k2") == null && r.get("right_k2") == null);
                if (isNull) {
                    nulls = true;
                    continue;
                }
                assertFalse(nulls, "round " + round + ": a row with a key after rows whose key has a null");
                String k = String.format(java.util.Locale.ROOT, "%08d", ((Number) k1).intValue() + 100);
                assertTrue(last == null || k.compareTo(last) >= 0, "round " + round + ": out of key order");
                last = k;
            }
            return h;
        } finally {
            h.close();
        }
    }

    private static VectorSchemaRoot[] batches(Harness h, Schema s, List<Object[]> rows) {
        List<VectorSchemaRoot> out = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += 1000) {
            out.add(h.batch(s, rows.subList(from, Math.min(rows.size(), from + 1000)).toArray(Object[][]::new)));
        }
        return out.toArray(VectorSchemaRoot[]::new);
    }

    /**
     * Each output pair "lid|rid" (null for no match) and how many times it comes out: every left row
     * against the right rows with equal keys, found plainly (a whole number equals a decimal of the
     * same value; a null never equals anything).
     */
    private static Map<String, Integer> reference(List<Object[]> left, List<Object[]> right, String how, boolean twoKeys) {
        Map<List<Object>, List<Integer>> byKey = new HashMap<>();
        for (int j = 0; j < right.size(); j++) {
            Object[] r = right.get(j);
            if (r[1] == null || (twoKeys && r[2] == null)) {
                continue;
            }
            List<Object> key = Arrays.asList(((java.math.BigDecimal) r[1]).intValueExact(), twoKeys ? r[2] : "");
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }
        Map<String, Integer> out = new HashMap<>();
        boolean[] rightMatched = new boolean[right.size()];
        for (Object[] l : left) {
            List<Integer> matches = l[1] == null || (twoKeys && l[2] == null) ? List.of()
                : byKey.getOrDefault(Arrays.asList(l[1], twoKeys ? l[2] : ""), List.of());
            for (int j : matches) {
                rightMatched[j] = true;
                out.merge(l[0] + "|" + right.get(j)[0], 1, Integer::sum);
            }
            if (matches.isEmpty() && (how.equals("left") || how.equals("full"))) {
                out.merge(l[0] + "|null", 1, Integer::sum);
            }
        }
        if (how.equals("right") || how.equals("full")) {
            for (int j = 0; j < right.size(); j++) {
                if (!rightMatched[j]) {
                    out.merge("null|" + right.get(j)[0], 1, Integer::sum);
                }
            }
        }
        return out;
    }

    // ---- spilling: files deleted whatever happens ----

    @Test
    void itsFilesAreDeletedWhenItIsStopped() throws Exception {
        Schema l = schema(field("k", INT64), field("a", UTF8));
        try (Harness h = new Harness()) {
            RowSet left = new RowSet("left", 1000);
            for (int b = 0; b < 30; b++) {
                Object[][] rows = new Object[2000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row((long) (b * 2000 + i), "left-" + i);
                }
                left.put(h.batch(l, rows));
            }
            RowSet right = new RowSet("right", 10); // never ends
            h.input("left", left).input("right", right).connect("out");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = Thread.ofPlatform().start(() -> {
                try {
                    h.run(new JoinFactory(), config("on", Map.of("k", "k"), "memory_limit_mb", 1));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (left.queued() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(200);
            List<Path> folders = ((JoinFactory.JoinNode) h.node).spillFolders();
            assertFalse(folders.isEmpty(), "it should have written to disk by now");
            t.interrupt();
            t.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(t.isAlive());
            assertTrue(failure.get() instanceof NodeException, String.valueOf(failure.get()));
            for (Path p : folders) {
                assertFalse(Files.exists(p), "its temporary folder is deleted when it is stopped");
            }
            right.finish();
            left.finish();
        }
    }

    @Test
    void itsFilesAreDeletedWhenItFails() throws Exception {
        Schema l = schema(field("k", INT64), field("a", UTF8));
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> lefts = new ArrayList<>();
            for (int b = 0; b < 30; b++) {
                Object[][] rows = new Object[2000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row((long) (b * 2000 + i), "left-" + i);
                }
                lefts.add(h.batch(l, rows));
            }
            lefts.add(h.batch(schema(field("k", INT64)), row(1L)));
            h.input("left", lefts.toArray(VectorSchemaRoot[]::new))
                .input("right", h.batch(schema(field("k", INT64)), row(1L))).connect("out");
            NodeException e = assertThrows(NodeException.class,
                () -> h.run(new JoinFactory(), config("on", Map.of("k", "k"), "memory_limit_mb", 1)));
            assertEquals("the left input: the columns changed part-way through", e.getMessage());
            List<Path> folders = ((JoinFactory.JoinNode) h.node).spillFolders();
            assertFalse(folders.isEmpty());
            assertTrue(folders.stream().noneMatch(Files::exists), "its temporary folders are deleted when it fails");
        }
    }
}
