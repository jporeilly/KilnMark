package io.kilnmark.spike.arrow;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded hand-over between two nodes. {@link #put} blocks when {@code capacity} batches are waiting:
 * that is the back-pressure. Ownership of a batch moves with it; the taker closes it.
 */
final class BatchPipe<T> {

    private static final Object END = new Object();

    private final ArrayBlockingQueue<Object> queue;
    private final AtomicInteger deepest = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final int consumers;

    BatchPipe(int capacity, int consumers) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.consumers = consumers;
    }

    void put(T batch) throws InterruptedException {
        queue.put(batch);
        deepest.accumulateAndGet(queue.size(), Math::max);
    }

    /** End of stream: one marker per consumer. */
    void finish() throws InterruptedException {
        for (int i = 0; i < consumers; i++) {
            queue.put(END);
        }
    }

    void fail(Throwable t) throws InterruptedException {
        failure.compareAndSet(null, t);
        finish();
    }

    /** The next batch, or null at the end of the stream. */
    @SuppressWarnings("unchecked")
    T take() throws InterruptedException {
        Object o = queue.take();
        if (o == END) {
            Throwable t = failure.get();
            if (t != null) {
                throw new IllegalStateException("producer failed", t);
            }
            return null;
        }
        return (T) o;
    }

    int deepest() {
        return deepest.get();
    }
}
