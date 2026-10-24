package io.kilnmark.kiln.connect;

import java.util.ArrayList;
import java.util.List;

/**
 * One glob pattern, matched ignoring case, in time proportional to the pattern's length times the
 * text's length: there is no regular expression and no backtracking, so no pattern can make a
 * match take long (a pattern such as {@code *a*a*a*a*a*a*a*a*b} costs no more than any other).
 *
 * <p>Syntax: {@code *} any run of characters except {@code /}; {@code **} any run including
 * {@code /}; {@code **}{@code /} zero or more whole folders; {@code ?} one character except
 * {@code /}; {@code [abc]}, {@code [a-z]}, {@code [!a-z]} one character of (or not of) a set;
 * {@code {a,b}} either alternative (braces are expanded before matching, at most
 * {@value #MAX_ALTERNATIVES} alternatives in all). A backslash makes the next character literal.
 */
final class Glob {

    /** The longest pattern accepted. */
    static final int MAX_LENGTH = 200;
    /** The most alternatives brace expansion may produce for one pattern. */
    static final int MAX_ALTERNATIVES = 64;

    private enum Kind { LITERAL, ONE, SET, STAR, GLOBSTAR, FOLDERS }

    /** One element of a pattern. {@code FOLDERS} is {@code **}{@code /}: empty, or anything ending in {@code /}. */
    private record Token(Kind kind, char c, char[] from, char[] to, boolean negated) {
        boolean matches(char x) {
            return switch (kind) {
                case LITERAL -> same(c, x);
                case ONE -> x != '/';
                case SET -> x != '/' && inSet(x) != negated;
                default -> false;
            };
        }

        private boolean inSet(char x) {
            char lo = Character.toLowerCase(x);
            char up = Character.toUpperCase(x);
            for (int i = 0; i < from.length; i++) {
                if (lo >= from[i] && lo <= to[i] || up >= from[i] && up <= to[i]) {
                    return true;
                }
            }
            return false;
        }
    }

    private final String text;
    private final List<Token[]> alternatives;

