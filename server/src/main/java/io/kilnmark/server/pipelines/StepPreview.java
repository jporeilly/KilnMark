package io.kilnmark.server.pipelines;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.connect.Preview;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Expression;
import io.kilnmark.kiln.yaml.PipelineDefinition;

/**
 * Preview at a step (docs/design/pipelines.md, "Preview at a step"), without the server around it:
 * which steps run (the chosen step and every step upstream of it, nothing else), which steps may run
 * in a preview at all, which columns must be masked for the caller, and the in-memory collector that
 * takes the first rows from the chosen output and then finishes, so the engine's "ends early" stops
 * the steps before it. {@link PipelinePreviews} adds the caller, the limits and the masking.
 */
final class StepPreview {

    /** Rows shown when the request does not say. */
    static final int DEFAULT_ROWS = 100;
    /** The most rows a preview shows, as a source's preview. */
    static final int MAX_ROWS = Preview.MAX_ROWS;
    /** The collector's id: it starts with a capital letter, so it can never be one of the pipeline's own. */
    static final String COLLECTOR = "Preview";

    /**
     * The step types a preview may run: reads and the steps that work on rows in memory. Anything
     * else (a write, or a step type added later) may write or change something, and is refused.
     */
    static final Set<String> PREVIEWABLE = Set.of(ReadStepFactory.TYPE, "filter", "compute", "select", "lookup",
        "aggregate", "union", "dedupe", "route", "limit", GateStepFactory.TYPE);

    private StepPreview() {
    }

    /** A preview this caller may not have (403), in words. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * What a preview runs: the pipeline cut down to the chosen step and the steps upstream of it,
     * in the order written.
     */
    record Plan(PipelineDefinition definition, String step, String type) {
    }

