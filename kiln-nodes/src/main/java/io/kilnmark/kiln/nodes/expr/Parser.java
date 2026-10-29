package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.kilnmark.kiln.nodes.expr.Lexer.Kind;
import io.kilnmark.kiln.nodes.expr.Lexer.Token;

/**
 * Recursive-descent parser for the expression language (docs/design/pipelines.md, section 3).
 * Precedence, loosest first: {@code or}; {@code and}; {@code not}; comparisons, {@code is null},
 * {@code in}, {@code between}, {@code like}; {@code ||}; {@code + -}; {@code * / %}; unary minus.
 */
final class Parser {

    private final List<Token> tokens;
    private int at;
    private int depth;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Node parse(String text) throws ExprException {
        if (text == null || text.isBlank()) {
            throw new ExprException("the expression is empty", 0);
        }
        if (text.length() > Expression.MAX_LENGTH) {
            throw new ExprException("the expression is longer than " + String.format(Locale.UK, "%,d", Expression.MAX_LENGTH)
                + " characters", 0);
        }
        Parser p = new Parser(Lexer.tokens(text));
        Node n = p.expression();
        Token t = p.peek();
        if (t.kind() != Kind.END) {
            String hint = t.is(")") ? "there is a ')' without a matching '('" : null;
            throw new ExprException("unexpected " + t.describe(), t.pos(), hint);
        }
        return n;
    }

    private Token peek() {
        return tokens.get(at);
    }

    private Token next() {
        Token t = tokens.get(at);
        if (t.kind() != Kind.END) {
            at++;
        }
        return t;
    }

    private boolean accept(String keywordOrOp) {
        if (peek().is(keywordOrOp)) {
            at++;
            return true;
        }
        return false;
    }

    private Token expect(String keywordOrOp, String context) throws ExprException {
        Token t = peek();
        if (!t.is(keywordOrOp)) {
            throw new ExprException("expected '" + keywordOrOp + "'" + context + " but found " + t.describe(), t.pos());
        }
        return next();
    }

    private void enter(int pos) throws ExprException {
        if (++depth > Expression.MAX_DEPTH) {
            throw new ExprException("the expression is nested more than " + Expression.MAX_DEPTH + " levels deep", pos);
        }
    }

    /** A whole expression; nesting is counted where brackets, calls, case, cast and in open. */
    private Node expression() throws ExprException {
        return or();
    }

    private Node or() throws ExprException {
        Node left = and();
        while (peek().is("or")) {
            Token op = next();
            left = new Node.Binary("or", left, and(), op.pos());
        }
        return left;
    }

    private Node and() throws ExprException {
        Node left = not();
        while (peek().is("and")) {
            Token op = next();
            left = new Node.Binary("and", left, not(), op.pos());
        }
        return left;
    }

    private Node not() throws ExprException {
        if (peek().is("not")) {
            Token op = next();
            enter(op.pos());
            try {
                return new Node.Unary("not", not(), op.pos());
            } finally {
                depth--;
            }
        }
        return comparison();
    }

    /**
     * Comparisons, {@code is null}, {@code in}, {@code between} and {@code like}. They do not chain:
     * {@code a = b = c} or {@code x between 1 and 2 between ...} needs brackets, as in SQL.
     */
    private Node comparison() throws ExprException {
        Node left = concat();
        Token first = null;
        while (true) {
            Token t = peek();
            boolean isComparison = t.kind() == Kind.OP && switch (t.text()) {
                case "=", "<>", "<", "<=", ">", ">=" -> true;
                default -> false;
            };
            boolean isTest = t.is("is") || t.is("in") || t.is("between") || t.is("like")
                || (t.is("not") && (tokens.get(at + 1).is("in") || tokens.get(at + 1).is("between")
                    || tokens.get(at + 1).is("like")));
            if (!isComparison && !isTest) {
                return left;
            }
            if (first != null) {
                throw new ExprException("comparisons cannot follow one another without brackets", t.pos(),
                    "join them with and, or put brackets around the first, like (a = b) = c");
            }
            first = t;
            if (isComparison) {
                next();
                left = new Node.Binary(t.text(), left, concat(), t.pos());
            } else if (t.is("is")) {
                next();
                boolean negated = accept("not");
                expect("null", " after 'is" + (negated ? " not" : "") + "'");
                left = new Node.IsNull(left, negated, t.pos());
            } else {
                boolean negated = false;
                if (t.is("not")) {
                    next();
                    negated = true;
                }
                Token op = next();
                switch (op.text()) {
                    case "in" -> left = in(left, negated, t.pos());
                    case "between" -> {
                        Node low = concat();
                        expect("and", " in 'between … and …'");
                        Node high = concat();
                        left = new Node.Between(left, low, high, negated, t.pos());
                    }
                    default -> left = new Node.Like(left, concat(), negated, t.pos());
                }
            }
        }
    }

