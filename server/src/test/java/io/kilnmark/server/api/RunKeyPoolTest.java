package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Many keyed starts at once, as a scheduler's batch window sends them, with a database pool smaller
 * than their number (ADR 0047). Checking a run reads the catalogue on connections of its own, so it
 * is done before the transaction that holds the key and queues the run: a start never holds one
 * connection while it waits for another, and every start gets through.
 */
@QuarkusTest
@TestProfile(RunKeyPoolTest.SmallPool.class)
class RunKeyPoolTest {

    /** A pool of six connections, and a short wait for one, so a start that holds two fails fast. */
    public static class SmallPool implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.jdbc.max-size", "6",
                "quarkus.datasource.jdbc.acquisition-timeout", "3S");
        }
    }

    static final int STARTS = 16;

    static String copy(int i) {
        return """
            kilnmark: pipeline/v1
            name: pool-copy-%d
            nodes:
              - id: people
                type: read
                source: pool-src
                asset: lab.people

              - id: out
                type: write
                input: people
                source: pool-out
                table: lab.pool_copy_%d
                mode: replace
            """.formatted(i, i);
    }

    @Test
    void manyKeyedStartsAtOnceAllGetThrough() throws Exception {
        String steward = newToken("steward", false);
        String designer = newToken("designer", false);
        admin().contentType(ContentType.JSON).body(Map.of("name", "pool-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "10"), "password", "pool-in-token-77")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "pool-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", "pool-out-token-78")).post("/api/v1/sources").then().statusCode(201);
        Tokens.as(Tokens.ADMIN).contentType(ContentType.JSON).body(Map.of("settings", Map.of("allow_writing", "true")))
            .put("/api/v1/sources/pool-out").then().statusCode(200);
        long scan = Tokens.as(designer).post("/api/v1/sources/pool-src/scans").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!"succeeded".equals(Tokens.as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"))) {
            assertTrue(System.nanoTime() < deadline, "the scan did not finish");
            Thread.sleep(100);
        }
        for (int i = 0; i < STARTS; i++) {
            Tokens.as(designer).contentType(ContentType.JSON).body(Map.of("name", "pool-copy-" + i, "yaml", copy(i)))
                .post("/api/v1/pipelines").then().statusCode(201);
        }
        ExecutorService threads = Executors.newFixedThreadPool(STARTS);
        try {
            List<Future<Response>> starts = new ArrayList<>();
            for (int i = 0; i < STARTS; i++) {
                int n = i;
                starts.add(threads.submit(() -> Tokens.as(steward).contentType(ContentType.JSON)
                    .header("Idempotency-Key", "batch-0200-" + n).body(Map.of())
                    .post("/api/v1/pipelines/pool-copy-" + n + "/runs")));
            }
            Set<Long> ids = new HashSet<>();
            for (Future<Response> f : starts) {
                Response r = f.get(60, TimeUnit.SECONDS);
                assertTrue(List.of(200, 202).contains(r.statusCode()), r.statusCode() + " " + r.asString());
                ids.add(r.jsonPath().getLong("id"));
            }
            assertEquals(STARTS, ids.size(), "each start queued its own run");
        } finally {
            threads.shutdownNow();
        }
    }
}
