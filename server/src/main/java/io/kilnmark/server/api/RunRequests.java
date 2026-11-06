package io.kilnmark.server.api;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Starting and reading runs for callers that wait (ADR 0047), the same for pipelines and flows: a
 * start with its request key and wait, a read with its wait, and the answer as JSON or plain text.
 */
@ApplicationScoped
public class RunRequests {

    private final RunWaits waits;
    private final RunRequestKeys keys;

    public RunRequests(RunWaits waits, RunRequestKeys keys) {
        this.waits = waits;
        this.keys = keys;
    }

    /** A run as the caller may see it: the JSON body, and its name, status and message for the text form. */
    public record Shown(Object body, String name, String status, String message) {
    }

    /**
     * Starts a run, once per request key when one is sent, then waits as asked. A start is never
     * refused for want of a wait place: with none free, it answers at once (202), and the caller
     * reads the run with wait.
     *
     * <p>With a key, a retry first looks the key up, and gets its run without any check. Otherwise the
     * run is checked ({@code check}, which opens connections of its own), and only then are the key
     * and the run written, in one transaction ({@code queue}, which must touch the run's tables only).
     *
     * @param kind {@code pipeline} or {@code flow}
     * @param check checks the run, queueing nothing
     * @param queue queues the checked run and gives its id
     * @param status the run's status now, or null when it is gone
     * @param show the run as the start answers with it
     */
    public <C> Response start(String kind, String name, Integer version, Map<String, Object> parameters, String wait,
                              String key, SecurityIdentity identity, HttpHeaders headers,
                              io.vertx.core.http.HttpServerRequest request, Supplier<C> check, ToLongFunction<C> queue,
                              LongFunction<String> status, LongFunction<Shown> show) {
        Duration waitFor = waits.parse(wait);
        String checkedKey = RunRequestKeys.check(key);
        String caller = RunRequestKeys.caller(identity);
        RunRequestKeys.Started started;
        if (checkedKey == null) {
            started = new RunRequestKeys.Started(queue.applyAsLong(check.get()), false);
        } else {
            String hash = RunRequestKeys.requestHash(kind, name, version, parameters);
            Optional<RunRequestKeys.Started> earlier = keys.find(caller, checkedKey, kind, name, hash);
            if (earlier.isPresent()) {
                started = earlier.get();
            } else {
                C checked = check.get();
                started = keys.start(caller, checkedKey, kind, name, hash, () -> queue.applyAsLong(checked));
            }
        }
        long id = started.runId();
        RunWaits.Place place = waits.tryEnter(waitFor, caller);
        if (place != null) {
            try (place) {
                waits.until(() -> RunWaits.endedOrGone(status.apply(id)), waitFor, RunWaits.gone(request));
            }
        }
        Shown run = show.apply(id);
        return RunWaits.started(run.body(), text(headers, kind, id, run), run.status(), started.repeated(),
            URI.create("/api/v1/" + kind + "-runs/" + id));
    }

    /**
     * Reads a run, waiting as asked when it has not ended.
     *
     * @param status the run's status now, or null when it is gone (the read then answers 404)
     * @param show the run in short, for the text form
     * @param detail the run in full, for JSON
     * @throws jakarta.ws.rs.WebApplicationException (503) when a wait finds no place
     */
    public Response read(String kind, long id, String wait, SecurityIdentity identity, HttpHeaders headers,
                         io.vertx.core.http.HttpServerRequest request, LongFunction<String> status,
                         LongFunction<Shown> show, Supplier<Object> detail) {
        Duration waitFor = waits.parse(wait);
        if (!RunWaits.endedOrGone(waitFor.isZero() ? null : status.apply(id))) {
            try (RunWaits.Place _ = waits.enter(waitFor, RunRequestKeys.caller(identity))) {
                waits.until(() -> RunWaits.endedOrGone(status.apply(id)), waitFor, RunWaits.gone(request));
            }
        }
        if (RunWaits.plain(headers)) {
            return RunWaits.read(null, text(headers, kind, id, show.apply(id)));
        }
        return RunWaits.read(detail.get(), null);
    }

    private static String text(HttpHeaders headers, String kind, long id, Shown run) {
        return RunWaits.plain(headers) ? RunWaits.text(kind, id, run.name(), run.status(), run.message()) : null;
    }
}
