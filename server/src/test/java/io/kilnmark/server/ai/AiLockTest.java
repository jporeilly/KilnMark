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
            return Map.of("kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts", "127.0.0.0/8");
        }
    }

    @Inject
    AgroalDataSource db;

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
        // An admin cannot lift the lock.
        admin().contentType(ContentType.JSON).body(Map.of("hostedModels", "allowed")).put("/api/v1/ai/settings")
            .then().statusCode(200).body("hostedAllowed", equalTo(false));
    }
}
