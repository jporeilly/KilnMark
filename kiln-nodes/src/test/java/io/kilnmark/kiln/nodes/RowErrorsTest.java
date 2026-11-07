package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.INT64;
import static io.kilnmark.kiln.nodes.Harness.UTF8;
import static io.kilnmark.kiln.nodes.Harness.field;
import static io.kilnmark.kiln.nodes.Harness.row;
import static io.kilnmark.kiln.nodes.Harness.schema;
import static io.kilnmark.kiln.nodes.RowNodesTest.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowErrors;

/**
 * Rows that fail (ADR 0054): compute, select, filter and route send them to their errors port with
 * the four error columns, never a value in the message; the tolerance stops the run past its count
 * or share; with nothing on the errors port the first failure stops the run, as before.
 */
class RowErrorsTest {

    /** Payments as a feed sends them: the amounts are text, and two are not numbers. */
    static final Schema PAYMENTS = schema(field("id", INT64), field("iban", UTF8), field("amount", UTF8));
    /** A value that must never appear in a message. */
    static final String SECRET = "GB33BUKB20201555555555";

    private static Harness payments(Harness h) {
        Object[][] first = {row(1L, "GB01", "10"), row(2L, SECRET, "12a"), row(3L, "GB03", "7"), row(4L, "GB04", "3")};
        Object[][] second = new Object[20][];
        for (int i = 0; i < second.length; i++) {
            second[i] = row(10L + i, "GB" + i, i == 13 ? "x" + SECRET : Integer.toString(i));
        }
        return h.input("in", h.batch(PAYMENTS, first), h.batch(PAYMENTS, second));
    }

