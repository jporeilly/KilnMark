package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A read-only view of a parsed expression, for code that reasons about what an expression does
 * without running it, such as predicting a pipeline's quality from the catalogue
 * (docs/design/data-quality.md section 8). It never evaluates anything.
 *
 * <ul>
 *   <li>{@link Ref}: a column.</li>
 *   <li>{@link Lit}: a literal: a {@code String}, {@code Long}, {@code Double}, {@code BigDecimal},
 *       {@code Boolean}, or null; dates are {@code "date:"} plus the ISO date, timestamps
 *       {@code "timestamp:"} plus microseconds since 1970.</li>
 *   <li>{@link Call}: a function call, its name in lower case ({@code count(*)} has no arguments).</li>
 *   <li>{@link Op}: everything else, by its operator: {@code and or not = <> != < <= > >= + - * / % ||
 *       neg is_null is_not_null in not_in between not_between like not_like case cast}. {@code in}
 *       has the operand first, then the items; {@code between} the operand, low and high;
 *       {@code case} its parts in order (operand if any, then each when and then, then else);
 *       {@code cast} the operand, with the target type in {@link Op#detail()}.</li>
 * </ul>
 */
public sealed interface ExprView {

    record Ref(String column) implements ExprView {
    }

    record Lit(Object value) implements ExprView {
    }

    record Call(String name, List<ExprView> args) implements ExprView {
        public Call {
            args = List.copyOf(args);
        }
    }

    record Op(String op, List<ExprView> args, String detail) implements ExprView {
        public Op {
            args = List.copyOf(args);
        }
    }

    /** The view of a parsed expression. */
    static ExprView of(Node n) {
        return switch (n) {
            case Node.Column c -> new Ref(c.name());
            case Node.Literal l -> new Lit(literal(l));
            case Node.Call c -> new Call(c.name(), views(c.args()));
            case Node.Unary u -> new Op(u.op().equals("-") ? "neg" : u.op(), List.of(of(u.operand())), null);
            case Node.Binary b -> new Op(b.op().equals("!=") ? "<>" : b.op(), List.of(of(b.left()), of(b.right())), null);
            case Node.IsNull i -> new Op(i.negated() ? "is_not_null" : "is_null", List.of(of(i.operand())), null);
            case Node.In i -> {
                List<ExprView> args = new ArrayList<>();
                args.add(of(i.operand()));
                args.addAll(views(i.items()));
                yield new Op(i.negated() ? "not_in" : "in", args, null);
            }
            case Node.Between b -> new Op(b.negated() ? "not_between" : "between",
                List.of(of(b.operand()), of(b.low()), of(b.high())), null);
            case Node.Like l -> new Op(l.negated() ? "not_like" : "like", List.of(of(l.operand()), of(l.pattern())), null);
            case Node.Case c -> {
                List<ExprView> args = new ArrayList<>();
                if (c.operand() != null) {
                    args.add(of(c.operand()));
                }
                for (int i = 0; i < c.whens().size(); i++) {
                    args.add(of(c.whens().get(i)));
                    args.add(of(c.thens().get(i)));
                }
                if (c.otherwise() != null) {
                    args.add(of(c.otherwise()));
                }
                yield new Op("case", args, null);
            }
            case Node.Cast c -> new Op("cast", List.of(of(c.operand())), c.target().kind().name().toLowerCase(
                java.util.Locale.ROOT));
        };
    }

    private static List<ExprView> views(List<Node> nodes) {
        List<ExprView> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            out.add(of(n));
        }
        return out;
    }

    private static Object literal(Node.Literal l) {
        Object v = l.value();
        return switch (l.type().kind()) {
            case DATE -> "date:" + java.time.LocalDate.ofEpochDay((Long) v);
            case TIMESTAMP -> "timestamp:" + v;
            case DECIMAL -> v instanceof BigDecimal d ? d : v;
            default -> v;
        };
    }

    /** Every column this view names, in the order first seen. */
    static Set<String> columns(ExprView v) {
        Set<String> out = new LinkedHashSet<>();
        collect(v, out);
        return out;
    }

    private static void collect(ExprView v, Set<String> out) {
        switch (v) {
            case Ref r -> out.add(r.column());
            case Lit _ -> { }
            case Call c -> c.args().forEach(a -> collect(a, out));
            case Op o -> o.args().forEach(a -> collect(a, out));
        }
    }
}
