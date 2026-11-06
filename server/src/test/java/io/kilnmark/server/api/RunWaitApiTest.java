package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Db;
import io.kilnmark.server.Tokens;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/**
 * Waiting for a run to end and request keys on the run endpoints (ADR 0047): wait returns when the
 * run ends or at the limit, never past kilnmark.api.max-wait (10 seconds in tests); at most
 * kilnmark.api.max-waiting (2 in tests) wait at once and one more gets 503; the same key from the
 * same caller for the same pipeline or flow gives the same run, even when two arrive at once; the
 * same key for another is 422; another caller's key is its own; keys expire after 24 hours.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunWaitApiTest {

    static final String COPY = """
        kilnmark: pipeline/v1
        name: rw-copy
        nodes:
          - id: people
            type: read
            source: rw-src
            asset: lab.people

          - id: out
            type: write
            input: people
            source: rw-out
            table: lab.rw_copy
            mode: replace
        """;

    /** A flow that runs for {@code seconds} and succeeds. */
    static String waiting(String name, String seconds) {
        return """
            kilnmark: flow/v1
            name: %s
            nodes:
              - { id: pause, type: wait, for: %s }
            """.formatted(name, seconds);
    }

    /**
     * Threads for requests sent at once. New threads keep the test's class loader, which the common
     * pool's do not (RestAssured's JSON then fails to load).
     */
    static final java.util.concurrent.ExecutorService THREADS = java.util.concurrent.Executors.newFixedThreadPool(4);

    static String steward;
    static String other;
    static String viewer;
    /** The slow flow's run, still going while the limits are tried. */
    static long slow;

    @Inject
    Db db;

    @Inject
    RunRequestKeys keys;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static long seconds(long startNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        steward = newToken("steward", false);
        other = newToken("steward", false);
        viewer = newToken("viewer", false);
        String designer = newToken("designer", false);
        admin().contentType(ContentType.JSON).body(Map.of("name", "rw-src", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "50"), "password", "rw-in-token-41")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "rw-out", "kind", MemoryConnector.KIND,
            "settings", Map.of(), "password", "rw-out-token-17")).post("/api/v1/sources").then().statusCode(201);
        as(Tokens.ADMIN).body(Map.of("settings", Map.of("allow_writing", "true"))).put("/api/v1/sources/rw-out")
            .then().statusCode(200);
        long scan = as(designer).post("/api/v1/sources/rw-src/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!"succeeded".equals(as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"))) {
            assertTrue(System.nanoTime() < deadline, "the scan did not finish");
            Thread.sleep(100);
        }
        as(designer).body(Map.of("name", "rw-copy", "yaml", COPY)).post("/api/v1/pipelines").then().statusCode(201);
        for (String[] f : List.of(new String[] {"rw-quick", "2s"}, new String[] {"rw-slow", "10m"},
            new String[] {"rw-pair", "3s"}, new String[] {"rw-other", "1s"})) {
            as(designer).body(Map.of("yaml", waiting(f[0], f[1]))).post("/api/v1/flows").then().statusCode(201);
        }
    }

    @Test
    @Order(2)
    void aWaitThatIsNotWholeSecondsIsRefused() {
        as(steward).body(Map.of()).post("/api/v1/flows/rw-quick/runs?wait=ten").then().statusCode(400)
            .body("error", containsString("wait"));
        as(viewer).get("/api/v1/flow-runs/1?wait=-1").then().statusCode(400);
        as(viewer).get("/api/v1/pipeline-runs/1?wait=1.5").then().statusCode(400);
    }

    @Test
    @Order(3)
    void startingWithWaitAnswersWhenTheRunHasEnded() {
        long t = System.nanoTime();
        Response r = as(steward).body(Map.of()).post("/api/v1/pipelines/rw-copy/runs?wait=10");
        r.then().statusCode(200).body("status", equalTo("succeeded"))
            .header("Location", endsWith("/api/v1/pipeline-runs/" + r.jsonPath().getLong("id")))
            .header(RunWaits.REPLAYED_HEADER, nullValue());
        assertTrue(seconds(t) < 10, "answered when the run ended, not at the limit");
    }

    @Test
    @Order(4)
    void readingWithWaitAnswersWhenTheRunHasEnded() {
        long id = as(steward).body(Map.of()).post("/api/v1/flows/rw-quick/runs").then().statusCode(202).extract()
            .jsonPath().getLong("id");
        long t = System.nanoTime();
        as(viewer).get("/api/v1/flow-runs/" + id + "?wait=10").then().statusCode(200)
            .body("run.status", equalTo("succeeded"));
        assertTrue(seconds(t) < 10, "answered when the run ended, not at the limit");
        // the plain-text form, for scripts that cannot read JSON
        Tokens.as(viewer).accept("text/plain").get("/api/v1/flow-runs/" + id).then().statusCode(200)
            .contentType(containsString("text/plain"))
            .body(containsString("id: " + id + "\n"), containsString("kind: flow\n"), containsString("name: rw-quick\n"),
                containsString("status: succeeded\n"), containsString("ended: true\n"),
                containsString("page: /#/flow-runs/" + id + "\n"), containsString("message: \n"));
    }

    @Test
    @Order(5)
    void aWaitThatIsUpAnswersWithTheRunStillGoing() {
        long t = System.nanoTime();
        Response r = as(steward).body(Map.of()).post("/api/v1/flows/rw-slow/runs?wait=2");
        r.then().statusCode(202).header("Location", endsWith("/api/v1/flow-runs/" + r.jsonPath().getLong("id")));
        slow = r.jsonPath().getLong("id");
        assertTrue(List.of("queued", "running").contains(r.jsonPath().getString("status")));
        assertTrue(seconds(t) >= 1, "waited");
        t = System.nanoTime();
        as(viewer).get("/api/v1/flow-runs/" + slow + "?wait=1").then().statusCode(200)
            .body("run.status", org.hamcrest.Matchers.oneOf("queued", "running"));
        Tokens.as(steward).accept("text/plain").get("/api/v1/flow-runs/" + slow + "?wait=1").then().statusCode(200)
            .body(containsString("ended: false\n"));
        assertTrue(seconds(t) >= 1 && seconds(t) < 8, "each waited its second");
    }

    @Test
    @Order(6)
    void noWaitIsLongerThanTheCap() {
        long t = System.nanoTime();
        as(viewer).get("/api/v1/flow-runs/" + slow + "?wait=100000000000000000000").then().statusCode(200)
            .body("run.status", org.hamcrest.Matchers.oneOf("queued", "running"));
        long took = seconds(t);
        assertTrue(took >= 9 && took < 20, "cut to the cap of 10 seconds, took " + took);
    }

    @Test
    @Order(7)
    void oneWaitMoreThanTheLimitIsRefusedAndStartsNothing() throws Exception {
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() ->
            as(viewer).get("/api/v1/flow-runs/" + slow + "?wait=6").statusCode(), THREADS);
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() ->
            as(steward).get("/api/v1/flow-runs/" + slow + "?wait=6").statusCode(), THREADS);
        Thread.sleep(3_000); // both are waiting now
        as(viewer).get("/api/v1/flow-runs/" + slow + "?wait=3").then().statusCode(503)
            .header("Retry-After", "5").body("error", containsString("waiting"));
        int before = as(viewer).get("/api/v1/flows/rw-other/runs").then().extract().jsonPath().getList("$").size();
        as(steward).body(Map.of()).post("/api/v1/flows/rw-other/runs?wait=5").then().statusCode(503);
        assertEquals(before, as(viewer).get("/api/v1/flows/rw-other/runs").then().extract().jsonPath()
            .getList("$").size(), "a refused wait starts no run");
        // without wait, or on a run that has ended, nothing waits and nothing is refused
        as(viewer).get("/api/v1/flow-runs/" + slow).then().statusCode(200);
        assertEquals(200, a.get(20, TimeUnit.SECONDS));
        assertEquals(200, b.get(20, TimeUnit.SECONDS));
        as(viewer).get("/api/v1/flow-runs/" + slow + "?wait=3").then().statusCode(200);
    }

    @Test
    @Order(8)
    void theSameKeyGivesTheSameRunAndAnotherPipelineIsRefused() {
        Response first = as(steward).header("Idempotency-Key", "nightly 2026-11-06").body(Map.of())
            .post("/api/v1/pipelines/rw-copy/runs?wait=10");
        first.then().statusCode(200).body("status", equalTo("succeeded"));
        long id = first.jsonPath().getLong("id");
        as(steward).header("Idempotency-Key", "nightly 2026-11-06").body(Map.of())
            .post("/api/v1/pipelines/rw-copy/runs").then().statusCode(200)
            .header(RunWaits.REPLAYED_HEADER, "true")
            .header("Location", endsWith("/api/v1/pipeline-runs/" + id))
            .body("id", equalTo((int) id)).body("status", equalTo("succeeded"));
        as(steward).header("Idempotency-Key", "nightly 2026-11-06").body(Map.of())
            .post("/api/v1/flows/rw-other/runs").then().statusCode(422)
            .body("error", containsString("new key"));
        // another caller's key is its own: the same key starts a run of its own
        Response theirs = as(other).header("Idempotency-Key", "nightly 2026-11-06").body(Map.of())
            .post("/api/v1/pipelines/rw-copy/runs?wait=10");
        theirs.then().statusCode(200).header(RunWaits.REPLAYED_HEADER, nullValue());
        assertNotEquals(id, theirs.jsonPath().getLong("id"));
    }

    @Test
    @Order(9)
    void aKeyThatCouldNotStartARunIsNotKept() {
        as(steward).header("Idempotency-Key", "no-such-thing").body(Map.of())
            .post("/api/v1/pipelines/rw-missing/runs").then().statusCode(404);
        assertEquals(0, count("no-such-thing"));
        as(steward).header("Idempotency-Key", "x".repeat(201)).body(Map.of())
            .post("/api/v1/flows/rw-other/runs").then().statusCode(400).body("error", containsString("Idempotency-Key"));
        assertEquals(0, count("x".repeat(201)));
    }

    @Test
    @Order(10)
    void twoRequestsWithOneKeyAtOnceStartOneRun() throws Exception {
        int before = as(viewer).get("/api/v1/flows/rw-pair/runs").then().extract().jsonPath().getList("$").size();
        List<CompletableFuture<Response>> both = List.of(
            CompletableFuture.supplyAsync(() -> as(steward).header("Idempotency-Key", "pair-1").body(Map.of())
                .post("/api/v1/flows/rw-pair/runs"), THREADS),
            CompletableFuture.supplyAsync(() -> as(steward).header("Idempotency-Key", "pair-1").body(Map.of())
                .post("/api/v1/flows/rw-pair/runs"), THREADS));
        Response one = both.get(0).get(30, TimeUnit.SECONDS);
        Response two = both.get(1).get(30, TimeUnit.SECONDS);
        assertTrue(List.of(200, 202).contains(one.statusCode()), one.asString());
        assertTrue(List.of(200, 202).contains(two.statusCode()), two.asString());
        assertEquals(one.jsonPath().getLong("id"), two.jsonPath().getLong("id"), "one run for both");
        assertEquals(1, (one.header(RunWaits.REPLAYED_HEADER) == null ? 0 : 1)
            + (two.header(RunWaits.REPLAYED_HEADER) == null ? 0 : 1), "one started it, the other found it");
        assertEquals(before + 1, as(viewer).get("/api/v1/flows/rw-pair/runs").then().extract().jsonPath()
            .getList("$").size());
        as(viewer).get("/api/v1/flow-runs/" + one.jsonPath().getLong("id") + "?wait=10").then().statusCode(200)
            .body("run.status", equalTo("succeeded"));
    }

    @Test
    @Order(11)
    void keysOlderThanADayAreUnusedAndCleanedUp() {
        long id = as(steward).header("Idempotency-Key", "aging").body(Map.of())
            .post("/api/v1/flows/rw-other/runs?wait=10").then().statusCode(200).extract().jsonPath().getLong("id");
        age("aging");
        // older than 24 hours: the key starts a new run, even before the clean-up
        long again = as(steward).header("Idempotency-Key", "aging").body(Map.of())
            .post("/api/v1/flows/rw-other/runs?wait=10").then().statusCode(200)
            .header(RunWaits.REPLAYED_HEADER, nullValue()).extract().jsonPath().getLong("id");
        assertNotEquals(id, again);
        assertEquals(1, count("aging"));
        age("aging");
        assertTrue(keys.purge() >= 1);
        assertEquals(0, count("aging"));
    }

    @Test
    @Order(99)
    void stopTheSlowRun() {
        as(steward).post("/api/v1/flow-runs/" + slow + "/cancel").then().statusCode(200);
    }

    @AfterAll
    static void stopTheThreads() {
        THREADS.shutdownNow();
    }

    /** Makes every key {@code key} 25 hours old. */
    private void age(String key) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run_request_key "
                + "SET created_at = now() - interval '25 hours' WHERE request_key = ?")) {
                ps.setString(1, key);
                return ps.executeUpdate();
            }
        });
    }

    private int count(String key) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM kilnmark.run_request_key WHERE request_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }
}
