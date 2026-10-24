package io.kilnmark.kiln;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
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

    private final String name;
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicLong rows = new AtomicLong();
    private volatile Throwable failure;
    private volatile boolean cancelled;

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
        Object o = queue.take();
        if (o == END) {
            if (failure != null) {
                throw new UpstreamFailedException("the step before " + name + " failed", failure);
            }
            return null;
        }
        return transfer((VectorSchemaRoot) o, into);
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
