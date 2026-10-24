package io.kilnmark.server.ai;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;

/**
 * Embeddings at scan, hybrid search, asking in plain English, the egress rule, and the MCP
 * server, against the fake model server.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiSearchTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    final FakeModelServer fake = FakeModelServer.get();
    String viewer;

    private void sql(String... statements) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private long count(String query) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void source(String name, String schema, String egress) throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("name", name, "kind", "postgresql", "password", password,
                "settings", Map.of("url", url, "username", user, "schemas", schema)))
            .post("/api/v1/sources").then().statusCode(201);
        // Stream A's source form writes ai_egress; here it is set directly.
        sql("UPDATE kilnmark.data_source SET settings = settings || jsonb_build_object('ai_egress', '" + egress
            + "') WHERE name = '" + name + "'");
    }

    private JsonPath scan(String source) throws InterruptedException {
        long id = admin().post("/api/v1/sources/" + source + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        for (int i = 0; i < 160; i++) {
            JsonPath run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            String status = run.getString("status");
            if (!status.equals("queued") && !status.equals("running") && run.getString("detail") != null
                && run.getString("detail").contains("Search by meaning")) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + id + " did not finish with an embedding note");
    }

    private void waitForBackground() throws InterruptedException {
        Thread.sleep(300);
        for (int i = 0; i < 120; i++) {
            if (!admin().get("/api/v1/ai/status").then().extract().jsonPath().getBoolean("embeddings.running")) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("background embedding did not finish");
    }

    private void profile(String name, String purpose, String model, boolean local, Integer dims) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("provider", "openai-compatible");
        m.put("baseUrl", fake.baseUrl());
        m.put("model", model);
        m.put("purpose", purpose);
        m.put("local", local);
        m.put("timeoutSeconds", 5);
        if (dims != null) {
            m.put("dimensions", dims);
        }
        admin().contentType(ContentType.JSON).body(m).post("/api/v1/ai/profiles").then().statusCode(201);
    }

    private void route(String feature, String profile) {
        Map<String, Object> body = new HashMap<>();
        body.put("profile", profile);
        admin().contentType(ContentType.JSON).body(body).put("/api/v1/ai/features/" + feature)
            .then().statusCode(200);
    }

    @Test
    @Order(1)
    void withoutAnEmbeddingModelAScanSaysSoAndSearchMatchesWords() throws Exception {
        fake.clear();
        viewer = admin().contentType(ContentType.JSON).body(Map.of("name", "ai-search-viewer", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().path("token");
        sql("CREATE SCHEMA aishop",
            "CREATE TABLE aishop.customers (customer_id integer PRIMARY KEY, email text, segment text)",
            "COMMENT ON TABLE aishop.customers IS 'People who buy from the shop'",
            "INSERT INTO aishop.customers SELECT n, 'b' || n || '@example.com', 'Retail' FROM generate_series(1, 50) n",
            "CREATE TABLE aishop.invoices (invoice_id integer PRIMARY KEY, cust_ref text, total numeric(10,2))",
            "INSERT INTO aishop.invoices SELECT n, n::text, n FROM generate_series(1, 20) n",
            "CREATE VIEW aishop.big_spenders AS SELECT * FROM aishop.invoices WHERE total > 10",
            "CREATE SCHEMA ailocked",
            "CREATE TABLE ailocked.clients (client_id integer, email text)",
            "CREATE SCHEMA ainone",
            "CREATE TABLE ainone.secret_buyers (buyer_id integer, email text)");
        source("ai-shop", "aishop", "allowed");
        JsonPath run = scan("ai-shop");
        assertTrue(run.getString("detail").contains("no embedding model is set"), run.getString("detail"));
        admin().queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200)
            .body("mode", equalTo("keyword"))
            .body("note", containsString("no embedding model"))
            .body("hits.urn", not(hasItem("urn:kilnmark:ai-shop:aishop.customers")));
        as(viewer).get("/api/v1/ai/status").then().body("searchByMeaning", equalTo(false));
    }

    @Test
    @Order(2)
    void aScanEmbedsEveryAssetWithTheEmbeddingModel() throws Exception {
        profile("s-embed", "embedding", "fake-embed-64", true, 64);
        fake.clear();
        // Choosing an embedding model embeds every source in the background.
        route("embed", "s-embed");
        waitForBackground();
        assertEquals(3, count("SELECT count(*) FROM kilnmark.asset_embedding e JOIN kilnmark.model_profile p "
            + "ON p.id = e.profile_id JOIN kilnmark.asset a ON a.urn = e.asset_urn JOIN kilnmark.data_source s "
            + "ON s.id = a.source_id WHERE p.name = 's-embed' AND s.name = 'ai-shop' AND e.dimensions = 64 "
            + "AND e.model = 'fake-embed-64'"));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE tablename = 'asset_embedding' AND indexname = "
            + "'asset_embedding_hnsw_p' || (SELECT id FROM kilnmark.model_profile WHERE name = 's-embed') || '_d64'"));
        // What was embedded: names, fields and types, never values.
        String sent = fake.seen().stream().filter(s -> s.path().endsWith("/embeddings")).map(FakeModelServer.Seen::body)
            .filter(b -> b.contains("aishop.customers")).findFirst().orElseThrow();
        assertTrue(sent.contains("customer_id (int4)") || sent.contains("customer_id (integer)"), sent);
        assertFalse(sent.contains("@example.com"), "no sample values are sent");

        // Nothing changed: nothing is embedded again.
        fake.clear();
        JsonPath run = scan("ai-shop");
        assertTrue(run.getString("detail").contains("0 assets embedded with s-embed (3 unchanged)"),
            run.getString("detail"));
        assertTrue(fake.seen().stream().noneMatch(s -> s.path().endsWith("/embeddings")));
        // A changed asset is embedded again.
        sql("COMMENT ON TABLE aishop.invoices IS 'Bills sent to customers'");
        run = scan("ai-shop");
        assertTrue(run.getString("detail").contains("1 asset embedded with s-embed (2 unchanged)"),
            run.getString("detail"));
        as(viewer).get("/api/v1/ai/status").then().body("searchByMeaning", equalTo(true))
            .body("embeddings.profile", equalTo("s-embed"));
    }

    @Test
    @Order(3)
    void hybridSearchFindsByMeaningAndFusesWithWords() {
        JsonPath r = as(viewer).queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200)
            .extract().jsonPath();
        assertEquals("hybrid", r.getString("mode"));
        assertEquals("urn:kilnmark:ai-shop:aishop.customers", r.getString("hits[0].urn"), r.prettify());
        assertEquals(List.of("meaning"), r.getList("hits[0].matchedBy"));
        // Found both ways ranks above found one way.
        r = as(viewer).queryParam("q", "customers").get("/api/v1/search").then().extract().jsonPath();
        assertEquals("urn:kilnmark:ai-shop:aishop.customers", r.getString("hits[0].urn"), r.prettify());
        assertEquals(List.of("words", "meaning"), r.getList("hits[0].matchedBy"));
        // Words only, on request.
        as(viewer).queryParam("q", "buyers").queryParam("mode", "keyword").get("/api/v1/search").then()
            .body("mode", equalTo("keyword"))
            .body("hits.urn", not(hasItem("urn:kilnmark:ai-shop:aishop.customers")));
        as(viewer).queryParam("q", "x").queryParam("mode", "psychic").get("/api/v1/search").then().statusCode(400);
    }

    @Test
    @Order(4)
    void theEgressRuleKeepsSourcesFromModelsTheyDoNotAllow() throws Exception {
        source("ai-locked", "ailocked", "local_only");
        source("ai-none", "ainone", "none");
        // A local model may see a local_only source, but none sees a source that allows no AI.
        assertTrue(scan("ai-locked").getString("detail").contains("1 asset embedded"));
        String none = scan("ai-none").getString("detail");
        assertTrue(none.contains("does not allow AI"), none);
        assertEquals(0, count("SELECT count(*) FROM kilnmark.asset_embedding e JOIN kilnmark.asset a ON a.urn = "
            + "e.asset_urn JOIN kilnmark.data_source s ON s.id = a.source_id WHERE s.name = 'ai-none'"));

        // A model not marked local may not see the local_only source; its embeddings are removed.
        profile("s-embed-hosted", "embedding", "fake-embed-64", false, 64);
        route("embed", "s-embed-hosted");
        waitForBackground();
        String locked = scan("ai-locked").getString("detail");
        assertTrue(locked.contains("allows only local models"), locked);
        assertEquals(0, count("SELECT count(*) FROM kilnmark.asset_embedding e JOIN kilnmark.model_profile p ON "
            + "p.id = e.profile_id JOIN kilnmark.asset a ON a.urn = e.asset_urn JOIN kilnmark.data_source s ON "
            + "s.id = a.source_id WHERE s.name = 'ai-locked' AND p.name = 's-embed-hosted'"));
        assertTrue(scan("ai-shop").getString("detail").contains("embedded with s-embed-hosted"));
        assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage WHERE outcome = 'refused' AND feature = 'embed' "
            + "AND source_name = 'ai-locked' AND profile_name = 's-embed-hosted'") >= 1);

        // Hosted models forbidden server-wide: search falls back to words and says why.
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "forbidden")).put("/api/v1/ai/settings")
            .then().statusCode(200);
        as(viewer).queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200)
            .body("mode", equalTo("keyword"))
            .body("note", containsString("only models marked local"));
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings")
            .then().statusCode(200);
        route("embed", "s-embed");
        waitForBackground();
    }

    @Test
    @Order(5)
    void askingInPlainEnglishShowsTheInterpretedSearch() {
        // No chat model: the question is searched as typed.
        as(viewer).contentType(ContentType.JSON).body(Map.of("question", "buyers")).post("/api/v1/search/ask")
            .then().statusCode(200)
            .body("plan", nullValue())
            .body("note", containsString("Searched as typed"))
            // People still find sources that allow no AI: the rule keeps them from models, not from people.
            .body("hits.urn", hasItem("urn:kilnmark:ai-shop:aishop.customers"))
            .body("hits.urn", hasItem("urn:kilnmark:ai-none:ainone.secret_buyers"));

        profile("s-chat", "chat", "fake-chat", true, null);
        route("search_nl", "s-chat");
        fake.clear();
        fake.chatReplies(q -> "```json\n{\"text\": \"buyers\", \"kinds\": [\"tables\", \"sculptures\"], "
            + "\"sources\": [\"AI-SHOP\", \"warehouse\"], \"fields\": [\"email\"], \"tags\": [\"pii\"]}\n```");
        JsonPath r = as(viewer).contentType(ContentType.JSON)
            .body(Map.of("question", "Which tables in ai-shop hold buyers' email?")).post("/api/v1/search/ask")
            .then().statusCode(200).extract().jsonPath();
        assertEquals("s-chat", r.getString("interpretedBy"));
        assertEquals("buyers", r.getString("plan.text"));
        assertEquals(List.of("table"), r.getList("plan.kinds"));
        assertEquals(List.of("ai-shop"), r.getList("plan.sources"));
        assertEquals(List.of("email"), r.getList("plan.fields"));
        List<String> ignored = r.getList("plan.ignored");
        assertTrue(ignored.stream().anyMatch(s -> s.contains("sculptures")), ignored::toString);
        assertTrue(ignored.stream().anyMatch(s -> s.contains("warehouse")), ignored::toString);
        assertTrue(ignored.stream().anyMatch(s -> s.contains("tags are not in the catalogue")), ignored::toString);
        assertEquals(List.of("urn:kilnmark:ai-shop:aishop.customers"), r.getList("hits.urn"), r.prettify());
        assertEquals(List.of("email"), r.getList("hits[0].matchedFields"));
        // The prompt names sources the model may see, never one that allows no AI.
        String prompt = fake.seen().stream().filter(s -> s.path().endsWith("/chat/completions")).findFirst()
            .orElseThrow().body();
        assertTrue(prompt.contains("ai-shop"), prompt);
        assertFalse(prompt.contains("ai-none"), prompt);
        assertTrue(prompt.contains("json_schema"), prompt);

        // Filters alone list what matches them.
        fake.chatReplies(q -> "{\"text\": \"\", \"kinds\": [\"view\"], \"sources\": [], \"fields\": [], \"tags\": []}");
        as(viewer).contentType(ContentType.JSON).body(Map.of("question", "show me every view"))
            .post("/api/v1/search/ask").then().statusCode(200)
            .body("plan.kinds", equalTo(List.of("view")))
            .body("hits.urn", hasItem("urn:kilnmark:ai-shop:aishop.big_spenders"))
            .body("hits.kind", not(hasItem("table")));

        // An answer that is not a plan, twice: searched as typed, with the reason.
        fake.clear();
        fake.chatReplies(q -> "I am not sure what you mean.");
        as(viewer).contentType(ContentType.JSON).body(Map.of("question", "buyers")).post("/api/v1/search/ask")
            .then().statusCode(200)
            .body("plan", nullValue())
            .body("note", containsString("could not be interpreted"))
            .body("hits.urn", hasItem("urn:kilnmark:ai-shop:aishop.customers"));
        assertEquals(2, fake.seen().stream().filter(s -> s.path().endsWith("/chat/completions")).count(),
            "retried once");
        as(viewer).contentType(ContentType.JSON).body(Map.of("question", " ")).post("/api/v1/search/ask")
            .then().statusCode(400);
        fake.clear();
    }

    /** One MCP JSON-RPC call over Streamable HTTP; answers come as JSON or as server-sent events. */
    private Response mcp(String token, String session, String body) {
        var req = RestAssured.given().contentType(ContentType.JSON).accept("application/json, text/event-stream")
            .body(body);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            req.header("Mcp-Session-Id", session);
        }
        return req.post("/mcp");
    }

    private static String payload(Response r) {
        String text = r.asString();
        if (!text.startsWith("{")) {
            StringBuilder b = new StringBuilder();
            for (String line : text.split("\n")) {
                if (line.startsWith("data:")) {
                    b.append(line.substring(5).strip());
                }
            }
            text = b.toString();
        }
        return text;
    }

    @Test
    @Order(6)
    void outsideAssistantsReadTheCatalogueThroughMcp() {
        String init = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {\"protocolVersion\": "
            + "\"2025-06-18\", \"capabilities\": {}, \"clientInfo\": {\"name\": \"test\", \"version\": \"1\"}}}";
        mcp(null, null, init).then().statusCode(401);
        mcp("km_not-a-real-token-at-all-0123456789abcdefghijklmnopq", null, init).then().statusCode(401);
        Response r = mcp(viewer, null, init);
        r.then().statusCode(200);
        String session = r.header("Mcp-Session-Id");
        assertTrue(payload(r).contains("kilnmark"), payload(r));
        mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}");

        String tools = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/list\"}"));
        for (String t : List.of("search_assets", "get_asset", "list_sources")) {
            assertTrue(tools.contains(t), tools);
        }
        String search = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"search_assets\", \"arguments\": {\"query\": \"buyers\"}}}"));
        assertTrue(search.contains("aishop.customers") || search.contains("urn:kilnmark:ai-shop:aishop.customers"),
            search);
        assertFalse(search.contains("secret_buyers"), "a source that allows no AI is hidden: " + search);

        String asset = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 4, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"get_asset\", \"arguments\": {\"urn\": \"urn:kilnmark:ai-shop:aishop.customers\"}}}"));
        assertTrue(asset.contains("customer_id") && asset.contains("distinct"), asset);
        String hidden = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 5, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"get_asset\", \"arguments\": {\"urn\": \"urn:kilnmark:ai-none:ainone.secret_buyers\"}}}"));
        assertTrue(hidden.contains("No asset"), hidden);
        String sources = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 6, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"list_sources\", \"arguments\": {}}}"));
        assertTrue(sources.contains("ai-shop") && sources.contains("ai-locked"), sources);
        assertFalse(sources.contains("ai-none"), sources);
        assertFalse(sources.contains(password) && !password.isBlank() && sources.contains("\"password"), sources);
    }

    @Test
    @Order(7)
    void usageIsRecordedPerFeatureAndCaller() throws Exception {
        assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage WHERE feature = 'embed' AND outcome = 'ok' "
            + "AND caller LIKE 'scan %' AND tokens_in > 0 AND source_name = 'ai-shop'") >= 1, "scan");
        assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage WHERE feature = 'embed' AND outcome = 'ok' "
            + "AND caller LIKE 'mcp: token %'") >= 1, callers());
        assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage WHERE feature = 'search_nl' AND outcome = 'ok' "
            + "AND tokens_out = 7 AND caller LIKE 'token %(ai-search-viewer)'") >= 1, callers());
        admin().get("/api/v1/ai/usage").then().statusCode(200)
            .body("totals.profile", hasItem("s-embed"))
            .body("totals.profile", hasItem("s-chat"));
    }

    private String callers() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT feature, outcome, caller FROM kilnmark.ai_usage")) {
            while (rs.next()) {
                b.append(rs.getString(1)).append('/').append(rs.getString(2)).append('/').append(rs.getString(3))
                    .append('\n');
            }
        }
        return b.toString();
    }

    @Test
    @Order(99)
    void tearDown() {
        for (String f : List.of("describe", "classify", "search_nl", "embed")) {
            admin().contentType(ContentType.JSON).body(Map.of()).put("/api/v1/ai/features/" + f);
        }
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings");
        fake.clear();
    }
}
