package io.kilnmark.kiln.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.graph.Toys.Collect;
import io.kilnmark.kiln.graph.Toys.Generate;
import io.kilnmark.kiln.graph.Toys.Pass;

/** Timing steps and telling a {@link RunListener} as a run goes. */
class RunListenerTest {

    private static PipelineGraph line(Step... steps) {
        List<GraphStep> gs = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        String[] ids = {"gen", "pass", "sink"};
        for (int i = 0; i < steps.length; i++) {
            gs.add(new GraphStep(ids[i], steps[i], null));
            if (i > 0) {
                edges.add(new Edge(PortRef.parse(ids[i - 1], "out"), PortRef.parse(ids[i], "in")));
            }
        }
        return new PipelineGraph("timing", gs, edges);
    }

    /** Keeps every call, as words, plus the snapshots. */
    private static final class Heard implements RunListener {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<RunProgress> snapshots = Collections.synchronizedList(new ArrayList<>());
        final List<String> notes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void stepStarted(String step, Instant at) {
            calls.add(step + " started");
        }

        @Override
        public void firstRowIn(String step, Instant at) {
            calls.add(step + " first in");
        }

        @Override
        public void firstRowOut(String step, Instant at) {
            calls.add(step + " first out");
        }

        @Override
        public void lastRowOut(String step, Instant at) {
            calls.add(step + " last out");
        }

        @Override
        public void stepEnded(String step, RunResult.Outcome outcome, Instant at) {
            calls.add(step + " ended " + outcome);
        }

        @Override
        public void progress(RunProgress progress) {
            snapshots.add(progress);
        }

        @Override
        public void note(Level level, String step, String text) {
            notes.add(level + " " + step + ": " + text);
        }

        List<String> of(String step) {
            synchronized (calls) {
                return calls.stream().filter(c -> c.startsWith(step + " ")).map(c -> c.substring(step.length() + 1))
                    .toList();
            }
        }
    }

    @Test
    @Timeout(30)
    void eachStepIsTimedInOrder() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30), Duration.ofMillis(50))
            .run(line(new Generate(20, 100), new Pass(2, -1), new Collect()), e -> { }, heard);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of("started", "first out", "last out", "ended FINISHED"), heard.of("gen"));
        assertEquals(List.of("started", "first in", "first out", "last out", "ended FINISHED"), heard.of("pass"));
        assertEquals(List.of("started", "first in", "ended FINISHED"), heard.of("sink"));
        for (RunResult.StepResult s : r.steps()) {
            assertNotNull(s.startedAt(), s.name());
            assertNotNull(s.firstRowAt(), s.name());
            assertNotNull(s.lastRowAt(), s.name());
            assertNotNull(s.finishedAt(), s.name());
            assertFalse(s.firstRowAt().isBefore(s.startedAt()), s.name());
            assertFalse(s.lastRowAt().isBefore(s.firstRowAt()), s.name());
            assertFalse(s.finishedAt().isBefore(s.lastRowAt()), s.name());
            assertFalse(s.startedAt().isBefore(r.startedAt()), s.name());
            assertFalse(s.finishedAt().isAfter(r.finishedAt()), s.name());
            assertNotNull(s.duration());
        }
        // the slow pass takes at least 20 x 2 ms
        assertTrue(r.step("pass").duration().toMillis() >= 40, r.step("pass").duration().toString());
        RunProgress end = heard.snapshots.getLast();
        assertEquals(2_000, end.step("sink").rowsIn());
        assertEquals(RunProgress.State.ENDED, end.step("gen").state());
        assertEquals(2, end.edges().size());
        assertEquals(2_000, end.edges().get(1).rows());
    }

    @Test
    @Timeout(30)
    void aStepThatCannotKeepUpIsTheSlowest() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 2, Duration.ofSeconds(30), Duration.ofMillis(40))
            .run(line(new Generate(60, 10), new Pass(15, -1), new Collect()), e -> { }, heard);
        assertTrue(r.succeeded(), r.error());
        List<String> slowest;
        synchronized (heard.snapshots) {
            slowest = heard.snapshots.stream().map(RunProgress::slowest).filter(s -> s != null).distinct().toList();
            assertTrue(heard.snapshots.stream().anyMatch(p -> p.edges().getFirst().full()), "the edge into pass filled");
            assertTrue(heard.snapshots.stream().anyMatch(p -> p.step("pass").rowsPerSecond() > 0));
        }
        assertEquals(List.of("pass"), slowest);
        assertNull(heard.snapshots.getLast().slowest(), "nothing holds back a run that has ended");
    }

    @Test
    @Timeout(30)
    void aFailureIsNoted() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30), Duration.ofMillis(50))
            .run(line(new Generate(20, 100), new Pass(0, 3), new Collect()), e -> { }, heard);
        assertFalse(r.succeeded());
        assertTrue(heard.notes.stream().anyMatch(n -> n.startsWith("ERROR pass: ") && n.contains("pass broke")),
            heard.notes.toString());
        assertTrue(heard.of("pass").contains("ended FAILED"), heard.calls.toString());
        assertTrue(heard.of("gen").getLast().startsWith("ended "), heard.calls.toString());
    }

    @Test
    @Timeout(30)
    void aListenerThatThrowsOrHangsNeverHoldsTheRunBack() throws Exception {
        RunListener throwing = new RunListener() {
            @Override
            public void stepStarted(String step, Instant at) {
                throw new IllegalStateException("listener broke");
            }

            @Override
            public void progress(RunProgress progress) {
                throw new OutOfMemoryError("not really");
            }
        };
        Collect sink = new Collect();
        RunResult r = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30), Duration.ofMillis(10))
            .run(line(new Generate(50, 100), new Pass(), sink), e -> { }, throwing);
        assertTrue(r.succeeded(), r.error());
        assertEquals(5_000, sink.rows.get());

        CountDownLatch never = new CountDownLatch(1);
        RunListener hanging = new RunListener() {
            @Override
            public void stepStarted(String step, Instant at) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Collect sink2 = new Collect();
        long t0 = System.nanoTime();
        RunResult r2 = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30), Duration.ofMillis(10))
            .run(line(new Generate(50, 100), new Pass(), sink2), e -> { }, hanging);
        assertTrue(r2.succeeded(), r2.error());
        assertEquals(5_000, sink2.rows.get());
        // the run waits for a hanging listener at most LISTENER_GRACE, then goes on
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).compareTo(KilnGraphRunner.LISTENER_GRACE.plusSeconds(5)) < 0);
    }

    @Test
    void withoutAListenerStepsAreStillTimed() throws Exception {
        RunResult r = new KilnGraphRunner(64L << 20, 4).run(line(new Generate(5, 10), new Pass(), new Collect()),
            e -> { });
        assertTrue(r.succeeded(), r.error());
        assertNotNull(r.step("pass").startedAt());
        assertNotNull(r.step("sink").lastRowAt());
    }
}
