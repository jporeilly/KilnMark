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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.GraphNode;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.graph.RunListener;

/**
 * One run of a compiled pipeline, without the database: finds what it reads and writes, runs the
 * graph with the {@link GraphRunner} (writers profile what they write themselves), stops it when
 * asked, keeps what the writers report about the assets they wrote, and turns the engine's result
 * into each node's record. {@link PipelineService} stores what it returns.
 */
final class PipelineExecution {

    /** Metadata events kept per run for written assets; profiles of very wide tables beyond this are dropped. */
    static final int MAX_EVENTS = 20_000;

    /** A write node: where it writes, how, and the assets it reads from upstream (its lineage). */
    record WriteTarget(String node, String source, String target, String mode, String urn, List<String> key,
                       List<String> inputs) {

        /** This write under another URN: the one its connector worked out when the node was made. */
        WriteTarget withUrn(String other) {
            return new WriteTarget(node, source, target, mode, other, key, inputs);
        }

        /** Whether the node writes the whole target, so a profile of its rows describes it. */
        boolean wholeTarget() {
            return mode.equals("create") || mode.equals("replace");
        }
    }

    /**
     * How a run ended. {@code status} is succeeded, failed or cancelled; {@code rowsIn} holds the
     * rows each write node received; {@code events} what the writers reported about the written
     * assets (docs/design/pipelines.md, section 5, "What a write reports").
     */
    record Outcome(String status, String error, List<PipelineStore.NodeRun> nodes, Map<String, Long> rowsIn,
                   List<MetadataEvent> events) {
    }

    /** Closes the nodes of a graph that will not run. */
    static void closeNodes(PipelineGraph graph) {
        for (GraphNode s : graph.nodes()) {
            try {
                s.node().close();
            } catch (RuntimeException e) {
                // never started; nothing to report
            }
        }
    }

    private final ObjectMapper json;

    PipelineExecution(ObjectMapper json) {
        this.json = json;
    }

    /** The assets the pipeline reads, by node id, as the settings name them (before a run resolves them). */
    static Map<String, String> reads(PipelineCompilerPort.Compilation c) {
        return reads(c, null);
    }

