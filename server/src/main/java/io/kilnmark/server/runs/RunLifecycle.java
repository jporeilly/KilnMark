package io.kilnmark.server.runs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.yaml.Parameters;
import io.kilnmark.server.api.AlreadyRunning;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.scan.ScanService;
import io.kilnmark.server.schedules.ScheduleStore;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The run lifecycle that pipelines and flows share (docs/design/pipelines.md, "One run lifecycle"):
 * what a queue, a cancel and a delete refuse and say, and the first steps of a run's job on a
 * worker. {@code PipelineService} and {@code FlowService} each hold one, over their own store; what
 * differs between them is in {@link RunKind} and {@link RunStore}.
 *
 * <p>What each checks before a run is queued (a pipeline's version compiled, a flow's pipelines
 * read) and what a run does on a worker stay in the services.
 */
public final class RunLifecycle<R extends RunRow> {

    private final RunStore<R> store;
    private final RunKind kind;
    private final Logger log;
    private final Supplier<RunWatch> watch;
    private final Class<? extends RuntimeException> busy;
    private final Class<? extends RuntimeException> missing;

    /**
     * @param log   the service's logger, so its lines keep their category
     * @param watch the process's run watcher; it may give null (a service built by hand in a test)
     */
    public RunLifecycle(RunStore<R> store, Logger log, Supplier<RunWatch> watch) {
        this.store = store;
        this.kind = store.kind();
        this.log = log;
        this.watch = watch;
        this.busy = kind.busy().get().getClass();
        this.missing = kind.missing().get().getClass();
    }

