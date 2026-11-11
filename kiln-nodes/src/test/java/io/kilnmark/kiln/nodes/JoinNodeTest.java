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

    /** Semi and anti: the left rows with a match, or with none, once each, with the left's columns alone. */
    @Test
    void semiAndAntiKeepLeftRowsWithTheLeftColumnsOnly() throws Exception {
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("semi"));
            // key 3: orders 2 and 5, once each though two customers match; key 7: order 1
            assertEquals(List.of(2L, 5L, 1L), h.column("out", "order_id"));
            assertEquals(ORDERS, h.schemas.get("out"), "the left's columns, with their types, and no right column");
        }
        try (Harness h = orders(new Harness()).connect("out")) {
            h.run(new JoinFactory(), on("anti"));
            // key 9 has no customer; then order 3, whose key is null, which matches nothing
            assertEquals(List.of(4L, 3L), h.column("out", "order_id"));
            assertEquals(ORDERS, h.schemas.get("out"));
        }
        JoinFactory f = new JoinFactory();
        Map<String, Schema> in = Map.of("left", ORDERS, "right", CUSTOMERS);
        assertEquals(ORDERS, f.outputSchema("out", in, on("semi")));
        assertEquals(ORDERS, f.outputSchema("out", in, on("anti")));
        // no right column comes out, so a name the prefix could not clear is no problem
        Schema clash = schema(field("id", INT64), field("right_id", INT64));
        assertEquals(clash, f.outputSchema("out", Map.of("left", clash, "right", schema(field("id", INT64))),
            config("on", Map.of("id", "id"), "how", "anti")));
        // the keys are still checked
        NodeException e = assertThrows(NodeException.class,
            () -> f.outputSchema("out", in, config("on", Map.of("customer_id", "nme"), "how", "anti")));
        assertEquals("on: the right input has no column 'nme'; did you mean name?", e.getMessage());
    }

    /**
     * Nulls in semi and anti, as SQL's EXISTS and NOT EXISTS: a null key never matches, so a left row
     * whose key has a null is in anti and never in semi, and a null on the right hides no left row
     * (SQL's NOT IN would give no row at all here).
     */
    @Test
    void semiAndAntiTreatNullKeysAsExistsDoes() throws Exception {
        Schema l = schema(field("k", UTF8), field("n", INT64), field("a", INT64));
        Schema r = schema(field("k", UTF8), field("n", INT64));
        for (String how : List.of("semi", "anti")) {
            try (Harness h = new Harness()) {
                h.input("left", h.batch(l, row(null, 1L, 1L), row("x", 1L, 2L), row("y", 1L, 3L), row("x", null, 4L),
                        row(null, null, 5L)))
                    .input("right", h.batch(r, row(null, 1L), row("x", 1L), row("x", null), row(null, null)))
                    .connect("out");
                h.run(new JoinFactory(), config("on", new LinkedHashMap<>(Map.of("k", "k", "n", "n")), "how", how));
                assertEquals(how.equals("semi") ? List.of(2L) : List.of(3L, 1L, 4L, 5L), h.column("out", "a"), how);
            }
        }
    }

    @Test
    void semiAndAntiWithAnEmptyOrUnknownSide() throws Exception {
        Schema l = schema(field("k", INT64), field("a", INT64));
        for (boolean declared : new boolean[] {true, false}) {
            try (Harness h = new Harness()) {
                h.input("left", h.batch(l, row(2L, 1L), row(1L, 2L))).input("right").connect("out");
                if (declared) {
                    h.declare("left", l).declare("right", l);
                }
                h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", "anti"));
                assertEquals(List.of(2L, 1L), h.column("out", "a"), "no right row: every left row has no match");
            }
            try (Harness h = new Harness()) {
                h.input("left", h.batch(l, row(2L, 1L))).input("right").connect("out");
                if (declared) {
                    h.declare("left", l).declare("right", l);
                }
                h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", "semi"));
                assertEquals(List.of(), h.rows.get("out"));
            }
        }
        try (Harness h = new Harness()) {
            h.input("left").input("right", h.batch(l, row(1L, 1L))).connect("out");
            h.declare("left", l).declare("right", l);
            h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", "anti"));
            assertEquals(List.of(), h.rows.get("out"));
        }
    }

    /** Semi and anti against a plain reference, in memory and spilling to disk: together they are the left, once. */
    @Test
    void semiAndAntiMatchTheReference() throws Exception {
        Random random = new Random(41);
        for (int round = 0; round < 6; round++) {
            boolean big = round >= 4;
            int keys = big ? 20_000 : 12;
            List<Object[]> left = new ArrayList<>();
            for (int i = 0; i < (big ? 30_000 : 1 + random.nextInt(400)); i++) {
                left.add(row((long) i, random.nextInt(8) == 0 ? null : random.nextInt(keys),
                    random.nextInt(8) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)], random.nextDouble()));
            }
            List<Object[]> right = new ArrayList<>();
            for (int i = 0; i < (big ? 25_000 : 1 + random.nextInt(400)); i++) {
                right.add(row((long) i, random.nextInt(8) == 0 ? null : java.math.BigDecimal.valueOf(random.nextInt(keys)),
                    random.nextInt(8) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)], (long) random.nextInt(100)));
            }
            boolean twoKeys = random.nextBoolean();
            Map<String, Object> on = new LinkedHashMap<>();
            on.put("k1", "k1");
            if (twoKeys) {
                on.put("k2", "k2");
            }
            java.util.Set<List<Object>> rightKeys = new java.util.HashSet<>();
            for (Object[] r : right) {
                if (r[1] != null && (!twoKeys || r[2] != null)) {
                    rightKeys.add(Arrays.asList(((java.math.BigDecimal) r[1]).intValueExact(), twoKeys ? r[2] : ""));
                }
            }
            List<Long> matched = new ArrayList<>();
            List<Long> unmatched = new ArrayList<>();
            for (Object[] l : left) {
                boolean has = l[1] != null && (!twoKeys || l[2] != null)
                    && rightKeys.contains(Arrays.asList(l[1], twoKeys ? l[2] : ""));
                (has ? matched : unmatched).add((Long) l[0]);
            }
            for (String how : List.of("semi", "anti")) {
                try (Harness h = new Harness()) {
                    h.input("left", batches(h, LEFT, left)).input("right", batches(h, RIGHT, right)).connect("out");
                    h.run(new JoinFactory(), config("on", on, "how", how, "memory_limit_mb", big ? 1 : 256));
                    List<Object> got = new ArrayList<>(h.column("out", "lid"));
                    got.sort(null);
                    assertEquals(how.equals("semi") ? matched : unmatched, got, "round " + round + ": " + how);
                    assertEquals(LEFT, h.schemas.get("out") == null ? LEFT : h.schemas.get("out"));
                    if (big) {
                        assertTrue(h.spilled.get() > 0, "round " + round + " should have written to disk");
                    }
                }
            }
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
        assertEquals(List.of("how: must be one of inner, left, right, full, semi, anti, not 'outer'"),
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

    // ---- review fixes ----

    private static org.apache.arrow.vector.VectorSchemaRoot nanos(Harness h, long... values) {
        Schema ns = schema(field("ts", new org.apache.arrow.vector.types.pojo.ArrowType.Timestamp(
            org.apache.arrow.vector.types.TimeUnit.NANOSECOND, "UTC")), field("n", INT64));
        org.apache.arrow.vector.VectorSchemaRoot b = org.apache.arrow.vector.VectorSchemaRoot.create(ns, h.source);
        b.allocateNew();
        for (int i = 0; i < values.length; i++) {
            ((org.apache.arrow.vector.TimeStampNanoTZVector) b.getVector(0)).set(i, values[i]);
            ((org.apache.arrow.vector.BigIntVector) b.getVector(1)).set(i, i);
        }
        b.setRowCount(values.length);
        return b;
    }

    @Test
    void nanosecondTimestampsMatchOnlyTheSameNanosecondAndUnsignedNumbersByValue() throws Exception {
        try (Harness h = new Harness()) {
            // two instants in the same microsecond, a nanosecond apart, and one equal
            h.input("left", nanos(h, 1_000_000_001L, 86_400_000_000_000L))
                .input("right", nanos(h, 1_000_000_002L, 1_000_000_001L)).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("ts", "ts")));
            assertEquals(List.of(0L), h.column("out", "n"));
            assertEquals(List.of(1L), h.column("out", "right_n"));
        }
        try (Harness h = new Harness()) {
            // a date matches a nanosecond timestamp at its midnight, and nothing a nanosecond after
            Schema d = schema(field("ts", DATE), field("n", INT64));
            h.input("left", h.batch(d, row(LocalDate.of(1970, 1, 2), 7L)))
                .input("right", nanos(h, 86_400_000_000_001L, 86_400_000_000_000L)).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("ts", "ts")));
            assertEquals(List.of(1L), h.column("out", "right_n"));
        }
        try (Harness h = new Harness()) {
            Schema u = schema(field("k", new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, false)), field("a", INT64));
            org.apache.arrow.vector.VectorSchemaRoot b = org.apache.arrow.vector.VectorSchemaRoot.create(u, h.source);
            b.allocateNew();
            ((org.apache.arrow.vector.UInt8Vector) b.getVector(0)).set(0, Long.MIN_VALUE); // 2^63
            ((org.apache.arrow.vector.UInt8Vector) b.getVector(0)).set(1, 5L);
            ((org.apache.arrow.vector.BigIntVector) b.getVector(1)).set(0, 1L);
            ((org.apache.arrow.vector.BigIntVector) b.getVector(1)).set(1, 2L);
            b.setRowCount(2);
            Schema r = schema(field("k", decimal(20, 0)), field("b", INT64));
            h.input("left", b).input("right", h.batch(r, row(dec("9223372036854775808"), 10L),
                row(dec("-9223372036854775808"), 20L), row(dec("5"), 30L))).connect("out");
            h.run(new JoinFactory(), config("on", Map.of("k", "k")));
            assertEquals(List.of(2L, 1L), h.column("out", "a"), "5, then 2^63; never -2^63");
            assertEquals(List.of(30L, 10L), h.column("out", "b"));
        }
    }

    @Test
    void aKeysRightRowsInMemoryAreJoinedWithoutCopies() throws Exception {
        Schema l = schema(field("k", INT64), field("a", INT64));
        Schema r = schema(field("k", INT64), field("b", INT64));
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> rights = new ArrayList<>();
            for (int b = 0; b < 4; b++) {
                Object[][] rows = new Object[5000][];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = row(1L, (long) (b * 5000 + i));
                }
                rights.add(h.batch(r, rows));
            }
            // 20,000 right rows of one key: more than a batch handed back, so the key's rows span batches
            h.input("left", h.batch(l, row(1L, 1L), row(1L, 2L))).input("right", rights.toArray(VectorSchemaRoot[]::new))
                .connect("out");
            h.run(new JoinFactory(), config("on", Map.of("k", "k")));
            assertEquals(40_000, h.rows.get("out").size());
            assertEquals(0L, ((JoinFactory.JoinNode) h.node).groupRowsCopied(), "held by the sorter: pointed at");
            assertEquals(19_999L, h.column("out", "b").get(19_999));
        }
    }

    @Test
    void aGroupGoesToDiskByBytesNotEveryThousandRows() throws Exception {
        Schema r = schema(field("k", INT64), field("b", UTF8));
        String wide = "w".repeat(50_000);
        try (Harness h = new Harness()) {
            VectorSchemaRoot src = Harness.batch(h.nodeMemory, r, row(1L, wide));
            try (JoinFactory.Group g = new JoinFactory.Group(r, h.nodeMemory, 1 << 20)) {
                int rows = 0;
                while (!g.onDisk() && rows < 1000) {
                    g.add(src, 0, null);
                    rows++;
                }
                assertTrue(g.onDisk());
                assertTrue(rows < 40, rows + " rows of 50 KB before it went to disk past 1 MB");
                g.seal();
                int[] seen = {0};
                g.forEach((root, row) -> seen[0]++);
                assertEquals(rows, seen[0]);
            } finally {
                src.close();
            }
            assertEquals(0, h.nodeMemory.getAllocatedMemory());
        }
    }

    @Test
    void aGroupStoppedWhileWritingToDiskLeavesNothing() throws Exception {
        Schema r = schema(field("k", INT64), field("b", UTF8));
        try (Harness h = new Harness()) {
            VectorSchemaRoot src = Harness.batch(h.nodeMemory, r, row(1L, "x".repeat(2_000)));
            Path folder;
            JoinFactory.Group g = new JoinFactory.Group(r, h.nodeMemory, 64 * 1024);
            Exception stopped = null;
            try {
                for (int i = 0; i < 200_000 && stopped == null; i++) {
                    if (g.onDisk()) {
                        Thread.currentThread().interrupt(); // a stop: the file's channel closes on the next write
                    }
                    try {
                        g.add(src, 0, null);
                    } catch (java.io.IOException e) {
                        stopped = e;
                    }
                }
            } finally {
                Thread.interrupted();
            }
            assertTrue(stopped != null, "writing should have been stopped");
            folder = g.folder();
            assertTrue(folder != null && Files.exists(folder));
            g.close(); // the writer fails again as it closes; the rest is still closed
            src.close();
            assertFalse(Files.exists(folder), "its folder and file are deleted");
            assertEquals(0, h.nodeMemory.getAllocatedMemory(), "and its memory freed");
        }
    }

    @Test
    void anInnerJoinWithAnEmptySideStopsReadingTheOther() throws Exception {
        Schema l = schema(field("k", INT64), field("a", INT64));
        for (String how : List.of("inner", "left")) {
            try (Harness h = new Harness()) {
                List<VectorSchemaRoot> rights = new ArrayList<>();
                for (int b = 0; b < 200; b++) {
                    rights.add(h.batch(l, row((long) b, (long) b)));
                }
                h.input("left").input("right", rights.toArray(VectorSchemaRoot[]::new)).connect("out");
                h.declare("left", l).declare("right", l);
                h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", how));
                assertEquals(List.of(), h.rows.get("out"));
                assertTrue(h.unread() > 100, how + ": the right side need not be read");
            }
        }
        try (Harness h = new Harness()) {
            h.input("left").input("right", h.batch(l, row(1L, 1L))).connect("out");
            h.declare("left", l).declare("right", l);
            h.run(new JoinFactory(), config("on", Map.of("k", "k"), "how", "right"));
            assertEquals(List.of(1L), h.column("out", "right_k"), "a right join still keeps the right's rows");
        }
    }

    @Test
    void namesFollowOneWalkWhereverTheyAreWorkedOut() {
        assertEquals(List.of("Name", "right_name", "right_ID"),
            JoinFactory.rightNames(List.of("id"), List.of("Name", "name", "ID"), "right_"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> JoinFactory.rightNames(List.of("id", "right_id"), List.of("id"), "right_"));
        assertTrue(e.getMessage().startsWith("right_prefix: the right column 'id' would be called 'right_id'"));
    }
}
