package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.server.ai.AiException;
import io.kilnmark.server.ai.ModelClient;
import io.kilnmark.server.quality.Rule;

/**
 * Suggestions for improving a pipeline's predicted quality (docs/design/data-quality.md section
 * 8): nodes to add before a write, each with its predicted effect on that write's score. Kilnmark
 * finds candidates from the predicted metadata by rule, with no model: trim padded text, standardise
 * competing date formats, deduplicate on a key that repeats, drop rows whose key has no match in
 * the related asset, set aside rows outside a column's accepted patterns or without a key. When a
 * model is routed to {@code pipeline_suggestions}, it ranks and explains them and may add its own,
 * which are checked like the rest before they are shown: only node types the server has, settings
 * those nodes take, and columns that reach that point.
 *
 * <p>What the model sees is metadata only, and only of sources whose {@code ai_egress} allows that
 * model ({@link Model#refusal}): asset and column names, kinds, shares (nulls, blanks, distinct
 * values, value shapes, formats) and accepted shapes. Never a value from the data, and nothing of a
 * column whose source does not allow it, nor of a column computed in the pipeline.
 */
final class PipelineAdvisor {

    /** The model, as suggestions use it: its rule for each source, and one call. */
    interface Model {
        String name();

        /** Why this model may not see {@code source}'s metadata, or null when it may. */
        String refusal(Records.Source source);

        /**
         * One chat call carrying the metadata of {@code source} (the strictest of the sources whose
         * metadata it holds), checked again by the gateway.
         *
         * @throws AiException when the call is refused, fails or times out
         */
        String chat(Records.Source source, ModelClient.ChatCall call);
    }

    /** A node to add: its id, type, settings as YAML holds them, and inputs (port to {@code node[.port]}). */
    record NewNode(String id, String type, Map<String, Object> settings, Map<String, String> inputs) {
    }

    /**
     * Where the nodes go: the connection from {@code from} into {@code before}'s {@code port} is
     * cut; the first new node reads {@code from}, and {@code before} then reads {@code through}.
     */
    record Placement(String before, String port, String from, String through) {
    }

    /**
     * The predicted score of {@code output} without the suggestion and with it (estimates; null when
     * unknown), and {@code note}: what it does that the score does not show, or null.
     */
    record Effect(String output, Double before, Double after, Map<String, Double> dimensionsBefore,
                  Map<String, Double> dimensionsAfter, Long rowsBefore, Long rowsAfter, String note) {

        Double gain() {
            return before == null || after == null ? null : after - before;
        }
    }

    /** {@code source} is {@code rules} (Kilnmark's own) or {@code ai}; {@code rank} orders the list. */
    record Suggestion(String id, String kind, String title, String why, List<NewNode> nodes, Placement placement,
                      Effect effect, String source) {
    }

    /**
     * What the model did: {@code off} (no model is set), {@code not_asked}, {@code used},
     * {@code withheld} (no source's metadata may go to it) or {@code unavailable} (refused, failed or
     * too slow); {@code message} in words for anyone.
     */
    record AiStatus(String state, String message, String model) {
    }

    record Advice(List<Suggestion> suggestions, AiStatus ai) {
    }

    /** Node types a suggestion may add. */
    static final Set<String> AI_TYPES = Set.of("compute", "filter", "select", "dedupe", "gate");
    static final Set<String> RULE_TYPES = Set.of("compute", "dedupe", "gate", "read", "lookup");
    static final int MAX_SUGGESTIONS = 12;
    static final int MAX_AI_ADDITIONS = 5;
    static final int MAX_WHY = 400;
    static final Pattern NODE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    /** Names that say "key", as the automatic measures read them. */
    static final Pattern KEY_NAME = Pattern.compile(
        "(?:^|_)(?i:id|key|code|uuid|guid|ref)$|[a-z0-9](?:Id|ID|Key|Code|Uuid|UUID|Ref)$");
    /** Date-like value shapes: three runs of digits with separators. */
    static final Pattern DATE_SHAPE = Pattern.compile("(9{1,4})([-/.])(9{1,2})\\2(9{1,4})");

    private final QualityPredictor predictor;
    private final QualityPredictor.Catalogue catalogue;
    private final NodeFactories factories;
    private final ObjectMapper json;

    PipelineAdvisor(QualityPredictor.Catalogue catalogue, NodeFactories factories, ObjectMapper json) {
        this.catalogue = catalogue;
        this.predictor = new QualityPredictor(catalogue);
        this.factories = factories;
        this.json = json;
    }

