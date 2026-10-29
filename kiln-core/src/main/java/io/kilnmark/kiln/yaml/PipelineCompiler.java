package io.kilnmark.kiln.yaml;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Decimals;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.yaml.PipelineDefinition.InputRef;
import io.kilnmark.kiln.yaml.PipelineDefinition.NodeDefinition;

/**
 * Checks a pipeline against the node types in a {@link NodeFactories} and works out the columns
 * leaving each node (docs/design/pipelines.md). Checking never creates a node: it calls only each
 * factory's ports, settings, {@code check} and {@code outputSchema}. {@link Compiled#graph()} then
 * creates the nodes for a run.
 *
 * <p>Checks, each reported with its line and column: the format line; unknown top-level keys; node
 * ids; unknown node types, suggesting the closest; each setting against the node type's settings
 * (required, kind, choices, unknown keys) and the factory's own {@code check}; inputs naming
 * unknown nodes or ports; required inputs not connected; a single-input port fed twice; loops; and
 * then, in the order rows flow, each node's columns from its factory's {@code outputSchema}. When a
 * node's columns cannot be known before the run, the nodes after it are not checked against
 * columns, and a note says so.
 */
public final class PipelineCompiler {

    /** Most digits a number setting may have, and furthest its decimal point may sit from them. */
    static final int MAX_DIGITS = 1000;

    /** A node id: a lower-case letter, then up to 63 lower-case letters, digits, dashes and underscores. */
    public static final Pattern NODE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private final NodeFactories factories;

    public PipelineCompiler(NodeFactories factories) {
        this.factories = Objects.requireNonNull(factories, "factories");
    }

