package io.kilnmark.server.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class EvalMetricsTest {

    @Test
    void recallAndReciprocalRank() {
        List<String> ranked = List.of("a", "b", "c", "d");
        assertEquals(0.5, EvalMetrics.recallAt(ranked, List.of("b", "z"), 3));
        assertEquals(0.0, EvalMetrics.recallAt(ranked, List.of("d"), 3));
        assertEquals(1.0, EvalMetrics.recallAt(List.of(), List.of(), 5));
        assertEquals(0.5, EvalMetrics.reciprocalRank(ranked, List.of("b", "d")));
        assertEquals(0.0, EvalMetrics.reciprocalRank(ranked, List.of("z")));
    }

    @Test
    void planListsMatchIgnoringCaseAndOrder() {
        assertEquals(1.0, EvalMetrics.sameSet(List.of("Table", "view"), List.of("view", "table")));
        assertEquals(0.0, EvalMetrics.sameSet(List.of("table"), List.of()));
        assertEquals(0.5, EvalMetrics.mean(List.of(1.0, 0.0)));
        assertEquals(0.0, EvalMetrics.mean(List.of()));
    }
}
