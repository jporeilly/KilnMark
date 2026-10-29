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
    static final class Numbers implements Node {
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
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            if (sent == failAt) {
                throw new NodeException("source broke");
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
                throw new NodeException("interrupted", e);
            }
            return ++sent < batches;
        }
    }

    /** Counts rows; optionally slow, failing, or leaking a batch. */
    static final class Count implements Node {
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
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
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
                    throw new NodeException("sink broke");
                }
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                if (context.hasOutput()) {
                    throw new NodeException("count is a sink");
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
        }
    }

    private static Pipeline line(Node... nodes) {
        var specs = new java.util.ArrayList<NodeSpec>();
        for (int i = 0; i < nodes.length; i++) {
            specs.add(new NodeSpec("s" + i, nodes[i], Map.of()));
        }
        return new Pipeline("test", specs);
    }

    @Test
    void movesEveryRowAndFreesAllMemory() throws Exception {
        Count sink = new Count(0, false, false);
        RunResult r = new Runner(64L << 20, 4).run(line(new Numbers(200, -1), sink), e -> { });
        assertTrue(r.succeeded(), r.error());
        assertEquals(200_000, sink.rows.get());
        assertEquals(200_000, r.nodes().get(0).rowsOut());
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
        assertNull(r.nodes().get(1).error(), "the sink only saw the source fail");
    }

    @Test
    void aFailingSinkStopsTheSourceInsteadOfHanging() throws Exception {
        Numbers source = new Numbers(10_000, -1);
        RunResult r = new Runner(64L << 20, 2).run(line(source, new Count(0, true, false)), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("sink broke"), r.error());
        assertNull(r.nodes().get(0).error(), "the source was stopped, not failed");
        assertTrue(source.sent < 10, "the source stopped soon after the sink, not at its end: sent " + source.sent);
    }

    @Test
    void interruptingTheCallerStopsEveryNodeBeforeRunReturns() throws Exception {
        java.util.concurrent.CountDownLatch flowing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        Numbers numbers = new Numbers(Integer.MAX_VALUE, -1);
        Node endless = new Node() {
            @Override
            public String type() {
                return numbers.type();
            }

            @Override
            public void init(NodeContext context) {
            }

            @Override
            public boolean process(NodeContext context) throws NodeException {
                flowing.countDown();
                return numbers.process(context);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                new Runner(64L << 20, 2).run(line(endless, new Count(5, false, false)), e -> { });
                outcome.set(new AssertionError("the run ended by itself"));
            } catch (InterruptedException e) {
                outcome.set(e);
            }
        });
        assertTrue(flowing.await(10, java.util.concurrent.TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(java.time.Duration.ofSeconds(10));
        assertTrue(!caller.isAlive(), "run returned after the interrupt");
        assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
        assertTrue(closed.get(), "the source node was stopped and closed before run returned");
    }

    @Test
    void aNodeThatLeaksMemoryIsNamed() throws Exception {
        RunResult r = new Runner(64L << 20, 4).run(line(new Numbers(3, -1), new Count(0, false, true)), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("s1") && r.error().contains("memory"), r.error());
    }

    @Test
    void metadataEventsReachTheEmitter() throws Exception {
        List<MetadataEvent> seen = new CopyOnWriteArrayList<>();
        Node reporter = new Node() {
            @Override
            public String type() {
                return "reporter";
            }

            @Override
            public void init(NodeContext context) {
            }

            @Override
            public boolean process(NodeContext context) {
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
