package io.kilnmark.kiln.plugins.relate;

import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.HllUnion;
import org.apache.datasketches.hll.TgtHllType;

/**
 * How far the distinct values of column A lie inside those of column B, estimated from their
 * HyperLogLog sketches alone: {@code |A ∩ B| = |A| + |B| - |A ∪ B|}, so A is inside B when
 * {@code |A ∪ B| ≈ |B|}.
 *
 * @param fraction  the estimated share of A's distinct values found in B, 0 to 1
 * @param margin    roughly how far {@code fraction} may be off (two standard deviations); large
 *                  when A is small next to B, since the error scales with {@code |B|}
 * @param fromCount A's estimated distinct values
 * @param toCount   B's estimated distinct values
 * @param union     the estimated distinct values of both together
 */
public record Containment(double fraction, double margin, double fromCount, double toCount, double union) {

    /** lgK of the merged sketch: as large as the profiles' own (14). */
    static final int LG_K = 14;

    /** True when the estimate is precise enough to count as evidence. */
    public boolean conclusive() {
        return margin <= 0.25;
    }

    /**
     * A's containment in B from serialised sketches (as {@code ProfileStep} stores them).
     *
     * @throws IllegalArgumentException if either is not a HyperLogLog sketch
     */
    public static Containment of(byte[] from, byte[] to) {
        HllSketch a = read(from);
        HllSketch b = read(to);
        HllUnion u = new HllUnion(LG_K);
        u.update(a);
        u.update(b);
        HllSketch both = u.getResult(TgtHllType.HLL_8);
        double na = a.getEstimate();
        double nb = b.getEstimate();
        double nu = both.getEstimate();
        if (na <= 0) {
            return new Containment(0, 1, 0, nb, nu);
        }
        double fraction = Math.max(0, Math.min(1, (na + nb - nu) / na));
        double spread = (a.getUpperBound(2) - a.getLowerBound(2) + b.getUpperBound(2) - b.getLowerBound(2)
            + both.getUpperBound(2) - both.getLowerBound(2)) / 2;
        return new Containment(fraction, spread / na, na, nb, nu);
    }

    private static HllSketch read(byte[] bytes) {
        try {
            return HllSketch.heapify(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a HyperLogLog sketch: " + e.getMessage(), e);
        }
    }
}
