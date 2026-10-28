package io.kilnmark.kiln.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.graph.Toys.Collect;
import io.kilnmark.kiln.graph.Toys.Generate;
import io.kilnmark.kiln.graph.Toys.Merge;
import io.kilnmark.kiln.graph.Toys.Pass;
import io.kilnmark.kiln.graph.Toys.Sequential;
import io.kilnmark.kiln.graph.Toys.Slow;
import io.kilnmark.kiln.graph.Toys.Split;

class KilnGraphRunnerTest {

    /** Builds a graph: steps by id, edges as "from.port -> to.port" (ports optional). */
    private static final class G {
        final Map<String, Step> steps = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        final List<Edge> edges = new ArrayList<>();

        G step(String id, Step s) {
            steps.put(id, s);
            return this;
        }

        G edge(String from, String to) {
            edges.add(new Edge(PortRef.parse(from, "out"), PortRef.parse(to, "in")));
            return this;
        }

        PipelineGraph graph() {
            List<GraphStep> gs = new ArrayList<>();
            steps.forEach((id, s) -> gs.add(new GraphStep(id, s, configs.get(id))));
            return new PipelineGraph("test", gs, edges);
        }

        RunResult run(long memory, int capacity) throws InterruptedException {
            return new KilnGraphRunner(memory, capacity).run(graph(), e -> { });
        }

        RunResult run(long memory, int capacity, Duration stall) throws InterruptedException {
            return new KilnGraphRunner(memory, capacity, stall).run(graph(), e -> { });
        }

        RunResult run() throws InterruptedException {
            return run(64L << 20, 4);
        }
    }

    private static long sum(int n) {
        return (long) n * (n - 1) / 2;
    }

    @Test
    void aLineMovesEveryRowAndCountsEachEdgeAndPort() throws Exception {
        Collect sink = new Collect();
        RunResult r = new G().step("gen", new Generate(50, 100)).step("pass", new Pass()).step("sink", sink)
            .edge("gen", "pass").edge("pass", "sink").run();
        assertTrue(r.succeeded(), r.error());
        assertEquals(5_000, sink.rows.get());
        assertEquals(sum(5_000), sink.sum());
        assertEquals(5_000, r.step("gen").rowsOut());
        assertEquals(5_000, r.step("pass").rowsIn());
        assertEquals(Map.of("in", 5_000L), r.step("pass").rowsInByPort());
        assertEquals(Map.of("out", 5_000L), r.step("pass").rowsOutByPort());
        assertEquals(0, r.step("sink").rowsOut());
        assertEquals(2, r.edges().size());
        assertEquals(new RunResult.EdgeResult("gen", "out", "pass", "in", 5_000), r.edges().get(0));
        assertTrue(r.step("gen").peakMemoryBytes() > 0);
    }

    @Test
    void oneOutputFeedingSeveralStepsGivesEachItsOwnCopy() throws Exception {
        // One reader scribbles over each batch and frees it at once; the slow one reads afterwards.
        Collect scribbler = new Collect();
        scribbler.scribble = true;
        Collect slow = new Collect();
        slow.sleepMillis = 2;
        Collect third = new Collect();
        RunResult r = new G().step("gen", new Generate(40, 500)).step("a", scribbler).step("b", slow).step("c", third)
            .edge("gen", "a").edge("gen", "b").edge("gen", "c").run(64L << 20, 2);
        assertTrue(r.succeeded(), r.error());
        for (Collect c : List.of(scribbler, slow, third)) {
            assertEquals(20_000, c.rows.get());
            assertEquals(sum(20_000), c.sum(), "every reader saw the numbers untouched");
            assertFalse(c.foreignMemory.get(), "each reader's batches are in its own memory");
        }
        assertEquals(20_000, r.step("gen").rowsOut(), "the port is counted once");
        assertEquals(List.of(20_000L, 20_000L, 20_000L), r.edges().stream().map(RunResult.EdgeResult::rows).toList());
    }

