package io.kilnmark.kiln.plugins.relate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Proposes relationships no source declares: a column whose name says it refers to another
 * table's key ({@code customer_id} to {@code customers.id} or {@code customers.customer_id}), of a
 * compatible type, where the other column is unique, and whose values lie inside the other's as
 * far as their HyperLogLog sketches can tell. The result is a list of proposals with their
 * evidence, for a person to accept or reject; nothing is applied.
 *
 * <p>The work is bounded: only key-like columns are targets (a single-column primary key, or an
 * identifier-like name whose distinct count equals its rows), candidates come only from name
 * rules (never every pair of columns), at most {@link Limits#maxPairs()} pairs are scored, and at
 * most {@link Limits#perColumn()} proposals are kept for one column.
 */
public final class KeyInference {

    /** A column as the inference sees it: where it is, its type, and its latest profile counts. */
    public record Column(String assetUrn, String source, String assetName, String field, String kind,
                         boolean primaryKey, boolean profiled, long rows, long nulls, long distinct) {

        public Column {
            Objects.requireNonNull(assetUrn, "assetUrn");
            Objects.requireNonNull(field, "field");
        }

        public ColumnRef ref() {
            return new ColumnRef(assetUrn, field);
        }

        long values() {
            return rows - nulls;
        }
    }

    /** One column of one asset. */
    public record ColumnRef(String assetUrn, String field) {
    }

    /** Where sketches come from: loaded only for the columns of candidate pairs, in one go. */
    @FunctionalInterface
    public interface Sketches {
        /** The serialised sketch of each column that has one; columns without one are left out. */
        Map<ColumnRef, byte[]> load(Set<ColumnRef> columns);
    }

    /**
     * Bounds on the work.
     *
     * @param maxPairs      candidate pairs scored at most; the rest are skipped and counted
     * @param perColumn     proposals kept for one referring column at most (the strongest)
     * @param minConfidence proposals weaker than this are dropped
     */
    public record Limits(int maxPairs, int perColumn, double minConfidence) {
        public static final Limits DEFAULT = new Limits(5_000, 3, 0.75);
    }

    /** Why a pair was proposed. {@code containment} is null when the sketches could not decide. */
    public record Evidence(String rule, double nameScore, Double containment, Double containmentMargin,
                           long fromDistinct, long toDistinct, long toRows, String unique, String fromType,
                           String toType) {
    }

    /** A proposed relationship from one column to a key column, with its confidence (0 to 0.99). */
    public record Proposal(Column from, Column to, double confidence, Evidence evidence) {
    }

    /** What a run found, and how many candidate pairs were skipped by {@link Limits#maxPairs()}. */
    public record Result(List<Proposal> proposals, int pairsScored, int pairsSkipped) {
    }

    /** Name rules, strongest first. */
    enum Rule {
        TABLE_ID("table name and id", 1.0),
        SAME_NAME_AS_TABLE_KEY("same name as the table's key", 1.0),
        PREFIXED("table name and id, with a prefix", 0.8),
        SAME_NAME("same column name", 0.7);

        final String label;
        final double score;

        Rule(String label, double score) {
            this.label = label;
            this.score = score;
        }
    }

    /** Containment (after the small-sample discount) a proposal needs to score above 0.7. */
    static final double STRONG_CONTAINMENT = 0.9;

    private final Limits limits;

    public KeyInference(Limits limits) {
        this.limits = limits;
    }

    /**
     * Proposals among {@code columns} (from any number of sources) where at least one end
     * satisfies {@code inScope}, usually "belongs to the source just scanned".
     */
    public Result infer(List<Column> columns, Predicate<Column> inScope, Sketches sketches) {
        List<Column> targets = columns.stream().filter(KeyInference::keyLike).toList();
        Map<String, List<Column>> byName = new HashMap<>();
        Map<String, List<Column>> byEntity = new HashMap<>();
        for (Column t : targets) {
            byName.computeIfAbsent(KeyNames.normal(t.field()), k -> new ArrayList<>()).add(t);
            byEntity.computeIfAbsent(KeyNames.entity(t.assetName()), k -> new ArrayList<>()).add(t);
        }

        // 1. Candidates from name rules only, best rule per pair.
        Map<List<ColumnRef>, Candidate> candidates = new LinkedHashMap<>();
        for (Column from : columns) {
            String name = KeyNames.normal(from.field());
            String suffix = KeyNames.suffix(name);
            if (suffix == null || name.equals(suffix) || family(from.kind()) == null) {
                continue; // not an identifier, or a bare "id": a table's own key refers to nothing
            }
            String stem = KeyNames.singular(name.substring(0, name.length() - suffix.length()));
            for (Column to : byEntity.getOrDefault(stem, List.of())) {
                String toName = KeyNames.normal(to.field());
                if (toName.equals(suffix) || toName.equals("id")) {
                    consider(candidates, from, to, Rule.TABLE_ID, inScope);
                } else if (toName.equals(name)) {
                    consider(candidates, from, to, Rule.SAME_NAME_AS_TABLE_KEY, inScope);
                }
            }
            // A prefix before the table name, on word boundaries: billing_customer_id, parentCustomerId.
            // Only whole words count, so account_id is never "a" + "count" and address_id never "ad" + "dress".
            List<String> words = KeyNames.words(from.field());
            if (words.size() >= 3 && KeyNames.SUFFIXES.contains(words.get(words.size() - 1))) {
                String word = words.get(words.size() - 1);
                List<String> stemWords = words.subList(0, words.size() - 1);
                for (int k = 1; k < stemWords.size(); k++) {
                    String tail = KeyNames.singular(String.join("", stemWords.subList(k, stemWords.size())));
                    for (Column to : byEntity.getOrDefault(tail, List.of())) {
                        String toName = KeyNames.normal(to.field());
                        if (toName.equals("id") || toName.equals(word) || toName.equals(tail + word)) {
                            consider(candidates, from, to, Rule.PREFIXED, inScope);
                        }
                    }
                }
            }
            for (Column to : byName.getOrDefault(name, List.of())) {
                consider(candidates, from, to, Rule.SAME_NAME, inScope);
            }
        }

        // 2. Bound the work: strongest names first.
        List<Candidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparingDouble((Candidate c) -> -c.rule.score)
            .thenComparing(c -> c.from.assetUrn()).thenComparing(c -> c.from.field())
            .thenComparing(c -> c.to.assetUrn()).thenComparing(c -> c.to.field()));
        int skipped = Math.max(0, ordered.size() - limits.maxPairs());
        if (skipped > 0) {
            ordered = ordered.subList(0, limits.maxPairs());
        }

        // 3. Sketches for the columns involved, then score.
        Set<ColumnRef> wanted = new LinkedHashSet<>();
        for (Candidate c : ordered) {
            wanted.add(c.from.ref());
            wanted.add(c.to.ref());
        }
        Map<ColumnRef, byte[]> loaded = wanted.isEmpty() ? Map.of() : sketches.load(wanted);
        List<Proposal> scored = new ArrayList<>();
        for (Candidate c : ordered) {
            Proposal p = score(c, loaded.get(c.from.ref()), loaded.get(c.to.ref()));
            if (p != null && p.confidence() >= limits.minConfidence()) {
                scored.add(p);
            }
        }
        return new Result(keepBest(withoutReverseDuplicates(scored)), ordered.size(), skipped);
    }

    private record Candidate(Column from, Column to, Rule rule) {
    }

    private static void consider(Map<List<ColumnRef>, Candidate> candidates, Column from, Column to, Rule rule,
                                 Predicate<Column> inScope) {
        if (from.ref().equals(to.ref()) || !(inScope.test(from) || inScope.test(to))) {
            return;
        }
        if (!Objects.equals(family(from.kind()), family(to.kind()))) {
            return;
        }
        // A table's single-column key referring to another table's key of the same name is a copy
        // or a one-to-one extension; still a candidate, judged on its values like any other.
        List<ColumnRef> key = List.of(from.ref(), to.ref());
        Candidate old = candidates.get(key);
        if (old == null || old.rule.score < rule.score) {
            candidates.put(key, new Candidate(from, to, rule));
        }
    }

    /** A column that can be referred to: a single-column primary key, or an identifier with unique values. */
    static boolean keyLike(Column c) {
        if (family(c.kind()) == null) {
            return false;
        }
        if (c.primaryKey()) {
            return true;
        }
        return c.profiled() && c.nulls() == 0 && c.rows() >= 2 && KeyNames.idLike(c.field()) && unique(c);
    }

    /** Distinct values equal to rows, allowing for the sketch's error (under 1% at lgK 14) on large columns. */
    static boolean unique(Column c) {
        long values = c.values();
        if (values <= 0) {
            return false;
        }
        return values < 1_000 ? c.distinct() >= values : c.distinct() >= values * 0.98;
    }

    /** Type families whose sketches hash values alike: whole numbers, other numbers, text. */
    static String family(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "int16", "int32", "int64" -> "integer";
            case "decimal", "float32", "float64" -> "number";
            case "text" -> "text";
            default -> null;
        };
    }

    private static Proposal score(Candidate c, byte[] fromSketch, byte[] toSketch) {
        Column from = c.from;
        Column to = c.to;
        double unique = to.primaryKey() ? 1.0 : 0.9;
        Double fraction = null;
        Double margin = null;
        if (fromSketch != null && toSketch != null) {
            try {
                Containment k = Containment.of(fromSketch, toSketch);
                if (k.conclusive()) {
                    fraction = round(k.fraction());
                    margin = round(k.margin());
                }
            } catch (IllegalArgumentException e) {
                // an unreadable sketch is no evidence either way
            }
        }
        double confidence;
        if (fraction == null) {
            // Names alone never make a strong proposal.
            confidence = 0.5 * c.rule.score * unique;
        } else {
            if (fraction < 0.5) {
                return null; // the values say no
            }
            double values = fraction;
            long distinct = from.distinct();
            if (distinct < 10) {
                values *= distinct / 10.0; // a handful of values fits inside almost anything
            }
            confidence = 0.3 * c.rule.score + 0.1 * unique + 0.6 * values;
            if (values < STRONG_CONTAINMENT) {
                // A join that leaves a tenth of the values without a match is never a strong proposal.
                confidence = Math.min(confidence, 0.7);
            }
        }
        confidence = Math.min(0.99, round(confidence));
        Evidence e = new Evidence(c.rule.label, c.rule.score, fraction, margin, from.distinct(), to.distinct(),
            to.rows(), to.primaryKey() ? "primary key" : "distinct values", from.kind(), to.kind());
        return new Proposal(from, to, confidence, e);
    }

    /** Of A to B and B to A (two copies of one key), keeps the stronger; ties go to the one pointing at a declared key. */
    private static List<Proposal> withoutReverseDuplicates(List<Proposal> in) {
        Map<List<ColumnRef>, Proposal> byPair = new LinkedHashMap<>();
        for (Proposal p : in) {
            byPair.put(List.of(p.from().ref(), p.to().ref()), p);
        }
        List<Proposal> out = new ArrayList<>();
        Set<List<ColumnRef>> dropped = new HashSet<>();
        for (Proposal p : in) {
            List<ColumnRef> key = List.of(p.from().ref(), p.to().ref());
            if (dropped.contains(key)) {
                continue;
            }
            Proposal reverse = byPair.get(List.of(p.to().ref(), p.from().ref()));
            if (reverse != null && better(reverse, p)) {
                dropped.add(key);
                continue;
            }
            if (reverse != null) {
                dropped.add(List.of(p.to().ref(), p.from().ref()));
            }
            out.add(p);
        }
        return out;
    }

    private static boolean better(Proposal a, Proposal b) {
        if (a.confidence() != b.confidence()) {
            return a.confidence() > b.confidence();
        }
        if (a.to().primaryKey() != b.to().primaryKey()) {
            return a.to().primaryKey();
        }
        return (a.from().assetUrn() + a.from().field()).compareTo(b.from().assetUrn() + b.from().field()) < 0;
    }

    private List<Proposal> keepBest(List<Proposal> in) {
        Map<ColumnRef, List<Proposal>> byFrom = new LinkedHashMap<>();
        for (Proposal p : in) {
            byFrom.computeIfAbsent(p.from().ref(), k -> new ArrayList<>()).add(p);
        }
        List<Proposal> out = new ArrayList<>();
        for (List<Proposal> ps : byFrom.values()) {
            ps.sort(Comparator.comparingDouble(Proposal::confidence).reversed());
            out.addAll(ps.subList(0, Math.min(limits.perColumn(), ps.size())));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
