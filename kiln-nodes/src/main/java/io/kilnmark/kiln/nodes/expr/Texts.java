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
        }
        padding.append(head(fill, need));
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
        if (n < 0) {
            long fields = 1;
            for (int at = s.indexOf(sep); at >= 0; at = s.indexOf(sep, at + sep.length())) {
                fields++;
            }
            n = fields + n + 1;
            if (n < 1) {
                return "";
            }
        }
        int from = 0;
        for (long field = 1; field < n; field++) {
            int at = s.indexOf(sep, from);
            if (at < 0) {
                return "";
            }
            from = at + sep.length();
        }
        int end = s.indexOf(sep, from);
        return end < 0 ? s.substring(from) : s.substring(from, end);
    }

    private static final HexFormat HEX = HexFormat.of();

    /** The SHA-256 digest of the text's UTF-8 bytes, as 64 lower-case hexadecimal digits. */
    static String sha256(String s) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }
}
