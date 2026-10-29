package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphNode;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.server.quality.Dimension;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleType;

/**
 * The gate node on kiln-core's graph runner: stop, quarantine and warn; its thresholds at their
 * edges; nulls, repeats and empty input; the asset's rules beside inline ones; and its score. Rows
 * are synthetic people (id, email, country).
 */
class QualityGateTest {

    static final Schema PEOPLE = new Schema(List.of(Field.nullable("id", new ArrowType.Int(64, true)),
        Field.nullable("email", ArrowType.Utf8.INSTANCE), Field.nullable("country", ArrowType.Utf8.INSTANCE)));

    /** One synthetic person. */
    record Person(long id, String email, String country) {
    }

    /** {@code n} people; every {@code nullEvery}-th has no email (0: none missing). */
    static List<Person> people(int n, int nullEvery) {
        List<Person> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new Person(i, nullEvery > 0 && i % nullEvery == 0 ? null : "person" + i + "@example.test",
                i % 3 == 0 ? "IE" : "GB"));
        }
        return out;
    }

    /** Puts people on its output in batches. */
    static final class Source implements Node {
        final List<Person> rows;
        final int batch;
        int sent;

        Source(List<Person> rows, int batch) {
            this.rows = rows;
            this.batch = batch;
        }

        @Override
        public String type() {
            return "people";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            if (sent >= rows.size()) {
                return false;
            }
            int n = Math.min(batch, rows.size() - sent);
            VectorSchemaRoot root = VectorSchemaRoot.create(PEOPLE, context.allocator());
            root.allocateNew();
            BigIntVector id = (BigIntVector) root.getVector(0);
            VarCharVector email = (VarCharVector) root.getVector(1);
            VarCharVector country = (VarCharVector) root.getVector(2);
            for (int i = 0; i < n; i++) {
                Person p = rows.get(sent + i);
                id.setSafe(i, p.id());
                if (p.email() == null) {
                    email.setNull(i);
                } else {
                    email.setSafe(i, p.email().getBytes(StandardCharsets.UTF_8));
                }
                country.setSafe(i, p.country().getBytes(StandardCharsets.UTF_8));
            }
            root.setRowCount(n);
            sent += n;
            try {
                context.emit("out", root);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
            return true;
        }
    }

    /** Collects the ids that reach it, and whether it saw the end of its rows (as a write commits). */
    static final class Sink implements Node {
        final List<Long> ids = Collections.synchronizedList(new ArrayList<>());
        volatile boolean ended;

        @Override
        public String type() {
            return "collect";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            VectorSchemaRoot b;
            try {
                b = context.input().take(context.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
            if (b == null) {
                ended = true;
                return false;
            }
            try (b) {
                BigIntVector id = (BigIntVector) b.getVector("id");
                for (int i = 0; i < b.getRowCount(); i++) {
                    ids.add(id.get(i));
                }
            }
            return true;
        }
    }

    /** What a run of source → gate → (out, quarantine) did. */
    record Ran(RunResult result, QualityGate gate, Sink out, Sink quarantine) {
        QualityGate.Outcome outcome() {
            return gate.outcome();
        }
    }

    static Ran run(QualityGate gate, List<Person> rows, int batch, boolean quarantineConnected) throws Exception {
        Sink out = new Sink();
        Sink quarantine = new Sink();
        List<GraphNode> nodes = new ArrayList<>(List.of(new GraphNode("people", new Source(rows, batch), Map.of()),
            new GraphNode("check", gate, Map.of()), new GraphNode("good", out, Map.of())));
        List<Edge> edges = new ArrayList<>(List.of(new Edge(new PortRef("people", "out"), new PortRef("check", "in")),
            new Edge(new PortRef("check", "out"), new PortRef("good", "in"))));
        if (quarantineConnected) {
            nodes.add(new GraphNode("bad", quarantine, Map.of()));
            edges.add(new Edge(new PortRef("check", "quarantine"), new PortRef("bad", "in")));
        }
        RunResult r = new KilnGraphRunner(64L << 20, 4).run(new PipelineGraph("gate-test", nodes, edges), e -> { });
        return new Ran(r, gate, out, quarantine);
    }

    static Map<String, Object> rule(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static QualityGate gate(String onFail, Double minScore, Double maxShare, List<Map<String, Object>> rules) {
        return new QualityGate(QualityGate.OnFail.parse(onFail), minScore, maxShare, GateRules.read(rules, null), false,
            null);
    }

    static final List<Map<String, Object>> EMAIL_PRESENT = List.of(rule("type", "not_null", "field", "email"));

    // ---- quarantine ----

    @Test
    void quarantineSendsFailingRowsAsideAndTheRestOn() throws Exception {
        Ran r = run(gate("quarantine", null, null, EMAIL_PRESENT), people(100, 10), 16, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(90, r.out().ids.size());
        assertEquals(10, r.quarantine().ids.size());
        assertTrue(r.quarantine().ids.stream().allMatch(id -> id % 10 == 0), r.quarantine().ids.toString());
        QualityGate.Outcome o = r.outcome();
        assertEquals("quarantined", o.decision());
        assertEquals(100, o.rowsIn());
        assertEquals(90, o.rowsPassed());
        assertEquals(10, o.rowsFailing());
        assertEquals(10, o.rowsQuarantined());
        assertEquals(0.9, o.score(), 1e-9);
        assertEquals(0.9, o.dimensions().get("completeness"), 1e-9);
        assertEquals("fail", o.rules().get(0).status(), "the rule fails by its own limit; the rows are set aside");
        assertEquals(10, o.rules().get(0).failing());
        assertEquals("inline", o.rules().get(0).origin());
        assertNull(o.stopReason());
    }

    @Test
    void quarantinedRowsWithNothingConnectedGoNowhereAndValidationSaysSo() throws Exception {
        // The run itself drops them (graphChecks below has the note that warns before it runs).
        Ran r = run(gate("quarantine", null, null, EMAIL_PRESENT), people(50, 5), 7, false);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(40, r.out().ids.size());
        assertEquals(10, r.outcome().rowsQuarantined());
    }

    @Test
    void quarantineStopsWhenTooManyRowsFail() throws Exception {
        // 20% fail; at most 10% may.
        Ran r = run(gate("quarantine", null, 0.1, EMAIL_PRESENT), people(100, 5), 25, true);
        assertFalse(r.result().succeeded());
        assertTrue(r.result().error().contains("20 of 100 rows (20%) failed a rule, more than the gate's "
            + "max_failing_share of 10%"), r.result().error());
        assertFalse(r.out().ended, "nothing after the gate finished");
        assertEquals("stopped", r.outcome().decision());
    }

    @Test
    void theFailingShareMayEqualTheThreshold() throws Exception {
        Ran r = run(gate("quarantine", null, 0.1, EMAIL_PRESENT), people(100, 10), 25, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("quarantined", r.outcome().decision());
    }

    @Test
    void theScoreMayEqualTheMinimum() throws Exception {
        Ran equal = run(gate("quarantine", 0.9, null, EMAIL_PRESENT), people(100, 10), 25, true);
        assertTrue(equal.result().succeeded(), equal.result().error());
        Ran below = run(gate("quarantine", 0.91, null, EMAIL_PRESENT), people(100, 10), 25, true);
        assertFalse(below.result().succeeded());
        assertTrue(below.result().error().contains("the rows scored 90%, below the gate's min_score of 91%"),
            below.result().error());
    }

    @Test
    void aWholeDataFailureStopsAQuarantineGate() throws Exception {
        Ran r = run(gate("quarantine", null, null, List.of(rule("type", "row_count", "min", 500))), people(100, 0),
            25, true);
        assertFalse(r.result().succeeded());
        assertTrue(r.result().error().contains("Rows cannot be set aside for a check of the whole data"),
            r.result().error());
    }

    // ---- stop ----

    @Test
    void stopFailsTheRunAtTheFirstRowThatCannotPassAndNamesTheRule() throws Exception {
        Ran r = run(gate("stop", null, null, EMAIL_PRESENT), people(1000, 100), 50, true);
        assertFalse(r.result().succeeded());
        String error = r.result().error();
        assertTrue(error.contains("The quality gate stopped the run: rule 'email not null' failed on 1 row in the "
            + "first 100, more than its limit of 0%"), error);
        assertFalse(r.out().ended, "the write after the gate never saw the end of its rows, so it commits nothing");
        assertEquals("stopped", r.outcome().decision());
        assertEquals(100, r.outcome().rowsIn(), "it stopped early: the rest were never checked");
    }

    @Test
    void stopLetsRowsThroughWhenEveryRuleHolds() throws Exception {
        Ran r = run(gate("stop", null, null, EMAIL_PRESENT), people(300, 0), 64, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertTrue(r.out().ended);
        assertEquals(300, r.out().ids.size());
        assertEquals(0, r.quarantine().ids.size(), "stop never quarantines");
        assertEquals("passed", r.outcome().decision());
        assertEquals(1.0, r.outcome().score());
    }

    @Test
    void stopJudgesAShareLimitAtTheEnd() throws Exception {
        List<Map<String, Object>> rules = List.of(rule("type", "not_null", "field", "email", "fail_above",
            new BigDecimal("0.15")));
        Ran within = run(gate("stop", null, null, rules), people(100, 10), 30, true);
        assertTrue(within.result().succeeded(), within.result().error());
        assertEquals("warned", within.outcome().decision(), "rows failed, within the rule's limit");
        Ran over = run(gate("stop", null, null, rules), people(100, 5), 30, true);
        assertFalse(over.result().succeeded());
        assertTrue(over.result().error().contains("failed on 20 of 100 rows (20%), more than its limit of 15%"),
            over.result().error());
        assertFalse(over.out().ended);
    }

    @Test
    void aCountLimitStopsAsSoonAsItIsPassed() throws Exception {
        List<Map<String, Object>> rules = List.of(rule("type", "not_null", "field", "email", "unit", "count",
            "fail_above", 2));
        Ran r = run(gate("stop", null, null, rules), people(1000, 10), 10, true);
        assertFalse(r.result().succeeded());
        assertTrue(r.result().error().contains("failed on 3 rows in the first 30, more than its limit of 2 rows"),
            r.result().error());
    }

    // ---- warn ----

    @Test
    void warnLetsEveryRowThroughAndRecordsWhatFailed() throws Exception {
        Ran r = run(gate("warn", 0.99, 0.01, EMAIL_PRESENT), people(100, 4), 33, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(100, r.out().ids.size());
        assertEquals(0, r.quarantine().ids.size());
        QualityGate.Outcome o = r.outcome();
        assertEquals("warned", o.decision());
        assertEquals(25, o.rowsFailing());
        assertEquals(0.75, o.score(), 1e-9);
    }

    // ---- nulls, repeats, empty input ----

    @Test
    void nullsFailOnlyNotNullAndBlanksOnlyWhenAsked() throws Exception {
        List<Person> rows = List.of(new Person(1, null, "GB"), new Person(2, "", "GB"), new Person(3, "a@example.test",
            "XX"));
        Ran plain = run(gate("warn", null, null, List.of(rule("type", "not_null", "field", "email"),
            rule("type", "allowed_values", "field", "email", "values", List.of("a@example.test", "")))), rows, 10, true);
        assertEquals(1, plain.outcome().rules().get(0).failing(), "only the null");
        assertEquals(0, plain.outcome().rules().get(1).failing(), "a null is not checked against the values");
        Ran blank = run(gate("warn", null, null, List.of(rule("type", "not_null", "field", "email",
            "blank_is_missing", true))), rows, 10, true);
        assertEquals(2, blank.outcome().rules().get(0).failing());
    }

    @Test
    void repeatsOfAKeyAreQuarantinedAndTheFirstPasses() throws Exception {
        List<Person> rows = List.of(new Person(1, "a", "GB"), new Person(2, "b", "GB"), new Person(1, "a", "GB"),
            new Person(3, "c", "IE"), new Person(1, "a", "GB"));
        Ran r = run(gate("quarantine", null, null, List.of(rule("type", "unique", "field", "id"))), rows, 2, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(List.of(1L, 2L, 3L), r.out().ids);
        assertEquals(List.of(1L, 1L), r.quarantine().ids);
        QualityGate.RuleOutcome u = r.outcome().rules().get(0);
        assertEquals(2, u.failing(), "the repeats, the rows set aside");
        assertEquals(2, r.outcome().rowsQuarantined());
        assertEquals("fail", u.status());
        assertEquals(0.6, r.outcome().score(), 1e-9, "3 of 5 rows passed");
    }

    @Test
    void noRowsPassEveryRuleOfRows() throws Exception {
        Ran r = run(gate("stop", 1.0, 0.0, EMAIL_PRESENT), List.of(), 10, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("passed", r.outcome().decision());
        assertEquals(0, r.outcome().rowsIn());
        assertEquals(1.0, r.outcome().score());
        assertEquals("pass", r.outcome().rules().get(0).status());
    }

    @Test
    void noRowsFailARowCountMinimum() throws Exception {
        Ran r = run(gate("stop", null, null, List.of(rule("type", "row_count", "min", 1))), List.of(), 10, true);
        assertFalse(r.result().succeeded());
        assertTrue(r.result().error().contains("rule 'row count' failed (0 rows)"), r.result().error());
    }

    @Test
    void aColumnThatDoesNotArriveIsAnErrorNotAFailure() throws Exception {
        Rule missing = new Rule(0, "urn:kilnmark:lab-db:crm.people", "phone", "phone present", RuleType.NOT_NULL,
            Dimension.COMPLETENESS, new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "share", null,
            0, Rule.Actions.NONE, true, "kilnmark", null, "steward", null, null);
        QualityGate g = new QualityGate(QualityGate.OnFail.STOP, null, null, List.of(), true,
            id -> assetRules("urn:kilnmark:lab-db:crm.people", List.of(missing)));
        Ran r = run(g, people(20, 0), 10, true);
        assertTrue(r.result().succeeded(), r.result().error());
        QualityGate.RuleOutcome o = r.outcome().rules().get(0);
        assertEquals("error", o.status());
        assertEquals("asset", o.origin());
        assertTrue(o.message().contains("no column phone"), o.message());
        assertNull(r.outcome().score(), "nothing could be checked, so nothing is scored");
        assertEquals("urn:kilnmark:lab-db:crm.people", r.outcome().asset());
    }

    @Test
    void theAssetsRulesAndInlineRulesAreCheckedTogether() throws Exception {
        Rule known = new Rule(0, "urn:kilnmark:lab-db:crm.people", "country", "country known", RuleType.ALLOWED_VALUES,
            Dimension.VALIDITY, (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind
                .ObjectMapper().readTree("{\"values\": [\"GB\"]}"), "share", null, 0.5, Rule.Actions.NONE, true,
            "kilnmark", null, "steward", null, null);
        QualityGate g = new QualityGate(QualityGate.OnFail.QUARANTINE, null, null, GateRules.read(EMAIL_PRESENT, null),
            true, id -> assetRules("urn:kilnmark:lab-db:crm.people", List.of(known)));
        Ran r = run(g, people(30, 10), 8, true);
        assertTrue(r.result().succeeded(), r.result().error());
        // ids 3, 6, ... 30 are IE (10 rows); 10, 20, 30 have no email: 12 distinct rows fail one or both.
        assertEquals(12, r.outcome().rowsFailing());
        assertEquals(18, r.out().ids.size());
        assertEquals(List.of("asset", "inline"), r.outcome().rules().stream().map(QualityGate.RuleOutcome::origin)
            .toList());
        assertEquals("pass", r.outcome().rules().get(0).status(), "a third fail, within its 50% limit");
        Map<String, Double> dims = r.outcome().dimensions();
        assertEquals(2.0 / 3, dims.get("validity"), 1e-4);
        assertEquals(0.9, dims.get("completeness"), 1e-4);
        assertEquals((2.0 / 3 + 0.9) / 2, r.outcome().score(), 1e-3);
    }

    static QualityGate.AssetRules assetRules(String urn, List<Rule> rules) {
        return new QualityGate.AssetRules() {
            @Override
            public String urn() {
                return urn;
            }

            @Override
            public List<Rule> rules() {
                return rules;
            }

            @Override
            public Map<String, String> kinds() {
                return Map.of();
            }

            @Override
            public Long previousRows() {
                return null;
            }
        };
    }

    // ---- the rules written on a gate ----

    @Test
    void inlineRulesAreCheckedAsRulesInQualityAre() {
        assertEquals(List.of(), GateRules.read(null, null));
        assertEquals("not null", GateRules.read(List.of(rule("type", "not_null", "field", "email")), null).get(0).type()
            .id().replace('_', ' '));
        assertTrue(message(List.of(rule("type", "nonsense", "field", "x"))).startsWith("rules: rule 1: type "
            + "'nonsense' is not a check a gate runs"));
        assertTrue(message(List.of(rule("type", "custom_sql", "sql", "select 1"))).contains("run in the source's "
            + "database"));
        assertTrue(message(List.of(rule("type", "not_null", "field", "email", "colour", "red")))
            .contains("a rule of type not_null has no setting 'colour'"));
        assertTrue(message(List.of(rule("type", "matches_pattern", "field", "email", "regex", "(")))
            .startsWith("rules: rule 1: regex:"));
        assertTrue(message(List.of(rule("type", "in_range", "field", "id"))).contains("give min, max or both"));
        assertTrue(message(List.of(rule("type", "not_null", "field", "email", "fail_above", 2)))
            .contains("fail_above: a share between 0 and 1"));
        assertTrue(message(List.of(rule("type", "not_null", "field", "email"), rule("type", "not_null", "field",
            "email"))).contains("rule 2: another rule is called 'email not null'"));
        assertTrue(message(List.of("not a rule")).contains("must be a mapping"));
        assertTrue(message("text").startsWith("rules: a list of rules"));
        // With the arriving columns known, a column must arrive.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GateRules.read(
            List.of(rule("name", "has phone", "type", "not_null", "field", "phone")), Map.of("email", "text")));
        assertEquals("rules: rule 1 (has phone): field: no column arrives at the gate called phone", e.getMessage());
        // Freshness needs a date or time column, once the columns are known.
        assertTrue(GateRules.read(List.of(rule("type", "freshness", "field", "seen", "max_age_hours", 24)), null)
            .get(0).params().has("maxAgeHours"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GateRules.read(List.of(rule("type", "freshness",
            "field", "seen", "max_age_hours", 24)), Map.of("seen", "text"))).getMessage().contains("date or timestamp"));
    }

    private static String message(Object rules) {
        return assertThrows(IllegalArgumentException.class, () -> GateRules.read(rules, null)).getMessage();
    }

    // ---- the factory ----

    @Test
    void theFactoryChecksItsSettings() throws Exception {
        GateNodeFactory f = new GateNodeFactory(new FakeSources());
        assertEquals("Quality", f.category());
        assertEquals(List.of("in"), f.inputPorts());
        assertEquals(List.of("out", "quarantine"), f.outputPorts());
        assertEquals(List.of(), f.check(Map.of()));
        assertEquals(List.of(), f.check(Map.of("on_fail", "quarantine", "rules", EMAIL_PRESENT, "min_score",
            new BigDecimal("0.95"), "max_failing_share", 0)));
        assertTrue(f.check(Map.of("min_score", new BigDecimal("1.5"))).get(0).startsWith("min_score: a share from 0 "
            + "to 1"));
        assertTrue(f.check(Map.of("max_failing_share", -1)).get(0).startsWith("max_failing_share:"));
        assertTrue(f.check(Map.of("asset_rules", false)).get(0).startsWith("rules: the gate has nothing to check"));
        assertTrue(f.check(Map.of("rules", List.of(rule("type", "nope")))).get(0).startsWith("rules: rule 1:"));
        // Columns pass through unchanged on both ports; an inline rule's column must arrive.
        Schema s = f.outputSchema("quarantine", Map.of("in", PEOPLE), Map.of("rules", EMAIL_PRESENT));
        assertEquals(PEOPLE, s);
        assertNull(f.outputSchema("out", Map.of(), Map.of()));
        NodeException bad = assertThrows(NodeException.class, () -> f.outputSchema("out", Map.of("in", PEOPLE),
            Map.of("rules", List.of(rule("type", "not_null", "field", "phone")))));
        assertTrue(bad.getMessage().startsWith("rules: rule 1: field: no column arrives"), bad.getMessage());
        assertNotNull(f.create(Map.of("rules", EMAIL_PRESENT)));
    }

    @Test
    void aGateTakesTheRulesOfTheOneReadUpstream() {
        Map<String, String> types = Map.of("a", "read", "b", "read", "u", "union", "f", "filter", "g", "gate");
        List<Edge> one = List.of(new Edge(new PortRef("a", "out"), new PortRef("f", "in")),
            new Edge(new PortRef("f", "out"), new PortRef("g", "in")));
        assertEquals("a", GateNodeFactory.upstream("g", Map.of(), one, types).read());
        assertNull(GateNodeFactory.upstream("g", Map.of("asset_rules", false), one, types).read());
        List<Edge> two = List.of(new Edge(new PortRef("a", "out"), new PortRef("u", "in")),
            new Edge(new PortRef("b", "out"), new PortRef("u", "in")),
            new Edge(new PortRef("u", "out"), new PortRef("g", "in")));
        GateNodeFactory.Upstream both = GateNodeFactory.upstream("g", Map.of(), two, types);
        assertNull(both.read());
        assertNull(both.key(), "about the node, not a setting it may not have");
        assertTrue(both.problem().startsWith("asset_rules: rows reach this gate from the read nodes a, b; name the "
            + "one whose asset's rules to check with rules_from"),
            both.problem());
        assertEquals("b", GateNodeFactory.upstream("g", Map.of("rules_from", "b"), two, types).read());
        assertTrue(GateNodeFactory.upstream("g", Map.of("rules_from", "f"), one, types).problem()
            .startsWith("rules_from: 'f' is not a read node"));
    }

    // ---- what only the whole pipeline shows ----

    static Edge edge(String from, String to) {
        return new Edge(PortRef.parse(from, "out"), PortRef.parse(to, "in"));
    }

    static List<String> findings(Map<String, String> types, Map<String, Map<String, Object>> configs, Edge... edges) {
        return GateNodeFactory.graphChecks(types, configs, List.of(edges)).stream()
            .map(f -> (f.note() ? "note " : "problem ") + f.node() + "." + f.key() + ": " + f.message()).toList();
    }

    @Test
    void aWriteThatCommitsAsItGoesCannotFollowAStopGate() {
        Map<String, String> types = new LinkedHashMap<>(Map.of("r", "read", "g", "gate", "w", "write"));
        Map<String, Object> append = Map.of("mode", "append", "commit_every", 1000);
        List<String> f = findings(types, Map.of("g", Map.of(), "w", append), edge("r", "g"), edge("g", "w"));
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).startsWith("problem w.commit_every: commit_every: the rows come through the stop gate g, "
            + "which decides only at the end of the rows; committing every 1000 rows would write them before it does"),
            f.get(0));
        for (String mode : List.of("create", "upsert")) {
            assertEquals(1, findings(types, Map.of("w", Map.of("mode", mode, "commit_every", 5)), edge("r", "g"),
                edge("g", "w")).size(), mode);
        }
        // Replace swaps the table in at the end; no commit_every, or 0, commits once; quarantine and warn decide per row.
        assertEquals(List.of(), findings(types, Map.of("w", Map.of("mode", "replace", "commit_every", 5)), edge("r", "g"),
            edge("g", "w")));
        assertEquals(List.of(), findings(types, Map.of("w", Map.of("mode", "append", "commit_every", 0)), edge("r", "g"),
            edge("g", "w")));
        assertEquals(List.of(), findings(types, Map.of("g", Map.of("on_fail", "warn"), "w", append), edge("r", "g"),
            edge("g", "w")));
        // Further down, through other nodes, it still counts.
        types.put("f", "filter");
        assertEquals(1, findings(types, Map.of("w", append), edge("r", "g"), edge("g", "f"), edge("f", "w")).size());
    }

    @Test
    void aNodeEndingTheRowsEarlyCannotFollowAStopGate() {
        Map<String, String> types = Map.of("r", "read", "g", "gate", "l", "limit", "w", "write");
        List<String> f = findings(types, Map.of(), edge("r", "g"), edge("g", "l"), edge("l", "w"));
        assertEquals(List.of("problem l.null: a limit after the stop gate g ends the rows early, so the gate never sees "
            + "their end and cannot stop the run. Put the limit before the gate, or set the gate's on_fail to quarantine "
            + "or warn"), f);
        assertEquals(List.of(), findings(types, Map.of(), edge("r", "l"), edge("l", "g"), edge("g", "w")));
        assertEquals(List.of(), findings(types, Map.of("g", Map.of("on_fail", "warn")), edge("r", "g"), edge("g", "l"),
            edge("l", "w")));
    }

    @Test
    void aWriteGettingTheSameRowsAroundAStopGateIsNoted() {
        Map<String, String> types = Map.of("r", "read", "g", "gate", "good", "write", "raw", "write");
        List<String> f = findings(types, Map.of(), edge("r", "g"), edge("g", "good"), edge("r", "raw"));
        assertEquals(List.of("note g.on_fail: on_fail: the write raw gets the same rows by another path, not through "
            + "this gate, so it is written even when the gate stops the run"), f);
        assertEquals(List.of(), findings(types, Map.of("g", Map.of("on_fail", "warn")), edge("r", "g"),
            edge("g", "good"), edge("r", "raw")));
    }

    @Test
    void quarantineWiringIsNoted() {
        Map<String, String> types = Map.of("r", "read", "g", "gate", "good", "write", "aside", "write");
        assertEquals(List.of("note g.on_fail: on_fail: rows that fail a rule go to the quarantine port, but nothing is "
            + "connected to it, so they are dropped. Connect a write to quarantine to keep them"),
            findings(types, Map.of("g", Map.of("on_fail", "quarantine")), edge("r", "g"), edge("g", "good")));
        assertEquals(List.of(), findings(types, Map.of("g", Map.of("on_fail", "quarantine")), edge("r", "g"),
            edge("g", "good"), edge("g.quarantine", "aside")));
        for (String onFail : List.of("stop", "warn")) {
            List<String> f = findings(types, Map.of("g", Map.of("on_fail", onFail)), edge("r", "g"), edge("g", "good"),
                edge("g.quarantine", "aside"));
            assertEquals(1, f.size(), f.toString());
            assertTrue(f.get(0).startsWith("note g.on_fail: on_fail: " + onFail + " sends no rows to the quarantine "
                + "port, so aside gets none, and a write there in mode replace or create empties"), f.get(0));
        }
    }

    /** Reads one batch, then ends: as a limit does once it has its rows. */
    static final class EndsEarly implements Node {
        @Override
        public String type() {
            return "limit";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try {
                VectorSchemaRoot b = context.input().take(context.allocator());
                if (b != null) {
                    b.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
            return false;
        }
    }

    @Test
    void aGateWhoseRowsAreCutShortIsStillJudgedAndSaysSo() throws Exception {
        QualityGate gate = gate("stop", null, null, List.of(rule("type", "not_null", "field", "email", "fail_above",
            new BigDecimal("0.5"))));
        List<GraphNode> nodes = List.of(new GraphNode("people", new Source(people(10_000, 10), 100), Map.of()),
            new GraphNode("check", gate, Map.of()), new GraphNode("first", new EndsEarly(), Map.of()));
        List<Edge> edges = List.of(edge("people", "check"), edge("check", "first"));
        RunResult r = new KilnGraphRunner(64L << 20, 2).run(new PipelineGraph("cut", nodes, edges), e -> { });
        assertTrue(r.succeeded(), r.error());
        QualityGate.Outcome o = gate.outcome();
        assertNotNull(o, "judged when the node closed");
        assertFalse(o.complete());
        assertTrue(o.rowsIn() > 0 && o.rowsIn() < 10_000, String.valueOf(o.rowsIn()));
        assertEquals("warned", o.decision());
        assertNull(o.stopReason());
        assertTrue(RunQuality.gate("check", o).note().startsWith("The gate checked only the first "),
            RunQuality.gate("check", o).note());
    }

    @Test
    void anEmptyInputJudgesRowCountChangeOnlyForTheAssetsOwnRules() throws Exception {
        // Inline rules have no earlier scan to compare with, whether or not rows arrive.
        QualityGate g = new QualityGate(QualityGate.OnFail.STOP, null, null, GateRules.read(List.of(rule("type",
            "row_count_change", "max_change", new BigDecimal("0.1"))), null), true,
            id -> new QualityGate.AssetRules() {
                @Override
                public String urn() {
                    return "urn:kilnmark:lab-db:crm.people";
                }

                @Override
                public List<Rule> rules() {
                    return List.of();
                }

                @Override
                public Map<String, String> kinds() {
                    return Map.of();
                }

                @Override
                public Long previousRows() {
                    return 500L;
                }
            });
        Ran r = run(g, List.of(), 10, true);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("pass", r.outcome().rules().get(0).status());
        assertEquals("No earlier scan to compare with", r.outcome().rules().get(0).message());
    }

    @Test
    void freshnessFailsOnNoRows() throws Exception {
        Ran r = run(gate("warn", null, null, List.of(rule("type", "freshness", "field", "seen", "max_age_hours", 24))),
            List.of(), 10, true);
        assertEquals("fail", r.outcome().rules().get(0).status());
        assertEquals("No values at all", r.outcome().rules().get(0).message());
    }
}
