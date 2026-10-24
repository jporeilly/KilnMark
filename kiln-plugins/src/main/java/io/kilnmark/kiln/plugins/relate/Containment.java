package io.kilnmark.kiln.plugins.relate;

import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.HllUnion;
import org.apache.datasketches.hll.TgtHllType;

/**
 * How far the distinct values of column A lie inside those of column B, estimated from their
 * HyperLogLog sketches alone: {@code |A ∩ B| = |A| + |B| - |A ∪ B|}, so A is inside B when
 * {@code |A ∪ B| ≈ |B|}.
 *
 * <p>All three counts are the sketches' composite estimates. Mixing estimators (the HIP estimate a
 * sketch keeps while it is built, against the composite estimate of a union made later) biases
 * {@code |A ∪ B| - |B|} by a constant several hundred values wide on a large B, which hides real
 * keys when A is small. With one estimator, a true subset leaves B's buckets unchanged, so
 * {@code |A ∪ B| = |B|} exactly and the fraction is 1.
 *
 * @param fraction  the estimated share of A's distinct values found in B, 0 to 1
 * @param margin    roughly how far {@code fraction} may be off: about 10% of the values A adds to
 *                  B (the error of a union's growth, measured), two standard errors of A's own
 *                  count, and B's resolution ({@link #RESOLUTION} values per bucket), relative to A.
 *                  Large when A is small next to B.
 * @param fromCount A's estimated distinct values
 * @param toCount   B's estimated distinct values
 * @param union     the estimated distinct values of both together
 */
public record Containment(double fraction, double margin, double fromCount, double toCount, double union) {

    /** lgK of the merged sketch: as large as the profiles' own (14). */
    static final int LG_K = 14;

    /** Relative standard error of an HLL count at lgK 14: 1.04 / sqrt(2^14). */
    static final double RSE = 1.04 / Math.sqrt(1 << LG_K);

    /**
     * B's resolution, in values per bucket. A small A outside a large B changes almost none of B's
     * {@code 2^14} buckets, so the union looks like B and the fraction comes out near 1; this term
     * makes such estimates inconclusive. Tuned by simulation: disjoint sets of 500 against a million,
     * or 20 against 100,000, are inconclusive; true subsets of 1,000 in a million, or 2% of 100,000,
     * stay conclusive.
     */
    static final double RESOLUTION = 3.0;

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
        double na = a.getCompositeEstimate();
        double nb = b.getCompositeEstimate();
        double nu = both.getCompositeEstimate();
        if (na <= 0) {
            return new Containment(0, 1, 0, nb, nu);
        }
        double fraction = Math.max(0, Math.min(1, (na + nb - nu) / na));
        double added = Math.max(0, nu - nb);
        double margin = Math.min(1, (0.1 * added + 2 * RSE * na + RESOLUTION * nb / (1 << LG_K)) / na);
        return new Containment(fraction, margin, na, nb, nu);
    }

    private static HllSketch read(byte[] bytes) {
        try {
            return HllSketch.heapify(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a HyperLogLog sketch: " + e.getMessage(), e);
        }
    }
}