    /**
     * Suggestions for {@code plan}: Kilnmark's own, then, when {@code model} is given, ranked,
     * explained and added to by it. {@code sources} finds a saved source by name, for its AI rule.
     */
    Advice advise(QualityPredictor.Plan plan, Model model, AiStatus noModel, Function<String, Records.Source> sources) {
        QualityPredictor.Trace trace = predictor.trace(plan);
        Set<String> taken = new HashSet<>();
        plan.nodes().forEach(s -> taken.add(s.id()));
        List<Suggestion> found = new ArrayList<>();
        for (QualityPredictor.PlanNode w : plan.nodes()) {
            if (!WriteNodeFactory.TYPE.equals(w.type())) {
                continue;
            }
            QualityPredictor.PlanEdge feeding = feeding(plan, w.id(), "in");
            QualityPredictor.Est est = trace.arriving(plan, w.id(), "in");
            if (feeding == null || est == null || est.cols == null) {
                continue;
            }
            for (Suggestion c : candidates(w, feeding, est, taken)) {
                Suggestion tried = tryOut(plan, trace, c, est, RULE_TYPES);
                if (tried != null) {
                    found.add(tried);
                }
            }
        }
        found.sort(byGain());
        List<Suggestion> list = new ArrayList<>(found.subList(0, Math.min(MAX_SUGGESTIONS, found.size())));
        if (model == null) {
            return new Advice(list, noModel);
        }
        return withModel(plan, trace, list, model, sources, taken);
    }

    private static Comparator<Suggestion> byGain() {
        return Comparator.comparingDouble((Suggestion s) -> s.effect() == null || s.effect().gain() == null ? -1
            : s.effect().gain()).reversed();
    }

    static QualityPredictor.PlanEdge feeding(QualityPredictor.Plan plan, String node, String port) {
        for (QualityPredictor.PlanEdge e : plan.edges()) {
            if (e.toNode().equals(node) && e.toPort().equals(port)) {
                return e;
            }
        }
        return null;
    }

    static String ref(String node, String port) {
        return "out".equals(port) ? node : node + "." + port;
    }

    static String freshId(String base, Set<String> taken) {
        String clean = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[^a-z]+", "");
        clean = clean.isEmpty() ? "node" : clean.substring(0, Math.min(56, clean.length()));
        String id = clean;
        for (int n = 2; taken.contains(id); n++) {
            id = clean + "-" + n;
        }
        return id;
    }

    // ---- Kilnmark's own candidates ----

