package io.kilnmark.kiln;

/**
 * One step in a Kiln pipeline: a source, transform, profiler, classifier or sink.
 *
 * <p>Each step runs on its own worker. The runner calls {@link #init} once, then
 * {@link #process} until it returns {@code false}, then {@link #close}. A source puts one
 * Arrow batch on {@link StepContext#output()} per call; a step with an input takes one batch
 * from {@link StepContext#input()} per call and returns {@code false} when it gets {@code null}.
 * Steps report schema, row counts, profiles and field lineage through
 * {@link StepContext#metadata()} as they run, so the engine writes the metadata
 * graph without a separate scan.
 *
 * <p>This interface is provisional until three different connectors have been
 * built on it (sprint 3); see docs/adr/0005-step-plugin-interface.md.
 */
public interface Step extends AutoCloseable {

    /** Stable type identifier used in pipeline YAML, for example {@code jdbc-sampler}. */
    String type();

    /** Prepares the step; called once before the first {@link #process} call. */
    void init(StepContext context) throws StepException;

    /**
     * Processes the next unit of work.
     *
     * @return {@code true} if the step may produce more output, {@code false} when finished
     */
    boolean process(StepContext context) throws StepException;

    /** Releases resources. Called once, even after a failure. */
    @Override
    default void close() {
        // Most steps hold nothing that needs releasing.
    }
}
