package io.kilnmark.kiln.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.flow.FlowEngine.Kind;
import io.kilnmark.kiln.flow.FlowEngine.Path;
import io.kilnmark.kiln.flow.FlowEngine.State;

class FlowEngineTest {

    private static Path p(String from, String to, Kind kind) {
        return new Path(from, to, kind, null);
    }

    private static Path below(String from, String to, double limit) {
        return new Path(from, to, Kind.CONDITION,
            v -> v.apply(from + ".share") instanceof Number n ? n.doubleValue() < limit : null);
    }

    private static Path atLeast(String from, String to, double limit) {
        return new Path(from, to, Kind.CONDITION,
            v -> v.apply(from + ".share") instanceof Number n ? n.doubleValue() >= limit : null);
    }

    @Test
    void successPathsRunInOrder() {
        FlowEngine e = new FlowEngine(List.of("a", "b", "c"),
            List.of(p("a", "b", Kind.SUCCESS), p("b", "c", Kind.SUCCESS)), Map.of());
        assertEquals(List.of("a"), e.ready());
        assertTrue(e.ready().isEmpty(), "a is running; nothing else may start");
        e.finish("a", State.SUCCEEDED, Map.of());
        assertEquals(List.of("b"), e.ready());
        e.finish("b", State.SUCCEEDED, Map.of());
        assertEquals(List.of("c"), e.ready());
        e.finish("c", State.SUCCEEDED, Map.of());
        assertTrue(e.done());
        assertEquals(FlowEngine.Outcome.SUCCEEDED, e.outcome());
    }

    @Test
    void stepsWithNothingBeforeThemStartTogether() {
        FlowEngine e = new FlowEngine(List.of("a", "b", "c"),
            List.of(p("a", "c", Kind.SUCCESS), p("b", "c", Kind.SUCCESS)), Map.of());
        assertEquals(List.of("a", "b"), e.ready());
        e.finish("a", State.SUCCEEDED, Map.of());
        assertTrue(e.ready().isEmpty(), "c waits for b's path to settle");
        e.finish("b", State.SUCCEEDED, Map.of());
        assertEquals(List.of("c"), e.ready());
    }

    @Test
    void aFailureWithNoPathFailsTheFlowAndSkipsWhatFollows() {
        FlowEngine e = new FlowEngine(List.of("a", "b", "c"),
            List.of(p("a", "b", Kind.SUCCESS), p("b", "c", Kind.SUCCESS)), Map.of());
        e.ready();
        e.finish("a", State.FAILED, Map.of());
        assertTrue(e.ready().isEmpty());
        assertEquals(State.SKIPPED, e.state("b"));
        assertEquals(State.SKIPPED, e.state("c"), "a skipped step settles its paths as not taken");
        assertTrue(e.done());
        assertEquals(FlowEngine.Outcome.FAILED, e.outcome());
    }

    @Test
    void aFailurePathHandlesTheFailure() {
        FlowEngine e = new FlowEngine(List.of("a", "b", "tell"),
            List.of(p("a", "b", Kind.SUCCESS), p("a", "tell", Kind.FAILURE)), Map.of());
        e.ready();
        e.finish("a", State.FAILED, Map.of());
        assertEquals(List.of("tell"), e.ready());
        e.finish("tell", State.SUCCEEDED, Map.of());
        assertEquals(State.SKIPPED, e.state("b"));
        assertEquals(FlowEngine.Outcome.SUCCEEDED, e.outcome(), "the failure was dealt with");
    }

    @Test
    void alwaysRunsAfterEitherEnding() {
        for (State end : List.of(State.SUCCEEDED, State.FAILED)) {
            FlowEngine e = new FlowEngine(List.of("a", "z"), List.of(p("a", "z", Kind.ALWAYS)), Map.of());
            e.ready();
            e.finish("a", end, Map.of());
            assertEquals(List.of("z"), e.ready(), end.name());
        }
    }

    @Test
    void aConditionNeedsSuccessAndATrueAnswer() {
        List<String> steps = List.of("clean", "spend", "tell");
        List<Path> paths = List.of(below("clean", "spend", 0.02), p("clean", "tell", Kind.FAILURE),
            atLeast("clean", "tell", 0.02));

        FlowEngine low = new FlowEngine(steps, paths, Map.of());
        low.ready();
        low.finish("clean", State.SUCCEEDED, Map.of("share", 0.01));
        assertEquals(List.of("spend"), low.ready());
        assertEquals(State.SKIPPED, low.state("tell"));

        FlowEngine high = new FlowEngine(steps, paths, Map.of());
        high.ready();
        high.finish("clean", State.SUCCEEDED, Map.of("share", 0.05));
        assertEquals(List.of("tell"), high.ready());
        assertEquals(State.SKIPPED, high.state("spend"));

        FlowEngine unknown = new FlowEngine(steps, paths, Map.of());
        unknown.ready();
        unknown.finish("clean", State.SUCCEEDED, Map.of());
        assertTrue(unknown.ready().isEmpty(), "an unknown answer takes neither path");
        assertEquals(State.SKIPPED, unknown.state("spend"));
        assertEquals(State.SKIPPED, unknown.state("tell"));

        FlowEngine failed = new FlowEngine(steps, paths, Map.of());
        failed.ready();
        failed.finish("clean", State.FAILED, Map.of("share", 0.0));
        assertEquals(List.of("tell"), failed.ready(), "only the failure path is taken, never a condition");
    }

