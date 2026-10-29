package io.kilnmark.server.pipelines;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.plugins.profile.ValueShape;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.ExprView;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.server.quality.Dimension;
import io.kilnmark.server.quality.Measures;
import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.Regexes;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleType;
import io.kilnmark.server.quality.Scores;

/**
 * Predicted quality at design time (docs/design/data-quality.md section 8, "Profile once, reuse
 * everywhere"): the catalogue's metadata about each asset a pipeline reads (profiles, value shapes,
 * most common values, accepted patterns, relationships and rule results) is carried through the
 * nodes, each with its own rule, to estimate what reaches every write and gate. No data is read:
 * only what the catalogue already holds. Each output is then scored with the same measures a run
 * scores what it wrote with ({@link RunQuality}), and each gate from the rules it would check, so
 * a run's predicted and measured scores compare directly.
 *
 * <p>What is not known stays unknown: a share the metadata cannot tell is never filled with a
 * guess, a dimension that cannot be judged gets no score, and the words say what was unknown and
 * which assumptions were made (most often, that a node's rows are like the rows it was given).
 *
 * <p>The rules per node are documented in docs/guide/pipelines.md, "Predicted quality".
 */
final class QualityPredictor {

    /** What the catalogue holds about an asset, as prediction needs it. Every method may return null or empty. */
    interface Catalogue {
        /** The latest profile version of an asset, with its accepted value shapes; null when it has none. */
        ProfileFacts facts(String urn);

        /** The asset's enabled rules. */
        List<Rule> rules(String urn);

        /** The latest result of each of the asset's rules, by rule id. */
        Map<Long, LastResult> lastResults(String urn);

        /**
         * The share of {@code fromField}'s distinct values not found in {@code toField}, from the stored
         * relationship's evidence or the two columns' sketches; null when it cannot be told.
         */
        Double orphanShare(String fromUrn, String fromField, String toUrn, String toField);

        /** The expressions of a field's accepted patterns, for suggestions; empty when none. */
        default List<String> acceptedRegexes(String urn, String field) {
            return List.of();
        }

        /** The single-column relationships from a field of {@code urn} to other assets, for suggestions. */
        default List<Link> links(String urn) {
            return List.of();
        }
    }

    /** A relationship from a field of an asset to a field of another, with its orphan share when known. */
    record Link(String field, String toUrn, String toField, Double orphanShare, String kind, String status) {
    }

    /** A rule's latest result: how many rows failed of how many checked, and its status. */
    record LastResult(long failing, long total, Rule.Status status) {
    }

    /** A node as prediction sees it: its type, settings as written, and the columns the check worked out. */
    record PlanNode(String id, String type, Map<String, Object> config,
                    Map<String, List<PipelineCompilerPort.Column>> columns) {
        PlanNode {
            config = config == null ? Map.of() : config;
            columns = columns == null ? Map.of() : columns;
        }
    }

    record PlanEdge(String fromNode, String fromPort, String toNode, String toPort) {
    }

    /** A pipeline to predict: nodes, edges in the order written, and the URN each read node reads. */
    record Plan(List<PlanNode> nodes, List<PlanEdge> edges, Map<String, String> reads) {

        static Plan of(PipelineCompilerPort.Compilation c, Map<String, String> reads) {
            List<PlanNode> nodes = new ArrayList<>();
            for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
                nodes.add(new PlanNode(s.id(), s.type(), c.configs().getOrDefault(s.id(), Map.of()), s.columns()));
            }
            List<PlanEdge> edges = c.edges().stream()
                .map(e -> new PlanEdge(e.fromNode(), e.fromPort(), e.toNode(), e.toPort())).toList();
            return new Plan(nodes, edges, reads);
        }

        PlanNode node(String id) {
            for (PlanNode s : nodes) {
                if (s.id().equals(id)) {
                    return s;
                }
            }
            return null;
        }

