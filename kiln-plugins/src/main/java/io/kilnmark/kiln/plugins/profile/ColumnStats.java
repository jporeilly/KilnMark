package io.kilnmark.kiln.plugins.profile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.datasketches.frequencies.ErrorType;
import org.apache.datasketches.frequencies.FrequentItemsSketch;
import org.apache.datasketches.frequencies.FrequentLongsSketch;
import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.HllUnion;
import org.apache.datasketches.hll.TgtHllType;
import org.apache.datasketches.kll.KllDoublesSketch;

/**
 * One column's profile from mergeable sketches: distinct count (HyperLogLog), quantiles (KLL) and
 * frequent values, plus exact counts, nulls, lengths and sums. Its size stays a few kilobytes
 * whatever the row count, and profiles of separate partitions merge into one.
 */
final class ColumnStats {

    /** 2^14 buckets: exact up to about 12,000 distinct values, about 0.8% error beyond. */
    static final int LG_K = 14;
    static final int TOP = 5;

    enum Shape { NUMBER, INTEGER, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP, BINARY }

    final String field;
    final Shape shape;
    long rows;
    long nulls;
    long trues;
    double sum;
    int minLength = Integer.MAX_VALUE;
    int maxLength;
    private HllSketch distinct = new HllSketch(LG_K, TgtHllType.HLL_8);
    private final KllDoublesSketch quantiles = KllDoublesSketch.newHeapInstance(200);
    private final FrequentItemsSketch<String> topText = new FrequentItemsSketch<>(64);
    private final FrequentLongsSketch topLongs = new FrequentLongsSketch(64);

    ColumnStats(String field, Shape shape) {
        this(field, shape, true, PatternProfile.DEFAULT_EXAMPLES);
    }

    /**
     * @param keepValues false to keep no values at all: no example values, and no most common
     *                   values for text columns (a source with {@code keep_samples} off)
     * @param examples   example values kept per value shape
     */
    ColumnStats(String field, Shape shape, boolean keepValues, int examples) {
        this.field = field;
        this.shape = shape;
        this.keepValues = keepValues;
        this.patterns = shape == Shape.TEXT ? new PatternProfile(keepValues, examples) : null;
    }

    // Data quality additions: value shapes and formats (text), negatives (numbers).
    private final boolean keepValues;
    final PatternProfile patterns;
    long negatives;

    void addNull() {
        rows++;
        nulls++;
    }

    void addLong(long v) {
        rows++;
        if (v < 0) {
            negatives++;
        }
        distinct.update(v);
        quantiles.update(v);
        sum += v;
        if (shape == Shape.INTEGER) {
            topLongs.update(v);
        }
    }

    void addDouble(double v) {
        rows++;
        if (v < 0) {
            negatives++;
        }
        distinct.update(v);
        quantiles.update(v);
        sum += v;
    }

    void addBoolean(boolean v) {
        rows++;
        distinct.update(v ? 1L : 0L);
        if (v) {
            trues++;
        }
    }

    void addText(byte[] utf8) {
        rows++;
        distinct.update(utf8);
        String s = new String(utf8, StandardCharsets.UTF_8);
        int length = s.codePointCount(0, s.length());
        minLength = Math.min(minLength, length);
        maxLength = Math.max(maxLength, length);
        topText.update(s);
        if (patterns != null) {
            patterns.add(s);
        }
    }

    void addBinary(byte[] bytes) {
        rows++;
        distinct.update(bytes);
        minLength = Math.min(minLength, bytes.length);
        maxLength = Math.max(maxLength, bytes.length);
    }

    void merge(ColumnStats other) {
        rows += other.rows;
        nulls += other.nulls;
        trues += other.trues;
        sum += other.sum;
        minLength = Math.min(minLength, other.minLength);
        maxLength = Math.max(maxLength, other.maxLength);
        HllUnion union = new HllUnion(LG_K);
        union.update(distinct);
        union.update(other.distinct);
        distinct = union.getResult(TgtHllType.HLL_8);
        if (!other.quantiles.isEmpty()) {
            quantiles.merge(other.quantiles);
        }
        topText.merge(other.topText);
        topLongs.merge(other.topLongs);
        negatives += other.negatives;
        if (patterns != null && other.patterns != null) {
            patterns.merge(other.patterns);
        }
    }

    long values() {
        return rows - nulls;
    }

