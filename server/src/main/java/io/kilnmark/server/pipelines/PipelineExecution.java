package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.plugins.profile.ProfileStep;

/**
 * One run of a compiled pipeline, without the database: finds what it reads and writes, adds a
 * profile step in front of each write that replaces its target (so what is written is profiled in
 * the same pass), runs the graph with the {@link GraphRunner}, stops it when asked, and turns the
 * engine's result into each step's record. {@link PipelineService} stores what it returns.
 */
final class PipelineExecution {

    /** Hidden steps' ids end with this; YAML step ids cannot contain it. */
    static final String PROFILE_SUFFIX = "~profile";
    /** Metadata events kept per run for written assets; profiles of very wide tables beyond this are dropped. */
    static final int MAX_EVENTS = 20_000;

    /** A write step: where it writes, how, and the assets it reads from upstream (its lineage). */
    record WriteTarget(String step, String source, String target, String mode, String urn, List<String> key,
                       List<String> inputs) {

        /** Whether the step writes the whole target, so a profile of its rows describes it. */
        boolean wholeTarget() {
            return mode.equals("create") || mode.equals("replace");
        }
    }

    /**
     * How a run ended. {@code status} is succeeded, failed or cancelled; {@code rowsIn} holds the
     * rows each write step received; {@code events} what was reported about the written assets.
     */
    record Outcome(String status, String error, List<PipelineStore.StepRun> steps, Map<String, Long> rowsIn,
                   List<MetadataEvent> events) {
    }

    private final ObjectMapper json;

    PipelineExecution(ObjectMapper json) {
        this.json = json;
    }

