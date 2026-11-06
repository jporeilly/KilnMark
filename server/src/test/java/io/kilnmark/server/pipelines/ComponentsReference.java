package io.kilnmark.server.pipelines;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.server.flows.FlowSourceChecks;
import io.kilnmark.server.quality.RuleType;
import io.kilnmark.server.sources.CommonSettings;

/**
 * The parts of the components reference (docs/guide/components-reference.md and the
 * reference-*.md pages) made from the catalogue the server itself uses: every node type's ports and
 * settings, every connector's abilities and settings, the settings every source has, each flow node's
 * controls and values, and each quality rule's facts. Each part sits on its page between two marker
 * lines, written as Markdown link definitions so that neither the app nor GitHub shows them:
 *
 * <pre>{@code
 * [generated: node read]: #
 *
 * ...made from the catalogue...
 *
 * [end: node read]: #
 * }</pre>
 *
 * {@link ComponentsReferenceTest} fails when a part differs from what this makes, or when a component
 * has no part (no entry); {@code ./gradlew :server:writeReference} writes them again.
 */
final class ComponentsReference {

    /** The pages, in docs/guide. */
    static final String INDEX = "components-reference.md";
    static final String NODES = "reference-pipeline-nodes.md";
    static final String FLOW_NODES = "reference-flow-nodes.md";
    static final String CONNECTORS = "reference-connectors.md";
    static final String RULES = "reference-quality-rules.md";
    static final List<String> PAGES = List.of(INDEX, NODES, FLOW_NODES, CONNECTORS, RULES);

    /** A part's first and last line. */
    static final Pattern START = Pattern.compile("(?m)^\\[generated: ([^\\]\\r\\n]+)\\]: #\\r?$");

    static String end(String name) {
        return "[end: " + name + "]: #";
    }

    /** The connectors the server's tests add (META-INF/services in server/src/test): never in a release. */
    private static final Set<String> TEST_CONNECTORS = Set.of("MemoryConnector", "FlakyKeysConnector");

    /** The components, made once: the server's node types and its connectors, as it loads them. */
    final List<NodeFactory> nodes;
    final List<Connector> connectors;
    final FakeSources sources;
    final WriteNodeFactory write;

    ComponentsReference() {
        sources = new FakeSources();
        sources.connectors.clear();
        List<Connector> real = new ArrayList<>();
        for (Connector c : Connectors.load().all()) {
            // the server's tests add connectors of their own (MemoryConnector, ...): never in a release
            if (!TEST_CONNECTORS.contains(c.getClass().getSimpleName())) {
                real.add(c);
                sources.connectors.put(c.kind(), c);
            }
        }
        connectors = List.copyOf(real);
        write = new WriteNodeFactory(sources);
        nodes = List.copyOf(NodeFactories.discover().register(new ReadNodeFactory(sources)).register(write)
            .register(new GateNodeFactory(sources)).all());
    }

    /** Every part, by name, with the page it belongs on. */
    Map<String, Part> parts() {
        Map<String, Part> out = new LinkedHashMap<>();
        out.put("index nodes", new Part(INDEX, nodeIndex()));
        out.put("index connectors", new Part(INDEX, connectorIndex()));
        out.put("index rules", new Part(INDEX, ruleIndex()));
        out.put("index functions", new Part(INDEX, functionIndex()));
        for (NodeFactory f : nodes) {
            out.put("node " + f.type(), new Part(NODES, node(f)));
        }
        for (String type : FlowDefinition.TYPES) {
            out.put("flow-node " + type, new Part(FLOW_NODES, flowNode(type)));
        }
        out.put("source settings", new Part(CONNECTORS, sourceSettings()));
        for (Connector c : connectors) {
            out.put("connector " + c.kind(), new Part(CONNECTORS, connector(c)));
        }
        for (RuleType t : RuleType.values()) {
            out.put("rule " + t.id(), new Part(RULES, rule(t)));
        }
        return out;
    }

    /** One generated part: the page it goes on and what it holds. */
    record Part(String page, String text) {
    }

    // ---- the index ----

    private String nodeIndex() {
        StringBuilder b = new StringBuilder("| Node | Kind | In the palette | What it does |\n| --- | --- | --- | --- |\n");
        nodes.stream().sorted(Comparator.comparing((NodeFactory f) -> f.kind()).thenComparing(NodeFactory::type))
            .forEach(f -> b.append("| [`").append(f.type()).append("`](").append(NODES).append('#').append(f.type())
                .append(") | ").append(lower(f.kind().name())).append(" | ").append(cell(f.label())).append(", in ")
                .append(f.category()).append(" | ").append(cell(firstSentence(f.help()))).append(" |\n"));
        return b.toString();
    }

