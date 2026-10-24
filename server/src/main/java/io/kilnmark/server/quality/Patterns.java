package io.kilnmark.server.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.server.quality.ProfileFacts.Counted;

/**
 * Regular expressions from value shapes: one per shape (for accepting a pattern), and a suggestion
 * covering a column's dominant shapes, with shapes that differ only in run lengths merged
 * ({@code A9 9AA} and {@code AA99 9AA} become {@code [A-Z]{1,2}[0-9]{1,2} [0-9][A-Z]{2}}).
 *
 * <p>The expressions use only what PostgreSQL, Java and ECMAScript read alike: character ranges,
 * counts in braces, backslash escapes and groups.
 */
public final class Patterns {

    /** A suggested expression, the share of values it covers, and why. */
    public record Suggestion(String regex, double coverage, String basis, List<String> shapes) {
    }

    /** Canonical expressions for formats the profile detects, where one fits better than shapes. */
    static final Map<String, String> FORMAT_REGEX = Map.of(
        "email", "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$",
        "uk_postcode", "^([A-Za-z]{1,2}[0-9][A-Za-z0-9]? ?[0-9][A-Za-z]{2}|GIR ?0AA)$",
        "ni_number", "^[A-CEGHJ-PR-TW-Za-ceghj-pr-tw-z]{2} ?[0-9]{2} ?[0-9]{2} ?[0-9]{2} ?[A-Da-d]$",
        "iso_date", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
        "iso_timestamp", "^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}([.,][0-9]+)?)?(Z|[+-][0-9]{2}(:?[0-9]{2})?)?$",
        "iban", "^[A-Z]{2}[0-9]{2}( ?[A-Z0-9]){11,30}$",
        "number_text", "^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$");

    /** Shapes covering this share of values make a suggestion. */
    static final double COVERAGE = 0.95;
    static final int MAX_ALTERNATIVES = 6;

    private Patterns() {
    }

    /** The anchored expression matching exactly the values of one shape (detailed or collapsed). */
    public static String regex(String shape) {
        return "^" + body(tokens(shape), null) + "$";
    }

    /** A run of one class, or one literal character. {@code plus} for collapsed runs. */
    private record Token(char c, int length, boolean plus) {
        boolean run() {
            return c == 'A' || c == 'a' || c == '9';
        }
    }

    private static List<Token> tokens(String shape) {
        List<Token> out = new ArrayList<>();
        int n = shape.length();
        for (int i = 0; i < n; ) {
            char c = shape.charAt(i);
            int j = i + 1;
            if (c == 'A' || c == 'a' || c == '9') {
                while (j < n && shape.charAt(j) == c) {
                    j++;
                }
                boolean plus = j < n && shape.charAt(j) == '+';
                out.add(new Token(c, j - i, plus));
                if (plus) {
                    j++;
                }
            } else {
                out.add(new Token(c, 1, false));
            }
            i = j;
        }
        return out;
    }

    /** The expression for tokens, with run lengths widened to {@code [min, max]} when given. */
    private static String body(List<Token> tokens, int[][] ranges) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.run()) {
                if (t.c() == '…') {
                    b.append(".*");
                } else if (t.c() == '?') {
                    b.append('.');
                } else {
                    b.append(literal(t.c()));
                }
                continue;
            }
            b.append(switch (t.c()) {
                case 'A' -> "[A-Z]";
                case 'a' -> "[a-z]";
                default -> "[0-9]";
            });
            int min = ranges == null ? t.length() : ranges[i][0];
            int max = ranges == null ? t.length() : ranges[i][1];
            if (t.plus()) {
                b.append('+');
            } else if (min == max) {
                if (min > 1) {
                    b.append('{').append(min).append('}');
                }
            } else {
                b.append('{').append(min).append(',').append(max).append('}');
            }
        }
        return b.toString();
    }

    private static String literal(char c) {
        if ("\\^$.|?*+()[]{}-/".indexOf(c) >= 0) {
            return "\\" + c;
        }
        if (c == '\t') {
            return "\\t";
        }
        return String.valueOf(c);
    }

    /**
     * A suggestion for a column: the canonical expression of a format that covers most values, or
     * else one built from the most frequent shapes until they cover {@value #COVERAGE} of values.
     */
    public static Suggestion suggest(List<Counted> patterns, Map<String, Long> formats, long values) {
        if (values <= 0 || patterns.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Long> f : formats.entrySet()) {
            String regex = FORMAT_REGEX.get(f.getKey());
            if (regex != null && f.getValue() >= COVERAGE * values) {
                return new Suggestion(regex, Measures.round((double) f.getValue() / values), "format " + f.getKey(),
                    List.of());
            }
        }
        List<Counted> chosen = new ArrayList<>();
        long covered = 0;
        for (Counted p : patterns) {
            if (covered >= COVERAGE * values) {
                break;
            }
            chosen.add(p);
            covered += p.count();
        }
        // Merge shapes with the same sequence of classes and literals, widening run lengths.
        Map<String, List<List<Token>>> groups = new LinkedHashMap<>();
        for (Counted p : chosen) {
            List<Token> t = tokens(p.item());
            StringBuilder key = new StringBuilder();
            t.forEach(x -> key.append(x.c()).append(x.plus() ? "+" : ""));
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(t);
        }
        if (groups.size() > MAX_ALTERNATIVES) {
            return null; // too varied to describe with one expression people would accept
        }
        List<String> alternatives = new ArrayList<>();
        for (List<List<Token>> group : groups.values()) {
            List<Token> first = group.get(0);
            int[][] ranges = new int[first.size()][2];
            for (int i = 0; i < first.size(); i++) {
                int min = Integer.MAX_VALUE;
                int max = 0;
                for (List<Token> t : group) {
                    min = Math.min(min, t.get(i).length());
                    max = Math.max(max, t.get(i).length());
                }
                ranges[i][0] = min;
                ranges[i][1] = max;
            }
            alternatives.add(body(first, ranges));
        }
        String regex = alternatives.size() == 1 ? "^" + alternatives.get(0) + "$"
            : "^(" + String.join("|", alternatives) + ")$";
        return new Suggestion(regex, Measures.round((double) covered / values), "most frequent shapes",
            chosen.stream().map(Counted::item).toList());
    }
}
