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
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.graph.Toys.Collect;
import io.kilnmark.kiln.graph.Toys.Generate;
import io.kilnmark.kiln.graph.Toys.Pass;

/** Timing nodes and telling a {@link RunListener} as a run goes. */
class RunListenerTest {

    private static PipelineGraph line(Node... nodes) {
        List<GraphNode> gs = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        String[] ids = {"gen", "pass", "sink"};
        for (int i = 0; i < nodes.length; i++) {
            gs.add(new GraphNode(ids[i], nodes[i], null));
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
        public void nodeStarted(String node, Instant at) {
            calls.add(node + " started");
        }

        @Override
        public void firstRowIn(String node, Instant at) {
            calls.add(node + " first in");
        }

        @Override
        public void firstRowOut(String node, Instant at) {
            calls.add(node + " first out");
        }

        @Override
        public void lastRowOut(String node, Instant at) {
            calls.add(node + " last out");
        }

        @Override
        public void nodeEnded(String node, RunResult.Outcome outcome, Instant at) {
            calls.add(node + " ended " + outcome);
        }

        @Override
        public void progress(RunProgress progress) {
            snapshots.add(progress);
        }

        @Override
        public void note(Level level, String node, String text, Instant at) {
            assertNotNull(at);
            notes.add(level + " " + node + ": " + text);
        }

        List<String> of(String node) {
            synchronized (calls) {
                return calls.stream().filter(c -> c.startsWith(node + " ")).map(c -> c.substring(node.length() + 1))
                    .toList();
            }
        }
    }

    @Test
    @Timeout(30)
    void eachNodeIsTimedInOrder() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30), Duration.ofMillis(50))
            .run(line(new Generate(20, 100), new Pass(2, -1), new Collect()), e -> { }, heard);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of("started", "first out", "last out", "ended FINISHED"), heard.of("gen"));
        assertEquals(List.of("started", "first in", "first out", "last out", "ended FINISHED"), heard.of("pass"));
        assertEquals(List.of("started", "first in", "ended FINISHED"), heard.of("sink"));
        for (RunResult.NodeResult s : r.nodes()) {
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
        assertTrue(r.node("pass").duration().toMillis() >= 40, r.node("pass").duration().toString());
        RunProgress end = heard.snapshots.getLast();
        assertEquals(2_000, end.node("sink").rowsIn());
        assertEquals(RunProgress.State.ENDED, end.node("gen").state());
        assertEquals(2, end.edges().size());
        assertEquals(2_000, end.edges().get(1).rows());
    }

    @Test
    @Timeout(30)
    void aNodeThatCannotKeepUpIsTheSlowest() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 2, Duration.ofSeconds(30), Duration.ofMillis(40))
            .run(line(new Generate(60, 10), new Pass(15, -1), new Collect()), e -> { }, heard);
        assertTrue(r.succeeded(), r.error());
        List<String> slowest;
        synchronized (heard.snapshots) {
            slowest = heard.snapshots.stream().map(RunProgress::slowest).filter(s -> s != null).distinct().toList();
            assertTrue(heard.snapshots.stream().anyMatch(p -> p.edges().getFirst().full()), "the edge into pass filled");
            assertTrue(heard.snapshots.stream().anyMatch(p -> p.node("pass").rowsPerSecond() > 0));
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
            public void nodeStarted(String node, Instant at) {
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
            public void nodeStarted(String node, Instant at) {
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
    @Timeout(30)
    void eachNodesTimeIsSplitIntoWorkingWaitingAndHeldUp() throws Exception {
        Heard heard = new Heard();
        RunResult r = new KilnGraphRunner(64L << 20, 2, Duration.ofSeconds(30), Duration.ofMillis(40))
            .run(line(new Generate(40, 10), new Pass(15, -1), new Collect()), e -> { }, heard);
        assertTrue(r.succeeded(), r.error());
        RunResult.NodeResult gen = r.node("gen");
        RunResult.NodeResult pass = r.node("pass");
        RunResult.NodeResult sink = r.node("sink");
        long passMillis = pass.duration().toMillis();
        // The source is held up by the slow node; the sink waits for its rows; the slow node works.
        assertTrue(gen.heldUpNanos() > gen.duration().toNanos() / 2, gen.toString());
        assertEquals(0, gen.waitingForRowsNanos());
        assertTrue(sink.waitingForRowsNanos() > sink.duration().toNanos() / 2, sink.toString());
        assertEquals(0, sink.heldUpNanos());
        assertTrue(pass.working().toMillis() > passMillis / 2, pass + " " + passMillis);
        assertTrue(heard.snapshots.stream().anyMatch(p -> p.node("gen").heldUpNanos() > 0
            && p.node("gen").outputFill() > 0.5), "running totals and queue fill in the snapshots");
        assertTrue(heard.snapshots.stream().anyMatch(p -> p.edges().getFirst().fill() == 1.0));
    }

    @Test
    void withoutAListenerNodesAreStillTimed() throws Exception {
        RunResult r = new KilnGraphRunner(64L << 20, 4).run(line(new Generate(5, 10), new Pass(), new Collect()),
            e -> { });
        assertTrue(r.succeeded(), r.error());
        assertNotNull(r.node("pass").startedAt());
        assertNotNull(r.node("sink").lastRowAt());
    }
}
