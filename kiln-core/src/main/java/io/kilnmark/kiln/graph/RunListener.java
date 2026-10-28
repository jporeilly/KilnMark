package io.kilnmark.kiln.graph;

import java.time.Instant;

import io.kilnmark.kiln.RunResult;

/**
 * Hears a graph run as it goes (docs/design/pipelines.md section 1, "Seeing inside a run"): each
 * step starting, its first row in and out, its last row out and how it ended; a {@link RunProgress}
 * snapshot every few seconds; and the engine's notes.
 *
 * <p>Every method is called on the listener's own virtual thread, one call at a time and in order,
 * never on a step's thread or the thread that called {@link GraphRunner#run}, so a slow listener
 * never holds a step back. Calls for one step come in the order above, with the time each thing
 * happened (which may be up to a second before the call). Anything a method throws is caught and
 * dropped; a listener still busy ten seconds after the run's steps have ended misses the rest. A
 * listener can never fail or stop a run. Every method does nothing unless overridden.
 */
public interface RunListener {

    /** Hears nothing. */
    RunListener NONE = new RunListener() {
    };

    /** How much a note matters. */
    enum Level { INFO, WARN, ERROR }

    /** The step's thread started. */
    default void stepStarted(String step, Instant at) {
    }

    /** The first row arrived at the step (steps with an input only). */
    default void firstRowIn(String step, Instant at) {
    }

    /** The step handed on its first row (steps with a connected output only). */
    default void firstRowOut(String step, Instant at) {
    }

    /** The step handed on its last row: called once the step has ended, before {@link #stepEnded}. */
    default void lastRowOut(String step, Instant at) {
    }

    /**
     * The step ended, its resources closed, and anything copying its rows to several steps has
     * finished too. Called once more, with the same time, if the run later finds its outcome was
     * otherwise (memory it did not free, found once every step has ended).
     */
    default void stepEnded(String step, RunResult.Outcome outcome, Instant at) {
    }

    /** Where the rows are now; about every two seconds while the run goes, and once at its end. */
    default void progress(RunProgress progress) {
    }

    /**
     * Something the engine noticed: a step's failure, a stall, memory a stopped step did not free.
     * {@code step} is null for the run as a whole. The text may quote values from the data (a
     * step's error often does).
     */
    default void note(Level level, String step, String text) {
    }

    /** {@link #note(Level, String, String)}, with when the engine noticed it. */
    default void note(Level level, String step, String text, Instant at) {
        note(level, step, text);
    }
}
