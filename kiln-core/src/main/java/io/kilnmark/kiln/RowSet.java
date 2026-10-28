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
 *
 * <p>{@link #rows()} counts the rows the reader was given; batches dropped because the reader
 * stopped, or because the step before failed, are not counted.
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
    private final AtomicLong moves = new AtomicLong();
    private volatile long readerWaitingSince;
    private volatile long writerWaitingSince;
    private final int capacity;
    // System.nanoTime() of the first and last batch with rows handed over and taken; 0 for none yet.
    // Plain volatile writes per batch, so timing a run allocates nothing.
    private volatile long firstPut;
    private volatile long lastPut;
    private volatile long firstTaken;
    private volatile long lastTaken;
    // Time the writer spent waiting for room, and the reader waiting for a batch, in nanoseconds.
    // Each has one writer (the writing or the reading thread), so a plain volatile add is enough.
    private volatile long putWaitNanos;
    private volatile long takeWaitNanos;

    public RowSet(String name, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.name = name;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Batches the row set holds before the writer waits. */
    public int capacity() {
        return capacity;
    }

    /** Batches waiting to be taken right now (the end counts as one). */
    public int queued() {
        return queue.size();
    }

    /**
     * When the first batch with rows was handed over by {@link #put}, as {@link System#nanoTime()};
     * 0 when none has been.
     */
    public long firstPutNanos() {
        return firstPut;
    }

    /** When the last batch with rows was handed over by {@link #put}, as {@link System#nanoTime()}; 0 for none. */
    public long lastPutNanos() {
        return lastPut;
    }

    /** When the reader was first given a batch with rows, as {@link System#nanoTime()}; 0 for none. */
    public long firstTakenNanos() {
        return firstTaken;
    }

    /** When the reader was last given a batch with rows, as {@link System#nanoTime()}; 0 for none. */
    public long lastTakenNanos() {
        return lastTaken;
    }

    /**
     * Nanoseconds the writer has spent waiting for room ({@link #put}, {@link #finish}): the step
     * writing here held up by the step reading. Counted as it goes, at most 100 ms behind.
     */
    public long putWaitNanos() {
        return putWaitNanos;
    }

    /**
     * Nanoseconds the reader has spent waiting for a batch ({@link #take}, a timed {@link #poll},
     * {@link #takeAny}): the step reading here waiting for rows. Counted as it goes, at most 100 ms behind.
     */
    public long takeWaitNanos() {
        return takeWaitNanos;
    }

    /** How full the row set is now, from 0 (empty) to 1 (full, or its writer waiting for room). */
    public double fill() {
        return writerWaiting() ? 1.0 : Math.min(1.0, queue.size() / (double) capacity);
    }

    public String name() {
        return name;
    }

    /** Rows the reader has been given so far. */
    public long rows() {
        return rows.get();
    }

    /**
     * How many times a batch or the end has moved through this row set: the engine's stall
     * watchdog watches it change.
     */
    public long progress() {
        return moves.get();
    }

    /** Whether the reader is waiting for a batch right now ({@link #take}, a timed {@link #poll}, {@link #takeAny}). */
    public boolean readerWaiting() {
        return readerWaitingSince != 0;
    }

    /** Whether the writer is waiting for room. */
    public boolean writerWaiting() {
        return writerWaitingSince != 0;
    }

    /** Whether the reader has stopped reading ({@link #cancel()}). */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Hands a batch to the next step, waiting while the row set is full. The caller gives up the
     * batch: it must not use or close it afterwards.
     *
     * @throws StepException if the next step has stopped, in which case the batch has been closed
     */
    public void put(VectorSchemaRoot batch) throws InterruptedException, StepException {
        int count = batch.getRowCount(); // read before the batch is handed over: the reader may close it
        if (cancelled) { // fail fast even when there is room, or a source would read to its end for nothing
            batch.close();
            throw new CancelledException("the next step stopped reading " + name);
        }
        if (!queue.offer(batch)) { // full: wait, counting the time
            long last = System.nanoTime();
            writerWaitingSince = last;
            try {
                while (!queue.offer(batch, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    long now = System.nanoTime();
                    putWaitNanos += now - last;
                    last = now;
                    if (cancelled) {
                        batch.close();
                        throw new CancelledException("the next step stopped reading " + name);
                    }
                }
            } catch (InterruptedException e) {
                batch.close(); // it never reached the queue
                throw e;
            } finally {
                putWaitNanos += System.nanoTime() - last;
                writerWaitingSince = 0;
            }
        }
        moves.incrementAndGet();
        if (count > 0) {
            long now = System.nanoTime();
            if (firstPut == 0) {
                firstPut = now;
            }
            lastPut = now;
        }
        if (cancelled) {
            drain();
        }
    }

    /** No more batches; waits for room for the end, unless the next step has stopped. */
    public void finish() throws InterruptedException {
        if (!cancelled && !queue.offer(END)) {
            long last = System.nanoTime();
            writerWaitingSince = last;
            try {
                while (!cancelled && !queue.offer(END, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    long now = System.nanoTime();
                    putWaitNanos += now - last;
                    last = now;
                }
            } finally {
                putWaitNanos += System.nanoTime() - last;
                writerWaitingSince = 0;
            }
        }
        moves.incrementAndGet();
    }

    /**
     * No more batches, because this step failed; the next step's {@link #take} reports it. Never
     * waits: batches still queued are dropped (the run is failing) to make room for the end, so a
     * failure can always reach the steps after it.
     *
     * @throws InterruptedException never; kept so callers written for the waiting version still compile
     */
    public void fail(Throwable cause) throws InterruptedException {
        failure = cause;
        while (!cancelled) {
            drain();
            if (queue.offer(END)) { // only the writer adds, so after draining there is room
                break;
            }
        }
        moves.incrementAndGet();
    }

    /**
     * The next batch, moved into {@code into}, or {@code null} at the end of the stream.
     *
     * @throws UpstreamFailedException if the step before this one failed
     */
    public VectorSchemaRoot take(BufferAllocator into) throws InterruptedException, StepException {
        Object o = queue.poll();
        if (o == null) {
            long last = System.nanoTime();
            readerWaitingSince = last;
            try {
                while ((o = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)) == null) {
                    long now = System.nanoTime();
                    takeWaitNanos += now - last;
                    last = now;
                }
            } finally {
                takeWaitNanos += System.nanoTime() - last;
                readerWaitingSince = 0;
            }
        }
        return received(o, into);
    }

    /**
     * The next batch if one arrives within {@code timeout}, moved into {@code into}; {@code null} if
     * none did or the stream has ended, which {@link #atEnd()} tells apart.
     *
     * @throws UpstreamFailedException if the step before this one failed
     */
    public VectorSchemaRoot poll(BufferAllocator into, long timeout, TimeUnit unit)
        throws InterruptedException, StepException {
        Object o = next(timeout, unit, true);
        return o == null ? null : received(o, into);
    }

    /**
     * Reads the next batch and closes it without counting its rows, for an engine dropping what a
     * finished step no longer reads. Returns {@code false} once the stream has ended.
     *
     * @throws UpstreamFailedException if the step before this one failed
     */
    public boolean discard(long timeout, TimeUnit unit) throws InterruptedException, StepException {
        long waited = takeWaitNanos;
        Object o;
        try {
            o = next(timeout, unit, true);
        } finally {
            takeWaitNanos = waited; // the engine dropping rows is not the step waiting for them
        }
        if (o == null) {
            return !ended;
        }
        if (o == END) {
            received(o, null);
            return false;
        }
        ((VectorSchemaRoot) o).close();
        moves.incrementAndGet();
        return true;
    }

    /**
     * The next item, waiting up to {@code timeout}. With {@code mark}, the reader counts as waiting
     * only while it waits here: a reader that finds nothing and goes off to work (a commit, a remote
     * call) is not taken for a stalled one.
     */
    private Object next(long timeout, TimeUnit unit, boolean mark) throws InterruptedException {
        if (ended) {
            return null;
        }
        Object o = queue.poll();
        if (o == null && timeout > 0) {
            long since = System.nanoTime();
            if (mark) {
                readerWaitingSince = since;
            }
            try {
                o = queue.poll(timeout, unit);
            } finally {
                takeWaitNanos += System.nanoTime() - since;
                if (mark) {
                    readerWaitingSince = 0;
                }
            }
        }
        return o;
    }

    /** Whether the reader has been told the stream ended, by {@link #take} or {@link #poll}. */
    public boolean atEnd() {
        return ended;
    }

    private VectorSchemaRoot received(Object o, BufferAllocator into) throws StepException {
        moves.incrementAndGet();
        if (o == END) {
            ended = true;
            readerWaitingSince = 0;
            if (failure != null) {
                throw new UpstreamFailedException("the step before " + name + " failed", failure);
            }
            return null;
        }
        VectorSchemaRoot batch = (VectorSchemaRoot) o;
        int count = batch.getRowCount();
        rows.addAndGet(count);
        if (count > 0) {
            long now = System.nanoTime();
            if (firstTaken == 0) {
                firstTaken = now;
            }
            lastTaken = now;
        }
        return transfer(batch, into);
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
        List<RowSet> marked = new ArrayList<>(); // waiting on all of them for as long as this call waits
        try {
            while (true) {
                int start = n == 0 ? 0 : ThreadLocalRandom.current().nextInt(n);
                RowSet waitOn = null;
                for (int k = 0; k < n; k++) {
                    int i = (start + k) % n;
                    RowSet r = from.get(i);
                    Object o = r.next(0, TimeUnit.MILLISECONDS, false);
                    if (o != null) {
                        VectorSchemaRoot b = r.received(o, into);
                        if (b != null) {
                            return new Taken(i, b);
                        }
                    }
                    if (!r.atEnd() && waitOn == null) {
                        waitOn = r;
                    }
                }
                if (waitOn == null) {
                    return null;
                }
                for (RowSet r : from) {
                    if (!r.atEnd() && r.readerWaitingSince == 0) {
                        r.readerWaitingSince = System.nanoTime();
                        marked.add(r);
                    }
                }
                Object o = waitOn.next(TAKE_ANY_WAIT_MILLIS, TimeUnit.MILLISECONDS, false);
                if (o != null) {
                    VectorSchemaRoot b = waitOn.received(o, into);
                    if (b != null) {
                        return new Taken(from.indexOf(waitOn), b);
                    }
                }
            }
        } finally {
            marked.forEach(r -> r.readerWaitingSince = 0);
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