    private String connectorIndex() {
        StringBuilder b = new StringBuilder("| Connector | Kind | Scan | Read | Write | Explore | Your own SQL |\n"
            + "| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Connector c : connectors) {
            b.append("| [").append(cell(c.displayName())).append("](").append(CONNECTORS).append('#')
                .append(anchor(c.displayName())).append(") | `").append(c.kind()).append("` | ")
                .append(yes(c.capabilities().contains(Connector.Capability.DISCOVER))).append(" | ")
                .append(yes(c.capabilities().contains(Connector.Capability.READ))).append(" | ")
                .append(yes(c.capabilities().contains(Connector.Capability.WRITE))).append(" | ")
                .append(yes(c.explores())).append(" | ").append(yes(allowsSql(c))).append(" |\n");
        }
        return b.toString();
    }

    private String ruleIndex() {
        StringBuilder b = new StringBuilder("| Rule | Dimension | Needs a column | On a gate |\n| --- | --- | --- | --- |\n");
        for (RuleType t : RuleType.values()) {
            b.append("| [`").append(t.id()).append("`](").append(RULES).append('#').append(t.id())
                .append(") | ").append(t.dimension().label()).append(" | ").append(needsField(t)).append(" | ")
                .append(yes(GateRules.PARAMS.containsKey(t))).append(" |\n");
        }
        return b.toString();
    }

    private static String functionIndex() {
        return "- [Functions](pipeline-nodes.md#functions), for text, numbers, dates and regular expressions: "
            + codes(Expression.functions()) + ".\n- [Aggregate functions](pipeline-nodes.md#aggregate), for an "
            + "aggregate node's values: " + codes(Expression.aggregates()) + ".\n";
    }

    /** Names in code, sorted, joined as a list in words. */
    private static String codes(Set<String> names) {
        List<String> sorted = names.stream().sorted().map(n -> "`" + n + "`").toList();
        return sorted.size() == 1 ? sorted.getFirst()
            : String.join(", ", sorted.subList(0, sorted.size() - 1)) + " and " + sorted.getLast();
    }

    // ---- pipeline nodes ----

    private String node(NodeFactory f) {
        StringBuilder b = new StringBuilder();
        b.append("**").append(cell(f.label())).append("**: a ").append(lower(f.kind().name()))
            .append(" node, in the palette's ").append(f.category()).append(" folder. ").append(sentence(f.help()))
            .append("\n\n");
        b.append("- Reads from: ").append(ports(f.inputPorts())).append('\n');
        b.append("- Writes to: ").append(ports(f.outputPorts())).append("\n\n");
        List<SettingSpec> own = f == write ? WriteNodeFactory.BASE : f.settings();
        b.append("| Setting | Type | Required | Default | Parameters | What it does |\n")
            .append("| --- | --- | --- | --- | --- | --- |\n");
        for (SettingSpec s : own) {
            b.append("| `").append(s.key()).append("` | ").append(kind(s.kind())).append(" | ").append(yes(s.required()))
                .append(" | ").append(value(s.defaultValue())).append(" | ").append(parameters(s)).append(" | ")
                .append(cell(sentence(s.help()) + choices(s.choices()))).append(" |\n");
        }
        if (f == write) {
            b.append("\nThe writer settings, which only some kinds of source take:\n\n")
                .append("| Setting | Type | Default | Sources that take it | What it does |\n")
                .append("| --- | --- | --- | --- | --- |\n");
            // Each writer setting as the writers taking it declare it, connectors in order of kind: where
            // they differ, the table says so and each connector's entry has its own.
            Map<String, Map<String, SettingSpec>> byKey = new LinkedHashMap<>();
            for (Connector c : connectors) {
                Set<String> keys = WriteNodeFactory.writerKeys(c);
                for (SettingSpec s : c.writeOptions()) {
                    if (keys.contains(s.key())) {
                        byKey.computeIfAbsent(s.key(), k -> new TreeMap<>()).put(c.kind(), s);
                    }
                }
            }
            byKey.forEach((key, specs) -> {
                List<SettingSpec> all = List.copyOf(specs.values());
                SettingSpec first = all.getFirst();
                boolean sameKind = all.stream().allMatch(x -> x.kind() == first.kind());
                boolean sameDefault = all.stream().allMatch(x -> java.util.Objects.equals(x.defaultValue(),
                    first.defaultValue()));
                boolean sameHelp = all.stream().allMatch(x -> x.help().equals(first.help())
                    && x.choices().equals(first.choices()));
                b.append("| `").append(key).append("` | ").append(sameKind ? kind(first.kind()) : "differs").append(" | ")
                    .append(sameDefault ? value(first.defaultValue()) : "differs").append(" | ")
                    .append(String.join(", ", specs.keySet().stream().map(k -> "`" + k + "`").toList())).append(" | ")
                    .append(sameHelp ? cell(sentence(first.help()) + choices(first.choices()))
                        : "Differs by kind of source: see each one in [Reference: connectors](" + CONNECTORS + ").")
                    .append(" |\n");
            });
        }
        return b.toString();
    }

