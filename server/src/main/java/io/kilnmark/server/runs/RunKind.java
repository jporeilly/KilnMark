package io.kilnmark.server.runs;

import java.util.Locale;
import java.util.function.Supplier;

import io.kilnmark.server.schedules.ScheduleStore;

/**
 * What differs between the run lifecycle of pipelines and that of flows (docs/design/pipelines.md,
 * "One run lifecycle"): the noun its tables and messages use, which column of a schedule names it,
 * its store's own refusals, and three ways its runs differ.
 *
 * <p>The tables follow from the noun: {@code kilnmark.<noun>} holds what runs, {@code
 * kilnmark.<noun>_run} its runs, and a run's job is of kind {@code <noun>} with the run's id under
 * {@code <noun>RunId} in its payload.
 *
 * @param busy           the store's refusal while a run is queued or running
 * @param missing        the store's refusal when the pipeline or flow is not there
 * @param notesWhileQueued whether a queued run may say what it waits for (a pipeline waiting for a
 *                         source's place, ADR 0040), which starting it clears
 * @param results        whether a run records a result when it finishes (pipelines)
 * @param deleteWaitsForJob whether a delete also waits for a run's job to end, after the run has
 *                          (flows, ADR 0048 decision 1)
 */
public record RunKind(String noun, ScheduleStore.Target target, Supplier<? extends RuntimeException> busy,
                      Supplier<? extends RuntimeException> missing, boolean notesWhileQueued, boolean results,
                      boolean deleteWaitsForJob) {

    /** The table of what runs: {@code kilnmark.pipeline} or {@code kilnmark.flow}. */
    public String table() {
        return "kilnmark." + noun;
    }

    /** The table of its runs. */
    public String runTable() {
        return "kilnmark." + noun + "_run";
    }

    /** The kind of its runs' jobs. */
    public String jobKind() {
        return noun;
    }

    /** Where a job's payload names its run. */
    public String payloadKey() {
        return noun + "RunId";
    }

    /** The noun with a capital, as messages start: Pipeline, Flow. */
    public String title() {
        return noun.substring(0, 1).toUpperCase(Locale.ROOT) + noun.substring(1);
    }
}
