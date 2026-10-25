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
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.graph.Toys.Collect;
import io.kilnmark.kiln.graph.Toys.Generate;
import io.kilnmark.kiln.graph.Toys.Merge;
import io.kilnmark.kiln.graph.Toys.Pass;
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
}
