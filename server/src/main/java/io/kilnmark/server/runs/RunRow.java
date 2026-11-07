package io.kilnmark.server.runs;

/** What the run lifecycle reads of a pipeline run or a flow run. */
public interface RunRow {

    long id();

    /** queued, running, succeeded, failed or cancelled. */
    String status();

    /** Whether someone has asked the run to stop. */
    boolean cancelRequested();

    /** Its try now, from 0 (ADR 0053). */
    int runTry();

    /** How many tries it may have by itself: 1 + its retries after the server's cap. */
    int tries();

    /** When its next try starts while it waits for one; null otherwise. */
    java.time.Instant retryAt();

    /** Whether the run has ended: neither queued nor running. */
    default boolean finished() {
        return !status().equals("queued") && !status().equals("running");
    }
}
