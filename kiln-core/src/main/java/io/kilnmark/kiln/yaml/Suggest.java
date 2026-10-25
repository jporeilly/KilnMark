package io.kilnmark.kiln.yaml;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean …?" for mistyped keys, step types and step ids. */
final class Suggest {

    private Suggest() {
    }

    /** The option closest to {@code typed}, if one is close enough to be a typing mistake; else null. */
    static String closest(String typed, Collection<String> options) {
        if (typed == null || typed.isEmpty()) {
            return null;
        }
        String t = typed.toLowerCase(Locale.ROOT);
        int allowed = t.length() <= 4 ? 1 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String o : options) {
            int d = distance(t, o.toLowerCase(Locale.ROOT));
            if (d <= allowed && d < bestDistance) {
                best = o;
                bestDistance = d;
            }
        }
        return best;
    }

    /** {@code " (did you mean 'x'?)"}, or nothing. */
    static String hint(String typed, Collection<String> options) {
        String c = closest(typed, options);
        return c == null ? "" : " (did you mean '" + c + "'?)";
    }

    /** Edits (insert, delete, substitute, swap two neighbours) to turn {@code a} into {@code b}. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
