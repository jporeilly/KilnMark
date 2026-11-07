package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.DATE;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeException;

/**
 * Which row of a repeated key wins, on dedupe, lookup and an upsert's chooser: every rule, ties,
 * empty deciding values, the checks, and messages that name the key's columns but never a value.
 */
class WhichRowTest {

    /** Account balances as they arrive: account 1 three times, account 2 twice. */
    static final Schema BALANCES = schema(field("account", INT64), field("balance", decimal(12, 2)),
        field("as_of", DATE), field("note", UTF8));

    private static Harness balances(Harness h) {
        return h.input("in",
            h.batch(BALANCES,
                row(1L, dec("10.00"), LocalDate.parse("2026-01-02"), "a"),
                row(2L, dec("5.00"), LocalDate.parse("2026-01-05"), "b"),
                row(1L, dec("30.00"), LocalDate.parse("2026-01-09"), "c")),
            h.batch(BALANCES,
                row(1L, dec("20.00"), LocalDate.parse("2026-01-01"), "d"),
                row(2L, dec("7.00"), LocalDate.parse("2026-01-05"), "e"),
                row(3L, dec("1.00"), null, "f")));
    }

    private static List<Object> dedupe(Object... settings) throws NodeException {
        try (Harness h = balances(new Harness()).connect("out", "rejected")) {
            h.run(new DedupeFactory(), config(settings));
            List<Object> kept = new ArrayList<>(h.column("out", "note"));
            kept.add("|");
            kept.addAll(h.column("rejected", "note"));
            return kept;
        }
    }

    // ---- dedupe ----

    @Test
    void dedupeKeepsTheFirstByDefaultAndEachRuleOnRequest() throws Exception {
        List<String> key = List.of("account");
        assertEquals(List.of("a", "b", "f", "|", "c", "d", "e"), dedupe("key", key));
        assertEquals(List.of("a", "b", "f", "|", "c", "d", "e"), dedupe("key", key, "duplicates", "first"));
        // held to the end, then sent in the order they came: winners to out, the rest to rejected
        assertEquals(List.of("d", "e", "f", "|", "a", "b", "c"), dedupe("key", key, "duplicates", "last"));
        assertEquals(List.of("c", "e", "f", "|", "a", "b", "d"), dedupe("key", key, "duplicates", "latest",
            "duplicates_by", "balance"));
        assertEquals(List.of("a", "b", "f", "|", "c", "d", "e"), dedupe("key", key, "duplicates", "earliest",
            "duplicates_by", "balance"));
        assertEquals(List.of("b", "c", "f", "|", "a", "d", "e"), dedupe("key", key, "duplicates", "latest",
            "duplicates_by", "as_of"));
        assertEquals(List.of("b", "d", "f", "|", "a", "c", "e"), dedupe("key", key, "duplicates", "earliest",
            "duplicates_by", "as_of"));
    }

    @Test
    void aTieGoesToTheFirstOfTheTiedRowsAndAnEmptyValueNeverWins() throws Exception {
        Schema s = schema(field("k", INT64), field("at", DATE), field("v", UTF8));
        for (String rule : List.of("latest", "earliest")) {
            try (Harness h = new Harness()) {
                h.input("in", h.batch(s, row(1L, null, "empty first"), row(1L, LocalDate.parse("2026-03-01"), "first tie"),
                        row(2L, null, "only empty"), row(2L, null, "also empty")),
                    h.batch(s, row(1L, LocalDate.parse("2026-03-01"), "second tie"), row(1L, null, "empty last")));
                h.connect("out", "rejected");
                h.run(new DedupeFactory(), config("key", List.of("k"), "duplicates", rule, "duplicates_by", "at"));
                assertEquals(List.of("first tie", "only empty"), h.column("out", "v"), rule);
                assertEquals(List.of("empty first", "also empty", "second tie", "empty last"), h.column("rejected", "v"),
                    rule);
            }
        }
    }

    @Test
    void textOrdersByCodePointAndNumbersByValueAcrossTypes() {
        assertTrue(WhichRow.compare("Z", "a") < 0);
        assertTrue(WhichRow.compare("￿", "😀") < 0, "a character beyond the first plane orders after");
        assertEquals(0, WhichRow.compare(1L, dec("1.00")));
        assertTrue(WhichRow.compare(2.5d, 2L) > 0);
        assertTrue(WhichRow.compare(Double.NaN, 1e300) > 0);
        assertTrue(WhichRow.compare(false, true) < 0);
        assertTrue(WhichRow.beats(WhichRow.Rule.LATEST, 1L, null));
        assertFalse(WhichRow.beats(WhichRow.Rule.EARLIEST, null, 1L));
        assertFalse(WhichRow.beats(WhichRow.Rule.LATEST, 1L, 1L), "a tie keeps the row that came first");
    }

