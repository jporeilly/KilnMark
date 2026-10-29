package io.kilnmark.kiln.yaml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The condition on a flow's path (docs/design/flows.md, "Conditions"): a small language of
 * comparisons over what earlier steps reported and the flow's parameters, such as
 * {@code clean.quarantined_share < 0.02 and clean.score >= good}.
 *
 * <p>Parsed once and checked against the values that exist ({@link #parse}); it never runs code.
 * Comparisons with an unknown value (a score nothing measured) are unknown, not false, with SQL's
 * rules for {@code and}, {@code or} and {@code not}; a path is taken only when its condition is
 * true. Quality words stand for the lowest score of the band, as people read them everywhere:
 * {@code good} is 0.95, {@code fair} 0.8, {@code poor} 0.
 */
public final class FlowCondition {

    /** The longest condition read, in characters. */
    public static final int MAX_CHARS = 1_000;
    static final int MAX_DEPTH = 32;
    private static final java.util.Set<String> OPERATORS = java.util.Set.of("=", "!=", "<", "<=", ">", ">=");

    /** The kind of a value. */
    public enum Type { NUMBER, TEXT, BOOLEAN }

    /** Which values exist and what kind they are. */
    public interface Scope {
        /** The kind of the value called {@code name}, or null if there is none. */
        Type typeOf(String name);

        /** Every name, for suggesting the closest. */
        Collection<String> names();
    }

    /** A problem in a condition: the column it starts at (from 1) and what is wrong. */
    public record Issue(int column, String message) {
    }

    /** The result of {@link #parse}: the condition, or why there is none. */
    public record Parsed(FlowCondition condition, List<Issue> issues) {
        public Parsed {
            issues = List.copyOf(issues);
        }
    }

    private final Node root;
    private final List<String> references;

    private FlowCondition(Node root, List<String> references) {
        this.root = root;
        this.references = List.copyOf(references);
    }

    /** The values the condition reads, in the order written, each once. */
    public List<String> references() {
        return references;
    }

    /**
     * Whether the condition holds: TRUE, FALSE, or null when a value it needs is unknown.
     *
     * @param values the value called a name (a Number, String or Boolean), or null when unknown
     */
    public Boolean evaluate(Function<String, Object> values) {
        Object v = root.eval(Objects.requireNonNull(values, "values"));
        return v instanceof Boolean b ? b : null;
    }

    /** Reads {@code text}, checking every value it names against {@code scope}. */
    public static Parsed parse(String text, Scope scope) {
        List<Issue> issues = new ArrayList<>();
        if (text == null || text.isBlank()) {
            issues.add(new Issue(1, "A condition is empty: write one such as clean.score >= good"));
            return new Parsed(null, issues);
        }
        if (text.length() > MAX_CHARS) {
            issues.add(new Issue(1, "The condition is too long: " + text.length() + " characters, and at most "
                + MAX_CHARS + " are allowed"));
            return new Parsed(null, issues);
        }
        Reader r = new Reader(text, scope, issues);
        Node n = null;
        try {
            n = r.or(0);
            if (r.peek().kind != Kind.END) {
                r.fail(r.peek(), "Unexpected '" + r.peek().text + "' after the condition: join comparisons with "
                    + "and / or");
            }
            if (n.type() != Type.BOOLEAN) {
                r.fail(null, "A condition must be a comparison or true / false, not a " + n.type().name().toLowerCase()
                    + " on its own");
            }
        } catch (Stop stop) {
            // the issue is recorded
        }
        if (!issues.isEmpty() || n == null) {
            return new Parsed(null, issues);
        }
        return new Parsed(new FlowCondition(n, r.refs), issues);
    }

    // ---- the tree ----

    private sealed interface Node permits Literal, Ref, Compare, Logic, Not {
        Type type();

        Object eval(Function<String, Object> values);
    }

    private record Literal(Object value, Type type) implements Node {
        @Override
        public Object eval(Function<String, Object> values) {
            return value;
        }
    }

    private record Ref(String name, Type type) implements Node {
        @Override
        public Object eval(Function<String, Object> values) {
            Object v = values.apply(name);
            if (v == null) {
                return null;
            }
            return switch (type) {
                case NUMBER -> v instanceof Number n ? n.doubleValue() : null;
                case TEXT -> v instanceof String s ? s : null;
                case BOOLEAN -> v instanceof Boolean b ? b : null;
            };
        }
    }

    private record Compare(String op, Node left, Node right) implements Node {
        @Override
        public Type type() {
            return Type.BOOLEAN;
        }

        @Override
        public Object eval(Function<String, Object> values) {
            Object a = left.eval(values);
            Object b = right.eval(values);
            if (a == null || b == null) {
                return null;
            }
            int c;
            if (a instanceof Double x && b instanceof Double y) {
                c = Double.compare(x, y);
            } else if (a instanceof String x && b instanceof String y) {
                c = x.compareTo(y);
            } else {
                c = a.equals(b) ? 0 : 1; // booleans: only = and != are allowed
            }
            return switch (op) {
                case "=" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> throw new IllegalStateException("operator " + op);
            };
        }
    }

    private record Logic(boolean and, Node left, Node right) implements Node {
        @Override
        public Type type() {
            return Type.BOOLEAN;
        }

        @Override
        public Object eval(Function<String, Object> values) {
            Object a = left.eval(values);
            Object b = right.eval(values);
            if (and) {
                if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
                    return false;
                }
                return a == null || b == null ? null : Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
                return true;
            }
            return a == null || b == null ? null : Boolean.FALSE;
        }
    }

    private record Not(Node inner) implements Node {
        @Override
        public Type type() {
            return Type.BOOLEAN;
        }

        @Override
        public Object eval(Function<String, Object> values) {
            Object v = inner.eval(values);
            return v instanceof Boolean b ? (Object) !b : null;
        }
    }

    // ---- reading ----

    private enum Kind { NUMBER, STRING, WORD, OP, OPEN, CLOSE, END }

    private record Token(Kind kind, String text, int column, Object value) {
    }

    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Reader {
        private final String text;
        private final Scope scope;
        private final List<Issue> issues;
        private final List<Token> tokens = new ArrayList<>();
        private final List<String> refs = new ArrayList<>();
        private int at;

        Reader(String text, Scope scope, List<Issue> issues) {
            this.text = text;
            this.scope = scope;
            this.issues = issues;
            try {
                lex();
            } catch (Stop stop) {
                tokens.clear();
                tokens.add(new Token(Kind.END, "", text.length() + 1, null));
            }
        }

        void fail(Token t, String message) {
            issues.add(new Issue(t == null ? 1 : t.column, message));
            throw new Stop();
        }

        Token peek() {
            return tokens.get(Math.min(at, tokens.size() - 1));
        }

        Token next() {
            Token t = peek();
            at++;
            return t;
        }

        private void lex() {
            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(') {
                    tokens.add(new Token(Kind.OPEN, "(", i + 1, null));
                    i++;
                } else if (c == ')') {
                    tokens.add(new Token(Kind.CLOSE, ")", i + 1, null));
                    i++;
                } else if (c == '\'') {
                    int end = text.indexOf('\'', i + 1);
                    if (end < 0) {
                        issues.add(new Issue(i + 1, "This text is not closed: add a closing '"));
                        throw new Stop();
                    }
                    tokens.add(new Token(Kind.STRING, text.substring(i, end + 1), i + 1, text.substring(i + 1, end)));
                    i = end + 1;
                } else if (c == '=' || c == '!' || c == '<' || c == '>') {
                    int len = i + 1 < n && text.charAt(i + 1) == '=' ? 2 : 1;
                    String op = text.substring(i, i + len);
                    if (op.equals("!")) {
                        issues.add(new Issue(i + 1, "Write != for 'not equal', or not before a comparison"));
                        throw new Stop();
                    }
                    if (!OPERATORS.contains(op)) {
                        issues.add(new Issue(i + 1, "'" + op + "' is not a comparison: write = for 'equal'"));
                        throw new Stop();
                    }
                    tokens.add(new Token(Kind.OP, op, i + 1, null));
                    i += len;
                } else if (isDigit(c) || (c == '-' && i + 1 < n && isDigit(text.charAt(i + 1)))
                    || (c == '.' && i + 1 < n && isDigit(text.charAt(i + 1)))) {
                    int j = i + 1;
                    while (j < n && (isDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                        j++;
                    }
                    String num = text.substring(i, j);
                    try {
                        double d = Double.parseDouble(num);
                        if (Double.isNaN(d) || Double.isInfinite(d)) {
                            throw new NumberFormatException();
                        }
                        tokens.add(new Token(Kind.NUMBER, num, i + 1, d));
                    } catch (NumberFormatException e) {
                        issues.add(new Issue(i + 1, "'" + Suggest.clip(num) + "' is not a number"));
                        throw new Stop();
                    }
                    i = j;
                } else if (isStart(c)) {
                    int j = i + 1;
                    while (j < n && (isStart(text.charAt(j)) || isDigit(text.charAt(j)) || text.charAt(j) == '-'
                        || text.charAt(j) == '.')) {
                        j++;
                    }
                    tokens.add(new Token(Kind.WORD, text.substring(i, j), i + 1, null));
                    i = j;
                } else {
                    issues.add(new Issue(i + 1, "Unexpected character '" + c + "' in the condition"));
                    throw new Stop();
                }
            }
            tokens.add(new Token(Kind.END, "", n + 1, null));
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private boolean word(String w) {
            Token t = peek();
            return t.kind == Kind.WORD && t.text.equalsIgnoreCase(w);
        }

        Node or(int depth) {
            Node left = and(depth);
            while (word("or")) {
                next();
                left = logic(false, left, and(depth));
            }
            return left;
        }

        private Node and(int depth) {
            Node left = not(depth);
            while (word("and")) {
                next();
                left = logic(true, left, not(depth));
            }
            return left;
        }

        private Node logic(boolean and, Node l, Node r) {
            if (l.type() != Type.BOOLEAN || r.type() != Type.BOOLEAN) {
                fail(peek(), "'" + (and ? "and" : "or") + "' joins comparisons, such as a.score >= good "
                    + (and ? "and" : "or") + " a.rows_written > 0");
            }
            return new Logic(and, l, r);
        }

        private Node not(int depth) {
            if (depth > MAX_DEPTH) {
                fail(peek(), "The condition is nested too deeply: at most " + MAX_DEPTH + " levels");
            }
            if (word("not")) {
                Token t = next();
                Node inner = not(depth + 1);
                if (inner.type() != Type.BOOLEAN) {
                    fail(t, "'not' goes before a comparison, such as not a.status = 'failed'");
                }
                return new Not(inner);
            }
            return compare(depth);
        }

        private Node compare(int depth) {
            Node left = operand(depth);
            if (peek().kind == Kind.OP) {
                Token op = next();
                Node right = operand(depth);
                check(op, left, right);
                return new Compare(op.text, left, right);
            }
            return left;
        }

        private void check(Token op, Node l, Node r) {
            if (l.type() != r.type()) {
                fail(op, "Cannot compare a " + l.type().name().toLowerCase() + " with a "
                    + r.type().name().toLowerCase() + ": " + describe(l) + " " + op.text + " " + describe(r));
            }
            if (l.type() != Type.NUMBER && l.type() != Type.TEXT && !op.text.equals("=") && !op.text.equals("!=")) {
                fail(op, "True / false values compare only with = and !=");
            }
        }

        private static String describe(Node n) {
            return switch (n) {
                case Literal l -> String.valueOf(l.value);
                case Ref ref -> ref.name;
                default -> "(…)";
            };
        }

        private Node operand(int depth) {
            Token t = next();
            switch (t.kind) {
                case NUMBER:
                    return new Literal(t.value, Type.NUMBER);
                case STRING:
                    return new Literal(t.value, Type.TEXT);
                case OPEN: {
                    if (depth > MAX_DEPTH) {
                        fail(t, "The condition is nested too deeply: at most " + MAX_DEPTH + " levels");
                    }
                    Node inner = or(depth + 1);
                    if (peek().kind != Kind.CLOSE) {
                        fail(t, "This bracket is not closed: add a )");
                    }
                    next();
                    return inner;
                }
                case WORD:
                    return wordOperand(t);
                default:
                    fail(t, t.kind == Kind.END ? "The condition ends too soon: a value is missing"
                        : "Expected a value or a comparison, not '" + t.text + "'");
                    return null;
            }
        }

        private Node wordOperand(Token t) {
            switch (t.text.toLowerCase()) {
                case "true":
                    return new Literal(Boolean.TRUE, Type.BOOLEAN);
                case "false":
                    return new Literal(Boolean.FALSE, Type.BOOLEAN);
                case "good":
                    return new Literal(0.95, Type.NUMBER);
                case "fair":
                    return new Literal(0.8, Type.NUMBER);
                case "poor":
                    return new Literal(0.0, Type.NUMBER);
                case "and", "or", "not":
                    fail(t, "'" + t.text + "' is a keyword; a value goes here");
                    return null;
                default:
                    break;
            }
            Type type = scope.typeOf(t.text);
            if (type == null) {
                fail(t, "Unknown value '" + Suggest.clip(t.text) + "'" + Suggest.hint(t.text, scope.names())
                    + ": a condition reads what an earlier step reported, such as step.score, or a parameter");
            }
            if (!refs.contains(t.text)) {
                refs.add(t.text);
            }
            return new Ref(t.text, type);
        }
    }
}
