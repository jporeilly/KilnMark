package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.RunListener;
import io.kilnmark.kiln.graph.RunProgress;

/**
 * A run's log and progress, as the worker hears them (V19, docs/design/pipelines.md section 5,
 * "Seeing inside a run"). The engine's calls are kept and written to {@code pipeline_run_event} in
 * batches, with each progress snapshot (about every two seconds) and when the run ends; the snapshot
 * itself goes to {@code pipeline_run.progress}, and each step's rows a second and output fill, a point
 * at a time, to {@code pipeline_run_point} (appended, never rewritten), so the API, which may be another
 * container, reads them from the database. Every time is on the worker's clock: the snapshot says when the worker started on
 * the run ({@code workerStartedAt}) and, once it has finished, when it did, so pages never mix it with
 * the database's or the browser's clock.
 *
 * <p>Event text is the server's own words. Anything an engine says (a step's error, a stall) is the
 * event's detail, with the run's secrets blanked out, and is held back from callers as the run's other
 * messages are. At most {@value #MAX_EVENTS} events are kept, then one saying the log was cut short.
 * Nothing here ever fails the run: a database problem is logged and the log goes on without it.
 */
final class RunLog implements RunListener {

    private static final Logger LOG = Logger.getLogger(RunLog.class);

    /** Events kept per run; one more says the log was cut short. */
    static final int MAX_EVENTS = 10_000;
    static final int MAX_TEXT = 2_000;
    static final int MAX_DETAIL = 8_000;
    /** The least time between two progress writes, so a run writes its progress at most every two seconds. */
    static final long PROGRESS_EVERY_MILLIS = 2_000;
    /**
     * Points kept per run; when a run reaches this, its points are merged in pairs (the higher rate and
     * fill kept, so spikes survive) and each later point stands for twice as many snapshots.
     */
    static final int MAX_POINTS = 900;

    private final PipelineStore store;
    private final long runId;
    private final RunContext context;
    private final ObjectMapper json;
    private final Instant workerStarted = Instant.now();
    private final List<PipelineStore.Event> pending = new ArrayList<>();
    private int seq;              // guarded by this
    private boolean truncated;    // guarded by this
    private long progressWritten; // System.nanoTime() of the last progress write, 0 for none; guarded by this
    private ObjectNode lastProgress; // guarded by this
    private boolean finished;     // guarded by this: the worker is done; a late snapshot changes nothing
    // Rows a second: the steps' order, the points kept (as written), how many snapshots a point stands
    // for, and the point being filled (the highest rate and fill of its snapshots so far).
    private final List<String> seriesSteps = new ArrayList<>(); // guarded by this
    private final List<PipelineStore.Point> points = new ArrayList<>(); // guarded by this
    private int stride = 1;       // guarded by this
    private int inBucket;         // guarded by this
    private long bucketT;         // guarded by this
    private double[] bucketRates; // guarded by this
    private double[] bucketFills; // guarded by this
    private Set<String> gates = Set.of();
    private final Set<String> quarantining = new HashSet<>(); // gates said to have started setting rows aside
    private final Set<String> ended = new HashSet<>();        // steps said to have ended; guarded by this

    RunLog(PipelineStore store, long runId, RunContext context, ObjectMapper json) {
        this.store = store;
        this.runId = runId;
        this.context = context;
        this.json = json;
    }

    /** The pipeline's gate steps: the first rows a gate sends anywhere but on {@code out} are it setting rows aside. */
    void gates(Set<String> ids) {
        gates = Set.copyOf(ids);
    }

    /** When the worker started on the run, on its own clock: the origin of every page's times. */
    Instant workerStarted() {
        return workerStarted;
    }

    // ---- what the engine says ----

    @Override
    public void stepStarted(String step, Instant at) {
        add(at, step, "info", "step_started", "Step " + step + " started", null);
    }

    @Override
    public void firstRowIn(String step, Instant at) {
        add(at, step, "info", "first_row_in", "Step " + step + " received its first row", null);
    }

    @Override
    public void firstRowOut(String step, Instant at) {
        add(at, step, "info", "first_row_out", "Step " + step + " handed on its first row", null);
    }

    @Override
    public void lastRowOut(String step, Instant at) {
        add(at, step, "info", "last_row_out", "Step " + step + " handed on its last row", null);
    }

