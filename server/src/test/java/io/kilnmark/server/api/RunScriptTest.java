package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.newToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * containers/launch/kilnmark-run.sh against this test server: each outcome gives its exit code
 * (0 succeeded, 1 failed, 2 could not start, 3 timed out, 4 cancelled, 5 bad usage), and a retry
 * with the same request key finds the run the first started. Needs sh and curl: CI runs it on
 * Linux; on Windows, set KILNMARK_TEST_SH to Git Bash's sh.exe to run it, else it is skipped.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunScriptTest {

    static final Path SCRIPT = Path.of("../containers/launch/kilnmark-run.sh").toAbsolutePath().normalize();

    static String sh;
    static Path tokenFile;
    static String steward;

    @TestHTTPResource("/")
    URL base;

    /** What a run of the script printed, and how it exited. */
    record Ran(int code, String out) {
    }

    @BeforeAll
    static void findShell() throws IOException {
        String given = System.getenv("KILNMARK_TEST_SH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        sh = given != null && !given.isBlank() ? given : windows ? null : "sh";
        Assumptions.assumeTrue(sh != null, "no sh here: set KILNMARK_TEST_SH to run these on Windows");
        Assumptions.assumeTrue(runs(sh, "-c", "command -v curl"), "curl is not installed here");
    }

    private static boolean runs(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Process start(Map<String, String> env, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(sh, SCRIPT.toString().replace('\\', '/')));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("KILNMARK_URL", base.toString());
        pb.environment().put("KILNMARK_TOKEN_FILE", tokenFile.toString().replace('\\', '/'));
        pb.environment().remove("KILNMARK_CA_FILE");
        pb.environment().putAll(env);
        return pb.start();
    }

    private static Ran finish(Process p) throws IOException, InterruptedException {
        byte[] out = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the script ended");
        return new Ran(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private Ran run(String... args) throws IOException, InterruptedException {
        return finish(start(Map.of(), args));
    }

    private static void flow(String designer, String yaml) {
        Tokens.as(designer).contentType(ContentType.JSON).body(Map.of("yaml", yaml)).post("/api/v1/flows").then()
            .statusCode(201);
    }

    @Test
    @Order(1)
    void setUp() throws IOException {
        steward = newToken("steward", false);
        String designer = newToken("designer", false);
        tokenFile = Files.createTempFile("kilnmark-run-token", ".txt");
        Files.writeString(tokenFile, steward + "\n");
        flow(designer, """
            kilnmark: flow/v1
            name: rs-ok
            parameters:
              label: { type: text, default: none }
              share: { type: number, default: 0.02 }
            nodes:
              - { id: pause, type: wait, for: 1s }
            """);
        flow(designer, """
            kilnmark: flow/v1
            name: rs-fail
            nodes:
              - { id: halt, type: stop, message: Stopped on purpose }
            """);
        for (String name : List.of("rs-long", "rs-cancel")) {
            flow(designer, """
                kilnmark: flow/v1
                name: %s
                nodes:
                  - { id: hold, type: wait, for: 10m }
                """.formatted(name));
        }
    }

    @Test
    @Order(2)
    void badUsageAndAnUnsafeAddressAre5() throws Exception {
        assertEquals(5, run().code());
        assertEquals(5, run("job", "rs-ok").code());
        assertEquals(5, run("flow", "rs-ok", "--timeout", "soon").code());
        Ran plain = finish(start(Map.of("KILNMARK_URL", "http://kilnmark.example.com"), "flow", "rs-ok"));
        assertEquals(5, plain.code(), plain.out());
        assertTrue(plain.out().contains("https://"), plain.out());
    }

    @Test
    @Order(3)
    void aRunThatSucceedsIs0AndTheSameKeyFindsIt() throws Exception {
        Ran ok = run("flow", "rs-ok", "--param", "label=north", "--param", "share=0.5", "--key", "rs key 1");
        assertEquals(0, ok.code(), ok.out());
        assertTrue(ok.out().contains("Request key: rs key 1"), ok.out());
        assertTrue(ok.out().contains("Status: succeeded"), ok.out());
        assertTrue(ok.out().contains("/#/flow-runs/"), ok.out());
        assertTrue(!ok.out().contains(steward), "the token is never printed");
        Ran again = run("flow", "rs-ok", "--key", "rs key 1");
        assertEquals(0, again.code(), again.out());
        assertTrue(again.out().contains("started earlier with this request key"), again.out());
        // made up and printed when left out
        Ran made = run("flow", "rs-ok");
        assertEquals(0, made.code(), made.out());
        assertTrue(made.out().contains("Request key: kilnmark-run-"), made.out());
    }

    @Test
    @Order(4)
    void aRunThatFailsIs1() throws Exception {
        Ran failed = run("flow", "rs-fail");
        assertEquals(1, failed.code(), failed.out());
        assertTrue(failed.out().contains("Status: failed"), failed.out());
    }

    @Test
    @Order(5)
    void aRunThatCannotStartIs2() throws Exception {
        Ran missing = run("flow", "rs-missing");
        assertEquals(2, missing.code(), missing.out());
        Ran wrong = run("flow", "rs-ok", "--param", "nothing=1");
        assertEquals(2, wrong.code(), wrong.out());
        assertTrue(wrong.out().contains("nothing"), "says why: " + wrong.out());
    }

    @Test
    @Order(6)
    void aTimeoutIs3AndCanCancel() throws Exception {
        Ran late = run("flow", "rs-long", "--timeout", "3", "--cancel-on-timeout");
        assertEquals(3, late.code(), late.out());
        assertTrue(late.out().contains("Cancel: asked"), late.out());
        long id = Tokens.as(steward).get("/api/v1/flows/rs-long/runs").then().statusCode(200).extract().jsonPath()
            .getLong("[0].id");
        Tokens.as(steward).get("/api/v1/flow-runs/" + id + "?wait=10").then().statusCode(200)
            .body("run.status", org.hamcrest.Matchers.equalTo("cancelled"));
    }

    @Test
    @Order(7)
    void aCancelledRunIs4() throws Exception {
        Process p = start(Map.of(), "flow", "rs-cancel");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        List<Object> runs = List.of();
        while (runs.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(200);
            runs = Tokens.as(steward).get("/api/v1/flows/rs-cancel/runs").then().extract().jsonPath().getList("$");
        }
        long id = Tokens.as(steward).get("/api/v1/flows/rs-cancel/runs").then().extract().jsonPath().getLong("[0].id");
        Tokens.as(steward).post("/api/v1/flow-runs/" + id + "/cancel").then().statusCode(200);
        Ran cancelled = finish(p);
        assertEquals(4, cancelled.code(), cancelled.out());
        assertTrue(cancelled.out().contains("Status: cancelled"), cancelled.out());
    }
}
