package io.kilnmark.server.runs;

/** What the run lifecycle reads of a pipeline run or a flow run. */
public interface RunRow {

    long id();

    /** queued, running, succeeded, failed or cancelled. */
    String status();

    /** Whether someone has asked the run to stop. */
    boolean cancelRequested();

    /** Whether the run has ended: neither queued nor running. */
    default boolean finished() {
        return !status().equals("queued") && !status().equals("running");
    }
}
