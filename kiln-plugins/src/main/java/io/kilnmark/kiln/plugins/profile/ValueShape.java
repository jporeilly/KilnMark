package io.kilnmark.kiln.plugins.profile;

/**
 * A value reduced to its shape: upper-case letters become {@code A}, lower-case letters {@code a},
 * digits {@code 9}; spaces and punctuation stay as they are. {@code SW1A 1AA} becomes
 * {@code AA9A 9AA}, {@code 2024-03-01} becomes {@code 9999-99-99}. The collapsed form writes each
 * run of lower-case letters or digits as {@code a+} or {@code 9+}, and runs of more than one
 * capital as {@code A+}: {@code Aa+ Aa+} for most two-word names, whatever their lengths. A
 * literal {@code +} (or backslash) in a collapsed shape is written with a backslash before it.
 */
public final class ValueShape {

    /** Shapes longer than this are cut, and end with {@value #CUT}. */
    public static final int MAX = 48;
    /** Marks a shape cut at {@link #MAX} characters. */
    public static final String CUT = "…";

    private ValueShape() {
    }

    /** The shape of {@code value}, at most {@link #MAX} characters plus {@link #CUT}. */
    public static String of(String value) {
        int n = value.length();
        StringBuilder b = new StringBuilder(Math.min(n, MAX + 1));
        int count = 0;
        for (int i = 0; i < n; ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (count == MAX) {
                return b.append(CUT).toString();
            }
            b.append(symbol(cp));
            count++;
        }
        return b.toString();
    }

    /** The shape with runs collapsed: see the class description. */
    public static String collapse(String shape) {
        StringBuilder b = new StringBuilder(shape.length());
        int n = shape.length();
        for (int i = 0; i < n; ) {
            char c = shape.charAt(i);
            int j = i + 1;
            if (c == 'A' || c == 'a' || c == '9') {
                while (j < n && shape.charAt(j) == c) {
                    j++;
                }
                b.append(c);
                if (c != 'A' || j - i > 1) {
                    b.append('+');
                }
            } else if (c == '+' || c == '\\') {
                b.append('\\').append(c); // a literal plus sign is \+, so + after a run stays unambiguous
            } else {
                b.append(c);
            }
            i = j;
        }
        return b.toString();
    }

    private static char symbol(int cp) {
        if (cp >= '0' && cp <= '9') {
            return '9';
        }
        if (cp >= 'A' && cp <= 'Z') {
            return 'A';
        }
        if (cp >= 'a' && cp <= 'z') {
            return 'a';
        }
        if (Character.isDigit(cp)) {
            return '9';
        }
        if (Character.isLetter(cp)) {
            return Character.isUpperCase(cp) || Character.isTitleCase(cp) ? 'A' : 'a';
        }
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return cp == '\t' ? '\t' : ' ';
        }
        if (Character.isISOControl(cp) || cp > 0xFFFF) {
            return '?'; // control characters and emoji: shown as one mark, not as themselves
        }
        return (char) cp;
    }
}
