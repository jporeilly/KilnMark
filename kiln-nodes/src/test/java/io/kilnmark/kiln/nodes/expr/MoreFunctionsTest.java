package io.kilnmark.kiln.nodes.expr;

import static io.kilnmark.kiln.nodes.expr.ExpressionTest.days;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.error;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.eval;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.evalColumn;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.micros;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The functions added after the first set (ADR 0049, 6.3): if, padding, the start and end of text,
 * splitting, text tests, greatest and least, adding to and the difference between dates, and sha256.
 * Each with its types, its empty (null) values and its mistakes found before a run.
 */
class MoreFunctionsTest {

    @Test
    void ifChoosesOneOfTwoAndWorksOutOnlyThatOne() throws ExprException {
        assertEquals("big", eval("if(i > 5, 'big', 'small')"));
        assertEquals("small", eval("if(i > 50, 'big', 'small')"));
        assertEquals("small", eval("if(nb, 'big', 'small')"), "an empty condition is not true");
        assertNull(eval("if(b, ns, 'x')"), "an empty result is passed on");
        assertEquals(Type.decimal(21, 2), type("if(b, i, d)"), "the results widen to one type");
        assertEquals(new BigDecimal("7.00"), eval("if(b, i, d)"));
        // only the branch taken is worked out, so this never divides by zero
        assertEquals(Arrays.asList(null, 5L, null), evalColumn("if(x <> 0, 10 / x, null)", "x", Type.INT64, 0L, 2L,
            null).stream().map(v -> v == null ? null : Math.round((Double) v)).toList());
        assertEquals("if needs a condition that is true or false as its first argument, not an int64 at 4; compare it, "
            + "like if(status = 'open', ...)", error("if(i, 1, 2)"));
        assertEquals("if needs its two results to have one type, but one is an int64 and the other utf8 (text) at 10; "
            + "cast them to one type", error("if(b, 1, 'x')"));
        assertEquals("if takes 3 arguments, not 2 at 1", error("if(b, 1)"));
        assertEquals("matched", eval("IF(s like '%Hello%', 'matched', 'no')"), "names are not case-sensitive");
        // case, as before, is the way to choose among more than two
        assertEquals("medium", eval("case when i >= 100 then 'large' when i >= 5 then 'medium' else 'small' end"));
    }

    @Test
    void paddingCountsCharactersAndCutsLongerText() {
        assertEquals("00042", eval("lpad('42', 5, '0')"));
        assertEquals("42000", eval("rpad('42', 5, '0')"));
        assertEquals("   42", eval("lpad('42', 5)"), "a space by default");
        assertEquals("abab42", eval("lpad('42', 6, 'ab')"));
        assertEquals("aba42", eval("lpad('42', 5, 'ab')"));
        assertEquals("hel", eval("lpad('hello', 3, '*')"), "longer text is cut to its first characters");
        assertEquals("hel", eval("rpad('hello', 3, '*')"));
        assertEquals("", eval("lpad('hello', 0)"));
        assertEquals("", eval("lpad('hello', -2)"));
        assertEquals("hi", eval("lpad('hi', 5, '')"), "an empty fill leaves the text");
        assertEquals("😀😀é", eval("lpad('é', 3, '😀')"), "an emoji is one character");
        assertNull(eval("lpad(ns, 3, '0')"));
        assertNull(eval("lpad('1', ni, '0')"));
        assertTrue(error("lpad('x', 2000000)").contains("1,000,000 characters"), error("lpad('x', 2000000)"));
        assertEquals("lpad needs a whole number as argument 2, not utf8 (text) at 11", error("lpad('x', '3')"));
    }

    @Test
    void leftAndRightTakeTheStartOrEnd() {
        assertEquals("He", eval("left(trim(s), 2)"));
        assertEquals("ld", eval("right(trim(s), 2)"));
        assertEquals("Hello Wor", eval("left(trim(s), -2)"), "a negative count leaves that many off the other end");
        assertEquals("llo World", eval("right(trim(s), -2)"));
        assertEquals("abc", eval("left('abc', 10)"));
        assertEquals("", eval("left('abc', 0)"));
        assertEquals("", eval("right('abc', -5)"));
        assertEquals("😀b", eval("right('a😀b', 2)"));
        assertNull(eval("left(ns, 1)"));
    }

