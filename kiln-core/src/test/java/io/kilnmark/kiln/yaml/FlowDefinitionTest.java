package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        nodes:
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
        connections:
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

    @Test
    void aFlowWrittenWithTheOldKeysStillReads() {
        FlowDefinition.Parsed old = FlowDefinition.parse(GOOD.replace("nodes:", "steps:").replace("connections:", "paths:"));
        assertTrue(old.valid(), () -> String.valueOf(old.problems()));
        assertEquals(3, old.definition().nodes().size());
        assertEquals(3, old.definition().connections().size());
        assertTrue(firstProblem(GOOD.replace("layout:", "paths: []\nlayout:"))
            .contains("'paths' is the old name of 'connections', and this flow has both: keep connections"));
    }

    private static String with(String from, String to) {
        assertTrue(GOOD.contains(from), "test bug: " + from);
        return GOOD.replace(from, to);
    }

    private static final String MORE = """
        kilnmark: flow/v1
        name: more
        parameters:
          region: { type: text, default: EMEA }
          statuses: { type: text list, default: [delivered] }
        nodes:
          - id: clean
            type: run-pipeline
            pipeline: demo-customers-clean
            with: { region: "${region}", note: "for ${vars.team}", limit: 5, dry: true }
          - id: pause
            type: wait
            for: 10m
          - id: tell
            type: notify
            severity: medium
            title: "Clean wrote ${clean.rows_written} rows for ${region}"
            message: Status ${clean.status}
        connections:
          - { from: clean, to: pause }
          - { from: pause, to: tell }
        """;

    @Test
    void readsWithNotifyAndWait() {
        FlowDefinition.Parsed p = FlowDefinition.parse(MORE);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition d = p.definition();
        assertEquals(Map.of("region", "${region}", "note", "for ${vars.team}", "limit", 5.0, "dry", true),
            d.node("clean").with());
        assertEquals(Duration.ofMinutes(10), d.node("pause").waitFor());
        FlowDefinition.Node tell = d.node("tell");
        assertEquals("medium", tell.severity());
        assertEquals("Clean wrote ${clean.rows_written} rows for ${region}", tell.title());
        assertEquals(java.util.Set.of("clean", "pause"), FlowDefinition.before(d, "tell"));
        assertTrue(d.parameter("statuses").list());
    }

    @Test
    void aNodeCannotBeCalledVars() {
        assertTrue(firstProblem(MORE.replace("id: pause", "id: vars").replace("to: pause", "to: vars")
            .replace("from: pause", "from: vars")).contains("cannot be called vars"));
    }

    @Test
    void checksWithNotifyAndWait() {
        assertTrue(firstProblem(MORE.replace("for: 10m", "for: 2d")).contains("for is a time"));
        assertTrue(firstProblem(MORE.replace("for: 10m", "for: 25h")).contains("at most 24h"));
        assertTrue(firstProblem(MORE.replace("severity: medium", "severity: urgent")).contains("info, low, medium, high"));
        assertTrue(firstProblem(MORE.replace("    title: \"Clean wrote ${clean.rows_written} rows for ${region}\"\n", ""))
            .contains("needs a title"));
        assertTrue(firstProblem(MORE.replace("\"${region}\", note", "\"${regon}\", note"))
            .contains("names the parameter 'regon', which is not declared (did you mean 'region'?)"));
        // A node value must come from a node that has always ended first, and be one it reports.
        assertTrue(firstProblem(MORE.replace("${clean.rows_written}", "${tell.rows_written}"))
            .contains("has not always run when tell starts"));
        // A branch may skip a node: only nodes on every way in can be read.
        assertTrue(firstProblem(MORE.replace("  - { from: clean, to: pause }",
            "  - { from: clean, to: pause, when: failure }\n  - { from: clean, to: tell, when: success }")
            .replace("message: Status ${clean.status}", "message: Paused ${pause.status}"))
            .contains("node pause has not always run when tell starts"));
        assertTrue(firstProblem(MORE.replace("${clean.rows_written}", "${pause.rows_written}"))
            .contains("which pause does not report"));
        assertTrue(firstProblem(MORE.replace("limit: 5,", "limit: [5],")).contains("is one value"));
        // A list parameter is for in (...) in a pipeline, not for a condition.
        assertTrue(FlowDefinition.parse(MORE.replace("  - { from: clean, to: pause }",
                "  - { from: clean, to: pause, when: \"statuses = 'x'\" }")).problems().stream()
            .anyMatch(x -> x.message().contains("Unknown value 'statuses'")));
    }

    private static final String SQL = """
        kilnmark: flow/v1
        name: reload
        nodes:
          - id: empty
            type: sql
            source: warehouse
            statements:
              - TRUNCATE staging.orders
              - "DELETE FROM staging.log WHERE at < now() - interval '30 days'"
          - id: load
            type: run-pipeline
            pipeline: orders-load
          - id: tell
            type: notify
            title: "Emptied ${empty.rows_affected} rows"
        connections:
          - { from: empty, to: load, when: "empty.rows_affected >= 0" }
          - { from: load, to: tell }
        """;

    @Test
    void readsASqlNode() {
        FlowDefinition.Parsed p = FlowDefinition.parse(SQL);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition.Node empty = p.definition().node("empty");
        assertEquals("warehouse", empty.source());
        assertEquals(List.of("TRUNCATE staging.orders",
            "DELETE FROM staging.log WHERE at < now() - interval '30 days'"), empty.statements());
        assertEquals(FlowCondition.Type.NUMBER, FlowDefinition.nodeValueType(p.definition(), "empty.rows_affected"));
        assertEquals(null, FlowDefinition.nodeValueType(p.definition(), "empty.rows_written"));
    }

    @Test
    void checksASqlNode() {
        // Never a value put into SQL (ADR 0028)
        assertTrue(firstProblem(SQL.replace("TRUNCATE staging.orders", "TRUNCATE staging.${table}"))
            .contains("${...} cannot be used in SQL"));
        assertTrue(firstProblem(SQL.replace("    source: warehouse\n", "")).contains("needs a source"));
        assertTrue(firstProblem(SQL.replace("""
                statements:
                  - TRUNCATE staging.orders
                  - "DELETE FROM staging.log WHERE at < now() - interval '30 days'"
            """, "    statements: TRUNCATE staging.orders\n")).contains("statements is a list"));
        assertTrue(firstProblem(SQL.replace("""
                  - TRUNCATE staging.orders
                  - "DELETE FROM staging.log WHERE at < now() - interval '30 days'"
            """, "      - \"" + "x".repeat(FlowDefinition.MAX_STATEMENT + 1) + "\"\n")).contains("at most 10,000"));
        assertTrue(firstProblem(SQL.replace("    source: warehouse\n", "    source: warehouse\n    with: {a: 1}\n"))
            .contains("has no setting 'with'"));
        // A sql node reports rows_affected and status, nothing else
        assertTrue(firstProblem(SQL.replace("${empty.rows_affected}", "${empty.rows_written}"))
            .contains("a sql node rows_affected"));
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
        assertEquals(3, d.nodes().size());
        assertEquals(3, d.node("spend").version());
        assertEquals("failed", d.node("halt").status());
        assertEquals(3, d.connections().size());
        assertEquals(FlowEngine.Kind.CONDITION, d.connections().getFirst().kind());
        assertNotNull(d.connections().getFirst().condition());
        assertEquals(FlowEngine.Kind.FAILURE, d.connections().get(2).kind());
        assertEquals(3, d.engineConnections().size());
    }

    @Test
    void aConnectionWithNoWhenIsSuccess() {
        FlowDefinition.Parsed p = FlowDefinition.parse("""
            kilnmark: flow/v1
            name: f
            nodes:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
            connections:
              - { from: a, to: b }
            """);
        assertTrue(p.valid(), p.problems().toString());
        assertEquals(FlowEngine.Kind.SUCCESS, p.definition().connections().getFirst().kind());
        assertEquals(FlowDefinition.DEFAULT_QUEUE_TIMEOUT, p.definition().queueTimeout());
    }

    @Test
    void anyOrderOfConnectionsWorksForConditions() {
        FlowDefinition.Parsed p = FlowDefinition.parse("""
            kilnmark: flow/v1
            name: f
            nodes:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
              - { id: c, type: run-pipeline, pipeline: p3 }
            connections:
              - { from: b, to: c, when: "a.score >= good and b.rows_written > 0" }
              - { from: a, to: b }
            """);
        assertTrue(p.valid(), "a is before b even though its connection is written second: " + p.problems());
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
        assertTrue(firstProblem(with("{ type: text, default: EMEA }", "{ type: text, default: [5] }")).contains("must be text"));
        assertTrue(firstProblem(with("limit_share: { type: number", "limit_share: { type: numbr")).contains("needs a type"));
        assertTrue(firstProblem(with("from: clean, to: spend", "from: clean, to: spnd")).contains("which is not a node"));
        assertTrue(firstProblem(with("from: clean, to: spend", "from: clean, to: clean")).contains("back to itself"));
        assertTrue(firstProblem(with("when: failure", "when: \"clean.scor >= good\"")).contains("did you mean 'clean.score'"));
        assertTrue(FlowDefinition.parse(with("nodes:", "nodez:")).problems().stream()
            .anyMatch(p -> p.message().contains("Unknown key 'nodez' (did you mean 'nodes'?)")));
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
    void aConditionMayOnlyReadNodesThatHaveEnded() {
        String yaml = """
            kilnmark: flow/v1
            name: f
            nodes:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
              - { id: c, type: run-pipeline, pipeline: p3 }
            connections:
              - { from: a, to: b, when: "c.score >= good" }
              - { from: b, to: c }
            """;
        assertTrue(firstProblem(yaml).contains("Unknown value 'c.score'"), firstProblem(yaml));
    }

    @Test
    void aStopNodeReportsOnlyItsStatus() {
        String yaml = """
            kilnmark: flow/v1
            name: f
            nodes:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: s, type: stop }
              - { id: b, type: run-pipeline, pipeline: p2 }
            connections:
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
            nodes:
              - { id: a, type: run-pipeline, pipeline: p1 }
              - { id: b, type: run-pipeline, pipeline: p2 }
            connections:
              - { from: a, to: b }
              - { from: b, to: a, when: always }
            """;
        assertTrue(firstProblem(loop).contains("loop through a, b"));
        String twice = loop.replace("{ from: b, to: a, when: always }", "{ from: a, to: b }");
        assertTrue(firstProblem(twice).contains("written twice"));
        String sameId = loop.replace("id: b", "id: a");
        assertTrue(FlowDefinition.parse(sameId).problems().stream().anyMatch(p -> p.message().contains("used twice")));
    }

    // ---- max_parallel and for-each (ADR 0040) ----

    static final String FAN = """
        kilnmark: flow/v1
        name: per-region
        max_parallel: 3
        parameters:
          regions: { type: text list, default: [EMEA, APAC] }
          years: { type: number list, default: [2025] }
          label: { type: text, default: nightly }
        nodes:
          - id: regions
            type: for-each
            pipeline: sales-by-region
            items: [EMEA, APAC, AMER]
            as: region
            with: { label: "${label}" }
            max_parallel: 2
          - id: years
            type: for-each
            pipeline: sales-by-year
            version: 2
            items: "${years}"
            as: year
          - id: tell
            type: notify
            title: "${regions.succeeded} of ${regions.items} regions"
        connections:
          - { from: regions, to: years, when: "regions.failed = 0" }
          - { from: years, to: tell }
        """;

    @Test
    void readsMaxParallelAndAForEachNode() {
        FlowDefinition.Parsed p = FlowDefinition.parse(FAN);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition d = p.definition();
        assertEquals(3, d.maxParallel());
        assertEquals(null, FlowDefinition.parse(GOOD).definition().maxParallel(), "no limit when left out");
        FlowDefinition.Node regions = d.node("regions");
        assertEquals("sales-by-region", regions.pipeline());
        assertEquals(List.of("EMEA", "APAC", "AMER"), regions.each().items());
        assertEquals("region", regions.each().as());
        assertEquals(2, regions.each().maxParallel());
        assertEquals(Map.of("label", "${label}"), regions.with());
        FlowDefinition.Node years = d.node("years");
        assertEquals(2, years.version());
        assertEquals("years", years.each().itemsParameter());
        assertEquals(FlowDefinition.DEFAULT_ITEMS_AT_ONCE, years.each().maxParallel(), "4 when left out");
        assertEquals(List.of(2025.0, 2026.0), years.each().itemsIn(Map.of("years", List.of(2025.0, 2026.0))));
        assertEquals(List.of("EMEA", "APAC", "AMER"), regions.each().itemsIn(Map.of()));
        assertEquals(FlowCondition.Type.NUMBER, FlowDefinition.nodeValueType(d, "regions.failed"));
        assertEquals(null, FlowDefinition.nodeValueType(d, "regions.rows_written"));
        assertEquals(List.of(1.0, 2.5), FlowDefinition.parse(FAN.replace("[EMEA, APAC, AMER]", "[1, 2.5]")).definition()
            .node("regions").each().items(), "numbers are read as numbers");
        assertEquals(List.of("007"), FlowDefinition.parse(FAN.replace("[EMEA, APAC, AMER]", "[\"007\"]")).definition()
            .node("regions").each().items(), "text keeps its text");
    }

    @Test
    void checksMaxParallelAndAForEachNode() {
        assertTrue(firstProblem(FAN.replace("max_parallel: 3", "max_parallel: 0"))
            .contains("max_parallel is how many of the flow's nodes run at once: a whole number from 1 to 50"));
        assertTrue(firstProblem(FAN.replace("max_parallel: 3", "max_parallel: 51")).contains("from 1 to 50"));
        assertTrue(firstProblem(FAN.replace("max_parallel: 3", "max_parallel: lots")).contains("from 1 to 50"));
        assertTrue(firstProblem(FAN.replace("    max_parallel: 2", "    max_parallel: 60"))
            .contains("max_parallel is how many items run at once"));
        assertTrue(firstProblem(FAN.replace("    as: region\n", "")).contains("needs as: the pipeline parameter"));
        assertTrue(firstProblem(FAN.replace("    pipeline: sales-by-region\n", "")).contains("needs a pipeline"));
        assertTrue(firstProblem(FAN.replace("    items: [EMEA, APAC, AMER]\n", "")).contains("needs items"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "[]")).contains("a list of 1 to 500 values"));
        String many = "[" + String.join(", ", java.util.Collections.nCopies(501, "x")) + "]";
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", many)).contains("a list of 1 to 500 values"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "[EMEA, 2]")).contains("all texts or all numbers"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "[EMEA, true]")).contains("one value: text or a number"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "[EMEA, [a]]")).contains("one value: text or a number"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "[\"${label}\"]")).contains("${...} is not read"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "EMEA")).contains("items is a list of the values"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "\"${label}\""))
            .contains("items names label, which is not a list parameter of the flow"));
        assertTrue(firstProblem(FAN.replace("[EMEA, APAC, AMER]", "\"${regoins}\"")).contains("regions"),
            "suggests the closest name");
        assertTrue(firstProblem(FAN.replace("as: region", "as: Region!")).contains("as is the name of the pipeline"));
        assertTrue(firstProblem(FAN.replace("{ label: \"${label}\" }", "{ region: EMEA }"))
            .contains("fills region with each item, so with: cannot give it a value too"));
        assertTrue(firstProblem(FAN.replace("    as: year\n", "    as: year\n    statements: [x]\n"))
            .contains("has no setting 'statements'"));
        // It reports items, succeeded and failed, nothing a pipeline run reports.
        List<Problem> score = FlowDefinition.parse(FAN.replace("regions.failed = 0", "regions.score >= good")).problems();
        assertTrue(score.stream().anyMatch(x -> x.message().contains("Connection from regions")
            && x.message().contains("regions.score")), score.toString());
        assertTrue(firstProblem(FAN.replace("${regions.items}", "${regions.rows_written}"))
            .contains("a for-each node items, succeeded and failed"));
        assertTrue(FlowDefinition.parse(FAN.replace("max_parallel: 3", "maxparallel: 3")).problems().stream()
            .anyMatch(x -> x.message().contains("did you mean 'max_parallel'")
                || x.message().contains("max_parallel")), "the top-level key is known");
    }

    @Test
    void refusesAnythingThatIsNotAFlow() {
        assertTrue(firstProblem("").contains("starts with kilnmark"));
        assertTrue(firstProblem("- a\n- b").contains("starts with kilnmark"));
        assertTrue(firstProblem("kilnmark: flow/v1\nname: f\nnodes: []").contains("at least one node"));
        assertTrue(firstProblem("kilnmark: flow/v1\nnodes:\n  - {id: a, type: stop}").contains("needs a name"));
        assertTrue(firstProblem("kilnmark: flow/v1\nname: f\nnodes:\n  - &x {id: a, type: stop}").toLowerCase()
            .contains("anchor"));
    }

    // ---- timeout (ADR 0041) ----

    @Test
    void readsATimeoutOnNodesThatRunSomething() {
        String yaml = SQL.replace("    source: warehouse\n", "    source: warehouse\n    timeout: 90s\n")
            .replace("    pipeline: orders-load\n", "    pipeline: orders-load\n    timeout: 2h\n");
        FlowDefinition.Parsed p = FlowDefinition.parse(yaml);
        assertTrue(p.valid(), p.problems().toString());
        assertEquals(Duration.ofSeconds(90), p.definition().node("empty").timeout());
        assertEquals(Duration.ofHours(2), p.definition().node("load").timeout());
        assertEquals(null, p.definition().node("tell").timeout());
        FlowDefinition.Parsed each = FlowDefinition.parse(FAN.replace("    max_parallel: 2\n",
            "    max_parallel: 2\n    timeout: 7d\n"));
        assertTrue(each.valid(), each.problems().toString());
        assertEquals(Duration.ofDays(7), each.definition().node("regions").timeout());
        assertEquals(null, FlowDefinition.parse(SQL).definition().node("load").timeout(), "no timeout unless written");
    }

    @Test
    void checksATimeout() {
        String load = "    pipeline: orders-load\n";
        for (String bad : List.of("0s", "8d", "169h", "soon", "2 weeks", "-1h", "[1h]")) {
            assertTrue(firstProblem(SQL.replace(load, load + "    timeout: " + bad + "\n"))
                .contains("timeout is a time such as 90s, 30m, 2h or 1d, more than nothing and at most 7d"), bad);
        }
        assertTrue(FlowDefinition.parse(SQL.replace(load, load + "    timeout: 168h\n")).valid(), "7 days is the most");
        // Only nodes that run something take one: a wait has for, a notify or stop ends at once.
        assertTrue(firstProblem(SQL.replace("    title: \"Emptied", "    timeout: 1h\n    title: \"Emptied"))
            .contains("has no setting 'timeout'"));
    }

    @Test
    void writesATimeInItsLargestWholeUnit() {
        assertEquals("2h", FlowDefinition.written(Duration.ofHours(2)));
        assertEquals("90m", FlowDefinition.written(Duration.ofMinutes(90)));
        assertEquals("1d", FlowDefinition.written(Duration.ofHours(24)));
        assertEquals("45s", FlowDefinition.written(Duration.ofSeconds(45)));
        assertEquals("2m", FlowDefinition.written(Duration.ofSeconds(120)));
    }

    // ---- priority (ADR 0041) ----

    @Test
    void readsAPriorityOnNodesThatQueuePipelineRuns() {
        String load = "    pipeline: orders-load\n";
        FlowDefinition.Parsed high = FlowDefinition.parse(SQL.replace(load, load + "    priority: high\n"));
        assertTrue(high.valid(), high.problems().toString());
        assertEquals(1, high.definition().node("load").priority());
        assertEquals(-1, FlowDefinition.parse(SQL.replace(load, load + "    priority: LOW\n")).definition().node("load")
            .priority(), "in any case");
        assertEquals(0, FlowDefinition.parse(SQL.replace(load, load + "    priority: normal\n")).definition()
            .node("load").priority());
        assertEquals(0, FlowDefinition.parse(SQL).definition().node("load").priority(), "normal unless written");
        FlowDefinition.Parsed each = FlowDefinition.parse(FAN.replace("    max_parallel: 2\n",
            "    max_parallel: 2\n    priority: high\n"));
        assertTrue(each.valid(), each.problems().toString());
        assertEquals(1, each.definition().node("regions").priority());
    }

    @Test
    void checksAPriority() {
        String load = "    pipeline: orders-load\n";
        for (String bad : List.of("urgent", "1", "[high]", "\"\"")) {
            assertTrue(firstProblem(SQL.replace(load, load + "    priority: " + bad + "\n"))
                .contains("priority is high, normal or low"), bad);
        }
        // A sql node runs on its flow's watcher, never queued: a priority would order nothing.
        assertTrue(firstProblem(SQL.replace("    source: warehouse\n", "    source: warehouse\n    priority: high\n"))
            .contains("has no setting 'priority'"));
        assertTrue(firstProblem(SQL.replace("    title: \"Emptied", "    priority: high\n    title: \"Emptied"))
            .contains("has no setting 'priority'"));
    }

    // ---- check-source (ADR 0041) ----

    static final String CHECK = """
        kilnmark: flow/v1
        name: check-first
        nodes:
          - id: ready
            type: check-source
            source: warehouse
            tables: [sales.orders, sales.customers]
            files: [incoming/today.csv]
            timeout: 2m
          - id: load
            type: run-pipeline
            pipeline: orders-load
          - id: tell
            type: notify
            title: "${ready.missing} missing"
        connections:
          - { from: ready, to: load, when: "ready.missing = 0" }
          - { from: ready, to: tell, when: failure }
        """;

    @Test
    void readsACheckSourceNode() {
        FlowDefinition.Parsed p = FlowDefinition.parse(CHECK);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition.Node ready = p.definition().node("ready");
        assertEquals("warehouse", ready.source());
        assertEquals(List.of("sales.orders", "sales.customers"), ready.check().tables());
        assertEquals(List.of("incoming/today.csv"), ready.check().files());
        assertEquals(Duration.ofMinutes(2), ready.timeout());
        assertEquals(FlowCondition.Type.NUMBER, FlowDefinition.nodeValueType(p.definition(), "ready.missing"));
        // Neither list: it checks only that the source can be reached.
        FlowDefinition.Parsed bare = FlowDefinition.parse(CHECK.replace("    tables: [sales.orders, sales.customers]\n",
            "").replace("    files: [incoming/today.csv]\n", ""));
        assertTrue(bare.valid(), bare.problems().toString());
        assertTrue(bare.definition().node("ready").check().tables().isEmpty());
    }

    @Test
    void checksACheckSourceNode() {
        assertTrue(firstProblem(CHECK.replace("    source: warehouse\n", "")).contains("needs a source"));
        assertTrue(firstProblem(CHECK.replace("[sales.orders, sales.customers]", "sales.orders"))
            .contains("tables is a list"));
        assertTrue(firstProblem(CHECK.replace("[sales.orders, sales.customers]", "[\"sales.${x}\"]"))
            .contains("${...} cannot be used"));
        assertTrue(firstProblem(CHECK.replace("[sales.orders, sales.customers]", "[sales.orders, sales.orders]"))
            .contains("names sales.orders twice"));
        assertTrue(firstProblem(CHECK.replace("[sales.orders, sales.customers]", "[\"\"]"))
            .contains("Each of tables is a table as the catalogue names it"));
        String many = "[" + String.join(", ", java.util.stream.IntStream.range(0, 50).mapToObj(i -> "s.t" + i).toList())
            + "]";
        assertTrue(firstProblem(CHECK.replace("[sales.orders, sales.customers]", many))
            .contains("names 51 tables and files: at most 50"));
        assertTrue(firstProblem(CHECK.replace("    timeout: 2m\n", "    priority: high\n"))
            .contains("has no setting 'priority'"));
        assertTrue(firstProblem(CHECK.replace("ready.missing = 0", "ready.rows_written = 0"))
            .contains("rows_written"));
    }
}
