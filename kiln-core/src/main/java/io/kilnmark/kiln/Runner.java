package io.kilnmark.kiln;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;

/**
 * Runs a pipeline in a line: each step on its own virtual thread, joined by bounded {@link RowSet}s,
 * inside one memory limit. A failing step stops the steps before it and ends the steps after it; the
 * run reports the step where the failure started. If the thread calling {@link #run} is interrupted,
 * every step is stopped and waited for before the {@link InterruptedException} is thrown, so no
 * step outlives the call.
 *
 * <p>The line is run as a graph by {@link KilnGraphRunner}: each step's {@code out} port feeds the
 * next step's {@code in} port, and its settings are its configuration.
 */
public final class Runner {

    private final KilnGraphRunner graphRunner;

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each row set holds before the step before it waits
     */
    public Runner(long memoryLimitBytes, int rowSetCapacity) {
        this.graphRunner = new KilnGraphRunner(memoryLimitBytes, rowSetCapacity);
    }

    /**
     * Runs {@code pipeline} to the end and reports each step.
     *
     * @throws InterruptedException if the calling thread is interrupted; the steps have stopped by then
     */
    public RunResult run(Pipeline pipeline, MetadataEmitter metadata) throws InterruptedException {
        return graphRunner.run(graph(pipeline), metadata);
    }

    /** The line as a graph: {@code a.out -> b.in -> ...}. */
    static PipelineGraph graph(Pipeline pipeline) {
        List<GraphStep> steps = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        StepSpec before = null;
        for (StepSpec spec : pipeline.steps()) {
            steps.add(new GraphStep(spec.name(), spec.step(), new LinkedHashMap<>(spec.settings())));
            if (before != null) {
                edges.add(new Edge(new PortRef(before.name(), "out"), new PortRef(spec.name(), "in")));
            }
            before = spec;
        }
        return new PipelineGraph(pipeline.name(), steps, edges);
    }
}
