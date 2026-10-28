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
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepCategories;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.server.quality.Rule;

/**
 * The {@code gate} step (docs/design/data-quality.md section 7, docs/guide/pipelines.md "Quality
 * gates"): checks the rows passing through against quality rules in the same pass, and stops the
 * run, sets failing rows aside on {@code quarantine}, or lets them through with a warning. The
 * rules are the enabled rules of the asset read upstream ({@code asset_rules}, on by default), and
 * any written on the step ({@code rules}).
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
public final class GateStepFactory implements StepFactory {

    public static final String TYPE = QualityGate.TYPE;
    public static final List<String> ON_FAIL = List.of("stop", "quarantine", "warn");

    private final PipelineSources sources;

    public GateStepFactory(PipelineSources sources) {
        this.sources = sources;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.GATE;
    }

    @Override
    public String category() {
        return StepCategories.QUALITY;
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
            SettingSpec.choice("on_fail", "When rows fail", false, "stop", "stop fails the run before anything after "
                + "the gate finishes; quarantine sends failing rows to the quarantine port and the rest on; warn lets "
                + "every row through and records what failed", ON_FAIL),
            SettingSpec.optional("asset_rules", "Check the asset's rules", SettingSpec.Kind.BOOLEAN, true,
                "Checks the enabled quality rules of the table or file read upstream"),
            SettingSpec.optional("rules_from", "Rules of read step", SettingSpec.Kind.TEXT, null,
                "When rows come from more than one read step: the id of the one whose asset's rules to check"),
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
        try {
            inline = GateRules.read(config.get("rules"), null);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        for (String key : List.of("min_score", "max_failing_share")) {
            Double v = share(config.get(key));
            if (config.get(key) != null && (v == null || v < 0 || v > 1)) {
                problems.add(key + ": a share from 0 to 1, such as " + (key.equals("min_score") ? "0.95 for 95%"
                    : "0.05 for 5%"));
            }
        }
        if (!assetRules(config) && inline.isEmpty() && config.get("rules") == null) {
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
        throws StepException {
        if (!QualityGate.PORTS.contains(port)) {
            throw new StepException("A gate step has no output port '" + port + "'");
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
            throw new StepException(e.getMessage(), e);
        }
        return in; // the gate never changes the columns: out and quarantine carry the rows as they came
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        List<Rule> inline;
        try {
            inline = GateRules.read(config.get("rules"), null);
        } catch (IllegalArgumentException e) {
            throw new StepException(e.getMessage(), e);
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
     * For a gate, the read steps upstream of it, nearest first: every read whose rows can reach it.
     *
     * @param types step id to type
     */
    static List<String> readsUpstream(String gate, List<Edge> edges, Map<String, String> types) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(gate));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (Edge e : edges) {
                if (e.to().step().equals(s) && seen.add(e.from().step())) {
                    todo.add(e.from().step());
                    if (ReadStepFactory.TYPE.equals(types.get(e.from().step()))) {
                        out.add(e.from().step());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Which read step's asset a gate takes its rules from, or the problem with it (starting with
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
                return new Upstream(null, "rules_from: '" + named + "' is not a read step whose rows reach this gate"
                    + (reads.isEmpty() ? "" : "; the read steps upstream are " + String.join(", ", reads)), "rules_from");
            }
            return new Upstream(named, null, null);
        }
        if (reads.isEmpty()) {
            return new Upstream(null, null, null); // nothing read upstream: a note says the gate checks only its own rules
        }
        if (reads.size() > 1) {
            return new Upstream(null, "rules_from: rows reach this gate from the read steps " + String.join(", ", reads)
                + "; name the one whose asset's rules to check, or set asset_rules: false", "asset_rules");
        }
        return new Upstream(reads.get(0), null, null);
    }

    /** The source and asset a read step names. */
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
