package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.flow.FlowEngine;

class FlowDefinitionTest {

    static final String GOOD = """
        kilnmark: flow/v1
        name: nightly-customers
        description: Clean customers, then publish spend only if quality holds.
        queue_timeout: 45m
        parameters:
          region: { type: text, default: EMEA }
          limit_share: { type: number, default: 0.02 }
        steps:
          - id: clean
            type: run-pipeline
            pipeline: demo-customers-clean
          - id: spend
            type: run-pipeline
            pipeline: demo-customer-spend
            version: 3
          - id: halt
            type: stop
            status: failed
            message: Too many customers were set aside
        paths:
          - { from: clean, to: spend, when: "clean.quarantined_share < limit_share and clean.score >= good" }
          - { from: clean, to: halt, when: "clean.quarantined_share >= limit_share" }
          - { from: clean, to: halt, when: failure }
        layout:
          clean: {x: 1, y: 2}
        """;

    private static String firstProblem(String yaml) {
        FlowDefinition.Parsed p = FlowDefinition.parse(yaml);
        assertTrue(!p.problems().isEmpty(), "expected a problem in:\n" + yaml);
        return p.problems().getFirst().message();
    }

    private static String with(String from, String to) {
        assertTrue(GOOD.contains(from), "test bug: " + from);
        return GOOD.replace(from, to);
    }

    @Test
    void readsAFlow() {
        FlowDefinition.Parsed p = FlowDefinition.parse(GOOD);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition d = p.definition();
        assertEquals("nightly-customers", d.name());
        assertEquals(Duration.ofMinutes(45), d.queueTimeout());
        assertEquals(2, d.parameters().size());
        assertEquals(0.02, d.parameter("limit_share").defaultValue());
        assertEquals("EMEA", d.parameter("region").defaultValue());
        assertEquals(3, d.steps().size());
        assertEquals(3, d.step("spend").version());
        assertEquals("failed", d.step("halt").status());
        assertEquals(3, d.paths().size());
        assertEquals(FlowEngine.Kind.CONDITION, d.paths().getFirst().kind());
        assertNotNull(d.paths().getFirst().condition());
        assertEquals(FlowEngine.Kind.FAILURE, d.paths().get(2).kind());
        assertEquals(3, d.enginePaths().size());
    }

    @Test
    void aPathWithNoWhenIsSuccess() {
        FlowDefinition.Parsed p = FlowDefinition.parse("""
            kilnmark: flow/v1
            name: f
            steps:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
            paths:
              - { from: a, to: b }
            """);
        assertTrue(p.valid(), p.problems().toString());
        assertEquals(FlowEngine.Kind.SUCCESS, p.definition().paths().getFirst().kind());
        assertEquals(FlowDefinition.DEFAULT_QUEUE_TIMEOUT, p.definition().queueTimeout());
    }

    @Test
    void anyOrderOfPathsWorksForConditions() {
        FlowDefinition.Parsed p = FlowDefinition.parse("""
            kilnmark: flow/v1
            name: f
            steps:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
              - { id: c, type: run-pipeline, pipeline: p3 }
            paths:
              - { from: b, to: c, when: "a.score >= good and b.rows_written > 0" }
              - { from: a, to: b }
            """);
        assertTrue(p.valid(), "a is before b even though its path is written second: " + p.problems());
    }

