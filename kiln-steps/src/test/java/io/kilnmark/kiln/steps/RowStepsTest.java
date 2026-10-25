package io.kilnmark.kiln.steps;

import static io.kilnmark.kiln.steps.Harness.BOOL;
import static io.kilnmark.kiln.steps.Harness.FLOAT64;
import static io.kilnmark.kiln.steps.Harness.INT32;
import static io.kilnmark.kiln.steps.Harness.INT64;
import static io.kilnmark.kiln.steps.Harness.UTF8;
import static io.kilnmark.kiln.steps.Harness.dec;
import static io.kilnmark.kiln.steps.Harness.decimal;
import static io.kilnmark.kiln.steps.Harness.field;
import static io.kilnmark.kiln.steps.Harness.row;
import static io.kilnmark.kiln.steps.Harness.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.steps.expr.Type;

/** filter, compute, select, dedupe, route and limit, each driven by the harness. */
class RowStepsTest {

    static final Schema PEOPLE = schema(field("id", INT32), field("first_name", UTF8), field("last_name", UTF8),
        field("amount", decimal(10, 2)), field("active", BOOL));

    static Harness people(Harness h) {
        return h.input("in",
            h.batch(PEOPLE, row(1, " Ada ", "Lovelace", dec("10.00"), true), row(2, "Grace", "Hopper", dec("0.00"), true)),
            h.batch(PEOPLE, row(3, "Alan", "Turing", dec("5.50"), false), row(4, null, "Nobody", null, null)));
    }

    static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- filter ----

    @Test
    void filterSplitsRowsAndSendsNullConditionsToRejected() throws Exception {
        try (Harness h = people(new Harness()).connect("out", "rejected")) {
            h.run(new FilterFactory(), config("condition", "active and amount > 0"));
            assertEquals(List.of(1L, 3L).subList(0, 1), h.column("out", "id"));
            assertEquals(List.of(2L, 3L, 4L), h.column("rejected", "id"));
            assertEquals(PEOPLE, h.schemas.get("out"));
        }
    }

