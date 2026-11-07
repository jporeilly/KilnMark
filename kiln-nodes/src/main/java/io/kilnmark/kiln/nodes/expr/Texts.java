package io.kilnmark.kiln.nodes.expr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The text functions that count characters: padding, the start or end of a text, and splitting
 * it. Characters are Unicode code points, as {@code length} and {@code substring} count them, so a
 * character outside the Basic Multilingual Plane (an emoji, say) is one character, never half of one.
 */
final class Texts {

    private Texts() {
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** The first {@code n} characters of {@code s} (all of it when shorter). */
    private static String head(String s, long n) {
        if (n <= 0) {
            return "";
        }
        if (n >= s.length()) {
            return s;
        }
        int len = length(s);
        return n >= len ? s : s.substring(0, s.offsetByCodePoints(0, (int) n));
    }

    /** The last {@code n} characters of {@code s} (all of it when shorter). */
    private static String tail(String s, long n) {
        if (n <= 0) {
            return "";
        }
        int len = length(s);
        return n >= len ? s : s.substring(s.offsetByCodePoints(0, (int) (len - n)));
    }

    /**
     * {@code s} filled to {@code n} characters with {@code fill} repeated, on the left or on the right,
     * as PostgreSQL's {@code lpad} and {@code rpad}: a longer {@code s} is cut to its first {@code n}
     * characters, an empty {@code fill} leaves {@code s} as it is, and {@code n} of 0 or less gives
     * empty text.
     */
    static String pad(String s, long n, String fill, boolean left, int pos, TextBudget budget) throws ExprException {
        if (n <= 0) {
            return "";
        }
        if (n > Casts.MAX_TEXT) {
            throw Casts.tooLong(pos);
        }
        int len = length(s);
        if (len >= n) {
            return head(s, n);
        }
        if (fill.isEmpty()) {
            return s;
        }
        long need = n - len;
        int fillLength = length(fill);
        StringBuilder padding = new StringBuilder();
        while (need >= fillLength) {
            padding.append(fill);
            need -= fillLength;
            if (s.length() + padding.length() > Casts.MAX_TEXT) {
                throw Casts.tooLong(pos); // as the other functions count it: in UTF-16 units
            }
        }
        padding.append(head(fill, need));
        if (s.length() + padding.length() > Casts.MAX_TEXT) {
            throw Casts.tooLong(pos);
        }
        budget.spend(s.length() + padding.length(), pos);
        return left ? padding + s : s + padding;
    }

    /**
     * {@code left(s, n)}: the first {@code n} characters, or with a negative {@code n} all but the
     * last {@code -n}; {@code right} the same from the end (PostgreSQL's {@code left} and {@code right}).
     */
    static String side(String s, long n, boolean left) {
        if (n < 0) {
            long keep = Math.max(0, (long) length(s) + n);
            return left ? head(s, keep) : tail(s, keep);
        }
        return left ? head(s, n) : tail(s, n);
    }

    /**
     * Field {@code n} of {@code s} split at every {@code sep}, counting from 1, or from the end when
     * {@code n} is negative ({@code -1} is the last); empty text when there is no such field. An empty
     * {@code sep} leaves {@code s} as its only field.
     */
    static String splitPart(String s, String sep, long n, int pos) throws ExprException {
        if (n == 0) {
            throw new ExprException("split_part's field number cannot be 0", pos,
                "count fields from 1, or from -1 for the last");
        }
        if (sep.isEmpty()) {
            return n == 1 || n == -1 ? s : "";
        }
        int[] table = sep.length() > SHORT ? table(sep) : null;
        if (n < 0) {
            long fields = 1;
            for (int at = find(s, sep, 0, table); at >= 0; at = find(s, sep, at + sep.length(), table)) {
                fields++;
            }
            n = fields + n + 1;
            if (n < 1) {
                return "";
            }
        }
        int from = 0;
        for (long field = 1; field < n; field++) {
            int at = find(s, sep, from, table);
            if (at < 0) {
                return "";
            }
            from = at + sep.length();
        }
        int end = find(s, sep, from, table);
        return end < 0 ? s.substring(from) : s.substring(from, end);
    }

    /** Whether {@code s} holds {@code x}, in time linear in their lengths. */
    static boolean contains(String s, String x) {
        return find(s, x, 0, x.length() > SHORT ? table(x) : null) >= 0;
    }

    /**
     * Below this length a text searched for is found with {@link String#indexOf}, whose worst case
     * (the text's length times this) stays small; longer ones with a search whose time is linear in
     * both lengths, so a hostile pair of long values cannot take minutes on one row.
     */
    private static final int SHORT = 64;

    /** Where {@code x} next appears in {@code s} from {@code from}, or -1; {@code table} null for a short {@code x}. */
    private static int find(String s, String x, int from, int[] table) {
        if (table == null) {
            return s.indexOf(x, from);
        }
        for (int i = from, k = 0; i < s.length(); i++) {
            while (k > 0 && s.charAt(i) != x.charAt(k)) {
                k = table[k - 1];
            }
            if (s.charAt(i) == x.charAt(k)) {
                k++;
            }
            if (k == x.length()) {
                return i - k + 1;
            }
        }
        return -1;
    }

    /** For each start of {@code x}, the longest proper start of it that is also its end (Knuth, Morris and Pratt). */
    private static int[] table(String x) {
        int[] t = new int[x.length()];
        for (int i = 1, k = 0; i < x.length(); i++) {
            while (k > 0 && x.charAt(i) != x.charAt(k)) {
                k = t[k - 1];
            }
            if (x.charAt(i) == x.charAt(k)) {
                k++;
            }
            t[i] = k;
        }
        return t;
    }

    private static final HexFormat HEX = HexFormat.of();

    /** A SHA-256 digest, for one function call (a compiled expression is used by one thread at a time). */
    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    /** The SHA-256 digest of the text's UTF-8 bytes, as 64 lower-case hexadecimal digits. */
    static String sha256(MessageDigest digest, String s) {
        return HEX.formatHex(digest.digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