    /** The assets the pipeline reads, by step id, from each read step's settings. */
    static Map<String, String> reads(PipelineCompilerPort.Compilation c) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PipelineCompilerPort.StepOutline s : c.steps()) {
            if (ReadStepFactory.TYPE.equals(s.type())) {
                String urn = ReadStepFactory.urn(c.configs().getOrDefault(s.id(), Map.of()));
                if (urn != null) {
                    out.put(s.id(), urn);
                }
            }
        }
        return out;
    }

    /** Every write step, with the read assets upstream of it. */
    static List<WriteTarget> writes(PipelineCompilerPort.Compilation c) {
        Map<String, String> reads = reads(c);
        List<WriteTarget> out = new ArrayList<>();
        for (PipelineCompilerPort.StepOutline s : c.steps()) {
            if (!WriteStepFactory.TYPE.equals(s.type())) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
            String urn = WriteStepFactory.urn(config);
            String mode = WriteStepFactory.mode(config);
            List<String> key = WriteStepFactory.key(config);
            if (urn == null || mode == null) {
                continue;
            }
            Set<String> inputs = new LinkedHashSet<>();
            for (String up : upstream(s.id(), c.edges())) {
                if (reads.containsKey(up)) {
                    inputs.add(reads.get(up));
                }
            }
            out.add(new WriteTarget(s.id(), PipelineSources.text(config, "source"), WriteStepFactory.target(config),
                mode, urn, key == null ? List.of() : key, List.copyOf(inputs)));
        }
        return out;
    }

    /** Every step whose rows reach {@code step}. */
    static Set<String> upstream(String step, List<PipelineCompilerPort.EdgeOutline> edges) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(step));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (PipelineCompilerPort.EdgeOutline e : edges) {
                if (e.toStep().equals(s) && seen.add(e.fromStep())) {
                    todo.add(e.fromStep());
                }
            }
        }
        return seen;
    }

    /**
     * The graph with a profile step in front of every write that replaces its whole target: rows
     * pass through it on their way to the write, and it reports the columns' profiles for the
     * written asset when the input ends. {@code settings} gives each one's profile settings.
     */
    static PipelineGraph withProfiles(PipelineGraph g, List<WriteTarget> writes,
                                      Function<WriteTarget, Map<String, String>> settings) {
        List<GraphStep> steps = new ArrayList<>(g.steps());
        List<Edge> edges = new ArrayList<>(g.edges());
        for (WriteTarget w : writes) {
            if (!w.wholeTarget()) {
                continue;
            }
            List<Edge> into = edges.stream().filter(e -> e.to().step().equals(w.step())).toList();
            if (into.size() != 1) {
                continue;
            }
            Edge e = into.get(0);
            String id = w.step() + PROFILE_SUFFIX;
            Map<String, String> s = new LinkedHashMap<>(settings.apply(w));
            s.put("asset-urn", w.urn());
            steps.add(new GraphStep(id, new WithSettings(new ProfileStep(), s), new LinkedHashMap<>(s)));
            edges.remove(e);
            edges.add(new Edge(e.from(), new PortRef(id, "in")));
            edges.add(new Edge(new PortRef(id, "out"), e.to()));
        }
        return new PipelineGraph(g.name(), steps, edges);
    }

    /**
     * Runs {@code graph}. {@code cancelled} is someone asking the run to stop; {@code shutdown} the
     * worker stopping. Either interrupts the run within about half a second.
     */
    Outcome run(GraphRunner runner, PipelineGraph graph, List<WriteTarget> writes, RunContext context,
                BooleanSupplier cancelled, BooleanSupplier shutdown) {
        Set<String> written = new HashSet<>();
        writes.forEach(w -> written.add(w.urn()));
        List<MetadataEvent> events = Collections.synchronizedList(new ArrayList<>());
        Thread self = Thread.currentThread();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean stopped = new AtomicBoolean();
        Thread watcher = Thread.ofVirtual().name("kilnmark-pipeline-watch-" + context.runId()).start(() -> {
            while (running.get()) {
                if (cancelled.getAsBoolean() || shutdown.getAsBoolean()) {
                    synchronized (running) {
                        if (running.get()) {
                            stopped.set(true);
                            self.interrupt();
                        }
                    }
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        RunResult result = null;
        boolean interrupted = false;
        try {
            result = runner.run(graph, e -> {
                if (written.contains(e.assetUrn()) && events.size() < MAX_EVENTS) {
                    events.add(e);
                }
            });
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (RuntimeException e) {
            result = new RunResult(graph.name(), RunResult.Status.FAILED, Instant.now(), Instant.now(), List.of(),
                "The engine failed: " + e.getMessage());
        } finally {
            synchronized (running) {
                running.set(false);
            }
            watcher.interrupt();
            joinQuietly(watcher);
            // Clear an interrupt meant for the run, so the run's records can still be written.
            if (Thread.interrupted() && !stopped.get()) {
                interrupted = true;
            }
        }
        if (interrupted || result == null) {
            boolean byPerson = cancelled.getAsBoolean();
            List<PipelineStore.StepRun> none = graph.order() == null ? List.of() : visible(graph.order()).stream()
                .map(id -> new PipelineStore.StepRun(id, graph.step(id).step().type(), position(graph, id), null, null,
                    "{}", null, null, null)).toList();
            return new Outcome(byPerson ? "cancelled" : "failed", byPerson ? "Cancelled while it was running"
                : "The worker running this pipeline was stopped before it finished. Run it again; check what its "
                + "writes had written first.", none, Map.of(), List.of());
        }
        Map<String, Long> rowsIn = new HashMap<>();
        List<PipelineStore.StepRun> steps = steps(graph, result, context, rowsIn);
        String error = result.succeeded() ? null : context.scrub(result.error());
        return new Outcome(result.succeeded() ? "succeeded" : "failed", error, steps, rowsIn, List.copyOf(events));
    }

    private static void joinQuietly(Thread t) {
        boolean again = false;
        while (t.isAlive()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                again = true;
            }
        }
        if (again) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> visible(List<String> ids) {
        return ids.stream().filter(id -> !id.endsWith(PROFILE_SUFFIX)).toList();
    }

    private static int position(PipelineGraph g, String id) {
        List<String> order = g.order();
        int i = order == null ? -1 : visible(order).indexOf(id);
        return Math.max(0, i);
    }

    /**
     * Rows on each edge, as the graph runner counted them ({@link RunResult#edges()}). A runner that
     * reports none leaves each edge carrying its step's rows out when the step has one output port
     * connected, and unknown (null) otherwise.
     */
    static Map<Edge, Long> rowsByEdge(PipelineGraph graph, RunResult result) {
        Map<Edge, Long> out = new LinkedHashMap<>();
        if (!result.edges().isEmpty()) {
            Map<String, Long> counted = new HashMap<>();
            for (RunResult.EdgeResult e : result.edges()) {
                counted.put(e.fromStep() + "." + e.fromPort() + ">" + e.toStep() + "." + e.toPort(), e.rows());
            }
            for (Edge e : graph.edges()) {
                out.put(e, counted.get(e.from() + ">" + e.to()));
            }
            return out;
        }
        Map<String, RunResult.StepResult> byName = new HashMap<>();
        result.steps().forEach(s -> byName.put(s.name(), s));
        for (Edge e : graph.edges()) {
            RunResult.StepResult from = byName.get(e.from().step());
            long ports = graph.outOf(e.from().step()).stream().map(x -> x.from().port()).distinct().count();
            out.put(e, from != null && ports == 1 ? from.rowsOut() : null);
        }
        return out;
    }

    private List<PipelineStore.StepRun> steps(PipelineGraph graph, RunResult result, RunContext context,
                                              Map<String, Long> rowsIn) {
        Map<String, RunResult.StepResult> byName = new HashMap<>();
        result.steps().forEach(s -> byName.put(s.name(), s));
        Map<Edge, Long> edgeRows = rowsByEdge(graph, result);
        List<String> order = graph.order() == null ? graph.steps().stream().map(GraphStep::id).toList() : graph.order();
        List<PipelineStore.StepRun> out = new ArrayList<>();
        int position = 0;
        for (String id : visible(order)) {
            GraphStep step = graph.step(id);
            RunResult.StepResult r = byName.get(id);
            // A write's rows arrive through its hidden profile step: count them as the write's input.
            Map<String, Long> in = new LinkedHashMap<>();
            Map<String, Long> outs = new LinkedHashMap<>();
            if (r != null && (!r.rowsInByPort().isEmpty() || !r.rowsOutByPort().isEmpty())) {
                in.putAll(r.rowsInByPort());
                outs.putAll(r.rowsOutByPort());
            } else {
                for (Edge e : graph.into(id)) {
                    Long n = edgeRows.get(e);
                    in.merge(e.to().port(), n == null ? -1 : n, (a, b) -> a < 0 || b < 0 ? -1 : a + b);
                }
                for (Edge e : graph.outOf(id)) {
                    outs.putIfAbsent(e.from().port(), edgeRows.get(e) == null ? -1 : edgeRows.get(e));
                }
            }
            Long rowsInTotal = in.isEmpty() ? null : in.values().stream().anyMatch(v -> v < 0) ? null
                : in.values().stream().mapToLong(Long::longValue).sum();
            // A step with nothing connected after it (a write) has no rows out: what it wrote is its target.
            Long rowsOut = r == null || graph.outOf(id).isEmpty() ? null : r.rowsOut();
            String error = r == null ? null : r.error();
            RunResult.StepResult profile = byName.get(id + PROFILE_SUFFIX);
            if (error == null && profile != null && profile.error() != null) {
                error = "Profiling what this step writes failed: " + profile.error();
            }
            long peak = (r == null ? 0 : r.peakMemoryBytes()) + (profile == null ? 0 : profile.peakMemoryBytes());
            if (rowsInTotal != null) {
                rowsIn.put(id, rowsInTotal);
            }
            ObjectNode ports = json.createObjectNode();
            ObjectNode inNode = ports.putObject("in");
            in.forEach((port, n) -> {
                if (n < 0) {
                    inNode.putNull(port);
                } else {
                    inNode.put(port, n);
                }
            });
            ObjectNode outNode = ports.putObject("out");
            outs.forEach((port, n) -> {
                if (n < 0) {
                    outNode.putNull(port);
                } else {
                    outNode.put(port, n);
                }
            });
            out.add(new PipelineStore.StepRun(id, step.step().type(), position++, rowsInTotal, rowsOut,
                ports.toString(), r == null ? null : peak, null, context.scrub(error)));
        }
        return out;
    }
}
