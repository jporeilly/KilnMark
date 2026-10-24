package io.kilnmark.kiln;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

/**
 * Runs a pipeline: each step on its own virtual thread, joined by bounded {@link RowSet}s, inside
 * one memory limit. A failing step stops the steps before it and ends the steps after it; the run
 * reports the step where the failure started. If the thread calling {@link #run} is interrupted,
 * every step is stopped and waited for before the {@link InterruptedException} is thrown, so no
 * step outlives the call.
 */
public final class Runner {

    private final long memoryLimitBytes;
    private final int rowSetCapacity;

    /**
     * @param memoryLimitBytes Arrow memory the whole run may hold at once
     * @param rowSetCapacity   batches each row set holds before the step before it waits
     */
    public Runner(long memoryLimitBytes, int rowSetCapacity) {
        this.memoryLimitBytes = memoryLimitBytes;
        this.rowSetCapacity = rowSetCapacity;
    }

    /**
     * Runs {@code pipeline} to the end and reports each step.
     *
     * @throws InterruptedException if the calling thread is interrupted; the steps have stopped by then
     */
    public RunResult run(Pipeline pipeline, MetadataEmitter metadata) throws InterruptedException {
        Instant started = Instant.now();
        List<StepSpec> specs = pipeline.steps();
        int n = specs.size();
        List<RowSet> rowSets = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            rowSets.add(new RowSet(specs.get(i).name() + "->" + specs.get(i + 1).name(), rowSetCapacity));
        }
        String[] errors = new String[n];
        long[] peaks = new long[n];
        List<Thread> threads = new ArrayList<>();
        try (RootAllocator root = new RootAllocator(memoryLimitBytes)) {
            List<BufferAllocator> allocators = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                allocators.add(root.newChildAllocator(specs.get(i).name(), 0, memoryLimitBytes));
            }
            for (int i = 0; i < n; i++) {
                StepSpec spec = specs.get(i);
                RowSet in = i > 0 ? rowSets.get(i - 1) : null;
                RowSet out = i + 1 < n ? rowSets.get(i) : null;
                Context ctx = new Context(spec.name(), spec.settings(), metadata, allocators.get(i), in, out);
                int index = i;
                threads.add(Thread.ofVirtual().name("kiln-" + pipeline.name() + "-" + spec.name())
                    .start(() -> errors[index] = runStep(spec.step(), ctx)));
            }
            try {
                for (Thread t : threads) {
                    t.join();
                }
            } catch (InterruptedException e) {
                stop(threads, rowSets);
                allocators.forEach(Runner::closeQuietly);
                throw e;
            }
            for (int i = 0; i < n; i++) {
                BufferAllocator a = allocators.get(i);
                peaks[i] = a.getPeakMemoryAllocation();
                try {
                    a.close();
                } catch (IllegalStateException leak) {
                    if (errors[i] == null) {
                        errors[i] = "Step " + specs.get(i).name() + " did not free all its memory: " + leak.getMessage();
                    }
                }
            }
        } catch (IllegalStateException leak) {
            // reported per step above
        }
        List<RunResult.StepResult> results = new ArrayList<>();
        String first = null;
        for (int i = 0; i < n; i++) {
            long rowsOut = i + 1 < n ? rowSets.get(i).rows() : 0;
            results.add(new RunResult.StepResult(specs.get(i).name(), specs.get(i).step().type(), rowsOut,
                peaks[i], errors[i]));
            if (first == null && errors[i] != null) {
                first = errors[i];
            }
        }
        return new RunResult(pipeline.name(), first == null ? RunResult.Status.SUCCEEDED : RunResult.Status.FAILED,
            started, Instant.now(), results, first);
    }

    /**
     * Stops every step: cancels the row sets (a blocked put or take fails at once) and interrupts the
     * step threads, then waits for all of them, however often this thread is interrupted meanwhile.
     * The interrupt flag is set again on return.
     */
    private static void stop(List<Thread> threads, List<RowSet> rowSets) {
        rowSets.forEach(RowSet::cancel);
        threads.forEach(Thread::interrupt);
        boolean interrupted = false;
        for (Thread t : threads) {
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(BufferAllocator a) {
        try {
            a.close();
        } catch (IllegalStateException leak) {
            // stopped mid-batch; the run is abandoned, so a leak here is not reported
        }
    }

    /** Runs one step to the end; returns its own failure, or null. */
    private static String runStep(Step step, Context ctx) {
        String error = null;
        try {
            step.init(ctx);
            while (step.process(ctx)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            if (ctx.out != null) {
                ctx.out.finish();
            }
        } catch (Throwable t) {
            boolean ownFailure = !(t instanceof RowSet.UpstreamFailedException)
                && !(t instanceof RowSet.CancelledException);
            if (ownFailure) {
                error = "Step " + ctx.name + " (" + step.type() + ") failed: " + message(t);
            }
            try {
                if (ctx.out != null) {
                    ctx.out.fail(t);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (ctx.in != null) {
                ctx.in.cancel();
            }
        } finally {
            try {
                step.close();
            } catch (RuntimeException e) {
                if (error == null) {
                    error = "Step " + ctx.name + " failed to close: " + message(e);
                }
            }
        }
        return error;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private record Context(String name, Map<String, String> settings, MetadataEmitter metadata,
                           BufferAllocator allocator, RowSet in, RowSet out) implements StepContext {

        @Override
        public String stepName() {
            return name;
        }

        @Override
        public RowSet input() {
            if (in == null) {
                throw new IllegalStateException("Step " + name + " has no input: it is the first step");
            }
            return in;
        }

        @Override
        public RowSet output() {
            if (out == null) {
                throw new IllegalStateException("Step " + name + " has no output: it is the last step");
            }
            return out;
        }

        @Override
        public boolean hasInput() {
            return in != null;
        }

        @Override
        public boolean hasOutput() {
            return out != null;
        }
    }
}
