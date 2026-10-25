package io.kilnmark.server.ai.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merging the two rankings of sections (words and meaning), the boost for the page the person is
 * on, and the passage shown as a snippet. No database here, so it is tested on its own.
 *
 * <p>Scores, not ranks, are merged: each list's scores are divided by its best, and a section's
 * score is the mean over the lists. Reciprocal rank fusion, as asset search uses, flattens a list
 * of a few hundred sections so much that a page boost either does nothing or always wins.
 */
public final class KnowledgeRanking {

    /** A section on the page the person is on scores this much more. */
    static final double PAGE_BOOST = 1.25;

    private KnowledgeRanking() {
    }

    /** A section's place in the merged ranking: its score and how it was found ({@code words}, {@code meaning}). */
    public record Scored(String id, double score, List<String> matchedBy) {
    }

    /** The page a section id belongs to: the part before the first slash. */
    public static String pageOf(String sectionId) {
        int slash = sectionId.indexOf('/');
        return slash < 0 ? sectionId : sectionId.substring(0, slash);
    }

    /**
     * Merges both rankings (best first, each section's score in its list), boosts sections on
     * {@code pageHint}, and returns them best first. Ties keep the words order, then meaning's.
     *
     * @param meaning empty when search by meaning was not used
     */
    public static List<Scored> fuse(Map<String, Double> words, Map<String, Double> meaning, String pageHint) {
        double topWords = max(words);
        double topMeaning = max(meaning);
        int lists = meaning.isEmpty() ? 1 : 2;
        Map<String, double[]> s = new LinkedHashMap<>(); // score, words rank, meaning rank
        int rank = 0;
        for (Map.Entry<String, Double> e : words.entrySet()) {
            double[] a = s.computeIfAbsent(e.getKey(), k -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            a[0] += topWords > 0 ? e.getValue() / topWords : 0;
            a[1] = rank++;
        }
        rank = 0;
        for (Map.Entry<String, Double> e : meaning.entrySet()) {
            double[] a = s.computeIfAbsent(e.getKey(), k -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            a[0] += topMeaning > 0 ? e.getValue() / topMeaning : 0;
            a[2] = rank++;
        }
        List<Scored> out = new ArrayList<>();
        Map<String, double[]> ranks = new HashMap<>();
        for (Map.Entry<String, double[]> e : s.entrySet()) {
            double score = e.getValue()[0] / lists;
            if (pageHint != null && pageHint.equals(pageOf(e.getKey()))) {
                score *= PAGE_BOOST;
            }
            List<String> by = new ArrayList<>(2);
            if (words.containsKey(e.getKey())) {
                by.add("words");
            }
            if (meaning.containsKey(e.getKey())) {
                by.add("meaning");
            }
            out.add(new Scored(e.getKey(), Math.round(score * 10000) / 10000.0, List.copyOf(by)));
            ranks.put(e.getKey(), e.getValue());
        }
        out.sort((x, y) -> {
            int c = Double.compare(y.score(), x.score());
            if (c != 0) {
                return c;
            }
            c = Double.compare(ranks.get(x.id())[1], ranks.get(y.id())[1]);
            return c != 0 ? c : Double.compare(ranks.get(x.id())[2], ranks.get(y.id())[2]);
        });
        return out;
    }

    private static double max(Map<String, Double> m) {
        return m.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Set<String> COMMON = Set.of("the", "and", "for", "are", "how", "what", "does", "can", "you",
        "with", "this", "that", "from", "have", "has", "why", "when", "where", "which", "who", "into", "about",
        "there", "their", "them", "then", "than", "not", "but", "use", "using", "kilnmark", "mean", "means");

    /** The words of a query worth looking for, cut to a rough stem: "thresholds" looks for "thresho". */
    static List<String> stems(String query) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(query == null ? "" : query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 3 || COMMON.contains(w)) {
                continue;
            }
            String stem = w.length() > 5 ? w.substring(0, w.length() - 2) : w;
            if (!out.contains(stem)) {
                out.add(stem);
            }
        }
        return out;
    }

    /**
     * About {@code length} characters of {@code text} around the first place a word of
     * {@code query} (or its stem) appears, on one line, cut at word boundaries with an ellipsis
     * where text is left out. The start of the text when no word appears.
     */
    public static String snippet(String text, String query, int length) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= length) {
            return flat;
        }
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String stem : stems(query)) {
            int i = indexOfWord(lower, stem);
            if (i >= 0 && (at < 0 || i < at)) {
                at = i;
            }
        }
        int start = at < 0 ? 0 : Math.max(0, at - length / 4);
        if (start > 0) {
            int space = flat.indexOf(' ', start);
            start = space < 0 || space > at ? start : space + 1;
        }
        int end = Math.min(flat.length(), start + length);
        if (end < flat.length()) {
            int space = flat.lastIndexOf(' ', end);
            end = space > start ? space : end;
        }
        return (start > 0 ? "…" : "") + flat.substring(start, end).strip() + (end < flat.length() ? "…" : "");
    }

    private static final Pattern BLOCK_START = Pattern.compile("^(?:[-*+] |\\d+[.)] |\\|)");

    /**
     * The parts of a long text that best match {@code query}, within {@code room} characters, in
     * their original order: paragraphs, list items and table rows that contain the most of the
     * query's words. A long section, such as a release's details, then sends the model the
     * passages that answer, not just its start. Text that fits is returned whole.
     */
    public static String excerpt(String text, String query, int room) {
        if (text.length() <= room) {
            return text;
        }
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            boolean starts = line.isBlank() || BLOCK_START.matcher(line).find();
            if (starts && !cur.isEmpty()) {
                blocks.add(cur.toString().strip());
                cur.setLength(0);
            }
            if (!line.isBlank()) {
                cur.append(line).append('\n');
            }
        }
        if (!cur.isEmpty()) {
            blocks.add(cur.toString().strip());
        }
        List<String> stems = stems(query);
        Integer[] order = new Integer[blocks.size()];
        int[] score = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            order[i] = i;
            String lower = blocks.get(i).toLowerCase(Locale.ROOT);
            for (String s : stems) {
                if (indexOfWord(lower, s) >= 0) {
                    score[i]++;
                }
            }
        }
        java.util.Arrays.sort(order, (a, b) -> score[b] != score[a] ? Integer.compare(score[b], score[a])
            : Integer.compare(a, b));
        boolean[] keep = new boolean[blocks.size()];
        int used = 0;
        for (int i : order) {
            int len = blocks.get(i).length() + 1;
            if (used + len > room) {
                continue;
            }
            keep[i] = true;
            used += len;
        }
        StringBuilder b = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < blocks.size(); i++) {
            if (keep[i]) {
                if (gap && !b.isEmpty()) {
                    b.append("…\n");
                }
                b.append(blocks.get(i)).append('\n');
                gap = false;
            } else {
                gap = true;
            }
        }
        if (b.isEmpty()) {
            return text.substring(0, room) + " …";
        }
        return (keep[0] ? "" : "…\n") + b.toString().strip() + (gap ? "\n…" : "");
    }

    /** The first place {@code stem} starts a word in {@code text}, or -1. */
    private static int indexOfWord(String text, String stem) {
        int from = 0;
        while (true) {
            int i = text.indexOf(stem, from);
            if (i < 0) {
                return -1;
            }
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) {
                return i;
            }
            from = i + 1;
        }
    }
}
