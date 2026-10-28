package io.kilnmark.kiln.steps.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The read-only view that quality prediction reasons about: shapes of conditions and calls, nothing run. */
class ExprViewTest {

    private static ExprView view(String text) throws ExprException {
        return Expression.parse(text).view();
    }

    @Test
    void conditionsKeepTheirShape() throws ExprException {
        assertEquals(new ExprView.Op("is_not_null", List.of(new ExprView.Ref("email")), null), view("email is not null"));
        assertEquals(new ExprView.Op("<>", List.of(new ExprView.Ref("a"), new ExprView.Lit("x")), null), view("a != 'x'"));
        ExprView in = view("country not in ('GB', 'IE')");
        assertEquals(new ExprView.Op("not_in", List.of(new ExprView.Ref("country"), new ExprView.Lit("GB"),
            new ExprView.Lit("IE")), null), in);
        ExprView between = view("amount between 0 and 10");
        assertEquals("between", ((ExprView.Op) between).op());
        assertEquals(new ExprView.Lit(0L), ((ExprView.Op) between).args().get(1));
        ExprView and = view("a > 1 and not b is null");
        assertEquals("and", ((ExprView.Op) and).op());
        assertEquals("not", ((ExprView.Op) ((ExprView.Op) and).args().get(1)).op());
    }

    @Test
    void callsLiteralsAndCasts() throws ExprException {
        assertEquals(new ExprView.Call("trim", List.of(new ExprView.Ref("name"))), view("TRIM(name)"));
        assertEquals(new ExprView.Call("parse_date", List.of(new ExprView.Ref("d"), new ExprView.Lit("dd/MM/yyyy"))),
            view("parse_date(d, 'dd/MM/yyyy')"));
        assertEquals(new ExprView.Lit("date:2024-01-31"), view("date '2024-01-31'"));
        assertEquals(new ExprView.Lit(null), view("null"));
        ExprView cast = view("cast(x as int64)");
        assertEquals("cast", ((ExprView.Op) cast).op());
        assertEquals("int64", ((ExprView.Op) cast).detail());
        assertEquals(new ExprView.Call("count", List.of()), view("count(*)"));
        assertEquals("case", ((ExprView.Op) view("case when a then 1 else 2 end")).op());
    }

    @Test
    void columnsAreListedOnceInOrder() throws ExprException {
        assertEquals(List.of("b", "a", "c"), List.copyOf(Expression.parse("b + a * coalesce(a, c)").columns()));
    }
}
