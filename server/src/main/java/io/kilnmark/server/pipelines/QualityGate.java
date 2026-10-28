package io.kilnmark.server.pipelines;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.plugins.profile.CellText;
import io.kilnmark.server.quality.Dimension;
import io.kilnmark.server.quality.RowChecks;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.Scores;

/**
 * The running {@code gate} step (docs/design/data-quality.md section 7): checks every row against
 * its rules as the rows stream past, in the one pass the pipeline makes anyway, and then
 * <ul>
 *   <li>{@link OnFail#STOP}: passes every row on {@code out}; at the end, fails the run when a rule
 *       fails by its own limit or the gate's threshold is crossed, before anything downstream can
 *       finish (a write commits only at the end of its rows). A rule that cannot pass any more stops
 *       the run at once.</li>
 *   <li>{@link OnFail#QUARANTINE}: rows that fail any rule go to {@code quarantine}, the rest to
 *       {@code out}; the run stops only when the gate's threshold is crossed (too much is failing
 *       to set aside), or a check of the whole data (row count, freshness) fails, which no row can
 *       be set aside for.</li>
 *   <li>{@link OnFail#WARN}: every row goes on {@code out}; what was found is recorded, nothing stops.</li>
 * </ul>
 * After the run, {@link #outcome()} says what it found, for the run's measured quality.
 */
final class QualityGate implements Step {

    static final String TYPE = "gate";
    static final List<String> PORTS = List.of("out", "quarantine");

