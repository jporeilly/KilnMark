package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.DATE;
import static io.kilnmark.kiln.nodes.Harness.FLOAT64;
import static io.kilnmark.kiln.nodes.Harness.INT32;
import static io.kilnmark.kiln.nodes.Harness.INT64;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.nodes.expr.Type;

/** window: each function, ties, nulls, partitions, the checks at save, and the path that spills to disk. */
class WindowNodeTest {

    static final Schema TXNS = schema(field("txn_id", INT64), field("account", UTF8), field("day", DATE),
        field("amount", decimal(10, 2)));

    private static Harness txns(Harness h) {
        // two batches, accounts interleaved, out of order
        return h.input("in",
            h.batch(TXNS, row(1L, "B", LocalDate.of(2026, 1, 3), dec("5.00")),
                row(2L, "A", LocalDate.of(2026, 1, 2), dec("100.00")),
                row(3L, "A", LocalDate.of(2026, 1, 1), dec("50.00"))),
            h.batch(TXNS, row(4L, "B", LocalDate.of(2026, 1, 1), dec("-2.50")),
                row(5L, "A", LocalDate.of(2026, 1, 2), dec("-30.00")),
                row(6L, "A", LocalDate.of(2026, 1, 4), null)));
    }

    private static Map<String, Object> columns(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aRunningBalancePerAccount() throws Exception {
        try (Harness h = txns(new Harness()).connect("out")) {
            h.run(new WindowFactory(), config("partition_by", List.of("account"), "order_by", List.of("day", "txn_id"),
                "columns", columns("balance", "sum(amount)", "n", "row_number()", "seen", "count(*)",
                    "paid", "count(amount)", "average", "avg(amount)", "least", "min(amount)", "most", "max(amount)")));
            // account A: 3 (1 Jan, 50), 2 (2 Jan, 100), 5 (2 Jan, -30), 6 (4 Jan, null); then B: 4, 1
            assertEquals(List.of(3L, 2L, 5L, 6L, 4L, 1L), h.column("out", "txn_id"));
            assertEquals(List.of(dec("50.00"), dec("150.00"), dec("120.00"), dec("120.00"), dec("-2.50"), dec("2.50")),
                h.column("out", "balance"));
            assertEquals(List.of(1L, 2L, 3L, 4L, 1L, 2L), h.column("out", "n"));
            assertEquals(List.of(1L, 2L, 3L, 4L, 1L, 2L), h.column("out", "seen"));
            assertEquals(List.of(1L, 2L, 3L, 3L, 1L, 2L), h.column("out", "paid"), "count(x) skips empty values");
            assertEquals(List.of(dec("50.000000"), dec("75.000000"), dec("40.000000"), dec("40.000000"),
                dec("-2.500000"), dec("1.250000")), h.column("out", "average"));
            assertEquals(List.of(dec("50.00"), dec("50.00"), dec("-30.00"), dec("-30.00"), dec("-2.50"), dec("-2.50")),
                h.column("out", "least"));
            assertEquals(List.of(dec("50.00"), dec("100.00"), dec("100.00"), dec("100.00"), dec("-2.50"), dec("5.00")),
                h.column("out", "most"));
            Schema out = h.schemas.get("out");
            assertEquals(TXNS.getFields(), out.getFields().subList(0, 4), "the input's columns come first, as they were");
            assertEquals(List.of("balance", "n", "seen", "paid", "average", "least", "most"),
                out.getFields().subList(4, 11).stream().map(Field::getName).toList());
            assertEquals(new WindowFactory().outputSchema("out", Map.of("in", TXNS), config("partition_by",
                List.of("account"), "order_by", List.of("day", "txn_id"), "columns", columns("balance", "sum(amount)",
                    "n", "row_number()", "seen", "count(*)", "paid", "count(amount)", "average", "avg(amount)", "least",
                    "min(amount)", "most", "max(amount)"))), out, "the columns the check works out are the ones it gives");
        }
    }

    @Test
    void aSumIsEmptyUntilAValueArrives() throws Exception {
        Schema s = schema(field("k", INT64), field("x", INT64));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, null), row(2L, 4L), row(3L, null), row(4L, 3L))).connect("out");
            h.run(new WindowFactory(), config("order_by", List.of("k"), "columns",
                columns("total", "sum(x)", "mean", "avg(x)", "low", "min(x)")));
            assertEquals(Arrays.asList(null, 4L, 4L, 7L), h.column("out", "total"));
            assertEquals(Arrays.asList(null, 4.0, 4.0, 3.5), h.column("out", "mean"));
            assertEquals(Arrays.asList(null, 4L, 4L, 3L), h.column("out", "low"));
        }
    }

    @Test
    void ranksShareTiesAndTiesKeepTheirArrivalOrder() throws Exception {
        Schema s = schema(field("id", INT64), field("score", INT32));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, 70), row(2L, 90), row(3L, 70), row(4L, 80)),
                h.batch(s, row(5L, 90), row(6L, 70))).connect("out");
            h.run(new WindowFactory(), config("order_by", List.of("score desc"), "columns",
                columns("n", "row_number()", "r", "rank()", "d", "dense_rank()")));
            // 90s (2, 5), 80 (4), 70s (1, 3, 6), each tie in arrival order
            assertEquals(List.of(2L, 5L, 4L, 1L, 3L, 6L), h.column("out", "id"));
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), h.column("out", "n"));
            assertEquals(List.of(1L, 1L, 3L, 4L, 4L, 4L), h.column("out", "r"));
            assertEquals(List.of(1L, 1L, 2L, 3L, 3L, 3L), h.column("out", "d"));
        }
    }

    @Test
    void ranksStartAgainInEachPartition() throws Exception {
        try (Harness h = txns(new Harness()).connect("out")) {
            h.run(new WindowFactory(), config("partition_by", "account", "order_by", "day", "columns",
                columns("r", "rank()", "d", "dense_rank()")));
            // A: 1 Jan, 2 Jan, 2 Jan (a tie), 4 Jan; B: 1 Jan, 3 Jan
            assertEquals(List.of(1L, 2L, 2L, 4L, 1L, 2L), h.column("out", "r"));
            assertEquals(List.of(1L, 2L, 2L, 3L, 1L, 2L), h.column("out", "d"));
        }
    }

    @Test
    void emptyValuesGoLastUnlessToldAndFormAGroupOfTheirOwn() throws Exception {
        Schema s = schema(field("id", INT64), field("g", UTF8), field("v", INT64));
        Object[][] rows = {row(1L, null, 5L), row(2L, "x", null), row(3L, "x", 1L), row(4L, null, null),
            row(5L, null, 2L)};
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            h.run(new WindowFactory(), config("partition_by", List.of("g"), "order_by", List.of("v"), "columns",
                columns("n", "row_number()")));
            // x first (1 then the empty v), then the empty group: 2, 5, then the empty v
            assertEquals(List.of(3L, 2L, 5L, 1L, 4L), h.column("out", "id"));
            assertEquals(List.of(1L, 2L, 1L, 2L, 3L), h.column("out", "n"));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            h.run(new WindowFactory(), config("partition_by", List.of("g"), "order_by", List.of("v desc nulls first"),
                "columns", columns("n", "row_number()", "r", "rank()")));
            assertEquals(List.of(2L, 3L, 4L, 1L, 5L), h.column("out", "id"));
            assertEquals(List.of(1L, 2L, 1L, 2L, 3L), h.column("out", "n"));
            assertEquals(List.of(1L, 2L, 1L, 2L, 3L), h.column("out", "r"));
        }
    }

    @Test
    void lagAndLeadStayInTheirPartition() throws Exception {
        try (Harness h = txns(new Harness()).connect("out")) {
            h.run(new WindowFactory(), config("partition_by", List.of("account"), "order_by", List.of("day", "txn_id"),
                "columns", columns("before", "lag(amount)", "after", "lead(txn_id)", "two_on", "lead(txn_id, 2, -1)",
                    "opening", "lag(amount, 1, 0)", "same", "lag(txn_id, 0)", "back2", "lag(day, 2)")));
            assertEquals(List.of(3L, 2L, 5L, 6L, 4L, 1L), h.column("out", "txn_id"));
            assertEquals(Arrays.asList(null, dec("50.00"), dec("100.00"), dec("-30.00"), null, dec("-2.50")),
                h.column("out", "before"));
            assertEquals(Arrays.asList(2L, 5L, 6L, null, 1L, null), h.column("out", "after"));
            assertEquals(List.of(5L, 6L, -1L, -1L, -1L, -1L), h.column("out", "two_on"));
            // a default of 0 for the first row; a null value further on stays null
            assertEquals(Arrays.asList(dec("0.00"), dec("50.00"), dec("100.00"), dec("-30.00"), dec("0.00"),
                dec("-2.50")), h.column("out", "opening"));
            assertEquals(List.of(3L, 2L, 5L, 6L, 4L, 1L), h.column("out", "same"));
            assertEquals(Arrays.asList(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), null, null),
                h.column("out", "back2"));
            Schema out = h.schemas.get("out");
            assertEquals(Type.decimal(10, 2), Type.of(out.findField("before").getType()));
            assertEquals(Type.INT64, Type.of(out.findField("two_on").getType()));
            assertEquals(Type.DATE, Type.of(out.findField("back2").getType()));
        }
    }

    @Test
    void theLatestRowPerKeyIsRowNumberOne() throws Exception {
        try (Harness h = txns(new Harness()).connect("out")) {
            h.run(new WindowFactory(), config("partition_by", List.of("account"), "order_by",
                List.of("day desc", "txn_id desc"), "columns", columns("recency", "row_number()")));
            List<Object> latest = new ArrayList<>();
            for (Map<String, Object> r : h.rows.get("out")) {
                if (Long.valueOf(1L).equals(r.get("recency"))) {
                    latest.add(r.get("txn_id"));
                }
            }
            assertEquals(List.of(6L, 1L), latest);
        }
    }

    @Test
    void anEmptyInputGivesNoRows() throws Exception {
        try (Harness h = new Harness()) {
            h.input("in").connect("out");
            h.run(new WindowFactory(), config("order_by", List.of("txn_id"), "columns", columns("n", "row_number()")));
            assertEquals(List.of(), h.rows.get("out"));
        }
    }

    // ---- checked at save ----

    private static String problem(Schema in, Map<String, Object> config) {
        NodeException e = assertThrows(NodeException.class, () -> new WindowFactory().outputSchema("out",
            Map.of("in", in), config));
        return e.getMessage();
    }

    @Test
    void theSettingsAreCheckedBeforeTheRun() {
        WindowFactory f = new WindowFactory();
        assertEquals(List.of("order_by: is required", "columns: is required"), f.check(config()));
        assertEquals(List.of("columns.Total: 'Total' and 'total' differ only in case, and a database would take them "
            + "for one column; give one another name"), f.check(config("order_by", "day", "columns",
                columns("total", "sum(amount)", "Total", "count(*)"))));
        assertEquals(List.of("memory_limit_mb: must be from 1 to 1048576"), f.check(config("order_by", "day",
            "columns", columns("n", "row_number()"), "memory_limit_mb", 0)));
        assertEquals("order_by: the input has no column 'dya'; did you mean day?",
            problem(TXNS, config("order_by", "dya", "columns", columns("n", "row_number()"))));
        assertEquals("partition_by: the input has no column 'acount'; did you mean account?",
            problem(TXNS, config("partition_by", "acount", "order_by", "day", "columns", columns("n", "row_number()"))));
        assertEquals("column Amount: the input already has a column called 'amount'; give the new column another "
            + "name, or drop that column with a select node first",
            problem(TXNS, config("order_by", "day", "columns", columns("Amount", "sum(amount)"))));
        assertTrue(problem(TXNS, config("order_by", "day", "columns", columns("x", "upper(account)")))
            .startsWith("column x: a window column is one window function"), "only window functions");
        assertTrue(problem(TXNS, config("order_by", "day", "columns", columns("x", "sum(amount) + 1")))
            .contains("not an expression"));
        assertEquals("column x: sum needs numbers, not utf8 (text) at 1",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "sum(account)"))));
        assertEquals("column x: unknown column 'amont' at 5; did you mean amount?",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "lag(amont)"))));
        assertEquals("column x: lag's offset must be a whole number from 0 to 1,000, written out at 13",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "lag(amount, -1)"))));
        assertEquals("column x: lead's offset must be a whole number from 0 to 1,000, written out at 14",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "lead(amount, 1001)"))));
        assertEquals("column x: lag's default must have the type of its value, a decimal, not utf8 (text) at 16; "
            + "cast one of them first",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "lag(amount, 1, 'none')"))));
        assertEquals("column x: row_number takes no arguments, not 1 at 1",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "row_number(day)"))));
        assertEquals("column x: a window function's arguments cannot hold another window or aggregate function "
            + "(row_number) at 5; work it out in a window node before this one",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "sum(row_number())"))));
        assertEquals("column x: a window function's arguments cannot hold another window or aggregate function "
            + "(sum) at 13; work it out in a window node before this one",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "lag(1 + abs(sum(amount)))"))));
        assertEquals("column x: only count(*) takes a star at 1; write sum(column)",
            problem(TXNS, config("order_by", "day", "columns", columns("x", "sum(*)"))));
        Schema withBinary = schema(field("id", INT64), field("blob", org.apache.arrow.vector.types.pojo.ArrowType.Binary
            .INSTANCE));
        assertEquals("partition_by: the column 'blob' holds Binary values, which cannot be grouped",
            problem(withBinary, config("partition_by", "blob", "order_by", "id", "columns", columns("n", "row_number()"))));
    }

    @Test
    void otherNodesSayWhereWindowFunctionsBelong() {
        ExprException e = assertThrows(ExprException.class, () -> Expression.parse("lag(amount) + 1")
            .compile(TXNS));
        assertEquals("lag(...) looks at other rows, so it can only be used in a window node's columns at 1",
            e.getMessage());
        assertTrue(Expression.windowFunctions().containsAll(List.of("row_number", "rank", "dense_rank", "lag", "lead",
            "sum", "count", "avg", "min", "max")));
    }

    // ---- many rows, and spilling ----

    @Test
    void matchesASimpleReferenceOnRandomData() throws Exception {
        Random random = new Random(20261107);
        for (int round = 0; round < 30; round++) {
            randomRound(random, 1 + random.nextInt(3000), 256, round);
        }
    }

    @Test
    void matchesTheReferenceWhenItSpillsToDisk() throws Exception {
        Random random = new Random(7);
        for (int round = 0; round < 4; round++) {
            Harness h = randomRound(random, 40_000 + random.nextInt(20_000), 1, round);
            assertTrue(h.spilled.get() > 0, "round " + round + " should have written to disk");
            assertTrue(h.notes.stream().anyMatch(n -> n.contains("rows in order on disk for the window functions")),
                h.notes.toString());
            Path folder = ((WindowFactory.WindowNode) h.node).spillFolder();
            assertNotNull(folder);
            assertFalse(Files.exists(folder), "its temporary folder is deleted once it ends");
        }
    }

    @Test
    void lookingTooFarAwayForTheMemoryLimitStopsTheRun() {
        Schema s = schema(field("id", INT64), field("text", UTF8));
        String wide = "x".repeat(20_000);
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> batches = new ArrayList<>();
            for (int b = 0; b < 40; b++) {
                Object[][] rows = new Object[50][];
                for (int i = 0; i < 50; i++) {
                    rows[i] = row((long) b * 50 + i, wide);
                }
                batches.add(h.batch(s, rows));
            }
            h.input("in", batches.toArray(VectorSchemaRoot[]::new)).connect("out");
            NodeException e = assertThrows(NodeException.class, () -> h.run(new WindowFactory(), config("order_by", "id",
                "columns", columns("ahead", "lead(text, 1000)"), "memory_limit_mb", 8)));
            assertTrue(e.getMessage().contains("the rows held for lag and lead take more than 8 MB"), e.getMessage());
            // the harness checks every byte was freed; and the files it wrote are gone
            Path folder = ((WindowFactory.WindowNode) h.node).spillFolder();
            assertNotNull(folder, "it had sorted on disk before it stopped");
            assertFalse(Files.exists(folder));
        }
    }

    /**
     * Wide rows and a small limit give the window many small batches, so partitions, ties, and lags
     * and leads of up to 1,000 rows all cross batch boundaries, while the batches are let go.
     */
    @Test
    void farLagsAndLeadsAcrossManySmallBatches() throws Exception {
        Schema s = schema(field("seq", INT64), field("g", INT32), field("o", INT32), field("pad", UTF8));
        String pad = "p".repeat(1_500);
        Random random = new Random(11);
        int rows = 6_000;
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(row((long) i, random.nextInt(3), random.nextInt(50), pad));
        }
        try (Harness h = new Harness()) {
            List<VectorSchemaRoot> batches = new ArrayList<>();
            for (int from = 0; from < rows; from += 500) {
                batches.add(h.batch(s, data.subList(from, from + 500).toArray(Object[][]::new)));
            }
            h.input("in", batches.toArray(VectorSchemaRoot[]::new)).connect("out");
            h.run(new WindowFactory(), config("partition_by", List.of("g"), "order_by", List.of("o"), "columns",
                columns("back", "lag(seq, 1000)", "on", "lead(seq, 999, -1)", "r", "rank()", "d", "dense_rank()"),
                "memory_limit_mb", 4));
            assertTrue(h.batches.get("out") > 10, "many small batches: " + h.batches.get("out"));
            List<Object[]> sorted = new ArrayList<>(data);
            sorted.sort(Comparator.comparing((Object[] r) -> (Integer) r[1]).thenComparing(r -> (Integer) r[2]));
            List<Map<String, Object>> got = h.rows.get("out");
            int start = 0;
            for (int i = 0; i < rows; i++) {
                if (i > 0 && !sorted.get(i)[1].equals(sorted.get(i - 1)[1])) {
                    start = i;
                }
                int end = i;
                while (end < rows && sorted.get(end)[1].equals(sorted.get(i)[1])) {
                    end++;
                }
                Map<String, Object> r = got.get(i);
                assertEquals(sorted.get(i)[0], r.get("seq"), "row " + i);
                assertEquals(i - 1000 >= start ? sorted.get(i - 1000)[0] : null, r.get("back"), "row " + i);
                assertEquals(i + 999 < end ? sorted.get(i + 999)[0] : -1L, r.get("on"), "row " + i);
                int first = i;
                while (first > start && sorted.get(first - 1)[2].equals(sorted.get(i)[2])) {
                    first--;
                }
                assertEquals((long) (first - start + 1), r.get("r"), "row " + i);
            }
        }
    }

    static final Schema RANDOM = schema(field("seq", INT64), field("g", INT32), field("o", UTF8), field("x", FLOAT64));
    private static final String[] TEXTS = {"", "a", "A", "b", "é", "north"};

    /** Random rows through the node, against the same functions worked out plainly in Java. */
    private static Harness randomRound(Random random, int rows, long limitMb, int round) throws Exception {
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(row((long) i, random.nextInt(8) == 0 ? null : random.nextInt(6),
                random.nextInt(8) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)],
                random.nextInt(8) == 0 ? null : (double) (random.nextInt(200) - 100)));
        }
        boolean partitioned = random.nextBoolean();
        boolean desc = random.nextBoolean();
        int back = random.nextInt(4);
        int ahead = random.nextInt(4) * (random.nextInt(4) == 0 ? 300 : 1);
        Harness h = new Harness();
        try {
            List<VectorSchemaRoot> batches = new ArrayList<>();
            for (int from = 0; from < rows; from += 1000) {
                batches.add(h.batch(RANDOM, data.subList(from, Math.min(rows, from + 1000)).toArray(Object[][]::new)));
            }
            h.input("in", batches.toArray(VectorSchemaRoot[]::new)).connect("out");
            Map<String, Object> config = config("order_by", List.of("o" + (desc ? " desc" : "")), "columns",
                columns("n", "row_number()", "r", "rank()", "d", "dense_rank()", "total", "sum(x)", "high", "max(x)",
                    "before", "lag(seq, " + back + ", -1)", "after", "lead(x, " + ahead + ")"),
                "memory_limit_mb", limitMb);
            if (partitioned) {
                config.put("partition_by", List.of("g"));
            }
            h.run(new WindowFactory(), config);

            // the reference: a stable sort by (g nulls last, o), then each function plainly
            Comparator<String> text = WindowNodeTest::codePoints;
            Comparator<Object[]> byO = Comparator.comparing((Object[] r) -> (String) r[2],
                Comparator.nullsLast(desc ? text.reversed() : text));
            Comparator<Object[]> order = partitioned ? Comparator.comparing((Object[] r) -> (Integer) r[1],
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byO) : byO;
            List<Object[]> sorted = new ArrayList<>(data);
            sorted.sort(order);
            List<Map<String, Object>> got = h.rows.get("out");
            assertEquals(rows, got.size(), "round " + round);
            Map<Object, List<Integer>> parts = new LinkedHashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                parts.computeIfAbsent(partitioned ? Objects.requireNonNullElse(sorted.get(i)[1], "null") : "all",
                    k -> new ArrayList<>()).add(i);
            }
            Map<Integer, Map<String, Object>> expected = new HashMap<>();
            for (List<Integer> p : parts.values()) {
                long r = 0;
                long d = 0;
                Double total = null;
                Double high = null;
                for (int k = 0; k < p.size(); k++) {
                    Object[] row = sorted.get(p.get(k));
                    boolean peer = k > 0 && Objects.equals(sorted.get(p.get(k - 1))[2], row[2]);
                    if (!peer) {
                        r = k + 1L;
                        d++;
                    }
                    Double x = (Double) row[3];
                    if (x != null) {
                        total = total == null ? x : total + x;
                        high = high == null ? x : Math.max(high, x);
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("seq", row[0]);
                    e.put("n", (long) k + 1);
                    e.put("r", r);
                    e.put("d", d);
                    e.put("total", total);
                    e.put("high", high);
                    e.put("before", k - back >= 0 ? sorted.get(p.get(k - back))[0] : -1L);
                    e.put("after", k + ahead < p.size() ? sorted.get(p.get(k + ahead))[3] : null);
                    expected.put(p.get(k), e);
                }
            }
            for (int i = 0; i < rows; i++) {
                Map<String, Object> want = expected.get(i);
                Map<String, Object> was = new LinkedHashMap<>();
                for (String key : want.keySet()) {
                    was.put(key, got.get(i).get(key));
                }
                if (!want.equals(was)) {
                    throw new AssertionError("round " + round + " (partitioned " + partitioned + ", desc " + desc
                        + ", lag " + back + ", lead " + ahead + "), row " + i + ": expected " + want + " but was " + was);
                }
            }
            return h;
        } finally {
            h.close();
        }
    }

    /** Text in code point order, as the node orders it. */
    private static int codePoints(String a, String b) {
        return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
    }

    @Test
    void decimalsKeepTheirScaleInTotals() throws Exception {
        Schema s = schema(field("k", INT64), field("m", decimal(12, 3)));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, new BigDecimal("1.250")), row(2L, new BigDecimal("2.125")))).connect("out");
            h.run(new WindowFactory(), config("order_by", "k", "columns", columns("t", "sum(m)")));
            assertEquals(List.of(new BigDecimal("1.250"), new BigDecimal("3.375")), h.column("out", "t"));
            assertEquals(Type.decimal(3), Type.of(h.schemas.get("out").findField("t").getType()));
        }
    }
}
