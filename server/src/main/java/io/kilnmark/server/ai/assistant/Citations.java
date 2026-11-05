package io.kilnmark.server.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the citations in a model's answer against the numbered sections it was given. Markers
 * like {@code [2]}, {@code [1, 3]} or {@code [Section 2]} are kept, one per number as
 * {@code [n]}, only for numbers that were sent; anything else in brackets that looks like a
 * citation is removed, so an answer can never link to a section the server did not send.
 */
public final class Citations {

    private static final Pattern MARKER = Pattern.compile(
        "\\s?\\[(?:(?i:sections?|source|sources)\\s*)?(\\d{1,3}(?:\\s*(?:,|and|&)\\s*\\d{1,3})*)\\]");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");

    private Citations() {
    }

    /** The answer with only valid markers, and the numbers it cites, in order of first use. */
    public record Checked(String text, List<Integer> cited) {
    }

    /**
     * Checks the answer's citations against the sections that were sent.
     *
     * @param sent the section numbers that were in the prompt (1 to n)
     */
    public static Checked check(String answer, int sent) {
        Set<Integer> cited = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(answer);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            List<Integer> valid = new ArrayList<>();
            Matcher n = NUMBER.matcher(m.group(1));
            while (n.find()) {
                int x = Integer.parseInt(n.group());
                if (x >= 1 && x <= sent && !valid.contains(x)) {
                    valid.add(x);
                }
            }
            StringBuilder r = new StringBuilder();
            if (!valid.isEmpty()) {
                r.append(m.group().startsWith("[") ? "" : m.group().substring(0, 1));
                for (int x : valid) {
                    r.append('[').append(x).append(']');
                    cited.add(x);
                }
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(b);
        return new Checked(b.toString().strip(), List.copyOf(cited));
    }
}