    /** Reads and checks a pipeline's YAML. */
    public Compiled compile(String yaml) {
        Set<String> inputProblems = new HashSet<>();
        PipelineDefinition.Parsed parsed = PipelineDefinition.parse(yaml, inputProblems);
        if (parsed.definition() == null) {
            return new Compiled(null, parsed.problems(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        }
        return new Check(parsed.definition(), parsed.problems(), inputProblems, false).run();
    }

    /**
     * Checks a pipeline built in code, including what reading YAML checks: the name, node ids
     * (pattern and uniqueness) and that each node has a type.
     */
    public Compiled compile(PipelineDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return new Check(definition, List.of(), Set.of(), true).run();
    }

    /** The problem with a node id that does not match {@link #NODE_ID}. */
    static String badId(String id) {
        return "Node id '" + Suggest.clip(id)
            + "' must start with a lower-case letter and use only a-z, 0-9, - and _, up to 64 characters";
    }

    /** A port name as edges use it: a many-input port {@code in*} is {@code in}. */
    static String portName(String declared) {
        return declared.endsWith("*") ? declared.substring(0, declared.length() - 1) : declared;
    }

    /** One run of the checks over one pipeline. */
    private final class Check {
        private final PipelineDefinition definition;
        private final List<Problem> problems;
        private final List<Problem> notes = new ArrayList<>();
        private final Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        private final Map<String, NodeFactory> factoryOf = new LinkedHashMap<>();
        /** Nodes whose settings or inputs have a problem: their columns are not worked out. */
        private final Set<String> broken = new HashSet<>();
        /** Nodes with a problem in the inputs they name, so a missing input is not reported as well. */
        private final Set<String> badInputs = new HashSet<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<Edge, InputRef> refOf = new HashMap<>();
        /** The nodes checked: those written, less any whose id is used twice. */
        private final List<NodeDefinition> list = new ArrayList<>();
        /** Each node's settings as its factory and node receive them. */
        private final Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        /** The columns arriving on each node's input ports, where known. */
        private final Map<String, Map<String, Schema>> inputSchemas = new LinkedHashMap<>();

        Check(PipelineDefinition definition, List<Problem> parseProblems, Set<String> inputProblems,
              boolean checkDefinition) {
            this.definition = definition;
            this.problems = new ArrayList<>(parseProblems);
            badInputs.addAll(inputProblems); // already reported while reading: not also "needs an input"
            if (checkDefinition && (definition.name() == null || definition.name().isBlank())) {
                problems.add(Problem.at(Position.START, null, "The pipeline needs a name"));
            }
            if (checkDefinition && definition.nodes().isEmpty()) {
                problems.add(Problem.at(Position.START, null, "The pipeline needs at least one node"));
            }
            for (NodeDefinition s : definition.nodes()) {
                if (checkDefinition) {
                    if (!NODE_ID.matcher(s.id()).matches()) {
                        problems.add(Problem.at(s.position(), Suggest.clip(s.id()), badId(s.id())));
                    }
                    if (nodes.containsKey(s.id())) {
                        problems.add(Problem.at(s.position(), Suggest.clip(s.id()), "Node id '" + Suggest.clip(s.id())
                            + "' is used twice (first on line " + nodes.get(s.id()).position().line() + ")"));
                        continue;
                    }
                    if (s.type() == null) {
                        problems.add(Problem.at(s.position(), s.id(), "Node " + Suggest.clip(s.id()) + " needs a type"));
                    }
                }
                if (nodes.putIfAbsent(s.id(), s) == null) {
                    list.add(s);
                }
            }
        }

        Compiled run() {
            for (NodeDefinition s : list) {
                if (s.type() == null) {
                    broken.add(s.id());
                    continue;
                }
                NodeFactory f = factories.get(s.type()).orElse(null);
                if (f == null) {
                    broken.add(s.id());
                    problems.add(Problem.at(s.at("type"), s.id(), unknownType(s.type())));
                    continue;
                }
                factoryOf.put(s.id(), f);
                settings(s, f);
            }
            for (NodeDefinition s : list) {
                inputs(s);
            }
            for (NodeDefinition s : list) {
                connected(s);
            }
            List<String> order = order();
            Map<String, Map<String, Schema>> schemas = order == null ? Map.of() : schemas(order);
            if (order != null) {
                sharedUpstream();
            }
            return new Compiled(definition, problems, notes, schemas, factoryOf, edges, configs, inputSchemas);
        }

        private String unknownType(String type) {
            Set<String> types = new TreeSet<>();
            factories.all().forEach(f -> types.add(f.type()));
            String closest = Suggest.closest(type, types);
            if (closest != null) {
                return "Unknown node type '" + Suggest.clip(type) + "' (did you mean '" + closest + "'?)";
            }
            return "Unknown node type '" + Suggest.clip(type) + "'. The node types are: " + String.join(", ", types);
        }

        /** A node's settings as its factory receives them (as written, if they could not be checked). */
        private Map<String, Object> config(NodeDefinition s) {
            return configs.getOrDefault(s.id(), s.config());
        }

        // ---- settings ----

        private void settings(NodeDefinition s, NodeFactory f) {
            int before = problems.size();
            Map<String, SettingSpec> specs = new LinkedHashMap<>();
            f.settings().forEach(spec -> specs.put(spec.key(), spec));
            for (String key : s.config().keySet()) {
                if (!specs.containsKey(key)) {
                    String hint = Suggest.hint(key, specs.keySet());
                    String known = specs.isEmpty() ? "it takes no settings"
                        : "its settings are " + String.join(", ", specs.keySet());
                    problems.add(Problem.at(s.at(key), s.id(), "Node " + s.id() + " (" + s.type()
                        + ") has no setting '" + Suggest.clip(key) + "'" + hint + ": " + known));
                }
            }
            Map<String, Object> config = new LinkedHashMap<>(s.config());
            for (SettingSpec spec : specs.values()) {
                Object value = s.config().get(spec.key());
                if (value == null) {
                    if (spec.required()) {
                        problems.add(Problem.at(s.position(), s.id(), "Node " + s.id() + " (" + s.type()
                            + ") needs the setting '" + spec.key() + "' (" + spec.label() + ")"));
                    }
                    continue;
                }
                Checked c = kind(spec, value, s.configText().get(spec.key()));
                if (c.wrong() != null) {
                    problems.add(Problem.at(s.at(spec.key()), s.id(), "Setting '" + spec.key() + "' of node "
                        + s.id() + " " + c.wrong()));
                } else {
                    config.put(spec.key(), c.value());
                }
            }
            configs.put(s.id(), Collections.unmodifiableMap(config));
            if (problems.size() == before) {
                try {
                    for (String message : f.check(config)) {
                        problems.add(Problem.at(s.at(firstWord(message, s.config().keySet())), s.id(),
                            "Node " + s.id() + ": " + message));
                    }
                } catch (RuntimeException e) {
                    problems.add(Problem.at(s.position(), s.id(), "Node " + s.id()
                        + ": its settings could not be checked: " + message(e)));
                }
            }
            if (problems.size() != before) {
                broken.add(s.id());
            }
        }

        /** A setting's value as the node receives it, or what is wrong with it. */
        private record Checked(Object value, String wrong) {
            static Checked ok(Object value) {
                return new Checked(value, null);
            }

            static Checked wrong(String wrong) {
                return new Checked(null, wrong);
            }
        }

        /**
         * Checks a value against its kind of setting and gives it the form the node receives: a
         * setting that is text keeps the text as written (007, 2024.10), a number is a whole number
         * or a BigDecimal, never infinite or not-a-number.
         */
        private static Checked kind(SettingSpec spec, Object v, Object text) {
            boolean scalar = v instanceof String || v instanceof Number || v instanceof Boolean;
            boolean name = v instanceof String || v instanceof Number;
            return switch (spec.kind()) {
                case TEXT, URL, EXPRESSION -> scalar ? Checked.ok(written(v, text))
                    : Checked.wrong("must be a single value, not a list or mapping");
                case COLUMN, SOURCE, ASSET -> name ? Checked.ok(written(v, text))
                    : Checked.wrong("must be a name, such as customer_id");
                case CHOICE -> scalar && spec.choices().contains(written(v, text)) ? Checked.ok(written(v, text))
                    : Checked.wrong("must be one of " + String.join(", ", spec.choices()));
                case NUMBER -> number(v);
                case BOOLEAN -> v instanceof Boolean ? Checked.ok(v) : Checked.wrong("must be true or false");
                case COLUMNS -> {
                    if (!(v instanceof List<?> l) || !l.stream().allMatch(i -> i instanceof String || i instanceof Number)) {
                        yield Checked.wrong("must be a list of columns, such as [customer_id, email]");
                    }
                    List<?> t = text instanceof List<?> tl && tl.size() == l.size() ? tl : l;
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < l.size(); i++) {
                        out.add(written(l.get(i), t.get(i)));
                    }
                    yield Checked.ok(Collections.unmodifiableList(out));
                }
                case RULES -> v instanceof List<?> l && l.stream().allMatch(i -> i instanceof Map<?, ?>)
                    ? Checked.ok(v)
                    : Checked.wrong("must be a list of rules, each a mapping such as { type: not_null, field: email }");
                case EXPRESSIONS, CONDITIONS, COLUMN_MAP -> {
                    boolean columns = spec.kind() == SettingSpec.Kind.COLUMN_MAP;
                    if (!(v instanceof Map<?, ?> m) || !m.values().stream().allMatch(i -> i instanceof String
                        || i instanceof Number || !columns && i instanceof Boolean)) {
                        yield Checked.wrong(columns
                            ? "must be a mapping of column names to column names, such as { id: customer_id }"
                            : "must be a mapping of names to expressions, such as { total: \"sum(amount)\" }");
                    }
                    Map<?, ?> t = text instanceof Map<?, ?> tm ? tm : m;
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, x) -> out.put(String.valueOf(k), written(x, t.get(k))));
                    yield Checked.ok(Collections.unmodifiableMap(out));
                }
            };
        }

