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
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.connect.Preview;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphNode;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.yaml.PipelineDefinition;

/**
 * Preview at a node (docs/design/pipelines.md, "Preview at a node"), without the server around it:
 * which nodes run (the chosen node and every node upstream of it, nothing else), which nodes may run
 * in a preview at all, which columns must be masked for the caller, and the in-memory collector that
 * takes the first rows from the chosen output and then finishes, so the engine's "ends early" stops
 * the nodes before it. {@link PipelinePreviews} adds the caller, the limits and the masking.
 */
final class NodePreview {

    /** Rows shown when the request does not say. */
    static final int DEFAULT_ROWS = 100;
    /** The most rows a preview shows, as a source's preview. */
    static final int MAX_ROWS = Preview.MAX_ROWS;
    /** The collector's id: it starts with a capital letter, so it can never be one of the pipeline's own. */
    static final String COLLECTOR = "Preview";

    /**
     * The node types a preview may run: reads and the nodes that work on rows in memory. Anything
     * else (a write, or a node type added later) may write or change something, and is refused.
     */
    static final Set<String> PREVIEWABLE = Set.of(ReadNodeFactory.TYPE, "filter", "compute", "select", "lookup",
        "aggregate", "union", "dedupe", "route", "limit", GateNodeFactory.TYPE);

    private NodePreview() {
    }

    /** A preview this caller may not have (403), in words. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * What a preview runs: the pipeline cut down to the chosen node and the nodes upstream of it,
     * in the order written.
     */
    record Plan(PipelineDefinition definition, String node, String type) {
    }

    /**
     * How big a preview may be: nodes in all and read nodes among them, and the memory its nodes
     * holding rows in their own state (aggregate, dedupe, lookup, union) may count between them.
     */
    record Caps(int maxNodes, int maxReads, long memoryMb) {
        static final Caps DEFAULT = new Caps(50, 10, 256);
    }

    /** Nodes that hold rows in state of their own, bounded by their {@code memory_limit_mb}. */
    static final Set<String> STATEFUL = Set.of("aggregate", "dedupe", "lookup", "union");

    static Plan plan(String yaml, String node) {
        return plan(yaml, node, Caps.DEFAULT);
    }

