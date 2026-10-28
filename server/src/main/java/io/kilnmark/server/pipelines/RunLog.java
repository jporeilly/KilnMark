package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * itself goes to {@code pipeline_run.progress}, so the API, which may be another container, reads it
 * from the database.
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

    private final PipelineStore store;
    private final long runId;
    private final RunContext context;
    private final ObjectMapper json;
    private final List<PipelineStore.Event> pending = new ArrayList<>();
    private int seq;             // guarded by this
    private boolean truncated;   // guarded by this
    private long progressWritten; // System.nanoTime() of the last progress write, 0 for none

    RunLog(PipelineStore store, long runId, RunContext context, ObjectMapper json) {
        this.store = store;
        this.runId = runId;
        this.context = context;
        this.json = json;
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

    /** The engine's own words may quote the data: they are the detail, under the server's words. */
    @Override
    public void note(Level level, String step, String text) {
        String l = level.name().toLowerCase(Locale.ROOT);
        String words = switch (level) {
            case ERROR -> (step == null ? "The engine" : "Step " + step) + " reported an error";
            case WARN -> (step == null ? "The engine" : "Step " + step) + " reported a warning";
            case INFO -> (step == null ? "The engine" : "Step " + step) + " reported a note";
        };
        add(Instant.now(), step, l, "engine_note", words, context.scrub(text));
    }

    @Override
    public void progress(RunProgress p) {
        long now = System.nanoTime();
        boolean ended = p.steps().stream().allMatch(s -> s.state() == RunProgress.State.ENDED
            || s.state() == RunProgress.State.NOT_STARTED);
        if (ended || progressWritten == 0 || (now - progressWritten) / 1_000_000 >= PROGRESS_EVERY_MILLIS - 100) {
            progressWritten = now;
            try {
                store.saveProgress(runId, progressJson(p));
            } catch (RuntimeException e) {
                LOG.debugf("Could not store the progress of run %d: %s", runId, context.scrub(e.getMessage()));
            }
        }
        flush();
    }

    // ---- what the server says ----

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
                "The log was cut short at " + String.format(Locale.UK, "%,d", MAX_EVENTS)
                    + " events; later events were not kept", null));
            return;
        }
        pending.add(new PipelineStore.Event(++seq, at, step, level, kind, cut(text, MAX_TEXT), cut(detail, MAX_DETAIL)));
    }

    /** Writes the events waiting; never throws. */
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
            LOG.debugf("Could not store %d events of run %d: %s", batch.size(), runId, context.scrub(e.getMessage()));
        }
    }

    /** How many events have been kept, the one saying the log was cut short included. */
    synchronized int size() {
        return seq;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** The snapshot as {@code pipeline_run.progress} keeps it: counts, states and times, never values. */
    String progressJson(RunProgress p) {
        ObjectNode root = json.createObjectNode();
        root.put("at", String.valueOf(p.at()));
        if (p.slowest() == null) {
            root.putNull("slowest");
        } else {
            root.put("slowest", p.slowest());
        }
        ArrayNode steps = root.putArray("steps");
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
        }
        ArrayNode edges = root.putArray("edges");
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
        }
        return root.toString();
    }

    private static void time(ObjectNode n, String key, Instant at) {
        if (at == null) {
            n.putNull(key);
        } else {
            n.put(key, at.toString());
        }
    }
}
