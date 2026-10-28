package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.Dimension;
import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleType;

/**
 * Predicted quality, step by step (docs/guide/pipelines.md, "Predicted quality"): each step's rule
 * for carrying the catalogue's metadata through, what stays unknown, and that a write is scored
 * the way a run scores what it wrote. Synthetic profiles only; nothing is read.
 */
class QualityPredictorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PEOPLE = "urn:kilnmark:lab:crm.people";
    static final String COUNTRIES = "urn:kilnmark:lab:ref.countries";
    static final String ORDERS = "urn:kilnmark:lab:sales.orders";

    /** A catalogue held in maps. */
    static final class Fake implements QualityPredictor.Catalogue {
        final Map<String, ProfileFacts> facts = new HashMap<>();
        final Map<String, List<Rule>> rules = new HashMap<>();
        final Map<String, Map<Long, QualityPredictor.LastResult>> last = new HashMap<>();
        final Map<String, Double> orphans = new HashMap<>();
        final Map<String, List<QualityPredictor.Link>> links = new HashMap<>();
        final Map<String, List<String>> accepted = new HashMap<>();

        @Override
        public ProfileFacts facts(String urn) {
            return facts.get(urn);
        }

        @Override
        public List<Rule> rules(String urn) {
            return rules.getOrDefault(urn, List.of());
        }

        @Override
        public Map<Long, QualityPredictor.LastResult> lastResults(String urn) {
            return last.getOrDefault(urn, Map.of());
        }

        @Override
        public Double orphanShare(String fromUrn, String fromField, String toUrn, String toField) {
            return orphans.get(fromUrn + "." + fromField + ">" + toUrn + "." + toField);
        }

        @Override
        public List<String> acceptedRegexes(String urn, String field) {
            return accepted.getOrDefault(urn + "." + field, List.of());
        }

        @Override
        public List<QualityPredictor.Link> links(String urn) {
            return links.getOrDefault(urn, List.of());
        }
    }

    // ---- synthetic profiles ----

    static ObjectNode stats(long rows, long nulls) {
        return JSON.createObjectNode().put("rows", rows).put("nulls", nulls);
    }

    static ObjectNode text(long rows, long nulls, long blanks, long distinct, Map<String, Long> shapes,
                           Map<String, Long> formats) {
        ObjectNode s = stats(rows, nulls).put("blanks", blanks).put("distinct", distinct).put("distinct_exact", true);
        ArrayNode p = s.putArray("patterns");
        shapes.forEach((k, v) -> p.addObject().put("shape", k).put("count", v));
        s.put("patterns_collapsed", "false");
        ArrayNode f = s.putArray("formats");
        formats.forEach((k, v) -> f.addObject().put("format", k).put("count", v));
        return s;
    }

    static ObjectNode top(ObjectNode s, Map<String, Long> values) {
        ArrayNode t = s.putArray("top");
        values.forEach((k, v) -> t.addObject().put("value", k).put("count", v));
        return s;
    }

    static ObjectNode number(long rows, long nulls, long distinct, double[] quantiles) {
        ObjectNode s = stats(rows, nulls).put("distinct", distinct).put("distinct_exact", true).put("negatives", 0)
            .put("outliers", 0).put("fence_low", "0").put("fence_high", "1000");
        ArrayNode q = s.putArray("quantiles");
        for (double d : quantiles) {
            q.add(d);
        }
        return s;
    }

    static double[] linear(double from, double to) {
        double[] q = new double[21];
        for (int i = 0; i <= 20; i++) {
            q[i] = from + (to - from) * i / 20.0;
        }
        return q;
    }

    static ProfileFacts.FieldFacts field(String name, String kind, ObjectNode stats, Set<String> accepted) {
        return new ProfileFacts.FieldFacts(name, 1, kind, kind, true, stats, accepted);
    }

    static ProfileFacts facts(String urn, long rows, List<String> key, ProfileFacts.FieldFacts... fields) {
        return new ProfileFacts(1, urn, Instant.parse("2026-10-01T00:00:00Z"), rows, true, false, key, List.of(fields));
    }

    /** 1,000 synthetic people: id unique, email 10% null, name with 5% padded, country from a small domain. */
    static Fake people() {
        Fake f = new Fake();
        f.facts.put(PEOPLE, facts(PEOPLE, 1000, List.of("id"),
            field("id", "int64", number(1000, 0, 1000, linear(1, 1000)), Set.of()),
            field("email", "text", text(1000, 100, 0, 900, Map.of("aaaa@aaaa.aaa", 900L), Map.of("email", 900L)),
                Set.of()),
            field("name", "text", text(1000, 0, 0, 950, Map.of("Aaaa Aaaa", 950L, " Aaaa Aaaa", 50L),
                Map.of("padded", 50L)), Set.of()),
            field("country", "text", top(text(1000, 0, 0, 3, Map.of("AA", 1000L), Map.of()),
                Map.of("GB", 600L, "IE", 300L, "XX", 100L)), Set.of()),
            field("joined", "text", text(1000, 0, 0, 1000, Map.of("9999-99-99", 700L, "99/99/9999", 300L),
                Map.of("iso_date", 700L)), Set.of())));
        return f;
    }

    static final List<PipelineCompilerPort.Column> PEOPLE_COLUMNS = List.of(col("id", "int64"), col("email", "text"),
        col("name", "text"), col("country", "text"), col("joined", "text"));

    static PipelineCompilerPort.Column col(String name, String kind) {
        return new PipelineCompilerPort.Column(name, kind);
    }

    /** A plan, written as steps in order with each reading the one before unless {@code in} says otherwise. */
    static final class P {
        final List<QualityPredictor.PlanStep> steps = new ArrayList<>();
        final List<QualityPredictor.PlanEdge> edges = new ArrayList<>();
        final Map<String, String> reads = new LinkedHashMap<>();

        P read(String id, String urn, List<PipelineCompilerPort.Column> columns) {
            steps.add(new QualityPredictor.PlanStep(id, "read", Map.of(), Map.of("out", columns)));
            reads.put(id, urn);
            return this;
        }

        P step(String id, String type, Map<String, Object> config, String... inputs) {
            steps.add(new QualityPredictor.PlanStep(id, type, config, Map.of()));
            for (String in : inputs) {
                String[] parts = in.split(">");
                String from = parts[0];
                String toPort = parts.length > 1 ? parts[1] : "in";
                int dot = from.lastIndexOf('.');
                edges.add(new QualityPredictor.PlanEdge(dot < 0 ? from : from.substring(0, dot), dot < 0 ? "out"
                    : from.substring(dot + 1), id, toPort));
            }
            return this;
        }

        P write(String id, String from) {
            return step(id, "write", Map.of("source", "wh", "table", "out." + id, "mode", "replace"), from);
        }

        QualityPredictor.Plan plan() {
            return new QualityPredictor.Plan(steps, edges, reads);
        }
    }

    static QualityPredictor.Result predict(Fake f, P p) {
        return new QualityPredictor(f).predict(p.plan());
    }

    static Long rows(QualityPredictor.Result r, String step, String port) {
        return r.steps().stream().filter(s -> s.step().equals(step)).findFirst().orElseThrow().rows().get(port);
    }

    static double dim(QualityPredictor.Predicted p, Dimension d) {
        Double v = p.dimensions().get(d.id());
        assertNotNull(v, d.id() + " in " + p.dimensions());
        return v;
    }

    // ---- read and write ----

    @Test
    void aReadCarriesTheProfileAndAWriteIsScoredAsARunScoresIt() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS).write("out",
            "people"));
        QualityPredictor.Predicted out = r.output("out");
        assertEquals("output", out.role());
        assertEquals(1000L, out.rows());
        assertEquals(0.98, dim(out, Dimension.COMPLETENESS), 1e-3, "email is 10% null: the mean of 5 columns");
        assertTrue(dim(out, Dimension.CONSISTENCY) < 1, "padded names and two date formats");
        assertNotNull(out.overall());
        // The same profile measured after a run scores the same: prediction and measurement compare directly.
        ProfileFacts asWritten = people().facts.get(PEOPLE);
        RunQuality.Score measured = RunQuality.score(new ProfileFacts(0, "x", Instant.now(), 1000L, true, false, List.of(),
            asWritten.fields()));
        assertEquals(measured.dimensions().get("completeness"), out.dimensions().get("completeness"), 1e-3);
        assertEquals(measured.dimensions().get("consistency"), out.dimensions().get("consistency"), 1e-3);
        assertEquals(1000L, rows(r, "people", "out"));
    }

    @Test
    void anAssetWithNoProfileIsUnknownNotGuessed() {
        QualityPredictor.Result r = predict(new Fake(), new P().read("people", PEOPLE, PEOPLE_COLUMNS).write("out",
            "people"));
        QualityPredictor.Predicted out = r.output("out");
        assertNull(out.overall());
        assertTrue(out.dimensions().isEmpty());
        assertNull(out.rows());
        assertTrue(out.unknown().get(0).contains("scan the sources"), out.unknown().toString());
        assertTrue(out.unknown().toString().contains("has no profile yet"), out.unknown().toString());
    }

    @Test
    void aRowLimitTakesTheFirstRowsAsLikeTheRest() {
        P p = new P().read("people", PEOPLE, PEOPLE_COLUMNS);
        p.steps.set(0, new QualityPredictor.PlanStep("people", "read", Map.of("row_limit", 10L),
            Map.of("out", PEOPLE_COLUMNS)));
        QualityPredictor.Result r = predict(people(), p.write("out", "people"));
        assertEquals(10L, r.output("out").rows());
        assertTrue(r.output("out").assumptions().toString().contains("first 10 rows"));
    }

    // ---- filter ----

    @Test
    void aNotNullFilterDropsTheKnownShare() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("has-email", "filter", Map.of("condition", "email is not null"), "people")
            .write("out", "has-email").write("none", "has-email.rejected"));
        assertEquals(900L, rows(r, "has-email", "out"));
        assertEquals(100L, rows(r, "has-email", "rejected"));
        assertEquals(1.0, dim(r.output("out"), Dimension.COMPLETENESS), 1e-9);
        assertTrue(dim(r.output("none"), Dimension.COMPLETENESS) < 0.9, "every email in the rejected rows is null");
    }

    @Test
    void equalityUsesTheMostCommonValuesAndIsUnknownWithoutThem() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("uk", "filter", Map.of("condition", "country in ('GB', 'IE')"), "people").write("out", "uk"));
        assertEquals(900L, rows(r, "uk", "out"));
        QualityPredictor.Result unknown = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("named", "filter", Map.of("condition", "name = 'Ann Lee'"), "people").write("out", "named"));
        assertNull(rows(unknown, "named", "out"), "names are not among the most common values kept");
        assertNull(unknown.output("out").rows());
        assertTrue(unknown.output("out").unknown().stream().anyMatch(u -> u.contains("how many rows named keeps")),
            unknown.output("out").unknown().toString());
        assertNotNull(unknown.output("out").overall(), "the columns' shares are still known, as an assumption");
        assertTrue(unknown.output("out").assumptions().toString().contains("spread the same"));
    }

    @Test
    void comparisonsUseTheQuantilesAndConditionsCombine() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("early", "filter", Map.of("condition", "id <= 250 and email is not null"), "people")
            .step("either", "filter", Map.of("condition", "id > 900 or email is null"), "people")
            .step("odd", "filter", Map.of("condition", "upper(name) like 'A%'"), "people")
            .write("a", "early").write("b", "either").write("c", "odd"));
        assertEquals(Math.round(1000 * (249.0 / 999) * 0.9), rows(r, "early", "out"), 1);
        assertEquals(Math.round(1000 * (1 - (1 - 99.0 / 999 - 1e-12) * 0.9)), rows(r, "either", "out"), 2);
        assertNull(rows(r, "odd", "out"), "a condition it cannot read is unknown");
    }

    // ---- compute and select ----

    @Test
    void computeTrimsFillsAndParses() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("tidy", "compute", Map.of("columns", Map.of("name", "trim(name)", "email",
                "coalesce(email, 'unknown')", "joined", "parse_date(joined, 'yyyy-MM-dd')", "label",
                "case when id > 5 then name else 'x' end")), "people")
            .write("out", "tidy").write("raw", "people"));
        QualityPredictor.Predicted out = r.output("out");
        QualityPredictor.Predicted raw = r.output("raw");
        assertTrue(dim(out, Dimension.CONSISTENCY) > dim(raw, Dimension.CONSISTENCY), out.dimensions() + " vs "
            + raw.dimensions());
        assertTrue(out.unknown().toString().contains("label as tidy computes it"), out.unknown().toString());
        // joined: 30% in another shape become null; email: none left null.
        assertEquals((1 + 1 + 1 + 0.7 + 1) / 5, dim(out, Dimension.COMPLETENESS), 1e-3, out.dimensions().toString());
    }

    @Test
    void standardisingDatesAddsUpTheShapesEachPatternReads() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("dates", "compute", Map.of("columns", Map.of("joined", "coalesce(format_date(parse_date(joined, "
                + "'yyyy-MM-dd'), 'yyyy-MM-dd'), format_date(parse_date(joined, 'dd/MM/yyyy'), 'yyyy-MM-dd'))")),
                "people").write("out", "dates").write("raw", "people"));
        assertEquals(0.98, dim(r.output("out"), Dimension.COMPLETENESS), 1e-3,
            "every date is in one of the two patterns, so none becomes empty; email stays 10% null");
        assertTrue(dim(r.output("out"), Dimension.VALIDITY) > dim(r.output("raw"), Dimension.VALIDITY));
    }

    @Test
    void selectKeepsRenamesAndCasts() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("slim", "select", Map.of("keep", List.of("id", "email"), "rename", Map.of("email", "mail")), "people")
            .write("out", "slim"));
        assertEquals(0.95, dim(r.output("out"), Dimension.COMPLETENESS), 1e-3, "two columns, one 10% null");
    }

    // ---- lookup ----

    static Fake withCountries(Double orphans) {
        Fake f = people();
        f.facts.put(COUNTRIES, facts(COUNTRIES, 2, List.of("code"),
            field("code", "text", text(2, 0, 0, 2, Map.of("AA", 2L), Map.of()), Set.of()),
            field("label", "text", text(2, 0, 0, 2, Map.of("Aaaa", 2L), Map.of()), Set.of())));
        if (orphans != null) {
            f.orphans.put(PEOPLE + ".country>" + COUNTRIES + ".code", orphans);
        }
        return f;
    }

    static P lookup(String unmatched) {
        return new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .read("countries", COUNTRIES, List.of(col("code", "text"), col("label", "text")))
            .step("named", "lookup", Map.of("on", Map.of("country", "code"), "unmatched", unmatched), "people>main",
                "countries>lookup")
            .write("out", "named").write("aside", "named.rejected");
    }

    @Test
    void aLookupsOrphanRateComesFromTheStoredRelationship() {
        QualityPredictor.Result kept = predict(withCountries(0.1), lookup("keep"));
        assertEquals(1000L, rows(kept, "named", "out"));
        // label is null for the 10% with no match: the mean over six columns.
        assertEquals((5 * 1.0 - 0.1 + 0.9) / 6, dim(kept.output("out"), Dimension.COMPLETENESS), 1e-3);
        QualityPredictor.Result rejected = predict(withCountries(0.1), lookup("rejected"));
        assertEquals(900L, rows(rejected, "named", "out"));
        assertEquals(100L, rows(rejected, "named", "rejected"));
        QualityPredictor.Result unknown = predict(withCountries(null), lookup("drop"));
        assertNull(rows(unknown, "named", "out"));
        assertTrue(unknown.output("out").unknown().toString().contains("no relationship or sketches stored"),
            unknown.output("out").unknown().toString());
    }

    // ---- aggregate, union, dedupe, route, limit ----

    @Test
    void anAggregateMakesOneRowPerGroupAndItsGroupsAreUnique() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("by", "aggregate", Map.of("group_by", List.of("country"), "values", Map.of("n", "count(*)", "last",
                "max(id)", "odd", "case when count(*) > 1 then 1 end")), "people")
            .write("out", "by"));
        assertEquals(3L, rows(r, "by", "out"));
        assertTrue(r.output("out").unknown().toString().contains("odd as by works it out"));
        assertEquals(1.0, dim(r.output("out"), Dimension.COMPLETENESS), 1e-9);
    }

    @Test
    void aUnionWeightsItsInputsAndAMissingColumnIsNull() {
        Fake f = people();
        f.facts.put(ORDERS, facts(ORDERS, 1000, List.of(),
            field("email", "text", text(1000, 0, 0, 1000, Map.of("aaaa@aaaa.aaa", 1000L), Map.of("email", 1000L)),
                Set.of())));
        QualityPredictor.Result r = predict(f, new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .read("orders", ORDERS, List.of(col("email", "text")))
            .step("both", "union", Map.of(), "people", "orders").write("out", "both"));
        assertEquals(2000L, rows(r, "both", "out"));
        // email: 5% null over both; the four people-only columns are null in half the rows.
        assertEquals((0.95 + 4 * 0.5) / 5, dim(r.output("out"), Dimension.COMPLETENESS), 1e-3);
        assertTrue(r.output("out").assumptions().toString().contains("union may repeat"));
    }

    @Test
    void dedupeKeepsOneRowPerKeyRouteSplitsAndLimitCaps() {
        Fake f = people();
        QualityPredictor.Result r = predict(f, new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("one", "dedupe", Map.of("key", List.of("email")), "people")
            .step("whole", "dedupe", Map.of(), "people")
            .step("split", "route", Map.of("routes", new LinkedHashMap<>(Map.of("gb", "country = 'GB'"))), "people")
            .step("ten", "limit", Map.of("rows", 10L), "people")
            .write("a", "one").write("b", "whole").write("c", "split.gb").write("d", "split").write("e", "ten"));
        assertEquals(901L, rows(r, "one", "out"), "900 distinct emails and one row for the nulls");
        assertEquals(99L, rows(r, "one", "rejected"));
        assertEquals(1000L, rows(r, "whole", "out"), "id is unique, so whole rows never repeat");
        assertEquals(600L, rows(r, "split", "gb"));
        assertEquals(400L, rows(r, "split", "out"));
        assertEquals(10L, rows(r, "ten", "out"));
    }

    // ---- gate ----

    static Rule rule(long id, String field, RuleType type, Dimension d, ObjectNode params) {
        return new Rule(id, PEOPLE, field, field + " " + type.id(), type, d, params, "share", null, 0, Rule.Actions.NONE,
            true, "kilnmark", null, "test", Instant.now(), Instant.now());
    }

    @Test
    void aQuarantineGateRemovesThePredictedFailingShareFromOut() {
        Fake f = people();
        f.rules.put(PEOPLE, List.of(rule(1, "email", RuleType.NOT_NULL, Dimension.COMPLETENESS,
            JSON.createObjectNode())));
        QualityPredictor.Result r = predict(f, new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("check", "gate", Map.of("on_fail", "quarantine", "rules", List.of(Map.of("type", "allowed_values",
                "field", "country", "values", List.of("GB", "IE")))), "people")
            .write("good", "check").write("aside", "check.quarantine"));
        QualityPredictor.Predicted gate = r.output("check");
        assertEquals("gate", gate.role());
        assertEquals(PEOPLE, gate.asset());
        assertEquals(2, gate.gate().rules().size());
        assertEquals(0.1, gate.gate().rules().get(0).failingShare(), 1e-9);
        assertEquals("profile", gate.gate().rules().get(0).basis());
        assertEquals(0.1, gate.gate().rules().get(1).failingShare(), 1e-9);
        assertEquals("quarantined", gate.gate().decision());
        assertEquals(810L, gate.gate().rowsPassed(), "independent failures: 1000 x 0.9 x 0.9");
        assertEquals(190L, gate.gate().rowsQuarantined());
        assertEquals(0.9, gate.overall(), 1e-3);
        assertEquals(810L, rows(r, "check", "out"));
        assertEquals(1.0, r.output("good").dimensions().get("completeness") * 5 - 4, 1e-3,
            "the rows passing a not-null rule have an email");
    }

    @Test
    void aRuleThatCannotBeToldIsUnknownAndALastResultIsUsedForAnUnchangedColumn() {
        Fake f = people();
        ObjectNode regex = JSON.createObjectNode().put("regex", "^[a-z]+@[a-z]+\\.[a-z]+$");
        f.rules.put(PEOPLE, List.of(rule(7, "name", RuleType.UNIQUE, Dimension.UNIQUENESS, JSON.createObjectNode()),
            rule(8, "id", RuleType.IN_RANGE, Dimension.ACCURACY, JSON.createObjectNode().put("max", 500))));
        f.last.put(PEOPLE, Map.of(7L, new QualityPredictor.LastResult(30, 1000, Rule.Status.FAIL)));
        QualityPredictor.Result r = predict(f, new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("check", "gate", Map.of("on_fail", "stop", "rules", List.of(
                Map.of("type", "matches_pattern", "field", "email", "regex", regex.get("regex").asText()),
                Map.of("type", "allowed_values", "field", "name", "values", List.of("x")))), "people")
            .write("good", "check"));
        List<QualityPredictor.PredictedRule> rules = r.output("check").gate().rules();
        assertEquals("profile", rules.get(0).basis(), "name's distinct count tells");
        assertEquals(0.05, rules.get(0).failingShare(), 1e-9);
        assertEquals(0.5, rules.get(1).failingShare(), 0.01, "quantiles: half the ids are over 500");
        assertEquals(0.0, rules.get(2).failingShare(), 1e-9, "the shape aaaa@aaaa.aaa matches");
        assertEquals("value shapes", rules.get(2).basis());
        assertNull(rules.get(3).failingShare(), "names are not among the most common values");
        assertEquals("error", rules.get(3).status());
        assertTrue(r.output("check").unknown().toString().contains("how many rows fail rule"),
            r.output("check").unknown().toString());
        assertEquals("stopped", r.output("check").gate().decision(), "a rule predicted to fail stops the run");
        assertEquals(1000L, rows(r, "check", "out"));
        assertEquals(0L, rows(r, "check", "quarantine"));

        // Without a profile, the asset's own rule on an unchanged column falls back to its last result.
        Fake bare = new Fake();
        bare.facts.put(PEOPLE, facts(PEOPLE, 1000, List.of(), field("name", "text", null, Set.of())));
        bare.rules.put(PEOPLE, List.of(rule(7, "name", RuleType.UNIQUE, Dimension.UNIQUENESS, JSON.createObjectNode())));
        bare.last.put(PEOPLE, Map.of(7L, new QualityPredictor.LastResult(30, 1000, Rule.Status.FAIL)));
        QualityPredictor.Result fallback = predict(bare, new P().read("people", PEOPLE, List.of(col("name", "text")))
            .step("check", "gate", Map.of("on_fail", "warn"), "people").write("good", "check"));
        QualityPredictor.PredictedRule last = fallback.output("check").gate().rules().get(0);
        assertEquals("its last result on the asset", last.basis());
        assertEquals(0.03, last.failingShare(), 1e-9);
        assertEquals("warned", fallback.output("check").gate().decision());
    }

    @Test
    void aGateWithNothingToCheckSaysSo() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("check", "gate", Map.of(), "people").write("good", "check"));
        assertNull(r.output("check").overall());
        assertTrue(r.output("check").unknown().get(0).contains("no enabled rules"), r.output("check").unknown().toString());
    }

    @Test
    void anUnknownStepTypeIsUnknownDownstream() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("magic", "ai-enrich", Map.of(), "people").write("out", "magic"));
        assertNull(r.output("out").overall());
        assertTrue(r.output("out").unknown().toString().contains("what magic sends on out"), r.output("out").unknown()
            .toString());
    }

    // ---- helpers ----

    @Test
    void quantilesAndDatePatterns() {
        double[] q = linear(0, 100);
        assertEquals(0.5, QualityPredictor.below(q, 50, true), 1e-9);
        assertEquals(0, QualityPredictor.below(q, -1, true), 1e-9);
        assertEquals(1, QualityPredictor.below(q, 100, true), 1e-9);
        assertTrue(QualityPredictor.patternShape("dd/MM/yyyy", false).matcher("99/99/9999").matches());
        assertTrue(QualityPredictor.patternShape("d/M/yyyy", false).matcher("9/99/9999").matches());
        assertFalse(QualityPredictor.patternShape("yyyy-MM-dd", false).matcher("99/99/9999").matches());
        assertTrue(QualityPredictor.patternShape("yyyy-MM-dd", true).matcher("9+-9+-9+").matches());
        assertEquals("9999-99-99", QualityPredictor.formatShape("yyyy-MM-dd"));
        assertNull(QualityPredictor.formatShape("d MMM yyyy"));
    }

    // ---- review fixes ----

    @Test
    void valuesAreWithheldFromCallersWhoMayNotSeeThem() {
        ProfileFacts all = people().facts.get(PEOPLE);
        P probe = new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("uk", "filter", Map.of("condition", "country in ('GB', 'IE')"), "people")
            .step("low", "filter", Map.of("condition", "id <= 250"), "people")
            .write("a", "uk").write("b", "low");
        // A role that may not see data: neither most common values nor the spread are used.
        Fake viewer = people();
        viewer.facts.put(PEOPLE, PredictionCatalogue.withheld(all, false, Set.of(), false));
        QualityPredictor.Result v = predict(viewer, probe);
        assertNull(rows(v, "uk", "out"), "membership of a value cannot be probed");
        assertNull(rows(v, "low", "out"), "quantiles cannot be probed with a bound");
        assertTrue(v.output("a").unknown().toString().contains("how many rows uk keeps"));
        // A steward: a column marked sensitive is withheld, the rest is used.
        Fake steward = people();
        steward.facts.put(PEOPLE, PredictionCatalogue.withheld(all, true, Set.of("country"), false));
        QualityPredictor.Result st = predict(steward, probe);
        assertNull(rows(st, "uk", "out"));
        assertEquals(Math.round(1000 * 249.0 / 999), rows(st, "low", "out"));
        // An admin sees all.
        assertEquals(PredictionCatalogue.withheld(all, true, Set.of("country"), true), all);
        assertEquals(900L, rows(predict(people(), probe), "uk", "out"));
    }

    @Test
    void quantileTiesCountEveryEqualValue() {
        double[] q = new double[21];
        for (int i = 11; i <= 20; i++) {
            q[i] = i - 10;
        }
        assertEquals(0.5, QualityPredictor.below(q, 0, true), 1e-9, "x <= 0 holds for the first eleven quantiles");
        assertEquals(0.0, QualityPredictor.below(q, 0, false), 1e-9);
        assertEquals(0.55, QualityPredictor.below(q, 1, true), 1e-9);
        assertEquals(0.55, QualityPredictor.below(q, 1, false), 1e-9, "between the 50% quantile (0) and the 55% one (1)");
    }

    static Fake scored() {
        Fake f = people();
        f.facts.put(ORDERS, facts(ORDERS, 1000, List.of(),
            field("score", "float64", number(1000, 200, 800, linear(0, 100)), Set.of()),
            field("n", "int64", top(number(1000, 0, 3, linear(10, 12)), Map.of("10", 500L, "11", 300L, "12", 200L)),
                Set.of())));
        return f;
    }

    @Test
    void notLeavesOutTheRowsWhereTheTestIsNull() {
        QualityPredictor.Result r = predict(scored(), new P().read("orders", ORDERS, List.of(col("score", "float64"),
                col("n", "int64")))
            .step("low", "filter", Map.of("condition", "not (score > 50)"), "orders")
            .step("filled", "filter", Map.of("condition", "not (score is null)"), "orders")
            .write("a", "low").write("b", "filled"));
        assertEquals(400L, rows(r, "low", "out"), "80% filled, half of them at most 50; the 20% empty are not kept");
        assertEquals(600L, rows(r, "low", "rejected"));
        assertEquals(800L, rows(r, "filled", "out"));
    }

    @Test
    void boundsOnOneColumnMakeARangeAndOtherConditionsSayTheyAreTakenAsIndependent() {
        QualityPredictor.Result r = predict(people(), new P().read("people", PEOPLE, PEOPLE_COLUMNS)
            .step("band", "filter", Map.of("condition", "id > 100 and id <= 300"), "people")
            .step("both", "filter", Map.of("condition", "id <= 500 and email is not null"), "people")
            .write("a", "band").write("b", "both"));
        assertEquals(200L, rows(r, "band", "out"), "one range on id, not two shares multiplied");
        assertFalse(r.output("a").assumptions().toString().contains("independent"), r.output("a").assumptions().toString());
        assertTrue(r.output("b").assumptions().toString().contains("both: conditions joined with and or or are taken "
            + "as independent"), r.output("b").assumptions().toString());
    }

    @Test
    void aPatternNamingLettersOrDigitsIsNotJudgedOnShapes() {
        QualityPredictor.Col country = QualityPredictor.fromFacts(people().facts.get(PEOPLE).field("country"), "text");
        assertNull(QualityPredictor.shapeShare(country, "^GB\\d*$"), "GB is two particular letters");
        assertNull(QualityPredictor.shapeShare(country, "^[A-M]+$"), "it depends on which letters");
        assertEquals(1.0, QualityPredictor.shapeShare(country, "^[A-Z]{2}$"), 1e-9);
        assertTrue(QualityPredictor.literalLettersOrDigits("^GB\\d+"));
        assertFalse(QualityPredictor.literalLettersOrDigits("^[A-Z]{2}\\d[A-Z]? \\d[A-Z]{2}$"));
    }

    @Test
    void aNumberWrittenAnotherWayIsUnknownNotZero() {
        QualityPredictor.Result r = predict(scored(), new P().read("orders", ORDERS, List.of(col("score", "float64"),
                col("n", "int64")))
            .step("ten", "filter", Map.of("condition", "n = 10"), "orders")
            .step("odd", "filter", Map.of("condition", "n = 10.50"), "orders")
            .write("a", "ten").write("b", "odd"));
        assertEquals(500L, rows(r, "ten", "out"));
        assertNull(rows(r, "odd", "out"), "no match among the common values of a number column is no answer");
    }

    @Test
    void anAggregatesEmptyGroupStaysUnknownWhenItsRowsAre() {
        Fake f = people();
        f.facts.put(ORDERS, new ProfileFacts(1, ORDERS, Instant.now(), null, null, false, List.of(),
            List.of(field("score", "float64", number(1000, 200, 800, linear(0, 100)), Set.of()))));
        QualityPredictor.Result r = predict(f, new P().read("orders", ORDERS, List.of(col("score", "float64")))
            .step("by", "aggregate", Map.of("group_by", List.of("score")), "orders").write("a", "by"));
        assertNull(rows(r, "by", "out"));
        assertFalse(r.output("a").dimensions().containsKey("completeness"), r.output("a").dimensions().toString());
    }
}
