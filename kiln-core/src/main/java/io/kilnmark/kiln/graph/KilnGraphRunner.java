package io.kilnmark.kiln.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * Runs a pipeline graph (docs/design/pipelines.md, ADR 0022): every step on its own virtual
 * thread, each edge a bounded {@link RowSet}, each step's memory a child allocator inside one
 * limit.
 *
 * <ul>
 *   <li><b>One output, several readers.</b> The step writes to one row set; a copying thread (the
 *       tee) gives each reader its own copy of every batch and the last reader the original, so
 *       each step owns what it takes. Copies waiting to be taken count against the writing step's
 *       memory, as batches waiting on any edge do. If one reader stops early, the others are still
 *       fed.</li>
 *   <li><b>Many inputs on one port.</b> Every edge into the same input port is offered to the step
 *       in the order of the graph's edges, through {@link StepContext#inputs(String)}.</li>
 *   <li><b>Unconnected outputs.</b> {@link StepContext#output(String)} returns {@code null} for an
 *       output port with nothing connected; the step drops those rows
 *       ({@link StepContext#emit} does that for it).</li>
 *   <li><b>A step that ends early</b> (it stops before its input has ended) releases its inputs: a
 *       step writing only to it is stopped, without failing; a step that also writes elsewhere
 *       goes on, and what it writes to the finished step is read and dropped.</li>
 *   <li><b>Failures.</b> A failing step stops the steps before it and ends the steps after it,
 *       along every edge, and steps not joined to it at all are stopped; the result names the step
 *       where the failure started. Steps that only saw it are not blamed. A step that does not free
 *       its memory is named.</li>
 *   <li><b>Interruption.</b> If the thread calling {@link #run} is interrupted, every step is
 *       stopped and waited for before the {@link InterruptedException} is thrown.</li>
 * </ul>
 */
public final class KilnGraphRunner implements GraphRunner {

    private static final long DRAIN_POLL_MILLIS = 100;

    private final long memoryLimitBytes;
    private final int rowSetCapacity;

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each edge holds before the step writing to it waits
     */
    public KilnGraphRunner(long memoryLimitBytes, int rowSetCapacity) {
        if (rowSetCapacity < 1) {
            throw new IllegalArgumentException("rowSetCapacity must be at least 1");
        }
        this.memoryLimitBytes = memoryLimitBytes;
        this.rowSetCapacity = rowSetCapacity;
    }

    /**
     * Runs {@code graph} to the end and reports each step and edge.
     *
     * @throws IllegalArgumentException if the graph cannot run ({@link PipelineGraph#check()})
     * @throws InterruptedException     if the calling thread is interrupted; the steps have stopped by then
     */
    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException {
        List<String> problems = graph.check();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The pipeline " + graph.name() + " cannot run: "
                + String.join("; ", problems));
        }
        return new Run(graph, metadata).execute();
    }

    /** One run: the steps, ports and row sets of the graph, and what has gone wrong. */
    private final class Run {
        private final PipelineGraph graph;
        private final MetadataEmitter metadata;
        private final List<Node> nodes = new ArrayList<>();
        private final Map<String, Node> byId = new HashMap<>();
        private final List<Port> ports = new ArrayList<>();
        private final List<Reader> readers = new ArrayList<>(); // one per edge, in the graph's order
        private final ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final Object lock = new Object();
        private int errors;

        Run(PipelineGraph graph, MetadataEmitter metadata) {
            this.graph = graph;
            this.metadata = metadata;
            for (GraphStep s : graph.steps()) {
                Node n = new Node(s, nodes.size());
                nodes.add(n);
                byId.put(s.id(), n);
            }
            Map<PortRef, Port> byRef = new LinkedHashMap<>();
            for (Edge e : graph.edges()) {
                Port p = byRef.computeIfAbsent(e.from(), ref -> {
                    Port np = new Port(byId.get(ref.step()), ref.port());
                    ports.add(np);
                    return np;
                });
                Reader r = new Reader(e, p, byId.get(e.to().step()));
                p.readers.add(r);
                readers.add(r);
            }
            for (Port p : ports) {
                if (p.readers.size() == 1) {
                    p.rowSet = new RowSet(edgeName(p.readers.getFirst().edge), rowSetCapacity);
                    p.readers.getFirst().rowSet = p.rowSet;
                } else {
                    p.rowSet = new RowSet(p.producer.id() + "." + p.port, rowSetCapacity);
                    for (Reader r : p.readers) {
                        r.rowSet = new RowSet(edgeName(r.edge), rowSetCapacity);
                    }
                }
                p.producer.outputs.put(p.port, p);
            }
            for (Reader r : readers) {
                r.consumer.inputs.computeIfAbsent(r.edge.to().port(), k -> new ArrayList<>()).add(r);
            }
            components();
        }

        /** Numbers each group of steps joined by edges, so a failure can stop the steps outside its own. */
        private void components() {
            int next = 0;
            for (Node start : nodes) {
                if (start.component >= 0) {
                    continue;
                }
                List<Node> todo = new ArrayList<>(List.of(start));
                start.component = next;
                while (!todo.isEmpty()) {
                    Node n = todo.removeLast();
                    List<Node> near = new ArrayList<>();
                    for (Port p : n.outputs.values()) {
                        p.readers.forEach(r -> near.add(r.consumer));
                    }
                    for (List<Reader> rs : n.inputs.values()) {
                        rs.forEach(r -> near.add(r.port.producer));
                    }
                    for (Node m : near) {
                        if (m.component < 0) {
                            m.component = next;
                            todo.add(m);
                        }
                    }
                }
                next++;
            }
        }

        RunResult execute() throws InterruptedException {
            Instant started = Instant.now();
            try (RootAllocator root = new RootAllocator(memoryLimitBytes)) {
                for (Node n : nodes) {
                    n.allocator = root.newChildAllocator(n.id(), 0, memoryLimitBytes);
                    for (Port p : n.outputs.values()) {
                        if (p.readers.size() > 1) {
                            p.copies = n.allocator.newChildAllocator(n.id() + "." + p.port + " copies", 0,
                                memoryLimitBytes);
                        }
                    }
                }
                synchronized (lock) { // a failure while starting waits until every thread is known
                    for (Node n : nodes) {
                        Context ctx = new Context(n, metadata);
                        start(n, "kiln-" + graph.name() + "-" + n.id(), () -> runStep(n, ctx));
                        for (Port p : n.outputs.values()) {
                            if (p.copies != null) {
                                start(n, "kiln-" + graph.name() + "-" + n.id() + "." + p.port + "-copies",
                                    () -> runTee(p));
                            }
                        }
                    }
                }
                try {
                    Thread t;
                    while ((t = threads.poll()) != null) {
                        t.join();
                    }
                } catch (InterruptedException e) {
                    stopAll();
                    for (Node n : nodes) {
                        n.closeAllocators(true);
                    }
                    throw e;
                }
                for (Node n : nodes) {
                    n.peak = n.allocator.getPeakMemoryAllocation();
                    String leak = n.closeAllocators(n.stoppedByEngine);
                    if (leak != null) {
                        recordError(n, "Step " + n.id() + " did not free all its memory: " + leak, true);
                    }
                }
            } catch (IllegalStateException leak) {
                // reported per step above
            }
            return result(started);
        }

        private void start(Node owner, String name, Runnable body) {
            Thread t = Thread.ofVirtual().name(name).unstarted(body);
            owner.threads.add(t);
            threads.add(t);
            t.start();
        }

        private RunResult result(Instant started) {
            List<RunResult.StepResult> steps = new ArrayList<>();
            Node first = null;
            for (Node n : nodes) {
                Map<String, Long> in = new LinkedHashMap<>();
                long rowsIn = 0;
                for (Map.Entry<String, List<Reader>> e : n.inputs.entrySet()) {
                    long rows = e.getValue().stream().mapToLong(r -> r.rowSet.rows()).sum();
                    in.put(e.getKey(), rows);
                    rowsIn += rows;
                }
                Map<String, Long> out = new LinkedHashMap<>();
                long rowsOut = 0;
                for (Port p : n.outputs.values()) {
                    out.put(p.port, p.rowSet.rows());
                    rowsOut += p.rowSet.rows();
                }
                steps.add(new RunResult.StepResult(n.id(), n.step().type(), rowsOut, n.peak, n.error, rowsIn, in, out));
                if (n.error != null && (first == null || n.errorOrder < first.errorOrder)) {
                    first = n;
                }
            }
            List<RunResult.EdgeResult> edges = new ArrayList<>();
            for (Reader r : readers) {
                Edge e = r.edge;
                edges.add(new RunResult.EdgeResult(e.from().step(), e.from().port(), e.to().step(), e.to().port(),
                    r.rowSet.rows()));
            }
            String error = first == null ? null : first.error;
            return new RunResult(graph.name(), error == null ? RunResult.Status.SUCCEEDED : RunResult.Status.FAILED,
                started, Instant.now(), steps, edges, error);
        }

        // ---- a step ----

        /** Runs one step to the end, recording its own failure if it has one. */
        private void runStep(Node n, Context ctx) {
            Step step = n.step();
            try {
                step.init(ctx);
                while (step.process(ctx)) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
                finishOutputs(n);
                releaseInputs(n);
            } catch (Throwable t) {
                boolean seen = t instanceof RowSet.UpstreamFailedException || t instanceof RowSet.CancelledException;
                if (!seen && !n.stoppedByEngine) {
                    recordError(n, "Step " + n.id() + " (" + step.type() + ") failed: " + message(t), false);
                }
                if (t instanceof RowSet.CancelledException && !failed.get()) {
                    // every step reading this one has finished early and nothing failed: end early too
                    finishOutputs(n);
                    releaseInputs(n);
                } else {
                    failOutputs(n, t);
                    cancelInputs(n);
                }
            } finally {
                try {
                    step.close();
                } catch (RuntimeException e) {
                    if (!n.stoppedByEngine) {
                        recordError(n, "Step " + n.id() + " failed to close: " + message(e), false);
                    }
                }
            }
        }

        private void finishOutputs(Node n) {
            for (Port p : n.outputs.values()) {
                try {
                    p.rowSet.finish();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void failOutputs(Node n, Throwable cause) {
            for (Port p : n.outputs.values()) {
                try {
                    p.rowSet.fail(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void cancelInputs(Node n) {
            n.inputs.values().forEach(rs -> rs.forEach(r -> r.rowSet.cancel()));
        }

        /**
         * The step has finished: a step writing only to it can stop, but one that also writes
         * elsewhere must go on, so what it still writes here is read and dropped.
         */
        private void releaseInputs(Node n) {
            for (List<Reader> rs : n.inputs.values()) {
                for (Reader r : rs) {
                    if (r.rowSet.atEnd()) {
                        continue;
                    }
                    if (n.stoppedByEngine || r.port.copies != null || r.port.producer.outputs.size() == 1) {
                        r.rowSet.cancel();
                    } else {
                        start(n, "kiln-" + graph.name() + "-" + n.id() + "-drain", () -> drain(r.rowSet, n));
                    }
                }
            }
        }

        /** Reads and drops what is left of an input, until it ends or the run is stopped. */
        private static void drain(RowSet rowSet, Node n) {
            try {
                while (!rowSet.atEnd() && !n.stoppedByEngine) {
                    VectorSchemaRoot b = rowSet.poll(n.allocator, DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (b != null) {
                        b.close();
                    }
                }
            } catch (StepException e) {
                // the step before failed: that step reports it
            } catch (InterruptedException e) {
                // stopped
            } finally {
                rowSet.cancel();
            }
        }

        // ---- copying one output to several readers ----

        private void runTee(Port p) {
            List<Reader> live = new ArrayList<>(p.readers);
            VectorSchemaRoot held = null;
            try {
                while (true) {
                    held = p.rowSet.take(p.copies);
                    if (held == null) {
                        break;
                    }
                    if (live.isEmpty()) { // every reader has finished; the step goes on for its other outputs
                        held.close();
                        held = null;
                        continue;
                    }
                    int i = 0;
                    while (i < live.size()) {
                        boolean last = i == live.size() - 1;
                        VectorSchemaRoot give = last ? held : Batches.copy(held, p.copies);
                        if (last) {
                            held = null; // put takes it, or closes it
                        }
                        try {
                            live.get(i).rowSet.put(give);
                            i++;
                        } catch (RowSet.CancelledException stopped) {
                            if (failed.get()) {
                                throw stopped;
                            }
                            live.remove(i); // this reader finished early; the others are still fed
                        }
                    }
                    if (live.isEmpty() && p.producer.outputs.size() == 1) {
                        p.rowSet.cancel(); // nobody reads this step any more: it can stop
                        return;
                    }
                }
                for (Reader r : live) {
                    r.rowSet.finish();
                }
            } catch (RowSet.UpstreamFailedException | RowSet.CancelledException seen) {
                p.rowSet.cancel();
                failAll(live, seen);
            } catch (InterruptedException e) {
                p.rowSet.cancel();
                live.forEach(r -> r.rowSet.cancel());
            } catch (Throwable t) {
                if (!p.producer.stoppedByEngine) {
                    recordError(p.producer, "Step " + p.producer.id() + " could not copy its '" + p.port
                        + "' rows to the " + p.readers.size() + " steps reading them: " + message(t), false);
                }
                p.rowSet.cancel();
                failAll(live, t);
            } finally {
                if (held != null) {
                    held.close();
                }
            }
        }

        private static void failAll(List<Reader> live, Throwable cause) {
            for (Reader r : live) {
                try {
                    r.rowSet.fail(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // ---- failures and stopping ----

        /**
         * Records where a failure started. The first failure also stops every step not joined to the
         * failing one; the steps joined to it hear of it through their row sets.
         */
        private void recordError(Node n, String message, boolean leak) {
            synchronized (lock) {
                if (n.error != null) {
                    return;
                }
                n.error = message;
                n.errorOrder = leak ? Integer.MAX_VALUE - nodes.size() + n.index : errors++;
            }
            if (!leak && failed.compareAndSet(false, true)) {
                synchronized (lock) {
                    for (Node m : nodes) {
                        if (m.component != n.component) {
                            stop(m);
                        }
                    }
                }
            }
        }

        private void stop(Node n) {
            n.stoppedByEngine = true;
            n.inputs.values().forEach(rs -> rs.forEach(r -> r.rowSet.cancel()));
            for (Port p : n.outputs.values()) {
                p.rowSet.cancel();
                p.readers.forEach(r -> r.rowSet.cancel());
            }
            n.threads.forEach(Thread::interrupt);
        }

        /**
         * Stops every step and waits for all of them, however often this thread is interrupted
         * meanwhile. The interrupt flag is set again on return.
         */
        private void stopAll() {
            synchronized (lock) {
                nodes.forEach(this::stop);
            }
            boolean interrupted = false;
            for (Node n : nodes) {
                for (Thread t : n.threads) {
                    while (t.isAlive()) {
                        try {
                            t.join();
                        } catch (InterruptedException again) {
                            interrupted = true;
                        }
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String edgeName(Edge e) {
        return "out".equals(e.from().port()) && "in".equals(e.to().port())
            ? e.from().step() + "->" + e.to().step()
            : e.from() + "->" + e.to();
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** A step of the run. */
    private static final class Node {
        final GraphStep graphStep;
        final int index;
        final Map<String, List<Reader>> inputs = new LinkedHashMap<>();
        final Map<String, Port> outputs = new LinkedHashMap<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        int component = -1;
        BufferAllocator allocator;
        long peak;
        volatile boolean stoppedByEngine;
        String error; // guarded by the run's lock until the threads have ended
        int errorOrder;

        Node(GraphStep graphStep, int index) {
            this.graphStep = graphStep;
            this.index = index;
        }

        String id() {
            return graphStep.id();
        }

        Step step() {
            return graphStep.step();
        }

        /** Closes the copying allocators and then the step's; returns what leaked, or null. */
        String closeAllocators(boolean quietly) {
            String leak = null;
            for (Port p : outputs.values()) {
                if (p.copies != null) {
                    leak = close(p.copies, leak);
                }
            }
            leak = close(allocator, leak);
            return quietly ? null : leak;
        }

        private static String close(BufferAllocator a, String leak) {
            try {
                a.close();
                return leak;
            } catch (IllegalStateException e) {
                return leak == null ? e.getMessage() : leak;
            }
        }
    }

    /** An output port that something reads. */
    private static final class Port {
        final Node producer;
        final String port;
        final List<Reader> readers = new ArrayList<>();
        RowSet rowSet;             // what the step writes to
        BufferAllocator copies;    // set when several steps read the port

        Port(Node producer, String port) {
            this.producer = producer;
            this.port = port;
        }
    }

    /** One edge, from the reading side. */
    private static final class Reader {
        final Edge edge;
        final Port port;
        final Node consumer;
        RowSet rowSet;             // what the reading step takes from: the port's own row set, or its copy

        Reader(Edge edge, Port port, Node consumer) {
            this.edge = edge;
            this.port = port;
            this.consumer = consumer;
        }
    }

    /** What a step in a graph sees: its named ports, its settings and its memory. */
    private static final class Context implements StepContext {
        private final Node node;
        private final MetadataEmitter metadata;
        private final Map<String, List<RowSet>> in = new LinkedHashMap<>();
        private final Map<String, RowSet> out = new LinkedHashMap<>();
        private final Map<String, String> settings;

        Context(Node node, MetadataEmitter metadata) {
            this.node = node;
            this.metadata = metadata;
            node.inputs.forEach((port, rs) -> in.put(port, rs.stream().map(r -> r.rowSet).toList()));
            node.outputs.forEach((port, p) -> out.put(port, p.rowSet));
            Map<String, String> s = new LinkedHashMap<>();
            node.graphStep.config().forEach((k, v) -> {
                String text = scalar(v);
                if (text != null) {
                    s.put(k, text);
                }
            });
            this.settings = Collections.unmodifiableMap(s);
        }

        private static String scalar(Object v) {
            if (v instanceof BigDecimal d) {
                return d.toPlainString();
            }
            if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) {
                return v.toString();
            }
            return null;
        }

        @Override
        public String stepName() {
            return node.id();
        }

        @Override
        public Map<String, String> settings() {
            return settings;
        }

        @Override
        public Map<String, Object> config() {
            return node.graphStep.config();
        }

        @Override
        public MetadataEmitter metadata() {
            return metadata;
        }

        @Override
        public BufferAllocator allocator() {
            return node.allocator;
        }

        @Override
        public RowSet input() {
            List<RowSet> rs = in.get("in");
            if (rs != null && rs.size() == 1) {
                return rs.getFirst();
            }
            if (in.isEmpty()) {
                throw new IllegalStateException("Step " + node.id() + " has no input: it is the first step");
            }
            throw new IllegalStateException(rs == null
                ? "Step " + node.id() + " has nothing connected to its 'in' port"
                : "Step " + node.id() + " has " + rs.size() + " inputs on its 'in' port: read them with inputs(\"in\")");
        }

        @Override
        public RowSet output() {
            RowSet r = out.get("out");
            if (r != null) {
                return r;
            }
            throw new IllegalStateException(out.isEmpty()
                ? "Step " + node.id() + " has no output: it is the last step"
                : "Step " + node.id() + " has nothing connected to its 'out' port");
        }

        @Override
        public boolean hasInput() {
            return !in.isEmpty();
        }

        @Override
        public boolean hasOutput() {
            return !out.isEmpty();
        }

        @Override
        public Set<String> inputPorts() {
            return Collections.unmodifiableSet(in.keySet());
        }

        @Override
        public Set<String> outputPorts() {
            return Collections.unmodifiableSet(out.keySet());
        }

        @Override
        public RowSet input(String port) throws StepException {
            List<RowSet> rs = in.get(port);
            if (rs == null) {
                throw new StepException("Step " + node.id() + " has no input connected to port '" + port + "'");
            }
            if (rs.size() > 1) {
                throw new StepException("Step " + node.id() + " has " + rs.size() + " inputs on port '" + port
                    + "': read them with inputs(\"" + port + "\")");
            }
            return rs.getFirst();
        }

        @Override
        public List<RowSet> inputs(String port) {
            return in.getOrDefault(port, List.of());
        }

        /** Null for any port with nothing connected: the graph was checked against the step's ports before the run. */
        @Override
        public RowSet output(String port) {
            return out.get(port);
        }
    }
}
