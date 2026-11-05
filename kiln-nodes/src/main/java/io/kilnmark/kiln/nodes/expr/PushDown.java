package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.connect.ReadFilter;

/**
 * The part of a read node's {@code where} its source can apply while reading
 * (docs/design/pipelines.md, "The read node's columns and where"), as a {@link ReadFilter}.
 *
 * <p>What it gives a source only ever lets through at least the rows the whole condition keeps: the
 * read node applies the whole condition again to what arrives, so the rows a pipeline gets are the
 * same whether a source helps or not. So a part is left out when a source could keep fewer rows than
 * Kilnmark: text is compared only for equality, {@code in} and {@code like} (a database that ignores
 * case or trailing spaces then lets more through, never fewer), and never under {@code not}; a part
 * of an {@code and} can be left to Kilnmark, a part of an {@code or} or a {@code not} cannot. Columns
 * are compared with values only: numbers, dates, text and true/false. Functions, arithmetic,
 * timestamps (time zones differ between databases) and comparisons between columns stay in Kilnmark,
 * as does every comparison on a column the source does not compare as Kilnmark reads it (a JSON,
 * spatial or uuid column read as text, a small number read as true/false): the source says which.
 */
public final class PushDown {

    /**
     * What a source can do of a condition: the filter (null when nothing), and why the rest stays in
     * Kilnmark, in words for the pipeline's notes (empty when the whole condition is sent).
     */
    public record Split(ReadFilter filter, List<String> kept, boolean exact) {
        public Split {
            kept = List.copyOf(kept);
        }

        /** Whether the source applies the whole condition (Kilnmark still checks it). */
        public boolean whole() {
            return filter != null && kept.isEmpty();
        }

        /**
         * Whether the source keeps exactly the rows Kilnmark keeps (the whole condition, no text
         * compared), so its own row limit counts the same rows.
         */
        public boolean keepsExactly() {
            return whole() && exact;
        }
    }

    private final Schema schema;
    private final Predicate<String> compared;
    private final Set<String> kept = new LinkedHashSet<>();
    /** A column just looked up that the source does not compare as Kilnmark reads it. */
    private String foreign;

    private PushDown(Schema schema, Predicate<String> compared) {
        this.schema = schema;
        this.compared = compared;
    }

    /**
     * Splits {@code where} as read against the asset's columns.
     *
     * @throws ExprException when the condition does not read or check against the columns
     */
    public static Split split(String where, Schema schema) throws ExprException {
        return split(where, schema, column -> true);
    }

    /**
     * Splits {@code where}, comparing at the source only the columns {@code compared} accepts (those
     * the source compares as Kilnmark reads them).
     *
     * @throws ExprException when the condition does not read or check against the columns
     */
    public static Split split(String where, Schema schema, Predicate<String> compared) throws ExprException {
        Expression e = Expression.parse(where);
        e.compile(schema); // the same checks as when the node runs
        PushDown p = new PushDown(schema, compared);
        Part part = p.part(e.root(), false);
        if (part == null) {
            return new Split(null, p.kept.isEmpty() ? List.of("the condition") : List.copyOf(p.kept), false);
        }
        return new Split(part.filter, part.complete ? List.of() : List.copyOf(p.kept), part.exact);
    }

    /**
     * A translated part: {@code complete} when it is the whole of what it came from; {@code exact}
     * when the source keeps exactly what Kilnmark keeps (no text compared), so it may go under not.
     */
    private record Part(ReadFilter filter, boolean complete, boolean exact) {
    }

    private Part part(Node n, boolean negated) {
        return switch (n) {
            case Node.Binary b when b.op().equals("and") -> and(b, negated);
            case Node.Binary b when b.op().equals("or") -> or(b, negated);
            case Node.Unary u when u.op().equals("not") -> not(u);
            case Node.Binary b -> compare(b);
            case Node.In in -> in(in);
            case Node.Between bt -> between(bt);
            case Node.IsNull isNull -> isNull(isNull);
            case Node.Like like -> like(like);
            case Node.Column c -> booleanColumn(c);
            default -> keep("a value worked out in Kilnmark");
        };
    }