    @Test
    void filterDropsRejectedRowsWhenNothingIsConnected() throws Exception {
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new FilterFactory(), config("condition", "amount >= 5"));
            assertEquals(List.of(1L, 3L), h.column("out", "id"));
        }
    }

    @Test
    void filterHandsOnWholeBatchesAndCopesWithEmptyInput() throws Exception {
        try (Harness h = people(new Harness()).connect("out", "rejected")) {
            h.run(new FilterFactory(), config("condition", "true"));
            assertEquals(4, h.rows.get("out").size());
            assertEquals(2, h.batches.get("out"));
            assertEquals(0, h.rows.get("rejected").size());
        }
        try (Harness h = new Harness().input("in").connect("out")) {
            h.run(new FilterFactory(), config("condition", "id > 1"));
            assertEquals(0, h.rows.get("out").size());
        }
    }

    @Test
    void filterConditionMustBeBoolean() throws Exception {
        FilterFactory f = new FilterFactory();
        StepException before = assertThrows(StepException.class,
            () -> f.outputSchema("out", Map.of("in", PEOPLE), config("condition", "amount + 1")));
        assertEquals("condition: the condition must be true or false, but it gives decimal; compare the value, for "
            + "example amount > 0", before.getMessage());
        try (Harness h = people(new Harness()).connect("out")) {
            StepException during = assertThrows(StepException.class,
                () -> h.run(f, config("condition", "upper(frist_name) = 'ADA'")));
            assertEquals("condition: unknown column 'frist_name' at 7; did you mean first_name?", during.getMessage());
        }
        assertEquals(List.of("condition: the expression ends too early at 6"), f.check(config("condition", "id = ")));
        assertEquals(List.of("condition: is required"), f.check(config()));
    }

    // ---- compute ----

    @Test
    void computeReplacesAppendsAndChains() throws Exception {
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new ComputeFactory(), config("columns", new LinkedHashMap<>(Map.of(
                "first_name", "trim(first_name)"))));
        }
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("first_name", "trim(first_name)");
        columns.put("full_name", "first_name || ' ' || last_name");
        columns.put("vat", "round(amount * 0.2, 2)");
        columns.put("big", 1); // YAML numbers are expressions too
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new ComputeFactory(), config("columns", columns));
            assertEquals(Arrays.asList("Ada Lovelace", "Grace Hopper", "Alan Turing", null), h.column("out", "full_name"));
            assertEquals(Arrays.asList(dec("2.00"), dec("0.00"), dec("1.10"), null), h.column("out", "vat"));
            assertEquals(List.of("id", "first_name", "last_name", "amount", "active", "full_name", "vat", "big"),
                h.schemas.get("out").getFields().stream().map(f -> f.getName()).toList());
            assertEquals(INT32, h.schemas.get("out").findField("id").getType());
            Schema predicted = new ComputeFactory().outputSchema("out", Map.of("in", PEOPLE), config("columns", columns));
            assertEquals(predicted, h.schemas.get("out"));
        }
    }

    @Test
    void computeErrorsNameTheColumn() throws Exception {
        ComputeFactory f = new ComputeFactory();
        Map<String, Object> cfg = config("columns", Map.of("full_name", "upper(frist_name) || last_name"));
        StepException e = assertThrows(StepException.class, () -> f.outputSchema("out", Map.of("in", PEOPLE), cfg));
        assertEquals("column full_name: unknown column 'frist_name' at 7; did you mean first_name?", e.getMessage());
        try (Harness h = people(new Harness()).connect("out")) {
            StepException run = assertThrows(StepException.class,
                () -> h.run(f, config("columns", Map.of("ratio", "100 / (id - 1)"))));
            assertEquals("column ratio: division by zero at 5; guard it with nullif, like x / nullif(y, 0)",
                run.getMessage());
        }
        assertEquals(List.of("columns.x: unexpected ')' at 3; there is a ')' without a matching '('"),
            f.check(config("columns", Map.of("x", "1 )"))));
        assertEquals(List.of("columns: must be a map of names to values, like { a: b }"),
            f.check(config("columns", List.of("a"))));
    }

    // ---- select ----

    @Test
    void selectKeepsRenamesOrdersAndCasts() throws Exception {
        Map<String, Object> cfg = config(
            "drop", List.of("active"),
            "rename", Map.of("last_name", "surname"),
            "order", List.of("last_name", "amount"),
            "cast", Map.of("id", "utf8", "amount", "float64"));
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new SelectFactory(), cfg);
            assertEquals(List.of("surname", "amount", "id", "first_name"),
                h.schemas.get("out").getFields().stream().map(f -> f.getName()).toList());
            assertEquals(List.of("1", "2", "3", "4"), h.column("out", "id"));
            assertEquals(Arrays.asList(10.0, 0.0, 5.5, null), h.column("out", "amount"));
            assertEquals(new SelectFactory().outputSchema("out", Map.of("in", PEOPLE), cfg), h.schemas.get("out"));
        }
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new SelectFactory(), config("keep", "last_name, id"));
            assertEquals(List.of("last_name", "id"), h.schemas.get("out").getFields().stream().map(f -> f.getName()).toList());
        }
    }

    @Test
    void selectProblems() throws Exception {
        SelectFactory f = new SelectFactory();
        assertEquals(List.of("drop: use either keep or drop, not both"),
            f.check(config("keep", List.of("a"), "drop", List.of("b"))));
        assertEquals(List.of("cast.id: unknown type 'money'; use boolean, int64, float64, decimal(p,s), utf8, date or "
            + "timestamp"), f.check(config("cast", Map.of("id", "money"))));
        StepException e = assertThrows(StepException.class,
            () -> f.outputSchema("out", Map.of("in", PEOPLE), config("keep", List.of("ammount"))));
        assertEquals("keep: unknown column 'ammount'; did you mean amount?", e.getMessage());
        StepException cast = assertThrows(StepException.class,
            () -> f.outputSchema("out", Map.of("in", PEOPLE), config("cast", Map.of("active", "date"))));
        assertEquals("cast: the column 'active' is boolean, which cannot be turned into date", cast.getMessage());
        try (Harness h = people(new Harness()).connect("out")) {
            StepException run = assertThrows(StepException.class,
                () -> h.run(f, config("cast", Map.of("last_name", "int64"))));
            assertTrue(run.getMessage().startsWith("cast.last_name: cannot cast a text value to int64"), run.getMessage());
        }
    }

    // ---- dedupe ----

    @Test
    void dedupeKeepsFirstPerKeyAndTreatsNullsAsEqual() throws Exception {
        Schema s = schema(field("k", INT64), field("v", UTF8), field("x", FLOAT64));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, "a", 1.0), row(1L, "b", 2.0), row(null, "c", 3.0)),
                h.batch(s, row(null, "d", 4.0), row(2L, "a", 5.0), row(1L, "a", 1.0)));
            h.connect("out", "rejected");
            h.run(new DedupeFactory(), config("key", List.of("k")));
            assertEquals(List.of("a", "c", "a"), h.column("out", "v"));
            assertEquals(List.of("b", "d", "a"), h.column("rejected", "v"));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, "a", 1.0), row(1L, "a", 1.0), row(1L, "a", -0.0), row(1L, "a", 0.0)));
            h.connect("out");
            h.run(new DedupeFactory(), config());
            assertEquals(List.of(1.0, -0.0), h.column("out", "x"));
        }
    }

    @Test
    void dedupeStopsAtItsMemoryLimit() throws Exception {
        Schema s = schema(field("k", UTF8));
        Object[][] rows = new Object[5_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(i + "x".repeat(300));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            StepException e = assertThrows(StepException.class, () -> h.run(new DedupeFactory(),
                config("memory_limit_mb", 1)));
            assertTrue(e.getMessage().startsWith("the keys seen so far take more than 1 MB (5,000 keys)"), e.getMessage());
        }
        StepException unknown = assertThrows(StepException.class,
            () -> new DedupeFactory().outputSchema("out", Map.of("in", s), config("key", List.of("kk"))));
        assertEquals("key: unknown column 'kk'; did you mean k?", unknown.getMessage());
    }

    // ---- route ----

    @Test
    void routeSendsRowsToTheFirstMatchingPort() throws Exception {
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("big", "amount >= 10");
        routes.put("some", "amount > 0");
        routes.put("inactive", "not active");
        RouteFactory f = new RouteFactory();
        assertEquals(List.of("big", "some", "inactive", "out"), f.outputPorts(config("routes", routes)));
        try (Harness h = people(new Harness()).connect("big", "some", "out")) {
            h.run(f, config("routes", routes));
            assertEquals(List.of(1L), h.column("big", "id"));
            assertEquals(List.of(3L), h.column("some", "id")); // not 'inactive': first match wins
            assertEquals(List.of(2L, 4L), h.column("out", "id"));
        }
        assertEquals(List.of("routes.Big: a port name must start with a small letter and use only small letters, digits,"
            + " - and _"), f.check(config("routes", Map.of("Big", "true"))));
        assertEquals(List.of("routes.out: 'out' is taken; choose another port name"),
            f.check(config("routes", Map.of("out", "true"))));
        StepException e = assertThrows(StepException.class,
            () -> f.outputSchema("big", Map.of("in", PEOPLE), config("routes", Map.of("big", "amount"))));
        assertTrue(e.getMessage().startsWith("route big: the condition must be true or false"));
        assertEquals(PEOPLE, f.outputSchema("big", Map.of("in", PEOPLE), config("routes", routes)));
    }

    // ---- limit ----

    @Test
    void limitPassesTheFirstRowsThenStops() throws Exception {
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new LimitFactory(), config("rows", 3));
            assertEquals(List.of(1L, 2L, 3L), h.column("out", "id"));
            assertEquals(0, h.unread());
        }
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new LimitFactory(), config("rows", "0"));
            assertEquals(0, h.rows.get("out").size());
            assertEquals(2, h.unread()); // stopped without reading: the runner ends the steps before it
        }
        try (Harness h = people(new Harness()).connect("out")) {
            h.run(new LimitFactory(), config("rows", 2));
            assertEquals(List.of(1L, 2L), h.column("out", "id"));
            assertEquals(1, h.unread());
        }
        assertEquals(List.of("rows: must be from 0 to " + LimitFactory.MAX_ROWS), new LimitFactory().check(config("rows", -1)));
        assertEquals(List.of("rows: must be a whole number"), new LimitFactory().check(config("rows", 2.5)));
        assertEquals(List.of("rows: is required"), new LimitFactory().check(config()));
    }

    @Test
    void typesSurviveAsExpressionTypes() {
        assertEquals(Type.INT64, Type.of(INT32));
    }
}