    @Test
    void conditionsReadParametersAndStatus() {
        Path p = new Path("a", "b", Kind.CONDITION,
            v -> "EMEA".equals(v.apply("region")) && "succeeded".equals(v.apply("a.status")));
        FlowEngine e = new FlowEngine(List.of("a", "b"), List.of(p), Map.of("region", "EMEA"));
        e.ready();
        e.finish("a", State.SUCCEEDED, Map.of());
        assertEquals(List.of("b"), e.ready());
    }

    @Test
    void haltEndsTheFlowAndSkipsWhatHasNotStarted() {
        FlowEngine e = new FlowEngine(List.of("a", "stop", "b"),
            List.of(p("a", "stop", Kind.SUCCESS), p("stop", "b", Kind.SUCCESS)), Map.of());
        e.ready();
        e.finish("a", State.SUCCEEDED, Map.of());
        assertEquals(List.of("stop"), e.ready());
        e.halt(true, "Too many set aside");
        e.finish("stop", State.SUCCEEDED, Map.of());
        assertTrue(e.ready().isEmpty());
        assertEquals(State.SKIPPED, e.state("b"));
        assertTrue(e.halted());
        assertEquals("Too many set aside", e.haltMessage());
        assertEquals(FlowEngine.Outcome.FAILED, e.outcome());
    }

    @Test
    void aHaltThatSucceedsLeavesTheFlowSucceeded() {
        FlowEngine e = new FlowEngine(List.of("a", "stop"), List.of(p("a", "stop", Kind.SUCCESS)), Map.of());
        e.ready();
        e.finish("a", State.SUCCEEDED, Map.of());
        e.ready();
        e.halt(false, "Nothing to do");
        e.finish("stop", State.SUCCEEDED, Map.of());
        assertEquals(FlowEngine.Outcome.SUCCEEDED, e.outcome());
    }

    @Test
    void cancelSkipsWhatIsPendingAndEndsCancelled() {
        FlowEngine e = new FlowEngine(List.of("a", "b"), List.of(p("a", "b", Kind.SUCCESS)), Map.of());
        e.ready();
        e.cancel();
        assertEquals(State.SKIPPED, e.state("b"));
        assertFalse(e.done(), "a is still running");
        e.finish("a", State.CANCELLED, Map.of());
        assertTrue(e.done());
        assertEquals(FlowEngine.Outcome.CANCELLED, e.outcome());
    }

    @Test
    void aStepCancelledByHandCancelsTheFlowUnlessAStopStepDecided() {
        FlowEngine byHand = new FlowEngine(List.of("a", "b"), List.of(p("a", "b", Kind.SUCCESS)), Map.of());
        byHand.ready();
        byHand.finish("a", State.CANCELLED, Map.of());
        byHand.ready();
        assertEquals(State.SKIPPED, byHand.state("b"));
        assertEquals(FlowEngine.Outcome.CANCELLED, byHand.outcome());

        // The steps a stop step cancels do not make the flow cancelled: the stop decides.
        FlowEngine stopped = new FlowEngine(List.of("slow", "stop"), List.of(), Map.of());
        stopped.ready();
        stopped.halt(true, "Enough");
        stopped.finish("stop", State.SUCCEEDED, Map.of());
        stopped.finish("slow", State.CANCELLED, Map.of());
        assertEquals(FlowEngine.Outcome.FAILED, stopped.outcome());

        FlowEngine explicit = new FlowEngine(List.of("slow", "stop"), List.of(), Map.of());
        explicit.ready();
        explicit.halt(true, "Enough");
        explicit.cancel();
        explicit.finish("stop", State.SUCCEEDED, Map.of());
        explicit.finish("slow", State.CANCELLED, Map.of());
        assertEquals(FlowEngine.Outcome.CANCELLED, explicit.outcome(), "a person's cancel outranks a stop step");
    }

    @Test
    void nullsAreLeftOutOfWhatAStepReports() {
        FlowEngine e = new FlowEngine(List.of("a"), List.of(), Map.of());
        e.ready();
        java.util.HashMap<String, Object> reported = new java.util.HashMap<>();
        reported.put("score", null);
        reported.put("rows_written", 5.0);
        e.finish("a", State.SUCCEEDED, reported);
        assertEquals(Map.of("rows_written", 5.0), e.results("a"));
        assertEquals(null, e.value("a.score"));
        assertEquals("succeeded", e.value("a.status"));
        assertEquals(null, e.value("b.status"));
    }

    @Test
    void refusesWhatCannotHappen() {
        FlowEngine e = new FlowEngine(List.of("a", "b"), List.of(p("a", "b", Kind.SUCCESS)), Map.of());
        assertThrows(IllegalStateException.class, () -> e.finish("a", State.SUCCEEDED, Map.of()), "not started");
        e.ready();
        assertThrows(IllegalStateException.class, e::outcome);
        assertThrows(IllegalArgumentException.class, () -> e.finish("a", State.SKIPPED, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> e.state("nope"));
        assertThrows(IllegalArgumentException.class,
            () -> new FlowEngine(List.of("a"), List.of(p("a", "x", Kind.SUCCESS)), Map.of()));
    }
}
