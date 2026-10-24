package io.kilnmark.server.quality;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The regular expressions rules may use: a subset that Java (for files and previews) and
 * PostgreSQL (in the database) read the same way. Literals and escaped punctuation, {@code .},
 * character classes with ranges, {@code \d \s \w} and their negations, {@code \p{Lu}} (capital
 * letters of any alphabet), {@code \p{Ll}} (small letters), {@code \p{L}} (any letter), groups
 * {@code ( )} and {@code (?: )}, alternation, anchors {@code ^ $}, and the quantifiers
 * {@code * + ? {m} {m,} {m,n}} (at most 255), optionally lazy. Not allowed: back-references,
 * look-around, inline flags, possessive quantifiers, word boundaries and other escapes, whose
 * meaning differs between the two or that PostgreSQL lacks.
 *
 * <p>Matching while streaming is bounded: a value longer than {@value #MAX_VALUE} characters, or
 * one whose match takes longer than {@value #BUDGET_MILLIS} ms, is not matched (and counts as
 * failing), so a pathological expression cannot stall a scan.
 */
public final class Regexes {

    static final int MAX_LENGTH = 1000;
    static final int MAX_VALUE = 10_000;
    static final int BUDGET_MILLIS = 50;

    private Regexes() {
    }

    /** Null when {@code regex} is in the common subset; otherwise what is wrong, in words. */
    public static String problem(String regex) {
        if (regex == null || regex.isEmpty() || regex.length() > MAX_LENGTH) {
            return "a regular expression of 1 to " + MAX_LENGTH + " characters";
        }
        try {
            walk(regex, null);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return e.getDescription() + " near position " + e.getIndex();
        }
        return null;
    }

    /** {@code regex} (already checked) as PostgreSQL writes it: {@code \p{Lu}} becomes {@code [[:upper:]]}. */
    public static String toPostgres(String regex) {
        StringBuilder b = new StringBuilder(regex.length() + 16);
        walk(regex, b);
        return b.toString();
    }

    /** Walks the expression, refusing what is outside the subset; writes the PostgreSQL form into {@code out}. */
    private static void walk(String r, StringBuilder out) {
        int n = r.length();
        boolean inClass = false;
        int classStart = -1;
        boolean quantifiable = false;
        for (int i = 0; i < n; i++) {
            char c = r.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    throw new IllegalArgumentException("ends with a lone backslash");
                }
                char e = r.charAt(i + 1);
                if (e == 'p') {
                    String cls = property(r, i);
                    String posix = switch (cls) {
                        case "Lu" -> "upper";
                        case "Ll" -> "lower";
                        case "L" -> "alpha";
                        default -> throw new IllegalArgumentException("\\p{" + cls + "} is not supported; use \\p{Lu}, "
                            + "\\p{Ll} or \\p{L}");
                    };
                    append(out, inClass ? "[:" + posix + ":]" : "[[:" + posix + ":]]");
                    i += cls.length() + 3;
                } else if ("dDsSwW".indexOf(e) >= 0 || e == 't' || e == 'n' || !Character.isLetterOrDigit(e)) {
                    append(out, r.substring(i, i + 2));
                    i++;
                } else {
                    throw new IllegalArgumentException("'\\" + e + "' is not supported here; escape only punctuation, "
                        + "or use \\d, \\s, \\w, \\p{Lu}, \\p{Ll} or \\p{L}");
                }
                quantifiable = !inClass;
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    throw new IllegalArgumentException("'[' inside a character class: escape it as \\[");
                }
                if (c == '&' && i + 1 < n && r.charAt(i + 1) == '&') {
                    throw new IllegalArgumentException("'&&' in a character class is not supported");
                }
                if (c == ']' && i > classStart + 1 && !(i == classStart + 2 && r.charAt(classStart + 1) == '^')) {
                    inClass = false;
                    quantifiable = true;
                }
                append(out, String.valueOf(c));
                continue;
            }
            switch (c) {
                case '[' -> {
                    inClass = true;
                    classStart = i;
                    append(out, "[");
                    continue;
                }
                case '(' -> {
                    if (i + 1 < n && r.charAt(i + 1) == '?') {
                        if (i + 2 < n && r.charAt(i + 2) == ':') {
                            append(out, "(?:");
                            i += 2;
                        } else {
                            throw new IllegalArgumentException("only (?: ) groups are supported, not look-around, "
                                + "flags or names");
                        }
                    } else {
                        append(out, "(");
                    }
                    quantifiable = false;
                    continue;
                }
                case '*', '+', '?' -> {
                    if (!quantifiable) {
                        throw new IllegalArgumentException("'" + c + "' at position " + i + " has nothing to repeat");
                    }
                    append(out, String.valueOf(c));
                    i = lazy(r, i, out);
                    quantifiable = false;
                    continue;
                }
                case '{' -> {
                    int close = r.indexOf('}', i);
                    if (close < 0 || !quantifiable || !r.substring(i + 1, close).matches("[0-9]{1,3}(,[0-9]{0,3})?")) {
                        throw new IllegalArgumentException("'{' must be a count such as {2} or {1,3} after something "
                            + "to repeat; escape it as \\{ to match it");
                    }
                    for (String part : r.substring(i + 1, close).split(",")) {
                        if (!part.isEmpty() && Integer.parseInt(part) > 255) {
                            throw new IllegalArgumentException("counts in braces are at most 255");
                        }
                    }
                    append(out, r.substring(i, close + 1));
                    i = lazy(r, close, out);
                    quantifiable = false;
                    continue;
                }
                case ')', '.' -> quantifiable = true;
                case '|', '^', '$' -> quantifiable = false;
                default -> quantifiable = true;
            }
            append(out, String.valueOf(c));
        }
        if (inClass) {
            throw new IllegalArgumentException("a character class '[' is not closed");
        }
    }

    /** After a quantifier at {@code i}: a lazy {@code ?} is copied; a possessive {@code +} is refused. */
    private static int lazy(String r, int i, StringBuilder out) {
        if (i + 1 < r.length() && r.charAt(i + 1) == '?') {
            append(out, "?");
            return i + 1;
        }
        if (i + 1 < r.length() && r.charAt(i + 1) == '+') {
            throw new IllegalArgumentException("possessive quantifiers such as *+ are not supported");
        }
        return i;
    }

    private static String property(String r, int i) {
        if (i + 2 >= r.length() || r.charAt(i + 2) != '{') {
            throw new IllegalArgumentException("write \\p{Lu}, \\p{Ll} or \\p{L}");
        }
        int close = r.indexOf('}', i + 3);
        if (close < 0) {
            throw new IllegalArgumentException("\\p{ is not closed");
        }
        return r.substring(i + 3, close);
    }

    private static void append(StringBuilder out, String s) {
        if (out != null) {
            out.append(s);
        }
    }

    /**
     * Whether {@code p} finds a match in {@code value}, or null when the value is too long or the
     * match took longer than the budget.
     */
    public static Boolean find(Pattern p, String value) {
        if (value.length() > MAX_VALUE) {
            return null;
        }
        try {
            return p.matcher(new Deadline(value, System.nanoTime() + BUDGET_MILLIS * 1_000_000L)).find();
        } catch (Deadline.Expired e) {
            return null;
        }
    }

    /** Text that stops a match running past its deadline. */
    private static final class Deadline implements CharSequence {
        private final String s;
        private final long deadline;
        private int reads;

        Deadline(String s, long deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() > deadline) {
                throw new Expired();
            }
            return s.charAt(index);
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(s.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return s;
        }

        static final class Expired extends RuntimeException {
            @java.io.Serial
            private static final long serialVersionUID = 1L;

            Expired() {
                super("regular expression took too long", null, false, false);
            }
        }
    }
}
