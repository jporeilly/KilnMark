package io.kilnmark.server.flows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.yaml.FlowDefinition;

/**
 * How a flow run's next try carries on from the try before (ADR 0053, decision 7), tested on the plan
 * itself: which nodes keep what they did, which run again, and what stops a run being tried again. A
 * takeover by a second worker process is not driven here; this is the logic it and every retry follow.
 */
class CarryOnTest {

    /** Pipelines by name: whether they write safely, and how their last run failed. */
    private static final class Pipelines implements CarryOn.Facts {
        final Map<String, String> unsafe = new HashMap<>();
        final Map<String, String> unchangeable = new HashMap<>();

        @Override
        public String unsafe(FlowDefinition.Node s) {
            return unsafe.get(s.pipeline());
        }

        @Override
        public String unchangeable(FlowDefinition.Node s) {
            return unchangeable.get(s.pipeline());
        }
    }

    private static FlowDefinition flow(String yaml) {
        FlowDefinition.Parsed p = FlowDefinition.parse(yaml);
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        return p.definition();
    }

    private static List<FlowStore.NodeRun> rows(FlowDefinition def, Map<String, String> statuses) {
        List<FlowStore.NodeRun> out = new ArrayList<>();
        // "sql-doubt": a sql node that failed with its commit in doubt, as PostgreSQL gives its row back (jsonb
        // as text, with a space after the colon)
        statuses.forEach((node, status) -> out.add(new FlowStore.NodeRun(node, def.node(node).type(), 0,
            status.equals("sql-doubt") ? "failed" : status, null, null, null, null,
            status.equals("sql-doubt") ? "{\"in_doubt\": true}" : "{}")));
        return out;
    }

    /** extract -> load -> publish, and a branch of its own: audit -> report. */
    private static final FlowDefinition BRANCHES = flow("""
        kilnmark: flow/v1
        name: branches
        nodes:
          - { id: extract, type: run-pipeline, pipeline: p-extract }
          - { id: load, type: run-pipeline, pipeline: p-load }
          - { id: publish, type: run-pipeline, pipeline: p-publish }
          - { id: audit, type: run-pipeline, pipeline: p-audit }
          - { id: report, type: run-pipeline, pipeline: p-report }
        connections:
          - { from: extract, to: load }
          - { from: load, to: publish }
          - { from: audit, to: report }
        """);

    @Test
    void succeededNodesKeepTheirResultsAndEachBranchCarriesOnFromItsOwnState() {
        CarryOn.Plan plan = CarryOn.plan(BRANCHES, rows(BRANCHES, Map.of("extract", "succeeded", "load", "failed",
            "publish", "skipped", "audit", "succeeded", "report", "succeeded")), new Pipelines(), false);
        assertNull(plan.blocker());
        assertEquals(Set.of("extract", "audit", "report"), plan.keep().keySet(),
            "the other branch finished: it is kept whole");
        assertEquals(Set.of("load"), plan.again(), "load ran and runs again; publish never ran, so it just runs");
    }

    @Test
    void aNodeAfterOneThatRunsAgainIsNotKeptAndMustBeSafeToRunAgain() {
        // load failed, but its failure was handled: alert ran on it, and fix ran after alert.
        FlowDefinition def = flow("""
            kilnmark: flow/v1
            name: handled
            nodes:
              - { id: load, type: run-pipeline, pipeline: p-load }
              - { id: alert, type: notify, title: Load failed }
              - { id: fix, type: run-pipeline, pipeline: p-fix }
              - { id: next, type: run-pipeline, pipeline: p-next }
            connections:
              - { from: load, to: alert, when: failure }
              - { from: alert, to: fix }
              - { from: fix, to: next }
            """);
        Map<String, String> statuses = Map.of("load", "failed", "alert", "succeeded", "fix", "succeeded",
            "next", "failed");
        CarryOn.Plan plan = CarryOn.plan(def, rows(def, statuses), new Pipelines(), false);
        assertNull(plan.blocker());
        assertTrue(plan.keep().isEmpty(), "load runs again, so what came after it is worked out again");
        assertEquals(Set.of("load", "alert", "fix", "next"), plan.again());

        Pipelines appends = new Pipelines();
        appends.unsafe.put("p-fix", "write-x appends");
        CarryOn.Plan unsafe = CarryOn.plan(def, rows(def, statuses), appends, false);
        assertEquals("node fix runs p-fix, and trying again could write twice: write-x appends", unsafe.blocker());
    }