    @Test
    void refusesWhatIsWrong() {
        assertTrue(firstProblem(with("flow/v1", "pipeline/v1")).contains("looks like a pipeline"));
        assertTrue(firstProblem(with("name: nightly-customers", "name: Nightly")).contains("must start with a lower-case"));
        assertTrue(firstProblem(with("type: run-pipeline\n    pipeline: demo-customers-clean",
            "type: run-pipline\n    pipeline: demo-customers-clean")).contains("did you mean 'run-pipeline'"));
        assertTrue(FlowDefinition.parse(with("pipeline: demo-customers-clean", "pipline: demo-customers-clean"))
            .problems().stream().anyMatch(p -> p.message().contains("did you mean 'pipeline'")));
        assertTrue(firstProblem(with("pipeline: demo-customers-clean", "pipeline: Demo_Customers")).contains("pipeline's name"));
        assertTrue(firstProblem(with("version: 3", "version: 0")).contains("version number"));
        assertTrue(firstProblem(with("status: failed", "status: maybe")).contains("succeeded or failed"));
        assertTrue(firstProblem(with("queue_timeout: 45m", "queue_timeout: soon")).contains("queue_timeout"));
        assertTrue(firstProblem(with("queue_timeout: 45m", "queue_timeout: 48h")).contains("at most 24h"));
        assertTrue(firstProblem(with("{ type: text, default: EMEA }", "{ type: text, default: 5 }")).contains("must be a text"));
        assertTrue(firstProblem(with("limit_share: { type: number", "limit_share: { type: numbr")).contains("needs a type"));
        assertTrue(firstProblem(with("from: clean, to: spend", "from: clean, to: spnd")).contains("which is not a step"));
        assertTrue(firstProblem(with("from: clean, to: spend", "from: clean, to: clean")).contains("back to itself"));
        assertTrue(firstProblem(with("when: failure", "when: \"clean.scor >= good\"")).contains("did you mean 'clean.score'"));
        assertTrue(FlowDefinition.parse(with("steps:", "stepz:")).problems().stream()
            .anyMatch(p -> p.message().contains("Unknown key 'stepz' (did you mean 'steps'?)")));
    }

    @Test
    void aParameterCannotBeNamedLikeAWordOfTheLanguage() {
        for (String word : List.of("good", "and", "true", "poor")) {
            String yaml = GOOD.replace("region: { type: text, default: EMEA }", word + ": { type: text, default: x }");
            assertTrue(FlowDefinition.parse(yaml).problems().stream().anyMatch(p -> p.message().contains("'" + word
                + "' cannot be a parameter name")), word);
        }
    }

    @Test
    void aConditionMayOnlyReadStepsThatHaveEnded() {
        String yaml = """
            kilnmark: flow/v1
            name: f
            steps:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
              - { id: c, type: run-pipeline, pipeline: p3 }
            paths:
              - { from: a, to: b, when: "c.score >= good" }
              - { from: b, to: c }
            """;
        assertTrue(firstProblem(yaml).contains("Unknown value 'c.score'"), firstProblem(yaml));
    }

    @Test
    void aStopStepReportsOnlyItsStatus() {
        String yaml = """
            kilnmark: flow/v1
            name: f
            steps:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: s, type: stop }
              - { id: b, type: run-pipeline, pipeline: p2 }
            paths:
              - { from: a, to: s }
              - { from: s, to: b, when: "s.score >= good" }
            """;
        assertTrue(firstProblem(yaml).contains("Unknown value 's.score'"));
    }

    @Test
    void refusesLoopsAndDuplicates() {
        String loop = """
            kilnmark: flow/v1
            name: f
            steps:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
            paths:
              - { from: a, to: b }
              - { from: b, to: a, when: always }
            """;
        assertTrue(firstProblem(loop).contains("loop through a, b"));
        String twice = loop.replace("{ from: b, to: a, when: always }", "{ from: a, to: b }");
        assertTrue(firstProblem(twice).contains("written twice"));
        String sameId = loop.replace("id: b", "id: a");
        assertTrue(FlowDefinition.parse(sameId).problems().stream().anyMatch(p -> p.message().contains("used twice")));
    }

    @Test
    void refusesAnythingThatIsNotAFlow() {
        assertTrue(firstProblem("").contains("starts with kilnmark"));
        assertTrue(firstProblem("- a\n- b").contains("starts with kilnmark"));
        assertTrue(firstProblem("kilnmark: flow/v1\nname: f\nsteps: []").contains("at least one step"));
        assertTrue(firstProblem("kilnmark: flow/v1\nsteps:\n  - {id: a, type: stop}").contains("needs a name"));
        assertTrue(firstProblem("kilnmark: flow/v1\nname: f\nsteps:\n  - &x {id: a, type: stop}").toLowerCase()
            .contains("anchor"));
    }
}