    enum OnFail {
        STOP, QUARANTINE, WARN;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static OnFail parse(String s) {
            return s == null ? STOP : valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    /** A rule the gate checks, and where it came from: {@code asset} (the asset's own rules) or {@code inline}. */
    record GateRule(Rule rule, String origin) {
    }

    /** The asset's rules, looked up when the step starts. */
    interface AssetRules {
        /** The asset read upstream whose rules apply, or null for none. */
        String urn();

        List<Rule> rules();

        /** The asset's columns, name to kind, for range bounds; empty when not known. */
        Map<String, String> kinds();

        /** Its row count at the last scan, for row_count_change; null when unknown. */
        Long previousRows();
    }

    /** One rule's result. {@code status} is pass, warn, fail or error (could not run, for example a missing column). */
    record RuleOutcome(String name, String type, String field, String dimension, String origin, String status,
                       long failing, long total, String message) {
    }

    /**
     * What the gate found. {@code decision}: passed (nothing failed), warned (something failed and
     * was let through, or a rule warned), quarantined (failing rows were set aside), or stopped.
     * {@code rowsFailing} is rows failing at least one rule; {@code score} the gate's measured
     * quality (overall and per dimension, from the rules' results), null when it checked no rules.
     */
    record Outcome(String onFail, String decision, String asset, long rowsIn, long rowsPassed, long rowsFailing,
                   long rowsQuarantined, Double score, Map<String, Double> dimensions, List<String> reasons,
                   List<RuleOutcome> rules, Double minScore, Double maxFailingShare, String stopReason) {
    }

    private final OnFail onFail;
    private final Double minScore;
    private final Double maxFailingShare;
    private final List<Rule> inline;
    private final boolean assetRules;
    private final AssetRulesLookup lookup;

    private List<GateRule> rules;
    private AssetRules asset;
    private List<RowChecks.Check> checks;
    private long[] failingSoFar;
    private long rowsIn;
    private long rowsFailing;
    private long rowsQuarantined;
    private volatile Outcome outcome;
    private String name = "gate";

    /** Finds the asset whose rules apply, given the step's id in its pipeline; null for none. */
    @FunctionalInterface
    interface AssetRulesLookup {
        AssetRules find(String stepId);
    }

    QualityGate(OnFail onFail, Double minScore, Double maxFailingShare, List<Rule> inline, boolean assetRules,
                AssetRulesLookup lookup) {
        this.onFail = onFail;
        this.minScore = minScore;
        this.maxFailingShare = maxFailingShare;
        this.inline = List.copyOf(inline);
        this.assetRules = assetRules;
        this.lookup = lookup;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** What the gate found; null until it has finished (or failed before starting). */
    Outcome outcome() {
        return outcome;
    }

    @Override
    public void init(StepContext context) throws StepException {
        name = context.stepName();
        rules = new ArrayList<>();
        asset = assetRules && lookup != null ? lookup.find(name) : null;
        if (asset != null) {
            for (Rule r : asset.rules()) {
                rules.add(new GateRule(r, "asset"));
            }
        }
        for (Rule r : inline) {
            rules.add(new GateRule(r, "inline"));
        }
        failingSoFar = new long[rules.size()];
    }

    @Override
    public boolean process(StepContext ctx) throws StepException {
        VectorSchemaRoot batch;
        try {
            batch = ctx.input("in").take(ctx.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (batch == null) {
            finish();
            return false;
        }
        int[] target;
        try {
            target = check(batch);
        } catch (RuntimeException e) {
            batch.close();
            throw new StepException("Checking rules failed: " + e.getMessage(), e);
        }
        String early = onFail == OnFail.STOP ? certainFailure() : null;
        if (early != null) {
            batch.close();
            outcome = judge(early);
            throw new StepException(early);
        }
        distribute(ctx, batch, target);
        return true;
    }

    /** Checks each row of a batch; returns each row's port (0 out, 1 quarantine). */
    int[] check(VectorSchemaRoot batch) {
        List<FieldVector> vectors = batch.getFieldVectors();
        if (checks == null) {
            List<String> columns = batch.getSchema().getFields().stream().map(Field::getName).toList();
            Map<String, String> kinds = new LinkedHashMap<>();
            if (asset != null) {
                kinds.putAll(asset.kinds());
            }
            for (Field f : batch.getSchema().getFields()) {
                kinds.put(f.getName(), ArrowKinds.kind(f)); // the columns as they arrive win
            }
            checks = new ArrayList<>();
            Long previous = asset == null ? null : asset.previousRows();
            for (GateRule g : rules) {
                checks.add(RowChecks.of(g.rule(), columns, kinds, "asset".equals(g.origin()) ? previous : null,
                    Instant.now(), 0, "pipeline"));
            }
        }
        int n = batch.getRowCount();
        int[] target = new int[n];
        String[] cache = new String[vectors.size()];
        boolean[] read = new boolean[vectors.size()];
        for (int i = 0; i < n; i++) {
            Arrays.fill(read, false);
            int row = i;
            RowChecks.Row r = c -> {
                if (!read[c]) {
                    cache[c] = CellText.of(vectors.get(c), row);
                    read[c] = true;
                }
                return cache[c];
            };
            boolean failed = false;
            for (int k = 0; k < checks.size(); k++) {
                if (checks.get(k).test(r)) { // every rule sees every row, so each counts its own
                    failingSoFar[k]++;
                    failed = true;
                }
            }
            rowsIn++;
            if (failed) {
                rowsFailing++;
                if (onFail == OnFail.QUARANTINE) {
                    rowsQuarantined++;
                    target[i] = 1;
                }
            }
        }
        return target;
    }

    /**
     * With {@code on_fail: stop}, the first rule that can no longer pass however many rows follow:
     * its limit is a count of rows and more than that have failed, or it allows no failing row and
     * one has. Null while every rule could still pass.
     */
    String certainFailure() {
        for (int k = 0; k < rules.size(); k++) {
            Rule r = rules.get(k).rule();
            if (r.type().wholeAsset() || failingSoFar[k] == 0) {
                continue;
            }
            boolean count = "count".equals(r.thresholdUnit());
            if (count ? failingSoFar[k] > r.failAbove() : r.failAbove() == 0) {
                return "The quality gate stopped the run: rule '" + r.name() + "' failed on " + count(failingSoFar[k])
                    + (failingSoFar[k] == 1 ? " row" : " rows") + " in the first " + count(rowsIn) + ", more than its "
                    + "limit of " + limit(r) + ". Nothing after the gate was finished.";
            }
        }
        return null;
    }

    /** At the end of the rows: every rule's result, the score and the decision; stops the run if it must. */
    void finish() throws StepException {
        Outcome o = judge(null);
        outcome = o;
        if (o.stopReason() != null) {
            throw new StepException(o.stopReason());
        }
    }

    /**
     * Every rule's result so far, the score and the decision. {@code early} is the reason when the
     * gate stops before the end of the rows; null at the end.
     */
    Outcome judge(String early) {
        List<RuleOutcome> results = new ArrayList<>();
        List<Scores.RuleResult> forScore = new ArrayList<>();
        String failedRule = null;
        boolean anyWarn = false;
        boolean wholeFailed = false;
        List<RowChecks.Evaluation> evaluations = evaluations();
        for (int k = 0; k < rules.size(); k++) {
            GateRule g = rules.get(k);
            Rule r = g.rule();
            RowChecks.Evaluation e = evaluations.get(k);
            Rule.Status status = e.error() != null ? Rule.Status.ERROR : r.status(e.failing(), e.total());
            results.add(new RuleOutcome(r.name(), r.type().id(), r.field(), r.dimension().id(), g.origin(), status.id(),
                e.failing(), e.total(), e.error() != null ? e.error() : e.message()));
            forScore.add(new Scores.RuleResult(r.field(), r.dimension(), r.name(), status, e.failing(), e.total(),
                r.type().wholeAsset()));
            if (status == Rule.Status.FAIL) {
                if (failedRule == null) {
                    failedRule = "rule '" + r.name() + "' failed" + (r.type().wholeAsset()
                        ? (e.message() == null ? "" : " (" + e.message() + ")")
                        : " on " + count(e.failing()) + " of " + count(e.total()) + " rows (" + percent(share(e.failing(),
                            e.total())) + "), more than its limit of " + limit(r));
                }
                wholeFailed |= r.type().wholeAsset();
            } else if (status == Rule.Status.WARN) {
                anyWarn = true;
            }
        }
        Score s = score(forScore);
        if (early != null) {
            return new Outcome(onFail.id(), "stopped", asset == null ? null : asset.urn(), rowsIn,
                rowsIn - rowsFailing, rowsFailing, rowsQuarantined, s.overall(), s.dimensions(), s.reasons(),
                List.copyOf(results), minScore, maxFailingShare, early);
        }
        String breach = breach(s.overall());
        String stop = switch (onFail) {
            case STOP -> failedRule != null ? failedRule : breach;
            case QUARANTINE -> breach != null ? breach : wholeFailed ? failedRule : null;
            case WARN -> null;
        };
        String decision;
        if (stop != null) {
            decision = "stopped";
        } else if (onFail == OnFail.QUARANTINE && rowsQuarantined > 0) {
            decision = "quarantined";
        } else if (failedRule != null || breach != null || anyWarn || rowsFailing > 0) {
            decision = "warned";
        } else {
            decision = "passed";
        }
        String reason = stop == null ? null : "The quality gate stopped the run: " + stop + "."
            + (onFail == OnFail.QUARANTINE && breach == null ? " Rows cannot be set aside for a check of the whole data."
            : "") + " Nothing after the gate was finished.";
        return new Outcome(onFail.id(), decision, asset == null ? null : asset.urn(), rowsIn, rowsIn - rowsFailing,
            rowsFailing, rowsQuarantined, s.overall(), s.dimensions(), s.reasons(), List.copyOf(results), minScore,
            maxFailingShare, reason);
    }

    private List<RowChecks.Evaluation> evaluations() {
        if (checks == null) {
            // No batch at all: every rule over no rows (row_count still judges the count, 0). The
            // columns the rules name are taken as there, so a rule of rows passes on no rows.
            java.util.Set<String> named = new java.util.LinkedHashSet<>();
            for (GateRule g : rules) {
                if (g.rule().field() != null) {
                    named.add(g.rule().field());
                }
                g.rule().params().path("fields").forEach(f -> named.add(f.asText()));
            }
            List<String> columns = List.copyOf(named);
            List<RowChecks.Evaluation> out = new ArrayList<>();
            for (GateRule g : rules) {
                out.add(RowChecks.of(g.rule(), columns, Map.of(), asset == null ? null : asset.previousRows(),
                    Instant.now(), 0, "pipeline").finish());
            }
            return out;
        }
        return checks.stream().map(RowChecks.Check::finish).toList();
    }

    /** Why the gate's threshold is crossed, or null when it is not (or has none). */
    String breach(Double score) {
        if (maxFailingShare != null && rowsIn > 0 && share(rowsFailing, rowsIn) > maxFailingShare) {
            return count(rowsFailing) + " of " + count(rowsIn) + " rows (" + percent(share(rowsFailing, rowsIn))
                + ") failed a rule, more than the gate's max_failing_share of " + percent(maxFailingShare);
        }
        if (minScore != null && score != null && score < minScore) {
            return "the rows scored " + percent(score) + ", below the gate's min_score of " + percent(minScore);
        }
        return null;
    }

    record Score(Double overall, Map<String, Double> dimensions, List<String> reasons) {
    }

    /** The gate's measured score from its rules' results: per dimension and overall, as an asset is scored. */
    static Score score(List<Scores.RuleResult> results) {
        if (results.stream().allMatch(r -> r.status() == Rule.Status.ERROR)) {
            return new Score(null, Map.of(), List.of());
        }
        Map<String, Double> dims = new LinkedHashMap<>();
        Double overall = null;
        List<String> reasons = List.of();
        for (Scores.Score s : Scores.of(List.of(), results, List.of())) {
            if (!Scores.ASSET.equals(s.field())) {
                continue;
            }
            if (Scores.OVERALL.equals(s.dimension())) {
                overall = s.score();
                reasons = s.reasons();
            } else {
                dims.put(s.dimension(), s.score());
            }
        }
        return new Score(overall, dims, reasons);
    }

    /** Rows to {@code out} or {@code quarantine}: a batch going one way whole is handed on as it is. */
    private static void distribute(StepContext ctx, VectorSchemaRoot batch, int[] target) throws StepException {
        boolean handedOver = false;
        try {
            int n = batch.getRowCount();
            int[] counts = new int[PORTS.size()];
            for (int t : target) {
                counts[t]++;
            }
            for (int p = 0; p < PORTS.size(); p++) {
                RowSet out = ctx.outputPorts().contains(PORTS.get(p)) ? ctx.output(PORTS.get(p)) : null;
                if (counts[p] == 0 || out == null) {
                    continue;
                }
                if (counts[p] == n) {
                    handedOver = true;
                    emit(ctx, PORTS.get(p), batch);
                    return;
                }
                int[] rows = new int[counts[p]];
                int k = 0;
                for (int i = 0; i < n; i++) {
                    if (target[i] == p) {
                        rows[k++] = i;
                    }
                }
                emit(ctx, PORTS.get(p), copy(batch, rows, ctx.allocator()));
            }
        } finally {
            if (!handedOver) {
                batch.close();
            }
        }
    }

    private static void emit(StepContext ctx, String port, VectorSchemaRoot batch) throws StepException {
        try {
            ctx.emit(port, batch);
        } catch (InterruptedException e) {
            batch.close();
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
    }

    private static VectorSchemaRoot copy(VectorSchemaRoot in, int[] rows, BufferAllocator allocator) {
        List<FieldVector> out = new ArrayList<>();
        try {
            for (FieldVector v : in.getFieldVectors()) {
                FieldVector o = v.getField().createVector(allocator);
                out.add(o);
                o.setInitialCapacity(rows.length);
                o.allocateNew();
                for (int j = 0; j < rows.length; j++) {
                    o.copyFromSafe(rows[j], j, v);
                }
                o.setValueCount(rows.length);
            }
            return new VectorSchemaRoot(in.getSchema(), out, rows.length);
        } catch (RuntimeException e) {
            out.forEach(FieldVector::close);
            throw e;
        }
    }

    static double share(long part, long whole) {
        return whole <= 0 ? 0 : (double) part / whole;
    }

    static String count(long n) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(n);
    }

    static String percent(double share) {
        NumberFormat p = NumberFormat.getPercentInstance(Locale.UK);
        p.setMaximumFractionDigits(share > 0 && share < 0.01 ? 2 : 1);
        return p.format(share);
    }

    private static String limit(Rule r) {
        if ("count".equals(r.thresholdUnit())) {
            long n = (long) r.failAbove();
            return count(n) + (n == 1 ? " row" : " rows");
        }
        return percent(r.failAbove());
    }

    /** For the dimension labels in messages. */
    static String label(String dimension) {
        try {
            return Dimension.parse(dimension).label();
        } catch (IllegalArgumentException e) {
            return dimension;
        }
    }
}
