package io.kilnmark.kiln.nodes.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.ReadFilter;

class PushDownTest {

    private static Field f(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }

    private static final Schema ORDERS = new Schema(List.of(
        f("order_id", new ArrowType.Int(64, true)),
        f("status", ArrowType.Utf8.INSTANCE),
        f("shipping_fee", new ArrowType.Decimal(7, 2, 128)),
        f("score", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
        f("ordered_on", new ArrowType.Date(DateUnit.DAY)),
        f("ordered_at", new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
        f("gift_wrap", ArrowType.Bool.INSTANCE)));

    private static PushDown.Split split(String where) throws ExprException {
        return PushDown.split(where, ORDERS);
    }

    @Test
    void comparisonsOfColumnsWithValuesGoToTheSource() throws ExprException {
        PushDown.Split s = split("status in ('delivered', 'shipped') and shipping_fee >= 5.0 and order_id <> 7");
        assertTrue(s.whole(), s.kept().toString());
        assertEquals(new ReadFilter.And(List.of(
            new ReadFilter.In("status", List.of("delivered", "shipped"), false),
            new ReadFilter.Compare("shipping_fee", ">=", new BigDecimal("5.0")),
            new ReadFilter.Compare("order_id", "<>", 7L))), s.filter());
        assertEquals(new ReadFilter.Compare("order_id", "<", 10L), split("10 > order_id").filter(), "flipped");
        assertEquals(new ReadFilter.Compare("order_id", ">", -3L), split("order_id > -3").filter());
        assertEquals(new ReadFilter.IsNull("ordered_on", true), split("ordered_on is not null").filter());
        assertEquals(new ReadFilter.Between("score", new BigDecimal("1.5"), new BigDecimal("2.5"), false),
            split("score between 1.5 and 2.5").filter());
        assertEquals(new ReadFilter.Compare("gift_wrap", "=", true), split("gift_wrap").filter());
        assertEquals(new ReadFilter.Like("status", "deliv%"), split("status like 'deliv%'").filter());
        assertEquals(new ReadFilter.Compare("ordered_on", ">=", LocalDate.of(2026, 1, 1)),
            split("ordered_on >= date '2026-01-01'").filter());
        assertEquals(new ReadFilter.Or(List.of(new ReadFilter.Compare("order_id", "=", 1L),
            new ReadFilter.Compare("order_id", "=", 2L))), split("order_id = 1 or order_id = 2").filter());
        assertEquals(new ReadFilter.Not(new ReadFilter.Compare("order_id", "=", 1L)), split("not order_id = 1").filter());
    }

    /**
     * What a source does must let through every row the condition keeps: text is compared only for
     * equality, never under not, and what the source cannot do of an or or a not stays in Kilnmark.
     */
    @Test
    void nothingThatCouldDropARowGoesToTheSource() throws ExprException {
        for (String w : new String[] {"status <> 'x'", "status > 'm'", "not status = 'x'", "status not in ('a')",
            "status not like 'a%'", "lower(status) = 'x'", "order_id + 1 = 3", "order_id = shipping_fee",
            "ordered_at > now()", "status like 'a\\\\%'", "status = 'a' or lower(status) = 'b'",
            "not (status = 'x' and order_id = 1)"}) {
            PushDown.Split s = split(w);
            assertNull(s.filter(), w);
            assertFalse(s.kept().isEmpty(), w);
        }
        // Half of an and is enough: the source lets more through, and Kilnmark drops the rest.
        PushDown.Split half = split("order_id > 1 and lower(status) = 'x'");
        assertEquals(new ReadFilter.Compare("order_id", ">", 1L), half.filter());
        assertFalse(half.whole());
        assertEquals(List.of("a comparison that is not a column with a value"), half.kept());
    }

    /** A JSON or uuid column read as text, or a number read as true/false: the source compares something else. */
    @Test
    void aColumnTheSourceStoresDifferentlyStaysInKilnmark() throws ExprException {
        PushDown.Split s = PushDown.split("status = 'a' and order_id = 1 and gift_wrap", ORDERS,
            c -> !c.equals("status") && !c.equals("gift_wrap"));
        assertEquals(new ReadFilter.Compare("order_id", "=", 1L), s.filter());
        assertTrue(s.kept().contains("status: the source stores it differently from how Kilnmark reads it"), s.kept().toString());
        assertTrue(s.kept().contains("gift_wrap: the source stores it differently from how Kilnmark reads it"));
        assertNull(PushDown.split("status is null", ORDERS, c -> false).filter());
    }

    /** SQL Server reads [a] in a like pattern as "a", so a pattern with [ stays in Kilnmark. */
    @Test
    void aLikePatternWithABracketStaysInKilnmark() throws ExprException {
        assertNull(split("status like 'a[1]%'").filter());
    }

    @Test
    void theSourceKeepsExactlyOnlyWithTheWholeConditionAndNoText() throws ExprException {
        assertTrue(split("order_id > 1 and ordered_on >= date '2026-01-01'").keepsExactly());
        assertFalse(split("status = 'x'").keepsExactly(), "a database may compare text more loosely");
        assertFalse(split("order_id > 1 and lower(status) = 'x'").keepsExactly(), "not the whole condition");
    }

    @Test
    void aValueOfTheWrongKindStaysInKilnmark() throws ExprException {
        assertNull(split("ordered_at > timestamp '2026-01-01 00:00:00'").filter(), "timestamps differ by time zone");
    }

    @Test
    void aConditionThatDoesNotCheckIsAnError() {
        assertThrows(ExprException.class, () -> split("no_such_column = 1"));
        assertThrows(ExprException.class, () -> split("status = "));
    }
}