    private List<Suggestion> candidates(QualityPredictor.PlanNode w,
                                        QualityPredictor.PlanEdge feeding, QualityPredictor.Est est, Set<String> taken) {
        List<Suggestion> out = new ArrayList<>();
        String from = ref(feeding.fromNode(), feeding.fromPort());
        Set<String> local = new HashSet<>(taken);
        // Trim: text columns with leading or trailing spaces.
        List<String> padded = est.cols.values().stream().filter(c -> c.text() && c.formats != null
            && c.formats.getOrDefault("padded", 0.0) > 0).map(c -> c.name).toList();
        if (!padded.isEmpty()) {
            String id = freshId("trim", local);
            Map<String, Object> columns = new LinkedHashMap<>();
            padded.forEach(c -> columns.put(c, "trim(" + quote(c) + ")"));
            out.add(single("trim-" + w.id(), "trim", "Trim spaces from " + names(padded) + " before " + w.id(),
                "Some values start or end with spaces, which counts against consistency.",
                new NewNode(id, "compute", Map.of("columns", columns), Map.of("in", from)), w, from));
        }
        // Standardise dates written in several formats.
        for (QualityPredictor.Col c : est.cols.values()) {
            if (!c.text() || c.patterns == null || c.collapsed) {
                continue;
            }
            List<String> formats = new ArrayList<>();
            for (Map.Entry<String, Double> p : c.patterns.entrySet()) {
                String fmt = datePattern(p.getKey());
                if (fmt != null && !formats.contains(fmt) && p.getValue() > 0) {
                    formats.add(fmt);
                }
            }
            if (formats.size() < 2) {
                continue;
            }
            String id = freshId("dates", local);
            StringBuilder e = new StringBuilder("coalesce(");
            for (int i = 0; i < formats.size(); i++) {
                e.append(i == 0 ? "" : ", ").append("format_date(parse_date(").append(quote(c.name)).append(", '")
                    .append(formats.get(i)).append("'), 'yyyy-MM-dd')");
            }
            e.append(')');
            out.add(single("dates-" + w.id() + "-" + c.name, "standardise_dates", "Standardise the date formats of "
                + c.name + " before " + w.id(), "Its dates are written as " + String.join(" and ", formats) + "; they "
                + "are rewritten as yyyy-MM-dd. Dates such as 01/02/2024 are read day first; values in no known "
                + "format become empty.", new NewNode(id, "compute", Map.of("columns", Map.of(c.name, e.toString())),
                Map.of("in", from)), w, from));
        }
        // Deduplicate on a key that repeats.
        List<String> writeKey = WriteNodeFactory.key(w.config());
        for (QualityPredictor.Col c : est.cols.values()) {
            boolean key = (writeKey != null && writeKey.size() == 1 && writeKey.get(0).equals(c.name))
                || (est.key.size() == 1 && est.key.get(0).equals(c.name)) || KEY_NAME.matcher(c.name).find();
            if (!key || c.distinct == null || c.distinct >= 0.999 || c.distinct < 0.5
                || (!c.distinctExact && c.distinct > 0.98)) {
                continue;
            }
            String id = freshId("dedupe", local);
            out.add(single("dedupe-" + w.id() + "-" + c.name, "dedupe", "Keep one row per " + c.name + " before "
                + w.id(), "About " + percent(1 - c.distinct) + " of its values repeat; the first row of each is kept.",
                new NewNode(id, "dedupe", Map.of("key", List.of(c.name)), Map.of("in", from)), w, from));
        }
        // Drop rows whose key has no match in the related asset.
        for (QualityPredictor.Col c : est.cols.values()) {
            if (c.origin == null || c.changed) {
                continue;
            }
            for (QualityPredictor.Link l : catalogue.links(c.origin.urn())) {
                if (!l.field().equals(c.origin.field()) || l.orphanShare() == null || l.orphanShare() <= 0.001
                    || l.orphanShare() >= 0.9) {
                    continue;
                }
                Urn target;
                try {
                    target = Urn.parse(l.toUrn());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String readId = freshId("read-" + lastPart(target.path()), local);
                local.add(readId);
                String lookupId = freshId("match-" + lastPart(target.path()), local);
                local.add(lookupId);
                Map<String, Object> lookup = new LinkedHashMap<>();
                lookup.put("on", Map.of(c.name, l.toField()));
                lookup.put("keep", List.of());
                lookup.put("unmatched", "drop");
                List<NewNode> nodes = List.of(
                    new NewNode(readId, "read", Map.of("source", target.source(), "asset", target.path()), Map.of()),
                    new NewNode(lookupId, "lookup", lookup, Map.of("main", from, "lookup", readId)));
                out.add(new Suggestion("orphans-" + w.id() + "-" + c.name, "repair_orphans", "Drop rows whose "
                    + c.name + " has no match in " + target.path() + " before " + w.id(), "About "
                    + percent(l.orphanShare()) + " of its values have no match in " + target.path() + "." + l.toField()
                    + " (from the stored relationship). A lookup keeps only the rows that match.", nodes,
                    new Placement(w.id(), "in", from, lookupId), null, "rules"));
                break;
            }
        }
        // Set aside rows outside the accepted patterns, or without a key.
        for (QualityPredictor.Col c : est.cols.values()) {
            if (c.origin == null || c.accepted.isEmpty() || c.patterns == null) {
                continue;
            }
            double outside = c.patterns.entrySet().stream().filter(p -> !c.accepted.contains(p.getKey()))
                .mapToDouble(Map.Entry::getValue).sum();
            List<String> regexes = catalogue.acceptedRegexes(c.origin.urn(), c.origin.field());
            if (outside <= 0.0005 || regexes.isEmpty()) {
                continue;
            }
            String regex = regexes.size() == 1 ? regexes.get(0) : regexes.stream().map(r -> "(?:" + r + ")")
                .reduce((a, b) -> a + "|" + b).orElseThrow();
            String id = freshId("check-" + c.name, local);
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("type", "matches_pattern");
            rule.put("field", c.name);
            rule.put("regex", regex);
            out.add(single("patterns-" + w.id() + "-" + c.name, "quarantine_patterns", "Set aside rows whose " + c.name
                + " is outside its accepted patterns before " + w.id(), "About " + percent(outside) + " of its values "
                + "are in shapes no steward accepted. A gate sends them to its quarantine port; connect it to keep them.",
                new NewNode(id, "gate", gate(rule), Map.of("in", from)), w, from));
        }
        for (QualityPredictor.Col c : est.cols.values()) {
            boolean key = (writeKey != null && writeKey.contains(c.name)) || est.key.contains(c.name)
                || KEY_NAME.matcher(c.name).find();
            if (!key || c.nulls == null || c.nulls <= 0.0005 || c.nulls >= 0.9) {
                continue;
            }
            String id = freshId("check-" + c.name, local);
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("type", "not_null");
            rule.put("field", c.name);
            out.add(single("missing-" + w.id() + "-" + c.name, "quarantine_missing", "Set aside rows without "
                + c.name + " before " + w.id(), "About " + percent(c.nulls) + " of rows have no " + c.name + ". A gate "
                + "sends them to its quarantine port; connect it to keep them.",
                new NewNode(id, "gate", gate(rule), Map.of("in", from)), w, from));
        }
        return out;
    }

    private static Map<String, Object> gate(Map<String, Object> rule) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("on_fail", "quarantine");
        g.put("asset_rules", false);
        g.put("rules", List.of(rule));
        return g;
    }

    private static Suggestion single(String id, String kind, String title, String why, NewNode node,
                                     QualityPredictor.PlanNode w, String from) {
        return new Suggestion(id, kind, title, why, List.of(node), new Placement(w.id(), "in", from, node.id()), null,
            "rules");
    }

    /**
     * The {@code DateTimeFormatter} pattern for a date-like value shape: year first is
     * {@code yyyy-MM-dd} (with its separator); year last is read day first ({@code dd/MM/yyyy}), as
     * dates are written in the UK. Null for other shapes.
     */
    static String datePattern(String shape) {
        Matcher m = DATE_SHAPE.matcher(shape);
        if (!m.matches()) {
            return null;
        }
        String sep = m.group(2);
        int a = m.group(1).length();
        int b = m.group(3).length();
        int c = m.group(4).length();
        if (a == 4 && c <= 2) {
            return "yyyy" + sep + (b == 2 ? "MM" : "M") + sep + (c == 2 ? "dd" : "d");
        }
        if (c == 4 && a <= 2) {
            return (a == 2 ? "dd" : "d") + sep + (b == 2 ? "MM" : "M") + sep + "yyyy";
        }
        return null;
    }