    private Node in(Node operand, boolean negated, int pos) throws ExprException {
        Token open = expect("(", " after 'in'");
        enter(open.pos());
        try {
            List<Node> items = new ArrayList<>();
            if (peek().is(")")) {
                throw new ExprException("'in' needs at least one value", peek().pos());
            }
            do {
                items.add(expression());
            } while (accept(","));
            expect(")", " to close the 'in' list");
            return new Node.In(operand, items, negated, pos);
        } finally {
            depth--;
        }
    }

    private Node concat() throws ExprException {
        Node left = additive();
        while (peek().is("||")) {
            Token op = next();
            left = new Node.Binary("||", left, additive(), op.pos());
        }
        return left;
    }

    private Node additive() throws ExprException {
        Node left = multiplicative();
        while (peek().is("+") || peek().is("-")) {
            Token op = next();
            left = new Node.Binary(op.text(), left, multiplicative(), op.pos());
        }
        return left;
    }

    private Node multiplicative() throws ExprException {
        Node left = unary();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            Token op = next();
            left = new Node.Binary(op.text(), left, unary(), op.pos());
        }
        return left;
    }

    private Node unary() throws ExprException {
        if (peek().is("-") || peek().is("+")) {
            Token op = next();
            Kind k = peek().kind();
            if (op.text().equals("-") && (k == Kind.INTEGER || k == Kind.DECIMAL || k == Kind.FLOAT)) {
                // A negative number is one literal, so -9223372036854775808 is a whole number.
                Token number = next();
                return number(new Token(k, "-" + number.text(), op.pos()));
            }
            enter(op.pos());
            try {
                Node operand = unary();
                return op.text().equals("+") ? operand : new Node.Unary("-", operand, op.pos());
            } finally {
                depth--;
            }
        }
        return primary();
    }

    private Node primary() throws ExprException {
        Token t = next();
        switch (t.kind()) {
            case INTEGER, DECIMAL, FLOAT -> {
                return number(t);
            }
            case STRING -> {
                return new Node.Literal(Type.UTF8, t.text(), t.pos());
            }
            case QUOTED_IDENT -> {
                return new Node.Column(t.text(), t.pos());
            }
            case IDENT -> {
                if (peek().is("(")) {
                    return call(t);
                }
                return new Node.Column(t.text(), t.pos());
            }
            case OP -> {
                if (t.text().equals("(")) {
                    enter(t.pos());
                    try {
                        Node inner = expression();
                        expect(")", " to close the '(' at " + t.pos());
                        return inner;
                    } finally {
                        depth--;
                    }
                }
                throw new ExprException("unexpected " + t.describe(), t.pos(), hintFor(t));
            }
            case KEYWORD -> {
                return keyword(t);
            }
            case END -> throw new ExprException("the expression ends too early", t.pos());
            default -> throw new ExprException("unexpected " + t.describe(), t.pos());
        }
    }

    private static Node number(Token t) throws ExprException {
        switch (t.kind()) {
            case INTEGER -> {
                try {
                    return new Node.Literal(Type.INT64, Long.parseLong(t.text()), t.pos());
                } catch (NumberFormatException e) {
                    BigDecimal v = new BigDecimal(t.text());
                    if (v.precision() > Type.MAX_PRECISION) {
                        throw new ExprException("the number " + shorten(t.text()) + " has more than 38 digits", t.pos());
                    }
                    return new Node.Literal(Type.decimal(v.precision(), 0), v, t.pos());
                }
            }
            case DECIMAL -> {
                BigDecimal v = new BigDecimal(t.text());
                if (Math.max(v.precision(), v.scale()) > Type.MAX_PRECISION) {
                    throw new ExprException("the number " + shorten(t.text()) + " has more than 38 digits", t.pos(),
                        "decimals hold at most 38 digits in all; use a float such as 1e-40 for tiny numbers");
                }
                return new Node.Literal(Type.decimal(Math.max(v.precision(), v.scale()), v.scale()), v, t.pos());
            }
            default -> {
                double v = Double.parseDouble(t.text());
                if (Double.isInfinite(v)) {
                    throw new ExprException("the number " + shorten(t.text()) + " is too large", t.pos());
                }
                return new Node.Literal(Type.FLOAT64, v, t.pos());
            }
        }
    }

    private static String shorten(String number) {
        return number.length() > 30 ? number.substring(0, 30) + "…" : number;
    }

    private static String hintFor(Token t) {
        return switch (t.text()) {
            case "=" -> "compare with '=' between two values, like status = 'open'";
            case ")" -> "there is a ')' without a matching '('";
            case "," -> "commas separate a function's arguments, inside its brackets";
            default -> null;
        };
    }

    private Node keyword(Token t) throws ExprException {
        switch (t.text()) {
            case "null" -> {
                return new Node.Literal(Type.NULL, null, t.pos());
            }
            case "true" -> {
                return new Node.Literal(Type.BOOLEAN, Boolean.TRUE, t.pos());
            }
            case "false" -> {
                return new Node.Literal(Type.BOOLEAN, Boolean.FALSE, t.pos());
            }
            case "date" -> {
                Token s = next();
                if (s.kind() != Kind.STRING) {
                    throw new ExprException("expected a date in quotes after 'date', like date '2024-01-31'", s.pos(),
                        "to use a column called date, quote it: \"date\"");
                }
                Long days = Temporal.parseDateLiteral(s.text());
                if (days == null) {
                    throw new ExprException("'" + s.text() + "' is not a date; write it as yyyy-mm-dd", s.pos());
                }
                return new Node.Literal(Type.DATE, days, t.pos());
            }
            case "timestamp" -> {
                Token s = next();
                if (s.kind() != Kind.STRING) {
                    throw new ExprException("expected a time in quotes after 'timestamp', like timestamp '2024-01-31 09:30:00'",
                        s.pos(), "to use a column called timestamp, quote it: \"timestamp\"");
                }
                Long micros = Temporal.parseTimestamp(s.text());
                if (micros == null) {
                    throw new ExprException("'" + s.text() + "' is not a timestamp; write it as yyyy-mm-dd hh:mm:ss",
                        s.pos());
                }
                return new Node.Literal(Type.TIMESTAMP, micros, t.pos());
            }
            case "case" -> {
                return caseExpression(t);
            }
            case "cast" -> {
                return cast(t);
            }
            default -> {
                String hint = Lexer.KEYWORDS.contains(t.text())
                    ? "to use a column called " + t.text() + ", quote it: \"" + t.text() + "\"" : null;
                throw new ExprException("unexpected '" + t.text() + "'", t.pos(), hint);
            }
        }
    }

    private Node caseExpression(Token start) throws ExprException {
        enter(start.pos());
        try {
            Node operand = peek().is("when") ? null : expression();
            List<Node> whens = new ArrayList<>();
            List<Node> thens = new ArrayList<>();
            if (!peek().is("when")) {
                throw new ExprException("expected 'when' after 'case' but found " + peek().describe(), peek().pos());
            }
            while (accept("when")) {
                whens.add(expression());
                expect("then", " after the 'when' condition");
                thens.add(expression());
            }
            Node otherwise = accept("else") ? expression() : null;
            expect("end", " to close the 'case' at " + start.pos());
            return new Node.Case(operand, whens, thens, otherwise, start.pos());
        } finally {
            depth--;
        }
    }

    private Node cast(Token start) throws ExprException {
        expect("(", " after 'cast'");
        enter(start.pos());
        try {
            Node operand = expression();
            expect("as", " in 'cast(… as type)'");
            Token name = next();
            if (name.kind() != Kind.IDENT && name.kind() != Kind.KEYWORD) {
                throw new ExprException("expected a type name after 'as' but found " + name.describe(), name.pos());
            }
            StringBuilder typeName = new StringBuilder(name.text());
            if (accept("(")) {
                typeName.append('(');
                while (!peek().is(")") && peek().kind() != Kind.END) {
                    typeName.append(next().text());
                }
                expect(")", " after the type's size");
                typeName.append(')');
            }
            Type target = Type.named(typeName.toString());
            if (target == null) {
                throw new ExprException("unknown type '" + typeName + "'", name.pos(),
                    "use boolean, int64, float64, decimal(p,s), utf8, date or timestamp");
            }
            expect(")", " to close the 'cast'");
            return new Node.Cast(operand, target, start.pos());
        } finally {
            depth--;
        }
    }

    private Node call(Token name) throws ExprException {
        expect("(", "");
        enter(name.pos());
        try {
            String fn = name.text().toLowerCase(Locale.ROOT);
            List<Node> args = new ArrayList<>();
            if (accept("*")) {
                expect(")", " after '*'; only count(*) takes a star");
                return new Node.Call(fn, args, true, name.pos());
            }
            if (!accept(")")) {
                do {
                    args.add(expression());
                } while (accept(","));
                expect(")", " to close " + name.text() + "(");
            }
            return new Node.Call(fn, args, false, name.pos());
        } finally {
            depth--;
        }
    }
}
