package io.kilnmark.kiln.graph;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RunResult;

/**
 * Runs a pipeline graph: every step on its own virtual thread, each edge a bounded row set, an
 * output feeding several inputs copied to each, inside one memory limit. A failing step stops the
 * run; the result names the step where the failure started and the rows that crossed each edge.
 */
public interface GraphRunner {

    RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException;
}
