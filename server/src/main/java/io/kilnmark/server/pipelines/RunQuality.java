package io.kilnmark.server.pipelines;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.server.quality.Measures;
import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.Scores;

/**
 * The measured quality of a run (docs/design/data-quality.md section 8, "Profile once, reuse
 * everywhere"): for each output, the score of the rows written, from the profile its writer made
 * while writing them, so nothing is read again; for each gate, the score of its rules' results.
 * Scores are per dimension and overall, as an asset's are ({@link Scores}), from the measures alone:
 * the rows of one run have no history to judge freshness or volume against.
 *
 * <p>Beside each measured score is the {@code predicted} one, worked out when the run started from
 * the catalogue's metadata about what it reads ({@link QualityPredictor}), so stewards see the gap.
 */
final class RunQuality {

    /** A score: overall and per dimension (0 to 1), with the main reasons in words. */
    record Score(Double overall, Map<String, Double> dimensions, List<String> reasons) {
    }

    /**
     * One node's quality in a run. {@code role} is {@code output} (a write) or {@code gate};
     * {@code measured} is null when it could not be measured, and {@code note} says why;
     * {@code predicted} is the estimate made when the run started (null when none could be made),
     * {@code predictedDetail} its JSON: {reasons, unknown, assumptions, rows, gate};
     * {@code gate} is a gate's outcome.
     */
    record NodeQuality(String node, String role, String asset, Score measured, Score predicted, String note,
                       QualityGate.Outcome gate, String predictedDetail) {

        NodeQuality(String node, String role, String asset, Score measured, Score predicted, String note,
                    QualityGate.Outcome gate) {
            this(node, role, asset, measured, predicted, note, gate, null);
        }
    }

    /** What the run could not measure for a node it predicted, by role and how the run ended. */
    static String notMeasured(String role, String status) {
        if ("succeeded".equals(status)) {
            return "output".equals(role) ? "The run did not report what this node wrote" : "The gate did not report";
        }
        return "output".equals(role) ? "The run did not succeed, so what this node wrote was not measured"
            : "The run ended before this gate reported";
    }

    /**
     * The measured quality with each node's prediction beside it: a node measured and predicted
     * gets both; one only predicted (the run failed before it wrote) keeps its prediction and says
     * why it was not measured.
     */
    static List<NodeQuality> withPredicted(List<NodeQuality> measured, List<NodeQuality> predicted, String status) {
        Map<String, NodeQuality> byNode = new LinkedHashMap<>();
        for (NodeQuality p : predicted) {
            byNode.put(p.node(), new NodeQuality(p.node(), p.role(), p.asset(), null, p.predicted(),
                notMeasured(p.role(), status), null, p.predictedDetail()));
        }
        for (NodeQuality m : measured) {
            NodeQuality p = byNode.get(m.node());
            byNode.put(m.node(), p == null ? m : new NodeQuality(m.node(), m.role(), m.asset() != null ? m.asset()
                : p.asset(), m.measured(), p.predicted(), m.note(), m.gate(), p.predictedDetail()));
        }
        return new ArrayList<>(byNode.values());
    }

    /** Most reasons kept per score. */
    static final int REASONS = 5;

    private RunQuality() {
    }

