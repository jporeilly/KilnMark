package io.kilnmark.server.ai;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/** Model profiles through the REST API: admin only, keys kept secret, tests in plain words, routing, egress. */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiProfilesTest {

    static final String KEY = "sk-test-secret-key-abcdefghijklmnop-6789";

    @Inject
    AgroalDataSource db;

    final FakeModelServer fake = FakeModelServer.get();
    String viewer;

    /** Every log record written while these tests run, to check no API key is ever logged. */
    final List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.logging.Handler capture = new java.util.logging.Handler() {
        @Override
        public void publish(java.util.logging.LogRecord r) {
            StringBuilder b = new StringBuilder(String.valueOf(r.getMessage()));
            if (r.getParameters() != null) {
                for (Object o : r.getParameters()) {
                    b.append(' ').append(o);
                }
            }
            for (Throwable t = r.getThrown(); t != null; t = t.getCause()) {
                b.append(' ').append(t);
            }
            logged.add(b.toString());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @Inject
    UsageRetention retention;

    @BeforeAll
    void setUp() {
        fake.clear();
        capture.setLevel(java.util.logging.Level.ALL);
        java.util.logging.Logger.getLogger("").addHandler(capture);
        // Everything Kilnmark and LangChain4j log, debug included, goes past the capture.
        for (String name : List.of("io.kilnmark", "dev.langchain4j", "io.quarkiverse.mcp")) {
            java.util.logging.Logger.getLogger(name).setLevel(java.util.logging.Level.ALL);
        }
    }

    @Test
    @Order(97)
    void oldUsageRecordsAreDeleted() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO kilnmark.ai_usage (at, feature, outcome, caller) "
                + "VALUES (now() - interval '400 days', 'embed', 'ok', 'old')");
        }
        assertTrue(retention.purge() >= 1);
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM kilnmark.ai_usage WHERE caller = 'old'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @Order(98)
    void theApiKeyNeverReachesTheLog() {
        // Make the key's profile fail in every way once more, with the capture in place.
        fake.failNext(401, 1);
        admin().post("/api/v1/ai/profiles/t-chat/test");
        fake.failNext(500, 3);
        admin().post("/api/v1/ai/profiles/t-chat/test");
        java.util.logging.Logger.getLogger("").removeHandler(capture);
        for (String name : List.of("io.kilnmark", "dev.langchain4j", "io.quarkiverse.mcp")) {
            java.util.logging.Logger.getLogger(name).setLevel(null);
        }
        assertFalse(logged.isEmpty(), "the capture saw the server's log");
        assertTrue(logged.stream().noneMatch(l -> l.contains(KEY)), "the key was logged");
        assertTrue(logged.stream().noneMatch(l -> l.contains(KEY.substring(0, 20))), "part of the key was logged");
    }

    /** Last: leaves no feature routed, so other tests see the server as it starts. */
    @Test
    @Order(99)
    void tearDown() {
        for (String f : List.of("describe", "classify", "search_nl", "embed")) {
            admin().contentType(ContentType.JSON).body(Map.of()).put("/api/v1/ai/features/" + f);
        }
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings");
        fake.clear();
    }

    private Map<String, Object> profile(String name, String provider, String purpose, String model, boolean local) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("provider", provider);
        m.put("baseUrl", fake.baseUrl());
        m.put("model", model);
        m.put("purpose", purpose);
        m.put("local", local);
        m.put("timeoutSeconds", 5);
        return m;
    }

    @Test
    @Order(1)
    void onlyAdminsManageModels() {
        viewer = admin().contentType(ContentType.JSON).body(Map.of("name", "ai-profiles-viewer", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().path("token");
        as(viewer).get("/api/v1/ai/profiles").then().statusCode(403);
        as(viewer).get("/api/v1/ai/usage").then().statusCode(403);
        as(viewer).contentType(ContentType.JSON).body(profile("nope", "openai-compatible", "chat", "m", true))
            .post("/api/v1/ai/profiles").then().statusCode(403);
        io.restassured.RestAssured.given().get("/api/v1/ai/profiles").then().statusCode(401);
        // Viewers may see which search features are on, without model details.
        as(viewer).get("/api/v1/ai/status").then().statusCode(200).body("$", hasKey("searchByMeaning"));
    }

    @Test
    @Order(2)
    void anApiKeyIsStoredEncryptedAndNeverReturned() throws Exception {
        Map<String, Object> p = profile("t-chat", "openai-compatible", "chat", "fake-chat", true);
        p.put("apiKey", KEY);
        String created = admin().contentType(ContentType.JSON).body(p).post("/api/v1/ai/profiles")
            .then().statusCode(201)
            .body("hasApiKey", equalTo(true))
            .body("apiKeyHint", equalTo("6789"))
            .body("$", not(hasKey("apiKey")))
            .body("local", equalTo(true))
            .extract().asString();
        assertFalse(created.contains(KEY));
        String listed = admin().get("/api/v1/ai/profiles").then().statusCode(200).extract().asString();
        assertFalse(listed.contains(KEY));
        assertTrue(listed.contains("t-chat"));
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT api_key FROM kilnmark.model_profile WHERE name = 't-chat'")) {
            assertTrue(rs.next());
            assertFalse(new String(rs.getBytes(1), StandardCharsets.ISO_8859_1).contains(KEY));
        }
    }

    @Test
    @Order(3)
    void testingAProfileSaysInPlainWordsWhetherItWorks() {
        fake.clear();
        admin().post("/api/v1/ai/profiles/t-chat/test").then().statusCode(200)
            .body("ok", equalTo(true))
            .body("message", containsString("answered"));
        FakeModelServer.Seen req = fake.seen().getLast();
        assertEquals("Bearer " + KEY, req.header("Authorization"), "the key reaches the model server");
        assertTrue(req.body().contains("json_schema"), "the answer's JSON schema is sent: " + req.body());

        // A server that fails once is retried.
        fake.failNext(503, 1);
        admin().post("/api/v1/ai/profiles/t-chat/test").then().statusCode(200).body("ok", equalTo(true));

        // A server that is not there.
        Map<String, Object> gone = profile("t-gone", "openai-compatible", "chat", "x", true);
        gone.put("baseUrl", "http://127.0.0.1:1/v1");
        admin().contentType(ContentType.JSON).body(gone).post("/api/v1/ai/profiles").then().statusCode(201);
        admin().post("/api/v1/ai/profiles/t-gone/test").then().statusCode(200)
            .body("ok", equalTo(false))
            .body("message", containsString("Cannot reach http://127.0.0.1:1/v1"));

        // A key the server refuses.
        fake.failNext(401, 1);
        admin().post("/api/v1/ai/profiles/t-chat/test").then().statusCode(200)
            .body("ok", equalTo(false))
            .body("message", containsString("refused the API key"))
            .body("message", not(containsString(KEY)));
    }

    @Test
    @Order(4)
    void anEmbeddingProfileWithTheWrongSizeSaysWhatToSet() {
        Map<String, Object> p = profile("t-embed", "openai-compatible", "embedding", "fake-embed-64", true);
        p.put("dimensions", 32);
        admin().contentType(ContentType.JSON).body(p).post("/api/v1/ai/profiles").then().statusCode(201);
        admin().post("/api/v1/ai/profiles/t-embed/test").then().statusCode(200)
            .body("ok", equalTo(false))
            .body("message", containsString("Set the profile's dimensions to 64"));
        p.put("dimensions", 64);
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-embed").then().statusCode(200);
        admin().post("/api/v1/ai/profiles/t-embed/test").then().statusCode(200).body("ok", equalTo(true));
    }

    @Test
    @Order(5)
    void badProfilesAreRefusedWithTheReason() {
        Map<String, Object> noUrl = profile("t-bad", "openai-compatible", "chat", "m", true);
        noUrl.remove("baseUrl");
        admin().contentType(ContentType.JSON).body(noUrl).post("/api/v1/ai/profiles").then().statusCode(400)
            .body("error", containsString("baseUrl"));
        Map<String, Object> hostedLocal = profile("t-bad", "anthropic", "chat", "m", true);
        hostedLocal.put("apiKey", KEY);
        admin().contentType(ContentType.JSON).body(hostedLocal).post("/api/v1/ai/profiles").then().statusCode(400)
            .body("error", containsString("local"));
        Map<String, Object> embedAnthropic = profile("t-bad", "anthropic", "embedding", "m", false);
        embedAnthropic.put("apiKey", KEY);
        embedAnthropic.put("dimensions", 8);
        admin().contentType(ContentType.JSON).body(embedAnthropic).post("/api/v1/ai/profiles").then().statusCode(400)
            .body("error", containsString("no embedding models"));
        Map<String, Object> noKey = profile("t-bad", "openai", "chat", "gpt", false);
        admin().contentType(ContentType.JSON).body(noKey).post("/api/v1/ai/profiles").then().statusCode(400)
            .body("error", containsString("apiKey"));
        Map<String, Object> userInfo = profile("t-bad", "openai-compatible", "chat", "m", true);
        userInfo.put("baseUrl", "http://user:pw@127.0.0.1:1/v1");
        admin().contentType(ContentType.JSON).body(userInfo).post("/api/v1/ai/profiles").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(profile("t-chat", "openai-compatible", "chat", "m", true))
            .post("/api/v1/ai/profiles").then().statusCode(409);
        admin().get("/api/v1/ai/profiles/nothing").then().statusCode(404);
        // Cloud metadata services are not model servers.
        for (String url : List.of("http://169.254.169.254/v1", "http://metadata.google.internal/v1",
            "http://[fe80::1]:8080/v1")) {
            Map<String, Object> meta = profile("t-bad", "openai-compatible", "chat", "m", false);
            meta.put("baseUrl", url);
            admin().contentType(ContentType.JSON).body(meta).post("/api/v1/ai/profiles").then().statusCode(400)
                .body("error", containsString("metadata"));
        }
    }

    @Test
    @Order(9)
    void aStoredKeyIsOnlySentWhereItWasEnteredFor() {
        Map<String, Object> p = profile("t-moved", "openai-compatible", "chat", "fake-chat", false);
        p.put("apiKey", KEY);
        admin().contentType(ContentType.JSON).body(p).post("/api/v1/ai/profiles").then().statusCode(201);
        // Same host, another path: the key stays.
        p.remove("apiKey");
        p.put("baseUrl", fake.baseUrl() + "/");
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-moved").then().statusCode(200)
            .body("hasApiKey", equalTo(true));
        // Another port, or another scheme: the key must be entered again.
        p.put("baseUrl", "http://127.0.0.1:1/v1");
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-moved").then().statusCode(400)
            .body("error", containsString("enter the API key again"));
        p.put("baseUrl", fake.baseUrl().replace("http://", "https://"));
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-moved").then().statusCode(400);
        // Another host: the key must be entered again.
        p.put("baseUrl", "http://localhost:1/v1");
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-moved").then().statusCode(400)
            .body("error", containsString("enter the API key again"));
        p.put("apiKey", "");
        admin().contentType(ContentType.JSON).body(p).put("/api/v1/ai/profiles/t-moved").then().statusCode(200)
            .body("hasApiKey", equalTo(false));
        admin().delete("/api/v1/ai/profiles/t-moved").then().statusCode(204);
    }

    @Test
    @Order(6)
    void featuresAreRoutedToProfilesOfTheRightKind() {
        admin().contentType(ContentType.JSON).body(Map.of("profile", "t-embed")).put("/api/v1/ai/features/search_nl")
            .then().statusCode(400).body("error", containsString("needs a chat model"));
        admin().contentType(ContentType.JSON).body(Map.of("profile", "t-chat")).put("/api/v1/ai/features/search_nl")
            .then().statusCode(200).body("profile", equalTo("t-chat"));
        admin().contentType(ContentType.JSON).body(Map.of("profile", "t-chat")).put("/api/v1/ai/features/teleport")
            .then().statusCode(400);
        admin().get("/api/v1/ai/features").then().statusCode(200)
            .body("find { it.id == 'search_nl' }.profile", equalTo("t-chat"))
            .body("find { it.id == 'describe' }.profile", nullValue());
        admin().get("/api/v1/ai/profiles/t-chat").then().body("usedBy", hasItem("search_nl"));
        // In use: cannot be deleted or change purpose.
        admin().delete("/api/v1/ai/profiles/t-chat").then().statusCode(409).body("error", containsString("search_nl"));
        // An update without a key keeps the stored one.
        admin().contentType(ContentType.JSON).body(profile("t-chat", "openai-compatible", "chat", "fake-chat-2", true))
            .put("/api/v1/ai/profiles/t-chat").then().statusCode(200)
            .body("hasApiKey", equalTo(true)).body("model", equalTo("fake-chat-2"));
        fake.clear();
        admin().post("/api/v1/ai/profiles/t-chat/test").then().body("ok", equalTo(true));
        assertTrue(fake.seen().getLast().body().contains("fake-chat-2"), "the changed model is used without a restart");
        assertEquals("Bearer " + KEY, fake.seen().getLast().header("Authorization"));
        admin().contentType(ContentType.JSON).body(Map.of()).put("/api/v1/ai/features/search_nl")
            .then().statusCode(200).body("profile", nullValue());
        admin().delete("/api/v1/ai/profiles/t-gone").then().statusCode(204);
    }

    @Test
    @Order(7)
    void hostedModelsCanBeForbiddenServerWide() {
        Map<String, Object> hosted = profile("t-hosted", "openai-compatible", "chat", "fake-chat", false);
        admin().contentType(ContentType.JSON).body(hosted).post("/api/v1/ai/profiles").then().statusCode(201);
        admin().post("/api/v1/ai/profiles/t-hosted/test").then().body("ok", equalTo(true));
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "forbidden")).put("/api/v1/ai/settings")
            .then().statusCode(200).body("hostedAllowed", equalTo(false)).body("lockedByServer", equalTo(false));
        fake.clear();
        admin().post("/api/v1/ai/profiles/t-hosted/test").then().statusCode(200)
            .body("ok", equalTo(false))
            .body("message", containsString("Only local models may be used"))
            .body("message", not(containsString("mark")));
        assertTrue(fake.seen().isEmpty(), "a refused call never reaches the model");
        // Local models still work.
        admin().post("/api/v1/ai/profiles/t-chat/test").then().body("ok", equalTo(true));
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "sometimes")).put("/api/v1/ai/settings")
            .then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings")
            .then().statusCode(200).body("hostedAllowed", equalTo(true));
    }

    @Test
    @Order(8)
    void everyCallIsRecordedWithoutItsText() throws Exception {
        JsonPath u = admin().get("/api/v1/ai/usage?days=7").then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> totals = u.getList("totals");
        assertTrue(totals.stream().anyMatch(t -> "t-chat".equals(t.get("profile"))
            && ((Number) t.get("ok")).intValue() >= 3 && ((Number) t.get("tokensIn")).intValue() >= 33), totals::toString);
        assertTrue(totals.stream().anyMatch(t -> "t-hosted".equals(t.get("profile"))
            && ((Number) t.get("refused")).intValue() >= 1), totals::toString);
        List<Map<String, Object>> recent = u.getList("recent");
        assertTrue(recent.stream().anyMatch(r -> "refused".equals(r.get("outcome"))
            && String.valueOf(r.get("detail")).contains("not a local model")));
        assertTrue(recent.stream().allMatch(r -> String.valueOf(r.get("caller")).contains("token")
            || String.valueOf(r.get("caller")).contains("environment")), recent::toString);
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM kilnmark.ai_usage "
                 + "WHERE prompt_text IS NOT NULL OR answer_text IS NOT NULL")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "no prompt or answer text is kept by default");
        }
    }
}