    /** An error answer: the status and the message as {@code {"error": ...}}. */
    public static WebApplicationException fail(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    /** 404: no pipeline or flow called {@code name}. */
    public WebApplicationException notFound(String name) {
        return fail(Response.Status.NOT_FOUND, "No " + kind.noun() + " called " + name);
    }

    // ---- queueing ----

    /**
     * Queues a checked run through {@code queue}, which writes it with its job in one transaction:
     * the kind's busy refusal is a 409 saying a run is already going, its missing refusal (deleted
     * since the run was checked) a 404.
     */
    public <T> T queue(String name, Supplier<T> queue) {
        try {
            return queue.get();
        } catch (RuntimeException e) {
            throw refusal(name, e);
        }
    }

    private RuntimeException refusal(String name, RuntimeException e) {
        if (busy.isInstance(e)) {
            return new AlreadyRunning("A run of " + name + " is already queued or running. Wait for it to finish, "
                + "or cancel it, then run the " + kind.noun() + " again.");
        }
        if (missing.isInstance(e)) {
            return notFound(name);
        }
        return e;
    }

    /**
     * Checks values for the parameters a pipeline's or flow's latest version declares, as a run of it
     * would, without queueing anything: a schedule's values, when it is saved (ADR 0046).
     *
     * @throws IllegalArgumentException saying what is wrong, starting {@code parameters:}
     */
    public static void checkParameters(String name, List<Parameters.Parameter> declared, Map<String, Object> given,
                                       Map<String, Object> variables) {
        try {
            Parameters.resolve(name, declared, given, variables);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("parameters: " + e.getMessage(), e);
        }
    }

    // ---- cancelling ----

    /**
     * Asks a run to stop: a queued run is cancelled at once, a running one is told (on this process
     * at once, on another at the watcher's next look) and stops within a moment. A run whose worker
     * stopped without finishing it is finished here, or it could never run again.
     *
     * @throws WebApplicationException 404 when there is no such run, 409 when it has finished
     */
    public R cancel(long id, String by) {
        R before = store.run(id).orElseThrow(() -> noRun(id));
        if (before.finished()) {
            throw fail(Response.Status.CONFLICT, "Run " + id + " has already finished (" + before.status() + ")");
        }
        R r = store.requestCancel(id, by).orElseThrow(() -> noRun(id));
        if (r.status().equals("running")) {
            RunWatch w = watch.get();
            if (w != null) {
                w.cancel(kind, id);
            }
            if (!store.jobActive(id)) {
                // Its worker stopped without finishing it: nothing will notice the request, so finish it here.
                store.finishRun(id, "cancelled", "Cancelled by " + by + "; the worker running it had stopped");
                r = store.run(id).orElse(r);
            }
        }
        return r;
    }

    /** 404: no run with that id. */
    public WebApplicationException noRun(long id) {
        return fail(Response.Status.NOT_FOUND, "No " + kind.noun() + " run " + id);
    }

    /** A signal of whether run {@code id} is asked to stop, read in memory; null without a watcher. */
    public RunWatch.Signal signal(long id) {
        RunWatch w = watch.get();
        return w == null ? null : w.watch(kind, id);
    }

    // ---- deleting ----

    /**
     * Deletes the pipeline or flow with its versions, runs and schedules (ADR 0046, ADR 0048);
     * returns the schedules deleted with it, to record.
     *
     * @throws WebApplicationException 404 when there is none; 409 while a run is going, or when a
     *                                 lock was not granted in time (nothing was deleted)
     */
    public List<ScheduleStore.Gone> delete(String name, String by) {
        try {
            List<ScheduleStore.Gone> gone = store.deleteWithSchedules(name).orElseThrow(() -> notFound(name));
            log.infof("%s %s deleted by %s, with %d schedules", kind.title(), name, by, gone.size());
            return gone;
        } catch (ScheduleStore.LockWaitTooLong e) {
            throw fail(Response.Status.CONFLICT, "Nothing was deleted: " + name + " or one of its schedules was busy "
                + "a moment ago, perhaps with a scheduled run starting. Try again.");
        } catch (RuntimeException e) {
            if (busy.isInstance(e)) {
                throw fail(Response.Status.CONFLICT, "A run of " + name + " is queued or running. Wait for it to "
                    + "finish, or cancel it, then delete the " + kind.noun() + ".");
            }
            throw e;
        }
    }

    // ---- on a worker ----

    /** What a run's job found first: the run to run, or why there is nothing to do. */
    public record Found<R>(R run, ScanService.Result skipped) {
    }

    /** The run behind job {@code jobId}, if it is there and not finished; else why the job does nothing. */
    public Found<R> find(long jobId) {
        Optional<Long> runId = store.runForJob(jobId);
        if (runId.isEmpty()) {
            return skipped("Job " + jobId + " names no " + kind.noun() + " run");
        }
        long id = runId.get();
        Optional<R> found = store.run(id);
        if (found.isEmpty()) {
            return skipped(kind.title() + " run " + id + " no longer exists");
        }
        if (found.get().finished()) {
            return skipped(kind.title() + " run " + id + " had already finished");
        }
        return new Found<>(found.get(), null);
    }

    private static <R> Found<R> skipped(String why) {
        return new Found<>(null, new ScanService.Result(ScanService.Outcome.SKIPPED, why));
    }

    /**
     * Marks the run running; null when it did, else the job's answer: it was cancelled before it
     * started (and is finished so), or another worker took it.
     */
    public ScanService.Result start(long id) {
        if (store.startRun(id)) {
            return null;
        }
        if (store.cancelRequested(id)) {
            store.finishRun(id, "cancelled", "Cancelled before it started");
        }
        return new ScanService.Result(ScanService.Outcome.SKIPPED, kind.title() + " run " + id + " was cancelled");
    }

    // ---- small things both read ----

    /** A role as a run records it; a role this server does not know reads as viewer. */
    public static AccessRole role(String text) {
        try {
            return AccessRole.parse(text);
        } catch (IllegalArgumentException e) {
            return AccessRole.VIEWER;
        }
    }

    /** Stored values (a JSON object), numbers as Doubles; null when they cannot be read. */
    public static Map<String, Object> values(ObjectMapper json, String text) {
        try {
            Map<String, Object> m = json.readValue(text == null ? "{}" : text,
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
            m.replaceAll((k, v) -> v instanceof Number n ? n.doubleValue() : v);
            return m;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