    private static String quote(String column) {
        return column.matches("[A-Za-z_][A-Za-z0-9_]*") && !Set.of("and", "or", "not", "null", "true", "false", "is", "in",
            "between", "like", "case", "when", "then", "else", "end", "cast", "as", "date", "timestamp")
            .contains(column.toLowerCase(Locale.ROOT)) ? column : "\"" + column.replace("\"", "\"\"") + "\"";
    }

    private static String names(List<String> names) {
        if (names.size() <= 3) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }

    private static String lastPart(String path) {
        String p = path;
        int slash = p.lastIndexOf('/');
        if (slash >= 0) {
            p = p.substring(slash + 1);
        }
        int dot = p.lastIndexOf('.');
        return dot > 0 && slash < 0 ? p.substring(dot + 1) : p;
    }

    static String percent(double share) {
        double p = share * 100;
        return (p >= 10 || p == 0 ? String.format(Locale.ROOT, "%.0f", p) : String.format(Locale.ROOT, "%.1f", p)) + "%";
    }

    // ---- trying a suggestion out ----

    /**
     * The suggestion checked (only node types allowed, settings the node takes, columns that reach
     * it, the node's own check) and its predicted effect on the write it goes before; null when it
     * fails a check or is not predicted to help.
     */
    Suggestion tryOut(QualityPredictor.Plan plan, QualityPredictor.Trace trace, Suggestion s, QualityPredictor.Est at,
                      Set<String> allowed) {
        if (problem(s, at, allowed, plan) != null) {
            return null;
        }
        List<QualityPredictor.PlanNode> added = new ArrayList<>();
        List<QualityPredictor.PlanEdge> edges = new ArrayList<>();
        Map<String, String> reads = new LinkedHashMap<>();
        for (NewNode n : s.nodes()) {
            added.add(new QualityPredictor.PlanNode(n.id(), n.type(), n.settings(), Map.of()));
            for (Map.Entry<String, String> in : n.inputs().entrySet()) {
                String r = in.getValue();
                int dot = r.lastIndexOf('.');
                edges.add(new QualityPredictor.PlanEdge(dot < 0 ? r : r.substring(0, dot), dot < 0 ? "out"
                    : r.substring(dot + 1), n.id(), in.getKey()));
            }
            if (n.type().equals("read")) {
                reads.put(n.id(), PipelineSources.urn(String.valueOf(n.settings().get("source")),
                    String.valueOf(n.settings().get("asset"))));
            }
        }
        QualityPredictor.PlanEdge cut = feeding(plan, s.placement().before(), s.placement().port());
        QualityPredictor.PlanEdge rewired = new QualityPredictor.PlanEdge(s.placement().through(), "out",
            s.placement().before(), s.placement().port());
        QualityPredictor.Plan tried = plan.with(added, edges, cut, rewired, reads);
        QualityPredictor.Predicted before = trace.result().output(s.placement().before());
        QualityPredictor.Predicted after = predictor.predict(tried).output(s.placement().before());
        if (before == null || after == null) {
            return null;
        }
        String note = null;
        if (before.rows() != null && after.rows() != null && after.rows() < before.rows()) {
            // Fewer rows can raise a score; the rows left out are said in words beside it. A write's score
            // does not measure referential integrity, so for a lookup this is all it shows.
            long lost = before.rows() - after.rows();
            note = "repair_orphans".equals(s.kind())
                ? "About " + QualityPredictor.countWords(lost) + " rows with no match are left out"
                : "About " + QualityPredictor.countWords(lost) + " fewer rows reach " + s.placement().before() + " ("
                    + QualityPredictor.countWords(before.rows()) + " → " + QualityPredictor.countWords(after.rows())
                    + ")";
        } else if (before.rows() != null && after.rows() == null) {
            note = "How many rows then reach " + s.placement().before() + " is not known";
        }
        Effect effect = new Effect(s.placement().before(), before.overall(), after.overall(), before.dimensions(),
            after.dimensions(), before.rows(), after.rows(), note);
        Double gain = effect.gain();
        boolean unscored = "repair_orphans".equals(s.kind());
        if (gain == null || (gain <= 0.0005 && (!unscored || note == null || gain < -0.0005))) {
            return null;
        }
        return new Suggestion(s.id(), s.kind(), s.title(), s.why(), s.nodes(), s.placement(), effect, s.source());
    }

