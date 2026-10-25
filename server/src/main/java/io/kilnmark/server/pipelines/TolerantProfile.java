package io.kilnmark.server.pipelines;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * The hidden profile step beside a write: if profiling fails, it notes why and goes on taking (and
 * dropping) its copy of the rows, so the write and the run are not failed by their profile. The
 * run then says the target could not be profiled, and a scan can do it.
 */
final class TolerantProfile implements Step {

    private final Step inner;
    private volatile String failure;

    TolerantProfile(Step inner) {
        this.inner = inner;
    }

    /** Why profiling failed, or null. */
    String failure() {
        return failure;
    }

    @Override
    public String type() {
        return inner.type();
    }

    @Override
    public void init(StepContext context) throws StepException {
        try {
            inner.init(context);
        } catch (StepException | RuntimeException e) {
            failure = message(e);
        }
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        if (failure == null) {
            try {
                return inner.process(context);
            } catch (RowSet.CancelledException | RowSet.UpstreamFailedException e) {
                throw e; // the engine stopping the run, not the profile failing
            } catch (StepException | RuntimeException e) {
                failure = message(e);
            }
        }
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (batch == null) {
            return false;
        }
        batch.close();
        return true;
    }

    @Override
    public void close() {
        inner.close();
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
