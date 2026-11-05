package io.kilnmark.kiln.graph;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Decimals;
import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;

/**
 * Runs a pipeline graph (docs/design/pipelines.md, ADR 0022): every node on its own virtual
 * thread, each edge a bounded {@link RowSet}, each node's memory a child allocator inside one
 * limit.
 *
 * <ul>
 *   <li><b>One output, several readers.</b> The node writes to one row set; a copying thread (the
 *       tee) gives each reader its own copy of every batch and the last reader the original, so
 *       each node owns what it takes. Copies waiting to be taken count against the writing node's
 *       memory, as batches waiting on any edge do. If one reader stops early, the others are still
 *       fed.</li>
 *   <li><b>Many inputs on one port.</b> Every edge into the same input port is offered to the node
 *       in the order of the graph's edges, through {@link NodeContext#inputs(String)}.</li>
 *   <li><b>Unconnected outputs.</b> {@link NodeContext#output(String)} returns {@code null} for an
 *       output port with nothing connected; the node drops those rows
 *       ({@link NodeContext#emit} does that for it).</li>
 *   <li><b>A node that ends early</b> (it stops before its input has ended) releases its inputs.
 *       Once every node reading a node has finished, that node is stopped, without failing; while
 *       another still reads it, what it writes to the finished node is read and dropped.</li>
 *   <li><b>Failures.</b> A failing node stops the nodes before it (they are interrupted, so a node
 *       waiting on a database stops too) and ends the nodes after it, along every edge; nodes not
 *       joined to it are stopped. The result names the node where the failure started. Nodes that
 *       only saw it are not blamed. A node that does not free its memory is named.</li>
 *   <li><b>Stalls.</b> If no batch moves for the stall timeout while every running node is waiting
 *       on a row set, the run fails with a message naming the waiting nodes.</li>
 *   <li><b>Interruption.</b> If the thread calling {@link #run} is interrupted, every node is
 *       stopped and waited for before the {@link InterruptedException} is thrown.</li>
 *   <li><b>Timing.</b> Each node's start, first row, last row and end are on its
 *       {@link RunResult.NodeResult}. A {@link RunListener} hears them as the run goes, with a
 *       {@link RunProgress} every couple of seconds, on a thread of its own: the nodes only record
 *       times (no allocation per batch), and the thread watching the run turns them into calls.</li>
 * </ul>
 */
public final class KilnGraphRunner implements GraphRunner {

    /** How long a run may go without moving a batch while every node waits, unless set otherwise. */
    public static final Duration DEFAULT_STALL_TIMEOUT = Duration.ofMinutes(2);

    /** How often a listener hears where the rows are, unless set otherwise. */
    public static final Duration DEFAULT_PROGRESS_EVERY = Duration.ofSeconds(2);

    /** How long the end of a run waits for a slow listener to hear the last of it. */
    static final Duration LISTENER_GRACE = Duration.ofSeconds(10);

    /** Calls waiting for a listener; beyond this a slow listener misses some. */
    static final int LISTENER_BACKLOG = 4096;

    private static final long POLL_MILLIS = 100;
    private static final System.Logger LOG = System.getLogger(KilnGraphRunner.class.getName());

    private final long memoryLimitBytes;
    private final int rowSetCapacity;
    private final Duration stallTimeout;
    private final Duration progressEvery;
    /** How long a stopped run waits for its nodes' threads; null waits until they end. */
    private final Duration stopWait;
    /** Threads a stopped run stopped waiting for (see {@link #threadsLeftRunning()}). */
    private final java.util.List<Thread> leftRunning = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each edge holds before the node writing to it waits
     */
    public KilnGraphRunner(long memoryLimitBytes, int rowSetCapacity) {
        this(memoryLimitBytes, rowSetCapacity, DEFAULT_STALL_TIMEOUT);
    }

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each edge holds before the node writing to it waits
     * @param stallTimeout     how long no batch may move while every running node waits before the
     *                         run fails as stalled
     */
    public KilnGraphRunner(long memoryLimitBytes, int rowSetCapacity, Duration stallTimeout) {
        this(memoryLimitBytes, rowSetCapacity, stallTimeout, DEFAULT_PROGRESS_EVERY);
    }

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each edge holds before the node writing to it waits
     * @param stallTimeout     how long no batch may move while every running node waits before the
     *                         run fails as stalled
     * @param progressEvery    how often a {@link RunListener} hears where the rows are
     */
    public KilnGraphRunner(long memoryLimitBytes, int rowSetCapacity, Duration stallTimeout, Duration progressEvery) {
        this(memoryLimitBytes, rowSetCapacity, stallTimeout, progressEvery, null);
    }

    /**
     * As {@link #KilnGraphRunner(long, int, Duration, Duration)}, with {@code stopWait}: how long a
     * run stopped by interruption waits for its nodes' threads before {@link #run} throws anyway
     * (null: until they end). A node stuck where an interrupt cannot reach it (a blocking call that
     * ignores it) is then left to end on its own, with a warning naming it, and listed by
     * {@link #threadsLeftRunning()}; its memory is released as it can be.
     */
    public KilnGraphRunner(long memoryLimitBytes, int rowSetCapacity, Duration stallTimeout, Duration progressEvery,
                           Duration stopWait) {
        if (progressEvery == null || progressEvery.isNegative() || progressEvery.isZero()) {
            throw new IllegalArgumentException("progressEvery must be positive");
        }
        this.progressEvery = progressEvery;
        if (rowSetCapacity < 1) {
            throw new IllegalArgumentException("rowSetCapacity must be at least 1");
        }
        if (stallTimeout == null || stallTimeout.isNegative() || stallTimeout.isZero()) {
            throw new IllegalArgumentException("stallTimeout must be positive");
        }
        this.memoryLimitBytes = memoryLimitBytes;
        this.rowSetCapacity = rowSetCapacity;
        this.stallTimeout = stallTimeout;
        this.stopWait = stopWait == null || stopWait.isNegative() ? null : stopWait;
    }