    @Test
    void splitPartCountsFieldsFromEitherEnd() {
        assertEquals("b", eval("split_part('a,b,c', ',', 2)"));
        assertEquals("c", eval("split_part('a,b,c', ',', -1)"));
        assertEquals("a", eval("split_part('a,b,c', ',', -3)"));
        assertEquals("", eval("split_part('a,b,c', ',', 4)"));
        assertEquals("", eval("split_part('a,b,c', ',', -4)"));
        assertEquals("", eval("split_part('a,,c', ',', 2)"));
        assertEquals("c", eval("split_part('a::b::c', '::', 3)"));
        assertEquals("a,b", eval("split_part('a,b', '', 1)"), "an empty separator leaves one field");
        assertEquals("", eval("split_part('a,b', '', 2)"));
        assertEquals("example.com", eval("split_part('ada@example.com', '@', 2)"));
        assertNull(eval("split_part(ns, ',', 1)"));
        assertEquals("split_part's field number cannot be 0 at 1; count fields from 1, or from -1 for the last",
            error("split_part('a', ',', 0)"));
    }

    @Test
    void textTests() {
        assertEquals(true, eval("starts_with(s, '  He')"));
        assertEquals(false, eval("starts_with(s, 'He')"));
        assertEquals(true, eval("ends_with(s, 'd ')"));
        assertEquals(true, eval("contains(s, 'lo Wo')"));
        assertEquals(false, eval("contains(s, 'lo wo')"), "case matters");
        assertEquals(true, eval("contains(s, '')"));
        assertNull(eval("contains(ns, 'x')"));
        assertNull(eval("starts_with('x', ns)"));
        assertEquals("starts_with needs text as argument 1, not an int64 at 13; cast it first, like cast(x as utf8)",
            error("starts_with(i, '7')"));
    }

    @Test
    void greatestAndLeastSkipEmptyValues() throws ExprException {
        assertEquals(7L, eval("greatest(i, j, zero)"));
        assertEquals(0L, eval("least(i, j, zero)"));
        assertEquals(new BigDecimal("12.34"), eval("greatest(i, d)"), "widened to one type");
        assertEquals(Type.decimal(21, 2), type("greatest(i, d)"));
        assertEquals(7L, eval("greatest(ni, i)"), "empty values are skipped");
        assertNull(eval("least(ni, null)"), "empty only when every value is");
        assertEquals("b", eval("greatest('a', 'b', 'B')"), "text in code point order");
        assertEquals(days("2024-01-31"), eval("greatest(dt, date '2023-12-31')"));
        assertEquals(micros("2024-01-31T09:30:15.25Z"), eval("greatest(dt, ts)"), "a date is midnight beside a timestamp");
        assertEquals("greatest needs arguments of one type, but has an int64 and utf8 (text) at 13; cast them to one "
            + "type", error("greatest(i, 'x')"));
    }

