package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.server.ai.AiException;
import io.kilnmark.server.ai.EgressPolicy;
import io.kilnmark.server.ai.ModelClient;

/**
 * Suggestions from the predicted metadata: Kilnmark's own with no model, each with its predicted
 * effect; the model's ranking and additions, checked so a step type or column that does not exist
 * is dropped; each source's AI rule honoured, so nothing it does not allow (and never a value) reaches
 * the model; and a model that fails leaves Kilnmark's own suggestions.
 */
class PipelineAdvisorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PAYROLL = "urn:kilnmark:hr:staff.payroll";

    /** A model client that records what it was sent and answers as told. */
    static final class Recording implements ModelClient {
        final List<ChatCall> calls = new ArrayList<>();
        String answer = "{\"ranking\": []}";
        RuntimeException fail;

        @Override
        public ChatAnswer chat(ChatCall call) {
            calls.add(call);
            if (fail != null) {
                throw fail;
            }
            return new ChatAnswer(answer, 10, 10);
        }

        @Override
        public EmbedAnswer embed(List<String> texts) {
            throw new UnsupportedOperationException();
        }
    }

    /** The model as suggestions use it, deciding each source's rule as the server's egress policy does. */
    static PipelineAdvisor.Model model(Recording client, boolean local) {
        return new PipelineAdvisor.Model() {
            @Override
            public String name() {
                return "test-model";
            }

            @Override
            public String refusal(Records.Source source) {
                return EgressPolicy.decide(local, "test-model", true, source.name(), EgressPolicy.SourceRule.of(source));
            }

            @Override
            public String chat(Records.Source source, ModelClient.ChatCall call) {
                String refused = refusal(source);
                if (refused != null) {
                    throw new AiException(AiException.Kind.REFUSED, refused);
                }
                return client.chat(call).text();
            }
        };
    }

    static Records.Source source(String name, String egress) {
        return new Records.Source(1, name, MemoryConnector.KIND, Map.of("ai_egress", egress), false, Instant.now(), name);
    }

    /** People from source lab, and a payroll asset from source hr. */
    static QualityPredictorTest.Fake catalogue() {
        QualityPredictorTest.Fake f = QualityPredictorTest.people();
        f.facts.put(PAYROLL, QualityPredictorTest.facts(PAYROLL, 500, List.of(),
            QualityPredictorTest.field("grade_code", "text", QualityPredictorTest.text(500, 50, 0, 400,
                Map.of("AA-99", 450L), Map.of()), Set.of())));
        return f;
    }

    static QualityPredictor.Plan plan() {
        return new QualityPredictorTest.P().read("people", QualityPredictorTest.PEOPLE, QualityPredictorTest.PEOPLE_COLUMNS)
            .read("payroll", PAYROLL, List.of(QualityPredictorTest.col("grade_code", "text")))
            .write("out", "people").write("pay", "payroll").plan();
    }

    static StepFactories factories(FakeSources sources) {
        return StepFactories.discover().register(new ReadStepFactory(sources)).register(new GateStepFactory(sources));
    }

    static FakeSources sources() {
        return new FakeSources().add("lab", Map.of(), null).add("hr", Map.of(), null);
    }

    static Function<String, Records.Source> egress(String lab, String hr) {
        return name -> name.equals("lab") ? source("lab", lab) : name.equals("hr") ? source("hr", hr) : null;
    }

    static PipelineAdvisor.AiStatus off() {
        return new PipelineAdvisor.AiStatus("off", "No model", null);
    }

    @Test
    void kilnmarksOwnSuggestionsComeWithTheirPredictedEffect() {
        QualityPredictorTest.Fake f = catalogue();
        f.links.put(QualityPredictorTest.PEOPLE, List.of(new QualityPredictor.Link("country",
            QualityPredictorTest.COUNTRIES, "code", 0.1, "declared", "accepted")));
        f.facts.put(QualityPredictorTest.COUNTRIES, QualityPredictorTest.withCountries(0.1).facts
            .get(QualityPredictorTest.COUNTRIES));
        f.orphans.put(QualityPredictorTest.PEOPLE + ".country>" + QualityPredictorTest.COUNTRIES + ".code", 0.1);
        PipelineAdvisor.Advice a = new PipelineAdvisor(f, factories(sources().add("lab", Map.of(), null)), JSON)
            .advise(plan(), null, off(), egress("allowed", "allowed"));
        assertEquals("off", a.ai().state());
        List<String> kinds = a.suggestions().stream().map(PipelineAdvisor.Suggestion::kind).toList();
        assertTrue(kinds.containsAll(List.of("trim", "standardise_dates", "repair_orphans", "quarantine_missing")),
            kinds.toString());
        for (PipelineAdvisor.Suggestion s : a.suggestions()) {
            assertEquals("rules", s.source());
            assertNotNull(s.effect(), s.id());
            assertTrue(s.effect().after() >= s.effect().before(), s.id() + " " + s.effect());
        }
        PipelineAdvisor.Suggestion trim = a.suggestions().stream().filter(s -> s.kind().equals("trim")).findFirst()
            .orElseThrow();
        assertEquals("compute", trim.steps().get(0).type());
        assertEquals(Map.of("name", "trim(name)"), trim.steps().get(0).settings().get("columns"));
        assertEquals(new PipelineAdvisor.Placement("out", "in", "people", "trim"), trim.placement());
        assertTrue(trim.effect().after() > trim.effect().before());
        PipelineAdvisor.Suggestion dates = a.suggestions().stream().filter(s -> s.kind().equals("standardise_dates"))
            .findFirst().orElseThrow();
        assertTrue(String.valueOf(dates.steps().get(0).settings()).contains("parse_date(joined, 'dd/MM/yyyy')"),
            dates.steps().get(0).settings().toString());
        PipelineAdvisor.Suggestion orphans = a.suggestions().stream().filter(s -> s.kind().equals("repair_orphans"))
            .findFirst().orElseThrow();
        assertEquals(List.of("read", "lookup"), orphans.steps().stream().map(PipelineAdvisor.NewStep::type).toList());
        assertEquals(Map.of("main", "people", "lookup", "read-countries"), orphans.steps().get(1).inputs());
        assertEquals(900L, orphans.effect().rowsAfter());
        assertTrue(orphans.effect().note().contains("100 rows with no match"), orphans.effect().note());
        // Best first: by predicted gain.
        double previous = Double.MAX_VALUE;
        for (PipelineAdvisor.Suggestion s : a.suggestions()) {
            double gain = s.effect().gain();
            assertTrue(gain <= previous + 1e-12, kinds.toString());
            previous = gain;
        }
    }

    @Test
    void theModelSeesOnlyWhatEachSourcesRuleAllowsAndNeverAValue() throws Exception {
        Recording client = new Recording();
        client.answer = "{\"ranking\": [\"dates-out-joined\"], \"explanations\": {\"dates-out-joined\": \"Two "
            + "formats confuse readers.\", \"nope\": \"x\"}}";
        PipelineAdvisor.Advice a = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, false), off(), egress("allowed", "none"));
        assertEquals("used", a.ai().state(), a.ai().message());
        assertTrue(a.ai().message().contains("not shown: hr"), a.ai().message());
        assertEquals(1, client.calls.size());
        String sent = client.calls.get(0).user();
        // Metadata of the allowed source: column names, kinds, shares and value shapes.
        assertTrue(sent.contains("\"email\"") && sent.contains("Aaaa Aaaa") && sent.contains("emptyShare"), sent);
        // Nothing of the source whose rule is none: not its asset, not its columns.
        assertFalse(sent.contains("payroll") || sent.contains("grade_code") || sent.contains("AA-99"), sent);
        // Never a value: the most common countries are known to Kilnmark but are not sent.
        assertFalse(sent.contains("\"GB\"") || sent.contains("\"IE\"") || sent.contains("\"XX\""), sent);
        assertNotNull(client.calls.get(0).jsonSchema());
        // The model's ranking and explanation are used; its explanation of an id it was not shown is not.
        assertEquals("dates-out-joined", a.suggestions().get(0).id());
        assertTrue(a.suggestions().get(0).why().endsWith("Two formats confuse readers."));
    }

    @Test
    void noModelCallWhenNoSourceAllowsIt() {
        Recording client = new Recording();
        PipelineAdvisor.Advice a = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, false), off(), egress("local_only", "none"));
        assertEquals("withheld", a.ai().state());
        assertTrue(client.calls.isEmpty(), "a hosted model may not see a local_only or none source");
        assertFalse(a.suggestions().isEmpty(), "Kilnmark's own suggestions still come");
        // A local model may see the local_only source.
        PipelineAdvisor.Advice local = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, true), off(), egress("local_only", "none"));
        assertEquals("used", local.ai().state());
        assertEquals(1, client.calls.size());
        assertFalse(client.calls.get(0).user().contains("grade_code"));
    }

    @Test
    void theModelsOwnSuggestionsAreCheckedAndHallucinationsDropped() {
        Recording client = new Recording();
        client.answer = """
            {"ranking": [],
             "additional": [
               {"before": "out", "type": "gate", "title": "Set aside rows without an email", "why": "Emails matter.",
                "settings": {"on_fail": "quarantine", "asset_rules": false, "rules": [{"type": "not_null", "field": "email"}]}},
               {"before": "out", "type": "compute", "title": "Trim a column that is not there",
                "settings": {"columns": {"zzz2": "trim(zzz)"}}},
               {"before": "out", "type": "enrich", "title": "A step type that does not exist", "settings": {}},
               {"before": "pay", "type": "gate", "title": "A column of a source it may not see",
                "settings": {"on_fail": "quarantine", "rules": [{"type": "not_null", "field": "grade_code"}]}},
               {"before": "nope", "type": "dedupe", "title": "Before a step that is not there", "settings": {"key": ["email"]}},
               {"before": "out", "type": "dedupe", "title": "A setting dedupe does not take",
                "settings": {"key": ["email"], "colour": "red"}},
               {"before": "out", "type": "filter", "title": "An expression on a missing column",
                "settings": {"condition": "ghost is not null"}}
             ]}""";
        PipelineAdvisor.Advice a = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, false), off(), egress("allowed", "none"));
        List<PipelineAdvisor.Suggestion> fromModel = a.suggestions().stream().filter(s -> s.source().equals("ai"))
            .toList();
        assertEquals(1, fromModel.size(), a.suggestions().toString());
        PipelineAdvisor.Suggestion gate = fromModel.get(0);
        assertEquals("gate", gate.steps().get(0).type());
        assertEquals("out", gate.placement().before());
        assertTrue(gate.effect().after() > gate.effect().before());
        assertTrue(a.ai().message().contains("added 1") && a.ai().message().contains("6 of its own were left out"),
            a.ai().message());
    }

    @Test
    void aModelThatFailsLeavesKilnmarksOwnSuggestions() {
        Recording client = new Recording();
        client.fail = new AiException(AiException.Kind.TIMEOUT, "slow");
        PipelineAdvisor.Advice a = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, false), off(), egress("allowed", "allowed"));
        assertEquals("unavailable", a.ai().state());
        assertTrue(a.ai().message().contains("did not answer in time"));
        assertFalse(a.suggestions().isEmpty());
        client.fail = null;
        client.answer = "not json at all";
        PipelineAdvisor.Advice unread = new PipelineAdvisor(catalogue(), factories(sources()), JSON)
            .advise(plan(), model(client, false), off(), egress("allowed", "allowed"));
        assertEquals("unavailable", unread.ai().state());
        assertTrue(unread.ai().message().contains("could not be read"));
        assertEquals(a.suggestions().size(), unread.suggestions().size());
    }

    @Test
    void datePatternsFromValueShapes() {
        assertEquals("yyyy-MM-dd", PipelineAdvisor.datePattern("9999-99-99"));
        assertEquals("dd/MM/yyyy", PipelineAdvisor.datePattern("99/99/9999"));
        assertEquals("d.M.yyyy", PipelineAdvisor.datePattern("9.9.9999"));
        assertNull(PipelineAdvisor.datePattern("99/99/99"), "which part is the year is not known");
        assertNull(PipelineAdvisor.datePattern("9999-99/99"));
    }
}