    private static Map<String, Object> computeConfig(Object... more) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("pence", "cast(amount as int64) * 100");
        List<Object> kv = new ArrayList<>(List.of("columns", columns));
        kv.addAll(List.of(more));
        return config(kv.toArray());
    }

    private static void noValueIn(Harness h) {
        for (Map<String, Object> r : h.rows.get(RowErrors.PORT)) {
            String said = r.get(RowErrors.MESSAGE) + " " + r.get(RowErrors.CODE) + " " + r.get(RowErrors.COLUMN);
            assertFalse(said.contains(SECRET) || said.contains("12a"), said);
        }
    }

    @Test
    void computeSendsFailingRowsToErrorsWithWhyAndKeepsTheRest() throws Exception {
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            h.run(new ComputeFactory(), computeConfig());
            assertEquals(22, h.rows.get("out").size());
            assertEquals(List.of(1L, 3L, 4L), h.column("out", "id").subList(0, 3));
            assertEquals(List.of(2L, 23L), h.column(RowErrors.PORT, "id"));
            // the row as it arrived, with its sensitive value in its own column, and the four columns
            assertEquals(List.of("id", "iban", "amount", "error_node", "error_column", "error_code", "error_message"),
                h.schemas.get(RowErrors.PORT).getFields().stream().map(Field::getName).toList());
            Map<String, Object> e = h.rows.get(RowErrors.PORT).get(0);
            assertEquals(SECRET, e.get("iban"));
            assertEquals("12a", e.get("amount"));
            assertEquals("pence", e.get(RowErrors.COLUMN));
            assertEquals("cast_failed", e.get(RowErrors.CODE));
            assertEquals("cannot cast a text value to int64: it is not a whole number; guard it, for example case when "
                + "regex_match(x, '^-?[0-9]+$') then cast(x as int64) end", e.get(RowErrors.MESSAGE));
            noValueIn(h);
            assertEquals(new ComputeFactory().outputSchema(RowErrors.PORT, Map.of("in", PAYMENTS), computeConfig()),
                h.schemas.get(RowErrors.PORT));
            assertEquals(List.of("2 rows of 24 failed and went to the errors port"), h.notes);
        }
    }

    @Test
    void withNothingOnTheErrorsPortTheFirstFailureStopsTheRunAsBefore() throws Exception {
        try (Harness h = payments(new Harness()).connect("out")) {
            NodeException e = assertThrows(NodeException.class, () -> h.run(new ComputeFactory(), computeConfig()));
            assertTrue(e.getMessage().startsWith("column pence: cannot cast a text value to int64"), e.getMessage());
        }
    }

    @Test
    void theToleranceStopsTheRunPastItsCountOrShare() throws Exception {
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            NodeException e = assertThrows(NodeException.class, () -> h.run(new ComputeFactory(),
                computeConfig(RowErrors.MAX_ERRORS, 1)));
            assertEquals("2 of the 24 rows so far failed, more than its max_errors of 1; the run stops. The failing "
                + "rows so far went to its errors port", e.getMessage());
        }
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            h.run(new ComputeFactory(), computeConfig(RowErrors.MAX_ERRORS, 2, RowErrors.MAX_SHARE, 0.1));
            assertEquals(2, h.rows.get(RowErrors.PORT).size(), "at the limits, not past them");
            assertEquals(List.of("2 rows of 24 failed and went to the errors port (limits: max_errors 2, "
                + "max_error_share 10.00%)"), h.notes);
        }
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            NodeException e = assertThrows(NodeException.class, () -> h.run(new ComputeFactory(),
                computeConfig(RowErrors.MAX_SHARE, 0.05)));
            assertEquals("2 of 24 rows (8.33%) failed, more than its max_error_share of 5.00%; the run stops. The "
                + "failing rows went to its errors port", e.getMessage());
        }
        ComputeFactory f = new ComputeFactory();
        assertEquals(List.of("max_errors: a whole number of rows, 0 or more, such as 100",
            "max_error_share: a share from 0 to 1, such as 0.01 for 1%"),
            f.check(computeConfig(RowErrors.MAX_ERRORS, -1, RowErrors.MAX_SHARE, 2)));
    }

    @Test
    void selectSetsAsideRowsWhoseCastFails() throws Exception {
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            h.run(new SelectFactory(), config("cast", Map.of("amount", "int64"), "rename", Map.of("iban", "account")));
            assertEquals(22, h.rows.get("out").size());
            assertEquals(List.of("id", "account", "amount"),
                h.schemas.get("out").getFields().stream().map(Field::getName).toList());
            // error rows are the rows as they arrived: the input's names, so their marks follow them
            assertEquals("iban", h.schemas.get(RowErrors.PORT).getFields().get(1).getName());
            assertEquals(List.of("amount", "amount"), h.column(RowErrors.PORT, RowErrors.COLUMN));
            noValueIn(h);
        }
    }

    @Test
    void filterKeepsFalseRowsApartFromRowsThatFail() throws Exception {
        try (Harness h = payments(new Harness()).connect("out", "rejected", RowErrors.PORT)) {
            h.run(new FilterFactory(), config("condition", "cast(amount as int64) > 5"));
            assertEquals(List.of(1L, 3L), h.column("out", "id").subList(0, 2));
            assertEquals(4L, h.column("rejected", "id").get(0));
            assertEquals(List.of(2L, 23L), h.column(RowErrors.PORT, "id"));
            assertEquals(List.of("condition", "condition"), h.column(RowErrors.PORT, RowErrors.COLUMN));
            assertEquals(24, h.rows.get("out").size() + h.rows.get("rejected").size() + 2, "every row is counted once");
            noValueIn(h);
        }
    }

    @Test
    void routeSetsAsideRowsWhereARouteFails() throws Exception {
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("small", "id < 3");
        routes.put("big", "cast(amount as int64) >= 10");
        try (Harness h = payments(new Harness()).connect("small", "big", "out", RowErrors.PORT)) {
            h.run(new RouteFactory(), config("routes", routes));
            // row 2 is routed by the first route before the failing one is tried
            assertEquals(List.of(1L, 2L), h.column("small", "id"));
            assertEquals(List.of(23L), h.column(RowErrors.PORT, "id"));
            assertEquals(List.of("routes.big"), h.column(RowErrors.PORT, RowErrors.COLUMN));
            noValueIn(h);
        }
        assertEquals(List.of("routes.errors: 'errors' is taken; choose another port name"),
            new RouteFactory().check(config("routes", Map.of("errors", "id > 1"))));
    }

    @Test
    void aRowThatFailedBeforeTakesTheLatestFailure() throws Exception {
        Schema failed = RowErrors.schema(PAYMENTS);
        assertEquals(failed, RowErrors.schema(failed), "the error columns are replaced, not added twice");
        try (Harness h = new Harness()) {
            h.input("in", h.batch(failed, row(2L, SECRET, "12a", "earlier", "x", "cast_failed", "an earlier failure")));
            h.connect("out", RowErrors.PORT);
            h.run(new ComputeFactory(), computeConfig());
            assertEquals("test", h.rows.get(RowErrors.PORT).get(0).get(RowErrors.NODE));
            assertEquals("pence", h.rows.get(RowErrors.PORT).get(0).get(RowErrors.COLUMN));
        }
    }

    @Test
    void aFailureWithoutAKnownCodeIsWordedGenerically() throws Exception {
        io.kilnmark.kiln.nodes.expr.ExprException plain = new io.kilnmark.kiln.nodes.expr.ExprException(
            "something about " + SECRET, 3);
        RowErrors.Failure f = Failing.failure("c", plain);
        assertEquals("expression_failed", f.code());
        assertEquals(RowErrors.GENERIC, f.message());
        assertNull(plain.code());
    }

    @Test
    void aFailureNotKnownToBeAboutTheRowStopsTheRunEvenWithTheErrorsPort() throws Exception {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("part", "split_part(amount, '-', 0)");
        try (Harness h = payments(new Harness()).connect("out", RowErrors.PORT)) {
            NodeException e = assertThrows(NodeException.class, () -> h.run(new ComputeFactory(),
                config("columns", columns)));
            assertTrue(e.getMessage().startsWith("column part: split_part's field number cannot be 0"), e.getMessage());
        }
    }

    @Test
    void divisionByZeroAndOutOfRangeHaveTheirOwnCodes() throws Exception {
        Schema s = schema(field("a", INT64), field("b", INT64));
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("q", "a / b");
        columns.put("big", "a * 2305843009213693952");
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row(1L, 0L), row(4L, 2L), row(3L, 1L)));
            h.connect("out", RowErrors.PORT);
            h.run(new ComputeFactory(), config("columns", columns));
            assertEquals(List.of("division_by_zero", "out_of_range"), h.column(RowErrors.PORT, RowErrors.CODE));
            assertEquals(List.of("q", "big"), h.column(RowErrors.PORT, RowErrors.COLUMN));
            assertEquals(List.of(3L), h.column("out", "a"));
        }
    }
}
