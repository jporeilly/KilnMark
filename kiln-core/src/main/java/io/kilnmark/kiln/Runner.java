package io.kilnmark.kiln;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphNode;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;

/**
 * Runs a pipeline in a line: each node on its own virtual thread, joined by bounded {@link RowSet}s,
 * inside one memory limit. A failing node stops the nodes before it and ends the nodes after it; the
 * run reports the node where the failure started. If the thread calling {@link #run} is interrupted,
 * every node is stopped and waited for before the {@link InterruptedException} is thrown, so no
 * node outlives the call.
 *
 * <p>The line is run as a graph by {@link KilnGraphRunner}: each node's {@code out} port feeds the
 * next node's {@code in} port, and its settings are its configuration.
 */
public final class Runner {

    private final KilnGraphRunner graphRunner;

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each row set holds before the node before it waits
     */
    public Runner(long memoryLimitBytes, int rowSetCapacity) {
        this.graphRunner = new KilnGraphRunner(memoryLimitBytes, rowSetCapacity);
    }

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each row set holds before the node before it waits
     * @param stallTimeout     how long no batch may move while every node waits before the run fails
     */
    public Runner(long memoryLimitBytes, int rowSetCapacity, Duration stallTimeout) {
        this.graphRunner = new KilnGraphRunner(memoryLimitBytes, rowSetCapacity, stallTimeout);
    }

    /**
     * Runs {@code pipeline} to the end and reports each node.
     *
     * @throws InterruptedException if the calling thread is interrupted; the nodes have stopped by then
     */
    public RunResult run(Pipeline pipeline, MetadataEmitter metadata) throws InterruptedException {
        return graphRunner.run(graph(pipeline), metadata);
    }

    /** The line as a graph: {@code a.out -> b.in -> ...}. */
    static PipelineGraph graph(Pipeline pipeline) {
        List<GraphNode> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        NodeSpec before = null;
        for (NodeSpec spec : pipeline.nodes()) {
            nodes.add(new GraphNode(spec.name(), spec.node(), new LinkedHashMap<>(spec.settings())));
            if (before != null) {
                edges.add(new Edge(new PortRef(before.name(), "out"), new PortRef(spec.name(), "in")));
            }
            before = spec;
        }
        return new PipelineGraph(pipeline.name(), nodes, edges);
    }
}