    @Test
    void aHandledFailureThatCannotRunAgainIsKeptAsItFailed() {
        FlowDefinition def = flow("""
            kilnmark: flow/v1
            name: kept
            nodes:
              - { id: load, type: run-pipeline, pipeline: p-load, retries: 2 }
              - { id: alert, type: notify, title: Load failed }
              - { id: other, type: run-pipeline, pipeline: p-other }
            connections:
              - { from: load, to: alert, when: failure }
            """);
        CarryOn.Plan plan = CarryOn.plan(def, rows(def, Map.of("load", "failed", "alert", "succeeded",
            "other", "failed")), new Pipelines(), false);
        assertNull(plan.blocker());
        assertEquals(Set.of("load", "alert"), plan.keep().keySet(),
            "load used its own retries: it is kept as it failed, and what handled it stands");
        assertEquals(Set.of("other"), plan.again());
    }

    @Test
    void nodeRetriesTakeThePlaceOfTheRunsSoTriesNeverMultiply() {
        FlowDefinition def = flow("""
            kilnmark: flow/v1
            name: own
            nodes:
              - { id: load, type: run-pipeline, pipeline: p-load, retries: 2 }
            """);
        List<FlowStore.NodeRun> failed = rows(def, Map.of("load", "failed"));
        CarryOn.Plan plan = CarryOn.plan(def, failed, new Pipelines(), false);
        assertTrue(plan.blocker().contains("has retries of its own"), plan.blocker());
        assertNull(CarryOn.plan(def, failed, new Pipelines(), true).blocker(),
            "a person's Run again from the failure runs it again, with its own tries");
    }

    @Test
    void aSqlNodeRunsAgainOnlyAfterARollback() {
        FlowDefinition def = flow("""
            kilnmark: flow/v1
            name: sql
            nodes:
              - { id: before, type: run-pipeline, pipeline: p-before }
              - id: tidy
                type: sql
                source: warehouse
                statements: ["delete from stage.t"]
            connections:
              - { from: before, to: tidy }
            """);
        assertNull(CarryOn.plan(def, rows(def, Map.of("before", "succeeded", "tidy", "failed")), new Pipelines(),
            false).blocker(), "a failed sql node rolled back: it may run again");
        CarryOn.Plan doubt = CarryOn.plan(def, rows(def, Map.of("before", "succeeded", "tidy", "sql-doubt")),
            new Pipelines(), true);
        assertTrue(doubt.blocker().contains("may have committed"), doubt.blocker());
        CarryOn.Plan committed = CarryOn.plan(def, rows(def, Map.of("before", "failed", "tidy", "succeeded")),
            new Pipelines(), true);
        assertTrue(committed.blocker().contains("committed its changes"), committed.blocker());
        CarryOn.Plan running = CarryOn.plan(def, rows(def, Map.of("before", "succeeded", "tidy", "running")),
            new Pipelines(), true);
        assertTrue(running.blocker().contains("may have committed"), "a sql node left running is in doubt too");
    }

    @Test
    void aGateOrARefusalIsNotTriedAgainByItselfButAPersonMayRunItAgain() {
        Pipelines gate = new Pipelines();
        gate.unchangeable.put("p-load", "ran p-load, which a quality gate stopped, and would stop again");
        Map<String, String> statuses = Map.of("extract", "succeeded", "load", "failed", "publish", "skipped",
            "audit", "succeeded", "report", "succeeded");
        assertTrue(CarryOn.plan(BRANCHES, rows(BRANCHES, statuses), gate, false).blocker().contains("quality gate"));
        assertNull(CarryOn.plan(BRANCHES, rows(BRANCHES, statuses), gate, true).blocker());
    }

    @Test
    void aFlowAStopNodeEndedIsNotRunAgain() {
        FlowDefinition def = flow("""
            kilnmark: flow/v1
            name: stopped
            nodes:
              - { id: check, type: run-pipeline, pipeline: p-check }
              - { id: halt, type: stop, status: failed, message: Too many set aside }
            connections:
              - { from: check, to: halt }
            """);
        CarryOn.Plan plan = CarryOn.plan(def, rows(def, Map.of("check", "succeeded", "halt", "succeeded")),
            new Pipelines(), true);
        assertEquals("the stop node halt ended it", plan.blocker());
    }
}
