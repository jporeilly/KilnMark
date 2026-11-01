package io.kilnmark.server.pipelines;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.assistant.CallerRate;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.Sights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Preview at a node (docs/design/pipelines.md, "Preview at a node"; docs/guide/pipelines.md,
 * "Preview a node"): the first rows leaving one output of one node, from the pipeline as it stands,
 * on the API's own process while the person waits.
 *
 * <p>Only the chosen node and the nodes upstream of it run, and only reads and the nodes that work
 * on rows in memory may ({@link NodePreview#plan}); the chosen output feeds a collector that takes
 * the rows asked for and finishes, so the engine ends the nodes before it early. Nothing is
 * written, stored or logged.
 *
 * <p>The same rules as previewing the sources involved: stewards and admins only (the resource
 * says so); reads resolve their saved sources as a run's do (credentials, allowed hosts) inside a
 * run context naming the caller; for all but admins, columns marked sensitive may only pass through
 * untouched and are masked where they leave, and a preview in which any node looks inside one is
 * refused ({@link NodePreview#hidden}); a failure's message, which may quote values, is held back
 * as a run's is.
 *
 * <p>Limits: one preview per person at a time and {@code previews-per-minute} (6) per person, and
 * {@code preview-concurrency} (2) at once in this process, all checked before any work;
 * {@code preview-max-nodes} (50) and {@code preview-max-reads} (10); rows 1 to 1,000;
 * {@code preview-memory-limit-mb} (256) of Arrow memory, with each stateful node's own limit lowered
 * to its share of it; and {@code preview-timeout} (30 seconds) of wall-clock time. The work runs on
 * a thread of its own, so the request is answered in time whatever a node does: a preview still
 * stopping then ends in the background, its slot held until it has, and is logged and counted.
 */
@ApplicationScoped
public class PipelinePreviews {

    private static final Logger LOG = Logger.getLogger(PipelinePreviews.class);

    /** The caller's sight of the assets read: whether they see sensitive columns, and each asset's marks. */
    interface Viewer {
        boolean mayUnmask();

        Set<String> marks(String urn);

        /** Whether any asset of the source has a column marked sensitive; true unless known otherwise. */
        default boolean sourceHasMarks(String source) {
            return true;
        }
    }

    /** The limits, from {@code kilnmark.pipelines.*}. */
    record Limits(long memoryBytes, int rowSetCapacity, Duration timeout, int perMinute, int concurrency,
                  int maxYamlKb, int maxNodes, int maxReads, Duration grace, Duration stopWait) {

        /** The limits tests use most: 50 nodes, 10 reads, 2 seconds' grace, 5 seconds to stop. */
        Limits(long memoryBytes, int rowSetCapacity, Duration timeout, int perMinute, int concurrency, int maxYamlKb) {
            this(memoryBytes, rowSetCapacity, timeout, perMinute, concurrency, maxYamlKb, 50, 10, Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        }

        static Limits of(KilnmarkConfig config) {
            KilnmarkConfig.Pipelines p = config.pipelines();
            return new Limits(Math.max(16, p.previewMemoryLimitMb()) << 20, Math.max(1, p.rowSetCapacity()),
                p.previewTimeout(), Math.max(1, p.previewsPerMinute()), Math.max(1, p.previewConcurrency()),
                Math.max(1, p.maxYamlKb()), Math.max(1, p.previewMaxNodes()), Math.max(1, p.previewMaxReads()),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        }

        NodePreview.Caps caps() {
            return new NodePreview.Caps(maxNodes, maxReads, Math.max(1, memoryBytes >> 20));
        }
    }

    private final NodeFactories nodes;
    private final PipelineStore store;
    private final PipelineSources sources;
    private final Limits limits;
    private final CallerRate rate;
    private final Semaphore slots;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Previews answered while their nodes were still stopping, not yet ended. */
    private final AtomicInteger stopping = new AtomicInteger();

    /** The server's variables (${vars.name}); null in tests that build previews by hand. */
    @Inject
    io.kilnmark.server.variables.VariableService variables;

    private Map<String, Object> variables() {
        return variables == null ? Map.of() : variables.snapshot();
    }

    @Inject
    public PipelinePreviews(NodeFactories nodes, PipelineStore store, PipelineSources sources, KilnmarkConfig config) {
        this(nodes, store, sources, Limits.of(config));
    }

    PipelinePreviews(NodeFactories nodes, PipelineStore store, PipelineSources sources, Limits limits) {
        this.nodes = nodes;
        this.store = store;
        this.sources = sources;
        this.limits = limits;
        this.rate = new CallerRate(limits.perMinute());
        this.slots = new Semaphore(limits.concurrency());
    }

    /** Previews answered while their nodes were still stopping and not ended yet, for monitoring. */
    public int stillStopping() {
        return stopping.get();
    }

    /**
     * Previews {@code node}'s output {@code port} of pipeline YAML as it stands, saved or not, for
     * {@code caller} with {@code role}, masked through the request's {@code sights}. {@code abandoned}
     * says the HTTP request was closed.
     */
    public PipelineViews.NodePreviewView preview(String yaml, String node, String port, Integer rows, String caller,
                                                 AccessRole role, Sights sights, BooleanSupplier abandoned) {
        return preview(yaml, node, port, rows, caller, role, viewer(sights), abandoned);
    }

    /** As {@link #preview}, for a saved version of a pipeline. */
    public PipelineViews.NodePreviewView previewVersion(String name, int n, String node, String port, Integer rows,
                                                        String caller, AccessRole role, Sights sights,
                                                        BooleanSupplier abandoned) {
        PipelineStore.Version v = store.version(name, n).orElseThrow(() -> PipelineService.fail(
            Response.Status.NOT_FOUND, "There is no version " + n + " of a pipeline called " + name));
        return preview(v.yaml(), node, port, rows, caller, role, viewer(sights), abandoned);
    }

    private static Viewer viewer(Sights sights) {
        return new Viewer() {
            @Override
            public boolean mayUnmask() {
                return sights.mayUnmask();
            }

            @Override
            public Set<String> marks(String urn) {
                return sights.marks(urn);
            }

            @Override
            public boolean sourceHasMarks(String source) {
                return sights.sourceHasMarks(source);
            }
        };
    }

    PipelineViews.NodePreviewView preview(String yaml, String node, String port, Integer rows, String caller,
                                          AccessRole role, Viewer viewer, BooleanSupplier abandoned) {
        checkSize(yaml);
        int n = rows == null ? NodePreview.DEFAULT_ROWS : rows;
        if (n < 1 || n > NodePreview.MAX_ROWS) {
            throw new IllegalArgumentException("rows: from 1 to " + NodePreview.MAX_ROWS);
        }
        // Who may start one, before any work: one each at a time, a few at once, so many a minute.
        String who = caller == null || caller.isBlank() ? "anonymous" : caller;
        if (!running.add(who)) {
            throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "You already have a preview running. Wait "
                + "for it to finish, then try again.");
        }
        java.util.concurrent.atomic.AtomicBoolean handedOn = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if (!slots.tryAcquire()) {
                throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "Kilnmark is running as many previews "
                    + "as it allows at once. Try again in a moment.");
            }
            try {
                if (!rate.allow(who, Instant.now())) {
                    throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "You have previewed "
                        + limits.perMinute() + " times in the last minute. Wait a minute, then try again.");
                }
                Prepared p = prepare(yaml, node, port, viewer);
                // handedOn is set once the job's thread has started: it then releases the slots.
                return run(p, n, who, role == null ? AccessRole.STEWARD : role, viewer, abandoned, handedOn);
            } finally {
                if (!handedOn.get()) {
                    slots.release();
                }
            }
        } finally {
            if (!handedOn.get()) {
                running.remove(who);
            }
            Thread.interrupted(); // never hand the request's thread back to the pool interrupted
        }
    }

    /** What is worked out before anything connects. */
    private record Prepared(NodePreview.Plan plan, PipelineCompilerPort.Compilation checked, String port,
                            Map<String, Object> variables) {
    }

    private Prepared prepare(String yaml, String node, String port, Viewer viewer) {
        NodePreview.Plan plan = NodePreview.plan(yaml, node, limits.caps());
        Map<String, Object> variables = variables();
        PipelineCompilerPort.Compilation checked = KilnPipelineCompiler.compile(plan.definition(), nodes, false,
            variables);
        if (!viewer.mayUnmask() && hasQueryRead(checked)) {
            throw PipelineService.fail(Response.Status.FORBIDDEN, QUERY_NEEDS_UNMASK);
        }
        if (!checked.valid()) {
            throw new IllegalArgumentException("Fix the pipeline before previewing " + plan.node() + ": "
                + problems(checked.problems()));
        }
        String out = NodePreview.port(checked, plan.node(), port);
        // Refused before anything connects, by the reads as their settings name them; checked again
        // once the reads are resolved (a table's name in the database's own case, say).
        try {
            NodePreview.hidden(checked, readHidden(checked, null, viewer), gateFields(checked, null), plan.node());
        } catch (NodePreview.Refused e) {
            throw PipelineService.fail(Response.Status.FORBIDDEN, e.getMessage());
        }
        return new Prepared(plan, checked, out, variables);
    }

    /** One preview's work on its own thread: create the nodes, wait for the go-ahead, run them. */
    private final class Job {
        final CompletableFuture<PipelineCompilerPort.Compilation> created = new CompletableFuture<>();
        /** The columns to mask, to run; null not to run. */
        final CompletableFuture<NodePreview.Hidden> go = new CompletableFuture<>();
        final CompletableFuture<NodePreview.Outcome> done = new CompletableFuture<>();
        /** Answered while its thread was still running: it is counted and logged when it ends. */
        final AtomicBoolean orphaned = new AtomicBoolean();
        /** Its thread has given the slots back (guarded by the job). */
        boolean finished;
        volatile Thread thread;
        /** The engine running it, for the nodes it stopped waiting for. */
        final java.util.concurrent.atomic.AtomicReference<io.kilnmark.kiln.graph.KilnGraphRunner> runner =
            new java.util.concurrent.atomic.AtomicReference<>();
    }

    private PipelineViews.NodePreviewView run(Prepared p, int rows, String who, AccessRole role, Viewer viewer,
                                              BooleanSupplier abandoned, AtomicBoolean handedOn) {
        NodePreview.Plan plan = p.plan();
        RunContext context = new RunContext(plan.definition().name(), 0, who, role);
        long started = System.nanoTime();
        long deadline = started + limits.timeout().toNanos();
        List<String> waits = NodePreview.waits(p.checked());
        Job job = new Job();
        job.thread = Thread.ofVirtual().name("kilnmark-preview-" + plan.node()).unstarted(() -> work(job, context, p,
            rows, who, started));
        job.thread.start();
        handedOn.set(true);
        try {
            PipelineCompilerPort.Compilation c = await(job, job.created, deadline, abandoned, waits);
            if (!c.valid() || c.graph() == null) {
                throw PreviewFailed.of(context.scrub("The preview could not start: " + problems(c.problems())));
            }
            NodePreview.Hidden masks;
            Map<String, Set<String>> hidden;
            try {
                hidden = readHidden(c, context, viewer);
                masks = NodePreview.hidden(c, hidden, gateFields(c, context), plan.node());
            } catch (NodePreview.Refused e) {
                throw PipelineService.fail(Response.Status.FORBIDDEN, e.getMessage());
            } catch (RuntimeException e) {
                checkStop(deadline, abandoned, waits); // out of time while looking: say so, not a server error
                throw e;
            }
            checkStop(deadline, abandoned, waits);
            boolean marked = !viewer.mayUnmask() && hidden.values().stream().anyMatch(s -> !s.isEmpty());
            job.go.complete(masks);
            NodePreview.Outcome o = await(job, job.done, deadline, abandoned, waits);
            RunResult result = o.result();
            if (result.status() != RunResult.Status.SUCCEEDED) {
                String at = result.nodes().stream().filter(s -> s.outcome() == RunResult.Outcome.FAILED)
                    .map(RunResult.NodeResult::name).filter(s -> !NodePreview.COLLECTOR.equals(s)).findFirst()
                    .orElse(null);
                // Its words may quote values from the rows: held back as a run's are.
                throw PreviewFailed.of(marked ? "The preview stopped" + (at == null ? "" : " at node " + at) + ". "
                    + PipelineService.FOR_DESIGNERS : context.scrub(result.error()));
            }
            return view(c, plan.node(), p.port(), o.rows(), (System.nanoTime() - started) / 1_000_000, waits);
        } catch (Stop s) {
            end(job, true);
            throw stopped(s.why, waits);
        } catch (PreviewFailed e) {
            throw unprocessable(e.getMessage());
        } finally {
            end(job, false); // a job not told to run closes its nodes; one still running is stopped
        }
    }

    /** The job's thread: owns the slots from here and gives them back when it has ended. */
    private void work(Job job, RunContext context, Prepared p, int rows, String who, long started) {
        try {
            PipelineCompilerPort.Compilation c = context.run(() -> KilnPipelineCompiler.compile(p.plan().definition(),
                nodes, true, p.variables()));
            job.created.complete(c);
            PipelineGraph graph = c.graph();
            if (graph == null) {
                return;
            }
            NodePreview.Hidden masks;
            try {
                masks = job.go.get();
            } catch (InterruptedException e) {
                masks = null;
            }
            if (masks == null) {
                PipelineExecution.closeNodes(graph);
                return;
            }
            try {
                job.done.complete(NodePreview.run(graph, p.plan().node(), p.port(), rows, masks, limits.memoryBytes(),
                    limits.rowSetCapacity(), limits.timeout(), limits.stopWait(), job.runner));
            } catch (IllegalArgumentException e) {
                PipelineExecution.closeNodes(graph); // refused before its nodes started
                job.done.completeExceptionally(PreviewFailed.of(context.scrub("The preview could not start: "
                    + e.getMessage())));
            }
        } catch (Throwable t) {
            job.created.completeExceptionally(t);
            job.done.completeExceptionally(t);
        } finally {
            job.created.completeExceptionally(new IllegalStateException("ended"));
            job.done.completeExceptionally(new IllegalStateException("ended"));
            // Nodes the engine stopped waiting for still hold their slot until they end.
            io.kilnmark.kiln.graph.KilnGraphRunner runner = job.runner.get();
            for (Thread left : runner == null ? List.<Thread>of() : runner.threadsLeftRunning()) {
                boolean again = false;
                while (left.isAlive()) {
                    try {
                        left.join();
                    } catch (InterruptedException e) {
                        again = true;
                    }
                }
                if (again) {
                    LOG.debug("interrupted while a preview's node ended");
                }
            }
            slots.release();
            running.remove(who);
            synchronized (job) {
                job.finished = true;
                if (job.orphaned.get()) {
                    int left = stopping.updateAndGet(x -> Math.max(0, x - 1));
                    LOG.infof("A preview at %s ended %d ms after it started, after its request had been answered; "
                        + "previews still stopping: %d", p.plan().node(), (System.nanoTime() - started) / 1_000_000,
                        left);
                }
            }
        }
    }

    /**
     * Ends the job's part in the request: tells a job not yet running not to run, interrupts one
     * still running when {@code stop}, and waits a moment for its thread. One still running then is
     * counted, logged, and ends on its own, holding its slots until it has.
     */
    private void end(Job job, boolean stop) {
        job.go.complete(null);
        Thread t = job.thread;
        if (t == null) {
            return;
        }
        if (stop) {
            t.interrupt();
        }
        boolean ended;
        try {
            ended = t.join(limits.grace());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ended = !t.isAlive();
        }
        synchronized (job) {
            if (!ended && !job.finished && job.orphaned.compareAndSet(false, true)) {
                int now = stopping.incrementAndGet();
                LOG.warnf("A preview's nodes had not stopped %s after it was answered; left to end on its own "
                    + "(previews still stopping: %d)", limits.grace(), now);
            }
        }
    }

    /** Waits for {@code f} until {@code deadline}, giving up when the request is closed. */
    private <T> T await(Job job, CompletableFuture<T> f, long deadline, BooleanSupplier abandoned, List<String> waits) {
        while (true) {
            checkStop(deadline, abandoned, waits);
            long left = Math.min(deadline - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(100));
            try {
                return f.get(Math.max(1, left), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // look again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Stop("abandoned");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof PreviewFailed pf) {
                    throw pf;
                }
                if (cause instanceof InterruptedException) {
                    throw new Stop(System.nanoTime() - deadline >= 0 ? "timeout" : "abandoned");
                }
                String message = cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
                throw PreviewFailed.of("The preview could not start: " + message);
            }
        }
    }

    private static void checkStop(long deadline, BooleanSupplier abandoned, List<String> waits) {
        if (System.nanoTime() - deadline >= 0) {
            throw new Stop("timeout");
        }
        if (abandoned != null && abandoned.getAsBoolean()) {
            throw new Stop("abandoned");
        }
    }

    /** The preview is out of time, or its request was closed. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String why;

        Stop(String why) {
            super(why, null, false, false);
            this.why = why;
        }
    }

    /** The preview ran, or tried to, and could not give rows (422). */
    private static final class PreviewFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PreviewFailed(String message) {
            super(message, null, false, false);
        }

        static PreviewFailed of(String message) {
            return new PreviewFailed(message);
        }
    }

    /**
     * Each read node's columns hidden from the caller (none for an admin), under every URN its asset
     * may be catalogued by: as the settings name it, as the catalogue escapes a file's path, and, with
     * {@code context}, as its connector resolved it.
     */
    private static Map<String, Set<String>> readHidden(PipelineCompilerPort.Compilation c, RunContext context,
                                                       Viewer viewer) {
        if (viewer.mayUnmask()) {
            return new HashMap<>();
        }
        return readMarks(c, context, viewer::marks, viewer::sourceHasMarks);
    }

    /** Why a pipeline with a query read is refused to a caller who may not see unmasked values. */
    static final String QUERY_NEEDS_UNMASK = "Only a designer who may see unmasked values writes or previews a read "
        + "that runs a query, since a query can read any column of the source";

    /** Whether any read node of {@code c} runs a query (ADR 0028). */
    static boolean hasQueryRead(PipelineCompilerPort.Compilation c) {
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (ReadNodeFactory.TYPE.equals(s.type())
                && ReadNodeFactory.isQuery(c.configs().getOrDefault(s.id(), Map.of()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * For each read node, the columns marked sensitive in what it reads (any of the URNs it may be
     * catalogued under), and the first such asset ({@code firstMarked}, when given), as {@code marksOf}
     * gives them. A read that runs a query may read any column of its source, so when the source has
     * any column marked sensitive ({@code sourceMarked}) every column it reads is marked.
     */
    static Map<String, Set<String>> readMarks(PipelineCompilerPort.Compilation c, RunContext context,
                                              java.util.function.Function<String, Set<String>> marksOf,
                                              java.util.function.Predicate<String> sourceMarked) {
        Map<String, Set<String>> out = new HashMap<>();
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (!ReadNodeFactory.TYPE.equals(s.type())) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
            Set<String> marks = new LinkedHashSet<>();
            if (ReadNodeFactory.isQuery(config)) {
                String source = PipelineSources.text(config, "source");
                if (source == null || sourceMarked.test(source)) {
                    marks.add(io.kilnmark.server.quality.QualityStore.EVERY_COLUMN);
                }
                out.put(s.id(), marks);
                continue;
            }
            readUrns(config, context).forEach(u -> marks.addAll(marksOf.apply(u)));
            out.put(s.id(), marks);
        }
        return out;
    }

    /** The URNs a read node may be catalogued under, for marks and messages. */
    static Set<String> readUrnsOf(Map<String, Object> readConfig, RunContext context) {
        return readUrns(readConfig, context);
    }

    private static Set<String> readUrns(Map<String, Object> readConfig, RunContext context) {
        String source = PipelineSources.text(readConfig, "source");
        String asset = PipelineSources.text(readConfig, "asset");
        Set<String> urns = new LinkedHashSet<>();
        urns.add(ReadNodeFactory.urn(readConfig));
        if (asset != null) {
            urns.add(PipelineSources.urn(source, ReadNodeFactory.escapedFilePath(asset)));
        }
        if (context != null) {
            urns.add(context.readUrn(source, asset, null));
        }
        urns.remove(null);
        return urns;
    }

    /**
     * For each gate, the columns its rules check: its own rules', and, when it checks the asset's
     * rules, those of the asset read upstream (as a run's gate takes them). Null for a gate whose
     * rules cannot be read, so it counts as checking every column.
     */
    private Map<String, Set<String>> gateFields(PipelineCompilerPort.Compilation c, RunContext context) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        c.nodes().forEach(s -> types.putIfAbsent(s.id(), s.type()));
        List<Edge> edges = new ArrayList<>();
        c.edges().forEach(e -> edges.add(new Edge(new PortRef(e.fromNode(), e.fromPort()), new PortRef(e.toNode(),
            e.toPort()))));
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (!GateNodeFactory.TYPE.equals(s.type())) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
            Set<String> fields = new LinkedHashSet<>();
            try {
                for (Rule r : GateRules.read(config.get("rules"), null)) {
                    ruleColumns(r, fields);
                }
                if (GateNodeFactory.assetRules(config)) {
                    GateNodeFactory.Upstream up = GateNodeFactory.upstream(s.id(), config, edges, types);
                    if (up.read() != null) {
                        for (String urn : readUrns(c.configs().getOrDefault(up.read(), Map.of()), context)) {
                            for (Rule r : sources == null ? List.<Rule>of() : sources.rules(urn)) {
                                ruleColumns(r, fields);
                            }
                        }
                    }
                }
                out.put(s.id(), fields);
            } catch (RuntimeException e) {
                out.put(s.id(), null);
            }
        }
        return out;
    }

    /**
     * Adds the columns a gate's rule checks, as {@code RowChecks} finds them ({@code unique} by its
     * {@code fields}, or its field); throws for a rule whose columns are not known, so the gate counts
     * as checking every column.
     */
    static void ruleColumns(Rule r, Set<String> into) {
        switch (r.type()) {
            case ROW_COUNT, ROW_COUNT_CHANGE -> {
                // counts rows only
            }
            case UNIQUE -> {
                if (r.params() != null && r.params().has("fields")) {
                    r.params().get("fields").forEach(n -> into.add(n.asText()));
                } else {
                    into.add(java.util.Objects.requireNonNull(r.field(), "a unique rule's columns"));
                }
            }
            case NOT_NULL, ALLOWED_VALUES, MATCHES_PATTERN, IN_RANGE, FRESHNESS ->
                into.add(java.util.Objects.requireNonNull(r.field(), "a rule's column"));
            default -> throw new IllegalArgumentException("columns not known for rules of type " + r.type());
        }
    }

    private static PipelineViews.NodePreviewView view(PipelineCompilerPort.Compilation c, String node, String port,
                                                      NodePreview.Collector rows, long millis, List<String> waits) {
        List<PipelineViews.PreviewColumn> columns = new ArrayList<>();
        if (!rows.fields.isEmpty()) {
            rows.fields.forEach(f -> columns.add(new PipelineViews.PreviewColumn(f.getName(), ArrowKinds.kind(f), null)));
        } else {
            c.nodes().stream().filter(s -> s.id().equals(node)).findFirst()
                .map(s -> s.columns().get(port)).ifPresent(known -> known.forEach(k ->
                    columns.add(new PipelineViews.PreviewColumn(k.name(), k.type(), null))));
        }
        List<String> masked = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (rows.masked(i, columns.get(i).name())) {
                masked.add(columns.get(i).name());
            }
        }
        return new PipelineViews.NodePreviewView(node, port, node + "." + port, columns, List.copyOf(rows.rows),
            rows.rows.size(), rows.truncated, Map.of(), rows.complete && !rows.truncated, millis, masked, waits);
    }

    private WebApplicationException stopped(String why, List<String> waits) {
        if ("abandoned".equals(why)) {
            return unprocessable("The preview was stopped: its request was closed.");
        }
        long seconds = Math.max(1, Math.round(limits.timeout().toMillis() / 1000.0));
        return unprocessable("The preview took longer than " + seconds + (seconds == 1 ? " second" : " seconds")
            + ", so it was stopped."
            + (waits.isEmpty() ? "" : " " + String.join(" ", waits))
            + " Preview a node nearer the start, or set a row limit on the read node while you try the pipeline out.");
    }

    /** 422: the request was understood, but the preview could not give rows. */
    static WebApplicationException unprocessable(String message) {
        return new WebApplicationException(Response.status(422)
            .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON).entity(new io.kilnmark.server.api.ApiError(message))
            .build());
    }

    private void checkSize(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml: send the pipeline's YAML");
        }
        int size = yaml.getBytes(StandardCharsets.UTF_8).length;
        if (size > limits.maxYamlKb() * 1024) {
            throw new IllegalArgumentException("yaml: the pipeline is " + ((size + 1023) / 1024) + " KB; this server "
                + "accepts at most " + limits.maxYamlKb() + " KB (kilnmark.pipelines.max-yaml-kb)");
        }
    }

    private static String problems(List<PipelineCompilerPort.Problem> problems) {
        List<String> out = new ArrayList<>();
        for (PipelineCompilerPort.Problem p : problems.subList(0, Math.min(3, problems.size()))) {
            out.add((p.line() > 0 ? "line " + p.line() + ": " : "") + p.message());
        }
        return String.join("; ", out) + (problems.size() > 3 ? "; and " + (problems.size() - 3) + " more" : "");
    }
}
