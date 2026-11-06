package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * On an install marked production (KILNMARK_PRODUCTION=true) the Starter Pack is refused, and
 * nothing of it is made; only an admin calling the API with force=true can add it there, and the
 * audit event says it was forced (ADR 0051).
 */
@QuarkusTest
@TestProfile(StarterPackProductionTest.Production.class)
class StarterPackProductionTest {

    public static class Production implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.production", "true", "kilnmark.samples-db.password", StarterPackApiTest.PASSWORD);
        }
    }

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @Test
    void refusedOnProductionUnlessForcedThroughTheApi() throws Exception {
        StarterPackApiTest.makeSamplesDatabase(db);
        admin().get("/api/v1/starter-pack/details").then().statusCode(200).body("production", equalTo(true));
        admin().post("/api/v1/starter-pack").then().statusCode(409)
            .body("error", containsString("marked as production"))
            .body("error", containsString("Nothing was changed"));
        admin().get("/api/v1/starter-pack").then().body("on", equalTo(false));
        admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(404);
        admin().get("/api/v1/sources/" + StarterPack.FILES_SOURCE).then().statusCode(404);

        int added = StarterPackApiTest.events(AuditLog.STARTER_PACK_ADDED);
        JsonPath r = admin().post("/api/v1/starter-pack?force=true").then().statusCode(200).extract().jsonPath();
        assertEquals("done", r.getString("steps.find { it.step == 'database source' }.outcome"), r.prettify());
        StarterPackApiTest.eventually(AuditLog.STARTER_PACK_ADDED, added + 1);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_ADDED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(ev.getBoolean("events[0].detail.forced"), ev.prettify());

        StarterPackApiTest.waitForRun(r.getLong("tablesRun.id"));
        // Turning it off is never refused: taking example data out of production is what should happen.
        JsonPath off = admin().contentType(ContentType.JSON).body(Map.of("confirm", "DELETE"))
            .delete("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), off.getList("steps.findAll { it.outcome == 'failed' }"), off.prettify());
    }

    @AfterAll
    static void dropTheDatabase() {
        try (var c = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class).get().getConnection();
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS kilnmark_samples WITH (FORCE)");
        } catch (Exception e) {
            // gone with the test's own database
        }
    }
}
