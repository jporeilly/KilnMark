package io.kilnmark.server.ai;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
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

    @Inject
    Embeddings embeddings;

    @Inject
    ModelProfiles profiles;

    @Inject
    AiGateway gateway;

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
        // Narrowed to one source by its name.
        as(viewer).queryParam("q", "customers").queryParam("mode", "keyword").queryParam("source", "ai-shop")
            .get("/api/v1/search").then().statusCode(200)
            .body("hits.urn", hasItem("urn:kilnmark:ai-shop:aishop.customers"))
            .body("hits.source", everyItem(equalTo("ai-shop")));
        as(viewer).queryParam("q", "customers").queryParam("mode", "keyword").queryParam("source", "no-such-source")
            .get("/api/v1/search").then().statusCode(200).body("hits", empty());
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
            .body("note", containsString("Only local models may be used"));
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
        // The older SSE transport is refused, even with a valid token.
        RestAssured.given().header("Authorization", "Bearer " + viewer).accept("text/event-stream").get("/mcp/sse")
            .then().statusCode(403);
        RestAssured.given().header("Authorization", "Bearer " + viewer).contentType(ContentType.JSON).body("{}")
            .post("/mcp/messages/x").then().statusCode(403);

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
        // By default an assistant sees only sources whose AI rule is allowed.
        assertTrue(sources.contains("ai-shop"), sources);
        assertFalse(sources.contains("ai-locked"), sources);
        assertFalse(sources.contains("ai-none"), sources);
        String lockedAsset = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 7, \"method\": "
            + "\"tools/call\", \"params\": {\"name\": \"get_asset\", \"arguments\": {\"urn\": "
            + "\"urn:kilnmark:ai-locked:ailocked.clients\"}}}"));
        assertTrue(lockedAsset.contains("No asset"), lockedAsset);

        // A token an admin flags as used by a local assistant also sees local_only sources, never none.
        long tokenId = as(viewer).get("/api/v1/me").then().extract().jsonPath().getLong("tokenId");
        as(viewer).contentType(ContentType.JSON).body(Map.of("localAssistant", true))
            .put("/api/v1/ai/assistant-tokens/" + tokenId).then().statusCode(403);
        admin().contentType(ContentType.JSON).body(Map.of("localAssistant", true))
            .put("/api/v1/ai/assistant-tokens/" + tokenId).then().statusCode(200);
        admin().get("/api/v1/ai/assistant-tokens").then().statusCode(200)
            .body("tokenIds", hasItem((int) tokenId));
        sources = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 8, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"list_sources\", \"arguments\": {}}}"));
        assertTrue(sources.contains("ai-locked"), sources);
        assertFalse(sources.contains("ai-none"), sources);
        String tooLong = payload(mcp(viewer, session, "{\"jsonrpc\": \"2.0\", \"id\": 9, \"method\": \"tools/call\", "
            + "\"params\": {\"name\": \"search_assets\", \"arguments\": {\"query\": \"" + "x".repeat(501) + "\"}}}"));
        assertTrue(tooLong.contains("500 characters"), tooLong);
        admin().contentType(ContentType.JSON).body(Map.of("localAssistant", false))
            .put("/api/v1/ai/assistant-tokens/" + tokenId).then().statusCode(200);
        admin().contentType(ContentType.JSON).body(Map.of("localAssistant", true))
            .put("/api/v1/ai/assistant-tokens/999999").then().statusCode(404);
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
    @Order(8)
    void viewersNeverSeeAModelsAddressOrReply() throws Exception {
        profile("s-embed-gone", "embedding", "fake-embed-64", true, 64);
        Map<String, Object> gone = new HashMap<>();
        gone.put("name", "s-embed-gone");
        gone.put("provider", "openai-compatible");
        gone.put("baseUrl", "http://127.0.0.1:1/v1");
        gone.put("model", "fake-embed-64");
        gone.put("purpose", "embedding");
        gone.put("dimensions", 64);
        gone.put("local", true);
        gone.put("timeoutSeconds", 5);
        admin().contentType(ContentType.JSON).body(gone).put("/api/v1/ai/profiles/s-embed-gone").then().statusCode(200);
        route("embed", "s-embed-gone");
        waitForBackground();

        long start = System.currentTimeMillis();
        String note = as(viewer).queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200)
            .body("mode", equalTo("keyword")).extract().path("note");
        assertTrue(note.contains("An admin can see why under AI usage"), note);
        assertFalse(note.contains("127.0.0.1") || note.contains("Cannot reach") || note.contains("fake-embed"), note);
        // The model failed moments ago, so the next search does not wait for it again.
        start = System.currentTimeMillis();
        as(viewer).queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200).body("mode", equalTo("keyword"));
        assertTrue(System.currentTimeMillis() - start < 1500, "skipped the model that is down");

        String detail = scan("ai-shop").getString("detail");
        assertFalse(detail.contains("127.0.0.1") || detail.contains("Cannot reach"), detail);
        assertTrue(detail.contains("An admin can see why under AI usage"), detail);
        // Admins see the reason in the usage records.
        List<String> details = admin().get("/api/v1/ai/usage").then().extract().jsonPath().getList("recent.detail");
        assertTrue(details.stream().anyMatch(d -> d != null && d.contains("Cannot reach http://127.0.0.1:1/v1")),
            details::toString);
        assertTrue(details.stream().anyMatch(d -> d != null && d.contains("not tried again yet")), details::toString);
        route("embed", "s-embed");
        waitForBackground();
    }

    @Test
    @Order(9)
    void vectorsOfAnotherSizeNeverBreakSearch() throws Exception {
        // A row of another size under the profile, as an edit during a scan could once leave behind.
        String v32 = Embeddings.literal(FakeModelServer.vector("buyers", 32));
        sql("INSERT INTO kilnmark.asset_embedding (profile_id, asset_urn, model, dimensions, text_hash, embedding) "
            + "SELECT id, 'urn:kilnmark:ai-none:ainone.secret_buyers', 'old', 32, '\\x00', '" + v32 + "' "
            + "FROM kilnmark.model_profile WHERE name = 's-embed'");
        JsonPath r = as(viewer).queryParam("q", "buyers").get("/api/v1/search").then().statusCode(200)
            .extract().jsonPath();
        assertEquals("hybrid", r.getString("mode"), r.prettify());
        assertTrue(r.getList("hits.findAll { it.matchedBy.contains('meaning') }.urn")
            .contains("urn:kilnmark:ai-shop:aishop.customers"), r.prettify());
        assertFalse(r.getList("hits.findAll { it.matchedBy.contains('meaning') }.urn")
            .contains("urn:kilnmark:ai-none:ainone.secret_buyers"), "the row of another size is never compared");
        sql("DELETE FROM kilnmark.asset_embedding WHERE dimensions = 32");

        // Vectors made before the profile's model changed are not saved; a timeout edit does not matter.
        ModelProfile p = profiles.find("s-embed").orElseThrow();
        ModelProfile stale = new ModelProfile(p.id(), p.name(), p.provider(), p.baseUrl(), "an-older-model",
            p.purpose(), p.dimensions(), p.contextSize(), p.local(), p.hasKey(), p.keyHint(), p.timeoutSeconds(),
            p.maxConcurrent(), p.version() - 1, p.createdBy(), p.createdAt(), p.updatedAt());
        ModelProfile retimed = new ModelProfile(p.id(), p.name(), p.provider(), p.baseUrl(), p.model(), p.purpose(),
            p.dimensions(), p.contextSize(), p.local(), p.hasKey(), p.keyHint(), p.timeoutSeconds() + 7,
            p.maxConcurrent(), p.version() - 1, p.createdBy(), p.createdAt(), p.updatedAt());
        assertTrue(Embeddings.sameVectors(p, retimed));
        assertFalse(Embeddings.sameVectors(p, stale));
        assertFalse(embeddings.saveIfCurrent(stale, List.of("urn:kilnmark:ai-none:ainone.secret_buyers"),
            List.of(new byte[] {1}), List.of(FakeModelServer.vector("x", 64))));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.asset_embedding WHERE asset_urn = "
            + "'urn:kilnmark:ai-none:ainone.secret_buyers'"));
        assertTrue(embeddings.saveIfCurrent(retimed, List.of("urn:kilnmark:ai-none:ainone.secret_buyers"),
            List.of(new byte[] {1}), List.of(FakeModelServer.vector("x", 64))));
        sql("DELETE FROM kilnmark.asset_embedding WHERE asset_urn = 'urn:kilnmark:ai-none:ainone.secret_buyers'");
    }

    @Test
    @Order(10)
    void reEmbedRequestsAreCoalescedNotDropped() throws Exception {
        waitForBackground();
        // Something to embed, so the first pass calls the (slowed) model and is still running.
        sql("DELETE FROM kilnmark.asset_embedding e USING kilnmark.asset a WHERE a.urn = e.asset_urn "
            + "AND a.urn LIKE 'urn:kilnmark:ai-shop:%'");
        int before = embeddings.passes();
        fake.delay(600);
        try {
            assertTrue(embeddings.reembedAllInBackground("test one"));
            Thread.sleep(150);
            assertFalse(embeddings.reembedAllInBackground("test two"), "joins the running one");
            waitForBackground();
        } finally {
            fake.delay(0);
        }
        assertTrue(embeddings.passes() >= before + 2, "the second request made a second pass");
        // The search's query shape (numbers in the SQL, same cast) can use the profile's partial index.
        long id = profiles.find("s-embed").orElseThrow().id();
        String v = Embeddings.literal(FakeModelServer.vector("buyers", 64));
        StringBuilder plan = new StringBuilder();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET LOCAL enable_seqscan = off");
            try (ResultSet rs = st.executeQuery("EXPLAIN SELECT e.asset_urn FROM kilnmark.asset_embedding e WHERE "
                + "e.profile_id = " + id + " AND e.dimensions = 64 ORDER BY e.embedding::public.vector(64) <=> '" + v
                + "'::public.vector(64) LIMIT 100")) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
            c.rollback();
        }
        assertTrue(plan.toString().contains("asset_embedding_hnsw_p" + id + "_d64"), plan.toString());
        assertEquals(1, count("SELECT count(*) FROM pg_index i JOIN pg_class x ON x.oid = i.indexrelid "
            + "WHERE x.relname = 'asset_embedding_hnsw_p' || (SELECT id FROM kilnmark.model_profile "
            + "WHERE name = 's-embed') || '_d64' AND i.indisvalid"));
    }

    @Test
    @Order(105)
    void anIndexAnotherProcessIsBuildingIsLeftAlone() throws Exception {
        ModelProfile p = profiles.find("s-embed").orElseThrow();
        String index = Embeddings.indexName(p);
        String valid = "SELECT count(*) FROM pg_index i JOIN pg_class x ON x.oid = i.indexrelid WHERE x.relname = '"
            + index + "' AND i.indisvalid";
        String exists = "SELECT count(*) FROM pg_class WHERE relname = '" + index + "'";
        assertEquals(1, count(valid));
        // What another process's CREATE INDEX CONCURRENTLY looks like while it runs: an invalid index,
        // and that process holding the profile's index lock.
        sql("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'kilnmark." + index + "'::regclass");
        try (Connection other = db.getConnection(); Statement st = other.createStatement()) {
            other.setAutoCommit(true);
            st.execute("SELECT pg_advisory_lock(" + Embeddings.INDEX_LOCK_CLASS + ", " + p.id() + ")");
            embeddings.ensureIndex(p);
            assertEquals(1, count(exists), "not dropped while another process holds the lock");
            assertEquals(0, count(valid));
            st.execute("SELECT pg_advisory_unlock(" + Embeddings.INDEX_LOCK_CLASS + ", " + p.id() + ")");
        }
        // Nobody building it: an invalid index is a failed build, dropped and built again.
        embeddings.ensureIndex(p);
        assertEquals(1, count(valid));
    }

    @Test
    @Order(11)
    void aHostedChatModelNeverHearsOfLocalOnlySources() {
        profile("s-chat-hosted", "chat", "fake-chat", false, null);
        route("search_nl", "s-chat-hosted");
        fake.clear();
        fake.chatReplies(q -> "{\"text\": \"clients\", \"kinds\": [], \"sources\": [], \"fields\": [], \"tags\": []}");
        as(viewer).contentType(ContentType.JSON).body(Map.of("question", "where are clients kept?"))
            .post("/api/v1/search/ask").then().statusCode(200).body("interpretedBy", equalTo("s-chat-hosted"));
        String prompt = fake.seen().stream().filter(x -> x.path().endsWith("/chat/completions")).findFirst()
            .orElseThrow().body();
        assertTrue(prompt.contains("ai-shop"), prompt);
        assertFalse(prompt.contains("ai-locked"), "a local_only source is not named to a hosted model: " + prompt);
        assertFalse(prompt.contains("ai-none"), prompt);
        route("search_nl", "s-chat");
        fake.clear();
    }

    @Test
    @Order(12)
    void slowBusyAndCancelledCallsEndClearly() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "s-slow");
        m.put("provider", "openai-compatible");
        m.put("baseUrl", fake.baseUrl());
        m.put("model", "fake-chat");
        m.put("purpose", "chat");
        m.put("local", true);
        m.put("timeoutSeconds", 1);
        m.put("maxConcurrent", 1);
        admin().contentType(ContentType.JSON).body(m).post("/api/v1/ai/profiles").then().statusCode(201);
        ModelClient.ChatCall call = new ModelClient.ChatCall("Say ok.", "ok?", null, 5);
        fake.delay(2500);
        try {
            ModelProfile slow = profiles.find("s-slow").orElseThrow();
            AiException t = org.junit.jupiter.api.Assertions.assertThrows(AiException.class, () -> gateway.chatWith(slow,
                "test", null, call, "test", () -> false, AiGateway.Budget.BACKGROUND));
            assertEquals(AiException.Kind.TIMEOUT, t.kind(), t.getMessage());

            m.put("timeoutSeconds", 10);
            admin().contentType(ContentType.JSON).body(m).put("/api/v1/ai/profiles/s-slow").then().statusCode(200);
            ModelProfile patient = profiles.find("s-slow").orElseThrow();
            Thread first = Thread.ofVirtual().start(() -> {
                try {
                    gateway.chatWith(patient, "test", null, call, "test", () -> false, AiGateway.Budget.BACKGROUND);
                } catch (AiException e) {
                    // not the point here
                }
            });
            Thread.sleep(300);
            long start = System.currentTimeMillis();
            AiException busy = org.junit.jupiter.api.Assertions.assertThrows(AiException.class, () -> gateway.chatWith(
                patient, "test", null, call, "test", () -> false, AiGateway.Budget.INTERACTIVE));
            assertEquals(AiException.Kind.BUSY, busy.kind(), busy.getMessage());
            assertTrue(System.currentTimeMillis() - start < 2600, "a search waits about 2 seconds for a slot");
            first.join();

            java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cancel.set(true);
            });
            start = System.currentTimeMillis();
            AiException cancelled = org.junit.jupiter.api.Assertions.assertThrows(AiException.class, () ->
                gateway.chatWith(patient, "test", null, call, "test", cancel::get, AiGateway.Budget.BACKGROUND));
            assertEquals(AiException.Kind.CANCELLED, cancelled.kind());
            assertTrue(System.currentTimeMillis() - start < 1500, "stopped at once");
        } finally {
            fake.delay(0);
        }
        for (String outcome : List.of("timeout", "busy", "cancelled")) {
            assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage WHERE profile_name = 's-slow' AND outcome = '"
                + outcome + "'") >= 1, outcome);
        }
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