    private Part and(Node.Binary b, boolean negated) {
        Part l = part(b.left(), negated);
        Part r = part(b.right(), negated);
        if (l == null && r == null) {
            return null;
        }
        if (l == null || r == null) {
            Part one = l == null ? r : l;
            return new Part(one.filter, false, one.exact); // the other half stays in Kilnmark: still a superset
        }
        List<ReadFilter> all = new ArrayList<>();
        for (Part p : List.of(l, r)) {
            if (p.filter instanceof ReadFilter.And a) {
                all.addAll(a.all());
            } else {
                all.add(p.filter);
            }
        }
        return new Part(new ReadFilter.And(all), l.complete && r.complete, l.exact && r.exact);
    }

    private Part or(Node.Binary b, boolean negated) {
        Part l = part(b.left(), negated);
        Part r = part(b.right(), negated);
        if (l == null || r == null || !l.complete || !r.complete) {
            return null; // leaving out half of an or would let fewer rows through
        }
        List<ReadFilter> any = new ArrayList<>();
        for (Part p : List.of(l, r)) {
            if (p.filter instanceof ReadFilter.Or o) {
                any.addAll(o.any());
            } else {
                any.add(p.filter);
            }
        }
        return new Part(new ReadFilter.Or(any), true, l.exact && r.exact);
    }

    private Part not(Node.Unary u) {
        Part inner = part(u.operand(), true);
        if (inner == null || !inner.complete || !inner.exact) {
            kept.add("not (...) over text or what Kilnmark works out");
            return null;
        }
        return new Part(new ReadFilter.Not(inner.filter), true, true);
    }

    private Part compare(Node.Binary b) {
        String op = switch (b.op()) {
            case "=", "==" -> "=";
            case "!=", "<>" -> "<>";
            case "<", "<=", ">", ">=" -> b.op();
            default -> null;
        };
        if (op == null) {
            return keep("'" + b.op() + "'");
        }
        Node colSide = b.left();
        Node valueSide = b.right();
        if (!(colSide instanceof Node.Column) && b.right() instanceof Node.Column) {
            colSide = b.right();
            valueSide = b.left();
            op = flip(op);
        }
        Field f = colSide instanceof Node.Column c ? field(c.name()) : null;
        Object value = value(valueSide, f);
        if (f == null || value == null) {
            return keep("a comparison that is not a column with a value");
        }
        if (kind(f) == Type.Kind.UTF8) {
            if (!op.equals("=")) {
                return keep("comparing text other than for equality (databases order and match text in "
                    + "their own way)");
            }
            return new Part(new ReadFilter.Compare(f.getName(), op, value), true, false);
        }
        return new Part(new ReadFilter.Compare(f.getName(), op, value), true, true);
    }

    private Part in(Node.In in) {
        Field f = in.operand() instanceof Node.Column c ? field(c.name()) : null;
        if (f == null) {
            return keep("in (...) on something other than a column");
        }
        List<Object> values = new ArrayList<>();
        for (Node item : in.items()) {
            Object v = value(item, f);
            if (v == null) {
                return keep("in (...) with something other than values");
            }
            values.add(v);
        }
        boolean text = kind(f) == Type.Kind.UTF8;
        if (text && in.negated()) {
            return keep("not in (...) on text");
        }
        return new Part(new ReadFilter.In(f.getName(), values, in.negated()), true, !text);
    }

    private Part between(Node.Between bt) {
        Field f = bt.operand() instanceof Node.Column c ? field(c.name()) : null;
        Object low = value(bt.low(), f);
        Object high = value(bt.high(), f);
        if (f != null && kind(f) == Type.Kind.UTF8) {
            return keep("between on text (databases order text in their own way)");
        }
        if (f == null || low == null || high == null) {
            return keep("between on a function or calculation, or with bounds that are not values");
        }
        return new Part(new ReadFilter.Between(f.getName(), low, high, bt.negated()), true, true);
    }

