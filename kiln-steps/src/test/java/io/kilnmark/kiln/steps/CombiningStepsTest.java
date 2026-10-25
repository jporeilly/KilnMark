package io.kilnmark.kiln.steps;

import static io.kilnmark.kiln.steps.Harness.DATE;
import static io.kilnmark.kiln.steps.Harness.FLOAT64;
import static io.kilnmark.kiln.steps.Harness.INT32;
import static io.kilnmark.kiln.steps.Harness.INT64;
import static io.kilnmark.kiln.steps.Harness.TIMESTAMP;
import static io.kilnmark.kiln.steps.Harness.UTF8;
import static io.kilnmark.kiln.steps.Harness.dec;
import static io.kilnmark.kiln.steps.Harness.decimal;
import static io.kilnmark.kiln.steps.Harness.field;
import static io.kilnmark.kiln.steps.Harness.row;
import static io.kilnmark.kiln.steps.Harness.schema;
import static io.kilnmark.kiln.steps.RowStepsTest.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.StepException;

/** lookup, aggregate and union. */
class CombiningStepsTest {

    static final Schema SPEND = schema(field("customer_id", INT64), field("total", decimal(12, 2)));
    static final Schema CUSTOMERS = schema(field("id", INT32), field("name", UTF8), field("tier", UTF8));

    private static Harness joined(Harness h) {
        return h.input("main",
                h.batch(SPEND, row(1L, dec("10.00")), row(2L, dec("20.00")), row(9L, dec("1.00"))),
                h.batch(SPEND, row(null, dec("2.00")), row(3L, dec("3.00"))))
            .input("lookup",
                h.batch(CUSTOMERS, row(1, "Ada", "gold"), row(2, "Grace", null)),
                h.batch(CUSTOMERS, row(3, "Alan", "silver"), row(1, "Ada again", "x")));
    }

    // ---- lookup ----

    @Test
    void lookupLeftKeepsUnmatchedRowsWithEmptyColumns() throws Exception {
        Map<String, Object> cfg = config("on", Map.of("customer_id", "id"), "keep", List.of("name"));
        try (Harness h = joined(new Harness()).connect("out", "rejected")) {
            h.run(new LookupFactory(), cfg);
            assertEquals(Arrays.asList(1L, 2L, 9L, null, 3L), h.column("out", "customer_id"));
            assertEquals(Arrays.asList("Ada", "Grace", null, null, "Alan"), h.column("out", "name"));
            assertEquals(0, h.rows.get("rejected").size());
            assertEquals(new LookupFactory().outputSchema("out", Map.of("main", SPEND, "lookup", CUSTOMERS), cfg),
                h.schemas.get("out"));
        }
    }

    @Test
    void lookupInnerAndRejected() throws Exception {
        try (Harness h = joined(new Harness()).connect("out", "rejected")) {
            h.run(new LookupFactory(), config("on", Map.of("customer_id", "id"), "join", "inner"));
            assertEquals(List.of(1L, 2L, 3L), h.column("out", "customer_id"));
            assertEquals(List.of("name", "tier"), h.schemas.get("out").getFields().subList(2, 4).stream()
                .map(f -> f.getName()).toList());
            assertEquals(0, h.rows.get("rejected").size());
        }
        try (Harness h = joined(new Harness()).connect("out", "rejected")) {
            h.run(new LookupFactory(), config("on", Map.of("customer_id", "id"), "unmatched", "rejected",
                "keep", List.of("tier")));
            assertEquals(Arrays.asList("gold", null, "silver"), h.column("out", "tier"));
            assertEquals(Arrays.asList(9L, null), h.column("rejected", "customer_id"));
            assertEquals(SPEND, h.schemas.get("rejected"));
        }
    }

    @Test
    void lookupDuplicateKeys() throws Exception {
        try (Harness h = joined(new Harness()).connect("out")) {
            StepException e = assertThrows(StepException.class, () -> h.run(new LookupFactory(),
                config("on", Map.of("customer_id", "id"), "duplicates", "error")));
            assertEquals("the lookup side has more than one row with the key customer_id = 1; set duplicates: first "
                + "to use the first such row", e.getMessage());
        }
    }