        /** A scalar as written. */
        private static String written(Object value, Object text) {
            if (text instanceof String t) {
                return t;
            }
            return value instanceof BigDecimal d ? Decimals.text(d) : String.valueOf(value);
        }

        private static Checked number(Object v) {
            if (v instanceof Integer || v instanceof Long) {
                return Checked.ok(v);
            }
            if (v instanceof BigInteger) {
                return Checked.wrong("is too large: whole numbers go up to " + Long.MAX_VALUE);
            }
            if (v instanceof BigDecimal d) {
                if (d.precision() > MAX_DIGITS || Math.abs((long) d.scale()) > MAX_DIGITS) {
                    return Checked.wrong("has too many digits: at most " + MAX_DIGITS
                        + ", with the decimal point no more than " + MAX_DIGITS + " places from them");
                }
                return d.abs().compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) > 0
                    ? Checked.wrong("is too large") : Checked.ok(d);
            }
            if (v instanceof Double d) {
                return d.isNaN() || d.isInfinite() ? Checked.wrong("must be a finite number") : Checked.ok(BigDecimal.valueOf(d));
            }
            if (v instanceof Number n) {
                return number(new BigDecimal(n.toString()));
            }
            return Checked.wrong("must be a number");
        }

        /** The setting a factory's message starts with, so the problem points at it. */
        private static String firstWord(String message, Collection<String> keys) {
            String m = message.strip();
            for (String k : keys) {
                if (m.startsWith(k) && (m.length() == k.length() || !Character.isLetterOrDigit(m.charAt(k.length()))
                    && m.charAt(k.length()) != '_')) {
                    return k;
                }
            }
            return "";
        }

        // ---- inputs ----

        private void inputs(NodeDefinition s) {
            NodeFactory f = factoryOf.get(s.id());
            for (InputRef ref : s.inputs()) {
                PortRef from = PortRef.parse(ref.from(), "out");
                NodeDefinition source = nodes.get(from.node());
                if (source == null) {
                    problems.add(Problem.at(ref.position(), s.id(), "Node " + s.id() + " reads from '"
                        + Suggest.clip(from.node()) + "', which is not a node in this pipeline" + Suggest.hint(from.node(), nodes.keySet())));
                    bad(s.id());
                    continue;
                }
                if (source.id().equals(s.id())) {
                    problems.add(Problem.at(ref.position(), s.id(), "Node " + s.id() + " reads from itself"));
                    bad(s.id());
                    continue;
                }
                if (!outputExists(s, ref, source, from.port())) {
                    continue;
                }
                if (f == null) {
                    continue; // its type is unknown, so its ports are too
                }
                String port = inputPort(s, f, ref);
                if (port == null) {
                    bad(s.id());
                    continue;
                }
                Edge e = new Edge(from, new PortRef(s.id(), port));
                if (refOf.containsKey(e)) {
                    problems.add(Problem.at(ref.position(), s.id(), "Node " + s.id() + " reads from "
                        + Suggest.clip(ref.from()) + " twice on its input '" + port + "'"));
                    bad(s.id());
                    continue;
                }
                edges.add(e);
                refOf.put(e, ref);
            }
        }

        private void bad(String id) {
            broken.add(id);
            badInputs.add(id);
        }

        private boolean outputExists(NodeDefinition s, InputRef ref, NodeDefinition source, String port) {
            NodeFactory sf = factoryOf.get(source.id());
            if (sf == null) {
                return true; // reported against that node
            }
            List<String> outs;
            try {
                outs = sf.outputPorts(config(source));
            } catch (RuntimeException e) {
                return true; // its settings are reported against it
            }
            if (outs.contains(port)) {
                return true;
            }
            bad(s.id());
            if (outs.isEmpty()) {
                problems.add(Problem.at(ref.position(), s.id(), "Node " + source.id() + " (" + source.type()
                    + ") has no outputs, so node " + s.id() + " cannot read from it"));
            } else {
                problems.add(Problem.at(ref.position(), s.id(), "Node " + source.id() + " (" + source.type()
                    + ") has no output '" + Suggest.clip(port) + "'" + Suggest.hint(port, outs) + ": its outputs are "
                    + String.join(", ", outs)));
            }
            return false;
        }

        /** The input port a reference connects to, or null after reporting why there is none. */
        private String inputPort(NodeDefinition s, NodeFactory f, InputRef ref) {
            List<String> declared = f.inputPorts().stream().map(PipelineCompiler::portName).toList();
            String what = "Node " + s.id() + " (" + s.type() + ")";
            if (declared.isEmpty()) {
                problems.add(Problem.at(ref.position(), s.id(), what + " takes no input"));
                return null;
            }
            if (ref.port() == null) {
                if (declared.size() > 1) {
                    problems.add(Problem.at(ref.position(), s.id(), what + " has inputs " + String.join(" and ", declared)
                        + ": connect each with inputs: { " + declared.getFirst() + ": …, " + declared.get(1) + ": … }"));
                    return null;
                }
                return declared.getFirst();
            }
            String port = portName(ref.port());
            if (!declared.contains(port)) {
                problems.add(Problem.at(ref.position(), s.id(), what + " has no input '" + Suggest.clip(port) + "'"
                    + Suggest.hint(port, declared) + ": its inputs are " + String.join(", ", declared)));
                return null;
            }
            return port;
        }

        /** Every input connected, and a single-input port fed once. */
        private void connected(NodeDefinition s) {
            NodeFactory f = factoryOf.get(s.id());
            if (f == null) {
                return;
            }
            for (String declared : f.inputPorts()) {
                String port = portName(declared);
                boolean many = declared.endsWith("*");
                List<Edge> into = into(s.id(), port);
                if (into.isEmpty() && !badInputs.contains(s.id())) {
                    String add = f.inputPorts().size() == 1 ? "add input: <node id>" : "add it under inputs";
                    problems.add(Problem.at(s.position(), s.id(), "Node " + s.id() + " (" + s.type()
                        + ") needs an input on port '" + port + "': " + add));
                    broken.add(s.id());
                } else if (!many && into.size() > 1) {
                    problems.add(Problem.at(refOf.get(into.get(1)).position(), s.id(), "Node " + s.id()
                        + "'s input '" + port + "' takes one input, but " + into.size()
                        + " are connected: combine them with a union node first"));
                    broken.add(s.id());
                }
            }
        }

        private List<Edge> into(String node, String port) {
            return edges.stream().filter(e -> e.to().node().equals(node) && e.to().port().equals(port)).toList();
        }

        // ---- order and columns ----

        /** Node ids in the order rows flow; null, after reporting the loop, if there is one. */
        private List<String> order() {
            Map<String, Integer> waiting = new LinkedHashMap<>();
            nodes.keySet().forEach(id -> waiting.put(id, 0));
            for (Edge e : edges) {
                waiting.merge(e.to().node(), 1, Integer::sum);
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
                for (Edge e : edges) {
                    if (e.from().node().equals(id) && waiting.merge(e.to().node(), -1, Integer::sum) == 0) {
                        ready.add(e.to().node());
                    }
                }
            }
            if (out.size() == nodes.size()) {
                return out;
            }
            List<String> loop = loop(new HashSet<>(out));
            NodeDefinition first = nodes.get(loop.getFirst());
            problems.add(Problem.at(first.position(), first.id(), "Nodes " + String.join(" → ", loop) + " → "
                + loop.getFirst() + " form a loop: rows in a pipeline must flow one way"));
            return null;
        }

        /**
         * One loop among the nodes not in {@code ordered}, in the direction rows flow. Each of those
         * nodes is fed by another of them, so walking back along inputs must come round.
         */
        private List<String> loop(Set<String> ordered) {
            String start = nodes.keySet().stream().filter(id -> !ordered.contains(id)).findFirst().orElseThrow();
            List<String> path = new ArrayList<>();
            Set<String> onPath = new LinkedHashSet<>();
            String at = start;
            while (onPath.add(at)) {
                path.add(at);
                String current = at;
                at = edges.stream().filter(e -> e.to().node().equals(current) && !ordered.contains(e.from().node()))
                    .map(e -> e.from().node()).findFirst().orElseThrow();
            }
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(at), path.size()));
            Collections.reverse(loop);
            List<String> written = new ArrayList<>(nodes.keySet()); // start from the node written first
            String first = loop.stream().min(Comparator.comparingInt(written::indexOf)).orElseThrow();
            Collections.rotate(loop, -loop.indexOf(first));
            return loop;
        }

        private Map<String, Map<String, Schema>> schemas(List<String> order) {
            Map<String, Map<String, Schema>> known = new LinkedHashMap<>();
            Map<String, String> unknownBecause = new HashMap<>(); // node → the node whose columns are unknown
            Map<String, List<String>> skipped = new LinkedHashMap<>();
            for (String id : order) {
                NodeDefinition s = nodes.get(id);
                NodeFactory f = factoryOf.get(id);
                if (f == null || broken.contains(id)) {
                    continue;
                }
                Map<String, Schema> inputs = new LinkedHashMap<>();
                String origin = null;
                boolean silent = false;
                for (String declared : f.inputPorts()) {
                    String port = portName(declared);
                    List<Edge> into = into(id, port);
                    for (int k = 0; k < into.size(); k++) {
                        PortRef from = into.get(k).from();
                        Schema schema = known.getOrDefault(from.node(), Map.of()).get(from.port());
                        if (schema != null) {
                            inputs.put(declared.endsWith("*") ? port + "[" + k + "]" : port, schema);
                        } else if (unknownBecause.containsKey(from.node())) {
                            origin = origin == null ? unknownBecause.get(from.node()) : origin;
                        } else {
                            silent = true; // that node has a problem of its own
                        }
                    }
                }
                inputSchemas.put(id, inputs);
                if (silent || origin != null) {
                    if (!silent) {
                        unknownBecause.put(id, origin);
                        skipped.computeIfAbsent(origin, k -> new ArrayList<>()).add(id);
                    }
                    continue;
                }
                List<String> outs;
                try {
                    outs = f.outputPorts(config(s));
                } catch (RuntimeException e) {
                    problems.add(Problem.at(s.position(), id, "Node " + id + ": its outputs could not be worked out: "
                        + message(e)));
                    continue;
                }
                Map<String, Schema> mine = new LinkedHashMap<>();
                for (String port : outs) {
                    Schema schema;
                    try {
                        schema = f.outputSchema(port, inputs, config(s));
                    } catch (NodeException e) {
                        problems.add(Problem.at(s.position(), id, "Node " + id + ": " + message(e)));
                        mine = null;
                        break;
                    } catch (RuntimeException e) {
                        problems.add(Problem.at(s.position(), id, "Node " + id + ": its columns could not be worked out: "
                            + message(e)));
                        mine = null;
                        break;
                    }
                    if (schema != null) {
                        mine.put(port, schema);
                    } else {
                        unknownBecause.put(id, id);
                    }
                }
                if (mine == null) {
                    unknownBecause.remove(id);
                    broken.add(id);
                } else {
                    known.put(id, mine);
                }
            }
            skipped.forEach((origin, after) -> notes.add(Problem.at(nodes.get(origin).position(), origin,
                "The columns leaving node " + origin + " cannot be known before the run, so "
                    + (after.size() == 1 ? "node " + after.getFirst() + " is" : "nodes " + String.join(", ", after) + " are")
                    + " not checked against columns until then")));
            return known;
        }

        /**
         * A note for a node with several input ports fed, through any number of nodes, from the
         * same node: if it reads one input to its end before another (a lookup reads its lookup
         * side first), the shared node waits for room on the input not being read, and the run
         * stalls once the data is larger than the edges hold.
         */
        private void sharedUpstream() {
            Map<String, Set<String>> above = new HashMap<>();
            for (NodeDefinition s : list) {
                Map<String, Set<String>> byPort = new LinkedHashMap<>(); // a port taking many: one entry per input
                Map<String, Integer> seen = new HashMap<>();
                for (Edge e : edges) {
                    if (e.to().node().equals(s.id())) {
                        int k = seen.merge(e.to().port(), 1, Integer::sum) - 1;
                        boolean many = into(s.id(), e.to().port()).size() > 1;
                        String key = many ? e.to().port() + "[" + k + "]" : e.to().port();
                        Set<String> a = byPort.computeIfAbsent(key, x -> new HashSet<>());
                        a.add(e.from().node());
                        a.addAll(ancestors(e.from().node(), above));
                    }
                }
                if (byPort.size() < 2) {
                    continue;
                }
                List<String> ports = new ArrayList<>(byPort.keySet());
                Set<String> common = new TreeSet<>(byPort.get(ports.get(0)));
                for (int i = 1; i < ports.size(); i++) {
                    common.retainAll(byPort.get(ports.get(i)));
                }
                if (!common.isEmpty()) {
                    notes.add(Problem.at(s.position(), s.id(), "Node " + s.id() + "'s inputs " + String.join(" and ", ports)
                        + " both come from node " + common.iterator().next() + ". If " + s.id()
                        + " reads one input to its end before the other, the run can stall once more rows wait than"
                        + " the edges hold; reading the source twice avoids that"));
                }
            }
        }

        private Set<String> ancestors(String id, Map<String, Set<String>> memo) {
            Set<String> done = memo.get(id);
            if (done != null) {
                return done;
            }
            Set<String> out = new HashSet<>();
            ArrayDeque<String> todo = new ArrayDeque<>(List.of(id));
            while (!todo.isEmpty()) {
                String at = todo.poll();
                for (Edge e : edges) {
                    if (e.to().node().equals(at) && out.add(e.from().node())) {
                        todo.add(e.from().node());
                    }
                }
            }
            memo.put(id, out);
            return out;
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
