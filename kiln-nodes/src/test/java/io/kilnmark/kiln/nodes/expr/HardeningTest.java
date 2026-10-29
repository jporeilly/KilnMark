package io.kilnmark.kiln.nodes.expr;

import static io.kilnmark.kiln.nodes.expr.ExpressionTest.error;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.eval;
import static io.kilnmark.kiln.nodes.expr.ExpressionTest.evalColumn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Inputs written to exhaust time, memory or the stack, and values at the edges of their types. */
class HardeningTest {

    @Test
    void chainedBetweenIsRefusedQuickly() {
        String chain = "b between true and true " + "between true and true ".repeat(40);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(error(chain)
            .startsWith("comparisons cannot follow one another without brackets at 25"), error(chain)));
        assertTrue(error("i = j = 7").startsWith("comparisons cannot follow one another without brackets"));
        assertEquals(false, eval("(i = j) = true"));
        // bracketed nesting is compiled once per level, so it stays quick
        String nested = "b";
        for (int k = 0; k < 30; k++) {
            nested = "(" + nested + " between false and true)";
        }
        String deep = nested;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(true, eval(deep)));
        assertEquals(true, eval("7 between j and i"));
        assertNull(eval("ni between 1 and 2"));
        assertEquals(false, eval("9 between ni and 8"));
        assertNull(eval("5 not between ni and 8"));
    }

    @Test
    void nestedSimpleCaseWorksItsOperandOutOnce() {
        String e = "i";
        for (int k = 0; k < 24; k++) {
            e = "case " + e + " when 1 then 1 when 7 then 7 else 3 end";
        }
        String expr = e;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(7L, eval(expr)));
    }

    @Test
    void textResultsAreCapped() {
        ExprException regex = assertThrows(ExprException.class, () -> evalColumn(
            "regex_replace(x, '', '" + "y".repeat(2_000) + "')", "x", Type.UTF8, "a".repeat(1_000)));
        assertTrue(regex.getMessage().startsWith("a text value would be longer than 1,000,000 characters"));
        ExprException replace = assertThrows(ExprException.class, () -> evalColumn(
            "replace(x, 'a', '" + "y".repeat(2_000) + "')", "x", Type.UTF8, "a".repeat(1_000)));
        assertTrue(replace.getMessage().startsWith("a text value would be longer"));
        String big = "z".repeat(600_000);
        assertThrows(ExprException.class, () -> evalColumn("x || x", "x", Type.UTF8, big));
        assertThrows(ExprException.class, () -> evalColumn("concat(x, x)", "x", Type.UTF8, big));
        assertTrue(error("like_me").startsWith("unknown column"));
    }

    @Test
    void regularExpressionsOnLongTextAndCommonPatterns() throws ExprException {
        assertEquals(true, eval("regex_match('a.b-c.d', '^[\\w-]+(\\.[\\w-]+)+$')"));
        assertEquals(false, eval("regex_match('a..b', '^[\\w-]+(\\.[\\w-]+)+$')"));
        assertEquals(true, eval("regex_match('HELLO', '(?i)hello')"));
        assertEquals(true, eval("regex_match('abab', '^(?i:ab)+$')"));
        assertTrue(error("regex_match(s, '(?x) a # (b+)+')").contains("comment mode"));
        assertTrue(error("regex_match(s, '(\\.[\\w.]+)+')").contains("repeats a group"));
        assertTrue(error("regex_match(s, '(-[a-z]+|x)+')").contains("repeats a group"));
        // patterns that repeat a group are run on shorter values only, clear of stack overflows
        ExprException longValue = assertThrows(ExprException.class,
            () -> evalColumn("regex_match(x, '^(a|b)*$')", "x", Type.UTF8, "ab".repeat(5_000)));
        assertTrue(longValue.getMessage().startsWith("a value is longer than 2,000 characters"), longValue.getMessage());
        assertEquals(true, evalColumn("regex_match(x, '^[ab]*$')", "x", Type.UTF8, "ab".repeat(5_000)).getFirst());
        ExprException likeLong = assertThrows(ExprException.class,
            () -> evalColumn("x like '%a'", "x", Type.UTF8, "a".repeat(Like.MAX_INPUT + 1)));
        assertTrue(likeLong.getMessage().startsWith("a value is longer than 100,000 characters, the most 'like'"));
    }

    @Test
    void theSeparatorExceptionIsStrict() throws ExprException {
        String[] refused = {
            "^(_\\w+)+$",              // \w matches _
            "^(_\\w+){30}$",           // counted repeats are never excepted
            "^(_\\w+){1,40}$",
            "^(\\.[a-z]+){2,5}$",
            "^(:[0-z]+)+$",             // [0-z] holds : ; < = > ? @ [ \ ] ^ _ and the backtick
            "^(\\.[A-z]+)+$",
            "^(\\.[a-z\\x2e]+)+$",      // escapes that spell the separator
            "^(\\.[a-z\\x{2e}]+)+$",
            "^(\\.a\\056+)+$",
            "^(\\.a\\cN+)+$",
            "^(\\.a\\N{FULL STOP}+)+$",
            "^(\\.[a[^b]]+)+$",          // nested class unions
            "^(\\.[a-z&&[^q]]+)+$",
            "^(\\.[\\w.]+)+$",
        };
        for (String p : refused) {
            assertTrue(error("regex_match(s, '" + p + "')").contains("repeats a group"), p);
        }
        assertEquals(true, eval("regex_match('a.b-c.d', '^[\\w-]+(\\.[\\w-]+)+$')"));
        assertEquals(true, eval("regex_match('x-a1-b2', '^x(-[a-z0-9]+)*$')"));
        assertEquals(true, eval("regex_match('a:b', '^a(:[a-z]+)+$')"));
    }

    @Test
    void textResultsHaveABatchBudget() {
        Object[] values = new Object[65];
        java.util.Arrays.fill(values, "a".repeat(1_000));
        String literal = "y".repeat(999);
        ExprException e = assertThrows(ExprException.class,
            () -> evalColumn("replace(x, 'a', '" + literal + "')", "x", Type.UTF8, values));
        assertTrue(e.getMessage().startsWith("the text this produces for one batch of rows would be more than "
            + "64,000,000 characters"), e.getMessage());
        Object[] halves = new Object[65];
        java.util.Arrays.fill(halves, "h".repeat(500_000));
        assertThrows(ExprException.class, () -> evalColumn("x || x", "x", Type.UTF8, halves));
        assertThrows(ExprException.class, () -> evalColumn("concat(x, x)", "x", Type.UTF8, halves));
        assertThrows(ExprException.class, () -> evalColumn("regex_replace(x, 'a', '" + literal + "')", "x", Type.UTF8,
            values));
    }

    @Test
    void valuesAtTheEdges() throws ExprException {
        assertTrue(error("0." + "0".repeat(50) + "1").contains("has more than 38 digits"));
        assertTrue(error("date '+999999999-12-31'").contains("is not a date"));
        assertEquals(LocalDate.of(9999, 12, 31).toEpochDay(), eval("date '9999-12-31'"));
        assertTrue(error("date '9999-12-31' + 1").startsWith("a date is out of range"));
        assertTrue(error("date '9999-12-31' + 9223372036854775807").startsWith("a whole number is too large"));
        assertEquals("15 Sep 2024", eval("format_date(date '2024-09-15', 'dd MMM yyyy')"));
        assertEquals(LocalDate.of(2024, 9, 15).toEpochDay(), eval("parse_date('15 Sep 2024', 'dd MMM yyyy')"));
        assertTrue(error("cast('١٢' as int64)").startsWith("cannot cast a text value to int64"));
        assertTrue(error("cast('1e999' as float64)").startsWith("cannot cast a text value to float64: it is too large"));
        assertEquals(true, evalColumn("x in (0.0e0)", "x", Type.FLOAT64, -0.0).getFirst());
        assertEquals(true, eval("'�' < '😀'")); // code point order, not UTF-16
        assertEquals(true, eval("'a' < 'b'"));
        assertEquals(ExpressionTest.micros("9999-12-31T00:00:00Z"), eval("cast(date '9999-12-31' as timestamp)"));
    }
}
