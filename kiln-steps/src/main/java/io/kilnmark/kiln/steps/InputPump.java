package io.kilnmark.kiln.steps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.StepException;

/**
 * Reads several input row sets at once, each on its own virtual thread, into one queue.
 *
 * <p>A step with more than one input cannot simply read one input and then the next: when two
 * inputs come from the same step upstream (a "diamond", such as a route whose branches meet again
 * in a union, or an aggregate looked up against its own detail rows), the upstream step waits for
 * room on the input not being read, and nothing moves. Reading every input as batches arrive keeps
 * them all flowing. Union reads its inputs this way; lookup reads its main input this way while it
 * loads the lookup side, buffering main batches until it is ready for them.
 *
 * <p>Batches are moved into the step's allocator as they are read, so buffered batches count
 * against the step's memory. {@link #close} stops the threads and closes anything still queued.
 */
final class InputPump implements AutoCloseable {

    /** A batch from input {@code source}, or the end of that input when {@code batch} is null. */
    record Item(int source, VectorSchemaRoot batch) {
    }

    private record Failure(int source, StepException error) {
    }

    private final List<RowSet> sources;
    private final BufferAllocator into;
    private final String name;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final List<Thread> threads = new ArrayList<>();
    private int capacity;
    private int queuedBatches;
    private boolean closed;

    /**
     * @param capacity batches queued before the readers wait; {@link Integer#MAX_VALUE} for no limit
     */
    InputPump(List<RowSet> sources, BufferAllocator into, int capacity, String name) {
        this.sources = List.copyOf(sources);
        this.into = into;
        this.capacity = capacity;
        this.name = name;
    }

    void start() {
        for (int i = 0; i < sources.size(); i++) {
            int source = i;
            threads.add(Thread.ofVirtual().name("kiln-" + name + "-in" + i).start(() -> read(source)));
        }
    }

    /** Changes how many batches may wait in the queue. */
    synchronized void capacity(int capacity) {
        this.capacity = capacity;
        notifyAll();
    }

    private void read(int source) {
        RowSet rs = sources.get(source);
        while (true) {
            VectorSchemaRoot batch = null;
            try {
                batch = rs.take(into);
                synchronized (this) {
                    while (!closed && batch != null && queuedBatches >= capacity) {
                        wait();
                    }
                    if (closed) {
                        return; // the finally block closes the batch
                    }
                    queue.add(new Item(source, batch));
                    if (batch != null) {
                        queuedBatches++;
                    }
                    notifyAll();
                }
                if (batch == null) {
                    return;
                }
                batch = null; // queued: no longer ours to close
            } catch (InterruptedException e) {
                return;
            } catch (StepException e) {
                synchronized (this) {
                    queue.add(new Failure(source, e));
                    notifyAll();
                }
                return;
            } catch (RuntimeException e) {
                synchronized (this) {
                    queue.add(new Failure(source, new StepException("Reading input " + (source + 1) + " failed: "
                        + e.getMessage(), e)));
                    notifyAll();
                }
                return;
            } finally {
                if (batch != null) {
                    batch.close();
                }
            }
        }
    }

    /**
     * The next batch or end of an input, waiting for one. A failure upstream is thrown as it was
     * reported, so the runner can tell it apart from this step's own failure.
     */
    synchronized Item next() throws StepException, InterruptedException {
        while (true) {
            Object o = queue.poll();
            if (o instanceof Item item) {
                if (item.batch() != null) {
                    queuedBatches--;
                    notifyAll();
                }
                return item;
            }
            if (o instanceof Failure f) {
                throw f.error();
            }
            wait();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        threads.forEach(Thread::interrupt);
        boolean interrupted = false;
        for (Thread t : threads) {
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        synchronized (this) {
            for (Object o : queue) {
                if (o instanceof Item item && item.batch() != null) {
                    item.batch().close();
                }
            }
            queue.clear();
            queuedBatches = 0;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
