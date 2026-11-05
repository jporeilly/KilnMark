package io.kilnmark.kiln.plugins.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.datasketches.frequencies.ErrorType;
import org.apache.datasketches.frequencies.FrequentItemsSketch;
import org.apache.datasketches.hash.MurmurHash3;

/**
 * Value shapes and formats of one text column, over every value: a frequent-items sketch of shapes
 * (and of collapsed shapes), counts per detected {@link Formats.Format}, blank values, and a few
 * example values per shape.
 *
 * <p>Examples are chosen by the hash of the value (the smallest {@value #DEFAULT_EXAMPLES} hashes
 * per shape), so the same values are chosen on every scan whatever order rows arrive in. They are
 * real data: they are kept only when {@code keepValues} is on, and reported apart from the profile
 * (in the {@code SAMPLE} event), so they can be stored under the same controls as row samples.
 */
@SuppressWarnings("EnumOrdinal") // counts per format in an array by ordinal: no map in the profiling loop
final class PatternProfile {

    /** Shapes reported, with counts; the rest are counted together. */
    static final int TOP = 20;
    static final int DEFAULT_EXAMPLES = 5;
    /** Shapes that get their own examples; later shapes share one pool. */
    static final int SHAPES_WITH_EXAMPLES = 256;
    /** Longest example kept, in characters. */
    static final int EXAMPLE_LENGTH = 200;
    /** Detailed shapes are reported only if their top shapes cover this share of values; else collapsed ones. */
    static final double DETAILED_COVERAGE = 0.8;
    private static final long SEED = 0x4B4D5348L;

    private final FrequentItemsSketch<String> shapes = new FrequentItemsSketch<>(128);
    private final FrequentItemsSketch<String> collapsed = new FrequentItemsSketch<>(128);
    private final long[] formats = new long[Formats.Format.values().length];
    private final boolean keepValues;
    private final int exampleCount;
    private final Map<String, Examples> examples = new HashMap<>();
    private final Map<String, Examples> collapsedExamples = new HashMap<>();
    private final Examples overflow;
    long values;
    long blanks;

    PatternProfile(boolean keepValues, int exampleCount) {
        this.keepValues = keepValues;
        this.exampleCount = Math.max(0, exampleCount);
        this.overflow = new Examples(this.exampleCount);
    }

    void add(String value) {
        values++;
        if (value.isBlank()) {
            blanks++;
        }
        String shape = ValueShape.of(value);
        String c = ValueShape.collapse(shape);
        shapes.update(shape);
        collapsed.update(c);
        int found = Formats.detect(value);
        if (found != 0) {
            for (Formats.Format f : Formats.Format.values()) {
                if ((found & f.bit()) != 0) {
                    formats[f.ordinal()]++;
                }
            }
        }
        if (keepValues && exampleCount > 0) {
            String example = value.length() > EXAMPLE_LENGTH ? value.substring(0, EXAMPLE_LENGTH) + "…" : value;
            // MurmurHash3 refuses an empty array: an empty string gets a fixed hash of its own
            long hash = example.isEmpty() ? 0x656D707479L : MurmurHash3.hash(example.getBytes(StandardCharsets.UTF_8), SEED)[0];
            offer(examples, shape, example, hash);
            offer(collapsedExamples, c, example, hash);
        }
    }

    private void offer(Map<String, Examples> into, String shape, String value, long hash) {
        Examples e = into.get(shape);
        if (e == null) {
            if (into.size() >= SHAPES_WITH_EXAMPLES) {
                overflow.offer(value, hash);
                return;
            }
            e = new Examples(exampleCount);
            into.put(shape, e);
        }
        e.offer(value, hash);
    }

    void merge(PatternProfile other) {
        values += other.values;
        blanks += other.blanks;
        shapes.merge(other.shapes);
        collapsed.merge(other.collapsed);
        for (int i = 0; i < formats.length; i++) {
            formats[i] += other.formats[i];
        }
        other.examples.forEach((k, v) -> v.entries.forEach((h, s) -> offer(examples, k, s, h)));
        other.collapsedExamples.forEach((k, v) -> v.entries.forEach((h, s) -> offer(collapsedExamples, k, s, h)));
        other.overflow.entries.forEach((h, s) -> overflow.offer(s, h));
    }

    /** True when the detailed shapes are reported; false when collapsed shapes are, for free text. */
    boolean detailed() {
        return covered(shapes) >= DETAILED_COVERAGE * values;
    }