    @Test
    void lookupMatchesAcrossNumberAndDateTypes() throws Exception {
        Schema main = schema(field("k", decimal(10, 2)), field("day", DATE));
        Schema lookup = schema(field("k", INT32), field("at", TIMESTAMP), field("label", UTF8));
        try (Harness h = new Harness()) {
            h.input("main", h.batch(main, row(dec("1.00"), LocalDate.of(2024, 1, 31)), row(dec("1.50"),
                LocalDate.of(2024, 1, 31))));
            h.input("lookup", h.batch(lookup, row(1, Instant.parse("2024-01-31T00:00:00Z"), "match")));
            h.connect("out");
            h.run(new LookupFactory(), config("on", new LinkedHashMap<>(Map.of("k", "k", "day", "at")),
                "keep", List.of("label")));
            assertEquals(Arrays.asList("match", null), h.column("out", "label"));
        }
        StepException e = assertThrows(StepException.class, () -> new LookupFactory().outputSchema("out",
            Map.of("main", schema(field("k", UTF8)), "lookup", schema(field("k", INT64))), config("on", Map.of("k", "k"))));
        assertEquals("on: the main column 'k' (utf8) cannot be matched with the lookup column 'k' (int64); cast one "
            + "of them first", e.getMessage());
    }

    @Test
    void lookupProblems() throws Exception {
        LookupFactory f = new LookupFactory();
        assertEquals(List.of("unmatched: 'keep' contradicts join: inner; leave one of them out"),
            f.check(config("on", Map.of("a", "b"), "join", "inner", "unmatched", "keep")));
        assertEquals(List.of("on: is required"), f.check(config()));
        assertEquals(List.of("join: must be one of left, inner, not 'outer'"),
            f.check(config("on", Map.of("a", "b"), "join", "outer")));
        StepException clash = assertThrows(StepException.class, () -> f.outputSchema("out",
            Map.of("main", schema(field("id", INT64), field("name", UTF8)), "lookup", CUSTOMERS),
            config("on", Map.of("id", "id"))));
        assertTrue(clash.getMessage().startsWith("keep: the column 'name' is on both sides"), clash.getMessage());
        StepException unknown = assertThrows(StepException.class, () -> f.outputSchema("out",
            Map.of("main", SPEND, "lookup", CUSTOMERS), config("on", Map.of("customer_id", "idd"))));
        assertEquals("on: the lookup input has no column 'idd'; did you mean id?", unknown.getMessage());
    }

    @Test
    void lookupStopsAtItsMemoryLimit() throws Exception {
        Object[][] rows = new Object[4_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(i, "n".repeat(400), "t");
        }
        try (Harness h = new Harness()) {
            h.input("main", h.batch(SPEND, row(1L, dec("1.00"))));
            h.input("lookup", h.batch(CUSTOMERS, rows));
            h.connect("out");
            StepException e = assertThrows(StepException.class, () -> h.run(new LookupFactory(),
                config("on", Map.of("customer_id", "id"), "memory_limit_mb", 1)));
            assertTrue(e.getMessage().startsWith("the lookup side holds more than 1 MB (4,000 rows read so far"),
                e.getMessage());
        }
    }

