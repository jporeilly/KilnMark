package io.kilnmark.server.ai.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Scores for the AI evaluation: search ranking (recall at k, reciprocal rank) and plan matching. */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** Share of the relevant assets found in the first {@code k} results. */
    public static double recallAt(List<String> ranked, Collection<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 1;
        }
        Set<String> top = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        return relevant.stream().filter(top::contains).count() / (double) relevant.size();
    }

    /** 1 / the rank of the first relevant result, or 0 when none is found. */
    public static double reciprocalRank(List<String> ranked, Collection<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /** 1 when two lists hold the same values (ignoring case and order), else 0. */
    public static double sameSet(Collection<String> expected, Collection<String> actual) {
        return lower(expected).equals(lower(actual)) ? 1 : 0;
    }

    private static Set<String> lower(Collection<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    public static double mean(Collection<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }
}
