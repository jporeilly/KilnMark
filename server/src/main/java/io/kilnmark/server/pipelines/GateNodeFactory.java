package io.kilnmark.server.pipelines;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeCategories;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.server.quality.Rule;

/**
 * The {@code gate} node (docs/design/data-quality.md section 7, docs/guide/quality-gates.md "Quality
 * gates"): checks the rows passing through against quality rules in the same pass, and stops the
 * run, sets failing rows aside on {@code quarantine}, or lets them through with a warning. The
 * rules are the enabled rules of the asset read upstream ({@code asset_rules}, on by default), and
 * any written on the node ({@code rules}).
 *
 * <pre>{@code
 * - id: check
 *   type: gate
 *   input: customers
 *   on_fail: quarantine        # stop (the default), quarantine or warn
 *   rules:
 *     - {type: not_null, field: email}
 *   max_failing_share: 0.05    # stop the run if more than 5% of rows fail a rule
 * }</pre>
 *
 * The server registers it: it needs the catalogue's rules.
 */
public final class GateNodeFactory implements NodeFactory {

    public static final String TYPE = QualityGate.TYPE;
    public static final List<String> ON_FAIL = List.of("stop", "quarantine", "warn");

    private final PipelineSources sources;

    public GateNodeFactory(PipelineSources sources) {
        this.sources = sources;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.GATE;
    }

    @Override
    public String category() {
        return NodeCategories.QUALITY;
    }

    @Override
    public String label() {
        return "Quality gate";
    }