    /** The profile as the attributes of a PROFILE event. Values are text; lists are JSON. */
    Map<String, String> attributes() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("field", field);
        a.put("shape", shape.name().toLowerCase(java.util.Locale.ROOT));
        a.put("rows", Long.toString(rows));
        a.put("nulls", Long.toString(nulls));
        if (values() == 0) {
            a.put("distinct", "0");
            return a;
        }
        long d = Math.min(Math.round(distinct.getEstimate()), values());
        a.put("distinct", Long.toString(d));
        // Below a few thousand values the sketch keeps every hash, and its bounds meet the estimate.
        a.put("distinct_exact", Boolean.toString(
            Math.round(distinct.getLowerBound(1)) == Math.round(distinct.getUpperBound(1))));
        switch (shape) {
            case NUMBER, INTEGER -> {
                a.put("min", number(quantiles.getMinItem()));
                a.put("p25", number(quantiles.getQuantile(0.25)));
                a.put("median", number(quantiles.getQuantile(0.5)));
                a.put("p75", number(quantiles.getQuantile(0.75)));
                a.put("max", number(quantiles.getMaxItem()));
                a.put("mean", number(sum / values()));
                if (shape == Shape.INTEGER) {
                    a.put("top", topLongs());
                }
            }
            case DATE, TIME, TIMESTAMP -> {
                a.put("min", temporal(quantiles.getMinItem()));
                a.put("median", temporal(quantiles.getQuantile(0.5)));
                a.put("max", temporal(quantiles.getMaxItem()));
            }
            case TEXT -> {
                a.put("min_length", Integer.toString(minLength));
                a.put("max_length", Integer.toString(maxLength));
                a.put("top", topText());
            }
            case BINARY -> {
                a.put("min_length", Integer.toString(minLength));
                a.put("max_length", Integer.toString(maxLength));
            }
            case BOOLEAN -> a.put("true", Long.toString(trues));
        }
        quality(a);
        return a;
    }

    /** Quantiles every 5%, for drift and for OpenLineage's {@code quantiles}. */
    static final int QUANTILE_STEPS = 20;

    /**
     * What data quality needs beyond the profile, from the same sketches with no extra pass:
     * <ul>
     * <li>numbers: {@code sum}, {@code negatives}, {@code quantiles} (JSON array of 21, every 5%),
     * Tukey fences {@code fence_low} and {@code fence_high} (1.5 interquartile ranges beyond the
     * quartiles) and the estimated count of {@code outliers} beyond them;</li>
     * <li>dates and timestamps: {@code quantiles} (as days or microseconds since 1970) and
     * {@code after_now}, the estimated count of values later than the end of the read;</li>
     * <li>text: shapes and formats ({@link PatternProfile#attributes}).</li>
     * </ul>
     * With values not kept, most common values are left out.
     */
    private void quality(Map<String, String> a) {
        switch (shape) {
            case NUMBER, INTEGER -> {
                a.put("sum", number(sum));
                a.put("negatives", Long.toString(negatives));
                a.put("quantiles", quantilesJson());
                double q1 = quantiles.getQuantile(0.25);
                double q3 = quantiles.getQuantile(0.75);
                double low = q1 - 1.5 * (q3 - q1);
                double high = q3 + 1.5 * (q3 - q1);
                a.put("fence_low", number(low));
                a.put("fence_high", number(high));
                double below = quantiles.getRank(low, org.apache.datasketches.quantilescommon.QuantileSearchCriteria.EXCLUSIVE);
                double above = 1 - quantiles.getRank(high, org.apache.datasketches.quantilescommon.QuantileSearchCriteria.INCLUSIVE);
                a.put("outliers", Long.toString(Math.round((below + above) * values())));
            }
            case DATE, TIMESTAMP -> {
                a.put("quantiles", quantilesJson());
                double now = shape == Shape.DATE ? java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()
                    : java.time.Instant.now().toEpochMilli() * 1000.0;
                double later = 1 - quantiles.getRank(now, org.apache.datasketches.quantilescommon.QuantileSearchCriteria.INCLUSIVE);
                a.put("after_now", Long.toString(Math.round(later * values())));
            }
            case TEXT -> patterns.attributes(a);
            default -> { }
        }
        if (!keepValues) {
            a.remove("top");
        }
    }

    private String quantilesJson() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i <= QUANTILE_STEPS; i++) {
            b.append(i == 0 ? "" : ",").append(number(quantiles.getQuantile((double) i / QUANTILE_STEPS)));
        }
        return b.append(']').toString();
    }

    private String topText() {
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (FrequentItemsSketch.Row<String> r : topText.getFrequentItems(ErrorType.NO_FALSE_POSITIVES)) {
            if (n == TOP || !repeated(r.getEstimate())) {
                break;
            }
            b.append(n++ == 0 ? "" : ",").append("{\"value\":").append(Json.string(r.getItem()))
                .append(",\"count\":").append(r.getEstimate()).append('}');
        }
        return b.append(']').toString();
    }

    private String topLongs() {
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (FrequentLongsSketch.Row r : topLongs.getFrequentItems(ErrorType.NO_FALSE_POSITIVES)) {
            if (n == TOP || !repeated(r.getEstimate())) {
                break;
            }
            b.append(n++ == 0 ? "" : ",").append("{\"value\":").append(Json.string(Long.toString(r.getItem())))
                .append(",\"count\":").append(r.getEstimate()).append('}');
        }
        return b.append(']').toString();
    }

    /** A value is worth listing only if it appears in at least 1% of rows and more than once. */
    private boolean repeated(long count) {
        return count > 1 && count * 100 >= values();
    }

    /**
     * A number as text: whole numbers without a fraction, others to at most six decimal places,
     * never in scientific notation. Exact at any magnitude (rounding through a long would cap
     * values above about 9.2e12).
     */
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Double.toString(v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        java.math.BigDecimal d = java.math.BigDecimal.valueOf(v); // the shortest decimal that is v
        if (d.scale() > 6) {
            d = d.setScale(6, java.math.RoundingMode.HALF_EVEN);
        }
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    private String temporal(double v) {
        long n = (long) v;
        return switch (shape) {
            case DATE -> LocalDate.ofEpochDay(n).toString();
            case TIME -> LocalTime.ofNanoOfDay(n * 1_000).toString();
            default -> Instant.ofEpochSecond(Math.floorDiv(n, 1_000_000), Math.floorMod(n, 1_000_000) * 1_000L)
                .toString();
        };
    }
}
