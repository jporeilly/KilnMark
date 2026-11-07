package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.sources.HostPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * A step that fails because a service the pack calls refused it says so in the pack's own words,
 * never the service's, which can name the host (ADR 0051): here this server lets sources reach one
 * host only, which is not Kilnmark's own database server.
 */
@QuarkusTest
@TestProfile(StarterPackHostsTest.OneHost.class)
class StarterPackHostsTest {

    public static class OneHost implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.samples-db.password", StarterPackApiTest.PASSWORD,
                "kilnmark.sources.allowed-hosts", "db.example.invalid");
        }
    }

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @Test
    void aRefusedSourceSaysWhatToDoAndNeverNamesTheHost() throws Exception {
        StarterPackApiTest.makeSamplesDatabase(db);
        String host = url.replaceFirst("^jdbc:postgresql:" + "/" + "/([^:/?]+).*$", "$1");
        JsonPath r = admin().post("/api/v1/starter-pack").then().statusCode(200).extract().jsonPath();
        assertEquals("failed", r.getString("steps.find { it.step == 'database source' }.outcome"), r.prettify());
        String detail = r.getString("steps.find { it.step == 'database source' }.detail");
        assertTrue(detail.contains(HostPolicy.SETTING), "says what to do: " + detail);
        assertFalse(r.prettify().contains(host), "the host is never shown: " + r.prettify());
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
