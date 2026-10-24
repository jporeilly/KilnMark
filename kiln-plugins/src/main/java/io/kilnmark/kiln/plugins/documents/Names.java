package io.kilnmark.kiln.plugins.documents;

/**
 * Names taken from inside files (sheet names, attachment file names) are untrusted: control and
 * formatting characters (NUL, line breaks, right-to-left overrides such as U+202E that disguise a
 * name's extension) become {@code _}, and length is capped.
 */
public final class Names {

    /** The longest name kept, in characters. */
    public static final int MAX = 200;

    private Names() {
    }

    /** {@code name} safe to store and show; {@code fallback} when nothing is left. */
    public static String clean(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String s = name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "_").strip();
        if (s.length() > MAX) {
            int end = Character.isHighSurrogate(s.charAt(MAX - 1)) ? MAX - 1 : MAX;
            s = s.substring(0, end);
        }
        return s.isEmpty() ? fallback : s;
    }
}