    @Override
    public void stepEnded(String step, RunResult.Outcome outcome, Instant at) {
        String level = switch (outcome) {
            case FAILED -> "error";
            case STOPPED -> "warn";
            default -> "info";
        };
        boolean again;
        synchronized (this) {
            again = !ended.add(step);
        }
        if (again) { // the engine found, once every step had ended, that it had not freed its memory
            add(at, step, level, "step_ended", "Step " + step + " counted as " + (outcome == RunResult.Outcome.FAILED
                ? "failed: it did not free all its memory" : outcome.name().toLowerCase(Locale.ROOT)), null);
            return;
        }
        String how = switch (outcome) {
            case FINISHED -> "finished";
            case ENDED_EARLY -> "ended early";
            case STOPPED -> "was stopped by the engine";
            case FAILED -> "failed";
        };
        add(at, step, level, "step_ended", "Step " + step + " " + how, null);
    }

    @Override
    public void note(Level level, String step, String text) {
        note(level, step, text, Instant.now());
    }

    /** The engine's own words may quote the data: they are the detail, under the server's words. */
    @Override
    public void note(Level level, String step, String text, Instant at) {
        String l = level.name().toLowerCase(Locale.ROOT);
        String words = switch (level) {
            case ERROR -> (step == null ? "The engine" : "Step " + step) + " reported an error";
            case WARN -> (step == null ? "The engine" : "Step " + step) + " reported a warning";
            case INFO -> (step == null ? "The engine" : "Step " + step) + " reported a note";
        };
        add(at == null ? Instant.now() : at, step, l, "engine_note", words, context.scrub(text));
    }

    @Override
    public void progress(RunProgress p) {
        // A gate sending its first rows anywhere but on, as a mark on the run's time axis.
        for (RunProgress.EdgeProgress e : p.edges()) {
            if (gates.contains(e.fromStep()) && !e.fromPort().equals("out") && e.rows() > 0
                && quarantining.add(e.fromStep())) {
                add(p.at(), e.fromStep(), "warn", "gate_quarantining", "Gate " + e.fromStep()
                    + " started setting rows aside", null);
            }
        }
        boolean last = p.steps().stream().allMatch(s -> s.state() == RunProgress.State.ENDED
            || s.state() == RunProgress.State.NOT_STARTED);
        String progress = null;
        List<PipelineStore.Point> append = new ArrayList<>();
        List<PipelineStore.Point> rewrite = null;
        String steps;
        int strideNow;
        synchronized (this) {
            if (finished) {
                return; // the worker has written its last snapshot, with when it finished
            }
            long now = System.nanoTime();
            lastProgress = progressNode(p);
            rewrite = sample(p, last, append);
            if (last || progressWritten == 0 || (now - progressWritten) / 1_000_000 >= PROGRESS_EVERY_MILLIS - 100) {
                progressWritten = now;
                progress = lastProgress.toString();
            }
            steps = stepsJson();
            strideNow = stride;
        }
        if (progress != null) {
            writeProgress(progress);
        }
        writePoints(steps, strideNow, append, rewrite);
        flush();
    }

    /**
     * The worker has finished with the run: the last snapshot says when, on the worker's clock (or, if
     * the engine never ran, says when the worker started), so pages measure the run on one clock. A
     * snapshot that reaches the log after this changes nothing.
     */
    void finish() {
        String progress;
        synchronized (this) {
            finished = true;
            ObjectNode p = lastProgress != null ? lastProgress : progressNode(null);
            p.put("workerFinishedAt", Instant.now().toString());
            progress = p.toString();
        }
        writeProgress(progress);
        flush();
    }

    /**
     * Folds a snapshot into the point being filled (the highest rate and fill of its snapshots), adding
     * the point to {@code append} once it stands for {@code stride} snapshots, or at the end. When the
     * run reaches {@link #MAX_POINTS}, merges its points in pairs and doubles the stride, and returns
     * the merged points to write instead; else null.
     */
    private List<PipelineStore.Point> sample(RunProgress p, boolean last, List<PipelineStore.Point> append) {
        if (seriesSteps.isEmpty()) {
            p.steps().forEach(s -> seriesSteps.add(s.name()));
        }
        int n = seriesSteps.size();
        if (inBucket == 0) {
            bucketT = Math.max(0, p.at().toEpochMilli() - workerStarted.toEpochMilli());
            bucketRates = new double[n];
            bucketFills = new double[n];
        }
        for (int i = 0; i < n; i++) {
            RunProgress.StepProgress s = p.step(seriesSteps.get(i));
            if (s != null) {
                bucketRates[i] = Math.max(bucketRates[i], s.rowsPerSecond());
                bucketFills[i] = Math.max(bucketFills[i], s.outputFill());
            }
        }
        inBucket++;
        if (inBucket < stride && !last) {
            return null;
        }
        inBucket = 0;
        PipelineStore.Point point = new PipelineStore.Point(bucketT, array(bucketRates, 10), array(bucketFills, 100));
        points.add(point);
        if (points.size() < MAX_POINTS) {
            append.add(point);
            return null;
        }
        List<PipelineStore.Point> merged = new ArrayList<>();
        for (int i = 0; i < points.size(); i += 2) {
            merged.add(i + 1 < points.size() ? merge(points.get(i), points.get(i + 1)) : points.get(i));
        }
        points.clear();
        points.addAll(merged);
        stride *= 2;
        return List.copyOf(merged);
    }