    private static String ports(List<String> ports) {
        if (ports.isEmpty()) {
            return "nothing";
        }
        return String.join(", ", ports.stream().map(p -> p.endsWith("*")
            ? "`" + p.substring(0, p.length() - 1) + "` (any number of edges)" : "`" + p + "`").toList());
    }

    /** What ${...} may do in a setting, as the pipeline compiler fills it ({@link SettingSpec#fill()}). */
    private static String parameters(SettingSpec s) {
        return switch (s.fill()) {
            case NONE -> "no";
            case TARGET_PART -> "part of the name";
            case TEXT, EXPRESSION, EXPRESSIONS -> "yes";
            case WHOLE_VALUE -> "whole value";
        };
    }

    private static String kind(SettingSpec.Kind k) {
        return switch (k) {
            case TEXT -> "text";
            case NUMBER -> "number";
            case BOOLEAN -> "true or false";
            case CHOICE -> "choice";
            case URL -> "address";
            case EXPRESSION -> "expression";
            case EXPRESSIONS -> "names and expressions";
            case COLUMN -> "column";
            case COLUMNS -> "list of columns";
            case ORDER -> "columns in order";
            case COLUMN_MAP -> "column to column";
            case CONDITIONS -> "names and conditions";
            case SOURCE -> "saved source";
            case ASSET -> "table or file";
            case RULES -> "list of rules";
        };
    }

    // ---- flow nodes ----

    private static String flowNode(String type) {
        StringBuilder b = new StringBuilder();
        b.append("- `timeout`: ").append(FlowDefinition.TIMED.contains(type) ? "yes: its work is stopped past it"
            : FlowDefinition.TYPE_KEYS.get(type).contains("timeout") ? "yes: how long it waits before it gives up"
            : "no").append('\n');
        b.append("- Tried again (`retries`): ").append(yes(FlowDefinition.RETRIED.contains(type))).append('\n');
        b.append("- Priority: ").append(yes(FlowDefinition.PRIORITISED.contains(type))).append('\n');
        Map<String, String> reports = new TreeMap<>();
        FlowDefinition.reports(type).forEach((k, v) -> reports.put(k, lower(v.name())));
        StringBuilder values = new StringBuilder("`status` (text)");
        reports.forEach((k, v) -> values.append(", `").append(k).append("` (").append(v).append(')'));
        b.append("- Reports, for conditions and `${node.value}`: ").append(values).append('\n');
        return b.toString();
    }

    // ---- connectors ----

    private String sourceSettings() {
        StringBuilder b = new StringBuilder("| Setting | Type | Default | What it does |\n| --- | --- | --- | --- |\n");
        for (CommonSettings.Definition d : CommonSettings.definitions()) {
            b.append("| `").append(d.key()).append("` | ").append(kind(d.kind())).append(d.multiple() ? ", several" : "")
                .append(" | ").append(value(d.defaultValue())).append(" | ")
                .append(cell(sentence(d.hint()) + (d.kind() == SettingSpec.Kind.CHOICE ? choices(d.choices()) : "")))
                .append(" |\n");
        }
        return b.toString();
    }