    /** Why a suggestion cannot be shown, or null when it can. */
    String problem(Suggestion s, QualityPredictor.Est at, Set<String> allowed, QualityPredictor.Plan plan) {
        if (s.nodes().isEmpty() || s.placement() == null) {
            return "no nodes";
        }
        QualityPredictor.PlanNode before = plan.node(s.placement().before());
        if (before == null || feeding(plan, before.id(), s.placement().port()) == null) {
            return "it goes before a node that is not there";
        }
        Set<String> ids = new HashSet<>();
        plan.nodes().forEach(x -> ids.add(x.id()));
        Set<String> newIds = new HashSet<>();
        for (NewNode n : s.nodes()) {
            if (n.id() == null || !NODE_ID.matcher(n.id()).matches() || ids.contains(n.id()) || !newIds.add(n.id())) {
                return "node id " + n.id() + " cannot be used";
            }
            if (!allowed.contains(n.type())) {
                return "a " + n.type() + " node cannot be suggested";
            }
            NodeFactory f = factories.get(n.type()).orElse(null);
            if (f == null) {
                return "this server has no " + n.type() + " node";
            }
            Set<String> keys = new HashSet<>();
            for (SettingSpec spec : f.settings()) {
                keys.add(spec.key());
            }
            for (String k : n.settings().keySet()) {
                if (!keys.contains(k)) {
                    return "a " + n.type() + " node has no setting " + k;
                }
            }
            List<String> problems;
            try {
                problems = f.check(n.settings());
            } catch (RuntimeException e) {
                return "its settings cannot be checked";
            }
            if (!problems.isEmpty()) {
                return problems.get(0);
            }
            if (n.type().equals("read")) {
                continue;
            }
            for (Map.Entry<String, String> in : n.inputs().entrySet()) {
                if (!f.inputPorts().stream().map(p -> p.replace("*", "")).toList().contains(in.getKey())) {
                    return "a " + n.type() + " node has no input " + in.getKey();
                }
            }
            String column = unknownColumn(n, at);
            if (column != null) {
                return "column " + column + " does not reach " + s.placement().before();
            }
            if (at.cols != null && !n.type().equals("lookup")) {
                try {
                    f.outputSchema("out", Map.of("in", schema(at)), n.settings());
                } catch (NodeException | RuntimeException e) {
                    return e.getMessage();
                }
            }
        }
        if (s.nodes().stream().noneMatch(n -> n.id().equals(s.placement().through()))) {
            return "it feeds " + s.placement().before() + " from no new node";
        }
        return null;
    }

    private static Schema schema(QualityPredictor.Est at) {
        List<Field> fields = new ArrayList<>();
        at.cols.values().forEach(c -> fields.add(Field.nullable(c.name, ArrowKinds.type(c.kind))));
        return new Schema(fields);
    }

