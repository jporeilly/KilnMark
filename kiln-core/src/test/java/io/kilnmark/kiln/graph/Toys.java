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
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;

/** Small nodes for testing graphs: generate numbers, pass them on, split them, merge and collect them. */
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

    static NodeException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new NodeException("interrupted", e);
    }

    /** Emits {@code batches} batches of {@code rows} numbers counting up from {@code start}; fails at batch {@code failAt}. */
    public static final class Generate implements Node {
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
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            int k = sent.get();
            if (k >= batches) {
                return false;
            }
            if (k == failAt) {
                throw new NodeException("generate broke");
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
    public static final class Pass implements Node {
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
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try {
                VectorSchemaRoot b = context.input("in").take(context.allocator());
                if (b == null) {
                    return false;
                }
                if (seen++ == failAfter) {
                    b.close();
                    throw new NodeException("pass broke");
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
    public static final class Split implements Node {
        @Override
        public String type() {
            return "split";
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
    public static final class Merge implements Node {
        @Override
        public String type() {
            return "merge";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
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

    /** Reads port {@code a} to its end, then port {@code b}, dropping the rows: the way a lookup reads. */
    public static final class Sequential implements Node {
        private String port = "a";

        @Override
        public String type() {
            return "sequential";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try {
                VectorSchemaRoot b = context.input(port).take(context.allocator());
                if (b != null) {
                    b.close();
                    return true;
                }
                if (port.equals("a")) {
                    port = "b";
                    return true;
                }
                return false;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    /** Emits one batch, then waits a long time outside any row set, as a node waiting on a database does. */
    public static final class Slow implements Node {
        final AtomicBoolean closed = new AtomicBoolean();
        private boolean sent;

        @Override
        public String type() {
            return "slow";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try {
                if (!sent) {
                    sent = true;
                    context.emit("out", batch(context.allocator(), 0, 10));
                    return true;
                }
                Thread.sleep(60_000);
                return false;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * Collects the numbers it is given; optionally slow, scribbling over each batch before freeing
     * it, stopping after {@code limit} batches, failing, or leaking a batch.
     */
    public static class Collect implements Node {
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
        public void init(NodeContext context) {
            settings = context.settings();
            config = context.config();
            inputPorts = context.inputPorts();
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
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
                    throw new NodeException("collect broke");
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
