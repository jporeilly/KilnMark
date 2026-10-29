package io.kilnmark.kiln.yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A pipeline as written in its YAML ({@code kilnmark: pipeline/v1}, docs/design/pipelines.md
 * section 2), before its node types are looked up: names, nodes, the inputs each node names and
 * each node's settings, with where each was written. {@link #parse} reads one;
 * {@link PipelineCompiler} checks it against the node types and makes it a graph.
 *
 * @param name        the pipeline's name
 * @param description what it does, or null
 * @param nodes       the nodes in the order written
 * @param quality     the pipeline's quality settings ({@code quality:}), or null when it has none
 * @param parameters  what it takes ({@code parameters:}), each with a default, in the order written
 */
public record PipelineDefinition(String name, String description, List<NodeDefinition> nodes, Quality quality,
                                 List<Parameters.Parameter> parameters) {

    /** The format this version reads. */
    public static final String FORMAT = "pipeline/v1";

    static final Set<String> TOP_LEVEL_KEYS = Set.of("kilnmark", "name", "description", "parameters", "quality",
        "nodes", "layout");
    static final Set<String> QUALITY_KEYS = Set.of("min_score");

    /**
     * The top-level key holding where the canvas draws each node ({@code layout: { rows: { x: 40, y: 120 } }}).
     * Reading accepts it and ignores what it holds: it never changes what a pipeline does, so a pipeline
     * runs the same however its nodes are drawn.
     */
    public static final String LAYOUT = "layout";
    static final Set<String> NODE_KEYS = Set.of("id", "type", "input", "inputs");

    public PipelineDefinition {
        nodes = List.copyOf(nodes);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** A pipeline without quality settings. */
    public PipelineDefinition(String name, String description, List<NodeDefinition> nodes) {
        this(name, description, nodes, null);
    }

    /** A pipeline without parameters. */
    public PipelineDefinition(String name, String description, List<NodeDefinition> nodes, Quality quality) {
        this(name, description, nodes, quality, List.of());
    }

    /**
     * The pipeline's quality settings, from its top-level {@code quality:} mapping.
     *
     * @param minScore the lowest measured quality score (0 to 1) an output of a successful run may
     *                 have before Kilnmark tells the pipeline's followers ({@code pipeline_low_quality});
     *                 null when not set
     */
    public record Quality(Double minScore) {
    }

    /**
     * One node as written.
     *
     * @param id       its id, unique in the pipeline
     * @param type     its node type, or null if none was given
     * @param inputs   the inputs it names, in the order written
     * @param config     its settings: every key except {@code id}, {@code type}, {@code input} and
     *                   {@code inputs}, as maps, lists, strings, numbers (whole numbers as Integer, Long
     *                   or BigInteger, decimals as BigDecimal), booleans and nulls
     * @param position   where the node starts
     * @param keys       where each of the node's keys is written
     * @param configText the same settings with every scalar as the text written ({@code 007},
     *                   {@code 2024.10}), so a setting that is text keeps exactly what was typed
     */
    public record NodeDefinition(String id, String type, List<InputRef> inputs, Map<String, Object> config,
                                 Position position, Map<String, Position> keys, Map<String, Object> configText) {

        public NodeDefinition {
            Objects.requireNonNull(id, "id");
            inputs = List.copyOf(inputs);
            config = Collections.unmodifiableMap(new LinkedHashMap<>(config));
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
            configText = configText == null ? textOf(config)
                : Collections.unmodifiableMap(new LinkedHashMap<>(configText));
        }

        /** A node built in code: the text of each setting is its value written out. */
        public NodeDefinition(String id, String type, List<InputRef> inputs, Map<String, Object> config,
                              Position position, Map<String, Position> keys) {
            this(id, type, inputs, config, position, keys, null);
        }

        @SuppressWarnings("unchecked")
        private static <T> T textOf(T value) {
            if (value instanceof Map<?, ?> m) {
                Map<Object, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(k, textOf(v)));
                return (T) Collections.unmodifiableMap(out);
            }
            if (value instanceof List<?> l) {
                List<Object> out = new ArrayList<>();
                l.forEach(v -> out.add(textOf(v)));
                return (T) Collections.unmodifiableList(out);
            }
            if (value instanceof java.math.BigDecimal d) {
                return (T) io.kilnmark.kiln.Decimals.text(d);
            }
            return value == null ? null : (T) String.valueOf(value);
        }

        /** Where {@code key} is written, or where the node starts if it is not. */
        public Position at(String key) {
            return keys.getOrDefault(key, position);
        }
    }

    /**
     * One input a node names.
     *
     * @param port     the node's own input port, or null for {@code input:}, meaning the node's
     *                 only input port
     * @param from     where the rows come from: {@code id} for that node's {@code out} port, or
     *                 {@code id.port}
     * @param position where it is written
     */
    public record InputRef(String port, String from, Position position) {

        public InputRef {
            Objects.requireNonNull(from, "from");
        }
    }

    /**
     * What reading a pipeline's YAML gave.
     *
     * @param definition the pipeline, or null when the YAML could not be read as one
     * @param problems   what is wrong with it, by position
     */
    public record Parsed(PipelineDefinition definition, List<Problem> problems) {

        public Parsed {
            problems = problems.stream().sorted(Problem.BY_POSITION).toList();
        }
    }

    /**
     * Reads a pipeline's YAML, checking what can be checked without knowing the node types: the
     * format line, the keys, node ids and the shape of {@code input} and {@code inputs}. Nodes
     * with a problem are kept where they can be, so later checks still find them.
     */
    public static Parsed parse(String yaml) {
        return parse(yaml, new HashSet<>());
    }

    /** As {@link #parse(String)}, also collecting the ids of nodes whose inputs could not be read. */
    static Parsed parse(String yaml, Set<String> inputProblems) {
        List<Problem> problems = new ArrayList<>();
        YamlReader.Node root;
        try {
            root = YamlReader.read(yaml == null ? "" : yaml);
        } catch (YamlReader.Failure f) {
            problems.add(Problem.at(f.position(), null, f.getMessage()));
            return new Parsed(null, problems);
        }
        if (!(root instanceof YamlReader.Mapping top)) {
            problems.add(Problem.at(root == null ? Position.START : root.position(), null,
                "A pipeline starts with kilnmark: " + FORMAT + ", followed by its name and nodes"));
            return new Parsed(null, problems);
        }
        YamlReader.Entry format = top.entries().get("kilnmark");
        if (format == null) {
            problems.add(Problem.at(top.position(), null,
                "The pipeline must start with kilnmark: " + FORMAT + ", naming its format"));
        } else if (!FORMAT.equals(format.value().value())) {
            problems.add(Problem.at(format.value().position(), null, "This pipeline is in the format '"
                + Suggest.clip(String.valueOf(format.value().text())) + "', but this version of Kilnmark reads " + FORMAT));
        }
        for (YamlReader.Entry e : top.entries().values()) {
            if (!TOP_LEVEL_KEYS.contains(e.key()) && !Renamed.isOld(e.key(), TOP_LEVEL_KEYS)) {
                problems.add(Problem.at(e.keyPosition(), null, "Unknown key '" + Suggest.clip(e.key()) + "'"
                    + Suggest.hint(e.key(), TOP_LEVEL_KEYS)
                    + ": a pipeline has kilnmark, name, description, parameters, quality, nodes and layout"));
            }
        }
        String name = text(top.entries().get("name"), "name", null, problems);
        if (name == null && !top.entries().containsKey("name")) {
            problems.add(Problem.at(top.position(), null, "The pipeline needs a name"));
        }
        String description = text(top.entries().get("description"), "description", null, problems);
        Quality quality = quality(top.entries().get("quality"), problems);
        List<Parameters.Parameter> parameters = Parameters.read(top.entries().get("parameters"), "pipeline", true,
            problems);

        List<NodeDefinition> nodes = new ArrayList<>();
        YamlReader.Entry nodesEntry = Renamed.entry(top, "nodes", "pipeline", problems);
        if (nodesEntry == null) {
            problems.add(Problem.at(top.position(), null, "The pipeline needs nodes"));
        } else if (!(nodesEntry.value() instanceof YamlReader.Sequence list)) {
            problems.add(Problem.at(nodesEntry.value().position(), null,
                "nodes must be a list, each node starting with - id: …"));
        } else if (list.items().isEmpty()) {
            problems.add(Problem.at(nodesEntry.value().position(), null, "The pipeline needs at least one node"));
        } else {
            Map<String, Position> ids = new HashMap<>();
            for (YamlReader.Node item : list.items()) {
                NodeDefinition s = node(item, ids, problems, inputProblems);
                if (s != null) {
                    nodes.add(s);
                }
            }
        }
        return new Parsed(new PipelineDefinition(name, description, nodes, quality, parameters), problems);
    }

    /** The {@code quality:} mapping: {@code min_score}, a share from 0 to 1. Null when absent or wrong. */
    private static Quality quality(YamlReader.Entry e, List<Problem> problems) {
        if (e == null) {
            return null;
        }
        if (!(e.value() instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(e.value().position(), null,
                "quality must be a mapping, such as quality: { min_score: 0.9 }"));
            return null;
        }
        for (YamlReader.Entry k : m.entries().values()) {
            if (!QUALITY_KEYS.contains(k.key())) {
                problems.add(Problem.at(k.keyPosition(), null, "quality has no setting '" + Suggest.clip(k.key()) + "'"
                    + Suggest.hint(k.key(), QUALITY_KEYS) + ": it takes min_score"));
            }
        }
        YamlReader.Entry min = m.entries().get("min_score");
        if (min == null) {
            return new Quality(null);
        }
        Object v = min.value().value();
        Double score = v instanceof Number n && !(v instanceof java.math.BigInteger) ? n.doubleValue() : null;
        if (score == null || score.isNaN() || score < 0 || score > 1) {
            problems.add(Problem.at(min.value().position(), null, "quality.min_score must be a share from 0 to 1, "
                + "such as 0.9 for 90%"));
            return new Quality(null);
        }
        return new Quality(score);
    }

    private static NodeDefinition node(YamlReader.Node item, Map<String, Position> ids, List<Problem> problems,
                                       Set<String> inputProblems) {
        if (!(item instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(item.position(), null, "Each node is a mapping starting with - id: …"));
            return null;
        }
        YamlReader.Entry idEntry = m.entries().get("id");
        if (idEntry == null) {
            problems.add(Problem.at(m.position(), null, "Each node needs an id"));
            return null;
        }
        if (!(idEntry.value() instanceof YamlReader.Scalar sc) || !(sc.value() instanceof String id)) {
            problems.add(Problem.at(idEntry.value().position(), null,
                "A node id is a word such as tidy-customers, not a number, list or mapping"));
            return null;
        }
        if (!PipelineCompiler.NODE_ID.matcher(id).matches()) {
            problems.add(Problem.at(idEntry.value().position(), Suggest.clip(id), PipelineCompiler.badId(id)));
            if (id.length() > Suggest.MAX_ECHO) {
                return null; // too long to name in later messages
            }
        }
        Position first = ids.putIfAbsent(id, idEntry.value().position());
        if (first != null) {
            problems.add(Problem.at(idEntry.value().position(), id,
                "Node id '" + id + "' is used twice (first on line " + first.line() + ")"));
            return null;
        }
        String type = text(m.entries().get("type"), "type", id, problems);
        if (!m.entries().containsKey("type")) {
            problems.add(Problem.at(m.position(), id, "Node " + id + " needs a type"));
        }

        Map<String, Position> keys = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> configText = new LinkedHashMap<>();
        for (YamlReader.Entry e : m.entries().values()) {
            keys.put(e.key(), e.keyPosition());
            if (!NODE_KEYS.contains(e.key())) {
                config.put(e.key(), e.value().value());
                configText.put(e.key(), e.value().text());
            }
        }
        List<InputRef> inputs = new ArrayList<>();
        YamlReader.Entry input = m.entries().get("input");
        YamlReader.Entry inputsEntry = m.entries().get("inputs");
        if (input != null && inputsEntry != null) {
            problems.add(Problem.at(inputsEntry.keyPosition(), id,
                "Node " + id + " has both input and inputs: use input for one, inputs to name ports"));
        }
        int before = problems.size();
        if (input != null) {
            refs(input.value(), null, id, inputs, problems);
        }
        if (inputsEntry != null && input == null) {
            if (inputsEntry.value() instanceof YamlReader.Mapping ports) {
                for (YamlReader.Entry p : ports.entries().values()) {
                    refs(p.value(), p.key(), id, inputs, problems);
                }
            } else {
                problems.add(Problem.at(inputsEntry.value().position(), id,
                    "inputs names each input port and the node feeding it, such as inputs: { main: spend, lookup: tidy }"));
            }
        }
        if (problems.size() != before) {
            inputProblems.add(id);
        }
        return new NodeDefinition(id, type, inputs, config, m.position(), keys, configText);
    }

    /** {@code node}, {@code node.port}, or a list of them. */
    private static void refs(YamlReader.Node value, String port, String id, List<InputRef> out, List<Problem> problems) {
        List<YamlReader.Node> items = value instanceof YamlReader.Sequence s ? s.items() : List.of(value);
        if (items.isEmpty()) {
            problems.add(Problem.at(value.position(), id, "Node " + id + " names no node to read from"));
        }
        for (YamlReader.Node n : items) {
            Object v = n.value();
            if (v instanceof String from && !from.isBlank()) {
                out.add(new InputRef(port, from.strip(), n.position()));
            } else {
                problems.add(Problem.at(n.position(), id,
                    "An input is the id of the node to read from, or id.port for another of its outputs"));
            }
        }
    }

    private static String text(YamlReader.Entry e, String key, String nodeId, List<Problem> problems) {
        if (e == null) {
            return null;
        }
        if (e.value() instanceof YamlReader.Scalar sc && sc.value() != null && !sc.written().isBlank()) {
            return sc.written(); // name: 2024 is the name "2024"
        }
        problems.add(Problem.at(e.value().position(), nodeId, key + " must be text"));
        return null;
    }
}
