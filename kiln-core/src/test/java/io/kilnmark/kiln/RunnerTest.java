package io.kilnmark.kiln;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

class RunnerTest {

    private static final Schema SCHEMA = new Schema(List.of(Field.nullable("n", new ArrowType.Int(32, true))));

    /** Emits {@code batches} batches of 1,000 ints. */
    static final class Numbers implements Step {
        private final int batches;
        private final int failAt;
        private int sent;

        Numbers(int batches, int failAt) {
            this.batches = batches;
            this.failAt = failAt;
        }

        @Override
        public String type() {
            return "numbers";
        }

        @Override
        public void init(StepContext context) {
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            if (sent == failAt) {
                throw new StepException("source broke");
            }
            VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, context.allocator());
            IntVector v = (IntVector) root.getVector(0);
            v.allocateNew(1000);
            for (int i = 0; i < 1000; i++) {
                v.set(i, sent * 1000 + i);
            }
            root.setRowCount(1000);
            try {
                context.output().put(root);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("interrupted", e);
            }
            return ++sent < batches;
        }
    }

    /** Counts rows; optionally slow, failing, or leaking a batch. */
    static final class Count implements Step {
        final AtomicLong rows = new AtomicLong();
        private final long sleepMillis;
        private final boolean fail;
        private final boolean leak;

        Count(long sleepMillis, boolean fail, boolean leak) {
            this.sleepMillis = sleepMillis;
            this.fail = fail;
            this.leak = leak;
        }

        @Override
        public String type() {
            return "count";
        }

        @Override
        public void init(StepContext context) {
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            try {
                VectorSchemaRoot b = context.input().take(context.allocator());
                if (b == null) {
                    return false;
                }
                rows.addAndGet(b.getRowCount());
                if (!leak) {
                    b.close();
                }
                if (fail) {
                    throw new StepException("sink broke");
                }
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                if (context.hasOutput()) {
                    throw new StepException("count is a sink");
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("interrupted", e);
            }
        }
    }

    private static Pipeline line(Step... steps) {
        var specs = new java.util.ArrayList<StepSpec>();
        for (int i = 0; i < steps.length; i++) {
            specs.add(new StepSpec("s" + i, steps[i], Map.of()));
        }
        return new Pipeline("test", specs);
    }

    @Test
    void movesEveryRowAndFreesAllMemory() throws Exception {
        Count sink = new Count(0, false, false);
        RunResult r = new Runner(64L << 20, 4).run(line(new Numbers(200, -1), sink), e -> { });
        assertTrue(r.succeeded(), r.error());
        assertEquals(200_000, sink.rows.get());
        assertEquals(200_000, r.steps().get(0).rowsOut());
    }

    @Test
    void backPressureKeepsMemoryUnderALimitSmallerThanTheData() throws Exception {
        // 400 batches of about 4 KB each through a 64 KB limit: only possible if the source waits.
        Count sink = new Count(1, false, false);
        RunResult r = new Runner(64L << 10, 2).run(line(new Numbers(400, -1), sink), e -> { });
        assertTrue(r.succeeded(), r.error());
        assertEquals(400_000, sink.rows.get());
    }

    @Test
    void aFailingSourceIsReportedAndTheRunEnds() throws Exception {
        Count sink = new Count(0, false, false);
        RunResult r = new Runner(64L << 20, 4).run(line(new Numbers(100, 10), sink), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("s0") && r.error().contains("source broke"), r.error());
        assertNull(r.steps().get(1).error(), "the sink only saw the source fail");
    }

    @Test
    void aFailingSinkStopsTheSourceInsteadOfHanging() throws Exception {
        RunResult r = new Runner(64L << 20, 2).run(line(new Numbers(10_000, -1), new Count(0, true, false)),
            e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("sink broke"), r.error());
        assertNull(r.steps().get(0).error(), "the source was stopped, not failed");
    }

    @Test
    void aStepThatLeaksMemoryIsNamed() throws Exception {
        RunResult r = new Runner(64L << 20, 4).run(line(new Numbers(3, -1), new Count(0, false, true)), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("s1") && r.error().contains("memory"), r.error());
    }

    @Test
    void metadataEventsReachTheEmitter() throws Exception {
        List<MetadataEvent> seen = new CopyOnWriteArrayList<>();
        Step reporter = new Step() {
            @Override
            public String type() {
                return "reporter";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) {
                context.metadata().emit(new MetadataEvent("urn:kilnmark:pg:t", MetadataEvent.Kind.ROW_COUNT,
                    Map.of("rows", "1"), Instant.now()));
                return false;
            }
        };
        RunResult r = new Runner(1L << 20, 1).run(line(reporter), seen::add);
        assertTrue(r.succeeded(), r.error());
        assertEquals(1, seen.size());
    }
}