    private String connector(Connector c) {
        StringBuilder b = new StringBuilder();
        b.append("Kind `").append(c.kind()).append("`, listed under ").append(c.category()).append(" (")
            .append(c.family()).append(").\n\n");
        b.append("| Scan | Read | Write | Preview | Explore | Your own SQL |\n| --- | --- | --- | --- | --- | --- |\n");
        boolean reads = c.capabilities().contains(Connector.Capability.READ);
        b.append("| ").append(yes(c.capabilities().contains(Connector.Capability.DISCOVER))).append(" | ")
            .append(yes(reads)).append(" | ").append(yes(c.capabilities().contains(Connector.Capability.WRITE)))
            .append(" | ").append(yes(reads)).append(" | ").append(yes(c.explores())).append(" | ")
            .append(allowsSql(c) ? "yes, with Allow SQL on" : "no").append(" |\n\n");
        if (!c.formats().isEmpty()) {
            b.append("- Formats it reads: ").append(String.join(", ", c.formats())).append('\n');
        }
        b.append("- In flows: ").append(inFlows(c)).append('\n');
        Connector.Driver d = c.driver();
        if (c instanceof io.kilnmark.kiln.plugins.jdbc.GenericJdbcConnector) {
            b.append("- Driver: the JDBC driver an admin registers on this server\n");
        } else if (d == null) {
            b.append("- Driver: ships with Kilnmark\n");
        } else {
            b.append("- Driver: installed by an admin (`").append(d.coordinates()).append("` ").append(d.version())
                .append(", ").append(d.licence()).append(d.bundleable() ? "" : "; Kilnmark may not ship it")
                .append(")\n");
        }
        b.append("- Who sets one up: ").append(c.adminOnly() ? "admins only" : "designers and admins").append("\n\n");
        if (c.settings().isEmpty()) {
            b.append("It has no settings of its own.\n");
        } else {
            b.append("| Setting | Type | Required | Default | What it does |\n| --- | --- | --- | --- | --- |\n");
        }
        for (Connector.Setting s : c.settings()) {
            b.append("| `").append(s.key()).append("` | ").append(kind(s.kind())).append(s.multiple() ? ", several" : "")
                .append(" | ").append(yes(s.required())).append(" | ").append(value(s.defaultValue())).append(" | ")
                .append(cell(sentence(s.hint()) + choices(s.choices()))).append(" |\n");
        }
        Set<String> writerKeys = WriteNodeFactory.writerKeys(c);
        if (!writerKeys.isEmpty()) {
            b.append("\nA write node writing to this kind of source also takes:\n\n")
                .append("| Setting | Type | Default | What it does |\n| --- | --- | --- | --- |\n");
            for (SettingSpec s : c.writeOptions()) {
                if (writerKeys.contains(s.key())) {
                    b.append("| `").append(s.key()).append("` | ").append(kind(s.kind())).append(" | ")
                        .append(value(s.defaultValue())).append(" | ")
                        .append(cell(sentence(s.help()) + choices(s.choices()))).append(" |\n");
                }
            }
        }
        return b.toString();
    }

    /** Whether SQL a person writes (query reads, custom rules, sql nodes) may run on a source of this kind. */
    private static boolean allowsSql(Connector c) {
        Records.Source s = new Records.Source(1, "probe", c.kind(), Map.of(CommonSettings.ALLOW_SQL, "true"), false,
            Instant.EPOCH, "probe");
        return ReadNodeFactory.queryProblem(s, c) == null;
    }

    /** What check-source and wait-for nodes may ask of a source of this kind, as saving a flow checks it. */
    private static String inFlows(Connector c) {
        FakeSources probe = new FakeSources();
        probe.connectors.put(c.kind(), c);
        probe.add("probe", c.kind(), Map.of(CommonSettings.ALLOW_SQL, "true", CommonSettings.ALLOW_WRITING, "true"),
            null);
        List<String> can = new ArrayList<>();
        can.add("`check-source` can reach it");
        if (flowAccepts(probe, "type: check-source, source: probe, tables: [a.b]")) {
            can.add("look for its tables");
        }
        if (flowAccepts(probe, "type: check-source, source: probe, files: [a/b.csv]")) {
            can.add("look for its files");
        }
        List<String> wait = new ArrayList<>();
        if (flowAccepts(probe, "type: wait-for, source: probe, file: \"in/*.csv\"")) {
            wait.add("a file");
        }
        if (flowAccepts(probe, "type: wait-for, source: probe, query: \"select true\"")) {
            wait.add("a query");
        }
        String out = String.join(", ", can.subList(0, can.size() - 1)) + (can.size() > 1 ? " and " : "")
            + can.get(can.size() - 1);
        if (!wait.isEmpty()) {
            out += "; `wait-for` can wait for " + String.join(" or ", wait);
        }
        if (flowAccepts(probe, "type: sql, source: probe, statements: [\"ANALYZE a.b\"]")) {
            out += "; a `sql` node can run on it, with Allow SQL and Allow writing on";
        }
        return out;
    }

