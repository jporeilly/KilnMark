package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Map;

/**
 * What the prediction and suggestion API takes and returns (docs/design/data-quality.md section 8;
 * docs/guide/pipelines.md, "Predicted quality"). Every score here is an estimate from the catalogue's
 * metadata ({@code estimate} is always true), worked out without reading any data. Words, counts and
 * shares only, never values from the data.
 */
public final class PredictionViews {

    private PredictionViews() {
    }

    /** {@code POST /api/v1/pipelines/predict}: the YAML to predict, as being edited. */
    public record PredictRequest(String yaml) {
    }

    /** {@code POST /api/v1/pipelines/suggest}: the YAML, and whether to ask the AI model too (default true). */
    public record SuggestRequest(String yaml, Boolean ai) {
    }

    /**
     * The predicted quality of a pipeline: rows on each node's output ports (null when unknown), and
     * each write ({@code outputs}) and gate. {@code valid} is whether the YAML has no problems; a
     * pipeline with problems is still predicted as far as it can be.
     */
    public record Prediction(boolean estimate, boolean valid, List<PredictedNode> nodes, List<PredictedQuality> outputs) {
    }

    /** Rows estimated to leave each output port. */
    public record PredictedNode(String node, String type, Map<String, Long> rows) {
    }

    /**
     * One write or gate. {@code overall} (0 to 1) is null when nothing could be predicted; per
     * dimension only those the metadata can tell. {@code unknown} says what could not be told,
     * {@code assumptions} what was assumed. {@code rows} the rows expected to arrive, or null.
     */
    public record PredictedQuality(String node, String role, String asset, Double overall, Map<String, Double> dimensions,
                                   List<String> reasons, List<String> unknown, List<String> assumptions, Long rows,
                                   PredictedGate gate) {
    }

    /** What a gate is expected to do: decision is passed, warned, quarantined or stopped, or null when unknown. */
    public record PredictedGate(String onFail, Long rowsIn, Long rowsPassed, Long rowsQuarantined, String decision,
                                List<PredictedRule> rules) {
    }

    /**
     * A rule a gate would check: the share of rows expected to fail it (null when unknown), its
     * expected status (pass, warn, fail; error when unknown) and what the estimate is based on.
     */
    public record PredictedRule(String name, String type, String field, String dimension, String origin,
                                Double failingShare, String status, String basis) {
    }

    /** Suggestions, best first, and what the AI model did. */
    public record Advice(boolean estimate, List<SuggestionView> suggestions, AiView ai) {
    }

    /**
     * A suggestion: the nodes to add (in order) and where (cut the connection from {@code from} into
     * {@code before}.{@code port}; the first new node reads {@code from}, {@code before} then reads
     * {@code through}), with the predicted effect on that write's score. {@code source} is
     * {@code rules} (Kilnmark's own) or {@code ai}.
     */
    public record SuggestionView(String id, String kind, String title, String why, String source,
                                 List<NewNodeView> nodes, PlacementView placement, EffectView effect) {
    }

    /** A node to add: id, type, its settings as YAML holds them, and its inputs (port to node[.port]). */
    public record NewNodeView(String id, String type, Map<String, Object> settings, Map<String, String> inputs) {
    }

    public record PlacementView(String before, String port, String from, String through) {
    }

    /**
     * The output's predicted score and rows before and after the suggestion: estimates. {@code note}
     * says what it does that the score does not show (rows with no match left out), or is null.
     */
    public record EffectView(String output, Double before, Double after, Map<String, Double> dimensionsBefore,
                             Map<String, Double> dimensionsAfter, Long rowsBefore, Long rowsAfter, String note) {
    }

    /**
     * What the model did: off, not_asked, used, withheld (no source's AI rule allows it) or
     * unavailable; {@code message} in words for anyone; {@code model} the profile's name, or null.
     */
    public record AiView(String state, String message, String model) {
    }

    // ---- from the predictor's own records ----

    static Prediction of(QualityPredictor.Result r, boolean valid) {
        return new Prediction(true, valid,
            r.nodes().stream().map(s -> new PredictedNode(s.node(), s.type(), s.rows())).toList(),
            r.outputs().stream().map(PredictionViews::of).toList());
    }

    static PredictedQuality of(QualityPredictor.Predicted p) {
        return new PredictedQuality(p.node(), p.role(), p.asset(), p.overall(), p.dimensions(), p.reasons(), p.unknown(),
            p.assumptions(), p.rows(), of(p.gate()));
    }

    static PredictedGate of(QualityPredictor.PredictedGate g) {
        return g == null ? null : new PredictedGate(g.onFail(), g.rowsIn(), g.rowsPassed(), g.rowsQuarantined(),
            g.decision(), g.rules().stream().map(r -> new PredictedRule(r.name(), r.type(), r.field(), r.dimension(),
                r.origin(), r.failingShare(), r.status(), r.basis())).toList());
    }

    static Advice of(PipelineAdvisor.Advice a) {
        return new Advice(true, a.suggestions().stream().map(PredictionViews::of).toList(),
            new AiView(a.ai().state(), a.ai().message(), a.ai().model()));
    }

    static SuggestionView of(PipelineAdvisor.Suggestion s) {
        PipelineAdvisor.Effect e = s.effect();
        return new SuggestionView(s.id(), s.kind(), s.title(), s.why(), s.source(),
            s.nodes().stream().map(n -> new NewNodeView(n.id(), n.type(), n.settings(), n.inputs())).toList(),
            new PlacementView(s.placement().before(), s.placement().port(), s.placement().from(),
                s.placement().through()),
            e == null ? null : new EffectView(e.output(), e.before(), e.after(), e.dimensionsBefore(),
                e.dimensionsAfter(), e.rowsBefore(), e.rowsAfter(), e.note()));
    }
}