    @Test
    void aCopyOwnsItsMemory() {
        try (BufferAllocator root = new RootAllocator(1 << 20);
             BufferAllocator a = root.newChildAllocator("a", 0, 1 << 20);
             BufferAllocator b = root.newChildAllocator("b", 0, 1 << 20)) {
            VectorSchemaRoot original = Toys.batch(a, 10, 100);
            VectorSchemaRoot copy = Batches.copy(original, b);
            ((IntVector) copy.getVector(0)).set(0, -5);
            assertEquals(10, ((IntVector) original.getVector(0)).get(0), "changing the copy leaves the original");
            original.close();
            assertEquals(0, a.getAllocatedMemory());
            assertEquals(100, copy.getRowCount());
            assertEquals(109, ((IntVector) copy.getVector(0)).get(99), "the copy outlives the original");
            assertTrue(b.getAllocatedMemory() > 0, "the copy is accounted to its own allocator");
            copy.close();
        }
    }

    @Test
    void aUnionReadsThreeInputsWithoutOneHoldingUpTheOthers() throws Exception {
        Collect sink = new Collect();
        RunResult r = new G()
            .step("a", new Generate(30, 100, 0, -1))
            .step("b", new Generate(30, 100, 3_000, -1))
            .step("c", new Generate(30, 100, 6_000, -1))
            .step("union", new Merge()).step("sink", sink)
            .edge("a", "union").edge("b", "union").edge("c", "union").edge("union", "sink").run(64L << 20, 1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(9_000, sink.rows.get());
        assertEquals(sum(9_000), sink.sum());
        assertEquals(Map.of("in", 9_000L), r.step("union").rowsInByPort());
        assertEquals(9_000, r.step("union").rowsIn());
    }

    @Test
    void manyInputsOnOnePortAreOfferedInOrder() throws Exception {
        AtomicReference<List<String>> names = new AtomicReference<>();
        AtomicReference<String> single = new AtomicReference<>();
        Step probe = new Step() {
            @Override
            public String type() {
                return "probe";
            }

            @Override
            public void init(StepContext context) {
                names.set(context.inputs("in").stream().map(RowSet::name).toList());
                try {
                    context.input("in");
                } catch (StepException e) {
                    single.set(e.getMessage());
                }
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    return RowSet.takeAny(context.inputs("in"), context.allocator()) instanceof RowSet.Taken t
                        && closeAndContinue(t);
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }

            private boolean closeAndContinue(RowSet.Taken t) {
                t.batch().close();
                return true;
            }
        };
        RunResult r = new G().step("x", new Generate(1, 1)).step("y", new Generate(1, 1)).step("p", probe)
            .edge("y", "p").edge("x", "p").run();
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of("y->p", "x->p"), names.get());
        assertTrue(single.get().contains("inputs(\"in\")"), single.get());
    }

    @Test
    void anUnconnectedOutputDropsItsRows() throws Exception {
        Collect evens = new Collect();
        RunResult r = new G().step("gen", new Generate(20, 100)).step("split", new Split()).step("evens", evens)
            .edge("gen", "split").edge("split.even", "evens").run();
        assertTrue(r.succeeded(), r.error());
        assertEquals(1_000, evens.rows.get());
        assertTrue(evens.values.stream().allMatch(v -> v % 2 == 0));
        assertEquals(Map.of("even", 1_000L), r.step("split").rowsOutByPort());
        assertEquals(1_000, r.step("split").rowsOut());
    }

