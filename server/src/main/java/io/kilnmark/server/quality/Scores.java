package io.kilnmark.server.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scores per column and dimension, rolled up to the asset, with the reasons for each deduction.
 * They start from the automatic measures; a rule's latest result lowers its column's score in the
 * rule's dimension to the share of rows that passed; drift lowers the score of the dimension it
 * touches (by 5%, 15% or 30% for low, medium and high).
 */
public final class Scores {

    /** The asset as a whole in {@link Score#field()}; the mean over dimensions in {@link Score#dimension()}. */
    public static final String ASSET = "";
    public static final String OVERALL = "overall";

    public record Score(String field, String dimension, double score, List<String> reasons) {
    }

    /** A rule's latest result, as scoring needs it. */
    public record RuleResult(String field, Dimension dimension, String ruleName, Rule.Status status, long failing,
                             long total, boolean wholeAsset) {
    }

    private Scores() {
    }

    private static final class Cell {
        double score = 1;
        final List<String> reasons = new ArrayList<>();
    }

    public static List<Score> of(List<Measures.Measure> measures, List<RuleResult> results, List<Drift.Event> drift) {
        Map<String, Map<String, Cell>> cells = new TreeMap<>();
        for (Measures.Measure m : measures) {
            Cell c = cell(cells, m.field(), m.dimension().id());
            c.score = Math.min(c.score, m.score());
            if (m.score() < 1) {
                c.reasons.add(m.reason());
            }
        }
        for (RuleResult r : results) {
            if (r.status() == Rule.Status.ERROR) {
                continue;
            }
            double passing = r.wholeAsset() ? (r.status() == Rule.Status.FAIL ? 0 : 1)
                : r.total() <= 0 ? 1 : 1 - (double) r.failing() / r.total();
            Cell c = cell(cells, r.field() == null ? ASSET : r.field(), r.dimension().id());
            c.score = Math.min(c.score, passing);
            if (r.status() != Rule.Status.PASS) {
                c.reasons.add("Rule '" + r.ruleName() + "' " + (r.status() == Rule.Status.FAIL ? "fails" : "warns")
                    + (r.wholeAsset() ? "" : ": " + Measures.count(r.failing()) + " of " + Measures.count(r.total())
                    + " rows"));
            }
        }
        for (Drift.Event e : drift) {
            Dimension d = dimension(e.change());
            double factor = switch (e.severity()) {
                case "high" -> 0.7;
                case "medium" -> 0.85;
                default -> 0.95;
            };
            Cell c = cell(cells, e.field() == null ? ASSET : e.field(), d.id());
            c.score *= factor;
            c.reasons.add("Drift: " + e.message());
        }

        List<Score> out = new ArrayList<>();
        Map<String, List<Double>> byDimension = new LinkedHashMap<>();
        Map<String, List<String>> reasonsByDimension = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Cell>> field : cells.entrySet()) {
            double sum = 0;
            List<String> reasons = new ArrayList<>();
            for (Map.Entry<String, Cell> dim : field.getValue().entrySet()) {
                Cell c = dim.getValue();
                double s = Measures.round(c.score);
                if (!field.getKey().equals(ASSET)) {
                    out.add(new Score(field.getKey(), dim.getKey(), s, List.copyOf(c.reasons)));
                }
                sum += s;
                byDimension.computeIfAbsent(dim.getKey(), k -> new ArrayList<>()).add(s);
                List<String> rs = reasonsByDimension.computeIfAbsent(dim.getKey(), k -> new ArrayList<>());
                for (String r : c.reasons) {
                    rs.add(field.getKey().equals(ASSET) ? r : field.getKey() + ": " + r);
                }
                reasons.addAll(c.reasons);
            }
            if (!field.getKey().equals(ASSET)) {
                out.add(new Score(field.getKey(), OVERALL, Measures.round(sum / field.getValue().size()),
                    reasons.stream().limit(3).toList()));
            }
        }
        List<Score> assetDims = new ArrayList<>();
        for (Map.Entry<String, List<Double>> d : byDimension.entrySet()) {
            double mean = d.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(1);
            assetDims.add(new Score(ASSET, d.getKey(), Measures.round(mean),
                reasonsByDimension.get(d.getKey()).stream().limit(5).toList()));
        }
        out.addAll(assetDims);
        if (!assetDims.isEmpty()) {
            double overall = assetDims.stream().mapToDouble(Score::score).average().orElse(1);
            List<String> reasons = assetDims.stream().filter(s -> s.score() < 1)
                .sorted(Comparator.comparingDouble(Score::score))
                .limit(3).map(s -> Dimension.parse(s.dimension()).label() + " " + Math.round(s.score() * 100) + "%"
                    + (s.reasons().isEmpty() ? "" : ": " + s.reasons().get(0)))
                .toList();
            out.add(new Score(ASSET, OVERALL, Measures.round(overall), reasons));
        }
        return out;
    }

    private static Cell cell(Map<String, Map<String, Cell>> cells, String field, String dimension) {
        return cells.computeIfAbsent(field, k -> new TreeMap<>()).computeIfAbsent(dimension, k -> new Cell());
    }

    /** The dimension a kind of drift counts against. */
    static Dimension dimension(String change) {
        return switch (change) {
            case "null_rate" -> Dimension.COMPLETENESS;
            case "distinct_count" -> Dimension.UNIQUENESS;
            case "pattern_share" -> Dimension.VALIDITY;
            case "quantile_shift", "top_values" -> Dimension.ACCURACY;
            case "row_count" -> Dimension.VOLUME;
            case "no_new_data" -> Dimension.TIMELINESS;
            default -> Dimension.CONSISTENCY; // schema changes
        };
    }
}
