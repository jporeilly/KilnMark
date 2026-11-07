package io.kilnmark.server.runs;

import java.time.Duration;

import io.kilnmark.kiln.yaml.RunOptions;
import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The server's settings for run retries (ADR 0053): {@code kilnmark.runs.lost-after} and
 * {@code kilnmark.runs.max-retries}, checked when the server starts. A value outside its range stops
 * the server, saying which and what it may be, rather than running with a cap nobody chose.
 */
@ApplicationScoped
@Startup
public class RunSettings {

    private final Duration lostAfter;
    private final int maxRetries;

    @Inject
    public RunSettings(KilnmarkConfig config) {
        this(config.runs().lostAfter(), config.runs().maxRetries());
    }

    /** Settings given directly, for tests that build services by hand. */
    public RunSettings(Duration lostAfter, int maxRetries) {
        String problem = problem(lostAfter, maxRetries);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        this.lostAfter = lostAfter;
        this.maxRetries = maxRetries;
    }

    /** The defaults: 2 minutes, 5 retries. */
    public static RunSettings defaults() {
        return new RunSettings(Duration.ofMinutes(2), RunOptions.MAX_RETRIES);
    }

    /** What is wrong with the settings, or null. */
    static String problem(Duration lostAfter, int maxRetries) {
        if (lostAfter == null || lostAfter.compareTo(KilnmarkConfig.Runs.MIN_LOST_AFTER) < 0
            || lostAfter.compareTo(KilnmarkConfig.Runs.MAX_LOST_AFTER) > 0) {
            return "kilnmark.runs.lost-after must be 30s to 30m: how long a running pipeline or flow may go without its "
                + "worker renewing its lease before it counts as lost";
        }
        if (maxRetries < 0 || maxRetries > RunOptions.MAX_RETRIES) {
            return "kilnmark.runs.max-retries must be 0 to " + RunOptions.MAX_RETRIES + ": the most retries any run may "
                + "have on this server (0 turns run retries off)";
        }
        return null;
    }

    /** How long a running pipeline or flow job may go without its lease renewed before it counts as lost. */
    public Duration lostAfter() {
        return lostAfter;
    }

    /** The most retries any run may have here. */
    public int maxRetries() {
        return maxRetries;
    }

    /** How many tries a run with these options may have here, by itself. */
    public int tries(RunOptions options) {
        return (options == null ? RunOptions.NONE : options).tries(maxRetries);
    }
}