    @Test
    void dateAddMovesByCalendarUnits() {
        assertEquals(days("2024-02-29"), eval("date_add(dt, 1, 'month')"), "31 January plus a month: the end of February");
        assertEquals(days("2024-04-30"), eval("date_add(dt, 1, 'quarter')"));
        assertEquals(days("2023-01-31"), eval("date_add(dt, -1, 'year')"));
        assertEquals(days("2024-02-14"), eval("date_add(dt, 2, 'week')"));
        assertEquals(days("2024-01-21"), eval("date_add(dt, -10, 'DAY')"));
        assertEquals(micros("2024-01-31T11:30:15.25Z"), eval("date_add(ts, 2, 'hour')"));
        assertEquals(micros("2024-01-31T09:29:15.25Z"), eval("date_add(ts, -1, 'minute')"));
        assertEquals(micros("2024-01-31T09:30:45.25Z"), eval("date_add(ts, 30, 'second')"));
        assertEquals(micros("2024-02-29T09:30:15.25Z"), eval("date_add(ts, 1, 'month')"));
        assertEquals(Type.DATE, typeOf("date_add(dt, 1, 'day')"));
        assertEquals(Type.TIMESTAMP, typeOf("date_add(ts, 1, 'day')"));
        assertNull(eval("date_add(dt, ni, 'day')"));
        assertNull(eval("date_add(null, 1, 'day')"));
        assertEquals("date_add: a date has no hours at 17; cast it to a timestamp first", error("date_add(dt, 1, 'hour')"));
        assertTrue(error("date_add(dt, 1, 'fortnight')").startsWith("date_add needs a unit in quotes as argument 3: one "
            + "of day, hour, minute, month, quarter, second, week, year"));
        assertTrue(error("date_add(dt, 1, s)").startsWith("date_add needs a unit in quotes"), "units are written out");
        assertEquals("a date is out of range (years -9999 to 9999) at 1", error("date_add(dt, 9000, 'year')"));
        assertTrue(error("date_add(dt, 9223372036854775807, 'quarter')").contains("out of range"));
        assertTrue(error("date_add(s, 1, 'day')").contains("read text with parse_date or parse_timestamp first"));
    }

    @Test
    void dateDiffCountsWholeUnits() {
        assertEquals(29L, eval("date_diff('day', dt, date '2024-02-29')"));
        assertEquals(0L, eval("date_diff('month', dt, date '2024-02-29')"), "not yet a whole month");
        assertEquals(1L, eval("date_diff('month', dt, date '2024-03-01')"));
        assertEquals(-1L, eval("date_diff('year', dt, date '2022-06-01')"), "negative when the second is earlier");
        assertEquals(1L, eval("date_diff('quarter', dt, date '2024-05-01')"));
        assertEquals(4L, eval("date_diff('week', dt, date '2024-03-01')"));
        assertEquals(9L, eval("date_diff('hour', dt, ts)"), "a date counts from its midnight");
        assertEquals(24L, eval("date_diff('hour', dt, date '2024-02-01')"), "time units between two dates");
        assertEquals(570L, eval("date_diff('minute', dt, ts)"));
        assertEquals(34215L, eval("date_diff('second', dt, ts)"));
        assertEquals(Type.INT64, typeOf("date_diff('day', dt, dt)"));
        assertNull(eval("date_diff('day', dt, null)"));
        assertTrue(error("date_diff(dt, dt, 'day')").startsWith("date_diff needs a unit in quotes as argument 1"));
        assertTrue(error("date_diff('day', dt, 3)").startsWith("date_diff needs a date or timestamp, not an int64"));
    }

    @Test
    void sha256GivesHexadecimalOfTheUtf8Bytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", eval("sha256('')"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", eval("sha256('abc')"));
        assertEquals("23d4c9f2a1ad11bc0d9cbfa8b4a8e0a0e0a61b6d1ef0f6dde6bbf1d2e1bd6b6c".length(),
            ((String) eval("sha256(s)")).length());
        assertNull(eval("sha256(ns)"));
        assertEquals("sha256 needs text as argument 1, not an int64 at 8; cast it first, like cast(x as utf8)",
            error("sha256(i)"));
        assertEquals(eval("sha256('42')"), eval("sha256(cast(42 as utf8))"));
    }

    @Test
    void everyNewFunctionIsListed() {
        assertTrue(Expression.functions().containsAll(List.of("if", "lpad", "rpad", "left", "right", "split_part",
            "starts_with", "ends_with", "contains", "greatest", "least", "date_add", "date_diff", "sha256")));
        assertTrue(!Expression.functions().contains("md5"), "md5 is left out: see the guide");
    }

    private static Type typeOf(String expr) {
        try {
            return type(expr);
        } catch (ExprException e) {
            throw new AssertionError(e);
        }
    }
}
