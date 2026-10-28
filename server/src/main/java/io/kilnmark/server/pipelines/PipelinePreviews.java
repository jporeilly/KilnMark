package io.kilnmark.server.pipelines;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.assistant.CallerRate;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import io.kilnmark.server.quality.Sights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Preview at a step (docs/design/pipelines.md, "Preview at a step"; docs/guide/pipelines.md,
 * "Preview a step"): the first rows leaving one output of one step, from the pipeline as it stands,
 * run on the API's own process while the person waits.
 *
 * <p>Only the chosen step and the steps upstream of it run, and only reads and the steps that work
 * on rows in memory may ({@link StepPreview#plan}); the chosen output feeds a collector that takes
 * the rows asked for and finishes, so the engine ends the steps before it early. Nothing is
 * written, stored or logged.
 *
 * <p>The same rules as previewing the sources involved: stewards and admins only (the resource
 * says so); reads resolve their saved sources as a run's do (credentials, allowed hosts) inside a
 * run context naming the caller; columns made from columns marked sensitive are masked for all but
 * admins, as a source's preview masks them, and a preview whose rows are chosen by such a column is
 * refused; a failure's message, which may quote values, is held back as a run's is.
 *
 * <p>Limits: rows 1 to 1,000 (100 by default); {@code kilnmark.pipelines.preview-timeout} (30
 * seconds) of wall-clock time; {@code preview-memory-limit-mb} (256) of Arrow memory; one preview
 * per person at a time, {@code previews-per-minute} (6) per person, and {@code preview-concurrency}
 * (4) at once in this process. A preview stops when its HTTP request is closed.
 */
@ApplicationScoped
public class PipelinePreviews {

    /** The caller's sight of the assets read: whether they see sensitive columns, and each asset's marks. */
    interface Viewer {
        boolean mayUnmask();

        Set<String> marks(String urn);
    }

    /** The limits, from {@code kilnmark.pipelines.*}. */
    record Limits(long memoryBytes, int rowSetCapacity, Duration timeout, int perMinute, int concurrency,
                  int maxYamlKb) {

        static Limits of(KilnmarkConfig config) {
            KilnmarkConfig.Pipelines p = config.pipelines();
            return new Limits(Math.max(16, p.previewMemoryLimitMb()) << 20, Math.max(1, p.rowSetCapacity()),
                p.previewTimeout(), Math.max(1, p.previewsPerMinute()), Math.max(1, p.previewConcurrency()),
                Math.max(1, p.maxYamlKb()));
        }
    }

    private final StepFactories steps;
    private final PipelineStore store;
    private final Limits limits;
    private final CallerRate rate;
    private final Semaphore slots;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Inject
    public PipelinePreviews(StepFactories steps, PipelineStore store, KilnmarkConfig config) {
        this(steps, store, Limits.of(config));
    }

    PipelinePreviews(StepFactories steps, PipelineStore store, Limits limits) {
        this.steps = steps;
        this.store = store;
        this.limits = limits;
        this.rate = new CallerRate(limits.perMinute());
        this.slots = new Semaphore(limits.concurrency());
    }

    /**
     * Previews {@code step}'s output {@code port} of pipeline YAML as it stands, saved or not, for
     * {@code caller} with {@code role}, masked through the request's {@code sights}. {@code abandoned}
     * says the HTTP request was closed.
     */
    public PipelineViews.StepPreviewView preview(String yaml, String step, String port, Integer rows, String caller,
                                                 AccessRole role, Sights sights, BooleanSupplier abandoned) {
        return preview(yaml, step, port, rows, caller, role, viewer(sights), abandoned);
    }

    /** As {@link #preview}, for a saved version of a pipeline. */
    public PipelineViews.StepPreviewView previewVersion(String name, int n, String step, String port, Integer rows,
                                                        String caller, AccessRole role, Sights sights,
                                                        BooleanSupplier abandoned) {
        PipelineStore.Version v = store.version(name, n).orElseThrow(() -> PipelineService.fail(
            Response.Status.NOT_FOUND, "There is no version " + n + " of a pipeline called " + name));
        return preview(v.yaml(), step, port, rows, caller, role, viewer(sights), abandoned);
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
        };
    }

    PipelineViews.StepPreviewView preview(String yaml, String step, String port, Integer rows, String caller,
                                          AccessRole role, Viewer viewer, BooleanSupplier abandoned) {
        checkSize(yaml);
        int n = rows == null ? StepPreview.DEFAULT_ROWS : rows;
        if (n < 1 || n > StepPreview.MAX_ROWS) {
            throw new IllegalArgumentException("rows: from 1 to " + StepPreview.MAX_ROWS);
        }
        StepPreview.Plan plan = StepPreview.plan(yaml, step);
        PipelineCompilerPort.Compilation checked = KilnPipelineCompiler.compile(plan.definition(), steps, false);
        if (!checked.valid()) {
            throw new IllegalArgumentException("Fix the pipeline before previewing " + plan.step() + ": "
                + problems(checked.problems()));
        }
        String out = StepPreview.port(checked, plan.step(), port);
        // Refused before anything connects, by the reads as their settings name them; checked again
        // once the reads are resolved (a file's path as the catalogue escapes it, say).
        try {
            StepPreview.hidden(checked, readHidden(checked, null, viewer), plan.step());
        } catch (StepPreview.Refused e) {
            throw PipelineService.fail(Response.Status.FORBIDDEN, e.getMessage());
        }
        String who = caller == null || caller.isBlank() ? "anonymous" : caller;
        if (!running.add(who)) {
            throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "You already have a preview running. Wait "
                + "for it to finish, then try again.");
        }
        try {
            if (!rate.allow(who, Instant.now())) {
                throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "You have previewed "
                    + limits.perMinute() + " times in the last minute. Wait a minute, then try again.");
            }
            if (!slots.tryAcquire()) {
                throw PipelineService.fail(Response.Status.TOO_MANY_REQUESTS, "Kilnmark is running as many previews "
                    + "as it allows at once. Try again in a moment.");
            }
            try {
                return run(plan, out, n, who, role == null ? AccessRole.STEWARD : role, viewer, abandoned);
            } finally {
                slots.release();
            }
        } finally {
            running.remove(who);
        }
    }

    private PipelineViews.StepPreviewView run(StepPreview.Plan plan, String port, int rows, String who, AccessRole role,
                                              Viewer viewer, BooleanSupplier abandoned) {
        RunContext context = new RunContext(plan.definition().name(), 0, who, role);
        Thread self = Thread.currentThread();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> stop = new AtomicReference<>();
        long started = System.nanoTime();
        long deadline = started + limits.timeout().toNanos();
        Thread watch = Thread.ofVirtual().name("kilnmark-preview-watch").start(() -> {
            while (!done.get()) {
                String why = System.nanoTime() - deadline >= 0 ? "timeout"
                    : abandoned != null && abandoned.getAsBoolean() ? "abandoned" : null;
                if (why != null) {
                    synchronized (done) {
                        if (!done.get()) {
                            stop.set(why);
                            self.interrupt();
                        }
                    }
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        PipelineGraph graph = null;
        boolean handedOn = false;
        List<String> waits = List.of();
        try {
            PipelineCompilerPort.Compilation c = context.run(() -> KilnPipelineCompiler.compile(plan.definition(), steps,
                true));
            waits = StepPreview.waits(c);
            graph = c.graph();
            if (stop.get() != null) {
                throw stopped(stop.get(), waits);
            }
            if (!c.valid() || graph == null) {
                throw unprocessable(context.scrub("The preview could not "
                    + "start: " + problems(c.problems())));
            }
            Map<String, Set<String>> hidden = readHidden(c, context, viewer);
            StepPreview.Hidden masks = StepPreview.hidden(c, hidden, plan.step());
            boolean marked = !viewer.mayUnmask() && hidden.values().stream().anyMatch(s -> !s.isEmpty());
            StepPreview.Outcome o;
            try {
                handedOn = true; // the runner closes every step from here, whatever happens
                o = StepPreview.run(graph, plan.step(), port, rows, limits.memoryBytes(), limits.rowSetCapacity(),
                    limits.timeout());
            } catch (InterruptedException e) {
                throw stopped(stop.get(), waits);
            } catch (RuntimeException e) {
                handedOn = false; // refused before its steps started
                throw unprocessable(context.scrub("The preview could not "
                    + "start: " + e.getMessage()));
            }
            if (stop.get() != null) {
                throw stopped(stop.get(), waits);
            }
            if (o.result().status() != RunResult.Status.SUCCEEDED) {
                String at = o.result().steps().stream().filter(s -> s.outcome() == RunResult.Outcome.FAILED)
                    .map(RunResult.StepResult::name).filter(s -> !StepPreview.COLLECTOR.equals(s)).findFirst()
                    .orElse(null);
                // Its words may quote values from the rows: held back as a run's are.
                String message = marked ? "The preview stopped" + (at == null ? "" : " at step " + at) + ". "
                    + PipelineService.FOR_ADMINS : context.scrub(o.result().error());
                throw unprocessable(message);
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            return view(c, plan.step(), port, o.rows(), masks, millis, waits);
        } catch (StepPreview.Refused e) {
            throw PipelineService.fail(Response.Status.FORBIDDEN, e.getMessage());
        } finally {
            if (graph != null && !handedOn) {
                PipelineExecution.closeSteps(graph);
            }
            synchronized (done) {
                done.set(true);
            }
            watch.interrupt();
            try {
                watch.join(1000);
            } catch (InterruptedException e) {
                // cleared below
            }
            Thread.interrupted(); // an interrupt meant for the preview must not reach the request's other work
        }
    }

    /**
     * Each read step's columns hidden from the caller (none for an admin), under every URN its asset
     * may be catalogued by: as the settings name it, as the catalogue escapes a file's path, and, with
     * {@code context}, as its connector resolved it.
     */
    private static Map<String, Set<String>> readHidden(PipelineCompilerPort.Compilation c, RunContext context,
                                                       Viewer viewer) {
        Map<String, Set<String>> out = new HashMap<>();
        if (!viewer.mayUnmask()) {
            for (PipelineCompilerPort.StepOutline s : c.steps()) {
                if (!ReadStepFactory.TYPE.equals(s.type())) {
                    continue;
                }
                Map<String, Object> config = c.configs().getOrDefault(s.id(), Map.of());
                String source = PipelineSources.text(config, "source");
                String asset = PipelineSources.text(config, "asset");
                Set<String> urns = new LinkedHashSet<>();
                urns.add(ReadStepFactory.urn(config));
                if (asset != null) {
                    urns.add(PipelineSources.urn(source, ReadStepFactory.escapedFilePath(asset)));
                }
                if (context != null) {
                    urns.add(context.readUrn(source, asset, null));
                }
                Set<String> marks = new LinkedHashSet<>();
                urns.stream().filter(u -> u != null).forEach(u -> marks.addAll(viewer.marks(u)));
                out.put(s.id(), marks);
            }
        }
        return out;
    }

    private static PipelineViews.StepPreviewView view(PipelineCompilerPort.Compilation c, String step, String port,
                                                      StepPreview.Collector rows, StepPreview.Hidden hidden,
                                                      long millis, List<String> waits) {
        List<PipelineViews.PreviewColumn> columns = new ArrayList<>();
        if (!rows.fields.isEmpty()) {
            rows.fields.forEach(f -> columns.add(new PipelineViews.PreviewColumn(f.getName(), ArrowKinds.kind(f), null)));
        } else {
            c.steps().stream().filter(s -> s.id().equals(step)).findFirst()
                .map(s -> s.columns().get(port)).ifPresent(known -> known.forEach(k ->
                    columns.add(new PipelineViews.PreviewColumn(k.name(), k.type(), null))));
        }
        List<Integer> hide = new ArrayList<>();
        List<String> masked = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (hidden.hides(columns.get(i).name())) {
                hide.add(i);
                masked.add(columns.get(i).name());
            }
        }
        List<List<String>> shown = new ArrayList<>();
        for (List<String> row : rows.rows) {
            if (hide.isEmpty()) {
                shown.add(row);
                continue;
            }
            List<String> r = new ArrayList<>(row);
            hide.forEach(i -> {
                if (i < r.size() && r.get(i) != null) {
                    r.set(i, Masking.MASK);
                }
            });
            shown.add(r);
        }
        return new PipelineViews.StepPreviewView(step, port, step + "." + port, columns, shown, shown.size(),
            rows.truncated, Map.of(), rows.complete && !rows.truncated, millis, masked, waits);
    }

    private jakarta.ws.rs.WebApplicationException stopped(String why, List<String> waits) {
        if ("abandoned".equals(why)) {
            return unprocessable("The preview was stopped: its request "
                + "was closed.");
        }
        long seconds = Math.max(1, Math.round(limits.timeout().toMillis() / 1000.0));
        return unprocessable("The preview took longer than " + seconds + (seconds == 1 ? " second" : " seconds")
            + ", so it was stopped."
            + (waits.isEmpty() ? "" : " " + String.join(" ", waits))
            + " Preview a step nearer the start, or set a row limit on the read step while you try the pipeline out.");
    }

    /** 422: the request was understood, but the preview could not give rows. */
    static jakarta.ws.rs.WebApplicationException unprocessable(String message) {
        return new jakarta.ws.rs.WebApplicationException(Response.status(422)
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
