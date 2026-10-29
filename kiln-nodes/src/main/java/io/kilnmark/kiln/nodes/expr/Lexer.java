package io.kilnmark.kiln.nodes.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Splits an expression into tokens. Hand-written: no regular expressions, nothing evaluated. */
final class Lexer {

    enum Kind { IDENT, QUOTED_IDENT, KEYWORD, STRING, INTEGER, DECIMAL, FLOAT, OP, END }

    /** One token; {@code pos} counts from 1. Keywords are lower case in {@code text}. */
    record Token(Kind kind, String text, int pos) {

        boolean is(String keywordOrOp) {
            return (kind == Kind.KEYWORD || kind == Kind.OP) && text.equals(keywordOrOp);
        }

        String describe() {
            return switch (kind) {
                case END -> "the end of the expression";
                case STRING -> "text '" + shorten(text) + "'";
                case QUOTED_IDENT -> "\"" + shorten(text) + "\"";
                default -> "'" + shorten(text) + "'";
            };
        }

        private static String shorten(String s) {
            return s.length() > 30 ? s.substring(0, 30) + "…" : s;
        }
    }

    static final Set<String> KEYWORDS = Set.of("and", "or", "not", "is", "null", "true", "false", "in", "between",
        "like", "case", "when", "then", "else", "end", "cast", "as", "date", "timestamp");

    private final String text;
    private int i;

    private Lexer(String text) {
        this.text = text;
    }

    static List<Token> tokens(String text) throws ExprException {
        return new Lexer(text).run();
    }

    private List<Token> run() throws ExprException {
        List<Token> out = new ArrayList<>();
        int n = text.length();
        while (true) {
            while (i < n && isSpace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                out.add(new Token(Kind.END, "", n + 1));
                return out;
            }
            int start = i;
            char c = text.charAt(i);
            if (isIdentStart(c)) {
                while (i < n && isIdentPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                String lower = word.toLowerCase(Locale.ROOT);
                out.add(KEYWORDS.contains(lower) ? new Token(Kind.KEYWORD, lower, start + 1)
                    : new Token(Kind.IDENT, word, start + 1));
            } else if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(text.charAt(i + 1)))) {
                out.add(number(start));
            } else if (c == '\'') {
                out.add(new Token(Kind.STRING, quoted('\'', "text"), start + 1));
            } else if (c == '"') {
                String name = quoted('"', "column name");
                if (name.isEmpty()) {
                    throw new ExprException("a quoted column name cannot be empty", start + 1);
                }
                out.add(new Token(Kind.QUOTED_IDENT, name, start + 1));
            } else {
                out.add(operator(start));
            }
        }
    }

    private Token number(int start) throws ExprException {
        int n = text.length();
        boolean point = false;
        boolean exponent = false;
        while (i < n && isDigit(text.charAt(i))) {
            i++;
        }
        if (i < n && text.charAt(i) == '.') {
            point = true;
            i++;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            if (i < n && isDigit(text.charAt(i))) {
                exponent = true;
                while (i < n && isDigit(text.charAt(i))) {
                    i++;
                }
            } else {
                i = save;
            }
        }
        if (i < n && isIdentStart(text.charAt(i))) {
            throw new ExprException("a number cannot run into a name", i + 1,
                "put a space or an operator between them");
        }
        String s = text.substring(start, i);
        return new Token(exponent ? Kind.FLOAT : point ? Kind.DECIMAL : Kind.INTEGER, s, start + 1);
    }

    /** Reads a quoted string, the quote doubled inside it standing for itself. */
    private String quoted(char quote, String what) throws ExprException {
        int start = i;
        i++;
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == quote) {
                if (i + 1 < n && text.charAt(i + 1) == quote) {
                    sb.append(quote);
                    i += 2;
                    continue;
                }
                i++;
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        throw new ExprException("the " + what + " starting here has no closing " + quote, start + 1);
    }

    private Token operator(int start) throws ExprException {
        char c = text.charAt(i);
        char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
        String two = "" + c + next;
        switch (two) {
            case "<=", ">=", "<>", "!=", "||" -> {
                i += 2;
                return new Token(Kind.OP, two.equals("!=") ? "<>" : two, start + 1);
            }
            default -> {
                // single character below
            }
        }
        if ("+-*/%=<>(),".indexOf(c) >= 0) {
            i++;
            return new Token(Kind.OP, String.valueOf(c), start + 1);
        }
        String shown = Character.isISOControl(c) ? String.format("U+%04X", (int) c) : "'" + c + "'";
        String hint = c == '`' || c == '[' ? "quote column names with double quotes, like \"order date\"" : null;
        throw new ExprException("unexpected character " + shown, start + 1, hint);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }
}
