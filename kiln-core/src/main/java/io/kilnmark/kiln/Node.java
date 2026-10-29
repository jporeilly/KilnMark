package io.kilnmark.kiln;

/**
 * One node in a Kiln pipeline: a source, transform, profiler, classifier or sink.
 *
 * <p>Each node runs on its own worker. The runner calls {@link #init} once, then
 * {@link #process} until it returns {@code false}, then {@link #close}. A source puts one
 * Arrow batch on {@link NodeContext#output()} per call; a node with an input takes one batch
 * from {@link NodeContext#input()} per call and returns {@code false} when it gets {@code null}.
 * Nodes report schema, row counts, profiles and field lineage through
 * {@link NodeContext#metadata()} as they run, so the engine writes the metadata
 * graph without a separate scan.
 *
 * <p>This interface is provisional until three different connectors have been
 * built on it (sprint 3); see docs/adr/0005-node-plugin-interface.md.
 */
public interface Node extends AutoCloseable {

    /** Stable type identifier used in pipeline YAML, for example {@code jdbc-sampler}. */
    String type();

    /** Prepares the node; called once before the first {@link #process} call. */
    void init(NodeContext context) throws NodeException;

    /**
     * Processes the next unit of work.
     *
     * @return {@code true} if the node may produce more output, {@code false} when finished
     */
    boolean process(NodeContext context) throws NodeException;

    /** Releases resources. Called once, even after a failure. */
    @Override
    default void close() {
        // Most nodes hold nothing that needs releasing.
    }
}
