package io.kilnmark.server.runs;

import java.util.Map;

import io.kilnmark.server.auth.AccessRole;

/**
 * Starting a run in two steps, as a request key (ADR 0047) and a schedule's fire (ADR 0046) need:
 * {@link #check}, which reads the catalogue on connections of its own and can take a while, then
 * {@link #queue}, one short transaction on the run's tables only.
 *
 * @param <C> the checked run
 */
public interface RunStarter<C> {

    /**
     * The checks a run must pass to be queued, queueing nothing.
     *
     * @throws jakarta.ws.rs.WebApplicationException (403, 404, 409) and IllegalArgumentException (400)
     */
    C check(String name, Integer version, Map<String, Object> parameters, AccessRole role);

    /**
     * Queues a checked run; its id.
     *
     * @throws io.kilnmark.server.api.AlreadyRunning (409) while a run is queued or running
     * @throws jakarta.ws.rs.WebApplicationException (404) when it was deleted since it was checked
     */
    long queue(C checked, String by, AccessRole role);

    /** The check of {@link RunStarter#check}. */
    @FunctionalInterface
    interface Check<C> {
        C check(String name, Integer version, Map<String, Object> parameters, AccessRole role);
    }

    /** The queueing of {@link RunStarter#queue}. */
    @FunctionalInterface
    interface Queue<C> {
        long queue(C checked, String by, AccessRole role);
    }

    /** A starter of these two steps. */
    static <C> RunStarter<C> of(Check<C> check, Queue<C> queue) {
        return new RunStarter<>() {
            @Override
            public C check(String name, Integer version, Map<String, Object> parameters, AccessRole role) {
                return check.check(name, version, parameters, role);
            }

            @Override
            public long queue(C checked, String by, AccessRole role) {
                return queue.queue(checked, by, role);
            }
        };
    }
}