    private Part isNull(Node.IsNull n) {
        Field f = n.operand() instanceof Node.Column c ? field(c.name()) : null;
        if (f == null) {
            return keep("is null on a function or calculation");
        }
        return new Part(new ReadFilter.IsNull(f.getName(), n.negated()), true, true);
    }

    private Part like(Node.Like like) {
        Field f = like.operand() instanceof Node.Column c ? field(c.name()) : null;
        if (f == null || kind(f) != Type.Kind.UTF8) {
            return keep("like on a function or calculation rather than a text column");
        }
        if (like.negated()) {
            return keep("not like");
        }
        if (!(like.pattern() instanceof Node.Literal l) || !(l.value() instanceof String pattern)) {
            return keep("like with a pattern that is not a value");
        }
        if (pattern.indexOf('\\') >= 0 || pattern.indexOf('[') >= 0) {
            return keep("like with \\ or [ in the pattern (some databases read them as more than a character)");
        }
        return new Part(new ReadFilter.Like(f.getName(), pattern), true, false);
    }

    /** A true/false column on its own: the rows where it is true. */
    private Part booleanColumn(Node.Column c) {
        Field f = field(c.name());
        if (f == null || kind(f) != Type.Kind.BOOLEAN) {
            return keep("a column used as a condition");
        }
        return new Part(new ReadFilter.Compare(f.getName(), "=", true), true, true);
    }

    private Part keep(String why) {
        if (foreign != null) {
            kept.add(foreign + ": the source stores it differently from how Kilnmark reads it");
            foreign = null;
        } else {
            kept.add(why);
        }
        return null;
    }

    private static String flip(String op) {
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }

    /**
     * A literal as the source compares it with the column {@code f}, or null when it cannot be: the
     * literal must be of the column's family (a number for a number, a date for a date, ...).
     */
    private static Object value(Node n, Field f) {
        if (f == null) {
            return null;
        }
        boolean negative = false;
        if (n instanceof Node.Unary u && u.op().equals("-") && u.operand() instanceof Node.Literal) {
            negative = true;
            n = u.operand();
        }
        if (!(n instanceof Node.Literal l) || l.value() == null) {
            return null;
        }
        Type.Kind column = kind(f);
        Type.Kind literal = l.type().kind();
        Object v = l.value();
        boolean number = column == Type.Kind.INT64 || column == Type.Kind.DECIMAL || column == Type.Kind.FLOAT64;
        if (number && (literal == Type.Kind.INT64 || literal == Type.Kind.DECIMAL || literal == Type.Kind.FLOAT64)) {
            if (v instanceof Long x) {
                return negative ? -x : x;
            }
            if (v instanceof BigDecimal x) {
                return negative ? x.negate() : x;
            }
            if (v instanceof Double x) {
                return negative ? -x : x;
            }
            return null;
        }
        if (negative) {
            return null;
        }
        if (column == Type.Kind.UTF8 && literal == Type.Kind.UTF8 && v instanceof String s) {
            return s;
        }
        if (column == Type.Kind.BOOLEAN && literal == Type.Kind.BOOLEAN && v instanceof Boolean b) {
            return b;
        }
        if (column == Type.Kind.DATE && literal == Type.Kind.DATE && v instanceof Long days) {
            return LocalDate.ofEpochDay(days);
        }
        return null;
    }

    private Field field(String name) {
        Field match = null;
        for (Field f : schema.getFields()) {
            if (f.getName().equals(name)) {
                match = f;
                break;
            }
            if (f.getName().equalsIgnoreCase(name)) {
                match = match == null ? f : match;
            }
        }
        foreign = null;
        if (match != null && !compared.test(match.getName())) {
            foreign = match.getName();
            return null;
        }
        return match;
    }

    private static Type.Kind kind(Field f) {
        Type t = Type.of(f.getType());
        return t == null ? Type.Kind.NULL : t.kind();
    }
}
