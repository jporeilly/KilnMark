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
 * itself goes to {@code pipeline_run.progress}, and a series of them, for rows a second over time, to
 * {@code pipeline_run.progress_series}, so the API, which may be another container, reads them from
 * the database. Every time is on the worker's clock: the snapshot says when the worker started on
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
    /** Points kept in the series of snapshots; when it fills, every other one goes and later ones are kept half as often. */
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
    // The series: the steps' order, one point per kept snapshot, and how many snapshots make a point.
    private final List<String> seriesSteps = new ArrayList<>(); // guarded by this
    private final List<ObjectNode> points = new ArrayList<>();  // guarded by this
    private int stride = 1;       // guarded by this
    private long snapshots;       // guarded by this
    private final Set<String> quarantining = new HashSet<>(); // gates said to have started setting rows aside

    RunLog(PipelineStore store, long runId, RunContext context, ObjectMapper json) {
        this.store = store;
        this.runId = runId;
        this.context = context;
        this.json = json;
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
        // A gate setting its first rows aside, as a mark on the run's time axis.
        for (RunProgress.EdgeProgress e : p.edges()) {
            if (e.fromPort().equals("quarantine") && e.rows() > 0 && quarantining.add(e.fromStep())) {
                add(p.at(), e.fromStep(), "warn", "gate_quarantining", "Gate " + e.fromStep()
                    + " started setting rows aside", null);
            }
        }
        boolean ended = p.steps().stream().allMatch(s -> s.state() == RunProgress.State.ENDED
            || s.state() == RunProgress.State.NOT_STARTED);
        String progress;
        String series;
        synchronized (this) {
            long now = System.nanoTime();
            lastProgress = progressNode(p);
            boolean point = ended || snapshots++ % stride == 0;
            if (point) {
                addPoint(p);
            }
            if (!ended && progressWritten != 0 && (now - progressWritten) / 1_000_000 < PROGRESS_EVERY_MILLIS - 100) {
                progress = null;
                series = null;
            } else {
                progressWritten = now;
                progress = lastProgress.toString();
                series = seriesJson();
            }
        }
        if (progress != null) {
            writeProgress(progress, series);
        }
        flush();
    }

    /**
     * The worker has finished with the run: the last snapshot says when, on the worker's clock (or, if
     * the engine never ran, says when the worker started), so pages measure the run on one clock.
     */
    void finish() {
        String progress;
        String series;
        synchronized (this) {
            ObjectNode p = lastProgress != null ? lastProgress : progressNode(null);
            p.put("workerFinishedAt", Instant.now().toString());
            progress = p.toString();
            series = points.isEmpty() ? null : seriesJson();
        }
        writeProgress(progress, series);
        flush();
    }

    private void writeProgress(String progress, String series) {
        try {
            store.saveProgress(runId, progress, series);
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

    /** One point of the series: when (ms from the worker's start), each step's rows a second and output fill. */
    private void addPoint(RunProgress p) {
        if (seriesSteps.isEmpty()) {
            p.steps().forEach(s -> seriesSteps.add(s.name()));
        }
        ObjectNode point = json.createObjectNode();
        point.put("t", Math.max(0, p.at().toEpochMilli() - workerStarted.toEpochMilli()));
        ArrayNode r = point.putArray("r");
        ArrayNode q = point.putArray("q");
        for (String id : seriesSteps) {
            RunProgress.StepProgress s = p.step(id);
            r.add(s == null ? 0 : Math.round(s.rowsPerSecond() * 10) / 10.0);
            q.add(s == null ? 0 : Math.round(s.outputFill() * 100) / 100.0);
        }
        points.add(point);
        if (points.size() >= MAX_POINTS) {
            for (int i = points.size() - 2; i > 0; i -= 2) { // keep the first and the last
                points.remove(i);
            }
            stride *= 2;
        }
    }

    private String seriesJson() {
        ObjectNode root = json.createObjectNode();
        ArrayNode steps = root.putArray("steps");
        seriesSteps.forEach(steps::add);
        root.put("stride", stride);
        ArrayNode out = root.putArray("points");
        points.forEach(out::add);
        return root.toString();
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