        /**
         * This plan with {@code added} nodes listed after {@code after} and their edges, and the edge
         * {@code cut} (when given) replaced by {@code rewired}: how a suggestion is tried out.
         */
        Plan with(List<PlanNode> added, List<PlanEdge> addedEdges, PlanEdge cut, PlanEdge rewired,
                  Map<String, String> addedReads) {
            List<PlanNode> s = new ArrayList<>(nodes);
            s.addAll(added);
            List<PlanEdge> e = new ArrayList<>();
            for (PlanEdge x : edges) {
                e.add(x.equals(cut) && rewired != null ? rewired : x);
            }
            e.addAll(addedEdges);
            Map<String, String> r = new LinkedHashMap<>(reads);
            r.putAll(addedReads);
            return new Plan(s, e, r);
        }
    }

    // ---- what prediction says ----

    /** The rows estimated to leave each output port of a node; a null count is unknown. */
    record NodeEstimate(String node, String type, Map<String, Long> rows) {
    }

    /** One rule a gate would check, with the share of rows estimated to fail it and where that came from. */
    record PredictedRule(String name, String type, String field, String dimension, String origin, Double failingShare,
                         String status, String basis) {
    }

    /** What a gate is expected to do: rows in, passed and set aside (null when unknown), and its rules. */
    record PredictedGate(String onFail, Long rowsIn, Long rowsPassed, Long rowsQuarantined, String decision,
                         List<PredictedRule> rules) {
    }

    /**
     * The predicted quality of one write ({@code output}) or gate. {@code overall} is null when
     * nothing could be predicted; {@code unknown} says what could not be told and {@code assumptions}
     * what was assumed. Always an estimate.
     */
    record Predicted(String node, String role, String asset, Double overall, Map<String, Double> dimensions,
                     List<String> reasons, List<String> unknown, List<String> assumptions, Long rows,
                     PredictedGate gate) {
    }

    record Result(List<NodeEstimate> nodes, List<Predicted> outputs) {

        Predicted output(String node) {
            for (Predicted p : outputs) {
                if (p.node().equals(node)) {
                    return p;
                }
            }
            return null;
        }
    }

    // ---- the estimate carried along each edge ----

    /** Where a column's values came from unchanged: a field of an asset read, for relationships and rule results. */
    record Origin(String urn, String field) {
    }

    /** What is estimated about one column. Shares are of rows (nulls, blanks) or of values (the rest); null is unknown. */
    static final class Col {
        String name;
        String kind;
        Origin origin;
        /** The values are no longer the asset's own (trimmed, computed), so its rule results no longer apply. */
        boolean changed;
        Double nulls;
        Double blanks;
        Double distinct;
        boolean distinctExact = true;
        LinkedHashMap<String, Double> patterns;
        boolean collapsed;
        Map<String, Double> formats;
        Set<String> accepted = Set.of();
        Double outliers;
        Double negatives;
        Double future;
        LinkedHashMap<String, Double> top;
        double[] quantiles;
        String fenceLow;
        String fenceHigh;

        Col(String name, String kind) {
            this.name = name;
            this.kind = kind == null ? "text" : kind;
        }

        Col copy(String newName) {
            Col c = new Col(newName, kind);
            c.origin = origin;
            c.changed = changed;
            c.nulls = nulls;
            c.blanks = blanks;
            c.distinct = distinct;
            c.distinctExact = distinctExact;
            c.patterns = patterns == null ? null : new LinkedHashMap<>(patterns);
            c.collapsed = collapsed;
            c.formats = formats == null ? null : new LinkedHashMap<>(formats);
            c.accepted = accepted;
            c.outliers = outliers;
            c.negatives = negatives;
            c.future = future;
            c.top = top == null ? null : new LinkedHashMap<>(top);
            c.quantiles = quantiles;
            c.fenceLow = fenceLow;
            c.fenceHigh = fenceHigh;
            return c;
        }

        Col copy() {
            return copy(name);
        }

        boolean text() {
            return "text".equals(kind);
        }

        boolean numeric() {
            return Set.of("int16", "int32", "int64", "float32", "float64", "decimal").contains(kind);
        }

        boolean temporal() {
            return Set.of("date", "timestamp", "timestamp_tz").contains(kind);
        }

        /** A column of which only its name and kind are known. */
        static Col unknown(String name, String kind) {
            return new Col(name, kind);
        }

        /** Every value null. */
        static Col allNull(String name, String kind) {
            Col c = new Col(name, kind);
            c.nulls = 1.0;
            c.blanks = 0.0;
            return c;
        }

        /** Forgets what describes values (shapes, values, spread), keeping completeness. */
        void forgetValues() {
            distinct = null;
            patterns = null;
            formats = null;
            outliers = null;
            negatives = null;
            future = null;
            top = null;
            quantiles = null;
            fenceLow = null;
            fenceHigh = null;
            accepted = Set.of();
        }
    }

    /** What is estimated about the rows on one edge. {@code cols} null: not even the columns are known. */
    static final class Est {
        Double rows;
        LinkedHashMap<String, Col> cols;
        List<String> key = List.of();
        /** Rows were taken out on the way (a filter, a gate, a lookup that drops), so a lookup side is narrower. */
        boolean narrowed;
        final LinkedHashSet<String> unknown = new LinkedHashSet<>();
        final LinkedHashSet<String> assumptions = new LinkedHashSet<>();

        Est copy() {
            Est e = new Est();
            e.rows = rows;
            if (cols != null) {
                e.cols = new LinkedHashMap<>();
                cols.forEach((k, v) -> e.cols.put(k, v.copy()));
            }
            e.key = key;
            e.narrowed = narrowed;
            e.unknown.addAll(unknown);
            e.assumptions.addAll(assumptions);
            return e;
        }

        Col col(String name) {
            return cols == null ? null : cols.get(name);
        }

        void scale(Double share) {
            rows = rows == null || share == null ? null : rows * share;
        }

        static Est unknownRows(List<PipelineCompilerPort.Column> columns) {
            Est e = new Est();
            if (columns != null) {
                e.cols = new LinkedHashMap<>();
                for (PipelineCompilerPort.Column c : columns) {
                    e.cols.put(c.name(), Col.unknown(c.name(), c.type()));
                }
            }
            return e;
        }
    }

    /** Rows used in place of an unknown count when scoring: scores depend on shares, not on the count. */
    static final long NOMINAL_ROWS = 1_000_000;
    /** Most words kept per list in what prediction says. */
    static final int MAX_WORDS = 8;
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private final Catalogue catalogue;

    QualityPredictor(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    // ---- running through the graph ----

    /** Predicts every write's and gate's quality, and the rows on each port, in the order rows flow. */
    Result predict(Plan plan) {
        return trace(plan).result();
    }

    /** A prediction with the estimate on every output port, by node and port, for suggestions. */
    record Trace(Result result, Map<String, Map<String, Est>> ports) {

        /** The estimate of the rows arriving at {@code node}'s {@code port}, from the first edge feeding it. */
        Est arriving(Plan plan, String node, String port) {
            for (PlanEdge e : plan.edges()) {
                if (e.toNode().equals(node) && e.toPort().equals(port)) {
                    return ports.getOrDefault(e.fromNode(), Map.of()).get(e.fromPort());
                }
            }
            return null;
        }
    }

    Trace trace(Plan plan) {
        Map<String, Map<String, Est>> out = new LinkedHashMap<>();
        List<NodeEstimate> nodes = new ArrayList<>();
        List<Predicted> predicted = new ArrayList<>();
        for (PlanNode s : order(plan)) {
            Map<String, List<Est>> inputs = new LinkedHashMap<>();
            for (PlanEdge e : plan.edges()) {
                if (!e.toNode().equals(s.id())) {
                    continue;
                }
                Est from = out.getOrDefault(e.fromNode(), Map.of()).get(e.fromPort());
                if (from == null) {
                    PlanNode up = plan.node(e.fromNode());
                    from = Est.unknownRows(up == null ? null : up.columns().get(e.fromPort()));
                    from.unknown.add("what " + e.fromNode() + " sends on " + e.fromPort());
                }
                inputs.computeIfAbsent(e.toPort(), k -> new ArrayList<>()).add(from);
            }
            Map<String, Est> ports;
            try {
                ports = node(plan, s, inputs, predicted);
            } catch (RuntimeException ex) {
                ports = new LinkedHashMap<>();
                for (Map.Entry<String, List<PipelineCompilerPort.Column>> c : s.columns().entrySet()) {
                    Est e = Est.unknownRows(c.getValue());
                    e.unknown.add("what " + s.id() + " does to its rows");
                    ports.put(c.getKey(), e);
                }
            }
            out.put(s.id(), ports);
            Map<String, Long> rows = new LinkedHashMap<>();
            ports.forEach((port, e) -> rows.put(port, e.rows == null ? null : Math.round(e.rows)));
            nodes.add(new NodeEstimate(s.id(), s.type(), rows));
        }
        return new Trace(new Result(nodes, predicted), out);
    }

    /** Nodes in the order rows reach them; nodes in a loop (refused by the check) come last. */
    static List<PlanNode> order(Plan plan) {
        Map<String, Integer> waiting = new LinkedHashMap<>();
        for (PlanNode s : plan.nodes()) {
            waiting.put(s.id(), 0);
        }
        for (PlanEdge e : plan.edges()) {
            if (waiting.containsKey(e.toNode()) && waiting.containsKey(e.fromNode())) {
                waiting.merge(e.toNode(), 1, Integer::sum);
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        waiting.forEach((id, n) -> {
            if (n == 0) {
                ready.add(id);
            }
        });
        List<PlanNode> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            if (!done.add(id)) {
                continue;
            }
            out.add(plan.node(id));
            for (PlanEdge e : plan.edges()) {
                if (e.fromNode().equals(id) && waiting.containsKey(e.toNode())) {
                    if (waiting.merge(e.toNode(), -1, Integer::sum) == 0) {
                        ready.add(e.toNode());
                    }
                }
            }
        }
        for (PlanNode s : plan.nodes()) {
            if (!done.contains(s.id())) {
                out.add(s);
            }
        }
        return out;
    }

    private Map<String, Est> node(Plan plan, PlanNode s, Map<String, List<Est>> inputs, List<Predicted> predicted) {
        String type = s.type() == null ? "" : s.type();
        Est in = first(inputs, "in");
        return switch (type) {
            case "read" -> Map.of("out", read(plan, s));
            case "filter" -> filter(s, in);
            case "compute" -> Map.of("out", compute(s, in));
            case "select" -> Map.of("out", select(s, in));
            case "lookup" -> lookup(s, first(inputs, "main"), first(inputs, "lookup"));
            case "aggregate" -> Map.of("out", aggregate(s, in));
            case "union" -> Map.of("out", union(inputs.getOrDefault("in", List.of())));
            case "dedupe" -> dedupe(s, in);
            case "route" -> route(s, in);
            case "limit" -> Map.of("out", limit(s, in));
            case "gate" -> gate(plan, s, in, predicted);
            case "write" -> {
                predicted.add(write(plan, s, in));
                yield Map.of();
            }
            default -> {
                Map<String, Est> ports = new LinkedHashMap<>();
                for (Map.Entry<String, List<PipelineCompilerPort.Column>> c : s.columns().entrySet()) {
                    Est e = Est.unknownRows(c.getValue());
                    e.unknown.add("what a " + type + " node does to its rows (" + s.id() + ")");
                    ports.put(c.getKey(), e);
                }
                yield ports;
            }
        };
    }

    private static Est first(Map<String, List<Est>> inputs, String port) {
        List<Est> l = inputs.get(port);
        if (l == null || l.isEmpty()) {
            Est e = new Est();
            e.unknown.add("the rows arriving on " + port);
            return e;
        }
        return l.get(0).copy();
    }

    // ---- read ----

    /**
     * The asset's latest profile: row count, and per column its nulls, blanks, distinct values,
     * value shapes, formats, most common values, spread and accepted shapes. With {@code row_limit},
     * the first rows are taken to be like the rest.
     */
    private Est read(Plan plan, PlanNode s) {
        String urn = plan.reads().get(s.id());
        ProfileFacts f = urn == null ? null : catalogue.facts(urn);
        List<PipelineCompilerPort.Column> outline = s.columns().get("out");
        if (f == null) {
            Est e = Est.unknownRows(outline);
            e.unknown.add((urn == null ? "the asset " + s.id() + " reads" : urn) + " has no profile yet: scan its source");
            return e;
        }
        Est e = new Est();
        e.rows = f.rowCount() == null ? null : f.rowCount().doubleValue();
        if (e.rows == null) {
            e.unknown.add("the row count of " + urn);
        }
        if (Boolean.FALSE.equals(f.rowCountComplete()) || f.estimate()) {
            e.assumptions.add("the profile of " + urn + " was made from a sample");
        }
        e.cols = new LinkedHashMap<>();
        Map<String, ProfileFacts.FieldFacts> byName = new LinkedHashMap<>();
        f.fields().forEach(x -> byName.put(x.name(), x));
        List<String> names = outline != null ? outline.stream().map(PipelineCompilerPort.Column::name).toList()
            : f.fields().stream().map(ProfileFacts.FieldFacts::name).toList();
        for (String name : names) {
            ProfileFacts.FieldFacts x = byName.get(name);
            String kind = outline == null ? (x == null ? "text" : x.kind()) : outline.stream()
                .filter(c -> c.name().equals(name)).map(PipelineCompilerPort.Column::type).findFirst().orElse("text");
            Col c = x == null ? Col.unknown(name, kind) : fromFacts(x, kind);
            c.origin = new Origin(urn, name);
            if (c.nulls == null) {
                e.unknown.add(name + " has no profile");
            }
            e.cols.put(name, c);
        }
        e.key = f.primaryKey();
        long limit = ReadNodeFactory.rowLimit(s.config());
        if (limit > 0) {
            e.rows = e.rows == null ? null : Math.min(e.rows, limit);
            e.assumptions.add("the first " + countWords(limit) + " rows of " + s.id() + " are like the rest");
        }
        return e;
    }

    /** A profiled field as an estimate: counts become shares. */
    static Col fromFacts(ProfileFacts.FieldFacts x, String kind) {
        Col c = new Col(x.name(), kind == null ? x.kind() : kind);
        if (x.stats() == null || x.rows() <= 0) {
            if (x.stats() != null && x.rows() == 0) {
                c.nulls = 0.0;
                c.blanks = 0.0;
            }
            return c;
        }
        double rows = x.rows();
        double values = Math.max(0, x.values());
        c.nulls = Math.max(0, x.nulls()) / rows;
        c.blanks = x.has("blanks") ? Math.max(0, x.get("blanks")) / rows : (c.text() ? null : 0.0);
        if (values <= 0) {
            return c;
        }
        if (x.has("distinct")) {
            c.distinct = Math.min(1, Math.max(0, x.get("distinct")) / values);
            c.distinctExact = !"false".equals(x.text("distinct_exact"));
        }
        if (x.has("patterns")) {
            c.patterns = new LinkedHashMap<>();
            for (ProfileFacts.Counted p : x.patterns()) {
                c.patterns.merge(p.item(), p.count() / values, Double::sum);
            }
            c.collapsed = "true".equals(x.text("patterns_collapsed"));
            c.formats = new LinkedHashMap<>();
            for (Map.Entry<String, Long> fm : x.formats().entrySet()) {
                c.formats.put(fm.getKey(), fm.getValue() / values);
            }
        }
        c.accepted = x.acceptedShapes();
        if (x.has("outliers")) {
            c.outliers = Math.max(0, x.get("outliers")) / values;
        }
        if (x.has("negatives")) {
            c.negatives = Math.max(0, x.get("negatives")) / values;
        }
        if (x.has("after_now")) {
            c.future = Math.max(0, x.get("after_now")) / values;
        }
        if (x.stats().path("top").isArray()) {
            c.top = new LinkedHashMap<>();
            for (ProfileFacts.Counted t : x.top()) {
                c.top.merge(t.item(), t.count() / values, Double::sum);
            }
        }
        c.quantiles = x.quantiles();
        c.fenceLow = x.text("fence_low");
        c.fenceHigh = x.text("fence_high");
        return c;
    }

    // ---- filter and route: what a condition keeps ----

    /**
     * What a condition does: the share of rows for which it is true ({@code share}, null when it
     * cannot be told), the share for which it is null ({@code nullShare}: its column is empty, so the
     * condition is neither true nor false; null when unknown), what is then certain about the columns
     * of the rows it keeps and of the rows it does not, and an assumption it made, or null.
     */
    record Selection(Double share, Double nullShare, Map<String, UnaryOperator<Col>> kept,
                     Map<String, UnaryOperator<Col>> rejected, String unknown, boolean narrows, String assumed) {

        Selection(Double share, Map<String, UnaryOperator<Col>> kept, Map<String, UnaryOperator<Col>> rejected,
                  String unknown, boolean narrows) {
            this(share, null, kept, rejected, unknown, narrows, null);
        }

        static Selection unknown(String why) {
            return new Selection(null, Map.of(), Map.of(), why, true);
        }

        Selection nulls(Double n) {
            return new Selection(share, n, kept, rejected, unknown, narrows, assumed);
        }

        Selection assuming(String a) {
            return new Selection(share, nullShare, kept, rejected, unknown, narrows, a);
        }

        /**
         * {@code not}: true where this is false, which leaves out the rows where it is null (SQL's
         * rules), so its share is the rest less the null share. A test that is never null (is null) swaps
         * what it keeps and sets aside; otherwise the rows kept have the column filled, and nothing more
         * is known of its values.
         */
        Selection not() {
            if (nullShare == null) {
                return new Selection(null, null, Map.of(), Map.of(), unknown != null ? unknown
                    : "which rows the condition is empty for", narrows, assumed);
            }
            Double s = share == null ? null : Math.max(0, 1 - nullShare - share);
            if (nullShare == 0) {
                return new Selection(s, 0.0, rejected, kept, unknown, narrows, assumed);
            }
            Map<String, UnaryOperator<Col>> filled = new LinkedHashMap<>();
            for (String c : kept.keySet()) {
                filled.put(c, x -> {
                    Col y = withoutNulls(x);
                    y.forgetValues();
                    return y;
                });
            }
            return new Selection(s, nullShare, filled, Map.of(), unknown, narrows, assumed);
        }
    }

    /** A range a condition sets on one column: a comparison, or between. */
    record Bound(Col col, Double lo, boolean loIncluded, Double hi, boolean hiIncluded) {
    }

    /** The range {@code v} sets on a column with a number bound, or null for any other condition. */
    static Bound rangeOf(ExprView v, Est in) {
        if (!(v instanceof ExprView.Op o)) {
            return null;
        }
        if (o.op().equals("between")) {
            Col c = refCol(o.args().get(0), in);
            Double lo = c == null ? null : number(o.args().get(1), c);
            Double hi = c == null ? null : number(o.args().get(2), c);
            return lo == null || hi == null ? null : new Bound(c, lo, true, hi, true);
        }
        if (!Set.of("<", "<=", ">", ">=").contains(o.op())) {
            return null;
        }
        ExprView a = o.args().get(0);
        ExprView b = o.args().get(1);
        String op = o.op();
        if (a instanceof ExprView.Lit && b instanceof ExprView.Ref) {
            ExprView t = a;
            a = b;
            b = t;
            op = switch (op) {
                case "<" -> ">";
                case "<=" -> ">=";
                case ">" -> "<";
                default -> "<=";
            };
        }
        Col c = refCol(a, in);
        Double n = c == null ? null : number(b, c);
        if (n == null) {
            return null;
        }
        return op.startsWith("<") ? new Bound(c, null, false, n, op.equals("<="))
            : new Bound(c, n, op.equals(">="), null, false);
    }

    /** Two conditions joined with and, on the same column with bounds, as one range; null otherwise. */
    private static Selection range(Bound a, Bound b, Selection sa, Selection sb) {
        if (a == null || b == null || !a.col().name.equals(b.col().name)) {
            return null;
        }
        Col c = a.col();
        if (c.quantiles == null || c.nulls == null) {
            return null;
        }
        Double lo = a.lo();
        boolean loIn = a.loIncluded();
        if (b.lo() != null && (lo == null || b.lo() > lo || (b.lo().equals(lo) && !b.loIncluded()))) {
            lo = b.lo();
            loIn = b.loIncluded();
        }
        Double hi = a.hi();
        boolean hiIn = a.hiIncluded();
        if (b.hi() != null && (hi == null || b.hi() < hi || (b.hi().equals(hi) && !b.hiIncluded()))) {
            hi = b.hi();
            hiIn = b.hiIncluded();
        }
        double upTo = hi == null ? 1 : below(c.quantiles, hi, hiIn);
        double from = lo == null ? 0 : below(c.quantiles, lo, !loIn);
        double share = (1 - c.nulls) * Math.max(0, upTo - from);
        return new Selection(share, c.nulls, merge(sa.kept(), sb.kept()), Map.of(), null, true, null);
    }

    static final String INDEPENDENT = "conditions joined with and or or are taken as independent of each other";

    private Map<String, Est> filter(PlanNode s, Est in) {
        String condition = PipelineSources.text(s.config(), "condition");
        Selection sel = condition == null ? Selection.unknown("the filter's condition") : selection(condition, in);
        Map<String, Est> out = new LinkedHashMap<>();
        out.put("out", apply(in, sel, true, s.id()));
        out.put("rejected", apply(in, sel, false, s.id()));
        return out;
    }

    /** The rows a condition keeps ({@code kept}) or does not. */
    static Est apply(Est in, Selection sel, boolean kept, String node) {
        Est e = in.copy();
        Double share = sel.share() == null ? null : kept ? sel.share() : 1 - sel.share();
        e.scale(share);
        e.narrowed = e.narrowed || sel.narrows();
        if (share == null) {
            e.unknown.add("how many rows " + node + (kept ? " keeps" : " sets aside") + " (" + sel.unknown() + ")");
        }
        if (sel.assumed() != null) {
            e.assumptions.add(node + ": " + sel.assumed());
        }
        Map<String, UnaryOperator<Col>> changes = kept ? sel.kept() : sel.rejected();
        if (e.cols != null) {
            for (Map.Entry<String, UnaryOperator<Col>> c : changes.entrySet()) {
                Col col = e.cols.get(c.getKey());
                if (col != null) {
                    e.cols.put(c.getKey(), c.getValue().apply(col.copy()));
                }
            }
            if (e.cols.size() > changes.size()) {
                e.assumptions.add("columns other than those " + node + " tests are spread the same in the rows it "
                    + (kept ? "keeps" : "sets aside"));
            }
        }
        return e;
    }

    Selection selection(String condition, Est in) {
        try {
            return selection(Expression.parse(condition).view(), in);
        } catch (ExprException e) {
            return Selection.unknown("the condition cannot be read");
        }
    }

    Selection selection(ExprView v, Est in) {
        if (v instanceof ExprView.Op o) {
            switch (o.op()) {
                case "and" -> {
                    Selection a = selection(o.args().get(0), in);
                    Selection b = selection(o.args().get(1), in);
                    Selection same = range(rangeOf(o.args().get(0), in), rangeOf(o.args().get(1), in), a, b);
                    if (same != null) {
                        return same;
                    }
                    Map<String, UnaryOperator<Col>> kept = merge(a.kept(), b.kept());
                    Double share = a.share() == null || b.share() == null ? null : a.share() * b.share();
                    Double nulls = Objects.equals(a.nullShare(), 0.0) && Objects.equals(b.nullShare(), 0.0) ? 0.0 : null;
                    return new Selection(share, nulls, kept, Map.of(), a.unknown() != null ? a.unknown() : b.unknown(),
                        true, share == null ? null : INDEPENDENT);
                }
                case "or" -> {
                    Selection a = selection(o.args().get(0), in);
                    Selection b = selection(o.args().get(1), in);
                    Map<String, UnaryOperator<Col>> rejected = merge(a.rejected(), b.rejected());
                    Double share = a.share() == null || b.share() == null ? null
                        : 1 - (1 - a.share()) * (1 - b.share());
                    Double nulls = Objects.equals(a.nullShare(), 0.0) && Objects.equals(b.nullShare(), 0.0) ? 0.0 : null;
                    return new Selection(share, nulls, Map.of(), rejected, a.unknown() != null ? a.unknown()
                        : b.unknown(), true, share == null ? null : INDEPENDENT);
                }
                case "not" -> {
                    return selection(o.args().get(0), in).not();
                }
                case "is_not_null", "is_null" -> {
                    Col c = refCol(o.args().get(0), in);
                    if (c == null) {
                        return Selection.unknown("the column tested for null");
                    }
                    Selection notNull = new Selection(c.nulls == null ? null : 1 - c.nulls,
                        Map.of(c.name, x -> withoutNulls(x)), Map.of(c.name, x -> Col.allNull(x.name, x.kind)),
                        c.nulls == null ? c.name + " has no profile" : null, true).nulls(0.0);
                    return o.op().equals("is_null") ? notNull.not() : notNull;
                }
                case "=", "<>", "in", "not_in" -> {
                    return equality(o, in);
                }
                case "<", "<=", ">", ">=" -> {
                    return comparison(o, in);
                }
                case "between", "not_between" -> {
                    Col c = refCol(o.args().get(0), in);
                    Double lo = c == null ? null : number(o.args().get(1), c);
                    Double hi = c == null ? null : number(o.args().get(2), c);
                    if (c == null || lo == null || hi == null || c.quantiles == null || c.nulls == null) {
                        return Selection.unknown("the share of " + (c == null ? "values" : c.name) + " in that range");
                    }
                    double inside = Math.max(0, below(c.quantiles, hi, true) - below(c.quantiles, lo, false));
                    Selection between = new Selection((1 - c.nulls) * inside, Map.of(c.name, x -> {
                        Col y = withoutNulls(x);
                        y.outliers = null;
                        y.quantiles = null;
                        if (lo >= 0) {
                            y.negatives = 0.0;
                        }
                        return y;
                    }), Map.of(), null, true).nulls(c.nulls);
                    return o.op().equals("between") ? between : new Selection(between.share() == null ? null
                        : (1 - c.nulls) * (1 - inside), Map.of(c.name, x -> {
                            Col y = withoutNulls(x);
                            y.quantiles = null;
                            y.outliers = null;
                            return y;
                        }), Map.of(), null, true).nulls(c.nulls);
                }
                default -> {
                    return Selection.unknown("what the condition keeps");
                }
            }
        }
        if (v instanceof ExprView.Call call && call.name().equals("regex_match") && call.args().size() == 2
            && call.args().get(1) instanceof ExprView.Lit lit && lit.value() instanceof String regex) {
            Col c = refCol(call.args().get(0), in);
            Double match = c == null ? null : shapeShare(c, regex);
            if (c == null || match == null || c.nulls == null) {
                return Selection.unknown("which values match the pattern");
            }
            Pattern p = Pattern.compile(regex);
            return new Selection((1 - c.nulls) * match, Map.of(c.name, x -> keepShapes(withoutNulls(x), p, true)),
                Map.of(), null, true).nulls(c.nulls);
        }
        if (v instanceof ExprView.Lit lit && lit.value() instanceof Boolean b) {
            return new Selection(b ? 1.0 : 0.0, Map.of(), Map.of(), null, !b).nulls(0.0);
        }
        return Selection.unknown("what the condition keeps");
    }

    private static Map<String, UnaryOperator<Col>> merge(Map<String, UnaryOperator<Col>> a,
                                                         Map<String, UnaryOperator<Col>> b) {
        Map<String, UnaryOperator<Col>> out = new LinkedHashMap<>(a);
        for (Map.Entry<String, UnaryOperator<Col>> e : b.entrySet()) {
            UnaryOperator<Col> first = out.get(e.getKey());
            out.put(e.getKey(), first == null ? e.getValue() : x -> e.getValue().apply(first.apply(x)));
        }
        return out;
    }

    /** {@code x = 'v'}, {@code x <> 'v'}, {@code x in (...)}, {@code x not in (...)}, from the most common values. */
    private Selection equality(ExprView.Op o, Est in) {
        boolean list = o.op().equals("in") || o.op().equals("not_in");
        ExprView left = o.args().get(0);
        List<ExprView> items = list ? o.args().subList(1, o.args().size()) : List.of(o.args().get(1));
        if (!list && left instanceof ExprView.Lit && items.get(0) instanceof ExprView.Ref) {
            ExprView t = left;
            left = items.get(0);
            items = List.of(t);
        }
        boolean negated = o.op().equals("<>") || o.op().equals("not_in");
        // x <> '' and trim(x) <> '': blanks and nulls out.
        if (o.op().equals("<>") && items.get(0) instanceof ExprView.Lit l && "".equals(l.value())) {
            Col c = refCol(left instanceof ExprView.Call t && t.name().equals("trim") && t.args().size() == 1
                ? t.args().get(0) : left, in);
            if (c == null || c.nulls == null || c.blanks == null) {
                return Selection.unknown("how many values are blank");
            }
            double share = Math.max(0, 1 - c.nulls - c.blanks);
            return new Selection(share, Map.of(c.name, x -> {
                Col y = withoutNulls(x);
                y.blanks = 0.0;
                return y;
            }), Map.of(), null, true).nulls(c.nulls);
        }
        Col c = refCol(left, in);
        if (c == null) {
            return Selection.unknown("what the condition keeps");
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (ExprView item : items) {
            if (!(item instanceof ExprView.Lit l) || l.value() == null) {
                return Selection.unknown("the values compared with " + c.name);
            }
            wanted.add(literalText(l.value()));
        }
        Double matched = topShare(c, wanted);
        if (matched == null || c.nulls == null) {
            return Selection.unknown("how many rows have those values of " + c.name + " (its most common values are not "
                + "kept, or do not cover them)");
        }
        double share = (1 - c.nulls) * (negated ? 1 - matched : matched);
        UnaryOperator<Col> keep = x -> {
            Col y = withoutNulls(x);
            if (y.top != null) {
                y.top.keySet().removeIf(k -> wanted.contains(k) == negated);
                normalise(y.top);
                y.patterns = shapesOf(y.top);
                y.collapsed = false;
                y.formats = null;
            }
            y.distinct = null;
            return y;
        };
        return new Selection(share, Map.of(c.name, keep), Map.of(), null, true).nulls(c.nulls);
    }

    /** {@code x > n} and the like on a number or date with quantiles. */
    private Selection comparison(ExprView.Op o, Est in) {
        ExprView a = o.args().get(0);
        ExprView b = o.args().get(1);
        String op = o.op();
        if (a instanceof ExprView.Lit && b instanceof ExprView.Ref) {
            ExprView t = a;
            a = b;
            b = t;
            op = switch (op) {
                case "<" -> ">";
                case "<=" -> ">=";
                case ">" -> "<";
                default -> "<=";
            };
        }
        Col c = refCol(a, in);
        Double n = c == null ? null : number(b, c);
        if (c == null || n == null || c.quantiles == null || c.nulls == null) {
            return Selection.unknown("the share of " + (c == null ? "values" : c.name) + " on that side of the bound");
        }
        boolean upper = op.startsWith("<");
        double below = below(c.quantiles, n, op.equals("<=") || op.equals(">"));
        double share = (1 - c.nulls) * (upper ? below : 1 - below);
        double bound = n;
        boolean nonNegative = !upper && bound >= 0;
        return new Selection(share, Map.of(c.name, x -> {
            Col y = withoutNulls(x);
            y.quantiles = null;
            y.outliers = null;
            if (nonNegative) {
                y.negatives = 0.0;
            }
            return y;
        }), Map.of(), null, true).nulls(c.nulls);
    }

    /**
     * The share of values at or below ({@code inclusive}) or below {@code v}, by the 21 quantiles.
     * Where several quantiles equal {@code v} (a value many rows share), at or below counts to the
     * last of them and below to the first.
     */
    static double below(double[] q, double v, boolean inclusive) {
        int n = q.length - 1;
        if (n <= 0) {
            return inclusive ? (v >= q[0] ? 1 : 0) : (v > q[0] ? 1 : 0);
        }
        if (v < q[0] || (!inclusive && v == q[0])) {
            return 0;
        }
        if (v > q[n] || (inclusive && v == q[n])) {
            return 1;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i <= n; i++) {
            if (q[i] == v) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first >= 0) {
            return (double) (inclusive ? last : first) / n;
        }
        for (int i = 0; i < n; i++) {
            if (v < q[i + 1]) {
                double span = q[i + 1] - q[i];
                double within = span <= 0 ? 0 : (v - q[i]) / span;
                return (i + within) / n;
            }
        }
        return 1;
    }

    static Col refCol(ExprView v, Est in) {
        return v instanceof ExprView.Ref r ? in.col(r.column()) : null;
    }

    /** A literal as a number on a column's scale: dates as days, timestamps as microseconds. */
    static Double number(ExprView v, Col c) {
        if (!(v instanceof ExprView.Lit l) || l.value() == null) {
            if (v instanceof ExprView.Op o && o.op().equals("neg") && o.args().get(0) instanceof ExprView.Lit) {
                Double d = number(o.args().get(0), c);
                return d == null ? null : -d;
            }
            return null;
        }
        Object x = l.value();
        if (x instanceof Number n && c.numeric()) {
            return n.doubleValue();
        }
        if (x instanceof String s && c.temporal()) {
            try {
                if (s.startsWith("date:")) {
                    long days = LocalDate.parse(s.substring(5)).toEpochDay();
                    return "date".equals(c.kind) ? (double) days : days * 86_400_000_000.0;
                }
                if (s.startsWith("timestamp:")) {
                    double micros = Double.parseDouble(s.substring(10));
                    return "date".equals(c.kind) ? Math.floor(micros / 86_400_000_000.0) : micros;
                }
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    static String literalText(Object v) {
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (v instanceof String s && s.startsWith("date:")) {
            return s.substring(5);
        }
        return String.valueOf(v);
    }

    /**
     * The share of a column's values that are among {@code wanted}, from its most common values;
     * null when they are not kept or do not account for every value (so a value could hide outside them).
     */
    static Double topShare(Col c, Set<String> wanted) {
        if (c.top == null || c.top.isEmpty()) {
            return null;
        }
        double listed = c.top.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean complete = listed >= 0.999;
        double matched = 0;
        for (String w : wanted) {
            Double share = c.top.get(w);
            if (share == null) {
                // A number or time may be written another way (10 and 10.00): no match there is no answer.
                if (!complete || !c.text()) {
                    return null;
                }
            } else {
                matched += share;
            }
        }
        return Math.min(1, matched / Math.max(listed, 1e-12) * Math.min(1, listed));
    }

    private static void normalise(Map<String, Double> m) {
        double sum = m.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0) {
            m.replaceAll((k, v) -> v / sum);
        }
    }

    private static LinkedHashMap<String, Double> shapesOf(Map<String, Double> values) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        values.forEach((v, share) -> out.merge(ValueShape.of(v), share, Double::sum));
        return out;
    }

    /** No nulls left: the shares of values stay, blanks rise as a share of the fewer rows. */
    static Col withoutNulls(Col x) {
        if (x.nulls != null && x.blanks != null && x.nulls < 1) {
            x.blanks = x.blanks / (1 - x.nulls);
        }
        x.nulls = 0.0;
        return x;
    }

    /**
     * The share of a column's values whose shape {@code regex} finds a match in, testing each value
     * shape as a value (letters as A or a, digits as 1); null when the shapes are not known exactly.
     * An estimate: a shape says what a value looks like, not what it is.
     */
    static Double shapeShare(Col c, String regex) {
        if (c.patterns == null || c.collapsed) {
            return null;
        }
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
        if (literalLettersOrDigits(regex)) {
            return null;
        }
        double listed = 0;
        double matched = 0;
        for (Map.Entry<String, Double> e : c.patterns.entrySet()) {
            listed += e.getValue();
            Boolean found = matchesShape(p, e.getKey());
            if (found == null) {
                return null;
            }
            if (found) {
                matched += e.getValue();
            }
        }
        if (listed < 0.95) {
            return null; // too many values in shapes the profile did not list
        }
        return Math.min(1, matched / listed);
    }

    /** Stand-ins tried for each shape symbol (capital, small letter, digit): all must agree. */
    static final char[][] STAND_INS = {{'A', 'a', '0'}, {'Z', 'z', '9'}, {'M', 'm', '5'}, {'A', 'z', '9'}};

    /**
     * Whether a value of this shape would match, trying several letters and digits for each symbol;
     * null when it cannot tell: the stand-ins disagree (the pattern cares which letter or digit), or
     * the shape was cut.
     */
    static Boolean matchesShape(Pattern p, String shape) {
        if (shape.endsWith(ValueShape.CUT)) {
            return null;
        }
        Boolean answer = null;
        for (char[] with : STAND_INS) {
            StringBuilder b = new StringBuilder(shape.length());
            for (int i = 0; i < shape.length(); i++) {
                char ch = shape.charAt(i);
                b.append(ch == 'A' ? with[0] : ch == 'a' ? with[1] : ch == '9' ? with[2] : ch);
            }
            Boolean found = Regexes.find(p, b.toString());
            if (found == null || (answer != null && !answer.equals(found))) {
                return null;
            }
            answer = found;
        }
        return answer;
    }

    /**
     * Whether a pattern names particular letters or digits ({@code ^GB\\d+}), outside character classes
     * and escapes: a value shape cannot tell which letters a value has, so such a pattern is not judged
     * on shapes.
     */
    static boolean literalLettersOrDigits(String regex) {
        String r = regex.replaceAll("\\\\[pP]\\{[^}]*\\}", "")
            .replaceAll("\\\\.", "")
            .replaceAll("\\[(?:\\\\.|[^\\]])*\\]", "")
            .replaceAll("\\{\\d*,?\\d*\\}", "")
            .replaceAll("\\(\\?[a-zA-Z:!=<>-]*", "(");
        return r.matches("(?s).*[A-Za-z0-9].*");
    }

    private static Col keepShapes(Col x, Pattern p, boolean matching) {
        if (x.patterns != null) {
            x.patterns.keySet().removeIf(shape -> !Objects.equals(matchesShape(p, shape), matching));
            normalise(x.patterns);
            x.formats = null;
        }
        x.top = null;
        x.distinct = null;
        return x;
    }

    private Map<String, Est> route(PlanNode s, Est in) {
        Map<String, Est> out = new LinkedHashMap<>();
        Object routes = s.config().get("routes");
        List<Selection> before = new ArrayList<>();
        if (routes instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> r : m.entrySet()) {
                String port = String.valueOf(r.getKey());
                Selection sel = r.getValue() == null ? Selection.unknown("the route's condition")
                    : selection(String.valueOf(r.getValue()), in);
                Est e = apply(in, sel, true, s.id() + " (" + port + ")");
                for (Selection b : before) {
                    Est rest = apply(e, b, false, s.id() + " (" + port + ")");
                    e = rest;
                }
                out.put(port, e);
                before.add(sel);
            }
        }
        Est rest = in.copy();
        for (Selection b : before) {
            rest = apply(rest, b, false, s.id());
        }
        out.put("out", rest);
        return out;
    }

    // ---- compute and select ----

    private Est compute(PlanNode s, Est in) {
        Est e = in.copy();
        Object columns = s.config().get("columns");
        if (!(columns instanceof Map<?, ?> m) || e.cols == null) {
            if (e.cols == null) {
                e.unknown.add("the columns arriving at " + s.id());
            }
            return e;
        }
        for (Map.Entry<?, ?> c : m.entrySet()) {
            String name = String.valueOf(c.getKey());
            Col col;
            try {
                col = derive(Expression.parse(String.valueOf(c.getValue())).view(), e, name);
            } catch (ExprException ex) {
                col = null;
            }
            if (col == null) {
                Col was = e.cols.get(name);
                col = Col.unknown(name, was == null ? "text" : was.kind);
                e.unknown.add(name + " as " + s.id() + " computes it");
            }
            col.name = name;
            e.cols.put(name, col);
        }
        return e;
    }

    /**
     * A computed column. Copies a column as it is ({@code x}); trims, changes case, fills nulls with a
     * value ({@code coalesce(x, 'v')}); parses dates (the values in other shapes become null) and formats
     * them; a literal is one value in every row. Arithmetic and joined text are null when any part is.
     * Anything else is unknown.
     */
    Col derive(ExprView v, Est in, String name) {
        switch (v) {
            case ExprView.Ref r -> {
                Col c = in.col(r.column());
                return c == null ? null : c.copy(name);
            }
            case ExprView.Lit l -> {
                return constant(name, l.value(), in.rows);
            }
            case ExprView.Call call -> {
                List<ExprView> a = call.args();
                switch (call.name()) {
                    case "trim" -> {
                        Col c = a.size() == 1 ? derive(a.get(0), in, name) : null;
                        if (c == null) {
                            return null;
                        }
                        c.changed = true;
                        if (c.formats != null) {
                            c.formats.remove("padded");
                        }
                        if (c.patterns != null) {
                            LinkedHashMap<String, Double> trimmed = new LinkedHashMap<>();
                            c.patterns.forEach((shape, share) -> trimmed.merge(shape.strip(), share, Double::sum));
                            c.patterns = trimmed;
                        }
                        if (c.top != null) {
                            LinkedHashMap<String, Double> trimmed = new LinkedHashMap<>();
                            c.top.forEach((value, share) -> trimmed.merge(value.strip(), share, Double::sum));
                            c.top = trimmed;
                        }
                        return c;
                    }
                    case "lower", "upper" -> {
                        Col c = a.size() == 1 ? derive(a.get(0), in, name) : null;
                        if (c == null) {
                            return null;
                        }
                        boolean lower = call.name().equals("lower");
                        c.changed = true;
                        if (c.patterns != null) {
                            LinkedHashMap<String, Double> cased = new LinkedHashMap<>();
                            c.patterns.forEach((shape, share) -> cased.merge(lower ? shape.replace('A', 'a')
                                : shape.replace('a', 'A'), share, Double::sum));
                            c.patterns = cased;
                        }
                        if (c.top != null) {
                            LinkedHashMap<String, Double> cased = new LinkedHashMap<>();
                            c.top.forEach((value, share) -> cased.merge(lower ? value.toLowerCase(Locale.ROOT)
                                : value.toUpperCase(Locale.ROOT), share, Double::sum));
                            c.top = cased;
                        }
                        c.distinct = null;
                        c.accepted = Set.of();
                        return c;
                    }
                    case "coalesce" -> {
                        return coalesce(a, in, name);
                    }
                    case "parse_date", "parse_timestamp" -> {
                        return parsed(call, in, name);
                    }
                    case "format_date" -> {
                        if (a.size() != 2 || !(a.get(1) instanceof ExprView.Lit l) || !(l.value() instanceof String fmt)) {
                            return null;
                        }
                        Col d = derive(a.get(0), in, name);
                        if (d == null) {
                            return null;
                        }
                        Col out = new Col(name, "text");
                        out.changed = true;
                        out.nulls = d.nulls;
                        out.blanks = 0.0;
                        String shape = formatShape(fmt);
                        if (shape != null) {
                            out.patterns = new LinkedHashMap<>(Map.of(shape, 1.0));
                            out.formats = fmt.equals("yyyy-MM-dd") ? new LinkedHashMap<>(Map.of("iso_date", 1.0))
                                : new LinkedHashMap<>();
                        }
                        return out;
                    }
                    case "count", "count_distinct" -> {
                        Col c = new Col(name, "int64");
                        c.nulls = 0.0;
                        c.negatives = 0.0;
                        return c;
                    }
                    case "sum", "min", "max", "avg" -> {
                        Col x = a.size() == 1 ? derive(a.get(0), in, name) : null;
                        if (x == null || x.nulls == null) {
                            return null;
                        }
                        Col c = new Col(name, call.name().equals("avg") ? "float64" : x.kind);
                        // A group is null only when every one of its values is: taken as none.
                        c.nulls = x.nulls >= 1 ? 1.0 : 0.0;
                        c.changed = true;
                        if (x.negatives != null && x.negatives == 0) {
                            c.negatives = 0.0;
                        }
                        return c;
                    }
                    case "length", "substring", "replace", "concat", "regex_replace", "abs", "round", "floor", "ceil",
                         "date_trunc", "date_part", "nullif" -> {
                        return nullPropagating(a, in, name, call.name().equals("length") || call.name().startsWith("date_part")
                            || call.name().equals("abs") || call.name().equals("round") || call.name().equals("floor")
                            || call.name().equals("ceil") ? "float64" : "text", call.name().equals("concat"),
                            call.name().equals("nullif"));
                    }
                    default -> {
                        return null;
                    }
                }
            }
            case ExprView.Op o -> {
                switch (o.op()) {
                    case "+", "-", "*", "/", "%", "||", "neg" -> {
                        return nullPropagating(o.args(), in, name, o.op().equals("||") ? "text" : "float64", false,
                            false);
                    }
                    case "cast" -> {
                        Col c = derive(o.args().get(0), in, name);
                        if (c == null) {
                            return null;
                        }
                        Col out = new Col(name, castKind(o.detail()));
                        out.nulls = c.nulls;
                        out.blanks = out.text() ? c.blanks : 0.0;
                        out.origin = c.origin;
                        out.changed = true;
                        return out;
                    }
                    default -> {
                        return null;
                    }
                }
            }
        }
    }

    private static String castKind(String type) {
        return switch (type == null ? "" : type) {
            case "int64" -> "int64";
            case "float64" -> "float64";
            case "decimal" -> "decimal";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "timestamp" -> "timestamp";
            default -> "text";
        };
    }

    /** A column null wherever any of {@code args} is ({@code concat} skips nulls; nullif may add some). */
    private Col nullPropagating(List<ExprView> args, Est in, String name, String kind, boolean skipsNulls,
                                boolean addsNulls) {
        double notNull = 1;
        double allNull = 1;
        for (ExprView a : args) {
            if (a instanceof ExprView.Lit l) {
                if (l.value() == null) {
                    notNull = 0;
                } else {
                    allNull = 0;
                }
                continue;
            }
            Col c = derive(a, in, name);
            if (c == null || c.nulls == null) {
                return null;
            }
            notNull *= 1 - c.nulls;
            allNull *= c.nulls;
        }
        if (addsNulls) {
            return null;
        }
        Col out = new Col(name, kind);
        out.changed = true;
        out.nulls = skipsNulls ? 0.0 : 1 - notNull;
        if (skipsNulls && allNull >= 1) {
            out.nulls = 0.0;
        }
        out.blanks = out.text() ? null : 0.0;
        return out;
    }

    private static Col constant(String name, Object value, Double rows) {
        String kind = value == null ? "text" : value instanceof Boolean ? "boolean" : value instanceof Long ? "int64"
            : value instanceof Number ? "float64" : value instanceof String s && s.startsWith("date:") ? "date"
            : value instanceof String s2 && s2.startsWith("timestamp:") ? "timestamp" : "text";
        if (value == null) {
            return Col.allNull(name, kind);
        }
        Col c = new Col(name, kind);
        c.changed = true;
        c.nulls = 0.0;
        String text = literalText(value);
        c.blanks = "text".equals(kind) && text.isBlank() ? 1.0 : 0.0;
        c.distinct = rows == null ? null : 1 / Math.max(1, rows);
        c.top = new LinkedHashMap<>(Map.of(text, 1.0));
        if ("text".equals(kind)) {
            c.patterns = new LinkedHashMap<>(Map.of(ValueShape.of(text), 1.0));
            c.formats = new LinkedHashMap<>();
        }
        if (c.numeric()) {
            c.negatives = ((Number) value).doubleValue() < 0 ? 1.0 : 0.0;
            c.outliers = 0.0;
        }
        return c;
    }

    /**
     * {@code coalesce}: with a literal last, no nulls are left and the literal's shape joins the
     * others; when every part parses the same column with another date pattern (standardising dates),
     * the parts' matching shapes add up; otherwise null only where every part is.
     */
    private Col coalesce(List<ExprView> args, Est in, String name) {
        if (args.isEmpty()) {
            return null;
        }
        Double parsed = parsedShare(args, in);
        if (parsed != null) {
            Col first = derive(args.get(0), in, name);
            if (first == null) {
                return null;
            }
            ExprView.Ref ref = parsedColumn(args.get(0));
            Col source = in.col(ref.column());
            first.nulls = source.nulls + (1 - source.nulls) * (1 - parsed);
            return first;
        }
        Col base = derive(args.get(0), in, name);
        if (base == null) {
            return null;
        }
        ExprView last = args.get(args.size() - 1);
        if (args.size() == 2 && last instanceof ExprView.Lit l && l.value() != null && base.nulls != null) {
            double was = base.nulls;
            Col c = base.copy(name);
            c.changed = true;
            String text = literalText(l.value());
            c.nulls = 0.0;
            double values = 1 - was;
            if (c.patterns != null) {
                c.patterns.replaceAll((k, s) -> s * values);
                c.patterns.merge(ValueShape.of(text), was, Double::sum);
            }
            if (c.top != null) {
                c.top.replaceAll((k, s) -> s * values);
                c.top.merge(text, was, Double::sum);
            }
            if (c.formats != null) {
                c.formats.replaceAll((k, s) -> s * values);
            }
            for (String key : List.of("outliers", "negatives", "future")) {
                Double share = switch (key) {
                    case "outliers" -> c.outliers;
                    case "negatives" -> c.negatives;
                    default -> c.future;
                };
                if (share != null) {
                    double scaled = share * values;
                    switch (key) {
                        case "outliers" -> c.outliers = scaled;
                        case "negatives" -> c.negatives = scaled;
                        default -> c.future = scaled;
                    }
                }
            }
            c.quantiles = null;
            c.distinct = c.distinct == null ? null : c.distinct * values;
            return c;
        }
        double nulls = 1;
        for (ExprView a : args) {
            if (a instanceof ExprView.Lit l) {
                nulls = l.value() == null ? nulls : 0;
                continue;
            }
            Col c = derive(a, in, name);
            if (c == null || c.nulls == null) {
                return null;
            }
            nulls *= c.nulls;
        }
        Col c = new Col(name, base.kind);
        c.changed = true;
        c.nulls = nulls;
        c.blanks = c.text() ? null : 0.0;
        return c;
    }

    /** The column every part of a coalesce parses ({@code [format_date(]parse_date(x, ...)[, ...)]}), or null. */
    private static ExprView.Ref parsedColumn(ExprView v) {
        if (v instanceof ExprView.Call c && c.name().equals("format_date") && !c.args().isEmpty()) {
            return parsedColumn(c.args().get(0));
        }
        if (v instanceof ExprView.Call c && (c.name().equals("parse_date") || c.name().equals("parse_timestamp"))
            && c.args().size() == 2 && c.args().get(0) instanceof ExprView.Ref r) {
            return r;
        }
        return null;
    }

    private static String parsedPattern(ExprView v) {
        if (v instanceof ExprView.Call c && c.name().equals("format_date") && !c.args().isEmpty()) {
            return parsedPattern(c.args().get(0));
        }
        if (v instanceof ExprView.Call c && c.args().size() == 2 && c.args().get(1) instanceof ExprView.Lit l
            && l.value() instanceof String p) {
            return p;
        }
        return null;
    }

    /** When every part parses one column, the share of its values some part can parse; else null. */
    private Double parsedShare(List<ExprView> args, Est in) {
        ExprView.Ref ref = parsedColumn(args.get(0));
        if (ref == null || args.size() < 2) {
            return null;
        }
        Col c = in.col(ref.column());
        if (c == null || c.patterns == null || c.nulls == null) {
            return null;
        }
        List<Pattern> shapes = new ArrayList<>();
        for (ExprView a : args) {
            ExprView.Ref r = parsedColumn(a);
            String p = parsedPattern(a);
            Pattern shape = p == null ? null : patternShape(p, c.collapsed);
            if (r == null || !r.column().equals(ref.column()) || shape == null) {
                return null;
            }
            shapes.add(shape);
        }
        double share = 0;
        double listed = 0;
        for (Map.Entry<String, Double> e : c.patterns.entrySet()) {
            listed += e.getValue();
            if (shapes.stream().anyMatch(s -> s.matcher(e.getKey()).matches())) {
                share += e.getValue();
            }
        }
        return listed <= 0 ? null : Math.min(1, share);
    }

    /** {@code parse_date(x, 'pattern')}: values in other shapes become null. */
    private Col parsed(ExprView.Call call, Est in, String name) {
        List<ExprView> a = call.args();
        if (a.size() != 2 || !(a.get(1) instanceof ExprView.Lit l) || !(l.value() instanceof String fmt)) {
            return null;
        }
        Col x = derive(a.get(0), in, name);
        if (x == null || x.nulls == null) {
            return null;
        }
        Col out = new Col(name, call.name().equals("parse_date") ? "date" : "timestamp");
        out.changed = true;
        out.blanks = 0.0;
        Pattern shape = patternShape(fmt, x.collapsed);
        if (x.patterns == null || shape == null) {
            out.nulls = null;
            return out;
        }
        double ok = 0;
        for (Map.Entry<String, Double> e : x.patterns.entrySet()) {
            if (shape.matcher(e.getKey()).matches()) {
                ok += e.getValue();
            }
        }
        out.nulls = x.nulls + (1 - x.nulls) * (1 - Math.min(1, ok));
        return out;
    }

    /**
     * The value shapes a date pattern reads, as a regular expression over shapes: {@code yyyy} four
     * digits, {@code MM} two, {@code d} one or two, and so on; letters for month and day names.
     * Null for a pattern it does not understand.
     */
    static Pattern patternShape(String fmt, boolean collapsed) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char ch = fmt.charAt(i);
            int j = i;
            while (j < fmt.length() && fmt.charAt(j) == ch) {
                j++;
            }
            int run = j - i;
            if (ch == '\'') {
                int end = fmt.indexOf('\'', i + 1);
                if (end < 0) {
                    return null;
                }
                b.append(Pattern.quote(ValueShape.of(fmt.substring(i + 1, end))));
                i = end + 1;
                continue;
            }
            if (Character.isLetter(ch)) {
                String digits;
                switch (ch) {
                    case 'y', 'u' -> digits = run == 2 ? "9{2}" : "9{4}";
                    case 'M', 'L' -> {
                        if (run >= 3) {
                            b.append(run == 3 ? "Aa{2}" : "Aa+");
                            i = j;
                            continue;
                        }
                        digits = run == 2 ? "9{2}" : "9{1,2}";
                    }
                    case 'd', 'H', 'h', 'm', 's', 'k', 'K' -> digits = run == 2 ? "9{2}" : "9{1,2}";
                    case 'S' -> digits = "9{" + run + "}";
                    case 'E' -> {
                        b.append(run >= 4 ? "Aa+" : "Aa{2}");
                        i = j;
                        continue;
                    }
                    case 'a' -> {
                        b.append("A{2}");
                        i = j;
                        continue;
                    }
                    default -> {
                        return null;
                    }
                }
                b.append(collapsed ? "9\\+" : digits);
            } else {
                b.append(Pattern.quote(String.valueOf(ch).repeat(run)));
            }
            i = j;
        }
        try {
            return Pattern.compile(b.toString());
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** The shape a date pattern writes ({@code yyyy-MM-dd} → {@code 9999-99-99}); null when it varies or is unknown. */
    static String formatShape(String fmt) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char ch = fmt.charAt(i);
            int j = i;
            while (j < fmt.length() && fmt.charAt(j) == ch) {
                j++;
            }
            int run = j - i;
            if (ch == '\'') {
                int end = fmt.indexOf('\'', i + 1);
                if (end < 0) {
                    return null;
                }
                b.append(ValueShape.of(fmt.substring(i + 1, end)));
                i = end + 1;
                continue;
            }
            if (Character.isLetter(ch)) {
                switch (ch) {
                    case 'y', 'u' -> b.append(run == 2 ? "99" : "9999");
                    case 'M', 'd', 'H', 'm', 's' -> {
                        if (run != 2) {
                            return null;
                        }
                        b.append("99");
                    }
                    case 'S' -> b.append("9".repeat(run));
                    default -> {
                        return null;
                    }
                }
            } else {
                b.append(String.valueOf(ch).repeat(run));
            }
            i = j;
        }
        return b.toString();
    }

    /** {@code select}: keep or drop, rename, cast; the values of what is kept stay as they were. */
    private Est select(PlanNode s, Est in) {
        Est e = in.copy();
        if (e.cols == null) {
            return e;
        }
        List<String> keep = list(s.config().get("keep"));
        List<String> drop = list(s.config().get("drop"));
        if (keep != null) {
            e.cols.keySet().retainAll(new HashSet<>(keep));
        }
        if (drop != null) {
            drop.forEach(e.cols::remove);
        }
        if (s.config().get("cast") instanceof Map<?, ?> cast) {
            for (Map.Entry<?, ?> c : cast.entrySet()) {
                Col col = e.cols.get(String.valueOf(c.getKey()));
                if (col != null) {
                    Col out = new Col(col.name, castKind(String.valueOf(c.getValue()).strip().toLowerCase(Locale.ROOT)));
                    out.nulls = col.nulls;
                    out.blanks = out.text() ? col.blanks : 0.0;
                    out.origin = col.origin;
                    out.changed = true;
                    e.cols.put(col.name, out);
                }
            }
        }
        if (s.config().get("rename") instanceof Map<?, ?> rename) {
            LinkedHashMap<String, Col> renamed = new LinkedHashMap<>();
            Map<String, String> to = new HashMap<>();
            rename.forEach((k, v) -> to.put(String.valueOf(k), String.valueOf(v)));
            e.cols.forEach((k, v) -> {
                String n = to.getOrDefault(k, k);
                renamed.put(n, v.copy(n));
            });
            e.cols = renamed;
            e.key = e.key.stream().map(k -> to.getOrDefault(k, k)).toList();
        }
        e.key = e.key.stream().filter(e.cols::containsKey).toList();
        return e;
    }

    static List<String> list(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).map(String::strip).toList();
        }
        String t = String.valueOf(v).strip();
        if (t.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(t.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    // ---- lookup ----

    /**
     * {@code lookup}: the share of main rows with no match is the main key's nulls plus, of the rest,
     * the share of its distinct values missing from the lookup side's key, from the stored
     * relationship or the two columns' sketches. Unmatched rows are kept with empty lookup columns
     * ({@code keep}), dropped, or sent to {@code rejected}.
     */
    private Map<String, Est> lookup(PlanNode s, Est main, Est side) {
        Map<String, Object> config = s.config();
        Map<String, String> on = new LinkedHashMap<>();
        if (config.get("on") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> on.put(String.valueOf(k), String.valueOf(v)));
        }
        String join = PipelineSources.text(config, "join");
        String unmatched = PipelineSources.text(config, "unmatched");
        if (unmatched == null) {
            unmatched = "inner".equals(join) ? "drop" : "keep";
        }
        Double orphans = null;
        String why = "which main rows find a match";
        if (on.size() == 1) {
            Map.Entry<String, String> k = on.entrySet().iterator().next();
            Col mk = main.col(k.getKey());
            Col lk = side.col(k.getValue());
            if (mk != null && lk != null && mk.origin != null && lk.origin != null && mk.nulls != null) {
                Double o = catalogue.orphanShare(mk.origin.urn(), mk.origin.field(), lk.origin.urn(), lk.origin.field());
                if (o != null) {
                    orphans = mk.nulls + (1 - mk.nulls) * Math.max(0, Math.min(1, o));
                    if (side.narrowed) {
                        main.assumptions.add("the rows reaching " + s.id() + "'s lookup side hold every key its asset "
                            + "has; fewer would leave more rows unmatched");
                    }
                    main.assumptions.add("the share of " + k.getKey() + "'s rows without a match is the share of its "
                        + "distinct values without one (from the stored relationship)");
                } else {
                    why = "how many " + k.getKey() + " values are missing from " + lk.origin.urn() + "." + lk.origin.field()
                        + " (no relationship or sketches stored)";
                }
            }
        } else if (on.size() > 1) {
            why = "which rows match on a key of several columns";
        }
        List<String> keep = list(config.get("keep"));
        Est out = main.copy();
        out.unknown.addAll(side.unknown);
        out.assumptions.addAll(side.assumptions);
        if (out.cols != null) {
            List<String> names = keep != null ? keep : side.cols == null ? List.of()
                : side.cols.keySet().stream().filter(n -> !on.containsValue(n)).toList();
            for (String n : names) {
                Col lc = side.col(n);
                Col c = lc == null ? Col.unknown(n, "text") : lc.copy(n);
                c.distinct = null;
                c.accepted = lc == null ? Set.of() : lc.accepted;
                if (unmatched.equals("keep")) {
                    if (orphans == null || c.nulls == null) {
                        c.nulls = null;
                    } else {
                        double u = orphans;
                        c.nulls = u + (1 - u) * c.nulls;
                        if (c.blanks != null) {
                            c.blanks = c.blanks * (1 - u);
                        }
                    }
                }
                if (!out.cols.containsKey(n)) {
                    out.cols.put(n, c);
                }
            }
        }
        Map<String, Est> ports = new LinkedHashMap<>();
        if (unmatched.equals("keep")) {
            if (orphans == null) {
                out.unknown.add(why + ", so how complete the columns " + s.id() + " adds are");
            }
            ports.put("out", out);
            Est none = main.copy();
            none.rows = 0.0;
            ports.put("rejected", none);
            return ports;
        }
        out.scale(orphans == null ? null : 1 - orphans);
        out.narrowed = true;
        if (orphans == null) {
            out.unknown.add(why);
        }
        for (String mainKey : on.keySet()) {
            Col c = out.col(mainKey);
            if (c != null) {
                withoutNulls(c);
            }
        }
        ports.put("out", out);
        Est rejected = main.copy();
        rejected.scale(unmatched.equals("rejected") ? orphans : 0.0);
        rejected.narrowed = true;
        if (orphans == null && unmatched.equals("rejected")) {
            rejected.unknown.add(why);
        }
        ports.put("rejected", rejected);
        return ports;
    }

    // ---- aggregate, union, dedupe, limit ----

    /** {@code aggregate}: one row per group (from the group column's distinct values); groups are unique. */
    private Est aggregate(PlanNode s, Est in) {
        Est e = new Est();
        e.unknown.addAll(in.unknown);
        e.assumptions.addAll(in.assumptions);
        e.cols = new LinkedHashMap<>();
        List<String> by = list(s.config().get("group_by"));
        if (by == null) {
            by = List.of();
        }
        if (by.isEmpty()) {
            e.rows = 1.0;
        } else if (by.size() == 1) {
            Col g = in.col(by.get(0));
            if (g != null && g.distinct != null && g.nulls != null && in.rows != null) {
                e.rows = Math.max(1, (1 - g.nulls) * in.rows * g.distinct + (g.nulls > 0 ? 1 : 0));
            } else {
                e.unknown.add("how many groups " + s.id() + " makes");
            }
        } else {
            e.unknown.add("how many groups " + s.id() + " makes from several columns");
        }
        for (String g : by) {
            Col c = in.col(g);
            Col out = c == null ? Col.unknown(g, "text") : c.copy();
            out.changed = true;
            if (c != null && c.nulls != null) {
                // One group for the empty values: a share of the groups only when their number is known.
                out.nulls = c.nulls == 0 ? 0.0 : e.rows != null ? 1 / e.rows : null;
                out.blanks = out.blanks == null ? null : Math.min(out.blanks, e.rows == null ? 1 : 1 / e.rows);
            }
            out.top = null;
            out.outliers = null;
            out.quantiles = null;
            out.distinct = by.size() == 1 ? 1.0 : null;
            e.cols.put(g, out);
        }
        e.key = by.size() == 1 ? by : List.of();
        if (s.config().get("values") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> v : values.entrySet()) {
                String name = String.valueOf(v.getKey());
                Col c;
                try {
                    c = derive(Expression.parse(String.valueOf(v.getValue())).view(), in, name);
                } catch (ExprException ex) {
                    c = null;
                }
                if (c == null) {
                    c = Col.unknown(name, "float64");
                    e.unknown.add(name + " as " + s.id() + " works it out");
                } else {
                    c.distinct = null;
                    c.top = null;
                    c.patterns = null;
                    c.quantiles = null;
                    c.outliers = null;
                }
                c.name = name;
                e.cols.put(name, c);
            }
        }
        e.assumptions.add("a group is empty in a column only when all its rows are");
        return e;
    }

    /** {@code union}: rows add up; each column's shares are weighted by its inputs' rows (missing columns are null). */
    static Est union(List<Est> inputs) {
        Est e = new Est();
        if (inputs.isEmpty()) {
            e.unknown.add("the rows of the union's inputs");
            return e;
        }
        if (inputs.size() == 1) {
            return inputs.get(0).copy();
        }
        boolean rowsKnown = inputs.stream().allMatch(i -> i.rows != null);
        e.rows = rowsKnown ? inputs.stream().mapToDouble(i -> i.rows).sum() : null;
        inputs.forEach(i -> {
            e.unknown.addAll(i.unknown);
            e.assumptions.addAll(i.assumptions);
            e.narrowed |= i.narrowed;
        });
        if (!rowsKnown) {
            e.unknown.add("how many rows each input of the union sends, so how its columns mix");
        }
        if (inputs.stream().anyMatch(i -> i.cols == null)) {
            return e;
        }
        e.cols = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        inputs.forEach(i -> names.addAll(i.cols.keySet()));
        double total = rowsKnown ? Math.max(e.rows, 1e-9) : 1;
        for (String n : names) {
            Col first = inputs.stream().map(i -> i.col(n)).filter(Objects::nonNull).findFirst().orElseThrow();
            Col c = new Col(n, first.kind);
            if (!rowsKnown) {
                e.cols.put(n, c);
                continue;
            }
            double nulls = 0;
            double blanks = 0;
            boolean nullsKnown = true;
            boolean blanksKnown = true;
            Map<String, Double> patterns = new LinkedHashMap<>();
            boolean patternsKnown = true;
            boolean collapsed = false;
            Map<String, Double> formats = new LinkedHashMap<>();
            double values = 0;
            Set<String> accepted = null;
            Origin origin = first.origin;
            for (Est i : inputs) {
                double w = i.rows / total;
                Col x = i.col(n);
                if (x == null) {
                    nulls += w;
                    continue;
                }
                if (x.nulls == null) {
                    nullsKnown = false;
                    continue;
                }
                nulls += w * x.nulls;
                if (x.blanks == null) {
                    blanksKnown = false;
                } else {
                    blanks += w * x.blanks;
                }
                double v = w * (1 - x.nulls);
                values += v;
                if (x.patterns == null) {
                    patternsKnown = false;
                } else {
                    collapsed |= x.collapsed;
                    x.patterns.forEach((k, s) -> patterns.merge(k, s * v, Double::sum));
                    if (x.formats != null) {
                        x.formats.forEach((k, s) -> formats.merge(k, s * v, Double::sum));
                    }
                }
                accepted = accepted == null ? x.accepted : accepted.equals(x.accepted) ? accepted : Set.of();
                if (!Objects.equals(origin, x.origin)) {
                    origin = null;
                }
            }
            if (nullsKnown) {
                c.nulls = nulls;
                c.blanks = blanksKnown ? blanks : null;
            }
            if (nullsKnown && patternsKnown && values > 0) {
                double vv = values;
                c.patterns = new LinkedHashMap<>();
                patterns.forEach((k, s) -> c.patterns.put(k, s / vv));
                c.formats = new LinkedHashMap<>();
                formats.forEach((k, s) -> c.formats.put(k, s / vv));
                c.collapsed = collapsed;
            }
            c.accepted = accepted == null ? Set.of() : accepted;
            c.origin = origin;
            e.cols.put(n, c);
        }
        e.assumptions.add("a union may repeat values its inputs share, so its uniqueness is not predicted");
        return e;
    }

    /** {@code dedupe}: one row per key value (from the key's distinct values); the repeats go to {@code rejected}. */
    private Map<String, Est> dedupe(PlanNode s, Est in) {
        List<String> key = list(s.config().get("key"));
        Est out = in.copy();
        Double keptShare = null;
        if (key != null && key.size() == 1) {
            Col k = in.col(key.get(0));
            if (k != null && k.distinct != null && k.nulls != null) {
                double rows = in.rows == null ? NOMINAL_ROWS : in.rows;
                double distinctRows = (1 - k.nulls) * k.distinct + (k.nulls > 0 ? 1 / rows : 0);
                keptShare = Math.min(1, distinctRows);
                Col kc = out.col(key.get(0));
                kc.distinct = 1.0;
                kc.distinctExact = true;
                kc.nulls = k.nulls > 0 ? Math.min(1, 1 / Math.max(1, rows * keptShare)) : 0.0;
                out.key = key;
            }
        } else if (in.cols != null) {
            List<String> cols = key == null ? List.copyOf(in.cols.keySet()) : key;
            for (String c : cols) {
                Col x = in.col(c);
                if (x != null && x.distinct != null && x.distinct >= 1 && x.nulls != null && x.nulls == 0) {
                    keptShare = 1.0; // a column that is unique already: no row repeats
                }
            }
        }
        Map<String, Est> ports = new LinkedHashMap<>();
        out.scale(keptShare);
        if (keptShare == null) {
            out.unknown.add("how many rows repeat the key of " + s.id());
        } else if (keptShare < 1) {
            out.narrowed = true;
            out.assumptions.add("other columns are spread the same in the rows " + s.id() + " keeps");
        }
        ports.put("out", out);
        Est rejected = in.copy();
        rejected.scale(keptShare == null ? null : 1 - keptShare);
        ports.put("rejected", rejected);
        return ports;
    }

    private static Est limit(PlanNode s, Est in) {
        Est e = in.copy();
        long n = -1;
        Object rows = s.config().get("rows");
        if (rows instanceof Number num) {
            n = num.longValue();
        } else if (rows instanceof String t) {
            try {
                n = Long.parseLong(t.strip());
            } catch (NumberFormatException ex) {
                n = -1;
            }
        }
        if (n >= 0) {
            e.rows = e.rows == null ? null : Math.min(e.rows, n);
            e.assumptions.add("the first " + countWords(n) + " rows are like the rest");
        }
        return e;
    }

    // ---- gate ----

    private Map<String, Est> gate(Plan plan, PlanNode s, Est in, List<Predicted> predicted) {
        Map<String, Object> config = s.config();
        String onFail = PipelineSources.text(config, "on_fail");
        onFail = onFail == null ? "stop" : onFail.toLowerCase(Locale.ROOT);
        List<QualityGate.GateRule> rules = new ArrayList<>();
        String assetUrn = null;
        Map<String, String> types = new LinkedHashMap<>();
        plan.nodes().forEach(x -> types.put(x.id(), x.type()));
        List<Edge> edges = plan.edges().stream().map(x -> new Edge(new PortRef(x.fromNode(), x.fromPort()),
            new PortRef(x.toNode(), x.toPort()))).toList();
        Est checked = in.copy();
        if (GateNodeFactory.assetRules(config)) {
            GateNodeFactory.Upstream up = GateNodeFactory.upstream(s.id(), config, edges, types);
            if (up.read() != null) {
                assetUrn = plan.reads().get(up.read());
                if (assetUrn != null) {
                    for (Rule r : catalogue.rules(assetUrn)) {
                        if (r.type() != RuleType.REFERENCES && r.type() != RuleType.CUSTOM_SQL) {
                            rules.add(new QualityGate.GateRule(r, "asset"));
                        }
                    }
                }
            }
        }
        Map<String, String> kinds = new LinkedHashMap<>();
        if (in.cols != null) {
            in.cols.forEach((k, v) -> kinds.put(k, v.kind));
        }
        try {
            for (Rule r : GateRules.read(config.get("rules"), in.cols == null ? null : kinds)) {
                rules.add(new QualityGate.GateRule(r, "inline"));
            }
        } catch (IllegalArgumentException e) {
            checked.unknown.add("the rules written on " + s.id() + " (" + e.getMessage() + ")");
        }
        Map<Long, LastResult> last = assetUrn == null ? Map.of() : catalogue.lastResults(assetUrn);
        ProfileFacts assetFacts = assetUrn == null ? null : catalogue.facts(assetUrn);
        double rows = in.rows == null ? NOMINAL_ROWS : in.rows;
        long total = Math.max(1, Math.round(rows));
        List<PredictedRule> out = new ArrayList<>();
        List<Scores.RuleResult> forScore = new ArrayList<>();
        List<UnaryOperator<Est>> passing = new ArrayList<>();
        double passShare = 1;
        boolean passKnown = true;
        for (QualityGate.GateRule g : rules) {
            Rule r = g.rule();
            Estimate est = ruleEstimate(r, g.origin(), in, last.get(r.id()), assetFacts);
            Rule.Status status;
            if (est.share() == null) {
                status = Rule.Status.ERROR;
                checked.unknown.add("how many rows fail rule '" + r.name() + "' (" + est.basis() + ")");
            } else {
                long failing = r.type().wholeAsset() ? (est.share() > 0 ? 1 : 0) : Math.round(est.share() * total);
                status = r.status(failing, r.type().wholeAsset() ? 1 : total);
                forScore.add(new Scores.RuleResult(r.field(), r.dimension(), r.name(), status, failing,
                    r.type().wholeAsset() ? 1 : total, r.type().wholeAsset()));
                if (!r.type().wholeAsset()) {
                    passShare *= 1 - est.share();
                }
            }
            if (!r.type().wholeAsset()) {
                if (est.share() == null) {
                    passKnown = false;
                }
                UnaryOperator<Est> after = passed(r);
                if (after != null) {
                    passing.add(after);
                }
            }
            out.add(new PredictedRule(r.name(), r.type().id(), r.field(), r.dimension().id(), g.origin(),
                est.share() == null ? null : round4(est.share()), status.id(), est.basis()));
        }
        QualityGate.Score score = forScore.isEmpty() ? new QualityGate.Score(null, Map.of(), List.of())
            : QualityGate.score(forScore);
        Double failingShare = passKnown ? 1 - passShare : null;
        Est outRows = checked.copy();
        Est quarantined = checked.copy();
        String decision;
        boolean anyFail = forScore.stream().anyMatch(x -> x.status() == Rule.Status.FAIL);
        boolean wholeFail = forScore.stream().anyMatch(x -> x.wholeAsset() && x.status() == Rule.Status.FAIL);
        Double minScore = GateNodeFactory.share(config.get("min_score"));
        Double maxShare = GateNodeFactory.share(config.get("max_failing_share"));
        boolean breach = (minScore != null && score.overall() != null && score.overall() < minScore)
            || (maxShare != null && failingShare != null && failingShare > maxShare);
        switch (onFail) {
            case "quarantine" -> {
                outRows.scale(failingShare == null ? null : 1 - failingShare);
                outRows.narrowed = true;
                for (UnaryOperator<Est> p : passing) {
                    outRows = p.apply(outRows);
                }
                quarantined.scale(failingShare);
                if (outRows.cols != null && !passing.isEmpty()) {
                    outRows.assumptions.add("rows fail " + s.id() + "'s rules independently of each other");
                }
                decision = breach || wholeFail ? "stopped" : failingShare == null ? null
                    : failingShare > 0 ? "quarantined" : "passed";
            }
            case "warn" -> {
                quarantined.rows = 0.0;
                decision = forScore.isEmpty() ? null : anyFail || forScore.stream()
                    .anyMatch(x -> x.status() == Rule.Status.WARN) ? "warned" : "passed";
            }
            default -> {
                quarantined.rows = 0.0;
                decision = anyFail || breach ? "stopped" : forScore.isEmpty() || !passKnown ? null : "passed";
            }
        }
        if ("stopped".equals(decision)) {
            outRows.assumptions.add(s.id() + " is predicted to stop the run");
        }
        Long rowsIn = in.rows == null ? null : Math.round(in.rows);
        Long rowsQ = quarantined.rows == null ? null : Math.round(quarantined.rows);
        Long rowsP = outRows.rows == null ? null : Math.round(outRows.rows);
        PredictedGate gate = new PredictedGate(onFail, rowsIn, rowsP, rowsQ, decision, List.copyOf(out));
        List<String> unknown = words(checked.unknown);
        if (rules.isEmpty()) {
            unknown = List.of("the rules " + s.id() + " checks: " + (assetUrn == null ? "it has none to predict"
                : assetUrn + " has no enabled rules and none are written on the gate"));
        }
        predicted.add(new Predicted(s.id(), "gate", assetUrn, score.overall(), score.dimensions(), score.reasons(),
            unknown, words(checked.assumptions), rowsIn, gate));
        Map<String, Est> ports = new LinkedHashMap<>();
        ports.put("out", outRows);
        ports.put("quarantine", quarantined);
        return ports;
    }

    /** A rule's estimated failing share (of rows checked; 0 or 1 for checks of the whole data) and its basis. */
    record Estimate(Double share, String basis) {
    }

    /**
     * How many rows are expected to fail a rule: from the profile of the column as it reaches the
     * gate; for one of the asset's rules on a column that arrives unchanged, from its last result
     * when the profile cannot tell; otherwise unknown.
     */
    Estimate ruleEstimate(Rule r, String origin, Est in, LastResult last, ProfileFacts asset) {
        Col c = r.field() == null ? null : in.col(r.field());
        Estimate fromProfile = profileEstimate(r, c, in, asset);
        if (fromProfile.share() != null) {
            return fromProfile;
        }
        boolean unchanged = c != null && c.origin != null && !c.changed && "asset".equals(origin)
            && c.origin.field().equals(r.field());
        if (last != null && last.status() != Rule.Status.ERROR && (unchanged || r.type().wholeAsset())
            && last.total() > 0) {
            double share = r.type().wholeAsset() ? (last.status() == Rule.Status.FAIL ? 1 : 0)
                : (double) last.failing() / last.total();
            return new Estimate(share, "its last result on the asset");
        }
        return fromProfile;
    }

    private static Estimate profileEstimate(Rule r, Col c, Est in, ProfileFacts asset) {
        JsonNode p = r.params();
        if (r.type() != RuleType.ROW_COUNT && r.type() != RuleType.ROW_COUNT_CHANGE && r.type() != RuleType.FRESHNESS
            && c == null) {
            return new Estimate(null, r.field() == null ? "it names no column" : r.field() + " does not reach the gate");
        }
        switch (r.type()) {
            case NOT_NULL -> {
                if (c.nulls == null) {
                    return new Estimate(null, r.field() + " has no profile");
                }
                boolean blank = p != null && p.path("blankIsMissing").asBoolean(false);
                if (blank && c.blanks == null) {
                    return new Estimate(null, "how many values of " + r.field() + " are blank");
                }
                return new Estimate(Math.min(1, c.nulls + (blank ? c.blanks : 0)), "profile");
            }
            case UNIQUE -> {
                List<String> fields = new ArrayList<>();
                if (p != null && p.path("fields").isArray()) {
                    p.get("fields").forEach(f -> fields.add(f.asText()));
                }
                if (fields.size() > 1) {
                    for (String f : fields) {
                        Col x = in.col(f);
                        if (x != null && x.distinct != null && x.distinct >= 1 && x.nulls != null && x.nulls == 0) {
                            return new Estimate(0.0, "profile");
                        }
                    }
                    return new Estimate(null, "repeats of a key of several columns");
                }
                if (c.distinct == null || c.nulls == null) {
                    return new Estimate(null, "the distinct values of " + r.field());
                }
                if (!c.distinctExact && c.distinct > 0.98) {
                    return new Estimate(0.0, "profile (distinct count estimated)");
                }
                return new Estimate((1 - c.nulls) * (1 - c.distinct), "profile");
            }
            case ALLOWED_VALUES -> {
                Set<String> allowed = new LinkedHashSet<>();
                if (p != null) {
                    p.path("values").forEach(v -> allowed.add(v.asText()));
                }
                Double in2 = topShare(c, allowed);
                if (in2 == null || c.nulls == null) {
                    return new Estimate(null, "the values of " + r.field() + " (its most common values are not kept or do "
                        + "not cover every value)");
                }
                return new Estimate((1 - c.nulls) * (1 - in2), "most common values");
            }
            case MATCHES_PATTERN -> {
                String regex = p == null ? null : p.path("regex").asText(null);
                Double share = regex == null ? null : shapeShare(c, regex);
                if (share == null || c.nulls == null) {
                    return new Estimate(null, "which values of " + r.field() + " match (its value shapes are not known "
                        + "exactly)");
                }
                return new Estimate((1 - c.nulls) * (1 - share), "value shapes");
            }
            case IN_RANGE -> {
                if (c.quantiles == null || c.nulls == null) {
                    return new Estimate(null, "the spread of " + r.field());
                }
                Double min = bound(p, "min", c);
                Double max = bound(p, "max", c);
                double outside = 0;
                if (min != null) {
                    outside += below(c.quantiles, min, false);
                }
                if (max != null) {
                    outside += 1 - below(c.quantiles, max, true);
                }
                return new Estimate((1 - c.nulls) * Math.min(1, outside), "quantiles");
            }
            case ROW_COUNT -> {
                if (in.rows == null) {
                    return new Estimate(null, "the row count");
                }
                Double min = p == null || !p.hasNonNull("min") ? null : p.get("min").asDouble();
                Double max = p == null || !p.hasNonNull("max") ? null : p.get("max").asDouble();
                boolean fails = (min != null && in.rows < min) || (max != null && in.rows > max);
                return new Estimate(fails ? 1.0 : 0.0, "row count");
            }
            case ROW_COUNT_CHANGE -> {
                if (in.rows == null || asset == null || asset.rowCount() == null || asset.rowCount() <= 0) {
                    return new Estimate(null, "the row count and the asset's last count");
                }
                double change = Math.abs(in.rows - asset.rowCount()) / asset.rowCount();
                double limit = p == null ? 0 : p.path("maxChange").asDouble(0);
                return new Estimate(change > limit ? 1.0 : 0.0, "row count");
            }
            case FRESHNESS -> {
                Col f = r.field() == null ? null : in.col(r.field());
                if (f == null || f.quantiles == null || !f.temporal()) {
                    return new Estimate(null, "the latest value");
                }
                double latest = f.quantiles[f.quantiles.length - 1];
                Instant at = "date".equals(f.kind) ? LocalDate.ofEpochDay((long) latest).atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC) : Instant.EPOCH.plusNanos((long) (latest * 1000));
                double hours = p == null ? 0 : p.path("maxAgeHours").asDouble(0);
                Instant observed = asset == null || asset.observedAt() == null ? Instant.now() : asset.observedAt();
                boolean stale = Duration.between(at, observed).toMinutes() > hours * 60;
                return new Estimate(stale ? 1.0 : 0.0, "the latest value at the last profile");
            }
            default -> {
                return new Estimate(null, "what " + r.type().id() + " finds");
            }
        }
    }

    private static Double bound(JsonNode p, String key, Col c) {
        if (p == null || !p.hasNonNull(key)) {
            return null;
        }
        JsonNode v = p.get(key);
        if (v.isNumber()) {
            return v.asDouble();
        }
        String t = v.asText();
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            Double d = number(new ExprView.Lit("date:" + t), c);
            return d;
        }
    }

    /** What is certain about the rows that pass a rule, for a quarantining gate's {@code out}. */
    private static UnaryOperator<Est> passed(Rule r) {
        String f = r.field();
        JsonNode p = r.params();
        return switch (r.type()) {
            case NOT_NULL -> e -> change(e, f, c -> {
                Col y = withoutNulls(c);
                if (p != null && p.path("blankIsMissing").asBoolean(false)) {
                    y.blanks = 0.0;
                }
                return y;
            });
            case UNIQUE -> p != null && p.path("fields").size() > 1 ? null : e -> change(e, f, c -> {
                c.distinct = 1.0;
                c.distinctExact = true;
                return c;
            });
            case ALLOWED_VALUES -> p == null ? null : e -> change(e, f, c -> {
                Set<String> allowed = new HashSet<>();
                p.path("values").forEach(v -> allowed.add(v.asText()));
                if (c.top != null) {
                    c.top.keySet().removeIf(k -> !allowed.contains(k));
                    normalise(c.top);
                }
                c.distinct = null;
                return c;
            });
            case MATCHES_PATTERN -> p == null ? null : e -> change(e, f, c -> {
                try {
                    return keepShapes(c, Pattern.compile(p.path("regex").asText()), true);
                } catch (PatternSyntaxException ex) {
                    return c;
                }
            });
            case IN_RANGE -> e -> change(e, f, c -> {
                c.quantiles = null;
                c.outliers = null;
                if (p != null && p.hasNonNull("min") && p.get("min").asDouble(-1) >= 0) {
                    c.negatives = 0.0;
                }
                return c;
            });
            default -> null;
        };
    }

    private static Est change(Est e, String field, UnaryOperator<Col> f) {
        Col c = e.col(field);
        if (c != null) {
            e.cols.put(field, f.apply(c.copy()));
        }
        return e;
    }

    // ---- write: the predicted score of what reaches it ----

    private Predicted write(Plan plan, PlanNode s, Est in) {
        String urn = WriteNodeFactory.urn(s.config());
        List<String> key = WriteNodeFactory.key(s.config());
        RunQuality.Score score = score(in, urn, key == null ? List.of() : key);
        List<String> unknown = new ArrayList<>(words(in.unknown));
        if (score.overall() == null) {
            unknown.add(0, "anything about the rows reaching " + s.id() + ": scan the sources it reads, so their "
                + "profiles are in the catalogue");
        }
        return new Predicted(s.id(), "output", urn, score.overall(), score.dimensions(), score.reasons(),
            words(unknown), words(in.assumptions), in.rows == null ? null : Math.round(in.rows), null);
    }

    /**
     * The score of an estimate, as a run scores what it wrote ({@link RunQuality#score}): the same
     * measures over the estimated profile, keeping only the dimensions the estimate can tell.
     */
    static RunQuality.Score score(Est e, String urn, List<String> key) {
        if (e.cols == null) {
            return new RunQuality.Score(null, Map.of(), List.of());
        }
        long rows = e.rows == null ? NOMINAL_ROWS : Math.max(1, Math.round(e.rows));
        List<ProfileFacts.FieldFacts> fields = new ArrayList<>();
        Map<String, Set<Dimension>> known = new HashMap<>();
        int position = 0;
        for (Col c : e.cols.values()) {
            position++;
            if (c.nulls == null) {
                continue;
            }
            fields.add(new ProfileFacts.FieldFacts(c.name, position, c.kind, c.kind, true, stats(c, rows), c.accepted));
            known.put(c.name, dimensions(c));
        }
        ProfileFacts facts = new ProfileFacts(0, urn, Instant.now(), rows, true, true, key, fields);
        List<Measures.Measure> measures = Measures.of(facts, List.of(), List.of(), Duration.ofDays(1)).stream()
            .filter(m -> known.getOrDefault(m.field(), Set.of()).contains(m.dimension())).toList();
        return RunQuality.asset(Scores.of(measures, List.of(), List.of()));
    }

    /** The dimensions an estimated column can be judged on: those whose evidence is known. */
    static Set<Dimension> dimensions(Col c) {
        Set<Dimension> d = new HashSet<>();
        if (c.nulls != null && (!c.text() || c.blanks != null)) {
            d.add(Dimension.COMPLETENESS);
        }
        if (c.distinct != null) {
            d.add(Dimension.UNIQUENESS);
        }
        if (c.patterns != null && c.formats != null) {
            d.add(Dimension.VALIDITY);
        }
        if ((c.text() && c.patterns != null && c.formats != null) || (c.numeric() && c.negatives != null)
            || (c.temporal() && c.future != null)) {
            d.add(Dimension.CONSISTENCY);
        }
        if ((c.numeric() && c.outliers != null) || (c.text() && c.top != null && c.distinct != null)) {
            d.add(Dimension.ACCURACY);
        }
        return d;
    }

    /** The estimated column as profile statistics over {@code rows} rows, as a profile writes them. */
    static ObjectNode stats(Col c, long rows) {
        ObjectNode s = J.objectNode();
        long nulls = Math.round(c.nulls * rows);
        long values = Math.max(0, rows - nulls);
        s.put("rows", rows);
        s.put("nulls", nulls);
        if (c.blanks != null) {
            s.put("blanks", Math.round(c.blanks * rows));
        }
        if (c.distinct != null) {
            s.put("distinct", Math.round(c.distinct * values));
            s.put("distinct_exact", Boolean.toString(c.distinctExact));
        }
        if (c.patterns != null) {
            ArrayNode a = s.putArray("patterns");
            c.patterns.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(x -> a.addObject().put("shape", x.getKey()).put("count", Math.round(x.getValue() * values)));
            s.put("patterns_collapsed", Boolean.toString(c.collapsed));
        }
        if (c.formats != null) {
            ArrayNode a = s.putArray("formats");
            c.formats.forEach((k, v) -> a.addObject().put("format", k).put("count", Math.round(v * values)));
        }
        if (c.outliers != null) {
            s.put("outliers", Math.round(c.outliers * values));
            if (c.fenceLow != null) {
                s.put("fence_low", c.fenceLow);
                s.put("fence_high", c.fenceHigh);
            }
        }
        if (c.negatives != null) {
            s.put("negatives", Math.round(c.negatives * values));
        }
        if (c.future != null) {
            s.put("after_now", Math.round(c.future * values));
        }
        if (c.top != null && c.distinct != null) {
            ArrayNode a = s.putArray("top");
            c.top.forEach((k, v) -> a.addObject().put("value", k).put("count", Math.round(v * values)));
        }
        return s;
    }

    // ---- words ----

    static List<String> words(java.util.Collection<String> all) {
        List<String> out = new ArrayList<>();
        for (String w : all) {
            if (out.size() == MAX_WORDS) {
                break;
            }
            if (!out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static String countWords(long n) {
        return String.format(Locale.UK, "%,d", n);
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
