package io.kilnmark.server.ai.assistant;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Section anchors, made exactly as the in-app documentation viewer makes them
 * ({@code ui/src/docs/slug.ts}), so a section id here is the anchor the viewer scrolls to. Both
 * are checked against {@code tests/docs/slug-cases.json}; change them together.
 */
public final class DocSlugs {

    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern MARKS = Pattern.compile("[`*]");
    private static final Pattern DROPPED = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\p{Pc} \\-]");

    private DocSlugs() {
    }

    /** Heading text without its Markdown: links keep their words, code and emphasis marks go. */
    public static String plainHeading(String markdown) {
        String s = LINK.matcher(markdown).replaceAll("$1");
        return jsTrim(MARKS.matcher(s).replaceAll(""));
    }

    /** The anchor for a heading, as GitHub makes it: "Custom SQL (admins)" gives "custom-sql-admins". */
    public static String slug(String heading) {
        String s = plainHeading(heading).toLowerCase(Locale.ROOT);
        return DROPPED.matcher(s).replaceAll("").replace(' ', '-');
    }

    /**
     * Gives repeated slugs on one page a -1, -2 ... suffix, skipping a suffix another heading
     * already has. Pass a fresh map per page.
     */
    public static String uniqueSlug(String base, Map<String, Integer> seen) {
        String result = base;
        while (seen.containsKey(result)) {
            int n = seen.getOrDefault(base, 0) + 1;
            seen.put(base, n);
            result = base + "-" + n;
        }
        seen.put(result, 0);
        return result;
    }

    /** JavaScript's String.trim: also drops no-break spaces and the byte order mark, which Java's strip keeps. */
    static String jsTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && jsSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && jsSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean jsSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f' || c == '\u00A0'
            || c == '\uFEFF' || c == '\u1680' || (c >= '\u2000' && c <= '\u200A') || c == '\u2028' || c == '\u2029'
            || c == '\u202F' || c == '\u205F' || c == '\u3000';
    }
}
