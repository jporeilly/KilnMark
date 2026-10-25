package io.kilnmark.kiln;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.TransferPair;

/**
 * A bounded hand-over of Arrow batches from one step to the next.
 *
 * <p>{@link #put} blocks while {@code capacity} batches are waiting: that is the back-pressure that
 * keeps a fast source from outrunning a slow step. Ownership moves with each batch. {@link #take}
 * moves the batch's buffers into the taking step's allocator, so memory is accounted per step, and
 * the taker must close what it takes. See docs/spikes/0001-arrow-row-sets.md.
 */
public final class RowSet {

    private static final Object END = new Object();
    private static final long POLL_MILLIS = 100;
    private static final long TAKE_ANY_WAIT_MILLIS = 5;

    private final String name;
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicLong rows = new AtomicLong();
    private volatile Throwable failure;
    private volatile boolean cancelled;
    private volatile boolean ended;

    public RowSet(String name, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.name = name;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public String name() {
        return name;
    }

    /** Rows put so far. */
    public long rows() {
        return rows.get();
    }

    /**
     * Hands a batch to the next step, waiting while the row set is full. The caller gives up the
     * batch: it must not use or close it afterwards.
     *
     * @throws StepException if the next step has stopped, in which case the batch has been closed
     */
    public void put(VectorSchemaRoot batch) throws InterruptedException, StepException {
        if (cancelled) { // fail fast even when there is room, or a source would read to its end for nothing
            batch.close();
            throw new CancelledException("the next step stopped reading " + name);
        }
        while (!queue.offer(batch, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (cancelled) {
                batch.close();
                throw new CancelledException("the next step stopped reading " + name);
            }
        }
        rows.addAndGet(batch.getRowCount());
        if (cancelled) {
            drain();
        }
    }

    /** No more batches. */
    public void finish() throws InterruptedException {
        end();
    }

    /** No more batches, because this step failed; the next step's {@link #take} reports it. */
    public void fail(Throwable cause) throws InterruptedException {
        failure = cause;
        end();
    }

    private void end() throws InterruptedException {
        while (!cancelled && !queue.offer(END, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            // wait for room, unless the next step has stopped
        }
    }

    /**
     * The next batch, moved into {@code into}, or {@code null} at the end of the stream.
     *
     * @throws UpstreamFailedException if the step before this one failed
     */
    public VectorSchemaRoot take(BufferAllocator into) throws InterruptedException, StepException {
        return received(queue.take(), into);
    }

    /**
     * The next batch if one arrives within {@code timeout}, moved into {@code into}; {@code null} if
     * none did or the stream has ended, which {@link #atEnd()} tells apart.
     *
     * @throws UpstreamFailedException if the step before this one failed
     */
    public VectorSchemaRoot poll(BufferAllocator into, long timeout, TimeUnit unit)
        throws InterruptedException, StepException {
        if (ended) {
            return null;
        }
        Object o = timeout <= 0 ? queue.poll() : queue.poll(timeout, unit);
        return o == null ? null : received(o, into);
    }

    /** Whether the reader has been told the stream ended, by {@link #take} or {@link #poll}. */
    public boolean atEnd() {
        return ended;
    }

    private VectorSchemaRoot received(Object o, BufferAllocator into) throws StepException {
        if (o == END) {
            ended = true;
            if (failure != null) {
                throw new UpstreamFailedException("the step before " + name + " failed", failure);
            }
            return null;
        }
        return transfer((VectorSchemaRoot) o, into);
    }

    /** A batch taken by {@link #takeAny}, and the position in the list of the row set it came from. */
    public record Taken(int index, VectorSchemaRoot batch) {
    }

    /**
     * The next batch from whichever of these row sets has one, or {@code null} once every one has
     * ended. For a step that reads several inputs and does not mind the order (union): reading them
     * one after another would stall a pipeline whose inputs share a step upstream, as that step
     * waits for room on the input not being read.
     *
     * @throws UpstreamFailedException if a step before any of them failed
     */
    public static Taken takeAny(List<RowSet> from, BufferAllocator into) throws InterruptedException, StepException {
        int n = from.size();
        while (true) {
            int start = n == 0 ? 0 : ThreadLocalRandom.current().nextInt(n);
            RowSet waitOn = null;
            for (int k = 0; k < n; k++) {
                int i = (start + k) % n;
                RowSet r = from.get(i);
                VectorSchemaRoot b = r.poll(into, 0, TimeUnit.MILLISECONDS);
                if (b != null) {
                    return new Taken(i, b);
                }
                if (!r.atEnd() && waitOn == null) {
                    waitOn = r;
                }
            }
            if (waitOn == null) {
                return null;
            }
            VectorSchemaRoot b = waitOn.poll(into, TAKE_ANY_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (b != null) {
                return new Taken(from.indexOf(waitOn), b);
            }
        }
    }

    /** The reading step has stopped: release waiting batches and make further puts fail fast. */
    public void cancel() {
        cancelled = true;
        drain();
    }

    private void drain() {
        List<Object> left = new ArrayList<>();
        queue.drainTo(left);
        for (Object o : left) {
            if (o instanceof VectorSchemaRoot batch) {
                batch.close();
            }
        }
    }

    static VectorSchemaRoot transfer(VectorSchemaRoot from, BufferAllocator to) {
        List<FieldVector> moved = new ArrayList<>(from.getFieldVectors().size());
        try {
            for (FieldVector v : from.getFieldVectors()) {
                TransferPair tp = v.getTransferPair(to);
                tp.transfer();
                moved.add((FieldVector) tp.getTo());
            }
            return new VectorSchemaRoot(from.getSchema(), moved, from.getRowCount());
        } finally {
            from.close();
        }
    }

    /** The step before failed; the runner reports that step's failure, not this one. */
    public static final class UpstreamFailedException extends StepException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        UpstreamFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The step after stopped reading; the runner reports that step's failure, not this one. */
    public static final class CancelledException extends StepException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        CancelledException(String message) {
            super(message);
        }
    }
}
