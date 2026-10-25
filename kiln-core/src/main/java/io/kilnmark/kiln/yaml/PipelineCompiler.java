package io.kilnmark.kiln.yaml;

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

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.yaml.PipelineDefinition.InputRef;
import io.kilnmark.kiln.yaml.PipelineDefinition.StepDefinition;

/**
 * Checks a pipeline against the step types in a {@link StepFactories} and works out the columns
 * leaving each step (docs/design/pipelines.md). Checking never creates a step: it calls only each
 * factory's ports, settings, {@code check} and {@code outputSchema}. {@link Compiled#graph()} then
 * creates the steps for a run.
 *
 * <p>Checks, each reported with its line and column: the format line; unknown top-level keys; step
 * ids; unknown step types, suggesting the closest; each setting against the step type's settings
 * (required, kind, choices, unknown keys) and the factory's own {@code check}; inputs naming
 * unknown steps or ports; required inputs not connected; a single-input port fed twice; loops; and
 * then, in the order rows flow, each step's columns from its factory's {@code outputSchema}. When a
 * step's columns cannot be known before the run, the steps after it are not checked against
 * columns, and a note says so.
 */
public final class PipelineCompiler {

    /** A step id: a lower-case letter, then up to 63 lower-case letters, digits, dashes and underscores. */
    public static final Pattern STEP_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private final StepFactories factories;

    public PipelineCompiler(StepFactories factories) {
        this.factories = Objects.requireNonNull(factories, "factories");
    }

    /** Reads and checks a pipeline's YAML. */
    public Compiled compile(String yaml) {
        PipelineDefinition.Parsed parsed = PipelineDefinition.parse(yaml);
        return compile(parsed.definition(), parsed.problems());
    }

    /** Checks a pipeline already read. */
    public Compiled compile(PipelineDefinition definition) {
        return compile(definition, List.of());
    }

