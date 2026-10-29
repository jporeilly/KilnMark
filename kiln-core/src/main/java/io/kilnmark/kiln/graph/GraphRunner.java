package io.kilnmark.kiln.graph;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RunResult;

/**
 * Runs a pipeline graph: every node on its own virtual thread, each edge a bounded row set, an
 * output feeding several inputs copied to each, inside one memory limit. A failing node stops the
 * run; the result names the node where the failure started and the rows that crossed each edge.
 */
public interface GraphRunner {

    RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException;

    /**
     * Runs {@code graph}, telling {@code listener} how it goes. A runner that cannot report runs
     * without telling it anything.
     */
    default RunResult run(PipelineGraph graph, MetadataEmitter metadata, RunListener listener)
        throws InterruptedException {
        return run(graph, metadata);
    }
}