    /** Two points as one: the first's time, the higher rate and fill of each step. */
    private PipelineStore.Point merge(PipelineStore.Point a, PipelineStore.Point b) {
        return new PipelineStore.Point(a.t(), maxArray(a.rates(), b.rates()), maxArray(a.fills(), b.fills()));
    }

    private String maxArray(String a, String b) {
        try {
            com.fasterxml.jackson.databind.JsonNode x = json.readTree(a);
            com.fasterxml.jackson.databind.JsonNode y = json.readTree(b);
            ArrayNode out = json.createArrayNode();
            for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
                out.add(Math.max(x.path(i).asDouble(0), y.path(i).asDouble(0)));
            }
            return out.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return a;
        }
    }

    private String array(double[] values, int scale) {
        ArrayNode out = json.createArrayNode();
        for (double v : values) {
            out.add(Math.round(v * scale) / (double) scale);
        }
        return out.toString();
    }

    private String stepsJson() {
        ArrayNode out = json.createArrayNode();
        seriesSteps.forEach(out::add);
        return out.toString();
    }

    private void writePoints(String steps, int strideNow, List<PipelineStore.Point> append,
                             List<PipelineStore.Point> rewrite) {
        try {
            if (rewrite != null) {
                store.replacePoints(runId, steps, strideNow, rewrite);
            }
            for (PipelineStore.Point point : append) {
                store.addPoint(runId, steps, strideNow, point);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not store rows a second of run %d: %s", runId, context.scrub(e.getMessage()));
        }
    }

    private void writeProgress(String progress) {
        try {
            store.saveProgress(runId, progress);
        } catch (RuntimeException e) {
            LOG.warnf("Could not store the progress of run %d: %s", runId, context.scrub(e.getMessage()));
        }
    }

    // ---- what the server says ----

    /**
     * A gate's verdict, once the run has measured it: "Gate check quarantined 229 rows (email format:
     * 229)". The rules that failed and their counts are in the words, as the run's quality section
     * shows them to everyone; a rule's message and the gate's stop reason can hold values, so they are
     * the detail, held back as the engine's words are.
     */
    void gate(String step, QualityGate.Outcome g) {
        if (g == null) {
            return;
        }
        String text;
        String level;
        switch (g.decision()) {
            case "passed" -> {
                level = "info";
                text = "Gate " + step + " passed all " + count(g.rowsIn()) + " rows";
            }
            case "quarantined" -> {
                level = "warn";
                text = "Gate " + step + " quarantined " + count(g.rowsQuarantined()) + " rows";
            }
            case "stopped" -> {
                level = "error";
                text = "Gate " + step + " stopped the run";
            }
            default -> {
                level = "warn";
                text = "Gate " + step + " let " + count(g.rowsFailing()) + " failing rows through with a warning";
            }
        }
        List<String> failing = new ArrayList<>();
        List<String> why = new ArrayList<>();
        for (QualityGate.RuleOutcome r : g.rules()) {
            if (r.failing() > 0) {
                failing.add(r.name() + ": " + count(r.failing()));
            }
            if (r.message() != null && !r.status().equals("pass")) {
                why.add(r.name() + ": " + r.message());
            }
        }
        if (g.stopReason() != null) {
            why.add(g.stopReason());
        }
        server(step, level, "gate_verdict", failing.isEmpty() ? text : text + " (" + String.join(", ", failing) + ")",
            why.isEmpty() ? null : String.join("; ", why));
    }

    private static String count(long n) {
        return String.format(Locale.UK, "%,d", n);
    }

    /** An event of the server's own: {@code text} its words, {@code detail} anything quoting the engine or data. */
    void server(String step, String level, String kind, String text, String detail) {
        add(Instant.now(), step, level, kind, text, detail == null ? null : context.scrub(detail));
    }

    private synchronized void add(Instant at, String step, String level, String kind, String text, String detail) {
        if (truncated) {
            return;
        }
        if (seq >= MAX_EVENTS) {
            truncated = true;
            pending.add(new PipelineStore.Event(++seq, at, null, "warn", "log_truncated",
                "The log was cut short at " + count(MAX_EVENTS) + " events; later events were not kept", null));
            return;
        }
        pending.add(new PipelineStore.Event(++seq, at, step, level, kind, cut(text, MAX_TEXT), cut(detail, MAX_DETAIL)));
    }

    /** Writes the events waiting; never throws. A batch the database refuses is tried again event by event. */
    void flush() {
        List<PipelineStore.Event> batch;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(pending);
            pending.clear();
        }
        try {
            store.addEvents(runId, batch);
        } catch (RuntimeException e) {
            if (!dataError(e)) {
                // A timeout or a lost connection: trying each event would only wait again for each.
                LOG.warnf("Could not store %d events of run %d; they are dropped: %s", batch.size(), runId,
                    context.scrub(e.getMessage()));
                return;
            }
            LOG.warnf("Could not store %d events of run %d together, trying each: %s", batch.size(), runId,
                context.scrub(e.getMessage()));
            int lost = 0;
            for (PipelineStore.Event one : batch) {
                try {
                    store.addEvents(runId, List.of(one));
                } catch (RuntimeException again) {
                    lost++;
                }
            }
            if (lost > 0) {
                LOG.warnf("Could not store %d events of run %d", lost, runId);
            }
        }
    }

    /**
     * Whether the database refused the data itself (SQLState class 22, data exception, or 23, a
     * constraint): then one event may be at fault and the others can be stored on their own.
     */
    static boolean dataError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return state.startsWith("22") || state.startsWith("23");
            }
        }
        return false;
    }

    /** How many events have been kept, the one saying the log was cut short included. */
    synchronized int size() {
        return seq;
    }

    /** Cut to {@code max} characters, without NUL characters (the database cannot store them). */
    static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String clean = s.indexOf('\u0000') < 0 ? s : s.replace("\u0000", "");
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }

    // ---- the snapshot and the series ----

    /** The snapshot as {@code pipeline_run.progress} keeps it: counts, states and times, never values. */
    String progressJson(RunProgress p) {
        return progressNode(p).toString();
    }

    private ObjectNode progressNode(RunProgress p) {
        ObjectNode root = json.createObjectNode();
        root.put("at", String.valueOf(p == null ? Instant.now() : p.at()));
        root.put("workerStartedAt", workerStarted.toString());
        if (p == null || p.slowest() == null) {
            root.putNull("slowest");
        } else {
            root.put("slowest", p.slowest());
        }
        ArrayNode steps = root.putArray("steps");
        ArrayNode edges = root.putArray("edges");
        if (p == null) {
            return root;
        }
        for (RunProgress.StepProgress s : p.steps()) {
            ObjectNode n = steps.addObject();
            n.put("step", s.name());
            n.put("state", s.state().name().toLowerCase(Locale.ROOT));
            n.put("rowsIn", s.rowsIn());
            n.put("rowsOut", s.rowsOut());
            n.put("rowsPerSecond", Math.round(s.rowsPerSecond() * 10) / 10.0);
            time(n, "startedAt", s.startedAt());
            time(n, "firstRowAt", s.firstRowAt());
            time(n, "lastRowAt", s.lastRowAt());
            time(n, "finishedAt", s.finishedAt());
            n.put("waitingForRowsMs", s.waitingForRowsNanos() / 1_000_000);
            n.put("heldUpMs", s.heldUpNanos() / 1_000_000);
            n.put("outputFill", Math.round(s.outputFill() * 100) / 100.0);
        }
        for (RunProgress.EdgeProgress e : p.edges()) {
            ObjectNode n = edges.addObject();
            n.put("from", e.fromStep());
            n.put("fromPort", e.fromPort());
            n.put("to", e.toStep());
            n.put("toPort", e.toPort());
            n.put("rows", e.rows());
            n.put("queued", e.queued());
            n.put("capacity", e.capacity());
            n.put("full", e.full());
            n.put("empty", e.empty());
            n.put("fill", Math.round(e.fill() * 100) / 100.0);
        }
        return root;
    }

    /** How many points the series holds; for tests. */
    synchronized int points() {
        return points.size();
    }

    private static void time(ObjectNode n, String key, Instant at) {
        if (at == null) {
            n.putNull(key);
        } else {
            n.put(key, at.toString());
        }
    }
}
