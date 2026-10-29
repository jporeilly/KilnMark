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
 * A flow as written in its YAML ({@code kilnmark: flow/v1}, docs/design/flows.md): flow steps
 * joined by paths, and the parameters it takes. {@link #parse} reads one and checks everything
 * that can be checked without the server: keys, ids, step settings, paths, loops, and every
 * condition against the values it can read. Whether the pipelines a flow runs exist is for the
 * server to check.
 *
 * @param name         the flow's name
 * @param description  what it does, or null
 * @param parameters   what it takes, in the order written
 * @param steps        the steps in the order written
 * @param paths        the paths in the order written
 * @param queueTimeout how long a step waits for a pipeline that is already running before it fails
 */
public record FlowDefinition(String name, String description, List<Parameter> parameters, List<Step> steps,
                             List<Path> paths, Duration queueTimeout) {

    /** The format this version reads. */
    public static final String FORMAT = "flow/v1";
    /** Flow names, as pipeline names. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    /** The step type that runs a pipeline. */
    public static final String RUN_PIPELINE = "run-pipeline";
    /** The step type that ends the flow. */
    public static final String STOP = "stop";
    /** The step types this version knows. */
    public static final List<String> TYPES = List.of(RUN_PIPELINE, STOP);
    /** What a step that runs a pipeline reports, for conditions, besides {@code status}. */
    public static final Map<String, FlowCondition.Type> PIPELINE_VALUES = Map.of(
        "score", FlowCondition.Type.NUMBER,
        "quarantined_share", FlowCondition.Type.NUMBER,
        "rows_written", FlowCondition.Type.NUMBER,
        "duration_s", FlowCondition.Type.NUMBER);

    /** The default of {@code queue_timeout}. */
    public static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration MAX_QUEUE_TIMEOUT = Duration.ofHours(24);
    static final int MAX_STEPS = 200;
    static final int MAX_PATHS = 1_000;
    static final int MAX_PARAMETERS = 50;

    static final Set<String> TOP_LEVEL_KEYS = Set.of("kilnmark", "name", "description", "parameters", "queue_timeout",
        "steps", "paths", "layout");
    static final Set<String> STEP_KEYS = Set.of("id", "type");
    static final Map<String, Set<String>> TYPE_KEYS = Map.of(
        RUN_PIPELINE, Set.of("pipeline", "version"),
        STOP, Set.of("status", "message"));
    static final Set<String> PATH_KEYS = Set.of("from", "to", "when");
    static final Set<String> PARAMETER_KEYS = Set.of("type", "default", "description");
    static final Set<String> RESERVED = Set.of("and", "or", "not", "true", "false", "good", "fair", "poor");
    static final Pattern PARAMETER_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,6})\\s*(s|m|h)");

    /** A parameter: its name, kind, default (null for none) and description. */
    public record Parameter(String name, FlowCondition.Type type, Object defaultValue, String description,
                            Position position) {
    }

    /**
     * One flow step. {@code pipeline} and {@code version} (null for the latest) are set for
     * {@link #RUN_PIPELINE}; {@code status} ({@code succeeded} or {@code failed}) and {@code message}
     * for {@link #STOP}.
     */
    public record Step(String id, String type, String pipeline, Integer version, String status, String message,
                       Position position, Map<String, Position> keys) {

        /** Where {@code key} is written, or where the step starts if it is not. */
        public Position at(String key) {
            return keys.getOrDefault(key, position);
        }
    }

    /**
     * One path. {@code when} is {@code success}, {@code failure}, {@code always} or a condition;
     * {@code condition} is set only in the last case.
     */
    public record Path(String from, String to, FlowEngine.Kind kind, String when, FlowCondition condition,
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
        steps = List.copyOf(steps);
        paths = List.copyOf(paths);
        queueTimeout = queueTimeout == null ? DEFAULT_QUEUE_TIMEOUT : queueTimeout;
    }

    /** The path conditions as the engine reads them. */
    public List<FlowEngine.Path> enginePaths() {
        List<FlowEngine.Path> out = new ArrayList<>();
        for (Path p : paths) {
            FlowCondition c = p.condition();
            out.add(new FlowEngine.Path(p.from(), p.to(), p.kind(), c == null ? null : c::evaluate));
        }
        return out;
    }

    /** The step with this id, or null. */
    public Step step(String id) {
        for (Step s : steps) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The parameter with this name, or null. */
    public Parameter parameter(String name) {
        for (Parameter p : parameters) {
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
                "A flow starts with kilnmark: " + FORMAT + ", followed by its name, steps and paths"));
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
            if (!TOP_LEVEL_KEYS.contains(e.key())) {
                problems.add(Problem.at(e.keyPosition(), null, "Unknown key '" + Suggest.clip(e.key()) + "'"
                    + Suggest.hint(e.key(), TOP_LEVEL_KEYS)
                    + ": a flow has kilnmark, name, description, parameters, queue_timeout, steps, paths and layout"));
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
        List<Parameter> parameters = parameters(top.entries().get("parameters"), problems);

        List<Step> steps = new ArrayList<>();
        YamlReader.Entry stepsEntry = top.entries().get("steps");
        if (stepsEntry == null) {
            problems.add(Problem.at(top.position(), null, "The flow needs steps"));
        } else if (!(stepsEntry.value() instanceof YamlReader.Sequence list)) {
            problems.add(Problem.at(stepsEntry.value().position(), null,
                "steps must be a list, each step starting with - id: …"));
        } else if (list.items().isEmpty()) {
            problems.add(Problem.at(stepsEntry.value().position(), null, "The flow needs at least one step"));
        } else if (list.items().size() > MAX_STEPS) {
            problems.add(Problem.at(list.position(), null, "A flow has at most " + MAX_STEPS + " steps"));
        } else {
            Map<String, Position> ids = new HashMap<>();
            for (YamlReader.Node item : list.items()) {
                Step s = step(item, ids, problems);
                if (s != null) {
                    steps.add(s);
                }
            }
        }
        List<Path> paths = new ArrayList<>();
        YamlReader.Entry pathsEntry = top.entries().get("paths");
        Map<String, Step> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));
        if (pathsEntry != null) {
            if (!(pathsEntry.value() instanceof YamlReader.Sequence list)) {
                problems.add(Problem.at(pathsEntry.value().position(), null,
                    "paths must be a list, each path starting with - from: …"));
            } else if (list.items().size() > MAX_PATHS) {
                problems.add(Problem.at(list.position(), null, "A flow has at most " + MAX_PATHS + " paths"));
            } else {
                pathItems(list, byId, parameters, paths, problems);
            }
        }
        FlowDefinition def = new FlowDefinition(name, description, parameters, steps, paths, timeout);
        loops(def, pathsEntry, problems);
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
                "queue_timeout is a time such as 30m, 90s or 2h: how long a step waits for a pipeline already running"));
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

    private static List<Parameter> parameters(YamlReader.Entry e, List<Problem> problems) {
        List<Parameter> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        if (!(e.value() instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(e.value().position(), null,
                "parameters is a mapping of names, such as parameters: { region: { type: text, default: EMEA } }"));
            return out;
        }
        if (m.entries().size() > MAX_PARAMETERS) {
            problems.add(Problem.at(m.position(), null, "A flow has at most " + MAX_PARAMETERS + " parameters"));
            return out;
        }
        for (YamlReader.Entry p : m.entries().values()) {
            if (RESERVED.contains(p.key())) {
                problems.add(Problem.at(p.keyPosition(), null, "'" + p.key() + "' cannot be a parameter name: a condition "
                    + "reads it as a word of the language (and, or, not, true, false, good, fair, poor)"));
                continue;
            }
            if (!PARAMETER_NAME.matcher(p.key()).matches()) {
                problems.add(Problem.at(p.keyPosition(), null, "The parameter name '" + Suggest.clip(p.key())
                    + "' must start with a lower-case letter and use only a-z, 0-9 and _, up to 64 characters"));
                continue;
            }
            if (!(p.value() instanceof YamlReader.Mapping spec)) {
                problems.add(Problem.at(p.value().position(), null, "Parameter " + p.key()
                    + " is a mapping with a type and, if you like, a default"));
                continue;
            }
            for (YamlReader.Entry k : spec.entries().values()) {
                if (!PARAMETER_KEYS.contains(k.key())) {
                    problems.add(Problem.at(k.keyPosition(), null, "Parameter " + p.key() + " has no setting '"
                        + Suggest.clip(k.key()) + "'" + Suggest.hint(k.key(), PARAMETER_KEYS)
                        + ": it takes type, default and description"));
                }
            }
            YamlReader.Entry t = spec.entries().get("type");
            FlowCondition.Type type = null;
            if (t != null && t.value().value() instanceof String ts) {
                type = switch (ts.strip().toLowerCase()) {
                    case "text" -> FlowCondition.Type.TEXT;
                    case "number" -> FlowCondition.Type.NUMBER;
                    case "boolean" -> FlowCondition.Type.BOOLEAN;
                    default -> null;
                };
            }
            if (type == null) {
                problems.add(Problem.at(t == null ? spec.position() : t.value().position(), null, "Parameter " + p.key()
                    + " needs a type: text, number or boolean"));
                continue;
            }
            Object def = null;
            YamlReader.Entry d = spec.entries().get("default");
            if (d != null && d.value().value() != null) {
                Object v = d.value().value();
                boolean fits = switch (type) {
                    case TEXT -> v instanceof String;
                    case NUMBER -> v instanceof Number && !(v instanceof java.math.BigInteger)
                        && !Double.isNaN(((Number) v).doubleValue()) && !Double.isInfinite(((Number) v).doubleValue());
                    case BOOLEAN -> v instanceof Boolean;
                };
                if (!fits) {
                    problems.add(Problem.at(d.value().position(), null, "The default of " + p.key() + " must be a "
                        + type.name().toLowerCase()));
                } else {
                    def = type == FlowCondition.Type.NUMBER ? (Object) ((Number) v).doubleValue() : v;
                }
            }
            YamlReader.Entry desc = spec.entries().get("description");
            out.add(new Parameter(p.key(), type, def, desc == null ? null : text(desc, "description", problems),
                p.keyPosition()));
        }
        return out;
    }

    private static Step step(YamlReader.Node item, Map<String, Position> ids, List<Problem> problems) {
        if (!(item instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(item.position(), null, "Each step is a mapping starting with - id: …"));
            return null;
        }
        YamlReader.Entry idEntry = m.entries().get("id");
        if (idEntry == null) {
            problems.add(Problem.at(m.position(), null, "Each step needs an id"));
            return null;
        }
        if (!(idEntry.value() instanceof YamlReader.Scalar sc) || !(sc.value() instanceof String id)) {
            problems.add(Problem.at(idEntry.value().position(), null,
                "A step id is a word such as nightly-load, not a number, list or mapping"));
            return null;
        }
        if (!PipelineCompiler.STEP_ID.matcher(id).matches()) {
            problems.add(Problem.at(idEntry.value().position(), Suggest.clip(id), PipelineCompiler.badId(id)));
            if (id.length() > Suggest.MAX_ECHO) {
                return null;
            }
        }
        Position first = ids.putIfAbsent(id, idEntry.value().position());
        if (first != null) {
            problems.add(Problem.at(idEntry.value().position(), id,
                "Step id '" + id + "' is used twice (first on line " + first.line() + ")"));
            return null;
        }
        Map<String, Position> keys = new LinkedHashMap<>();
        m.entries().values().forEach(e -> keys.put(e.key(), e.keyPosition()));
        YamlReader.Entry typeEntry = m.entries().get("type");
        String type = typeEntry == null ? null : text(typeEntry, "type", problems);
        if (typeEntry == null) {
            problems.add(Problem.at(m.position(), id, "Step " + id + " needs a type: " + String.join(" or ", TYPES)));
            return new Step(id, null, null, null, null, null, m.position(), keys);
        }
        if (type != null && !TYPES.contains(type)) {
            problems.add(Problem.at(typeEntry.value().position(), id, "Unknown step type '" + Suggest.clip(type) + "'"
                + Suggest.hint(type, TYPES) + ": a flow step is " + String.join(" or ", TYPES)));
            return new Step(id, null, null, null, null, null, m.position(), keys);
        }
        if (type == null) {
            return new Step(id, null, null, null, null, null, m.position(), keys);
        }
        Set<String> allowed = TYPE_KEYS.get(type);
        for (YamlReader.Entry e : m.entries().values()) {
            if (!STEP_KEYS.contains(e.key()) && !allowed.contains(e.key())) {
                List<String> all = new ArrayList<>(allowed);
                problems.add(Problem.at(e.keyPosition(), id, "Step " + id + " (" + type + ") has no setting '"
                    + Suggest.clip(e.key()) + "'" + Suggest.hint(e.key(), all) + ": it takes " + String.join(", ",
                    allowed.stream().sorted().toList())));
            }
        }
        if (type.equals(RUN_PIPELINE)) {
            YamlReader.Entry p = m.entries().get("pipeline");
            String pipeline = null;
            if (p == null) {
                problems.add(Problem.at(m.position(), id, "Step " + id + " needs a pipeline: the name of the pipeline to run"));
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
            return new Step(id, type, pipeline, version, null, null, m.position(), keys);
        }
        String status = "failed";
        YamlReader.Entry s = m.entries().get("status");
        if (s != null) {
            String t = s.value().value() instanceof String str ? str.strip().toLowerCase() : null;
            if ("succeeded".equals(t) || "failed".equals(t)) {
                status = t;
            } else {
                problems.add(Problem.at(s.value().position(), id, "status is succeeded or failed: how the flow counts "
                    + "when this step ends it"));
            }
        }
        YamlReader.Entry msg = m.entries().get("message");
        String message = msg == null ? null : text(msg, "message", problems);
        return new Step(id, type, null, null, status, message, m.position(), keys);
    }

    private static final Pattern PIPELINE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    /** A path as written, before its condition is read. */
    private record RawPath(String from, String to, String when, YamlReader.Node whenNode, Position position) {
    }

    private static void pathItems(YamlReader.Sequence list, Map<String, Step> byId, List<Parameter> parameters,
                                  List<Path> out, List<Problem> problems) {
        List<RawPath> raw = new ArrayList<>();
        for (YamlReader.Node item : list.items()) {
            if (!(item instanceof YamlReader.Mapping m)) {
                problems.add(Problem.at(item.position(), null, "Each path is a mapping: - { from: a, to: b, when: success }"));
                continue;
            }
            for (YamlReader.Entry e : m.entries().values()) {
                if (!PATH_KEYS.contains(e.key())) {
                    problems.add(Problem.at(e.keyPosition(), null, "A path has no setting '" + Suggest.clip(e.key()) + "'"
                        + Suggest.hint(e.key(), PATH_KEYS) + ": it takes from, to and when"));
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
                problems.add(Problem.at(m.entries().get("to").value().position(), to, "A path cannot lead from step "
                    + from + " back to itself"));
                continue;
            }
            raw.add(new RawPath(from, to, when, w == null ? null : w.value(), m.position()));
        }
        // Every step that can have ended before a path is decided: found from all the paths, so the
        // order they are written in does not matter.
        Map<String, Set<String>> before = new HashMap<>();
        for (RawPath r : raw) {
            before.computeIfAbsent(r.to(), k -> new LinkedHashSet<>()).add(r.from());
        }
        Set<String> seen = new HashSet<>();
        for (RawPath r : raw) {
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
                problems.add(Problem.at(r.position(), r.to(), "The path from " + r.from() + " to " + r.to() + " when "
                    + r.when().strip() + " is written twice"));
                continue;
            }
            out.add(new Path(r.from(), r.to(), kind, kind == FlowEngine.Kind.CONDITION ? r.when().strip() : word,
                condition, r.position()));
        }
    }

    private static String endpoint(YamlReader.Mapping m, String key, Map<String, Step> byId, List<Problem> problems) {
        YamlReader.Entry e = m.entries().get(key);
        if (e == null) {
            problems.add(Problem.at(m.position(), null, "A path needs a '" + key + "': the id of a step"));
            return null;
        }
        if (!(e.value() instanceof YamlReader.Scalar sc) || !(sc.value() instanceof String id) || id.isBlank()) {
            problems.add(Problem.at(e.value().position(), null, "'" + key + "' is the id of a step"));
            return null;
        }
        if (!byId.containsKey(id)) {
            problems.add(Problem.at(e.value().position(), null, "This path " + key + " step '" + Suggest.clip(id)
                + "' which is not a step of the flow" + Suggest.hint(id, byId.keySet())));
            return null;
        }
        return id;
    }

    /**
     * Reads a path's condition. It may read the parameters, and the values of the step it leaves
     * and of steps before that one (a step that has not ended when the path is decided has nothing
     * to report).
     */
    private static FlowCondition condition(String text, Position at, String from, Map<String, Step> byId,
                                           List<Parameter> parameters, Map<String, Set<String>> before,
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
        for (Parameter p : parameters) {
            types.put(p.name(), p.type());
        }
        for (String id : upstream) {
            Step s = byId.get(id);
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
                "Path from " + from + ": " + issue.message()));
        }
        return parsed.condition();
    }

    private static void loops(FlowDefinition def, YamlReader.Entry pathsEntry, List<Problem> problems) {
        Map<String, Integer> waiting = new HashMap<>();
        def.steps().forEach(s -> waiting.put(s.id(), 0));
        for (Path p : def.paths()) {
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
            for (Path p : def.paths()) {
                if (p.from().equals(id) && waiting.merge(p.to(), -1, Integer::sum) == 0) {
                    ready.add(p.to());
                }
            }
        }
        if (done.size() < waiting.size()) {
            List<String> stuck = def.steps().stream().map(Step::id).filter(s -> !done.contains(s)).toList();
            problems.add(Problem.at(pathsEntry == null ? Position.START : pathsEntry.keyPosition(), null,
                "The paths form a loop through " + String.join(", ", stuck.subList(0, Math.min(5, stuck.size())))
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