    /**
     * Cuts {@code yaml} down to {@code step} and everything upstream of it. Refuses (with an
     * {@link IllegalArgumentException}, a 400) YAML that cannot be read, an unknown step, a step
     * that writes, any step upstream that a preview may not run, and problems the reading found in
     * the part that would run. Problems elsewhere in the pipeline do not stop a preview.
     */
    static Plan plan(String yaml, String step) {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("step: the id of the step to preview");
        }
        PipelineDefinition.Parsed parsed = PipelineDefinition.parse(yaml);
        PipelineDefinition d = parsed.definition();
        if (d == null) {
            throw new IllegalArgumentException("The YAML cannot be read" + where(parsed.problems()));
        }
        Map<String, PipelineDefinition.StepDefinition> byId = new LinkedHashMap<>();
        d.steps().forEach(s -> byId.putIfAbsent(s.id(), s));
        String id = step.strip();
        PipelineDefinition.StepDefinition chosen = byId.get(id);
        if (chosen == null) {
            throw new IllegalArgumentException("step: there is no step called '" + clip(id) + "' in this pipeline");
        }
        Set<String> keep = upstreamAndSelf(id, byId);
        List<PipelineDefinition.StepDefinition> sub = d.steps().stream()
            .filter(s -> keep.contains(s.id()) && byId.get(s.id()) == s).toList();
        if (WriteStepFactory.TYPE.equals(chosen.type())) {
            throw new IllegalArgumentException("Step " + id + " writes rows, so it has nothing to show here. Preview "
                + "the step that feeds it instead.");
        }
        for (PipelineDefinition.StepDefinition s : sub) {
            if (s.type() != null && !PREVIEWABLE.contains(s.type())) {
                throw new IllegalArgumentException("Step " + s.id() + " (" + clip(s.type()) + ") cannot run in a "
                    + "preview. A preview runs only reads and the steps that work on rows in memory, never a step "
                    + "that writes or could change anything" + (s.id().equals(id) ? "." : ", and " + id
                    + " needs its rows."));
            }
        }
        List<io.kilnmark.kiln.yaml.Problem> mine = parsed.problems().stream()
            .filter(p -> p.stepId() == null || keep.contains(p.stepId())).toList();
        if (!mine.isEmpty()) {
            throw new IllegalArgumentException("Fix the YAML before previewing " + id + where(mine));
        }
        String name = d.name() == null || d.name().isBlank() ? "preview" : d.name();
        return new Plan(new PipelineDefinition(name, d.description(), sub, d.quality()), id, chosen.type());
    }

    /** {@code step} and every step whose rows can reach it, by the inputs the steps name. */
    static Set<String> upstreamAndSelf(String step, Map<String, PipelineDefinition.StepDefinition> byId) {
        Set<String> seen = new LinkedHashSet<>(List.of(step));
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(step));
        while (!todo.isEmpty()) {
            PipelineDefinition.StepDefinition s = byId.get(todo.poll());
            if (s == null) {
                continue; // an input naming no step: the check reports it
            }
            for (PipelineDefinition.InputRef r : s.inputs()) {
                String from;
                try {
                    from = PortRef.parse(r.from(), "out").step();
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (seen.add(from)) {
                    todo.add(from);
                }
            }
        }
        return seen;
    }

    /**
     * The output port to preview: the one asked for, or {@code out}, or the step's first. Refuses a
     * step with no output port and a port the step does not have.
     */
    static String port(PipelineCompilerPort.Compilation c, String step, String asked) {
        PipelineCompilerPort.StepOutline s = c.steps().stream().filter(x -> x.id().equals(step)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("step: there is no step called '" + clip(step) + "'"));
        if (s.outputs().isEmpty()) {
            throw new IllegalArgumentException("Step " + step + " passes no rows on, so it has nothing to show here.");
        }
        if (asked == null || asked.isBlank()) {
            return s.outputs().contains("out") ? "out" : s.outputs().getFirst();
        }
        String p = asked.strip();
        if (!s.outputs().contains(p)) {
            throw new IllegalArgumentException("port: step " + step + " has no output called '" + clip(p) + "'; its "
                + "outputs are " + String.join(", ", s.outputs()));
        }
        return p;
    }

    /**
     * Steps in the preview that read all their input (or one side of it) before they pass a row on,
     * in plain words, so a slow preview explains itself.
     */
    static List<String> waits(PipelineCompilerPort.Compilation c) {
        List<String> out = new ArrayList<>();
        for (PipelineCompilerPort.StepOutline s : c.steps()) {
            if ("aggregate".equals(s.type())) {
                out.add("Step " + s.id() + " (aggregate) reads every row it is given before it passes any on, so every "
                    + "row before it was read.");
            } else if ("lookup".equals(s.type())) {
                List<String> from = c.edges().stream().filter(e -> e.toStep().equals(s.id())
                    && e.toPort().equals("lookup")).map(PipelineCompilerPort.EdgeOutline::fromStep).distinct().toList();
                out.add("Step " + s.id() + " (lookup) reads all of its lookup side" + (from.isEmpty() ? ""
                    : " (from " + String.join(", ", from) + ")") + " before it passes any rows on.");
            }
        }
        return out;
    }

    // ---- which columns are hidden from the caller ----

    /**
     * Columns whose values a caller may not see, by name (any case), or all of them. A preview's
     * columns may come from columns marked sensitive under another name (a rename, a computed
     * column), so a column is hidden when anything it was made from was.
     */
    record Hidden(Set<String> names, boolean all) {

        static final Hidden NONE = new Hidden(Set.of(), false);
        static final Hidden ALL = new Hidden(Set.of(), true);

        Hidden {
            Set<String> n = new HashSet<>();
            names.forEach(x -> n.add(key(x)));
            names = Set.copyOf(n);
        }

        static Hidden of(Collection<String> names) {
            return new Hidden(new HashSet<>(names), false);
        }

        boolean any() {
            return all || !names.isEmpty();
        }

        boolean hides(String column) {
            return all || column != null && names.contains(key(column));
        }

        /** Whether any of {@code columns} is hidden; null columns means not known, so any hidden one. */
        boolean touches(Collection<String> columns) {
            if (columns == null) {
                return any();
            }
            return columns.stream().anyMatch(this::hides);
        }

        /** The first of {@code columns} that is hidden, for a message; null when none or not known. */
        String first(Collection<String> columns) {
            return columns == null ? null : columns.stream().filter(this::hides).findFirst().orElse(null);
        }

        Hidden plus(Hidden o) {
            if (all || o.all) {
                return ALL;
            }
            Set<String> n = new HashSet<>(names);
            n.addAll(o.names);
            return new Hidden(n, false);
        }
    }

    private static String key(String column) {
        return column.toLowerCase(Locale.ROOT);
    }

    /**
     * The columns of {@code step}'s output that are hidden from the caller, worked out step by step
     * from what each read hides ({@code readHidden}, by read step id). Refuses ({@link Refused})
     * when a step upstream chooses or divides rows by a hidden column (a filter's condition, a
     * route's, a dedupe's key, a lookup's keys, a gate quarantining rows that have hidden columns),
     * since the rows shown would then give its values away even with the column masked.
     */
    static Hidden hidden(PipelineCompilerPort.Compilation c, Map<String, Set<String>> readHidden, String step) {
        Map<String, PipelineCompilerPort.StepOutline> steps = new LinkedHashMap<>();
        c.steps().forEach(s -> steps.putIfAbsent(s.id(), s));
        Map<String, Hidden> out = new HashMap<>();
        for (String id : order(steps.keySet(), c.edges())) {
            PipelineCompilerPort.StepOutline s = steps.get(id);
            Map<String, Hidden> in = new HashMap<>();
            Hidden any = Hidden.NONE;
            for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
                if (e.toStep().equals(id)) {
                    Hidden h = out.getOrDefault(e.fromStep(), Hidden.NONE);
                    in.merge(e.toPort(), h, Hidden::plus);
                    any = any.plus(h);
                }
            }
            Map<String, Object> config = c.configs().getOrDefault(id, Map.of());
            out.put(id, through(s.id(), s.type(), config, in, any, readHidden.getOrDefault(id, Set.of())));
        }
        return out.getOrDefault(step, Hidden.NONE);
    }

    /** What one step hides on its outputs, given what arrives hidden on its inputs. */
    private static Hidden through(String id, String type, Map<String, Object> config, Map<String, Hidden> in,
                                  Hidden any, Set<String> read) {
        Hidden main = in.getOrDefault("in", any);
        switch (type == null ? "" : type) {
            case "read" -> {
                return Hidden.of(read);
            }
            case "compute" -> {
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>(main.names());
                for (Map.Entry<String, String> e : texts(config.get("columns")).entrySet()) {
                    Hidden current = Hidden.of(now);
                    if (current.touches(refs(e.getValue()))) {
                        now.add(key(e.getKey()));
                    } else {
                        now.remove(key(e.getKey())); // replaced by a value made only from visible columns
                    }
                }
                return Hidden.of(now);
            }
            case "select" -> {
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>(main.names());
                texts(config.get("rename")).forEach((from, to) -> {
                    if (main.hides(from)) {
                        now.add(key(to));
                    }
                });
                return Hidden.of(now);
            }
            case "aggregate" -> {
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>();
                for (String g : list(config.get("group_by"))) {
                    if (main.hides(g)) {
                        now.add(g);
                    }
                }
                texts(config.get("values")).forEach((name, expr) -> {
                    if (main.touches(refs(expr))) {
                        now.add(name);
                    }
                });
                return Hidden.of(now);
            }
            case "filter" -> {
                chooses(id, "filter", main, refs(text(config.get("condition"))));
                return main;
            }
            case "route" -> {
                for (String cond : texts(config.get("routes")).values()) {
                    chooses(id, "route", main, refs(cond));
                }
                return main;
            }
            case "dedupe" -> {
                List<String> key = list(config.get("key"));
                chooses(id, "dedupe", main, key.isEmpty() ? null : key);
                return main;
            }
            case "lookup" -> {
                Hidden m = in.getOrDefault("main", Hidden.NONE);
                Hidden l = in.getOrDefault("lookup", Hidden.NONE);
                Map<String, String> on = texts(config.get("on"));
                chooses(id, "lookup", m, new ArrayList<>(on.keySet()));
                chooses(id, "lookup", l, new ArrayList<>(on.values()));
                return m.plus(l);
            }
            case "gate" -> {
                String onFail = text(config.get("on_fail"));
                if ("quarantine".equals(onFail) && main.any()) {
                    throw new Refused("Only an admin can preview this: step " + id + " (gate) sets rows aside by "
                        + "rules that may check columns marked sensitive, so the rows shown could give their values "
                        + "away.");
                }
                return main;
            }
            case "limit", "union" -> {
                return any;
            }
            default -> {
                return any.any() ? Hidden.ALL : Hidden.NONE;
            }
        }
    }

    /** Refuses when a step chooses rows by a hidden column ({@code columns} null: not known). */
    private static void chooses(String id, String type, Hidden in, Collection<String> columns) {
        if (!in.touches(columns)) {
            return;
        }
        String which = in.first(columns);
        throw new Refused("Only an admin can preview this: step " + id + " (" + type + ") chooses rows by "
            + (which == null ? "columns that include one marked sensitive" : which + ", which is marked sensitive")
            + ", so the rows shown would give its values away even with the column masked.");
    }

    /** The columns an expression names, or null when it cannot be read (then it may name any). */
    static Set<String> refs(String expression) {
        if (expression == null) {
            return Set.of();
        }
        try {
            return Expression.parse(expression).columns();
        } catch (ExprException | RuntimeException e) {
            return null;
        }
    }

    /** A setting holding a map of names to text (columns, values, rename, routes, on), as text. */
    private static Map<String, String> texts(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    private static List<String> list(Object value) {
        if (value instanceof List<?> l) {
            return l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    /** The steps in an order where each comes after those feeding it (any left over at the end). */
    private static List<String> order(Collection<String> ids, List<PipelineCompilerPort.EdgeOutline> edges) {
        Map<String, Integer> waiting = new LinkedHashMap<>();
        ids.forEach(id -> waiting.put(id, 0));
        for (PipelineCompilerPort.EdgeOutline e : edges) {
            if (waiting.containsKey(e.toStep()) && waiting.containsKey(e.fromStep())) {
                waiting.merge(e.toStep(), 1, Integer::sum);
            }
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
            for (PipelineCompilerPort.EdgeOutline e : edges) {
                if (e.fromStep().equals(id) && waiting.containsKey(e.toStep())
                    && waiting.merge(e.toStep(), -1, Integer::sum) == 0) {
                    ready.add(e.toStep());
                }
            }
        }
        ids.stream().filter(id -> !out.contains(id)).forEach(out::add);
        return out;
    }

    // ---- running it ----

    /** How a preview's run ended, and the rows its collector kept. */
    record Outcome(RunResult result, Collector rows) {
    }

    /**
     * Runs {@code graph} with the collector reading {@code step.port}, inside {@code memoryBytes} of
     * Arrow memory. Interrupting the calling thread stops every step before this returns.
     */
    static Outcome run(PipelineGraph graph, String step, String port, int rows, long memoryBytes, int capacity,
                       Duration stall) throws InterruptedException {
        Collector collector = new Collector(rows);
        List<GraphStep> steps = new ArrayList<>(graph.steps());
        steps.add(new GraphStep(COLLECTOR, collector, Map.of()));
        List<Edge> edges = new ArrayList<>(graph.edges());
        edges.add(new Edge(new PortRef(step, port), new PortRef(COLLECTOR, "in")));
        RunResult result = new KilnGraphRunner(memoryBytes, capacity, stall)
            .run(new PipelineGraph(graph.name(), steps, edges), event -> {
                // a preview records nothing
            });
        return new Outcome(result, collector);
    }

    /**
     * The last step of a preview: keeps the first rows as text, then finishes, so the steps before
     * it are ended early. Values as a source's preview shows them (cut at 500 characters, bytes as
     * hex), dates and instants written out.
     */
    static final class Collector implements Step {
        private final int limit;
        final List<Field> fields = new ArrayList<>();
        final List<List<String>> rows = new ArrayList<>();
        /** The byte cap stopped the rows short of what was asked for. */
        boolean truncated;
        /** The step's output ended before the rows asked for: these are all its rows. */
        boolean complete;
        private long bytes;

        Collector(int limit) {
            this.limit = limit;
        }

        @Override
        public String type() {
            return "preview";
        }

        @Override
        public void init(StepContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            VectorSchemaRoot batch;
            try {
                batch = context.input().take(context.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Interrupted", e);
            }
            if (batch == null) {
                complete = true;
                return false;
            }
            try (batch) {
                if (fields.isEmpty()) {
                    fields.addAll(batch.getSchema().getFields());
                }
                List<FieldVector> vectors = batch.getFieldVectors();
                for (int r = 0; r < batch.getRowCount() && rows.size() < limit; r++) {
                    List<String> row = new ArrayList<>(vectors.size());
                    long size = 0;
                    for (FieldVector v : vectors) {
                        String t = value(v, r);
                        size += t == null ? 4 : Preview.utf8Length(t);
                        row.add(t);
                    }
                    if (bytes + size > Preview.MAX_BYTES) {
                        truncated = true;
                        return false;
                    }
                    bytes += size;
                    rows.add(row);
                }
            }
            return rows.size() < limit;
        }
    }

    /** One value as text, as people read it. */
    static String value(FieldVector v, int row) {
        if (v.isNull(row)) {
            return null;
        }
        if (v instanceof DateDayVector d) {
            return LocalDate.ofEpochDay(d.get(row)).toString();
        }
        if (v instanceof TimeStampVector t && t.getField().getType() instanceof ArrowType.Timestamp ts
            && ts.getTimezone() != null) {
            long x = t.get(row);
            Instant at = switch (ts.getUnit()) {
                case SECOND -> Instant.ofEpochSecond(x);
                case MILLISECOND -> Instant.ofEpochMilli(x);
                case MICROSECOND -> Instant.ofEpochSecond(Math.floorDiv(x, 1_000_000L), Math.floorMod(x, 1_000_000L) * 1000);
                case NANOSECOND -> Instant.ofEpochSecond(Math.floorDiv(x, 1_000_000_000L), Math.floorMod(x, 1_000_000_000L));
            };
            return at.toString();
        }
        return Preview.text(v.getObject(row));
    }

    private static String where(List<io.kilnmark.kiln.yaml.Problem> problems) {
        if (problems.isEmpty()) {
            return ".";
        }
        List<String> out = new ArrayList<>();
        for (io.kilnmark.kiln.yaml.Problem p : problems.subList(0, Math.min(3, problems.size()))) {
            out.add((p.line() > 0 ? "line " + p.line() + ": " : "") + p.message());
        }
        return ": " + String.join("; ", out) + (problems.size() > 3 ? "; and " + (problems.size() - 3) + " more" : "")
            + ".";
    }

    static String clip(String text) {
        String t = text.replaceAll("\\p{Cntrl}", "?");
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }
}
