package io.kilnmark.kiln.nodes.expr;

import java.util.Map;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * An expression of the pipeline expression language (docs/design/pipelines.md, section 3;
 * docs/guide/pipeline-nodes.md): parsed once with {@link #parse}, checked against the columns it
 * will see with {@link #compile}, then worked out a batch at a time. It never runs code: the
 * parser is hand-written, there is no reflection on what users write, and functions come from a
 * fixed list.
 *
 * <pre>{@code
 * Expression e = Expression.parse("trim(first_name) || ' ' || trim(last_name)");
 * Expression.Compiled c = e.compile(batch.getSchema());   // types checked here
 * Col names = c.eval(Batch.of(batch, nowMicros));
 * FieldVector v = ArrowIO.write(names, c.field("full_name"), allocator);
 * }</pre>
 */
public final class Expression {

    /** The longest expression, in characters. */
    public static final int MAX_LENGTH = 10_000;
    /** How deeply brackets, function calls, {@code case} and {@code not} may nest. */
    public static final int MAX_DEPTH = 64;

    private final String text;
    private final Node root;

    private Expression(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /**
     * Reads an expression, checking only its form; columns and types are checked by {@link #compile}.
     *
     * @throws ExprException naming the problem and where it is
     */
    public static Expression parse(String text) throws ExprException {
        return new Expression(text, Parser.parse(text));
    }

    public String text() {
        return text;
    }

    Node root() {
        return root;
    }

    /**
     * Checks the expression against these columns and readies it to be worked out. Aggregate
     * functions are not allowed; see {@link Aggregation}.
     *
     * @throws ExprException for an unknown column, a type mismatch or a misused function
     */
    public Compiled compile(Schema schema) throws ExprException {
        return new Compiled(new Compiler(Compiler.Scope.of(schema), null).compile(root));
    }

    /** As {@link #compile(Schema)}, for columns given by expression type. */
    public Compiled compile(Map<String, Type> columns) throws ExprException {
        return new Compiled(new Compiler(Compiler.Scope.of(columns), null).compile(root));
    }

    /** A read-only view of the parsed expression, for reasoning about it without running it. */
    public ExprView view() {
        return ExprView.of(root);
    }

    /** Every column the expression names, in the order first seen. */
    public java.util.Set<String> columns() {
        return ExprView.columns(view());
    }

    /** Whether the expression calls an aggregate function anywhere. */
    public boolean usesAggregates() {
        return uses(root);
    }

    private static boolean uses(Node n) {
        return switch (n) {
            case Node.Call c -> Compiler.AGGREGATES.contains(c.name()) || c.args().stream().anyMatch(Expression::uses);
            case Node.Unary u -> uses(u.operand());
            case Node.Binary b -> uses(b.left()) || uses(b.right());
            case Node.IsNull i -> uses(i.operand());
            case Node.In i -> uses(i.operand()) || i.items().stream().anyMatch(Expression::uses);
            case Node.Between b -> uses(b.operand()) || uses(b.low()) || uses(b.high());
            case Node.Like l -> uses(l.operand()) || uses(l.pattern());
            case Node.Case c -> (c.operand() != null && uses(c.operand())) || c.whens().stream().anyMatch(Expression::uses)
                || c.thens().stream().anyMatch(Expression::uses) || (c.otherwise() != null && uses(c.otherwise()));
            case Node.Cast c -> uses(c.operand());
            case Node.Literal _, Node.Column _ -> false;
        };
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * Works out a node, turning the arithmetic and calendar errors Java reports for values out of
     * range (a date in year 300,000, say) into a plain error rather than letting them escape.
     */
    static Col safely(Ev ev, Batch batch, boolean[] mask) throws ExprException {
        try {
            return ev.eval(batch, mask);
        } catch (ArithmeticException | java.time.DateTimeException e) {
            throw new ExprException("a value is out of range", ev.pos,
                "dates and timestamps must fall in years -9999 to 9999, and whole numbers within int64");
        }
    }

    /** An expression checked against a set of columns, ready to work out. Not safe for use by two threads at once. */
    public static final class Compiled {

        private final Ev ev;

        Compiled(Ev ev) {
            this.ev = ev;
        }

        /** The type of the result. */
        public Type type() {
            return ev.type;
        }

        /** A column to hold the result. */
        public Field field(String name) {
            return ev.type.field(name);
        }

        /** The value for every row. */
        public Col eval(Batch batch) throws ExprException {
            return eval(batch, null);
        }

        /** The value for the rows in {@code mask} (null for all); other rows are null and are not worked out. */
        public Col eval(Batch batch, boolean[] mask) throws ExprException {
            return safely(ev, batch, mask);
        }

        /**
         * For a condition: which rows of {@code mask} (null for all) it is true for. False and null
         * both count as not true, as in SQL's {@code where}.
         */
        public boolean[] test(Batch batch, boolean[] mask) throws ExprException {
            Col c = safely(ev, batch, mask);
            boolean[] out = new boolean[c.rows];
            if (c.type.kind() == Type.Kind.BOOLEAN) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (mask == null || mask[i]) && c.isTrue(i);
                }
            }
            return out;
        }
    }
}