    /** Main batches arrive before the lookup side ends, as when both come from one step upstream. */
    @Test
    void lookupReadsMainWhileTheLookupSideLoads() throws Exception {
        try (Harness h = new Harness()) {
            RowSet main = new RowSet("main", 1);
            RowSet lookup = new RowSet("lookup", 1);
            h.input("main", main).input("lookup", lookup).connect("out");
            Thread upstream = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        main.put(h.batch(SPEND, row((long) i, dec("1.00"))));
                    }
                    main.finish();
                    lookup.put(h.batch(CUSTOMERS, row(1, "Ada", "gold")));
                    lookup.finish();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> h.run(new LookupFactory(), config("on", Map.of("customer_id", "id"), "keep", List.of("name"))));
            upstream.join();
            assertEquals(Arrays.asList(null, "Ada", null, null, null), h.column("out", "name"));
        }
    }

    // ---- aggregate ----

    static final Schema TXNS = schema(field("customer", UTF8), field("amount", decimal(10, 2)), field("qty", INT32),
        field("rate", FLOAT64), field("at", TIMESTAMP));

    private static Harness txns(Harness h) {
        return h.input("in",
            h.batch(TXNS, row("a", dec("10.00"), 1, 1.5, Instant.parse("2024-01-01T00:00:00Z")),
                row("b", dec("5.00"), 2, null, Instant.parse("2024-02-01T00:00:00Z")),
                row("a", dec("2.50"), 3, 2.5, Instant.parse("2024-03-01T00:00:00Z"))),
            h.batch(TXNS, row(null, dec("1.00"), 4, 0.5, null), row("a", null, 1, 1.5, null)));
    }

    @Test
    void aggregateGroupsAndSummarises() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rows", "count(*)");
        values.put("amounts", "count(amount)");
        values.put("total", "sum(amount)");
        values.put("biggest", "max(amount)");
        values.put("first", "min(at)");
        values.put("mean_rate", "avg(rate)");
        values.put("mean_amount", "avg(amount)");
        values.put("qtys", "count_distinct(qty)");
        values.put("per_row", "sum(amount * qty) / count(*)");
        values.put("label", "upper(customer) || '!'");
        Map<String, Object> cfg = config("group_by", List.of("customer"), "values", values);
        try (Harness h = txns(new Harness()).connect("out")) {
            h.run(new AggregateFactory(), cfg);
            assertEquals(Arrays.asList("a", "b", null), h.column("out", "customer"));
            assertEquals(List.of(3L, 1L, 1L), h.column("out", "rows"));
            assertEquals(List.of(2L, 1L, 1L), h.column("out", "amounts"));
            assertEquals(List.of(dec("12.50"), dec("5.00"), dec("1.00")), h.column("out", "total"));
            assertEquals(List.of(dec("10.00"), dec("5.00"), dec("1.00")), h.column("out", "biggest"));
            assertEquals(Arrays.asList(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-02-01T00:00:00Z"),
                null), h.column("out", "first"));
            assertEquals(Arrays.asList(5.5 / 3, null, 0.5), h.column("out", "mean_rate"));
            assertEquals(List.of(dec("6.250000"), dec("5.000000"), dec("1.000000")), h.column("out", "mean_amount"));
            assertEquals(List.of(2L, 1L, 1L), h.column("out", "qtys"));
            assertEquals(List.of(dec("5.833333"), dec("10.000000"), dec("4.000000")), h.column("out", "per_row"));
            assertEquals(Arrays.asList("A!", "B!", null), h.column("out", "label"));
            assertEquals(new AggregateFactory().outputSchema("out", Map.of("in", TXNS), cfg), h.schemas.get("out"));
        }
    }

    @Test
    void aggregateWithoutGroupsGivesOneRowEvenForNoRows() throws Exception {
        Map<String, Object> cfg = config("values", new LinkedHashMap<>(Map.of("n", "count(*)", "total", "sum(amount)")));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(TXNS)).connect("out");
            h.run(new AggregateFactory(), cfg);
            assertEquals(List.of(0L), h.column("out", "n"));
            assertEquals(Arrays.asList((Object) null), h.column("out", "total"));
        }
        try (Harness h = new Harness().input("in").connect("out")) {
            h.run(new AggregateFactory(), cfg);
            assertEquals(0, h.rows.get("out").size()); // no input at all: the columns are unknown
        }
    }

    @Test
    void aggregateWritesManyGroupsInBatches() throws Exception {
        Schema s = schema(field("k", INT64));
        Object[][] rows = new Object[20_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row((long) i % 10_000);
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            h.run(new AggregateFactory(), config("group_by", "k", "values", Map.of("n", "count(*)")));
            assertEquals(10_000, h.rows.get("out").size());
            assertEquals(2, h.batches.get("out"));
            assertTrue(h.column("out", "n").stream().allMatch(n -> n.equals(2L)));
        }
    }

    @Test
    void aggregateProblems() throws Exception {
        AggregateFactory f = new AggregateFactory();
        Map<String, Schema> in = Map.of("in", TXNS);
        StepException outside = assertThrows(StepException.class, () -> f.outputSchema("out", in,
            config("group_by", List.of("customer"), "values", Map.of("x", "amount + 1"))));
        assertEquals("value x: the column 'amount' must be in group_by, or inside an aggregate such as max(amount) at 1",
            outside.getMessage());
        StepException nested = assertThrows(StepException.class, () -> f.outputSchema("out", in,
            config("values", Map.of("x", "sum(max(amount))"))));
        assertEquals("value x: an aggregate cannot be inside another aggregate at 5", nested.getMessage());
        StepException text = assertThrows(StepException.class, () -> f.outputSchema("out", in,
            config("values", Map.of("x", "sum(customer)"))));
        assertEquals("value x: sum needs numbers, not utf8 (text) at 1", text.getMessage());
        StepException group = assertThrows(StepException.class, () -> f.outputSchema("out", in,
            config("group_by", List.of("custmer"), "values", Map.of("x", "count(*)"))));
        assertEquals("group_by: unknown column 'custmer'; did you mean customer?", group.getMessage());
        assertEquals(List.of("values.customer: 'customer' is also a group_by column; give the value another name"),
            f.check(config("group_by", List.of("customer"), "values", Map.of("customer", "count(*)"))));
    }

    @Test
    void aggregateStopsAtItsMemoryLimit() throws Exception {
        Schema s = schema(field("k", UTF8));
        Object[][] rows = new Object[5_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(i + "k".repeat(200));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            StepException e = assertThrows(StepException.class, () -> h.run(new AggregateFactory(),
                config("group_by", List.of("k"), "values", Map.of("n", "count_distinct(k)"), "memory_limit_mb", 1)));
            assertTrue(e.getMessage().startsWith("the groups take more than 1 MB (5,000 groups so far)"), e.getMessage());
        }
    }

    // ---- union ----

    @Test
    void unionMatchesColumnsByNameAndWidens() throws Exception {
        Schema a = schema(field("id", INT32), field("name", UTF8));
        Schema b = schema(field("name", UTF8), field("id", INT64), field("extra", FLOAT64));
        Schema c = schema(field("id", decimal(5, 1)));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(a, row(1, "x"), row(2, "y")));
            h.input("in", h.batch(b, row("z", 3L, 1.5)));
            h.input("in"); // ends without a batch: adds no columns
            h.connect("out");
            h.run(new UnionFactory(), config());
            Schema out = h.schemas.get("out");
            assertEquals(List.of("id", "name", "extra"), out.getFields().stream().map(f -> f.getName()).toList());
            assertEquals(new ArrowType.Int(64, true), out.findField("id").getType());
            assertEquals(3, h.rows.get("out").size());
            assertTrue(h.column("out", "id").containsAll(List.of(1L, 2L, 3L)));
            assertTrue(h.column("out", "extra").contains(null));
        }
        Schema predicted = new UnionFactory().outputSchema("out", new LinkedHashMap<>(Map.of("in[0]", a, "in[1]", c)),
            config());
        assertEquals(new ArrowType.Decimal(20, 1, 128), predicted.findField("id").getType());
        assertTrue(predicted.findField("name").isNullable());
    }

    @Test
    void unionStrictAndConflicts() throws Exception {
        Schema a = schema(field("id", INT64), field("name", UTF8));
        Schema b = schema(field("id", INT64));
        UnionFactory f = new UnionFactory();
        StepException strict = assertThrows(StepException.class, () -> f.outputSchema("out",
            new LinkedHashMap<>(Map.of("in[0]", a, "in[1]", b)), config("strict", true)));
        assertEquals("strict: input 2 differs from input 1: it has no column 'name'", strict.getMessage());
        assertEquals(a, f.outputSchema("out", new LinkedHashMap<>(Map.of("in[0]", a, "in[1]", a)), config("strict", true)));
        StepException conflict = assertThrows(StepException.class, () -> f.outputSchema("out",
            new LinkedHashMap<>(Map.of("in[0]", a, "in[1]", schema(field("id", UTF8)))), config()));
        assertTrue(conflict.getMessage().startsWith("the column 'id' is int64 in one input and utf8 in another"),
            conflict.getMessage());
        try (Harness h = new Harness()) {
            h.input("in", h.batch(a, row(1L, "x"))).input("in", h.batch(b, row(2L))).connect("out");
            StepException run = assertThrows(StepException.class, () -> h.run(f, config("strict", "true")));
            assertTrue(run.getMessage().startsWith("strict: input 2 differs"), run.getMessage());
        }
    }

    @Test
    void unionReadsWhicheverInputHasRows() throws Exception {
        Schema s = schema(field("n", INT64));
        try (Harness h = new Harness()) {
            RowSet first = new RowSet("first", 1);
            RowSet second = new RowSet("second", 1);
            h.input("in", first).input("in", second).connect("out");
            Thread upstream = Thread.ofVirtual().start(() -> {
                try {
                    for (long i = 0; i < 5; i++) {
                        second.put(h.batch(s, row(i)));
                    }
                    second.finish();
                    first.put(h.batch(s, row(99L)));
                    first.finish();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> h.run(new UnionFactory(), config()));
            upstream.join();
            assertEquals(6, h.rows.get("out").size());
        }
    }

    @Test
    void emptyBatchesAreNotPassedOn() throws Exception {
        try (Harness h = new Harness()) {
            VectorSchemaRoot empty = h.batch(SPEND);
            h.input("in", empty).connect("out");
            h.run(new FilterFactory(), config("condition", "true"));
            assertEquals(0, h.batches.get("out"));
        }
        assertNull(new FilterFactory().outputSchema("out", Map.of(), config("condition", "true")));
        assertFalse(new LimitFactory().settings().isEmpty());
    }
}
