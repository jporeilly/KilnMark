package io.kilnmark.server.sources;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jboss.logging.Logger;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Connection tests and container listings, each limited in time
 * ({@code kilnmark.sources.check-timeout}, 15 s by default) and in number: people's checks share
 * {@code kilnmark.sources.check-concurrency} places, so a burst of clicks cannot tie the server up.
 *
 * <p>Each check runs on a virtual thread of its own, which keeps its place until it has really
 * ended, so the limit holds even for a check that will not stop. Three things end a check in time:
 * the connector bounds its own network waits from the timeout (JDBC connect, login and socket
 * timeouts; an S3 request timeout and a listing deadline); when the timeout passes the caller
 * answers at once and interrupts the thread, which closes a virtual thread's blocked socket; and
 * the place is only given back when the thread returns. Problems come back in plain words, with the
 * source's secret blanked out should a driver ever repeat it.
 */
@ApplicationScoped
public class SourceChecks {

    private static final Logger LOG = Logger.getLogger(SourceChecks.class);

    /** The outcome of a connection test: {@code problem} is null when it worked. */
    public record TestResult(boolean ok, String problem, long millis) {
    }

    /** Every place for people's checks is taken; try again shortly. */
    public static final class Busy extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Busy(String message) {
            super(message, null, false, false);
        }
    }

    /** A check that could not finish: unreachable, refused, too slow, or the server is busy. */
    public static final class CheckFailed extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final boolean busy;

        CheckFailed(String message, boolean busy) {
            super(message, null, false, false);
            this.busy = busy;
        }

        /** True when it did not run because too many checks were already running. */
        public boolean busy() {
            return busy;
        }
    }

    private final Duration timeout;
    private final Semaphore people;
    private final int maxPlaces;

    public SourceChecks(KilnmarkConfig config) {
        this.timeout = config.sources().checkTimeout();
        this.maxPlaces = Math.max(1, config.sources().checkConcurrency());
        this.people = new Semaphore(maxPlaces);
    }

    /** How long a check may take. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Tests a connection for a person waiting on the answer.
     *
     * @throws Busy when every place for people's checks is taken
     */
    public TestResult test(Connector connector, SourceConfig source) {
        long start = System.nanoTime();
        try {
            limited(source, "connect to it", true, () -> {
                connector.test(source, timeout);
                return null;
            });
            return new TestResult(true, null, elapsed(start));
        } catch (CheckFailed e) {
            if (e.busy()) {
                throw new Busy(e.getMessage());
            }
            return new TestResult(false, e.getMessage(), elapsed(start));
        }
    }

    /** Tests a connection for a background batch, which keeps its own limit on how many run at once. */
    public TestResult testInBackground(Connector connector, SourceConfig source) {
        long start = System.nanoTime();
        try {
            limited(source, "connect to it", false, () -> {
                connector.test(source, timeout);
                return null;
            });
            return new TestResult(true, null, elapsed(start));
        } catch (CheckFailed e) {
            return new TestResult(false, e.getMessage(), elapsed(start));
        }
    }

    /** Lists a source's containers with their counts, for a person waiting on the answer. */
    public List<Connector.ContainerInfo> containers(Connector connector, SourceConfig source) throws CheckFailed {
        return limited(source, "list what it holds", true, () -> connector.containers(source, timeout));
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Places in use now, for tests. */
    int placesInUse() {
        return maxPlaces - people.availablePermits();
    }

    private <T> T limited(SourceConfig source, String doing, boolean person, Callable<T> work) throws CheckFailed {
        if (person && !people.tryAcquire()) {
            throw new CheckFailed("Kilnmark is already running as many connection checks as it allows. Try again in a "
                + "few seconds.", true);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread;
        try {
            thread = Thread.ofVirtual().name("kilnmark-check-" + source.name()).start(() -> {
                try {
                    result.complete(work.call());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                } finally {
                    if (person) {
                        people.release(); // only once the work has really ended
                    }
                }
            });
        } catch (RuntimeException | Error e) {
            if (person) {
                people.release();
            }
            throw e;
        }
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            thread.interrupt(); // a virtual thread blocked on a socket gets it closed
            throw new CheckFailed("Kilnmark could not " + doing + " within " + timeout.toSeconds() + " seconds. "
                + "Check the host and port, that the source is running, and that no firewall blocks the way "
                + "from the Kilnmark server.", false);
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            throw new CheckFailed("The check was stopped before it finished. Try again.", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message;
            if (cause instanceof ConnectorException ce) {
                message = ce.getMessage();
            } else {
                LOG.debugf(cause, "Check of %s failed", source.name());
                message = "Kilnmark could not " + doing + ": " + cause;
            }
            throw new CheckFailed(scrub(message, source.secret()), false);
        }
    }

    /** {@code message} with any copy of the secret blanked out. */
    static String scrub(String message, String secret) {
        if (message == null) {
            return "The check failed without saying why.";
        }
        if (secret == null || secret.length() < 3) {
            return message;
        }
        return message.replace(secret, "********");
    }
}
