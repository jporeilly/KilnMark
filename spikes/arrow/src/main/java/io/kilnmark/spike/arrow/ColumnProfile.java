package io.kilnmark.spike.arrow;

import java.nio.charset.StandardCharsets;

import org.apache.datasketches.common.ArrayOfStringsSerDe;
import org.apache.datasketches.frequencies.ErrorType;
import org.apache.datasketches.frequencies.FrequentItemsSketch;
import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.TgtHllType;
import org.apache.datasketches.hll.HllUnion;
import org.apache.datasketches.kll.KllDoublesSketch;

/** One column's profile, built from mergeable sketches so partitions can be profiled apart and combined. */
final class ColumnProfile {

    static final int LG_K = 12;

    enum Kind { NUMBER, TEXT, TIME, OTHER }

    final String name;
    final Kind kind;
    long count;
    long nulls;
    HllSketch distinct = new HllSketch(LG_K, TgtHllType.HLL_8);
    final KllDoublesSketch quantiles = KllDoublesSketch.newHeapInstance(200);
    final FrequentItemsSketch<String> top = new FrequentItemsSketch<>(64);
    int minLength = Integer.MAX_VALUE;
    int maxLength;

    ColumnProfile(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    void addNull() {
        count++;
        nulls++;
    }

    void addLong(long v) {
        count++;
        distinct.update(v);
        quantiles.update(v);
    }

    void addDouble(double v) {
        count++;
        distinct.update(v);
        quantiles.update(v);
    }

    void addText(byte[] utf8) {
        count++;
        distinct.update(utf8);
        top.update(new String(utf8, StandardCharsets.UTF_8));
        minLength = Math.min(minLength, utf8.length);
        maxLength = Math.max(maxLength, utf8.length);
    }

    void addText(String s) {
        addText(s.getBytes(StandardCharsets.UTF_8));
    }

    void merge(ColumnProfile other) {
        count += other.count;
        nulls += other.nulls;
        HllUnion union = new HllUnion(LG_K);
        union.update(distinct);
        union.update(other.distinct);
        distinct = union.getResult(TgtHllType.HLL_8);
        if (!other.quantiles.isEmpty()) {
            quantiles.merge(other.quantiles);
        }
        top.merge(other.top);
        minLength = Math.min(minLength, other.minLength);
        maxLength = Math.max(maxLength, other.maxLength);
    }

    /** Serialized size, to show a profile stays small whatever the row count. */
    int bytes() {
        return distinct.toCompactByteArray().length + quantiles.toByteArray().length
            + top.toByteArray(new ArrayOfStringsSerDe()).length;
    }

    String summary() {
        StringBuilder b = new StringBuilder();
        b.append(String.format("%-11s rows=%d nulls=%d distinct~%.0f", name, count, nulls, distinct.getEstimate()));
        if (kind != Kind.TEXT && !quantiles.isEmpty()) {
            b.append(String.format(" min=%.2f p50=%.2f p99=%.2f max=%.2f", quantiles.getMinItem(),
                quantiles.getQuantile(0.5), quantiles.getQuantile(0.99), quantiles.getMaxItem()));
        }
        if (kind == Kind.TEXT && count > nulls) {
            FrequentItemsSketch.Row<String>[] rows = top.getFrequentItems(ErrorType.NO_FALSE_POSITIVES);
            b.append(String.format(" len=%d..%d top=%s", minLength, maxLength,
                rows.length == 0 ? "-" : rows[0].getItem() + "~" + rows[0].getEstimate()));
        }
        b.append(String.format(" sketch=%dB", bytes()));
        return b.toString();
    }
}