    /** A column a new node names that does not reach it, or null. */
    static String unknownColumn(NewNode n, QualityPredictor.Est at) {
        if (at.cols == null) {
            return null;
        }
        Set<String> have = new LinkedHashSet<>(at.cols.keySet());
        Map<String, Object> s = n.settings();
        List<String> named = new ArrayList<>();
        try {
            switch (n.type()) {
                case "compute" -> {
                    if (s.get("columns") instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            for (String c : Expression.parse(String.valueOf(e.getValue())).columns()) {
                                if (!have.contains(c)) {
                                    return c;
                                }
                            }
                            have.add(String.valueOf(e.getKey()));
                        }
                    }
                }
                case "filter" -> named.addAll(Expression.parse(String.valueOf(s.get("condition"))).columns());
                case "dedupe" -> named.addAll(nonNull(QualityPredictor.list(s.get("key"))));
                case "select" -> {
                    named.addAll(nonNull(QualityPredictor.list(s.get("keep"))));
                    named.addAll(nonNull(QualityPredictor.list(s.get("drop"))));
                    if (s.get("rename") instanceof Map<?, ?> m) {
                        m.keySet().forEach(k -> named.add(String.valueOf(k)));
                    }
                    if (s.get("cast") instanceof Map<?, ?> m) {
                        m.keySet().forEach(k -> named.add(String.valueOf(k)));
                    }
                }
                case "lookup" -> {
                    if (s.get("on") instanceof Map<?, ?> m) {
                        m.keySet().forEach(k -> named.add(String.valueOf(k)));
                    }
                }
                case "gate" -> {
                    Map<String, String> kinds = new LinkedHashMap<>();
                    at.cols.values().forEach(c -> kinds.put(c.name, c.kind));
                    try {
                        for (Rule r : GateRules.read(s.get("rules"), kinds)) {
                            if (r.field() != null) {
                                named.add(r.field());
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        return "named in its rules";
                    }
                }
                default -> {
                    return null;
                }
            }
        } catch (ExprException e) {
            return "named in an expression that cannot be read";
        }
        for (String c : named) {
            if (!have.contains(c)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> nonNull(List<String> l) {
        return l == null ? List.of() : l;
    }

    // ---- the model: rank, explain, add ----

    static final String SYSTEM = """
        You help people improve the data quality of a pipeline in Kilnmark, a data integration tool. You are \
        given the predicted metadata of the rows reaching each write node (column names, kinds, the share of \
        empty values, distinct values, value shapes in which letters are A or a and digits 9, detected formats \
        and accepted shapes), each write's predicted quality score, and candidate improvements Kilnmark found. \
        All scores and shares are estimates from catalogue metadata. You never see values from the data. \
        Answer in JSON only, matching the schema. In "ranking", list candidate ids, most useful first. In \
        "explanations", give for some ids one or two plain sentences on why it helps, without inventing numbers. \
        In "additional", suggest at most 5 further nodes, each placed before a write node (its id in "before"), \
        of type compute, filter, select, dedupe or gate, with "settings" as the node's YAML settings: compute \
        takes columns (name to expression), filter takes condition, select takes keep, drop, rename, dedupe takes \
        key (a list of columns), gate takes on_fail (quarantine) and rules (a list of {type, field, ...} with \
        type not_null, unique, allowed_values, matches_pattern or in_range). Use only columns listed for that \
        write. Expressions are SQL-like: trim, lower, upper, coalesce, parse_date(x, 'pattern'), \
        format_date(d, 'pattern'), regex_match(x, 'regex'), is null, comparisons, and, or, not.""";

    static final String SCHEMA = """
        {"type":"object","properties":{
          "ranking":{"type":"array","items":{"type":"string"}},
          "explanations":{"type":"object","additionalProperties":{"type":"string"}},
          "additional":{"type":"array","items":{"type":"object","properties":{
            "before":{"type":"string"},"type":{"type":"string"},"title":{"type":"string"},"why":{"type":"string"},
            "settings":{"type":"object"}},"required":["before","type","title","settings"]}}},
         "required":["ranking"]}""";

    private Advice withModel(QualityPredictor.Plan plan, QualityPredictor.Trace trace, List<Suggestion> found,
                             Model model, Function<String, Records.Source> sources, Set<String> taken) {
        // Which sources' metadata this model may see.
        Map<String, Records.Source> allowed = new LinkedHashMap<>();
        Set<String> refused = new LinkedHashSet<>();
        for (String urn : plan.reads().values()) {
            String name = sourceOf(urn);
            if (name == null || allowed.containsKey(name) || refused.contains(name)) {
                continue;
            }
            Records.Source s = sources.apply(name);
            if (s != null && model.refusal(s) == null) {
                allowed.put(name, s);
            } else {
                refused.add(name);
            }
        }
        if (allowed.isEmpty()) {
            return new Advice(found, new AiStatus("withheld", "No source this pipeline reads lets " + model.name()
                + " see its metadata (each source's AI rule), so the suggestions are Kilnmark's own.", model.name()));
        }
        ObjectNode payload = json.createObjectNode();
        ArrayNode nodesArray = payload.putArray("nodes");
        ArrayNode edgesNode = payload.putArray("connections");
        ArrayNode writes = payload.putArray("writes");
        Map<String, Set<String>> sent = new LinkedHashMap<>();
        for (QualityPredictor.PlanNode w : plan.nodes()) {
            if (!WriteNodeFactory.TYPE.equals(w.type())) {
                continue;
            }
            QualityPredictor.Est est = trace.arriving(plan, w.id(), "in");
            QualityPredictor.Predicted p = trace.result().output(w.id());
            if (est == null || est.cols == null) {
                continue;
            }
            ObjectNode wn = writes.addObject().put("before", w.id());
            if (p != null && p.overall() != null) {
                wn.put("predictedScore", round(p.overall()));
                ObjectNode d = wn.putObject("predictedDimensions");
                p.dimensions().forEach((k, v) -> d.put(k, round(v)));
            }
            ArrayNode cols = wn.putArray("columns");
            Set<String> names = new LinkedHashSet<>();
            for (QualityPredictor.Col c : est.cols.values()) {
                if (c.origin == null || !allowed.containsKey(sourceOf(c.origin.urn()))) {
                    continue; // computed here, or from a source that does not allow this model: left out entirely
                }
                names.add(c.name);
                cols.add(columnNode(c));
            }
            if (names.isEmpty()) {
                writes.remove(writes.size() - 1); // nothing about it may be shown
                continue;
            }
            sent.put(w.id(), names);
        }
        // The nodes and connections leading to the writes shown, less the reads of sources it may not see.
        Set<String> visible = new LinkedHashSet<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(sent.keySet());
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!visible.add(id)) {
                continue;
            }
            for (QualityPredictor.PlanEdge e : plan.edges()) {
                if (e.toNode().equals(id)) {
                    todo.add(e.fromNode());
                }
            }
        }
        for (Map.Entry<String, String> r : plan.reads().entrySet()) {
            if (!allowed.containsKey(sourceOf(r.getValue()))) {
                visible.remove(r.getKey());
            }
        }
        for (QualityPredictor.PlanNode s : plan.nodes()) {
            if (visible.contains(s.id())) {
                nodesArray.addObject().put("id", s.id()).put("type", s.type());
            }
        }
        plan.edges().stream().filter(e -> visible.contains(e.fromNode()) && visible.contains(e.toNode()))
            .forEach(e -> edgesNode.add(ref(e.fromNode(), e.fromPort()) + " -> " + e.toNode() + "." + e.toPort()));
        ArrayNode cands = payload.putArray("candidates");
        List<Suggestion> shareable = new ArrayList<>();
        for (Suggestion s : found) {
            Set<String> mine = sent.getOrDefault(s.placement().before(), Set.of());
            // A node reading another asset names it (in its title too): only of a source the model may see.
            boolean readsAllowed = s.nodes().stream().filter(n -> n.type().equals("read"))
                .allMatch(n -> allowed.containsKey(String.valueOf(n.settings().get("source"))));
            if (readsAllowed && columnsOf(s).stream().allMatch(mine::contains)) {
                shareable.add(s);
                ObjectNode n = cands.addObject().put("id", s.id()).put("kind", s.kind()).put("title", s.title())
                    .put("before", s.placement().before());
                if (s.effect() != null && s.effect().after() != null) {
                    n.put("predictedScoreAfter", round(s.effect().after()));
                }
            }
        }
        String answer;
        try {
            Records.Source strictest = strictest(allowed.values());
            answer = model.chat(strictest, new ModelClient.ChatCall(SYSTEM, json.writeValueAsString(payload), SCHEMA,
                1500));
        } catch (AiException e) {
            return new Advice(found, new AiStatus("unavailable", unavailable(e), model.name()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Advice(found, new AiStatus("unavailable", "The suggestions could not be sent to the model.",
                model.name()));
        }
        JsonNode reply;
        try {
            reply = json.readTree(strip(answer));
        } catch (Exception e) {
            reply = null;
        }
        if (reply == null || !reply.isObject()) {
            return new Advice(found, new AiStatus("unavailable", model.name() + "'s answer could not be read, so the "
                + "suggestions are Kilnmark's own.", model.name()));
        }
        // Explanations, for candidates the model was shown only.
        Set<String> shownIds = new HashSet<>();
        shareable.forEach(s -> shownIds.add(s.id()));
        Map<String, String> explained = new LinkedHashMap<>();
        reply.path("explanations").properties().forEach(e -> {
            if (shownIds.contains(e.getKey()) && e.getValue().isTextual()) {
                explained.put(e.getKey(), clean(e.getValue().asText()));
            }
        });
        List<Suggestion> merged = new ArrayList<>();
        for (Suggestion s : found) {
            String why = explained.get(s.id());
            merged.add(why == null || why.isBlank() ? s : new Suggestion(s.id(), s.kind(), s.title(), s.why() + " " + why,
                s.nodes(), s.placement(), s.effect(), s.source()));
        }
        // The model's own, checked like the rest.
        int dropped = 0;
        int added = 0;
        Set<String> local = new HashSet<>(taken);
        for (Suggestion s : merged) {
            s.nodes().forEach(n -> local.add(n.id()));
        }
        for (JsonNode a : reply.path("additional")) {
            if (added >= MAX_AI_ADDITIONS) {
                break;
            }
            Suggestion s = fromModel(a, plan, local, sent);
            QualityPredictor.Est at = s == null ? null : trace.arriving(plan, s.placement().before(), "in");
            Suggestion tried = s == null || at == null ? null : tryOut(plan, trace, s, sentOnly(at,
                sent.getOrDefault(s.placement().before(), Set.of())), AI_TYPES);
            if (tried == null) {
                dropped++;
                continue;
            }
            local.add(tried.nodes().get(0).id());
            merged.add(tried);
            added++;
        }
        // The model's ranking first, the rest after by predicted gain.
        List<String> ranking = new ArrayList<>();
        reply.path("ranking").forEach(n -> ranking.add(n.asText()));
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (String id : ranking) {
            rank.putIfAbsent(id, rank.size());
        }
        merged.sort(Comparator.comparingInt((Suggestion s) -> rank.getOrDefault(s.id(), Integer.MAX_VALUE))
            .thenComparing(byGain()));
        String message = model.name() + " ranked and explained the suggestions" + (added > 0 ? " and added "
            + added : "") + (dropped > 0 ? "; " + dropped + " of its own were left out because they named a node or "
            + "column that does not exist here, or would not help" : "") + "."
            + (refused.isEmpty() ? "" : " Sources whose AI rule does not allow it were not shown: "
            + String.join(", ", refused) + ".");
        return new Advice(merged.subList(0, Math.min(MAX_SUGGESTIONS + MAX_AI_ADDITIONS, merged.size())),
            new AiStatus("used", message, model.name()));
    }

    /** The estimate with only the columns the model was shown, so it cannot name others. */
    private static QualityPredictor.Est sentOnly(QualityPredictor.Est at, Set<String> names) {
        QualityPredictor.Est e = at.copy();
        if (e.cols != null) {
            e.cols.keySet().retainAll(names);
        }
        return e;
    }

    /** A node the model suggested, as a suggestion to check; null when it is not even well formed. */
    private Suggestion fromModel(JsonNode a, QualityPredictor.Plan plan, Set<String> taken,
                                 Map<String, Set<String>> sent) {
        String before = a.path("before").asText("");
        String type = a.path("type").asText("").strip().toLowerCase(Locale.ROOT);
        String title = clean(a.path("title").asText(""));
        if (!sent.containsKey(before) || !AI_TYPES.contains(type) || title.isBlank() || !a.path("settings").isObject()) {
            return null;
        }
        QualityPredictor.PlanEdge feeding = feeding(plan, before, "in");
        if (feeding == null) {
            return null;
        }
        Map<String, Object> settings;
        try {
            settings = plain(a.get("settings"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String id = freshId(type, taken);
        String from = ref(feeding.fromNode(), feeding.fromPort());
        NewNode node = new NewNode(id, type, settings, Map.of("in", from));
        String why = clean(a.path("why").asText(""));
        return new Suggestion("ai-" + id + "-" + before, "ai", title.length() > 200 ? title.substring(0, 200) : title,
            why.isBlank() ? "Suggested by the model." : why, List.of(node), new Placement(before, "in", from, id), null,
            "ai");
    }

    /** JSON settings as YAML values: maps, lists, text, whole numbers, decimals, booleans; nothing deeper than 4. */
    static Map<String, Object> plain(JsonNode n) {
        Object v = plainValue(n, 0);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("settings");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, x) -> out.put(String.valueOf(k), x));
        return out;
    }

    private static Object plainValue(JsonNode n, int depth) {
        if (depth > 4) {
            throw new IllegalArgumentException("too deep");
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.properties().forEach(e -> m.put(e.getKey(), plainValue(e.getValue(), depth + 1)));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(plainValue(x, depth + 1)));
            return l;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isNull()) {
            throw new IllegalArgumentException("null");
        }
        String t = n.asText();
        if (t.length() > 2000) {
            throw new IllegalArgumentException("too long");
        }
        return t;
    }

    /** Every column a suggestion names, for checking it against what may be shown. */
    static Set<String> columnsOf(Suggestion s) {
        Set<String> out = new LinkedHashSet<>();
        for (NewNode n : s.nodes()) {
            collectNames(n.type(), n.settings(), out);
        }
        return out;
    }

    private static void collectNames(String type, Map<String, Object> s, Set<String> out) {
        try {
            switch (type) {
                case "compute" -> {
                    if (s.get("columns") instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            out.add(String.valueOf(e.getKey()));
                            out.addAll(Expression.parse(String.valueOf(e.getValue())).columns());
                        }
                    }
                }
                case "dedupe" -> out.addAll(nonNull(QualityPredictor.list(s.get("key"))));
                case "lookup" -> {
                    if (s.get("on") instanceof Map<?, ?> m) {
                        m.keySet().forEach(k -> out.add(String.valueOf(k)));
                    }
                }
                case "gate" -> {
                    if (s.get("rules") instanceof List<?> l) {
                        for (Object r : l) {
                            if (r instanceof Map<?, ?> m && m.get("field") != null) {
                                out.add(String.valueOf(m.get("field")));
                            }
                        }
                    }
                }
                default -> { }
            }
        } catch (ExprException e) {
            out.add("\u0000");
        }
    }

    /** A column as the model sees it: names, kinds and shares only, never a value. */
    private ObjectNode columnNode(QualityPredictor.Col c) {
        ObjectNode n = json.createObjectNode();
        n.put("name", c.name);
        n.put("kind", c.kind);
        n.put("asset", c.origin.urn());
        if (c.nulls != null) {
            n.put("emptyShare", round(c.nulls));
        }
        if (c.blanks != null) {
            n.put("blankShare", round(c.blanks));
        }
        if (c.distinct != null) {
            n.put("distinctShare", round(c.distinct));
        }
        if (c.patterns != null) {
            ArrayNode p = n.putArray("valueShapes");
            c.patterns.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(6)
                .forEach(e -> p.addObject().put("shape", e.getKey()).put("share", round(e.getValue())));
        }
        if (c.formats != null && !c.formats.isEmpty()) {
            ObjectNode f = n.putObject("formats");
            c.formats.forEach((k, v) -> f.put(k, round(v)));
        }
        if (!c.accepted.isEmpty()) {
            ArrayNode a = n.putArray("acceptedShapes");
            c.accepted.stream().sorted().limit(10).forEach(a::add);
        }
        if (c.negatives != null) {
            n.put("negativeShare", round(c.negatives));
        }
        if (c.outliers != null) {
            n.put("outlierShare", round(c.outliers));
        }
        return n;
    }

    static String sourceOf(String urn) {
        try {
            return Urn.parse(urn).source();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The source with the strictest AI rule: local_only before allowed. */
    private static Records.Source strictest(java.util.Collection<Records.Source> sources) {
        Records.Source best = null;
        for (Records.Source s : sources) {
            if (best == null || io.kilnmark.server.ai.EgressPolicy.SourceRule.of(s)
                == io.kilnmark.server.ai.EgressPolicy.SourceRule.LOCAL_ONLY) {
                best = s;
            }
        }
        return best;
    }

    private static String unavailable(AiException e) {
        return switch (e.kind()) {
            case REFUSED -> "The model may not see this pipeline's metadata, so the suggestions are Kilnmark's own.";
            case TIMEOUT -> "The model did not answer in time, so the suggestions are Kilnmark's own.";
            case BUSY -> "The model is busy, so the suggestions are Kilnmark's own. Try again shortly.";
            default -> "The model could not be reached, so the suggestions are Kilnmark's own.";
        };
    }

    /** Text from the model as plain words: no control characters, at most {@link #MAX_WHY} characters. */
    static String clean(String text) {
        String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("\\s+", " ").strip();
        return t.length() > MAX_WHY ? t.substring(0, MAX_WHY) + "…" : t;
    }

    /** A JSON answer without a code fence around it. */
    static String strip(String answer) {
        String t = answer == null ? "" : answer.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                t = t.substring(nl + 1, end);
            }
        }
        return t;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
