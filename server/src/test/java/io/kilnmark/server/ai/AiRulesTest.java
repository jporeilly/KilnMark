package io.kilnmark.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import io.kilnmark.server.ai.EgressPolicy.SourceRule;

/** The AI runtime's rules, without a server: egress, plan checking, fusion, asset text, profile checks, retries. */
class AiRulesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void egressAllowsOnlyWhatBothRulesAllow() {
        // No source: only the server-wide rule applies.
        assertNull(EgressPolicy.decide(false, "gpt", true, null, null));
        assertNotNull(EgressPolicy.decide(false, "gpt", false, null, null));
        assertNull(EgressPolicy.decide(true, "llama", false, null, null));
        // local_only, the default.
        assertNull(EgressPolicy.decide(true, "llama", true, "crm", SourceRule.LOCAL_ONLY));
        assertTrue(EgressPolicy.decide(false, "gpt", true, "crm", SourceRule.LOCAL_ONLY).contains("only local models"));
        // none refuses even local models.
        assertTrue(EgressPolicy.decide(true, "llama", true, "crm", SourceRule.NONE).contains("does not allow AI"));
        // allowed lets hosted models in, unless the server forbids them.
        assertNull(EgressPolicy.decide(false, "gpt", true, "crm", SourceRule.ALLOWED));
        assertNotNull(EgressPolicy.decide(false, "gpt", false, "crm", SourceRule.ALLOWED));
    }

    @Test
    void anAbsentOrUnknownSourceRuleIsLocalOnly() {
        assertEquals(SourceRule.LOCAL_ONLY, SourceRule.of((String) null));
        assertEquals(SourceRule.LOCAL_ONLY, SourceRule.of(""));
        assertEquals(SourceRule.LOCAL_ONLY, SourceRule.of("everything"));
        assertEquals(SourceRule.NONE, SourceRule.of(" NONE "));
        assertEquals(SourceRule.ALLOWED, SourceRule.of("allowed"));
    }

    @Test
    void aPlanKeepsOnlyWhatTheCatalogueSupports() throws Exception {
        JsonNode raw = json.readTree("""
            {"text": "customer\\u0007 emails", "kinds": ["Tables", "VIEW", "spreadsheet"], "sources": ["CRM", "nowhere"],
             "fields": ["email", "drop table x;"], "tags": ["pii"], "extra": "ignored"}""");
        QueryPlan p = QueryPlan.validate(raw, "q", List.of("table", "view"), List.of("crm", "billing"));
        assertEquals("customer emails", p.text());
        assertEquals(List.of("table", "view"), p.kinds());
        assertEquals(List.of("crm"), p.sources());
        assertEquals(List.of("email"), p.fields());
        assertEquals(4, p.ignored().size(), p.ignored().toString());
        assertTrue(p.ignored().stream().anyMatch(s -> s.contains("spreadsheet")));
        assertTrue(p.ignored().stream().anyMatch(s -> s.contains("tags are not in the catalogue")));
    }

    @Test
    void anEmptyPlanSearchesTheQuestionAndLongListsAreCut() throws Exception {
        QueryPlan p = QueryPlan.validate(json.readTree("{}"), "  where are the invoices?  ", List.of(), List.of());
        assertEquals("where are the invoices?", p.text());
        QueryPlan many = QueryPlan.validate(json.readTree("{\"text\": \"x\", \"fields\": [\"a\",\"b\",\"c\",\"d\",\"e\","
            + "\"f\",\"g\"]}"), "q", List.of(), List.of());
        assertEquals(5, many.fields().size());
        assertTrue(many.ignored().get(0).contains("2 more fields"));
        QueryPlan longText = QueryPlan.validate(json.readTree("{\"text\": \"" + "a".repeat(500) + "\"}"), "q",
            List.of(), List.of());
        assertEquals(QueryPlan.MAX_TEXT, longText.text().length());
    }

    @Test
    void aPlanIsFoundInsideFencesOrProse() {
        assertTrue(QueryPlan.parse(json, "```json\n{\"text\": \"a\"}\n```").isPresent());
        assertTrue(QueryPlan.parse(json, "Here you go: {\"text\": \"a\"} Hope it helps").isPresent());
        assertFalse(QueryPlan.parse(json, "no idea").isPresent());
        assertFalse(QueryPlan.parse(json, "{not json}").isPresent());
        assertFalse(QueryPlan.parse(json, null).isPresent());
    }

    @Test
    void fusionRanksAssetsFoundBothWaysFirst() {
        List<HybridSearch.Fused> f = HybridSearch.fuse(List.of("a", "b", "c"), List.of("c", "d", "a"));
        // a: 1/61 + 1/63, c: 1/63 + 1/61 (tie, a was ranked higher by words), b: 1/62, d: 1/62 (tie, words first).
        assertEquals(List.of("a", "c", "b", "d"), f.stream().map(HybridSearch.Fused::urn).toList());
        assertEquals(1.0 / 61 + 1.0 / 63, f.get(0).score(), 1e-12);
        assertEquals(List.of("x"), HybridSearch.fuse(List.of(), List.of("x")).stream()
            .map(HybridSearch.Fused::urn).toList());
        // A duplicate within one list counts once.
        assertEquals(1.0 / 61, HybridSearch.fuse(List.of("a", "a"), List.of()).get(0).score(), 1e-12);
    }

    @Test
    void fieldPatternsEscapeLikeCharacters() {
        assertEquals("%cust\\_ref%", HybridSearch.pattern("Cust_Ref"));
        assertEquals("%50\\%%", HybridSearch.pattern("50%"));
    }

    @Test
    void assetTextHasNamesTypesAndDescriptionsButNoValues() {
        String t = AssetText.of("lab", "Synthetic CRM", "table", "crm", "customerOrders", "One row per order",
            List.of(new AssetText.Field("order_id", "bigint", null), new AssetText.Field("email", "text",
                "Contact\naddress")), 1500);
        assertEquals("""
            table crm.customerOrders (customer orders)
            Source: lab, Synthetic CRM
            Description: One row per order
            Fields: order_id (bigint); email (text): Contact address""", t);
        assertEquals(20, AssetText.of("s", null, "table", "", "x", null, List.of(new AssetText.Field("a".repeat(100),
            "int", null)), 20).length());
        assertEquals("cust ref 2024", AssetText.words("cust_ref-2024"));
    }

    private static ModelProfile.Input input(String provider, String purpose, String url, Integer dims, Boolean local,
                                            String key) {
        return new ModelProfile.Input("p1", provider, url, "m", purpose, dims, null, local, key, null, null);
    }

    @Test
    void profilesAreCheckedBeforeTheyAreStored() {
        ModelProfile.Valid v = ModelProfile.validate(input("openai-compatible", "embedding", "http://llama:8080/v1/",
            384, true, null), false);
        assertEquals("http://llama:8080/v1", v.baseUrl());
        assertEquals(60, v.timeoutSeconds());
        assertEquals(2, v.maxConcurrent());
        // The provider's own address is used when none is given.
        assertNull(ModelProfile.validate(input("openai", "chat", null, null, false, "sk-x"), false).baseUrl());
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("openai-compatible", "embedding", "http://h/v1", null, true, null), false));
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("openai-compatible", "chat", "ftp://h/v1", null, true, null), false));
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("openai-compatible", "chat", "http://h/v1?key=1", null, true, null), false));
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("gemini", "chat", null, null, true, "k"), false));
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("openai", "chat", null, null, false, "line\nbreak"), false));
        // An update may leave the key out when one is stored.
        ModelProfile.validate(input("openai", "chat", null, null, false, null), true);
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.validate(
            input("openai", "chat", null, null, false, ""), true));
        assertNull(ModelProfile.hint("short-key"));
        assertEquals("wxyz", ModelProfile.hint("sk-abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void onlyPassingFailuresAreRetried() {
        assertTrue(AiGateway.retryable(new RateLimitException("slow down")));
        assertTrue(AiGateway.retryable(new HttpException(503, "busy")));
        assertTrue(AiGateway.retryable(new RuntimeException(new java.net.ConnectException("refused"))));
        assertFalse(AiGateway.retryable(new AuthenticationException("bad key")));
        assertFalse(AiGateway.retryable(new HttpException(400, "bad request")));
        assertFalse(AiGateway.retryable(new IllegalStateException("bug")));
    }

    @Test
    void vectorsAreWrittenInPgvectorsTextForm() {
        assertEquals("[1.0,-0.5,0]", Embeddings.literal(new float[] {1f, -0.5f, Float.NaN}));
        assertEquals("public.vector(768)", Embeddings.cast(768));
        assertEquals("public.halfvec(3072)", Embeddings.cast(3072));
    }
}
