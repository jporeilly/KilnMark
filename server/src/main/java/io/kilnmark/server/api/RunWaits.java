package io.kilnmark.server.api;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Waiting for a run to end (ADR 0047): {@code ?wait=<seconds>} on the run-start and run-read
 * endpoints. A waiting request sleeps on a virtual thread and looks at the run's status about once a
 * second; at most {@code kilnmark.api.max-waiting} wait at once on this process, at most
 * {@code kilnmark.api.max-waiting-per-caller} of them for one token or person, and no wait is
 * longer than {@code kilnmark.api.max-wait}. A start is never refused for want of a place: it
 * starts the run and answers at once.
 */
@ApplicationScoped
public class RunWaits {

    /** How often a waiting request looks at the run. */
    static final Duration LOOK_EVERY = Duration.ofSeconds(1);
    /** What a refused wait is told to wait before asking again, in seconds. */
    static final int RETRY_AFTER = 5;

    private final Duration cap;
    private final Semaphore waiting;
    private final int perCaller;
    /** Places each caller holds now. */
    private final ConcurrentHashMap<String, Integer> byCaller = new ConcurrentHashMap<>();

    public RunWaits(KilnmarkConfig config) {
        this.cap = config.api().maxWait();
        this.waiting = new Semaphore(Math.max(1, config.api().maxWaiting()));
        this.perCaller = Math.max(1, config.api().maxWaitingPerCaller());
    }

    /** A place among the waiting requests; closing it gives the place back. */
    public interface Place extends AutoCloseable {
        @Override
        void close();
    }

    /** No place is needed: the request does not wait. */
    private static final Place NONE = () -> { };

    /**
     * How long to wait: {@code wait} is a whole number of seconds from 0, cut to the cap. Left out,
     * blank or 0 is no wait.
     *
     * @throws IllegalArgumentException (400) for anything else
     */
    public Duration parse(String wait) {
        if (wait == null || wait.isBlank()) {
            return Duration.ZERO;
        }
        String digits = wait.strip();
        if (!digits.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException("wait: a whole number of seconds from 0, for example 600");
        }
        // more digits than a long holds is still only "longer than the cap"
        long seconds = digits.length() > 12 ? Long.MAX_VALUE : Long.parseLong(digits);
        Duration asked = Duration.ofSeconds(Math.min(seconds, cap.toSeconds() + 1));
        return asked.compareTo(cap) > 0 ? cap : asked;
    }

    /**
     * Takes a place among the waiting requests for {@code caller}'s wait of {@code wait}, or null when
     * there is none: as many requests as allowed are waiting, on this process or for this caller. No
     * place is needed for no wait.
     */
    public Place tryEnter(Duration wait, String caller) {
        if (wait.isZero()) {
            return NONE;
        }
        boolean[] taken = {false};
        byCaller.compute(caller, (k, held) -> {
            int now = held == null ? 0 : held;
            if (now >= perCaller) {
                return held;
            }
            taken[0] = true;
            return now + 1;
        });
        if (!taken[0]) {
            return null;
        }
        if (!waiting.tryAcquire()) {
            leave(caller);
            return null;
        }
        return () -> {
            waiting.release();
            leave(caller);
        };
    }

    /**
     * As {@link #tryEnter}, refusing instead of giving null.
     *
     * @throws WebApplicationException (503, with Retry-After) when as many requests as allowed are waiting
     */
    public Place enter(Duration wait, String caller) {
        Place place = tryEnter(wait, caller);
        if (place == null) {
            throw new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", Integer.toString(RETRY_AFTER))
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError("As many requests as allowed are already waiting for runs. Ask again in "
                    + RETRY_AFTER + " seconds, or ask without wait and read the run later."))
                .build());
        }
        return place;
    }

    /** Gives back one of {@code caller}'s places, forgetting the caller when it holds none. */
    private void leave(String caller) {
        byCaller.computeIfPresent(caller, (k, held) -> held <= 1 ? null : held - 1);
    }

    /**
     * Waits up to {@code wait} for {@code ended} to be true, looking about once a second; stops early
     * when {@code gone} says the caller has gone.
     *
     * @return whether it ended
     */
    public boolean until(BooleanSupplier ended, Duration wait, BooleanSupplier gone) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            if (ended.getAsBoolean()) {
                return true;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || gone.getAsBoolean()) {
                return false;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, LOOK_EVERY.toNanos())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Whether a run status is final: the run has ended and will not change again. */
    public static boolean ended(String status) {
        return status != null && !"queued".equals(status) && !"running".equals(status);
    }

    /** Whether a run with this status (null when there is no such run) will not change again. */
    static boolean endedOrGone(String status) {
        return status == null || ended(status);
    }

    /** The request header carrying a request key. */
    static final String KEY_HEADER = "Idempotency-Key";
    /** The answer's header saying an earlier request with the same key had started the run. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * The answer to a request that started a run, or found the one its key started: 200 when the run
     * has ended or the key had started it (with {@value #REPLAYED_HEADER}), else 202; the run as JSON,
     * or as {@code text} when that is not null; and Location, the run's address.
     */
    static Response started(Object run, String text, String status, boolean repeated, java.net.URI location) {
        Response.ResponseBuilder b = Response.status(repeated || ended(status) ? 200 : 202).location(location);
        if (repeated) {
            b.header(REPLAYED_HEADER, "true");
        }
        return text == null ? b.type(MediaType.APPLICATION_JSON).entity(run).build() : b.type(TEXT).entity(text).build();
    }

    /** The answer to a request that read a run: the run as JSON, or as {@code text} when that is not null. */
    static Response read(Object run, String text) {
        return text == null ? Response.ok(run, MediaType.APPLICATION_JSON).build() : Response.ok(text, TEXT).build();
    }

    /** Whether the caller asked for the plain-text form ({@code Accept: text/plain}) before JSON. */
    public static boolean plain(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        List<MediaType> accepted = headers.getAcceptableMediaTypes();
        if (accepted == null || accepted.isEmpty()) {
            return false;
        }
        MediaType first = accepted.getFirst();
        return "text".equalsIgnoreCase(first.getType()) && "plain".equalsIgnoreCase(first.getSubtype());
    }

    /** The plain-text form's media type. */
    static final String TEXT = MediaType.TEXT_PLAIN + "; charset=utf-8";

    /**
     * A run as plain text, one {@code name: value} a line, for scripts that cannot read JSON: its id,
     * kind, name, status, whether it has ended, its page in the app, and its message (the run's error
     * as the caller may see it, on one line; empty when there is none).
     */
    static String text(String kind, long id, String name, String status, String message) {
        String page = "/#/" + kind + "-runs/" + id;
        String oneLine = message == null ? "" : message.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+", " ").strip();
        return "id: " + id + "\n"
            + "kind: " + kind + "\n"
            + "name: " + name + "\n"
            + "status: " + status + "\n"
            + "ended: " + ended(status) + "\n"
            + "page: " + page + "\n"
            + "message: " + oneLine + "\n";
    }

    /** Whether the connection carrying the request has closed: no one is waiting for the answer. */
    static BooleanSupplier gone(io.vertx.core.http.HttpServerRequest request) {
        return () -> request != null && request.response().closed();
    }
}
