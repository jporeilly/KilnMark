package io.kilnmark.server.runs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.random.RandomGenerator;

import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.kiln.yaml.RunOptions;

/**
 * Whether a try of a pipeline or flow run that has ended is tried again, and when (ADR 0053,
 * decisions 2 to 5). Pure: what it needs is given to it, so each rule is tested on its own. The
 * services work out what it is given (how the try ended, whether its writes are safe to repeat, the
 * schedule's next fire) and write what it decides ({@link RunTables#retry}).
 */
public final class RunRetries {

    private RunRetries() {
    }

    /** How a try ended, as far as trying it again goes. */
    public enum Ending {
        /** An error a second try could change: an engine's, a source that could not be reached, a target in use. */
        ERROR,
        /** Its worker stopped, or lost its connection to Kilnmark's database. */
        LOST,
        /** It took longer than {@code run.timeout} and was stopped. */
        TIMED_OUT,
        /** Cancelled: by a person, by the flow, or by a {@code stop} node. Never tried again. */
        CANCELLED,
        /** A refusal a second try cannot change: a version that cannot run, values that cannot be read, rights. */
        REFUSED,
        /** A quality gate stopped it: the same rows fail the same rules. */
        GATE,
        /** A sql node's commit failed, so whether it committed is not known. */
        IN_DOUBT,
        /** A flow's {@code stop} node failed it: the flow's own decision. */
        STOPPED;

        /** As a pipeline run records it ({@code pipeline_run.failed_as}), or null for an ending it does not record. */
        public String recorded() {
            return switch (this) {
                case ERROR -> "error";
                case LOST -> "lost";
                case TIMED_OUT -> "timed_out";
                case CANCELLED -> "cancelled";
                case REFUSED, STOPPED -> "refused";
                case GATE -> "gate";
                case IN_DOUBT -> "error";
            };
        }

        /** Whether running again what ended so could change how it ends. */
        public boolean couldChange() {
            return this == ERROR || this == LOST || this == TIMED_OUT;
        }
    }

    /**
     * What to do after a try: tried again at {@code at}, or not, with {@code why} in the run's words
     * ("Not tried again: ...").
     */
    public record Decision(boolean again, Instant at, String why) {
        static Decision no(String why) {
            return new Decision(false, null, "Not tried again: " + why);
        }
    }

    /**
     * Decides after try {@code tried} (from 0) of a run that ended as {@code ending}.
     *
     * @param options   the run options of the version the run used
     * @param tries     how many tries the run may have by itself ({@code run.tries}): 1 + its retries, after
     *                  the server's cap; 1 for a run a flow node started, or a try started by hand
     * @param unsafe    why running it again could write twice ("write-orders appends"), or null when it cannot
     * @param nextFire  when the schedule that started the run fires next, or null
     * @param now       the time now
     * @param random    for the random part of the wait
     */
    public static Decision decide(RunOptions options, int tries, int tried, Ending ending, String unsafe,
                                  Instant nextFire, Instant now, RandomGenerator random) {
        switch (ending) {
            case CANCELLED -> {
                return Decision.no("a cancelled run is never tried again");
            }
            case REFUSED -> {
                return Decision.no("a second try would be refused the same way");
            }
            case GATE -> {
                return Decision.no("a quality gate stopped it, and would stop a second try the same way");
            }
            case IN_DOUBT -> {
                return Decision.no("a sql node's commit failed, so it may have committed");
            }
            case STOPPED -> {
                return Decision.no("a stop node ended it");
            }
            default -> {
                // ERROR, LOST and TIMED_OUT may be tried again
            }
        }
        if (tries <= 1) {
            return Decision.no(options.retries() > 0 ? "run retries are off on this server, or for this try"
                : "it has no retries (run: retries)");
        }
        if (tried + 1 >= tries) {
            return Decision.no("all " + tries + " tries were made");
        }
        if (options.retryOn() == RunOptions.RetryOn.LOST && ending != Ending.LOST) {
            return Decision.no("it is tried again only when lost with its worker (retry_on: lost)");
        }
        if (unsafe != null) {
            return Decision.no("trying again could write twice: " + unsafe);
        }
        Duration wait = RunOptions.waitWithJitter(options.retryDelay(), tried + 1, random);
        Instant at = now.plus(wait);
        if (nextFire != null && !at.isBefore(nextFire)) {
            return Decision.no("the next scheduled run is due first");
        }
        return new Decision(true, at, null);
    }

    /** "Try 1 of 3 failed; trying again at 14:05 (UTC)": what a run waiting for its next try says. */
    public static String waiting(int tried, int tries, Instant at, ZoneId zone) {
        return "Try " + (tried + 1) + " of " + tries + " failed; trying again at "
            + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(at) + " ("
            + zone.getId() + ")";
    }

    /** ", after 3 tries" for a run that had more than one try; empty for one that had one. */
    public static String afterTries(int tried) {
        return tried == 0 ? "" : ", after " + (tried + 1) + " tries";
    }

    /** "Took longer than 2h and was stopped": a try stopped for its {@code run.timeout}. */
    public static String tookTooLong(Duration timeout) {
        return "Took longer than " + FlowDefinition.written(timeout) + " and was stopped";
    }
}