    @Test
    void aFailureInTheMiddleOfADiamondStopsEverythingAndNamesWhereItStarted() throws Exception {
        Generate gen = new Generate(100_000, 100);
        Collect sink = new Collect();
        RunResult r = new G().step("gen", gen).step("left", new Pass()).step("right", new Pass(0, 5))
            .step("merge", new Merge()).step("sink", sink)
            .edge("gen", "left").edge("gen", "right").edge("left", "merge.a").edge("right", "merge.b")
            .edge("merge", "sink").run(64L << 20, 2);
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("right") && r.error().contains("pass broke"), r.error());
        for (String other : List.of("gen", "left", "merge", "sink")) {
            assertNull(r.step(other).error(), other + " only saw the failure");
        }
        assertTrue(gen.sent.get() < 100, "the source stopped soon after, not at its end: sent " + gen.sent.get());
        assertTrue(gen.closed.get() && sink.closed.get());
    }

    @Test
    void aFailureStopsStepsThatAreNotJoinedToIt() throws Exception {
        Generate endless = new Generate(Integer.MAX_VALUE, 10);
        Collect slow = new Collect();
        slow.sleepMillis = 5;
        Collect failing = new Collect();
        failing.failAfter = 3;
        RunResult r = new G().step("endless", endless).step("slow", slow)
            .step("gen", new Generate(100, 10)).step("failing", failing)
            .edge("endless", "slow").edge("gen", "failing").run();
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("failing") && r.error().contains("collect broke"), r.error());
        assertNull(r.step("endless").error());
        assertNull(r.step("slow").error());
        assertTrue(endless.closed.get() && slow.closed.get());
    }

    @Test
    void aStepThatStopsEarlyLetsTheOtherReadersFinish() throws Exception {
        Collect firstTwo = new Collect();
        firstTwo.limit = 2;
        Collect all = new Collect();
        RunResult r = new G().step("gen", new Generate(200, 10)).step("head", firstTwo).step("all", all)
            .edge("gen", "head").edge("gen", "all").run(64L << 20, 2);
        assertTrue(r.succeeded(), r.error());
        assertEquals(20, firstTwo.rows.get());
        assertEquals(2_000, all.rows.get());
    }

    @Test
    void aStepThatStopsEarlyStopsTheStepFeedingOnlyIt() throws Exception {
        Generate gen = new Generate(100_000, 10);
        Collect head = new Collect();
        head.limit = 2;
        RunResult r = new G().step("gen", gen).step("head", head).edge("gen", "head").run(64L << 20, 2);
        assertTrue(r.succeeded(), r.error());
        assertTrue(gen.sent.get() < 100, "sent " + gen.sent.get());
    }

    @Test
    void aStepWritingOnlyToAnUnconnectedPortEndsWhenItsReadersHaveFinished() throws Exception {
        // The first batch goes to "first", which a collector reads and then stops; every batch after
        // goes to "rest", which nothing reads. No write tells the step its reader has gone, yet it
        // must end early rather than read its whole input.
        Generate gen = new Generate(100_000, 10);
        Step firstOnly = new Step() {
            private boolean sent;

            @Override
            public String type() {
                return "first-only";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    VectorSchemaRoot b = context.input().take(context.allocator());
                    if (b == null) {
                        return false;
                    }
                    context.emit(sent ? "rest" : "first", b);
                    sent = true;
                    return true;
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        Collect head = new Collect();
        head.limit = 1;
        RunResult r = new G().step("gen", gen).step("split", firstOnly).step("head", head)
            .edge("gen", "split").edge("split.first", "head").run(64L << 20, 2);
        assertTrue(r.succeeded(), r.error());
        assertEquals(10, head.rows.get());
        assertEquals(RunResult.Outcome.ENDED_EARLY, r.step("split").outcome());
        assertTrue(gen.sent.get() < 1_000, "sent " + gen.sent.get());
    }

    @Test
    void aStoppedRunWaitsOnlySoLongForAStepThatIgnoresInterrupts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        Step stuck = new Step() {
            @Override
            public String type() {
                return "stuck";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) {
                inside.countDown();
                while (true) {
                    try {
                        release.await();
                        return false;
                    } catch (InterruptedException ignored) {
                        // an interrupt does not reach it
                    }
                }
            }
        };
        PipelineGraph g = new PipelineGraph("stuck", List.of(new GraphStep("s", stuck, Map.of())), List.of());
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(new KilnGraphRunner(64L << 20, 2, Duration.ofSeconds(30), Duration.ofMillis(300))
                    .run(g, e -> { }));
            } catch (InterruptedException e) {
                outcome.set(e);
            }
        });
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            long started = System.nanoTime();
            caller.interrupt();
            caller.join(Duration.ofSeconds(10));
            assertFalse(caller.isAlive(), "run returned though its step had not ended");
            assertInstanceOf(InterruptedException.class, outcome.get());
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aStepWithTwoOutputsGoesOnWhenOneReaderStopsEarly() throws Exception {
        Collect evens = new Collect();
        evens.limit = 1;
        Collect odds = new Collect();
        RunResult r = new G().step("gen", new Generate(100, 10)).step("split", new Split())
            .step("evens", evens).step("odds", odds)
            .edge("gen", "split").edge("split.even", "evens").edge("split.odd", "odds").run(64L << 20, 1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(5, evens.rows.get());
        assertEquals(500, odds.rows.get());
    }

    @Test
    void interruptingTheCallerStopsEveryStepBeforeRunReturns() throws Exception {
        CountDownLatch flowing = new CountDownLatch(1);
        Generate endless = new Generate(Integer.MAX_VALUE, 100);
        Collect a = new Collect();
        a.sleepMillis = 5;
        Collect b = new Collect() {
            @Override
            public boolean process(StepContext context) throws StepException {
                flowing.countDown();
                return super.process(context);
            }
        };
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(new G().step("endless", endless).step("a", a).step("b", b)
                    .edge("endless", "a").edge("endless", "b").run(64L << 20, 2));
            } catch (InterruptedException e) {
                outcome.set(e);
            }
        });
        assertTrue(flowing.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(Duration.ofSeconds(10));
        assertFalse(caller.isAlive(), "run returned after the interrupt");
        assertInstanceOf(InterruptedException.class, outcome.get());
        assertTrue(endless.closed.get() && a.closed.get() && b.closed.get(), "every step was closed before run returned");
    }

    @Test
    void aStepThatLeaksMemoryIsNamed() throws Exception {
        Collect leaky = new Collect();
        leaky.leak = true;
        RunResult r = new G().step("gen", new Generate(3, 10)).step("fine", new Collect()).step("leaky", leaky)
            .edge("gen", "fine").edge("gen", "leaky").run();
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("leaky") && r.error().contains("memory"), r.error());
        assertNull(r.step("fine").error());
    }

    @Test
    void aSlowReaderOnOneBranchHoldsBackTheSourceWithinTheMemoryLimit() throws Exception {
        // 400 batches of about 4 KB through a 160 KB limit with two readers: only possible if the source waits.
        Collect fast = new Collect();
        Collect slow = new Collect();
        slow.sleepMillis = 1;
        RunResult r = new G().step("gen", new Generate(400, 1_000)).step("fast", fast).step("slow", slow)
            .edge("gen", "fast").edge("gen", "slow").run(160L << 10, 2);
        assertTrue(r.succeeded(), r.error());
        assertEquals(400_000, fast.rows.get());
        assertEquals(400_000, slow.rows.get());
        assertTrue(r.step("gen").peakMemoryBytes() < 160L << 10);
    }

    @Test
    void settingsAreTheTopLevelValuesAsText() throws Exception {
        Collect sink = new Collect();
        G g = new G().step("gen", new Generate(1, 1)).step("sink", sink).edge("gen", "sink");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("limit", 5);
        config.put("name", "x");
        config.put("strict", true);
        config.put("ratio", 0.5);
        config.put("columns", List.of("a", "b"));
        config.put("map", Map.of("k", "v"));
        g.configs.put("sink", config);
        RunResult r = g.run();
        assertTrue(r.succeeded(), r.error());
        assertEquals(Map.of("limit", "5", "name", "x", "strict", "true", "ratio", "0.5"), sink.settings);
        assertEquals(config, sink.config);
        assertEquals(java.util.Set.of("in"), sink.inputPorts);
    }

    @Test
    void aGraphThatCannotRunIsRefused() {
        G g = new G().step("a", new Pass()).step("b", new Pass()).edge("a", "b").edge("b", "a");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, g::run);
        assertTrue(e.getMessage().contains("loop"), e.getMessage());
    }

    /** gen feeds p1 and p2; seq reads a (from p1) to its end before b (from p2), so p2 soon waits. */
    private static G stallShape(Pass p2) {
        return new G().step("gen", new Generate(100_000, 10)).step("p1", new Pass()).step("p2", p2)
            .step("seq", new Sequential())
            .edge("gen", "p1").edge("gen", "p2").edge("p1", "seq.a").edge("p2", "seq.b");
    }

    @Test
    @Timeout(30)
    void aFailureBreaksAStallInsteadOfWaitingBehindIt() throws Exception {
        long start = System.nanoTime();
        RunResult r = stallShape(new Pass(0, 1)).run(64L << 20, 1);
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("p2") && r.error().contains("pass broke"), r.error());
        for (String other : List.of("gen", "p1", "seq")) {
            assertNull(r.step(other).error(), other);
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "no waiting for the stall timeout");
    }

    @Test
    @Timeout(30)
    void aStallFailsTheRunNamingTheWaitingSteps() throws Exception {
        RunResult r = stallShape(new Pass()).run(64L << 20, 1, Duration.ofMillis(300));
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().startsWith("The run stalled: no rows moved for 300 milliseconds"), r.error());
        assertTrue(r.error().contains("seq (to read from p1.out->seq.a)"), r.error());
        assertTrue(r.error().contains("p2 (to write to p2.out->seq.b)"), r.error());
        r.steps().forEach(s -> assertNull(s.error(), s.name()));
    }

    @Test
    @Timeout(30)
    void aStepThatOnlyWaitsOnItsOwnWorkIsNotAStall() throws Exception {
        // Slow sleeps outside any row set for longer than the stall timeout; its reader waits on it.
        Slow slow = new Slow();
        Collect sink = new Collect();
        Thread caller = Thread.currentThread();
        Thread stopper = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1_000);
                caller.interrupt();
            } catch (InterruptedException e) {
                // the run ended first
            }
        });
        assertThrows(InterruptedException.class,
            () -> new G().step("slow", slow).step("sink", sink).edge("slow", "sink").run(64L << 20, 1, Duration.ofMillis(200)));
        stopper.interrupt();
        assertTrue(slow.closed.get());
        assertEquals(10, sink.rows.get());
    }

    @Test
    @Timeout(30)
    void aFailureInterruptsStepsBeforeItThatWaitOutsideARowSet() throws Exception {
        Slow slow = new Slow();
        Collect failing = new Collect();
        failing.failAfter = 1;
        long start = System.nanoTime();
        RunResult r = new G().step("slow", slow).step("failing", failing).edge("slow", "failing").run();
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("failing"), r.error());
        assertNull(r.step("slow").error());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "the slow step was interrupted");
    }

    @Test
    void whenEveryReaderHasFinishedTheStepsBeforeStopAndDroppedRowsAreNotCounted() throws Exception {
        Generate gen = new Generate(2_000, 10);
        Collect evens = new Collect();
        evens.limit = 1;
        Collect odds = new Collect();
        odds.limit = 1;
        RunResult r = new G().step("gen", gen).step("split", new Split()).step("evens", evens).step("odds", odds)
            .edge("gen", "split").edge("split.even", "evens").edge("split.odd", "odds").run(64L << 20, 1);
        assertTrue(r.succeeded(), r.error());
        assertTrue(gen.sent.get() < 100, "gen stopped early: sent " + gen.sent.get());
        assertEquals(5, r.edges().get(1).rows(), "split.even -> evens: the one batch evens took");
        assertEquals(5, r.edges().get(2).rows(), "split.odd -> odds: the one batch odds took");
        assertEquals(Map.of("in", 5L), r.step("evens").rowsInByPort());
    }

    @Test
    void runningOutOfMemoryNamesTheRunsLimit() throws Exception {
        RunResult r = new G().step("gen", new Generate(10, 100_000)).step("sink", new Collect()).edge("gen", "sink")
            .run(256L << 10, 2);
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Step gen (generate) ran out of memory: the run may hold 256 KB"), r.error());
    }

    @Test
    void inAndOutMeanThoseTwoPortsAndInputColumnsComeFromTheGraph() throws Exception {
        AtomicReference<List<Object>> seen = new AtomicReference<>();
        Step probe = new Step() {
            @Override
            public String type() {
                return "probe";
            }

            @Override
            public void init(StepContext context) {
                seen.set(List.of(context.hasInput(), context.hasOutput(), context.inputPorts(), context.outputPorts(),
                    String.valueOf(context.inputSchema("main")), String.valueOf(context.inputSchema("main", 0)),
                    String.valueOf(context.inputSchema("other"))));
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    RowSet.Taken t = RowSet.takeAny(context.inputs("main"), context.allocator());
                    if (t == null) {
                        return false;
                    }
                    context.emit("rejected", t.batch());
                    return true;
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        List<GraphStep> steps = List.of(new GraphStep("gen", new Generate(2, 5), Map.of()),
            new GraphStep("probe", probe, Map.of(), Map.of("main", Toys.N)),
            new GraphStep("sink", new Collect(), Map.of()));
        List<Edge> edges = List.of(new Edge(new PortRef("gen", "out"), new PortRef("probe", "main")),
            new Edge(new PortRef("probe", "rejected"), new PortRef("sink", "in")));
        RunResult r = new KilnGraphRunner(64L << 20, 2).run(new PipelineGraph("t", steps, edges), e -> { });
        assertTrue(r.succeeded(), r.error());
        Schema n = Toys.N;
        assertEquals(List.of(false, false, java.util.Set.of("main"), java.util.Set.of("rejected"), n.toString(),
            n.toString(), "null"), seen.get());
        assertEquals(10, r.step("sink").rowsIn());
    }

    @Test
    @Timeout(10)
    void failingARowSetNeverWaitsAndDropsWhatIsQueued() throws Exception {
        try (BufferAllocator root = new RootAllocator(1 << 20)) {
            RowSet rs = new RowSet("a->b", 1);
            rs.put(Toys.batch(root, 0, 10));
            rs.fail(new StepException("broke"));
            assertEquals(0, root.getAllocatedMemory(), "the queued batch was closed");
            assertThrows(RowSet.UpstreamFailedException.class, () -> rs.take(root));
            assertEquals(0, rs.rows(), "rows count what the reader was given");
        }
    }

    @Test
    @Timeout(10)
    void anInterruptedPutClosesItsBatch() throws Exception {
        try (BufferAllocator root = new RootAllocator(1 << 20)) {
            RowSet rs = new RowSet("a->b", 1);
            rs.put(Toys.batch(root, 0, 10));
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    rs.put(Toys.batch(root, 10, 10));
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            while (!rs.writerWaiting()) {
                Thread.onSpinWait();
            }
            writer.interrupt();
            writer.join();
            assertInstanceOf(InterruptedException.class, outcome.get());
            VectorSchemaRoot first = rs.take(root);
            assertEquals(10, rs.rows());
            first.close();
            assertEquals(0, root.getAllocatedMemory(), "the batch that never reached the queue was closed");
        }
    }

    @Test
    @Timeout(30)
    void aFailureWhileStartingStopsTheStepsAlreadyStarted() throws Exception {
        Generate endless = new Generate(Integer.MAX_VALUE, 10);
        Collect b = new Collect();
        b.sleepMillis = 5;
        Collect c = new Collect();
        KilnGraphRunner.beforeStart = name -> {
            if (name.endsWith("-c")) {
                throw new IllegalStateException("cannot start c");
            }
        };
        try {
            G g = new G().step("a", endless).step("b", b).step("c", c).edge("a", "b").edge("a", "c");
            IllegalStateException e = assertThrows(IllegalStateException.class, g::run);
            assertEquals("cannot start c", e.getMessage());
        } finally {
            KilnGraphRunner.beforeStart = null;
        }
        assertTrue(endless.closed.get() && b.closed.get(), "the steps already started were stopped before run threw");
        assertFalse(c.closed.get(), "c never started");
    }

    @Test
    @SuppressWarnings("serial")
    void aFailureBuildingAContextStartsNothing() {
        Generate gen = new Generate(10, 10);
        G g = new G().step("gen", gen).step("sink", new Collect()).edge("gen", "sink");
        g.configs.put("sink", Map.of("bad", new Number() {
            @Override
            public int intValue() {
                return 0;
            }

            @Override
            public long longValue() {
                return 0;
            }

            @Override
            public float floatValue() {
                return 0;
            }

            @Override
            public double doubleValue() {
                return 0;
            }

            @Override
            public String toString() {
                throw new LinkageError("no text");
            }
        }));
        assertThrows(LinkageError.class, g::run);
        assertEquals(0, gen.sent.get());
        assertFalse(gen.closed.get(), "no step started");
    }

    @Test
    void aHugeDecimalSettingStaysShortText() throws Exception {
        Collect sink = new Collect();
        G g = new G().step("gen", new Generate(1, 1)).step("sink", sink).edge("gen", "sink");
        g.configs.put("sink", Map.of("tiny", new java.math.BigDecimal("1e-999999999"), "small", new java.math.BigDecimal("2.50")));
        RunResult r = g.run();
        assertTrue(r.succeeded(), r.error());
        assertEquals("1E-999999999", sink.settings.get("tiny"));
        assertEquals("2.50", sink.settings.get("small"));
    }

    @Test
    @Timeout(30)
    void aReaderThatPollsAndThenWorksIsNotStalled() throws Exception {
        AtomicReference<Long> rows = new AtomicReference<>(0L);
        Step flushing = new Step() {
            boolean flushed;

            @Override
            public String type() {
                return "flushing";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    VectorSchemaRoot b = context.input().poll(context.allocator(), 20, TimeUnit.MILLISECONDS);
                    if (b == null) {
                        if (context.input().atEnd()) {
                            return false;
                        }
                        if (!flushed) { // a long commit, outside any row set
                            flushed = true;
                            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(900);
                            while (System.nanoTime() < until) {
                                Thread.onSpinWait();
                            }
                        }
                        return true;
                    }
                    rows.set(rows.get() + b.getRowCount());
                    b.close();
                    return true;
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        Step delayed = new Step() {
            int k;

            @Override
            public String type() {
                return "delayed";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    if (k == 0) {
                        Thread.sleep(100);
                    }
                    context.emit("out", Toys.batch(context.allocator(), k * 10, 10));
                    return ++k < 50;
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        RunResult r = new G().step("src", delayed).step("w", flushing).edge("src", "w")
            .run(64L << 20, 2, Duration.ofMillis(300));
        assertTrue(r.succeeded(), r.error());
        assertEquals(500L, rows.get());
    }

    @Test
    void eachStepSaysHowItEnded() throws Exception {
        Collect head = new Collect();
        head.limit = 2;
        RunResult early = new G().step("gen", new Generate(100_000, 10)).step("head", head).edge("gen", "head")
            .run(64L << 20, 2);
        assertEquals(RunResult.Outcome.ENDED_EARLY, early.step("head").outcome());
        assertEquals(RunResult.Outcome.ENDED_EARLY, early.step("gen").outcome());

        RunResult fine = new G().step("gen", new Generate(5, 10)).step("sink", new Collect()).edge("gen", "sink").run();
        assertEquals(RunResult.Outcome.FINISHED, fine.step("gen").outcome());
        assertEquals(RunResult.Outcome.FINISHED, fine.step("sink").outcome());

        RunResult failed = new G().step("gen", new Generate(100_000, 100)).step("left", new Pass())
            .step("right", new Pass(0, 5)).step("merge", new Merge()).step("sink", new Collect())
            .edge("gen", "left").edge("gen", "right").edge("left", "merge.a").edge("right", "merge.b")
            .edge("merge", "sink").run(64L << 20, 2);
        assertEquals(RunResult.Outcome.FAILED, failed.step("right").outcome());
        for (String other : List.of("gen", "left", "merge", "sink")) {
            assertEquals(RunResult.Outcome.STOPPED, failed.step(other).outcome(), other);
        }
    }

    @Test
    @Timeout(30)
    void aStepInterruptedWhileEndingItsOutputsSaysStopped() throws Exception {
        // split gets one batch and finishes; its odd reader sleeps before reading, so split waits to end
        // that output. Then its even reader fails, which interrupts split while it waits.
        Step failing = new Step() {
            @Override
            public String type() {
                return "failing";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    VectorSchemaRoot b = context.input().take(context.allocator());
                    if (b != null) {
                        b.close();
                    }
                    Thread.sleep(300);
                    throw new StepException("even broke");
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        Step sleepy = new Step() {
            @Override
            public String type() {
                return "sleepy";
            }

            @Override
            public void init(StepContext context) throws StepException {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                try {
                    VectorSchemaRoot b = context.input().take(context.allocator());
                    if (b == null) {
                        return false;
                    }
                    b.close();
                    return true;
                } catch (InterruptedException e) {
                    throw Toys.interrupted(e);
                }
            }
        };
        RunResult r = new G().step("gen", new Generate(1, 10)).step("split", new Split())
            .step("even", failing).step("odd", sleepy)
            .edge("gen", "split").edge("split.even", "even").edge("split.odd", "odd").run(64L << 20, 1);
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("even broke"), r.error());
        assertEquals(RunResult.Outcome.STOPPED, r.step("split").outcome(), "its odd output was failed, not ended");
        assertEquals(RunResult.Outcome.FINISHED, r.step("gen").outcome());
    }
}
