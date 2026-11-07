package io.kilnmark.server.runs;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * One watcher per process for the runs going on in it (docs/design/pipelines.md, "One run
 * lifecycle"). Every {@link #EVERY} one thread looks, in one query per kind whatever the number of
 * runs, at which of them someone has asked to stop, and reads in one query the rows the flows on this
 * process are watching; then it wakes the flows waiting for it ({@link #awaitTick}).
 *
 * <ul>
 *   <li><b>Cancel signals.</b> A running pipeline or flow run holds a {@link Signal} and reads it
 *       while it works, in memory. A cancel asked on this process sets it at once ({@link #cancel});
 *       one asked on another process is seen in the database at the next look. Runs no longer ask
 *       the database themselves.</li>
 *   <li><b>Watched rows.</b> A flow reads the pipeline runs its nodes started through a
 *       {@link Rows.Session}: one query for every flow on the process, not one per node and flow.</li>
 * </ul>
 *
 * <p>A look that fails (the database is out) is logged and tried again at the next; what was read
 * last stands meanwhile. Nothing is queried while nothing is watched.
 */
@ApplicationScoped
public class RunWatch {

    private static final Logger LOG = Logger.getLogger(RunWatch.class);

    /** How often the watcher looks: as often as a flow looked at its nodes, and a run for its cancel. */
    public static final Duration EVERY = Duration.ofMillis(500);

    /** Which of some runs of a kind someone has asked to stop. */
    @FunctionalInterface
    interface CancelReader {
        Set<Long> cancelledAmong(RunKind kind, Collection<Long> ids);
    }

    private final CancelReader reader;
    private final Duration every;
    /** The signals held, by kind's noun, then run id. */
    private final Map<String, Watched> watched = new ConcurrentHashMap<>();
    private final Set<Rows<?>> rows = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ticked = lock.newCondition();
    private long ticks;
    private Thread thread;
    private volatile boolean stopped;
    /** Whether the last look failed: logged once until one works again. */
    private boolean failing;

    @Inject
    public RunWatch(Db db) {
        this((kind, ids) -> RunTables.cancelledAmong(db, kind, ids), EVERY);
    }

    RunWatch(CancelReader reader, Duration every) {
        this.reader = reader;
        this.every = every;
    }

    /** The signals of one kind of run. */
    private record Watched(RunKind kind, Map<Long, Set<Signal>> byRun) {
    }

    /**
     * Whether one run has been asked to stop, read in memory. Closed when the run's work ends. A run
     * holding one is looked at by the watcher until then.
     */
    public final class Signal implements AutoCloseable {
        private final RunKind kind;
        private final long id;
        private volatile boolean cancelled;

        private Signal(RunKind kind, long id) {
            this.kind = kind;
            this.id = id;
        }

        /** Whether someone has asked the run to stop: never a database call. */
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void close() {
            Watched w = watched.get(kind.noun());
            if (w == null) {
                return;
            }
            w.byRun().computeIfPresent(id, (k, set) -> {
                set.remove(this);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /**
     * A signal for run {@code id} of {@code kind}, read once from the database now, so a cancel asked
     * before the run started its work is not missed, and by the watcher from then on.
     */
    public Signal watch(RunKind kind, long id) {
        Signal s = new Signal(kind, id);
        watched.computeIfAbsent(kind.noun(), k -> new Watched(kind, new ConcurrentHashMap<>())).byRun()
            .computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(s);
        try {
            if (reader.cancelledAmong(kind, List.of(id)).contains(id)) {
                s.cancelled = true;
            }
        } catch (RuntimeException e) {
            LOG.debugf("Could not read whether %s run %d is cancelled; the watcher's next look does", kind.noun(), id);
        }
        ensureRunning();
        return s;
    }

    /**
     * Tells the runs on this process that run {@code id} of {@code kind} was asked to stop: its
     * signals turn at once, and the flows waiting for the watcher look again now.
     */
    public void cancel(RunKind kind, long id) {
        Watched w = watched.get(kind.noun());
        Set<Signal> set = w == null ? null : w.byRun().get(id);
        if (set == null) {
            return;
        }
        set.forEach(s -> s.cancelled = true);
        wake();
    }

    /**
     * Rows that flows watch, read for all of them in one query a look ({@code loader}, given the ids
     * and giving those it found). A row {@code settled} (a run that has ended) is not read again.
     */
    public <R> Rows<R> rows(Function<Collection<Long>, Map<Long, R>> loader, Predicate<R> settled) {
        Rows<R> r = new Rows<>(loader, settled);
        rows.add(r);
        return r;
    }

    /** Rows of one kind that the flows on this process watch, each through a session of its own. */
    public static final class Rows<R> {
        private final Function<Collection<Long>, Map<Long, R>> loader;
        private final Predicate<R> settled;
        /** The rows as last read; empty when the row is gone. */
        private final Map<Long, Optional<R>> latest = new ConcurrentHashMap<>();
        private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

        private Rows(Function<Collection<Long>, Map<Long, R>> loader, Predicate<R> settled) {
            this.loader = loader;
            this.settled = settled;
        }

        /** A session for one flow run: the rows it asks for are watched until it closes. */
        public Session session() {
            Session s = new Session();
            sessions.add(s);
            return s;
        }

        /** Reads again, in one query, every row a session watches that has not settled. */
        void refresh() {
            Set<Long> all = new HashSet<>();
            sessions.forEach(s -> all.addAll(s.ids));
            latest.keySet().retainAll(all);
            Set<Long> read = new HashSet<>();
            for (Long id : all) {
                Optional<R> last = latest.get(id);
                if (last != null && last.isPresent() && !settled.test(last.get())) {
                    read.add(id);
                }
            }
            if (read.isEmpty()) {
                return;
            }
            Map<Long, R> found = loader.apply(read);
            for (Long id : read) {
                latest.put(id, Optional.ofNullable(found.get(id)));
            }
        }

        /** One flow run's view of the rows. */
        public final class Session implements AutoCloseable {
            private final Set<Long> ids = ConcurrentHashMap.newKeySet();

            private Session() {
            }

            /**
             * Row {@code id} as the watcher last read it; the first time it is asked for, read now with
             * {@code direct}, and watched from then on.
             */
            public Optional<R> get(long id, LongFunction<Optional<R>> direct) {
                Optional<R> last = latest.get(id);
                if (ids.add(id) || last == null) {
                    Optional<R> now = direct.apply(id);
                    latest.put(id, now);
                    return now;
                }
                return last;
            }

            @Override
            public void close() {
                sessions.remove(this);
            }
        }
    }

    /**
     * Waits until the watcher has next looked, or {@code most} has passed: a flow's pause between its
     * looks at its nodes, so that what it reads is fresh.
     */
    public void awaitTick(Duration most) throws InterruptedException {
        ensureRunning();
        lock.lock();
        try {
            long seen = ticks;
            long left = most.toNanos();
            while (ticks == seen && left > 0) {
                left = ticked.awaitNanos(left);
            }
        } finally {
            lock.unlock();
        }
    }

    private void wake() {
        lock.lock();
        try {
            ticks++;
            ticked.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** One look: the cancels asked of the runs watched, then the rows flows watch. */
    void tick() {
        for (Watched w : watched.values()) {
            Set<Long> open = new HashSet<>();
            w.byRun().forEach((id, set) -> {
                if (set.stream().anyMatch(s -> !s.cancelled)) {
                    open.add(id);
                }
            });
            if (open.isEmpty()) {
                continue;
            }
            for (Long id : reader.cancelledAmong(w.kind(), open)) {
                Set<Signal> set = w.byRun().get(id);
                if (set != null) {
                    set.forEach(s -> s.cancelled = true);
                }
            }
        }
        for (Rows<?> r : rows) {
            r.refresh();
        }
    }

    private synchronized void ensureRunning() {
        if (stopped || (thread != null && thread.isAlive())) {
            return;
        }
        thread = Thread.ofVirtual().name("kilnmark-run-watch").start(this::loop);
    }

    private void loop() {
        while (!stopped) {
            try {
                TimeUnit.NANOSECONDS.sleep(every.toNanos());
            } catch (InterruptedException e) {
                return;
            }
            try {
                tick();
                if (failing) {
                    LOG.info("The run watcher can read the database again");
                    failing = false;
                }
            } catch (RuntimeException e) {
                if (!failing) {
                    // a database's message, never a run's values
                    LOG.warnf("The run watcher could not look at the runs going on (%s); looking again shortly",
                        e.getClass().getSimpleName());
                    failing = true;
                }
            }
            wake();
        }
    }

    /** Stops the watcher's thread, when the server stops. */
    @PreDestroy
    synchronized void stop() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