    /** The quality of what a write wrote, from its writer's events. */
    static NodeQuality output(PipelineExecution.WriteTarget w, List<MetadataEvent> events,
                              Function<MetadataEvent, String> profileJson, ObjectMapper json) {
        List<MetadataEvent> mine = events.stream().filter(e -> e.assetUrn().equals(w.urn())).toList();
        MetadataEvent rowCount = mine.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
            .orElse(null);
        List<MetadataEvent> profiles = mine.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE).toList();
        if (profiles.isEmpty()) {
            return new NodeQuality(w.node(), "output", w.urn(), null, null, unmeasured(w, rowCount), null);
        }
        Score s = score(facts(w, mine, profiles, rowCount, profileJson, json));
        return new NodeQuality(w.node(), "output", w.urn(), s.overall() == null ? null : s, null,
            s.overall() == null ? "Nothing in the rows written could be measured" : null, null);
    }

    /** Why a write has no measured score. */
    static String unmeasured(PipelineExecution.WriteTarget w, MetadataEvent rowCount) {
        if (!w.wholeTarget()) {
            return "The " + w.mode() + " added rows to what was there, and rows added are not profiled, so they have "
                + "no score";
        }
        if (rowCount != null && "false".equals(rowCount.attributes().get(WriteReport.PROFILED))) {
            return "What it wrote could not be profiled, so it has no score";
        }
        if (rowCount == null) {
            return "The writer reported nothing about what it wrote";
        }
        return "Profiling was turned off for this write (profile_output: false), so it has no score";
    }

    /** The rows written as quality analysis reads a profile version. */
    static ProfileFacts facts(PipelineExecution.WriteTarget w, List<MetadataEvent> mine, List<MetadataEvent> profiles,
                              MetadataEvent rowCount, Function<MetadataEvent, String> profileJson, ObjectMapper json) {
        Map<String, String> kinds = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (MetadataEvent e : mine) {
            if (e.kind() != MetadataEvent.Kind.SCHEMA) {
                continue;
            }
            try {
                for (JsonNode c : json.readTree(e.attributes().getOrDefault("columns", "[]"))) {
                    String name = c.path("name").asText(null);
                    if (name != null) {
                        kinds.put(name, c.path("kind").asText("text"));
                        types.put(name, c.path("type").asText(c.path("kind").asText("text")));
                    }
                }
            } catch (Exception ex) {
                // no columns from the schema: kinds come from the profiles' shapes below
            }
        }
        List<ProfileFacts.FieldFacts> fields = new ArrayList<>();
        int position = 0;
        for (MetadataEvent p : profiles) {
            String name = p.attributes().get("field");
            if (name == null) {
                continue;
            }
            JsonNode stats;
            try {
                stats = json.readTree(profileJson.apply(p));
            } catch (Exception ex) {
                continue;
            }
            String kind = kinds.getOrDefault(name, kindOfShape(p.attributes().get("shape")));
            fields.add(new ProfileFacts.FieldFacts(name, ++position, kind, types.getOrDefault(name, kind), true, stats,
                null));
        }
        Long rows = null;
        if (rowCount != null) {
            try {
                rows = Long.valueOf(rowCount.attributes().get("rows"));
            } catch (RuntimeException ex) {
                rows = null;
            }
        }
        return new ProfileFacts(0, w.urn(), Instant.now(), rows, Boolean.TRUE, false, w.key(), fields);
    }

    /** The asset-level scores of a profile with no history: per dimension and overall. */
    static Score score(ProfileFacts facts) {
        List<Measures.Measure> measures = Measures.of(facts, List.of(), List.of(), Duration.ofDays(1));
        return asset(Scores.of(measures, List.of(), List.of()));
    }

    /** The whole-asset rows of a list of scores. */
    static Score asset(List<Scores.Score> scores) {
        Map<String, Double> dims = new LinkedHashMap<>();
        Double overall = null;
        List<String> reasons = List.of();
        for (Scores.Score s : scores) {
            if (!Scores.ASSET.equals(s.field())) {
                continue;
            }
            if (Scores.OVERALL.equals(s.dimension())) {
                overall = s.score();
                reasons = s.reasons().stream().limit(REASONS).toList();
            } else {
                dims.put(s.dimension(), s.score());
            }
        }
        return new Score(overall, dims, reasons);
    }

    /** A gate's quality from its outcome. */
    static NodeQuality gate(String node, QualityGate.Outcome o) {
        Score s = o.score() == null ? null : new Score(o.score(), o.dimensions(),
            o.reasons().stream().limit(REASONS).toList());
        String note = !o.complete() ? "The gate checked only the first " + QualityGate.count(o.rowsIn()) + " rows: the "
            + "run ended its rows early, so it could not judge the rest"
            : s != null ? null : o.rules().isEmpty() ? "The gate had no rules to check"
            : "None of the gate's rules could run";
        return new NodeQuality(node, "gate", o.asset(), s, null, note, o);
    }

    /** The lowest measured overall score among the outputs, or null when none was measured. */
    static Double lowestOutput(List<NodeQuality> nodes) {
        Double low = null;
        for (NodeQuality q : nodes) {
            if ("output".equals(q.role()) && q.measured() != null && q.measured().overall() != null) {
                low = low == null ? q.measured().overall() : Math.min(low, q.measured().overall());
            }
        }
        return low;
    }

    /** The output with that lowest score, for the notification's words; null when none. */
    static NodeQuality lowestOutputNode(List<NodeQuality> nodes) {
        NodeQuality low = null;
        for (NodeQuality q : nodes) {
            if ("output".equals(q.role()) && q.measured() != null && q.measured().overall() != null
                && (low == null || q.measured().overall() < low.measured().overall())) {
                low = q;
            }
        }
        return low;
    }

    private static String kindOfShape(String shape) {
        if (shape == null) {
            return "text";
        }
        return switch (shape) {
            case "integer" -> "int64";
            case "number" -> "float64";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "timestamp" -> "timestamp";
            default -> "text";
        };
    }
}
