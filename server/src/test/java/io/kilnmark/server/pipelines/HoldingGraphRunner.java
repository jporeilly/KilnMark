package io.kilnmark.server.pipelines;

import java.util.concurrent.atomic.AtomicReference;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

/**
 * The graph runner in tests: kiln-core's, except that while {@link #hold} is set a run waits until
 * it is interrupted, so tests can catch a run while it is running and cancel it. It also keeps the
 * last graph it was given.
 */
@Alternative
@Priority(1)
@Singleton
public class HoldingGraphRunner implements GraphRunner {

    static volatile boolean hold;
    /** When set, the next run fails in the engine with this message. */
    static volatile String failWith;
    static final AtomicReference<PipelineGraph> LAST = new AtomicReference<>();

    private final GraphRunner real = new KilnGraphRunner(256L << 20, 4);

    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException {
        return run(graph, metadata, io.kilnmark.kiln.graph.RunListener.NONE);
    }

    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata, io.kilnmark.kiln.graph.RunListener listener)
        throws InterruptedException {
        LAST.set(graph);
        String fail = failWith;
        if (fail != null) {
            failWith = null;
            throw new IllegalStateException(fail);
        }
        while (hold) {
            Thread.sleep(100);
        }
        return real.run(graph, metadata, listener);
    }
}
