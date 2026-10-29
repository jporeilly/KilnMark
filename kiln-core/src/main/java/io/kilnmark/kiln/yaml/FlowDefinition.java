package io.kilnmark.kiln.yaml;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.kilnmark.kiln.flow.FlowEngine;

/**
 * A flow as written in its YAML ({@code kilnmark: flow/v1}, docs/design/flows.md): flow nodes
 * joined by connections, and the parameters it takes. {@link #parse} reads one and checks everything
 * that can be checked without the server: keys, ids, node settings, connections, loops, and every
 * condition against the values it can read. Whether the pipelines a flow runs exist is for the
 * server to check.
 *
 * @param name         the flow's name
 * @param description  what it does, or null
 * @param parameters   what it takes, in the order written
 * @param nodes        the nodes in the order written
 * @param connections  the connections in the order written
 * @param queueTimeout how long a node waits for a pipeline that is already running before it fails
 */
public record FlowDefinition(String name, String description, List<Parameters.Parameter> parameters, List<Node> nodes,
                             List<Connection> connections, Duration queueTimeout) {

    /** The format this version reads. */
    public static final String FORMAT = "flow/v1";
    /** Flow names, as pipeline names. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    /** The node type that runs a pipeline. */
    public static final String RUN_PIPELINE = "run-pipeline";
    /** The node type that ends the flow. */
    public static final String STOP = "stop";
    /** The node type that sends a notification. */
    public static final String NOTIFY = "notify";
    /** The node type that waits a while. */
    public static final String WAIT = "wait";
    /** The node types this version knows. */
    public static final List<String> TYPES = List.of(RUN_PIPELINE, STOP, NOTIFY, WAIT);
    /** How much a notify node's message matters, least first. */
    public static final List<String> SEVERITIES = List.of("info", "low", "medium", "high");
    /** Longest wait. */
    public static final Duration MAX_WAIT = Duration.ofHours(24);
    static final int MAX_TITLE = 200;
    static final int MAX_MESSAGE = 2_000;
    /** What a node that runs a pipeline reports, for conditions, besides {@code status}. */
    public static final Map<String, FlowCondition.Type> PIPELINE_VALUES = Map.of(
        "score", FlowCondition.Type.NUMBER,
        "quarantined_share", FlowCondition.Type.NUMBER,
        "rows_written", FlowCondition.Type.NUMBER,
        "duration_s", FlowCondition.Type.NUMBER);

    /** The default of {@code queue_timeout}. */
    public static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration MAX_QUEUE_TIMEOUT = Duration.ofHours(24);
    static final int MAX_NODES = 200;
    static final int MAX_CONNECTIONS = 1_000;

    static final Set<String> TOP_LEVEL_KEYS = Set.of("kilnmark", "name", "description", "parameters", "queue_timeout",
        "nodes", "connections", "layout");
    static final Set<String> NODE_KEYS = Set.of("id", "type");
    static final Map<String, Set<String>> TYPE_KEYS = Map.of(
        RUN_PIPELINE, Set.of("pipeline", "version", "with"),
        STOP, Set.of("status", "message"),
        NOTIFY, Set.of("severity", "title", "message"),
        WAIT, Set.of("for"));
    static final Set<String> CONNECTION_KEYS = Set.of("from", "to", "when");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,6})\\s*(s|m|h)");

    /**
     * One flow node. {@code pipeline}, {@code version} (null for the latest) and {@code with} (the
     * values given to the pipeline's parameters, as written: text, which may name ${...}, a number or
     * true/false) are set for {@link #RUN_PIPELINE}; {@code status} ({@code succeeded} or
     * {@code failed}) and {@code message} for {@link #STOP}; {@code severity}, {@code title} and
     * {@code message} for {@link #NOTIFY}; {@code waitFor} for {@link #WAIT}.
     */
    public record Node(String id, String type, String pipeline, Integer version, String status, String message,
                       Position position, Map<String, Position> keys, Map<String, Object> with, String severity,
                       String title, Duration waitFor) {

        public Node {
            with = with == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(with));
        }

        /** A node of no type, or before its settings are read. */
        static Node bare(String id, String type, Position position, Map<String, Position> keys) {
            return new Node(id, type, null, null, null, null, position, keys, Map.of(), null, null, null);
        }

        /** Where {@code key} is written, or where the node starts if it is not. */
        public Position at(String key) {
            return keys.getOrDefault(key, position);
        }
    }

    /**
     * One connection. {@code when} is {@code success}, {@code failure}, {@code always} or a condition;
     * {@code condition} is set only in the last case.
     */
    public record Connection(String from, String to, FlowEngine.Kind kind, String when, FlowCondition condition,
                       Position position) {
    }

    /** What reading a flow's YAML gave: the flow (null when it could not be read as one) and the problems. */
    public record Parsed(FlowDefinition definition, List<Problem> problems) {
        public Parsed {
            problems = problems.stream().sorted(Problem.BY_POSITION).toList();
        }

        public boolean valid() {
            return definition != null && problems.isEmpty();
        }
    }

    public FlowDefinition {
        parameters = List.copyOf(parameters);
        nodes = List.copyOf(nodes);
        connections = List.copyOf(connections);
        queueTimeout = queueTimeout == null ? DEFAULT_QUEUE_TIMEOUT : queueTimeout;
    }

    /** The connection conditions as the engine reads them. */
    public List<FlowEngine.Connection> engineConnections() {
        List<FlowEngine.Connection> out = new ArrayList<>();
        for (Connection p : connections) {
            FlowCondition c = p.condition();
            out.add(new FlowEngine.Connection(p.from(), p.to(), p.kind(), c == null ? null : c::evaluate));
        }
        return out;
    }

    /** The node with this id, or null. */
    public Node node(String id) {
        for (Node s : nodes) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The parameter with this name, or null. */
    public Parameters.Parameter parameter(String name) {
        for (Parameters.Parameter p : parameters) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    // ---- reading ----

    /** Reads and checks a flow's YAML. */
    public static Parsed parse(String yaml) {
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
                "A flow starts with kilnmark: " + FORMAT + ", followed by its name, nodes and connections"));
            return new Parsed(null, problems);
        }
        YamlReader.Entry format = top.entries().get("kilnmark");
        if (format == null) {
            problems.add(Problem.at(top.position(), null, "The flow must start with kilnmark: " + FORMAT
                + ", naming its format"));
        } else if (!FORMAT.equals(format.value().value())) {
            problems.add(Problem.at(format.value().position(), null, "This is in the format '"
                + Suggest.clip(String.valueOf(format.value().text())) + "', but a flow is " + FORMAT
                + (String.valueOf(format.value().text()).startsWith("pipeline/")
                ? ": this looks like a pipeline, which belongs under Pipelines" : "")));
        }
        for (YamlReader.Entry e : top.entries().values()) {
            if (!TOP_LEVEL_KEYS.contains(e.key()) && !Renamed.isOld(e.key(), TOP_LEVEL_KEYS)) {
                problems.add(Problem.at(e.keyPosition(), null, "Unknown key '" + Suggest.clip(e.key()) + "'"
                    + Suggest.hint(e.key(), TOP_LEVEL_KEYS)
                    + ": a flow has kilnmark, name, description, parameters, queue_timeout, nodes, connections and layout"));
            }
        }
        String name = text(top.entries().get("name"), "name", problems);
        if (!top.entries().containsKey("name")) {
            problems.add(Problem.at(top.position(), null, "The flow needs a name"));
        } else if (name != null && !NAME.matcher(name).matches()) {
            problems.add(Problem.at(top.entries().get("name").value().position(), null, "The flow's name '"
                + Suggest.clip(name) + "' must start with a lower-case letter and use only a-z, 0-9 and -, up to 63 "
                + "characters"));
        }
        String description = text(top.entries().get("description"), "description", problems);
        Duration timeout = queueTimeout(top.entries().get("queue_timeout"), problems);
        List<Parameters.Parameter> parameters = Parameters.read(top.entries().get("parameters"), "flow", false, problems);
        Set<String> names = new LinkedHashSet<>();
        parameters.forEach(p -> names.add(p.name()));

        List<Node> nodes = new ArrayList<>();
        YamlReader.Entry nodesEntry = Renamed.entry(top, "nodes", "flow", problems);
        if (nodesEntry == null) {
            problems.add(Problem.at(top.position(), null, "The flow needs nodes"));
        } else if (!(nodesEntry.value() instanceof YamlReader.Sequence list)) {
            problems.add(Problem.at(nodesEntry.value().position(), null,
                "nodes must be a list, each node starting with - id: …"));
        } else if (list.items().isEmpty()) {
            problems.add(Problem.at(nodesEntry.value().position(), null, "The flow needs at least one node"));
        } else if (list.items().size() > MAX_NODES) {
            problems.add(Problem.at(list.position(), null, "A flow has at most " + MAX_NODES + " nodes"));
        } else {
            Map<String, Position> ids = new HashMap<>();
            for (YamlReader.Node item : list.items()) {
                Node s = node(item, ids, names, problems);
                if (s != null) {
                    nodes.add(s);
                }
            }
        }
        List<Connection> connections = new ArrayList<>();
        YamlReader.Entry connectionsEntry = Renamed.entry(top, "connections", "flow", problems);
        Map<String, Node> byId = new LinkedHashMap<>();
        nodes.forEach(s -> byId.put(s.id(), s));
        if (connectionsEntry != null) {
            if (!(connectionsEntry.value() instanceof YamlReader.Sequence list)) {
                problems.add(Problem.at(connectionsEntry.value().position(), null,
                    "connections must be a list, each connection starting with - from: …"));
            } else if (list.items().size() > MAX_CONNECTIONS) {
                problems.add(Problem.at(list.position(), null, "A flow has at most " + MAX_CONNECTIONS + " connections"));
            } else {
                connectionItems(list, byId, parameters, connections, problems);
            }
        }
        FlowDefinition def = new FlowDefinition(name, description, parameters, nodes, connections, timeout);
        loops(def, connectionsEntry, problems);
        nodeValues(def, problems);
        return new Parsed(def, problems);
    }

    private static Duration queueTimeout(YamlReader.Entry e, List<Problem> problems) {
        if (e == null) {
            return null;
        }
        var m = e.value() instanceof YamlReader.Scalar sc && sc.value() != null
            ? DURATION.matcher(sc.written().strip().toLowerCase()) : null;
        if (m == null || !m.matches()) {
            problems.add(Problem.at(e.value().position(), null,
                "queue_timeout is a time such as 30m, 90s or 2h: how long a node waits for a pipeline already running"));
            return null;
        }
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
        if (d.isZero() || d.compareTo(MAX_QUEUE_TIMEOUT) > 0) {
            problems.add(Problem.at(e.value().position(), null, "queue_timeout must be more than nothing and at most 24h"));
            return null;
        }
        return d;
    }

    private static Node node(YamlReader.Node item, Map<String, Position> ids, Set<String> names,
                             List<Problem> problems) {
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
                "A node id is a word such as nightly-load, not a number, list or mapping"));
            return null;
        }
        if ("vars".equals(id)) {
            problems.add(Problem.at(idEntry.value().position(), id, "A node cannot be called vars: ${vars.name} "
                + "reads a server variable"));
        }
        if (!PipelineCompiler.NODE_ID.matcher(id).matches()) {
            problems.add(Problem.at(idEntry.value().position(), Suggest.clip(id), PipelineCompiler.badId(id)));
            if (id.length() > Suggest.MAX_ECHO) {
                return null;
            }
        }
        Position first = ids.putIfAbsent(id, idEntry.value().position());
        if (first != null) {
            problems.add(Problem.at(idEntry.value().position(), id,
                "Node id '" + id + "' is used twice (first on line " + first.line() + ")"));
            return null;
        }
        Map<String, Position> keys = new LinkedHashMap<>();
        m.entries().values().forEach(e -> keys.put(e.key(), e.keyPosition()));
        YamlReader.Entry typeEntry = m.entries().get("type");
        String type = typeEntry == null ? null : text(typeEntry, "type", problems);
        if (typeEntry == null) {
            problems.add(Problem.at(m.position(), id, "Node " + id + " needs a type: " + String.join(" or ", TYPES)));
            return Node.bare(id, null, m.position(), keys);
        }
        if (type != null && !TYPES.contains(type)) {
            problems.add(Problem.at(typeEntry.value().position(), id, "Unknown node type '" + Suggest.clip(type) + "'"
                + Suggest.hint(type, TYPES) + ": a flow node is " + String.join(" or ", TYPES)));
            return Node.bare(id, null, m.position(), keys);
        }
        if (type == null) {
            return Node.bare(id, null, m.position(), keys);
        }
        Set<String> allowed = TYPE_KEYS.get(type);
        for (YamlReader.Entry e : m.entries().values()) {
            if (!NODE_KEYS.contains(e.key()) && !allowed.contains(e.key())) {
                List<String> all = new ArrayList<>(allowed);
                problems.add(Problem.at(e.keyPosition(), id, "Node " + id + " (" + type + ") has no setting '"
                    + Suggest.clip(e.key()) + "'" + Suggest.hint(e.key(), all) + ": it takes " + String.join(", ",
                    allowed.stream().sorted().toList())));
            }
        }
        if (type.equals(RUN_PIPELINE)) {
            YamlReader.Entry p = m.entries().get("pipeline");
            String pipeline = null;
            if (p == null) {
                problems.add(Problem.at(m.position(), id, "Node " + id + " needs a pipeline: the name of the pipeline to run"));
            } else {
                pipeline = text(p, "pipeline", problems);
                if (pipeline != null && !PIPELINE_NAME.matcher(pipeline).matches()) {
                    problems.add(Problem.at(p.value().position(), id, "pipeline must be a pipeline's name, such as "
                        + "customers-clean (lower case, digits and -)"));
                    pipeline = null;
                }
            }
            Integer version = null;
            YamlReader.Entry v = m.entries().get("version");
            if (v != null) {
                if (v.value().value() instanceof Integer n && n >= 1) {
                    version = n;
                } else {
                    problems.add(Problem.at(v.value().position(), id, "version is a version number from 1; leave it out "
                        + "to run the pipeline's latest"));
                }
            }
            Map<String, Object> with = with(m.entries().get("with"), id, names, problems);
            return new Node(id, type, pipeline, version, null, null, m.position(), keys, with, null, null, null);
        }
        if (type.equals(NOTIFY)) {
            return notifyNode(id, m, keys, names, problems);
        }
        if (type.equals(WAIT)) {
            YamlReader.Entry f = m.entries().get("for");
            Duration d = null;
            if (f == null) {
                problems.add(Problem.at(m.position(), id, "Node " + id + " needs for: how long to wait, such as 10m"));
            } else {
                d = duration(f);
                if (d == null || d.isZero() || d.compareTo(MAX_WAIT) > 0) {
                    problems.add(Problem.at(f.value().position(), id, "for is a time such as 10m, 90s or 2h, more than "
                        + "nothing and at most 24h"));
                    d = null;
                }
            }
            return new Node(id, type, null, null, null, null, m.position(), keys, Map.of(), null, null, d);
        }
        String status = "failed";
        YamlReader.Entry s = m.entries().get("status");
        if (s != null) {
            String t = s.value().value() instanceof String str ? str.strip().toLowerCase() : null;
            if ("succeeded".equals(t) || "failed".equals(t)) {
                status = t;
            } else {
                problems.add(Problem.at(s.value().position(), id, "status is succeeded or failed: how the flow counts "
                    + "when this node ends it"));
            }
        }
        YamlReader.Entry msg = m.entries().get("message");
        String message = msg == null ? null : text(msg, "message", problems);
        return new Node(id, type, null, null, status, message, m.position(), keys, Map.of(), null, null, null);
    }

    /**
     * A run-pipeline node's {@code with:}: a value for each pipeline parameter it sets, as written. Text
     * may name the flow's parameters and server variables; which parameters the pipeline takes, and of
     * what kind, is for the server to check.
     */
    private static Map<String, Object> with(YamlReader.Entry e, String id, Set<String> names, List<Problem> problems) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (e == null) {
            return out;
        }
        if (!(e.value() instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(e.value().position(), id, "with is a mapping of the pipeline's parameters to "
                + "values, such as with: { region: EMEA, limit: \"${limit}\" }"));
            return out;
        }
        for (YamlReader.Entry p : m.entries().values()) {
            if (!Parameters.NAME.matcher(p.key()).matches()) {
                problems.add(Problem.at(p.keyPosition(), id, "'" + Suggest.clip(p.key()) + "' is not a parameter name"));
                continue;
            }
            if (!(p.value() instanceof YamlReader.Scalar sc) || sc.value() == null) {
                problems.add(Problem.at(p.value().position(), id, "with: " + p.key() + " is one value: text, a number "
                    + "or true/false"));
                continue;
            }
            Object v = sc.value() instanceof String ? sc.written() : sc.value();
            if (v instanceof String t) {
                String wrong = Parameters.unknownName(t, names, true);
                if (wrong != null) {
                    problems.add(Problem.at(p.value().position(), id, "with: " + p.key() + " " + wrong));
                    continue;
                }
            } else if (v instanceof Number n) {
                v = n.doubleValue();
            }
            out.put(p.key(), v);
        }
        return out;
    }

    private static Node notifyNode(String id, YamlReader.Mapping m, Map<String, Position> keys, Set<String> names,
                                   List<Problem> problems) {
        String severity = "info";
        YamlReader.Entry s = m.entries().get("severity");
        if (s != null) {
            String t = s.value().value() instanceof String str ? str.strip().toLowerCase() : null;
            if (SEVERITIES.contains(t)) {
                severity = t;
            } else {
                problems.add(Problem.at(s.value().position(), id, "severity is " + String.join(", ", SEVERITIES)));
            }
        }
        String title = words(m.entries().get("title"), "title", MAX_TITLE, id, names, problems);
        if (!m.entries().containsKey("title")) {
            problems.add(Problem.at(m.position(), id, "Node " + id + " needs a title: one line, the notification's subject"));
        }
        String message = words(m.entries().get("message"), "message", MAX_MESSAGE, id, names, problems);
        return new Node(id, NOTIFY, null, null, null, message, m.position(), keys, Map.of(), severity, title, null);
    }

    /** A notification's words, which may name the flow's parameters and server variables. */
    private static String words(YamlReader.Entry e, String key, int max, String id, Set<String> names,
                                List<Problem> problems) {
        String t = e == null ? null : text(e, key, problems);
        if (t == null) {
            return null;
        }
        if (t.length() > max) {
            problems.add(Problem.at(e.value().position(), id, key + " is at most " + String.format("%,d", max)
                + " characters"));
            return null;
        }
        String wrong = Parameters.unknownName(t, names, true);
        if (wrong != null) {
            problems.add(Problem.at(e.value().position(), id, key + " " + wrong));
            return null;
        }
        return t;
    }

    /** A time such as 30m, 90s or 2h, or null. */
    private static Duration duration(YamlReader.Entry e) {
        var m = e.value() instanceof YamlReader.Scalar sc && sc.value() != null
            ? DURATION.matcher(sc.written().strip().toLowerCase()) : null;
        if (m == null || !m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    private static final Pattern PIPELINE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    /** A connection as written, before its condition is read. */
    private record RawConnection(String from, String to, String when, YamlReader.Node whenNode, Position position) {
    }

    private static void connectionItems(YamlReader.Sequence list, Map<String, Node> byId, List<Parameters.Parameter> parameters,
                                  List<Connection> out, List<Problem> problems) {
        List<RawConnection> raw = new ArrayList<>();
        for (YamlReader.Node item : list.items()) {
            if (!(item instanceof YamlReader.Mapping m)) {
                problems.add(Problem.at(item.position(), null, "Each connection is a mapping: - { from: a, to: b, when: success }"));
                continue;
            }
            for (YamlReader.Entry e : m.entries().values()) {
                if (!CONNECTION_KEYS.contains(e.key())) {
                    problems.add(Problem.at(e.keyPosition(), null, "A connection has no setting '" + Suggest.clip(e.key()) + "'"
                        + Suggest.hint(e.key(), CONNECTION_KEYS) + ": it takes from, to and when"));
                }
            }
            String from = endpoint(m, "from", byId, problems);
            String to = endpoint(m, "to", byId, problems);
            YamlReader.Entry w = m.entries().get("when");
            String when = w == null ? "success" : text(w, "when", problems);
            if (from == null || to == null || when == null) {
                continue;
            }
            if (from.equals(to)) {
                problems.add(Problem.at(m.entries().get("to").value().position(), to, "A connection cannot lead from node "
                    + from + " back to itself"));
                continue;
            }
            raw.add(new RawConnection(from, to, when, w == null ? null : w.value(), m.position()));
        }
        // Every node that can have ended before a connection is decided: found from all the connections, so the
        // order they are written in does not matter.
        Map<String, Set<String>> before = new HashMap<>();
        for (RawConnection r : raw) {
            before.computeIfAbsent(r.to(), k -> new LinkedHashSet<>()).add(r.from());
        }
        Set<String> seen = new HashSet<>();
        for (RawConnection r : raw) {
            String word = r.when().strip().toLowerCase();
            FlowEngine.Kind kind;
            FlowCondition condition = null;
            switch (word) {
                case "success" -> kind = FlowEngine.Kind.SUCCESS;
                case "failure" -> kind = FlowEngine.Kind.FAILURE;
                case "always" -> kind = FlowEngine.Kind.ALWAYS;
                default -> {
                    kind = FlowEngine.Kind.CONDITION;
                    condition = condition(r.when(), r.whenNode().position(), r.from(), byId, parameters, before, problems);
                    if (condition == null) {
                        continue;
                    }
                }
            }
            if (!seen.add(r.from() + "|" + r.to() + "|" + (condition == null ? word : r.when().strip()))) {
                problems.add(Problem.at(r.position(), r.to(), "The connection from " + r.from() + " to " + r.to() + " when "
                    + r.when().strip() + " is written twice"));
                continue;
            }
            out.add(new Connection(r.from(), r.to(), kind, kind == FlowEngine.Kind.CONDITION ? r.when().strip() : word,
                condition, r.position()));
        }
    }

    private static String endpoint(YamlReader.Mapping m, String key, Map<String, Node> byId, List<Problem> problems) {
        YamlReader.Entry e = m.entries().get(key);
        if (e == null) {
            problems.add(Problem.at(m.position(), null, "A connection needs a '" + key + "': the id of a node"));
            return null;
        }
        if (!(e.value() instanceof YamlReader.Scalar sc) || !(sc.value() instanceof String id) || id.isBlank()) {
            problems.add(Problem.at(e.value().position(), null, "'" + key + "' is the id of a node"));
            return null;
        }
        if (!byId.containsKey(id)) {
            problems.add(Problem.at(e.value().position(), null, "This connection " + key + " node '" + Suggest.clip(id)
                + "' which is not a node of the flow" + Suggest.hint(id, byId.keySet())));
            return null;
        }
        return id;
    }

    /**
     * The type of a node's value as ${node.field} names it ({@code status} for any node; {@code score},
     * {@code quarantined_share}, {@code rows_written} and {@code duration_s} for a run-pipeline node), or
     * null when there is no such value.
     */
    public static FlowCondition.Type nodeValueType(FlowDefinition def, String name) {
        int dot = name.indexOf('.');
        if (dot < 1) {
            return null;
        }
        Node n = def.node(name.substring(0, dot));
        String field = name.substring(dot + 1);
        if (n == null) {
            return null;
        }
        if (field.equals("status")) {
            return FlowCondition.Type.TEXT;
        }
        return RUN_PIPELINE.equals(n.type()) ? PIPELINE_VALUES.get(field) : null;
    }

    /**
     * The nodes that have always run, and ended, whenever {@code id} runs. A node runs when one of the
     * connections into it is taken, and a taken connection's node ran; so these are the nodes on every
     * way in: for each connection in, its node and the nodes always run before that one, in common.
     * A node a branch may skip is not among them.
     */
    public static Set<String> before(FlowDefinition def, String id) {
        return before(def, id, new java.util.HashMap<>(), new HashSet<>());
    }

    private static Set<String> before(FlowDefinition def, String id, Map<String, Set<String>> memo, Set<String> busy) {
        Set<String> known = memo.get(id);
        if (known != null) {
            return known;
        }
        if (!busy.add(id)) {
            return Set.of(); // a loop, reported on its own
        }
        Set<String> common = null;
        for (Connection c : def.connections()) {
            if (!c.to().equals(id)) {
                continue;
            }
            Set<String> way = new LinkedHashSet<>(before(def, c.from(), memo, busy));
            way.add(c.from());
            if (common == null) {
                common = way;
            } else {
                common.retainAll(way);
            }
        }
        Set<String> out = common == null ? Set.of() : common;
        busy.remove(id);
        memo.put(id, out);
        return out;
    }

    /**
     * Checks the node values that with: and notify name (${clean.rows_written}): the node must have ended
     * before this one starts, and have such a value.
     */
    private static void nodeValues(FlowDefinition def, List<Problem> problems) {
        for (Node n : def.nodes()) {
            List<String> texts = new ArrayList<>();
            n.with().values().forEach(v -> {
                if (v instanceof String t) {
                    texts.add(t);
                }
            });
            if (NOTIFY.equals(n.type())) {
                if (n.title() != null) {
                    texts.add(n.title());
                }
                if (n.message() != null) {
                    texts.add(n.message());
                }
            }
            Set<String> before = null;
            for (String t : texts) {
                for (String name : Parameters.names(t)) {
                    if (name.startsWith(Parameters.VARS) || name.indexOf('.') < 1) {
                        continue; // parameters and variables are checked where written
                    }
                    String other = name.substring(0, name.indexOf('.'));
                    before = before == null ? before(def, n.id()) : before;
                    String why = !before.contains(other)
                        ? " names " + name + ", but node " + Suggest.clip(other) + " has not always run when "
                            + n.id() + " starts: only nodes on every way into " + n.id() + " can be read, as a branch "
                            + "may skip the others"
                        : nodeValueType(def, name) == null
                            ? " names " + name + ", which " + other + " does not report: a node reports status, and a "
                                + "run-pipeline node score, quarantined_share, rows_written and duration_s"
                            : null;
                    if (why != null) {
                        problems.add(Problem.at(n.position(), n.id(), "Node " + n.id() + why));
                    }
                }
            }
        }
    }

    /**
     * Reads a connection's condition. It may read the parameters, and the values of the node it leaves
     * and of nodes before that one (a node that has not ended when the connection is decided has nothing
     * to report).
     */
    private static FlowCondition condition(String text, Position at, String from, Map<String, Node> byId,
                                           List<Parameters.Parameter> parameters, Map<String, Set<String>> before,
                                           List<Problem> problems) {
        Set<String> upstream = new LinkedHashSet<>();
        upstream.add(from);
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(from));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (String up : before.getOrDefault(s, Set.of())) {
                if (upstream.add(up)) {
                    todo.add(up);
                }
            }
        }
        Map<String, FlowCondition.Type> types = new LinkedHashMap<>();
        for (Parameters.Parameter p : parameters) {
            if (!p.list()) {
                types.put(p.name(), p.type()); // a list is for in (...) in pipelines, not for conditions
            }
        }
        for (String id : upstream) {
            Node s = byId.get(id);
            types.put(id + ".status", FlowCondition.Type.TEXT);
            if (s != null && RUN_PIPELINE.equals(s.type())) {
                PIPELINE_VALUES.forEach((field, type) -> types.put(id + "." + field, type));
            }
        }
        FlowCondition.Parsed parsed = FlowCondition.parse(text, new FlowCondition.Scope() {
            @Override
            public FlowCondition.Type typeOf(String name) {
                return types.get(name);
            }

            @Override
            public Collection<String> names() {
                return types.keySet();
            }
        });
        for (FlowCondition.Issue issue : parsed.issues()) {
            problems.add(Problem.at(new Position(at.line(), at.column() + issue.column() - 1), from,
                "Connection from " + from + ": " + issue.message()));
        }
        return parsed.condition();
    }

    private static void loops(FlowDefinition def, YamlReader.Entry connectionsEntry, List<Problem> problems) {
        Map<String, Integer> waiting = new HashMap<>();
        def.nodes().forEach(s -> waiting.put(s.id(), 0));
        for (Connection p : def.connections()) {
            waiting.merge(p.to(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        waiting.forEach((k, v) -> {
            if (v == 0) {
                ready.add(k);
            }
        });
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            done.add(id);
            for (Connection p : def.connections()) {
                if (p.from().equals(id) && waiting.merge(p.to(), -1, Integer::sum) == 0) {
                    ready.add(p.to());
                }
            }
        }
        if (done.size() < waiting.size()) {
            List<String> stuck = def.nodes().stream().map(Node::id).filter(s -> !done.contains(s)).toList();
            problems.add(Problem.at(connectionsEntry == null ? Position.START : connectionsEntry.keyPosition(), null,
                "The connections form a loop through " + String.join(", ", stuck.subList(0, Math.min(5, stuck.size())))
                    + ": a flow must run one way"));
        }
    }

    private static String text(YamlReader.Entry e, String key, List<Problem> problems) {
        if (e == null) {
            return null;
        }
        if (e.value() instanceof YamlReader.Scalar sc && sc.value() != null && !sc.written().isBlank()) {
            return sc.written();
        }
        problems.add(Problem.at(e.value().position(), null, key + " must be text"));
        return null;
    }
}