    /**
     * Cuts {@code yaml} down to {@code node} and everything upstream of it. Refuses (with an
     * {@link IllegalArgumentException}, a 400) YAML that cannot be read, an unknown node, a node
     * that writes, any node upstream that a preview may not run, and problems the reading found in
     * the part that would run. Problems elsewhere in the pipeline do not stop a preview.
     */
    static Plan plan(String yaml, String node, Caps caps) {
        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("node: the id of the node to preview");
        }
        PipelineDefinition.Parsed parsed = PipelineDefinition.parse(yaml);
        PipelineDefinition d = parsed.definition();
        if (d == null) {
            throw new IllegalArgumentException("The YAML cannot be read" + where(parsed.problems()));
        }
        Map<String, PipelineDefinition.NodeDefinition> byId = new LinkedHashMap<>();
        d.nodes().forEach(s -> byId.putIfAbsent(s.id(), s));
        String id = node.strip();
        PipelineDefinition.NodeDefinition chosen = byId.get(id);
        if (chosen == null) {
            throw new IllegalArgumentException("node: there is no node called '" + clip(id) + "' in this pipeline");
        }
        Set<String> keep = upstreamAndSelf(id, byId);
        List<PipelineDefinition.NodeDefinition> sub = d.nodes().stream()
            .filter(s -> keep.contains(s.id()) && byId.get(s.id()) == s).toList();
        if (WriteNodeFactory.TYPE.equals(chosen.type())) {
            throw new IllegalArgumentException("Node " + id + " writes rows, so it has nothing to show here. Preview "
                + "the node that feeds it instead.");
        }
        for (PipelineDefinition.NodeDefinition s : sub) {
            if (s.type() == null) {
                throw new IllegalArgumentException("Node " + s.id() + " needs a type before " + id + " can be previewed.");
            }
            if (!PREVIEWABLE.contains(s.type())) {
                throw new IllegalArgumentException("Node " + s.id() + " (" + clip(s.type()) + ") cannot run in a "
                    + "preview. A preview runs only reads and the nodes that work on rows in memory, never a node "
                    + "that writes or could change anything" + (s.id().equals(id) ? "." : ", and " + id
                    + " needs its rows."));
            }
        }
        List<io.kilnmark.kiln.yaml.Problem> mine = parsed.problems().stream()
            .filter(p -> p.nodeId() == null || keep.contains(p.nodeId())).toList();
        if (!mine.isEmpty()) {
            throw new IllegalArgumentException("Fix the YAML before previewing " + id + where(mine));
        }
        if (sub.size() > caps.maxNodes()) {
            throw new IllegalArgumentException("A preview runs at most " + caps.maxNodes() + " nodes, and " + id
                + " needs " + sub.size() + ". Preview a node nearer the start.");
        }
        long reads = sub.stream().filter(s -> ReadNodeFactory.TYPE.equals(s.type())).count();
        if (reads > caps.maxReads()) {
            throw new IllegalArgumentException("A preview reads from at most " + caps.maxReads() + " read nodes, and "
                + id + " needs " + reads + ". Preview a node nearer the start.");
        }
        String name = d.name() == null || d.name().isBlank() ? "preview" : d.name();
        return new Plan(new PipelineDefinition(name, d.description(), clamp(sub, caps.memoryMb()), d.quality(),
            d.parameters()), id,
            chosen.type());
    }

    /**
     * The nodes with the memory limit of each node holding rows in its own state lowered to its
     * share of the preview's: that state is counted against {@code memory_limit_mb}, which a
     * pipeline may set far higher than a preview may hold.
     */
    static List<PipelineDefinition.NodeDefinition> clamp(List<PipelineDefinition.NodeDefinition> nodes, long memoryMb) {
        long stateful = nodes.stream().filter(s -> STATEFUL.contains(s.type())).count();
        if (stateful == 0) {
            return nodes;
        }
        long share = Math.max(1, memoryMb / stateful);
        List<PipelineDefinition.NodeDefinition> out = new ArrayList<>();
        for (PipelineDefinition.NodeDefinition s : nodes) {
            if (!STATEFUL.contains(s.type())) {
                out.add(s);
                continue;
            }
            // Always written, whatever was: a lower whole number is kept, anything else (a decimal, text,
            // a number too large) becomes the share.
            Object asked = s.config().get("memory_limit_mb");
            long limit = share;
            if (asked instanceof Number n) {
                try {
                    long v = new java.math.BigDecimal(n.toString()).longValueExact();
                    if (v > 0 && v < share) {
                        limit = v;
                    }
                } catch (ArithmeticException | NumberFormatException e) {
                    // not a whole number: the share
                }
            }
            Map<String, Object> config = new LinkedHashMap<>(s.config());
            config.put("memory_limit_mb", limit);
            Map<String, Object> text = new LinkedHashMap<>(s.configText());
            text.put("memory_limit_mb", Long.toString(limit));
            out.add(new PipelineDefinition.NodeDefinition(s.id(), s.type(), s.inputs(), config, s.position(), s.keys(),
                text));
        }
        return out;
    }

    /** {@code node} and every node whose rows can reach it, by the inputs the nodes name. */
    static Set<String> upstreamAndSelf(String node, Map<String, PipelineDefinition.NodeDefinition> byId) {
        Set<String> seen = new LinkedHashSet<>(List.of(node));
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(node));
        while (!todo.isEmpty()) {
            PipelineDefinition.NodeDefinition s = byId.get(todo.poll());
            if (s == null) {
                continue; // an input naming no node: the check reports it
            }
            for (PipelineDefinition.InputRef r : s.inputs()) {
                String from;
                try {
                    from = PortRef.parse(r.from(), "out").node();
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
     * The output port to preview: the one asked for, or {@code out}, or the node's first. Refuses a
     * node with no output port and a port the node does not have.
     */
    static String port(PipelineCompilerPort.Compilation c, String node, String asked) {
        PipelineCompilerPort.NodeOutline s = c.nodes().stream().filter(x -> x.id().equals(node)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("node: there is no node called '" + clip(node) + "'"));
        if (s.outputs().isEmpty()) {
            throw new IllegalArgumentException("Node " + node + " passes no rows on, so it has nothing to show here.");
        }
        if (asked == null || asked.isBlank()) {
            return s.outputs().contains("out") ? "out" : s.outputs().getFirst();
        }
        String p = asked.strip();
        if (!s.outputs().contains(p)) {
            throw new IllegalArgumentException("port: node " + node + " has no output called '" + clip(p) + "'; its "
                + "outputs are " + String.join(", ", s.outputs()));
        }
        return p;
    }

    /**
     * Nodes in the preview that read all their input (or one side of it) before they pass a row on,
     * in plain words, so a slow preview explains itself.
     */
    static List<String> waits(PipelineCompilerPort.Compilation c) {
        List<String> out = new ArrayList<>();
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if ("aggregate".equals(s.type())) {
                out.add("Node " + s.id() + " (aggregate) reads every row it is given before it passes any on, so every "
                    + "row before it was read.");
            } else if ("lookup".equals(s.type())) {
                List<String> from = c.edges().stream().filter(e -> e.toNode().equals(s.id())
                    && e.toPort().equals("lookup")).map(PipelineCompilerPort.EdgeOutline::fromNode).distinct().toList();
                out.add("Node " + s.id() + " (lookup) reads all of its lookup side" + (from.isEmpty() ? ""
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
            // a carried "every column" mark hides every column
            return names.contains(io.kilnmark.server.quality.QualityStore.EVERY_COLUMN) ? ALL
                : new Hidden(new HashSet<>(names), false);
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
     * The columns of {@code node}'s output that are hidden from the caller, worked out node by node
     * from what each read hides ({@code readHidden}, by read node id); {@code gateFields} holds, for
     * each gate, the columns its rules check (inline and the asset's), or null when not known.
     *
     * <p>The rule (docs/design/pipelines.md, "Preview at a node"): a hidden column may pass through
     * a preview untouched, and is masked where it leaves; no node may look inside one. Refuses
     * ({@link Refused}) when any node upstream names a hidden column in an expression (compute,
     * filter, route, aggregate values), groups, deduplicates or matches by one (aggregate
     * {@code group_by}, dedupe {@code key} or whole rows, lookup {@code on}), changes its type
     * ({@code select}'s {@code cast}) or checks it with a rule (a gate, whatever it does on failure).
     * Each of those could show a value through the rows kept, a computed value, or simply whether
     * the preview succeeds or fails.
     *
     * <p>Names are compared ignoring case, and a column once hidden stays hidden: a computed column
     * that shares a hidden column's name, in any case, is masked too, so it can never un-hide one.
     */
    static Hidden hidden(PipelineCompilerPort.Compilation c, Map<String, Set<String>> readHidden, String node) {
        return hidden(c, readHidden, Map.of(), node);
    }

    static Hidden hidden(PipelineCompilerPort.Compilation c, Map<String, Set<String>> readHidden,
                         Map<String, Set<String>> gateFields, String node) {
        Map<String, PipelineCompilerPort.NodeOutline> nodes = new LinkedHashMap<>();
        c.nodes().forEach(s -> nodes.putIfAbsent(s.id(), s));
        Map<String, Hidden> out = new HashMap<>();
        for (String id : order(nodes.keySet(), c.edges())) {
            PipelineCompilerPort.NodeOutline s = nodes.get(id);
            Map<String, Hidden> in = new HashMap<>();
            Hidden any = Hidden.NONE;
            for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
                if (e.toNode().equals(id)) {
                    Hidden h = out.getOrDefault(e.fromNode(), Hidden.NONE);
                    in.merge(e.toPort(), h, Hidden::plus);
                    any = any.plus(h);
                }
            }
            Map<String, Object> config = c.configs().getOrDefault(id, Map.of());
            if ("union".equals(s.type())) {
                unionTypes(id, c.edges(), nodes, any);
            }
            Set<String> gate = gateFields.containsKey(id) ? gateFields.get(id) : null;
            out.put(id, through(s.id(), s.type(), config, in, any, readHidden.getOrDefault(id, Set.of()), gate));
        }
        return out.getOrDefault(node, Hidden.NONE);
    }

    /**
     * The columns of {@code node}'s output, or arriving at it for a write, made from columns marked
     * sensitive, worked out from what each read reads marked ({@code readMarks}, by read node id). For
     * marks that follow the data (a write carries them to what it writes): unlike {@link #hidden} it
     * never refuses. A column passed on, renamed or looked up keeps its mark; a value worked out from
     * a marked column (a computed column, an aggregate's value or group) is marked; and after a node
     * whose columns cannot be followed (one of a kind this does not know), everything is, if anything
     * marked went into it.
     */
    static Hidden marked(PipelineCompilerPort.Compilation c, Map<String, Set<String>> readMarks, String node) {
        Map<String, PipelineCompilerPort.NodeOutline> nodes = new LinkedHashMap<>();
        c.nodes().forEach(s -> nodes.putIfAbsent(s.id(), s));
        Map<String, Hidden> out = new HashMap<>();
        for (String id : order(nodes.keySet(), c.edges())) {
            Map<String, Hidden> in = new HashMap<>();
            Hidden any = Hidden.NONE;
            for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
                if (e.toNode().equals(id)) {
                    Hidden h = out.getOrDefault(e.fromNode(), Hidden.NONE);
                    in.merge(e.toPort(), h, Hidden::plus);
                    any = any.plus(h);
                }
            }
            out.put(id, marking(nodes.get(id).type(), c.configs().getOrDefault(id, Map.of()), in, any,
                readMarks.getOrDefault(id, Set.of())));
        }
        return out.getOrDefault(node, Hidden.NONE);
    }

    /** What one node passes on marked, given what arrives marked. */
    private static Hidden marking(String type, Map<String, Object> config, Map<String, Hidden> in, Hidden any,
                                  Set<String> read) {
        Hidden main = in.getOrDefault("in", any);
        switch (type == null ? "" : type) {
            case "read" -> {
                return Hidden.of(read);
            }
            case "compute" -> {
                Set<String> now = new HashSet<>(main.names());
                texts(config.get("columns")).forEach((name, expr) -> {
                    if (main.touches(refs(expr))) {
                        now.add(name);
                    }
                });
                return main.all() ? Hidden.ALL : Hidden.of(now);
            }
            case "select" -> {
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>(main.names());
                texts(config.get("rename")).forEach((from, to) -> {
                    if (main.hides(from) && to != null) {
                        now.add(to);
                    }
                });
                return Hidden.of(now);
            }
            case "aggregate" -> {
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>();
                list(config.get("group_by")).stream().filter(main::hides).forEach(now::add);
                texts(config.get("values")).forEach((name, expr) -> {
                    if (main.touches(refs(expr))) {
                        now.add(name);
                    }
                });
                return Hidden.of(now);
            }
            case "lookup" -> {
                return in.getOrDefault("main", Hidden.NONE).plus(in.getOrDefault("lookup", Hidden.NONE));
            }
            case "filter", "route", "dedupe", "gate", "limit", "union", "write" -> {
                return any;
            }
            default -> {
                return any.any() ? Hidden.ALL : Hidden.NONE;
            }
        }
    }

    /** What one node hides on its outputs, given what arrives hidden; refuses a node looking inside one. */
    private static Hidden through(String id, String type, Map<String, Object> config, Map<String, Hidden> in,
                                  Hidden any, Set<String> read, Set<String> gateFields) {
        Hidden main = in.getOrDefault("in", any);
        String t = type == null ? "" : type;
        switch (t) {
            case "read" -> {
                return Hidden.of(read);
            }
            case "compute" -> {
                Map<String, String> columns = texts(config.get("columns"));
                for (String expr : columns.values()) {
                    uses(id, t, main, refs(expr), "works out a value from");
                }
                // Nothing computed may name a hidden column, so a hidden column stays hidden; a new
                // column sharing its name in another case is masked too.
                return main;
            }
            case "select" -> {
                Map<String, String> casts = texts(config.get("cast"));
                uses(id, t, main, new ArrayList<>(casts.keySet()), "changes the type of");
                if (main.all()) {
                    return Hidden.ALL;
                }
                Set<String> now = new HashSet<>(main.names());
                texts(config.get("rename")).forEach((from, to) -> {
                    if (main.hides(from) && to != null) {
                        now.add(to);
                    }
                });
                return Hidden.of(now);
            }
            case "aggregate" -> {
                uses(id, t, main, list(config.get("group_by")), "groups rows by");
                for (String expr : texts(config.get("values")).values()) {
                    uses(id, t, main, refs(expr), "works out a value from");
                }
                return Hidden.NONE; // its columns are the group's and the values, none from a hidden column
            }
            case "filter" -> {
                uses(id, t, main, refs(text(config.get("condition"))), "chooses rows by");
                return main;
            }
            case "route" -> {
                for (String cond : texts(config.get("routes")).values()) {
                    uses(id, t, main, refs(cond), "chooses rows by");
                }
                return main;
            }
            case "dedupe" -> {
                List<String> key = list(config.get("key"));
                uses(id, t, main, key.isEmpty() ? null : key, key.isEmpty() ? "compares whole rows, which include"
                    : "compares rows by");
                return main;
            }
            case "lookup" -> {
                Hidden m = in.getOrDefault("main", Hidden.NONE);
                Hidden l = in.getOrDefault("lookup", Hidden.NONE);
                Map<String, String> on = texts(config.get("on"));
                uses(id, t, m, new ArrayList<>(on.keySet()), "matches rows by");
                uses(id, t, l, new ArrayList<>(on.values()), "matches rows by");
                return m.plus(l);
            }
            case "gate" -> {
                uses(id, t, main, gateFields == null ? null : gateFields, "checks rules on");
                return main;
            }
            case "limit", "union" -> {
                return any;
            }
            default -> {
                if (any.any()) {
                    throw new Refused("Only a designer or an admin can preview this: node " + id + " (" + clip(t) + ") may use "
                        + "columns marked sensitive.");
                }
                return Hidden.NONE;
            }
        }
    }

    /**
     * A union converts a column whose type differs between its inputs (whole numbers to decimals,
     * one decimal scale to another), and a conversion can fail on a value. So for a hidden column
     * the inputs must agree on its kind, and a decimal (whose precision and scale the kind does not
     * say) may come from one input only; an input whose columns are not known before the run counts
     * as disagreeing.
     */
    private static void unionTypes(String id, List<PipelineCompilerPort.EdgeOutline> edges,
                                   Map<String, PipelineCompilerPort.NodeOutline> nodes, Hidden hidden) {
        if (!hidden.any()) {
            return;
        }
        List<Map<String, String>> inputs = new ArrayList<>();
        for (PipelineCompilerPort.EdgeOutline e : edges) {
            if (!e.toNode().equals(id)) {
                continue;
            }
            PipelineCompilerPort.NodeOutline from = nodes.get(e.fromNode());
            List<PipelineCompilerPort.Column> cols = from == null ? null : from.columns().get(e.fromPort());
            if (cols == null) {
                throw new Refused("Only a designer or an admin can preview this: node " + id + " (union) combines rows whose "
                    + "columns are not known before the preview runs, and it may convert columns marked sensitive, "
                    + "which could give their values away.");
            }
            Map<String, String> kinds = new HashMap<>();
            cols.forEach(col -> kinds.putIfAbsent(key(col.name()), col.type()));
            inputs.add(kinds);
        }
        Set<String> names = new LinkedHashSet<>();
        inputs.forEach(m -> m.keySet().stream().filter(hidden::hides).forEach(names::add));
        for (String name : names) {
            List<String> kinds = inputs.stream().map(m -> m.get(name)).filter(java.util.Objects::nonNull).toList();
            boolean differ = kinds.stream().distinct().count() > 1;
            boolean decimals = kinds.contains("decimal") && kinds.size() > 1;
            if (differ || decimals) {
                throw new Refused("Only a designer or an admin can preview this: node " + id + " (union) converts " + name
                    + ", which is marked sensitive, between its inputs' types (" + String.join(", ", kinds)
                    + "), and a conversion can fail on a value, which could give it away.");
            }
        }
    }

    /**
     * Refuses when a node uses a hidden column ({@code columns} null: not known, so any hidden
     * column counts). {@code doing} says how, in words.
     */
    private static void uses(String id, String type, Hidden in, Collection<String> columns, String doing) {
        if (!in.touches(columns)) {
            return;
        }
        String which = in.first(columns);
        throw new Refused("Only a designer or an admin can preview this: node " + id + " (" + type + ") " + doing + " "
            + (which == null ? "columns that may include one marked sensitive" : which + ", which is marked sensitive")
            + ". In a preview, columns marked sensitive can only pass through unchanged; anything that looks at "
            + "their values (a condition, a calculation, a key, a rule) could give them away, even by whether the "
            + "preview works.");
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

    /** The nodes in an order where each comes after those feeding it (any left over at the end). */
    private static List<String> order(Collection<String> ids, List<PipelineCompilerPort.EdgeOutline> edges) {
        Map<String, Integer> waiting = new LinkedHashMap<>();
        ids.forEach(id -> waiting.put(id, 0));
        for (PipelineCompilerPort.EdgeOutline e : edges) {
            if (waiting.containsKey(e.toNode()) && waiting.containsKey(e.fromNode())) {
                waiting.merge(e.toNode(), 1, Integer::sum);
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
                if (e.fromNode().equals(id) && waiting.containsKey(e.toNode())
                    && waiting.merge(e.toNode(), -1, Integer::sum) == 0) {
                    ready.add(e.toNode());
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
     * Runs {@code graph} with the collector reading {@code node.port}, inside {@code memoryBytes} of
     * Arrow memory. Interrupting the calling thread stops every node before this returns.
     */
    static Outcome run(PipelineGraph graph, String node, String port, int rows, long memoryBytes, int capacity,
                       Duration stall) throws InterruptedException {
        return run(graph, node, port, rows, Hidden.NONE, memoryBytes, capacity, stall, null, null);
    }

    /**
     * As {@link #run(PipelineGraph, String, String, int, long, int, Duration)}, masking the
     * {@code hidden} columns as the rows are taken (so the size cap counts what is shown), and
     * waiting at most {@code stopWait} for the nodes to stop when interrupted (null: until they do).
     */
    static Outcome run(PipelineGraph graph, String node, String port, int rows, Hidden hidden, long memoryBytes,
                       int capacity, Duration stall, Duration stopWait,
                       java.util.concurrent.atomic.AtomicReference<KilnGraphRunner> runnerOut)
        throws InterruptedException {
        Collector collector = new Collector(rows, hidden);
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        nodes.add(new GraphNode(COLLECTOR, collector, Map.of()));
        List<Edge> edges = new ArrayList<>(graph.edges());
        edges.add(new Edge(new PortRef(node, port), new PortRef(COLLECTOR, "in")));
        KilnGraphRunner runner = new KilnGraphRunner(memoryBytes, capacity, stall,
            KilnGraphRunner.DEFAULT_PROGRESS_EVERY, stopWait);
        if (runnerOut != null) {
            runnerOut.set(runner);
        }
        RunResult result = runner
            .run(new PipelineGraph(graph.name(), nodes, edges), event -> {
                // a preview records nothing
            });
        return new Outcome(result, collector);
    }

    /**
     * The last node of a preview: keeps the first rows as text, then finishes, so the nodes before
     * it are ended early. Values as a source's preview shows them (cut at 500 characters, bytes as
     * hex), dates and instants written out.
     */
    static final class Collector implements Node {
        private final int limit;
        private final Hidden hidden;
        /** For each column, whether its values are masked. */
        private boolean[] mask;
        final List<Field> fields = new ArrayList<>();
        final List<List<String>> rows = new ArrayList<>();
        /** The byte cap stopped the rows short of what was asked for. */
        boolean truncated;
        /** The node's output ended before the rows asked for: these are all its rows. */
        boolean complete;
        private long bytes;

        Collector(int limit) {
            this(limit, Hidden.NONE);
        }

        Collector(int limit, Hidden hidden) {
            this.limit = limit;
            this.hidden = hidden == null ? Hidden.NONE : hidden;
        }

        /** Whether column {@code index} (called {@code name}) was masked. */
        boolean masked(int index, String name) {
            return mask != null && index < mask.length ? mask[index] : hidden.hides(name);
        }

        @Override
        public String type() {
            return "preview";
        }

        @Override
        public void init(NodeContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            VectorSchemaRoot batch;
            try {
                batch = context.input().take(context.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
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
                if (mask == null || mask.length != vectors.size()) {
                    mask = new boolean[vectors.size()];
                    for (int i = 0; i < vectors.size(); i++) {
                        mask[i] = hidden.hides(vectors.get(i).getField().getName());
                    }
                }
                for (int r = 0; r < batch.getRowCount() && rows.size() < limit; r++) {
                    List<String> row = new ArrayList<>(vectors.size());
                    long size = 0;
                    for (int i = 0; i < vectors.size(); i++) {
                        FieldVector v = vectors.get(i);
                        // Masked here, so nothing hidden is held and the size cap counts what is shown.
                        String t = mask[i] ? (v.isNull(r) ? null : io.kilnmark.server.quality.Masking.MASK)
                            : value(v, r);
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