    private Compiled compile(PipelineDefinition definition, List<Problem> parseProblems) {
        if (definition == null) {
            return new Compiled(null, parseProblems, List.of(), Map.of(), Map.of(), List.of());
        }
        return new Check(definition, parseProblems).run();
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
        private final Map<String, StepDefinition> steps = new LinkedHashMap<>();
        private final Map<String, StepFactory> factoryOf = new LinkedHashMap<>();
        /** Steps whose settings or inputs have a problem: their columns are not worked out. */
        private final Set<String> broken = new HashSet<>();
        /** Steps with a problem in the inputs they name, so a missing input is not reported as well. */
        private final Set<String> badInputs = new HashSet<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<Edge, InputRef> refOf = new HashMap<>();

        Check(PipelineDefinition definition, List<Problem> parseProblems) {
            this.definition = definition;
            this.problems = new ArrayList<>(parseProblems);
            definition.steps().forEach(s -> steps.put(s.id(), s));
            // a step already reported while reading is not also reported for a missing input
            parseProblems.stream().map(Problem::stepId).filter(Objects::nonNull).forEach(badInputs::add);
        }

        Compiled run() {
            for (StepDefinition s : definition.steps()) {
                if (s.type() == null) {
                    broken.add(s.id());
                    continue;
                }
                StepFactory f = factories.get(s.type()).orElse(null);
                if (f == null) {
                    broken.add(s.id());
                    problems.add(Problem.at(s.at("type"), s.id(), unknownType(s.type())));
                    continue;
                }
                factoryOf.put(s.id(), f);
                settings(s, f);
            }
            for (StepDefinition s : definition.steps()) {
                inputs(s);
            }
            for (StepDefinition s : definition.steps()) {
                connected(s);
            }
            List<String> order = order();
            Map<String, Map<String, Schema>> schemas = order == null ? Map.of() : schemas(order);
            if (order != null) {
                sharedUpstream();
            }
            return new Compiled(definition, problems, notes, schemas, factoryOf, edges);
        }

        private String unknownType(String type) {
            Set<String> types = new TreeSet<>();
            factories.all().forEach(f -> types.add(f.type()));
            String closest = Suggest.closest(type, types);
            if (closest != null) {
                return "Unknown step type '" + type + "' (did you mean '" + closest + "'?)";
            }
            return "Unknown step type '" + type + "'. The step types are: " + String.join(", ", types);
        }

        // ---- settings ----

        private void settings(StepDefinition s, StepFactory f) {
            int before = problems.size();
            Map<String, SettingSpec> specs = new LinkedHashMap<>();
            f.settings().forEach(spec -> specs.put(spec.key(), spec));
            for (String key : s.config().keySet()) {
                if (!specs.containsKey(key)) {
                    String hint = Suggest.hint(key, specs.keySet());
                    String known = specs.isEmpty() ? "it takes no settings"
                        : "its settings are " + String.join(", ", specs.keySet());
                    problems.add(Problem.at(s.at(key), s.id(), "Step " + s.id() + " (" + s.type()
                        + ") has no setting '" + key + "'" + hint + ": " + known));
                }
            }
            for (SettingSpec spec : specs.values()) {
                Object value = s.config().get(spec.key());
                if (value == null) {
                    if (spec.required()) {
                        problems.add(Problem.at(s.position(), s.id(), "Step " + s.id() + " (" + s.type()
                            + ") needs the setting '" + spec.key() + "' (" + spec.label() + ")"));
                    }
                    continue;
                }
                String wrong = kind(spec, value);
                if (wrong != null) {
                    problems.add(Problem.at(s.at(spec.key()), s.id(), "Setting '" + spec.key() + "' of step "
                        + s.id() + " " + wrong));
                }
            }
            if (problems.size() == before) {
                try {
                    for (String message : f.check(s.config())) {
                        problems.add(Problem.at(s.at(firstWord(message, s.config().keySet())), s.id(),
                            "Step " + s.id() + ": " + message));
                    }
                } catch (RuntimeException e) {
                    problems.add(Problem.at(s.position(), s.id(), "Step " + s.id()
                        + ": its settings could not be checked: " + message(e)));
                }
            }
            if (problems.size() != before) {
                broken.add(s.id());
            }
        }

        /** What is wrong with a value for its kind of setting, or null. */
        private static String kind(SettingSpec spec, Object v) {
            boolean scalar = v instanceof String || v instanceof Number || v instanceof Boolean;
            return switch (spec.kind()) {
                case TEXT, EXPRESSION -> scalar ? null : "must be a single value, not a list or mapping";
                case COLUMN, SOURCE, ASSET -> v instanceof String || v instanceof Number ? null
                    : "must be a name, such as customer_id";
                case NUMBER -> v instanceof Number ? null : "must be a number";
                case BOOLEAN -> v instanceof Boolean ? null : "must be true or false";
                case CHOICE -> scalar && spec.choices().contains(String.valueOf(v)) ? null
                    : "must be one of " + String.join(", ", spec.choices());
                case COLUMNS -> v instanceof List<?> l && l.stream().allMatch(i -> i instanceof String || i instanceof Number)
                    ? null : "must be a list of columns, such as [customer_id, email]";
                case EXPRESSIONS, CONDITIONS -> v instanceof Map<?, ?> m && m.values().stream()
                    .allMatch(i -> i instanceof String || i instanceof Number || i instanceof Boolean)
                    ? null : "must be a mapping of names to expressions, such as { total: \"sum(amount)\" }";
                case COLUMN_MAP -> v instanceof Map<?, ?> m && m.values().stream()
                    .allMatch(i -> i instanceof String || i instanceof Number)
                    ? null : "must be a mapping of column names to column names, such as { id: customer_id }";
            };
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

        private void inputs(StepDefinition s) {
            StepFactory f = factoryOf.get(s.id());
            for (InputRef ref : s.inputs()) {
                PortRef from = PortRef.parse(ref.from(), "out");
                StepDefinition source = steps.get(from.step());
                if (source == null) {
                    problems.add(Problem.at(ref.position(), s.id(), "Step " + s.id() + " reads from '" + from.step()
                        + "', which is not a step in this pipeline" + Suggest.hint(from.step(), steps.keySet())));
                    bad(s.id());
                    continue;
                }
                if (source.id().equals(s.id())) {
                    problems.add(Problem.at(ref.position(), s.id(), "Step " + s.id() + " reads from itself"));
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
                    problems.add(Problem.at(ref.position(), s.id(), "Step " + s.id() + " reads from " + ref.from()
                        + " twice on its input '" + port + "'"));
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

        private boolean outputExists(StepDefinition s, InputRef ref, StepDefinition source, String port) {
            StepFactory sf = factoryOf.get(source.id());
            if (sf == null) {
                return true; // reported against that step
            }
            List<String> outs;
            try {
                outs = sf.outputPorts(source.config());
            } catch (RuntimeException e) {
                return true; // its settings are reported against it
            }
            if (outs.contains(port)) {
                return true;
            }
            bad(s.id());
            if (outs.isEmpty()) {
                problems.add(Problem.at(ref.position(), s.id(), "Step " + source.id() + " (" + source.type()
                    + ") has no outputs, so step " + s.id() + " cannot read from it"));
            } else {
                problems.add(Problem.at(ref.position(), s.id(), "Step " + source.id() + " (" + source.type()
                    + ") has no output '" + port + "'" + Suggest.hint(port, outs) + ": its outputs are "
                    + String.join(", ", outs)));
            }
            return false;
        }

        /** The input port a reference connects to, or null after reporting why there is none. */
        private String inputPort(StepDefinition s, StepFactory f, InputRef ref) {
            List<String> declared = f.inputPorts().stream().map(PipelineCompiler::portName).toList();
            String what = "Step " + s.id() + " (" + s.type() + ")";
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
                problems.add(Problem.at(ref.position(), s.id(), what + " has no input '" + port + "'"
                    + Suggest.hint(port, declared) + ": its inputs are " + String.join(", ", declared)));
                return null;
            }
            return port;
        }

        /** Every input connected, and a single-input port fed once. */
        private void connected(StepDefinition s) {
            StepFactory f = factoryOf.get(s.id());
            if (f == null) {
                return;
            }
            for (String declared : f.inputPorts()) {
                String port = portName(declared);
                boolean many = declared.endsWith("*");
                List<Edge> into = into(s.id(), port);
                if (into.isEmpty() && !badInputs.contains(s.id())) {
                    String add = f.inputPorts().size() == 1 ? "add input: <step id>" : "add it under inputs";
                    problems.add(Problem.at(s.position(), s.id(), "Step " + s.id() + " (" + s.type()
                        + ") needs an input on port '" + port + "': " + add));
                    broken.add(s.id());
                } else if (!many && into.size() > 1) {
                    problems.add(Problem.at(refOf.get(into.get(1)).position(), s.id(), "Step " + s.id()
                        + "'s input '" + port + "' takes one input, but " + into.size()
                        + " are connected: combine them with a union step first"));
                    broken.add(s.id());
                }
            }
        }

        private List<Edge> into(String step, String port) {
            return edges.stream().filter(e -> e.to().step().equals(step) && e.to().port().equals(port)).toList();
        }

        // ---- order and columns ----

        /** Step ids in the order rows flow; null, after reporting the loop, if there is one. */
        private List<String> order() {
            Map<String, Integer> waiting = new LinkedHashMap<>();
            steps.keySet().forEach(id -> waiting.put(id, 0));
            for (Edge e : edges) {
                waiting.merge(e.to().step(), 1, Integer::sum);
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
                    if (e.from().step().equals(id) && waiting.merge(e.to().step(), -1, Integer::sum) == 0) {
                        ready.add(e.to().step());
                    }
                }
            }
            if (out.size() == steps.size()) {
                return out;
            }
            List<String> loop = loop(new HashSet<>(out));
            StepDefinition first = steps.get(loop.getFirst());
            problems.add(Problem.at(first.position(), first.id(), "Steps " + String.join(" → ", loop) + " → "
                + loop.getFirst() + " form a loop: rows in a pipeline must flow one way"));
            return null;
        }

        /**
         * One loop among the steps not in {@code ordered}, in the direction rows flow. Each of those
         * steps is fed by another of them, so walking back along inputs must come round.
         */
        private List<String> loop(Set<String> ordered) {
            String start = steps.keySet().stream().filter(id -> !ordered.contains(id)).findFirst().orElseThrow();
            List<String> path = new ArrayList<>();
            Set<String> onPath = new LinkedHashSet<>();
            String at = start;
            while (onPath.add(at)) {
                path.add(at);
                String current = at;
                at = edges.stream().filter(e -> e.to().step().equals(current) && !ordered.contains(e.from().step()))
                    .map(e -> e.from().step()).findFirst().orElseThrow();
            }
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(at), path.size()));
            Collections.reverse(loop);
            List<String> written = new ArrayList<>(steps.keySet()); // start from the step written first
            String first = loop.stream().min(Comparator.comparingInt(written::indexOf)).orElseThrow();
            Collections.rotate(loop, -loop.indexOf(first));
            return loop;
        }

        private Map<String, Map<String, Schema>> schemas(List<String> order) {
            Map<String, Map<String, Schema>> known = new LinkedHashMap<>();
            Map<String, String> unknownBecause = new HashMap<>(); // step → the step whose columns are unknown
            Map<String, List<String>> skipped = new LinkedHashMap<>();
            for (String id : order) {
                StepDefinition s = steps.get(id);
                StepFactory f = factoryOf.get(id);
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
                        Schema schema = known.getOrDefault(from.step(), Map.of()).get(from.port());
                        if (schema != null) {
                            inputs.put(declared.endsWith("*") ? port + "[" + k + "]" : port, schema);
                        } else if (unknownBecause.containsKey(from.step())) {
                            origin = origin == null ? unknownBecause.get(from.step()) : origin;
                        } else {
                            silent = true; // that step has a problem of its own
                        }
                    }
                }
                if (silent || origin != null) {
                    if (!silent) {
                        unknownBecause.put(id, origin);
                        skipped.computeIfAbsent(origin, k -> new ArrayList<>()).add(id);
                    }
                    continue;
                }
                List<String> outs;
                try {
                    outs = f.outputPorts(s.config());
                } catch (RuntimeException e) {
                    problems.add(Problem.at(s.position(), id, "Step " + id + ": its outputs could not be worked out: "
                        + message(e)));
                    continue;
                }
                Map<String, Schema> mine = new LinkedHashMap<>();
                for (String port : outs) {
                    Schema schema;
                    try {
                        schema = f.outputSchema(port, inputs, s.config());
                    } catch (StepException e) {
                        problems.add(Problem.at(s.position(), id, "Step " + id + ": " + message(e)));
                        mine = null;
                        break;
                    } catch (RuntimeException e) {
                        problems.add(Problem.at(s.position(), id, "Step " + id + ": its columns could not be worked out: "
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
            skipped.forEach((origin, after) -> notes.add(Problem.at(steps.get(origin).position(), origin,
                "The columns leaving step " + origin + " cannot be known before the run, so "
                    + (after.size() == 1 ? "step " + after.getFirst() + " is" : "steps " + String.join(", ", after) + " are")
                    + " not checked against columns until then")));
            return known;
        }

        /**
         * A note for a step with several input ports fed, through any number of steps, from the
         * same step: if it reads one input to its end before another (a lookup reads its lookup
         * side first), the shared step waits for room on the input not being read, and the run
         * stalls once the data is larger than the edges hold.
         */
        private void sharedUpstream() {
            Map<String, Set<String>> above = new HashMap<>();
            for (StepDefinition s : definition.steps()) {
                Map<String, Set<String>> byPort = new LinkedHashMap<>();
                for (Edge e : edges) {
                    if (e.to().step().equals(s.id())) {
                        Set<String> a = byPort.computeIfAbsent(e.to().port(), k -> new HashSet<>());
                        a.add(e.from().step());
                        a.addAll(ancestors(e.from().step(), above));
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
                    notes.add(Problem.at(s.position(), s.id(), "Step " + s.id() + "'s inputs " + String.join(" and ", ports)
                        + " both come from step " + common.iterator().next() + ". If " + s.id()
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
                    if (e.to().step().equals(at) && out.add(e.from().step())) {
                        todo.add(e.from().step());
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
