package io.kilnmark.kiln.steps.expr;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean ...?" for column and function names. */
public final class Names {

    private Names() {
    }

    /** The closest name, if close enough to be a likely typing mistake. */
    public static String suggest(String bad, Collection<String> names) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String name : names) {
            if (name.equalsIgnoreCase(bad)) {
                return name;
            }
            int d = distance(bad.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
            if (d < bestScore) {
                bestScore = d;
                best = name;
            }
        }
        int allowed = Math.max(1, Math.min(3, bad.length() / 3));
        return bestScore <= allowed ? best : null;
    }

    /** Edit distance, counting a swap of two neighbouring letters as one edit. */
    static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 3 || a.length() > 200 || b.length() > 200) {
            return Integer.MAX_VALUE;
        }
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
