package io.kilnmark.server.runs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.pipelines.PipelineStore;

/**
 * The run watcher (efficiency review, 6 November 2026): a run reads whether it is asked to stop in
 * memory, never from the database; a cancel asked on this process turns it at once; one asked on
 * another is seen at the watcher's next look, which asks the database once a kind however many runs
 * are going; and the pipeline runs flows watch are read in one query for every flow.
 */
class RunWatchTest {

    /** A database that counts its queries and says which runs are asked to stop. */
    private static final class Database implements RunWatch.CancelReader {
        final AtomicInteger queries = new AtomicInteger();
        final List<Collection<Long>> asked = new ArrayList<>();
        final Set<Long> cancelled = new HashSet<>();

        @Override
        public synchronized Set<Long> cancelledAmong(RunKind kind, Collection<Long> ids) {
            queries.incrementAndGet();
            asked.add(List.copyOf(ids));
            Set<Long> out = new HashSet<>(ids);
            out.retainAll(cancelled);
            return out;
        }
    }

    @Test
    void aRunReadsItsCancelInMemoryAndACancelAskedHereTurnsItAtOnce() {
        Database database = new Database();
        RunWatch watch = new RunWatch(database, Duration.ofDays(1));
        try {
            RunWatch.Signal s = watch.watch(PipelineStore.KIND, 1);
            assertEquals(1, database.queries.get(), "read once as the run starts");
            for (int i = 0; i < 1000; i++) {
                assertFalse(s.cancelled());
            }
            watch.cancel(PipelineStore.KIND, 1);
            assertTrue(s.cancelled(), "a cancel asked on this process turns the signal at once");
            assertEquals(1, database.queries.get(), "reading the signal never asks the database");
            // a flow run with the same id is another run
            RunWatch.Signal flow = watch.watch(FlowStore.KIND, 1);
            assertFalse(flow.cancelled());
        } finally {
            watch.stop();
        }
    }

    @Test
    void manyRunsAreLookedAtInOneQueryALook() {
        Database database = new Database();
        RunWatch watch = new RunWatch(database, Duration.ofDays(1));
        try {
            List<RunWatch.Signal> signals = new ArrayList<>();
            for (long id = 1; id <= 50; id++) {
                signals.add(watch.watch(PipelineStore.KIND, id));
            }
            database.queries.set(0);
            database.asked.clear();
            database.cancelled.add(7L); // asked of another process, so seen only in the database
            watch.tick();
            watch.tick();
            watch.tick();
            assertEquals(3, database.queries.get(), "one query a look, not one a run");
            assertEquals(50, database.asked.get(0).size());
            assertTrue(signals.get(6).cancelled(), "a cancel asked elsewhere is seen at the next look");
            assertFalse(signals.get(7).cancelled());
            assertEquals(49, database.asked.get(1).size(), "a run already cancelled is not asked about again");

            signals.forEach(RunWatch.Signal::close);
            database.queries.set(0);
            watch.tick();
            assertEquals(0, database.queries.get(), "nothing watched, nothing asked");
        } finally {
            watch.stop();
        }
    }

    @Test
    void thePipelineRunsFlowsWatchAreReadInOneQueryForEveryFlow() {
        RunWatch watch = new RunWatch(new Database(), Duration.ofDays(1));
        try {
            Map<Long, String> table = new HashMap<>();
            AtomicInteger loads = new AtomicInteger();
            AtomicInteger direct = new AtomicInteger();
            RunWatch.Rows<String> rows = watch.rows(ids -> {
                loads.incrementAndGet();
                Map<Long, String> out = new HashMap<>();
                ids.forEach(id -> {
                    if (table.containsKey(id)) {
                        out.put(id, table.get(id));
                    }
                });
                return out;
            }, "succeeded"::equals);
            List<RunWatch.Rows<String>.Session> flows = new ArrayList<>();
            for (int f = 0; f < 10; f++) {
                RunWatch.Rows<String>.Session session = rows.session();
                flows.add(session);
                for (long id = f * 5L; id < f * 5L + 5; id++) {
                    table.put(id, "running");
                    final long run = id;
                    assertEquals(Optional.of("running"), session.get(run, x -> {
                        direct.incrementAndGet();
                        return Optional.ofNullable(table.get(x));
                    }));
                }
            }
            assertEquals(50, direct.get(), "each run is read once as a flow first asks for it");
            table.put(3L, "succeeded");
            table.remove(4L);
            watch.tick();
            assertEquals(1, loads.get(), "ten flows, fifty runs, one query");
            assertEquals(Optional.of("succeeded"), flows.get(0).get(3, x -> Optional.of("read again")));
            assertEquals(Optional.empty(), flows.get(0).get(4, x -> Optional.of("read again")), "a run gone reads so");
            assertEquals(50, direct.get(), "a flow reads what the watcher read");
            watch.tick();
            assertEquals(2, loads.get());
            flows.forEach(session -> session.close());
            watch.tick();
            assertEquals(2, loads.get(), "no flow watching, nothing read");
        } finally {
            watch.stop();
        }
    }

    @Test
    void aFlowWaitingForTheWatcherIsWokenByItsLook() throws Exception {
        RunWatch watch = new RunWatch(new Database(), Duration.ofMillis(20));
        try {
            long start = System.nanoTime();
            watch.awaitTick(Duration.ofSeconds(30));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0,
                "woken by the next look, not by the longest wait");
        } finally {
            watch.stop();
        }
    }

    @Test
    void aTryPastItsTimeoutStopsAndItsOwnersAreToldOnceAtWarnAfter() throws Exception {
        Database database = new Database();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2026-11-07T10:00:00Z"));
        RunWatch watch = new RunWatch(database, Duration.ofDays(1), now::get);
        try {
            AtomicInteger warned = new AtomicInteger();
            java.util.concurrent.CountDownLatch told = new java.util.concurrent.CountDownLatch(1);
            RunWatch.Signal s = watch.watch(PipelineStore.KIND, 7).limits(now.get(), Duration.ofHours(2),
                Duration.ofHours(1), () -> {
                    warned.incrementAndGet();
                    told.countDown();
                });
            now.set(now.get().plus(Duration.ofMinutes(59)));
            watch.tick();
            assertFalse(s.timedOut());
            assertEquals(0, warned.get(), "not yet");
            now.set(now.get().plus(Duration.ofMinutes(2)));
            watch.tick();
            watch.tick();
            assertTrue(told.await(5, java.util.concurrent.TimeUnit.SECONDS), "told past warn_after");
            assertFalse(s.timedOut(), "warn_after does not stop the run");
            assertFalse(s.cancelled());
            now.set(now.get().plus(Duration.ofHours(1)));
            watch.tick();
            assertTrue(s.timedOut(), "past its timeout the try is to stop");
            assertFalse(s.cancelled(), "a timeout is not a person's cancel: the try fails, and may be tried again");
            Thread.sleep(200);
            assertEquals(1, warned.get(), "told once");
            RunWatch.Signal free = watch.watch(FlowStore.KIND, 8).limits(now.get(), null, null, () -> { });
            now.set(now.get().plus(Duration.ofDays(30)));
            watch.tick();
            assertFalse(free.timedOut(), "no timeout: no limit");
        } finally {
            watch.stop();
        }
    }
}