    private static boolean flowAccepts(PipelineSources sources, String node) {
        FlowDefinition.Parsed p = FlowDefinition.parse("kilnmark: flow/v1\nname: probe\nnodes:\n  - {id: n, " + node
            + "}\n");
        if (!p.valid()) {
            throw new IllegalStateException("The probe flow does not read: " + p.problems());
        }
        return FlowSourceChecks.problems(p.definition(), sources).isEmpty();
    }

    // ---- quality rules ----

    private static String rule(RuleType t) {
        StringBuilder b = new StringBuilder();
        b.append("- Dimension: ").append(t.dimension().label()).append(" (`").append(t.dimension().name()
            .toLowerCase(Locale.ROOT)).append("`)\n");
        b.append("- Column: ").append(needsField(t)).append('\n');
        b.append("- Result: ").append(t.wholeAsset() ? "pass or fail for the whole asset; thresholds do not apply"
            : "a count or share of failing rows, compared with its thresholds").append('\n');
        Map<String, String> keys = GateRules.PARAMS.get(t);
        if (keys == null) {
            b.append("- On a gate: no; it runs in the source's database, from the quality pages or the API\n");
        } else {
            Map<String, String> sorted = new TreeMap<>(keys);
            List<String> pairs = new ArrayList<>();
            sorted.forEach((yaml, api) -> pairs.add(yaml.equals(api) ? "`" + yaml + "`" : "`" + yaml + "` (`" + api
                + "` in the API)"));
            b.append("- On a gate: yes, with ").append(String.join(", ", pairs)).append('\n');
        }
        return b.toString();
    }

    private static String needsField(RuleType t) {
        if (t == RuleType.UNIQUE) {
            return "yes, or several in `fields`";
        }
        if (t == RuleType.CUSTOM_SQL) {
            return "optional";
        }
        return t.needsField() ? "yes" : "no";
    }

    // ---- words ----

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A value as a table shows it: in code, or empty for none. */
    private static String value(Object v) {
        if (v == null || (v instanceof String s && s.isEmpty())) {
            return "";
        }
        if (v instanceof Double d && d == Math.rint(d)) {
            v = BigDecimal.valueOf(d).toBigInteger();
        }
        return "`" + cell(String.valueOf(v)) + "`";
    }

    private static String choices(List<String> choices) {
        if (choices.isEmpty()) {
            return "";
        }
        return " One of " + String.join(", ", choices.stream().map(c -> "`" + c + "`").toList()) + ".";
    }

    /** The first sentence of {@code s}. */
    private static String firstSentence(String s) {
        int end = s.indexOf(". ");
        return sentence(end < 0 ? s : s.substring(0, end));
    }

    /** A sentence ending in a full stop. */
    private static String sentence(String s) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty()) {
            return t;
        }
        return t.endsWith(".") ? t : t + ".";
    }

    /** Text safe in a table cell: one line, with bars escaped. */
    private static String cell(String s) {
        return s.replaceAll("\\s+", " ").replace("|", "\\|").strip();
    }

    /** The anchor the app, the assistant and GitHub give a heading. */
    static String anchor(String heading) {
        return io.kilnmark.server.ai.assistant.DocSlugs.slug(heading);
    }

    // ---- reading and writing a page's parts ----

    /** The parts written on a page, by name, as they are now. */
    static Map<String, String> partsOn(String page) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = START.matcher(page);
        while (m.find()) {
            String name = m.group(1);
            int close = page.indexOf(end(name), m.end());
            if (close < 0) {
                throw new IllegalStateException("The part '" + name + "' has no end line: " + end(name));
            }
            if (out.put(name, page.substring(m.end(), close).replace("\r\n", "\n").strip() + "\n") != null) {
                throw new IllegalStateException("The part '" + name + "' is on the page twice");
            }
        }
        return out;
    }

    /** {@code page} with each part named in {@code parts} replaced by its new text. */
    static String withParts(String page, Map<String, String> parts) {
        StringBuilder b = new StringBuilder();
        Matcher m = START.matcher(page);
        int at = 0;
        while (m.find()) {
            String name = m.group(1);
            int close = page.indexOf(end(name), m.end());
            if (close < 0 || !parts.containsKey(name)) {
                continue;
            }
            b.append(page, at, m.end()).append("\n\n").append(parts.get(name).strip()).append("\n\n");
            at = close;
        }
        return b.append(page.substring(at)).toString();
    }
}