    @Override
    public String help() {
        return "Checks rows against quality rules as they pass, with no second read: the rules of the asset read "
            + "upstream and any written here. Stops the run, sets failing rows aside on the quarantine port, or lets "
            + "them through with a warning.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return QualityGate.PORTS;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.choice("on_fail", "When rows fail", false, "stop", "What to do when rows fail: stop fails the run, so "
                + "the writes after the gate do not commit; quarantine sends failing rows to the quarantine port and "
                + "the rest on; warn lets every row through and records what failed", ON_FAIL),
            SettingSpec.optional("asset_rules", "Check the asset's rules", SettingSpec.Kind.BOOLEAN, true,
                "Checks the enabled quality rules of the table or file read upstream"),
            SettingSpec.optional("rules_from", "Rules of read node", SettingSpec.Kind.TEXT, null,
                "When rows come from more than one read node: the id of the one whose asset's rules to check")
                .withParameters(SettingSpec.Parameters.NONE),
            SettingSpec.optional("rules", "Rules", SettingSpec.Kind.RULES, null,
                "Rules for this pipeline only, each with a type, a column and its own settings"),
            SettingSpec.optional("min_score", "Lowest score", SettingSpec.Kind.NUMBER, null,
                "Stops the run (with stop or quarantine) when the rows score below this, from 0 to 1"),
            SettingSpec.optional("max_failing_share", "Most failing rows", SettingSpec.Kind.NUMBER, null,
                "Stops the run (with stop or quarantine) when more than this share of rows fail a rule, from 0 to 1"));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        List<String> problems = new ArrayList<>();
        List<Rule> inline = List.of();
        boolean readable = true;
        try {
            inline = GateRules.read(config.get("rules"), null);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            readable = false;
        }
        for (String key : List.of("min_score", "max_failing_share")) {
            Double v = share(config.get(key));
            if (config.get(key) != null && (v == null || v < 0 || v > 1)) {
                problems.add(key + ": a share from 0 to 1, such as " + (key.equals("min_score") ? "0.95 for 95%"
                    : "0.05 for 5%"));
            }
        }
        if (!assetRules(config) && inline.isEmpty() && readable) {
            problems.add("rules: the gate has nothing to check. Write rules here, or turn on asset_rules to check "
                + "the rules of the asset read upstream");
        }
        if (!assetRules(config) && config.get("rules_from") != null) {
            problems.add("rules_from: only used with asset_rules on; remove it, or turn asset_rules on");
        }
        return problems;
    }

    static boolean assetRules(Map<String, Object> config) {
        Object v = config.get("asset_rules");
        return !(Boolean.FALSE.equals(v) || "false".equals(v));
    }

    static Double share(Object v) {
        if (v instanceof BigDecimal d) {
            return d.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.valueOf(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!QualityGate.PORTS.contains(port)) {
            throw new NodeException("A gate node has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Map<String, String> kinds = new LinkedHashMap<>();
        for (Field f : in.getFields()) {
            kinds.put(f.getName(), ArrowKinds.kind(f));
        }
        try {
            GateRules.read(config.get("rules"), kinds);
        } catch (IllegalArgumentException e) {
            throw new NodeException(e.getMessage(), e);
        }
        return in; // the gate never changes the columns: out and quarantine carry the rows as they came
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        List<Rule> inline;
        try {
            inline = GateRules.read(config.get("rules"), null);
        } catch (IllegalArgumentException e) {
            throw new NodeException(e.getMessage(), e);
        }
        String onFail = PipelineSources.text(config, "on_fail");
        RunContext run = RunContext.current().orElse(null);
        QualityGate.AssetRulesLookup lookup = run == null ? null : id -> lookup(run, id);
        return new QualityGate(QualityGate.OnFail.parse(onFail), share(config.get("min_score")),
            share(config.get("max_failing_share")), inline, assetRules(config), lookup);
    }

    /** The asset whose rules gate {@code id} checks, as the run resolved its read; null when it has none. */
    private QualityGate.AssetRules lookup(RunContext run, String id) {
        RunContext.ReadRef ref = run.gateRead(id);
        if (ref == null) {
            return null;
        }
        String urn = run.readUrn(ref.source(), ref.asset(), PipelineSources.urn(ref.source(), ref.asset()));
        if (urn == null) {
            return null;
        }
        List<Rule> rules = sources.rules(urn).stream()
            .filter(r -> r.type() != io.kilnmark.server.quality.RuleType.REFERENCES
                && r.type() != io.kilnmark.server.quality.RuleType.CUSTOM_SQL)
            .toList();
        Map<String, String> kinds = new LinkedHashMap<>();
        sources.asset(urn).ifPresent(a -> a.fields().forEach(f -> kinds.put(f.name(), f.kind())));
        Long previous = sources.rowCount(urn);
        return new QualityGate.AssetRules() {
            @Override
            public String urn() {
                return urn;
            }

            @Override
            public List<Rule> rules() {
                return rules;
            }

            @Override
            public Map<String, String> kinds() {
                return kinds;
            }

            @Override
            public Long previousRows() {
                return previous;
            }
        };
    }

    // ---- what the gate checks, from the pipeline's graph ----

    /**
     * For a gate, the read nodes upstream of it, nearest first: every read whose rows can reach it.
     *
     * @param types node id to type
     */
    static List<String> readsUpstream(String gate, List<Edge> edges, Map<String, String> types) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(gate));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (Edge e : edges) {
                if (e.to().node().equals(s) && seen.add(e.from().node())) {
                    todo.add(e.from().node());
                    if (ReadNodeFactory.TYPE.equals(types.get(e.from().node()))) {
                        out.add(e.from().node());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Which read node's asset a gate takes its rules from, or the problem with it (starting with
     * the setting it is about). {@code read} is null when the gate checks no asset's rules.
     */
    record Upstream(String read, String problem, String key) {
    }

    static Upstream upstream(String gate, Map<String, Object> config, List<Edge> edges, Map<String, String> types) {
        if (!assetRules(config)) {
            return new Upstream(null, null, null);
        }
        List<String> reads = readsUpstream(gate, edges, types);
        String named = PipelineSources.text(config, "rules_from");
        if (named != null) {
            if (!reads.contains(named)) {
                return new Upstream(null, "rules_from: '" + named + "' is not a read node whose rows reach this gate"
                    + (reads.isEmpty() ? "" : "; the read nodes upstream are " + String.join(", ", reads)), "rules_from");
            }
            return new Upstream(named, null, null);
        }
        if (reads.isEmpty()) {
            return new Upstream(null, null, null); // nothing read upstream: a note says the gate checks only its own rules
        }
        if (reads.size() > 1) {
            return new Upstream(null, "asset_rules: rows reach this gate from the read nodes " + String.join(", ", reads)
                + "; name the one whose asset's rules to check with rules_from, or set asset_rules: false", null);
        }
        return new Upstream(reads.get(0), null, null);
    }

    /**
     * Something about a gate that only the whole graph shows: a problem (stops the pipeline) or a
     * note, about node {@code node} and its setting {@code key} (null: the node).
     */
    record Finding(String node, String key, String message, boolean note) {
    }

    /** Nodes whose rows can stop before their input ends, so a stop gate before them never judges. */
    static final java.util.Set<String> ENDS_EARLY = java.util.Set.of("limit");

    /**
     * The checks of gates against the rest of the pipeline:
     * <ul>
     *   <li>a stop gate holds back what comes after it only because writes commit at the end of their
     *       rows: a write after it that commits as it goes ({@code commit_every} with create, append or
     *       upsert; replace swaps the table in at the end) is refused, as is a node that ends the rows
     *       early (limit), which would end the gate before it can judge;</li>
     *   <li>a note when the rows a stop gate checks also reach a write by another path, which is
     *       written whatever the gate decides;</li>
     *   <li>a note when quarantined rows have nowhere to go, or the quarantine port is connected but
     *       the gate never sends it rows.</li>
     * </ul>
     *
     * @param types   node id to type
     * @param configs node id to its settings
     */
    static List<Finding> graphChecks(Map<String, String> types, Map<String, Map<String, Object>> configs,
                                     List<Edge> edges) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, String> t : types.entrySet()) {
            if (!TYPE.equals(t.getValue())) {
                continue;
            }
            String gate = t.getKey();
            Map<String, Object> config = configs.getOrDefault(gate, Map.of());
            String onFail = PipelineSources.text(config, "on_fail");
            onFail = onFail == null ? "stop" : onFail;
            List<String> quarantine = edges.stream().filter(e -> e.from().node().equals(gate)
                && e.from().port().equals("quarantine")).map(e -> e.to().node()).distinct().toList();
            if (onFail.equals("quarantine") && quarantine.isEmpty()) {
                out.add(new Finding(gate, "on_fail", "on_fail: rows that fail a rule go to the quarantine port, but "
                    + "nothing is connected to it, so they are dropped. Connect a write to quarantine to keep them", true));
            }
            if (!onFail.equals("quarantine") && !quarantine.isEmpty()) {
                out.add(new Finding(gate, "on_fail", "on_fail: " + onFail + " sends no rows to the quarantine port, so "
                    + String.join(", ", quarantine) + " gets none, and a write there in mode replace or create empties "
                    + "or creates an empty table on every run. Set on_fail: quarantine, or disconnect it", true));
            }
            if (!onFail.equals("stop")) {
                continue;
            }
            java.util.Set<String> after = downstream(gate, edges);
            for (String s : after) {
                String type = types.get(s);
                Map<String, Object> c = configs.getOrDefault(s, Map.of());
                if (WriteNodeFactory.TYPE.equals(type) && commitsAsItGoes(c)) {
                    out.add(new Finding(s, "commit_every", "commit_every: the rows come through the stop gate " + gate
                        + ", which decides only at the end of the rows; committing every " + c.get("commit_every")
                        + " rows would write them before it does. Remove commit_every, use mode replace, or set the "
                        + "gate's on_fail to quarantine or warn", false));
                }
                if (ENDS_EARLY.contains(type)) {
                    out.add(new Finding(s, null, "a " + type + " after the stop gate " + gate + " ends the rows early, "
                        + "so the gate never sees their end and cannot stop the run. Put the " + type + " before the "
                        + "gate, or set the gate's on_fail to quarantine or warn", false));
                }
            }
            // Writes the gate's rows reach by another path are written whatever it decides.
            List<String> reads = readsUpstream(gate, edges, types);
            java.util.Set<String> around = new java.util.TreeSet<>();
            for (String read : reads) {
                for (String s : downstream(read, edges)) {
                    if (WriteNodeFactory.TYPE.equals(types.get(s)) && !after.contains(s)) {
                        around.add(s);
                    }
                }
            }
            if (!around.isEmpty()) {
                out.add(new Finding(gate, "on_fail", "on_fail: " + (around.size() == 1 ? "the write " : "the writes ")
                    + String.join(", ", around) + " " + (around.size() == 1 ? "gets" : "get") + " the same rows by "
                    + "another path, not through this gate, so " + (around.size() == 1 ? "it is" : "they are")
                    + " written even when the gate stops the run", true));
            }
        }
        return out;
    }

    /** Whether a write commits as its rows arrive, rather than once at their end. */
    static boolean commitsAsItGoes(Map<String, Object> write) {
        Double every = share(write.get("commit_every"));
        String mode = WriteNodeFactory.mode(write);
        return every != null && every > 0 && !"replace".equals(mode == null ? "create" : mode);
    }

    /** Every node the rows of {@code node} can reach, through any of its ports. */
    static java.util.Set<String> downstream(String node, List<Edge> edges) {
        java.util.Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(node));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (Edge e : edges) {
                if (e.from().node().equals(s) && seen.add(e.to().node())) {
                    todo.add(e.to().node());
                }
            }
        }
        return seen;
    }

    /** The source and asset a read node names. */
    static RunContext.ReadRef readOf(Map<String, Object> readConfig) {
        return new RunContext.ReadRef(PipelineSources.text(readConfig, "source"),
            PipelineSources.text(readConfig, "asset"));
    }

    /** Whether the catalogued asset has rules a gate can check; for a note at validation. */
    boolean hasRules(String urn) {
        if (urn == null) {
            return false;
        }
        try {
            return sources.rules(urn).stream().anyMatch(r -> r.type() != io.kilnmark.server.quality.RuleType.REFERENCES
                && r.type() != io.kilnmark.server.quality.RuleType.CUSTOM_SQL);
        } catch (RuntimeException e) {
            return true; // cannot tell: say nothing
        }
    }
}
