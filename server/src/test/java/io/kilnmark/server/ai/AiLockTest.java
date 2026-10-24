package io.kilnmark.server.ai;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * The operator lock, set in configuration: hosted models forbidden, and only 127.0.0.0/8 local.
 * Ticking "local" cannot get round it, on save or at call time.
 */
@QuarkusTest
@TestProfile(AiLockTest.Locked.class)
class AiLockTest {

    public static class Locked implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // kb-flaky.invalid: a name the operator lists as local, which does not resolve.
            return Map.of("kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts",
                "127.0.0.0/8,kb-flaky.invalid");
        }
    }

    @Inject
    AgroalDataSource db;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private long count(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String scanDetail(String source) throws InterruptedException {
        long id = admin().post("/api/v1/sources/" + source + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        for (int i = 0; i < 160; i++) {
            io.restassured.path.json.JsonPath run = admin().get("/api/v1/runs/" + id).then().extract().jsonPath();
            String d = run.getString("detail");
            if (!run.getString("status").matches("queued|running") && d != null && d.contains("Search by meaning")) {
                return d;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("scan " + id + " did not finish");
    }

    private Map<String, Object> profile(String name, String url, boolean local) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("provider", "openai-compatible");
        m.put("baseUrl", url);
        m.put("model", "fake-chat");
        m.put("purpose", "chat");
        m.put("local", local);
        m.put("timeoutSeconds", 5);
        return m;
    }

    @Test
    void localMeansOnALocalNetworkWhileTheLockIsOn() throws Exception {
        FakeModelServer fake = FakeModelServer.get();
        fake.clear();
        admin().get("/api/v1/ai/settings").then().statusCode(200)
            .body("lockedByServer", equalTo(true)).body("hostedAllowed", equalTo(false));
        // A hosted service ticked local is refused when saved.
        admin().contentType(ContentType.JSON).body(profile("l-cloud", "https://api.openai.com/v1", true))
            .post("/api/v1/ai/profiles").then().statusCode(400)
            .body("error", containsString("not on one of its local networks"));
        // So is a private address outside the operator's narrowed list.
        admin().contentType(ContentType.JSON).body(profile("l-private", "http://10.1.2.3/v1", true))
            .post("/api/v1/ai/profiles").then().statusCode(400);
        // A server on this machine is local, and works.
        admin().contentType(ContentType.JSON).body(profile("l-here", fake.baseUrl(), true))
            .post("/api/v1/ai/profiles").then().statusCode(201);
        admin().post("/api/v1/ai/profiles/l-here/test").then().statusCode(200).body("ok", equalTo(true));
        // Not ticked: not local, refused at call time.
        admin().contentType(ContentType.JSON).body(profile("l-unticked", fake.baseUrl(), false))
            .post("/api/v1/ai/profiles").then().statusCode(201);
        admin().post("/api/v1/ai/profiles/l-unticked/test").then().statusCode(200)
            .body("ok", equalTo(false)).body("message", containsString("Only local models may be used"));

        // A profile stored as local with an outside address (from before the lock) is refused when called.
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO kilnmark.model_profile (name, provider, base_url, model, purpose, local, created_by) "
                + "VALUES ('l-stored', 'openai-compatible', 'http://203.0.113.9:8080/v1', 'x', 'chat', true, 'test')");
        }
        fake.clear();
        admin().post("/api/v1/ai/profiles/l-stored/test").then().statusCode(200)
            .body("ok", equalTo(false)).body("message", containsString("Only local models may be used"));
        // The view says whether the server accepts a profile as local, not only what was ticked.
        admin().get("/api/v1/ai/profiles/l-stored").then().body("local", equalTo(true))
            .body("effectiveLocal", equalTo(false));
        admin().get("/api/v1/ai/profiles/l-here").then().body("effectiveLocal", equalTo(true));
        // Names count as local only when listed: never by what they resolve to.
        admin().contentType(ContentType.JSON).body(profile("l-name", "http://localhost.localdomain:8080/v1", true))
            .post("/api/v1/ai/profiles").then().statusCode(400);
        // An admin cannot lift the lock.
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings")
            .then().statusCode(200).body("hostedAllowed", equalTo(false));
    }

    @Test
    void aNameThatDoesNotResolveIsAFailedCallAndKeepsEmbeddings() throws Exception {
        FakeModelServer fake = FakeModelServer.get();
        fake.clear();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS lkshop");
            s.execute("CREATE TABLE IF NOT EXISTS lkshop.clients (client_id integer, email text)");
        }
        admin().contentType(ContentType.JSON).body(Map.of("name", "lk-src", "kind", "postgresql", "password", password,
            "settings", Map.of("url", url, "username", user, "schemas", "lkshop"))).post("/api/v1/sources")
            .then().statusCode(201);
        Map<String, Object> embed = profile("lk-embed", fake.baseUrl(), true);
        embed.put("purpose", "embedding");
        embed.put("model", "fake-embed-64");
        embed.put("dimensions", 64);
        admin().contentType(ContentType.JSON).body(embed).post("/api/v1/ai/profiles").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("profile", "lk-embed")).put("/api/v1/ai/features/embed")
            .then().statusCode(200);
        String first = scanDetail("lk-src");
        String embedded = "SELECT count(*) FROM kilnmark.asset_embedding e JOIN kilnmark.asset a ON a.urn = e.asset_urn "
            + "JOIN kilnmark.data_source s ON s.id = a.source_id WHERE s.name = 'lk-src'";
        org.junit.jupiter.api.Assertions.assertEquals(1, count(embedded), first);

        // The listed name stops resolving (here: it never did). That is a failed call, not "not local":
        // nothing is refused, and the source's embeddings stay.
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE kilnmark.model_profile SET base_url = 'http://kb-flaky.invalid:8080/v1', "
                + "version = version + 1 WHERE name = 'lk-embed'");
        }
        String detail = scanDetail("lk-src");
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains("unavailable right now"), detail);
        org.junit.jupiter.api.Assertions.assertEquals(1, count(embedded), "embeddings kept: " + detail);
        org.junit.jupiter.api.Assertions.assertEquals(0, count("SELECT count(*) FROM kilnmark.ai_usage "
            + "WHERE profile_name = 'lk-embed' AND outcome = 'refused'"));
        org.junit.jupiter.api.Assertions.assertTrue(count("SELECT count(*) FROM kilnmark.ai_usage "
            + "WHERE profile_name = 'lk-embed' AND outcome = 'failed' AND detail LIKE 'Cannot reach%'") >= 1);
        admin().contentType(ContentType.JSON).body(Map.of()).put("/api/v1/ai/features/embed").then().statusCode(200);
    }
}