    /**
     * The nodes' threads still running when a run stopped by interruption gave up waiting for them
     * ({@code stopWait}), so a caller can wait for them, or count them, itself. Empty otherwise.
     */
    public java.util.List<Thread> threadsLeftRunning() {
        return java.util.List.copyOf(leftRunning);
    }

    /**
     * Runs {@code graph} to the end and reports each node and edge.
     *
     * @throws IllegalArgumentException if the graph cannot run ({@link PipelineGraph#check()})
     * @throws InterruptedException     if the calling thread is interrupted; the nodes have stopped by then
     */
    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException {
        return run(graph, metadata, RunListener.NONE);
    }

    /**
     * Runs {@code graph} to the end, telling {@code listener} how it goes; see {@link RunListener}.
     *
     * @throws IllegalArgumentException if the graph cannot run ({@link PipelineGraph#check()})
     * @throws InterruptedException     if the calling thread is interrupted; the nodes have stopped by then
     */
    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata, RunListener listener)
        throws InterruptedException {
        List<String> problems = graph.check();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The pipeline " + graph.name() + " cannot run: "
                + String.join("; ", problems));
        }
        return new Run(graph, metadata, listener == null ? RunListener.NONE : listener).execute();
    }

    /** One run: the nodes, ports and row sets of the graph, and what has gone wrong. */
    private final class Run {
        private final PipelineGraph graph;
        private final MetadataEmitter metadata;
        private final List<RunNode> runNodes = new ArrayList<>();
        private final Map<String, RunNode> byId = new HashMap<>();
        private final List<Port> ports = new ArrayList<>();
        private final List<Reader> readers = new ArrayList<>(); // one per edge, in the graph's order
        /** Every thread started; a thread leaves only once it has ended. */
        private final ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final Object lock = new Object();
        private int errors;            // guarded by lock
        private boolean stopping;      // guarded by lock: no thread may start once set
        private volatile String runError;
        private final RunListener listener;
        /** Notes for the listener, from any thread; rare, so allocating for each is fine. */
        private final ConcurrentLinkedQueue<Note> notes = new ConcurrentLinkedQueue<>();
        private Teller teller;           // the listener's thread; null with no listener
        private Instant baseWall;        // the run's start, by the clock and by System.nanoTime()
        private long baseNanos;
        private long lastProgress;       // nanoTime of the last snapshot, 0 for none
        private long[] handled;          // each node's rows handled at the last snapshot

        Run(PipelineGraph graph, MetadataEmitter metadata, RunListener listener) {
            this.graph = graph;
            this.metadata = metadata;
            this.listener = listener;
            for (GraphNode s : graph.nodes()) {
                RunNode n = new RunNode(s, runNodes.size());
                runNodes.add(n);
                byId.put(s.id(), n);
            }
            Map<PortRef, Port> byRef = new LinkedHashMap<>();
            for (Edge e : graph.edges()) {
                Port p = byRef.computeIfAbsent(e.from(), ref -> {
                    Port np = new Port(byId.get(ref.node()), ref.port());
                    ports.add(np);
                    return np;
                });
                Reader r = new Reader(e, p, byId.get(e.to().node()));
                p.readers.add(r);
                readers.add(r);
                p.producer.liveReaders.incrementAndGet();
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

        /** Numbers each group of nodes joined by edges, so a failure can stop the nodes outside its own. */
        private void components() {
            int next = 0;
            for (RunNode start : runNodes) {
                if (start.component >= 0) {
                    continue;
                }
                List<RunNode> todo = new ArrayList<>(List.of(start));
                start.component = next;
                while (!todo.isEmpty()) {
                    RunNode n = todo.removeLast();
                    List<RunNode> near = new ArrayList<>();
                    for (Port p : n.outputs.values()) {
                        p.readers.forEach(r -> near.add(r.consumer));
                    }
                    for (List<Reader> rs : n.inputs.values()) {
                        rs.forEach(r -> near.add(r.port.producer));
                    }
                    for (RunNode m : near) {
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
            baseWall = started;
            baseNanos = System.nanoTime();
            handled = new long[runNodes.size()];
            RootAllocator root = new RootAllocator(memoryLimitBytes);
            try {
                try {
                    for (RunNode n : runNodes) {
                        n.allocator = root.newChildAllocator(n.id(), 0, memoryLimitBytes);
                        for (Port p : n.outputs.values()) {
                            if (p.readers.size() > 1) {
                                p.copies = n.allocator.newChildAllocator(n.id() + "." + p.port + " copies", 0,
                                    memoryLimitBytes);
                            }
                        }
                    }
                } catch (Throwable t) {
                    runNodes.forEach(n -> n.closeAllocators(true));
                    throw t;
                }
                startAll();
                if (!RunListener.NONE.equals(listener)) {
                    teller = new Teller(graph.name());
                }
                try {
                    try {
                        watch();
                    } catch (InterruptedException e) {
                        stopAll();
                        joinAll();
                        for (RunNode n : runNodes) {
                            n.closeAllocators(true);
                        }
                        throw e;
                    }
                    for (RunNode n : runNodes) {
                        n.peak = n.allocator.getPeakMemoryAllocation();
                        String leak = n.closeAllocators(n.stoppedByEngine);
                        if (leak != null) {
                            recordError(n, "Node " + n.id() + " did not free all its memory: " + leak, true);
                        } else if (n.quietLeak != null) {
                            note(RunListener.Level.WARN, n, "Node " + n.id() + " was stopped and did not free all "
                                + "its memory: " + n.quietLeak);
                        }
                    }
                } finally {
                    finishTelling(); // whatever ended the run, the listener hears the rest
                }
            } finally {
                try {
                    root.close();
                } catch (IllegalStateException leak) {
                    // reported per node, or the run is being abandoned
                }
            }
            return result(started);
        }

        /**
         * Builds every node's context, then starts every thread. If anything goes wrong on the way, the
         * threads already started are stopped and waited for before the failure is thrown, so no node
         * outlives the call.
         */
        private void startAll() {
            try {
                Map<RunNode, Context> contexts = new LinkedHashMap<>();
                for (RunNode n : runNodes) {
                    contexts.put(n, new Context(n, metadata));
                }
                synchronized (lock) { // a failure while starting waits until every thread is known
                    for (RunNode n : runNodes) {
                        Context ctx = contexts.get(n);
                        n.nodeThread = start(n, "kiln-" + graph.name() + "-" + n.id(), () -> runNode(n, ctx));
                        for (Port p : n.outputs.values()) {
                            if (p.copies != null) {
                                p.teeThread = start(n, "kiln-" + graph.name() + "-" + n.id() + "." + p.port + "-copies",
                                    () -> runTee(p));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                stopAll();
                joinAll();
                runNodes.forEach(n -> n.closeAllocators(true));
                throw t;
            }
        }

        /** Starts a thread for {@code owner}, unless the run or that node is being stopped; then null. */
        private Thread start(RunNode owner, String name, Runnable body) {
            synchronized (lock) {
                if (stopping || owner.stoppedByEngine) {
                    return null;
                }
                Consumer<String> hook = beforeStart;
                if (hook != null) {
                    hook.accept(name);
                }
                Thread t = Thread.ofVirtual().name(name).unstarted(body);
                owner.threads.add(t);
                threads.add(t);
                t.start();
                return t;
            }
        }

        // ---- waiting for the end, and the stall watchdog ----

        /** Waits for every thread, failing the run if it stalls. */
        private void watch() throws InterruptedException {
            long stallNanos = stallTimeout.toNanos();
            Duration tick = Duration.ofNanos(Math.max(TimeUnit.MILLISECONDS.toNanos(10),
                Math.min(TimeUnit.SECONDS.toNanos(1), stallNanos / 4)));
            long seen = progress();
            long since = System.nanoTime();
            Thread t;
            while ((t = threads.peek()) != null) {
                observe(false);
                if (t.join(tick)) {
                    threads.poll(); // only this thread removes, so the head is still t
                    continue;
                }
                long now = System.nanoTime();
                long p = progress();
                if (p != seen) {
                    seen = p;
                    since = now;
                } else if (now - since >= stallNanos) {
                    List<String> waiting = waiting();
                    if (waiting != null) {
                        stalled(waiting);
                        since = now;
                    } // else some node is busy: look again at the next tick
                }
            }
        }

        private long progress() {
            long p = 0;
            for (Port port : ports) {
                p += port.rowSet.progress();
                if (port.copies != null) {
                    for (Reader r : port.readers) {
                        p += r.rowSet.progress();
                    }
                }
            }
            return p;
        }

        /** What each running node waits for, or null if some running node is not waiting on a row set. */
        private List<String> waiting() {
            List<String> out = new ArrayList<>();
            for (RunNode n : runNodes) {
                if (n.nodeThread != null && n.nodeThread.isAlive()) {
                    List<String> on = new ArrayList<>();
                    n.inputs.values().forEach(rs -> rs.stream().filter(r -> r.rowSet.readerWaiting())
                        .forEach(r -> on.add("to read from " + r.rowSet.name())));
                    n.outputs.values().stream().filter(p -> p.rowSet.writerWaiting())
                        .forEach(p -> on.add("to write to " + p.rowSet.name()));
                    if (on.isEmpty()) {
                        return null;
                    }
                    out.add(n.id() + " (" + String.join(", ", on) + ")");
                }
                for (Port p : n.outputs.values()) {
                    if (p.teeThread != null && p.teeThread.isAlive()) {
                        boolean busy = !p.rowSet.readerWaiting()
                            && p.readers.stream().noneMatch(r -> r.rowSet.writerWaiting());
                        if (busy) {
                            return null;
                        }
                    }
                }
            }
            return out.isEmpty() ? null : out;
        }

        private void stalled(List<String> waiting) {
            String time = stallTimeout.toMillis() % 1000 == 0 ? stallTimeout.toSeconds() + " seconds"
                : stallTimeout.toMillis() + " milliseconds";
            String message = "The run stalled: no rows moved for " + time + " while every running node waited for another. Waiting: " + String.join("; ", waiting)
                + ". A node that reads one input to its end before another can cause this when both come"
                + " from the same node.";
            synchronized (lock) {
                if (runError == null && errors == 0) {
                    runError = message;
                }
            }
            note(RunListener.Level.ERROR, null, message);
            failed.set(true);
            stopAll();
        }

        // ---- telling a listener ----

        private Instant at(long nanos) {
            return nanos == 0 ? null : baseWall.plusNanos(nanos - baseNanos);
        }

        private void note(RunListener.Level level, RunNode n, String text) {
            if (!RunListener.NONE.equals(listener)) {
                notes.add(new Note(level, n == null ? null : n.id(), text, System.nanoTime()));
            }
        }

        /**
         * Turns what the nodes recorded since the last look into calls to the listener, and every
         * {@code progressEvery} (and at the end, {@code last}) a snapshot. Runs on the watching thread.
         */
        private void observe(boolean last) {
            if (teller == null) {
                return;
            }
            for (RunNode n : runNodes) {
                String id = n.id();
                if ((n.told & TOLD_STARTED) == 0 && n.startedNanos != 0) {
                    n.told |= TOLD_STARTED;
                    Instant when = at(n.startedNanos);
                    teller.tell(() -> listener.nodeStarted(id, when));
                }
                long in = firstIn(n);
                if ((n.told & TOLD_FIRST_IN) == 0 && in != 0) {
                    n.told |= TOLD_FIRST_IN;
                    Instant when = at(in);
                    teller.tell(() -> listener.firstRowIn(id, when));
                }
                long out = firstOut(n);
                if ((n.told & TOLD_FIRST_OUT) == 0 && out != 0) {
                    n.told |= TOLD_FIRST_OUT;
                    Instant when = at(out);
                    teller.tell(() -> listener.firstRowOut(id, when));
                }
                // Ended once its thread has, and anything copying its rows (whose failure is the node's).
                if ((n.told & TOLD_ENDED) == 0 && n.finishedNanos != 0 && (last || teesDone(n))) {
                    n.told |= TOLD_ENDED;
                    long lastOut = lastOut(n);
                    if (lastOut != 0) {
                        Instant when = at(lastOut);
                        teller.tell(() -> listener.lastRowOut(id, when));
                    }
                    Instant when = at(n.finishedNanos);
                    RunResult.Outcome outcome = outcome(n);
                    n.toldOutcome = outcome;
                    teller.tell(() -> listener.nodeEnded(id, outcome, when));
                } else if (last && n.toldOutcome != null && n.toldOutcome != outcome(n)) {
                    // Found once every node had ended (memory it did not free): say so again.
                    Instant when = at(n.finishedNanos);
                    RunResult.Outcome outcome = outcome(n);
                    n.toldOutcome = outcome;
                    teller.tell(() -> listener.nodeEnded(id, outcome, when));
                }
            }
            Note note;
            while ((note = notes.poll()) != null) {
                Note told = note;
                Instant when = at(told.nanos());
                teller.tell(() -> listener.note(told.level(), told.node(), told.text(), when));
            }
            long now = System.nanoTime();
            if (last || lastProgress == 0 || now - lastProgress >= progressEvery.toNanos()) {
                RunProgress p = snapshot(now);
                teller.tell(() -> listener.progress(p));
            }
        }

        private RunResult.Outcome outcome(RunNode n) {
            return n.error != null ? RunResult.Outcome.FAILED
                : n.outcome != null ? n.outcome : RunResult.Outcome.STOPPED;
        }

        /** Where every node and edge is now; also works out the node holding the run back. */
        private RunProgress snapshot(long now) {
            double seconds = lastProgress == 0 ? (now - baseNanos) / 1e9 : (now - lastProgress) / 1e9;
            lastProgress = now;
            List<RunProgress.NodeProgress> nodes = new ArrayList<>(runNodes.size());
            String slowest = null;
            int mostFull = 0;
            for (RunNode n : runNodes) {
                long in = 0;
                int fullInputs = 0;
                for (List<Reader> rs : n.inputs.values()) {
                    for (Reader r : rs) {
                        in += r.rowSet.rows();
                        if (full(r.rowSet)) {
                            fullInputs++;
                        }
                    }
                }
                long out = 0;
                boolean outputFull = false;
                double fill = 0;
                for (Port p : n.outputs.values()) {
                    out += p.rowSet.rows();
                    outputFull |= full(p.rowSet);
                    fill = Math.max(fill, p.rowSet.fill());
                    if (p.copies != null) { // the copies waiting for each reader count against this node
                        for (Reader r : p.readers) {
                            fill = Math.max(fill, r.rowSet.fill());
                        }
                    }
                }
                long rows = n.outputs.isEmpty() ? in : out;
                double rate = seconds <= 0 ? 0 : Math.max(0, rows - handled[n.index]) / seconds;
                handled[n.index] = rows;
                RunProgress.State state = n.startedNanos == 0 ? RunProgress.State.NOT_STARTED
                    : n.finishedNanos != 0 ? RunProgress.State.ENDED
                    : firstRow(n) == 0 ? RunProgress.State.WAITING : RunProgress.State.RUNNING;
                if (state != RunProgress.State.ENDED && state != RunProgress.State.NOT_STARTED && !outputFull
                    && fullInputs > mostFull) {
                    slowest = n.id();
                    mostFull = fullInputs;
                }
                nodes.add(new RunProgress.NodeProgress(n.id(), state, in, out, rate, at(n.startedNanos),
                    at(firstRow(n)), at(lastRow(n)), at(n.finishedNanos), waitIn(n), waitOut(n), fill));
            }
            List<RunProgress.EdgeProgress> edges = new ArrayList<>(readers.size());
            for (Reader r : readers) {
                RowSet rs = r.rowSet;
                int queued = rs.queued();
                edges.add(new RunProgress.EdgeProgress(r.edge.from().node(), r.edge.from().port(), r.edge.to().node(),
                    r.edge.to().port(), rs.rows(), queued, rs.capacity(), full(rs),
                    queued == 0 || rs.readerWaiting(), rs.fill()));
            }
            return new RunProgress(at(now), nodes, edges, slowest);
        }

        private static boolean full(RowSet rs) {
            return rs.writerWaiting() || rs.queued() >= rs.capacity();
        }

        /** Gives the listener what is left, then waits a while for it to hear it. */
        private void finishTelling() {
            if (teller == null) {
                return;
            }
            try {
                observe(true);
            } finally {
                teller.finish();
                teller = null;
            }
        }

        // ---- the result ----

        private RunResult result(Instant started) {
            List<RunResult.NodeResult> nodes = new ArrayList<>();
            RunNode first = null;
            for (RunNode n : runNodes) {
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
                RunResult.Outcome outcome = n.outcome != null ? n.outcome : RunResult.Outcome.STOPPED; // never started
                nodes.add(new RunResult.NodeResult(n.id(), n.node().type(), rowsOut, n.peak, n.error, rowsIn, in, out,
                    outcome, at(n.startedNanos), at(firstRow(n)), at(lastRow(n)), at(n.finishedNanos), waitIn(n),
                    waitOut(n)));
                if (n.error != null && (first == null || n.errorOrder < first.errorOrder)) {
                    first = n;
                }
            }
            List<RunResult.EdgeResult> edges = new ArrayList<>();
            for (Reader r : readers) {
                Edge e = r.edge;
                edges.add(new RunResult.EdgeResult(e.from().node(), e.from().port(), e.to().node(), e.to().port(),
                    r.rowSet.rows()));
            }
            // a node's own failure first, then a stall, then a node that did not free its memory
            boolean ownFailure = first != null && first.errorOrder < Integer.MAX_VALUE - runNodes.size();
            String error = ownFailure ? first.error : runError != null ? runError : first == null ? null : first.error;
            return new RunResult(graph.name(), error == null ? RunResult.Status.SUCCEEDED : RunResult.Status.FAILED,
                started, at(System.nanoTime()), nodes, edges, error);
        }

        // ---- a node ----

        /** Runs one node to the end, recording its own failure if it has one. */
        private void runNode(RunNode n, Context ctx) {
            n.startedNanos = System.nanoTime();
            Node node = n.node();
            try {
                node.init(ctx);
                while (node.process(ctx)) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    if (!n.stoppedByEngine && nobodyReads(n)) {
                        // Every node reading it has finished, but it only wrote to ports with nothing
                        // connected since, so no write told it: it ends early, as a write would have.
                        throw new EveryReaderFinished();
                    }
                }
                boolean early = n.inputs.values().stream().flatMap(List::stream).anyMatch(r -> !r.rowSet.atEnd());
                releaseInputs(n);
                boolean ended = finishOutputs(n);
                n.outcome = !ended ? RunResult.Outcome.STOPPED
                    : early ? RunResult.Outcome.ENDED_EARLY : RunResult.Outcome.FINISHED;
            } catch (Throwable t) {
                boolean cancelled = t instanceof RowSet.CancelledException || t instanceof EveryReaderFinished;
                boolean seen = t instanceof RowSet.UpstreamFailedException || cancelled;
                if (!seen && !n.stoppedByEngine) {
                    recordError(n, describe(n, t), false);
                }
                if (cancelled && !failed.get()) {
                    // every node reading this one has finished and nothing failed: end early too
                    releaseInputs(n);
                    n.outcome = finishOutputs(n) ? RunResult.Outcome.ENDED_EARLY : RunResult.Outcome.STOPPED;
                } else {
                    n.outcome = RunResult.Outcome.STOPPED; // or FAILED, when it recorded an error of its own
                    cancelInputs(n); // first, so the nodes before stop even if a node after is stuck
                    failOutputs(n, t);
                }
            } finally {
                try {
                    node.close();
                } catch (RuntimeException e) {
                    if (!n.stoppedByEngine) {
                        recordError(n, "Node " + n.id() + " failed to close: " + message(e), false);
                    }
                }
                n.finishedNanos = System.nanoTime();
            }
        }

        /** Whether the node has outputs and every one of them has been let go by the nodes reading it. */
        private static boolean nobodyReads(RunNode n) {
            if (n.outputs.isEmpty()) {
                return false;
            }
            for (Port p : n.outputs.values()) {
                if (!p.rowSet.isCancelled()) {
                    return false;
                }
            }
            return true;
        }

        private String describe(RunNode n, Throwable t) {
            if (t instanceof OutOfMemoryException) {
                return "Node " + n.id() + " (" + n.node().type() + ") ran out of memory: the run may hold "
                    + megabytes(memoryLimitBytes) + " of Arrow memory in all, and it was used up (" + message(t) + ")";
            }
            return "Node " + n.id() + " (" + n.node().type() + ") failed: " + message(t);
        }

        /**
         * Ends every output; if interrupted (the node is being stopped), ends the rest as failed without
         * waiting. Returns whether every output was ended normally.
         */
        private boolean finishOutputs(RunNode n) {
            InterruptedException interrupted = null;
            for (Port p : n.outputs.values()) {
                try {
                    if (interrupted == null) {
                        p.rowSet.finish();
                    } else {
                        p.rowSet.fail(interrupted);
                    }
                } catch (InterruptedException e) {
                    interrupted = e;
                    try {
                        p.rowSet.fail(e);
                    } catch (InterruptedException never) {
                        // fail never waits
                    }
                }
            }
            if (interrupted != null) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        private void failOutputs(RunNode n, Throwable cause) {
            for (Port p : n.outputs.values()) {
                try {
                    p.rowSet.fail(cause); // never waits
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void cancelInputs(RunNode n) {
            n.inputs.values().forEach(rs -> rs.forEach(r -> r.rowSet.cancel()));
        }

        /**
         * The node has finished. Once every node reading a node has finished, that node can stop; until
         * then, what it still writes here is read and dropped (a tee simply stops copying to this one).
         */
        private void releaseInputs(RunNode n) {
            for (List<Reader> rs : n.inputs.values()) {
                for (Reader r : rs) {
                    if (!r.released.compareAndSet(false, true)) {
                        continue;
                    }
                    RunNode producer = r.port.producer;
                    boolean last = producer.liveReaders.decrementAndGet() == 0;
                    if (last) {
                        cancelOutputs(producer); // nobody reads it any more
                    } else if (!r.rowSet.atEnd()) {
                        if (n.stoppedByEngine || r.port.copies != null) {
                            r.rowSet.cancel();
                        } else if (start(n, "kiln-" + graph.name() + "-" + n.id() + "-drain",
                            () -> drain(r.rowSet, n)) == null) {
                            r.rowSet.cancel();
                        }
                    }
                }
            }
        }

        private void cancelOutputs(RunNode producer) {
            for (Port p : producer.outputs.values()) {
                p.rowSet.cancel();
                if (p.copies != null) {
                    p.readers.forEach(r -> r.rowSet.cancel());
                }
            }
        }

        /** Reads and drops what is left of an input, without counting it, until it ends or is stopped. */
        private static void drain(RowSet rowSet, RunNode n) {
            try {
                while (!rowSet.isCancelled() && !n.stoppedByEngine
                    && rowSet.discard(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    // dropped
                }
            } catch (NodeException e) {
                // the node before failed: that node reports it
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
                    if (p.producer.stoppedByEngine) {
                        throw new InterruptedException(); // its node is being stopped: end the readers
                    }
                    if (p.rowSet.isCancelled()) {
                        return; // nobody reads this node any more
                    }
                    if (live.isEmpty()) { // these readers have finished; the node goes on for its other readers
                        if (!p.rowSet.discard(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                            break;
                        }
                        continue;
                    }
                    held = p.rowSet.poll(p.copies, POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (held == null) {
                        if (p.rowSet.atEnd()) {
                            break;
                        }
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
                }
                for (Reader r : live) {
                    r.rowSet.finish();
                }
            } catch (RowSet.UpstreamFailedException | RowSet.CancelledException seen) {
                p.rowSet.cancel();
                failAll(live, seen);
            } catch (InterruptedException e) {
                p.rowSet.cancel();
                failAll(live, e);
            } catch (Throwable t) {
                if (!p.producer.stoppedByEngine) {
                    String what = t instanceof OutOfMemoryException
                        ? "ran out of memory copying its '" + p.port + "' rows to the " + p.readers.size()
                            + " nodes reading them: the run may hold " + megabytes(memoryLimitBytes)
                            + " of Arrow memory in all (" + message(t) + ")"
                        : "could not copy its '" + p.port + "' rows to the " + p.readers.size()
                            + " nodes reading them: " + message(t);
                    recordError(p.producer, "Node " + p.producer.id() + " " + what, false);
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
         * Records where a failure started. The first failure also interrupts the nodes before the
         * failing one (so a node waiting on a database stops) and stops every node not joined to it;
         * the nodes after it hear of it through their row sets.
         */
        private void recordError(RunNode n, String message, boolean leak) {
            synchronized (lock) {
                if (n.error != null) {
                    return;
                }
                n.error = message;
                n.errorOrder = leak ? Integer.MAX_VALUE - runNodes.size() + n.index : errors++;
            }
            note(leak ? RunListener.Level.WARN : RunListener.Level.ERROR, n, message);
            if (!leak && failed.compareAndSet(false, true)) {
                synchronized (lock) {
                    for (RunNode m : runNodes) {
                        if (m.component != n.component) {
                            stop(m);
                        }
                    }
                    for (RunNode up : upstream(n)) {
                        up.stoppedByEngine = true; // it ends through its own catch, failing its outputs
                        up.threads.forEach(Thread::interrupt);
                    }
                }
            }
        }

        /** Every node feeding {@code n}, directly or through others. */
        private Set<RunNode> upstream(RunNode n) {
            Set<RunNode> out = new HashSet<>();
            ArrayDeque<RunNode> todo = new ArrayDeque<>(List.of(n));
            while (!todo.isEmpty()) {
                for (List<Reader> rs : todo.poll().inputs.values()) {
                    for (Reader r : rs) {
                        if (out.add(r.port.producer)) {
                            todo.add(r.port.producer);
                        }
                    }
                }
            }
            out.remove(n);
            return out;
        }

        private void stop(RunNode n) {
            n.stoppedByEngine = true;
            n.inputs.values().forEach(rs -> rs.forEach(r -> r.rowSet.cancel()));
            for (Port p : n.outputs.values()) {
                p.rowSet.cancel();
                p.readers.forEach(r -> r.rowSet.cancel());
            }
            n.threads.forEach(Thread::interrupt);
        }

        /** Stops every node; no thread starts after this. */
        private void stopAll() {
            synchronized (lock) {
                stopping = true;
                runNodes.forEach(this::stop);
            }
        }

        /**
         * Waits for every thread ever started, however often this thread is interrupted meanwhile.
         * The interrupt flag is set again on return.
         */
        private void joinAll() {
            boolean interrupted = false;
            long deadline = stopWait == null ? 0 : System.nanoTime() + stopWait.toNanos();
            Thread t;
            while ((t = threads.peek()) != null) {
                try {
                    if (stopWait == null) {
                        t.join();
                    } else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0 || !t.join(Duration.ofNanos(left))) {
                            threads.stream().filter(Thread::isAlive).forEach(leftRunning::add);
                            LOG.log(System.Logger.Level.WARNING, "Run {0}: stopped, but these nodes had not ended "
                                + "after {1}; left to end on their own: {2}", graph.name(), stopWait,
                                threads.stream().filter(Thread::isAlive).map(Thread::getName).toList());
                            break;
                        }
                    }
                    threads.poll();
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final int TOLD_STARTED = 1;
    private static final int TOLD_FIRST_IN = 2;
    private static final int TOLD_FIRST_OUT = 4;
    private static final int TOLD_ENDED = 8;

    /** When the first row arrived at the node, as nanoTime; 0 for none. */
    private static long firstIn(RunNode n) {
        long t = 0;
        for (List<Reader> rs : n.inputs.values()) {
            for (Reader r : rs) {
                t = earliest(t, r.rowSet.firstTakenNanos());
            }
        }
        return t;
    }

    /** When the node first handed on a row; 0 for none. */
    private static long firstOut(RunNode n) {
        long t = 0;
        for (Port p : n.outputs.values()) {
            t = earliest(t, p.rowSet.firstPutNanos());
        }
        return t;
    }

    /** When the node last handed on a row; 0 for none. */
    private static long lastOut(RunNode n) {
        long t = 0;
        for (Port p : n.outputs.values()) {
            t = latest(t, p.rowSet.lastPutNanos());
        }
        return t;
    }

    /** Its first row: the first in, or for a node with no input the first out. */
    private static long firstRow(RunNode n) {
        return n.inputs.isEmpty() ? firstOut(n) : firstIn(n);
    }

    /** Its last row: the last out, or for a node with no output the last in. */
    private static long lastRow(RunNode n) {
        if (!n.outputs.isEmpty()) {
            return lastOut(n);
        }
        long t = 0;
        for (List<Reader> rs : n.inputs.values()) {
            for (Reader r : rs) {
                t = latest(t, r.rowSet.lastTakenNanos());
            }
        }
        return t;
    }

    private static long earliest(long a, long b) {
        return a == 0 ? b : b == 0 ? a : (b - a < 0 ? b : a);
    }

    private static long latest(long a, long b) {
        return a == 0 ? b : b == 0 ? a : (b - a > 0 ? b : a);
    }

    /** A note for the listener. */
    private record Note(RunListener.Level level, String node, String text, long nanos) {
    }

    /** Whether every thread copying the node's rows to several readers has ended. */
    private static boolean teesDone(RunNode n) {
        for (Port p : n.outputs.values()) {
            Thread t = p.teeThread;
            if (t != null && t.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /** Time the node has waited for rows on its inputs. */
    private static long waitIn(RunNode n) {
        long t = 0;
        for (List<Reader> rs : n.inputs.values()) {
            for (Reader r : rs) {
                t += r.rowSet.takeWaitNanos();
            }
        }
        return t;
    }

    /** Time the node has waited for room on what it writes to: held up by the nodes after it. */
    private static long waitOut(RunNode n) {
        long t = 0;
        for (Port p : n.outputs.values()) {
            t += p.rowSet.putWaitNanos();
        }
        return t;
    }

    /**
     * The listener's own thread: calls it one at a time, in order, catching anything it throws. A
     * slow listener only delays itself; past {@link #LISTENER_BACKLOG} calls waiting, it misses some.
     */
    private static final class Teller {
        private final java.util.concurrent.LinkedBlockingQueue<Runnable> queue =
            new java.util.concurrent.LinkedBlockingQueue<>(LISTENER_BACKLOG);
        private final Thread thread;
        private volatile boolean closed;
        private int dropped;

        Teller(String run) {
            thread = Thread.ofVirtual().name("kiln-" + run + "-listener").start(this::loop);
        }

        void tell(Runnable call) {
            if (!queue.offer(call)) {
                dropped++;
            }
        }

        private void loop() {
            while (true) {
                Runnable call;
                try {
                    call = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (call == null) {
                    if (closed) {
                        return;
                    }
                    continue;
                }
                try {
                    call.run();
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.DEBUG, "A run listener failed; the run goes on", t);
                }
            }
        }

        /** No more calls: waits up to {@link #LISTENER_GRACE} for the listener to hear the rest. */
        void finish() {
            closed = true;
            boolean interrupted = Thread.interrupted();
            try {
                if (!thread.join(LISTENER_GRACE)) {
                    thread.interrupt();
                    LOG.log(System.Logger.Level.WARNING, "A run listener was still busy {0} after the run ended; "
                        + "it missed the rest", LISTENER_GRACE);
                }
            } catch (InterruptedException e) {
                thread.interrupt();
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (dropped > 0) {
                LOG.log(System.Logger.Level.WARNING, "A run listener was too slow and missed {0} calls", dropped);
            }
        }
    }

    private static String edgeName(Edge e) {
        return "out".equals(e.from().port()) && "in".equals(e.to().port())
            ? e.from().node() + "->" + e.to().node()
            : e.from() + "->" + e.to();
    }

    /** Called with each thread's name just before it starts; for tests that make starting fail. */
    static volatile Consumer<String> beforeStart;

    private static String megabytes(long bytes) {
        return bytes >= 1 << 20 ? (bytes >> 20) + " MB" : (bytes >> 10) + " KB";
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** A node of the run. */
    private static final class RunNode {
        final GraphNode graphNode;
        final int index;
        final Map<String, List<Reader>> inputs = new LinkedHashMap<>();
        final Map<String, Port> outputs = new LinkedHashMap<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        /** Readers of any of its ports that have not finished. */
        final AtomicInteger liveReaders = new AtomicInteger();
        int component = -1;
        BufferAllocator allocator;
        volatile Thread nodeThread;
        long peak;
        volatile boolean stoppedByEngine;
        volatile RunResult.Outcome outcome;
        String error; // guarded by the run's lock until the threads have ended
        int errorOrder;
        /** System.nanoTime() when its thread started and when it ended; 0 until then. */
        volatile long startedNanos;
        volatile long finishedNanos;
        /** What the listener has heard of it (TOLD_*), and the outcome it was told; the watching thread's only. */
        int told;
        RunResult.Outcome toldOutcome;
        /** Memory a stopped node did not free, logged rather than reported. */
        String quietLeak;

        RunNode(GraphNode graphNode, int index) {
            this.graphNode = graphNode;
            this.index = index;
        }

        String id() {
            return graphNode.id();
        }

        Node node() {
            return graphNode.node();
        }

        /**
         * Closes the copying allocators and then the node's; returns what leaked, or null. A leak by a
         * node that was stopped mid-batch is logged rather than reported.
         */
        String closeAllocators(boolean quietly) {
            String leak = null;
            for (Port p : outputs.values()) {
                if (p.copies != null) {
                    leak = close(p.copies, leak);
                }
            }
            if (allocator != null) {
                leak = close(allocator, leak);
            }
            if (quietly && leak != null) {
                LOG.log(System.Logger.Level.WARNING, "Node {0} was stopped and did not free all its memory: {1}",
                    id(), leak);
                quietLeak = leak;
                return null;
            }
            return leak;
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
        final RunNode producer;
        final String port;
        final List<Reader> readers = new ArrayList<>();
        RowSet rowSet;             // what the node writes to
        BufferAllocator copies;    // set when several nodes read the port
        volatile Thread teeThread;

        Port(RunNode producer, String port) {
            this.producer = producer;
            this.port = port;
        }
    }

    /** One edge, from the reading side. */
    private static final class Reader {
        final Edge edge;
        final Port port;
        final RunNode consumer;
        final AtomicBoolean released = new AtomicBoolean();
        RowSet rowSet;             // what the reading node takes from: the port's own row set, or its copy

        Reader(Edge edge, Port port, RunNode consumer) {
            this.edge = edge;
            this.port = port;
            this.consumer = consumer;
        }
    }

    /** What a node in a graph sees: its named ports, its settings, its input columns and its memory. */
    private static final class Context implements NodeContext {
        private final RunNode node;
        private final MetadataEmitter metadata;
        private final Map<String, List<RowSet>> in = new LinkedHashMap<>();
        private final Map<String, RowSet> out = new LinkedHashMap<>();
        private final Map<String, String> settings;

        Context(RunNode node, MetadataEmitter metadata) {
            this.node = node;
            this.metadata = metadata;
            node.inputs.forEach((port, rs) -> in.put(port, rs.stream().map(r -> r.rowSet).toList()));
            node.outputs.forEach((port, p) -> out.put(port, p.rowSet));
            Map<String, String> s = new LinkedHashMap<>();
            node.graphNode.config().forEach((k, v) -> {
                String text = scalar(v);
                if (text != null) {
                    s.put(k, text);
                }
            });
            this.settings = Collections.unmodifiableMap(s);
        }

        private static String scalar(Object v) {
            if (v instanceof BigDecimal d) {
                return Decimals.text(d);
            }
            if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) {
                return v.toString();
            }
            return null;
        }

        @Override
        public String nodeName() {
            return node.id();
        }

        @Override
        public Map<String, String> settings() {
            return settings;
        }

        @Override
        public Map<String, Object> config() {
            return node.graphNode.config();
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
                throw new IllegalStateException("Node " + node.id() + " has no input: it is the first node");
            }
            throw new IllegalStateException(rs == null
                ? "Node " + node.id() + " has nothing connected to its 'in' port"
                : "Node " + node.id() + " has " + rs.size() + " inputs on its 'in' port: read them with inputs(\"in\")");
        }

        @Override
        public RowSet output() {
            RowSet r = out.get("out");
            if (r != null) {
                return r;
            }
            throw new IllegalStateException(out.isEmpty()
                ? "Node " + node.id() + " has no output: it is the last node"
                : "Node " + node.id() + " has nothing connected to its 'out' port");
        }

        /** Whether the {@code in} port is connected; {@link #inputPorts()} lists every connected input. */
        @Override
        public boolean hasInput() {
            return in.containsKey("in");
        }

        /** Whether the {@code out} port is connected; {@link #outputPorts()} lists every connected output. */
        @Override
        public boolean hasOutput() {
            return out.containsKey("out");
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
        public RowSet input(String port) throws NodeException {
            List<RowSet> rs = in.get(port);
            if (rs == null) {
                throw new NodeException("Node " + node.id() + " has no input connected to port '" + port + "'");
            }
            if (rs.size() > 1) {
                throw new NodeException("Node " + node.id() + " has " + rs.size() + " inputs on port '" + port
                    + "': read them with inputs(\"" + port + "\")");
            }
            return rs.getFirst();
        }

        @Override
        public List<RowSet> inputs(String port) {
            return in.getOrDefault(port, List.of());
        }

        /** Null for any port with nothing connected: the graph was checked against the node's ports before the run. */
        @Override
        public RowSet output(String port) {
            return out.get(port);
        }

        @Override
        public Schema inputSchema(String port) {
            return node.graphNode.inputSchemas().get(port);
        }

        @Override
        public Schema inputSchema(String port, int index) {
            Schema s = node.graphNode.inputSchemas().get(port + "[" + index + "]");
            return s != null || index != 0 ? s : node.graphNode.inputSchemas().get(port);
        }
    }

    /** A node's readers have all finished while it wrote only to ports with nothing connected. */
    private static final class EveryReaderFinished extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        EveryReaderFinished() {
            super("every node reading it has finished", null, false, false);
        }
    }
}