    private static long covered(FrequentItemsSketch<String> sketch) {
        long n = 0;
        int i = 0;
        for (FrequentItemsSketch.Row<String> r : sketch.getFrequentItems(ErrorType.NO_FALSE_NEGATIVES)) {
            if (i++ == TOP) {
                break;
            }
            n += r.getEstimate();
        }
        return n;
    }

    private List<FrequentItemsSketch.Row<String>> top(FrequentItemsSketch<String> sketch) {
        List<FrequentItemsSketch.Row<String>> out = new ArrayList<>();
        for (FrequentItemsSketch.Row<String> r : sketch.getFrequentItems(ErrorType.NO_FALSE_NEGATIVES)) {
            if (out.size() == TOP) {
                break;
            }
            out.add(r);
        }
        return out;
    }

    /**
     * Adds the profile attributes: {@code blanks}, {@code patterns} (JSON array of
     * {@code {"shape","count"}}, most frequent first), {@code patterns_other} (values in no listed
     * shape), {@code patterns_exact}, {@code patterns_collapsed} and {@code formats} (JSON array of
     * {@code {"format","count"}} for formats found at least once).
     */
    void attributes(Map<String, String> a) {
        a.put("blanks", Long.toString(blanks));
        boolean detailed = detailed();
        FrequentItemsSketch<String> sketch = detailed ? shapes : collapsed;
        StringBuilder b = new StringBuilder("[");
        long listed = 0;
        int n = 0;
        for (FrequentItemsSketch.Row<String> r : top(sketch)) {
            b.append(n++ == 0 ? "" : ",").append("{\"shape\":").append(Json.string(r.getItem()))
                .append(",\"count\":").append(r.getEstimate()).append('}');
            listed += r.getEstimate();
        }
        a.put("patterns", b.append(']').toString());
        a.put("patterns_other", Long.toString(Math.max(0, values - listed)));
        a.put("patterns_exact", Boolean.toString(sketch.getMaximumError() == 0));
        a.put("patterns_collapsed", Boolean.toString(!detailed));
        StringBuilder f = new StringBuilder("[");
        int k = 0;
        for (Formats.Format format : Formats.Format.values()) {
            long count = formats[format.ordinal()];
            if (count > 0) {
                f.append(k++ == 0 ? "" : ",").append("{\"format\":\"").append(format.id()).append("\",\"count\":")
                    .append(count).append('}');
            }
        }
        a.put("formats", f.append(']').toString());
    }

    long format(Formats.Format f) {
        return formats[f.ordinal()];
    }

    /**
     * Examples for the reported shapes as JSON: {@code {"shape": ["value", ...], ...}}, plus
     * {@code "~other"} with values from shapes outside the reported ones. Null when values are not kept.
     */
    String examplesJson() {
        if (!keepValues || exampleCount == 0) {
            return null;
        }
        boolean detailed = detailed();
        Map<String, Examples> source = detailed ? examples : collapsedExamples;
        List<FrequentItemsSketch.Row<String>> top = top(detailed ? shapes : collapsed);
        Map<String, List<String>> out = new LinkedHashMap<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (FrequentItemsSketch.Row<String> r : top) {
            listed.add(r.getItem());
            Examples e = source.get(r.getItem());
            if (e != null) {
                out.put(r.getItem(), e.values());
            }
        }
        Examples other = new Examples(exampleCount);
        source.forEach((shape, e) -> {
            if (!listed.contains(shape)) {
                e.entries.forEach((h, s) -> other.offer(s, h));
            }
        });
        overflow.entries.forEach((h, s) -> other.offer(s, h));
        if (!other.entries.isEmpty()) {
            out.put("~other", other.values());
        }
        StringBuilder b = new StringBuilder("{");
        int n = 0;
        for (Map.Entry<String, List<String>> e : out.entrySet()) {
            b.append(n++ == 0 ? "" : ",").append(Json.string(e.getKey())).append(":[");
            int i = 0;
            for (String v : e.getValue()) {
                b.append(i++ == 0 ? "" : ",").append(Json.string(v));
            }
            b.append(']');
        }
        return b.append('}').toString();
    }

    /** The values with the smallest hashes, one per distinct value. */
    static final class Examples {
        private final int k;
        final TreeMap<Long, String> entries = new TreeMap<>();

        Examples(int k) {
            this.k = k;
        }

        void offer(String value, long hash) {
            if (k == 0 || entries.containsKey(hash)) {
                return;
            }
            if (entries.size() < k) {
                entries.put(hash, value);
            } else if (hash < entries.lastKey()) {
                entries.pollLastEntry();
                entries.put(hash, value);
            }
        }

        List<String> values() {
            return List.copyOf(entries.values());
        }
    }
}
