package io.kilnmark.kiln.graph;

import java.time.Instant;

import io.kilnmark.kiln.RunResult;

/**
 * Hears a graph run as it goes (docs/design/pipelines.md section 1, "Seeing inside a run"): each
 * node starting, its first row in and out, its last row out and how it ended; a {@link RunProgress}
 * snapshot every few seconds; and the engine's notes.
 *
 * <p>Every method is called on the listener's own virtual thread, one call at a time and in order,
 * never on a node's thread or the thread that called {@link GraphRunner#run}, so a slow listener
 * never holds a node back. Calls for one node come in the order above, with the time each thing
 * happened (which may be up to a second before the call). Anything a method throws is caught and
 * dropped; a listener still busy ten seconds after the run's nodes have ended misses the rest. A
 * listener can never fail or stop a run. Every method does nothing unless overridden.
 */
public interface RunListener {

    /** Hears nothing. */
    RunListener NONE = new RunListener() {
    };

    /** How much a note matters. */
    enum Level { INFO, WARN, ERROR }

    /** The node's thread started. */
    default void nodeStarted(String node, Instant at) {
    }

    /** The first row arrived at the node (nodes with an input only). */
    default void firstRowIn(String node, Instant at) {
    }

    /** The node handed on its first row (nodes with a connected output only). */
    default void firstRowOut(String node, Instant at) {
    }

    /** The node handed on its last row: called once the node has ended, before {@link #nodeEnded}. */
    default void lastRowOut(String node, Instant at) {
    }

    /**
     * The node ended, its resources closed, and anything copying its rows to several nodes has
     * finished too. Called once more, with the same time, if the run later finds its outcome was
     * otherwise (memory it did not free, found once every node has ended).
     */
    default void nodeEnded(String node, RunResult.Outcome outcome, Instant at) {
    }

    /** Where the rows are now; about every two seconds while the run goes, and once at its end. */
    default void progress(RunProgress progress) {
    }

    /**
     * Something the engine noticed: a node's failure, a stall, memory a stopped node did not free.
     * {@code node} is null for the run as a whole. The text may quote values from the data (a
     * node's error often does).
     */
    default void note(Level level, String node, String text) {
    }

    /** {@link #note(Level, String, String)}, with when the engine noticed it. */
    default void note(Level level, String node, String text, Instant at) {
        note(level, node, text);
    }
}