    /**
     * The assets the pipeline reads, by node id: under the URN each read node resolved and the
     * catalogue records (a file's {@code #} and {@code %} escaped, say) when {@code run} made the
     * nodes, else as the settings name them.
     */
    static Map<String, String> reads(PipelineCompilerPort.Compilation c, RunContext run) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (ReadNodeFactory.TYPE.equals(s.type())) {
                Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
                String urn = ReadNodeFactory.urn(config);
                if (run != null) {
                    urn = run.readUrn(PipelineSources.text(config, "source"), PipelineSources.text(config, "asset"), urn);
                }
                if (urn != null) {
                    out.put(s.id(), urn);
                }
            }
        }
        return out;
    }

    /**
     * The tables each read that runs a query read, by node id, as {@code run} noted them when the
     * nodes were made; empty without a run (ADR 0028).
     */
    static Map<String, List<String>> queryReads(PipelineCompilerPort.Compilation c, RunContext run) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (run == null) {
            return out;
        }
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
            if (ReadNodeFactory.TYPE.equals(s.type()) && ReadNodeFactory.isQuery(config)) {
                out.put(s.id(), run.queryReadUrns(PipelineSources.text(config, "source"),
                    ReadNodeFactory.query(config)));
            }
        }
        return out;
    }

    /** Every write node, with the read assets upstream of it (as the settings name them). */
    static List<WriteTarget> writes(PipelineCompilerPort.Compilation c) {
        return writes(c, null);
    }

    /**
     * Every write node, with the read assets upstream of it; with {@code run}, each under the URN
     * its connector resolved, read and written alike.
     */
    static List<WriteTarget> writes(PipelineCompilerPort.Compilation c, RunContext run) {
        Map<String, String> reads = reads(c, run);
        Map<String, List<String>> queried = queryReads(c, run);
        List<WriteTarget> out = new ArrayList<>();
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (!WriteNodeFactory.TYPE.equals(s.type())) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
            String urn = WriteNodeFactory.urn(config);
            String mode = WriteNodeFactory.mode(config);
            List<String> key = WriteNodeFactory.key(config);
            if (urn == null || mode == null) {
                continue;
            }
            Set<String> inputs = new LinkedHashSet<>();
            for (String up : upstream(s.id(), c.edges())) {
                if (reads.containsKey(up)) {
                    inputs.add(reads.get(up));
                }
                inputs.addAll(queried.getOrDefault(up, List.of()));
            }
            out.add(new WriteTarget(s.id(), PipelineSources.text(config, "source"), WriteNodeFactory.target(config),
                mode, urn, key == null ? List.of() : key, List.copyOf(inputs)));
        }
        return run == null ? out
            : out.stream().map(w -> w.withUrn(run.writtenUrn(w.source(), w.target(), w.urn()))).toList();
    }

    /**
     * Whether an event comes from a writer: a writer's row count says {@code written=true}; a
     * reader's row count for the same asset does not. Writers alone report schemas and profiles in
     * a pipeline.
     */
    static boolean writerEvent(MetadataEvent e) {
        return e.kind() != MetadataEvent.Kind.ROW_COUNT || "true".equals(e.attributes().get("written"));
    }

    /** Every node whose rows reach {@code node}. */
    static Set<String> upstream(String node, List<PipelineCompilerPort.EdgeOutline> edges) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(node));
        while (!todo.isEmpty()) {
            String s = todo.poll();
            for (PipelineCompilerPort.EdgeOutline e : edges) {
                if (e.toNode().equals(s) && seen.add(e.fromNode())) {
                    todo.add(e.fromNode());
                }
            }
        }
        return seen;
    }

    /** How often a running pipeline looks whether it is asked to stop: a read in memory, not a query. */
    static final long WATCH_EVERY_MS = 100;

    /**
     * Runs {@code graph}. {@code cancelled} is someone asking the run to stop; {@code shutdown} the
     * worker stopping. Either interrupts the run within about half a second. Both are read in memory
     * ({@code cancelled} through the run watcher's signal), so they are looked at ten times a second.
     */
    Outcome run(GraphRunner runner, PipelineGraph graph, List<WriteTarget> writes, RunContext context,
                BooleanSupplier cancelled, BooleanSupplier shutdown) {
        return run(runner, graph, writes, context, cancelled, shutdown, RunListener.NONE);
    }

    /** Runs {@code graph} as above, telling {@code listener} how it goes (the run's log and progress). */
    Outcome run(GraphRunner runner, PipelineGraph graph, List<WriteTarget> writes, RunContext context,
                BooleanSupplier cancelled, BooleanSupplier shutdown, RunListener listener) {
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
                    Thread.sleep(WATCH_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        RunResult result = null;
        boolean interrupted = false;
        try {
            // Only what the writers report (written=true): a pipeline reading the asset it writes also
            // hears its reader's row count, which is not the written asset's.
            result = runner.run(graph, e -> {
                if (written.contains(e.assetUrn()) && writerEvent(e) && events.size() < MAX_EVENTS) {
                    events.add(e);
                }
            }, listener);
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (RuntimeException e) {
            closeNodes(graph); // the runner refused the graph before starting its nodes
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
            List<PipelineStore.NodeRun> none = graph.order() == null ? List.of() : graph.order().stream()
                .map(id -> new PipelineStore.NodeRun(id, graph.node(id).node().type(), position(graph, id), null, null,
                    "{}", null, null, null, "stopped")).toList();
            return new Outcome(byPerson ? "cancelled" : "failed", byPerson ? "Cancelled while it was running"
                : "The worker running this pipeline was stopped before it finished. Run it again; check what its "
                + "writes had written first.", none, Map.of(), List.of());
        }
        Map<String, Long> rowsIn = new HashMap<>();
        List<PipelineStore.NodeRun> nodes = nodes(graph, result, context, rowsIn);
        String error = result.succeeded() ? null : context.scrub(result.error());
        return new Outcome(result.succeeded() ? "succeeded" : "failed", error, nodes, rowsIn, List.copyOf(events));
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


    private static int position(PipelineGraph g, String id) {
        List<String> order = g.order();
        int i = order == null ? -1 : order.indexOf(id);
        return Math.max(0, i);
    }

    /**
     * Rows on each edge, as the graph runner counted them ({@link RunResult#edges()}). A runner that
     * reports none leaves each edge carrying its node's rows out when the node has one output port
     * connected, and unknown (null) otherwise.
     */
    static Map<Edge, Long> rowsByEdge(PipelineGraph graph, RunResult result) {
        Map<Edge, Long> out = new LinkedHashMap<>();
        if (!result.edges().isEmpty()) {
            Map<String, Long> counted = new HashMap<>();
            for (RunResult.EdgeResult e : result.edges()) {
                counted.put(e.fromNode() + "." + e.fromPort() + ">" + e.toNode() + "." + e.toPort(), e.rows());
            }
            for (Edge e : graph.edges()) {
                out.put(e, counted.get(e.from() + ">" + e.to()));
            }
            return out;
        }
        Map<String, RunResult.NodeResult> byName = new HashMap<>();
        result.nodes().forEach(s -> byName.put(s.name(), s));
        for (Edge e : graph.edges()) {
            RunResult.NodeResult from = byName.get(e.from().node());
            long ports = graph.outOf(e.from().node()).stream().map(x -> x.from().port()).distinct().count();
            out.put(e, from != null && ports == 1 ? from.rowsOut() : null);
        }
        return out;
    }

    private List<PipelineStore.NodeRun> nodes(PipelineGraph graph, RunResult result, RunContext context,
                                              Map<String, Long> rowsIn) {
        Map<String, RunResult.NodeResult> byName = new HashMap<>();
        result.nodes().forEach(s -> byName.put(s.name(), s));
        Map<Edge, Long> edgeRows = rowsByEdge(graph, result);
        List<String> order = graph.order() == null ? graph.nodes().stream().map(GraphNode::id).toList() : graph.order();
        List<PipelineStore.NodeRun> out = new ArrayList<>();
        int position = 0;
        for (String id : order) {
            GraphNode node = graph.node(id);
            RunResult.NodeResult r = byName.get(id);
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
            // A node with nothing connected after it (a write) has no rows out: what it wrote is its target.
            Long rowsOut = r == null || graph.outOf(id).isEmpty() ? null : r.rowsOut();
            String error = r == null ? null : r.error();
            long peak = r == null ? 0 : r.peakMemoryBytes();
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
            java.time.Duration took = r == null ? null : r.duration();
            out.add(new PipelineStore.NodeRun(id, node.node().type(), position++, rowsInTotal, rowsOut,
                ports.toString(), r == null ? null : peak, took == null ? null : took.toMillis(), context.scrub(error),
                r == null ? null : r.outcome().name().toLowerCase(java.util.Locale.ROOT),
                r == null ? null : r.startedAt(), r == null ? null : r.firstRowAt(), r == null ? null : r.lastRowAt(),
                r == null ? null : r.finishedAt(), took == null ? null : r.waitingForRowsNanos() / 1_000_000,
                took == null ? null : r.heldUpNanos() / 1_000_000));
        }
        return out;
    }
}
