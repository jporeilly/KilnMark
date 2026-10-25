package io.kilnmark.server.pipelines;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.arrow.vector.types.pojo.Schema;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;

/**
 * TEMPORARY: a minimal compiler for pipeline YAML, so the service, its API and the UI work before
 * kiln-core's {@code PipelineCompiler} is merged. Replace it through {@link PipelineCompilers}; see
 * {@link PipelineCompilerPort}. It reads the format of docs/design/pipelines.md section 2 with
 * SnakeYAML's node tree (for line numbers; nothing is constructed from tags), and checks keys,
 * step types, settings, ports, edges and loops.
 *
 * <p>Ports declared with a trailing {@code *} (union's {@code in*}) are named without it in edges
 * and take any number of edges; every other input port takes exactly one.
 */
final class InterimPipelineCompiler implements PipelineCompilerPort {

    static final String FORMAT = "pipeline/v1";
    private static final Set<String> TOP = Set.of("kilnmark", "name", "description", "steps");
    private static final Set<String> STRUCTURAL = Set.of("id", "type", "input", "inputs");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,62}");

    @Override
    public Compilation check(String yaml, StepFactories factories) {
        return run(yaml, factories, false);
    }

    @Override
    public Compilation compile(String yaml, StepFactories factories) {
        return run(yaml, factories, true);
    }

    /** One step as read. */
    private static final class Parsed {
        String id;
        String type;
        StepFactory factory;
        int line;
        int column;
        final Map<String, Object> config = new LinkedHashMap<>();
        final Map<String, Mark> keyMarks = new HashMap<>();
        final List<Pending> inputs = new ArrayList<>();
    }

    /** An edge as written, checked once every step is known. */
    private record Pending(String text, String toPort, Mark mark) {
    }

    private Compilation run(String yaml, StepFactories factories, boolean create) {
        List<Problem> problems = new ArrayList<>();
        Node root;
        try {
            root = new Yaml(options()).compose(new StringReader(yaml == null ? "" : yaml));
        } catch (MarkedYAMLException e) {
            Mark m = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            problems.add(at(m, null, "The YAML cannot be read: " + plain(e.getProblem())));
            return new Compilation(null, null, problems, List.of(), List.of(), Map.of(), null);
        } catch (YAMLException e) {
            problems.add(new Problem(0, 0, null, "The YAML cannot be read: " + plain(e.getMessage())));
            return new Compilation(null, null, problems, List.of(), List.of(), Map.of(), null);
        }
        if (!(root instanceof MappingNode top)) {
            problems.add(new Problem(root == null ? 0 : line(root.getStartMark()), 1, null,
                "A pipeline is a set of keys: kilnmark, name, description and steps"));
            return new Compilation(null, null, problems, List.of(), List.of(), Map.of(), null);
        }
        String name = null;
        String description = null;
        String format = null;
        SequenceNode stepsNode = null;
        for (NodeTuple t : top.getValue()) {
            String key = key(t, problems, null);
            if (key == null) {
                continue;
            }
            Node v = t.getValueNode();
            switch (key) {
                case "kilnmark" -> format = scalar(v, "kilnmark", null, problems);
                case "name" -> name = scalar(v, "name", null, problems);
                case "description" -> description = scalar(v, "description", null, problems);
                case "steps" -> {
                    if (v instanceof SequenceNode s) {
                        stepsNode = s;
                    } else {
                        problems.add(at(v.getStartMark(), null, "steps: a list of steps, each starting with '- id:'"));
                    }
                }
                default -> problems.add(at(t.getKeyNode().getStartMark(), null, "Unknown key '" + key
                    + "'. A pipeline has " + String.join(", ", List.of("kilnmark", "name", "description", "steps"))));
            }
        }
        duplicates(top, problems, null);
        if (format == null) {
            problems.add(new Problem(1, 1, null, "kilnmark: start the pipeline with 'kilnmark: " + FORMAT + "'"));
        } else if (!FORMAT.equals(format)) {
            problems.add(new Problem(markLine(top, "kilnmark"), 1, null, "kilnmark: this server reads '" + FORMAT
                + "', not '" + format + "'"));
        }
        List<Parsed> steps = new ArrayList<>();
        if (stepsNode == null) {
            if (top.getValue().stream().noneMatch(t -> "steps".equals(keyText(t)))) {
                problems.add(new Problem(0, 0, null, "steps: a pipeline needs at least one step"));
            }
        } else {
            if (stepsNode.getValue().isEmpty()) {
                problems.add(at(stepsNode.getStartMark(), null, "steps: a pipeline needs at least one step"));
            }
            for (Node n : stepsNode.getValue()) {
                Parsed p = step(n, factories, problems);
                if (p != null) {
                    steps.add(p);
                }
            }
        }

        // Ids, edges and ports.
        Map<String, Parsed> byId = new LinkedHashMap<>();
        for (Parsed p : steps) {
            if (p.id == null) {
                continue;
            }
            if (byId.putIfAbsent(p.id, p) != null) {
                problems.add(new Problem(p.line, p.column, p.id, "id: '" + p.id + "' is used by another step; "
                    + "each step needs its own id"));
            }
        }
        List<EdgeOutline> edges = new ArrayList<>();
        for (Parsed p : steps) {
            if (p.id == null || p.factory == null) {
                continue;
            }
            Map<String, Integer> connected = new HashMap<>();
            for (Pending in : p.inputs) {
                PortRef from;
                try {
                    from = PortRef.parse(in.text(), "out");
                } catch (IllegalArgumentException e) {
                    problems.add(at(in.mark(), p.id, "input: name the step that feeds this one, as id or id.port"));
                    continue;
                }
                Parsed source = byId.get(from.step());
                if (source == null) {
                    problems.add(at(in.mark(), p.id, "input: there is no step called '" + from.step() + "'"));
                    continue;
                }
                if (source == p) {
                    problems.add(at(in.mark(), p.id, "input: a step cannot feed itself"));
                    continue;
                }
                if (source.factory != null) {
                    List<String> outs = ports(source.factory.outputPorts(source.config));
                    if (!outs.contains(from.port())) {
                        problems.add(at(in.mark(), p.id, "input: step '" + source.id + "' has "
                            + (outs.isEmpty() ? "no outputs: a " + source.type + " step ends a pipeline"
                            : "no output port '" + from.port() + "'; its ports are " + String.join(", ", outs))));
                        continue;
                    }
                }
                String toPort = in.toPort();
                List<String> ins = p.factory.inputPorts();
                if (toPort == null) {
                    if (ins.isEmpty()) {
                        problems.add(at(in.mark(), p.id, "input: a " + p.type + " step reads from its source and takes "
                            + "no input"));
                        continue;
                    }
                    if (ins.size() > 1) {
                        problems.add(at(in.mark(), p.id, "input: a " + p.type + " step has the inputs "
                            + String.join(" and ", ports(ins)) + "; connect them with inputs: {"
                            + String.join(": ..., ", ports(ins)) + ": ...}"));
                        continue;
                    }
                    toPort = ins.get(0);
                } else if (!ins.contains(toPort) && !ins.contains(toPort + "*")) {
                    problems.add(at(in.mark(), p.id, "inputs: a " + p.type + " step has no input port '" + toPort + "'"
                        + (ins.isEmpty() ? "" : "; its ports are " + String.join(", ", ports(ins)))));
                    continue;
                }
                boolean many = toPort.endsWith("*") || ins.contains(toPort + "*");
                String bare = toPort.endsWith("*") ? toPort.substring(0, toPort.length() - 1) : toPort;
                int n = connected.merge(bare, 1, Integer::sum);
                if (n > 1 && !many) {
                    problems.add(at(in.mark(), p.id, "input: port '" + bare + "' takes one input; add a union step to "
                        + "combine several"));
                    continue;
                }
                edges.add(new EdgeOutline(source.id, from.port(), p.id, bare));
            }
            for (String port : ports(p.factory.inputPorts())) {
                if (!connected.containsKey(port)) {
                    problems.add(new Problem(p.line, p.column, p.id, p.factory.inputPorts().size() == 1
                        ? "input: a " + p.type + " step needs an input; add input: <id of the step feeding it>"
                        : "inputs: connect the '" + port + "' input of this " + p.type + " step"));
                }
            }
        }
        List<String> order = order(byId.keySet(), edges);
        if (order == null) {
            problems.add(new Problem(0, 0, null, "The steps form a loop: a pipeline must flow one way. Check the "
                + "input of each step"));
            order = new ArrayList<>(byId.keySet());
        }

        // Columns leaving each step, in run order.
        Map<String, Map<String, Schema>> outputs = new HashMap<>();
        Map<String, Map<String, List<Column>>> columns = new HashMap<>();
        for (String id : order) {
            Parsed p = byId.get(id);
            if (p.factory == null) {
                continue;
            }
            Map<String, Schema> ins = new LinkedHashMap<>();
            boolean known = true;
            for (EdgeOutline e : edges) {
                if (!e.toStep().equals(id)) {
                    continue;
                }
                Schema s = outputs.getOrDefault(e.fromStep(), Map.of()).get(e.fromPort());
                if (s == null) {
                    known = false;
                    break;
                }
                String key = ins.containsKey(e.toPort()) ? e.toPort() + "." + ins.size() : e.toPort();
                ins.put(key, s);
            }
            Map<String, Schema> out = new LinkedHashMap<>();
            Map<String, List<Column>> cols = new LinkedHashMap<>();
            for (String port : ports(p.factory.outputPorts(p.config))) {
                Schema s = null;
                if (known) {
                    try {
                        s = p.factory.outputSchema(port, ins, p.config);
                    } catch (StepException e) {
                        problems.add(new Problem(p.line, p.column, id, plain(e.getMessage())));
                    } catch (RuntimeException e) {
                        problems.add(new Problem(p.line, p.column, id, "The columns of this step could not be "
                            + "worked out: " + plain(e.getMessage())));
                    }
                }
                out.put(port, s);
                cols.put(port, s == null ? null : s.getFields().stream()
                    .map(f -> new Column(f.getName(), ArrowKinds.kind(f))).toList());
            }
            outputs.put(id, out);
            columns.put(id, cols);
        }

        List<StepOutline> outlines = new ArrayList<>();
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        for (Parsed p : steps) {
            if (p.id == null) {
                continue;
            }
            configs.putIfAbsent(p.id, p.config);
            outlines.add(new StepOutline(p.id, p.type, p.factory == null ? null
                : p.factory.kind().name().toLowerCase(Locale.ROOT), p.line,
                p.factory == null ? List.of() : ports(p.factory.inputPorts()),
                p.factory == null ? List.of() : ports(p.factory.outputPorts(p.config)),
                columns.getOrDefault(p.id, Map.of())));
        }
        List<Problem> unique = new ArrayList<>(new LinkedHashSet<>(problems));
        unique.sort(java.util.Comparator.comparingInt(Problem::line));
        if (!create || !unique.isEmpty()) {
            return new Compilation(name, description, unique, outlines, edges, configs, null);
        }

        // Every step for one run.
        List<GraphStep> graphSteps = new ArrayList<>();
        List<Problem> failures = new ArrayList<>();
        for (Parsed p : steps) {
            try {
                Step step = p.factory.create(p.config);
                graphSteps.add(new GraphStep(p.id, step, p.config));
            } catch (StepException e) {
                failures.add(new Problem(p.line, p.column, p.id, plain(e.getMessage())));
            } catch (RuntimeException e) {
                failures.add(new Problem(p.line, p.column, p.id, "Step " + p.id + " could not be set up: "
                    + plain(e.getMessage())));
            }
        }
        if (!failures.isEmpty()) {
            graphSteps.forEach(g -> closeQuietly(g.step()));
            return new Compilation(name, description, failures, outlines, edges, configs, null);
        }
        List<Edge> graphEdges = edges.stream().map(e -> new Edge(new PortRef(e.fromStep(), e.fromPort()),
            new PortRef(e.toStep(), e.toPort()))).toList();
        PipelineGraph graph = new PipelineGraph(name == null ? "pipeline" : name, graphSteps, graphEdges);
        List<Problem> shape = graph.check().stream().map(m -> new Problem(0, 0, null, m)).toList();
        if (!shape.isEmpty()) {
            graphSteps.forEach(g -> closeQuietly(g.step()));
            return new Compilation(name, description, shape, outlines, edges, configs, null);
        }
        return new Compilation(name, description, List.of(), outlines, edges, configs, graph);
    }

    private static LoaderOptions options() {
        LoaderOptions o = new LoaderOptions();
        o.setAllowDuplicateKeys(true); // reported as problems with their lines below
        o.setMaxAliasesForCollections(20);
        o.setAllowRecursiveKeys(false);
        o.setNestingDepthLimit(40);
        o.setCodePointLimit(8 * 1024 * 1024);
        return o;
    }

    private Parsed step(Node n, StepFactories factories, List<Problem> problems) {
        if (!(n instanceof MappingNode m)) {
            problems.add(at(n.getStartMark(), null, "Each step is a set of keys starting with '- id:'"));
            return null;
        }
        Parsed p = new Parsed();
        p.line = line(m.getStartMark());
        p.column = m.getStartMark().getColumn() + 1;
        Map<String, NodeTuple> keyed = new LinkedHashMap<>();
        for (NodeTuple t : m.getValue()) {
            String key = key(t, problems, null);
            if (key != null) {
                keyed.putIfAbsent(key, t);
                p.keyMarks.putIfAbsent(key, t.getKeyNode().getStartMark());
            }
        }
        NodeTuple idTuple = keyed.get("id");
        if (idTuple == null) {
            problems.add(new Problem(p.line, p.column, null, "id: every step needs an id, such as 'customers'"));
        } else {
            String id = scalar(idTuple.getValueNode(), "id", null, problems);
            if (id != null && !ID.matcher(id).matches()) {
                problems.add(at(idTuple.getValueNode().getStartMark(), null, "id: '" + id + "' is not a step id: "
                    + "start with a small letter, then small letters, digits, - or _ (at most 63)"));
            } else {
                p.id = id;
            }
        }
        duplicates(m, problems, p.id);
        NodeTuple typeTuple = keyed.get("type");
        if (typeTuple == null) {
            problems.add(new Problem(p.line, p.column, p.id, "type: say what the step does, for example 'type: read'"));
        } else {
            p.type = scalar(typeTuple.getValueNode(), "type", p.id, problems);
            if (p.type != null) {
                p.factory = factories.get(p.type).orElse(null);
                if (p.factory == null) {
                    problems.add(at(typeTuple.getValueNode().getStartMark(), p.id, "type: there is no step type '"
                        + p.type + "'. The types are " + String.join(", ", factories.all().stream()
                        .map(StepFactory::type).toList())));
                }
            }
        }
        NodeTuple input = keyed.get("input");
        if (input != null) {
            Node v = input.getValueNode();
            if (v instanceof SequenceNode list) {
                for (Node item : list.getValue()) {
                    String text = scalar(item, "input", p.id, problems);
                    if (text != null) {
                        p.inputs.add(new Pending(text, null, item.getStartMark()));
                    }
                }
            } else {
                String text = scalar(v, "input", p.id, problems);
                if (text != null) {
                    p.inputs.add(new Pending(text, null, v.getStartMark()));
                }
            }
        }
        NodeTuple inputs = keyed.get("inputs");
        if (inputs != null) {
            if (input != null) {
                problems.add(at(inputs.getKeyNode().getStartMark(), p.id, "inputs: use input or inputs, not both"));
            } else if (inputs.getValueNode() instanceof MappingNode ports) {
                for (NodeTuple t : ports.getValue()) {
                    String port = key(t, problems, p.id);
                    if (port == null) {
                        continue;
                    }
                    Node v = t.getValueNode();
                    List<Node> items = v instanceof SequenceNode s ? s.getValue() : List.of(v);
                    for (Node item : items) {
                        String text = scalar(item, "inputs", p.id, problems);
                        if (text != null) {
                            p.inputs.add(new Pending(text, port, item.getStartMark()));
                        }
                    }
                }
            } else {
                problems.add(at(inputs.getValueNode().getStartMark(), p.id, "inputs: a set of port: step pairs, such "
                    + "as inputs: {main: spend, lookup: customers}"));
            }
        }
        Set<String> known = new HashSet<>();
        if (p.factory != null) {
            p.factory.settings().forEach(s -> known.add(s.key()));
        }
        for (Map.Entry<String, NodeTuple> e : keyed.entrySet()) {
            String key = e.getKey();
            if (STRUCTURAL.contains(key)) {
                continue;
            }
            if (p.factory != null && !known.contains(key)) {
                problems.add(at(e.getValue().getKeyNode().getStartMark(), p.id, "Unknown setting '" + key + "' for a "
                    + p.type + " step." + (known.isEmpty() ? " It takes no settings"
                    : " It takes " + String.join(", ", p.factory.settings().stream().map(SettingSpec::key).toList()))));
                continue;
            }
            p.config.put(key, value(e.getValue().getValueNode(), problems, p.id));
        }
        if (p.factory != null) {
            for (SettingSpec s : p.factory.settings()) {
                Object v = p.config.get(s.key());
                if (s.required() && (v == null || v instanceof String str && str.isBlank())) {
                    problems.add(new Problem(p.line, p.column, p.id, s.key() + ": a " + p.type + " step needs "
                        + s.label().toLowerCase(Locale.ROOT) + (s.help().isBlank() ? "" : ". " + s.help())));
                }
            }
            try {
                for (String message : p.factory.check(p.config)) {
                    int colon = message.indexOf(':');
                    Mark mark = colon > 0 ? p.keyMarks.get(message.substring(0, colon).trim()) : null;
                    problems.add(mark != null ? at(mark, p.id, message) : new Problem(p.line, p.column, p.id, message));
                }
            } catch (RuntimeException e) {
                problems.add(new Problem(p.line, p.column, p.id, "The settings could not be checked: "
                    + plain(e.getMessage())));
            }
        }
        return p;
    }

    /** Port names as edges use them: without the trailing {@code *} of a port taking many edges. */
    private static List<String> ports(List<String> declared) {
        return declared.stream().map(d -> d.endsWith("*") ? d.substring(0, d.length() - 1) : d).toList();
    }

    private static List<String> order(Set<String> ids, List<EdgeOutline> edges) {
        Map<String, Integer> waiting = new LinkedHashMap<>();
        ids.forEach(id -> waiting.put(id, 0));
        for (EdgeOutline e : edges) {
            waiting.merge(e.toStep(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        waiting.forEach((id, n) -> {
            if (n == 0) {
                ready.add(id);
            }
        });
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            out.add(id);
            for (EdgeOutline e : edges) {
                if (e.fromStep().equals(id) && waiting.merge(e.toStep(), -1, Integer::sum) == 0) {
                    ready.add(e.toStep());
                }
            }
        }
        return out.size() == ids.size() ? out : null;
    }

    private static void duplicates(MappingNode m, List<Problem> problems, String step) {
        Set<String> seen = new HashSet<>();
        for (NodeTuple t : m.getValue()) {
            String k = keyText(t);
            if (k != null && !seen.add(k)) {
                problems.add(at(t.getKeyNode().getStartMark(), step, k + ": written twice; keep one"));
            }
        }
    }

    private static String keyText(NodeTuple t) {
        return t.getKeyNode() instanceof ScalarNode s ? s.getValue() : null;
    }

    private static String key(NodeTuple t, List<Problem> problems, String step) {
        String k = keyText(t);
        if (k == null) {
            problems.add(at(t.getKeyNode().getStartMark(), step, "Keys are plain names, such as type or input"));
        }
        return k;
    }

    private static String scalar(Node v, String key, String step, List<Problem> problems) {
        if (v instanceof ScalarNode s && s.getTag() != Tag.NULL) {
            return s.getValue();
        }
        problems.add(at(v.getStartMark(), step, key + ": a single value is needed here"));
        return null;
    }

    /** A node as maps, lists, strings, whole and decimal numbers, booleans and nulls. */
    private static Object value(Node n, List<Problem> problems, String step) {
        switch (n) {
            case ScalarNode s -> {
                Tag tag = s.getTag();
                String v = s.getValue();
                if (Tag.NULL.equals(tag)) {
                    return null;
                }
                if (Tag.BOOL.equals(tag)) {
                    String b = v.toLowerCase(Locale.ROOT);
                    return b.equals("true") || b.equals("yes") || b.equals("on");
                }
                if (Tag.INT.equals(tag)) {
                    try {
                        return Long.parseLong(v.replace("_", ""));
                    } catch (NumberFormatException e) {
                        return v;
                    }
                }
                if (Tag.FLOAT.equals(tag)) {
                    try {
                        return Double.parseDouble(v.replace("_", ""));
                    } catch (NumberFormatException e) {
                        return v;
                    }
                }
                return v;
            }
            case SequenceNode s -> {
                List<Object> out = new ArrayList<>();
                s.getValue().forEach(item -> out.add(value(item, problems, step)));
                return out;
            }
            case MappingNode m -> {
                duplicates(m, problems, step);
                Map<String, Object> out = new LinkedHashMap<>();
                for (NodeTuple t : m.getValue()) {
                    String k = key(t, problems, step);
                    if (k != null) {
                        out.putIfAbsent(k, value(t.getValueNode(), problems, step));
                    }
                }
                return out;
            }
            default -> {
                return null;
            }
        }
    }

    private static int markLine(MappingNode m, String key) {
        for (NodeTuple t : m.getValue()) {
            if (key.equals(keyText(t))) {
                return line(t.getKeyNode().getStartMark());
            }
        }
        return 0;
    }

    private static int line(Mark m) {
        return m == null ? 0 : m.getLine() + 1;
    }

    private static Problem at(Mark m, String step, String message) {
        return m == null ? new Problem(0, 0, step, message) : new Problem(m.getLine() + 1, m.getColumn() + 1, step,
            message);
    }

    private static String plain(String message) {
        if (message == null || message.isBlank()) {
            return "no reason given";
        }
        return message.strip();
    }

    private static void closeQuietly(Step step) {
        try {
            step.close();
        } catch (RuntimeException e) {
            // never started; nothing to report
        }
    }
}
