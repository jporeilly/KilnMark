package io.kilnmark.kiln.nodes.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpressionTest {

    /** Microseconds of 2026-10-25T12:00:00Z, what now() gives in these tests. */
    static final long NOW = Instant.parse("2026-10-25T12:00:00Z").getEpochSecond() * 1_000_000L;

    static final Map<String, Type> COLUMNS = new LinkedHashMap<>();
    static final Map<String, Object> VALUES = new LinkedHashMap<>();

    static {
        put("i", Type.INT64, 7L);
        put("j", Type.INT64, 2L);
        put("zero", Type.INT64, 0L);
        put("f", Type.FLOAT64, 2.5);
        put("d", Type.decimal(10, 2), new BigDecimal("12.34"));
        put("s", Type.UTF8, "  Hello World ");
        put("first_name", Type.UTF8, "Ada");
        put("b", Type.BOOLEAN, true);
        put("dt", Type.DATE, LocalDate.of(2024, 1, 31).toEpochDay());
        put("ts", Type.TIMESTAMP, micros("2024-01-31T09:30:15.25Z"));
        put("ni", Type.INT64, null);
        put("ns", Type.UTF8, null);
        put("nb", Type.BOOLEAN, null);
        put("order date", Type.DATE, LocalDate.of(2024, 3, 1).toEpochDay());
    }

    private static void put(String name, Type t, Object v) {
        COLUMNS.put(name, t);
        VALUES.put(name, v);
    }

    static long micros(String instant) {
        Instant t = Instant.parse(instant);
        return t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000;
    }

    static long days(String date) {
        return LocalDate.parse(date).toEpochDay();
    }

    /** One row of the test columns. */
    static Batch row() {
        Map<String, Col> cols = new LinkedHashMap<>();
        for (Map.Entry<String, Type> e : COLUMNS.entrySet()) {
            Col c = Col.nulls(e.getValue(), 1);
            c.set(0, VALUES.get(e.getKey()));
            cols.put(e.getKey(), c);
        }
        return Batch.of(1, cols, NOW);
    }

    static Object eval(String expr) {
        try {
            return Expression.parse(expr).compile(COLUMNS).eval(row()).get(0);
        } catch (ExprException e) {
            throw new AssertionError(expr + ": " + e.getMessage(), e);
        }
    }

    static Type type(String expr) throws ExprException {
        return Expression.parse(expr).compile(COLUMNS).type();
    }

    /** The error message, from parsing, checking or working out. */
    static String error(String expr) {
        ExprException e = assertThrows(ExprException.class,
            () -> Expression.parse(expr).compile(COLUMNS).eval(row()), expr);
        return e.getMessage();
    }

    /** Evaluates over several rows of one column. */
    static List<Object> evalColumn(String expr, String column, Type t, Object... values) throws ExprException {
        Col c = Col.nulls(t, values.length);
        for (int i = 0; i < values.length; i++) {
            c.set(i, values[i]);
        }
        Col out = Expression.parse(expr).compile(Map.of(column, t)).eval(Batch.of(values.length, Map.of(column, c), NOW));
        List<Object> r = new ArrayList<>();
        for (int i = 0; i < out.rows(); i++) {
            r.add(out.get(i));
        }
        return r;
    }

    // ---- literals and arithmetic ----

    @Test
    void literals() {
        assertEquals(1L, eval("1"));
        assertEquals(-5L, eval("-5"));
        assertEquals(Long.MIN_VALUE, eval("-9223372036854775808"));
        assertEquals(new BigDecimal("1.50"), eval("1.50"));
        assertEquals(1000.0, eval("1e3"));
        assertEquals("it's", eval("'it''s'"));
        assertEquals(true, eval("TRUE"));
        assertNull(eval("null"));
        assertEquals(days("2024-02-29"), eval("date '2024-02-29'"));
        assertEquals(micros("2024-01-31T09:30:00Z"), eval("timestamp '2024-01-31 09:30:00'"));
        assertEquals(new BigDecimal("12345678901234567890"), eval("12345678901234567890"));
    }

    @Test
    void arithmetic() throws ExprException {
        assertEquals(9L, eval("i + j"));
        assertEquals(3.5, eval("i / j"));
        assertEquals(1L, eval("i % j"));
        assertEquals(new BigDecimal("24.68"), eval("d * 2"));
        assertEquals(new BigDecimal("4.113333"), eval("d / 3"));
        assertEquals(9.5, eval("i + f"));
        assertEquals(new BigDecimal("0.3"), eval("0.1 + 0.2"));
        assertEquals(-14L, eval("-i * j"));
        assertEquals(7L, eval("1 + 2 * 3"));
        assertEquals(9L, eval("(1 + 2) * 3"));
        assertEquals(Type.decimal(2), type("d + 1"));
        assertEquals(Type.FLOAT64, type("d + f"));
        assertEquals(Type.INT64, type("i * j"));
        assertTrue(error("9223372036854775807 + 1").startsWith("a whole number is too large for int64 at 21"));
        assertEquals("division by zero at 3; guard it with nullif, like x / nullif(y, 0)", error("i / zero"));
        assertNull(eval("i / nullif(zero, 0)"));
        assertTrue(error("i % 0").startsWith("division by zero"));
        assertTrue(error("s + 1").contains("'+' needs numbers, but one side is utf8 (text)"));
        assertTrue(error("s + 1").contains("join text with || or concat"));
    }

    @Test
    void dateArithmetic() {
        assertEquals(days("2024-02-01"), eval("dt + 1"));
        assertEquals(days("2024-02-01"), eval("1 + dt"));
        assertEquals(days("2024-01-24"), eval("dt - 7"));
        assertEquals(30L, eval("dt - date '2024-01-01'"));
        assertEquals(days("2024-03-02"), eval("\"order date\" + 1"));
    }

    // ---- null logic ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "null and false | false", "null and true | NULL", "false and null | false", "true and null | NULL",
        "null or true | true", "null or false | NULL", "true or null | true", "not nb | NULL",
        "null = null | NULL", "ni = 1 | NULL", "ni <> 1 | NULL", "ni is null | true", "ni is not null | false",
        "ni in (1, 2) | NULL", "3 in (1, null) | NULL", "1 in (1, null) | true", "3 not in (1, 2) | true",
        "3 not in (1, null) | NULL", "ni between 1 and 3 | NULL", "2 between 1 and 3 | true",
        "5 not between 1 and 3 | true", "ns like 'a%' | NULL", "'abc' like ns | NULL",
        "not (1 = 1) or nb | NULL", "nb is null and true | true"})
    void threeValuedLogic(String expr, Boolean expected) {
        assertEquals(expected, eval(expr));
    }

    @Test
    void nullsPropagate() {
        assertNull(eval("ni + 1"));
        assertNull(eval("ns || 'x'"));
        assertNull(eval("-ni"));
        assertNull(eval("upper(ns)"));
        assertEquals(5L, eval("coalesce(ni, 5)"));
        assertEquals("x", eval("coalesce(ns, ns, 'x')"));
        assertNull(eval("coalesce(ni, null)"));
        assertEquals("a", eval("concat('a', ns)"));
    }

    @Test
    void shortCircuitAndMasksAvoidErrors() throws ExprException {
        assertEquals(false, eval("zero <> 0 and 10 / zero > 1"));
        assertEquals(true, eval("zero = 0 or 10 / zero > 1"));
        assertEquals(0.0, eval("case when zero = 0 then 0 else 10 / zero end"));
        assertEquals(List.of(5L, 0L), evalColumn("case when x <> 0 then cast(10 / x as int64) else 0 end", "x",
            Type.INT64, 2L, 0L));
        assertEquals(Arrays.asList(12L, null), evalColumn(
            "case when regex_match(x, '^[0-9]+$') then cast(x as int64) end", "x", Type.UTF8, "12", "n/a"));
        assertEquals(7.0, eval("coalesce(i, 10 / zero)"));
    }

    // ---- comparisons and conditions ----

    @Test
    void comparisons() {
        assertEquals(true, eval("i = 7.0"));
        assertEquals(true, eval("d > 12"));
        assertEquals(true, eval("dt < ts"));
        assertEquals(true, eval("'a' < 'b'"));
        assertEquals(true, eval("i != j"));
        assertEquals(true, eval("b = true"));
        assertEquals(true, eval("1 + 2 = 3 and 2 < 3"));
        assertEquals(true, eval("not false or false"));
        assertEquals(true, eval("i in (1, 7.0, 9)"));
        assertEquals(true, eval("d in (12.340)"));
        assertTrue(error("s = 1").startsWith("cannot compare utf8 (text) with an int64 at 3"));
    }

    @Test
    void caseExpressions() throws ExprException {
        assertEquals("seven", eval("case when i = 7 then 'seven' else 'other' end"));
        assertEquals("seven", eval("case i when 1 then 'one' when 7 then 'seven' end"));
        assertNull(eval("case when i = 1 then 'one' end"));
        assertEquals(1.0, eval("case when b then 1 else 2.5e0 end"));
        assertEquals(Type.FLOAT64, type("case when b then 1 else 2.5e0 end"));
        assertTrue(error("case when b then 1 else 'x' end").startsWith("the results of 'case' must have one type"));
        assertTrue(error("case when i then 1 end").startsWith("a 'when' condition must be true or false"));
    }

    @Test
    void like() throws ExprException {
        assertEquals(true, eval("'abc' like 'a%'"));
        assertEquals(true, eval("'abc' like 'a_c'"));
        assertEquals(false, eval("'abc' like 'A%'"));
        assertEquals(true, eval("'100%' like '100\\%'"));
        assertEquals(false, eval("'1000' like '100\\%'"));
        assertEquals(true, eval("'abc' not like '%z%'"));
        assertEquals(true, eval("'' like '%'"));
        assertEquals(List.of(true, false), evalColumn("x like 'a%' || ''", "x", Type.UTF8, "ab", "ba"));
        String longText = "a".repeat(50_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertEquals(false, evalColumn("x like '%a%a%a%a%b'", "x", Type.UTF8, longText).getFirst()));
    }

    // ---- functions ----

    @Test
    void textFunctions() {
        assertEquals("Hello World", eval("trim(s)"));
        assertEquals("  hello world ", eval("lower(s)"));
        assertEquals("ADA", eval("upper(first_name)"));
        assertEquals(14L, eval("length(s)"));
        assertEquals(2L, eval("length('😀a')"));
        assertEquals("ell", eval("substring('hello', 2, 3)"));
        assertEquals("he", eval("substring('hello', 0, 3)"));
        assertEquals("lo", eval("substring('hello', 4)"));
        assertEquals("", eval("substring('hello', 9)"));
        assertEquals("a", eval("substring('😀a', 2)"));
        assertTrue(error("substring('hello', 1, -1)").startsWith("substring's length cannot be negative"));
        assertEquals("heLLo", eval("replace('hello', 'l', 'L')"));
        assertEquals("hello", eval("replace('hello', '', 'L')"));
        assertEquals("a1true2024-01-312.5", eval("concat('a', 1, null, true, dt, f)"));
        assertEquals("Ada Lovelace", eval("trim(first_name) || ' ' || 'Lovelace'"));
    }

    @Test
    void numberFunctions() {
        assertNull(eval("nullif(i, 7)"));
        assertEquals(7L, eval("nullif(i, 8)"));
        assertEquals(3L, eval("abs(-3)"));
        assertEquals(2.5, eval("abs(-2.5e0)"));
        assertEquals(new BigDecimal("1.5"), eval("abs(-1.5)"));
        assertEquals(new BigDecimal("3"), eval("round(2.5)"));
        assertEquals(new BigDecimal("-3"), eval("round(-2.5)"));
        assertEquals(new BigDecimal("12.3"), eval("round(d, 1)"));
        assertEquals(1200L, eval("round(1234, -2)"));
        assertEquals(3.0, eval("round(f)"));
        assertEquals(-3.0, eval("floor(-2.5e0)"));
        assertEquals(new BigDecimal("13"), eval("ceil(d)"));
        assertEquals(7L, eval("floor(i)"));
        assertTrue(error("round(d, j)").startsWith("round's second argument must be a whole number"));
        assertTrue(error("abs(-9223372036854775808)").startsWith("a whole number is too large"));
        assertTrue(error("abs(s)").startsWith("abs needs a number, not utf8 (text)"));
    }

    @Test
    void dateFunctions() {
        assertEquals(NOW, eval("now()"));
        assertEquals(days("2026-10-25"), eval("today()"));
        assertEquals(days("2024-01-01"), eval("date_trunc('month', dt)"));
        assertEquals(days("2024-01-01"), eval("date_trunc('quarter', dt)"));
        assertEquals(days("2024-01-29"), eval("date_trunc('week', dt)"));
        assertEquals(micros("2024-01-31T09:00:00Z"), eval("date_trunc('hour', ts)"));
        assertEquals(micros("2024-01-01T00:00:00Z"), eval("date_trunc('YEAR', ts)"));
        assertEquals(2024L, eval("date_part('year', dt)"));
        assertEquals(3L, eval("date_part('dow', dt)"));
        assertEquals(31L, eval("date_part('day_of_year', dt)"));
        assertEquals(5L, eval("date_part('week', dt)"));
        assertEquals(9L, eval("date_part('hour', ts)"));
        assertEquals(15L, eval("date_part('second', ts)"));
        assertTrue(error("date_part('hour', dt)").contains("a date has no hours"));
        assertTrue(error("date_part('fortnight', dt)").startsWith("date_part needs a unit in quotes"));
        assertTrue(error("date_part(s, dt)").startsWith("date_part needs a unit in quotes"));
        assertEquals(days("2024-01-31"), eval("parse_date('31/01/2024', 'dd/MM/yyyy')"));
        assertNull(eval("parse_date('31/02/2024', 'dd/MM/yyyy')"));
        assertNull(eval("parse_date('nope')"));
        assertEquals(days("2024-01-31"), eval("parse_date(' 2024-01-31 ')"));
        assertEquals(micros("2024-01-31T09:00:00Z"), eval("parse_timestamp('2024-01-31 10:00:00+01:00')"));
        assertEquals(micros("2024-01-31T09:30:00Z"), eval("parse_timestamp('31/01/2024 09:30', 'dd/MM/yyyy HH:mm')"));
        assertEquals(micros("2024-01-31T00:00:00Z"), eval("parse_timestamp('31/01/2024', 'dd/MM/yyyy')"));
        assertEquals("31 Jan 2024", eval("format_date(dt, 'dd MMM yyyy')"));
        assertEquals("2024-01-31T09:30:15Z", eval("format_date(ts, 'yyyy-MM-dd''T''HH:mm:ssXXX')"));
        assertTrue(error("format_date(dt, 'HH:mm')").startsWith("format_date: the pattern asks for something a date"));
        assertTrue(error("format_date(dt, 'qqqqqqqq')").startsWith("format_date: the pattern 'qqqqqqqq' is not valid"));
        assertTrue(error("format_date(dt, s)").startsWith("format_date needs its pattern written out in quotes"));
        assertTrue(error("date_trunc('day', s)").contains("read text with parse_date or parse_timestamp first"));
    }

    @Test
    void timestampsAreUtc() {
        assertEquals(eval("timestamp '2024-01-31 09:30:00'"), eval("timestamp '2024-01-31T09:30:00Z'"));
        assertEquals(eval("timestamp '2024-01-31 09:30:00'"), eval("timestamp '2024-01-31 10:30:00+01:00'"));
        assertEquals(micros("2024-01-31T00:00:00Z"), eval("timestamp '2024-01-31'"));
        assertEquals("2024-01-31 09:30:15.25", eval("cast(ts as utf8)"));
        assertTrue(error("timestamp '2024-13-01 00:00:00'").startsWith("'2024-13-01 00:00:00' is not a timestamp"));
        assertTrue(error("date '2024-02-30'").startsWith("'2024-02-30' is not a date; write it as yyyy-mm-dd at 6"));
    }

    // ---- casts ----

    @Test
    void casts() throws ExprException {
        assertEquals(42L, eval("cast(' 42 ' as int64)"));
        assertEquals(42L, eval("cast('42' as bigint)"));
        assertEquals(new BigDecimal("4.50"), eval("cast(' 4.5 ' as decimal(5,2))"));
        assertEquals(Type.decimal(5, 2), type("cast(s as decimal(5, 2))"));
        assertEquals(3L, eval("cast(2.5 as int64)"));
        assertEquals(-3L, eval("cast(-2.5e0 as int64)"));
        assertEquals("7", eval("cast(i as utf8)"));
        assertEquals("2.5", eval("cast(f as text)"));
        assertEquals("12.34", eval("cast(d as string)"));
        assertEquals("true", eval("cast(b as varchar)"));
        assertEquals(1L, eval("cast(true as int64)"));
        assertEquals(true, eval("cast('yes' as boolean)"));
        assertEquals(false, eval("cast(0 as boolean)"));
        assertEquals(days("2024-01-31"), eval("cast('2024-01-31' as date)"));
        assertEquals(days("2024-01-31"), eval("cast(ts as date)"));
        assertEquals(micros("2024-01-31T00:00:00Z"), eval("cast(dt as timestamp)"));
        assertEquals(2.5, eval("cast('2.5' as double)"));
        assertEquals(7.0, eval("cast(i as float64)"));
        assertNull(eval("cast(null as int64)"));
        assertTrue(error("cast('abc' as int64)").startsWith("cannot cast a text value to int64: it is not a whole number at 1"));
        assertTrue(error("cast('0x10' as float64)").startsWith("cannot cast a text value to float64"));
        assertTrue(error("cast('NaN' as float64)").startsWith("cannot cast a text value to float64"));
        assertTrue(error("cast('1.5d' as float64)").startsWith("cannot cast a text value to float64"));
        assertTrue(error("cast('1e999999' as decimal(10,2))").startsWith("cannot cast a text value to decimal"));
        assertTrue(error("cast(12345.6 as decimal(4,1))").startsWith("a value is too large for decimal(4,1)"));
        assertTrue(error("cast(dt as int64)").startsWith("cannot cast a date to int64"));
        assertTrue(error("cast(i as money)").startsWith("unknown type 'money'"));
        assertTrue(error("cast('31/01/2024' as date)").contains("use parse_date"));
        assertTrue(error("cast(1e300 as int64)").startsWith("cannot turn a floating-point value into int64"));
    }

    // ---- errors and limits ----

    @Test
    void errorsSayWhereAndSuggest() {
        assertEquals("unknown column 'frist_name' at 7; did you mean first_name?", error("upper(frist_name)"));
        assertEquals("unknown function 'lenght' at 1; did you mean length?", error("lenght(s)"));
        assertEquals("the expression ends too early at 4", error("1 +"));
        assertEquals("expected ')' to close the '(' at 1 but found the end of the expression at 7", error("(1 + 2"));
        assertEquals("the text starting here has no closing ' at 1", error("'abc"));
        assertEquals("unexpected character '#' at 3", error("i # j"));
        assertTrue(error("i j").startsWith("unexpected 'j' at 3"));
        assertTrue(error("sum(i)").startsWith("sum(...) summarises many rows, so it can only be used in an aggregate"));
        assertTrue(error("date + 1").contains("to use a column called date, quote it"));
        assertTrue(error("trim(s, s)").startsWith("trim takes 1 argument, not 2"));
        assertTrue(error("count(*) + 1").startsWith("count(...) summarises many rows"));
        assertTrue(error("upper(*)").startsWith("only count(*) takes a star"));
        assertEquals("the expression is empty", error("   "));
        assertTrue(error("i = 'x").contains("no closing '"));
        assertTrue(error("b and 1").startsWith("'and' needs true or false values, not an int64"));
        assertTrue(error("s || 1").contains("use concat(...)"));
        assertTrue(error("`i`").contains("quote column names with double quotes"));
        assertTrue(error("1abc").startsWith("a number cannot run into a name"));
    }

    @Test
    void injectionIsJustText() {
        assertEquals("unexpected character ';' at 2", error("1; drop table x"));
        assertTrue(error("java.lang.Runtime.getRuntime()").startsWith("unexpected character '.'"));
        assertTrue(error("${jndi:ldap://x}").startsWith("unexpected character '$'"));
        assertEquals("'; drop table x --", eval("'''; drop table x --'"));
        assertTrue(error("exec('rm -rf /')").startsWith("unknown function 'exec'"));
        assertTrue(error("\"i\"\"x\"").startsWith("unknown column 'i\"x'"));
        assertEquals(7L, eval("\"i\""));
        assertTrue(error("i\u0000").startsWith("unexpected character U+0000"));
        assertTrue(error("getClass()").startsWith("unknown function 'getclass'"));
    }

    @Test
    void limits() throws ExprException {
        String tooLong = "1" + " ".repeat(Expression.MAX_LENGTH);
        assertEquals("the expression is longer than 10,000 characters", error(tooLong));
        String deep = "(".repeat(64) + "1" + ")".repeat(64);
        assertEquals(1L, eval(deep));
        assertTrue(error("(".repeat(65) + "1" + ")".repeat(65)).startsWith("the expression is nested more than 64 levels"));
        assertTrue(error("abs(".repeat(65) + "1" + ")".repeat(65)).startsWith("the expression is nested more than 64"));
        assertTrue(error("not ".repeat(65) + "true").startsWith("the expression is nested more than 64"));
        assertEquals(900L, eval("1" + " + 1".repeat(899)));
        assertTrue(error("1" + "+1".repeat(1500)).startsWith("the expression has more than 1,000 operations"));
        assertFalse(Expression.parse("i + 1").usesAggregates());
        assertTrue(Expression.parse("1 + sum(i)").usesAggregates());
    }

    // ---- regular expressions ----

    @Test
    void regularExpressions() throws ExprException {
        assertEquals(true, eval("regex_match(s, '^\\s*Hello')"));
        assertEquals(false, eval("regex_match(s, '^Hello')"));
        assertEquals("31/01/2024", eval("regex_replace('2024-01-31', '(\\d+)-(\\d+)-(\\d+)', '$3/$2/$1')"));
        assertEquals("a-b-c", eval("regex_replace('a b  c', '\\s+', '-')"));
        assertEquals(List.of(true, false, true), evalColumn("regex_match('abc', x)", "x", Type.UTF8, "b", "z", "^a"));
        assertTrue(error("regex_match(s, '(a+)+$')").contains("repeats a group that itself repeats"));
        assertTrue(error("regex_match(s, '(\\w+\\s?)*$')").contains("repeats a group that itself repeats"));
        assertTrue(error("regex_match(s, '((ab)*c)+')").contains("repeats a group that itself repeats"));
        assertTrue(error("regex_match(s, '(a)\\1')").contains("back-reference"));
        assertTrue(error("regex_match(s, '(')").startsWith("the regular expression is not valid"));
        assertTrue(error("regex_replace(s, '(a)', '$2')").contains("refers to group 2 but the pattern has 1"));
        assertTrue(error("regex_replace(s, 'a', 'cost $')").contains("ends with a lone $"));
        // safe patterns pass the check
        assertEquals(true, eval("regex_match('aaa', '(?:a|b)+')"));
        assertEquals(true, eval("regex_match('a+', '[+*]')"));
        assertEquals(true, eval("regex_match('x{2}', 'x\\{2\\}')"));
        assertEquals(true, eval("regex_match('ab', '(ab)?')"));
    }

    @Test
    void regexBudgetStopsSlowMatches() {
        // no nested quantifier for the check to catch, but polynomial backtracking: about 30^8 nodes
        String text = "1,".repeat(30) + "x";
        String slow = "^" + ".*,".repeat(8) + "P";
        ExprException e = assertThrows(ExprException.class, () -> assertTimeoutPreemptively(Duration.ofSeconds(20),
            () -> evalColumn("regex_match(x, '" + slow + "')", "x", Type.UTF8, text)));
        assertTrue(e.getMessage().startsWith("the regular expression took too long on a value"), e.getMessage());
        ExprException big = assertThrows(ExprException.class,
            () -> evalColumn("regex_match(x, 'a')", "x", Type.UTF8, "a".repeat(Regexes.MAX_INPUT + 1)));
        assertTrue(big.getMessage().startsWith("a value is longer than 100,000 characters"));
        // a pattern taken from data is checked too
        ExprException fromData = assertThrows(ExprException.class,
            () -> evalColumn("regex_match('aaa', x)", "x", Type.UTF8, "(a*)*"));
        assertTrue(fromData.getMessage().contains("repeats a group"));
    }

    @Test
    void unsafePatternScan() {
        assertNull(Regexes.unsafe("^[a-z]+@[a-z]+\\.[a-z]{2,}$"));
        assertNull(Regexes.unsafe("(?i)hello"));
        assertNull(Regexes.unsafe("(?<year>\\d{4})-(\\d{2})"));
        assertNull(Regexes.unsafe("[(a+)+]"));
        assertNull(Regexes.unsafe("\\Q(a+)+\\E"));
        assertNull(Regexes.unsafe("(a+)?"));
        assertTrue(Regexes.unsafe("(a+){2,}") != null);
        assertTrue(Regexes.unsafe("(?:a*)+") != null);
        assertTrue(Regexes.unsafe("(a+)+?") != null);
        assertTrue(Regexes.unsafe("\\k<x>") != null);
    }

    // ---- types ----

    @Test
    void typesAndNames() {
        assertEquals(Type.decimal(10, 2), Type.named("numeric(10, 2)"));
        assertEquals(Type.INT64, Type.named("Integer"));
        assertNull(Type.named("decimal(40,2)"));
        assertNull(Type.named("decimal(5,6)"));
        assertNull(Type.named("blob"));
        assertEquals(Type.TIMESTAMP, Type.common(Type.DATE, Type.TIMESTAMP));
        assertEquals(Type.FLOAT64, Type.common(Type.INT64, Type.FLOAT64));
        assertNull(Type.common(Type.UTF8, Type.INT64));
        assertEquals("decimal(38,2)", Type.decimal(2).toString());
    }
}