    @Test
    void errorStopsTheRunNamingTheKeyButNeverItsValue() throws Exception {
        Schema s = schema(field("iban", UTF8), field("v", INT64));
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row("GB33BUKB20201555555555", 1L)), h.batch(s, row("GB33BUKB20201555555555", 2L)));
            h.connect("out", "rejected");
            NodeException e = assertThrows(NodeException.class,
                () -> h.run(new DedupeFactory(), config("key", List.of("iban"), "duplicates", "error")));
            assertEquals("the input has more than one row with the same key (iban); set duplicates to first, last, "
                + "latest or earliest to choose which row is kept", e.getMessage());
            assertFalse(e.getMessage().contains("GB33"));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, row("x", 1L), row("x", 1L)));
            h.connect("out");
            NodeException e = assertThrows(NodeException.class,
                () -> h.run(new DedupeFactory(), config("duplicates", "error")));
            assertTrue(e.getMessage().startsWith("the input has more than one row with the same values in every column"),
                e.getMessage());
        }
        try (Harness h = balances(new Harness()).connect("out")) {
            // no key repeats in a whole row here: error lets every row through
            h.run(new DedupeFactory(), config("duplicates", "error"));
            assertEquals(6, h.rows.get("out").size());
        }
    }

    @Test
    void theSettingsAreChecked() throws Exception {
        DedupeFactory f = new DedupeFactory();
        assertEquals(List.of("duplicates_by: latest needs the column that decides, such as updated_at"),
            f.check(config("duplicates", "latest")));
        assertEquals(List.of("duplicates_by: only latest and earliest decide by a column; leave it out for first"),
            f.check(config("duplicates_by", "as_of")));
        assertEquals(List.of("duplicates: one of first, last, latest, earliest, error"),
            f.check(config("duplicates", "newest")));
        assertEquals(List.of("duplicates_by: one column name"),
            f.check(config("duplicates", "latest", "duplicates_by", List.of("a", "b"))));
        NodeException unknown = assertThrows(NodeException.class, () -> f.outputSchema("out", Map.of("in", BALANCES),
            config("duplicates", "latest", "duplicates_by", "as_off")));
        assertEquals("duplicates_by: unknown column 'as_off'; did you mean as_of?", unknown.getMessage());
        Schema binary = schema(field("k", INT64), field("blob", new org.apache.arrow.vector.types.pojo.ArrowType.Binary()));
        NodeException cannot = assertThrows(NodeException.class, () -> f.outputSchema("out", Map.of("in", binary),
            config("duplicates", "latest", "duplicates_by", "blob")));
        assertTrue(cannot.getMessage().startsWith("duplicates_by: the column 'blob' holds Binary values, which cannot "
            + "be put in order"), cannot.getMessage());
    }

    @Test
    void dedupeHoldingRowsStopsAtItsMemoryLimit() throws Exception {
        Schema s = schema(field("k", UTF8));
        Object[][] rows = new Object[5_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(i + "x".repeat(300));
        }
        try (Harness h = new Harness()) {
            h.input("in", h.batch(s, rows)).connect("out");
            NodeException e = assertThrows(NodeException.class, () -> h.run(new DedupeFactory(),
                config("duplicates", "last", "memory_limit_mb", 1)));
            assertTrue(e.getMessage().startsWith("the rows held to choose which row wins, and their keys, take more "
                + "than 1 MB"), e.getMessage());
        }
    }

    // ---- lookup ----

    static final Schema ORDERS = schema(field("customer_id", INT64), field("order", UTF8));
    static final Schema TIERS = schema(field("id", INT32), field("tier", UTF8), field("since", DATE));

    private static List<Object> tiers(Object... settings) throws NodeException {
        try (Harness h = new Harness()) {
            h.input("main", h.batch(ORDERS, row(1L, "o1"), row(2L, "o2"), row(3L, "o3")))
                .input("lookup",
                    h.batch(TIERS, row(1, "bronze", LocalDate.parse("2025-01-01")), row(2, "silver", null),
                        row(1, "gold", LocalDate.parse("2026-01-01"))),
                    h.batch(TIERS, row(1, "silver", LocalDate.parse("2024-01-01")),
                        row(2, "gold", LocalDate.parse("2020-01-01")), row(3, "x", LocalDate.parse("2026-01-01")),
                        row(3, "y", LocalDate.parse("2026-01-01"))))
                .connect("out");
            List<Object> on = new ArrayList<>(List.of("on", Map.of("customer_id", "id"), "keep", List.of("tier")));
            on.addAll(Arrays.asList(settings));
            h.run(new LookupFactory(), config(on.toArray()));
            return h.column("out", "tier");
        }
    }

    @Test
    void lookupUsesTheWinnerOfItsLookupSide() throws Exception {
        assertEquals(List.of("bronze", "silver", "x"), tiers());
        assertEquals(List.of("bronze", "silver", "x"), tiers("duplicates", "first"));
        assertEquals(List.of("silver", "gold", "y"), tiers("duplicates", "last"));
        // the deciding column need not be kept; a tie (customer 3) keeps the first, an empty date never wins
        assertEquals(List.of("gold", "gold", "x"), tiers("duplicates", "latest", "duplicates_by", "since"));
        assertEquals(List.of("silver", "gold", "x"), tiers("duplicates", "earliest", "duplicates_by", "since"));
    }

    @Test
    void lookupErrorNamesTheLookupKeyButNeverItsValue() throws Exception {
        NodeException e = assertThrows(NodeException.class, () -> tiers("duplicates", "error"));
        assertEquals("the lookup side has more than one row with the same key (id); set duplicates to first, last, "
            + "latest or earliest to choose which row is kept", e.getMessage());
        LookupFactory f = new LookupFactory();
        NodeException unknown = assertThrows(NodeException.class, () -> f.outputSchema("out",
            Map.of("main", ORDERS, "lookup", TIERS), config("on", Map.of("customer_id", "id"), "duplicates", "latest",
                "duplicates_by", "sinse")));
        assertEquals("duplicates_by: unknown column 'sinse'; did you mean since?", unknown.getMessage(),
            "the deciding column is the lookup side's");
    }

    // ---- an upsert's chooser ----

    private static List<List<Object>> upserts(String rule, String by) throws NodeException {
        try (BufferAllocator a = new RootAllocator(Long.MAX_VALUE)) {
            WhichRow.Upserts u = new WhichRow.Upserts(new WhichRow.Settings(WhichRow.Rule.valueOf(rule), by),
                List.of("account"), 16);
            List<List<Object>> sent = new ArrayList<>();
            for (Object[][] rows : List.of(
                new Object[][] {row(1L, dec("10.00"), LocalDate.parse("2026-01-02"), "a"),
                    row(2L, dec("5.00"), LocalDate.parse("2026-01-05"), "b"),
                    row(1L, dec("30.00"), LocalDate.parse("2026-01-09"), "c")},
                new Object[][] {row(1L, dec("20.00"), LocalDate.parse("2026-01-01"), "d"),
                    row(null, dec("1.00"), null, "no key"), row(2L, dec("7.00"), LocalDate.parse("2026-01-05"), "e")})) {
                try (VectorSchemaRoot out = u.choose(Harness.batch(a, BALANCES, rows), a)) {
                    sent.add(Harness.rowsOf(out).stream().map(r -> r.get("note")).toList());
                }
            }
            sent.add(List.of(u.leftOut(), u.replacing()));
            return sent;
        }
    }

    @Test
    void anUpsertIsSentOnlyRowsThatLeaveEachKeysWinnerInTheTable() throws Exception {
        assertEquals(List.of(List.of("a", "b"), List.of("no key"), List.of(3L, 0L)), upserts("FIRST", null));
        // a better row is sent again and replaces the earlier one; the table ends with c and e
        assertEquals(List.of(List.of("a", "b", "c"), List.of("no key", "e"), List.of(1L, 2L)),
            upserts("LATEST", "balance"));
        assertEquals(List.of(List.of("a", "b"), List.of("d", "no key"), List.of(2L, 1L)), upserts("EARLIEST", "as_of"));
        NodeException e = assertThrows(NodeException.class, () -> upserts("ERROR", null));
        assertEquals("the input has more than one row with the same key (account); set duplicates to first, last, "
            + "latest or earliest to choose which row is kept", e.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> new WhichRow.Upserts(new WhichRow.Settings(WhichRow.Rule.LAST, null), List.of("k"), 1));
    }
}
