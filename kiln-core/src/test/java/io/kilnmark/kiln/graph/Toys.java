package io.kilnmark.kiln.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/** Small steps for testing graphs: generate numbers, pass them on, split them, merge and collect them. */
public final class Toys {

    public static final Schema N = new Schema(List.of(Field.nullable("n", new ArrowType.Int(32, true))));

    private Toys() {
    }

    static VectorSchemaRoot batch(BufferAllocator a, int from, int rows) {
        VectorSchemaRoot root = VectorSchemaRoot.create(N, a);
        IntVector v = (IntVector) root.getVector(0);
        v.allocateNew(rows);
        for (int i = 0; i < rows; i++) {
            v.set(i, from + i);
        }
        root.setRowCount(rows);
        return root;
    }

    static StepException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new StepException("interrupted", e);
    }

    /** Emits {@code batches} batches of {@code rows} numbers counting up from {@code start}; fails at batch {@code failAt}. */
    public static final class Generate implements Step {
        final int batches;
        final int rows;
        final int start;
        final int failAt;
        final AtomicInteger sent = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        public Generate(int batches, int rows, int start, int failAt) {
            this.batches = batches;
            this.rows = rows;
            this.start = start;
            this.failAt = failAt;
        }

        public Generate(int batches, int rows) {
            this(batches, rows, 0, -1);
        }

        @Override
        public String type() {
            return "generate";
        }

        @Override
        public void init(StepContext context) {
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            int k = sent.get();
            if (k >= batches) {
                return false;
            }
            if (k == failAt) {
                throw new StepException("generate broke");
            }
            try {
                context.emit("out", batch(context.allocator(), start + k * rows, rows));
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
            return sent.incrementAndGet() < batches;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** Passes each batch on; optionally slow, or failing after {@code failAfter} batches. */
    public static final class Pass implements Step {
        final long sleepMillis;
        final int failAfter;
        int seen;

        public Pass(long sleepMillis, int failAfter) {
            this.sleepMillis = sleepMillis;
            this.failAfter = failAfter;
        }

        public Pass() {
            this(0, -1);
        }

        @Override
        public String type() {
            return "pass";
        }

        @Override
        public void init(StepContext context) {
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            try {
                VectorSchemaRoot b = context.input("in").take(context.allocator());
                if (b == null) {
                    return false;
                }
                if (seen++ == failAfter) {
                    b.close();
                    throw new StepException("pass broke");
                }
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                context.emit("out", b);
                return true;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    /** Sends even numbers to {@code even} and odd numbers to {@code odd}. */
    public static final class Split implements Step {
        @Override
        public String type() {
            return "split";
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
                try (b) {
                    IntVector v = (IntVector) b.getVector(0);
                    context.emit("even", pick(context.allocator(), v, 0));
                    context.emit("odd", pick(context.allocator(), v, 1));
                }
                return true;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }

        private static VectorSchemaRoot pick(BufferAllocator a, IntVector from, int parity) {
            VectorSchemaRoot out = VectorSchemaRoot.create(N, a);
            IntVector v = (IntVector) out.getVector(0);
            v.allocateNew(from.getValueCount());
            int n = 0;
            for (int i = 0; i < from.getValueCount(); i++) {
                if (Math.floorMod(from.get(i), 2) == parity) {
                    v.set(n++, from.get(i));
                }
            }
            out.setRowCount(n);
            return out;
        }
    }

    /** Takes from every input on every port, whichever has a batch, and passes it on. */
    public static final class Merge implements Step {
        @Override
        public String type() {
            return "merge";
        }

        @Override
        public void init(StepContext context) {
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            List<RowSet> all = new java.util.ArrayList<>();
            for (String port : context.inputPorts()) {
                all.addAll(context.inputs(port));
            }
            try {
                RowSet.Taken t = RowSet.takeAny(all, context.allocator());
                if (t == null) {
                    return false;
                }
                context.emit("out", t.batch());
                return true;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    /**
     * Collects the numbers it is given; optionally slow, scribbling over each batch before freeing
     * it, stopping after {@code limit} batches, failing, or leaking a batch.
     */
    public static class Collect implements Step {
        final ConcurrentLinkedQueue<Integer> values = new ConcurrentLinkedQueue<>();
        final AtomicLong rows = new AtomicLong();
        final AtomicBoolean foreignMemory = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        long sleepMillis;
        boolean scribble;
        boolean leak;
        int limit = -1;
        int failAfter = -1;
        int batches;
        volatile Map<String, String> settings;
        volatile Map<String, Object> config;
        volatile Set<String> inputPorts;

        @Override
        public String type() {
            return "collect";
        }

        @Override
        public void init(StepContext context) {
            settings = context.settings();
            config = context.config();
            inputPorts = context.inputPorts();
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            if (batches == limit) {
                return false;
            }
            try {
                VectorSchemaRoot b = context.input().take(context.allocator());
                if (b == null) {
                    return false;
                }
                if (b.getVector(0).getAllocator() != context.allocator()) {
                    foreignMemory.set(true);
                }
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                IntVector v = (IntVector) b.getVector(0);
                for (int i = 0; i < b.getRowCount(); i++) {
                    values.add(v.get(i));
                    if (scribble) {
                        v.set(i, -1);
                    }
                }
                rows.addAndGet(b.getRowCount());
                if (!leak) {
                    b.close();
                }
                if (++batches == failAfter) {
                    throw new StepException("collect broke");
                }
                return true;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        long sum() {
            return values.stream().mapToLong(Integer::longValue).sum();
        }
    }
}