    private Glob(String text, List<Token[]> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    /** The pattern as written. */
    String text() {
        return text;
    }

    /**
     * Compiles a pattern.
     *
     * @throws IllegalArgumentException naming {@code key}, saying what is wrong
     */
    static Glob compile(String key, String pattern) {
        if (pattern.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(key + ": a pattern may be at most " + MAX_LENGTH + " characters long");
        }
        List<String> expanded = new ArrayList<>();
        expand(key, pattern, pattern, expanded);
        List<Token[]> alternatives = new ArrayList<>();
        for (String alternative : expanded) {
            alternatives.add(tokens(key, pattern, alternative));
        }
        return new Glob(pattern, List.copyOf(alternatives));
    }

    /** Expands the first top-level brace group, then the rest, recursively; caps the total. */
    private static void expand(String key, String original, String p, List<String> out) {
        int open = -1;
        int depth = 0;
        int close = -1;
        List<Integer> commas = new ArrayList<>();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') {
                int end = p.indexOf(']', i + 2);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '{') {
                if (depth == 0) {
                    open = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    throw new IllegalArgumentException(key + ": '" + original + "' closes a } it never opened");
                }
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            } else if (c == ',' && depth == 1) {
                commas.add(i);
            }
        }
        if (depth > 0) {
            throw new IllegalArgumentException(key + ": '" + original + "' opens a { without closing it");
        }
        if (open < 0) {
            if (out.size() >= MAX_ALTERNATIVES) {
                throw new IllegalArgumentException(key + ": '" + original + "' has too many {a,b} alternatives; at most "
                    + MAX_ALTERNATIVES);
            }
            out.add(p);
            return;
        }
        String head = p.substring(0, open);
        String tail = p.substring(close + 1);
        int start = open + 1;
        commas.add(close);
        for (int comma : commas) {
            expand(key, original, head + p.substring(start, comma) + tail, out);
            start = comma + 1;
        }
    }

    private static Token[] tokens(String key, String original, String p) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (i + 1 >= p.length()) {
                        throw new IllegalArgumentException(key + ": '" + original + "' ends with a \\");
                    }
                    out.add(literal(p.charAt(++i)));
                }
                case '*' -> {
                    if (i + 1 < p.length() && p.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < p.length() && p.charAt(i + 1) == '/') {
                            i++;
                            add(out, new Token(Kind.FOLDERS, '\0', null, null, false));
                        } else {
                            add(out, new Token(Kind.GLOBSTAR, '\0', null, null, false));
                        }
                    } else {
                        add(out, new Token(Kind.STAR, '\0', null, null, false));
                    }
                }
                case '?' -> out.add(new Token(Kind.ONE, '\0', null, null, false));
                case '[' -> {
                    int end = p.indexOf(']', i + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException(key + ": '" + original + "' opens a [ set without closing it");
                    }
                    String body = p.substring(i + 1, end);
                    boolean negated = body.startsWith("!") || body.startsWith("^");
                    if (negated) {
                        body = body.substring(1);
                    }
                    if (body.isEmpty()) {
                        throw new IllegalArgumentException(key + ": '" + original + "' has an empty [ ] set");
                    }
                    List<char[]> ranges = new ArrayList<>();
                    for (int j = 0; j < body.length(); j++) {
                        char a = Character.toLowerCase(body.charAt(j));
                        char b = a;
                        if (j + 2 < body.length() && body.charAt(j + 1) == '-') {
                            b = Character.toLowerCase(body.charAt(j + 2));
                            j += 2;
                        }
                        ranges.add(new char[] {(char) Math.min(a, b), (char) Math.max(a, b)});
                    }
                    char[] from = new char[ranges.size()];
                    char[] to = new char[ranges.size()];
                    for (int j = 0; j < ranges.size(); j++) {
                        from[j] = ranges.get(j)[0];
                        to[j] = ranges.get(j)[1];
                    }
                    out.add(new Token(Kind.SET, '\0', from, to, negated));
                    i = end;
                }
                default -> out.add(literal(c));
            }
        }
        return out.toArray(new Token[0]);
    }

    /** Adds a wildcard, folding runs such as {@code ***} that match nothing more than one would. */
    private static void add(List<Token> out, Token t) {
        if (!out.isEmpty() && out.get(out.size() - 1).kind() == t.kind() && t.kind() != Kind.FOLDERS) {
            return;
        }
        out.add(t);
    }

    private static Token literal(char c) {
        return new Token(Kind.LITERAL, c, null, null, false);
    }

    private static boolean same(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b)
            || Character.toUpperCase(a) == Character.toUpperCase(b);
    }

    /** Whether the whole of {@code s} matches, ignoring case. */
    boolean matches(String s) {
        for (Token[] t : alternatives) {
            if (matches(t, s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simulates the pattern as a set of positions (a non-deterministic automaton): for each
     * character of the text, every live position moves at once. Never backtracks.
     */
    private static boolean matches(Token[] t, String s) {
        int m = t.length;
        boolean[] live = new boolean[m + 1];
        boolean[] next = new boolean[m + 1];
        live[0] = true;
        close(t, live);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            java.util.Arrays.fill(next, false);
            boolean any = false;
            for (int i = 0; i < m; i++) {
                if (!live[i]) {
                    continue;
                }
                Token tok = t[i];
                switch (tok.kind()) {
                    case STAR -> {
                        if (c != '/') {
                            next[i] = true;
                            any = true;
                        }
                    }
                    case GLOBSTAR -> {
                        next[i] = true;
                        any = true;
                    }
                    case FOLDERS -> {
                        next[i] = true; // still inside the folders
                        if (c == '/') {
                            next[i + 1] = true; // the folders end here
                        }
                        any = true;
                    }
                    default -> {
                        if (tok.matches(c)) {
                            next[i + 1] = true;
                            any = true;
                        }
                    }
                }
            }
            if (!any) {
                return false;
            }
            close(t, next);
            boolean[] swap = live;
            live = next;
            next = swap;
        }
        return live[m];
    }

    /** Adds the positions reachable without reading a character: past any wildcard that may match nothing. */
    private static void close(Token[] t, boolean[] live) {
        for (int i = 0; i < t.length; i++) {
            if (live[i] && (t[i].kind() == Kind.STAR || t[i].kind() == Kind.GLOBSTAR || t[i].kind() == Kind.FOLDERS)) {
                live[i + 1] = true;
            }
        }
    }

    @Override
    public String toString() {
        return text;
    }
}
