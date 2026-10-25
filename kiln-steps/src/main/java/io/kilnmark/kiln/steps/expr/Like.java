package io.kilnmark.kiln.steps.expr;

/**
 * A {@code like} pattern: {@code %} matches any run of characters, {@code _} any one character,
 * and a backslash makes the next character plain ({@code 100\%}). Case matters. Matched without
 * regular expressions, in time proportional to the text times the pattern at worst.
 */
final class Like {

    private static final int ANY_RUN = -1;
    private static final int ANY_ONE = -2;

    /** The pattern as code points, with {@link #ANY_RUN} and {@link #ANY_ONE} for the wildcards. */
    private final int[] pattern;

    Like(String text) {
        int[] cps = text.codePoints().toArray();
        int[] out = new int[cps.length];
        int n = 0;
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c == '\\' && i + 1 < cps.length) {
                out[n++] = cps[++i];
            } else if (c == '%') {
                if (n == 0 || out[n - 1] != ANY_RUN) { // %% is the same as %
                    out[n++] = ANY_RUN;
                }
            } else if (c == '_') {
                out[n++] = ANY_ONE;
            } else {
                out[n++] = c;
            }
        }
        pattern = java.util.Arrays.copyOf(out, n);
    }

    boolean matches(String value) {
        int[] s = value.codePoints().toArray();
        int si = 0;
        int pi = 0;
        int starP = -1;
        int starS = 0;
        while (si < s.length) {
            if (pi < pattern.length && (pattern[pi] == ANY_ONE || pattern[pi] == s[si])) {
                si++;
                pi++;
            } else if (pi < pattern.length && pattern[pi] == ANY_RUN) {
                starP = pi++;
                starS = si;
            } else if (starP >= 0) {
                pi = starP + 1;
                si = ++starS;
            } else {
                return false;
            }
        }
        while (pi < pattern.length && pattern[pi] == ANY_RUN) {
            pi++;
        }
        return pi == pattern.length;
    }
}
