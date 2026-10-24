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

    @Test
    void profileInputsNeverShowTheirKey() {
        ModelProfile.Input in = new ModelProfile.Input("p", "openai", null, "m", "chat", null, null, false,
            "sk-very-secret-key-123456", null, null);
        assertFalse(in.toString().contains("sk-very-secret"), in.toString());
        ModelProfile.Valid v = ModelProfile.validate(in, false);
        assertFalse(v.toString().contains("sk-very-secret"), v.toString());
        assertTrue(v.toString().contains("[hidden]"));
    }

    private static io.smallrye.config.SmallRyeConfig config(String... pairs) {
        io.smallrye.config.SmallRyeConfigBuilder b = new io.smallrye.config.SmallRyeConfigBuilder()
            .addDefaultInterceptors().withMapping(io.kilnmark.server.KilnmarkConfig.class)
            .withConverter(java.time.Duration.class, 200, new io.quarkus.runtime.configuration.DurationConverter())
            .withDefaultValue("kilnmark.secret-key-file", "/tmp/unused.key");
        for (int i = 0; i < pairs.length; i += 2) {
            b.withDefaultValue(pairs[i], pairs[i + 1]);
        }
        return b.build();
    }

    @Test
    void anUnknownHostedModelsValueStopsTheServer() {
        assertThrows(RuntimeException.class, () -> config("kilnmark.ai.hosted-models", "sometimes"));
        assertEquals(io.kilnmark.server.KilnmarkConfig.Ai.HostedModels.FORBIDDEN,
            config("kilnmark.ai.hosted-models", "forbidden").getConfigMapping(io.kilnmark.server.KilnmarkConfig.class)
                .ai().hostedModels());
    }

    @Test
    void localHostsAreCheckedByNameAndNetwork() {
        io.kilnmark.server.KilnmarkConfig locked = config("kilnmark.ai.hosted-models", "forbidden")
            .getConfigMapping(io.kilnmark.server.KilnmarkConfig.class);
        ModelHosts hosts = new ModelHosts(locked);
        assertTrue(hosts.mayBeLocal("http://127.0.0.1:8080/v1"));
        assertTrue(hosts.mayBeLocal("http://10.1.2.3/v1"));
        assertTrue(hosts.mayBeLocal("http://192.168.0.9/v1"));
        assertTrue(hosts.mayBeLocal("http://[::1]:8080/v1"));
        assertTrue(hosts.mayBeLocal("http://localhost:8080/v1"));
        assertFalse(hosts.mayBeLocal("http://203.0.113.7/v1"));
        assertFalse(hosts.mayBeLocal("http://172.32.0.1/v1"), "just outside 172.16.0.0/12");
        assertTrue(hosts.mayBeLocal("http://172.31.255.255/v1"));
        assertFalse(hosts.mayBeLocal("http://no-such-host.invalid/v1"));
        ModelHosts narrow = new ModelHosts(config("kilnmark.ai.hosted-models", "forbidden",
            "kilnmark.ai.local-hosts", "10.20.0.0/16,ai").getConfigMapping(io.kilnmark.server.KilnmarkConfig.class));
        assertTrue(narrow.mayBeLocal("http://10.20.3.4/v1"));
        assertTrue(narrow.mayBeLocal("http://ai:8080/v1"));
        assertFalse(narrow.mayBeLocal("http://10.21.0.1/v1"));
        assertFalse(narrow.mayBeLocal("http://127.0.0.1/v1"));
        // Without the lock, the admin's word stands.
        assertTrue(new ModelHosts(config().getConfigMapping(io.kilnmark.server.KilnmarkConfig.class))
            .mayBeLocal("http://203.0.113.7/v1"));
    }

    @Test
    void metadataAddressesAreRefused() {
        ModelHosts hosts = new ModelHosts(config().getConfigMapping(io.kilnmark.server.KilnmarkConfig.class));
        assertTrue(hosts.forbidden("http://169.254.169.254/latest").isPresent());
        assertTrue(hosts.forbidden("http://metadata.google.internal/v1").isPresent());
        assertTrue(hosts.forbidden("http://[fe80::1]/v1").isPresent());
        assertFalse(hosts.forbidden("http://127.0.0.1:8080/v1").isPresent());
        assertFalse(new ModelHosts(config("kilnmark.ai.allow-metadata-addresses", "true")
            .getConfigMapping(io.kilnmark.server.KilnmarkConfig.class)).forbidden("http://169.254.169.254/").isPresent());
    }

    @Test
    void theMcpHostGuardKnowsLocalNames() {
        assertTrue(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local("localhost:8780"));
        assertTrue(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local("127.0.0.1"));
        assertTrue(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local("[::1]:8780"));
        assertFalse(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local("evil.example:8780"));
        assertFalse(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local("localhost.evil.example"));
        assertFalse(io.kilnmark.server.ai.mcp.McpHostGuardAccess.local(null));
    }

    @Test
    void anEmbeddingOfTheWrongShapeIsAFailure() {
        ModelProfile p = new ModelProfile(1, "e", Provider.OPENAI_COMPATIBLE, "http://h/v1", "m",
            AiFeature.Purpose.EMBEDDING, 3, null, true, false, null, 5, 1, 1, "t", null, null);
        assertNull(AiGateway.wrongShape(p, 1, new ModelClient.EmbedAnswer(List.of(new float[3]), null)));
        assertTrue(AiGateway.wrongShape(p, 1, new ModelClient.EmbedAnswer(List.of(new float[4]), null))
            .contains("Set the profile's dimensions to 4"));
        assertNotNull(AiGateway.wrongShape(p, 2, new ModelClient.EmbedAnswer(List.of(new float[3]), null)));
    }

    @Test
    void pgvectorVersionsAreCompared() {
        assertTrue(HybridSearch.atLeast("0.8.0", 0, 8));
        assertTrue(HybridSearch.atLeast("1.0", 0, 8));
        assertFalse(HybridSearch.atLeast("0.7.4", 0, 8));
        assertFalse(HybridSearch.atLeast("x", 0, 8));
    }

    @Test
    void wrongAiSettingsStopTheServer() {
        java.util.function.Function<String[], List<String>> problems = pairs -> AiConfigCheck.problems(
            config(pairs).getConfigMapping(io.kilnmark.server.KilnmarkConfig.class));
        assertTrue(problems.apply(new String[] {}).isEmpty());
        assertTrue(problems.apply(new String[] {"kilnmark.ai.local-hosts", "10.0.0.0/33"}).get(0).contains("10.0.0.0/33"));
        assertTrue(problems.apply(new String[] {"kilnmark.ai.local-hosts", "not a host!"}).get(0).contains("not a host!"));
        assertTrue(problems.apply(new String[] {"kilnmark.ai.usage-retention", "1h"}).get(0).contains("at least 1d"));
        assertTrue(problems.apply(new String[] {"kilnmark.ai.usage-retention", "1d"}).isEmpty());
    }

    @Test
    void anEmptyLocalHostsValueMeansTheDefaultNetworks() {
        ModelHosts hosts = new ModelHosts(config("kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts", "")
            .getConfigMapping(io.kilnmark.server.KilnmarkConfig.class));
        assertTrue(hosts.mayBeLocal("http://10.1.2.3/v1"));
        assertTrue(hosts.mayBeLocal("http://localhost:8080/v1"));
        // Under the lock, a name counts only when listed, whatever it resolves to.
        assertFalse(hosts.mayBeLocal("http://localhost.localdomain/v1"));
        ModelHosts listed = new ModelHosts(config("kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts",
            "kb-x.invalid").getConfigMapping(io.kilnmark.server.KilnmarkConfig.class));
        assertTrue(listed.mayBeLocal("http://kb-x.invalid/v1"), "listed: local without a lookup");
    }

    @Test
    void aKeyStaysWithItsSchemeHostAndPort() {
        assertEquals(ModelHosts.origin("http://h/v1"), ModelHosts.origin("http://H:80/other"));
        assertEquals(ModelHosts.origin("https://h/v1"), ModelHosts.origin("https://h:443/v1"));
        assertFalse(ModelHosts.origin("http://h/v1").equals(ModelHosts.origin("https://h/v1")));
        assertFalse(ModelHosts.origin("http://h:8080/v1").equals(ModelHosts.origin("http://h:8081/v1")));
    }

    @Test
    void assetTextFitsA512TokenContext() {
        ModelProfile small = new ModelProfile(1, "e", Provider.OPENAI_COMPATIBLE, "http://h/v1", "m",
            AiFeature.Purpose.EMBEDDING, 384, 512, true, false, null, 5, 1, 1, "t", null, null);
        assertEquals(768, AssetText.maxChars(small));
        assertEquals(768, AssetText.DEFAULT_MAX_CHARS);
    }
}
