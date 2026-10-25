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
    /** While set, the profile beside each write fails before it starts, as a profile that cannot be made does. */
    static volatile boolean breakProfiles;
    static final AtomicReference<PipelineGraph> LAST = new AtomicReference<>();

    private final GraphRunner real = new KilnGraphRunner(256L << 20, 4);

    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException {
        LAST.set(graph);
        if (breakProfiles) {
            for (io.kilnmark.kiln.graph.GraphStep s : graph.steps()) {
                if (s.step() instanceof TolerantProfile p) {
                    breakProfile(p);
                }
            }
        }
        while (hold) {
            Thread.sleep(100);
        }
        return real.run(graph, metadata);
    }

    /** Sets the profile's failure as its own catch would (tests only, by reflection: no hook in the server). */
    private static void breakProfile(TolerantProfile p) {
        try {
            java.lang.reflect.Field f = TolerantProfile.class.getDeclaredField("failure");
            f.setAccessible(true);
            f.set(p, "cannot profile a column of maps (test)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
